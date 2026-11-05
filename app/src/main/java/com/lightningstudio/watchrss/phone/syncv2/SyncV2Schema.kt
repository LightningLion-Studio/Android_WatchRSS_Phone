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
    /** D64 decimal carry uses only one-digit casts, including above signed Long. */
    fun increment(column: String): String {
        val stem = "rtrim($column,'9')"
        return "CASE WHEN $stem='' THEN '1'||replace($column,'9','0') ELSE substr($stem,1,length($stem)-1)||CAST(CAST(substr($stem,-1) AS INTEGER)+1 AS TEXT)||replace(substr($column,length($stem)+1),'9','0') END"
    }
    fun onOpen(db: SupportSQLiteDatabase) {
        if (BuildConfig.SYNC_OBJECTS_V2_LOCAL_PROJECTION) install(db)
        else if (db.query("SELECT 1 FROM sqlite_master WHERE type='table' AND name='syncv2_control'").use { it.moveToFirst() }) {
            // Closing the projection gate is reversible; retain every projection/checkpoint byte.
            db.execSQL("UPDATE syncv2_control SET enabled=0 WHERE enabled!=0")
        }
    }
    fun install(db: SupportSQLiteDatabase) {
        db.beginTransaction()
        try {
            listOf(
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
            db.execSQL("INSERT OR IGNORE INTO syncv2_control VALUES(1,0,?,NULL,NULL)", arrayOf(SyncV2Codec.hex(SyncV2Codec.random(16))))
            val existing = db.query("SELECT name,sql FROM sqlite_master WHERE type='trigger' AND name LIKE 'syncv2_%'").use { c -> buildMap { while(c.moveToNext()) put(c.getString(0),c.getString(1)) } }
            for (domain in domains + listOf(Domain("imported_book_chapters", "book", "bookId"), Domain("note_conflicts", "note", "noteId"), Domain("article_register_provenance", "article", "articleId"))) {
                val columns = db.query("PRAGMA table_info(`${domain.table}`)").use { c -> buildList { while(c.moveToNext()) add(c.getString(1)) } }
                check(columns.isNotEmpty()) { "syncv2_missing_domain" }
                for (op in listOf("INSERT", "UPDATE", "DELETE")) {
                    val alias = if (op == "DELETE") "OLD" else "NEW"
                    fun key(prefix: String) = domain.key.split(" || ").joinToString(" || ") { if (it.startsWith("'")) it else "$prefix.$it" }
                    val guard = if(op == "UPDATE") " AND (" + columns.filterNot { it.startsWith("sync") }.joinToString(" OR ") { "OLD.`$it` IS NOT NEW.`$it`" } + ")" else ""
                    val name = "syncv2_${domain.table}_${op.lowercase()}"
                    val oldKey = if(op == "UPDATE") "INSERT INTO syncv2_outbox(scope,revision,table_name,logical_id,operation,base_state) SELECT scope,local_revision,'${domain.table}',${key("OLD")},'DELETE',body FROM syncv2_state WHERE scope=(SELECT active_scope FROM syncv2_control WHERE id=1) AND ${key("OLD")} IS NOT ${key("NEW")};" else ""
                    val sql = "CREATE TRIGGER $name AFTER $op ON `${domain.table}` WHEN (SELECT enabled FROM syncv2_control WHERE id=1)=1$guard BEGIN " +
                        "SELECT CASE WHEN EXISTS(SELECT 1 FROM syncv2_state WHERE (state_version='18446744073709551615' OR local_revision='18446744073709551615') AND scope=(SELECT active_scope FROM syncv2_control WHERE id=1)) THEN RAISE(ABORT,'counter_exhausted') END;" +
                        "UPDATE syncv2_state SET state_version=${increment("state_version")},local_revision=${increment("local_revision")} WHERE scope=(SELECT active_scope FROM syncv2_control WHERE id=1);" + oldKey +
                        "INSERT INTO syncv2_outbox(scope,revision,table_name,logical_id,operation,base_state) SELECT scope,local_revision,'${domain.table}',${key(alias)},'$op',body FROM syncv2_state WHERE scope=(SELECT active_scope FROM syncv2_control WHERE id=1); END"
                    if(existing[name] != sql) { db.execSQL("DROP TRIGGER IF EXISTS $name"); db.execSQL(sql) }
                }
            }
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
