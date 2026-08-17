package com.lightningstudio.watchrss.phone.account

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

class AppAccessCoordinator(
    private val context: Context,
    private val accountRepository: PhoneAccountRepository,
    private val identity: LicenseDeviceIdentity,
    private val scope: CoroutineScope,
    private val store: AppAccessStore = AppAccessStore(context, AccountEnvironment.active(context).storageSuffix),
    private val onAuthorized: () -> Unit = {}
) {
    private val operationMutex = Mutex()
    private val authorizationNotifier = AuthorizationReadyNotifier(onAuthorized)
    private val environment = AccountEnvironment.active(context)
    private val _state = MutableStateFlow<AppAccessState>(AppAccessState.Loading)
    val state: StateFlow<AppAccessState> = _state.asStateFlow()

    val isAuthorized: Boolean get() = _state.value is AppAccessState.Authorized
    val deviceAccessToken: String? get() = store.load()?.deviceAccessToken

    fun initialize() { scope.launch(Dispatchers.IO) { reconcile() } }

    suspend fun reconcile() {
        operationMutex.withLock { reconcileLocked() }
        authorizationNotifier.afterReconcile(isAuthorized)
    }

    private suspend fun reconcileLocked() {
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

        if (!cachedValid) {
            val sessionUserId = accountRepository.session.value?.userId.orEmpty()
            store.loadPendingOrder(sessionUserId)?.let { order ->
                if (order.status == "pending") {
                    _state.value = AppAccessState.PaymentPending(order)
                    refreshPaymentLocked(order.orderId)
                    return
                }
            }
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
        if (error.findAccountHttpException()?.statusCode != 403) {
            _state.value = AppAccessState.Authorized(cached.access, offline = true)
            return
        }
        val serverSummary = runCatching { accountRepository.appAccessStatus() }.getOrNull()
        val decision = validLeaseRefreshDecision(cached.access, serverSummary)
        if (decision.clearCache) store.clear()
        _state.value = decision.state
    }

    suspend fun claim() {
        val wasAuthorized = isAuthorized
        operationMutex.withLock { claimLocked() }
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
            val status = runCatching { accountRepository.appAccessStatus() }.getOrDefault(AppAccessSummary())
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

    private fun notifyIfNewlyAuthorized(wasAuthorized: Boolean) {
        authorizationNotifier.afterPotentialTransition(wasAuthorized, isAuthorized)
    }

    private suspend fun refreshPaymentLocked(orderId: String) {
        runCatching { accountRepository.paymentOrder(orderId) }.onSuccess { order ->
            when (order.status) {
                "paid" -> {
                    store.clearPendingOrder()
                    claimLocked()
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

    /**
     * A revoked device must produce a new SMS/Passkey activation proof before claiming again.
     * Keep local content and the revoked authorization record intact; only forget the account
     * session so AccountActivity presents the real login flow.
     */
    suspend fun beginReauthentication() = operationMutex.withLock {
        store.clearPendingOrder()
        store.clearOrderIdempotencyKey()
        accountRepository.logout()
        _state.value = AppAccessState.LoggedOut
    }

    private suspend fun loadServerStatusLocked() {
        runCatching { accountRepository.appAccessStatus() }.onSuccess { summary ->
            if (summary.purchaseCount > 0 && accountRepository.session.value?.activationProof?.isNotBlank() == true) {
                claimLocked()
                return@onSuccess
            }
            _state.value = when (summary.deviceStatus) {
                "authorized" -> AppAccessState.ReauthenticationRequired(summary)
                "revoked" -> AppAccessState.Revoked(summary)
                "unclaimed" -> AppAccessState.ReauthenticationRequired(summary)
                else -> AppAccessState.PurchaseRequired(summary)
            }
        }.onFailure { _state.value = AppAccessState.ValidationError(it.message ?: "授权状态获取失败") }
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
