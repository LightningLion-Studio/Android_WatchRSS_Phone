package com.lightningstudio.watchrss.phone.connection.bluetooth

import androidx.room.Room
import com.lightningstudio.watchrss.phone.data.db.PhoneCompanionDatabase
import com.lightningstudio.watchrss.phone.data.note.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class NoteCommitRecoveryProgressTest {
    private val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), PhoneCompanionDatabase::class.java)
        .allowMainThreadQueries().build()
    private val notes = NoteRepository(db.noteDao(), "phone", now = { 100 })
    @After fun close() = db.close()

    private suspend fun conflictingRemote(): NoteEntity {
        val base = notes.save("conflict", "title", "base")
        notes.save(base.noteId, "title", "local")
        return base.copy(markdown = "remote", contentHash = MarkdownNoteCodec.sha256("remote"),
            updatedAt = 200, modifiedBy = "watch")
    }

    @Test fun noteInsertAndMetadataCommitCountButIdenticalOrStaleRowsDoNot() = runBlocking<Unit> {
        val remote = notes.save("seed", "title", "body").copy(noteId = "remote", modifiedBy = "watch")
        assertEquals(1, notes.applyRemoteWithChanges(remote, "watch").changedNotes)
        assertEquals(remote, notes.note(remote.noteId))
        assertEquals(0, notes.applyRemoteWithChanges(remote, "watch").changedNotes)
        val updated = remote.copy(title = "new title", updatedAt = 200)
        assertEquals(1, notes.applyRemoteWithChanges(updated, "watch").changedNotes)
        assertEquals(0, notes.applyRemoteWithChanges(remote, "watch").changedNotes)
        assertEquals(updated, notes.note(remote.noteId))
    }

    @Test fun onlyNewConflictRowsCountIncludingAfterResolution() = runBlocking<Unit> {
        val remote = conflictingRemote()
        val first = notes.applyRemoteWithChanges(remote, "watch")
        assertTrue(first.mergeResult is MarkdownMergeResult.Conflict)
        assertEquals(0, first.changedNotes)
        assertEquals(1, first.addedConflicts)
        val conflict = notes.observeConflicts().first().single()
        assertEquals(0, notes.applyRemoteWithChanges(remote, "watch").addedConflicts)
        db.noteDao().resolveConflict(conflict.conflictId, 300)
        assertEquals(0, notes.applyRemoteWithChanges(remote, "watch").addedConflicts)
        assertTrue(notes.observeConflicts().first().isEmpty())
        assertEquals("local", notes.note(remote.noteId)!!.markdown)
    }

    @Test fun newConflictCommitsRenewFourReadyIntervalsButReplaysExhaustTheBudget() = runBlocking<Unit> {
        val remote = conflictingRemote()
        val progress = LibraryReconnectProgress()
        var last = remote
        for (generation in 1L..4L) {
            progress.ready()
            val body = "remote-$generation"
            last = remote.copy(markdown = body, contentHash = MarkdownNoteCodec.sha256(body))
            val committed = notes.applyRemoteWithChanges(last, "watch")
            assertEquals(1, committed.addedConflicts)
            if (committed.changedNotes + committed.addedConflicts > 0) progress.confirmed()
            progress.disconnected()
            assertEquals(0, progress.consecutive)
        }
        assertEquals(4, notes.observeConflicts().first().size)
        for (generation in 5L..7L) {
            progress.ready()
            val replay = notes.applyRemoteWithChanges(last, "watch")
            assertEquals(0, replay.changedNotes + replay.addedConflicts)
            if (replay.changedNotes + replay.addedConflicts > 0) progress.confirmed()
            val failure = runCatching { progress.disconnected() }.exceptionOrNull()
            if (generation == 7L) assertTrue(failure is LibraryNoProgressLimit) else assertNull(failure)
        }
        assertEquals(4, notes.observeConflicts().first().size)
        assertEquals(3, progress.consecutive)
    }
}
