package com.lightningstudio.watchrss.phone.cloud

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

/** Data-only server cleanup can discard an in-flight reservation while retaining the key epoch. */
internal suspend fun <T> retryDiscardedCloudTransfer(rebuild: suspend (CloudHttpException) -> Unit, attempt: suspend () -> T): T {
    for (round in 0..1) {
        try { return attempt() }
        catch (failure: CloudHttpException) {
            currentCoroutineContext().ensureActive()
            if (round != 0 || !discardedCloudTransfer(failure)) throw failure
            rebuild(failure)
        }
    }
    error("unreachable")
}
internal fun discardedCloudTransfer(failure: CloudHttpException): Boolean = when (failure.status) {
    409 -> cloudErrorCode(failure) == "cloud_content_rebuild_required"
    404 -> cloudErrorCode(failure) in setOf("snapshot_not_found", "upload_reservation_not_found")
    400 -> cloudErrorCode(failure) == "reservation_expired"
    else -> false
}
internal fun cloudErrorCode(failure: CloudHttpException): String =
    runCatching { JSONObject(failure.detail).optString("error") }.getOrDefault("")
internal fun cloudContentRebuildRequired() = CloudHttpException(409, "{\"error\":\"cloud_content_rebuild_required\"}")

internal data class CloudContentMetadata(val generation: String, val clearedAt: String?, val empty: Boolean) {
    companion object {
        fun fromJson(json: JSONObject): CloudContentMetadata? {
            if (!json.has("contentGeneration") || json.isNull("contentGeneration")) return null
            val generation = json.getString("contentGeneration")
            require(java.util.UUID.fromString(generation).toString() == generation)
            return CloudContentMetadata(generation,
                if (json.has("contentClearedAt") && !json.isNull("contentClearedAt")) json.getString("contentClearedAt") else null,
                json.getBoolean("contentEmpty"))
        }
    }
}

internal data class CloudContentRequestGeneration(val value: String?)

/** A whole pass retains the generation it started with, even while foreground bootstrap refreshes. */
internal class CloudContentGenerationFence(val scope: String, val generation: String?) :
    kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<CloudContentGenerationFence>
}

internal fun isCloudContentWrite(method: String, path: String): Boolean = method != "GET" && (
    path == "/functions/v1/cloud/records" || path == "/functions/v1/cloud/records/consent" ||
    path.startsWith("/functions/v1/cloud/snapshots/") || path.startsWith("/functions/v1/cloud/uploads/") ||
    path == "/functions/v1/cloud/chunks/missing" || path.endsWith("/missing-chunks") ||
    path.startsWith("/functions/v1/reader/") && path.endsWith("/bulk-grant"))

/** Every returned pass must be quiescent. A pending follow-up is not an active download. */
internal fun cloudPassCompletion(hasNewLocalChanges: Boolean): Pair<CloudSyncPhase, String> =
    if (hasNewLocalChanges) CloudSyncPhase.IDLE to "本轮同步已结束；本机新改动已排队"
    else CloudSyncPhase.COMPLETE to "云同步完成"

internal fun safeCloudTrace(stage: String): String {
    val kind = stage.substringBefore(':')
    return if (kind in setOf("object-start", "object-done")) "$kind:id=" +
        CloudSnapshotCodec.sha256(stage.substringAfter(':').toByteArray()).take(16) else stage
}
