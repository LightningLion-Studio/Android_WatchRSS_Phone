package com.lightningstudio.watchrss.phone.diagnostics

import androidx.room.Room
import com.lightningstudio.watchrss.phone.data.db.PhoneCompanionDatabase
import com.lightningstudio.watchrss.phone.data.book.BookEntity
import com.lightningstudio.watchrss.phone.data.local.FileArticleContentStore
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class SyncDiagnosticsTest {
    private class Storage:DiagnosticStorage {var value:String?=null;override fun read()=value;override fun write(value:String){this.value=value}}
    private val id="a".repeat(64)
    @Test fun `dump of a missing database never creates or migrates a library`() {
        val context=RuntimeEnvironment.getApplication();val root=File(context.cacheDir,"diag-missing-${System.nanoTime()}")
        val isolated=object:android.content.ContextWrapper(context) {override fun getApplicationContext():android.content.Context=this;override fun getDatabasePath(name:String)=File(root,name)}
        val snapshot=com.lightningstudio.watchrss.phone.PhoneCompanionContainer(isolated).diagnosticLibrarySnapshot()
        assertEquals(0,snapshot.first.length());assertEquals(0,snapshot.second.getInt("books"));assertFalse(root.exists())
    }
    @Test fun `empty library reads no bodies and writes no business rows`() {
        val context=RuntimeEnvironment.getApplication()
        val db=Room.inMemoryDatabaseBuilder(context,PhoneCompanionDatabase::class.java).allowMainThreadQueries().build()
        try {
            val sql=db.openHelper.readableDatabase;val before=sql.query("SELECT total_changes()").use{it.moveToFirst();it.getLong(0)}
            val reader=LibraryDiagnostics(sql,File(context.cacheDir,"diagnostic-books"),FileArticleContentStore(context))
            assertEquals(0,reader.books().length());assertEquals(DiagnosticFields.summary().toString(),reader.summary().toString())
            val after=sql.query("SELECT total_changes()").use{it.moveToFirst();it.getLong(0)};assertEquals(before,after)
        }finally{db.close()}
    }
    @Test fun `book manifest projection and missing package reason never expose source identity`()=runBlocking {
        val context=RuntimeEnvironment.getApplication();val db=Room.inMemoryDatabaseBuilder(context,PhoneCompanionDatabase::class.java).allowMainThreadQueries().build()
        try {
            db.bookDao().put(BookEntity(id,JSONObject().put("title","Book").put("chapterCount",1).put("fingerprint",id).put("deleted",false).put("sourceUrl","https://secret").put("sourceDeviceId","secret").toString()))
            val reader=LibraryDiagnostics(db.openHelper.readableDatabase,File(context.cacheDir,"diagnostic-absent"),FileArticleContentStore(context))
            val row=reader.books().getJSONObject(0)
            assertEquals(setOf("bookId","title","chapterCount","packageBytes","packageSha256","status","reasonCode","readable","manifest"),row.keys().asSequence().toSet())
            val rejected=LibraryDiagnostics(db.openHelper.readableDatabase,File(context.cacheDir,"diagnostic-absent"),FileArticleContentStore(context),mapOf(id to "book_package_invalid")).books().getJSONObject(0)
            assertEquals("failed",rejected.getString("status"));assertEquals("book_package_invalid",rejected.getString("reasonCode"))
            assertEquals("package_missing",row.getString("reasonCode"));assertFalse(row.getBoolean("readable"));assertFalse(row.toString().contains("secret"));assertEquals(0,reader.summary().getInt("books"))
        }finally{db.close()}
    }
    @Test fun `pending imports remain visible without registering or inventing identity`()=runBlocking {
        val context=RuntimeEnvironment.getApplication();val db=Room.inMemoryDatabaseBuilder(context,PhoneCompanionDatabase::class.java).allowMainThreadQueries().build()
        try {
            db.phoneArticleDao().upsert(article("chapter","https://watchrss.local/import-content/txt/test","body"))
            val sql=db.openHelper.readableDatabase;val before=sql.query("SELECT total_changes()").use{it.moveToFirst();it.getLong(0)}
            val reader=LibraryDiagnostics(sql,File(context.cacheDir,"diag-pending"),FileArticleContentStore(context))
            val row=reader.books().getJSONObject(0);assertTrue(row.isNull("bookId"));assertEquals("registration_pending",row.getString("reasonCode"));assertEquals(1,reader.summary().getInt("books"));assertTrue(row.getBoolean("readable"))
            val after=sql.query("SELECT total_changes()").use{it.moveToFirst();it.getLong(0)};assertEquals(before,after);assertTrue(db.bookDao().all().isEmpty())
        }finally{db.close()}
    }
    @Test fun `actual package digest and first chapter marker determine readiness`()=runBlocking {
        val context=RuntimeEnvironment.getApplication();val db=Room.inMemoryDatabaseBuilder(context,PhoneCompanionDatabase::class.java).allowMainThreadQueries().build()
        val root=File(context.cacheDir,"diag-ready-${System.nanoTime()}").apply{mkdirs()}
        try {
            val file=File(root,"$id.jsonl").apply{writeText("private body")};val hash=com.lightningstudio.watchrss.phone.cloud.CloudSnapshotCodec.sha256(file.readBytes())
            val state=JSONObject().put("title","Book").put("chapterCount",1).put("fingerprint",id).put("deleted",false)
            db.bookDao().put(BookEntity(id,state.toString(),hash,file.length()));db.phoneArticleDao().upsert(article("chapter","https://watchrss.local/import-content/txt/test","body"));db.bookDao().putChapters(listOf(com.lightningstudio.watchrss.phone.data.book.BookChapterEntity(id,1,"chapter")))
            val reader=LibraryDiagnostics(db.openHelper.readableDatabase,root,FileArticleContentStore(context))
            assertEquals("ready",reader.books().getJSONObject(0).getString("status"));assertEquals(1,reader.summary().getInt("books"));db.phoneArticleDao().upsert(article("chapter","https://watchrss.local/import-content/txt/test","").copy(contentHtml="<img src=\"cover\">"));assertTrue(reader.books().getJSONObject(0).getBoolean("readable"));file.appendText("corrupt");assertEquals("package_hash_mismatch",reader.books().getJSONObject(0).getString("reasonCode"))
        }finally{db.close();root.deleteRecursively()}
    }
    private fun article(id:String,url:String,text:String)=com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity(id,"private-owner",url,"Book","","",null,text,null,"hash",1,1,true,1,1,null,null,false,0,0,false,0,0,false,0)
    @Test fun `persistent ring rejects item overflow and default count`() {
        val storage=Storage();var time=1L;val history=SyncDiagnostics(storage,{time},{time})
        repeat(17){time++;val trace=history.begin();trace.bytes(200);trace.item("book",id,"rejected","book_package_invalid");time+=1000;trace.finish(true)}
        assertEquals(3,history.last().length());assertEquals(12,SyncDiagnostics(storage,{time},{time}).last(12).length())
        assertEquals("book_package_invalid",history.bookRejections()[id]);val row=history.last().getJSONObject(0);assertEquals(setOf("startedAt","endedAt","channel","result","totalBytes","bytesPerSecond","reconnectCount","items","itemsTruncated"),row.keys().asSequence().toSet());assertEquals("partial",row.getString("result"));assertEquals(200,row.getLong("bytesPerSecond"));assertEquals(id,row.getJSONArray("items").getJSONObject(0).getString("id"))
        time++;val trace=history.begin();repeat(DiagnosticFields.ITEM_LIMIT+1){trace.item("book",id,"applied","none")};trace.channel(true);trace.reconnect();trace.finish(false)
        val overflow=history.last(1).getJSONObject(0);assertEquals("failed",overflow.getString("result"));assertEquals("IP",overflow.getString("channel"));assertEquals(1,overflow.getInt("reconnectCount"));assertTrue(overflow.getBoolean("itemsTruncated"));assertEquals(DiagnosticFields.ITEM_LIMIT,overflow.getJSONArray("items").length())
    }
    @Test fun `poisoned storage fields and identifiers are stripped on read`() {
        val storage=Storage();storage.value=JSONArray().put(JSONObject().put("Cookie","secret").put("route","https://secret").put("items",JSONArray().put(JSONObject().put("kind","https://secret").put("id","secret").put("reasonCode","token=secret")))).toString()
        val output=SyncDiagnostics(storage).last().toString();assertFalse(output.contains("secret"));assertFalse(output.contains("Cookie"));assertFalse(output.contains("route"))
        storage.value="broken";assertEquals(0,SyncDiagnostics(storage).last().length())
    }
    @Test fun `fixed fields exclude nested manifest secrets and validate count`() {
        val book=DiagnosticFields.book(id,"Book",1,2,id,"ready","none",true,JSONObject().put("fingerprint",id).put("chapterCount",1).put("token","secret").put("sourceUrl","secret"))
        assertTrue(DiagnosticFields.item("account","12345678-1234-1234-1234-123456789abc","rejected","account_required").isNull("id"));assertEquals("Book [redacted]",DiagnosticFields.title("Book https://secret"));assertFalse(book.toString().contains("secret"));assertEquals(setOf("fingerprint","chapterCount","deleted"),book.getJSONObject("manifest").keys().asSequence().toSet())
        assertEquals(setOf("channels","articles","favorites","watchLater","memos","books"),DiagnosticFields.summary().keys().asSequence().toSet())
        assertEquals("sync.last" to 3,DiagnosticFields.selection(arrayOf("sync.last")));assertEquals("all" to 3,DiagnosticFields.selection(emptyArray()));assertTrue(runCatching{DiagnosticFields.selection(arrayOf("library.books","3"))}.isFailure);assertEquals(3,DiagnosticFields.lastCount(emptyArray()));assertEquals(12,DiagnosticFields.lastCount(arrayOf("12")))
        for(args in listOf(arrayOf("0"),arrayOf("13"),arrayOf("3.0"),arrayOf("-1"),arrayOf("3","url"),arrayOf("01")))assertTrue(runCatching{DiagnosticFields.lastCount(args)}.isFailure)
    }
    @Test fun `IP fallback reports the final RFCOMM data channel`() {
        val history=SyncDiagnostics(Storage(),{1},{1});val trace=history.begin();trace.channel(true);trace.reconnect();trace.channel(false);trace.finish(true)
        assertEquals("RFCOMM",history.last(1).getJSONObject(0).getString("channel"));assertEquals(1,history.last(1).getJSONObject(0).getInt("reconnectCount"))
    }
    @Test fun `batch refusals keep note IDs and asset digests without paths or account identity`() {
        val note="12345678-1234-1234-1234-123456789abc"
        assertEquals(listOf("note" to note,"note" to id),DiagnosticFields.requestItems(JSONObject().put("action","syncNotes").put("notes",JSONArray().put(JSONObject().put("noteId",note).put("markdown","secret")).put(JSONObject().put("noteId",id)))))
        assertEquals(listOf("asset" to id),DiagnosticFields.requestItems(JSONObject().put("action","syncNoteAsset").put("sha256",id).put("storageKey","secret.png").put("data","secret")))
        assertEquals(listOf("account" to null),DiagnosticFields.requestItems(JSONObject().put("action","syncAccount").put("noteId",note).put("userId",note)))
        assertEquals(DiagnosticFields.ITEM_LIMIT+1,DiagnosticFields.requestItems(JSONObject().put("action","syncNotes").put("notes",JSONArray().apply{repeat(DiagnosticFields.ITEM_LIMIT+2){put(JSONObject().put("noteId",id))}})).size)
    }
    @Test fun `bounded history serialization remains below 600 KB`() {
        val item=DiagnosticFields.item("article",id,"rejected","book_reading_delta_not_negotiated")
        val items=JSONArray().apply{repeat(DiagnosticFields.ITEM_LIMIT){put(item)}}
        val row=JSONObject().put("startedAt",Long.MAX_VALUE).put("endedAt",Long.MAX_VALUE).put("totalBytes",Long.MAX_VALUE).put("bytesPerSecond",Long.MAX_VALUE).put("reconnectCount",Long.MAX_VALUE).put("channel","RFCOMM").put("result","partial").put("items",items).put("itemsTruncated",true)
        val storage=Storage().apply{value=JSONArray().apply{repeat(DiagnosticFields.HISTORY_LIMIT){put(row)}}.toString()}
        assertTrue(SyncDiagnostics(storage).last(12).toString().toByteArray().size<600000)
    }
    @Test fun `release service is unexported has no bind API and uses DUMP boundary`() {
        val manifest=File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:name=\".diagnostics.SyncDiagnosticService\" android:exported=\"false\" android:permission=\"android.permission.DUMP\""))
        assertNull(SyncDiagnosticService().onBind(null))
    }
}
