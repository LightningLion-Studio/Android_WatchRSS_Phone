package com.lightningstudio.watchrss.phone.push

import android.util.Log
import com.lightningstudio.watchrss.phone.account.AccountEnvironment
import com.lightningstudio.watchrss.phone.account.PhoneAccountRepository
import com.lightningstudio.watchrss.phone.network.withWatchRssAppVersionHeader
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Uploads the OPPO regId to the backend so the server can target this device.
 * The backend requires both the account Bearer token and the device-possession
 * authorization header; without a usable session or device access the upload is
 * deferred and retried on the next cold start.
 */
class PhonePushRegistrationUploader(
    private val environment: AccountEnvironment,
    private val accountRepository: PhoneAccountRepository,
    private val deviceAccessTokenProvider: () -> String?,
    private val installId: String,
    private val deviceId: String,
    private val httpClient: OkHttpClient = defaultHttpClient()
) {
    /** Returns true only when the backend accepted the registration. Never throws. */
    fun upload(regId: String): Boolean {
        val session = accountRepository.session.value ?: return false
        if (session.isExpired) return false
        val deviceToken = deviceAccessTokenProvider()?.takeIf { it.isNotBlank() } ?: return false
        val payload = JSONObject().apply {
            put("registrationId", regId)
            put("deviceId", deviceId)
            put("installId", installId)
        }
        val request = Request.Builder()
            .url("${environment.backendBaseUrl}/functions/v1/account/push-registration")
            .header("Authorization", "Bearer ${session.accessToken}")
            .header("x-watchrss-device-authorization", "Bearer $deviceToken")
            .withWatchRssAppVersionHeader()
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return try {
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    true
                } else {
                    Log.w(TAG, "push-registration rejected: HTTP ${response.code}")
                    false
                }
            }
        } catch (error: IOException) {
            Log.w(TAG, "push-registration upload failed", error)
            false
        }
    }

    companion object {
        private const val TAG = "WatchRSS_PushUpload"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            .build()
    }
}
