package com.lightningstudio.watchrss.phone.account

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class PhoneAccountClient(
    private val environment: AccountEnvironment,
    private val httpClient: OkHttpClient = defaultHttpClient()
) {
    suspend fun requestPhoneOtp(phone: String) = withContext(Dispatchers.IO) {
        requireConfigured()
        val body = JSONObject().apply {
            put("phone", phone.trim())
            put("create_user", true)
        }
        post(
            path = "/auth/v1/otp",
            body = body,
            bearerToken = null
        ).close()
    }

    suspend fun verifyPhoneOtp(phone: String, otp: String): PhoneAccountSession = withContext(Dispatchers.IO) {
        requireConfigured()
        val body = JSONObject().apply {
            put("phone", phone.trim())
            put("token", otp.trim())
            put("type", "sms")
        }
        post(
            path = "/auth/v1/verify",
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
                expiresAtMillis = System.currentTimeMillis() + expiresInSeconds * 1000L
            )
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
        post(
            path = "/functions/v1/issue-watch-device-token",
            body = body,
            bearerToken = session.accessToken
        ).use { response ->
            val json = response.jsonBody()
            WatchDeviceToken(
                token = json.optString("watchDeviceToken").trim(),
                expiresAtMillis = json.optLong("tokenExpiresAt"),
                entitlement = parseEntitlement(json.optJSONObject("entitlement"))
            ).also {
                require(it.token.isNotBlank()) { "后端未返回手表设备 token" }
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
        if (!response.isSuccessful) {
            val text = response.body?.string().orEmpty()
            response.close()
            throw IOException("HTTP ${response.code}: ${text.ifBlank { response.message }}")
        }
        return response
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

