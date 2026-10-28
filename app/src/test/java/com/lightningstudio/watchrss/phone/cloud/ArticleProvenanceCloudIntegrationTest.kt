package com.lightningstudio.watchrss.phone.cloud

import androidx.room.Room
import com.lightningstudio.watchrss.phone.account.*
import com.lightningstudio.watchrss.phone.data.backup.WatchRssBackupService
import com.lightningstudio.watchrss.phone.data.db.PhoneCompanionDatabase
import com.lightningstudio.watchrss.phone.data.reader.ReaderPresetRepository
import com.lightningstudio.watchrss.phone.data.repo.*
import kotlinx.coroutines.*
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

/** Real production AEAD -> HTTP pagination -> SQLite witnesses -> native repository -> old writer. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class ArticleProvenanceCloudIntegrationTest {
    private val context=RuntimeEnvironment.getApplication()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    private val db=Room.inMemoryDatabaseBuilder(context,PhoneCompanionDatabase::class.java)
        .addCallback(PhoneCompanionDatabase.ARTICLE_PROVENANCE_CALLBACK).allowMainThreadQueries().build()
    private val native=ArticleProvenanceStore(db)
    private val repository=PhoneCompanionRepository(db.phoneSavedItemDao(),db.phoneArticleDao(),db.phoneRssSourceDao(),"phone",appMetaDao=db.appMetaDao(),articleProvenance=native)
    private val backup=WatchRssBackupService(context,db,repository,ReaderPresetRepository(context,db,db.readerPresetDao(),"phone",scope),"phone")
    private val session=PhoneAccountSession(ArticleProvenanceFixtures.user,"***","session","",Long.MAX_VALUE)
    private var records:PhoneCloudRecordStore?=null
    @Before fun fresh(){context.deleteDatabase("cloud-records-v1.db")}
    @After fun close(){scope.cancel();records?.close();db.close();context.deleteDatabase("cloud-records-v1.db")}
    private fun store()=PhoneCloudRecordStore(context).also{records=it}
    private fun input()=JSONObject(ArticleProvenanceFixtures.resource("vectors.json").toString(Charsets.UTF_8)).getJSONArray("mergeCases").getJSONObject(0).getJSONArray("inputs").getJSONObject(0).toString()
    private fun id(raw:String)=PhoneCloudRecordCodec.id(ArticleProvenanceFixtures.key,session.userId,"article",JSONObject(raw).getJSONObject("value"))
    private fun envelope(raw:String)=ArticleProvenanceFixtures.envelope(raw.toByteArray(),id(raw))
    private fun client(handle:(Request)->Pair<Int,JSONObject>)=PhoneCloudClient(AccountEnvironment(backendBaseUrl="https://test.invalid"),http=OkHttpClient.Builder().addInterceptor{c->
        val(code,body)=handle(c.request());Response.Builder().request(c.request()).protocol(Protocol.HTTP_1_1).code(code).message("fixture").body(body.toString().toResponseBody("application/json".toMediaType())).build()
    }.build()).also{it.useEpoch(session,"epoch");it.useContentGeneration(session,"22222222-2222-4222-8222-222222222222")}
    private fun runner(c:PhoneCloudClient,s:PhoneCloudRecordStore)=PhoneCloudRecordSync(c,s,backup::cloudRecordPages,{_,kind,v->backup.applyCloudRecord(kind,v)},{1},{_,_->ArticleProvenanceFixtures.key.copyOf()},{})
    private fun page(revision:Int,raw:String,after:String)=JSONObject().put("formatVersion",1).put("cursor",revision.toString()).put("headRevision",revision.toString()).put("hasMore",false)
        .put("items",JSONArray().apply{if(after.toInt()<revision)put(JSONObject().put("recordId",id(raw)).put("revision",revision.toString()).put("envelope",envelope(raw)))})

    @Test fun nativePendingAndAllAuthenticatedVersionsSurviveNewRemoteCursorResetAndCacheRestart()=runBlocking<Unit> {
        var revision=1;var raw=CloudRawDocument.parse(input()).patch(listOf("value","future"),"{\"n\":1e+03,\"s\":\"\\u0061\"}").source
        var posts=0;var reset=false
        val c=client{r->assertNull(r.header("x-watchrss-r48-features"));if(r.method=="GET") {
            val after=r.url.queryParameter("after")!!
            if(reset&&after!="0"){reset=false;400 to JSONObject().put("error","cloud_record_cursor_invalid")}else 200 to page(revision,raw,after)
        }else{posts++;error("read-only object must not be posted")}}
        var s=store();runner(c,s).sync(session,true)
        val before=native.record("synthetic-article")!!
        assertEquals(1,s.formatStatus(c.scopeId(session)).readOnly)
        assertThrows(CloudFormatUpgradeRequired::class.java){runBlocking{repository.markArticleRead("synthetic-article",true)}}
        assertEquals(1,native.pendingCount())
        revision=2;reset=true
        raw=CloudRawDocument.parse(raw).patch(listOf("value","readingPositionChangedAt"),"500").patch(listOf("value","readingProgress"),"0.1234565").source
        s.close();s=store();runner(c,s).sync(session,true)
        val accepted=native.record("synthetic-article")!!
        assertEquals(500L,accepted.value.getLong("readingPositionChangedAt"));assertEquals(.123457,accepted.value.getDouble("readingProgress"),0.0)
        assertEquals(before.value.getJSONObject("registerSources").toString(),accepted.value.getJSONObject("registerSources").toString())
        assertEquals(1,native.pendingCount());assertEquals(0,posts)
        assertEquals(2,s.historicalDocuments(c.scopeId(session),id(raw)).size)
        assertTrue(s.document(c.scopeId(session),id(raw))!!.contains("1e+03"));assertNotNull(s.rawEnvelope(c.scopeId(session),id(raw)))
        assertTrue(s.historicalDocuments("other-account",id(raw)).isEmpty())
        s.resetCursor(c.scopeId(session));assertEquals(2,s.historicalDocuments(c.scopeId(session),id(raw)).size)
        s.clear(c.scopeId(session)) // R45 cache reset must not delete business witnesses or local drafts.
        assertEquals(accepted.canonicalUtf8(),ArticleProvenanceStore(db).record("synthetic-article")!!.canonicalUtf8())
        assertEquals(1,ArticleProvenanceStore(db).pendingCount())
        assertThrows(CloudFormatUpgradeRequired::class.java){runBlocking{backup.createCloudPrivateArchive()}}
        assertThrows(CloudFormatUpgradeRequired::class.java){runBlocking{repository.getArticlesForBackup()}}
    }
    @Test fun unknownRoCompatUsesNativeProtectionAndDoesNotDowngradeOrEraseTheDraft()=runBlocking {
        val raw=CloudRawDocument.parse(input()).remove(listOf("value","registerSources"))
            .patch(listOf("features","roCompat"),"[\"future.article.v1\"]").patch(listOf("value","future"),"{\"wide\":9007199254740993,\"zero\":-0}").source
        var posts=0;val c=client{r->if(r.method=="GET")200 to page(1,raw,r.url.queryParameter("after")!!)else{posts++;error("unknown roCompat write")}}
        val s=store();runner(c,s).sync(session,true)
        assertNotNull(db.phoneArticleDao().getById("synthetic-article"))
        assertThrows(CloudFormatUpgradeRequired::class.java){runBlocking{repository.deleteArticle("synthetic-article")}}
        runner(c,s).sync(session,true)
        assertEquals(0,posts);assertEquals(1,native.pendingCount());assertEquals(raw,native.state("synthetic-article")!!.readableRecordJson)
        assertEquals(raw,s.historicalDocuments(c.scopeId(session),id(raw)).single())
        assertFalse(db.phoneArticleDao().getById("synthetic-article")!!.deleted)
    }
    @Test fun mixedKnownTitleBindingAndProvenanceFeaturesAreReadableButNeverWritable() {
        val base=CloudRawDocument.parse(input());val value=base.json().getJSONObject("value")
        val raw=base.patch(listOf("features","roCompat"),"[\"article-register-provenance.v1\",\"r48.title-bound.v1\"]")
            .patch(listOf("value","titleBinding"),CloudFormatR48.titleBinding(value).toString()).source
        val decoded=ArticleProvenanceFixtures.decode(raw)
        assertEquals(CloudFormatAccess.READ_ONLY,decoded.access);assertTrue(decoded.value is ArticleProvenanceValue)
        assertEquals(raw,decoded.document.source)
        assertThrows(CloudFormatUpgradeRequired::class.java){PhoneCloudRecordCodec.encrypt(ArticleProvenanceFixtures.key,session.userId,id(raw),1,"article",decoded.value)}
    }
    @Test fun realDeviceRegistrationDeclaresAcceptedReaderButNoWriterOrFeatureWriteHeader()=runBlocking {
        var calls=0
        val c=client{r->
            calls++;assertTrue(r.url.encodedPath.endsWith("/devices/register"));assertNull(r.header("x-watchrss-r48-features"))
            val body=JSONObject(okio.Buffer().also{r.body!!.writeTo(it)}.readUtf8()).getJSONObject("capabilities")
            assertTrue(body.getBoolean("cloudArticleProvenanceReadV1"));assertFalse(body.getBoolean("cloudArticleProvenanceWriteV1"))
            200 to JSONObject()
        }
        c.registerDevice(session,"synthetic-device","Synthetic phone","fixture-spki",1)
        assertEquals(1,calls)
    }
    @Test fun cacheResetCannotDowngradeProtectedNativeArticleOrPreventAnUnrelatedRecordCas()=runBlocking {
        native.accept(ArticleRegisterProvenance.decode(input().toByteArray()))
        backup.applyCloudRecord("source",JSONObject().put("url","https://watchrss.local/import-content/txt/unrelated").put("title","unrelated").put("updatedAt",1))
        var posts=0
        val c=client{r->if(r.method=="GET")200 to JSONObject().put("formatVersion",1).put("cursor","0").put("headRevision","0").put("hasMore",false).put("items",JSONArray())
            else {
                posts++;val items=JSONObject(okio.Buffer().also{r.body!!.writeTo(it)}.readUtf8()).getJSONArray("items")
                assertEquals(1,items.length());val row=items.getJSONObject(0)
                val decoded=PhoneCloudRecordCodec.decryptDocument(ArticleProvenanceFixtures.key,session.userId,row.getString("recordId"),row.getJSONObject("envelope"))
                assertEquals("source",decoded.kind)
                200 to JSONObject().put("items",JSONArray().put(JSONObject().put("recordId",row.getString("recordId")).put("revision","1")))
            }}
        val s=store();runner(c,s).sync(session,true)
        assertEquals(1,posts);assertNotNull(s.pendingValue(c.scopeId(session),id(input())))
        assertNotNull(native.record("synthetic-article"))
    }
    @Test fun olderReplayRetainsItsAuthenticatedHistoryWithoutReplacingTheNewerBaselineEnvelope() {
        val s=store();val recordId=id(input());val latest=CloudRawDocument.parse(input()).patch(listOf("value","future"),"{\"n\":1e+03}").source
        val latestEnvelope=envelope(latest).toString();val olderEnvelope=envelope(input()).toString()
        s.acceptDocument("scope",recordId,"10",latest,CloudFormatAccess.READ_ONLY,latestEnvelope)
        s.acceptDocument("scope",recordId,"9",input(),CloudFormatAccess.READ_ONLY,olderEnvelope)
        assertEquals(latest,s.document("scope",recordId));assertEquals(latestEnvelope,s.rawEnvelope("scope",recordId))
        assertEquals(2,s.historicalDocuments("scope",recordId).size)
    }
}
