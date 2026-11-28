package com.lightningstudio.watchrss.phone.syncv2

import androidx.sqlite.db.SupportSQLiteDatabase
import com.lightningstudio.watchrss.phone.data.db.installCloudLocalChanges

/** Derived index of references in live notes and retained conflict text. */
internal object SyncV2NoteAssetReferences {
    fun keys(db: SupportSQLiteDatabase): Set<String> =
        read(db, "SELECT DISTINCT storageKey FROM note_asset_refs")

    fun owners(db: SupportSQLiteDatabase, storageKey: String, liveOnly: Boolean = false): Set<String> {
        val live = if (liveOnly) " AND EXISTS(SELECT 1 FROM notes n WHERE n.noteId=r.noteId AND n.deleted=0)" else ""
        return read(db, "SELECT r.ownerKey FROM note_asset_refs r WHERE r.storageKey=?$live", arrayOf(storageKey))
    }

    private fun read(db: SupportSQLiteDatabase, select: String, args: Array<String> = emptyArray()): Set<String> {
        val installed = db.controlQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='note_asset_refs'").use {
            it.moveToFirst()
        }
        // A single snapshot checks both the index and the journal. Healthy point
        // reads never acquire the SQLite writer lock.
        fun indexed(): Set<String>? = db.controlQuery("""
            SELECT * FROM ($select)
            UNION ALL SELECT NULL WHERE NOT EXISTS (
                SELECT 1 FROM note_asset_ref_source r JOIN cloud_local_change_identity w
                    ON w.id=r.id AND w.identity=r.identity
                WHERE r.id=1 AND NOT EXISTS (SELECT 1 FROM note_asset_ref_dirty)
            )
            ORDER BY 1
        """.trimIndent(), args).use { rows ->
            buildSet {
                while (rows.moveToNext()) {
                    if (rows.isNull(0)) return@use null
                    add(rows.getString(0))
                }
            }
        }
        if (installed) indexed()?.let { return it }
        else installCloudLocalChanges(db)

        val ownsTransaction = !db.inTransaction()
        if (ownsTransaction) db.beginTransaction()
        try {
            val witness = db.controlQuery("SELECT identity FROM cloud_local_change_identity WHERE id=1").use {
                check(it.moveToFirst())
                it.getString(0)
            }
            val indexedWitness = db.controlQuery("SELECT identity FROM note_asset_ref_source WHERE id=1").use {
                if (it.moveToFirst()) it.getString(0) else null
            }
            if (indexedWitness != witness) {
                db.execSQL("DELETE FROM note_asset_refs")
                db.execSQL("""
                    INSERT INTO note_asset_ref_dirty SELECT noteId FROM notes
                    UNION SELECT noteId FROM note_conflicts WHERE 1 ON CONFLICT(noteId) DO NOTHING
                """.trimIndent())
                db.execSQL("INSERT OR REPLACE INTO note_asset_ref_source VALUES(1,?)", arrayOf(witness))
            }
            val refreshed = db.controlQuery("SELECT noteId FROM note_asset_ref_dirty ORDER BY noteId").use { dirty ->
                var any = false
                while (dirty.moveToNext()) {
                    refreshNote(db, dirty.getString(0))
                    any = true
                }
                any
            }
            if (refreshed) db.execSQL("DELETE FROM note_asset_ref_dirty")
            val result = checkNotNull(indexed())
            if (ownsTransaction) db.setTransactionSuccessful()
            return result
        } finally {
            if (ownsTransaction) db.endTransaction()
        }
    }

    private fun refreshNote(db: SupportSQLiteDatabase, noteId: String) {
        db.execSQL("DELETE FROM note_asset_refs WHERE noteId=?", arrayOf(noteId))
        fun add(ownerKey: String, text: String) {
            for (key in SyncV2BodyRepository.assetKeys(text)) {
                db.execSQL("INSERT OR IGNORE INTO note_asset_refs VALUES(?,?,?)", arrayOf(noteId, ownerKey, key))
            }
        }
        db.controlQuery("SELECT markdown,baseMarkdown FROM notes WHERE noteId=? AND deleted=0", arrayOf(noteId)).use { row ->
            if (row.moveToFirst()) for (i in 0..1) add("note:$noteId", row.getString(i))
        }
        db.controlQuery(
            "SELECT conflictId,baseMarkdown,localMarkdown,remoteMarkdown FROM note_conflicts WHERE noteId=?",
            arrayOf(noteId)
        ).use { rows ->
            while (rows.moveToNext()) for (i in 1..3) add("conflict:${rows.getString(0)}", rows.getString(i))
        }
    }
}
