package com.lightningstudio.watchrss.phone.cloud

import com.lightningstudio.watchrss.phone.account.AccountEnvironment
import com.lightningstudio.watchrss.phone.account.PhoneAccountSession
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PhoneCloudRecordSyncTest {
    private val key = ByteArray(32) { it.toByte() }
    private val session = PhoneAccountSession(userId = "owner", phoneMasked = "***", accessToken = "session", refreshToken = "", expiresAtMillis = Long.MAX_VALUE)
    private class State : CloudRecordState {
        var cursor = "0"; val rows = mutableMapOf<String, Pair<String, String>>()
        override fun cursor(owner: String) = cursor
        override fun record(owner: String, id: String) = rows[id]
        override fun save(owner: String, id: String, revision: String, hash: String) { rows[id] = revision to hash }
        override fun advance(owner: String, cursor: String) { this.cursor = cursor }
        override fun clear(owner: String) { cursor = "0"; rows.clear() }
    }
    private fun value(i: Int) = JSONObject().put("articleId", "a$i").put("url", "https://example.com/$i")
    private fun page(items: JSONArray = JSONArray(), cursor: String = "0", more: Boolean = false, format: Int = 1) = JSONObject().put("formatVersion", format).put("items", items).put("cursor", cursor).put("headRevision", cursor).put("hasMore", more)
    private fun client(handle: (Request) -> Pair<Int, JSONObject>) = PhoneCloudClient(AccountEnvironment(backendBaseUrl = "https://test.invalid"), http = OkHttpClient.Builder().addInterceptor { chain ->
        val (status, json) = handle(chain.request())
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("test").body(json.toString().toResponseBody("application/json".toMediaType())).build()
    }.build()).also { it.useEpoch(session, "epoch");it.useContentGeneration(session,"22222222-2222-4222-8222-222222222222") }
    private fun posted(request: Request): JSONArray { val buffer = okio.Buffer(); request.body!!.writeTo(buffer); return JSONObject(buffer.readUtf8()).getJSONArray("items") }
    private fun sync(client: PhoneCloudClient, state: State, values: List<JSONObject> = emptyList(), apply: suspend (String, JSONObject) -> Unit = { _, _ -> }, owner: (PhoneAccountSession) -> Unit = {}) =
        PhoneCloudRecordSync(client, state, { consume -> values.forEach { consume("article", it) } }, { _, kind, value -> apply(kind, value) }, { 1 }, { _, _ -> key.copyOf() }, owner)

    @Test fun onlyChangedRecordsPostedInBoundedBatches() = runBlocking {
        val state = State(); val values = (1..120).map(::value); val sizes = mutableListOf<Int>()
        val client = client { request -> if (request.method == "GET") 200 to page() else {
            val items = posted(request); sizes += items.length()
            200 to JSONObject().put("items", JSONArray().apply { for (i in 0 until items.length()) put(JSONObject().put("recordId", items.getJSONObject(i).getString("recordId")).put("revision", "1")) })
        } }
        sync(client, state, values).sync(session, true)
        assertEquals(listOf(50, 50, 20), sizes)
        sizes.clear(); sync(client, state, values).sync(session, true); assertTrue(sizes.isEmpty())
        values[0].put("favoriteSaved", true); sync(client, state, values).sync(session, true); assertEquals(listOf(1), sizes)
    }
    @Test fun cursorOnlyAdvancesAfterAllPageApplications() = runBlocking {
        val state = State(); var applied = 0
        val items = JSONArray().apply { for (i in 1..2) { val v=value(i); val id=PhoneCloudRecordCodec.id(key, session.userId, "article", v); put(JSONObject().put("recordId", id).put("revision", "$i").put("envelope", PhoneCloudRecordCodec.encrypt(key, session.userId, id, 1, "article", v))) } }
        val client = client { 200 to page(items, "2") }
        var failed = false
        try { sync(client, state, apply={ _, _ -> if (++applied == 2) error("interrupted") }).sync(session, false) } catch (_: IllegalStateException) { failed=true }
        assertTrue(failed); assertEquals("0", state.cursor); assertEquals(1, state.rows.size)
    }
    @Test fun casConflictPullsWinnerBeforeRebuildingWrite() = runBlocking {
        val state=State(); var posts=0; var gets=0
        val local=value(1).put("favoriteSaved", false)
        val remote=value(1).put("favoriteSaved", true)
        val id=PhoneCloudRecordCodec.id(key,session.userId,"article",remote)
        val client=client { request ->
            if(request.method=="GET") {
                gets++
                val items=JSONArray()
                if(posts>0) items.put(JSONObject().put("recordId",id).put("revision","7").put("envelope",PhoneCloudRecordCodec.encrypt(key,session.userId,id,1,"article",remote)))
                200 to page(items,if(posts>0) "7" else "0")
            } else {
                posts++
                assertEquals("0",posted(request).getJSONObject(0).getString("expectedRevision"))
                409 to JSONObject().put("error","cloud_record_conflict")
            }
        }
        sync(client,state,listOf(local),apply={_,v->local.put("favoriteSaved",v.getBoolean("favoriteSaved"))}).sync(session,true)
        assertEquals(1,posts);assertEquals(2,gets);assertEquals("7",state.cursor);assertTrue(local.getBoolean("favoriteSaved"))
    }
    @Test fun serverResetInvalidatesOnlyRecordCacheAndRetriesFromZero() = runBlocking {
        val state=State(); state.cursor="9"; state.rows["old"]="9" to "oldhash"
        val requested=mutableListOf<String>()
        val client=client { request ->
            val after=request.url.queryParameter("after")!!; requested+=after
            if(after=="9") 400 to JSONObject().put("error","cloud_record_cursor_invalid") else 200 to page()
        }
        sync(client,state).sync(session,false)
        assertEquals(listOf("9","0"),requested);assertEquals("0",state.cursor);assertTrue(state.rows.isEmpty())
    }
    @Test fun paginatedPullAppliesEachPageAndCheckpointsItsCursor() = runBlocking {
        val state=State();var applied=0;val requested=mutableListOf<String>()
        val client=client { request ->
            val after=request.url.queryParameter("after")!!;requested+=after
            val indices=if(after=="0") 1..50 else 51..51
            val items=JSONArray().apply { for(i in indices) { val v=value(i);val id=PhoneCloudRecordCodec.id(key,session.userId,"article",v);put(JSONObject().put("recordId",id).put("revision","$i").put("envelope",PhoneCloudRecordCodec.encrypt(key,session.userId,id,1,"article",v))) } }
            200 to page(items,if(after=="0") "50" else "51",after=="0").put("headRevision","51")
        }
        sync(client,state,apply={_,_->applied++}).sync(session,false)
        assertEquals(listOf("0","50"),requested);assertEquals(51,applied);assertEquals("51",state.cursor)
    }
    @Test fun disabledConsentClearsStaleRecordCursorAndHashes() = runBlocking {
        val state=State();state.cursor="10";state.rows["old"]="10" to "hash"
        sync(client { 200 to page(format=0) },state).sync(session,true)
        assertEquals("0",state.cursor);assertTrue(state.rows.isEmpty())
    }
    @Test fun malformedTerminalPageDoesNotAdvanceCursor() = runBlocking {
        val state=State();var failed=false
        try { sync(client { 200 to page(cursor="2").put("headRevision","3") },state).sync(session,false) } catch (_:IllegalArgumentException) { failed=true }
        assertTrue(failed);assertEquals("0",state.cursor)
    }
    @Test fun emptyContinuationDoesNotAdvanceCursor() = runBlocking {
        val state=State();var failed=false
        try { sync(client { 200 to page(cursor="1",more=true) },state).sync(session,false) } catch (_:IllegalArgumentException) { failed=true }
        assertTrue(failed);assertEquals("0",state.cursor)
    }
    @Test fun disabledConsentNeverPushes() = runBlocking {
        var posts=0; val client=client { request -> if(request.method=="POST") posts++; 200 to page(format=0) }
        sync(client, State(), listOf(value(1))).sync(session, true); assertEquals(0, posts)
    }
    @Test fun accountSwitchAfterApplyPreventsAcknowledgment() = runBlocking {
        val state=State(); val v=value(1); val id=PhoneCloudRecordCodec.id(key, session.userId,"article",v); var switched=false
        val item=JSONObject().put("recordId",id).put("revision","1").put("envelope", PhoneCloudRecordCodec.encrypt(key,session.userId,id,1,"article",v))
        var failed=false
        try { sync(client { 200 to page(JSONArray().put(item),"1") },state,apply={_,_->switched=true},owner={check(!switched)}).sync(session,false) } catch (_:IllegalStateException) { failed=true }
        assertTrue(failed); assertTrue(state.rows.isEmpty()); assertEquals("0",state.cursor)
    }
}
