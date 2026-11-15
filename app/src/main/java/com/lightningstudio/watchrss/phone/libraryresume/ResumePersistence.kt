package com.lightningstudio.watchrss.phone.libraryresume

import androidx.room.*
import com.lightningstudio.watchrss.phone.data.db.PhoneCompanionDatabase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** Namespaces: job, object, request, page, snapshot, preparation, chunk, peerAck.
 * All C records share the BUSINESS Room DB, so its transaction covers business DAOs too.
 * Keys are opaque digest locators; they never become file paths. */
@Entity(tableName = "library_resume_records", primaryKeys = ["scope", "namespace", "id"])
data class ResumeRecord(val scope: String, val namespace: String, val id: String, val body: String)
@Dao
interface ResumeDao {
    @Query("SELECT * FROM library_resume_records WHERE scope=:scope AND namespace=:namespace AND id=:id")
    suspend fun read(scope: String, namespace: String, id: String): ResumeRecord?
    @Query("SELECT * FROM library_resume_records WHERE scope=:scope AND namespace=:namespace ORDER BY id")
    suspend fun list(scope: String, namespace: String): List<ResumeRecord>
    @Query("SELECT * FROM library_resume_records") suspend fun all(): List<ResumeRecord>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(record: ResumeRecord)
    @Query("DELETE FROM library_resume_records WHERE scope=:scope AND namespace=:namespace AND id=:id") suspend fun delete(scope: String, namespace: String, id: String)
}
class ResumePersistence(val db: PhoneCompanionDatabase) {
    companion object {
        private val lanes = java.util.WeakHashMap<PhoneCompanionDatabase, Mutex>()
        private fun lane(db: PhoneCompanionDatabase) = synchronized(lanes) { lanes.getOrPut(db) { Mutex() } }
    }
    internal val lane = lane(db)
    private var durable = false
    private fun ensureDurability() {
        if (durable) return
        val sql = db.openHelper.writableDatabase
        // WAL/NORMAL is atomic but does not fsync each commit. Enable FULL on the
        // single writer before first C use, outside a transaction; dormant C changes nothing.
        sql.execSQL("PRAGMA synchronous=FULL")
        val mode = sql.query("PRAGMA synchronous").use { if (it.moveToFirst()) it.getInt(0) else -1 }
        if (mode != 2) fail("LOCAL_IO")
        durable = true
    }
    suspend fun <T> exclusive(block: suspend () -> T): T = lane.withLock { ensureDurability(); block() }
    suspend fun <T> transaction(block: suspend () -> T): T = db.withTransaction { block() }
    suspend fun get(scope: String, ns: String, id: String): JSONObject? = db.resumeDao().read(scope, ns, id)?.let { try { ResumeJson.parse(it.body, false) } catch (_: Exception) { fail("JOURNAL_CORRUPT") } }
    suspend fun list(scope: String, ns: String) = db.resumeDao().list(scope, ns).map { it.id to ResumeJson.parse(it.body, false) }
    suspend fun put(scope: String, ns: String, id: String, value: JSONObject) { db.resumeDao().put(ResumeRecord(scope, ns, id, value.toString())) }
    suspend fun delete(scope: String, ns: String, id: String) = db.resumeDao().delete(scope, ns, id)
    /** Conservative physical JSON bytes plus SQLite/request/receipt overhead; reservations
     * stay charged through terminal retention and cannot be reclaimed to admit a job. */
    suspend fun budget(extra: Long, limit: Long = 67108864L) {
        val records = db.resumeDao().all()
        var ceiling = limit
        val used = records.sumOf { row ->
            if (row.namespace == "job") { val j = ResumeJson.parse(row.body, false); ceiling = minOf(ceiling, j.o("description").o("selection").o("limits").n("maxJournalBytes")); j.n("reservation") }
            else ResumeJson.utf8(row.body).size.toLong() + 512
        }
        if (used + extra > ceiling) fail("STORAGE_PRESSURE")
    }
}
