package com.lightningstudio.watchrss.phone.connection.bluetooth

import androidx.room.Room
import com.lightningstudio.watchrss.phone.data.book.*
import com.lightningstudio.watchrss.phone.data.db.PhoneCompanionDatabase
import kotlinx.coroutines.runBlocking
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class BookManifestIsolationTest {
    private val root=File(RuntimeEnvironment.getApplication().cacheDir,"manifest-${System.nanoTime()}").apply{mkdirs()}
    private val db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),PhoneCompanionDatabase::class.java).allowMainThreadQueries().build()
    private val store=ImportedBookStore(db,File(root,"books"),null,"phone")
    private val trace=mutableListOf<String>()
    private var reading=false
    @After fun close(){db.close();root.deleteRecursively()}
    private fun state():JSONObject {
        val fp=BookSync.fingerprint(listOf(BookSync.chapterHash(1,"chapter","full text")))
        return JSONObject().put("bookId",BookSync.id(fp)).put("fingerprint",fp).put("title","用户原有书")
            .put("chapterCount",1).put("deletedChapters",JSONArray()).put("deletionClock",0).put("deleted",false)
            .put("deletedAt",0).put("updatedAt",1).put("sourceDeviceId","watch").put("contentAvailable",false)
    }
    private suspend fun sync(books:JSONArray,missing:JSONArray=JSONArray()):BookBluetoothSync {
        fun reply(phase:String)=JSONObject().put("action","syncBooks").put("version",1).put("success",true).put("phase",phase)
        val sync=BookBluetoothSync(store,"phone",File(root,"staging")){q->when(q.getString("phase")) {
            "manifest"->reply("manifest").put("books",books).put("missingBookIds",missing)
            "reading"->{reading=true;reply("reading").put("states",JSONArray()).put("hasMore",false).put("nextAfter",0)}
            else->error("invalid item must never transfer a body")
        }}
        sync.trace={trace+=it};sync.sync();assertTrue(reading);assertFalse(sync.booksComplete)
        return sync
    }
    @Test fun malformedManifestKeepsTitleAndExactRuleWithoutLeakingTextIntoTrace()=runBlocking<Unit> {
        val raw=state().put("chapterCount",0).put("contentText","must not log body")
        val issue=sync(JSONArray().put(raw)).issues.single()
        assertEquals("manifest_chapter_count_range",issue.reason);assertEquals("用户原有书",issue.title)
        assertEquals("book_manifest_invalid",issue.code)
        assertTrue(trace.any{it.contains("reason=manifest_chapter_count_range")})
        assertFalse(trace.any{it.contains("must not log body")})
    }
    @Test fun duplicateIdsAndNonObjectItemsHaveDistinctReasons()=runBlocking<Unit> {
        assertEquals("manifest_duplicate_book_id",sync(JSONArray().put(state()).put(state())).issues.single().reason)
        val issue=sync(JSONArray().put(42)).issues.single()
        assertEquals("manifest_not_object",issue.reason);assertEquals("invalid",issue.bookIdPrefix);assertNull(issue.title)
    }
    @Test fun localCountConflictAndEveryMissingRequestFailureHaveDistinctReasons()=runBlocking<Unit> {
        store.merge(state())
        val conflict=sync(JSONArray().put(state().put("chapterCount",2))).issues.single()
        assertEquals("manifest_local_identity_conflict",conflict.reason)
        assertEquals(1,store.states().single().getInt("chapterCount"))
        val id=state().getString("bookId")
        assertEquals("manifest_requested_body_unavailable",sync(JSONArray(),JSONArray().put(id)).issues.single().reason)
        assertEquals("manifest_requested_unknown_book",sync(JSONArray(),JSONArray().put("a".repeat(64))).issues.single().reason)
        store.markDeleted(id,20)
        assertEquals("manifest_requested_deleted_book",sync(JSONArray(),JSONArray().put(id)).issues.single().reason)
    }
}
