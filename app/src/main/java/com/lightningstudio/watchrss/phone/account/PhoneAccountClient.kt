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
    suspend fun startLogin(phone: String): LoginProgress = withContext(Dispatchers.IO) {
        requireConfigured()
        post(
            path = "/functions/v1/account/login/start",
            body = JSONObject().apply {
                put("phone", normalizeAccountPhone(phone))
                put("licenseDeviceId", licenseIdentity.deviceId)
                put("devicePublicKey", licenseIdentity.publicKeyPem)
            },
            bearerToken = null
        ).use { parseLoginProgress(it.jsonBody()) }
    }

    suspend fun loginWithPasswordFactor(transactionId: String, password: String): LoginProgress =
        withContext(Dispatchers.IO) {
            requireConfigured()
            post(
                path = "/functions/v1/account/login/mfa/password",
                body = JSONObject().apply {
                    put("transactionId", transactionId)
                    put("password", password)
                },
                bearerToken = null
            ).use { parseLoginProgress(it.jsonBody()) }
        }

    suspend fun requestPhoneOtpFactor(transactionId: String) = withContext(Dispatchers.IO) {
        requireConfigured()
        post(
            path = "/functions/v1/account/login/mfa/sms/request",
            body = JSONObject().put("transactionId", transactionId),
            bearerToken = null
        ).close()
    }

    suspend fun verifyPhoneOtpFactor(transactionId: String, otp: String): LoginProgress =
        verifyLoginCode("/functions/v1/account/login/mfa/sms/verify", transactionId, otp)

    suspend fun verifyTotpFactor(transactionId: String, code: String): LoginProgress =
        verifyLoginCode("/functions/v1/account/login/mfa/totp/verify", transactionId, code)

    suspend fun securityStatus(session: PhoneAccountSession): AccountSecurityStatus =
        withContext(Dispatchers.IO) {
            get("/functions/v1/account/security", session.accessToken).use {
                parseAccountSecurityStatus(it.jsonBody())
            }
        }

    suspend fun setTwoFactorEnabled(
        session: PhoneAccountSession,
        enabled: Boolean,
        verificationToken: String? = null
    ): AccountSecurityStatus = withContext(Dispatchers.IO) {
        backendPut(
            path = "/functions/v1/account/two-factor",
            body = JSONObject().apply {
                put("enabled", enabled)
                verificationToken?.let { put("verificationToken", it) }
            },
            bearerToken = session.accessToken
        ).use { parseAccountSecurityStatus(it.jsonBody()) }
    }

    suspend fun startSecurityVerification(session: PhoneAccountSession): LoginProgress =
        withContext(Dispatchers.IO) {
            post(
                path = "/functions/v1/account/security-verification/start",
                body = JSONObject(),
                bearerToken = session.accessToken
            ).use { parseLoginProgress(it.jsonBody()) }
        }

    suspend fun loginWithPassword(phone: String, password: String): PasswordLoginResult = withContext(Dispatchers.IO) {
        requireConfigured()
        require(password.length in 10..128) { "密码长度必须为 10–128 位" }
        val normalizedPhone = normalizeAccountPhone(phone)
        val url = authUrl("token").newBuilder().addQueryParameter("grant_type", "password").build()
        val response = authRequest(url, bearerToken = null)
            .post(JSONObject().apply {
                put("phone", normalizedPhone)
                put("password", password)
            }.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
            .let(::execute)
        val session = response.use { parseSupabaseSession(it.jsonBody(), normalizedPhone) }
        val factor = listTotpFactors(session).firstOrNull { it.verified }
        if (factor == null) {
            PasswordLoginResult.Complete(activatePasswordSession(session))
        } else {
            PasswordLoginResult.TotpRequired(PendingPasswordLogin(session, factor.id))
        }
    }

    suspend fun completePasswordTotp(
        pending: PendingPasswordLogin,
        code: String
    ): PhoneAccountSession = withContext(Dispatchers.IO) {
        val upgraded = verifyTotp(pending.session, pending.factorId, code)
        activatePasswordSession(upgraded)
    }

    suspend fun updatePassword(session: PhoneAccountSession, password: String) = withContext(Dispatchers.IO) {
        require(password.length in 10..128) { "密码长度必须为 10–128 位" }
        backendPut(
            path = "/functions/v1/account/password",
            body = JSONObject().apply { put("password", password) },
            bearerToken = session.accessToken
        ).close()
    }

    suspend fun listTotpFactors(session: PhoneAccountSession): List<TotpFactor> = withContext(Dispatchers.IO) {
        get("/functions/v1/account/totp", session.accessToken).use {
            parseTotpFactors(it.jsonBody())
        }
    }

    suspend fun beginTotpEnrollment(session: PhoneAccountSession): TotpEnrollment = withContext(Dispatchers.IO) {
        post("/functions/v1/account/totp", JSONObject(), session.accessToken).use { response ->
            val json = response.jsonBody()
            TotpEnrollment(
                factorId = json.getString("factorId"),
                secret = json.getString("secret"),
                uri = json.getString("uri")
            )
        }
    }

    suspend fun confirmTotpEnrollment(
        session: PhoneAccountSession,
        enrollment: TotpEnrollment,
        code: String
    ) = withContext(Dispatchers.IO) {
        post(
            "/functions/v1/account/totp/${enrollment.factorId}/confirm",
            JSONObject().put("code", code),
            session.accessToken
        ).close()
    }

    suspend fun disableTotp(
        session: PhoneAccountSession,
        factor: TotpFactor,
        code: String
    ) = withContext(Dispatchers.IO) {
        deleteWithBody(
            "/functions/v1/account/totp/${factor.id}",
            JSONObject().put("code", code),
            session.accessToken
        ).close()
    }
    suspend fun requestPhoneOtp(phone: String) = withContext(Dispatchers.IO) {
        requireConfigured()
        val body = JSONObject().apply {
            put("phone", normalizeAccountPhone(phone))
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
        val normalizedPhone = normalizeAccountPhone(phone)
        val body = JSONObject().apply {
            put("phone", normalizedPhone)
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
                phoneMasked = maskPhone(user.optString("phone").ifBlank { normalizedPhone }),
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

    suspend fun startPasskeyAuthentication(
        phone: String,
        transactionId: String? = null
    ): PasskeyOptions =
        withContext(Dispatchers.IO) {
            requireConfigured()
            post(
                path = "/functions/v1/account/passkeys/authentication/options",
                body = JSONObject().apply {
                    put("phone", normalizeAccountPhone(phone))
                    transactionId?.let { put("loginTransactionId", it) }
                },
                bearerToken = null
            ).use { response ->
                parsePasskeyOptions(response.jsonBody())
            }
        }

    suspend fun finishPasskeyAuthentication(
        challengeId: String,
        credentialJson: String
    ): LoginProgress = withContext(Dispatchers.IO) {
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
            parseLoginProgress(response.jsonBody())
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

    private suspend fun verifyLoginCode(
        path: String,
        transactionId: String,
        code: String
    ): LoginProgress = withContext(Dispatchers.IO) {
        requireConfigured()
        post(
            path = path,
            body = JSONObject().apply {
                put("transactionId", transactionId)
                put("code", code.trim())
            },
            bearerToken = null
        ).use { parseLoginProgress(it.jsonBody()) }
    }

    private fun backendPut(path: String, body: JSONObject, bearerToken: String): okhttp3.Response {
        val request = Request.Builder()
            .url(environment.backendBaseUrl + path)
            .addHeader("apikey", environment.supabaseAnonKey)
            .addHeader("content-type", JSON_MEDIA_TYPE.toString())
            .addHeader("authorization", "Bearer $bearerToken")
            .put(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return execute(request)
    }

    private fun authUrl(path: String): HttpUrl = environment.backendBaseUrl.toHttpUrl().newBuilder()
        .addPathSegments("auth/v1")
        .addPathSegments(path)
        .build()

    private fun authRequest(url: HttpUrl, bearerToken: String?): Request.Builder = Request.Builder()
        .url(url)
        .addHeader("apikey", environment.supabaseAnonKey)
        .addHeader("content-type", JSON_MEDIA_TYPE.toString())
        .apply { if (!bearerToken.isNullOrBlank()) addHeader("authorization", "Bearer $bearerToken") }

    private fun verifyTotp(
        session: PhoneAccountSession,
        factorId: String,
        code: String
    ): PhoneAccountSession {
        require(code.matches(Regex("\\d{6}"))) { "请输入 6 位动态验证码" }
        val factorUrl = authUrl("factors").newBuilder().addPathSegment(factorId).build()
        val challengeId = authRequest(factorUrl.newBuilder().addPathSegment("challenge").build(), session.accessToken)
            .post("{}".toRequestBody(JSON_MEDIA_TYPE)).build().let(::execute).use {
                it.jsonBody().getString("id")
            }
        return authRequest(factorUrl.newBuilder().addPathSegment("verify").build(), session.accessToken)
            .post(JSONObject().apply {
                put("challenge_id", challengeId)
                put("code", code)
            }.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build().let(::execute).use { parseSupabaseSession(it.jsonBody(), session.phoneMasked) }
    }

    private fun activatePasswordSession(session: PhoneAccountSession): PhoneAccountSession {
        val proof = post(
            path = "/functions/v1/account/login/password/activate",
            body = JSONObject().apply {
                put("licenseDeviceId", licenseIdentity.deviceId)
                put("devicePublicKey", licenseIdentity.publicKeyPem)
            },
            bearerToken = session.accessToken
        ).use { it.jsonBody().getString("activationProof") }
        return session.copy(activationProof = proof, updatedAtMillis = System.currentTimeMillis())
    }

    private fun parseSupabaseSession(json: JSONObject, fallbackPhone: String): PhoneAccountSession {
        val user = json.optJSONObject("user") ?: JSONObject()
        val userId = user.optString("id").trim()
        val accessToken = json.optString("access_token").trim()
        require(userId.isNotBlank() && accessToken.isNotBlank()) { "登录响应缺少账号信息" }
        return PhoneAccountSession(
            userId = userId,
            phoneMasked = maskPhone(user.optString("phone").ifBlank { fallbackPhone }),
            accessToken = accessToken,
            refreshToken = json.optString("refresh_token"),
            expiresAtMillis = System.currentTimeMillis() + json.optLong("expires_in", 3600L) * 1000L
        )
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

    private fun deleteWithBody(
        path: String,
        body: JSONObject,
        bearerToken: String
    ): okhttp3.Response {
        val request = Request.Builder()
            .url(environment.backendBaseUrl + path)
            .addHeader("apikey", environment.supabaseAnonKey)
            .addHeader("content-type", JSON_MEDIA_TYPE.toString())
            .addHeader("authorization", "Bearer $bearerToken")
            .delete(body.toString().toRequestBody(JSON_MEDIA_TYPE))
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

    private fun parseLoginProgress(json: JSONObject): LoginProgress {
        val transactionId = json.optString("transactionId").trim()
        require(transactionId.isNotBlank()) { "登录响应缺少事务信息" }
        val complete = json.optBoolean("complete", false)
        val sessionJson = json.optJSONObject("session")
        val session = sessionJson?.let(::parsePasskeySession)
        val verificationToken = json.optString("verificationToken").trim().ifBlank { null }
        require(!complete || session != null || verificationToken != null) { "验证响应缺少完成信息" }
        return LoginProgress(
            transactionId = transactionId,
            requiredFactorCount = json.optInt("requiredFactorCount", 1),
            completedFactors = json.optJSONArray("completedFactors").toStringList(),
            complete = complete,
            session = session,
            verificationToken = verificationToken
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

internal fun parseAccountSecurityStatus(json: JSONObject): AccountSecurityStatus =
    AccountSecurityStatus(
        twoFactorEnabled = json.optBoolean("twoFactorEnabled", false),
        availableMethods = json.optJSONArray("availableMethods").toStringSet()
    )

private fun JSONArray?.toStringSet(): Set<String> {
    if (this == null) return emptySet()
    return buildSet {
        for (index in 0 until length()) {
            optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
        }
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

internal fun parseTotpFactors(json: JSONObject): List<TotpFactor> {
    val factors = json.optJSONArray("factors") ?: return emptyList()
    return buildList {
        for (index in 0 until factors.length()) {
            val factor = factors.optJSONObject(index) ?: continue
            if (factor.has("factor_type") && factor.optString("factor_type") != "totp") continue
            val id = factor.optString("id").trim()
            if (id.isBlank()) continue
            add(
                TotpFactor(
                    id = id,
                    friendlyName = factor.optString("friendlyName")
                        .ifBlank { factor.optString("friendly_name") }
                        .ifBlank { "验证器" },
                    verified = factor.optBoolean("verified", factor.optString("status") == "verified")
                )
            )
        }
    }
}

internal fun normalizeAccountPhone(phone: String): String {
    val compact = phone.trim().filterNot { it == ' ' || it == '-' }
    val localPhone = when {
        compact.matches(Regex("1\\d{10}")) -> "+86$compact"
        compact.matches(Regex("86\\d{11}")) -> "+$compact"
        compact.matches(Regex("\\+861\\d{10}")) -> compact
        else -> throw IllegalArgumentException("目前仅支持中国大陆手机号")
    }
    require(localPhone.matches(Regex("\\+861[3-9]\\d{9}"))) { "请输入正确的中国大陆手机号" }
    return localPhone
}
