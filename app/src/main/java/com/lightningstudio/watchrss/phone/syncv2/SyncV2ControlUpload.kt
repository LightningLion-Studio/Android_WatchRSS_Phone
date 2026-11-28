package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** A renewed read window must still describe the account used to build the publication. */
internal class SyncV2ControlUpload(
    private val client: SyncV2CloudClient,
    private val expectedOid: String?,
    private val expectedRevision: String,
    private val expectedGeneration: String,
    private val now: () -> Long,
    private val requestId: () -> String,
    private val conflictCode: String,
    initialWindow: JSONObject? = null
) {
    private var window = initialWindow

    fun currentWindow(): JSONObject {
        val current = window?.takeIf { it.getLong("expiresAtMillis") - now() > 30_000 }
            ?: client.negotiate().also { window = it }
        val account = current.getJSONObject("account")
        val oid = if (account.isNull("commitOid")) null else account.getString("commitOid")
        if (oid != expectedOid || account.getString("revision") != expectedRevision ||
            account.getString("rollbackGeneration") != expectedGeneration
        ) throw SyncV2CloudError(conflictCode, retryable = conflictCode == "ref_conflict")
        return current
    }

    fun objects(
        oids: List<String>,
        read: (String) -> ByteArray,
        checkOffset: (String, Int, Int) -> Unit = { _, _, _ -> },
        checkpoint: (String, Int, Int) -> Unit = { _, _, _ -> }
    ) {
        for (keys in oids.chunked(64)) {
            // Bound memory to one batch, including for the disk-backed account action journal.
            val batch = keys.associateWith(read)
            val settled = ConcurrentHashMap.newKeySet<String>()
            val progress = AtomicLong()
            var reservation: String? = null
            var windowRetries = 0
            var reservationRetries = 0
            while (settled.size < keys.size) {
                val before = progress.get()
                try {
                    val remaining = keys.filterNot(settled::contains)
                    val sizes = remaining.map { it to batch.getValue(it).size }
                    val windowId = currentWindow().getString("windowId")
                    // Reserve before have/want: the service needs an active reservation
                    // to settle a complete prefix if its final acknowledgement was lost.
                    val reserved = reservation ?: client.reserve(requestId(), sizes).getString("reservationId")
                        .also { reservation = it }
                    val statuses = client.verifiedHaveWant(windowId, sizes, now())
                    val present = remaining.filter { statuses.getValue(it).getString("status") == "present" }
                    settled.addAll(present)
                    progress.addAndGet(present.size.toLong())
                    client.parallelControlUpload(remaining.filterNot(settled::contains)) { oid ->
                        val bytes = batch.getValue(oid)
                        var offset = statuses.getValue(oid).getInt("durableOffset")
                        checkOffset(oid, bytes.size, offset)
                        while (offset < bytes.size) {
                            val end = minOf(offset + 65536, bytes.size)
                            val response = client.put(oid, offset, reserved, requestId(), bytes.copyOfRange(offset, end))
                            if (response.getString("oid") != oid ||
                                SyncV2Wire.uint(response, "durableOffset") != end.toLong()
                            ) SyncV2Codec.fail("invalid_schema")
                            client.verifyReceipt(response.getJSONObject("receipt"), oid, bytes.size.toLong(), end.toLong(), now())
                            progress.addAndGet((end - offset).toLong())
                            offset = end
                            checkpoint(oid, bytes.size, offset)
                        }
                        val complete = client.complete(oid, reserved, requestId(), bytes.size)
                        client.verifyReceipt(complete.getJSONObject("receipt"), oid, bytes.size.toLong(), bytes.size.toLong(), now())
                        settled.add(oid)
                        progress.incrementAndGet()
                    }
                } catch (failure: SyncV2CloudError) {
                    if (progress.get() != before) {
                        windowRetries = 0
                        reservationRetries = 0
                    }
                    when {
                        failure.code == "window_expired" && windowRetries++ < 2 -> window = null
                        failure.code == "reservation_expired" && reservationRetries++ < 2 -> reservation = null
                        else -> throw failure
                    }
                }
            }
        }
    }
}
