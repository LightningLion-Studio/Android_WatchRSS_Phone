package com.lightningstudio.watchrss.phone.cloud

import com.lightningstudio.watchrss.phone.account.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class CloudR48RecordSyncTest {
    private val context=RuntimeEnvironment.getApplication()
    private val key=ByteArray(32){it.toByte()}
    private val session=PhoneAccountSession("owner","***","session","",Long.MAX_VALUE)
    private val stores=mutableListOf<PhoneCloudRecordStore>()
    @Before fun fresh(){context.deleteDatabase("cloud-records-v1.db")}
    @After fun cleanup(){stores.forEach{it.close()};context.deleteDatabase("cloud-records-v1.db")}
    private fun store()=PhoneCloudRecordStore(context).also{stores+=it}
    private fun client(handle:(Request)->Pair<Int,JSONObject>)=PhoneCloudClient(AccountEnvironment(backendBaseUrl="https://test.invalid"),http=OkHttpClient.Builder().addInterceptor{c->
        val (code,json)=handle(c.request());Response.Builder().request(c.request()).protocol(Protocol.HTTP_1_1).code(code).message("fixture").body(json.toString().toResponseBody("application/json".toMediaType())).build()
    }.build()).also{it.useEpoch(session,"epoch");it.useContentGeneration(session,"22222222-2222-4222-8222-222222222222")}
    private fun raw(feature:String?=null,extra:String="{ \"wide\":9007199254740993, \"minus\":-0, \"escaped\":\"\\u4e2d\" }"):String =
        "{\"format\":\"watchrss-rss-record\",\"version\":1,\"kind\":\"article\","+(feature?:"")+"\"value\":{\"articleId\":\"a\",\"url\":\"https://example.invalid/a\",\"title\":\"old\",\"x-extra\":"+extra+"}}"
    private fun value()=JSONObject().put("articleId","a").put("url","https://example.invalid/a").put("title","edited").put("updatedAt",200)
    private val id get()=PhoneCloudRecordCodec.id(key,session.userId,"article",value())
    private fun encrypt(raw:String):JSONObject {
        val n=ByteArray(12);val k=CloudSnapshotCodec.hkdfSha256(key,session.userId.toByteArray(),"watchrss/cloud/record/v1".toByteArray(),32)
        val ct=CloudSnapshotCodec.encryptAesGcm(k,n,raw.toByteArray(),"watchrss-cloud-record-v1:${session.userId}:$id:1".toByteArray())
        return JSONObject().put("algorithm","A256GCM").put("keyVersion",1).put("nonceBase64",Base64.getEncoder().encodeToString(n)).put("ciphertextBase64",Base64.getEncoder().encodeToString(ct))
    }
    private fun page(rev:String,env:JSONObject?,after:String)=JSONObject().put("formatVersion",1).put("cursor",rev).put("headRevision",rev).put("hasMore",false).put("items",JSONArray().apply{
        if(env!=null&&after.toLong()<rev.toLong())put(JSONObject().put("recordId",id).put("revision",rev).put("envelope",env))})
    private fun sync(c:PhoneCloudClient,s:PhoneCloudRecordStore,apply:suspend(String,JSONObject)->Unit={_,_->})=PhoneCloudRecordSync(c,s,{consume->consume("article",value())},{_,kind,v->apply(kind,v)},{1},{_,_->key.copyOf()},{})
    private fun posted(r:Request)=JSONObject(okio.Buffer().also{r.body!!.writeTo(it)}.readUtf8()).getJSONArray("items")

    @Test fun actualPullCasRebaseAndPushPreserveNewestRawExtensionAcrossRestart()=runBlocking<Unit>{
        var rev="1";var env=encrypt(raw());var posts=0
        val newest="{ \"peer\":9007199254740995, \"escape\":\"\\u4e2d\", \"minus\":-0 }"
        val c=client{r->if(r.method=="GET")200 to page(rev,env,r.url.queryParameter("after")!!) else {
            posts++;if(posts==1){rev="2";env=encrypt(raw(extra=newest));409 to JSONObject().put("error","cloud_record_conflict")}else{
                val row=posted(r).getJSONObject(0);assertEquals("2",row.getString("expectedRevision"));env=row.getJSONObject("envelope");rev="3"
                200 to JSONObject().put("items",JSONArray().put(JSONObject().put("recordId",id).put("revision",rev)))
            }
        }}
        val first=store();sync(c,first).sync(session,true)
        assertEquals(2,posts);val decoded=PhoneCloudRecordCodec.decryptDocument(key,session.userId,id,env)
        assertEquals("edited",decoded.value.getString("title"));assertTrue(decoded.document.source.contains(newest))
        assertEquals(decoded.document.source,first.document(c.scopeId(session),id))
        first.close();val second=store();sync(c,second).sync(session,true);assertEquals(2,posts)
    }
    @Test fun readonlyPendingEditSurvivesRealSqliteRestartAndNeverPosts()=runBlocking<Unit>{
        val env=encrypt(raw("\"features\":{\"roCompat\":[\"future.title-bound.v1\"]},"));var posts=0;var applied=0
        val c=client{r->if(r.method=="GET")200 to page("1",env,r.url.queryParameter("after")!!)else{posts++;error("readonly post")}}
        val first=store();sync(c,first){_,v->applied++;assertEquals("old",v.getString("title"))}.sync(session,true)
        assertEquals(1,applied);assertEquals(0,posts);assertEquals(1,first.formatStatus(c.scopeId(session)).editPending)
        first.close();val second=store();assertEquals(1,second.formatStatus(c.scopeId(session)).readOnly)
        sync(c,second).sync(session,true);assertEquals(0,posts);assertEquals(env.toString(),second.rawEnvelope(c.scopeId(session),id))
    }
    @Test fun stagedReadonlyEditSurvivesNewerRemoteAndCursorResetThenUsesLatestWritableBaseline()=runBlocking<Unit>{
        var revision="1";var env=encrypt(raw("\"features\":{\"roCompat\":[\"future.title-bound.v1\"]},"));var posts=0;var reset=false
        var local=JSONObject(raw()).getJSONObject("value")
        val c=client{r->if(r.method=="GET") {
            val after=r.url.queryParameter("after")!!
            if(reset&&after=="1"){reset=false;400 to JSONObject().put("error","cloud_record_cursor_invalid")}
            else 200 to page(revision,env,after)
        }else {
            posts++;val row=posted(r).getJSONObject(0);assertEquals("3",row.getString("expectedRevision"));env=row.getJSONObject("envelope")
            200 to JSONObject().put("items",JSONArray().put(JSONObject().put("recordId",id).put("revision","4")))
        }}
        fun runner(s:PhoneCloudRecordStore)=PhoneCloudRecordSync(c,s,{consume->consume("article",local)},{_,_,v->local=v},{1},{_,_->key.copyOf()},{})
        val first=store();runner(first).sync(session,true);local=value();runner(first).sync(session,true)
        assertEquals(1,first.formatStatus(c.scopeId(session)).editPending)
        first.close();val second=store();reset=true;revision="2";env=encrypt(raw("\"features\":{\"roCompat\":[\"future.title-bound.v1\"]},",extra="{ \"peer\":9007199254740995 }"))
        runner(second).sync(session,true);assertEquals("edited",local.getString("title"));assertEquals(0,posts)
        assertEquals("edited",JSONObject(second.pendingValue(c.scopeId(session),id)!!).getString("title"))
        revision="3";env=encrypt(raw(extra="{ \"peer\":9007199254740995 }"));runner(second).sync(session,true)
        assertEquals(1,posts);assertEquals(0,second.formatStatus(c.scopeId(session)).editPending)
        val final=PhoneCloudRecordCodec.decryptDocument(key,session.userId,id,env)
        assertEquals("edited",final.value.getString("title"));assertTrue(final.document.source.contains("9007199254740995"))
    }
    @Test fun unknownIncompatIsOpaqueAndCannotOverwriteLocalRecordOrClearAccount()=runBlocking<Unit>{
        val env=encrypt(raw("\"features\":{\"incompat\":[\"future.codec.v1\"]},"));var applied=0;var posts=0
        val c=client{r->if(r.method=="GET")200 to page("1",env,r.url.queryParameter("after")!!)else{posts++;error("opaque post")}}
        val state=store();sync(c,state){_,_->applied++}.sync(session,true)
        assertEquals(0,applied);assertEquals(0,posts);assertEquals("1",state.cursor(c.scopeId(session)))
        assertEquals(env.toString(),state.quarantined(c.scopeId(session)).single().envelope)
        assertTrue(state.formatStatus(c.scopeId(session)).upgradeRequired)
        assertEquals("session",session.accessToken)
    }
    @Test fun rawBaselineIsRevisionBoundAndOlderReplayCannotDropNewExtension() {
        val s=store();s.saveDocument("scope",id,"10",raw(extra="{\"new\":-0}"),CloudFormatAccess.WRITABLE)
        s.saveDocument("scope",id,"9",raw(),CloudFormatAccess.WRITABLE)
        assertTrue(s.document("scope",id)!!.contains("{\"new\":-0}"))
        assertNull(s.document("other-owner",id))
    }
}
