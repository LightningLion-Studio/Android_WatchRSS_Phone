package com.lightningstudio.watchrss.phone.cloud

import android.content.ContentValues
import android.content.Context
import org.json.JSONObject
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

internal data class QuarantinedCloudRecord(val id: String, val revision: String, val keyVersion: Int, val reason: String, val envelope: String, val tombstone: Boolean = false)
internal interface CloudRecordState {
    fun acceptDocument(owner:String,id:String,revision:String,raw:String,access:CloudFormatAccess,envelope:String) {
        saveDocument(owner,id,revision,raw,access);saveRawEnvelope(owner,id,envelope)
    }
    fun document(owner:String,id:String):String? = null
    fun saveDocument(owner:String,id:String,revision:String,raw:String,access:CloudFormatAccess) = Unit
    fun pending(owner:String,id:String,value:String?) = Unit
    fun pendingValue(owner:String,id:String):String? = null
    fun restrictedRecordIds(owner:String):Set<String> = emptySet()
    fun resetCursor(owner:String) = clear(owner)
    fun formatStatus(owner:String):CloudFormatStatus = CloudFormatStatus()
    fun markHeads(owner:String,ids:Set<String>) = Unit
    fun clearSnapshotPending(owner:String) = Unit

    fun quarantine(owner: String, record: QuarantinedCloudRecord) { error("隔离存储不可用") }
    fun quarantined(owner: String): List<QuarantinedCloudRecord> = emptyList()
    fun release(owner: String, id: String) = Unit

    fun rawPage(owner: String, after: String, limit: Int): List<Pair<String, String>> = emptyList()
    fun saveRssState(owner: String, value: JSONObject) = Unit
    fun rssStates(owner: String): List<JSONObject> = emptyList()
    fun rawEnvelope(owner: String, id: String): String? = null
    fun saveRawEnvelope(owner: String, id: String, envelope: String) = Unit
    fun cursor(owner: String): String
    fun record(owner: String, id: String): Pair<String, String>?
    fun save(owner: String, id: String, revision: String, hash: String)
    fun advance(owner: String, cursor: String)
    fun clear(owner: String)
}

