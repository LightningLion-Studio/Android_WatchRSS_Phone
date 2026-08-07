package com.lightningstudio.watchrss.phone.account

import android.content.Context
import android.util.Base64
import com.lightningstudio.watchrss.phone.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

class AppAccessCoordinator(
    private val context: Context,
    private val accountRepository: PhoneAccountRepository,
    private val identity: LicenseDeviceIdentity,
    private val scope: CoroutineScope,
    private val store: AppAccessStore = AppAccessStore(context, AccountEnvironment.active(context).storageSuffix)
) {
    private val _state = MutableStateFlow<AppAccessState>(AppAccessState.Loading)
    val state: StateFlow<AppAccessState> = _state.asStateFlow()

    val isAuthorized: Boolean get() = _state.value is AppAccessState.Authorized
    val deviceAccessToken: String? get() = store.load()?.deviceAccessToken

    fun initialize() { scope.launch(Dispatchers.IO) { reconcile() } }

    suspend fun reconcile() {
        val session = accountRepository.session.value
        if (session == null || session.isExpired) {
            _state.value = AppAccessState.LoggedOut
            return
        }
        store.loadPendingOrder()?.let { order ->
            if (order.status == "pending") {
                _state.value = AppAccessState.PaymentPending(order)
                refreshPayment(order.orderId)
                return
            }
        }
        val cached = store.load()
        val cachedValid = cached != null && LeaseVerifier.verify(cached.lease, identity.deviceId)
        if (cachedValid) _state.value = AppAccessState.Authorized(cached!!.access, offline = true)
        runCatching {
            if (cachedValid) {
                accountRepository.refreshAppAccess(cached!!).also(store::save)
            } else null
        }.onSuccess { refreshed ->
            if (refreshed != null) _state.value = AppAccessState.Authorized(refreshed.access, offline = false)
            else loadServerStatus()
        }.onFailure { error ->
            val http = error as? PhoneAccountHttpException
            if (http?.statusCode == 403) {
                store.clear()
                scope.launch { loadServerStatus() }
            } else if (!cachedValid) {
                _state.value = AppAccessState.ValidationError(error.message ?: "授权校验失败")
            }
        }
    }

    suspend fun claim() {
        runCatching { accountRepository.claimAppAccess(store.claimIdempotencyKey()) }
            .onSuccess { authorization -> store.save(authorization); store.clearClaimIdempotencyKey(); _state.value = AppAccessState.Authorized(authorization.access, false) }
            .onFailure { error ->
                val status = runCatching { accountRepository.appAccessStatus() }.getOrDefault(AppAccessSummary())
                _state.value = when ((error as? PhoneAccountHttpException)?.statusCode) {
                    402 -> AppAccessState.PurchaseRequired(status)
                    409 -> AppAccessState.ReauthenticationRequired(status)
                    else -> AppAccessState.ValidationError(error.message ?: "设备授权失败")
                }
            }
    }

    suspend fun startPayment(): AppPaymentOrder {
        val order = accountRepository.createPaymentOrder(store.orderIdempotencyKey())
        store.clearOrderIdempotencyKey()
        store.savePendingOrder(order)
        _state.value = AppAccessState.PaymentPending(order)
        return order
    }

    suspend fun refreshPayment(orderId: String) {
        runCatching { accountRepository.paymentOrder(orderId) }.onSuccess { order ->
            when (order.status) {
                "paid" -> {
                    store.savePendingOrder(null)
                    claim()
                }
                "pending" -> {
                    store.savePendingOrder(order)
                    _state.value = AppAccessState.PaymentPending(order)
                }
                else -> {
                    store.savePendingOrder(null)
                    loadServerStatus()
                }
            }
        }
    }

    suspend fun logout(): Boolean {
        val current = store.load()
        val released = if (current != null) runCatching { accountRepository.releaseAppAccess(current) }.getOrDefault(false) else true
        if (!released && current != null) {
            store.queueRelease(identity.deviceId, current.releaseGrant)
            PendingReleaseWorker.schedule(context)
        }
        store.clear()
        accountRepository.logout()
        _state.value = AppAccessState.LoggedOut
        return released
    }

    /**
     * A revoked device must produce a new SMS/Passkey activation proof before claiming again.
     * Keep local content and the revoked authorization record intact; only forget the account
     * session so AccountActivity presents the real login flow.
     */
    suspend fun beginReauthentication() {
        accountRepository.logout()
        _state.value = AppAccessState.LoggedOut
    }

    private suspend fun loadServerStatus() {
        runCatching { accountRepository.appAccessStatus() }.onSuccess { summary ->
            if (summary.purchaseCount > 0 && accountRepository.session.value?.activationProof?.isNotBlank() == true) {
                claim()
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

private object LeaseVerifier {
    fun verify(compact: String, expectedDeviceId: String, nowSeconds: Long = System.currentTimeMillis() / 1000): Boolean = runCatching {
        val parts = compact.split('.')
        require(parts.size == 3)
        val header = JSONObject(String(Base64.decode(parts[0], FLAGS)))
        require(header.optString("alg") == "ES256")
        val payload = JSONObject(String(Base64.decode(parts[1], FLAGS)))
        require(payload.getString("licenseDeviceId") == expectedDeviceId)
        require(payload.getLong("expiresAt") > nowSeconds)
        val pem = BuildConfig.WATCHRSS_APP_ACCESS_PUBLIC_KEY
        require(pem.isNotBlank())
        val der = Base64.decode(pem.replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "").replace("\n", ""), Base64.DEFAULT)
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
