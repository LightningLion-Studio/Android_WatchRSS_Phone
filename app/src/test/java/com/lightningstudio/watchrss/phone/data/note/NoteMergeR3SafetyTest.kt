package com.lightningstudio.watchrss.phone.data.note

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lightningstudio.watchrss.phone.data.db.PhoneCompanionDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class NoteMergeR3SafetyTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun database() = Room.inMemoryDatabaseBuilder(context,PhoneCompanionDatabase::class.java).allowMainThreadQueries().build()

    @Test fun receiptFailureRollsBackBusinessWritesAndRetryIsNotSuppressed() = runBlocking {
        val db=database()
        try {
            val dao=db.noteDao();val repo=NoteRepository(dao,"phone"){100}
            val a=repo.save("n","T","a")
            val b=a.copy(markdown="b",plainText="b",contentHash=MarkdownNoteCodec.sha256("b"),modifiedBy="peer")
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER receipt_failure BEFORE INSERT ON note_remote_receipts BEGIN SELECT RAISE(ABORT,'receipt_failure'); END")
            assertNotNull(runCatching{repo.applyRemote(b,"peer")}.exceptionOrNull())
            assertEquals(a,dao.note("n"));assertFalse(dao.hasRemoteReceipt(noteRemoteSnapshotHash(b,"peer")))
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER receipt_failure")
            repo.applyRemote(b,"peer")
            assertEquals("b",dao.note("n")!!.markdown);assertTrue(dao.hasRemoteReceipt(noteRemoteSnapshotHash(b,"peer")))
        } finally { db.close() }
    }

    @Test fun upgradeAndReopenNormalizeOldDatabaseWithoutRemoteTraffic() = runBlocking {
        val name="notes-r3-upgrade-test.db";context.deleteDatabase(name)
        fun open()=Room.databaseBuilder(context,PhoneCompanionDatabase::class.java,name).allowMainThreadQueries()
            .addMigrations(PhoneCompanionDatabase.MIGRATION_19_20,PhoneCompanionDatabase.MIGRATION_20_21)
            .addCallback(PhoneCompanionDatabase.ARTICLE_PROVENANCE_CALLBACK).build()
        try {
            open().let { db ->
                val dao=db.noteDao();val repo=NoteRepository(dao,"phone"){100}
                val a=repo.save("n","T","a");repo.save("n","T","b")
                repo.applyRemote(a.copy(markdown="c",modifiedBy="peer"),"peer")
                val c=dao.conflictsFor("n").single()
                val old=MarkdownNoteCodec.sha256(listOf("n","a","b","c","peer").joinToString("\u0000"))
                dao.upsertConflicts(listOf(c.copy(conflictId=old)))
                dao.resolveConflict(c.conflictId,50)
                db.openHelper.writableDatabase.execSQL("ALTER TABLE imported_books DROP COLUMN sourceUrl")
                db.openHelper.writableDatabase.execSQL("DROP TABLE note_remote_receipts")
                db.openHelper.writableDatabase.version=19
                db.close()
            }
            repeat(2) {
                val db=open()
                try {
                    val rows=db.noteDao().allConflicts()
                    assertEquals(1,rows.size);assertEquals(50L,rows.single().resolvedAt)
                    assertFalse(db.noteDao().hasRemoteReceipt("missing"))
                } finally { db.close() }
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun freshCaptureAfterFurtherEditingCanResolveButRemovedConflictCannot() = runBlocking {
        val db=database()
        try {
            val dao=db.noteDao();val repo=NoteRepository(dao,"phone"){100}
            val a=repo.save("n","T","a");repo.save("n","T","b")
            repo.applyRemote(a.copy(markdown="c",modifiedBy="peer"),"peer")
            val conflict=dao.conflictsFor("n").single();val current=repo.save("n","T","later")
            val race=object:NoteDao by dao {
                override suspend fun commitNote(noteId:String,expected:NoteEntity?,replacement:NoteEntity?,conflict:NoteConflictEntity?,resolvedId:String?,resolvedAt:Long,expectedConflicts:List<NoteConflictEntity>?):Boolean {
                    dao.clearConflicts()
                    return dao.commitNote(noteId,expected,replacement,conflict,resolvedId,resolvedAt,expectedConflicts)
                }
            }
            assertEquals("notes_stale",runCatching { NoteRepository(race,"phone"){100}.resolveConflict(conflict,"chosen",current) }.exceptionOrNull()?.message)
            assertEquals(current,dao.note("n"))
            dao.upsertConflicts(listOf(conflict))
            assertEquals("chosen",repo.resolveConflict(conflict,"chosen",current).markdown)
        } finally { db.close() }
    }
    @Test fun repeatedDeletionOfResolvedContentKeepsLiveNoteAndMonotonicMarker() = runBlocking {
        val db=database()
        try {
            val dao=db.noteDao();val repo=NoteRepository(dao,"phone"){100}
            val a=repo.save("n","T","a")
            val dead=a.copy(deleted=true,deletedAt=200,updatedAt=200,modifiedBy="peer")
            repo.applyRemote(dead,"peer")
            val conflict=dao.conflictsFor("n").single()
            val live=repo.resolveConflict(conflict,"a",dao.note("n"))
            val resolved=dao.conflictsFor("n").single()
            val again=dead.copy(deletedAt=300,updatedAt=300)
            repo.applyRemote(again,"peer")
            assertEquals(live,dao.note("n"));assertFalse(live.deleted)
            assertEquals(listOf(resolved),dao.conflictsFor("n"));assertTrue(resolved.resolvedAt>0)
            assertEquals(1,dao.allNotes().size)
            assertTrue(dao.hasRemoteReceipt(noteRemoteSnapshotHash(again,"peer")))
        } finally { db.close() }
    }

}
