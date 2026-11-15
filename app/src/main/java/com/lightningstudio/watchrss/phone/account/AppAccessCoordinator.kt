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

    internal suspend fun reconcile(): WatchAccountRefreshResult {
        val result = operationMutex.withLock { reconcileLocked() }
        authorizationNotifier.afterReconcile(isAuthorized)
        return result
    }

    /** Uses the existing lease/possession refresh; account tokens have no refresh API here. */
    internal suspend fun buildWatchAccountSyncRequest(watch: String, install: String?, name: String?): JSONObject {
        if (!accountRepository.hasUsableSession) throw PhoneLoginRequired()
        val owner = accountRepository.session.value!!.userId
        val readiness = operationMutex.withLock {
            val result = reconcileLocked()
            if (result != WatchAccountRefreshResult.Ready) return@withLock result
            if (accountRepository.session.value?.userId != owner) WatchAccountRefreshResult.DeviceVerificationRequired()
            else if (!accountRepository.hasUsableSession) WatchAccountRefreshResult.AccountUnrecoverable()
            else if (deviceAccessToken.isNullOrBlank()) WatchAccountRefreshResult.DeviceVerificationRequired()
            else result
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
        val result = reconcileLocked()
        if (result != WatchAccountRefreshResult.Ready) return@withLock result
        if (accountRepository.session.value?.userId != expectedUserId) return@withLock WatchAccountRefreshResult.DeviceVerificationRequired()
        if (store.load()?.deviceAccessToken?.let { it.isNotBlank() && it != rejectedToken } == true) WatchAccountRefreshResult.Ready
        else WatchAccountRefreshResult.DeviceVerificationRequired()
    }

    /** Legacy cloud callers keep their Boolean API until the coordinated 115 merge. */
    suspend fun refreshRejectedDeviceSession(rejectedToken: String, expectedUserId: String): Boolean =
        refreshWatchDeviceSession(rejectedToken, expectedUserId).let { result -> result.requireReady(); true }

    private suspend fun reconcileLocked(): WatchAccountRefreshResult = try {
        val cached = store.load()
        val trustedNow = store.trustedNowMillis()
        val cachedValid = cached != null && trustedNow != null && LeaseVerifier.verify(
            compact = cached.lease, expectedDeviceId = identity.deviceId,
            publicKeyPem = environment.appAccessPublicKey, nowSeconds = trustedNow / 1000
        )
        if (cachedValid) store.recordTrustedTime(trustedNow!!)
        if (cached != null && !cachedValid) store.clear()
        val hasUsableSession = accountRepository.hasUsableSession
        initialAppAccessState(cached?.access, cachedValid, hasUsableSession)?.let { _state.value = it }
        if (!hasUsableSession) WatchAccountRefreshResult.AccountUnrecoverable()
        else {
            val order = store.loadPendingOrder(accountRepository.session.value!!.userId)
            when {
                order?.status == "pending" -> {
                    _state.value = AppAccessState.PaymentPending(order)
                    refreshPaymentLocked(order.orderId)
                }
                !cachedValid -> loadServerStatusLocked()
                else -> refreshLeaseLocked(requireNotNull(cached))
            }
        }
    } catch (error: Throwable) { accountSyncFailureResult(error) }

    private suspend fun refreshLeaseLocked(cached: AppAuthorization): WatchAccountRefreshResult = try {
        val refreshed = accountRepository.refreshAppAccess(cached).also(store::save)
        _state.value = AppAccessState.Authorized(refreshed.access, offline = false)
        WatchAccountRefreshResult.Ready
    } catch (error: Throwable) { handleValidLeaseRefreshFailure(cached, error) }

    private suspend fun handleValidLeaseRefreshFailure(cached: AppAuthorization, error: Throwable): WatchAccountRefreshResult {
        val failure = accountSyncFailureResult(error) // Cancellation always escapes before fallback I/O.
        if (error.findAccountHttpException()?.statusCode != 403) {
            _state.value = AppAccessState.Authorized(cached.access, offline = true)
            return failure
        }
        val summary = try { accountRepository.appAccessStatus() } catch (statusError: Throwable) {
            _state.value = AppAccessState.Authorized(cached.access, offline = true)
            return accountSyncFailureResult(statusError)
        }
        val decision = validLeaseRefreshDecision(cached.access, summary)
        if (decision.clearCache) store.clear()
        _state.value = decision.state
        return failure
    }

    internal suspend fun claim(): WatchAccountRefreshResult {
        val wasAuthorized = isAuthorized
        val result = operationMutex.withLock { claimLocked() }
        notifyIfNewlyAuthorized(wasAuthorized)
        return result
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
                if (error is kotlinx.coroutines.CancellationException) throw error
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

    private suspend fun claimLocked(): WatchAccountRefreshResult {
        val activationProof = accountRepository.session.value?.activationProof.orEmpty()
        if (activationProof.isBlank()) {
            _state.value = AppAccessState.ReauthenticationRequired(store.load()?.access ?: AppAccessSummary())
            return WatchAccountRefreshResult.DeviceVerificationRequired()
        }
        return try {
            val authorization = accountRepository.claimAppAccess(store.claimIdempotencyKey())
            store.save(authorization)
            store.clearClaimIdempotencyKey()
            accountRepository.consumeActivationProof(activationProof)
            _state.value = AppAccessState.Authorized(authorization.access, false)
            WatchAccountRefreshResult.Ready
        } catch (error: Throwable) {
            val failure = accountSyncFailureResult(error)
            val cached = store.load()?.takeIf {
                val now = store.trustedNowMillis() ?: return@takeIf false
                LeaseVerifier.verify(it.lease, identity.deviceId, environment.appAccessPublicKey, now / 1000)
            }
            if (cached != null) _state.value = AppAccessState.Authorized(cached.access, offline = true)
            else {
                val status = try { accountRepository.appAccessStatus() } catch (statusError: Throwable) {
                    val statusFailure = accountSyncFailureResult(statusError)
                    _state.value = AppAccessState.ValidationError(statusError.message ?: "授权状态获取失败")
                    // Authentication is definitive; temporary failures must not become device rejection.
                    return when {
                        failure is WatchAccountRefreshResult.AccountUnrecoverable -> failure
                        statusFailure is WatchAccountRefreshResult.AccountUnrecoverable -> statusFailure
                        failure is WatchAccountRefreshResult.TemporaryFailure -> failure
                        statusFailure is WatchAccountRefreshResult.TemporaryFailure -> statusFailure
                        else -> failure
                    }
                }
                _state.value = when (error.findAccountHttpException()?.statusCode) {
                    402 -> AppAccessState.PurchaseRequired(status)
                    409 -> AppAccessState.ReauthenticationRequired(status)
                    else -> AppAccessState.ValidationError(error.message ?: "设备授权失败")
                }
            }
            return failure
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

    internal suspend fun refreshPayment(orderId: String): WatchAccountRefreshResult {
        val wasAuthorized = isAuthorized
        val result = operationMutex.withLock { refreshPaymentLocked(orderId) }
        notifyIfNewlyAuthorized(wasAuthorized)
        return result
    }

    internal suspend fun cancelPendingPayment() = operationMutex.withLock {
        store.clearPendingOrder()
        loadServerStatusLocked()
    }

    private fun notifyIfNewlyAuthorized(wasAuthorized: Boolean) {
        authorizationNotifier.afterPotentialTransition(wasAuthorized, isAuthorized)
    }

    private suspend fun refreshPaymentLocked(orderId: String): WatchAccountRefreshResult = try {
        val order = accountRepository.paymentOrder(orderId)
        when (order.status) {
            "paid" -> {
                store.clearPendingOrder()
                val cached = store.load()
                if (cached != null) refreshLeaseLocked(cached) else claimLocked()
            }
            "pending" -> {
                accountRepository.session.value?.userId?.let { store.savePendingOrder(order, it) }
                _state.value = AppAccessState.PaymentPending(order)
                WatchAccountRefreshResult.DeviceVerificationRequired()
            }
            else -> { store.clearPendingOrder(); loadServerStatusLocked() }
        }
    } catch (error: Throwable) { accountSyncFailureResult(error) }

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

    private suspend fun loadServerStatusLocked(): WatchAccountRefreshResult = try {
        val summary = accountRepository.appAccessStatus()
        if (summary.accessMode != "none" && accountRepository.session.value?.activationProof?.isNotBlank() == true) claimLocked()
        else {
            _state.value = when (summary.deviceStatus) {
                "authorized", "unclaimed" -> AppAccessState.ReauthenticationRequired(summary)
                "revoked" -> AppAccessState.Revoked(summary)
                else -> AppAccessState.PurchaseRequired(summary)
            }
            WatchAccountRefreshResult.DeviceVerificationRequired()
        }
    } catch (error: Throwable) {
        val failure = accountSyncFailureResult(error)
        _state.value = AppAccessState.ValidationError(error.message ?: "授权状态获取失败")
        failure
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
