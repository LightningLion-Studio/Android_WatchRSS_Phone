package com.lightningstudio.watchrss.phone.cloud

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

internal data class QuarantinedCloudRecord(val id: String, val revision: String, val keyVersion: Int, val reason: String, val envelope: String, val tombstone: Boolean = false)
internal interface CloudRecordState {
    fun quarantine(owner: String, record: QuarantinedCloudRecord) { error("隔离存储不可用") }
    fun quarantined(owner: String): List<QuarantinedCloudRecord> = emptyList()
    fun release(owner: String, id: String) = Unit

    fun rawEnvelope(owner: String, id: String): String? = null
    fun saveRawEnvelope(owner: String, id: String, envelope: String) = Unit
    fun cursor(owner: String): String
    fun record(owner: String, id: String): Pair<String, String>?
    fun save(owner: String, id: String, revision: String, hash: String)
    fun advance(owner: String, cursor: String)
    fun clear(owner: String)
}

/** Dedicated app-private SQLite, never the unbounded shared-preferences XML. */
internal class PhoneCloudRecordStore(context: Context) : SQLiteOpenHelper(context, "cloud-records-v1.db", null, 4), CloudRecordState {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE records (owner TEXT NOT NULL, id TEXT NOT NULL, revision TEXT NOT NULL, hash TEXT NOT NULL, PRIMARY KEY(owner,id))")
        db.execSQL("CREATE TABLE cursors (owner TEXT PRIMARY KEY, revision TEXT NOT NULL)")
        createQuarantine(db)
        createRaw(db)
    }
    private fun createQuarantine(db: SQLiteDatabase) { db.execSQL("CREATE TABLE IF NOT EXISTS quarantine (owner TEXT NOT NULL, id TEXT NOT NULL, revision TEXT NOT NULL, key_version INTEGER NOT NULL, reason TEXT NOT NULL, envelope TEXT NOT NULL, tombstone INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(owner,id))") }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { if (oldVersion < 2) createQuarantine(db); if (oldVersion < 3) createRaw(db); if (oldVersion in 2..3) db.execSQL("ALTER TABLE quarantine ADD COLUMN tombstone INTEGER NOT NULL DEFAULT 0") }
    private fun createRaw(db: SQLiteDatabase) { db.execSQL("CREATE TABLE IF NOT EXISTS raw_records (owner TEXT NOT NULL, id TEXT NOT NULL, envelope TEXT NOT NULL, PRIMARY KEY(owner,id))") }
    override fun rawEnvelope(owner: String, id: String): String? = readableDatabase.rawQuery("SELECT envelope FROM raw_records WHERE owner=? AND id=?", arrayOf(owner,id)).use { if (it.moveToFirst()) it.getString(0) else null }
    override fun saveRawEnvelope(owner: String, id: String, envelope: String) { writableDatabase.insertWithOnConflict("raw_records", null, ContentValues().apply { put("owner", owner); put("id", id); put("envelope", envelope) }, SQLiteDatabase.CONFLICT_REPLACE) }
    override fun quarantine(owner: String, record: QuarantinedCloudRecord) {
        writableDatabase.insertWithOnConflict("quarantine", null, ContentValues().apply {
            put("owner", owner); put("id", record.id); put("revision", record.revision); put("key_version", record.keyVersion); put("reason", record.reason); put("envelope", record.envelope); put("tombstone", if (record.tombstone) 1 else 0)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    override fun quarantined(owner: String): List<QuarantinedCloudRecord> = readableDatabase.rawQuery(
        "SELECT id,revision,key_version,reason,envelope,tombstone FROM quarantine WHERE owner=?", arrayOf(owner)).use { cursor ->
        buildList { while (cursor.moveToNext()) add(QuarantinedCloudRecord(cursor.getString(0), cursor.getString(1), cursor.getInt(2), cursor.getString(3), cursor.getString(4), cursor.getInt(5) != 0)) }
    }
    override fun release(owner: String, id: String) { writableDatabase.delete("quarantine", "owner=? AND id=?", arrayOf(owner,id)) }
    override fun cursor(owner: String): String = readableDatabase.rawQuery("SELECT revision FROM cursors WHERE owner=?", arrayOf(owner)).use { if (it.moveToFirst()) it.getString(0) else "0" }
    override fun record(owner: String, id: String): Pair<String, String>? = readableDatabase.rawQuery("SELECT revision,hash FROM records WHERE owner=? AND id=?", arrayOf(owner, id)).use { if (it.moveToFirst()) it.getString(0) to it.getString(1) else null }
    override fun save(owner: String, id: String, revision: String, hash: String) {
        writableDatabase.insertWithOnConflict("records", null, ContentValues().apply { put("owner", owner); put("id", id); put("revision", revision); put("hash", hash) }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    override fun advance(owner: String, cursor: String) {
        writableDatabase.insertWithOnConflict("cursors", null, ContentValues().apply { put("owner", owner); put("revision", cursor) }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    override fun clear(owner: String) { writableDatabase.delete("raw_records", "owner=?", arrayOf(owner)); writableDatabase.delete("quarantine", "owner=?", arrayOf(owner)); writableDatabase.delete("records", "owner=?", arrayOf(owner)); writableDatabase.delete("cursors", "owner=?", arrayOf(owner)) }
}
