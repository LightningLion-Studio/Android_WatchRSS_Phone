package com.lightningstudio.watchrss.phone.diagnostics
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class) @Config(manifest=Config.NONE,sdk=[35])
class SyncDiagR2Test {
 private val id="a".repeat(64)
 @Test fun manifestBatchKeepsEveryIdentity(){val ids=listOf(id,"b".repeat(64));assertEquals(ids,DiagnosticFields.requestItems(JSONObject().put("action","syncBooks").put("phase","manifest").put("books",JSONArray().apply{ids.forEach{put(JSONObject().put("bookId",it))}})).map{it.second})}
 @Test fun rejectionBeyondDetailLimitRemainsPartialAndCounted(){var data:String?=null;val h=SyncDiagnostics(object:DiagnosticStorage{override fun read()=data;override fun write(value:String){data=value}},{1},{1});val t=h.begin();repeat(256){t.item("article",id,"applied","none")};repeat(7){t.item("reader",null,"rejected","peer_rejected")};t.finish(true);val row=h.last().getJSONObject(0);assertEquals("partial",row.getString("result"));for(k in listOf("pendingCount","rejectedCount","omittedCount","omittedRejectedCount"))assertEquals(k,7,row.getInt(k));assertEquals(0,row.getInt("omittedAppliedCount"))}
 @Test fun titleRedactsCredentialAssignments(){for(prefix in listOf("Cookie:","Authorization: Bearer","token=","api_key:","K_id =")){val s="Book $prefix RV_REVIEW_SYNTHETIC";assertFalse(DiagnosticFields.title(s).contains("RV_REVIEW_SYNTHETIC"));assertFalse(DiagnosticFields.rejectionTitle(s)!!.contains("RV_REVIEW_SYNTHETIC"))}}
 @Test fun booksUseABoundedDefaultPage() {val context=org.robolectric.RuntimeEnvironment.getApplication();var maxRows=0;val q=object:DiagnosticQuery{override fun query(sql:String,args:Array<String>):android.database.Cursor{val cursor=android.database.MatrixCursor(arrayOf("bookId","stateJson","packageSha256","packageBytes"));if(sql.startsWith("SELECT bookId,stateJson")){val limit=if(sql.contains("LIMIT"))args.first().toInt()else 20000;repeat(limit){cursor.addRow(arrayOf(it.toString(16).padStart(64,'0'),"{\"title\":\"Book\"}",null,0))};maxRows=limit};return cursor}};val reader=LibraryDiagnostics(q,java.io.File(context.cacheDir,"absent-r2"),com.lightningstudio.watchrss.phone.data.local.FileArticleContentStore(context));assertTrue(reader.books().length()<=12);assertTrue(maxRows<=13)}
}
