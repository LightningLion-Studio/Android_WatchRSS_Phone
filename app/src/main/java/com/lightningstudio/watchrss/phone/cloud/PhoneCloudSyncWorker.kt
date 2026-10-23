package com.lightningstudio.watchrss.phone.cloud

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lightningstudio.watchrss.phone.PhoneCompanionApplication
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

class PhoneCloudSyncWorker(
    appContext: Context,
    parameters: WorkerParameters
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as PhoneCompanionApplication).container
        if (!container.accountRepository.hasUsableSession || !container.appAccessCoordinator.isAuthorized) return Result.success()
        return runCatching {
            container.cloudSyncService.syncNow()
            Result.success()
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            if (runAttemptCount < 3 && error !is IllegalArgumentException && !(error is CloudHttpException && error.status == 426)) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    companion object {
        private const val WORK_NAME = "watchrss-cloud-sync-6h"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<PhoneCloudSyncWorker>(
                6,
                TimeUnit.HOURS
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun scheduleSnapshot(context: Context, delayMillis: Long) {
            val request = OneTimeWorkRequestBuilder<PhoneCloudSyncWorker>()
                .setInitialDelay(delayMillis.coerceAtLeast(1),TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("watchrss-cloud-snapshot-window",ExistingWorkPolicy.REPLACE,request)
        }

        fun scheduleFollowUp(context: Context) {
            val request=OneTimeWorkRequestBuilder<PhoneCloudSyncWorker>()
                .setInitialDelay(30,TimeUnit.SECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            // Append after a currently running follow-up instead of cancelling its own worker.
            WorkManager.getInstance(context).enqueueUniqueWork("watchrss-cloud-follow-up",ExistingWorkPolicy.APPEND_OR_REPLACE,request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork("watchrss-cloud-follow-up")
            WorkManager.getInstance(context).cancelUniqueWork("watchrss-cloud-snapshot-window")
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
