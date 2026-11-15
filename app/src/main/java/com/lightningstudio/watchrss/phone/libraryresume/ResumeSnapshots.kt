package com.lightningstudio.watchrss.phone.libraryresume

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/** Export adapters capture descriptors and original encoded bytes under their existing
 * read transaction. freeze copies bytes before any network begin; reads never re-export
 * live rows or recompress an accepted version. */
class ResumeSnapshots(private val store: ResumePersistence, private val files: ResumeFiles) {
    suspend fun freeze(scope: String, snapshotId: String, descriptors: List<JSONObject>, readOriginal: suspend (JSONObject) -> ByteArray, fromExclusive: Long = 0, toInclusive: Long = 0): JSONObject = store.exclusive {
        val ds = descriptors.map { copy(it).also { d -> ResumeWire.descriptor(d) } }.sortedBy { it.s("objectId") }
        if (ds.size > 4096) fail("LIMIT_EXCEEDED"); ResumeReceiver.validateGraph(ds)
        val inventory = ResumeJson.utf8(JSONArray(ds).toString()); if (inventory.size > 16777216) fail("LIMIT_EXCEEDED")
        val witness = obj("snapshotId" to snapshotId, "manifestDigest" to ResumeDigests.hash(inventory), "fromExclusive" to fromExclusive, "toInclusive" to toInclusive)
        ResumeWire.snapshot(witness)
        val old = store.get(scope, "snapshot", snapshotId)
        if (old != null && (old.o("witness").toString() != witness.toString() || old.s("inventoryBase64") != Base64.getEncoder().encodeToString(inventory))) fail("PLAN_CONFLICT")
        for (d in ds) {
            val bytes = readOriginal(copy(d))
            if (bytes.size.toLong() != d.n("size") || ResumeDigests.hash(bytes) != d.s("sha256")) fail("SOURCE_CHANGED")
            d.a("chunkHashes").strings().forEachIndexed { i, h -> if (ResumeDigests.hash(bytes.copyOfRange(i * ResumeWire.CHUNK, minOf(bytes.size, (i + 1) * ResumeWire.CHUNK))) != h) fail("SOURCE_CHANGED") }
            if (!files.verified(files.frozen(scope, snapshotId, d.s("objectId")), d.n("size"), d.s("sha256"))) files.durable(files.frozen(scope, snapshotId, d.s("objectId")), bytes, 134217728)
        }
        store.budget(inventory.size.toLong() * 2 + 2048)
        store.put(scope, "snapshot", snapshotId, obj("witness" to witness, "inventoryBase64" to Base64.getEncoder().encodeToString(inventory)))
        copy(witness)
    }
    suspend fun descriptors(scope: String, witness: JSONObject): List<JSONObject> {
        val saved = store.get(scope, "snapshot", witness.s("snapshotId")) ?: fail("SOURCE_CHANGED")
        if (ResumeDigests.snapshot(saved.o("witness")) != ResumeDigests.snapshot(witness)) fail("PLAN_CONFLICT")
        val raw = ResumeJson.b64(saved.get("inventoryBase64"), 16777216)
        if (ResumeDigests.hash(raw) != witness.s("manifestDigest")) fail("SOURCE_CHANGED")
        // Inventory is an array wrapped only for the strict parser's object root contract.
        return ResumeJson.parse("{\"entries\":" + ResumeJson.decode(raw) + "}", false).a("entries").values().map { ResumeWire.descriptor(it as JSONObject) }
    }
    fun read(scope: String, witness: JSONObject, d: JSONObject, index: Int): ByteArray = try {
        val f = files.frozen(scope, witness.s("snapshotId"), d.s("objectId"))
        if (!files.verified(f, d.n("size"), d.s("sha256"))) fail("SOURCE_CHANGED")
        if (index !in 0 until d.a("chunkHashes").length()) fail()
        val bytes = java.io.RandomAccessFile(f, "r").use { file -> file.seek(index * ResumeWire.CHUNK.toLong()); ByteArray(minOf(ResumeWire.CHUNK.toLong(), d.n("size") - index * ResumeWire.CHUNK.toLong()).toInt()).also { file.readFully(it) } }
        if (ResumeDigests.hash(bytes) != d.a("chunkHashes").getString(index)) fail("SOURCE_CHANGED")
        bytes
    } catch (_: java.io.IOException) { fail("LOCAL_IO") }
}
