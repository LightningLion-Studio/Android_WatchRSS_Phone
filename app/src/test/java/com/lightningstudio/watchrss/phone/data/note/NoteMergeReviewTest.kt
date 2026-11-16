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

/** Review acceptance assertions. Red assertions deliberately describe the required behavior. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class NoteMergeReviewTest {
    @Test fun RVM08_oldAncestorAfterARevertCannotBecomeTheDescendant() = runBlocking {
        val db = database()
        try {
            val dao = db.noteDao(); val repo = NoteRepository(dao, "phone") { 100 }
            repo.save("n", "T", "a")
            val b = repo.save("n", "T", "b")
            repo.applyRemote(peer(b, "a", "b"), "peer")
            assertEquals("a", dao.note("n")!!.markdown)
            repo.applyRemote(b, "phone")
            assertEquals("RVM-08: replay of a -> b reversed the later b -> a edit", "a", dao.note("n")!!.markdown)
        } finally { db.close() }
    }
    private fun database() = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(), PhoneCompanionDatabase::class.java
    ).allowMainThreadQueries().build()
    private fun peer(a: NoteEntity, body: String, base: String = a.baseMarkdown) = a.copy(
        markdown = body, plainText = body, contentHash = MarkdownNoteCodec.sha256(body),
        baseMarkdown = base, baseContentHash = MarkdownNoteCodec.sha256(base),
        modifiedBy = "peer", updatedAt = 300
    )

    @Test fun RVM01_resolvedDifferentBaseReplayDoesNotReopen() = runBlocking {
        val db = database()
        try {
            val dao = db.noteDao(); val repo = NoteRepository(dao, "phone") { 1000 }
            val a = repo.save("n", "T", "a"); repo.save("n", "T", "b")
            val remote = peer(a, "c", "z")
            repo.applyRemote(remote, "peer")
            repo.resolveConflict(dao.conflictsFor("n").single(), "resolved")
            repo.applyRemote(remote, "peer")
            assertTrue("RVM-01: identical remote reopened a resolved incident", dao.conflictsFor("n").all { it.resolvedAt > 0 })
        } finally { db.close() }
    }

    @Test fun RVM02_newDeleteIsNotAnOldBodyReplay() = runBlocking {
        val db = database()
        try {
            val dao = db.noteDao(); val repo = NoteRepository(dao, "phone") { 1000 }
            val a = repo.save("n", "T", "a"); repo.save("n", "T", "b")
            val remote = peer(a, "c")
            repo.applyRemote(remote, "peer")
            repo.resolveConflict(dao.conflictsFor("n").single(), "c")
            repo.applyRemote(remote.copy(deleted = true, deletedAt = 5000, updatedAt = 5000), "peer")
            assertTrue("RVM-02: peer deleted the chosen body; delete was silently ignored",
                dao.note("n")!!.deleted || dao.conflictsFor("n").any { it.resolvedAt == 0L })
        } finally { db.close() }
    }

    @Test fun RVM03_capturedResolutionCannotEraseLaterLocalEdit() = runBlocking {
        val db = database()
        try {
            val dao = db.noteDao(); val repo = NoteRepository(dao, "phone") { 1000 }
            val a = repo.save("n", "T", "a"); repo.save("n", "T", "b")
            repo.applyRemote(peer(a, "c"), "peer")
            val captured = dao.conflictsFor("n").single()
            repo.save("n", "T", "new unsent edit")
            val failure = runCatching { repo.resolveConflict(captured, captured.localMarkdown) }.exceptionOrNull()
            assertEquals("RVM-03: stale conflict dialog overwrote a newer local save", "new unsent edit", dao.note("n")!!.markdown)
            assertEquals("notes_stale", failure?.message)
        } finally { db.close() }
    }

    @Test fun RVM04_unchangedLiveSnapshotCannotResurrectTombstone() = runBlocking {
        val db = database()
        try {
            val dao = db.noteDao(); val repo = NoteRepository(dao, "a-device") { 100 }
            val a = repo.save("n", "T", "a"); val dead = repo.delete("n")
            repo.applyRemote(a.copy(updatedAt = dead.updatedAt + 1, modifiedBy = "z-compose", pinned = true), "z-compose")
            assertTrue("RVM-04: metadata-only live peer resurrected an unchanged deleted body", dao.note("n")!!.deleted)
        } finally { db.close() }
    }
    @Test fun RVM03_capturedNoteRejectsAnABAReturnToTheSameBody() = runBlocking {
        val db = database()
        try {
            val dao=db.noteDao();val repo=NoteRepository(dao,"phone"){1000}
            val a=repo.save("n","T","a");repo.save("n","T","b")
            repo.applyRemote(peer(a,"c"),"peer")
            val conflict=dao.conflictsFor("n").single();val captured=dao.note("n")!!
            repo.save("n","T","later");val latest=repo.save("n","T","b")
            val failure=runCatching { repo.resolveConflict(conflict,"c",captured) }.exceptionOrNull()
            assertEquals("notes_stale",failure?.message);assertEquals(latest,dao.note("n"))
            assertEquals(0L,dao.conflictsFor("n").single().resolvedAt)
        } finally { db.close() }
    }
    @Test fun RVM08_bothCycleVersionsRemainRecoverable() = runBlocking {
        val db=database()
        try {
            val dao=db.noteDao();val repo=NoteRepository(dao,"phone"){100}
            repo.save("n","T","a");val b=repo.save("n","T","b")
            repo.applyRemote(peer(b,"a","b"),"peer")
            repo.applyRemote(b,"phone")
            val bodies=dao.conflictsFor("n").flatMap { listOf(it.localMarkdown,it.remoteMarkdown) }
            assertTrue(bodies.containsAll(listOf("a","b")));assertEquals("a",dao.note("n")!!.markdown)
        } finally { db.close() }
    }

    @Test fun RVM04_sameWriterRestoreAndForeignMetadataConvergeInEitherOrder() = runBlocking {
        val db=database()
        try {
            val dao=db.noteDao();val repo=NoteRepository(dao,"phone"){100}
            val a=repo.save("n","T","a")
            for(source in listOf("peer","other","")) for(reverse in listOf(false,true)) {
                val dead=a.copy(deleted=true,deletedAt=101,updatedAt=101,modifiedBy="peer")
                val live=a.copy(updatedAt=102,modifiedBy=source,pinned=true)
                dao.upsertNotes(listOf(if(reverse)live else dead))
                repo.applyRemote(if(reverse)dead else live,if(reverse)"peer" else source)
                assertEquals(source!="peer",dao.note("n")!!.deleted)
            }
        } finally { db.close() }
    }

}
