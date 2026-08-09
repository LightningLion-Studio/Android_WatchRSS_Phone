package com.lightningstudio.watchrss.phone.cloud

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lightningstudio.watchrss.phone.PhoneCompanionApplication
import java.util.concurrent.TimeUnit

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
            if (runAttemptCount < 3 && error !is IllegalArgumentException) {
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

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
