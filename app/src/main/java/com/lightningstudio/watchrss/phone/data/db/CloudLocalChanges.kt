package com.lightningstudio.watchrss.phone.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.UUID

/** Capture every producer input in the writer transaction, including DAO/BLE/restore note writes.
 * These log rows do not claim a Bluetooth article/source edit. They only advance localMaxSeq.
 */
internal class CloudLocalChanges(private val database:PhoneCompanionDatabase) {
    private var schemaCookie=-1
    private val db get()=database.openHelper.writableDatabase
    @Synchronized fun schemaIdentity():String {
        val cookie=db.scalar("PRAGMA schema_version")
        if(cookie!=schemaCookie)install()
        return db.query("SELECT identity FROM cloud_local_change_identity WHERE id=1").use {
            check(it.moveToFirst());it.getString(0)+":"+db.version+":"+schemaCookie
        }
    }
    fun hasPendingProvenance():Boolean = db.query("SELECT EXISTS(SELECT 1 FROM cloud_local_pending_provenance)").use { it.moveToFirst();it.getInt(0)!=0 }
    private fun SupportSQLiteDatabase.scalar(sql:String)=query(sql).use{it.moveToFirst();it.getInt(0)}
    private fun install() {
        installCloudLocalChanges(db)
        schemaCookie=db.scalar("PRAGMA schema_version")
    }
}

/** Install before the first writer on a fresh database, including before the first cloud pass. */
internal fun installCloudLocalChanges(db:SupportSQLiteDatabase) {
        db.beginTransaction()
        try {
            db.execSQL("CREATE TABLE IF NOT EXISTS cloud_local_change_identity(id INTEGER PRIMARY KEY,identity TEXT NOT NULL)")
            db.execSQL("INSERT OR IGNORE INTO cloud_local_change_identity VALUES(1,?)",arrayOf(UUID.randomUUID().toString()))
            var rebuildPending=db.query("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='cloud_local_pending_provenance'").use { it.moveToFirst();it.getInt(0)==0 }
            db.execSQL("CREATE TABLE IF NOT EXISTS cloud_local_pending_provenance(articleId TEXT PRIMARY KEY NOT NULL)")
            val tables=listOf("phone_articles","phone_rss_sources","phone_saved_items","notes","note_folders","note_assets","note_conflicts",
                "reader_presets","reader_font_assets","reader_background_assets","reader_deletions","app_meta",
                "imported_books","imported_book_chapters","imported_book_reading","book_reading_versions",
                "article_register_provenance","article_register_originals","article_register_versions")
            val existing=db.query("SELECT name,sql FROM sqlite_master WHERE type='trigger' AND name LIKE 'cloud_local_change_%'").use { c ->
                buildMap { while(c.moveToNext())put(c.getString(0),c.getString(1)) }
            }
            for(table in tables) {
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
                    val sql="CREATE TRIGGER $name AFTER $op ON `$table`$guard BEGIN $pending INSERT INTO sync_change_log(kind,entityId,changedAt,originDeviceId,reason,createdAt) VALUES('cloud-change','$table',0,'','cloud-local-change-v1',0); END"
                    if(existing[name]!=sql) { db.execSQL("DROP TRIGGER IF EXISTS $name");db.execSQL(sql);rebuildPending=true }
                }
            }
            if(rebuildPending) {
                db.execSQL("DELETE FROM cloud_local_pending_provenance")
                db.execSQL("INSERT INTO cloud_local_pending_provenance SELECT articleId FROM article_register_provenance WHERE pending IS NOT NULL")
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
}