/** Dedicated app-private SQLite, never the unbounded shared-preferences XML. */
internal class PhoneCloudRecordStore(context: Context) : SQLiteOpenHelper(context, "cloud-records-v1.db", null, 7), CloudRecordState {
    override fun onOpen(db:SQLiteDatabase) {
        super.onOpen(db)
        db.execSQL("CREATE INDEX IF NOT EXISTS raw_documents_owner_access ON raw_documents(owner,access)")
    }
    internal fun hasCycleBlockers(owner:String):Boolean = readableDatabase.rawQuery(
        "SELECT EXISTS(SELECT 1 FROM pending_records WHERE owner=?) OR EXISTS(SELECT 1 FROM quarantine WHERE owner=?) OR EXISTS(SELECT 1 FROM raw_documents WHERE owner=? AND access IN ('READ_ONLY','OPAQUE','ISOLATED'))",
        arrayOf(owner,owner,owner)).use { it.moveToFirst();it.getInt(0)!=0 }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE records (owner TEXT NOT NULL, id TEXT NOT NULL, revision TEXT NOT NULL, hash TEXT NOT NULL, PRIMARY KEY(owner,id))")
        db.execSQL("CREATE TABLE cursors (owner TEXT PRIMARY KEY, revision TEXT NOT NULL)")
        createQuarantine(db)
        createRaw(db)
        createRssState(db)
        createDocuments(db)
        createVersions(db)
    }
    private fun createQuarantine(db: SQLiteDatabase) { db.execSQL("CREATE TABLE IF NOT EXISTS quarantine (owner TEXT NOT NULL, id TEXT NOT NULL, revision TEXT NOT NULL, key_version INTEGER NOT NULL, reason TEXT NOT NULL, envelope TEXT NOT NULL, tombstone INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(owner,id))") }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { if(oldVersion<7)createVersions(db); if (oldVersion < 6) createDocuments(db); if (oldVersion < 5) createRssState(db); if (oldVersion < 2) createQuarantine(db); if (oldVersion < 3) createRaw(db); if (oldVersion in 2..3) db.execSQL("ALTER TABLE quarantine ADD COLUMN tombstone INTEGER NOT NULL DEFAULT 0") }
    private fun createVersions(db:SQLiteDatabase) {db.execSQL("CREATE TABLE IF NOT EXISTS raw_document_versions(owner TEXT NOT NULL,id TEXT NOT NULL,revision TEXT NOT NULL,raw TEXT NOT NULL,envelope TEXT NOT NULL,PRIMARY KEY(owner,id,revision))")}
    internal fun historicalDocuments(owner:String,id:String):List<String> = readableDatabase.rawQuery("SELECT raw FROM raw_document_versions WHERE owner=? AND id=? ORDER BY revision",arrayOf(owner,id)).use{c->buildList{while(c.moveToNext())add(c.getString(0))}}
    override fun acceptDocument(owner:String,id:String,revision:String,raw:String,access:CloudFormatAccess,envelope:String) {
        val db=writableDatabase;db.beginTransaction()
        try {
            val previous=db.rawQuery("SELECT revision FROM raw_documents WHERE owner=? AND id=?",arrayOf(owner,id)).use{if(it.moveToFirst())it.getString(0).toLong()else -1L}
            if(access!=CloudFormatAccess.WRITABLE) {
                val count=db.rawQuery("SELECT COUNT(*) FROM raw_document_versions WHERE owner=? AND id=? AND revision!=?",arrayOf(owner,id,revision)).use{it.moveToFirst();it.getInt(0)}
                if(count>=64)throw CloudFormatIsolationException("record_versions_limit")
                db.insertWithOnConflict("raw_document_versions",null,ContentValues().apply{put("owner",owner);put("id",id);put("revision",revision);put("raw",raw);put("envelope",envelope)},SQLiteDatabase.CONFLICT_IGNORE)
            }
            saveDocument(owner,id,revision,raw,access)
            if(revision.toLong()>=previous)saveRawEnvelope(owner,id,envelope)
            db.setTransactionSuccessful()
        }finally{db.endTransaction()}
    }
    private fun createDocuments(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS raw_documents(owner TEXT NOT NULL,id TEXT NOT NULL,revision TEXT NOT NULL,raw TEXT NOT NULL,access TEXT NOT NULL,PRIMARY KEY(owner,id))")
        db.execSQL("CREATE TABLE IF NOT EXISTS pending_records(owner TEXT NOT NULL,id TEXT NOT NULL,value TEXT NOT NULL,PRIMARY KEY(owner,id))")
    }
    internal fun privateOverlayEntries(owner:String):List<CloudPrivateOverlayEntry> = readableDatabase.rawQuery(
        "SELECT raw FROM raw_documents WHERE owner=? AND id LIKE 'private-overlay:%'",arrayOf(owner)).use { c ->
        buildList { while(c.moveToNext())addAll(CloudPrivateOverlay.decode(("{\"format\":\"watchrss-private-overlay\",\"version\":1,\"entries\":["+c.getString(0)+"]}").toByteArray())) }
    }
    override fun document(owner:String,id:String):String?=readableDatabase.rawQuery("SELECT raw FROM raw_documents WHERE owner=? AND id=?",arrayOf(owner,id)).use{if(it.moveToFirst())it.getString(0)else null}
    override fun saveDocument(owner:String,id:String,revision:String,raw:String,access:CloudFormatAccess) {
        // Android 8's bundled SQLite predates UPSERT ... DO UPDATE.
        val db=writableDatabase;db.beginTransaction()
        try {
            val previous=db.rawQuery("SELECT revision FROM raw_documents WHERE owner=? AND id=?",arrayOf(owner,id)).use{if(it.moveToFirst())it.getString(0).toLong()else -1L}
            if(revision.toLong()>=previous)db.insertWithOnConflict("raw_documents",null,ContentValues().apply {
                put("owner",owner);put("id",id);put("revision",revision);put("raw",raw);put("access",access.name)
            },SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        }finally{db.endTransaction()}
    }
    override fun pending(owner:String,id:String,value:String?) {
        if(value==null)writableDatabase.delete("pending_records","owner=? AND id=?",arrayOf(owner,id))
        else writableDatabase.insertWithOnConflict("pending_records",null,ContentValues().apply{put("owner",owner);put("id",id);put("value",value)},SQLiteDatabase.CONFLICT_REPLACE)
    }
    override fun pendingValue(owner:String,id:String):String?=readableDatabase.rawQuery("SELECT value FROM pending_records WHERE owner=? AND id=?",arrayOf(owner,id)).use{if(it.moveToFirst())it.getString(0)else null}
    override fun restrictedRecordIds(owner:String):Set<String> = readableDatabase.rawQuery("SELECT id FROM raw_documents WHERE owner=? AND access!='WRITABLE' AND id NOT LIKE 'snapshot:%' AND id NOT LIKE 'object:%'",arrayOf(owner)).use{c->buildSet{while(c.moveToNext())add(c.getString(0))}}
    override fun resetCursor(owner:String) {
        // A stale pagination cursor is not permission to discard authenticated future data or drafts.
        writableDatabase.beginTransaction()
        try {writableDatabase.delete("records","owner=?",arrayOf(owner));writableDatabase.delete("cursors","owner=?",arrayOf(owner));writableDatabase.setTransactionSuccessful()}
        finally{writableDatabase.endTransaction()}
    }
    override fun markHeads(owner:String,ids:Set<String>) {
        val rows=readableDatabase.rawQuery("SELECT id FROM raw_documents WHERE owner=? AND (id LIKE 'snapshot:%' OR id LIKE 'object:%')",arrayOf(owner)).use{c->buildList{while(c.moveToNext())add(c.getString(0))}}
        for(id in rows){val snapshot=if(id.startsWith("snapshot:"))id.removePrefix("snapshot:") else id.removePrefix("object:").substringBefore(':')
            if(snapshot !in ids)writableDatabase.delete("raw_documents","owner=? AND id=?",arrayOf(owner,id))}
    }
    override fun clearSnapshotPending(owner:String) {writableDatabase.delete("pending_records","owner=? AND (id LIKE 'manifest:%' OR id LIKE 'object:%')",arrayOf(owner))}
    override fun formatStatus(owner:String):CloudFormatStatus {
        fun count(sql:String)=readableDatabase.rawQuery(sql,arrayOf(owner)).use{it.moveToFirst();it.getInt(0)}
        val opaque=count("SELECT COUNT(*) FROM raw_documents WHERE owner=? AND access IN ('OPAQUE','ISOLATED')")+
            count("SELECT COUNT(*) FROM quarantine q WHERE owner=? AND reason='r48_isolated' AND NOT EXISTS(SELECT 1 FROM raw_documents d WHERE d.owner=q.owner AND d.id=q.id)")
        val readOnly=count("SELECT COUNT(*) FROM raw_documents WHERE owner=? AND access='READ_ONLY'")
        val pending=count("SELECT COUNT(*) FROM pending_records WHERE owner=?")
        return CloudFormatStatus(opaque,readOnly,pending,opaque+readOnly+pending>0)
    }
    private fun createRssState(db: SQLiteDatabase) { db.execSQL("CREATE TABLE IF NOT EXISTS rss_states (owner TEXT NOT NULL, source_id TEXT NOT NULL, article_hash TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY(owner,source_id,article_hash))") }
    override fun rawPage(owner: String, after: String, limit: Int): List<Pair<String, String>> = readableDatabase.rawQuery(
        "SELECT id,envelope FROM raw_records WHERE owner=? AND id>? ORDER BY id LIMIT ?", arrayOf(owner,after,limit.toString())).use { c ->
        buildList { while(c.moveToNext()) add(c.getString(0) to c.getString(1)) } }
    override fun saveRssState(owner: String, value: JSONObject) {
        val source = value.getString("sourceId"); val hash = value.getString("articleIdHash")
        val old = readableDatabase.rawQuery("SELECT value FROM rss_states WHERE owner=? AND source_id=? AND article_hash=?", arrayOf(owner,source,hash)).use {
            if(it.moveToFirst()) JSONObject(it.getString(0)) else null }
        val merged = CloudRssInteraction.merge(old, value)
        writableDatabase.insertWithOnConflict("rss_states",null,ContentValues().apply { put("owner",owner); put("source_id",source); put("article_hash",hash); put("value",merged.toString()) },SQLiteDatabase.CONFLICT_REPLACE)
    }
    override fun rssStates(owner: String): List<JSONObject> = readableDatabase.rawQuery("SELECT value FROM rss_states WHERE owner=?",arrayOf(owner)).use { c ->
        buildList { while(c.moveToNext()) add(JSONObject(c.getString(0))) } }
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
    override fun clear(owner: String) { writableDatabase.delete("raw_document_versions","owner=?",arrayOf(owner)); writableDatabase.delete("raw_documents","owner=?",arrayOf(owner));writableDatabase.delete("pending_records","owner=?",arrayOf(owner)); writableDatabase.delete("rss_states", "owner=?", arrayOf(owner)); writableDatabase.delete("raw_records", "owner=?", arrayOf(owner)); writableDatabase.delete("quarantine", "owner=?", arrayOf(owner)); writableDatabase.delete("records", "owner=?", arrayOf(owner)); writableDatabase.delete("cursors", "owner=?", arrayOf(owner)) }
}
