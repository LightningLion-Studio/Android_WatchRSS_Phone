package com.lightningstudio.watchrss.phone.data.note

import androidx.room.Room
import com.lightningstudio.watchrss.phone.NoteEditorDraft
import com.lightningstudio.watchrss.phone.connection.bluetooth.NoteSyncPayload
import com.lightningstudio.watchrss.phone.data.db.PhoneCompanionDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoteEditorPreferenceMigrationTest {
    private val context = RuntimeEnvironment.getApplication()
    private val noteId = "existing-note"
    private val body = "6. prefix\nplain"

    @Test fun additiveMigrationLeavesExistingNoteAndSchemaVersionIntact() = runBlocking {
        val name = "editor-preference-migration.db"
        context.deleteDatabase(name)
        try {
            val originalBuilder = Room.databaseBuilder(context, PhoneCompanionDatabase::class.java, name)
            val (original, version) = originalBuilder.build().useDatabase { db ->
                val repo = NoteRepository(db.noteDao(), "phone")
                val saved = repo.save(noteId, "Title", body)
                val sqlite = db.openHelper.readableDatabase
                sqlite.query("SELECT name FROM sqlite_master WHERE name = 'note_editor_preferences'").use {
                    assertFalse(it.moveToFirst())
                }
                saved to sqlite.version
            }

            fun migratedDatabase() = Room.databaseBuilder(context, PhoneCompanionDatabase::class.java, name)
                .addCallback(NoteEditorPreferenceMigration).build()
            migratedDatabase().useDatabase { db ->
                val dao = db.noteDao()
                val repo = NoteRepository(dao, "phone")
                assertEquals(version, db.openHelper.readableDatabase.version)
                assertEquals(original, dao.note(noteId))
                assertNull(repo.editorSourceMode(noteId))
                val draft = NoteEditorDraft(original.markdown, repo.editorSourceMode(noteId))
                assertTrue(draft.sourceMode)
                draft.editSource("plain")
                val saved = repo.save(noteId, original.title, draft.storageMarkup(),
                    editorSourceMode = draft.sourceMode)
                assertEquals("plain", saved.markdown)
                assertEquals(MarkdownNoteCodec.sha256("plain"), saved.contentHash)
            }

            migratedDatabase().useDatabase { db ->
                val dao = db.noteDao()
                val repo = NoteRepository(dao, "phone")
                val saved = requireNotNull(dao.note(noteId))
                val reopened = NoteEditorDraft(saved.markdown, repo.editorSourceMode(noteId))
                assertTrue(reopened.sourceMode)
                assertEquals("plain", reopened.sourceText)

                // Android can transfer the database, but excludes the installation identity.
                val restored = NoteRepository(dao, "phone", editorPreferenceOwner = "another-installation")
                assertNull(restored.editorSourceMode(noteId))
                assertFalse(NoteEditorDraft(saved.markdown, restored.editorSourceMode(noteId)).sourceMode)

                val peer = NoteSyncPayload.decodeNotes(NoteSyncPayload.manifest("phone", listOf(saved))).single()
                val cloud = NoteCloudStateCodec.decode(NoteCloudStateCodec.encode(listOf(peer))).single()
                assertEquals("plain", cloud.markdown)
                assertEquals(saved.contentHash, cloud.contentHash)
                Room.inMemoryDatabaseBuilder(context, PhoneCompanionDatabase::class.java)
                    .addCallback(NoteEditorPreferenceMigration).build().useDatabase { other ->
                        val otherRepo = NoteRepository(other.noteDao(), "other")
                        otherRepo.applyRemote(cloud, "phone")
                        assertNull(otherRepo.editorSourceMode(noteId))
                        assertFalse(NoteEditorDraft(cloud.markdown, otherRepo.editorSourceMode(noteId)).sourceMode)
                    }

                // A synchronized replacement updates the note row while preserving the local preference.
                dao.upsertNotes(listOf(saved.copy(title = "New title")))
                assertEquals(true, repo.editorSourceMode(noteId))
                repo.save("rich-note", "Title", "ordinary", editorSourceMode = false)
                assertEquals(false, repo.editorSourceMode("rich-note"))
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test fun preferenceAndEditorSaveCommitTogether() = runBlocking {
        Room.inMemoryDatabaseBuilder(context, PhoneCompanionDatabase::class.java)
            .addCallback(NoteEditorPreferenceMigration).build().useDatabase { db ->
                val dao = db.noteDao()
                val repo = NoteRepository(dao, "phone")
                val original = repo.save(noteId, "Before", body, editorSourceMode = true)
                db.openHelper.writableDatabase.execSQL(
                    """CREATE TRIGGER fail_editor_write BEFORE INSERT ON note_editor_preferences
                        BEGIN SELECT RAISE(ABORT, 'test editor preference write failed'); END"""
                )
                val caught = runCatching {
                    repo.save(noteId, "After", "new body", editorSourceMode = false)
                }.exceptionOrNull()
                assertTrue(caught.toString(), caught?.message.orEmpty().contains("test editor preference write failed"))
                assertEquals(original, dao.note(noteId))
                assertEquals(true, repo.editorSourceMode(noteId))
            }
    }

    private suspend fun <T> PhoneCompanionDatabase.useDatabase(
        block: suspend (PhoneCompanionDatabase) -> T
    ): T = try {
        block(this)
    } finally {
        close()
    }
}
