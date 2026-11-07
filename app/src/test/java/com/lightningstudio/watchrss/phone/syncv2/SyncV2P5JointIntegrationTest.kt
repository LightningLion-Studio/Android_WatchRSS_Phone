package com.lightningstudio.watchrss.phone.syncv2

import androidx.room.Room
import com.lightningstudio.watchrss.phone.cloud.CloudProtocolV2
import com.lightningstudio.watchrss.phone.data.db.PhoneCompanionDatabase
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Only phone-owned synthetic actors on the separately pinned b87fe55 backend. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SyncV2P5JointIntegrationTest {
    private class Proxy:AutoCloseable {
        val server=MockWebServer();var drop:String?=null;var dropped=0
        private val http=OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).callTimeout(30,TimeUnit.SECONDS).build()
        init {server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
            val url=request.requestUrl!!;val path=url.encodedPath;check(path.startsWith("/functions/v1/sync/v2/"))
            val forward=Request.Builder().url("http://127.0.0.1:18972"+path+(url.encodedQuery?.let{"?$it"}?:""))
            for(name in request.headers.names())if(name.lowercase() !in setOf("host","connection","content-length"))forward.header(name,request.getHeader(name)!!)
            forward.method(request.method!!,if(request.method in setOf("POST","PUT"))request.body.readByteArray().toRequestBody(request.getHeader("Content-Type")?.toMediaType())else null)
            return http.newCall(forward.build()).execute().use{r->
                val raw=r.body!!.bytes()
                if(r.code==200 && drop!=null && path.endsWith(drop!!)){drop=null;dropped++;MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)}
                else MockResponse().setResponseCode(r.code).setBody(Buffer().write(raw)).also{out->for(name in r.headers.names())if(name.lowercase() !in setOf("content-length","transfer-encoding","connection"))out.setHeader(name,r.header(name)!!)}
            }
        }};server.start()}
        override fun close(){server.shutdown()}
    }
    @Test fun pinnedBackendRollbackThreeChoicesLostResponsesAndThirtySamples():Unit {
        val fixture=SyncV2JointEnvironment.directory("WATCHRSS_P5_PHONE_JOINT");val evidence=SyncV2JointEnvironment.directory("WRSS_P5_EVIDENCE")
        val owner=SyncV2Wire.parse(File(fixture,".owner.json").readBytes());check(owner.getBoolean("phoneP5Owned") && owner.getInt("backendPort")==18972)
        val pin=SyncV2Wire.parse(File(evidence,"backend-pin.json").readBytes());check(pin.getString("sourceCommit")=="b87fe55aa38fe934dbe46fb61b1f39ab8c3944a9" && pin.getString("binarySha256").startsWith("ccca16f1") && pin.getBoolean("runningExecutableMatches"))
        val phone=SyncV2Wire.parse(File(fixture,"phone.json").readBytes());val watch=SyncV2Wire.parse(File(fixture,"watch.json").readBytes())
        check(listOf(phone,watch).all{it.getBoolean("syntheticOnly") && it.getString("baseUrl")=="http://127.0.0.1:18972"})
        val http=OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).callTimeout(30,TimeUnit.SECONDS).build()
        val bootReq=Request.Builder().url("http://127.0.0.1:18972/functions/v1/cloud/bootstrap").post("{}".toRequestBody("application/json".toMediaType()))
        val headers=phone.getJSONObject("headers");for(k in headers.keys())bootReq.header(k,headers.getString(k))
        val boot=http.newCall(bootReq.build()).execute().use{check(it.code==200);SyncV2Wire.parse(it.body!!.bytes())}
        val key=SyncV2Codec.unhex(phone.getJSONObject("crypto").getString("contentKeyHex"),32)
        val trust=SyncV2ServiceTrust.fromBootstrap(phone.getString("accountId"),boot.getJSONObject("syncV2"),phone.getJSONObject("servicePin")){error("service pin changed")}
        val devices=boot.getJSONArray("devices");val approved=(0 until devices.length()).map{devices.getJSONObject(it)}.filter{it.isNull("revokedAt") && it.optInt("attestationKeyVersion")==1 && it.getString("attestation")==CloudProtocolV2.attestation(key,phone.getString("userId"),phone.getString("legacyLibraryEpoch"),it.getString("deviceId"),Base64.getDecoder().decode(it.getString("publicKeySpki")))}.map{it.getString("deviceId")}.toSet()
        check(approved.size==4)
        fun client(f:JSONObject,base:String="http://127.0.0.1:18972/functions/v1/sync/v2/"):SyncV2CloudClient {
            val h=f.getJSONObject("headers");val session=SyncV2CloudClient.Session(f.getString("userId"),f.getString("legacyDeviceId"),f.getString("legacyLibraryEpoch"),f.getString("legacyContentGeneration"),SyncV2Codec.scope(f.getJSONObject("scope")),f.getString("syncDeviceId"),h.getString("Authorization").removePrefix("Bearer "),"",h.optString("x-watchrss-device-authorization").takeIf{it.isNotEmpty()})
            return SyncV2CloudClient(base.toHttpUrl(),{session},trust,true,http).also{it.capabilities()}
        }
        fun signer(f:JSONObject):SyncV2Signing {val k=f.getJSONObject("signingKey");return SyncV2Signing.fromPrivate(SyncV2Codec.unhex(k.getString("privateKeyHex"),32),f.getString("syncDeviceId"),k.getString("keyId"))}
        val direct=client(phone);val descriptor=direct.descriptor();val identity=SyncV2Identity.recover(SyncV2Wire.parse(direct.recovery(descriptor.getString("recoveryOid")),8192,true),descriptor.getJSONObject("descriptor"),key,phone.getString("accountId"),phone.getJSONObject("scope"),descriptor.getString("descriptorOid"))
        val checkpointStart=System.nanoTime();val checkpoint=direct.checkpoint().getJSONObject("body");val at=System.nanoTime();val serverUtc=checkpoint.getLong("issuedAtMillis")+(at-checkpointStart)/1000000+1;fun now()=serverUtc+(System.nanoTime()-at)/1000000
        val root=java.nio.file.Files.createTempDirectory(fixture.toPath(),"phone-p5-jvm-").toFile();val dbs=mutableListOf<PhoneCompanionDatabase>()
        fun database(name:String)=Room.databaseBuilder(org.robolectric.RuntimeEnvironment.getApplication(),PhoneCompanionDatabase::class.java,File(root,"$name.db").absolutePath).allowMainThreadQueries().build().also{dbs.add(it)}
        val result=JSONObject().put("backendCommit",pin.get("sourceCommit")).put("backendBinarySha256",pin.get("binarySha256")).put("ownedSyntheticAccount",true).put("sharedFixturesMutated",false).put("physicalDevices",false)
        var phase="initial"
        fun save(){evidence.mkdirs();File(evidence,"joint-summary.json").writeText(result.put("phase",phase).toString(2)+"\n")}
        fun drain(p:SyncV2Projection){repeat(30){if(!p.runWindow().paused)return};error("projection_incomplete")}
        Proxy().use{proxy->try {
            val publisher=database("watch");val wp=SyncV2Projection(publisher.openHelper.writableDatabase,identity,SyncV2Codec.random(32),true).also{it.install()};val wc=client(watch);val ws=signer(watch)
            val publish=SyncV2CloudSync(wc,identity,wp,{it in approved},{key},now={now()})
            val records=javaClass.classLoader!!.getResourceAsStream("syncv2-business-schema.json")!!.bufferedReader().use{JSONObject(it.readText())}.getJSONArray("records")
            fun record(kind:String):JSONObject {val r=(0 until records.length()).map{records.getJSONObject(it)}.single{it.getString("kind")==kind};val v=JSONObject(r.getJSONObject("value").toString());val id=SyncV2Business.logicalId(kind,v);val pair=SyncV2Business.split(kind,id,identity.entity(kind,id),v,null);return JSONObject().put("meta",pair.meta).put("state",pair.state).put("value",v)}
            val note=record("note");val folder=record("folder");val sql=publisher.openHelper.writableDatabase
            phase="seed-baseline";save();publish.readCurrent();sql.beginTransaction();try{SyncV2PhoneApply(sql).apply(emptyList(),listOf(note));sql.setTransactionSuccessful()}finally{sql.endTransaction()};drain(wp)
            val first=publish.publishIndex(ws,key,1).getJSONObject("account").getString("commitOid");publish.readCurrent()
            sql.beginTransaction();try{SyncV2PhoneApply(sql).apply(listOf(note),listOf(note,folder));sql.setTransactionSuccessful()}finally{sql.endTransaction()};drain(wp);publish.publishIndex(ws,key,1)
            val watchActions=SyncV2AccountActions(wc,identity,wp,ws,{key},{it in approved},File(root,"watch-files"),true,{now()});watchActions.refresh();drain(wp)
            val room=database("phone");val projection=SyncV2Projection(room.openHelper.writableDatabase,identity,SyncV2Codec.random(32),true).also{it.install()};val pc=client(phone,proxy.server.url("/functions/v1/sync/v2/").toString());val ps=signer(phone)
            val actions=SyncV2AccountActions(pc,identity,projection,ps,{key},{it in approved},File(root,"phone-files"),true,{now()})
            phase="phone-follow";save();actions.refresh();drain(projection)
            val noteId=note.getJSONObject("value").getString("noteId");val phoneSql=room.openHelper.writableDatabase
            fun edit(value:String,titleOnly:Boolean=false){if(titleOnly)phoneSql.execSQL("UPDATE notes SET title=? WHERE noteId=?",arrayOf(value,noteId))else phoneSql.execSQL("UPDATE notes SET markdown=?,plainText=?,contentHash=?,updatedAt=? WHERE noteId=?",arrayOf<Any>(value,value,com.lightningstudio.watchrss.phone.data.note.MarkdownNoteCodec.sha256(value),now(),noteId));drain(projection)}
            fun rollback(){watchActions.refresh();drain(wp);watchActions.rollback(watchActions.captureRollback(),first);actions.refresh();drain(projection);assertEquals("awaiting-user",projection.status())}
            val choices=JSONArray()
            for(choice in listOf("cloud","local","merge")) {
                phase="choice-$choice";save();edit("P5 $choice synthetic edit",titleOnly=choice=="merge");rollback();val preview=actions.preview();if(choice=="merge")assertTrue("merge unavailable ${preview.reason}",preview.canMerge)
                proxy.drop=if(choice=="cloud")"choices/cloud"else if(choice=="local")"account/publish"else null
                val response=actions.choose(preview,choice);assertTrue(response.getBoolean("localCommitted"));assertTrue(projection.state().body.isNull("pendingChoice"));drain(projection)
                choices.put(JSONObject().put("choice",choice).put("generation",projection.state().body.getString("rollbackGeneration")).put("commitOid",projection.state().body.getJSONObject("account").getString("oid")));result.put("choices",choices);save()
            }
            assertEquals(2,proxy.dropped);result.put("lostResponsesRecovered",proxy.dropped).put("historyItems",actions.history().getJSONArray("items").length()).put("recoveryCopies",actions.recoveryCopies().length())
            val samples=JSONArray();val rounds=(System.getenv("WATCHRSS_P5_SAMPLES")?.toIntOrNull()?:30).also{require(it in 30..1000)}
            repeat(rounds){n->phase="sample-${n+1}";edit("sample-$n",true);rollback();val preview=actions.preview();val started=System.nanoTime();actions.choose(preview,"cloud");samples.put((System.nanoTime()-started)/1000000);drain(projection);if((n+1)%10==0){result.put("sampleCount",n+1);save()}}
            val sorted=(0 until samples.length()).map{samples.getLong(it)}.sorted();result.put("sampleCount",samples.length()).put("choiceApplyMillis",samples).put("maxMillis",sorted.last()).put("p99Reported",samples.length()>=1000)
            if(samples.length()>=1000)result.put("p99Millis",sorted[(samples.length()*99+99)/100-1])
            result.put("status","passed");phase="complete";save()
        }catch(e:Throwable){result.put("status","failed").put("error",if(e is SyncV2CloudError)e.code else e.message?.takeIf{Regex("[a-z][a-z0-9_]{0,63}").matches(it)}?:e.javaClass.simpleName)
            result.put("frames",JSONArray(e.stackTrace.filter{it.className.startsWith("com.lightningstudio.watchrss.phone.syncv2")}.take(8).map{"${it.className.substringAfterLast('.')}::${it.methodName}:${it.lineNumber}"}));save();throw AssertionError("P5 joint failed at $phase; safe details in joint-summary.json")
        }finally{dbs.forEach{it.close()};key.fill(0);root.deleteRecursively()}}
    }
}
