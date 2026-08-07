package com.lightningstudio.watchrss.phone.account

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class PendingReleaseWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val environment = AccountEnvironment.active(applicationContext)
        val store = AppAccessStore(applicationContext, environment.storageSuffix)
        val (deviceId, grant) = store.pendingRelease() ?: return Result.success()
        val request = Request.Builder()
            .url(environment.backendBaseUrl + "/functions/v1/account/phone-authorizations/release")
            .header("apikey", environment.supabaseAnonKey)
            .post(JSONObject().apply { put("licenseDeviceId", deviceId); put("releaseGrant", grant) }
                .toString().toRequestBody("application/json".toMediaType()))
            .build()
        return runCatching { OkHttpClient().newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use Result.retry()
            store.clearPendingRelease(); Result.success()
        }}.getOrDefault(Result.retry())
    }

    companion object {
        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<PendingReleaseWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(context).enqueueUniqueWork("watchrss-release-phone-slot", ExistingWorkPolicy.REPLACE, request)
        }
    }
}
