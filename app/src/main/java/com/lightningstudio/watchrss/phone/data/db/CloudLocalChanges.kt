package com.lightningstudio.watchrss.phone.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** Capture every producer input in the writer transaction, including DAO/BLE/restore note writes.
 * The compact AUTOINCREMENT high-water mark is independent of all Bluetooth history.
 * This auxiliary table is deliberately outside Room clearAllTables and WRSS business exports.
 */
internal class CloudLocalChanges(private val database:PhoneCompanionDatabase) {
    private var schemaCookie=-1
    private val db get()=database.openHelper.writableDatabase
    @Synchronized fun schemaIdentity():String {
        val cookie=db.scalar("PRAGMA schema_version")
        if(cookie!=schemaCookie)install()
        return db.query("SELECT identity FROM cloud_local_change_identity WHERE id=1").use {
            check(it.moveToFirst());"cloud-counter-v2:"+it.getString(0)+":"+db.version+":"+schemaCookie
        }
    }
    fun sequence():Long {
        schemaIdentity() // also installs on databases opened without the production callback
        return db.query("SELECT seq FROM sqlite_sequence WHERE name='cloud_local_change_counter'").use {
            check(it.moveToFirst()) { "cloud_change_counter_missing" };it.getLong(0)
        }
    }
    fun observeSequence():Flow<Long> = database.invalidationTracker.createFlow(*CLOUD_CHANGE_TABLES.toTypedArray())
        .map { sequence() }.distinctUntilChanged().flowOn(Dispatchers.IO)
    fun observeSchedulingSequence(): Flow<CloudSchedulingSequence> =
        database.invalidationTracker.createFlow(*CLOUD_CHANGE_TABLES.toTypedArray()).map {
            schemaIdentity()
            db.query("""SELECT seq, (SELECT seq FROM cloud_local_non_cursor WHERE id=1)
                FROM sqlite_sequence WHERE name='cloud_local_change_counter'""").use {
                check(it.moveToFirst())
                CloudSchedulingSequence(it.getLong(0), it.getLong(1))
            }
        }.distinctUntilChanged().flowOn(Dispatchers.IO)
    fun hasPendingProvenance():Boolean = db.query("SELECT EXISTS(SELECT 1 FROM cloud_local_pending_provenance)").use { it.moveToFirst();it.getInt(0)!=0 }
    private fun SupportSQLiteDatabase.scalar(sql:String)=query(sql).use{it.moveToFirst();it.getInt(0)}
    private fun install() {
        installCloudLocalChanges(db)
        schemaCookie=db.scalar("PRAGMA schema_version")
    }
}

internal data class CloudSchedulingSequence(val sequence: Long, val lastNonCursor: Long)

internal val CLOUD_CHANGE_TABLES=listOf("phone_articles","phone_rss_sources","phone_saved_items","notes","note_folders","note_assets","note_conflicts",
    "reader_presets","reader_font_assets","reader_background_assets","reader_deletions","app_meta",
    "imported_books","imported_book_chapters","imported_book_reading","book_reading_versions",
    "article_register_provenance","article_register_originals","article_register_versions")

