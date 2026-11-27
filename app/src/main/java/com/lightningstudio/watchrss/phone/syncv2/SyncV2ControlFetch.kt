package com.lightningstudio.watchrss.phone.syncv2

import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONObject

/**
 * Transport layer in front of the per-object reads of one control closure verification.
 *
 * It answers from the installed control objects and from a persisted fetch cache, and it
 * replaces the serial per-object GETs with `objects/batch-get` calls. It is a locator only:
 * [SyncV2ControlClosure] authenticates every returned byte and mapping again, so a stale or
 * hostile entry can fail a round but can never be accepted. Everything it fetches is stored
 * at once, so a round that runs out of budget resumes instead of starting again.
 */
internal class SyncV2ControlFetch(
    private val db: SupportSQLiteDatabase, private val scope: String,
    private val seal: (String, Any?) -> ByteArray, private val open: (String, ByteArray) -> JSONObject,
    private val get: (String) -> ByteArray, private val reference: (String) -> JSONObject,
    private val remote: Remote, private val readMappings: Boolean = true
) {
    /** [objects] is the raw `objects/batch-get` response. [references] maps each parent to its
     * mapping in the form [reference] returns, or to null when the server has none. */
    class Remote(val objects: (List<String>) -> JSONObject, val references: (List<String>) -> Map<String, JSONObject?>)
    /** Number of mappings answered from the cache. A failed round with a hit is retried without them. */
    var cachedMappings = 0; private set
    private var warmed = false
    private val states = hashSetOf<String>()

    fun get(oid: String): ByteArray {
        // The closure asks for the tree root only after it authenticated the signed header.
        if (!warmed && oid.startsWith("tree:")) { warmed = true; warm(oid) }
        return cached(oid) ?: get.invoke(oid)
    }

    fun reference(parent: String): JSONObject {
        if (readMappings) mapping(parent)?.let { cachedMappings++; return it }
        // Only row parents are kept. A segment mapping would never be pruned.
        return reference.invoke(parent).also { if (parent in states) put("map:$parent", seal("verify-map", it)) }
    }

    fun dropMappings() = db.execSQL("DELETE FROM syncv2_verify_cache WHERE scope=? AND key LIKE 'map:%'", arrayOf(scope))

    private fun hashed(oid: String, bytes: ByteArray) = oid.substringAfter(":sha256:") == SyncV2Codec.hex(SyncV2Codec.sha(bytes))
    private fun blob(sql: String, key: String) = db.controlQuery(sql, arrayOf(scope, key)).use { if (it.moveToFirst()) it.getBlob(0) else null }
    private fun cached(oid: String): ByteArray? =
        (blob("SELECT body FROM syncv2_cloud_objects WHERE scope=? AND oid=?", oid)
            ?: blob("SELECT body FROM syncv2_verify_cache WHERE scope=? AND key=?", oid))?.takeIf { hashed(oid, it) }
    private fun mapping(parent: String) = blob("SELECT body FROM syncv2_verify_cache WHERE scope=? AND key=?", "map:$parent")
        ?.let { runCatching { open("verify-map", it) }.getOrNull() }
    private fun put(key: String, body: ByteArray) = db.execSQL("INSERT OR REPLACE INTO syncv2_verify_cache VALUES(?,?,?)", arrayOf(scope, key, body))
    private fun <T> atomic(work: () -> T): T {
        if (db.inTransaction()) return work()
        db.beginTransaction()
        try { return work().also { db.setTransactionSuccessful() } } finally { db.endTransaction() }
    }
    private fun batchable(oid: String) = oid.substringBefore(':') in TYPES && runCatching { SyncV2Wire.oid(oid) }.isSuccess
    /** A failed batch call is never the round's verdict. The single reads report every server
     * condition as before. Only a lost network, a stopped round or a used budget end the round here. */
    private var batchOff = false
    private fun <T> attempt(call: () -> T): T? = try { call() } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException || e.message == "budget_paused" ||
            (e is java.io.IOException && e !is SyncV2CloudError)) throw e
        batchOff = true; null
    }

    /** Best effort. A missing object ends the batch; the closure then reads it alone and reports it. */
    private fun load(oids: Collection<String>) {
        if (batchOff) return
        for (chunk in oids.asSequence().distinct().filter { batchable(it) && cached(it) == null }.chunked(512).toList()) {
            var pending = chunk
            while (pending.isNotEmpty()) {
                val response = attempt { remote.objects(pending) } ?: return
                val wanted = pending.toSet(); val items = response.optJSONArray("objects"); var stored = 0
                atomic {
                    for (i in 0 until (items?.length() ?: 0)) {
                        val item = items!!.optJSONObject(i) ?: continue; val oid = item.optString("oid")
                        val bytes = runCatching { SyncV2Codec.decode64(item.getString("bytes")) }.getOrNull() ?: continue
                        if (oid in wanted && hashed(oid, bytes)) { put(oid, bytes); stored++ }
                    }
                }
                // The server returns the overflow of its response limit as nextOids.
                val next = response.optJSONArray("nextOids")
                pending = (0 until (next?.length() ?: 0)).map { next!!.optString(it) }.filter { it in wanted }
                if (stored == 0) break
            }
        }
    }

    private fun warm(root: String) {
        val keep = linkedSetOf<String>(); var level = listOf(root); var depth = 0
        while (level.isNotEmpty() && depth++ <= 64 && keep.size <= 1000000) {
            keep += level; load(level)
            val next = linkedSetOf<String>()
            for (oid in level) {
                val node = cached(oid)?.let { runCatching { SyncV2Wire.parse(it, 262144, true) }.getOrNull() } ?: continue
                node.optJSONArray("children")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.optString("oid")?.let(next::add) }
                node.optJSONArray("rows")?.let { a ->
                    for (i in 0 until a.length()) {
                        val row = a.optJSONObject(i) ?: continue
                        keep += row.optString("metaOid"); row.optString("stateOid").let { keep += it; states += it }
                    }
                }
            }
            level = next.filter { it.startsWith("tree:") && it !in keep }
        }
        load(keep)
        val parents = states.filter { runCatching { SyncV2Wire.oid(it, "state") }.isSuccess }
        // Each chunk is stored before the next one starts. A paused round keeps its progress.
        for (chunk in parents.filter { mapping(it) == null }.chunked(64)) {
            val found = (if (batchOff) null else attempt { remote.references(chunk) }) ?: break
            atomic { for (parent in chunk) found[parent]?.let { put("map:$parent", seal("verify-map", it)) } }
        }
        val refs = parents.mapNotNull { mapping(it)?.optString("refsOid")?.takeIf { oid -> oid.startsWith("state-refs:") } }
        keep += refs; load(refs)
        // Drop what an abandoned head left behind. The cache never exceeds one closure.
        atomic {
            val stale = db.controlQuery("SELECT key FROM syncv2_verify_cache WHERE scope=?", arrayOf(scope)).use { c ->
                buildList { while (c.moveToNext()) c.getString(0).let { if (it.removePrefix("map:").let { id -> if (it.startsWith("map:")) id !in states else id !in keep }) add(it) } }
            }
            for (key in stale) db.execSQL("DELETE FROM syncv2_verify_cache WHERE scope=? AND key=?", arrayOf(scope, key))
        }
    }

    companion object {
        private val TYPES = setOf("tree", "meta", "state", "state-refs")
        /** Installed objects are served from `syncv2_cloud_objects`; the fetch copy is no longer needed. */
        fun settle(db: SupportSQLiteDatabase, scope: String) = runCatching {
            db.execSQL("DELETE FROM syncv2_verify_cache WHERE scope=? AND key IN (SELECT oid FROM syncv2_cloud_objects WHERE scope=?)", arrayOf(scope, scope))
        }
    }
}
