package com.lightningstudio.watchrss.phone.syncv2

import androidx.sqlite.db.SupportSQLiteDatabase
import com.lightningstudio.watchrss.phone.BuildConfig

/** Additive auxiliary tables: Room version, business rows, BLE cursors and pairing are untouched. */
internal object SyncV2Schema {
    data class Domain(val table: String, val kind: String, val key: String)
    val domains = listOf(
        Domain("phone_articles", "article", "articleId"), Domain("phone_rss_sources", "source", "url"),
        Domain("imported_books", "book", "bookId"), Domain("imported_book_reading", "book-reading", "bookId || ':' || chapterIndex"),
        Domain("notes", "note", "noteId"), Domain("note_folders", "folder", "folderId"), Domain("note_assets", "asset", "assetId")
    )
    /** Remove the old P1 business triggers even when the projection gate is closed. */
    fun beforeBusinessOpen(db:SupportSQLiteDatabase) {
        val tables=(domains.map{it.table}+listOf("imported_book_chapters","note_conflicts","article_register_provenance")).toSet()
        val names=db.query("SELECT name,tbl_name FROM sqlite_master WHERE type='trigger' AND name LIKE 'syncv2_%'").use { c ->
            buildList { while(c.moveToNext())if(c.getString(1) in tables)add(c.getString(0)) }
        }
        for(name in names)db.execSQL("DROP TRIGGER IF EXISTS `${name.replace("`","``")}`")
        // Remember a missing/changed witness before the original business callback repairs it.
        if(hasColumn(db,"syncv2_control","witness_epoch") && !SyncV2BusinessWitness.isCurrent(db))
            runCatching {db.execSQL("UPDATE syncv2_control SET witness_epoch='' WHERE witness_epoch!=''")}
    }
    fun onOpen(db:SupportSQLiteDatabase) {
        try {
            beforeBusinessOpen(db)
            if(BuildConfig.SYNC_OBJECTS_V2_LOCAL_PROJECTION)install(db)
            else if(hasColumn(db,"syncv2_control","enabled"))db.execSQL("UPDATE syncv2_control SET enabled=0 WHERE enabled!=0")
        } catch(_:Exception) {
            // Isolate projection setup/closing faults from the original Room business open.
            runCatching {db.execSQL("UPDATE syncv2_control SET enabled=0,failure_code='projection_setup_failed' WHERE id=1")}
        }
    }
    private fun hasColumn(db:SupportSQLiteDatabase,table:String,column:String)=db.query("PRAGMA table_info(`$table`)").use { c ->
        var found=false;while(c.moveToNext())if(c.getString(1)==column)found=true;found
    }
    fun install(db: SupportSQLiteDatabase) {
        beforeBusinessOpen(db)
        db.beginTransaction()
        try {
            listOf(
                "CREATE TABLE IF NOT EXISTS syncv2_p5_objects(scope TEXT NOT NULL,action_id TEXT NOT NULL,oid TEXT NOT NULL,body BLOB NOT NULL,PRIMARY KEY(scope,action_id,oid))",
                "CREATE TABLE IF NOT EXISTS syncv2_p5_rows(scope TEXT NOT NULL,action_id TEXT NOT NULL,entity_key TEXT NOT NULL,body BLOB NOT NULL,PRIMARY KEY(scope,action_id,entity_key))",
                "CREATE TABLE IF NOT EXISTS syncv2_recovery_chunks(scope TEXT NOT NULL,copy_id TEXT NOT NULL,kind TEXT NOT NULL,ordinal INTEGER NOT NULL,bytes BLOB NOT NULL,PRIMARY KEY(scope,copy_id,kind,ordinal))",
                "CREATE TABLE IF NOT EXISTS syncv2_recovery_copies(scope TEXT NOT NULL,copy_id TEXT NOT NULL,body BLOB NOT NULL,pin BLOB NOT NULL,PRIMARY KEY(scope,copy_id))",
                "CREATE TABLE IF NOT EXISTS syncv2_recovery_objects(scope TEXT NOT NULL,oid TEXT NOT NULL,body BLOB NOT NULL,PRIMARY KEY(scope,oid))",
                "CREATE TABLE IF NOT EXISTS syncv2_p5_bindings(scope TEXT NOT NULL,entity_key TEXT NOT NULL,body BLOB NOT NULL,PRIMARY KEY(scope,entity_key))",
                "CREATE TABLE IF NOT EXISTS syncv2_cloud_rows(scope TEXT NOT NULL,entity_key TEXT NOT NULL,body BLOB NOT NULL,PRIMARY KEY(scope,entity_key))",
                "CREATE TABLE IF NOT EXISTS syncv2_cloud_objects(scope TEXT NOT NULL,oid TEXT NOT NULL,body BLOB NOT NULL,PRIMARY KEY(scope,oid))",
                "CREATE TABLE IF NOT EXISTS syncv2_wire_candidates(scope TEXT NOT NULL,entity_key TEXT NOT NULL,body BLOB NOT NULL,PRIMARY KEY(scope,entity_key))",
                "CREATE TABLE IF NOT EXISTS syncv2_control(id INTEGER PRIMARY KEY CHECK(id=1),enabled INTEGER NOT NULL,library_id TEXT NOT NULL,active_scope TEXT,failure_code TEXT)",
                "CREATE TABLE IF NOT EXISTS syncv2_state(scope TEXT PRIMARY KEY NOT NULL,state_version TEXT NOT NULL,local_revision TEXT NOT NULL,body BLOB NOT NULL,identity BLOB NOT NULL,descriptor_pin TEXT NOT NULL,account_id TEXT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS syncv2_identity_map(library_id TEXT NOT NULL,repo_id TEXT NOT NULL,kind TEXT NOT NULL,logical_id TEXT NOT NULL,entity_key TEXT NOT NULL,mapping_version INTEGER NOT NULL CHECK(mapping_version=2),PRIMARY KEY(library_id,repo_id,kind,logical_id),UNIQUE(library_id,repo_id,entity_key))",
                "CREATE TABLE IF NOT EXISTS syncv2_outbox(seq INTEGER PRIMARY KEY AUTOINCREMENT,scope TEXT NOT NULL,revision TEXT NOT NULL,table_name TEXT NOT NULL,logical_id TEXT NOT NULL,operation TEXT NOT NULL,base_state BLOB NOT NULL)",
                "CREATE INDEX IF NOT EXISTS syncv2_outbox_scope_seq ON syncv2_outbox(scope,seq)",
                "CREATE TABLE IF NOT EXISTS syncv2_candidates(scope TEXT NOT NULL,entity_key TEXT NOT NULL,kind TEXT NOT NULL,logical_id TEXT NOT NULL,revision TEXT NOT NULL,meta BLOB NOT NULL,state BLOB NOT NULL,source_hash TEXT NOT NULL,PRIMARY KEY(scope,entity_key))",
                "CREATE TABLE IF NOT EXISTS syncv2_tree_nodes(scope TEXT NOT NULL,prefix TEXT NOT NULL,body TEXT NOT NULL,hash TEXT NOT NULL,PRIMARY KEY(scope,prefix))",
                "CREATE TABLE IF NOT EXISTS syncv2_provenance(scope TEXT NOT NULL,entity_key TEXT NOT NULL,body BLOB NOT NULL,PRIMARY KEY(scope,entity_key))",
                "CREATE TABLE IF NOT EXISTS syncv2_edit_base(scope TEXT NOT NULL,entity_key TEXT NOT NULL,account_base BLOB NOT NULL,meta BLOB NOT NULL,state BLOB NOT NULL,PRIMARY KEY(scope,entity_key))",
                "CREATE TABLE IF NOT EXISTS syncv2_change_log(scope TEXT NOT NULL,revision TEXT NOT NULL,entity_key TEXT NOT NULL,PRIMARY KEY(scope,revision,entity_key))",
                "CREATE TABLE IF NOT EXISTS syncv2_scan(scope TEXT NOT NULL,domain TEXT NOT NULL,binding TEXT NOT NULL,r0 TEXT NOT NULL,r1 TEXT,page_key TEXT NOT NULL,tail_seq INTEGER NOT NULL,complete INTEGER NOT NULL,PRIMARY KEY(scope,domain))",
                "CREATE TABLE IF NOT EXISTS syncv2_legacy_inbox(scope TEXT NOT NULL,peer TEXT NOT NULL,version INTEGER NOT NULL,cursor TEXT NOT NULL,arrival INTEGER NOT NULL,body_hash TEXT NOT NULL,body BLOB NOT NULL,route TEXT NOT NULL,applied INTEGER NOT NULL,PRIMARY KEY(scope,peer,version,cursor),UNIQUE(scope,peer,version,arrival))"
            ).forEach(db::execSQL)
            db.execSQL("INSERT OR IGNORE INTO syncv2_control(id,enabled,library_id,active_scope,failure_code) VALUES(1,0,?,NULL,NULL)", arrayOf(SyncV2Codec.hex(SyncV2Codec.random(16))))
            for((column,type)in listOf("witness_epoch" to "TEXT NOT NULL DEFAULT ''","witness_coverage" to "TEXT NOT NULL DEFAULT ''"))
                if(!hasColumn(db,"syncv2_control",column))db.execSQL("ALTER TABLE syncv2_control ADD COLUMN $column $type")
            if(!hasColumn(db,"syncv2_scan","business_stamp"))db.execSQL("ALTER TABLE syncv2_scan ADD COLUMN business_stamp TEXT NOT NULL DEFAULT ''")
            beforeBusinessOpen(db)
            SyncV2BusinessWitness.install(db)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun semanticBinding(db: SupportSQLiteDatabase, tables: List<String>): String {
        val placeholders = tables.joinToString(",") { "?" }
        return db.query("SELECT type,name,sql FROM sqlite_master WHERE tbl_name IN ($placeholders) AND sql IS NOT NULL ORDER BY type,name", tables.toTypedArray()).use { c ->
            val rows = org.json.JSONArray()
            while(c.moveToNext()) rows.put(org.json.JSONArray().put(c.getString(0)).put(c.getString(1)).put(c.getString(2)))
            SyncV2Codec.hash(rows)
        }
    }
}
