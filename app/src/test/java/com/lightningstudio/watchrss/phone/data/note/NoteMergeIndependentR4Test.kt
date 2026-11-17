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
class NoteMergeIndependentR4Test {

 private fun db()=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),PhoneCompanionDatabase::class.java).allowMainThreadQueries().build()
 private fun peer(n:NoteEntity,body:String,base:String,who:String="peer")=n.copy(markdown=body,plainText=body,contentHash=MarkdownNoteCodec.sha256(body),baseMarkdown=base,baseContentHash=MarkdownNoteCodec.sha256(base),modifiedBy=who,updatedAt=300)
 @Test fun zeroClockResolutionClosesMarkerAndOldWindowIsRejected()=runBlocking {
  val db=db();try{
   var clock=1000L;val dao=db.noteDao();val repo=NoteRepository(dao,"phone"){clock};val a=repo.save("n","T","a");repo.save("n","T","b");repo.applyRemote(peer(a,"c","a"),"peer")
   val capture=repo.captureConflict(dao.conflictsFor("n").single())!!;clock=0L;val chosen=repo.resolveConflict(capture,"chosen")
   assertEquals("chosen",chosen.markdown);assertTrue(chosen.updatedAt>capture.note.updatedAt);val rows=dao.conflictsFor("n");assertTrue(rows.all{it.resolvedAt>0L});assertEquals("b",rows.single().localMarkdown);assertEquals("c",rows.single().remoteMarkdown)
   assertEquals("notes_stale",runCatching{repo.resolveConflict(capture,"c")}.exceptionOrNull()?.message);assertEquals(chosen,dao.note("n"));assertEquals(rows,dao.conflictsFor("n"))
  }finally{db.close()}
 }
 @Test fun allThreeWriterOrdersAndHistoricalReplayPreserveBodies()=runBlocking {
  for(order in listOf(listOf(0,1,2),listOf(0,2,1),listOf(1,0,2),listOf(1,2,0),listOf(2,0,1),listOf(2,1,0))){val db=db();try{
   val dao=db.noteDao();val repo=NoteRepository(dao,"phone"){1000};val a=repo.save("n","T","base");val bodies=listOf("phone\u0000👩‍👩‍👧‍👦","watch\ne\u0301","mini\n\u202eRTL")
   val rows=bodies.mapIndexed{i,body->peer(a,body,"base",listOf("phone","watch","mini")[i]).copy(updatedAt=(3-i).toLong())}
   for(i in order)repo.applyRemote(rows[i],rows[i].modifiedBy);val before=dao.note("n");val conflicts=dao.conflictsFor("n")
   repeat(3){for(i in order.reversed())repo.applyRemote(rows[i],rows[i].modifiedBy)}
   assertEquals(before,dao.note("n"));assertEquals(conflicts,dao.conflictsFor("n"));assertEquals(2,conflicts.size)
   val all=listOf(before!!.markdown)+conflicts.flatMap{listOf(it.localMarkdown,it.remoteMarkdown)};for(body in bodies)assertTrue(all.contains(body))
  }finally{db.close()}}
 }

 @Test fun zeroRollbackAndEqualClocksCloseSelectedRowWithoutReopeningHistory()=runBlocking {
  for(mode in listOf("zero","rollback","equal"))for(body in listOf("b","c","chosen")){
   val db=db();try{
    var clock=1000L;val dao=db.noteDao();val repo=NoteRepository(dao,"phone"){clock}
    val a=repo.save("n","T","a");repo.save("n","T","b")
    val remote=peer(a,"c","a");repo.applyRemote(remote,"peer")
    val selected=dao.conflictsFor("n").single();repo.applyRemote(peer(a,"d","a","third"),"third")
    val capture=repo.captureConflict(selected)!!;assertEquals(2,capture.openConflicts.size)
    clock=when(mode){"zero"->0L;"rollback"->500L;else->capture.note.updatedAt}
    val chosen=repo.resolveConflict(capture,body)
    assertEquals(body,chosen.markdown);assertTrue(chosen.updatedAt>capture.note.updatedAt)
    val rows=dao.conflictsFor("n");assertEquals(selected.copy(resolvedAt=maxOf(1L,clock)),rows.single{it.conflictId==selected.conflictId})
    assertEquals(1,rows.count{it.resolvedAt==0L})
    assertEquals("notes_stale",runCatching{repo.resolveConflict(capture,"stale")}.exceptionOrNull()?.message)
    assertEquals(chosen,dao.note("n"));assertEquals(rows,dao.conflictsFor("n"))
    val reopened=NoteRepository(dao,"phone"){0L};val fresh=reopened.captureConflict(rows.single{it.resolvedAt==0L})!!
    val last=reopened.resolveConflict(fresh,"last");assertTrue(last.updatedAt>chosen.updatedAt)
    val closed=dao.conflictsFor("n");assertTrue(closed.all{it.resolvedAt>0L})
    assertEquals(rows.single{it.conflictId==selected.conflictId},closed.single{it.conflictId==selected.conflictId})
    reopened.applyRemote(remote,"peer");assertEquals(last,dao.note("n"));assertEquals(closed,dao.conflictsFor("n"))
   }finally{db.close()}
  }
 }
}
