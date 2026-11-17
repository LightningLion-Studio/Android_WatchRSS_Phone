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
class NoteMergeIndependentR3Test {

 private fun db()=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),PhoneCompanionDatabase::class.java)
  .addCallback(NoteEditorPreferenceMigration).allowMainThreadQueries().build()
 private fun peer(n:NoteEntity,body:String,base:String,who:String="peer")=n.copy(markdown=body,plainText=body,contentHash=MarkdownNoteCodec.sha256(body),baseMarkdown=base,baseContentHash=MarkdownNoteCodec.sha256(base),modifiedBy=who,updatedAt=300)
 @Test fun RVM303_addedConflictInvalidatesOpenWindow()=runBlocking {
  val db=db();try{
   val dao=db.noteDao();val repo=NoteRepository(dao,"phone"){1000}
   val a=repo.save("n","T","a");repo.save("n","T","b")
   repo.applyRemote(peer(a,"c","a"),"peer")
   val captured=dao.note("n")!!;val selected=dao.conflictsFor("n").single()
   repo.applyRemote(peer(a,"d","a","third"),"third")
   val before=dao.conflictsFor("n");assertEquals(captured,dao.note("n"));assertEquals(2,before.size)
   val error=runCatching{repo.resolveConflict(selected,"c",captured)}.exceptionOrNull()
   println("RVM3-03 error=${error?.message} body=${dao.note("n")!!.markdown} open=${dao.conflictsFor("n").count{it.resolvedAt==0L}}")
   assertEquals("notes_stale",error?.message);assertEquals(captured,dao.note("n"));assertEquals(before,dao.conflictsFor("n"))
  }finally{db.close()}
 }
 @Test fun CONTROL_fullReceiptIncludesEverySemanticFieldAndSource()=runBlocking {
  val db=db();try{
   val repo=NoteRepository(db.noteDao(),"phone"){100};val n=repo.save("n","T","a")
   val variants=listOf(n,n.copy(noteId="n2"),n.copy(folderId=""),n.copy(title="T2"),n.copy(markdown="b"),n.copy(plainText="b"),n.copy(contentHash="other"),n.copy(baseContentHash="other"),n.copy(baseMarkdown="b"),n.copy(pinned=true),n.copy(createdAt=2),n.copy(updatedAt=3),n.copy(modifiedBy="peer"),n.copy(deleted=true),n.copy(deletedAt=4))
   val hashes=variants.map{noteRemoteSnapshotHash(it,"peer")}.toMutableList();hashes+=noteRemoteSnapshotHash(n,"other")
   assertEquals(16,hashes.toSet().size)
   repo.applyRemote(n,"peer");assertTrue(db.noteDao().hasRemoteReceipt(hashes[0]));repo.clearForAccountSwitch();assertFalse(db.noteDao().hasRemoteReceipt(hashes[0]))
  }finally{db.close()}
 }
 @Test fun CONTROL_receiptHistoryDoesNotForgetOldNoteWhenUnrelatedNoteArrives()=runBlocking {
  val db=db();try{
   val dao=db.noteDao();val repo=NoteRepository(dao,"phone"){1000};val a=repo.save("n","T","a");repo.save("n","T","b")
   val remote=peer(a,"c","z");repo.applyRemote(remote,"peer")
   val chosen=repo.resolveConflict(dao.conflictsFor("n").single(),"resolved");val rows=dao.conflictsFor("n")
   repo.applyRemote(a.copy(noteId="other"),"peer");repo.applyRemote(remote,"peer")
   assertEquals(chosen,dao.note("n"));assertEquals(rows,dao.conflictsFor("n"))
  }finally{db.close()}
 }
 @Test fun CONTROL_exhaustedLongClockDoesNotWriteBodyOrReceipt()=runBlocking {
  val db=db();try{
   val dao=db.noteDao();val repo=NoteRepository(dao,"phone"){100};val a=repo.save("n","T","a").copy(updatedAt=Long.MAX_VALUE,modifiedBy="z")
   dao.upsertNotes(listOf(a));val remote=peer(a,"b","a","a").copy(updatedAt=Long.MAX_VALUE)
   assertEquals("counter_exhausted",runCatching{repo.applyRemote(remote,"a")}.exceptionOrNull()?.message)
   assertEquals(a,dao.note("n"));assertFalse(dao.hasRemoteReceipt(noteRemoteSnapshotHash(remote,"a")))
  }finally{db.close()}
 }
 @Test fun completeCaptureRejectsConflictSetChangesInsideFinalTransaction()=runBlocking {
  for(mode in listOf("add","remove","edit","resolve")) {
   val db=db();try {
    val dao=db.noteDao();val repo=NoteRepository(dao,"phone"){1000}
    val a=repo.save("n","T","a");repo.save("n","T","b")
    repo.applyRemote(peer(a,"c","a"),"peer");val selected=dao.conflictsFor("n").single()
    repo.applyRemote(peer(a,"d","a","third"),"third")
    val capture=repo.captureConflict(selected)!!
    assertEquals(2,capture.openConflicts.size)
    var latest=emptyList<NoteConflictEntity>()
    val race=object:NoteDao by dao {
     override suspend fun commitNote(noteId:String,expected:NoteEntity?,replacement:NoteEntity?,conflict:NoteConflictEntity?,resolvedId:String?,resolvedAt:Long,expectedConflicts:List<NoteConflictEntity>?):Boolean {
      val other=capture.openConflicts.single{it.conflictId!=selected.conflictId}
      when(mode) {
       "add" -> dao.upsertConflicts(listOf(other.copy(conflictId="new-incident",remoteMarkdown="e")))
       "remove" -> {dao.clearConflicts();dao.upsertConflicts(listOf(selected))}
       "edit" -> {dao.clearConflicts();dao.upsertConflicts(listOf(selected,other.copy(createdAt=other.createdAt+1)))}
       "resolve" -> dao.resolveConflict(other.conflictId,9999)
      }
      latest=dao.conflictsFor("n")
      return dao.commitNote(noteId,expected,replacement,conflict,resolvedId,resolvedAt,expectedConflicts)
     }
    }
    assertEquals(mode,"notes_stale",runCatching{NoteRepository(race,"phone"){1000}.resolveConflict(capture,"c")}.exceptionOrNull()?.message)
    assertEquals(capture.note,dao.note("n"));assertEquals(latest,dao.conflictsFor("n"))
   }finally{db.close()}
  }
 }
 @Test fun freshCompleteCaptureWithMultipleConflictsCanResolveSelectedRow()=runBlocking {
  val db=db();try {
   val dao=db.noteDao();val repo=NoteRepository(dao,"phone"){1000}
   val a=repo.save("n","T","a");repo.save("n","T","b")
   repo.applyRemote(peer(a,"c","a"),"peer");val selected=dao.conflictsFor("n").single()
   val stale=repo.captureConflict(selected)!!
   repo.applyRemote(peer(a,"d","a","third"),"third")
   assertEquals("notes_stale",runCatching{repo.resolveConflict(stale,"c")}.exceptionOrNull()?.message)
   val fresh=repo.captureConflict(selected)!!;assertEquals(2,fresh.openConflicts.size)
   assertEquals("c",repo.resolveConflict(fresh,"c").markdown)
   assertEquals(1,dao.conflictsFor("n").count{it.resolvedAt==0L})
  }finally{db.close()}
 }

}
