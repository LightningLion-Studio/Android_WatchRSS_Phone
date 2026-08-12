package com.lightningstudio.watchrss.phone.account

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters

class SessionRevocationWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val environment = AccountEnvironment.active(applicationContext)
        val store = EncryptedAccountSessionStore(
            applicationContext,
            "watchrss_account_session${environment.storageSuffix}"
        )
        val session = store.pendingRevocation() ?: return Result.success()
        if (session.isExpired) {
            store.clearPendingRevocation()
            return Result.success()
        }
        val client = PhoneAccountClient(environment, LicenseDeviceIdentity(applicationContext))
        return try {
            client.logout(session)
            store.clearPendingRevocation()
            Result.success()
        } catch (error: PhoneAccountHttpException) {
            if (error.statusCode in setOf(400, 401, 403)) {
                // The token is already unusable or cannot represent a current
                // opaque phone session, so there is nothing left to revoke.
                store.clearPendingRevocation()
                Result.success()
            } else {
                Result.retry()
            }
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "watchrss-revoke-phone-session"

        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<SessionRevocationWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
