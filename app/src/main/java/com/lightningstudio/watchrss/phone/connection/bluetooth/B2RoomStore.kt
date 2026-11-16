package com.lightningstudio.watchrss.phone.connection.bluetooth

import androidx.room.withTransaction
import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.*
import com.lightningstudio.watchrss.phone.data.db.PhoneCompanionDatabase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** Dormant until the local flag selects B2. Added tables share the existing business
 * database. No Room version bump or table creation occurs on the disabled path. */
internal class B2RoomStore(private val db: PhoneCompanionDatabase) : B2Persistence {
    private var initialized = false
    private val lane = synchronized(lanes) { lanes.getOrPut(db) { Mutex() } }
    private val sql get() = db.openHelper.writableDatabase
    private fun initialize() {
        if (initialized) return
        sql.execSQL("PRAGMA synchronous=FULL")
        if (sql.query("PRAGMA synchronous").use { it.moveToFirst(); it.getInt(0) } != 2) throw B2Exception("LOCAL_IO")
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
        initialized = true
    }
    override suspend fun <T> exclusive(block: suspend () -> T): T = lane.withLock { initialize(); block() }
    override suspend fun <T> transaction(block: suspend () -> T): T = db.withTransaction { block() }
    override suspend fun get(scope: String, ns: String, id: String): JSONObject? = sql.query("SELECT body FROM library_binary_records WHERE scope=? AND namespace=? AND id=?", arrayOf(scope, ns, id)).use { if (it.moveToFirst()) B2Json.parse(it.getString(0), false) else null }
    override suspend fun list(scope: String, ns: String): List<Pair<String, JSONObject>> = sql.query("SELECT id,body FROM library_binary_records WHERE scope=? AND namespace=? ORDER BY id", arrayOf(scope, ns)).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to B2Json.parse(c.getString(1), false)) } }
    override suspend fun all(): List<B2Persistence.Record> = sql.query("SELECT scope,namespace,id,body FROM library_binary_records").use { c -> buildList { while (c.moveToNext()) add(B2Persistence.Record(c.getString(0), c.getString(1), c.getString(2), c.getString(3))) } }
    private fun charge(ns: String, value: JSONObject): Long = if (ns == "job") value.getLong("reservation") else B2Json.utf8(value.toString()).size.toLong() + 512
    override suspend fun budget(extra: Long, limit: Long) {
        sql.query("SELECT used,ceiling FROM library_binary_budget WHERE singleton=1").use { c ->
            if (!c.moveToFirst() || extra < 0 || c.getLong(0) > minOf(limit, c.getLong(1)) - extra) throw B2Exception("STORAGE_PRESSURE")
        }
    }
    override suspend fun put(scope: String, ns: String, id: String, value: JSONObject) = db.withTransaction {
        val old = get(scope, ns, id)
        val delta = charge(ns, value) - (old?.let { charge(ns, it) } ?: 0)
        if (delta > 0) budget(delta, 33554432)
        sql.execSQL("INSERT OR REPLACE INTO library_binary_records(scope,namespace,id,body) VALUES(?,?,?,?)", arrayOf(scope, ns, id, value.toString()))
        sql.execSQL("UPDATE library_binary_budget SET used=used+? WHERE singleton=1", arrayOf(delta))
        if (ns == "job") sql.execSQL("UPDATE library_binary_budget SET ceiling=MIN(ceiling,?) WHERE singleton=1", arrayOf(value.getJSONObject("description").getJSONObject("selection").getJSONObject("limits").getLong("maxJournalBytes") / 2))
    }
    override suspend fun delete(scope: String, ns: String, id: String) = db.withTransaction {
        val old = get(scope, ns, id)
        sql.execSQL("DELETE FROM library_binary_records WHERE scope=? AND namespace=? AND id=?", arrayOf(scope, ns, id))
        if (old != null) sql.execSQL("UPDATE library_binary_budget SET used=used-? WHERE singleton=1", arrayOf(charge(ns, old)))
    }
    companion object { private val lanes = java.util.WeakHashMap<PhoneCompanionDatabase, Mutex>() }
}
