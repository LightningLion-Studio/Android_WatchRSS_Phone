package com.lightningstudio.watchrss.phone.data.telemetry

import android.content.Context
import com.lightningstudio.watchrss.phone.BuildConfig
import com.lightningstudio.watchrss.phone.account.AccountEnvironment
import com.lightningstudio.watchrss.phone.account.PhoneAccountRepository
import com.lightningstudio.watchrss.phone.account.PhoneInstallationIdentity
import com.lightningstudio.watchrss.phone.network.withWatchRssAppVersionHeader
import com.lightningstudio.watchrss.phone.privacy.PhonePrivacyConsentStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Stores only daily aggregate counters on device and uploads cumulative snapshots to WatchRSS.
 * Event properties that may contain article titles, URLs, or messages never leave this class.
 */
class PhoneUsageTelemetry(
    context: Context,
    private val environment: AccountEnvironment,
    private val installationIdentity: PhoneInstallationIdentity,
    private val deviceId: String,
    private val accountRepository: PhoneAccountRepository,
    private val appScope: CoroutineScope,
    private val httpClient: OkHttpClient = defaultHttpClient()
) {
    private val consentStore = PhonePrivacyConsentStore(context)
    private val store = DailyTelemetryStore(context)
    private val uploadScheduled = AtomicBoolean(false)
    private val generation = AtomicLong(0L)

    init {
        appScope.launch {
            accountRepository.session.filterNotNull().collect {
                scheduleUpload()
            }
        }
    }

    fun recordAppLaunch() = capture("app_opened")

    fun recordScreenOpen(screen: String) =
        capture("screen_opened", mapOf("screen" to screen))

    fun recordScreenDuration(screen: String, durationMs: Long) {
        if (durationMs > 0L) {
            capture("screen_duration", mapOf("screen" to screen, "durationMs" to durationMs))
        }
    }

    fun recordSyncResult(success: Boolean, kind: String, message: String? = null) =
        capture("sync_completed", mapOf("success" to success))

    fun recordRssSourceAdded(url: String, title: String, articleCount: Int) =
        capture("rss_source_added")

    fun recordArticleImported(source: String, url: String, title: String?) =
        capture("article_imported")

    fun recordLocalContentImported(kind: String, title: String?, articleCount: Int) =
        capture("local_content_imported")

    fun recordBackupImported(mode: String, articleCount: Int, sourceCount: Int) =
        capture("backup_imported")

    fun recordBackupExported(articleCount: Int, sourceCount: Int) =
        capture("backup_exported")

    fun recordRemoteInputSent(url: String) = capture("remote_input_sent")

    fun recordAccountSignedIn(userId: String) = capture("account_signed_in")

    private fun capture(event: String, properties: Map<String, Any?> = emptyMap()) {
        if (!consentStore.hasRequiredConsent()) return
        store.record(event, properties)
        generation.incrementAndGet()
        scheduleUpload()
    }

    private fun scheduleUpload() {
        if (!consentStore.hasRequiredConsent() || !uploadScheduled.compareAndSet(false, true)) return
        appScope.launch(Dispatchers.IO) {
            delay(UPLOAD_DEBOUNCE_MS)
            val uploadingGeneration = generation.get()
            runCatching { uploadPending() }
            uploadScheduled.set(false)
            if (generation.get() != uploadingGeneration) scheduleUpload()
        }
    }

    private fun uploadPending() {
        val session = accountRepository.session.value ?: return
        if (session.isExpired) return
        for (snapshot in store.snapshots()) {
            val payload = JSONObject().apply {
                put("day", snapshot.day)
                put("installId", installationIdentity.installId)
                put("deviceId", deviceId)
                put("platform", "phone")
                put("appVersionName", BuildConfig.VERSION_NAME)
                put("appVersionCode", BuildConfig.VERSION_CODE)
                put("screenOpenCounts", JSONObject(snapshot.screenOpenCounts))
                put("screenDurationMs", JSONObject(snapshot.screenDurationMs))
                put("eventCounts", JSONObject(snapshot.eventCounts))
                put("appForegroundMs", snapshot.appForegroundMs)
                put("syncSuccessCount", snapshot.syncSuccessCount)
                put("syncFailureCount", snapshot.syncFailureCount)
                put("diagnosticsOptedIn", false)
            }
            val request = Request.Builder()
                .url("${environment.backendBaseUrl}/functions/v1/telemetry-rollup")
                .header("Authorization", "Bearer ${session.accessToken}")
                .withWatchRssAppVersionHeader()
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return
            }
        }
    }

    companion object {
        private const val UPLOAD_DEBOUNCE_MS = 750L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            .build()
    }
}
