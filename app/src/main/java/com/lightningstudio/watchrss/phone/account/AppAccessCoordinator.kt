package com.lightningstudio.watchrss.phone.account

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import com.lightningstudio.watchrss.phone.connection.bluetooth.PhoneLoginRequired

class AppAccessCoordinator(
    private val context: Context,
    private val accountRepository: PhoneAccountRepository,
    private val identity: LicenseDeviceIdentity,
    private val scope: CoroutineScope,
    private val store: AppAccessStore = AppAccessStore(context, AccountEnvironment.active(context).storageSuffix),
    environmentOverride: AccountEnvironment? = null,
    private val onAuthorized: () -> Unit = {}
) {
    private val operationMutex = Mutex()
    private var lastReconcileFailure: Throwable? = null
    private val authorizationNotifier = AuthorizationReadyNotifier(onAuthorized)
    private val environment = environmentOverride ?: AccountEnvironment.active(context)
    private val _state = MutableStateFlow<AppAccessState>(AppAccessState.Loading)
    val state: StateFlow<AppAccessState> = _state.asStateFlow()

    val isAuthorized: Boolean get() = _state.value is AppAccessState.Authorized
    val deviceAccessToken: String? get() = store.load()?.deviceAccessToken

    init {
        scope.launch(Dispatchers.IO) {
            state.collectLatest { accessState ->
                val expiresAt = (accessState as? AppAccessState.Authorized)
                    ?.summary
                    ?.takeIf { it.accessMode == "trial" }
                    ?.trialExpiresAtMillis
                    ?: return@collectLatest
                val now = store.trustedNowMillis() ?: System.currentTimeMillis()
                delay((expiresAt - now).coerceAtLeast(0L))
                reconcile()
            }
        }
    }

    fun initialize() { scope.launch(Dispatchers.IO) { reconcile() } }

    suspend fun reconcile() {
        operationMutex.withLock { reconcileLocked() }
        authorizationNotifier.afterReconcile(isAuthorized)
    }

    /** Uses the existing lease/possession refresh; account tokens have no refresh API here. */
    internal suspend fun buildWatchAccountSyncRequest(watch: String, install: String?, name: String?): JSONObject {
        if (!accountRepository.hasUsableSession) throw PhoneLoginRequired()
        val owner = accountRepository.session.value!!.userId
        val readiness = operationMutex.withLock {
            try { reconcileLocked() } catch (failure: Throwable) { return@withLock accountSyncFailureResult(failure) }
            if (accountRepository.session.value?.userId != owner) return@withLock WatchAccountRefreshResult.DeviceVerificationRequired()
            if (!accountRepository.hasUsableSession) return@withLock WatchAccountRefreshResult.AccountUnrecoverable()
            lastReconcileFailure?.let { return@withLock accountSyncFailureResult(it) }
            if (deviceAccessToken.isNullOrBlank()) WatchAccountRefreshResult.DeviceVerificationRequired() else WatchAccountRefreshResult.Ready
        }
        readiness.requireReady()
        authorizationNotifier.afterReconcile(isAuthorized)
        return withWatchAccountDeviceRefresh(
            deviceToken = { deviceAccessToken },
            refresh = { rejected -> refreshWatchDeviceSession(rejected, owner) },
            build = { accountRepository.buildAccountSyncRequest(watch, install, name) }
        )
    }

    internal suspend fun refreshWatchDeviceSession(rejectedToken: String, expectedUserId: String): WatchAccountRefreshResult = operationMutex.withLock {
        if (accountRepository.session.value?.userId != expectedUserId) return@withLock WatchAccountRefreshResult.DeviceVerificationRequired()
        if (!accountRepository.hasUsableSession) return@withLock WatchAccountRefreshResult.AccountUnrecoverable()
        val current = store.load()?.deviceAccessToken
        if (!current.isNullOrBlank() && current != rejectedToken) return@withLock WatchAccountRefreshResult.Ready
        try { reconcileLocked() } catch (failure: Throwable) { return@withLock accountSyncFailureResult(failure) }
        lastReconcileFailure?.let { return@withLock accountSyncFailureResult(it) }
        if (accountRepository.session.value?.userId != expectedUserId) return@withLock WatchAccountRefreshResult.DeviceVerificationRequired()
        if (store.load()?.deviceAccessToken?.let { it.isNotBlank() && it != rejectedToken } == true) WatchAccountRefreshResult.Ready
        else WatchAccountRefreshResult.DeviceVerificationRequired()
    }

    /** Legacy cloud callers keep their Boolean API until the coordinated 115 merge. */
    suspend fun refreshRejectedDeviceSession(rejectedToken: String, expectedUserId: String): Boolean =
        refreshWatchDeviceSession(rejectedToken, expectedUserId) == WatchAccountRefreshResult.Ready

    /** A new account token alone does not complete same-account device reauthentication. */
    internal suspend fun completeSameAccountLogin(): WatchAccountRefreshResult {
        val result = operationMutex.withLock {
            val owner = accountRepository.session.value?.userId
            val readiness = try {
                reconcileLocked()
                when {
                    lastReconcileFailure != null -> accountSyncFailureResult(requireNotNull(lastReconcileFailure))
                    !accountRepository.hasUsableSession -> WatchAccountRefreshResult.AccountUnrecoverable()
                    accountRepository.session.value?.userId != owner -> WatchAccountRefreshResult.DeviceVerificationRequired()
                    isAuthorized && !deviceAccessToken.isNullOrBlank() -> WatchAccountRefreshResult.Ready
                    else -> WatchAccountRefreshResult.DeviceVerificationRequired()
                }
            } catch (failure: Throwable) { accountSyncFailureResult(failure) }
            if (readiness is WatchAccountRefreshResult.AccountUnrecoverable) {
                accountRepository.beginSameAccountReauthentication()
            }
            readiness
        }
        authorizationNotifier.afterReconcile(result == WatchAccountRefreshResult.Ready && isAuthorized)
        return result
    }

    private suspend fun reconcileLocked() {
        lastReconcileFailure = null
        val cached = store.load()
        val trustedNow = store.trustedNowMillis()
        val cachedValid = cached != null && trustedNow != null && LeaseVerifier.verify(
            compact = cached.lease,
            expectedDeviceId = identity.deviceId,
            publicKeyPem = environment.appAccessPublicKey,
            nowSeconds = trustedNow / 1000
        )
        if (cachedValid) store.recordTrustedTime(trustedNow!!)
        if (cached != null && !cachedValid) store.clear()
        val hasUsableSession = accountRepository.hasUsableSession
        initialAppAccessState(
            cachedSummary = cached?.access,
            cachedLeaseValid = cachedValid,
            hasUsableSession = hasUsableSession
        )?.let { _state.value = it }

        if (!hasUsableSession) {
            return
        }

        val sessionUserId = accountRepository.session.value?.userId.orEmpty()
        store.loadPendingOrder(sessionUserId)?.let { order ->
            if (order.status == "pending") {
                _state.value = AppAccessState.PaymentPending(order)
                refreshPaymentLocked(order.orderId)
                return
            }
        }

        if (!cachedValid) {
            loadServerStatusLocked()
            return
        }

        try {
            val refreshed = accountRepository.refreshAppAccess(cached!!).also(store::save)
            _state.value = AppAccessState.Authorized(refreshed.access, offline = false)
        } catch (error: Throwable) {
            handleValidLeaseRefreshFailure(cached!!, error)
        }
    }

    private suspend fun handleValidLeaseRefreshFailure(
        cached: AppAuthorization,
        error: Throwable
    ) {
        if (error is kotlinx.coroutines.CancellationException) throw error
        lastReconcileFailure = error
        if (error.findAccountHttpException()?.statusCode != 403) {
            _state.value = AppAccessState.Authorized(cached.access, offline = true)
            return
        }
        val serverSummary = runCatching { accountRepository.appAccessStatus() }.onFailure {
            if (it is kotlinx.coroutines.CancellationException) throw it
            lastReconcileFailure = it
        }.getOrNull()
        val decision = validLeaseRefreshDecision(cached.access, serverSummary)
        if (decision.clearCache) store.clear()
        _state.value = decision.state
    }

    suspend fun claim() {
        val wasAuthorized = isAuthorized
        operationMutex.withLock { claimLocked() }
        notifyIfNewlyAuthorized(wasAuthorized)
    }

    suspend fun startTrial() {
        val wasAuthorized = isAuthorized
        operationMutex.withLock {
            val activationProof = accountRepository.session.value?.activationProof.orEmpty()
            try {
                val authorization = accountRepository.startTrialAppAccess(store.claimIdempotencyKey())
                store.save(authorization)
                store.clearClaimIdempotencyKey()
                accountRepository.consumeActivationProof(activationProof)
                _state.value = AppAccessState.Authorized(authorization.access, false)
            } catch (error: Throwable) {
                val summary = runCatching { accountRepository.appAccessStatus() }
                    .getOrDefault(AppAccessSummary())
                _state.value = when (error.findAccountHttpException()?.statusCode) {
                    402, 409 -> AppAccessState.PurchaseRequired(summary)
                    else -> AppAccessState.ValidationError(trialAccessErrorMessage(error))
                }
                throw IllegalStateException(trialAccessErrorMessage(error), error)
            }
        }
        notifyIfNewlyAuthorized(wasAuthorized)
    }

    private suspend fun claimLocked() {
        val activationProof = accountRepository.session.value?.activationProof.orEmpty()
        try {
            val authorization = accountRepository.claimAppAccess(store.claimIdempotencyKey())
            store.save(authorization)
            store.clearClaimIdempotencyKey()
            accountRepository.consumeActivationProof(activationProof)
            _state.value = AppAccessState.Authorized(authorization.access, false)
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            lastReconcileFailure = error
            val cached = store.load()?.takeIf {
                val trustedNow = store.trustedNowMillis() ?: return@takeIf false
                LeaseVerifier.verify(
                    compact = it.lease,
                    expectedDeviceId = identity.deviceId,
                    publicKeyPem = environment.appAccessPublicKey,
                    nowSeconds = trustedNow / 1000
                )
            }
            if (cached != null) {
                _state.value = AppAccessState.Authorized(cached.access, offline = true)
                return
            }
            val status = try { accountRepository.appAccessStatus() } catch (statusFailure: Throwable) {
                if (statusFailure is kotlinx.coroutines.CancellationException) throw statusFailure
                // Keep the primary claim failure, unless the status query proves account authentication failed.
                if (statusFailure.findAccountHttpException()?.statusCode == 401) lastReconcileFailure = statusFailure
                AppAccessSummary()
            }
            _state.value = when ((error as? PhoneAccountHttpException)?.statusCode) {
                402 -> AppAccessState.PurchaseRequired(status)
                409 -> AppAccessState.ReauthenticationRequired(status)
                else -> AppAccessState.ValidationError(error.message ?: "设备授权失败")
            }
        }
    }

    suspend fun startPayment(agreementAccepted: Boolean): AppPaymentOrder = operationMutex.withLock {
        require(agreementAccepted) { "请先阅读并同意《腕上RSS手机版付费服务协议》" }
        val userId = accountRepository.session.value?.userId ?: error("请先登录账号")
        val order = accountRepository.createPaymentOrder(store.orderIdempotencyKey())
        store.clearOrderIdempotencyKey()
        store.savePendingOrder(order, userId)
        _state.value = AppAccessState.PaymentPending(order)
        return order
    }

    suspend fun refreshPayment(orderId: String) {
        val wasAuthorized = isAuthorized
        operationMutex.withLock { refreshPaymentLocked(orderId) }
        notifyIfNewlyAuthorized(wasAuthorized)
    }

    suspend fun cancelPendingPayment() = operationMutex.withLock {
        store.clearPendingOrder()
        loadServerStatusLocked()
    }

    private fun notifyIfNewlyAuthorized(wasAuthorized: Boolean) {
        authorizationNotifier.afterPotentialTransition(wasAuthorized, isAuthorized)
    }

    private suspend fun refreshPaymentLocked(orderId: String) {
        runCatching { accountRepository.paymentOrder(orderId) }.onSuccess { order ->
            when (order.status) {
                "paid" -> {
                    store.clearPendingOrder()
                    val cached = store.load()
                    if (cached != null) {
                        val refreshed = accountRepository.refreshAppAccess(cached).also(store::save)
                        _state.value = AppAccessState.Authorized(refreshed.access, offline = false)
                    } else {
                        claimLocked()
                    }
                }
                "pending" -> {
                    accountRepository.session.value?.userId?.let { userId ->
                        store.savePendingOrder(order, userId)
                    }
                    _state.value = AppAccessState.PaymentPending(order)
                }
                else -> {
                    store.clearPendingOrder()
                    loadServerStatusLocked()
                }
            }
        }.onFailure {
            if (it is kotlinx.coroutines.CancellationException) throw it
            lastReconcileFailure = it
        }
    }



    suspend fun logout(): Boolean = operationMutex.withLock {
        val current = store.load()
        val released = if (current != null) runCatching { accountRepository.releaseAppAccess(current) }.getOrDefault(false) else true
        if (!released && current != null) {
            store.queueRelease(identity.deviceId, current.releaseGrant)
            PendingReleaseWorker.schedule(context)
        }
        store.clear()
        store.clearPendingOrder()
        store.clearOrderIdempotencyKey()
        accountRepository.logout()
        _state.value = AppAccessState.LoggedOut
        return released
    }

    suspend fun deleteAccount(verificationToken: String): AccountDeletionResult =
        operationMutex.withLock {
            val result = accountRepository.deleteAccount(verificationToken)
            store.clear()
            store.clearPendingOrder()
            store.clearOrderIdempotencyKey()
            store.clearClaimIdempotencyKey()
            _state.value = AppAccessState.LoggedOut
            result
        }

    /** Pause authenticated operations while retaining the owner and its cloud custody. */
    suspend fun beginReauthentication() = operationMutex.withLock {
        accountRepository.beginSameAccountReauthentication()
    }

    private suspend fun loadServerStatusLocked() {
        runCatching { accountRepository.appAccessStatus() }.onSuccess { summary ->
            if (summary.accessMode != "none" && accountRepository.session.value?.activationProof?.isNotBlank() == true) {
                claimLocked()
                return@onSuccess
            }
            _state.value = when (summary.deviceStatus) {
                "authorized" -> AppAccessState.ReauthenticationRequired(summary)
                "revoked" -> AppAccessState.Revoked(summary)
                "unclaimed" -> AppAccessState.ReauthenticationRequired(summary)
                else -> AppAccessState.PurchaseRequired(summary)
            }
        }.onFailure {
            if (it is kotlinx.coroutines.CancellationException) throw it
            lastReconcileFailure = it
            _state.value = AppAccessState.ValidationError(it.message ?: "授权状态获取失败")
        }
    }
}

