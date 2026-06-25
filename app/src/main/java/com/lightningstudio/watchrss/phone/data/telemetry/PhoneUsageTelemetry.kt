package com.lightningstudio.watchrss.phone.data.telemetry

import android.content.Context
import android.os.Build
import com.lightningstudio.watchrss.phone.BuildConfig
import com.lightningstudio.watchrss.phone.account.AccountEnvironment
import com.lightningstudio.watchrss.phone.account.PhoneAccountRepository
import com.lightningstudio.watchrss.phone.account.PhoneInstallationIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class PhoneUsageTelemetry(
    private val context: Context,
    private val environment: AccountEnvironment,
    private val installationIdentity: PhoneInstallationIdentity,
    private val accountRepository: PhoneAccountRepository,
    private val appScope: CoroutineScope,
    private val httpClient: OkHttpClient = defaultHttpClient()
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "watchrss_usage_telemetry",
        Context.MODE_PRIVATE
    )

    fun recordAppLaunch() {
        capture("app_opened")
    }

    fun recordScreenOpen(screen: String) {
        preferences.edit()
            .putInt("screen_count_$screen", preferences.getInt("screen_count_$screen", 0) + 1)
            .apply()
        capture("screen_opened", mapOf("screen" to screen))
    }

    fun recordScreenDuration(screen: String, durationMs: Long) {
        if (durationMs <= 0L) return
        preferences.edit()
            .putLong("screen_duration_$screen", preferences.getLong("screen_duration_$screen", 0L) + durationMs)
            .apply()
        capture("screen_duration", mapOf("screen" to screen, "durationMs" to durationMs))
    }

    fun recordSyncResult(success: Boolean, kind: String, message: String? = null) {
        capture(
            event = "sync_completed",
            properties = mapOf(
                "success" to success,
                "kind" to kind,
                "message" to message.orEmpty()
            )
        )
    }

    private fun capture(event: String, properties: Map<String, Any?> = emptyMap()) {
        if (!environment.isTelemetryConfigured) return
        appScope.launch(Dispatchers.IO) {
            runCatching {
                val userId = accountRepository.session.value?.userId
                val distinctId = userId ?: installationIdentity.installId
                val payload = JSONObject().apply {
                    put("api_key", environment.posthogApiKey)
                    put("event", event)
                    put("distinct_id", distinctId)
                    put("properties", JSONObject().apply {
                        put("installId", installationIdentity.installId)
                        put("userId", userId.orEmpty())
                        put("platform", "phone")
                        put("packageName", context.packageName)
                        put("appVersionName", BuildConfig.VERSION_NAME)
                        put("appVersionCode", BuildConfig.VERSION_CODE)
                        put("firstInstalledAt", installationIdentity.firstInstalledAtMillis)
                        put("databaseInitializedAt", installationIdentity.databaseInitializedAtMillis)
                        put("deviceModel", "${Build.MANUFACTURER} ${Build.MODEL}")
                        put("sdk", Build.VERSION.SDK_INT)
                        properties.forEach { (key, value) -> put(key, value) }
                    })
                }
                val request = Request.Builder()
                    .url("${environment.posthogHost}/capture/")
                    .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                httpClient.newCall(request).execute().close()
            }
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun defaultHttpClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(12, TimeUnit.SECONDS)
                .writeTimeout(12, TimeUnit.SECONDS)
                .build()
    }
}

