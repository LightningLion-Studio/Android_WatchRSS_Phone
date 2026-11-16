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
class NoteMergeFixTest {
    @Test fun readBasisGuardRetriesAgainstAnActualRoomCommit() = runBlocking {
        val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),PhoneCompanionDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao=db.noteDao();val repo=NoteRepository(dao,"phone"){100}
            val a=repo.save("n","T","a")
            var injected=false
            val guarded=object:NoteDao by dao {
                override suspend fun commitNote(noteId:String,expected:NoteEntity?,replacement:NoteEntity?,conflict:NoteConflictEntity?,resolvedId:String?,resolvedAt:Long):Boolean {
                    if(!injected){injected=true;repo.save("n","T","unsent local")}
                    return dao.commitNote(noteId,expected,replacement,conflict,resolvedId,resolvedAt)
                }
            }
            val remote=a.copy(markdown="remote",contentHash=MarkdownNoteCodec.sha256("remote"),updatedAt=200,modifiedBy="peer")
            assertEquals(MarkdownMergeResult.Conflict,NoteRepository(guarded,"phone"){300}.applyRemote(remote,"peer"))
            assertEquals("unsent local",dao.note("n")!!.markdown)
            assertEquals("remote",dao.conflictsFor("n").single().remoteMarkdown)
            val c=dao.conflictsFor("n").single()
            val resolved=repo.resolveConflict(c,"resolved")
            NoteRepository(dao,"phone"){400}.applyRemote(remote,"peer")
            assertEquals(resolved,dao.note("n"));assertTrue(dao.conflictsFor("n").all{it.resolvedAt>0})
        } finally { db.close() }
    }

    @Test fun existingLegacyConflictRemainsReadableAndResolvable() = runBlocking {
        val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),PhoneCompanionDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao=db.noteDao();val repo=NoteRepository(dao,"phone"){100}
            val a=repo.save("n","T","a");repo.save("n","T","b")
            val tuple=listOf("n","a","b","c","peer")
            val legacy=NoteConflictEntity(MarkdownNoteCodec.sha256(tuple.joinToString("\u0000")),"n","a","b","c","peer",1)
            dao.upsertConflicts(listOf(legacy))
            repo.applyRemote(a.copy(markdown="c",contentHash=MarkdownNoteCodec.sha256("c"),updatedAt=200),"peer")
            assertEquals(listOf(legacy),dao.conflictsFor("n"))
            repo.resolveConflict(legacy,"kept")
            assertEquals(legacy.conflictId,dao.conflictsFor("n").single().conflictId)
            assertTrue(dao.conflictsFor("n").single().resolvedAt>0)
        } finally { db.close() }
    }

    @Test fun touchingHunksAreSymmetricAndDisjointEditsStillMerge() {
        val strings=listOf("", "a", "b", "x\na", "y\na", "a\nx", "a\ny", "A\nb", "a\nB", "a\nb", "x\u0000y", "😀")
        for(base in strings)for(local in strings)for(remote in strings)
            assertEquals(MarkdownThreeWayMerge.merge(base,local,remote),MarkdownThreeWayMerge.merge(base,remote,local))
        assertEquals(MarkdownMergeResult.Merged("A\nB"),MarkdownThreeWayMerge.merge("a\nb","A\nb","a\nB"))
        assertNotEquals(noteConflictId("n","a","x\u0000y","z","peer"),noteConflictId("n","a","x","y\u0000z","peer"))
    }
}