/**
 * Reconciliation is also a readiness boundary: an already-authorized cached lease may
 * gain a usable account session without changing [AppAccessState]. Consumers waiting on
 * both authorization and session readiness must therefore be notified after every
 * authorized reconciliation. Their own idempotency guards decide whether work is needed.
 */
internal class AuthorizationReadyNotifier(
    private val onAuthorized: () -> Unit
) {
    fun afterReconcile(isAuthorized: Boolean) {
        if (isAuthorized) runCatching(onAuthorized)
    }

    fun afterPotentialTransition(wasAuthorized: Boolean, isAuthorized: Boolean) {
        if (!wasAuthorized && isAuthorized) runCatching(onAuthorized)
    }
}

internal data class ValidLeaseRefreshDecision(
    val state: AppAccessState,
    val clearCache: Boolean
)

internal fun initialAppAccessState(
    cachedSummary: AppAccessSummary?,
    cachedLeaseValid: Boolean,
    hasUsableSession: Boolean
): AppAccessState? = when {
    cachedLeaseValid && cachedSummary != null ->
        AppAccessState.Authorized(cachedSummary, offline = true)
    !hasUsableSession -> AppAccessState.LoggedOut
    else -> null
}

internal fun validLeaseRefreshDecision(
    cachedSummary: AppAccessSummary,
    serverSummary: AppAccessSummary?
): ValidLeaseRefreshDecision = when (serverSummary?.deviceStatus) {
    "authorized" -> ValidLeaseRefreshDecision(
        AppAccessState.Authorized(serverSummary, offline = true),
        clearCache = false
    )
    "revoked" -> ValidLeaseRefreshDecision(
        AppAccessState.Revoked(serverSummary),
        clearCache = true
    )
    "unclaimed" -> ValidLeaseRefreshDecision(
        AppAccessState.ReauthenticationRequired(serverSummary),
        clearCache = true
    )
    "purchase_required" -> ValidLeaseRefreshDecision(
        AppAccessState.PurchaseRequired(serverSummary),
        clearCache = true
    )
    else -> ValidLeaseRefreshDecision(
        AppAccessState.Authorized(cachedSummary, offline = true),
        clearCache = false
    )
}