/** Install before the first writer on a fresh database, including before the first cloud pass. */
internal fun installCloudLocalChanges(db:SupportSQLiteDatabase) {
        db.beginTransaction()
        try {
            db.execSQL("CREATE TABLE IF NOT EXISTS cloud_local_change_identity(id INTEGER PRIMARY KEY,identity TEXT NOT NULL)")
            db.execSQL("INSERT OR IGNORE INTO cloud_local_change_identity VALUES(1,?)",arrayOf(UUID.randomUUID().toString()))
            db.execSQL("CREATE TABLE IF NOT EXISTS cloud_local_change_counter(seq INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)")
            // Start above every legacy allocated sequence, even if history rows were deleted.
            // Only initialization allocates an explicit value; normal mutations always use NULL.
            val missingSequence=db.query("SELECT NOT EXISTS(SELECT 1 FROM sqlite_sequence WHERE name='cloud_local_change_counter')").use{it.moveToFirst();it.getInt(0)!=0}
            db.execSQL("INSERT INTO cloud_local_change_counter(seq) SELECT MAX(COALESCE((SELECT seq FROM sqlite_sequence WHERE name='sync_change_log'),0),COALESCE((SELECT MAX(seq) FROM cloud_local_change_counter),0))+1 WHERE NOT EXISTS(SELECT 1 FROM sqlite_sequence WHERE name='cloud_local_change_counter')")
            db.execSQL("CREATE TABLE IF NOT EXISTS cloud_local_non_cursor(id INTEGER PRIMARY KEY,seq INTEGER NOT NULL)")
            db.execSQL("INSERT OR IGNORE INTO cloud_local_non_cursor SELECT 1,seq FROM sqlite_sequence WHERE name='cloud_local_change_counter'")
            db.execSQL("CREATE TABLE IF NOT EXISTS cloud_local_dirty(tbl TEXT NOT NULL,key TEXT NOT NULL,seq INTEGER NOT NULL,PRIMARY KEY(tbl,key))")
            db.execSQL("CREATE INDEX IF NOT EXISTS cloud_local_dirty_seq ON cloud_local_dirty(seq,tbl,key)")
            db.execSQL("CREATE TABLE IF NOT EXISTS cloud_local_dirty_floor(id INTEGER PRIMARY KEY,seq INTEGER NOT NULL)")
            db.execSQL("INSERT OR IGNORE INTO cloud_local_dirty_floor SELECT 1,seq FROM sqlite_sequence WHERE name='cloud_local_change_counter'")
            db.execSQL("CREATE TABLE IF NOT EXISTS note_asset_ref_dirty(noteId TEXT PRIMARY KEY NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS note_asset_refs(noteId TEXT NOT NULL,ownerKey TEXT NOT NULL,storageKey TEXT NOT NULL,PRIMARY KEY(noteId,ownerKey,storageKey))")
            db.execSQL("CREATE INDEX IF NOT EXISTS note_asset_refs_storage ON note_asset_refs(storageKey,ownerKey)")
            db.execSQL("CREATE TABLE IF NOT EXISTS note_asset_ref_source(id INTEGER PRIMARY KEY,identity TEXT NOT NULL)")
            var rebuildPending=db.query("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='cloud_local_pending_provenance'").use { it.moveToFirst();it.getInt(0)==0 }
            db.execSQL("CREATE TABLE IF NOT EXISTS cloud_local_pending_provenance(articleId TEXT PRIMARY KEY NOT NULL)")
            val existing=db.query("SELECT name,sql FROM sqlite_master WHERE type='trigger' AND name LIKE 'cloud_local_change_%'").use { c ->
                buildMap { while(c.moveToNext())put(c.getString(0),c.getString(1)) }
            }
            for(table in CLOUD_CHANGE_TABLES) {
                val columns=db.query("PRAGMA table_info(`$table`)").use { c -> buildList { while(c.moveToNext())add(c.getString(1)) } }
                check(columns.isNotEmpty()) { "cloud_change_counter_missing_table:$table" }
                for(op in listOf("INSERT","UPDATE","DELETE")) {
                    val name="cloud_local_change_${table}_${op.lowercase()}"
                    val changes=columns.filterNot { table=="phone_articles" && it in setOf("syncBodyHash","syncBodyByteCount","syncChunkSize","syncChunkHashesJson","syncMetadataHash") }
                    val guard=if(op=="UPDATE")" WHEN "+changes.joinToString(" OR "){"OLD.`$it` IS NOT NEW.`$it`"} else ""
                    val pending=if(table=="article_register_provenance") {
                        val alias=if(op=="DELETE")"OLD" else "NEW"
                        "DELETE FROM cloud_local_pending_provenance WHERE articleId=$alias.articleId;"+
                            (if(op=="UPDATE")"DELETE FROM cloud_local_pending_provenance WHERE articleId=OLD.articleId;" else "")+
                            (if(op=="DELETE")"" else "INSERT OR IGNORE INTO cloud_local_pending_provenance SELECT NEW.articleId WHERE NEW.pending IS NOT NULL;")
                    } else ""
                    val cursorCondition = if (op == "UPDATE" && table == "phone_articles") {
                        val cursor = setOf("readingProgress", "readingPositionBytes", "readingPositionContentHash", "readingPositionChangedAt", "isRead")
                        val others = changes.filterNot(cursor::contains).joinToString(" AND ") { "OLD.`$it` IS NEW.`$it`" }
                        // Autosave may mark an article read. An explicit read-state write has no cursor change.
                        " WHERE NOT (($others) AND (OLD.readingProgress IS NOT NEW.readingProgress OR " +
                            "OLD.readingPositionBytes IS NOT NEW.readingPositionBytes OR " +
                            "OLD.readingPositionContentHash IS NOT NEW.readingPositionContentHash))"
                    } else ""
                    val nonCursor = "UPDATE cloud_local_non_cursor SET seq=last_insert_rowid()$cursorCondition;"
                    val deleted = op == "DELETE"
                    val dirty = cloudDirtyKey(table, if (deleted) "OLD" else "NEW") +
                        if (op == "UPDATE") cloudDirtyKey(table, "OLD") else ""
                    val noteRefs = if (table in setOf("notes", "note_conflicts")) {
                        val aliases = if (op == "UPDATE") listOf("OLD", "NEW") else listOf(if (deleted) "OLD" else "NEW")
                        val fields = if (table == "notes") listOf("noteId", "deleted", "markdown", "baseMarkdown")
                            else listOf("noteId", "baseMarkdown", "localMarkdown", "remoteMarkdown")
                        val refGuard = if (op == "UPDATE") " WHERE " +
                            fields.joinToString(" OR ") { "OLD.`$it` IS NOT NEW.`$it`" } else ""
                        aliases.joinToString("") { alias ->
                            "INSERT INTO note_asset_ref_dirty SELECT $alias.noteId$refGuard ON CONFLICT(noteId) DO NOTHING;"
                        }
                    } else ""
                    val sql="CREATE TRIGGER $name AFTER $op ON `$table`$guard BEGIN $pending INSERT INTO cloud_local_change_counter(seq) VALUES(NULL); $nonCursor DELETE FROM cloud_local_change_counter WHERE seq < last_insert_rowid(); $dirty $noteRefs END"
                    if(existing[name]!=sql) { db.execSQL("DROP TRIGGER IF EXISTS $name");db.execSQL(sql);rebuildPending=true }
                }
            }
            if(rebuildPending || missingSequence) {
                // A repaired/missing witness cannot authenticate an older acknowledged prefix.
                // Keep its allocated sequence, but change the BUSINESS witness incarnation.
                db.execSQL("UPDATE cloud_local_change_identity SET identity=? WHERE id=1",arrayOf(UUID.randomUUID().toString()))
                db.execSQL("DELETE FROM cloud_local_pending_provenance")
                db.execSQL("INSERT INTO cloud_local_pending_provenance SELECT articleId FROM article_register_provenance WHERE pending IS NOT NULL")
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
}

/** Read the witness sequence, rather than last_insert_rowid(), across multiple key writes. */
internal fun cloudDirtyKey(table: String, alias: String): String {
    val (domain, key) = when (table) {
        "phone_articles", "article_register_provenance" -> "phone_articles" to "$alias.articleId"
        "phone_rss_sources" -> table to "$alias.url"
        "imported_books", "imported_book_chapters" -> "imported_books" to "$alias.bookId"
        "imported_book_reading" -> table to "$alias.bookId || ':' || $alias.chapterIndex"
        "notes", "note_conflicts" -> "notes" to "$alias.noteId"
        "note_folders" -> table to "$alias.folderId"
        "note_assets" -> table to "$alias.storageKey"
        else -> return ""
    }
    return "INSERT INTO cloud_local_dirty(tbl,key,seq) VALUES('$domain',$key," +
        "(SELECT seq FROM cloud_local_change_counter)) ON CONFLICT(tbl,key) DO UPDATE SET seq=excluded.seq;"
}
