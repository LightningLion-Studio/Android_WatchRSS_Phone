package com.lightningstudio.watchrss.phone.syncv2

import android.util.Log
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

internal object SyncV2ChoiceDiagnostics {
    const val TAG = "WatchRssSyncV2"
    private val entityKey = Regex("[0-9a-f]{64}")
    // A server error code can be arbitrary protocol-shaped text. Log known codes only.
    private val codes = setOf(
        "closure_incomplete", "object_missing", "not_found", "hash_mismatch", "identity_conflict",
        "invalid_schema", "invalid_encoding", "storage_pressure", "quota_exceeded", "object_too_large",
        "choice_stale", "choice_pending", "projection_incomplete", "merge_unavailable",
        "authentication_required", "feature_disabled", "window_expired", "trusted_time_unavailable",
        "signing_state_unavailable", "body_revision_changed", "ref_conflict", "rollback_conflict",
        "snapshot_expired", "lease_expired", "temporarily_unavailable", "rate_limited",
    )

    // Stage and reason are fixed call-site labels. Never pass messages, details or exceptions to Log.
    fun warn(stage: String, error: Throwable, meta: JSONObject? = null, reason: String = "operation_failed",
        count: Int? = null) {
        if (error is CancellationException) return
        val code = syncV2ErrorCode(error)?.takeIf { it in codes } ?: "other"
        val kind = meta?.optString("kind")?.takeIf { it in SyncV2Codec.kinds } ?: "unknown"
        // entityKey is already a keyed digest. Never derive this field from an untrusted prefix.
        val prefix = meta?.optString("entityKey")?.takeIf(entityKey::matches)?.take(8) ?: "-"
        val missing = (error as? SyncV2CloudError)?.details?.optJSONArray("missing")?.length()
        val occurrences = count ?: if (meta != null) 1 else missing ?: 0
        Log.w(TAG, "stage=$stage code=$code kind=$kind reason=$reason entityKeyPrefix=$prefix count=$occurrences")
    }

    inline fun <T> at(stage: String, meta: JSONObject? = null, reason: String = "operation_failed", work: () -> T): T =
        try {
            work()
        } catch (e: Exception) {
            warn(stage, e, meta, reason)
            throw e
        }
}
