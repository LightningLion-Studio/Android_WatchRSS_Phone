package com.lightningstudio.watchrss.phone.account

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class PhoneAccountClient(
    private val environment: AccountEnvironment,
    private val licenseIdentity: LicenseDeviceIdentity,
    private val deviceAccessToken: () -> String? = { null },
    private val httpClient: OkHttpClient = defaultHttpClient()
) {
    suspend fun requestPhoneOtp(phone: String) = withContext(Dispatchers.IO) {
        requireConfigured()
        val body = JSONObject().apply {
            put("phone", phone.trim())
            put("create_user", true)
        }
        post(
            path = "/functions/v1/account/login/phone/request",
            body = body,
            bearerToken = null
        ).close()
    }

    suspend fun verifyPhoneOtp(phone: String, otp: String): PhoneAccountSession = withContext(Dispatchers.IO) {
        requireConfigured()
        val body = JSONObject().apply {
            put("phone", phone.trim())
            put("otp", otp.trim())
            put("licenseDeviceId", licenseIdentity.deviceId)
            put("devicePublicKey", licenseIdentity.publicKeyPem)
        }
        post(
            path = "/functions/v1/account/login/phone/verify",
            body = body,
            bearerToken = null
        ).use { response ->
            val json = response.jsonBody()
            val accessToken = json.optString("access_token").trim()
            val refreshToken = json.optString("refresh_token").trim()
            val expiresInSeconds = json.optLong("expires_in", 3600L)
            val user = json.optJSONObject("user") ?: JSONObject()
            val userId = user.optString("id").trim()
            require(accessToken.isNotBlank() && userId.isNotBlank()) { "登录响应缺少账号信息" }
            PhoneAccountSession(
                userId = userId,
                phoneMasked = maskPhone(user.optString("phone").ifBlank { phone }),
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresAtMillis = System.currentTimeMillis() + expiresInSeconds * 1000L,
                activationProof = json.optString("activationProof")
            )
        }
    }

    suspend fun startPasskeyRegistration(
        session: PhoneAccountSession
    ): PasskeyOptions = withContext(Dispatchers.IO) {
        requireConfigured()
        post(
            path = "/functions/v1/account/passkeys/registration/options",
            body = JSONObject(),
            bearerToken = session.accessToken
        ).use { response ->
            parsePasskeyOptions(response.jsonBody())
        }
    }

    suspend fun listRegisteredPasskeys(
        session: PhoneAccountSession
    ): List<RegisteredPasskey> = withContext(Dispatchers.IO) {
        requireConfigured()
        get(
            path = "/functions/v1/account/passkeys",
            bearerToken = session.accessToken
        ).use { response ->
            parseRegisteredPasskeys(response.jsonBody())
        }
    }

    suspend fun renameRegisteredPasskey(
        session: PhoneAccountSession,
        credentialId: String,
        displayName: String
    ) = withContext(Dispatchers.IO) {
        requireConfigured()
        patch(
            url = passkeyCredentialUrl(credentialId),
            body = JSONObject().apply { put("displayName", displayName) },
            bearerToken = session.accessToken
        ).close()
    }

    suspend fun deleteRegisteredPasskey(
        session: PhoneAccountSession,
        credentialId: String
    ) = withContext(Dispatchers.IO) {
        requireConfigured()
        delete(
            url = passkeyCredentialUrl(credentialId),
            bearerToken = session.accessToken
        ).close()
    }

    suspend fun finishPasskeyRegistration(
        session: PhoneAccountSession,
        challengeId: String,
        credentialJson: String
    ) = withContext(Dispatchers.IO) {
        requireConfigured()
        post(
            path = "/functions/v1/account/passkeys/registration/verify",
            body = JSONObject().apply {
                put("challengeId", challengeId)
                put("credential", JSONObject(credentialJson))
                put("licenseDeviceId", licenseIdentity.deviceId)
                put("devicePublicKey", licenseIdentity.publicKeyPem)
            },
            bearerToken = session.accessToken
        ).close()
    }

    suspend fun startPasskeyAuthentication(phone: String): PasskeyOptions =
        withContext(Dispatchers.IO) {
            requireConfigured()
            post(
                path = "/functions/v1/account/passkeys/authentication/options",
                body = JSONObject().apply { put("phone", phone.trim()) },
                bearerToken = null
            ).use { response ->
                parsePasskeyOptions(response.jsonBody())
            }
        }

    suspend fun finishPasskeyAuthentication(
        challengeId: String,
        credentialJson: String
    ): PhoneAccountSession = withContext(Dispatchers.IO) {
        requireConfigured()
        post(
            path = "/functions/v1/account/passkeys/authentication/verify",
            body = JSONObject().apply {
                put("challengeId", challengeId)
                put("credential", JSONObject(credentialJson))
                put("licenseDeviceId", licenseIdentity.deviceId)
                put("devicePublicKey", licenseIdentity.publicKeyPem)
            },
            bearerToken = null
        ).use { response ->
            parsePasskeySession(response.jsonBody())
        }
    }

    suspend fun issueWatchDeviceToken(
        session: PhoneAccountSession,
        phoneDeviceId: String,
        watchDeviceId: String,
        watchInstallId: String? = null,
        displayName: String? = null
    ): WatchDeviceToken = withContext(Dispatchers.IO) {
        requireConfigured()
        require(!session.isExpired) { "登录已过期，请重新登录" }
        val body = JSONObject().apply {
            put("phoneDeviceId", phoneDeviceId)
            put("watchDeviceId", watchDeviceId)
            put("watchInstallId", watchInstallId.orEmpty())
            put("displayName", displayName.orEmpty())
        }
        val response = deviceAccessToken()?.let { token ->
            postWithDeviceToken(
                path = "/functions/v1/issue-watch-device-token",
                body = body,
                bearerToken = session.accessToken,
                deviceToken = token
            )
        } ?: error("手机尚未授权")
        response.use { response ->
            val json = response.jsonBody()
            WatchDeviceToken(
                accessToken = json.optString("watchAccessToken")
                    .ifBlank { json.optString("watchDeviceToken") }.trim(),
                accessTokenExpiresAtMillis = json.optLong(
                    "accessTokenExpiresAt", json.optLong("tokenExpiresAt")
                ),
                refreshToken = json.optString("watchRefreshToken").trim(),
                refreshTokenExpiresAtMillis = json.optLong("refreshTokenExpiresAt"),
                entitlement = parseEntitlement(json.optJSONObject("entitlement"))
            ).also {
                require(it.accessToken.isNotBlank()) { "后端未返回手表 access token" }
                require(it.refreshToken.isNotBlank()) { "后端未返回手表 refresh token" }
            }
        }
    }

    private fun post(path: String, body: JSONObject, bearerToken: String?): okhttp3.Response {
        val request = Request.Builder()
            .url(environment.backendBaseUrl + path)
            .addHeader("apikey", environment.supabaseAnonKey)
            .addHeader("content-type", JSON_MEDIA_TYPE.toString())
            .apply {
                if (!bearerToken.isNullOrBlank()) {
                    addHeader("authorization", "Bearer $bearerToken")
                }
            }
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val response = httpClient.newCall(request).execute()
        return checkResponse(response)
    }

    private fun get(path: String, bearerToken: String?): okhttp3.Response {
        val request = Request.Builder()
            .url(environment.backendBaseUrl + path)
            .addHeader("apikey", environment.supabaseAnonKey)
            .apply {
                if (!bearerToken.isNullOrBlank()) {
                    addHeader("authorization", "Bearer $bearerToken")
                }
            }
            .get()
            .build()
        val response = httpClient.newCall(request).execute()
        return checkResponse(response)
    }

    private fun patch(
        url: HttpUrl,
        body: JSONObject,
        bearerToken: String?
    ): okhttp3.Response {
        val request = Request.Builder()
            .url(url)
            .addHeader("apikey", environment.supabaseAnonKey)
            .addHeader("content-type", JSON_MEDIA_TYPE.toString())
            .apply {
                if (!bearerToken.isNullOrBlank()) {
                    addHeader("authorization", "Bearer $bearerToken")
                }
            }
            .patch(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return execute(request)
    }

    private fun delete(url: HttpUrl, bearerToken: String?): okhttp3.Response {
        val request = Request.Builder()
            .url(url)
            .addHeader("apikey", environment.supabaseAnonKey)
            .apply {
                if (!bearerToken.isNullOrBlank()) {
                    addHeader("authorization", "Bearer $bearerToken")
                }
            }
            .delete()
            .build()
        return execute(request)
    }

    private fun execute(request: Request): okhttp3.Response {
        return checkResponse(httpClient.newCall(request).execute())
    }

    private fun checkResponse(response: okhttp3.Response): okhttp3.Response {
        if (!response.isSuccessful) {
            val text = response.body?.string().orEmpty()
            val error = PhoneAccountHttpException(
                statusCode = response.code,
                responseBody = text
            )
            response.close()
            throw error
        }
        return response
    }

    private fun passkeyCredentialUrl(credentialId: String): HttpUrl {
        require(credentialId.isNotBlank()) { "Passkey 凭据 ID 无效" }
        return environment.backendBaseUrl.toHttpUrl().newBuilder()
            .addPathSegments("functions/v1/account/passkeys")
            .addPathSegment(credentialId)
            .build()
    }

    private fun okhttp3.Response.jsonBody(): JSONObject {
        val text = body?.string().orEmpty()
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    private fun requireConfigured() {
        require(environment.isAuthConfigured) { "账号后端未配置" }
    }

    private fun parseEntitlement(json: JSONObject?): WatchEntitlementSnapshot {
        if (json == null) return WatchEntitlementSnapshot()
        val features = json.optJSONArray("features").toStringList()
        return WatchEntitlementSnapshot(
            plan = json.optString("plan").ifBlank { "free" },
            active = json.optBoolean("active", true),
            expiresAtMillis = json.optLong("expiresAt", json.optLong("expires_at", 0L)),
            features = features
        )
    }

    private fun parsePasskeyOptions(json: JSONObject): PasskeyOptions {
        val challengeId = json.optString("challengeId").trim()
        val publicKey = json.optJSONObject("publicKey")
        require(challengeId.isNotBlank() && publicKey != null) {
            "后端返回的 Passkey 挑战无效"
        }
        return PasskeyOptions(
            challengeId = challengeId,
            requestJson = publicKey.toString()
        )
    }

    private fun parsePasskeySession(json: JSONObject): PhoneAccountSession {
        val userId = json.optString("userId").trim()
        val accessToken = json.optString("accessToken").trim()
        val expiresInSeconds = json.optLong("expiresIn", 3600L)
        val expiresAtMillis = json.optLong("expiresAt").takeIf { it > 0L }
            ?: (System.currentTimeMillis() + expiresInSeconds * 1000L)
        require(userId.isNotBlank() && accessToken.isNotBlank()) {
            "Passkey 登录响应缺少账号信息"
        }
        return PhoneAccountSession(
            userId = userId,
            phoneMasked = json.optString("phoneMasked").ifBlank { "已登录账号" },
            accessToken = accessToken,
            refreshToken = json.optString("refreshToken"),
            expiresAtMillis = expiresAtMillis,
            activationProof = json.optString("activationProof")
        )
    }

    suspend fun appAccessStatus(session: PhoneAccountSession): AppAccessSummary = withContext(Dispatchers.IO) {
        get(
            path = "/functions/v1/account/app-access?licenseDeviceId=${licenseIdentity.deviceId}",
            bearerToken = session.accessToken
        ).use { it.jsonBody().toAccessSummary() }
    }

    suspend fun claimAppAccess(session: PhoneAccountSession, idempotencyKey: String): AppAuthorization = withContext(Dispatchers.IO) {
        require(session.activationProof.isNotBlank()) { "请重新完成短信或 Passkey 登录" }
        post(
            path = "/functions/v1/account/phone-authorizations/claim",
            body = JSONObject().apply {
                put("activationProof", session.activationProof)
                put("licenseDeviceId", licenseIdentity.deviceId)
                put("devicePublicKey", licenseIdentity.publicKeyPem)
                put("idempotencyKey", idempotencyKey)
            },
            bearerToken = session.accessToken
        ).use { parseAuthorization(it.jsonBody()) }
    }

    suspend fun refreshAppAccess(session: PhoneAccountSession, current: AppAuthorization): AppAuthorization = withContext(Dispatchers.IO) {
        postWithDeviceToken(
            path = "/functions/v1/account/app-access/refresh",
            body = JSONObject().apply { put("licenseDeviceId", licenseIdentity.deviceId) },
            bearerToken = session.accessToken,
            deviceToken = current.deviceAccessToken
        ).use { parseAuthorization(it.jsonBody()) }
    }

    suspend fun releaseAppAccess(session: PhoneAccountSession, current: AppAuthorization): Boolean = withContext(Dispatchers.IO) {
        postWithDeviceToken(
            path = "/functions/v1/account/phone-authorizations/release",
            body = JSONObject().apply { put("licenseDeviceId", licenseIdentity.deviceId) },
            bearerToken = session.accessToken,
            deviceToken = current.deviceAccessToken
        ).use { it.jsonBody().optBoolean("released") }
    }

    suspend fun createPaymentOrder(session: PhoneAccountSession, idempotencyKey: String): AppPaymentOrder = withContext(Dispatchers.IO) {
        post(
            path = "/functions/v1/payments/xunhupay/orders",
            body = JSONObject().apply { put("idempotencyKey", idempotencyKey) },
            bearerToken = session.accessToken
        ).use { it.jsonBody().toPaymentOrder() }
    }

    suspend fun paymentOrder(session: PhoneAccountSession, orderId: String): AppPaymentOrder = withContext(Dispatchers.IO) {
        get("/functions/v1/payments/orders/$orderId", session.accessToken).use { it.jsonBody().toPaymentOrder() }
    }

    private fun postWithDeviceToken(path: String, body: JSONObject, bearerToken: String, deviceToken: String): okhttp3.Response {
        val request = Request.Builder().url(environment.backendBaseUrl + path)
            .addHeader("apikey", environment.supabaseAnonKey)
            .addHeader("content-type", JSON_MEDIA_TYPE.toString())
            .addHeader("authorization", "Bearer $bearerToken")
            .addHeader("x-watchrss-device-authorization", "Bearer $deviceToken")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE)).build()
        return execute(request)
    }

    private fun parseAuthorization(json: JSONObject) = AppAuthorization(
        deviceAccessToken = json.getString("deviceAccessToken"),
        deviceAccessTokenExpiresAt = json.getLong("deviceAccessTokenExpiresAt"),
        lease = json.getString("lease"), leaseExpiresAt = json.getLong("leaseExpiresAt"),
        releaseGrant = json.optString("releaseGrant"), access = json.getJSONObject("access").toAccessSummary()
    )

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun maskPhone(phone: String): String {
            val trimmed = phone.trim()
            if (trimmed.length <= 4) return "****"
            return "${trimmed.take(3)}****${trimmed.takeLast(4)}"
        }

        private fun defaultHttpClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .build()
    }
}

internal fun parseRegisteredPasskeys(json: JSONObject): List<RegisteredPasskey> {
    val passkeys = json.optJSONArray("passkeys") ?: return emptyList()
    return buildList {
        for (index in 0 until passkeys.length()) {
            val item = passkeys.optJSONObject(index) ?: continue
            val credentialId = item.optString("credentialId").trim()
            if (credentialId.isBlank()) continue
            add(
                RegisteredPasskey(
                    credentialId = credentialId,
                    displayName = item.optString("displayName").trim(),
                    createdAtMillis = item.optLong("createdAt"),
                    lastUsedAtMillis = item.optLong("lastUsedAt").takeIf { it > 0L }
                )
            )
        }
    }
}
