package com.lightningstudio.watchrss.phone.cloud

import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Fixed vocabulary only: never include exception text, object names, URLs, IDs, or cryptographic material. */
enum class CloudMigrationOperation { CREATE, CONSENT, REFRESH, CANCEL, VALIDATE, DOWNLOAD }
enum class CloudMigrationFailure(val retryable:Boolean) {
    NETWORK(true), CONFLICT(true), CHECKPOINT(true), BUDGET(true), DISABLED(false), SCOPE_CHANGED(false),
    CLOUD_FORMAT_UPGRADE_REQUIRED(false), CANCELLED(false), EXPIRED(false), UNSUITABLE(false), REJECTED(false), INVALID_RESULT(false), INTERNAL(false)
}
data class CloudMigrationEvent(val operation:CloudMigrationOperation,val outcome:String,val elapsedMs:Long,
                               val failure:CloudMigrationFailure?=null,val downloadedBytes:Long=0) {
    fun fields():Map<String,Any> = mapOf("operation" to operation.name.lowercase(),"outcome" to outcome,
        "elapsedMs" to elapsedMs.coerceAtLeast(0),"failure" to (failure?.name?.lowercase() ?: "none"),
        "retryable" to (failure?.retryable ?: false),"downloadedBytes" to downloadedBytes.coerceAtLeast(0))
}
fun interface CloudMigrationObserver { fun record(event:CloudMigrationEvent) }
internal class CloudMigrationDiagnostics(private val observer:CloudMigrationObserver,
                                         private val nanoTime:()->Long=System::nanoTime) {
    suspend fun <T> measure(operation:CloudMigrationOperation,downloadedBytes:Long=0,
                            failed:(CloudMigrationFailure)->Unit={},block:suspend()->T):T {
        val started=nanoTime()
        try {
            val result=block()
            emit(CloudMigrationEvent(operation,if(result==false)"deferred" else if(operation==CloudMigrationOperation.CONSENT && result==null)"declined" else "success",elapsed(started),downloadedBytes=downloadedBytes))
            return result
        } catch(error:Exception) {
            val failure=classify(error)
            failed(failure)
            emit(CloudMigrationEvent(operation,if(error is CancellationException)"cancelled" else "failure",elapsed(started),failure))
            throw error
        }
    }
    private fun elapsed(started:Long)=TimeUnit.NANOSECONDS.toMillis((nanoTime()-started).coerceAtLeast(0))
    private fun emit(event:CloudMigrationEvent) { runCatching { observer.record(event) } }
    companion object {
        fun classify(error:Exception):CloudMigrationFailure = when {
            error is kotlinx.coroutines.TimeoutCancellationException -> CloudMigrationFailure.BUDGET
            error is CancellationException -> CloudMigrationFailure.CANCELLED
            error is CloudHttpException && error.status==409 && cloudErrorCode(error)=="migration_plan_conflict" -> CloudMigrationFailure.CONFLICT
            error is CloudHttpException && error.status==426 -> CloudMigrationFailure.CLOUD_FORMAT_UPGRADE_REQUIRED
            error is CloudHttpException -> if(error.status in setOf(408,429,500,502,503,504))CloudMigrationFailure.NETWORK else CloudMigrationFailure.REJECTED
            error is IOException -> CloudMigrationFailure.NETWORK
            error.message=="migration_checkpoint_failed" -> CloudMigrationFailure.CHECKPOINT
            error.message=="migration_client_run_budget" -> CloudMigrationFailure.BUDGET
            error.message=="migration_feature_disabled" -> CloudMigrationFailure.DISABLED
            error.message=="migration_scope_changed" -> CloudMigrationFailure.SCOPE_CHANGED
            error.message=="migration_cancelled" -> CloudMigrationFailure.CANCELLED
            error.message=="migration_not_suitable" -> CloudMigrationFailure.UNSUITABLE
            error.message=="migration_candidate_download_not_configured" -> CloudMigrationFailure.DISABLED
            error is IllegalArgumentException || error is java.security.GeneralSecurityException -> CloudMigrationFailure.INVALID_RESULT
            else -> CloudMigrationFailure.INTERNAL
        }
    }
}
