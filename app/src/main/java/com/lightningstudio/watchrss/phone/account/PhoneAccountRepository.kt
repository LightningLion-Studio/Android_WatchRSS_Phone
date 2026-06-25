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

    suspend fun logout() {
        sessionStore.clear()
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
                put("watchDeviceToken", token.token)
                put("tokenExpiresAt", token.expiresAtMillis)
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

