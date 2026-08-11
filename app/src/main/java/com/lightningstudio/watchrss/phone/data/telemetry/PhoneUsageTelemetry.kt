package com.lightningstudio.watchrss.phone.data.telemetry

import android.content.Context
import android.os.Build
import com.lightningstudio.watchrss.phone.BuildConfig
import com.lightningstudio.watchrss.phone.account.AccountEnvironment
import com.lightningstudio.watchrss.phone.account.PhoneAccountRepository
import com.lightningstudio.watchrss.phone.account.PhoneInstallationIdentity
import com.lightningstudio.watchrss.phone.privacy.PhonePrivacyConsentStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
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
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val openPanelAnalytics: OpenPanelAnalytics? = null
) {
    private val consentStore = PhonePrivacyConsentStore(context)
    private val preferences = context.applicationContext.getSharedPreferences(
        "watchrss_usage_telemetry",
        Context.MODE_PRIVATE
    )

    @Volatile
    private var analyticsInitialized = false

    init {
        // This collector is local until consent is granted. Network analytics initialization is
        // deferred to ensureAnalyticsInitialized(), which is called only by an accepted event.
        appScope.launch {
            accountRepository.session.filterNotNull().collect { session ->
                if (ensureAnalyticsInitialized()) {
                    openPanelAnalytics?.identify(
                        session.userId,
                        mapOf(
                            "installId" to installationIdentity.installId,
                            "userId" to session.userId,
                            "phoneMasked" to session.phoneMasked
                        )
                    )
                }
            }
        }
    }

    private fun ensureAnalyticsInitialized(): Boolean {
        if (!consentStore.hasRequiredConsent()) return false
        if (analyticsInitialized) return true
        synchronized(this) {
            if (analyticsInitialized) return true
            openPanelAnalytics?.setGlobalProperties(
                mapOf(
                    "platform" to "phone",
                    "packageName" to context.packageName,
                    "appVersionName" to BuildConfig.VERSION_NAME,
                    "appVersionCode" to BuildConfig.VERSION_CODE,
                    "deviceModel" to "${Build.MANUFACTURER} ${Build.MODEL}",
                    "sdk" to Build.VERSION.SDK_INT,
                    "firstInstalledAt" to installationIdentity.firstInstalledAtMillis
                )
            )
            val session = accountRepository.session.value
            openPanelAnalytics?.identify(
                session?.userId ?: installationIdentity.installId,
                buildMap {
                    put("installId", installationIdentity.installId)
                    session?.let {
                        put("userId", it.userId)
                        put("phoneMasked", it.phoneMasked)
                    }
                }
            )
            analyticsInitialized = true
        }
        return true
    }

    fun recordAppLaunch() {
        capture("app_opened")
    }

    fun recordScreenOpen(screen: String) {
        if (!consentStore.hasRequiredConsent()) return
        preferences.edit()
            .putInt("screen_count_$screen", preferences.getInt("screen_count_$screen", 0) + 1)
            .apply()
        capture("screen_opened", mapOf("screen" to screen))
    }

    fun recordScreenDuration(screen: String, durationMs: Long) {
        if (!consentStore.hasRequiredConsent()) return
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

    fun recordRssSourceAdded(url: String, title: String, articleCount: Int) {
        capture(
            event = "rss_source_added",
            properties = mapOf(
                "url" to url,
                "title" to title,
                "articleCount" to articleCount
            )
        )
    }

    fun recordArticleImported(source: String, url: String, title: String?) {
        capture(
            event = "article_imported",
            properties = mapOf(
                "source" to source,
                "url" to url,
                "title" to title.orEmpty()
            )
        )
    }

    fun recordLocalContentImported(kind: String, title: String?, articleCount: Int) {
        capture(
            event = "local_content_imported",
            properties = mapOf(
                "kind" to kind,
                "title" to title.orEmpty(),
                "articleCount" to articleCount
            )
        )
    }

    fun recordBackupImported(mode: String, articleCount: Int, sourceCount: Int) {
        capture(
            event = "backup_imported",
            properties = mapOf(
                "mode" to mode,
                "articleCount" to articleCount,
                "sourceCount" to sourceCount
            )
        )
    }

    fun recordBackupExported(articleCount: Int, sourceCount: Int) {
        capture(
            event = "backup_exported",
            properties = mapOf(
                "articleCount" to articleCount,
                "sourceCount" to sourceCount
            )
        )
    }

    fun recordRemoteInputSent(url: String) {
        capture(
            event = "remote_input_sent",
            properties = mapOf("url" to url)
        )
    }


    fun recordAccountSignedIn(userId: String) {
        capture(
            event = "account_signed_in",
            properties = mapOf("userId" to userId)
        )
    }

    private fun capture(event: String, properties: Map<String, Any?> = emptyMap()) {
        if (!ensureAnalyticsInitialized()) return
        openPanelAnalytics?.track(event, properties.filterValues { it != null }.mapValues { it.value!! })
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
