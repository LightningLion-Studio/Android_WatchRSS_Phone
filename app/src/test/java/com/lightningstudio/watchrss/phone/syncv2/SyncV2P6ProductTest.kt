package com.lightningstudio.watchrss.phone.syncv2

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import com.lightningstudio.watchrss.phone.cloud.CloudKeyManager
import com.lightningstudio.watchrss.phone.data.db.PhoneCompanionDatabase
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
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
        var descriptorUnavailable=false;var failAfterConflict=false;var dropInit=false;val initBytes=mutableListOf<String>()
        val objects=linkedMapOf<String,ByteArray>();val prefixes=linkedMapOf<String,ByteArray>();val totals=linkedMapOf<String,Int>()
        val bindings=linkedMapOf<String,JSONObject>();val refs=linkedMapOf<String,JSONObject>();val devices=linkedMapOf<String,JSONObject>();val results=linkedMapOf<String,JSONObject>()
        var account=JSONObject().put("commitOid",JSONObject.NULL).put("revision","0").put("rollbackGeneration","0")
        var publishes=0;var acks=0;var inits=0;var queries=0;var dropPublish=false;var failQuery=false
        var block:String?=null;val entered=CountDownLatch(1);val unblock=CountDownLatch(1)
        var concurrentWinner:SyncV2RepoBootstrap.Candidate?=null
        fun scope()=descriptor!!.getJSONObject("body").getJSONObject("scope")
        fun dto(s:JSONObject)=JSONObject().put("version",2).put("scope",s)
        fun response(v:JSONObject)=MockResponse().setHeader("Content-Type","application/json").setBody(SyncV2Codec.canonical(v))
        fun error(code:String)=response(JSONObject().put("error",JSONObject().put("code",code).put("retryable",code=="ref_conflict").put("retryAfterMillis",JSONObject.NULL).put("details",if(code=="ref_conflict")JSONObject().put("currentOid",account.get("commitOid")).put("currentRevision",account.get("revision"))else JSONObject()))).setResponseCode(if(code=="object_missing")404 else if(code=="temporarily_unavailable")503 else 409)
        fun window(s:JSONObject)=dto(s).put("windowId","aa".repeat(16)).put("issuedAtMillis",now).put("expiresAtMillis",now+300000)
            .put("historyFloorMillis",0).put("historyFloorRevision","0").put("serverRevision",account.get("revision")).put("refs",JSONArray(devices.values.sortedBy{it.getString("deviceId")})).put("account",JSONObject(account.toString()))
        fun receipt(s:JSONObject,oid:String,total:Int,offset:Int)=receipt.signed("wrss.sync.v2/object-receipt-sign",JSONObject().put("version",2).put("scope",s).put("oid",oid).put("totalBytes",total).put("durableOffset",offset)
            .put("storeId","99".repeat(16)).put("receiptRevision","1").put("createdAtMillis",now).put("expiresAtMillis",now+604800000))
        init {
            http.dispatcher=object:Dispatcher(){override fun dispatch(r:RecordedRequest):MockResponse {
                try {
                    assertEquals("Bearer synthetic-account",r.getHeader("Authorization"));assertEquals("synthetic-api",r.getHeader("apikey"))
                    assertEquals("Bearer synthetic-device",r.getHeader("x-watchrss-device-authorization"))
                    val route=r.requestUrl!!.encodedPath.removePrefix(SyncV2Origin.path)
                    if(route==block){block=null;entered.countDown();assertTrue(unblock.await(10,TimeUnit.SECONDS))}
                    val s=JSONObject().put("repoId",r.requestUrl!!.queryParameter("repoId")).put("libraryEpoch",r.requestUrl!!.queryParameter("libraryEpoch")).put("contentGeneration",r.requestUrl!!.queryParameter("contentGeneration"))
                    fun body()=SyncV2Wire.parse(r.body.readByteArray())
                    when {
                        route=="capabilities"->return response(dto(s).put("minVersion",2).put("maxVersion",2).put("maxPartBytes",65536).put("historyDays",7).put("features",JSONArray())
                            .put("receiptKeyId",announcement.get("receiptKeyId")).put("receiptSigningPublicKey",announcement.get("receiptSigningPublicKey")).put("flags",JSONObject().put("read",true).put("indexWrite",true).put("contentWrite",true)))
                        route=="repo/descriptor"->{if(descriptorUnavailable)return error("temporarily_unavailable");val d=descriptor?:return error("object_missing");return response(dto(scope()).put("descriptor",d).put("descriptorOid",SyncV2Codec.oid("repo-descriptor",d)).put("recoveryOid",SyncV2Codec.oid("id-key-recovery",capsule)))}
                        route.startsWith("repo/id-key-recovery/")->return response(capsule!!)
                        route=="repo/init"->{
                            inits++;val b=body();initBytes.add(SyncV2Codec.canonical(b));concurrentWinner?.let{descriptor=it.descriptor;capsule=it.recoveryCapsule;concurrentWinner=null}
                            if(descriptor!=null && SyncV2Codec.canonical(descriptor)!=SyncV2Codec.canonical(b.getJSONObject("descriptor")))run{if(failAfterConflict)descriptorUnavailable=true;return error("ref_conflict")}
                            descriptor=b.getJSONObject("descriptor");capsule=b.getJSONObject("recoveryCapsule")
                            if(dropInit){dropInit=false;return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)}
                            return response(dto(s).put("descriptorOid",SyncV2Codec.oid("repo-descriptor",descriptor)))
                        }
                        route=="checkpoint"->{val w=window(s).apply{remove("version")}.put("checkpointId","33".repeat(16));return response(receipt.signed("wrss.sync.v2/checkpoint-sign",w).put("version",2).put("scope",s))}
                        route=="negotiate" || route=="account"->return response(window(s))
                        route=="devices/signing-key"->{
                            val b=body();val v=b.getJSONObject("body");val id=v.getString("deviceId")
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
    private inner class Host(val server:Server,val id:String,localSwitch:Boolean=true):AutoCloseable {
        val root=File((RuntimeEnvironment.getApplication() as Context).cacheDir,"p6-host-${System.nanoTime()}").also{it.mkdirs()}
        val context=object:ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext():Context=this
            override fun getFilesDir()=File(root,"files").also{it.mkdirs()}
            override fun getNoBackupFilesDir()=File(root,"no-backup").also{it.mkdirs()}
        }
        val room=Room.databaseBuilder(context,PhoneCompanionDatabase::class.java,File(root,"phone.db").absolutePath).allowMainThreadQueries().build()
        val prefs=context.getSharedPreferences("host-$id-${System.nanoTime()}",Context.MODE_PRIVATE)
        val manager=CloudKeyManager(null,preferencesOverride=prefs).also{it.bindBackend(server.http.url("/").toString());it.saveAccountKey(user,1,server.wrap,true)}
        var account:SyncV2PhoneProduct.Account?=SyncV2PhoneProduct.Account(user,server.http.url("/").toString(),id,"old-epoch","old-generation","synthetic-account","synthetic-api","Bearer synthetic-device")
        var enabled=localSwitch
        val product=SyncV2PhoneProduct(context,{room},manager,{account},{_,values->assertTrue(java.security.MessageDigest.isEqual(server.wrap,values.getValue(1)))},{true},{enabled},signals={SyncV2TransportSignals(wifi=true,internet=true)})
        fun note(text:String="local"){val v=SyncV2P5Fixture().use{it.note(markdown=text)};SyncV2PhoneApply(room.openHelper.writableDatabase).upsert("notes",listOf("noteId"),v)}
        fun text()=room.openHelper.writableDatabase.query("SELECT markdown FROM notes WHERE noteId='note'").use{if(it.moveToFirst())it.getString(0)else null}
        fun sync()=runBlocking{product.sync(server.announcement)}
        override fun close(){product.release();room.close();root.deleteRecursively()}
    }
    @Test fun disabledAndUnannouncedEntryHasZeroRequestsNoRuntimeAndNoDatabaseOpen()=runBlocking {
        Server().use {server->Host(server,"a",false).use{h->
            val before=server.http.requestCount
            val beforeStorage=SyncV2Codec.hex(SyncV2Codec.sha(h.prefs.all.toString().toByteArray()))
            assertFalse(h.product.sync(server.announcement).enabled);assertFalse(com.lightningstudio.watchrss.phone.BuildConfig.SYNC_V2)
            assertNull(h.product.uiPort.value);h.enabled=true
            assertEquals("not_announced",h.product.sync(null).reason);assertEquals(before,server.http.requestCount)
            assertFalse(File(h.root,"phone.db").exists())
            assertEquals(beforeStorage,SyncV2Codec.hex(SyncV2Codec.sha(h.prefs.all.toString().toByteArray())))
        }}
    }
    @Test fun realCreateScanPublishRoomApplyReceiverAndSubsequentSchedule() {
        Server().use {server->Host(server,"a").use{a->
            a.note();assertTrue(a.sync().enabled);assertEquals(1,server.publishes);assertEquals("local",server.values().single().getString("markdown"));assertTrue(server.acks>0)
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
            a.note();a.sync();server.capsule=JSONObject(server.capsule!!.toString()).put("ciphertext","AAAA")
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

    @Test fun reviewClosedUiGatesRejectRecoveryWithoutWritesOrRequests() {
        Server().use {server->Host(server,"a").use {h->
            h.note();h.sync()
            val port=h.product.uiPort.value!!
            val runtime=SyncV2PhoneProduct::class.java.getDeclaredField("runtime").also{it.isAccessible=true}.get(h.product) as SyncV2PhoneProduct.Runtime
            val revision=runtime.projection.state().revision
            val requests=server.http.requestCount
            assertEquals("feature_disabled",runCatching{runBlocking{port.restoreRecovery("aa".repeat(16))}}.exceptionOrNull()?.message)
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
}
