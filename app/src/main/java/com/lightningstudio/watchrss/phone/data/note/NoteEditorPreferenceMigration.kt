package com.lightningstudio.watchrss.phone.data.note

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/** Local-only additive schema shared by the three Room database versions. */
internal object NoteEditorPreferenceMigration : RoomDatabase.Callback() {
    override fun onOpen(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS note_editor_preferences (
                noteId TEXT NOT NULL PRIMARY KEY,
                installationId TEXT NOT NULL,
                sourceMode INTEGER NOT NULL CHECK(sourceMode IN (0, 1))
            )"""
        )
    }
}
