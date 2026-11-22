package com.lightningstudio.watchrss.phone.data.telemetry

import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lightningstudio.watchrss.phone.BuildConfig
import com.lightningstudio.watchrss.phone.PhoneCompanionApplication
import com.lightningstudio.watchrss.phone.account.AccountEnvironment
import com.lightningstudio.watchrss.phone.network.CloudCallCancellation
import com.lightningstudio.watchrss.phone.network.withWatchRssAppVersionHeader
import com.lightningstudio.watchrss.phone.privacy.PhonePrivacyConsentStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

internal class BtSyncTelemetry(
    context: Context,
    environment: AccountEnvironment,
    private val installId: String,
    private val http: OkHttpClient = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build(),
    private val scheduleUpload: (ExistingWorkPolicy) -> Unit = { BtSyncTelemetryWorker.schedule(context, it) }
) {
    private val consent = PhonePrivacyConsentStore(context)
    private val endpoint = "${environment.backendBaseUrl}/functions/v1/telemetry-events".toHttpUrlOrNull()
    private val queue = BtSyncEventQueue(
        File(context.noBackupFilesDir, "bt_sync_events${environment.storageSuffix}.json")
    )

    fun begin(trigger: BtSyncTrigger): BtSyncSession? {
        if (!consent.hasRequiredConsent()) return null
        val device = BtSyncDevice(installId, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE,
            Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE, Build.VERSION.SDK_INT)
        return BtSyncSession(device, trigger, ::record, System::currentTimeMillis, SystemClock::elapsedRealtime)
    }

    private suspend fun record(event: BtSyncEvent) {
        withContext(NonCancellable + Dispatchers.IO) {
            if (!consent.hasRequiredConsent()) return@withContext
            // Telemetry storage or scheduler failure must not change the sync result.
            runCatching {
                queue.append(event)
                if (endpoint != null) scheduleUpload(ExistingWorkPolicy.APPEND_OR_REPLACE)
            }
        }
    }

    suspend fun onAppEntry() = withContext(Dispatchers.IO) {
        runCatching {
            if (!consent.hasRequiredConsent()) queue.clear()
            else if (endpoint != null && queue.hasPending()) scheduleUpload(ExistingWorkPolicy.KEEP)
        }
    }

    suspend fun uploadPending(): Boolean = withContext(Dispatchers.IO) {
        if (!consent.hasRequiredConsent()) {
            queue.clear()
            return@withContext true
        }
        val url = endpoint ?: return@withContext true
        while (consent.hasRequiredConsent()) {
            val batch = queue.batch()
            if (batch.isEmpty()) return@withContext true
            val payload = JSONObject().put("events", JSONArray(batch.map { it.data })).toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder().url(url).withWatchRssAppVersionHeader().post(payload).build()
            val status = CloudCallCancellation.run { call ->
                if (!consent.hasRequiredConsent()) return@run 0
                call.execute(http.newCall(request)) { it.code }
            }
            if (status == 0) break
            // A 200 includes rejected rows. They are terminal just like a bad request.
            if (status == 429 || status in 500..599) return@withContext false
            queue.remove(batch.mapTo(mutableSetOf()) { it.id })
        }
        queue.clear()
        true
    }

    companion object {
        private val initializationDrops = AtomicLong()
        val droppedInitializationEvents: Long get() = initializationDrops.get()

        suspend fun beginWithBudget(
            context: Context,
            scope: CoroutineScope,
            trigger: BtSyncTrigger,
            waitMillis: Long,
            initialize: () -> BtSyncTelemetry?
        ): BtSyncSession? {
            val pending = scope.async(Dispatchers.IO) {
                runCatching {
                    if (!PhonePrivacyConsentStore(context).hasRequiredConsent()) return@runCatching null
                    ensureActive()
                    initialize()?.begin(trigger)
                }
            }
            val result = try {
                withTimeoutOrNull(waitMillis) { pending.await() }
            } catch (_: CancellationException) {
                currentCoroutineContext().ensureActive()
                null
            } finally {
                pending.cancel()
            }

            // A missed startup budget leaves no event or writer waiting for initialization.
            if (result == null || result.isFailure) initializationDrops.incrementAndGet()
            return result?.getOrNull()
        }
    }
}

class BtSyncTelemetryWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val app = applicationContext as PhoneCompanionApplication
        return try {
            val uploaded = withContext(Dispatchers.IO) { app.container.btSyncTelemetry.uploadPending() }
            if (uploaded) Result.success() else Result.retry()
        } catch (_: IOException) {
            Result.retry()
        }
    }

    companion object {
        internal fun schedule(context: Context, policy: ExistingWorkPolicy) {
            val request = OneTimeWorkRequestBuilder<BtSyncTelemetryWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("watchrss-bt-sync-telemetry", policy, request)
        }
    }
}
