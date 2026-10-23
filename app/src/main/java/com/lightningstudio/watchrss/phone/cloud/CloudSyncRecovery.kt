package com.lightningstudio.watchrss.phone.cloud

import com.lightningstudio.watchrss.phone.data.backup.CloudRestoreWarning
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import java.io.IOException

/** A valid record may precede its body snapshot; this is not a failed sync or an invalid owner. */
internal class MissingCloudBodyException : IOException("正文尚未同步完成，请稍后重试；后台将继续恢复")
internal class CloudRecordsUnavailableException : IOException("记录通道暂不可用，请稍后重试")

internal fun recoverableCloudRestoreFailure(error: Exception): Boolean =
    error !is CancellationException && error !is CloudTrustException &&
        !(error is CloudHttpException && discardedCloudTransfer(error)) &&
        !(error is CloudHttpException && error.status in setOf(401, 403, 409, 426)) &&
        (error is IOException || error is IllegalArgumentException || error is org.json.JSONException)

internal suspend fun restoreCloudBodyOrWarn(id: String, checkOwner: () -> Unit, restore: suspend () -> Unit): CloudRestoreWarning? =
    try { restore(); null } catch (error: Exception) {
        checkOwner()
        if (!recoverableCloudRestoreFailure(error)) throw error
        val reason=when (error) {
            is CloudObjectDecryptionException -> "decryption_failed:${error.objectId}"
            is CloudObjectParseException -> "parse_failed:${error.objectId}"
            else -> "missing_body"
        }
        // A body lookup may fail before its manifest is authenticated; it cannot abandon a head.
        CloudRestoreWarning(id, reason)
    }

/** Reading records is independent of reading snapshots. Upload callers still require a successful flush. */
internal suspend fun readCloudRecords(checkOwner: () -> Unit, read: suspend () -> Unit): Boolean =
    try { read(); checkOwner(); true } catch (error: Exception) {
        checkOwner()
        if (!recoverableCloudRestoreFailure(error)) throw error
        false
    }

/** Only this exact, already inspected head may be exempted from the fresh-head merge fence. */
internal class CloudSnapshotMergeProgress {
    private val warned = mutableSetOf<Pair<String, String>>()
    fun pendingWarnings(head: CloudSnapshotHead) { warned += head.sourceDeviceId to head.id }
    fun readyToPublish(heads: List<CloudSnapshotHead>, applied: (String) -> Long): Boolean =
        heads.filterNot { it.sourceDeviceId.startsWith("reader-migration-") }.all {
            it.deviceSequence <= applied(it.sourceDeviceId) || (it.sourceDeviceId to it.id) in warned
        }
    // Pending heads remain on the server and locally unapplied. Do not claim to have subsumed them.
    fun appliedHeads(heads: List<CloudSnapshotHead>, applied: (String) -> Long): List<CloudSnapshotHead> =
        heads.filterNot { it.sourceDeviceId.startsWith("reader-migration-") }.filter { it.deviceSequence <= applied(it.sourceDeviceId) }
}


internal fun permanentCloudRestoreFailure(error: Exception): Boolean =
    error is CloudObjectDecryptionException || error is CloudObjectParseException ||
        (error is javax.crypto.BadPaddingException || error is javax.crypto.IllegalBlockSizeException) || error is org.json.JSONException ||
        (error is CloudHttpException && error.status in setOf(404,410) && !discardedCloudTransfer(error))

internal data class CloudRestoreSettlement(val visible: List<CloudRestoreWarning>, val pending: List<CloudRestoreWarning>)

/** Persist a permanent decision before applied/ACK; never turn transient transport failures into data loss. */
internal fun settleCloudRestoreWarnings(settings: PhoneCloudStateStore, snapshotId: String,
    warnings: List<CloudRestoreWarning>, checkOwner: () -> Unit): CloudRestoreSettlement {
    val visible=mutableListOf<CloudRestoreWarning>()
    val pending=mutableListOf<CloudRestoreWarning>()
    for (warning in warnings.distinct()) {
        checkOwner()
        if (!warning.permanent) { pending += warning; visible += warning }
        else if (settings.abandonRestore(snapshotId,warning)) visible += warning
    }
    return CloudRestoreSettlement(visible,pending)
}


/** Transport integrity has not authenticated any content and can never justify abandonment. */
internal class CloudDownloadIntegrityException : IOException("云对象下载长度或哈希不匹配，请稍后重试")
internal class CloudObjectDecryptionException(val objectId: String) : IOException("云对象解密失败（对象ID：$objectId）")
internal class CloudObjectParseException(val objectId: String) : IOException("云对象解析失败（对象ID：$objectId）")

internal fun cloudObjectDiagnosticId(snapshotId: String, name: String): String =
    CloudSnapshotCodec.sha256("$snapshotId\n$name".toByteArray(Charsets.UTF_8)).take(16)

/** Called only around authenticated bytes decoding/parsing, never around a network request. */
internal inline fun <T> decodeCloudObject(snapshotId: String, name: String, decode: () -> T): T = try { decode() }
    catch (_: javax.crypto.BadPaddingException) { throw CloudObjectDecryptionException(cloudObjectDiagnosticId(snapshotId,name)) }
    catch (_: javax.crypto.IllegalBlockSizeException) { throw CloudObjectDecryptionException(cloudObjectDiagnosticId(snapshotId,name)) }
    catch (_: org.json.JSONException) { throw CloudObjectParseException(cloudObjectDiagnosticId(snapshotId,name)) }
    catch (_: java.util.zip.ZipException) { throw CloudObjectParseException(cloudObjectDiagnosticId(snapshotId,name)) }
    catch (_: java.io.EOFException) { throw CloudObjectParseException(cloudObjectDiagnosticId(snapshotId,name)) }
    catch (_: IllegalArgumentException) { throw CloudObjectParseException(cloudObjectDiagnosticId(snapshotId,name)) }

internal fun cloudPrivateObjectWarning(snapshotId: String, name: String, error: Exception): CloudRestoreWarning {
    val reason=when (error) {
        is CloudObjectDecryptionException -> "decryption_failed:${error.objectId}"
        is CloudObjectParseException -> "parse_failed:${error.objectId}"
        is CloudDownloadIntegrityException -> "download_integrity_retry"
        is CloudHttpException -> if (error.status in setOf(404,410)) "private_object_not_found" else "private_object_unavailable"
        else -> "private_object_unavailable"
    }
    return CloudRestoreWarning(cloudObjectDiagnosticId(snapshotId,name),reason,permanentCloudRestoreFailure(error))
}

/** Three attempts total; cancellation, authorization, 404/410 and decode failures never retry here. */
internal suspend fun <T> retryCloudObjectDownload(pause: suspend (Long) -> Unit, download: suspend () -> T): T {
    for (attempt in 0..2) {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        try { return download() }
        catch (error: IOException) {
            val retryable=when (error) {
                is CloudHttpException -> error.status in setOf(408,429) || error.status in 500..599
                is CloudObjectDecryptionException, is CloudObjectParseException -> false
                else -> true
            }
            if (!retryable || attempt==2) throw error
            pause(500L shl attempt)
        }
    }
    error("unreachable")
}
