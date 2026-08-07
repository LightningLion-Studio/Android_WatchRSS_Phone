package com.lightningstudio.watchrss.phone.account

import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

class PhoneAccountRepository(
    private val environment: AccountEnvironment,
    private val sessionStore: EncryptedAccountSessionStore,
    private val installationIdentity: PhoneInstallationIdentity,
    private val accountClient: PhoneAccountClient,
    private val phoneDeviceId: String
) {
    val session: StateFlow<PhoneAccountSession?> = sessionStore.session

    val installId: String
        get() = installationIdentity.installId

    suspend fun initialize() {
        sessionStore.load()
    }

    suspend fun requestPhoneOtp(phone: String) {
        accountClient.requestPhoneOtp(phone)
    }

    suspend fun verifyPhoneOtp(phone: String, otp: String): PhoneAccountSession {
        return accountClient.verifyPhoneOtp(phone, otp).also { session ->
            sessionStore.save(session)
        }
    }

    suspend fun loginWithPassword(phone: String, password: String): PasswordLoginResult {
        return when (val result = accountClient.loginWithPassword(phone, password)) {
            is PasswordLoginResult.Complete -> result.also { sessionStore.save(it.session) }
            is PasswordLoginResult.TotpRequired -> result
        }
    }

    suspend fun completePasswordTotp(
        pending: PendingPasswordLogin,
        code: String
    ): PhoneAccountSession = accountClient.completePasswordTotp(pending, code)
        .also { sessionStore.save(it) }

    suspend fun updatePassword(password: String) {
        accountClient.updatePassword(requireSession(), password)
    }

    suspend fun listTotpFactors(): List<TotpFactor> =
        accountClient.listTotpFactors(requireSession())

    suspend fun beginTotpEnrollment(): TotpEnrollment =
        accountClient.beginTotpEnrollment(requireSession())

    suspend fun confirmTotpEnrollment(enrollment: TotpEnrollment, code: String) {
        accountClient.confirmTotpEnrollment(requireSession(), enrollment, code)
            .also { sessionStore.save(it) }
    }

    suspend fun disableTotp(factor: TotpFactor, code: String) {
        accountClient.disableTotp(requireSession(), factor, code)
            .also { sessionStore.save(it) }
    }

    suspend fun startPasskeyRegistration(): PasskeyOptions {
        val session = session.value ?: error("请先使用手机号验证码登录")
        require(!session.isExpired) { "登录已过期，请重新登录" }
        return accountClient.startPasskeyRegistration(session)
    }

    suspend fun listRegisteredPasskeys(): List<RegisteredPasskey> {
        val session = session.value ?: error("请先使用手机号验证码登录")
        require(!session.isExpired) { "登录已过期，请重新登录" }
        return accountClient.listRegisteredPasskeys(session)
    }

    suspend fun renameRegisteredPasskey(credentialId: String, displayName: String) {
        val session = session.value ?: error("请先使用手机号验证码登录")
        require(!session.isExpired) { "登录已过期，请重新登录" }
        accountClient.renameRegisteredPasskey(session, credentialId, displayName)
    }

    suspend fun deleteRegisteredPasskey(credentialId: String) {
        val session = session.value ?: error("请先使用手机号验证码登录")
        require(!session.isExpired) { "登录已过期，请重新登录" }
        accountClient.deleteRegisteredPasskey(session, credentialId)
    }

    suspend fun finishPasskeyRegistration(
        challengeId: String,
        credentialJson: String
    ) {
        val session = session.value ?: error("请先使用手机号验证码登录")
        accountClient.finishPasskeyRegistration(session, challengeId, credentialJson)
    }

    suspend fun startPasskeyAuthentication(phone: String): PasskeyOptions {
        return accountClient.startPasskeyAuthentication(phone)
    }

    suspend fun finishPasskeyAuthentication(
        challengeId: String,
        credentialJson: String
    ): PhoneAccountSession {
        return accountClient.finishPasskeyAuthentication(challengeId, credentialJson)
            .also { sessionStore.save(it) }
    }

    suspend fun logout() {
        sessionStore.clear()
    }

    suspend fun consumeActivationProof(expectedProof: String) {
        val current = session.value ?: return
        if (expectedProof.isBlank() || current.activationProof != expectedProof) return
        sessionStore.save(
            current.copy(
                activationProof = "",
                updatedAtMillis = System.currentTimeMillis()
            )
        )
    }

    suspend fun appAccessStatus(): AppAccessSummary =
        accountClient.appAccessStatus(requireSession())

    suspend fun claimAppAccess(idempotencyKey: String): AppAuthorization =
        accountClient.claimAppAccess(requireSession(), idempotencyKey)

    suspend fun refreshAppAccess(current: AppAuthorization): AppAuthorization =
        accountClient.refreshAppAccess(requireSession(), current)

    suspend fun releaseAppAccess(current: AppAuthorization): Boolean =
        accountClient.releaseAppAccess(requireSession(), current)

    suspend fun createPaymentOrder(idempotencyKey: String): AppPaymentOrder =
        accountClient.createPaymentOrder(requireSession(), idempotencyKey)

    suspend fun paymentOrder(orderId: String): AppPaymentOrder =
        accountClient.paymentOrder(requireSession(), orderId)

    private fun requireSession(): PhoneAccountSession {
        val current = session.value ?: error("请先登录")
        require(!current.isExpired) { "登录已过期，请重新登录" }
        return current
    }

    suspend fun buildAccountSyncRequest(
        watchDeviceId: String,
        watchInstallId: String? = null,
        watchDisplayName: String? = null
    ): JSONObject {
        val session = session.value ?: error("请先在手机端登录腕上RSS账号")
        val token = accountClient.issueWatchDeviceToken(
            session = session,
            phoneDeviceId = phoneDeviceId,
            watchDeviceId = watchDeviceId,
            watchInstallId = watchInstallId,
            displayName = watchDisplayName
        )
        return JSONObject().apply {
            put("version", 1)
            put("action", "syncAccount")
            put("deviceId", phoneDeviceId)
            put("account", JSONObject().apply {
                put("userId", session.userId)
                put("phoneMasked", session.phoneMasked)
                put("installId", installId)
                put("watchDeviceToken", token.accessToken)
                put("tokenExpiresAt", token.accessTokenExpiresAtMillis)
                put("watchAccessToken", token.accessToken)
                put("accessTokenExpiresAt", token.accessTokenExpiresAtMillis)
                put("watchRefreshToken", token.refreshToken)
                put("refreshTokenExpiresAt", token.refreshTokenExpiresAtMillis)
                put("backendBaseUrl", environment.backendBaseUrl)
                put("posthogHost", environment.posthogHost)
                put("posthogProjectApiKey", environment.posthogApiKey)
            })
            put("entitlement", JSONObject().apply {
                put("plan", token.entitlement.plan)
                put("active", token.entitlement.active)
                put("expiresAt", token.entitlement.expiresAtMillis)
                put("features", JSONArray(token.entitlement.features))
            })
            put("telemetry", JSONObject().apply {
                put("anonymousEnabled", true)
                put("diagnosticsEnabled", false)
                put("sampleRate", 1.0)
            })
        }
    }
}
