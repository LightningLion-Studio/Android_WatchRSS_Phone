package com.lightningstudio.watchrss.phone.syncv2

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import com.lightningstudio.watchrss.phone.PhoneCompanionContainer
import com.lightningstudio.watchrss.phone.cloud.CloudHeadReply
import com.lightningstudio.watchrss.phone.cloud.CloudKeyManager
import com.lightningstudio.watchrss.phone.cloud.CloudProtocolV2
import com.lightningstudio.watchrss.phone.cloud.PhoneCloudHeadPoller
import com.lightningstudio.watchrss.phone.cloud.PhoneCloudSyncResult
import com.lightningstudio.watchrss.phone.data.db.PhoneCompanionDatabase
import com.lightningstudio.watchrss.phone.account.LoginProgress
import com.lightningstudio.watchrss.phone.cloud.CloudSyncPhase
import com.lightningstudio.watchrss.phone.cloud.CloudSyncSingleFlight
import com.lightningstudio.watchrss.phone.cloud.MissingCloudBodyException
import com.lightningstudio.watchrss.phone.cloud.PhoneCloudSyncState
import com.lightningstudio.watchrss.phone.data.local.ConditionalBodyFile
import com.lightningstudio.watchrss.phone.data.local.FileArticleContentStore
import com.lightningstudio.watchrss.phone.data.local.articleContentMarkerFileName
import com.lightningstudio.watchrss.phone.testing.MemoryPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.mockwebserver.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Only the HTTP service/host boundary is synthetic. All phone product components are real. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SyncV2P6ProductTest {
    private val user="12121212-1212-1212-1212-121212121212"
    private class Server:AutoCloseable {
        val http=MockWebServer();val receipt=SyncV2Signing.fromPrivate(ByteArray(31)+byteArrayOf(2),"ee".repeat(16),"ef".repeat(16))
        val now=System.currentTimeMillis();val wrap=ByteArray(32){7}
        val announcement=JSONObject().put("algorithm","ES256").put("receiptKeyId","cd".repeat(16)).put("receiptSigningPublicKey",SyncV2Codec.b64(SubjectPublicKeyInfo(
            AlgorithmIdentifier(ASN1ObjectIdentifier("1.2.840.10045.2.1"),ASN1ObjectIdentifier("1.2.840.10045.3.1.7")),SyncV2Codec.unhex(receipt.publicHex,65)).encoded))
        var descriptor:JSONObject?=null;var capsule:JSONObject?=null
        var readGate=true;var descriptorUnavailable=false;var failAfterConflict=false;var dropInit=false;val initBytes=mutableListOf<String>()
        val objects=linkedMapOf<String,ByteArray>();val prefixes=linkedMapOf<String,ByteArray>();val totals=linkedMapOf<String,Int>()
        val bindings=linkedMapOf<String,JSONObject>();val refs=linkedMapOf<String,JSONObject>();val devices=linkedMapOf<String,JSONObject>();val results=linkedMapOf<String,JSONObject>()
        val boundAliases=linkedMapOf<String,String>();val registrationBodies=mutableListOf<JSONObject>()
        var negotiateStepMillis=0L;var timeOffsetMillis=0L;var expiredNegotiate=false;var checkpoints=0
        var account=JSONObject().put("commitOid",JSONObject.NULL).put("revision","0").put("rollbackGeneration","0")
        var closedBodyChannel=false;var bodyRequests=0
        var headResponse: () -> MockResponse = { MockResponse().setResponseCode(404) }
        var deviceAuthorization = "Bearer synthetic-device"
        val headRequests = java.util.concurrent.CopyOnWriteArrayList<RecordedRequest>()
        var blockBodyList = false
        var publishes=0;var acks=0;var inits=0;var queries=0;var dropPublish=false;var failQuery=false
        var block:String?=null;val entered=CountDownLatch(1);val unblock=CountDownLatch(1)
        var concurrentWinner:SyncV2RepoBootstrap.Candidate?=null
        fun scope()=descriptor!!.getJSONObject("body").getJSONObject("scope")
        fun dto(s:JSONObject)=JSONObject().put("version",2).put("scope",s)
        fun response(v:JSONObject)=MockResponse().setHeader("Content-Type","application/json").setBody(SyncV2Codec.canonical(v))
        fun error(code:String)=response(JSONObject().put("error",JSONObject().put("code",code).put("retryable",code=="ref_conflict").put("retryAfterMillis",JSONObject.NULL).put("details",if(code=="ref_conflict")JSONObject().put("currentOid",account.get("commitOid")).put("currentRevision",account.get("revision"))else JSONObject()))).setResponseCode(if(code=="object_missing")404 else if(code=="temporarily_unavailable")503 else 409)
        fun window(s:JSONObject)=dto(s).put("windowId","aa".repeat(16)).put("issuedAtMillis",now+timeOffsetMillis).put("expiresAtMillis",now+timeOffsetMillis+300000)
            .put("historyFloorMillis",0).put("historyFloorRevision","0").put("serverRevision",account.get("revision")).put("refs",JSONArray(devices.values.sortedBy{it.getString("deviceId")})).put("account",JSONObject(account.toString()))
        fun receipt(s:JSONObject,oid:String,total:Int,offset:Int)=receipt.signed("wrss.sync.v2/object-receipt-sign",JSONObject().put("version",2).put("scope",s).put("oid",oid).put("totalBytes",total).put("durableOffset",offset)
            .put("storeId","99".repeat(16)).put("receiptRevision","1").put("createdAtMillis",now).put("expiresAtMillis",now+604800000))
        init {
            http.dispatcher=object:Dispatcher(){override fun dispatch(r:RecordedRequest):MockResponse {
                try {
                    assertEquals("Bearer synthetic-account",r.getHeader("Authorization"));assertEquals("synthetic-api",r.getHeader("apikey"))
                    val route=r.requestUrl!!.encodedPath.removePrefix(SyncV2Origin.path)
                    if (route == "account/head") {
                        headRequests += r
                        return headResponse()
                    }
                    assertEquals(deviceAuthorization,r.getHeader("x-watchrss-device-authorization"))
                    if(closedBodyChannel && r.requestUrl!!.encodedPath.startsWith("/functions/v1/cloud/bodies/v1/")) {
                        bodyRequests++
                        if (r.method == "GET" && blockBodyList) {
                            blockBodyList = false
                            entered.countDown()
                            assertTrue(unblock.await(10, TimeUnit.SECONDS))
                        }
                        if(r.method!="GET")return error("body_channel_write_disabled").setResponseCode(503)
                        return response(JSONObject().put("version",1).put("purpose",SyncV2BodyCodec.PURPOSE).put("scope",scope()).put("carriers",JSONArray()).put("nextAfter",JSONObject.NULL))
                    }
                    if(route==block){block=null;entered.countDown();assertTrue(unblock.await(10,TimeUnit.SECONDS))}
                    val s=JSONObject().put("repoId",r.requestUrl!!.queryParameter("repoId")).put("libraryEpoch",r.requestUrl!!.queryParameter("libraryEpoch")).put("contentGeneration",r.requestUrl!!.queryParameter("contentGeneration"))
                    if(!readGate && route!="capabilities")return error("feature_disabled")
                    fun body()=SyncV2Wire.parse(r.body.readByteArray())
                    when {
                        route=="capabilities"->return response(dto(s).put("minVersion",2).put("maxVersion",2).put("maxPartBytes",65536).put("historyDays",7).put("features",JSONArray())
                            .put("receiptKeyId",announcement.get("receiptKeyId")).put("receiptSigningPublicKey",announcement.get("receiptSigningPublicKey")).put("flags",JSONObject().put("read",readGate).put("indexWrite",readGate).put("contentWrite",readGate)))
                        route=="repo/descriptor"->{if(descriptorUnavailable)return error("temporarily_unavailable");val d=descriptor?:return error("object_missing");return response(dto(scope()).put("descriptor",d).put("descriptorOid",SyncV2Codec.oid("repo-descriptor",d)).put("recoveryOid",SyncV2Codec.oid("id-key-recovery",capsule)))}
                        route.startsWith("repo/id-key-recovery/")->return response(capsule!!)
                        route=="repo/init"->{
                            inits++;val b=body();initBytes.add(SyncV2Codec.canonical(b));concurrentWinner?.let{descriptor=it.descriptor;capsule=it.recoveryCapsule;concurrentWinner=null}
                            if(descriptor!=null && SyncV2Codec.canonical(descriptor)!=SyncV2Codec.canonical(b.getJSONObject("descriptor")))run{if(failAfterConflict)descriptorUnavailable=true;return error("ref_conflict")}
                            descriptor=b.getJSONObject("descriptor");capsule=b.getJSONObject("recoveryCapsule")
                            boundAliases[r.getHeader("x-watchrss-cloud-device-id")!!]=descriptor!!.getJSONObject("body").getString("creatorDeviceId")
                            if(dropInit){dropInit=false;return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)}
                            return response(dto(s).put("descriptorOid",SyncV2Codec.oid("repo-descriptor",descriptor)))
                        }
                        route=="checkpoint"->{checkpoints++;val w=window(s).apply{remove("version")}.put("checkpointId","33".repeat(16));return response(receipt.signed("wrss.sync.v2/checkpoint-sign",w).put("version",2).put("scope",s))}
                        route=="negotiate"->{timeOffsetMillis+=negotiateStepMillis;return response(window(s).also{if(expiredNegotiate)it.put("expiresAtMillis",now-1)})}
                        route=="account"->return response(window(s))
                        route=="devices/signing-key"->{
                            val b=body();val v=b.getJSONObject("body");val id=v.getString("deviceId")
                            registrationBodies.add(JSONObject(b.toString()))
                            val legacy=r.getHeader("x-watchrss-cloud-device-id")!!
                            if(boundAliases[legacy]?.let{it!=id}==true || boundAliases.any{it.key!=legacy && it.value==id})return error("invalid_schema").setResponseCode(400)
                            boundAliases[legacy]=id
                            val map=JSONObject().put("scope",s).put("deviceId",id).put("keyId",v.get("keyId")).put("legacyDeviceId",r.getHeader("x-watchrss-cloud-device-id")).put("approvalRevision","1")
                            bindings[id]=b.put("deviceMapping",receipt.signed("wrss.sync.v2/device-mapping",map));return response(dto(s).put("registered",true))
                        }
                        route=="devices/signing-keys"->return response(dto(s).put("bindings",JSONArray(bindings.values)).put("revocationRevision","0"))
                        route=="objects/reserve"->{val a=body().getJSONArray("objects");for(i in 0 until a.length()){val v=a.getJSONObject(i);totals[v.getString("oid")]=v.getInt("totalBytes");v.put("durableOffset",0)}
                            return response(dto(s).put("reservationId","44".repeat(16)).put("expiresAtMillis",now+600000).put("reservedBytes",0).put("objects",a))}
                        route=="objects/have-want"->{val list=body().getJSONArray("oids");val rows=JSONArray()
                            for(i in 0 until list.length()){val oid=list.getString(i);val data=objects[oid];val off=data?.size?:prefixes[oid]?.size?:0;val total=data?.size?:totals[oid]?:0
                                rows.put(JSONObject().put("oid",oid).put("status",if(data!=null)"present"else if(off>0)"partial"else"missing").put("totalBytes",if(off==0)JSONObject.NULL else total).put("durableOffset",off).put("receipt",if(off==0)JSONObject.NULL else receipt(s,oid,total,off)))}
                            return response(dto(s).put("windowId","aa".repeat(16)).put("objects",rows))}
                        route.startsWith("objects/") && "/parts/" in route->{val oid=route.substringAfter("objects/").substringBefore("/parts/");val off=route.substringAfter("/parts/").toInt();val raw=r.body.readByteArray();val old=prefixes[oid]?:byteArrayOf()
                            assertEquals(old.size,off);prefixes[oid]=old+raw;return response(dto(s).put("oid",oid).put("durableOffset",off+raw.size).put("receipt",receipt(s,oid,totals.getValue(oid),off+raw.size)))}
                        route.startsWith("objects/") && route.endsWith("/complete")-> {val oid=route.substringAfter("objects/").substringBefore("/complete");objects[oid]=prefixes.getValue(oid);return response(dto(s).put("oid",oid).put("receipt",receipt(s,oid,totals.getValue(oid),objects.getValue(oid).size)))}
                        route=="state-refs" || route=="recipe-refs"->{val b=body();val type=route.substringBefore('-');val oid=SyncV2Codec.oid(route,b);val raw=SyncV2Codec.utf8(SyncV2Codec.canonical(b));objects[oid]=raw
                            val signer=bindings.values.first{v->val pub=SyncV2Codec.hex(SyncV2Codec.decode64(v.getJSONObject("body").getString("publicKey"),65));SyncV2Identity.verifySignature(pub,SyncV2Codec.lp(SyncV2Codec.utf8("wrss.sync.v2/$route-sign"),SyncV2Codec.utf8(SyncV2Codec.canonical(b.getJSONObject("body")))),b.getString("signature"))}
                            val key=signer.getJSONObject("body");refs[b.getJSONObject("body").getString("${type}Oid")]=dto(s).put("parentOid",b.getJSONObject("body").get("${type}Oid")).put("refsOid",oid).put("authorDeviceId",key.get("deviceId")).put("keyId",key.get("keyId")).put("totalBytes",raw.size)
                            return response(dto(s).put("accepted",true))}
                        route.startsWith("state-refs/") || route.startsWith("recipe-refs/")->return response(refs.getValue(route.substringAfter('/')))
                        route.startsWith("objects/")->{val oid=route.substringAfter("objects/");return MockResponse().setHeader("ETag",oid).setBody(Buffer().write(objects.getValue(oid)))}
                        route=="account/ancestry"->{var at=r.requestUrl!!.queryParameter("head");val stop=r.requestUrl!!.queryParameter("stop");val a=JSONArray();while(at!=null && at!=stop && a.length()<64){val h=SyncV2Wire.parse(objects.getValue(at),262144,true);a.put(JSONObject(h.toString()).put("commitOid",at).put("headerOid",SyncV2Codec.oid("commit-header",h)));at=h.getJSONObject("body").optString("accountParent").takeIf{it!="null" && it.isNotEmpty()}}
                            return response(dto(s).put("head",r.requestUrl!!.queryParameter("head")).put("stop",stop?:JSONObject.NULL).put("headers",a).put("nextOid",at?:JSONObject.NULL).put("complete",at==stop))}
                        route.startsWith("requests/")->{queries++;if(failQuery)return error("temporarily_unavailable");val old=results[route.substringAfter('/')];return response(dto(s).put("status",if(old==null)"unknown"else"succeeded").put("response",old?:JSONObject.NULL))}
                        route=="account/publish"->{val v=body();val a=v.getJSONObject("account").getJSONObject("body");val d=v.getJSONObject("device").getJSONObject("body");val id=a.getString("requestId");results[id]?.let{return response(it)}
                            if(a.get("expectedOid")!=account.get("commitOid") || a.get("expectedRevision")!=account.get("revision"))return error("ref_conflict")
                            val oid=a.getString("newCommitOid");val commit=SyncV2Wire.parse(objects.getValue(oid),262144,true);val cb=commit.getJSONObject("body");assertEquals(SyncV2Codec.oid("commit",cb),oid);assertEquals(account.get("commitOid"),cb.get("accountParent"))
                            SyncV2Index.read(s,cb.getString("treeRoot"),objects::getValue)
                            publishes++;account=JSONObject().put("commitOid",oid).put("revision",SyncV2Codec.next(account.getString("revision"))).put("rollbackGeneration","0")
                            val device=JSONObject().put("deviceId",cb.get("authorDeviceId")).put("commitOid",oid).put("refRevision",SyncV2Codec.next(d.getString("expectedRevision")));devices[cb.getString("authorDeviceId")]=device
                            val result=dto(s).put("account",JSONObject(account.toString())).put("device",JSONObject().put("commitOid",oid).put("revision",device.get("refRevision"))).put("serverRevision",account.get("revision"));results[id]=result
                            if(dropPublish){dropPublish=false;return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)}
                            return response(result)}
                        route=="choices/cloud" -> {
                            val selection = body()
                            if (selection.getString("expectedAccountRevision") != account.getString("revision") ||
                                selection.getString("expectedRollbackGeneration") != account.getString("rollbackGeneration")
                            ) return error("choice_stale")
                            return response(dto(s).put("choiceId", selection.getString("choiceId"))
                                .put("account", account).put("confirmedAccount", account)
                                .put("auditRevision", account.getString("revision")))
                        }
                        route=="acks/applied"->{acks++;return response(dto(s).put("accepted",true).put("serverRevision",account.get("revision")))}
                        route=="demand"->return response(dto(s).put("serverNowMillis",now).put("demands",JSONArray()))
                    }
                    return error("object_missing")
                }catch(e:Throwable){return MockResponse().setResponseCode(500).setBody(e.toString())}
            }}
            http.start()
        }
        fun values():List<JSONObject> {
            val id=SyncV2Identity.recover(capsule!!,descriptor!!,wrap,"12".repeat(16),scope(),SyncV2Codec.oid("repo-descriptor",descriptor))
            try {
                val head=SyncV2Wire.parse(objects.getValue(account.getString("commitOid")),262144,true)
                return SyncV2Index.read(scope(),head.getJSONObject("body").getString("treeRoot"),objects::getValue).map{row->
                    fun open(field:String):JSONObject{val oid=row.getString(field);return SyncV2Wire.parse(SyncV2Envelope.open(id,run {val raw=objects.getValue(oid);val len=java.nio.ByteBuffer.wrap(raw,4,4).int;val version=SyncV2Wire.parse(raw.copyOfRange(8,8+len),4096,true).getLong("keyVersion");if(version==1L)wrap else ByteArray(32){8}},oid,objects.getValue(oid)),262144,true)}
                    val meta=open("metaOid");SyncV2Business.join(meta.getString("kind"),meta,open("stateOid"))
                }
            } finally {id.close()}
        }
        override fun close(){unblock.countDown();http.shutdown();receipt.close()}
    }
    private inner class Host(val server:Server,val id:String,localSwitch:Boolean=true,bodyChannel:Boolean=true):AutoCloseable {
        val root=File((RuntimeEnvironment.getApplication() as Context).cacheDir,"p6-host-${System.nanoTime()}").also{it.mkdirs()}
        val context=object:ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext():Context=this
            override fun getCacheDir()=File(root,"cache").also{it.mkdirs()}
            override fun getFilesDir()=File(root,"files").also{it.mkdirs()}
            override fun getNoBackupFilesDir()=File(root,"no-backup").also{it.mkdirs()}
        }
        val room=Room.databaseBuilder(context,PhoneCompanionDatabase::class.java,File(root,"phone.db").absolutePath).allowMainThreadQueries().build()
        val prefs=context.getSharedPreferences("host-$id-${System.nanoTime()}",Context.MODE_PRIVATE)
        val manager=CloudKeyManager(null,preferencesOverride=prefs).also{it.bindBackend(server.http.url("/").toString());it.saveAccountKey(user,1,server.wrap,true)}
        var account:SyncV2PhoneProduct.Account?=SyncV2PhoneProduct.Account(user,server.http.url("/").toString(),id,"old-epoch","old-generation","synthetic-account","synthetic-api","Bearer synthetic-device")
        var enabled=localSwitch
        var wifi=true
        var transportSignals = { SyncV2TransportSignals(wifi=wifi,internet=true) }
        val product=SyncV2PhoneProduct(context,{room},manager,{account},{_,values->assertTrue(java.security.MessageDigest.isEqual(server.wrap,values.getValue(1)))},{true},{enabled},signals={transportSignals()},contentEnabled=bodyChannel)
        fun note(text:String="local"){val v=SyncV2P5Fixture().use{it.note(markdown=text)};SyncV2PhoneApply(room.openHelper.writableDatabase).upsert("notes",listOf("noteId"),v)}
        fun text()=room.openHelper.writableDatabase.query("SELECT markdown FROM notes WHERE noteId='note'").use{if(it.moveToFirst())it.getString(0)else null}
        fun sync()=runBlocking{product.sync(server.announcement)}
        override fun close(){product.release();room.close();root.deleteRecursively()}
    }
    @Test fun announcedButClosedServerGateDoesNotFailTheLegacyCloudPass() {
        Server().use {server->Host(server,"a").use{h->
            server.readGate=false;h.note()
            val result=h.sync()
            assertFalse(result.enabled);assertEquals("feature_disabled",result.reason)
            assertEquals(0,server.inits);assertEquals(0,server.publishes);assertEquals(0,server.acks)
            assertEquals("local",h.text());assertNull(h.product.uiPort.value)
            server.readGate=true;assertTrue(h.sync().enabled);assertEquals(1,server.publishes)
            server.readGate=false
            assertFalse(h.sync().enabled);assertEquals(1,server.publishes)
            assertEquals("local",h.text());assertFalse(h.product.uiPort.value!!.state.value.enabled)
        }}
    }
    @Test fun independentBodyClosedGateKeepsOriginalAndContinuesProductMetadata() {
        Server().use {server->Host(server,"a").use {h->
            fun syncFully(){repeat(4){try{h.sync();return}catch(e:IllegalStateException){if(e.message!="budget_paused")throw e}};fail("bounded scan did not resume")}
            server.closedBodyChannel=true;h.note("![a](assets/product.png)")
            val raw="product original".toByteArray();val file=File(h.context.filesDir,"notes/assets/product.png");file.parentFile!!.mkdirs();file.writeBytes(raw)
            // Reference discovery registers the literal storageKey, even without an old private asset row.
            syncFully();assertEquals(1,server.publishes);assertTrue(server.bodyRequests>0)
            val runtime=SyncV2PhoneProduct::class.java.getDeclaredField("runtime").also{it.isAccessible=true}.get(h.product) as SyncV2PhoneProduct.Runtime
            assertEquals(listOf("body_channel_write_disabled"),runtime.bodyResult.errors)
            assertArrayEquals(raw,file.readBytes())
            assertTrue(server.values().any{it.optString("assetId")=="product.png"})
            assertEquals("body_channel_write_disabled",h.product.uiPort.value!!.state.value.errorCode)
            val entity=runtime.identity.entity("asset","product.png");val row=runtime.projection.cloudRows(setOf(entity)).single().getJSONObject("row")
            assertEquals(SyncV2BodyCodec.revision("asset",entity,SyncV2BodyCodec.hash(raw)),row.getString("bodyRevision"));assertTrue(row.isNull("recipeOid"))
            h.note("later metadata");syncFully();assertEquals(2,server.publishes);assertEquals("later metadata",h.text());assertArrayEquals(raw,file.readBytes())
        }}
    }
    @Test fun originalOnReceivingPhoneReplacesRandomPlaceholderWithoutMetadataEdits() {
        Server().use{server->
            server.closedBodyChannel=true;val raw="old original".toByteArray()
            fun full(h:Host){repeat(4){try{h.sync();return}catch(e:IllegalStateException){if(e.message!="budget_paused")throw e}};fail("scan did not finish")}
            Host(server,"old",bodyChannel=false).use{h->
                h.note("![a](assets/old.png)")
                SyncV2PhoneApply(h.room.openHelper.writableDatabase).upsert("note_assets",listOf("assetId"),JSONObject().put("assetId","private-old").put("storageKey","old.png").put("mimeType","image/png").put("displayName","old.png").put("createdAt",1).put("deleted",false).put("deletedAt",0))
                full(h);assertEquals(1,server.publishes);assertEquals(0,server.bodyRequests)
            }
            Host(server,"receiver").use{h->
                val file=File(h.context.filesDir,"notes/assets/old.png");file.parentFile!!.mkdirs();file.writeBytes(raw)
                full(h)
                val runtime=SyncV2PhoneProduct::class.java.getDeclaredField("runtime").also{it.isAccessible=true}.get(h.product) as SyncV2PhoneProduct.Runtime
                val entity = runtime.identity.entity("asset", "old.png")
                val digest = SyncV2BodyCodec.hash(raw)
                val originalRevision = SyncV2BodyCodec.revision("asset", entity, digest)
                val placeholder = runtime.projection.cloudRows(setOf(entity)).single().getJSONObject("row")
                assertEquals(1, server.publishes)
                assertNotEquals(originalRevision, placeholder.getString("bodyRevision"))
                assertTrue(placeholder.isNull("recipeOid"))
                assertArrayEquals(raw, file.readBytes())

                // D1 requires a committed local digest before replacing a different cloud revision.
                val db = h.room.openHelper.writableDatabase
                db.execSQL("UPDATE note_assets SET sha256=?, byteCount=? WHERE storageKey=?",
                    arrayOf(digest, raw.size, "old.png"))
                full(h)

                assertEquals(2, server.publishes)
                assertTrue(runtime.projection.state().body.isNull("pendingChoice"))
                val row = runtime.projection.cloudRows(setOf(entity)).single().getJSONObject("row")
                assertEquals(originalRevision, row.getString("bodyRevision"))
                assertTrue(row.isNull("recipeOid"))
                assertEquals(listOf("body_channel_write_disabled"), runtime.bodyResult.errors)
                assertArrayEquals(raw, file.readBytes())
                assertEquals(1, db.query("SELECT createdAt FROM note_assets WHERE storageKey='old.png'").use {
                    it.moveToFirst()
                    it.getInt(0)
                })
            }
        }
    }
    @Test fun disabledAndUnannouncedEntryHasZeroRequestsNoRuntimeAndNoDatabaseOpen()=runBlocking {
        Server().use {server->Host(server,"a",false).use{h->
            val before=server.http.requestCount
            val beforeStorage=SyncV2Codec.hex(SyncV2Codec.sha(h.prefs.all.toString().toByteArray()))
            val disabled = h.product.sync(server.announcement)
            assertFalse(disabled.enabled)
            assertEquals("feature_disabled", disabled.reason)
            assertNull(h.product.uiPort.value);h.enabled=true
            assertEquals("not_announced",h.product.sync(null).reason);assertEquals(before,server.http.requestCount)
            assertFalse(File(h.root,"phone.db").exists())
            assertEquals(beforeStorage,SyncV2Codec.hex(SyncV2Codec.sha(h.prefs.all.toString().toByteArray())))
        }}
    }
    @Test fun p6jPendingCreatorOnlyUpgradesAndRegisters()=legacyAliasUpgrade(pending=true,signer=false)
    @Test fun p6jInstalledCreatorOnlyRecoversServerAlias()=legacyAliasUpgrade(pending=false,signer=false)
    @Test fun p6jTwoPersistedAliasesWithPendingUpgradeWithoutReplacingKey()=legacyAliasUpgrade(pending=true,signer=true)
    @Test fun p6jTwoPersistedAliasesAfterPendingDeletionRecoverWithoutReplacingKey()=legacyAliasUpgrade(pending=false,signer=true)
    @Test fun p6jSignerOnlyReceiverKeepsItsOwnAlias()=legacyAliasUpgrade(pending=false,signer=true,receiver=true)
    @Test fun p6jLaterWindowRefreshesVerifiedTimeWithoutExtendingItsLifetime() {
        Server().use {server->Host(server,"a").use{h->
            server.negotiateStepMillis=25
            h.note();assertTrue(h.sync().enabled)
            assertEquals(1,server.publishes);assertEquals("local",server.values().single().getString("markdown"))
            assertTrue("server time did not advance",server.timeOffsetMillis>0)
            assertTrue("missing authenticated time refresh",server.checkpoints>4)
        }}
    }
    @Test fun p6jExpiredWindowStillRejectsPublication() {
        Server().use {server->Host(server,"a").use{h->
            server.expiredNegotiate=true;h.note()
            assertEquals("window_expired",runCatching{h.sync()}.exceptionOrNull()?.message)
            assertEquals(0,server.publishes);assertEquals("local",h.text())
        }}
    }
    private fun legacyAliasUpgrade(pending:Boolean,signer:Boolean,receiver:Boolean=false) {
        Server().use {server->Host(server,"a").use {h->
            val life=SyncV2Lifetime{true};val store=SyncV2AccountStore(h.manager,user,life)
            try {
                val candidate=SyncV2RepoBootstrap.prepareStored("12".repeat(16),"a","old-epoch","old-generation",1,{server.wrap},store::pending,store::pending)
                val repo=candidate.scope.getString("repoId")
                if(!pending) {
                    server.descriptor=candidate.descriptor;server.capsule=candidate.recoveryCapsule
                    server.boundAliases[if(receiver)"other"else"a"]=candidate.descriptor.getJSONObject("body").getString("creatorDeviceId")
                    store.write("secret/pending",null)
                }
                val path=SyncV2Codec.hash(JSONObject().put("backend",server.http.url("/").toString()).put("account","12".repeat(16)).put("repo",repo))
                val counter=File(h.context.noBackupFilesDir,"syncv2/$path.sequence")
                val old=if(signer) {
                    SyncV2SigningVault(store,repo,counter,life).use{assertNotNull(it.signerOrNull())}
                    store.read("local/signing/$repo")!!
                }else null
                h.note();assertTrue(h.sync().enabled);assertEquals(1,server.publishes)
                val current=store.read("local/signing/$repo")!!
                if(old!=null) {
                    assertTrue("migration replaced signing private key",old.getString("privateHex")==current.getString("privateHex"))
                    assertEquals(old.getString("keyId"),current.getString("keyId"))
                }
                val device=current.getString("deviceId")
                assertEquals(if(receiver)old!!.getString("deviceId")else candidate.descriptor.getJSONObject("body").getString("creatorDeviceId"),device)
                assertEquals(device,h.product.uiPort.value!!.state.value.ownDeviceId)
                assertEquals("local",server.values().single().getString("markdown"))
                val registrations=server.registrationBodies.size
                h.product.release();h.sync()
                assertEquals(registrations,server.registrationBodies.size)
                assertTrue("reopen replaced signing private key",current.getString("privateHex")==store.read("local/signing/$repo")!!.getString("privateHex"))
            }finally{life.close()}
        }}
    }
    @Test fun receivingNotesRefreshesAnExistingRoomObserverAfterOneCheckpoint() = runBlocking {
        Server().use { server ->
            Host(server, "sender", bodyChannel = false).use { sender ->
                sender.note("before")
                sender.sync()
                Host(server, "receiver", bodyChannel = false).use { receiver ->
                    receiver.sync()
                    val initial = CompletableDeferred<Unit>()
                    val updated = async {
                        withTimeout(10_000) {
                            receiver.room.noteDao().observeNotes().first { notes ->
                                if (notes.any { it.markdown == "before" }) initial.complete(Unit)
                                notes.any { it.markdown == "after" }
                            }
                        }
                    }
                    withTimeout(10_000) { initial.await() }

                    sender.note("after")
                    sender.sync()
                    val checkpoints = server.checkpoints
                    receiver.sync()

                    assertEquals(checkpoints + 1, server.checkpoints)
                    assertEquals("after", updated.await().single().markdown)
                }
            }
        }
    }

    @Test fun realCreateScanPublishRoomApplyReceiverAndSubsequentSchedule() {
        Server().use {server->Host(server,"a").use{a->
            a.note();assertTrue(a.sync().enabled);assertEquals(1,server.publishes);assertEquals("local",server.values().single().getString("markdown"));assertTrue(server.acks>0)
            val creator=server.descriptor!!.getJSONObject("body")
            val registered=server.registrationBodies.first().getJSONObject("body")
            assertEquals(creator.getString("creatorDeviceId"),registered.getString("deviceId"))
            assertNotEquals(creator.getString("creatorPublicKey"),SyncV2Codec.hex(SyncV2Codec.decode64(registered.getString("publicKey"),65)))
            val port=a.product.uiPort.value!!;assertNotNull(port.state.value.ownDeviceId)
            a.room.openHelper.writableDatabase.execSQL("UPDATE notes SET markdown='changed',plainText='changed',updatedAt=updatedAt+1 WHERE noteId='note'")
            a.sync();assertEquals(2,server.publishes);assertEquals("changed",server.values().single().getString("markdown"))
            Host(server,"b").use {b->b.manager.saveAccountKey(user,2,ByteArray(32){8},true);b.sync();assertEquals("changed",b.text());assertEquals(2,server.publishes);assertNotNull(b.product.uiPort.value)}
            val identityName="secret/installed";assertNotNull(a.manager.syncV2Read(user,identityName))
            a.manager.clearAccountKeys(user);assertNull(a.manager.syncV2Read(user,identityName));assertNull(a.product.uiPort.value);assertNull(port.state.value.ownDeviceId)
            val failure=runCatching{runBlocking{port.history()}}.exceptionOrNull();assertEquals("authentication_required",failure?.message)
        }}
    }
    @Test fun concurrentCreatorAdoptsWinnerUsingItsOldWrappingVersionBeforeAnyIdentityScan() {
        Server().use {server->Host(server,"a").use{h->
            server.concurrentWinner=SyncV2RepoBootstrap.prepareStored("12".repeat(16),"winner","epoch","gen",1,{server.wrap},{null}){}
            val winner=server.concurrentWinner!!.scope.getString("repoId")
            h.manager.saveAccountKey(user,2,ByteArray(32){8},true);h.note();h.sync()
            assertEquals(winner,server.scope().getString("repoId"));assertEquals("local",server.values().single().getString("markdown"))
            h.room.openHelper.writableDatabase.query("SELECT DISTINCT repo_id FROM syncv2_identity_map").use{assertTrue(it.moveToFirst());assertEquals(winner,it.getString(0));assertFalse(it.moveToNext())}
        }}
    }
    @Test fun logoutDuringActualDefaultHttpWaitImmediatelyWipesOpeningKeysAndCancelsInstall() {
        Server().use {server->Host(server,"a").use{h->
            server.descriptor=SyncV2RepoBootstrap.prepareStored("12".repeat(16),"creator","epoch","gen",1,{server.wrap},{null}){}.let{server.capsule=it.recoveryCapsule;it.descriptor}
            server.block="repo/id-key-recovery/"+SyncV2Codec.oid("id-key-recovery",server.capsule)
            val executor=Executors.newSingleThreadExecutor();val future=executor.submit<Throwable?>{runCatching{h.sync()}.exceptionOrNull()}
            try {
                assertTrue(server.entered.await(10,TimeUnit.SECONDS))
                val life=SyncV2PhoneProduct::class.java.getDeclaredField("opening").also{it.isAccessible=true}.get(h.product) as SyncV2Lifetime
                val owned=SyncV2Lifetime::class.java.getDeclaredField("owned").also{it.isAccessible=true}.get(life) as List<*>
                val buffer=owned.filterIsInstance<SyncV2ContentKeys>().single().read(1)
                h.account=null;h.manager.clearAccountKeys(user)
                assertTrue(buffer.all{it==0.toByte()});assertNull(h.product.uiPort.value);assertNull(h.manager.syncV2Read(user,"secret/installed"))
                server.unblock.countDown();assertNotNull(future.get(10,TimeUnit.SECONDS))
            } finally {server.unblock.countDown();executor.shutdownNow()}
        }}
    }
    @Test fun successfulLostPublishReconcilesOriginalRequestBeforeNewLocalSource() {
        Server().use {server->Host(server,"a").use{h->
            h.note();server.dropPublish=true;server.failQuery=true
            assertNotNull(runCatching{h.sync()}.exceptionOrNull());assertEquals(1,server.publishes)
            h.room.openHelper.writableDatabase.execSQL("UPDATE notes SET markdown='tail',plainText='tail',updatedAt=updatedAt+1 WHERE noteId='note'")
            server.failQuery=false;h.sync()
            assertEquals("tail",h.text());assertEquals(1,server.publishes);assertTrue(server.queries>0)
            assertEquals("local",server.values().single().getString("markdown"))
        }}
    }
    @Test fun knownPinPreventsReplacementIdentityWhenBackendHidesDescriptor() {
        Server().use {server->Host(server,"a").use{h->
            h.note();h.sync();val count=server.inits
            h.product.release();h.manager.saveAccountKey(user,1,server.wrap,true)
            server.descriptor=null
            val failure=runCatching{h.sync()}.exceptionOrNull()
            assertEquals("identity_conflict",failure?.message);assertEquals(count,server.inits);assertEquals("local",h.text())
        }}
    }
    @Test fun samePinReinstallationAndDeviceSequenceSurviveLogoutAndReopen() {
        Server().use {server->Host(server,"a").use{h->
            h.note();h.sync();val firstDevice=h.product.uiPort.value!!.state.value.ownDeviceId
            val pin=server.descriptor!!.toString()
            h.manager.clearAccountKeys(user);h.manager.saveAccountKey(user,1,server.wrap,true)
            h.sync();assertEquals(pin,server.descriptor!!.toString());assertEquals(firstDevice,h.product.uiPort.value!!.state.value.ownDeviceId)
            h.room.openHelper.writableDatabase.execSQL("UPDATE notes SET markdown='after restart',plainText='after restart',updatedAt=updatedAt+1 WHERE noteId='note'")
            h.sync();assertEquals(2,server.publishes)
            val commit=SyncV2Wire.parse(server.objects.getValue(server.account.getString("commitOid")),262144,true)
            assertEquals("2",commit.getJSONObject("body").getString("deviceSequence"))
        }}
    }

    @Test fun productArticleBodyFlowsThroughActualRoomCacheAndContentTransport() {
        Server().use {server->Host(server,"a").use{h->
            val records=javaClass.classLoader!!.getResourceAsStream("syncv2-business-schema.json")!!.bufferedReader().use{JSONObject(it.readText())}.getJSONArray("records")
            val article=(0 until records.length()).map{records.getJSONObject(it)}.single{it.getString("kind")=="article"}.getJSONObject("value")
            val body="real product 正文 é😀"
            val content=com.lightningstudio.watchrss.phone.data.local.FileArticleContentStore(h.context)
            val marker=content.storeText(article.getString("articleId"),body)
            SyncV2PhoneApply(h.room.openHelper.writableDatabase).upsert("phone_articles",listOf("articleId"),article.put("contentText",marker))
            h.sync();assertEquals(1,server.publishes)
            val head=SyncV2Wire.parse(server.objects.getValue(server.account.getString("commitOid")),262144,true)
            val rows=SyncV2Index.read(server.scope(),head.getJSONObject("body").getString("treeRoot"),server.objects::getValue)
            val row=rows.first{!it.isNull("recipeOid")}
            assertEquals(body.toByteArray().size.toLong(),row.getLong("contentBytes"))
            val identity=SyncV2Identity.recover(server.capsule!!,server.descriptor!!,server.wrap,"12".repeat(16),server.scope(),SyncV2Codec.oid("repo-descriptor",server.descriptor))
            try {
                val oid=row.getString("recipeOid");val recipe=SyncV2Wire.parse(SyncV2Envelope.open(identity,server.wrap,oid,server.objects.getValue(oid)),262144,true)
                val blob=recipe.getJSONArray("blocks").getJSONObject(0).getString("oid")
                assertArrayEquals(body.toByteArray(),SyncV2Envelope.open(identity,server.wrap,blob,server.objects.getValue(blob)))
            }finally{identity.close()}
            assertEquals(marker,h.room.openHelper.writableDatabase.query("SELECT contentText FROM phone_articles").use{it.moveToFirst();it.getString(0)})
            val status=runBlocking{h.product.uiPort.value!!.bodyStatus("book","local book")}
            assertEquals("body_not_synced",status.getString("reason"));assertTrue(status.getBoolean("keepLocalBody"))
        }}
    }
    @Test fun corruptSigningCounterStopsWritesButNotAuthenticatedBusinessReception() {
        Server().use {server->Host(server,"a").use{a->
            a.note();a.sync()
            Host(server,"b").use {b->
                b.sync();b.room.openHelper.writableDatabase.execSQL("UPDATE notes SET markdown='remote update',plainText='remote update',updatedAt=updatedAt+1 WHERE noteId='note'");b.sync()
                a.product.release();a.context.noBackupFilesDir.walkTopDown().single{it.name.endsWith(".sequence")}.writeText("corrupt")
                a.sync();assertEquals("remote update",a.text())
                a.room.openHelper.writableDatabase.execSQL("UPDATE notes SET markdown='local retained',plainText='local retained',updatedAt=updatedAt+1 WHERE noteId='note'")
                assertEquals("signing_state_unavailable",runCatching{a.sync()}.exceptionOrNull()?.message)
                assertEquals("local retained",a.text());assertEquals("remote update",server.values().single().getString("markdown"))
            }
        }}
    }

    @Test fun missingDescriptorKeyVersionAndTamperedCapsulePreserveLocalLibrary() {
        Server().use {server->Host(server,"creator").use{a->
            a.manager.saveAccountKey(user,2,ByteArray(32){8},true);a.note();a.sync()
            Host(server,"receiver").use {b->
                b.note("local retained")
                assertEquals("key_unavailable",runCatching{b.sync()}.exceptionOrNull()?.message)
                assertEquals("local retained",b.text());assertNull(b.manager.syncV2Read(user,"secret/installed"))
            }
        }}
        Server().use {server->Host(server,"creator").use{a->
            a.note();a.sync()
            server.capsule = SyncV2Fixtures.tamperRecoveryCapsule(requireNotNull(server.capsule))
            Host(server,"receiver").use {b->
                b.note("local retained")
                assertNotNull(runCatching{b.sync()}.exceptionOrNull());assertEquals("local retained",b.text())
                assertEquals(1,server.publishes);assertNull(b.manager.syncV2Read(user,"secret/installed"))
            }
        }}
    }
    @Test fun logoutDuringFirstCheckpointClosesAlreadyConstructedProjectionAndUi() {
        Server().use {server->Host(server,"a").use{h->
            server.block="checkpoint"
            val executor=Executors.newSingleThreadExecutor();val future=executor.submit<Throwable?>{runCatching{h.sync()}.exceptionOrNull()}
            try {
                assertTrue(server.entered.await(10,TimeUnit.SECONDS))
                val port=h.product.uiPort.value!!
                val runtime=SyncV2PhoneProduct::class.java.getDeclaredField("runtime").also{it.isAccessible=true}.get(h.product) as SyncV2PhoneProduct.Runtime
                val root=runtime.keys.read(1)
                val local=SyncV2Projection::class.java.getDeclaredField("key").also{it.isAccessible=true}.get(runtime.projection) as ByteArray
                h.account=null;h.manager.clearAccountKeys(user)
                assertTrue(root.all{it==0.toByte()});assertTrue(local.all{it==0.toByte()});assertNull(h.product.uiPort.value);assertFalse(port.state.value.enabled)
                h.room.openHelper.writableDatabase.query("SELECT length(identity) FROM syncv2_state").use{assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0))}
                server.unblock.countDown();assertNotNull(future.get(10,TimeUnit.SECONDS))
            }finally{server.unblock.countDown();executor.shutdownNow()}
        }}
    }

    @Test fun authenticatedAnnouncementKeyChangeStopsLiveRuntimeBeforeAnyV2Request() {
        Server().use {server->Host(server,"a").use {h->
            h.note();h.sync();val port=h.product.uiPort.value!!;val before=server.http.requestCount
            val changed=JSONObject(server.announcement.toString()).put("receiptKeyId","ef".repeat(16))
            val failure=runCatching{runBlocking{h.product.sync(changed)}}.exceptionOrNull() as SyncV2CloudError
            assertEquals("service_key_changed",failure.code);assertEquals(before,server.http.requestCount)
            assertEquals("authentication_required",runCatching{runBlocking{port.bodyStatus("book","b")}}.exceptionOrNull()?.message)
        }}
    }

    @Test fun concurrentFirstPublishPreservesBothLibrariesAndNeverRefreshesParentToOverwrite() {
        Server().use {server->Host(server,"a").use {a->Host(server,"b").use {b->
            a.note("A local");b.note("B local");server.block="objects/reserve"
            val executor=Executors.newSingleThreadExecutor();val future=executor.submit<Throwable?>{runCatching{a.sync()}.exceptionOrNull()}
            try {
                assertTrue(server.entered.await(10,TimeUnit.SECONDS))
                b.sync();assertEquals(1,server.publishes)
                server.unblock.countDown()
                assertEquals("ref_conflict",(future.get(10,TimeUnit.SECONDS) as SyncV2CloudError).code)
                a.sync()
                assertEquals(1,server.publishes);assertEquals("A local",a.text());assertEquals("B local",server.values().single().getString("markdown"))
                val runtime=SyncV2PhoneProduct::class.java.getDeclaredField("runtime").also{it.isAccessible=true}.get(a.product) as SyncV2PhoneProduct.Runtime
                assertFalse(runtime.projection.state().body.isNull("pendingChoice"))
            }finally{server.unblock.countDown();executor.shutdownNow()}
        }}}
    }
    @Test fun unknownPublishWithChangedSourceNeverResendsOldSnapshot() {
        Server().use {server->Host(server,"a").use {h->
            h.note();server.dropPublish=true;server.failQuery=true
            assertNotNull(runCatching{h.sync()}.exceptionOrNull());assertEquals(1,server.publishes)
            h.room.openHelper.writableDatabase.execSQL("UPDATE notes SET markdown='new local source',plainText='new local source',updatedAt=updatedAt+1 WHERE noteId='note'")
            server.failQuery=false;server.results.clear()
            assertEquals("source_changed",runCatching{h.sync()}.exceptionOrNull()?.message)
            assertEquals(1,server.publishes);assertEquals("new local source",h.text())
        }}
    }

    @Test fun editDuringBodyUploadCannotBeAcknowledgedAsPartOfTheOlderScan() {
        Server().use {server->Host(server,"a").use {h->
            h.note("before upload")
            val records=javaClass.classLoader!!.getResourceAsStream("syncv2-business-schema.json")!!.bufferedReader().use{JSONObject(it.readText())}.getJSONArray("records")
            val article=(0 until records.length()).map{records.getJSONObject(it)}.single{it.getString("kind")=="article"}.getJSONObject("value")
            SyncV2PhoneApply(h.room.openHelper.writableDatabase).upsert("phone_articles",listOf("articleId"),article.put("contentText","body upload"))
            server.block="demand"
            val executor=Executors.newSingleThreadExecutor();val future=executor.submit<Throwable?>{runCatching{h.sync()}.exceptionOrNull()}
            try {
                assertTrue(server.entered.await(10,TimeUnit.SECONDS))
                h.room.openHelper.writableDatabase.execSQL("UPDATE notes SET markdown='during upload',plainText='during upload',updatedAt=updatedAt+1 WHERE noteId='note'")
                server.unblock.countDown()
                val failure=future.get(10,TimeUnit.SECONDS)
                assertEquals("during upload",h.text())
                assertEquals("source_changed",failure?.message)
                assertEquals(0,server.publishes)
                h.sync();assertEquals(1,server.publishes);assertEquals("during upload",h.text())
                assertEquals("during upload",server.values().single{it.has("noteId")}.getString("markdown"))
            }finally{server.unblock.countDown();executor.shutdownNow()}
        }}
    }

    @Test fun reviewSecureSigningCorruptionMustNotBlockReception()=damagedSigningReception("{broken")
    @Test fun reviewMissingSigningRecordMustNotBlockReception()=damagedSigningReception(null)
    @Test fun malformedSigningFieldsMustNotBlockReception()=damagedSigningReception("{}")
    private fun damagedSigningReception(damage:String?) {
        Server().use {server->Host(server,"a").use {a->
            a.note();a.sync()
            Host(server,"b").use {b->
                b.sync();b.room.openHelper.writableDatabase.execSQL("UPDATE notes SET markdown='remote latest',plainText='remote latest',updatedAt=updatedAt+1 WHERE noteId='note'");b.sync()
                a.product.release()
                val repo=server.scope().getString("repoId");val name="local/signing/$repo"
                val counter=a.context.noBackupFilesDir.walkTopDown().single{it.name.endsWith(".sequence")}
                val counterBytes=counter.readBytes()
                a.manager.syncV2Write(user,name,damage)
                val acks=server.acks;val publishes=server.publishes;val registrations=server.bindings.size
                val failure=runCatching{a.sync()}.exceptionOrNull()
                assertEquals("secure signing damage stopped authenticated reception","remote latest",a.text())
                assertEquals("signing_state_unavailable",failure?.message)
                val port=a.product.uiPort.value!!
                assertEquals("signing_state_unavailable",port.state.value.errorCode);assertNull(port.state.value.ownDeviceId)
                assertEquals("blocked",port.state.value.phase)
                assertTrue("damaged signing record must remain untouched",damage==a.manager.syncV2Read(user,name));assertArrayEquals(counterBytes,counter.readBytes())
                assertEquals(acks,server.acks);assertEquals(registrations,server.bindings.size)
                // A later receive round still follows the remote account without replacing the signer.
                b.room.openHelper.writableDatabase.execSQL("UPDATE notes SET markdown='remote again',plainText='remote again',updatedAt=updatedAt+1 WHERE noteId='note'");b.sync()
                val laterAcks=server.acks
                assertEquals("signing_state_unavailable",runCatching{a.sync()}.exceptionOrNull()?.message)
                assertEquals("remote again",a.text());assertEquals(laterAcks,server.acks)
                a.room.openHelper.writableDatabase.execSQL("UPDATE notes SET markdown='local retained',plainText='local retained',updatedAt=updatedAt+1 WHERE noteId='note'")
                assertEquals("signing_state_unavailable",runCatching{a.sync()}.exceptionOrNull()?.message)
                assertEquals("local retained",a.text());assertEquals(publishes+1,server.publishes)
                assertTrue("damaged signing record must remain untouched",damage==a.manager.syncV2Read(user,name));assertArrayEquals(counterBytes,counter.readBytes())
            }
        }}
    }
    @Test fun reviewRepoConflictReadFailureMustNotReplayLosingInit() {
        Server().use {server->Host(server,"a").use {h->
            server.concurrentWinner=SyncV2RepoBootstrap.prepareStored("12".repeat(16),"winner","e","g",1,{server.wrap},{null}){}
            h.note();server.failAfterConflict=true
            assertNotNull(runCatching{h.sync()}.exceptionOrNull())
            assertEquals(1,server.inits)
            val pending=h.manager.syncV2Read(user,"secret/pending")
            h.product.release()
            assertNotNull(runCatching{h.sync()}.exceptionOrNull())
            assertEquals("19.5 forbids blind losing repo-init replay after descriptor failure",1,server.inits)
            assertTrue("pending bytes changed after a failed winner read",pending==h.manager.syncV2Read(user,"secret/pending"));assertEquals("local",h.text())
            assertEquals(0,server.publishes)
            server.descriptorUnavailable=false;server.failAfterConflict=false
            h.sync();assertEquals(1,server.inits);assertEquals(1,server.publishes)
            assertEquals("local",server.values().single().getString("markdown"))
            assertNull(h.manager.syncV2Read(user,"secret/pending"))
        }}
    }
    @Test fun reviewLostInitResponseReplaysExactFullBytesAcrossRelease() {
        Server().use {server->Host(server,"a").use {h->
            h.note();server.dropInit=true
            assertNotNull(runCatching{h.sync()}.exceptionOrNull());assertEquals(1,server.inits)
            h.product.release();h.sync()
            assertEquals(2,server.inits);assertEquals(server.initBytes[0],server.initBytes[1]);assertEquals(1,server.publishes)
        }}
    }
    @Test fun reviewHtmlFileMarkerPublishesActualV1BodyBytes()=htmlBodyPublication(true)
    @Test fun inlineHtmlPublishesActualV1BodyBytes()=htmlBodyPublication(false)
    private fun htmlBodyPublication(fileBody:Boolean) {
        Server().use {server->Host(server,"a").use {h->
            val records=javaClass.classLoader!!.getResourceAsStream("syncv2-business-schema.json")!!.bufferedReader().use{JSONObject(it.readText())}.getJSONArray("records")
            val article=(0 until records.length()).map{records.getJSONObject(it)}.single{it.getString("kind")=="article"}.getJSONObject("value")
            val html="<p>actual v1 HTML 正文 é😀</p>"
            val content=com.lightningstudio.watchrss.phone.data.local.FileArticleContentStore(h.context)
            val marker=content.storeText(article.getString("articleId")+"-html",html)
            assertEquals(html,content.loadText(marker))
            SyncV2PhoneApply(h.room.openHelper.writableDatabase).upsert("phone_articles",listOf("articleId"),article.put("contentText","").put("contentHtml",if(fileBody)marker else html))
            h.sync()
            val head=SyncV2Wire.parse(server.objects.getValue(server.account.getString("commitOid")),262144,true)
            val row=SyncV2Index.read(server.scope(),head.getJSONObject("body").getString("treeRoot"),server.objects::getValue).single()
            val identity=SyncV2Identity.recover(server.capsule!!,server.descriptor!!,server.wrap,"12".repeat(16),server.scope(),SyncV2Codec.oid("repo-descriptor",server.descriptor))
            try {
                val oid=row.getString("recipeOid")
                val recipe=SyncV2Wire.parse(SyncV2Envelope.open(identity,server.wrap,oid,server.objects.getValue(oid)),262144,true)
                val blob=recipe.getJSONArray("blocks").getJSONObject(0).getString("oid")
                val actual=SyncV2Envelope.open(identity,server.wrap,blob,server.objects.getValue(blob)).toString(Charsets.UTF_8)
                assertEquals("HTML marker must resolve through the same v1 content store",html,actual)
            }finally{identity.close()}
        }}
    }
    @Test fun reviewR0ReallyPagesAndPublishesEveryBusinessRow() {
        Server().use {server->Host(server,"a").use {h->
            val apply=SyncV2PhoneApply(h.room.openHelper.writableDatabase)
            SyncV2P5Fixture().use {f->repeat(130){n->apply.upsert("notes",listOf("noteId"),f.note(markdown="body-$n").put("noteId","note-%03d".format(n)))}}
            h.sync();assertEquals(1,server.publishes)
            assertEquals((0 until 130).map{"body-$it"}.toSet(),server.values().map{it.getString("markdown")}.toSet())
            h.room.openHelper.writableDatabase.query("SELECT complete,page_key FROM syncv2_scan WHERE domain='notes'").use{assertTrue(it.moveToFirst());assertEquals(1,it.getInt(0))}
        }}
    }

    @Test fun historyRemainsClosedIndependentlyOfCurrentLibraryChoice() {
        Server().use {server->Host(server,"a").use {h->
            h.note();h.sync()
            val port=h.product.uiPort.value!!
            val runtime=SyncV2PhoneProduct::class.java.getDeclaredField("runtime").also{it.isAccessible=true}.get(h.product) as SyncV2PhoneProduct.Runtime
            val revision=runtime.projection.state().revision
            val requests=server.http.requestCount
            assertTrue(port.state.value.gates.choiceApply)
            assertFalse(port.state.value.gates.historyRestore)
            assertEquals("feature_disabled",runCatching{runBlocking{port.history()}}.exceptionOrNull()?.message)
            assertEquals(revision,runtime.projection.state().revision)
            assertEquals(requests,server.http.requestCount);assertEquals("local",h.text())
        }}
    }

    @Test fun reviewArticleCursorMustRestartWhenEarlierSourceChanges()=articleCursorSourceChange(false)
    @Test fun articleCursorMustRestartAcrossProductReopen()=articleCursorSourceChange(true)
    private fun articleCursorSourceChange(reopen:Boolean) {
        Server().use {server->Host(server,"a").use {h->
            val records=javaClass.classLoader!!.getResourceAsStream("syncv2-business-schema.json")!!.bufferedReader().use{JSONObject(it.readText())}.getJSONArray("records")
            val sample=(0 until records.length()).map{records.getJSONObject(it)}.single{it.getString("kind")=="article"}.getJSONObject("value")
            val apply=SyncV2PhoneApply(h.room.openHelper.writableDatabase)
            repeat(17){n->val value=JSONObject(sample.toString()).put("articleId","article-$n").put("url","https://synthetic.invalid/$n").put("contentText","initial body $n");apply.upsert("phone_articles",listOf("articleId"),value)}
            assertEquals("budget_paused",runCatching{h.sync()}.exceptionOrNull()?.message)
            assertEquals(0,server.publishes)
            val runtime=SyncV2PhoneProduct::class.java.getDeclaredField("runtime").also{it.isAccessible=true}.get(h.product) as SyncV2PhoneProduct.Runtime
            val first=h.room.openHelper.writableDatabase.query("SELECT entity_key,logical_id FROM syncv2_candidates WHERE kind='article' ORDER BY entity_key LIMIT 1").use{it.moveToFirst();it.getString(0) to it.getString(1)}
            assertFalse(runtime.projection.wireContentRow(first.first)!!.isNull("recipeOid"))
            h.room.openHelper.writableDatabase.execSQL("UPDATE phone_articles SET contentText='edited earlier body',contentHash=?,updatedAt=updatedAt+1 WHERE articleId=?",arrayOf(SyncV2Codec.hex(SyncV2Codec.sha("edited earlier body".toByteArray())),first.second))
            if(reopen)h.product.release()
            // Restarting the prefix can take another bounded page. No snapshot may omit its body.
            repeat(3) {
                val failure=runCatching{h.sync()}.exceptionOrNull()
                if(failure!=null)assertEquals("budget_paused",failure.message)
            }
            assertEquals(1,server.publishes)
            val head=SyncV2Wire.parse(server.objects.getValue(server.account.getString("commitOid")),262144,true)
            val row=SyncV2Index.read(server.scope(),head.getJSONObject("body").getString("treeRoot"),server.objects::getValue).single{it.getString("entityKey")==first.first}
            assertEquals("edited earlier body",h.room.openHelper.writableDatabase.query("SELECT contentText FROM phone_articles WHERE articleId=?",arrayOf(first.second)).use{it.moveToFirst();it.getString(0)})
            val live=SyncV2PhoneProduct::class.java.getDeclaredField("runtime").also{it.isAccessible=true}.get(h.product) as SyncV2PhoneProduct.Runtime
            val state=live.projection.state()
            assertEquals(state.revision,state.body.getString("acknowledgedLocalRevision"))
            assertFalse("earlier cached body was acknowledged without a recipe after article cursor resume",row.isNull("recipeOid"))
            val all=SyncV2Index.read(server.scope(),head.getJSONObject("body").getString("treeRoot"),server.objects::getValue)
            assertEquals(17,all.size);assertTrue(all.all{!it.isNull("recipeOid")})
            val identity=SyncV2Identity.recover(server.capsule!!,server.descriptor!!,server.wrap,"12".repeat(16),server.scope(),SyncV2Codec.oid("repo-descriptor",server.descriptor))
            try {
                val oid=row.getString("recipeOid")
                val recipe=SyncV2Wire.parse(SyncV2Envelope.open(identity,server.wrap,oid,server.objects.getValue(oid)),262144,true)
                val blob=recipe.getJSONArray("blocks").getJSONObject(0).getString("oid")
                assertArrayEquals("edited earlier body".toByteArray(),SyncV2Envelope.open(identity,server.wrap,blob,server.objects.getValue(blob)))
            }finally{identity.close()}
        }}
    }
    private inner class ServiceEntry(
        val h: Host,
        roundTimeoutMillis: Long? = null,
        sessionPreferences: SharedPreferences = MemoryPreferences(),
        statePreferences: SharedPreferences = MemoryPreferences(),
        cloudEntryEnabled: () -> Boolean = { true },
        refreshHeadDevice: suspend (String, String) -> Boolean = { _, _ -> false }
    ):AutoCloseable {
        private val c=h.context
        private val epoch="11111111-1111-4111-8111-111111111111"
        private val generation="22222222-2222-4222-8222-222222222222"
        private val scope=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()+kotlinx.coroutines.Dispatchers.Default)
        val preferences=statePreferences
        val settings=com.lightningstudio.watchrss.phone.cloud.PhoneCloudStateStore(preferences)
        var announced=true;var failAuthentication=false;var unsupported=false;var legacyCalls=0;var bootstraps=0
        var bootstrapTimesOut = false
        var approvedForHead = false
        private fun approvedDevices(owner: String): JSONArray {
            if (!approvedForHead) return JSONArray()
            val publicKey = "head-poll-fixture".toByteArray()
            return JSONArray().put(JSONObject()
                .put("deviceId", h.id).put("platform", "phone")
                .put("publicKeySpki", java.util.Base64.getEncoder().encodeToString(publicKey))
                .put("attestationKeyVersion", 1)
                .put("attestation", CloudProtocolV2.attestation(h.server.wrap, owner, epoch, h.id, publicKey)))
        }
        var memberReadable = true
        var hasCloudCommitments = true
        var onBootstrapRequest: (() -> Unit)? = null
        var onRevokeRequest: (() -> Unit)? = null
        private val nextOwner = "34343434-3434-3434-3434-343434343434"
        private fun memberJson() = JSONObject().put("active", true).put("writable", true)
            .put("readable", memberReadable).put("quotaBytes", 1000000)
            .put("usedBytes", 0).put("reservedBytes", 0)
        private val env=com.lightningstudio.watchrss.phone.account.AccountEnvironment(backendBaseUrl=h.server.http.url("/").toString(),supabaseAnonKey="synthetic-api")
        val session=com.lightningstudio.watchrss.phone.account.PhoneAccountSession(user,"***","synthetic-account","synthetic-refresh",Long.MAX_VALUE)
        private val store=com.lightningstudio.watchrss.phone.account.EncryptedAccountSessionStore(c).also {store->
            store.javaClass.getDeclaredField("prefsRef").apply{isAccessible=true}.set(store,sessionPreferences)
        }
        private val account=com.lightningstudio.watchrss.phone.account.PhoneAccountRepository(env,store,com.lightningstudio.watchrss.phone.account.PhoneInstallationIdentity(c),
            com.lightningstudio.watchrss.phone.account.PhoneAccountClient(env,com.lightningstudio.watchrss.phone.account.LicenseDeviceIdentity(c)),h.id)
        private val repo=com.lightningstudio.watchrss.phone.data.repo.PhoneCompanionRepository(h.room.phoneSavedItemDao(),h.room.phoneArticleDao(),h.room.phoneRssSourceDao(),h.id,
            syncChangeLogDao=h.room.syncChangeLogDao(),syncPeerStateDao=h.room.syncPeerStateDao(),appMetaDao=h.room.appMetaDao(),
            articleContentStore=FileArticleContentStore(c))
        private val reader=com.lightningstudio.watchrss.phone.data.reader.ReaderPresetRepository(c,h.room,h.room.readerPresetDao(),h.id,scope)
        private val client=com.lightningstudio.watchrss.phone.cloud.PhoneCloudClient(env,
            deviceAccessToken = { h.account?.deviceAuthorization?.removePrefix("Bearer ") },
            refreshDeviceSession = refreshHeadDevice,
            http=okhttp3.OkHttpClient.Builder().addInterceptor{chain->
            val request=chain.request()
            val path = request.url.encodedPath
            val bootstrap = path.endsWith("/bootstrap")
            val owner = if (request.header("Authorization") == "Bearer other-session") nextOwner else user
            val (status, body) = when {
                path.endsWith("/entitlement") -> 200 to memberJson()
                path.endsWith("/revoke") && onRevokeRequest != null -> {
                    onRevokeRequest?.invoke()
                    409 to JSONObject().put("error", "library_epoch_mismatch")
                }
                else -> {
            if (bootstrap) onBootstrapRequest?.invoke()
            if (bootstrap && bootstrapTimesOut) throw java.net.SocketTimeoutException("fixture timeout")
            val status=if(bootstrap){bootstraps++;if(failAuthentication)401 else if(unsupported)426 else 200}else{legacyCalls++;426}
            val body=if(status==200)JSONObject().put("libraryEpoch",epoch).put("contentGeneration",generation).put("contentEmpty",false)
                .put("member",memberJson())
                .put("keyVersionFloor",0).put("keyCommitments",JSONArray().apply {
                    if (hasCloudCommitments) put(JSONObject().put("keyVersion",1).put("commitment",CloudProtocolV2.commitment(h.server.wrap,owner,epoch,1)))
                })
                .put("keyEnvelopes",JSONArray()).put("devices",approvedDevices(owner)).also{if(announced)it.put("syncV2",h.server.announcement)}
                else JSONObject().put("error",if(unsupported)"client_upgrade_required"else if(failAuthentication)"invalid_token"else JSONObject().put("code","upgrade_required").put("details",JSONObject().put("requiredFeatures",JSONArray().put("sync-objects.v2"))))
                    status to body
                }
            }
            okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(status).message("synthetic")
                .body(body.toString().toResponseBody("application/json".toMediaType())).build()
        }.build())
        val service:com.lightningstudio.watchrss.phone.cloud.PhoneCloudSyncService
        init {
            runBlocking{store.save(session)}
            h.account=h.account!!.copy(epoch=epoch,generation=generation)
            val manager=c.getSystemService(android.net.ConnectivityManager::class.java);val shadow=org.robolectric.Shadows.shadowOf(manager)
            shadow.setActiveNetworkInfo(org.robolectric.shadows.ShadowNetworkInfo.newInstance(android.net.NetworkInfo.DetailedState.CONNECTED,android.net.ConnectivityManager.TYPE_MOBILE,0,true,true))
            val caps=android.net.NetworkCapabilities();org.robolectric.Shadows.shadowOf(caps).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET);shadow.setNetworkCapabilities(manager.activeNetwork,caps)
            service=com.lightningstudio.watchrss.phone.cloud.PhoneCloudSyncService(c,account,com.lightningstudio.watchrss.phone.data.backup.WatchRssBackupService(c,h.room,repo,reader,h.id),repo,
                com.lightningstudio.watchrss.phone.data.note.NoteRepository(h.room.noteDao(),h.id),h.id,client,keyManager=h.manager,settings=settings,syncV2Database={h.room},
                syncV2RoundTimeoutMillis = roundTimeoutMillis ?: 330_000L, cloudEntryEnabled = cloudEntryEnabled)
            // Replace only the local build gate with the existing real enabled product fixture.
            service.javaClass.getDeclaredField("syncV2Product\$delegate").apply{isAccessible=true}.set(service,lazy{h.product})
        }
        fun sync(manual:Boolean)=runBlocking{service.syncNow(manual)}
        fun prepareRevoke() = client.useEpoch(session, epoch)
        suspend fun switchAccount(withCloudKey: Boolean = true): String {
            val next = session.copy(userId = nextOwner, accessToken = "other-session")
            account.saveCompletedLogin(LoginProgress("test-login", 1, listOf("otp"), true, next))
            assertEquals(next.userId, account.session.value?.userId)
            if (withCloudKey) h.manager.saveAccountKey(next.userId, 1, h.server.wrap, true)
            h.account = h.account!!.copy(userId = next.userId)
            return next.userId
        }

        fun containerWithCloudQueue(queue: CoroutineScope): PhoneCompanionContainer =
            PhoneCompanionContainer(c).also { container ->
                val type = PhoneCompanionContainer::class.java
                type.getDeclaredField("accountRepository\$delegate").apply { isAccessible = true }
                    .set(container, lazy { account })
                type.getDeclaredField("cloudSyncService\$delegate").apply { isAccessible = true }
                    .set(container, lazy { service })
                type.getDeclaredField("appScope").apply { isAccessible = true }.set(container, queue)
            }
        override fun close(){scope.coroutineContext[kotlinx.coroutines.Job]?.cancel();service.javaClass.getDeclaredField("recordStore").apply{isAccessible=true}.get(service).let{(it as com.lightningstudio.watchrss.phone.cloud.PhoneCloudRecordStore).close()}}
    }
    @Test fun cloudEntryAtZeroPercentSurvivesLocalKeyRemovalAndServiceRecreation() = runBlocking {
        Server().use { server -> Host(server, "entry-recovery").use { host ->
            val state = MemoryPreferences()
            val session = MemoryPreferences()
            ServiceEntry(host, sessionPreferences = session, statePreferences = state, cloudEntryEnabled = { false }).use { entry ->
                assertTrue(entry.service.canOpenCloudSync())
                entry.service.discardSessionCloudState(entry.session)
                assertFalse(entry.service.hasLocalAccountKey())
                assertTrue("Keep recovery after clearing the old registration", entry.service.canOpenCloudSync())
            }
            ServiceEntry(host, sessionPreferences = session, statePreferences = state, cloudEntryEnabled = { false }).use { entry ->
                assertFalse(entry.service.hasLocalAccountKey())
                assertTrue("Keep recovery after restart", entry.service.canOpenCloudSync())
                assertFalse(entry.service.acceptDeviceApproval(entry.session.userId))
            }
            ServiceEntry(host, cloudEntryEnabled = { false }).use { entry ->
                assertFalse(entry.service.canOpenCloudSync())
                val error = runCatching { entry.service.prepareFirstDevice() }.exceptionOrNull()
                assertEquals("此手机暂未开放云同步入口", error?.message)
                assertEquals(0, entry.bootstraps)
            }
        } }
    }

    @Test fun cloudEntryDiscoversPreviouslyEnabledAccountAtZeroWithoutRegisteringThisPhone() = runBlocking {
        Server().use { server -> Host(server, "entry-discovery").use { host ->
            host.manager.clearAccountKeys(user)
            ServiceEntry(host, cloudEntryEnabled = { false }).use { entry ->
                assertFalse(entry.service.canOpenCloudSync())
                entry.hasCloudCommitments = false
                entry.service.refreshCloudEntryEligibility(user)
                assertFalse(entry.service.canOpenCloudSync())
                entry.hasCloudCommitments = true
                entry.service.refreshCloudEntryEligibility(user)
                assertTrue(entry.service.canOpenCloudSync())
                assertFalse(entry.service.hasLocalAccountKey())
                assertNull(entry.service.pairingEpoch())
                assertEquals(2, entry.bootstraps)
                assertEquals(0, entry.legacyCalls)
                assertFalse(entry.service.acceptDeviceApproval(user))
                assertFalse(entry.service.hasLocalAccountKey())

                val otherUser = entry.switchAccount(withCloudKey = false)
                assertFalse(entry.service.canOpenCloudSync())
                entry.service.refreshCloudEntryEligibility(user)
                assertFalse(entry.service.canOpenCloudSync())
                entry.hasCloudCommitments = false
                entry.service.refreshCloudEntryEligibility(otherUser)
                assertFalse(entry.service.canOpenCloudSync())
            }
        } }
    }

    @Test fun headPollSkipsRepeatedFullRoundsUntilTagChangesOrChoiceIsApplied() = runBlocking {
        Server().use { server ->
            Host(server, "choice-writer").use { host -> ServiceEntry(host).use { entry ->
                host.note("cloud copy")
                assertTrue(entry.service.syncNow().completed)
            } }
            Host(server, "choice-reader").use { host -> ServiceEntry(host).use { entry ->
                entry.approvedForHead = true
                host.note("local copy")
                assertEquals("pending_choice", entry.service.restoreCurrentLibrary().outcome?.errorCode)
                val poller = PhoneCloudHeadPoller({ 0 }, { 0 })
                fun serve(tag: String) {
                    // Return 200 even with the same ETag, as a server may revalidate unconditionally.
                    server.headResponse = {
                        server.response(JSONObject().put("etag", tag).put("pollAfterMs", 20_000))
                            .setHeader("ETag", "\"$tag\"")
                    }
                }
                serve("first")
                assertTrue(entry.service.pollCloudHead(poller) { true })
                val waiting = entry.bootstraps
                repeat(3) { assertTrue(entry.service.pollCloudHead(poller) { true }) }
                assertEquals(waiting, entry.bootstraps)
                assertEquals("\"first\"", server.headRequests.last().getHeader("If-None-Match"))
                server.headResponse = { MockResponse().setResponseCode(304) }
                assertTrue(entry.service.pollCloudHead(poller) { true })
                assertEquals(waiting, entry.bootstraps)
                serve("changed")
                assertTrue(entry.service.pollCloudHead(poller) { true })
                assertEquals(waiting + 1, entry.bootstraps)
                assertTrue(entry.service.pollCloudHead(poller) { true })
                assertEquals(waiting + 1, entry.bootstraps)

                val port = requireNotNull(host.product.uiPort.value)
                port.chooseCloud(port.openChoice())
                assertNull(port.state.value.choice)
                assertTrue(entry.service.pollCloudHead(poller) { true })
                assertEquals(waiting + 2, entry.bootstraps)
                assertNull(server.headRequests.last().getHeader("If-None-Match"))
                assertEquals(CloudSyncPhase.COMPLETE, entry.service.state.value.phase)
            } }
        }
    }

    @Test fun headPoll200RunsAFullRoundAnd304OnlyReadsHead() = runBlocking {
        Server().use { server -> Host(server, "head").use { host -> ServiceEntry(host).use { entry ->
            entry.approvedForHead = true
            host.note()
            assertTrue(entry.service.syncNow().completed)
            val poller = PhoneCloudHeadPoller({ 0 }, { 0 })
            val head = "\"first-head\""
            server.headResponse = {
                server.response(JSONObject().put("etag", "first-head")
                    .put("head", server.account.get("commitOid")).put("epoch", "1")
                    .put("signingKeysRev", "1").put("pollAfterMs", 20_000))
                    .setHeader("ETag", head)
            }
            entry.service.setBluetoothSyncActive(true)
            assertFalse(entry.service.pollCloudHead(poller) { true })
            assertEquals(0, server.headRequests.size)
            entry.service.setBluetoothSyncActive(false)
            val before = entry.service.state.value
            val bootstraps = entry.bootstraps
            val checkpoints = server.checkpoints
            assertTrue(entry.service.pollCloudHead(poller) { true })
            assertTrue(entry.service.state.value.lastCompletedAt > before.lastCompletedAt)
            assertTrue(server.checkpoints > checkpoints)
            assertEquals(bootstraps + 1, entry.bootstraps)
            assertNull(server.headRequests.single().getHeader("If-None-Match"))

            server.headResponse = { MockResponse().setResponseCode(304) }
            val state = entry.service.state.value
            val ui = host.product.uiPort.value!!.state.value
            val total = server.http.requestCount
            assertTrue(entry.service.pollCloudHead(poller) { true })
            assertEquals(total + 1, server.http.requestCount)
            assertEquals(bootstraps + 1, entry.bootstraps)
            val probe = server.headRequests.last()
            assertEquals("GET", probe.method)
            assertEquals("/functions/v1/sync/v2/account/head", probe.requestUrl!!.encodedPath)
            assertEquals(head, probe.getHeader("If-None-Match"))
            assertEquals(state, entry.service.state.value)
            assertEquals(ui, host.product.uiPort.value!!.state.value)
        } } }
    }

    @Test fun headPollRetriesRejectedDeviceTokenInTheSameRound() = runBlocking {
        for ((status, code) in listOf(403 to "scope_mismatch", 401 to "authentication_required")) {
            Server().use { server -> Host(server, "head-refresh").use { host ->
                var refreshes = 0
                ServiceEntry(host, refreshHeadDevice = { token, owner ->
                    assertEquals("synthetic-device", token)
                    assertEquals(user, owner)
                    refreshes++
                    server.deviceAuthorization = "Bearer rotated-device"
                    host.account = host.account!!.copy(deviceAuthorization = server.deviceAuthorization)
                    true
                }).use { entry ->
                    entry.approvedForHead = true
                    host.note()
                    assertTrue(entry.service.syncNow().completed)
                    val poller = PhoneCloudHeadPoller({ 0 }, { 0 })
                    server.headResponse = {
                        if (server.headRequests.size == 1) {
                            MockResponse().setResponseCode(status).setBody("""{"error":{"code":"$code"}}""")
                        } else {
                            MockResponse().setResponseCode(304)
                        }
                    }
                    assertTrue(entry.service.pollCloudHead(poller) { true })
                    assertEquals(1, refreshes)
                    assertEquals(listOf("Bearer synthetic-device", "Bearer rotated-device"),
                        server.headRequests.map { it.getHeader("x-watchrss-device-authorization") })
                    assertTrue(poller.available.value)
                }
            } }
        }
    }

    @Test fun headPollStopsAfterOneRetryAndKeepsTheNextChangedHeadEligible() = runBlocking {
        Server().use { server -> Host(server, "head-bounded").use { host ->
            var refreshes = 0
            ServiceEntry(host, refreshHeadDevice = { _, _ ->
                refreshes++
                server.deviceAuthorization = "Bearer rotated-device"
                host.account = host.account!!.copy(deviceAuthorization = server.deviceAuthorization)
                true
            }).use { entry ->
                entry.approvedForHead = true
                host.note()
                assertTrue(entry.service.syncNow().completed)
                val poller = PhoneCloudHeadPoller({ 0 }, { 0 })
                server.headResponse = {
                    MockResponse().setResponseCode(403).setBody("""{"error":{"code":"scope_mismatch"}}""")
                }
                assertTrue(entry.service.pollCloudHead(poller) { true })
                assertEquals(1, refreshes)
                assertEquals(2, server.headRequests.size)
                assertFalse(poller.available.value)

                val bootstraps = entry.bootstraps
                server.headResponse = {
                    server.response(JSONObject().put("etag", "next-head")
                        .put("head", server.account.get("commitOid")).put("epoch", "1")
                        .put("signingKeysRev", "1").put("pollAfterMs", 20_000))
                        .setHeader("ETag", "\"next-head\"")
                }
                assertTrue(entry.service.pollCloudHead(poller) { true })
                assertTrue(poller.available.value)
                assertEquals(bootstraps + 1, entry.bootstraps)
                assertEquals(1, refreshes)
            }
        } }
    }

    @Test fun headPollUnsupportedMatchesLegacySyncAndAddsNoPrompt() = runBlocking {
        data class LegacyResult(val sync: PhoneCloudSyncResult, val markdown: String, val requests: Int)
        suspend fun runAgainstOldBackend(enabled: Boolean, status: Int = 404): LegacyResult =
            Server().use { server -> Host(server, "legacy-head").use { host -> ServiceEntry(host).use { entry ->
                entry.approvedForHead = true
                server.headResponse = {
                    MockResponse().setResponseCode(status).setBody("""{"error":{"code":"invalid_schema"}}""")
                }
                host.note()
                assertTrue(entry.service.syncNow().completed)
                val poller = PhoneCloudHeadPoller({ 0 }, { 0 })
                val state = entry.service.state.value
                val ui = host.product.uiPort.value!!.state.value
                val bootstraps = entry.bootstraps
                val toastCount = org.robolectric.shadows.ShadowToast.shownToastCount()
                assertEquals(enabled, entry.service.pollCloudHead(poller) { enabled })
                repeat(3) { assertFalse(entry.service.pollCloudHead(poller) { enabled }) }
                assertEquals(if (enabled) 1 else 0, server.headRequests.size)
                assertFalse(poller.available.value)
                assertEquals(state, entry.service.state.value)
                assertEquals(ui, host.product.uiPort.value!!.state.value)
                assertEquals(bootstraps, entry.bootstraps)
                assertEquals(toastCount, org.robolectric.shadows.ShadowToast.shownToastCount())

                host.note("legacy follow-up")
                val before = server.http.requestCount
                val result = entry.service.syncDebouncedChanges(fast = poller.available.value)
                assertTrue(result.completed)
                assertEquals(toastCount, org.robolectric.shadows.ShadowToast.shownToastCount())
                LegacyResult(result.copy(outcome = result.outcome?.copy(lastCompletedAt = 0)),
                    server.values().single().getString("markdown"), server.http.requestCount - before)
            } } }

        val legacy = runAgainstOldBackend(false)
        for (status in listOf(404, 405, 400)) {
            assertEquals(legacy, runAgainstOldBackend(true, status))
        }
    }

    @Test fun headPoll429ReadsRetryAfterAndServerDelayWithoutUpdatingSyncState() = runBlocking {
        Server().use { server -> Host(server, "head-rate").use { host ->
            host.note()
            assertTrue(host.product.sync(server.announcement).enabled)
            val owner = requireNotNull(host.product.headOwner(user))
            server.headResponse = {
                MockResponse().setResponseCode(429).setHeader("Retry-After", 13)
                    .setBody("""{"pollAfterMs":29000}""")
            }
            assertEquals(CloudHeadReply.RateLimited(29_000), host.product.probeHead(owner, null))
            server.headResponse = { MockResponse().setResponseCode(429).setBody("""{"pollAfterMs":29000}""") }
            assertEquals(CloudHeadReply.RateLimited(29_000), host.product.probeHead(owner, null))
            server.headResponse = {
                MockResponse().setResponseCode(429).setHeader("Retry-After", "720")
                    .setBody("""{"pollAfterMs":600000}""")
            }
            assertEquals(CloudHeadReply.RateLimited(720_000), host.product.probeHead(owner, null))
            server.headResponse = {
                val date = java.util.Date(System.currentTimeMillis() + 600_000)
                val format = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US)
                format.timeZone = java.util.TimeZone.getTimeZone("GMT")
                MockResponse().setResponseCode(429).setHeader("Retry-After", format.format(date))
            }
            val dateReply = host.product.probeHead(owner, null) as CloudHeadReply.RateLimited
            assertTrue(requireNotNull(dateReply.retryAfterMs) in 590_000..600_000)
            server.headResponse = {
                MockResponse().setResponseCode(403).setBody("""{"error":{"code":"head_poll_disabled"}}""")
            }
            assertEquals(CloudHeadReply.Disabled, host.product.probeHead(owner, null))
            server.headResponse = {
                MockResponse().setResponseCode(503).setBody("""{"error":{"code":"temporarily_unavailable"}}""")
            }
            assertTrue(runCatching { host.product.probeHead(owner, null) }.exceptionOrNull() is java.io.IOException)
            assertEquals(6, server.headRequests.size)
        } }
    }

    @Test fun formalEntrySelectsAuthenticatedV2BeforeLegacyAndForwardsManualBodyConsent() {
        Server().use{server->Host(server,"entry").use{h->ServiceEntry(h).use{entry->
            server.closedBodyChannel=true;h.wifi=false;h.note("![a](assets/entry.png)")
            val file=File(h.context.filesDir,"notes/assets/entry.png");file.parentFile!!.mkdirs();file.writeText("retained entry original")
            entry.settings.formatUpgradeRequiredVersion=com.lightningstudio.watchrss.phone.BuildConfig.VERSION_CODE
            fun finishProjection(manual: Boolean) {
                repeat(4) {
                    val outcome = requireNotNull(entry.sync(manual).outcome)
                    if (outcome.errorCode != "budget_paused") return
                    assertEquals(CloudSyncPhase.IDLE, outcome.phase)
                }
                fail("entry scan did not resume")
            }
            finishProjection(false);assertEquals(1,server.publishes);assertEquals(0,entry.legacyCalls);assertEquals(0,server.bodyRequests)
            assertEquals(0,entry.settings.formatUpgradeRequiredVersion);assertFalse(entry.service.formatStatus.value.upgradeRequired)
            finishProjection(true);assertTrue(server.bodyRequests>0);assertEquals(0,entry.legacyCalls);assertEquals("retained entry original",file.readText())
            assertEquals("body_channel_write_disabled",h.product.uiPort.value!!.state.value.errorCode)
        }}}
    }
    @Test fun currentRestoreDoesNotCreateOrPublishAnEmptyCloudLibrary() {
        Server().use { server -> Host(server, "empty-restore").use { host -> ServiceEntry(host).use { entry ->
            host.note()
            val error = runCatching { runBlocking { entry.service.restoreCurrentLibrary() } }.exceptionOrNull()
            assertEquals("current_library_missing", (error as? SyncV2CloudError)?.code)
            assertEquals("云端还没有可恢复的 Sync v2 资料库。", entry.service.state.value.message)
            assertEquals(0, server.inits)
            assertEquals(0, server.publishes)
            assertEquals(0, entry.legacyCalls)
            assertEquals("local", host.text())
        } } }
    }

    @Test fun currentRestoreAppliesOnAnEmptyPhoneAndOffersChoiceOnANonemptyPhone() {
        Server().use { server ->
            Host(server, "author").use { writer -> ServiceEntry(writer).use { entry ->
                writer.note("cloud copy")
                entry.sync(true)
                assertEquals(1, server.publishes)
            } }
            Host(server, "clean").use { clean -> ServiceEntry(clean).use { entry ->
                assertTrue(runBlocking { entry.service.restoreCurrentLibrary() }.completed)
                assertEquals("cloud copy", clean.text())
                assertEquals("当前云端资料库已恢复", entry.service.state.value.message)
                assertEquals(0, entry.legacyCalls)
                assertEquals(1, server.publishes)
            } }
            Host(server, "nonempty").use { nonempty -> ServiceEntry(nonempty).use { entry ->
                nonempty.note("local edit")
                val result = runBlocking { entry.service.restoreCurrentLibrary() }
                assertFalse(result.completed)
                assertEquals("pending_choice", result.outcome?.errorCode)
                val port = requireNotNull(nonempty.product.uiPort.value)
                assertEquals("awaiting_choice", port.state.value.phase)
                assertEquals("请查看并选择保留哪一份", entry.service.state.value.message)
                val preview = runBlocking { port.openChoice() }
                assertTrue(preview.local.isNotEmpty())
                assertTrue(preview.cloud.isNotEmpty())
                assertFalse(port.state.value.gates.historyRestore)
                assertEquals("local edit", nonempty.text())
                assertEquals(1, server.publishes)
                runBlocking { port.chooseCloud(preview) }
                assertEquals("cloud copy", nonempty.text())
                assertNull(port.state.value.choice)
                assertTrue(port.state.value.recoveryCopies.isNotEmpty())
                assertEquals(0, entry.legacyCalls)
                assertEquals(1, server.publishes)
            } }
        }
    }

    @Test fun obsoleteAccountPanelActionsCannotReachLegacyHttpWhenV2IsEnabled() {
        Server().use { server -> Host(server, "legacy-fence").use { host -> ServiceEntry(host).use { entry ->
            entry.sync(true)
            val service = entry.service
            runBlocking {
                for (action in listOf<suspend () -> Any>(
                    { service.listSnapshotHistory() },
                    { service.estimateManualSync() },
                    { service.inspectSnapshot("legacy") },
                    { service.restoreSnapshot("legacy") },
                    { service.deleteSnapshot("legacy") },
                    { service.cloudRecordSyncEnabled(user) },
                    { service.resetCloudLibrary("legacy") },
                )) {
                    assertEquals("此云端操作当前不可用", runCatching { action() }.exceptionOrNull()?.message)
                }
            }
            assertEquals(0, entry.legacyCalls)
        } } }
    }

    @Test fun formalEntryClosedProductRetainsLegacyFenceAndLocalLibrary() {
        Server().use{server->Host(server,"closed",localSwitch=false).use{h->ServiceEntry(h).use{entry->
            h.note()
            assertTrue(runCatching{entry.sync(false)}.isFailure)
            entry.sync(false)
            assertEquals(0,server.http.requestCount);assertEquals(1,entry.bootstraps);assertEquals(1,entry.legacyCalls)
            assertEquals(0,entry.settings.formatUpgradeRequiredVersion);assertFalse(entry.service.formatStatus.value.upgradeRequired)
            assertFalse(entry.service.state.value.message.contains("请升级"));assertEquals("local",h.text())
        }}}
    }

    @Test fun formalEntryStopsBothCloudChannelsAfterBootstrapWithdrawsAnnouncement() {
        Server().use{server->Host(server,"withdrawal").use{h->ServiceEntry(h).use{entry->
            h.note();entry.sync(false)
            assertTrue(server.http.requestCount>0)
            val requests=server.http.requestCount
            val preferences=entry.preferences.all.toMap()
            entry.announced=false
            repeat(2){entry.sync(false)}
            assertEquals(requests,server.http.requestCount);assertEquals(0,entry.legacyCalls)
            assertEquals(3,entry.bootstraps);assertEquals("local",h.text())
            assertNull(h.product.uiPort.value)
            assertEquals(preferences,entry.preferences.all)
            assertFalse(entry.service.formatStatus.value.upgradeRequired)
        }}}
    }

    @Test fun formalEntryNeverFallsBackToLegacyWhenV2ReadGateCloses() {
        Server().use{server->Host(server,"read-closed").use{h->ServiceEntry(h).use{entry->
            h.note();entry.sync(false)
            server.readGate=false
            entry.sync(false)
            assertEquals(0,entry.legacyCalls);assertEquals("local",h.text())
            assertFalse(entry.service.formatStatus.value.upgradeRequired)
        }}}
    }

    @Test fun manualRoundReportsMissingAnnouncementAndRecoversInTheSameEpoch() {
        Server().use { server -> Host(server, "manual-announcement").use { host -> ServiceEntry(host).use { entry ->
            host.note("retained")
            entry.announced = false
            val attempt = entry.sync(true)
            assertFalse(attempt.completed)
            val blocked = entry.service.state.value
            assertEquals(blocked, attempt.outcome)
            assertEquals("not_announced", blocked.errorCode)
            assertEquals("BLOCKED", blocked.phase.name)
            assertEquals(0L, blocked.lastCompletedAt)
            assertEquals(0, server.http.requestCount)
            assertEquals("retained", host.text())

            entry.announced = true
            assertTrue(entry.sync(true).completed)
            assertEquals("COMPLETE", entry.service.state.value.phase.name)
            assertNull(entry.service.state.value.errorCode)
            assertEquals(1, server.publishes)
            assertEquals(0, entry.legacyCalls)
        } } }
    }

    @Test fun manualRoundReportsClosedReadGateAndRecoversWithoutLegacyRequests() {
        Server().use { server -> Host(server, "manual-read-gate").use { host -> ServiceEntry(host).use { entry ->
            host.note("retained")
            server.readGate = false
            val attempt = entry.sync(true)
            assertFalse(attempt.completed)
            val blocked = entry.service.state.value
            assertEquals(blocked, attempt.outcome)
            assertEquals("feature_disabled", blocked.errorCode)
            assertEquals("BLOCKED", blocked.phase.name)
            assertEquals(0L, blocked.lastCompletedAt)
            assertEquals(0, server.publishes)
            assertEquals("retained", host.text())

            server.readGate = true
            assertTrue(entry.sync(true).completed)
            assertEquals("COMPLETE", entry.service.state.value.phase.name)
            assertNull(entry.service.state.value.errorCode)
            assertEquals(1, server.publishes)
            assertEquals(0, entry.legacyCalls)
        } } }
    }

    @Test fun manualRoundReportsBootstrapTimeoutWithoutMarkingCompletion() {
        Server().use { server -> Host(server, "manual-timeout").use { host -> ServiceEntry(host).use { entry ->
            host.note("retained")
            entry.bootstrapTimesOut = true
            assertTrue(runCatching { entry.sync(true) }.isFailure)
            val state = entry.service.state.value
            assertEquals("ERROR", state.phase.name)
            assertEquals("sync_timeout", state.errorCode)
            assertEquals(0L, state.lastCompletedAt)
            assertEquals("retained", host.text())
            assertEquals(0, entry.legacyCalls)
            entry.bootstrapTimesOut = false
            assertTrue(entry.sync(true).completed)
            assertNull(entry.service.state.value.errorCode)
        } } }
    }

    @Test fun formalEntryRequiresAuthenticatedBootstrapAndOnlyServerUnsupportedBuildShowsUpgrade() {
        for(unsupported in listOf(false,true))Server().use{server->Host(server,"auth").use{h->ServiceEntry(h).use{entry->
            entry.unsupported=unsupported;entry.failAuthentication=!unsupported
            assertTrue(runCatching{entry.sync(true)}.isFailure)
            assertEquals(0,server.http.requestCount);assertEquals(0,entry.legacyCalls)
            assertEquals(unsupported,entry.service.formatStatus.value.upgradeRequired)
            assertEquals(if(unsupported)com.lightningstudio.watchrss.phone.BuildConfig.VERSION_CODE else 0,entry.settings.formatUpgradeRequiredVersion)
        }}}
    }

    @Test fun fix1ImportedArticleWithoutBodyDoesNotCompleteRestoreOrOpenReader() {
        Server().use { server ->
            Host(server, "article-author").use { writer -> ServiceEntry(writer).use { entry ->
                val records = javaClass.classLoader!!.getResourceAsStream("syncv2-business-schema.json")!!
                    .bufferedReader().use { JSONObject(it.readText()) }.getJSONArray("records")
                val article = (0 until records.length()).map(records::getJSONObject)
                    .single { it.getString("kind") == "article" }.getJSONObject("value")
                    .put("articleId", "imported-article")
                    .put("url", "https://watchrss.local/import-content/txt/example")
                    .put("independentSaved", true).put("contentText", "private cloud body")
                SyncV2PhoneApply(writer.room.openHelper.writableDatabase)
                    .upsert("phone_articles", listOf("articleId"), article)
                assertTrue(entry.sync(true).completed)
                assertEquals(1, server.publishes)
            } }
            Host(server, "article-receiver").use { reader -> ServiceEntry(reader).use { entry ->
                val outcome = runBlocking { entry.service.restoreCurrentLibrary() }
                val row = runBlocking { reader.room.phoneArticleDao().getById("imported-article") }
                assertEquals("", row?.contentText)
                val read = runCatching { runBlocking { entry.service.loadArticleForReading("imported-article") } }
                assertTrue("Cloud restore must not report completion with an empty article", !outcome.completed)
                assertEquals("pending_bodies", outcome.outcome?.errorCode)
                assertTrue("Reader must expose missing body as unavailable", read.isFailure)
            } }
        }
    }

    @Test fun fix2MissingImportedTextFileDoesNotCompleteRestoreOrOpenReader() {
        Server().use { server ->
            val id = "missing-imported-text"
            Host(server, "import-writer").use { writer -> ServiceEntry(writer).use { entry ->
                val records = javaClass.getResourceAsStream("/syncv2-business-schema.json")!!
                    .bufferedReader().use { JSONObject(it.readText()).getJSONArray("records") }
                val article = (0 until records.length()).map(records::getJSONObject)
                    .single { it.getString("kind") == "article" }.getJSONObject("value")
                    .put("articleId", id)
                    .put("url", "https://watchrss.local/import-content/txt/missing")
                    .put("independentSaved", true).put("contentText", "private cloud body")
                SyncV2PhoneApply(writer.room.openHelper.writableDatabase)
                    .upsert("phone_articles", listOf("articleId"), article)
                assertTrue(entry.sync(true).completed)
            } }
            Host(server, "import-receiver").use { reader -> ServiceEntry(reader).use { entry ->
                val articles = reader.room.phoneArticleDao()
                val initial = runBlocking { entry.service.restoreCurrentLibrary() }
                assertEquals("pending_bodies", initial.outcome?.errorCode)
                val row = runBlocking { requireNotNull(articles.getById(id)) }
                assertTrue(row.contentHash.isNotBlank())
                val files = FileArticleContentStore(reader.context)
                val marker = files.markerFor(id)
                assertNull(files.textChunkHandle(marker))
                fun cacheBody(text: String, installFile: Boolean = false, html: String? = null) = runBlocking {
                    reader.product.action(requireNotNull(reader.product.uiPort.value)) { runtime ->
                        val projection = runtime.projection
                        projection.accountWriter(projection.state().version) { state ->
                            val db = projection.accountDatabase
                            if (installFile) {
                                val file = File(reader.context.filesDir,
                                    "imported_text/" + requireNotNull(articleContentMarkerFileName(text)))
                                file.parentFile!!.mkdirs()
                                // Install as a cache file, without recording a new local import.
                                ConditionalBodyFile.install(file, "private cloud body".toByteArray())
                            }
                            db.execSQL("UPDATE phone_articles SET contentText=?, contentHtml=? WHERE articleId=?", arrayOf(text, html, id))
                            state.body.put("businessStamp", SyncV2BusinessWitness.read(db).encoded())
                        }
                    }
                }
                for (body in listOf(marker, " \t\n\u00a0\u2003\u3000")) {
                    cacheBody(body)
                    val restore = runBlocking { entry.service.restoreCurrentLibrary() }.outcome
                    val read = runCatching { runBlocking { entry.service.loadArticleForReading(id) } }
                    val local = runBlocking { requireNotNull(articles.getById(id)) }
                    assertEquals(row.contentHash, local.contentHash)
                    assertEquals(body, local.contentText)
                    assertEquals(CloudSyncPhase.IDLE, restore?.phase)
                    assertEquals("pending_bodies", restore?.errorCode)
                    assertTrue(read.exceptionOrNull() is MissingCloudBodyException)
                    val sync = entry.sync(true).outcome
                    assertEquals(CloudSyncPhase.IDLE, sync?.phase)
                    assertEquals("pending_bodies", sync?.errorCode)
                }
                cacheBody(marker, installFile = true)
                val restored = runBlocking { entry.service.restoreCurrentLibrary() }
                assertTrue(restored.outcome.toString(), restored.completed)
                assertEquals(marker, runBlocking { entry.service.loadArticleForReading(id) }?.contentText)
                val largeInline = "x".repeat(17 * 1024 * 1024)
                for ((text, html) in listOf(largeInline to null, "" to largeInline)) {
                    cacheBody(text, html = html)
                    val large = runBlocking { entry.service.restoreCurrentLibrary() }
                    assertTrue(large.outcome.toString(), large.completed)
                }
            } }
        }
    }

    @Test fun fix2MembershipCannotRepopulateStateWhileLoginPublishesAnotherAccount() = runBlocking {
        val clearing = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val backing = MemoryPreferences()
        val prefs = object : SharedPreferences by backing {
            override fun edit(): SharedPreferences.Editor {
                val editor = backing.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun commit(): Boolean {
                        clearing.countDown()
                        assertTrue(resume.await(10, TimeUnit.SECONDS))
                        return editor.commit()
                    }
                }
            }
        }
        Server().use { server -> Host(server, "switch-window").use { host ->
            ServiceEntry(host, sessionPreferences = prefs).use { entry ->
                val switch = async(Dispatchers.IO) { entry.switchAccount() }
                try {
                    assertTrue(clearing.await(5, TimeUnit.SECONDS))
                    runCatching { entry.service.refreshDashboardMembership() }
                } finally {
                    resume.countDown()
                    switch.await()
                }
                assertEquals(PhoneCloudSyncState(), entry.service.state.value)
            }
        } }
    }

    @Test fun fix2LateDeviceRevokeEpochMismatchLeavesTheNewAccountIntact() = runBlocking {
        Server().use { server -> Host(server, "late-revoke").use { host -> ServiceEntry(host).use { entry ->
            val revokeEntered = CountDownLatch(1)
            val revokeReply = CountDownLatch(1)
            entry.prepareRevoke()
            entry.onRevokeRequest = {
                revokeEntered.countDown()
                assertTrue(revokeReply.await(10, TimeUnit.SECONDS))
            }
            val revoke = async(Dispatchers.IO) { runCatching { entry.service.revokeDevice("old-device") } }
            try {
                assertTrue(revokeEntered.await(5, TimeUnit.SECONDS))
                entry.switchAccount()
                // These fixtures exercise B after the user confirms ownership.
                entry.service.resolveLibraryOwner(merge = true)
                entry.service.syncNow(manual = true)
                val port = requireNotNull(host.product.uiPort.value)
                val state = entry.service.state.value
                assertEquals(CloudSyncPhase.COMPLETE, state.phase)
                val epoch = requireNotNull(entry.settings.libraryEpoch)
                revokeReply.countDown()
                assertNotNull(revoke.await().exceptionOrNull())
                assertEquals(state, entry.service.state.value)
                assertEquals(epoch, entry.settings.libraryEpoch)
                assertTrue(host.manager.hasAccountKey(host.account!!.userId))
                assertSame(port, host.product.uiPort.value)
            } finally {
                revokeReply.countDown()
            }
        } } }
    }

    @Test fun fix2CancelledBodyChannelDoesNotScanTheNextEntry() {
        Server().use { server -> Host(server, "cancel-bodies").use { host ->
            server.closedBodyChannel = true
            host.note("![one](assets/one.png) ![two](assets/two.png)")
            for (name in listOf("one", "two")) {
                File(host.context.filesDir, "notes/assets/$name.png").apply {
                    parentFile!!.mkdirs()
                    writeText(name)
                }
            }
            repeat(4) {
                if (server.publishes == 0) {
                    try { host.sync() } catch (error: IllegalStateException) {
                        if (error.message != "budget_paused") throw error
                    }
                }
            }
            assertEquals(1, server.publishes)
            var scanned = 0
            val cancelled = CancellationException("cancelled during body scan")
            host.transportSignals = {
                scanned++
                throw cancelled
            }
            val result = runCatching {
                runBlocking {
                    host.product.action(requireNotNull(host.product.uiPort.value)) {
                        requireNotNull(it.bodies).sync(manual = true)
                    }
                }
            }
            assertEquals(1, scanned)
            val failure = result.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertEquals(cancelled.message, failure?.message)
        } }
    }

    @Test fun fix2DeadlineDuringBodyTransferReportsSyncTimeout() = runBlocking {
        Server().use { server -> Host(server, "body-deadline").use { host ->
            server.closedBodyChannel = true
            host.note("![one](assets/one.png) ![two](assets/two.png)")
            for (name in listOf("one", "two")) {
                File(host.context.filesDir, "notes/assets/$name.png").apply {
                    parentFile!!.mkdirs()
                    writeText(name)
                }
            }
            ServiceEntry(host, roundTimeoutMillis = 3_000).use { entry ->
                repeat(3) {
                    if (server.publishes == 0) entry.service.syncNow(manual = true)
                }
                assertEquals(1, server.publishes)
                val before = server.bodyRequests
                server.blockBodyList = true
                val round = async(Dispatchers.IO) { entry.service.syncNow(manual = true) }
                try {
                    assertTrue(server.entered.await(5, TimeUnit.SECONDS))
                    val outcome = withTimeout(4_500) { round.await().outcome }
                    assertEquals(CloudSyncPhase.ERROR, outcome?.phase)
                    assertEquals("sync_timeout", outcome?.errorCode)
                    assertEquals(before + 1, server.bodyRequests)
                } finally {
                    server.unblock.countDown()
                }
            }
        } }
    }

    @Test fun fix2DeadlineDuringKeyPreparationRetainsReadableMembership() = runBlocking {
        Server().use { server -> Host(server, "key-deadline").use { host ->
            host.manager.clearAccountKeys(user)
            ServiceEntry(host, roundTimeoutMillis = 1_200).use { entry ->
                var calls = 0
                entry.onBootstrapRequest = {
                    if (++calls == 2) Thread.sleep(2_500)
                }
                val outcome = entry.service.syncNow(manual = true).outcome
                assertEquals(CloudSyncPhase.ERROR, outcome?.phase)
                assertEquals("sync_timeout", outcome?.errorCode)
                assertTrue(outcome?.member?.readable == true)
                assertFalse(entry.service.hasLocalAccountKey())
                assertEquals(0, server.http.requestCount)
            }
        } }
    }

    @Test fun fix1ProductCancellationStopsInFlightControlRequest() = runBlocking {
        Server().use { server -> Host(server, "timeout-control").use { host ->
            server.block = "capabilities"
            val attempt = async(Dispatchers.IO) { withTimeoutOrNull(800) { host.product.sync(server.announcement) } }
            val rescue = Executors.newSingleThreadScheduledExecutor()
            try {
                assertTrue(server.entered.await(5, TimeUnit.SECONDS))
                rescue.schedule({ server.unblock.countDown() }, 4, TimeUnit.SECONDS)
                assertNull(withTimeout(2_000) { attempt.await() })
                assertEquals("cancelled discovery must stop subsequent control calls", 1, server.http.requestCount)
            } finally {
                server.unblock.countDown()
                rescue.shutdownNow()
            }
        } }
    }

    @Test fun fix1AccountReleaseClearsBlockedStateAndMembership() {
        Server().use { server -> Host(server, "state-release").use { host -> ServiceEntry(host).use { entry ->
            entry.announced = false
            entry.sync(true)
            assertNotNull(entry.service.state.value.member)
            assertEquals("not_announced", entry.service.state.value.errorCode)
            entry.service.discardSessionCloudState(entry.session)
            assertEquals(PhoneCloudSyncState(), entry.service.state.value)
        } } }
    }

    @Test fun fix1BoundedArticlePageUsesAnExplicitContinuationState() {
        Server().use { server -> Host(server, "page-budget").use { host -> ServiceEntry(host).use { entry ->
            val records = javaClass.classLoader!!.getResourceAsStream("syncv2-business-schema.json")!!
                .bufferedReader().use { JSONObject(it.readText()) }.getJSONArray("records")
            val sample = (0 until records.length()).map(records::getJSONObject)
                .single { it.getString("kind") == "article" }.getJSONObject("value")
            val apply = SyncV2PhoneApply(host.room.openHelper.writableDatabase)
            repeat(17) { n ->
                apply.upsert("phone_articles", listOf("articleId"), JSONObject(sample.toString())
                    .put("articleId", "page-$n").put("url", "https://synthetic.invalid/$n")
                    .put("contentText", "article body $n"))
            }
            val outcome = entry.sync(true).outcome
            assertEquals("IDLE", outcome?.phase?.name)
            assertEquals("budget_paused", outcome?.errorCode)
            assertEquals(0, server.publishes)
            assertTrue(entry.sync(true).completed)
            assertEquals(1, server.publishes)
        } } }
    }

    @Test fun fix1QueuedManualSyncAndRestoreDoNotUseTheNewAccount() = runBlocking {
        Server().use { server -> Host(server, "queued-owner").use { host -> ServiceEntry(host).use { entry ->
            entry.announced = false
            entry.sync(true)
            val gate = entry.service.javaClass.getDeclaredField("syncMutex")
                .apply { isAccessible = true }.get(entry.service) as CloudSyncSingleFlight
            val trace = File(host.context.cacheDir, "cloud-sync-debug.trace")
            fun requested() = trace.readLines().count { it.endsWith(" sync-request") }
            val before = requested()
            val locked = CompletableDeferred<Unit>()
            val unlock = CompletableDeferred<Unit>()
            val blocker = async(Dispatchers.IO) {
                gate.withLock {
                    locked.complete(Unit)
                    unlock.await()
                }
            }
            try {
                locked.await()
                val manual = async(Dispatchers.IO) {
                    runCatching { entry.service.syncNow(manual = true) }
                }
                val restore = async(Dispatchers.IO) {
                    runCatching { entry.service.restoreCurrentLibrary() }
                }
                withTimeout(5_000) { while (requested() < before + 2) delay(10) }
                assertFalse(manual.isCompleted)
                assertFalse(restore.isCompleted)
                val bootstraps = entry.bootstraps
                entry.switchAccount()
                assertEquals(PhoneCloudSyncState(), entry.service.state.value)
                unlock.complete(Unit)
                assertEquals("账号已切换，请重新操作", manual.await().exceptionOrNull()?.message)
                assertEquals("账号已切换，请重新操作", restore.await().exceptionOrNull()?.message)
                assertEquals(bootstraps, entry.bootstraps)
                assertEquals(PhoneCloudSyncState(), entry.service.state.value)
                // These fixtures exercise B after the user confirms ownership.
                entry.service.resolveLibraryOwner(merge = true)
                assertEquals("not_announced", entry.service.syncNow(manual = true).outcome?.errorCode)
                assertEquals(bootstraps + 1, entry.bootstraps)
            } finally {
                unlock.complete(Unit)
                blocker.await()
            }
        } } }
    }

    @Test fun fix4DashboardActionsQueuedBeforeSwitchCannotPublishThePreviousLibrary() = runBlocking {
        Server().use { server -> Host(server, "dashboard-switch").use { host -> ServiceEntry(host).use { entry ->
            val articleOwner = entry.session.userId
            host.note("private library of A")
            assertEquals(articleOwner, entry.settings.ownerUserId)
            assertFalse(entry.service.libraryOwnerConflict())
            entry.service.setSyncPaused(true, expectedUserId = articleOwner)

            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
                val actions = CoroutineScope(SupervisorJob() + dispatcher)
                val startActions = CountDownLatch(1)
                actions.launch { check(startActions.await(10, TimeUnit.SECONDS)) }
                try {
                    val container = entry.containerWithCloudQueue(actions)
                    // Both clicks are pending. Drain resume before retry so the automatic
                    // single-flight fast path cannot hide either account check.
                    val retry = actions.async(start = CoroutineStart.LAZY) {
                        runCatching { entry.service.syncNow(expectedUserId = articleOwner) }
                    }
                    container.setCloudSyncPaused(false, expectedUserId = articleOwner)
                    val queued = actions.coroutineContext[Job]!!.children.filter { it != retry }.toList()
                    assertEquals(2, queued.size)

                    val nextOwner = entry.switchAccount()
                    assertTrue(entry.service.hasLocalAccountKey())
                    assertTrue(entry.service.libraryOwnerConflict())
                    assertEquals("0", server.account.getString("revision"))
                    assertEquals(0, entry.bootstraps)
                    val staleToggle = runCatching {
                        container.setCloudSyncPaused(true, expectedUserId = articleOwner)
                    }
                    assertEquals("账号已切换，请重新操作", staleToggle.exceptionOrNull()?.message)
                    assertFalse(entry.service.paused.value)
                    startActions.countDown()
                    val retryResult = withTimeout(10_000) {
                        queued.joinAll()
                        retry.await()
                    }
                    val stateAfterClicks = entry.service.state.value
                    val direct = entry.service.syncNow(manual = true, expectedUserId = nextOwner).outcome

                    assertEquals("账号已切换，请重新操作", retryResult.exceptionOrNull()?.message)
                    assertEquals("Queued resume must reject A; direct result=${direct?.phase}; publishes=${server.publishes}",
                        PhoneCloudSyncState(), stateAfterClicks)
                    assertEquals(CloudSyncPhase.BLOCKED, direct?.phase)
                    assertEquals("library_owner_conflict", direct?.errorCode)
                    assertEquals(articleOwner, entry.settings.ownerUserId)
                    assertEquals("private library of A", host.text())
                    assertEquals(0, entry.bootstraps)
                    assertEquals(0, server.http.requestCount)
                    assertEquals(0, server.publishes)
                    assertEquals("0", server.account.getString("revision"))
                } finally {
                    startActions.countDown()
                    actions.cancel()
                }
            }
        } } }
    }

    @Test fun fix1RoundDeadlineStopsDiscoveryAndRecordsSyncTimeout() = runBlocking {
        Server().use { server -> Host(server, "outer-deadline").use { host ->
            ServiceEntry(host, roundTimeoutMillis = 1_200L).use { entry ->
                entry.memberReadable = false
                server.block = "capabilities"
                val result = async(Dispatchers.IO) { entry.service.syncNow(manual = true) }
                try {
                    assertTrue(server.entered.await(5, TimeUnit.SECONDS))
                    val outcome = withTimeout(2_500) { result.await() }.outcome
                    assertEquals(CloudSyncPhase.ERROR, outcome?.phase)
                    assertEquals("sync_timeout", outcome?.errorCode)
                    assertEquals(0L, outcome?.lastCompletedAt)
                    assertFalse(requireNotNull(outcome?.member).readable)
                    assertEquals(1, server.http.requestCount)
                    assertEquals(0, server.publishes)
                } finally {
                    server.unblock.countDown()
                }
            }
        } }
    }

}
