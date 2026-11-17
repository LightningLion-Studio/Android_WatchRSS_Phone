package com.lightningstudio.watchrss.phone.diagnostics
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class) @Config(manifest=Config.NONE,sdk=[35],application=android.app.Application::class)
class SyncDiagR2Test {
 private val id="a".repeat(64)
 private fun history():SyncDiagnostics {var data:String?=null;return SyncDiagnostics(object:DiagnosticStorage{override fun read()=data;override fun write(value:String){data=value}},{1},{1})}
 @Test fun actualDumpSchedulesWorkAndReturnsWithoutMainThreadScanning(){
  val service=org.robolectric.Robolectric.buildService(SyncDiagnosticService::class.java).create().get()
  val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1);var main=true
  service.collect={main=android.os.Looper.myLooper()==android.os.Looper.getMainLooper();entered.countDown();release.await(3,java.util.concurrent.TimeUnit.SECONDS);"{\"books\":[]}"}
  fun dump():String {val out=java.io.StringWriter();val m=SyncDiagnosticService::class.java.getDeclaredMethod("dump",java.io.FileDescriptor::class.java,java.io.PrintWriter::class.java,Array<String>::class.java);m.isAccessible=true;m.invoke(service,java.io.FileDescriptor(),java.io.PrintWriter(out),arrayOf("library.books"));return out.toString()}
  try{val start=System.nanoTime();assertTrue(dump().contains("pending"));assertTrue((System.nanoTime()-start)/1_000_000<500);assertTrue(entered.await(3,java.util.concurrent.TimeUnit.SECONDS));assertFalse(main);release.countDown();var output="";val deadline=System.nanoTime()+3_000_000_000;while(System.nanoTime()<deadline){output=dump();if(output.contains("books"))break;Thread.yield()};assertTrue(output.contains("books"))}finally{release.countDown();service.onDestroy()}
 }
 @Test fun boundedCacheNeverQueuesAdditionalPagesWhileBusy(){val jobs=mutableListOf<Runnable>();val cache=DiagnosticSnapshotCache({jobs.add(it)},{0});repeat(1000){cache.poll(DiagnosticQuerySelection("library.books",offset=it)){"{}"}};assertEquals(1,jobs.size);jobs.single().run();assertEquals("{}",cache.poll(DiagnosticQuerySelection("library.books")){error("cached")})}
 @Test fun bodyRefusalObserverKeepsReasonAndTitleAndBatchHasSeparateCode(){val h=history();val t=h.begin();DiagnosticFields.recordResponse(t,JSONObject().put("action","syncBooks").put("phase","body").put("bookId",id).put("title","Known"),JSONObject().put("success",false).put("errorCode","book_manifest_invalid").put("reason","manifest_identity_mismatch"));DiagnosticFields.recordResponse(t,JSONObject().put("action","syncBooks").put("phase","manifest").put("books",JSONArray().put(JSONObject().put("bookId",id).put("title","First")).put(JSONObject().put("bookId","b".repeat(64)).put("title","Second"))),JSONObject().put("success",false));t.finish(true);val items=h.last().getJSONObject(0).getJSONArray("items");assertEquals("manifest_identity_mismatch",items.getJSONObject(0).getString("reason"));assertEquals("Known",items.getJSONObject(0).getString("title"));assertEquals(3,items.length());assertEquals("book_batch_rejected",items.getJSONObject(1).getString("code"));assertEquals("bbbbbbbb",items.getJSONObject(2).getString("bookIdPrefix"))}

 @Test fun manifestBatchKeepsEveryIdentity(){val ids=listOf(id,"b".repeat(64));assertEquals(ids,DiagnosticFields.requestItems(JSONObject().put("action","syncBooks").put("phase","manifest").put("books",JSONArray().apply{ids.forEach{put(JSONObject().put("bookId",it))}})).map{it.second})}
 @Test fun rejectionBeyondDetailLimitRemainsPartialAndCounted(){var data:String?=null;val h=SyncDiagnostics(object:DiagnosticStorage{override fun read()=data;override fun write(value:String){data=value}},{1},{1});val t=h.begin();repeat(256){t.item("article",id,"applied","none")};repeat(7){t.item("reader",null,"rejected","peer_rejected")};t.finish(true);val row=h.last().getJSONObject(0);assertEquals("partial",row.getString("result"));for(k in listOf("pendingCount","rejectedCount","omittedCount","omittedRejectedCount"))assertEquals(k,7,row.getInt(k));assertEquals(0,row.getInt("omittedAppliedCount"))}
 @Test fun titleRedactsCredentialAssignments(){for(prefix in listOf("Cookie:","Authorization: Bearer","token=","api_key:","K_id =")){val s="Book $prefix RV_REVIEW_SYNTHETIC";assertFalse(DiagnosticFields.title(s).contains("RV_REVIEW_SYNTHETIC"));assertFalse(DiagnosticFields.rejectionTitle(s)!!.contains("RV_REVIEW_SYNTHETIC"))}}
 @Test fun booksUseABoundedDefaultPage() {val context=org.robolectric.RuntimeEnvironment.getApplication();var maxRows=0;val q=object:DiagnosticQuery{override fun query(sql:String,args:Array<String>):android.database.Cursor{val cursor=android.database.MatrixCursor(arrayOf("bookId","stateJson","packageSha256","packageBytes"));if(sql.startsWith("SELECT bookId,")&&sql.contains("packageBytes")){val limit=if(sql.contains("LIMIT"))args.first().toInt()else 20000;repeat(limit){cursor.addRow(arrayOf<Any?>(it.toString(16).padStart(64,'0'),"{\"title\":\"Book\"}",null,0))};maxRows=limit};return cursor}};val reader=LibraryDiagnostics(q,java.io.File(context.cacheDir,"absent-r2"),com.lightningstudio.watchrss.phone.data.local.FileArticleContentStore(context));assertTrue(reader.books().length()<=12);assertTrue(maxRows<=13)}
 @Test fun realSQLitePagesSkipTombstonesAndDoNotHashOverBudgetPackages()=kotlinx.coroutines.runBlocking {
  val context=org.robolectric.RuntimeEnvironment.getApplication();val db=androidx.room.Room.inMemoryDatabaseBuilder(context,com.lightningstudio.watchrss.phone.data.db.PhoneCompanionDatabase::class.java).allowMainThreadQueries().build();val root=java.io.File(context.cacheDir,"r2-page-${System.nanoTime()}").apply{mkdirs()}
  try {
   val ids=(0 until 20).map{it.toString(16).padStart(64,'0')}
   for((i,key) in ids.withIndex())db.bookDao().put(com.lightningstudio.watchrss.phone.data.book.BookEntity(key,JSONObject().put("title","Book $i").put("chapterCount",1).put("deleted",i==0).toString(),key,if(i==1)64L*1024*1024+1 else 0))
   java.io.RandomAccessFile(java.io.File(root,"${ids[1]}.jsonl"),"rw").use{it.setLength(64L*1024*1024+1)}
   val reader=LibraryDiagnostics(db.openHelper.readableDatabase,root,com.lightningstudio.watchrss.phone.data.local.FileArticleContentStore(context));val first=reader.page(0,8);val second=reader.page(first.getInt("nextOffset"),8)
   assertEquals(ids[1],first.getJSONArray("books").getJSONObject(0).getString("bookId"));assertEquals(ids[9],second.getJSONArray("books").getJSONObject(0).getString("bookId"));assertEquals("hash_budget_exceeded",first.getJSONArray("books").getJSONObject(0).getString("reasonCode"));assertEquals("staged",first.getJSONArray("books").getJSONObject(0).getString("status"));assertEquals(8,first.getJSONArray("books").length());assertEquals(3,reader.page(16,8).getJSONArray("books").length());assertTrue(reader.page(16,8).isNull("nextOffset"))
  }finally{db.close();root.deleteRecursively()}
 }

}
