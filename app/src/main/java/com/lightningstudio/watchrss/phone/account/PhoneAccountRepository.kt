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

    val hasUsableSession: Boolean
        get() = session.value?.isExpired == false

    val installId: String
        get() = installationIdentity.installId

    suspend fun initialize() {
        sessionStore.load()
    }

    suspend fun startLogin(phone: String): LoginProgress = accountClient.startLogin(phone)

    suspend fun loginWithPasswordFactor(transactionId: String, password: String): LoginProgress {
        return accountClient.loginWithPasswordFactor(transactionId, password).let { progress ->
            saveCompletedLogin(progress)
            progress
        }
    }

    suspend fun requestPhoneOtpFactor(transactionId: String) {
        accountClient.requestPhoneOtpFactor(transactionId)
    }

    suspend fun verifyPhoneOtpFactor(transactionId: String, otp: String): LoginProgress {
        return accountClient.verifyPhoneOtpFactor(transactionId, otp).let { progress ->
            saveCompletedLogin(progress)
            progress
        }
    }

    suspend fun verifyTotpFactor(transactionId: String, code: String): LoginProgress {
        return accountClient.verifyTotpFactor(transactionId, code).let { progress ->
            saveCompletedLogin(progress)
            progress
        }
    }

    suspend fun updatePassword(password: String, verificationToken: String) {
        val current = requireSession()
        try {
            accountClient.updatePassword(current, password, verificationToken)
        } finally {
            // The backend revokes all opaque sessions before attempting the
            // upstream credential update, including on an upstream failure.
            sessionStore.clear()
        }
    }

    suspend fun listTotpFactors(): List<TotpFactor> =
        accountClient.listTotpFactors(requireSession())

    suspend fun beginTotpEnrollment(): TotpEnrollment =
        accountClient.beginTotpEnrollment(requireSession())

    suspend fun confirmTotpEnrollment(
        enrollment: TotpEnrollment,
        code: String
    ) {
        accountClient.confirmTotpEnrollment(requireSession(), enrollment, code)
    }

    suspend fun disableTotp(factor: TotpFactor, code: String) {
        accountClient.disableTotp(requireSession(), factor, code)
    }

    suspend fun securityStatus(): AccountSecurityStatus =
        accountClient.securityStatus(requireSession())

    suspend fun setTwoFactorEnabled(
        enabled: Boolean,
        verificationToken: String? = null
    ): AccountSecurityStatus {
        val status = accountClient.setTwoFactorEnabled(
            requireSession(),
            enabled,
            verificationToken
        )
        if (enabled) sessionStore.clear()
        return status
    }

    suspend fun startSecurityVerification(): LoginProgress =
        accountClient.startSecurityVerification(requireSession())

    suspend fun startPasswordSecurityVerification(): LoginProgress =
        accountClient.startPasswordSecurityVerification(requireSession())

    suspend fun startActionSecurityVerification(action: String): LoginProgress =
        accountClient.startActionSecurityVerification(requireSession(), action)

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

    suspend fun startPasskeyAuthentication(
        phone: String,
        transactionId: String? = null
    ): PasskeyOptions = accountClient.startPasskeyAuthentication(phone, transactionId)

    suspend fun finishPasskeyAuthentication(
        challengeId: String,
        credentialJson: String
    ): LoginProgress {
        return accountClient.finishPasskeyAuthentication(challengeId, credentialJson).let { progress ->
            saveCompletedLogin(progress)
            progress
        }
    }

    private suspend fun saveCompletedLogin(progress: LoginProgress) {
        progress.session?.let { sessionStore.save(it) }
    }

    suspend fun logout() {
        val current = session.value
        if (current != null && !current.isExpired) {
            val queued = runCatching { sessionStore.queueRevocation(current) }.isSuccess
            val revoked = runCatching { accountClient.logout(current) }.isSuccess
            check(revoked || queued) { "无法安全退出，请检查网络后重试" }
            if (revoked) {
                if (queued) {
                    sessionStore.clearPendingRevocation()
                }
            } else {
                sessionStore.schedulePendingRevocation()
            }
        }
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

    suspend fun startTrialAppAccess(idempotencyKey: String): AppAuthorization =
        accountClient.startTrialAppAccess(requireSession(), idempotencyKey)

    suspend fun refreshAppAccess(current: AppAuthorization): AppAuthorization =
        accountClient.refreshAppAccess(requireSession(), current)

    suspend fun releaseAppAccess(current: AppAuthorization): Boolean =
        accountClient.releaseAppAccess(requireSession(), current)

    suspend fun createPaymentOrder(idempotencyKey: String): AppPaymentOrder =
        accountClient.createPaymentOrder(requireSession(), idempotencyKey)

    suspend fun paymentOrder(orderId: String): AppPaymentOrder =
        accountClient.paymentOrder(requireSession(), orderId)

    suspend fun paymentOrders(): List<AppPaymentOrder> =
        accountClient.paymentOrders(requireSession())

    suspend fun refundPaymentOrder(
        orderId: String,
        verificationToken: String,
        idempotencyKey: String
    ): AppPaymentOrder = accountClient.refundPaymentOrder(
        requireSession(),
        orderId,
        verificationToken,
        idempotencyKey
    )

    suspend fun deleteAccount(verificationToken: String): AccountDeletionResult {
        val result = accountClient.deleteAccount(requireSession(), verificationToken)
        sessionStore.clear()
        return result
    }

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
                put("posthogHost", "")
                put("posthogProjectApiKey", "")
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
