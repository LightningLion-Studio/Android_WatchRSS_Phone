package com.lightningstudio.watchrss.phone.connection.bluetooth.b2

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Bounded streaming freeze; original files are copied once, then immutable spools are
 * reused. Reads hash only the requested block, never rescan an object in the hot path. */
class B2Snapshots(private val store: B2Persistence, private val files: B2Files) {
    suspend fun freeze(scope: String, snapshotId: String, descriptors: List<JSONObject>, source: (JSONObject) -> File): JSONObject = store.exclusive {
        val ds = descriptors.map { copy(it).also(B2Wire::descriptor) }.sortedBy { it.s("objectId") }
        if (ds.size > 4096) fail("LIMIT_EXCEEDED"); B2Receiver.validateGraph(ds)
        val inventory = B2Json.utf8(JSONArray(ds).toString())
        if (inventory.size > 16777216) fail("LIMIT_EXCEEDED")
        val witness = obj("snapshotId" to snapshotId, "manifestDigest" to B2Digests.hash(inventory), "fromExclusive" to 0, "toInclusive" to 0)
        B2Wire.snapshot(witness)
        val old = store.get(scope, "snapshot", snapshotId)
        if (old != null && old.o("witness").s("manifestDigest") != witness.s("manifestDigest")) fail("PLAN_CONFLICT")
        for (d in ds) {
            val target = files.frozen(scope, snapshotId, d.s("objectId"))
            runInterruptible(Dispatchers.IO) { files.working(snapshotId) {
                if (!files.verified(target, d.n("size"), d.s("sha256"))) files.publish(source(d), target, d.n("size"), d.s("sha256"), 67108864)
            } }
        }
        store.budget(inventory.size.toLong() * 2 + 2048)
        store.put(scope, "snapshot", snapshotId, obj("witness" to witness, "inventoryUtf8" to B2Json.decode(inventory)))
        copy(witness)
    }
    suspend fun descriptors(scope: String, witness: JSONObject): List<JSONObject> {
        val saved = store.get(scope, "snapshot", witness.s("snapshotId")) ?: fail("SOURCE_CHANGED")
        if (B2Digests.snapshot(saved.o("witness")) != B2Digests.snapshot(witness)) fail("PLAN_CONFLICT")
        val raw = B2Json.utf8(saved.s("inventoryUtf8"))
        if (B2Digests.hash(raw) != witness.s("manifestDigest")) fail("SOURCE_CHANGED")
        return B2Json.parse("{\"entries\":" + B2Json.decode(raw) + "}", false).a("entries").values().map { B2Wire.descriptor(it as JSONObject) }
    }
    fun read(scope: String, witness: JSONObject, d: JSONObject, index: Int): ByteArray = try {
        val f = files.frozen(scope, witness.s("snapshotId"), d.s("objectId"))
        if (!f.isFile || f.length() != d.n("size")) fail("SOURCE_CHANGED")
        if (index !in 0 until d.a("chunkHashes").length()) fail()
        val bytes = ByteArray(minOf(65536L, d.n("size") - index * 65536L).toInt())
        java.nio.channels.FileChannel.open(f.toPath(), java.nio.file.StandardOpenOption.READ).use { channel ->
            channel.position(index * 65536L); val buffer = java.nio.ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) if (channel.read(buffer) < 0) fail("SOURCE_CHANGED")
        }
        if (B2Digests.hash(bytes) != d.a("chunkHashes").getString(index)) fail("SOURCE_CHANGED")
        bytes
    } catch (_: java.io.IOException) { fail("LOCAL_IO") }
}