private object LeaseVerifier {
    fun verify(
        compact: String,
        expectedDeviceId: String,
        publicKeyPem: String,
        nowSeconds: Long = System.currentTimeMillis() / 1000
    ): Boolean = runCatching {
        val parts = compact.split('.')
        require(parts.size == 3)
        val header = JSONObject(String(Base64.decode(parts[0], FLAGS)))
        require(header.optString("alg") == "ES256")
        val payload = JSONObject(String(Base64.decode(parts[1], FLAGS)))
        require(payload.getString("licenseDeviceId") == expectedDeviceId)
        require(payload.getLong("expiresAt") > nowSeconds)
        require(publicKeyPem.isNotBlank())
        val der = Base64.decode(publicKeyPem.replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "").replace("\n", ""), Base64.DEFAULT)
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(key)
        verifier.update("${parts[0]}.${parts[1]}".toByteArray())
        verifier.verify(rawToDer(Base64.decode(parts[2], FLAGS)))
    }.getOrDefault(false)

    private fun rawToDer(raw: ByteArray): ByteArray {
        require(raw.size == 64)
        fun integer(bytes: ByteArray): ByteArray {
            val candidate = bytes.dropWhile { it == 0.toByte() }.toByteArray()
            val stripped = if (candidate.isEmpty()) byteArrayOf(0) else candidate
            return if (stripped[0].toInt() and 0x80 != 0) byteArrayOf(0) + stripped else stripped
        }
        val r = integer(raw.copyOfRange(0, 32)); val s = integer(raw.copyOfRange(32, 64))
        val body = byteArrayOf(2, r.size.toByte()) + r + byteArrayOf(2, s.size.toByte()) + s
        return byteArrayOf(0x30, body.size.toByte()) + body
    }

    private const val FLAGS = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
}
