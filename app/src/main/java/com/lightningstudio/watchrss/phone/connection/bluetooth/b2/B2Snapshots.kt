package com.lightningstudio.watchrss.phone.connection.bluetooth.b2

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Bounded streaming freeze; original files are copied once, then immutable spools are
 * reused. Reads hash only the requested block, never rescan an object in the hot path. */
class B2Snapshots(private val store: B2Persistence, private val files: B2Files) {
    companion object {
        private const val MAX_OBJECTS = 4096
        private const val MAX_INVENTORY = 16777216
        private const val PAGE_ENTRIES = 16
        private const val PAGE_BYTES = 131072 - 4096
    }
    private fun pageId(snapshot: String, index: Int) = B2Digests.d("wrss.library-binary.v2/snapshot-page", listOf(snapshot, index.toString()))
    /** The limit belongs to each persisted page, just as it belongs to each CONTROL.
     * The complete inventory digest still covers the original compact JSON array. */
    private fun pages(entries: Sequence<String>): List<String> {
        val result = mutableListOf<String>(); val current = mutableListOf<String>(); var bytes = 2
        for (entry in entries) {
            val n = B2Json.utf8(entry).size
            if (n + 2 > PAGE_BYTES) fail("LIMIT_EXCEEDED")
            if (current.isNotEmpty() && (current.size == PAGE_ENTRIES || bytes + n + 1 > PAGE_BYTES)) {
                result.add(current.joinToString(",", "[", "]")); current.clear(); bytes = 2
            }
            bytes += n + if (current.isEmpty()) 0 else 1; current.add(entry)
        }
        if (current.isNotEmpty()) result.add(current.joinToString(",", "[", "]"))
        return result.also { ps -> ps.forEach { parsePage(it) } }
    }
    private fun parsePage(raw: String): List<JSONObject> {
        if (B2Json.utf8(raw).size > PAGE_BYTES) fail("LIMIT_EXCEEDED")
        val entries = B2Json.exact(B2Json.parse("{\"entries\":" + raw + "}", false), "entries").a("entries")
        if (entries.length() !in 1..PAGE_ENTRIES) fail("SOURCE_CHANGED")
        return entries.values().map { B2Wire.descriptor(it as? JSONObject ?: fail()) }
    }
    private class Inventory {
        private val digest = java.security.MessageDigest.getInstance("SHA-256")
        private var size = 2
        private var count = 0
        init { digest.update('['.code.toByte()) }
        fun add(page: String) {
            if (!page.startsWith("[") || !page.endsWith("]")) fail("SOURCE_CHANGED")
            val bytes = B2Json.utf8(page.substring(1, page.length - 1))
            if (count++ > 0) { digest.update(','.code.toByte()); size++ }
            size += bytes.size; if (size > MAX_INVENTORY) fail("LIMIT_EXCEEDED")
            digest.update(bytes)
        }
        fun finish(): Pair<String, Int> { digest.update(']'.code.toByte()); return B2Frames.hex(digest.digest()) to size }
    }
    private fun inventory(pages: List<String>): Pair<String, Int> = Inventory().also { digest -> pages.forEach(digest::add) }.finish()
    private suspend fun save(scope: String, witness: JSONObject, pages: List<String>, count: Int, size: Int) {
        store.budget(size.toLong() * 2 + pages.size * 512L + 2048)
        store.transaction {
            pages.forEachIndexed { i, raw -> store.put(scope, "snapshotPage", pageId(witness.s("snapshotId"), i), obj("entriesUtf8" to raw)) }
            store.put(scope, "snapshot", witness.s("snapshotId"), obj("witness" to witness, "format" to 1,
                "objectCount" to count, "pageCount" to pages.size, "inventoryBytes" to size))
        }
    }
    suspend fun freeze(scope: String, snapshotId: String, descriptors: List<JSONObject>,
        sourceBudget: Long = 67108864, ownedSources: File? = null, source: (JSONObject) -> File): JSONObject = store.exclusive {
        if (descriptors.size > MAX_OBJECTS) fail("LIMIT_EXCEEDED")
        val ds = descriptors.map { copy(it).also(B2Wire::descriptor) }.sortedBy { it.s("objectId") }
        B2Receiver.validateGraph(ds)
        val pages = pages(ds.asSequence().map { it.toString() }); val (hash, size) = inventory(pages)
        val witness = obj("snapshotId" to snapshotId, "manifestDigest" to hash, "fromExclusive" to 0, "toInclusive" to 0)
        B2Wire.snapshot(witness)
        val old = store.get(scope, "snapshot", snapshotId)
        if (old != null && old.o("witness").s("manifestDigest") != hash) fail("PLAN_CONFLICT")
        val created = mutableListOf<File>()
        try {
            for (d in ds) {
                val target = files.frozen(scope, snapshotId, d.s("objectId"))
                runInterruptible(Dispatchers.IO) { files.working(snapshotId) {
                    if (!files.verified(target, d.n("size"), d.s("sha256"))) {
                        val origin = source(d)
                        if (d.s("kind") in B2Wire.bookKinds && origin.parentFile == ownedSources && d.n("size") > 0) {
                            files.adoptSource(origin, target, d.n("size"), d.s("sha256"), sourceBudget)
                        } else files.publish(origin, target, d.n("size"), d.s("sha256"), sourceBudget)
                        created += target
                    }
                } }
            }
            save(scope, witness, pages, ds.size, size)
            copy(witness)
        } catch (failure: Throwable) {
            created.forEach { it.delete() }
            throw failure
        }
    }
    /** Compatibility migration for already frozen local journals. Extract one bounded
     * object at a time; each persisted page still passes the unchanged strict parser. */
    private fun legacyPages(raw: String): List<String> {
        if (B2Json.utf8(raw).size > MAX_INVENTORY || !raw.startsWith("[") || !raw.endsWith("]")) fail("SOURCE_CHANGED")
        val entries = sequence {
            var i = 1; var count = 0
            while (i < raw.length - 1) {
                if (raw[i] != '{' || ++count > MAX_OBJECTS) fail("SOURCE_CHANGED")
                val start = i; var depth = 0; var quoted = false; var escaped = false
                do {
                    val c = raw[i++]
                    if (quoted) {
                        if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '\"') quoted = false
                    } else when (c) { '\"' -> quoted = true; '{' -> depth++; '}' -> depth-- }
                    if (i - start > PAGE_BYTES) fail("LIMIT_EXCEEDED")
                } while (i < raw.length - 1 && (quoted || depth > 0))
                if (quoted || depth != 0) fail("SOURCE_CHANGED")
                yield(raw.substring(start, i))
                if (i < raw.length - 1) { if (raw[i++] != ',' || i == raw.length - 1) fail("SOURCE_CHANGED") }
            }
        }
        return pages(entries)
    }
    suspend fun descriptors(scope: String, witness: JSONObject): List<JSONObject> = store.exclusive {
        val saved = store.get(scope, "snapshot", witness.s("snapshotId")) ?: fail("SOURCE_CHANGED")
        if (B2Digests.snapshot(saved.o("witness")) != B2Digests.snapshot(witness)) fail("PLAN_CONFLICT")
        val legacy = saved.has("inventoryUtf8")
        val oldPages = if (legacy) legacyPages(saved.s("inventoryUtf8")) else null
        if (!legacy && (saved.n("format") != 1L || saved.n("pageCount") > MAX_OBJECTS || saved.n("objectCount") > MAX_OBJECTS)) fail("SOURCE_CHANGED")
        val count = oldPages?.size ?: saved.n("pageCount").toInt()
        val digest = Inventory(); val ds = mutableListOf<JSONObject>()
        // Load, strictly validate and hash one durable page before reading the next.
        for (i in 0 until count) {
            val raw = oldPages?.get(i) ?: (store.get(scope, "snapshotPage", pageId(witness.s("snapshotId"), i)) ?: fail("SOURCE_CHANGED")).s("entriesUtf8")
            ds.addAll(parsePage(raw)); digest.add(raw)
            if (ds.size > MAX_OBJECTS) fail("SOURCE_CHANGED")
        }
        val (hash, size) = digest.finish()
        if (hash != witness.s("manifestDigest") || !legacy && (ds.size.toLong() != saved.n("objectCount") || size.toLong() != saved.n("inventoryBytes"))) fail("SOURCE_CHANGED")
        B2Wire.sorted(ds.map { it.s("objectId") }); B2Receiver.validateGraph(ds)
        if (legacy) save(scope, witness, oldPages!!, ds.size, size)
        ds
    }
    internal fun range(scope: String, witness: JSONObject, d: JSONObject, index: Int): B2FileRef {
        val file = files.frozen(scope, witness.s("snapshotId"), d.s("objectId"))
        if (!file.isFile || file.length() != d.n("size")) fail("SOURCE_CHANGED")
        if (index !in 0 until d.a("chunkHashes").length()) fail()
        val offset = index * B2Wire.CHUNK.toLong()
        return B2FileRef(file, offset, minOf(B2Wire.CHUNK.toLong(), d.n("size") - offset))
    }

    fun read(scope: String, witness: JSONObject, d: JSONObject, index: Int): ByteArray {
        val ref = range(scope, witness, d, index)
        return ref.readBlock(ByteArray(ref.size.toInt()), d.a("chunkHashes").getString(index)) {}
    }
}
