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

    /**
     * 引导漏斗计数器事件。仅上报事件名计数，不含任何答案内容——引导中收集的自由文本
     * 永远不会离开本机。注意：capture() 在隐私同意前静默丢弃（步骤 1-2 不计数）。
     * 事件名集中在 companion 常量中，供 JVM 单测校验"事件名不含任何答案键/答案值"。
     */
    fun recordOnboardingStepCompleted() = capture(EVENT_ONBOARDING_STEP_COMPLETED)

    fun recordOnboardingStepSkipped() = capture(EVENT_ONBOARDING_STEP_SKIPPED)

    fun recordOnboardingImportSucceeded() = capture(EVENT_ONBOARDING_IMPORT_SUCCEEDED)

    fun recordOnboardingImportFailed() = capture(EVENT_ONBOARDING_IMPORT_FAILED)

    fun recordOnboardingCompleted() = capture(EVENT_ONBOARDING_COMPLETED)

    fun recordOnboardingDropped() = capture(EVENT_ONBOARDING_DROPPED)

    /** 情境提示计数器。仅上报事件名计数，Tip 内容与 id 永远不离开本机。 */
    fun recordTipShown() = capture(EVENT_TIP_SHOWN)

    fun recordTipDismissed() = capture(EVENT_TIP_DISMISSED)

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
        const val EVENT_ONBOARDING_STEP_COMPLETED = "onboarding_step_completed"
        const val EVENT_ONBOARDING_STEP_SKIPPED = "onboarding_step_skipped"
        const val EVENT_ONBOARDING_IMPORT_SUCCEEDED = "onboarding_import_succeeded"
        const val EVENT_ONBOARDING_IMPORT_FAILED = "onboarding_import_failed"
        const val EVENT_ONBOARDING_COMPLETED = "onboarding_completed"
        const val EVENT_ONBOARDING_DROPPED = "onboarding_dropped"
        const val EVENT_TIP_SHOWN = "tip_shown"
        const val EVENT_TIP_DISMISSED = "tip_dismissed"

        private const val UPLOAD_DEBOUNCE_MS = 750L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            .build()
    }
}
