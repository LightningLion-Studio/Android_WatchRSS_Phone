package com.lightningstudio.watchrss.phone.account

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters

class PendingReleaseWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val environment = AccountEnvironment.active(applicationContext)
        val store = AppAccessStore(applicationContext, environment.storageSuffix)
        val (deviceId, grant) = store.pendingRelease() ?: return Result.success()
        val client = PhoneAccountClient(
            environment = environment,
            licenseIdentity = LicenseDeviceIdentity(applicationContext)
        )
        return runCatching {
            client.releasePendingAppAccess(deviceId, grant)
            store.clearPendingRelease()
            Result.success()
        }.getOrDefault(Result.retry())
    }

    companion object {
        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<PendingReleaseWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(context).enqueueUniqueWork("watchrss-release-phone-slot", ExistingWorkPolicy.REPLACE, request)
        }
    }
}
