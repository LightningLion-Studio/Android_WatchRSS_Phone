package com.lightningstudio.watchrss.phone.connection.bluetooth

import androidx.room.withTransaction
import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.*
import com.lightningstudio.watchrss.phone.data.db.PhoneCompanionDatabase
import com.lightningstudio.watchrss.phone.data.note.NoteAssetFiles
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** Dormant until the local flag selects B2. Added tables share the existing business
 * database. No Room version bump or table creation occurs on the disabled path. */
internal class B2RoomStore(private val db: PhoneCompanionDatabase) : B2Persistence {
    private var initialized = false
    private val lane = synchronized(lanes) { lanes.getOrPut(db) { Mutex() } }
    private val sql get() = db.openHelper.writableDatabase
    internal val attachmentDatabase get() = sql
    private fun initialize() {
        if (initialized) return
        sql.execSQL("PRAGMA synchronous=FULL")
        // A standalone PRAGMA query can use a different pooled read connection.
        // Pin the actual writer while creating and checking the durable journal.
        sql.beginTransactionNonExclusive()
        try {
            requireFullWriter()
            sql.execSQL("CREATE TABLE IF NOT EXISTS library_binary_records(scope TEXT NOT NULL, namespace TEXT NOT NULL, id TEXT NOT NULL, body TEXT NOT NULL, PRIMARY KEY(scope,namespace,id))")
            sql.execSQL("CREATE TABLE IF NOT EXISTS library_binary_budget(singleton INTEGER PRIMARY KEY, used INTEGER NOT NULL, ceiling INTEGER NOT NULL)")
            if (!sql.query("SELECT used FROM library_binary_budget WHERE singleton=1").use { it.moveToFirst() }) {
                var used = 0L; var ceiling = 33554432L
                sql.query("SELECT namespace,body FROM library_binary_records").use { c -> while (c.moveToNext()) {
                    val value = B2Json.parse(c.getString(1), false)
                    used += charge(c.getString(0), value)
                    if (c.getString(0) == "job") ceiling = minOf(ceiling, value.getJSONObject("description").getJSONObject("selection").getJSONObject("limits").getLong("maxJournalBytes") / 2)
                } }
                sql.execSQL("INSERT INTO library_binary_budget(singleton,used,ceiling) VALUES(1,?,?)", arrayOf(used, ceiling))
            }
            NoteAssetFiles.initialize(sql)
            sql.setTransactionSuccessful()
        } finally { sql.endTransaction() }
        initialized = true
    }
    private fun requireFullWriter() {
        check(sql.inTransaction())
        if (sql.query("PRAGMA synchronous").use { it.moveToFirst(); it.getInt(0) } != 2) throw B2Exception("LOCAL_IO")
    }
    private suspend fun <T> durable(block: suspend () -> T): T {
        // Android may replace/reconfigure pooled connections. A raw PRAGMA is local
        // to a connection; it is not a lasting pool configuration. Re-establish FULL
        // before beginning, then verify the pinned writer inside every transaction.
        // Never attempt to change synchronous mode inside a transaction.
        if (!sql.inTransaction()) sql.execSQL("PRAGMA synchronous=FULL")
        return db.withTransaction { requireFullWriter(); block() }
    }
    override suspend fun <T> exclusive(block: suspend () -> T): T = lane.withLock { initialize(); block() }
    override suspend fun <T> transaction(block: suspend () -> T): T = durable(block)
    override suspend fun get(scope: String, ns: String, id: String): JSONObject? = sql.query("SELECT body FROM library_binary_records WHERE scope=? AND namespace=? AND id=?", arrayOf(scope, ns, id)).use { if (it.moveToFirst()) B2Json.parse(it.getString(0), false) else null }
    override suspend fun list(scope: String, ns: String): List<Pair<String, JSONObject>> = sql.query("SELECT id,body FROM library_binary_records WHERE scope=? AND namespace=? ORDER BY id", arrayOf(scope, ns)).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to B2Json.parse(c.getString(1), false)) } }
    override suspend fun listForJob(scope: String, namespace: String, jobId: String): List<JSONObject> {
        // Stored job IDs are lower-case hex. Embedded metadata is an escaped
        // JSON string, so this predicate only matches the record's job member.
        val record = """"jobId":"$jobId""""
        return sql.query(
            "SELECT body FROM library_binary_records WHERE scope=? AND namespace=? AND instr(body,?)>0",
            arrayOf(scope, namespace, record)
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val row = B2Json.parse(cursor.getString(0), false)
                    if (row.optString("jobId") == jobId) add(row)
                }
            }
        }
    }
    override suspend fun all(): List<B2Persistence.Record> = sql.query("SELECT scope,namespace,id,body FROM library_binary_records").use { c -> buildList { while (c.moveToNext()) add(B2Persistence.Record(c.getString(0), c.getString(1), c.getString(2), c.getString(3))) } }
    override suspend fun jobs() = records("job")
    override suspend fun requestOwners() = records("nativeOwner")
    override suspend fun applyRejections(): List<B2Persistence.Record> =
        sql.query("SELECT scope,namespace,id,body FROM library_binary_records WHERE namespace GLOB ?",
            arrayOf(B2Retention.REJECTION_PREFIX + "*")).use { c ->
            buildList { while (c.moveToNext()) add(B2Persistence.Record(c.getString(0), c.getString(1), c.getString(2), c.getString(3))) }
        }


    private fun records(namespace: String): List<B2Persistence.Record> =
        sql.query("SELECT scope,id,body FROM library_binary_records WHERE namespace=?", arrayOf(namespace)).use { c ->
            buildList { while (c.moveToNext()) add(B2Persistence.Record(c.getString(0), namespace, c.getString(1), c.getString(2))) }
        }

    private suspend fun deleteRecords(namespace: String, scope: String?, matches: (JSONObject) -> Boolean) = durable {
        require(namespace != "job")
        val fixedArgs = if (scope == null) listOf(namespace) else listOf(namespace, scope)
        val where = if (scope == null) "namespace=?" else "namespace=? AND scope=?"
        var lastScope = ""
        var lastId = ""
        var released = 0L
        while (true) {
            var scanned = 0
            val removed = sql.query(
                "SELECT scope,id,body,length(CAST(body AS BLOB))+512 FROM library_binary_records " +
                    "WHERE $where AND (scope,id)>(?,?) ORDER BY scope,id LIMIT 128",
                (fixedArgs + lastScope + lastId).toTypedArray()
            ).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        scanned++
                        lastScope = c.getString(0)
                        lastId = c.getString(1)
                        if (matches(B2Json.parse(c.getString(2), false))) {
                            add(lastScope to lastId)
                            released += c.getLong(3)
                        }
                    }
                }
            }
            for ((recordScope, id) in removed) sql.execSQL(
                "DELETE FROM library_binary_records WHERE scope=? AND namespace=? AND id=?",
                arrayOf(recordScope, namespace, id)
            )
            if (scanned < 128) break
        }
        if (released != 0L) {
            sql.execSQL("UPDATE library_binary_budget SET used=used-? WHERE singleton=1", arrayOf(released))
        }
    }

    override suspend fun deleteJobRecords(scope: String, job: String, namespace: String) =
        deleteRecords(namespace, scope) { it.optString("jobId") == job }

    override suspend fun deleteLegacyRequestRecords() =
        deleteRecords("request", null) { !it.has("jobId") }

    private fun charge(ns: String, value: JSONObject): Long = if (ns == "job") value.getLong("reservation") else B2Json.utf8(value.toString()).size.toLong() + 512
    override suspend fun budget(extra: Long, limit: Long) {
        sql.query("SELECT used,ceiling FROM library_binary_budget WHERE singleton=1").use { c ->
            if (!c.moveToFirst() || extra < 0 || c.getLong(0) > minOf(limit, c.getLong(1)) - extra) throw B2Exception("STORAGE_PRESSURE")
        }
    }
    override suspend fun put(scope: String, ns: String, id: String, value: JSONObject) = durable {
        val old = get(scope, ns, id)
        val delta = charge(ns, value) - (old?.let { charge(ns, it) } ?: 0)
        if (delta > 0) budget(delta, 33554432)
        sql.execSQL("INSERT OR REPLACE INTO library_binary_records(scope,namespace,id,body) VALUES(?,?,?,?)", arrayOf(scope, ns, id, value.toString()))
        sql.execSQL("UPDATE library_binary_budget SET used=used+? WHERE singleton=1", arrayOf(delta))
        if (ns == "job") sql.execSQL("UPDATE library_binary_budget SET ceiling=MIN(ceiling,?) WHERE singleton=1", arrayOf(value.getJSONObject("description").getJSONObject("selection").getJSONObject("limits").getLong("maxJournalBytes") / 2))
    }
    override suspend fun delete(scope: String, ns: String, id: String) = durable {
        val old = get(scope, ns, id)
        sql.execSQL("DELETE FROM library_binary_records WHERE scope=? AND namespace=? AND id=?", arrayOf(scope, ns, id))
        if (old != null) sql.execSQL("UPDATE library_binary_budget SET used=used-? WHERE singleton=1", arrayOf(charge(ns, old)))
    }
    companion object { private val lanes = java.util.WeakHashMap<PhoneCompanionDatabase, Mutex>() }
}
