package com.lightningstudio.watchrss.phone.data.note

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lightningstudio.watchrss.phone.data.db.PhoneCompanionDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Review acceptance assertions. Red assertions deliberately describe the required behavior. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class NoteMergeR2ReviewTest {
    private fun database() = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),
        PhoneCompanionDatabase::class.java).allowMainThreadQueries().build()
    private fun peer(a: NoteEntity, body: String, base: String, time: Long = 300) = a.copy(
        markdown=body, plainText=body, contentHash=MarkdownNoteCodec.sha256(body),
        baseMarkdown=base, baseContentHash=MarkdownNoteCodec.sha256(base), modifiedBy="peer", updatedAt=time)

    @Test fun RVM209_freshCycleConflictCanBeResolvedFromFreshWindow() = runBlocking {
        val db=database();try {
            val dao=db.noteDao();val repo=NoteRepository(dao,"phone"){100}
            repo.save("n","T","a");val b=repo.save("n","T","b")
            repo.applyRemote(peer(b,"a","b"),"peer")
            val conflict=dao.conflictsFor("n").single();val captured=dao.note("n")!!
            assertEquals("a",captured.markdown)
            val error=runCatching { repo.resolveConflict(conflict,"chosen",captured) }.exceptionOrNull()
            assertNull("RVM2-09: fresh cycle window reports notes_stale forever: $error",error)
            assertEquals("chosen",dao.note("n")!!.markdown)
            assertTrue(dao.conflictsFor("n").single().resolvedAt>0)
        } finally {db.close()}
    }

    @Test fun RVM210_newDescendantMayReusePreviouslyRejectedText() = runBlocking {
        val db=database();try {
            val dao=db.noteDao();val repo=NoteRepository(dao,"phone"){1000}
            val a=repo.save("n","T","a");repo.save("n","T","b")
            repo.applyRemote(peer(a,"c","z"),"peer")
            val chosen=repo.resolveConflict(dao.conflictsFor("n").single(),"resolved")
            repo.applyRemote(peer(chosen,"c","resolved",chosen.updatedAt+100),"peer")
            assertTrue("RVM2-10: new descendant silently discarded as old content replay",
                dao.note("n")!!.markdown=="c" || dao.conflictsFor("n").any {it.resolvedAt==0L})
        } finally {db.close()}
    }

    @Test fun RVM211_sameWriterRestoreSurvivesThirdWriterAndOldTombstone() = runBlocking {
        val db=database();try {
            val dao=db.noteDao();val repo=NoteRepository(dao,"phone"){100}
            val a=repo.save("n","T","a")
            val dead=a.copy(deleted=true,deletedAt=101,updatedAt=101,modifiedBy="peer")
            val restored=a.copy(updatedAt=102,modifiedBy="peer")
            val metadata=a.copy(updatedAt=103,modifiedBy="third",pinned=true)
            val outcomes=mutableListOf<Boolean>()
            for(rows in listOf(listOf(dead,restored,metadata),listOf(restored,metadata,dead))) {
                dao.clearNotes();for(r in rows)repo.applyRemote(r,r.modifiedBy)
                outcomes.add(dao.note("n")!!.deleted)
            }
            assertEquals("RVM2-11: the same delete, restore and later pin must converge",listOf(false,false),outcomes)
        } finally {db.close()}
    }

    @Test fun RVM212_conflictMarkerChangedAtCommitMustFailAtomically() = runBlocking {
        val db=database();try {
            val dao=db.noteDao();val repo=NoteRepository(dao,"phone"){1000}
            val a=repo.save("n","T","a");repo.save("n","T","b")
            repo.applyRemote(peer(a,"c","a"),"peer")
            val c=dao.conflictsFor("n").single();val captured=dao.note("n")!!
            // Real Room transaction, with a peer marker committed after the repository's
            // read checks but before commitNote enters its transaction. No product stubs.
            val race=object:NoteDao by dao {
                override suspend fun commitNote(noteId:String,expected:NoteEntity?,replacement:NoteEntity?,
                    conflict:NoteConflictEntity?,resolvedId:String?,resolvedAt:Long):Boolean {
                    dao.resolveConflict(c.conflictId,9999L)
                    return dao.commitNote(noteId,expected,replacement,conflict,resolvedId,resolvedAt)
                }
            }
            val error=runCatching {NoteRepository(race,"phone"){1000}.resolveConflict(c,"c",captured)}.exceptionOrNull()
            println("RVM2-12 actual body=${dao.note("n")!!.markdown} marker=${dao.conflictsFor("n").single().resolvedAt} error=${error?.message}")
            assertEquals("RVM2-12: conflict-row CAS is absent", "notes_stale",error?.message)
            assertEquals(captured,dao.note("n"));assertEquals(9999L,dao.conflictsFor("n").single().resolvedAt)
        } finally {db.close()}
    }

    @Test fun RVM214_upgradedLegacyDatabaseDoesNotExposeResolvedAliasAsOpen() = runBlocking {
        val db=database();try {
            val dao=db.noteDao();val repo=NoteRepository(dao,"phone"){1000}
            val a=repo.save("n","T","a");repo.save("n","T","b")
            repo.applyRemote(peer(a,"c","a"),"peer")
            val fresh=dao.conflictsFor("n").single()
            val old=MarkdownNoteCodec.sha256(listOf("n","a","b","c","peer").joinToString("\u0000"))
            dao.upsertConflicts(listOf(fresh.copy(conflictId=old)))
            dao.resolveConflict(fresh.conflictId,50L)
            // Database content left by the previous version, opened without any v2 download.
            assertTrue("RVM2-14: legacy UI reopened the already resolved new alias",
                repo.observeConflicts().first().isEmpty())
        } finally {db.close()}
    }

    @Test fun RVM215_freshConflictWindowCannotRestoreDeletedNote() = runBlocking {
        val db=database();try {
            val dao=db.noteDao();val repo=NoteRepository(dao,"phone"){1000}
            val a=repo.save("n","T","a");repo.save("n","T","b")
            repo.applyRemote(peer(a,"c","a"),"peer")
            val c=dao.conflictsFor("n").single();val deleted=repo.delete("n")
            val error=runCatching {repo.resolveConflict(c,"c",deleted)}.exceptionOrNull()
            println("RVM2-15 actual deleted=${dao.note("n")!!.deleted} body=${dao.note("n")!!.markdown} marker=${dao.conflictsFor("n").single().resolvedAt}")
            assertEquals("RVM2-15: deleted note must refuse conflict resolution","notes_stale",error?.message)
            assertEquals(deleted,dao.note("n"));assertEquals(0L,dao.conflictsFor("n").single().resolvedAt)
        } finally {db.close()}
    }

    @Test fun RVM213_controlRepeatedCycleAcceptsTheLatestVersionOnPhone() = runBlocking {
        val db=database();try {
            val dao=db.noteDao();val repo=NoteRepository(dao,"phone"){100}
            val a=repo.save("n","T","a")
            for(r in listOf(peer(a,"b","a",101),peer(a,"a","b",300),peer(a,"b","a",400),peer(a,"a","b",500)))
                repo.applyRemote(r,"peer")
            assertEquals("a",dao.note("n")!!.markdown);assertEquals(500L,dao.note("n")!!.updatedAt)
        } finally {db.close()}
    }
}
