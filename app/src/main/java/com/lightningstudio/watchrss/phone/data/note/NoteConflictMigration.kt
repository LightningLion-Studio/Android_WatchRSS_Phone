package com.lightningstudio.watchrss.phone.data.note

import androidx.sqlite.db.SupportSQLiteDatabase

/** Runs on every database open, without requiring a sync download. */
fun normalizeStoredNoteConflicts(db: SupportSQLiteDatabase) {
    db.beginTransaction()
    try {
        val rows = db.query("SELECT conflictId,noteId,baseMarkdown,localMarkdown,remoteMarkdown,remoteDeviceId,createdAt,resolvedAt FROM note_conflicts").use { c ->
            buildList { while (c.moveToNext()) add(NoteConflictEntity(c.getString(0), c.getString(1), c.getString(2),
                c.getString(3), c.getString(4), c.getString(5), c.getLong(6), c.getLong(7))) }
        }
        val normalized = normalizeNoteConflicts(rows)
        if (normalized.size != rows.size) {
            for (row in normalized) db.execSQL("UPDATE note_conflicts SET createdAt=?,resolvedAt=? WHERE conflictId=?",
                arrayOf(row.createdAt, row.resolvedAt, row.conflictId))
            val kept = normalized.map { it.conflictId }.toSet()
            for (row in rows) if (row.conflictId !in kept)
                db.execSQL("DELETE FROM note_conflicts WHERE conflictId=?", arrayOf(row.conflictId))
        }
        db.setTransactionSuccessful()
    } finally { db.endTransaction() }
}
