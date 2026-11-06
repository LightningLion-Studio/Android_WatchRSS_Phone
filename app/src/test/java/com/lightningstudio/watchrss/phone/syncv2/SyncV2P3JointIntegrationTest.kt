package com.lightningstudio.watchrss.phone.syncv2

import android.content.Context
import androidx.room.Room
import com.lightningstudio.watchrss.phone.cloud.CloudProtocolV2
import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
import com.lightningstudio.watchrss.phone.data.db.PhoneCompanionDatabase
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.io.FileOutputStream
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Explicit opt-in: the unchanged phone fixture, real loopback HTTP and native SQLite.
 * Never rewrites another device's row or reads another device's private fixture. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SyncV2P3JointIntegrationTest {
    private var evidence=File("/home/wilf/data/OPPO_Develop/.codex-tmp/cloud-v2/evidence/T-SYNCGIT-P3A/phone/joint")
    private val result=JSONObject().put("platform","JVM/native SQLite + real isolated P3A backend").put("productionAccess",false)
    private var phase="initial"
    private val started=System.nanoTime()
    private fun need(value:Boolean,code:String){if(!value)SyncV2Codec.fail(code)}
    private fun id()=SyncV2Codec.hex(SyncV2Codec.random(16))
    private fun save(){evidence.mkdirs();File(evidence,"summary.json").writeText(result.toString(2)+"\n")}
    private fun mark(name:String){phase=name;result.put("phase",name).put("elapsedMillis",(System.nanoTime()-started)/1000000);save()}
    private fun record(name:String,value:Any){result.put(name,value);save()}
    private fun http()=OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).callTimeout(15,TimeUnit.SECONDS).build()
    private fun bootstrap(f:JSONObject):JSONObject {
        need(f.getString("baseUrl")=="http://127.0.0.1:18942","isolated_backend_required")
        val r=Request.Builder().url(f.getString("baseUrl")+"/functions/v1/cloud/bootstrap").post("{}".toRequestBody("application/json".toMediaType()))
        val headers=f.getJSONObject("headers");for(k in headers.keys())r.header(k,headers.getString(k))
        return http().newCall(r.build()).execute().use{need(it.code==200,"bootstrap_http_${it.code}");SyncV2Wire.parse(it.body!!.bytes())}
    }
    private class Relay(private val readOnly:Boolean=false):AutoCloseable {
        val server=MockWebServer();val requests=AtomicInteger();val drops=AtomicInteger()
        val blockedMutations=AtomicInteger()
        val partOrder=java.util.Collections.synchronizedList(mutableListOf<Pair<String,Int>>())
        val partBytes=ConcurrentHashMap<String,AtomicInteger>();val routeCounts=ConcurrentHashMap<String,AtomicInteger>()
        val partCalls=ConcurrentHashMap<String,AtomicInteger>();val responseCodes=ConcurrentHashMap<String,Int>()
        val rangeOrder=java.util.Collections.synchronizedList(mutableListOf<Triple<String,Int,Int>>())
        val completeCalls=AtomicInteger();val held=CountDownLatch(1);val release=CountDownLatch(1)
        val holdOnce=AtomicBoolean(false);val midOnce=AtomicBoolean(false);val lastOnce=AtomicBoolean(false)
        var holdOid:String?=null;var midOid:String?=null;var finalOid:String?=null;var finalOffset:Int=0
        var beforePublish:(()->Unit)?=null;private val publishOnce=AtomicBoolean(false)
        private val network=OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).callTimeout(15,TimeUnit.SECONDS).build()
        val base:HttpUrl get()=server.url("/functions/v1/sync/v2/")
        init {
            server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
                try {
                    val url=request.requestUrl!!;val path=url.encodedPath
                    if(!path.startsWith("/functions/v1/sync/v2/"))return MockResponse().setResponseCode(403)
                    requests.incrementAndGet()
                    val tail=path.substringAfter("/sync/v2/");val route=when {
                        "/parts/" in tail->"objects/parts"
                        tail.endsWith("/complete")->"objects/complete"
                        tail.startsWith("objects/")->"objects/get"
                        tail.startsWith("recipe-refs/")->"recipe-refs/get"
                        tail.startsWith("state-refs/")->"state-refs/get"
                        else->tail
                    }
                    if(readOnly && request.method!="GET" && !(request.method=="POST" && tail in setOf("negotiate","objects/have-want","objects/batch-get","availability/check"))) {
                        blockedMutations.incrementAndGet();return MockResponse().setResponseCode(403)
                    }
                    routeCounts.computeIfAbsent(route){AtomicInteger()}.incrementAndGet()
                    if(tail=="account/publish" && publishOnce.compareAndSet(false,true))beforePublish?.invoke()
                    val body=if(request.method in setOf("POST","PUT"))request.body.readByteArray()else null
                    val forward=Request.Builder().url("http://127.0.0.1:18942"+path+(url.encodedQuery?.let{"?$it"}?:""))
                    for(k in request.headers.names())if(k.lowercase() !in setOf("host","connection","content-length"))forward.header(k,request.getHeader(k)!!)
                    forward.method(request.method!!,body?.toRequestBody(request.getHeader("Content-Type")?.toMediaType()))
                    val (code,headers,bytes)=network.newCall(forward.build()).execute().use { r ->Triple(r.code,r.headers,r.body?.bytes()?:ByteArray(0)) }
                    val range=request.getHeader("Range")
                    if(code==206 && range!=null && tail.startsWith("objects/")) {
                        val ends=range.removePrefix("bytes=").split('-').map{it.toInt()}
                        rangeOrder.add(Triple(tail.substringAfter("objects/"),ends[0],ends[1]))
                    }
                    var drop=false
                    if("/parts/" in tail) {
                        val oid=tail.substringAfter("objects/").substringBefore("/parts/");val at=tail.substringAfter("/parts/").toInt()
                        partOrder.add(oid to at);partBytes.computeIfAbsent(oid){AtomicInteger()}.addAndGet(body!!.size)
                        partCalls.computeIfAbsent("$oid/$at"){AtomicInteger()}.incrementAndGet();responseCodes["$oid/$at"]=code
                        if(code==200 && oid==holdOid && at==0 && holdOnce.compareAndSet(false,true)) {held.countDown();release.await(20,TimeUnit.SECONDS)}
                        if(code==200 && oid==midOid && at==65536 && midOnce.compareAndSet(false,true))drop=true
                        if(code==200 && oid==finalOid && at==finalOffset && lastOnce.compareAndSet(false,true))drop=true
                    }
                    if(tail.endsWith("/complete"))completeCalls.incrementAndGet()
                    if(drop){drops.incrementAndGet();return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)}
                    val reply=MockResponse().setResponseCode(code)
                    for(k in headers.names())if(k.lowercase() !in setOf("connection","transfer-encoding","content-length"))reply.setHeader(k,headers[k]!!)
                    return reply.setBody(Buffer().write(bytes))
                }catch(_:Throwable){return MockResponse().setResponseCode(503).setBody("{\"error\":{\"code\":\"temporarily_unavailable\",\"retryable\":true,\"retryAfterMillis\":1000,\"details\":{}}}")}
            }}
            server.start()
        }
        override fun close(){release.countDown();server.shutdown();network.connectionPool.evictAll();network.dispatcher.executorService.shutdown()}
    }
    private fun uploadControl(client:SyncV2CloudClient,oid:String,bytes:ByteArray) {
        val reservation=client.reserve(id(),listOf(oid to bytes.size)).getString("reservationId");var at=0
        while(at<bytes.size) {
            val part=bytes.copyOfRange(at,minOf(at+65536,bytes.size));val response=client.put(oid,at,reservation,id(),part)
            client.verifyReceipt(response.getJSONObject("receipt"),oid,bytes.size.toLong(),(at+part.size).toLong(),System.currentTimeMillis());at+=part.size
        }
        client.verifyReceipt(client.complete(oid,reservation,id(),bytes.size).getJSONObject("receipt"),oid,bytes.size.toLong(),bytes.size.toLong(),System.currentTimeMillis())
    }
    /** Uses the current tree without changing any row, to cause one real optimistic CAS race. */
    private fun publishNoop(client:SyncV2CloudClient,projection:SyncV2Projection,signer:SyncV2Signing,authors:SyncV2Authors) {
        repeat(20) {
            val window=client.negotiate();val account=window.getJSONObject("account");val refs=window.getJSONArray("refs")
            val own=(0 until refs.length()).map{refs.getJSONObject(it)}.singleOrNull{it.getString("deviceId")==signer.deviceId}
            val previous=own?.getString("commitOid");val floor=previous?.let{authors.header(SyncV2Wire.parse(client.raw(it),262144,true)).getString("deviceSequence")}?:"0"
            val accountOid=if(account.isNull("commitOid"))null else account.getString("commitOid")
            val tree=accountOid?.let{authors.header(SyncV2Wire.parse(client.raw(it),262144,true)).getString("treeRoot")}?:SyncV2Index.build(client.scope,emptyList()).also{t->t.objects.forEach{(o,b)->uploadControl(client,o,b)}}.root
            val body=JSONObject().put("version",2).put("scope",client.scope).put("authorDeviceId",signer.deviceId).put("deviceSequence",projection.reserveDeviceSequence(floor))
                .put("previousOwnCommit",previous?:JSONObject.NULL).put("mergedParents",JSONArray()).put("treeRoot",tree).put("requiredFeatures",JSONArray()).put("createdAtMillis",System.currentTimeMillis())
                .put("observedRefs",JSONArray()).put("indexComplete",true).put("accountParent",accountOid?:JSONObject.NULL).put("rollbackGeneration",account.getString("rollbackGeneration"))
                .put("operation",JSONObject().put("kind","normal").put("rollbackTarget",JSONObject.NULL).put("resolvesGeneration",JSONObject.NULL))
            val signed=signer.commit(body);val oid=SyncV2Codec.oid("commit",body);uploadControl(client,oid,SyncV2Codec.utf8(SyncV2Codec.canonical(signed)))
            val request=id();val now=System.currentTimeMillis()
            val update=JSONObject().put("account",SyncV2CloudClient.refUpdate(signer,client.scope,"refs/account/current",accountOid,account.getString("revision"),oid,request,now))
                .put("device",SyncV2CloudClient.refUpdate(signer,client.scope,"refs/devices/${signer.deviceId}/index",previous,own?.getString("refRevision")?:"0",oid,request,now)).put("relayGrant",JSONObject.NULL)
            try{client.publish(update);return}catch(e:SyncV2CloudError){if(e.code !in setOf("ref_conflict","cloud_account_busy","library_busy"))throw e}
        }
        SyncV2Codec.fail("noop_cas_retry_exhausted")
    }
    private fun reserveSequence(projection:SyncV2Projection,signer:SyncV2Signing,client:SyncV2CloudClient,authors:SyncV2Authors) {
        val file=File(evidence,"phone-sequence-watermark.json")
        val previous=if(file.exists())SyncV2Wire.parse(file.readBytes()).also{need(it.getString("deviceId")==signer.deviceId,"fixture_sequence_owner")}.getString("reservedThrough")else "0"
        val refs=client.negotiate().getJSONArray("refs");val own=(0 until refs.length()).map{refs.getJSONObject(it)}.singleOrNull{it.getString("deviceId")==signer.deviceId}
        val floor=own?.let{authors.header(SyncV2Wire.parse(client.raw(it.getString("commitOid")),262144,true)).getString("deviceSequence")}?:"0"
        val base=maxOf(SyncV2Codec.d64(previous),SyncV2Codec.d64(floor),BigInteger.valueOf(System.currentTimeMillis()))
        val value=JSONObject().put("deviceId",signer.deviceId).put("reservedThrough",(base+BigInteger.valueOf(4096)).toString())
        val tmp=File(evidence,"phone-sequence-watermark.next");FileOutputStream(tmp).use{it.write(SyncV2Codec.utf8(SyncV2Codec.canonical(value)));it.fd.sync()}
        Files.move(tmp.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);projection.reserveDeviceSequence(base.toString())
    }
    /** Follow-up is read-only at the account/object layer: consume every foreign row, not
     * one sample per author. The proxy prevents accidentally rerunning publication work. */
    @Test fun phoneP3ForeignAllPublishedBodiesRangeResumeAndBl03() {
        need(System.getenv("WATCHRSS_P3_PHONE_JOINT")=="1","joint_not_enabled")
        evidence=File(evidence.parentFile,"foreign-all");evidence.mkdirs()
        val fixture=File("/home/wilf/data/OPPO_Develop/.codex-tmp/cloud-v2/integration/phone.json")
        val fixtureHash=SyncV2Codec.hex(SyncV2Codec.sha(fixture.readBytes()));val f=SyncV2Wire.parse(fixture.readBytes())
        need(f.getBoolean("syntheticOnly") && f.getString("platform")=="phone" && f.getString("baseUrl")=="http://127.0.0.1:18942","fixture_scope_invalid")
        val local=File((RuntimeEnvironment.getApplication() as Context).cacheDir,"p3-foreign-${System.nanoTime()}").also{it.mkdirs()}
        val wrap=SyncV2Codec.unhex(f.getJSONObject("crypto").getString("contentKeyHex"),32);val localKey=SyncV2Codec.random(32)
        var db:PhoneCompanionDatabase?=null;var relay:Relay?=null
        try {
            mark("foreign-bootstrap-c3-c4")
            val boot=bootstrap(f);val approved=boot.getJSONArray("devices").let{a->(0 until a.length()).map{a.getJSONObject(it)}.filter{d->
                d.isNull("revokedAt") && d.optInt("attestationKeyVersion")==1 && d.optString("attestation")==CloudProtocolV2.attestation(wrap,f.getString("userId"),f.getString("legacyLibraryEpoch"),d.getString("deviceId"),Base64.getDecoder().decode(d.getString("publicKeySpki")))
            }}
            val approvedLegacy=approved.map{it.getString("deviceId")}.toSet();need(approvedLegacy.size==4,"approved_roster_invalid")
            val profile=approved.associate{it.getString("deviceId") to it.optString("platform","unknown")}
            val pin=SyncV2Wire.parse(File("/home/wilf/data/OPPO_Develop/.codex-tmp/cloud-v2/integration/bootstrap-public.json").readBytes()).getJSONObject("syncV2")
            val trust=SyncV2ServiceTrust.fromBootstrap(f.getString("accountId"),boot.getJSONObject("syncV2"),pin){SyncV2Codec.fail("pin_rewritten")}
            need(trust.keyId=="b73621a8ae71467db3a89da4c5ba659d","service_key_changed")
            val h=f.getJSONObject("headers");val session=SyncV2CloudClient.Session(f.getString("userId"),f.getString("legacyDeviceId"),f.getString("legacyLibraryEpoch"),f.getString("legacyContentGeneration"),SyncV2Codec.scope(f.getJSONObject("scope")),f.getString("syncDeviceId"),h.getString("Authorization").removePrefix("Bearer "),"",h.getString("x-watchrss-device-authorization"))
            relay=Relay(readOnly=true);val proxy=relay
            fun client()=SyncV2CloudClient(proxy.base,{session},trust,true,http())
            val control=client();control.capabilities()
            val descriptor=control.descriptor();val recovery=SyncV2Wire.parse(control.recovery(descriptor.getString("recoveryOid")),8192,true)
            need(SyncV2Codec.oid("id-key-recovery",recovery)==descriptor.getString("recoveryOid"),"hash_mismatch")
            val identity=SyncV2Identity.recover(recovery,descriptor.getJSONObject("descriptor"),wrap,f.getString("accountId"),f.getJSONObject("scope"),descriptor.getString("descriptorOid"))
            val window=control.negotiate();val registrations=control.signingKeys(window.getString("windowId"));val bindings=registrations.getJSONArray("bindings")
            val authors=control.authors(identity){it in approvedLegacy};val platforms=mutableMapOf<String,String>()
            for(i in 0 until bindings.length()) {
                val binding=bindings.getJSONObject(i);authors.accept(binding,registrations.getString("revocationRevision"))
                val m=binding.getJSONObject("deviceMapping").getJSONObject("body");platforms[m.getString("deviceId")]=profile[m.getString("legacyDeviceId")]?:"unknown"
            }
            val approvedSync=platforms.keys.toSet();need(approvedSync.size==4,"binding_roster_invalid")
            val signing=f.getJSONObject("signingKey");val signer=SyncV2Signing.fromPrivate(SyncV2Codec.unhex(signing.getString("privateKeyHex"),32),session.deviceId,signing.getString("keyId"))
            need(signer.publicHex==authors.publicKey(signer.deviceId,signer.keyId),"identity_conflict")
            db=Room.databaseBuilder(RuntimeEnvironment.getApplication(),PhoneCompanionDatabase::class.java,File(local,"phone.db").absolutePath).allowMainThreadQueries().build()
            val projection=SyncV2Projection(db.openHelper.writableDatabase,identity,localKey,true).also{it.install()}
            val sync=SyncV2CloudSync(control,identity,projection,{it in approvedLegacy},{v->need(v==1L,"unsupported_feature");wrap})
            need(sync.readCurrent()=="metadata-ready","current_index_not_ready")
            val account=JSONObject(projection.state().body.getJSONObject("account").toString())
            val allRows=projection.cloudRows().map{it.getJSONObject("row")};val rows=mutableListOf<JSONObject>();val inventory=JSONArray()
            val authorByRecipe=mutableMapOf<String,String>();val w=control.negotiate().getString("windowId")
            for(row in allRows.sortedBy{it.getString("entityKey")}) {
                if(row.getBoolean("deleted") || row.isNull("recipeOid"))continue
                val oid=row.getString("recipeOid");val mapping=control.reference(oid,w).apply{remove("version")}
                val raw=control.raw(mapping.getString("refsOid"),w);val refs=SyncV2Wire.parse(raw,262144,true)
                SyncV2ContentCodec.verifyRefs(identity,authors,oid,refs.getJSONObject("body").getJSONArray("blobOids"),mapping,raw)
                val author=mapping.getString("authorDeviceId");need(author in approvedSync,"unapproved_content_author")
                if(author==signer.deviceId)continue
                authorByRecipe[oid]=author;rows.add(row)
                inventory.put(JSONObject().put("authorDeviceId",author).put("entityKey",row.getString("entityKey")).put("bodyRevision",row.getString("bodyRevision")).put("recipeOid",oid))
            }
            need(authorByRecipe.values.toSet()==approvedSync-signer.deviceId,"three_foreign_content_authors_not_ready")
            record("accountSnapshot",account);record("currentRows",allRows.size);record("foreignInventory",inventory)
            record("foreignAuthors",authorByRecipe.values.toSet().size);record("foreignPublishedBodies",rows.size)
            mark("foreign-all-range-reopen")
            val dir=File(local,"content");val counts=SyncV2ContentStore.Counts();val proofs=JSONArray();var paused=0;var resumedInsideObject=0
            fun store()=SyncV2ContentStore(dir,identity.scopeJson,localKey,counts)
            fun download(row:JSONObject,budget:Long):SyncV2ContentSession.DownloadOutcome {
                val destination=store();val dc=client()
                val worker=SyncV2ContentWorker(dc,identity,destination,{SyncV2TransportSignals(wifi=true,internet=true,cellular=false)},enabled=true,approvedDevice={it in approvedSync})
                val receiver=SyncV2ContentReceive(identity,authors,destination,worker,dc,{v->need(v==1L,"unsupported_feature");wrap})
                val content=SyncV2ContentSession(identity,destination,worker,dc,signer,SyncV2ContentExport(identity,wrap,1,destination))
                var bound=0
                val outcome=content.downloadPage(listOf(row),receiver,System::currentTimeMillis,{entity->allRows.singleOrNull{it.getString("entityKey")==entity}},{bound++},SyncV2ContentWorker.Budget(bytes=budget))
                if(outcome.status=="complete")need(bound==1 && outcome.completeBodies==1,"foreign_body_not_bound")
                return outcome
            }
            for((index,row)in rows.withIndex()) {
                val windows=JSONArray();var complete=false
                repeat(4096){n->if(!complete) {
                    val rangeAt=proxy.rangeOrder.size;val previous=proxy.rangeOrder.lastOrNull()
                    val r=download(row,196608);need(r.status in setOf("budget_paused","complete"),"foreign_resume_status_${r.status}")
                    if(r.status=="budget_paused"){need(r.transferredBytes>0,"foreign_window_no_progress");paused++}
                    val windowProof=JSONObject().put("window",n+1).put("status",r.status).put("bytes",r.transferredBytes)
                    if(proxy.rangeOrder.size>rangeAt) {
                        val first=proxy.rangeOrder[rangeAt];val last=proxy.rangeOrder.last()
                        windowProof.put("firstRangeOid",first.first).put("firstRangeOffset",first.second).put("lastRangeOid",last.first).put("lastRangeEnd",last.third)
                        if(n>0 && previous!=null && first.first==previous.first && first.second==previous.third+1)resumedInsideObject++
                        if(r.status=="budget_paused")need(store().load("prefix/${last.first}")!!.getInt("offset")==last.third+1,"range_checkpoint_not_durable")
                    }
                    windows.put(windowProof);complete=r.status=="complete"
                }}
                need(complete,"foreign_resume_incomplete")
                val destination=store();val recipe=destination.load("recipe/${row.getString("entityKey")}/${row.getString("bodyRevision")}")!!.getJSONObject("recipe")
                SyncV2ContentCodec.matchRow(recipe,row)
                SyncV2ContentCodec.verifyBody(identity,recipe,{b->destination.plain(b.getString("oid"))})
                val blockProofs=JSONArray();val blocks=recipe.getJSONArray("blocks")
                for(i in 0 until blocks.length()) {
                    val block=blocks.getJSONObject(i);val oid=block.getString("oid");val sealed=destination.objectBytes(oid);val plain=destination.plain(oid)
                    need(oid=="blob:sha256:${SyncV2Codec.hex(SyncV2Codec.sha(sealed))}","hash_mismatch")
                    blockProofs.put(JSONObject().put("index",i).put("oid",oid).put("plainBytes",plain.size).put("plaintextSha256",SyncV2Codec.hex(SyncV2Codec.sha(plain))).put("opaqueContentIdVerified",identity.opaque("blob",plain)==block.getString("opaqueContentId")))
                }
                val oid=row.getString("recipeOid");need(oid=="recipe:sha256:${SyncV2Codec.hex(SyncV2Codec.sha(destination.objectBytes(oid)))}","hash_mismatch")
                val author=authorByRecipe.getValue(oid)
                proofs.put(JSONObject().put("authorDeviceId",author).put("platform",platforms[author]).put("entityKey",row.getString("entityKey")).put("bodyRevision",row.getString("bodyRevision"))
                    .put("recipeOid",oid).put("plainBytes",recipe.getLong("totalPlainBytes")).put("plaintextSha256",recipe.getString("plaintextSha256")).put("blocks",blockProofs).put("windows",windows)
                    .put("recipeFieldsVerified",true).put("recipeRefsSignatureVerified",true).put("availabilitySignatureVerified",true).put("allBlocksAndWholeBodyVerified",true))
                record("foreignBodies",proofs);record("completedBodies",index+1)
            }
            need(paused>0 && resumedInsideObject>0,"range_resume_not_exercised")
            val ranges=proxy.rangeOrder.groupBy{it.first}
            need(ranges.values.all{r->r.first().second==0 && r.zipWithNext().all{(a,b)->b.second==a.third+1}},"range_confirmed_bytes_replayed")
            record("rangeRequests",proxy.rangeOrder.size);record("rangeObjects",ranges.size);record("rangeReplayBytes",0);record("pausedWindows",paused)
            record("resumedInsideObject",resumedInsideObject)
            record("rangeAudit",JSONArray(ranges.map{(oid,r)->JSONObject().put("oid",oid).put("requests",r.size).put("firstOffset",r.first().second).put("endExclusive",r.last().third+1).put("replayBytes",0)}))
            record("firstPassCounts",counts.json())
            mark("foreign-bl03-reopen-all")
            val before=counts.json();val rangesBefore=proxy.rangeOrder.size
            for(row in rows)need(download(row,32L*1024*1024).transferredBytes==0L,"bl03_cached_body_transferred")
            val after=counts.json();val delta=JSONObject()
            for(k in listOf("sourceReadBytes","exports","seals","authentications","decompressBytes","hashBytes","networkBytes","fileReadBytes"))delta.put(k,after.getLong(k)-before.getLong(k))
            need(listOf("sourceReadBytes","exports","seals","authentications","decompressBytes","hashBytes","networkBytes").all{delta.getLong(it)==0L},"bl03_repeated_body_work")
            need(proxy.rangeOrder.size==rangesBefore,"bl03_repeated_range")
            record("bl03Delta",delta);record("bl03ReopenedBodies",rows.size)
            mark("foreign-final-current-tree-audit")
            need(sync.readCurrent()=="metadata-ready","current_index_not_ready")
            need(SyncV2Codec.canonical(account)==SyncV2Codec.canonical(projection.state().body.getJSONObject("account")),"account_changed_during_foreign_download")
            need(allRows.associate{it.getString("entityKey") to SyncV2Codec.canonical(it)}==projection.cloudRows().associate{it.getJSONObject("row").getString("entityKey") to SyncV2Codec.canonical(it.getJSONObject("row"))},"current_rows_changed")
            need(proxy.blockedMutations.get()==0 && proxy.partOrder.isEmpty() && proxy.completeCalls.get()==0,"foreign_test_attempted_mutation")
            need(fixtureHash==SyncV2Codec.hex(SyncV2Codec.sha(fixture.readBytes())),"fixture_changed")
            record("currentTreeUnchanged",true);record("fixtureUnchanged",true);record("publicationRequests",0);record("businessAppliedAck",false)
            record("proxyRoutes",JSONObject(proxy.routeCounts.mapValues{it.value.get()}));record("status","passed")
        }catch(t:Throwable) {
            val raw=t.message.orEmpty();val safe=if(Regex("[a-z][a-z0-9_]{0,100}").matches(raw))raw else t.javaClass.simpleName
            result.put("status","failed").put("errorPhase",phase).put("errorCode",safe).put("errorFrames",JSONArray(t.stackTrace.filter{it.className.startsWith("com.lightningstudio.watchrss.phone.syncv2")}.take(10).map{"${it.className.substringAfterLast('.')}::${it.methodName}:${it.lineNumber}"}));save()
            throw AssertionError("p3 foreign phase=$phase code=$safe")
        }finally {db?.close();relay?.close();wrap.fill(0);localKey.fill(0);local.deleteRecursively()}
    }
    @Test fun phoneP3RealBackendContentCasAvailabilityForeignDownloadResumeDemandAndCache() {
        need(System.getenv("WATCHRSS_P3_PHONE_JOINT")=="1","joint_not_enabled");evidence.mkdirs()
        val fixture=File("/home/wilf/data/OPPO_Develop/.codex-tmp/cloud-v2/integration/phone.json")
        val fixtureHash=SyncV2Codec.hex(SyncV2Codec.sha(fixture.readBytes()));val f=SyncV2Wire.parse(fixture.readBytes())
        need(f.getBoolean("syntheticOnly") && f.getString("platform")=="phone" && f.getString("baseUrl")=="http://127.0.0.1:18942","fixture_scope_invalid")
        val local=File((RuntimeEnvironment.getApplication() as Context).cacheDir,"p3-joint-${System.nanoTime()}").also{it.mkdirs()}
        val wrap=SyncV2Codec.unhex(f.getJSONObject("crypto").getString("contentKeyHex"),32)
        var db:PhoneCompanionDatabase?=null;var relay:Relay?=null
        try {
            mark("bootstrap-c3-c4")
            val boot=bootstrap(f);val approved=boot.getJSONArray("devices").let{a->(0 until a.length()).map{a.getJSONObject(it)}.filter{d->
                d.isNull("revokedAt") && d.optInt("attestationKeyVersion")==1 && d.optString("attestation")==CloudProtocolV2.attestation(wrap,f.getString("userId"),f.getString("legacyLibraryEpoch"),d.getString("deviceId"),Base64.getDecoder().decode(d.getString("publicKeySpki")))
            }}
            val approvedLegacy=approved.map{it.getString("deviceId")}.toSet();need(approvedLegacy.size==4,"approved_roster_invalid")
            val profile=approved.associate{it.getString("deviceId") to it.optString("platform","unknown")}
            val pin=SyncV2Wire.parse(File("/home/wilf/data/OPPO_Develop/.codex-tmp/cloud-v2/integration/bootstrap-public.json").readBytes()).getJSONObject("syncV2")
            val trust=SyncV2ServiceTrust.fromBootstrap(f.getString("accountId"),boot.getJSONObject("syncV2"),pin){SyncV2Codec.fail("pin_rewritten")}
            need(trust.keyId=="b73621a8ae71467db3a89da4c5ba659d","service_key_changed")
            val h=f.getJSONObject("headers");val session=SyncV2CloudClient.Session(f.getString("userId"),f.getString("legacyDeviceId"),f.getString("legacyLibraryEpoch"),f.getString("legacyContentGeneration"),SyncV2Codec.scope(f.getJSONObject("scope")),f.getString("syncDeviceId"),h.getString("Authorization").removePrefix("Bearer "),"",h.getString("x-watchrss-device-authorization"))
            val direct=(f.getString("syncBaseUrl")+"/").toHttpUrl()
            fun client(base:HttpUrl=direct)=SyncV2CloudClient(base,{session},trust,true,http())
            val control=client();val caps=control.capabilities();need(caps.getJSONObject("flags").getBoolean("contentWrite"),"content_gate_closed")
            val descriptor=control.descriptor();val recovery=SyncV2Wire.parse(control.recovery(descriptor.getString("recoveryOid")),8192,true)
            need(SyncV2Codec.oid("id-key-recovery",recovery)==descriptor.getString("recoveryOid"),"hash_mismatch")
            val identity=SyncV2Identity.recover(recovery,descriptor.getJSONObject("descriptor"),wrap,f.getString("accountId"),f.getJSONObject("scope"),descriptor.getString("descriptorOid"))
            val window=control.negotiate();val registrations=control.signingKeys(window.getString("windowId"));val bindings=registrations.getJSONArray("bindings")
            val authors=control.authors(identity){it in approvedLegacy};val devicePlatforms=mutableMapOf<String,String>()
            for(i in 0 until bindings.length()) {val binding=bindings.getJSONObject(i);authors.accept(binding,registrations.getString("revocationRevision"));val m=binding.getJSONObject("deviceMapping").getJSONObject("body");devicePlatforms[m.getString("deviceId")]=profile[m.getString("legacyDeviceId")]?:"unknown"}
            val signing=f.getJSONObject("signingKey");val signer=SyncV2Signing.fromPrivate(SyncV2Codec.unhex(signing.getString("privateKeyHex"),32),session.deviceId,signing.getString("keyId"))
            need(signer.publicHex==authors.publicKey(signer.deviceId,signer.keyId),"identity_conflict")
            val approvedSync=devicePlatforms.keys.toSet();need(approvedSync.size==4,"binding_roster_invalid")
            record("c3C4Authenticated",true);record("backendContentWrite",true)
            db=Room.databaseBuilder(RuntimeEnvironment.getApplication(),PhoneCompanionDatabase::class.java,File(local,"phone.db").absolutePath).allowMainThreadQueries().build()
            val database=requireNotNull(db)
            val localKey=SyncV2Codec.random(32);val projection=SyncV2Projection(database.openHelper.writableDatabase,identity,localKey,true).also{it.install()}
            reserveSequence(projection,signer,control,authors)
            relay=Relay();val proxy=relay;val publishing=client(proxy.base)
            val sync=SyncV2CloudSync(publishing,identity,projection,{it in approvedLegacy},{v->need(v==1L,"unsupported_feature");wrap})
            sync.readCurrent()
            mark("prepare-originals-and-index")
            val run="phone-p3-${System.nanoTime()}";val ids=listOf("$run-a","$run-b").sortedBy{identity.entity("article",it)};val instant=System.currentTimeMillis()
            val files=linkedMapOf<String,File>();val sources=linkedMapOf<String,SyncV2ContentExport.Source>()
            for((i,logical) in ids.withIndex()) {
                val size=if(i==0)70037 else 2*1024*1024+37
                val plain=buildString(size){repeat(size){append(('a'.code+(it*17+i)%26).toChar())}}
                val hash=SyncV2Codec.hex(SyncV2Codec.sha(SyncV2Codec.utf8(plain)));val original=File(local,"original-$i.txt").also{it.writeText(plain)}
                files[logical]=original;sources[logical]=SyncV2ContentSession.FileSource(original,hash)
                runBlocking{database.phoneArticleDao().upsert(PhoneArticleEntity(logical,"phone-p3-source","https://example.invalid/$logical","Phone P3 synthetic $i","synthetic","summary",null,plain,null,hash,instant,instant,false,0,0,"https://example.invalid/$run/feed","synthetic",true,instant,0,false,0,0,false,0))}
            }
            while(!projection.scanPage("phone_articles").complete){}
            projection.prepareWireIndex(signer,wrap,1)
            val contentDir=File(local,"content");val counts=SyncV2ContentStore.Counts()
            fun store()=SyncV2ContentStore(contentDir,identity.scopeJson,localKey,counts)
            var contentStore=store();var exporter=SyncV2ContentExport(identity,wrap,1,contentStore)
            val rows=ids.associateWith{projection.wireContentRow(identity.entity("article",it))!!}
            val exports=linkedMapOf<String,JSONObject>();val tasks=mutableListOf<SyncV2ContentWorker.Upload>()
            for(logical in ids) {
                val row=rows.getValue(logical);val binding=SyncV2ContentExport.Binding(row.getString("entityKey"),row.getString("bodyRevision"),exporter.authenticationBinding)
                while(!exporter.step(binding,sources.getValue(logical))){}
                val value=exporter.descriptor(binding)!!;exports[logical]=value;val blocks=value.getJSONObject("recipe").getJSONArray("blocks")
                for(i in 0 until blocks.length())tasks.add(SyncV2ContentWorker.Upload(blocks.getJSONObject(i).getString("oid"),3,row.getString("entityKey"),blockIndex=i,revision=row.getString("bodyRevision")))
                tasks.add(SyncV2ContentWorker.Upload(value.getString("recipeOid"),3,row.getString("entityKey"),blockIndex=blocks.length(),revision=row.getString("bodyRevision")))
            }
            val small=exports.getValue(ids[0]).getJSONObject("recipe").getJSONArray("blocks").getJSONObject(0).getString("oid")
            val largeBlocks=exports.getValue(ids[1]).getJSONObject("recipe").getJSONArray("blocks")
            proxy.holdOid=small;proxy.midOid=largeBlocks.getJSONObject(0).getString("oid");proxy.finalOid=largeBlocks.getJSONObject(1).getString("oid")
            proxy.finalOffset=(contentStore.objectSize(proxy.finalOid!!)-1)/65536*65536
            var eligible=false
            fun worker(s:SyncV2ContentStore=contentStore)=SyncV2ContentWorker(client(proxy.base),identity,s,{if(eligible)SyncV2TransportSignals(wifi=true,internet=true,cellular=false)else SyncV2TransportSignals()},enabled=true,approvedDevice={it in approvedSync})
            var contentWorker=worker();val callsBefore=proxy.requests.get()
            val wait=contentWorker.uploadScheduled({tasks});need(wait.status=="transport_wait" && proxy.requests.get()==callsBefore,"condition_gate_bypassed");record("conditionWaitZeroRequests",true)
            eligible=true
            mark("real-content-with-fast-control-and-demand")
            val dispatcher=SyncV2ContentDispatcher();val future=dispatcher.submit{contentWorker.uploadScheduled({tasks})}
            val quick=JSONArray()
            try {
                need(proxy.held.await(20,TimeUnit.SECONDS),"content_request_not_held")
                repeat(6) {val at=System.nanoTime();control.account();val ms=(System.nanoTime()-at)/1000000;quick.put(ms);need(ms<=1000,"control_latency_exceeded")}
                val target=rows.getValue(ids[1]);val demandAt=System.currentTimeMillis()
                val demandClient=client();demandClient.capabilities()
                val demandWorker=SyncV2ContentWorker(demandClient,identity,contentStore,{SyncV2TransportSignals()},enabled=true,approvedDevice={it in approvedSync})
                val at=System.nanoTime();demandWorker.sendDemand(target.getString("entityKey"),target.getString("bodyRevision"),id(),demandAt,demandAt+600000)
                val demandMillis=(System.nanoTime()-at)/1000000;need(demandMillis<=1000,"demand_latency_exceeded")
                val active=contentWorker.pollDemands(true);need(active.any{it.get("entityKey")==target.get("entityKey") && it.get("bodyRevision")==target.get("bodyRevision")},"demand_not_visible")
                record("controlLatenciesMillis",quick);record("demandPostGetMillis",demandMillis);record("demandAuthenticated",true)
                proxy.release.countDown()
                val first=future.get(45,TimeUnit.SECONDS);need(first.status=="temporarily_unavailable","mid_ack_loss_not_observed")
                need(proxy.partOrder.size>=2 && proxy.partOrder[0].first==small && proxy.partOrder[1].first==proxy.midOid,"demand_did_not_preempt")
                record("rank0PreemptedAtFineFrame",true)
            }finally {proxy.release.countDown();dispatcher.close()}
            mark("resume-middle-and-c8-final-ack")
            val windows=JSONArray();var done=false
            repeat(12) { index ->if(!done) {
                contentStore=store();contentWorker=worker(contentStore)
                val r=contentWorker.uploadScheduled({tasks},SyncV2ContentWorker.Budget(bytes=700000))
                windows.put(JSONObject().put("window",index+1).put("status",r.status).put("durableBytes",r.durableBytes).put("completed",r.completed))
                need(r.status in setOf("budget_paused","temporarily_unavailable","complete"),"unexpected_content_status_${r.status}")
                done=r.status=="complete"
            }}
            need(done && proxy.midOnce.get() && proxy.lastOnce.get() && proxy.drops.get()==2,"resume_not_complete")
            need(proxy.partCalls.values.all{it.get()==1},"confirmed_frame_replayed")
            val allOids=tasks.map{it.oid}.distinct();val exact=control.haveWant(control.negotiate().getString("windowId"),allOids.sorted()).getJSONArray("objects")
            need(exact.length()==allOids.size && (0 until exact.length()).all{exact.getJSONObject(it).getString("status")=="present"},"content_not_present")
            record("resumeWindows",windows);record("ackLosses",proxy.drops.get());record("unconfirmedReplayBytes",0);record("confirmedReplayBytes",0)
            val final=proxy.finalOid!!;val firstReceipt=contentStore.load("receipt/$final")?:SyncV2Codec.fail("c8_receipt_missing")
            val n=contentStore.objectSize(final);val reservation=control.reserve(id(),listOf(final to n),"content").getString("reservationId")
            val revisionBefore=control.account().getString("serverRevision");val request=id()
            val completed=control.complete(final,reservation,request,n);val replay=control.complete(final,reservation,request,n)
            need(SyncV2Codec.canonical(completed)==SyncV2Codec.canonical(replay) && SyncV2Codec.canonical(firstReceipt)==SyncV2Codec.canonical(completed.getJSONObject("receipt")),"c8_receipt_or_response_changed")
            val revisionAfter=control.account().getString("serverRevision")
            record("c8ConcurrentRevisionAdvance",revisionBefore!=revisionAfter)
            // The account is shared. Another device may publish between these two observations.
            // Exact identical complete responses and receipts, not a global quiet interval, prove this retry.

            record("c8PresentThenCompleteSameReceipt",true);record("c8DuplicateCompleteSameResponse",true)
            mark("recipe-refs-and-local-cas")
            for(logical in ids) {
                val value=exports.getValue(logical);val blocks=value.getJSONObject("recipe").getJSONArray("blocks");val oids=JSONArray((0 until blocks.length()).map{blocks.getJSONObject(it).getString("oid")})
                val refs=SyncV2ContentCodec.refs(identity,signer,value.getString("recipeOid"),oids);control.recipeRefs(refs)
                projection.attachContentRecipe(rows.getValue(logical),value.getString("recipeOid"),value.getJSONObject("recipe"))
            }
            proxy.beforePublish={publishNoop(control,projection,signer,authors);record("realCasRaceInjected",true)}
            fun publishPreservingAllRows():JSONObject {
                var conflicts=0
                repeat(30) {
                    try {
                        sync.readCurrent();val base=projection.cloudRows().associate{it.getJSONObject("row").getString("entityKey") to SyncV2Codec.canonical(it.getJSONObject("row"))}
                        val response=sync.publishIndex(signer,wrap,1)
                        val commit=response.getJSONObject("account").getString("commitOid");val header=authors.header(SyncV2Wire.parse(control.raw(commit),262144,true))
                        val audit=SyncV2Index.read(identity.scope,header.getString("treeRoot")){oid->control.raw(oid)}.associateBy{it.getString("entityKey")}
                        need(base.all{(entity,row)->audit[entity]?.let(SyncV2Codec::canonical)==row},"foreign_row_changed_or_dropped")
                        record("preservedRows",base.size);record("casConflicts",result.optInt("casConflicts",0)+conflicts);return response
                    }catch(e:SyncV2CloudError) {if(e.code !in setOf("ref_conflict","cloud_account_busy","library_busy"))throw e;conflicts++;Thread.sleep(50)}
                }
                SyncV2Codec.fail("cas_retry_exhausted")
            }
            val published=publishPreservingAllRows();need(result.getInt("casConflicts")>=1,"real_cas_conflict_not_observed")
            record("publishedCommitOid",published.getJSONObject("account").getString("commitOid"));record("publishedRevision",published.getJSONObject("account").getString("revision"))
            mark("c10-current-account-availability")
            for(logical in ids) {
                val d=exports.getValue(logical);val row=rows.getValue(logical);val blocks=d.getJSONObject("recipe").getJSONArray("blocks");val blobs=JSONArray((0 until blocks.length()).map{blocks.getJSONObject(it).getString("oid")})
                val a=control.availability(row.getString("entityKey"),row.getString("bodyRevision"),d.getString("recipeOid")).getJSONObject("availability")
                need(control.verifyAvailability(a.getJSONObject("receipt"),row.getString("entityKey"),row.getString("bodyRevision"),d.getString("recipeOid"),blobs,System.currentTimeMillis())=="cloud-ready","availability_not_ready")
            }
            record("c10UploadPublishAvailability",true)
            mark("real-range-download-reopen")
            sync.readCurrent()
            val currentLarge=projection.cloudRows().single{it.getJSONObject("row").getString("entityKey")==rows.getValue(ids[1]).getString("entityKey")}.getJSONObject("row")
            val receiveDir=File(local,"own-download");val receiveCounts=SyncV2ContentStore.Counts();val downloadWindows=JSONArray();var downloaded=false
            repeat(8) { index ->if(!downloaded) {
                val destination=SyncV2ContentStore(receiveDir,identity.scopeJson,localKey,receiveCounts)
                val dc=client(proxy.base);val dw=SyncV2ContentWorker(dc,identity,destination,{SyncV2TransportSignals(wifi=true,internet=true,cellular=false)},enabled=true,approvedDevice={it in approvedSync})
                val receiver=SyncV2ContentReceive(identity,authors,destination,dw,dc,{wrap})
                val engine=SyncV2ContentSession(identity,destination,dw,dc,signer,SyncV2ContentExport(identity,wrap,1,destination));var bound=0
                val r=engine.downloadPage(listOf(currentLarge),receiver,System::currentTimeMillis,{currentLarge},{bound++},SyncV2ContentWorker.Budget(bytes=900000))
                need(r.status in setOf("budget_paused","complete"),"download_resume_status_${r.status}")
                need(r.transferredBytes>0 || r.completeBodies>0,"download_window_no_progress")
                downloadWindows.put(JSONObject().put("window",index+1).put("status",r.status).put("bytes",r.transferredBytes).put("completeBodies",r.completeBodies))
                downloaded=r.status=="complete";if(downloaded)need(bound==1,"download_not_bound")
            }}
            need(downloaded && downloadWindows.length()>=3,"download_resume_incomplete")
            val byOid=proxy.rangeOrder.groupBy{it.first}
            need(byOid.values.all{ranges->ranges.first().second==0 && ranges.zipWithNext().all{(a,b)->b.second==a.third+1}},"range_confirmed_bytes_replayed")
            record("downloadResumeWindows",downloadWindows);record("rangeReplayBytes",0);record("receiveCounts",receiveCounts.json())

            mark("small-state-publication-and-zero-reexport")
            val sql=database.openHelper.writableDatabase;val smallNote="$run-control-note"
            sql.execSQL("INSERT INTO notes VALUES(?,NULL,'Phone P3 control','small state','small state','hash','hash','small state',0,?,?,'phone-p3-source',0,0)",arrayOf<Any>(smallNote,instant,instant))
            while(!projection.scanPage("notes").complete){}
            publishPreservingAllRows()
            contentStore=store();exporter=SyncV2ContentExport(identity,wrap,1,contentStore)
            val before=JSONObject(counts.json().toString());val putBefore=proxy.partOrder.size;val completesBefore=proxy.completeCalls.get()
            for(logical in ids) {
                val row=rows.getValue(logical);val binding=SyncV2ContentExport.Binding(row.getString("entityKey"),row.getString("bodyRevision"),exporter.authenticationBinding)
                need(exporter.step(binding,sources.getValue(logical)),"confirmed_export_not_reused")
            }
            val cached=worker(contentStore).uploadScheduled({tasks});need(cached.status=="complete","cache_round_incomplete")
            val after=counts.json();val delta=JSONObject()
            for(k in listOf("sourceReadBytes","exports","seals","hashBytes","fileReadBytes","networkBytes"))delta.put(k,after.getLong(k)-before.getLong(k))
            need(listOf("sourceReadBytes","exports","seals","hashBytes","networkBytes").all{delta.getLong(it)==0L},"bl03_reexport_or_body_read")
            need(proxy.partOrder.size==putBefore && proxy.completeCalls.get()==completesBefore,"confirmed_object_resent")
            record("bl03Delta",delta);record("bl03AfterReopenAndSmallState",true)
            mark("foreign-current-bodies-download")
            val foreign=approvedSync-signer.deviceId;val proofs=linkedMapOf<String,JSONObject>();val deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(4)
            val mappingCache=mutableMapOf<String,JSONObject>()
            while(proofs.keys!=foreign && System.nanoTime()<deadline) {
                sync.readCurrent();val current=projection.cloudRows();val w=control.negotiate().getString("windowId")
                for(entry in current) {
                    val row=entry.getJSONObject("row");if(row.getBoolean("deleted") || row.isNull("recipeOid"))continue
                    val recipeOid=row.getString("recipeOid");val mapping=mappingCache[recipeOid]?:control.reference(recipeOid,w).also{mappingCache[recipeOid]=it}
                    val author=mapping.getString("authorDeviceId");if(author !in foreign || author in proofs)continue
                    val destination=SyncV2ContentStore(File(local,"foreign-$author"),identity.scopeJson,localKey)
                    val downloadClient=client();val downloadWorker=SyncV2ContentWorker(downloadClient,identity,destination,{SyncV2TransportSignals(wifi=true,internet=true,cellular=false)},enabled=true,approvedDevice={it in approvedSync})
                    val receiver=SyncV2ContentReceive(identity,authors,destination,downloadWorker,downloadClient,{version->need(version==1L,"unsupported_feature");wrap})
                    val content=SyncV2ContentSession(identity,destination,downloadWorker,downloadClient,signer,SyncV2ContentExport(identity,wrap,1,destination))
                    var ready=0
                    val received=content.downloadPage(listOf(row),receiver,System::currentTimeMillis,{entity->current.firstOrNull{it.getJSONObject("row").getString("entityKey")==entity}?.getJSONObject("row")},{ready++})
                    need(received.status=="complete" && received.completeBodies==1 && ready==1,"foreign_body_not_complete")
                    val accepted=destination.load("recipe/${row.getString("entityKey")}/${row.getString("bodyRevision")}")!!.getJSONObject("recipe")
                    SyncV2ContentCodec.verifyBody(identity,accepted,{b->destination.plain(b.getString("oid"))})
                    proofs[author]=JSONObject().put("platform",devicePlatforms[author]?:"unknown").put("recipeOid",recipeOid).put("plainBytes",accepted.getLong("totalPlainBytes"))
                        .put("blocks",accepted.getJSONArray("blocks").length()).put("plaintextSha256",accepted.getString("plaintextSha256")).put("downloadBytes",received.transferredBytes)
                        .put("recipeRefsSignatureVerified",true).put("availabilitySignatureVerified",true).put("allBlocksAndWholeBodyVerified",true)
                    record("foreignBodies",JSONArray(proofs.values.toList()))
                }
                if(proofs.keys!=foreign)Thread.sleep(1000)
            }
            need(proofs.keys==foreign,"three_foreign_content_authors_not_ready")
            record("foreignAuthors",proofs.size);record("businessAppliedAck",false)
            need(fixtureHash==SyncV2Codec.hex(SyncV2Codec.sha(fixture.readBytes())),"fixture_changed")
            record("fixtureUnchanged",true);record("contentCounts",counts.json());record("proxyRoutes",JSONObject(proxy.routeCounts.mapValues{it.value.get()}));record("status","passed")
        }catch(t:Throwable) {
            val raw=t.message.orEmpty();val safe=if(Regex("[a-z][a-z0-9_]{0,100}").matches(raw))raw else t.javaClass.simpleName
            result.put("status","failed").put("errorPhase",phase).put("errorCode",safe).put("errorFrames",JSONArray(t.stackTrace.filter{it.className.startsWith("com.lightningstudio.watchrss.phone.syncv2")}.take(10).map{"${it.className.substringAfterLast('.')}::${it.methodName}:${it.lineNumber}"}));save()
            throw AssertionError("p3 joint phase=$phase code=$safe")
        }finally {db?.close();relay?.close();wrap.fill(0);local.deleteRecursively()}
    }
}
