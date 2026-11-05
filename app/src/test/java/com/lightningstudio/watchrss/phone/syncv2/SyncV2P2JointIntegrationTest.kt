package com.lightningstudio.watchrss.phone.syncv2

import android.content.Context
import androidx.room.Room
import com.lightningstudio.watchrss.phone.cloud.CloudProtocolV2
import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
import com.lightningstudio.watchrss.phone.data.db.PhoneCompanionDatabase
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/** Opt-in only: one phone fixture, real isolated HTTP, real SQLite. Never run in ordinary unit tests. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SyncV2P2JointIntegrationTest {
    private val root=File("/home/wilf/data/OPPO_Develop/.codex-tmp/cloud-v2/evidence/T-SYNCGIT-P2/phone")
    private val summary=JSONObject().put("platform","JVM/native SQLite + isolated real backend").put("productionAccess",false)
    private var phase="initial"
    private fun record(name:String,value:Any) {summary.put(name,value);save()}
    private fun save(){root.mkdirs();File(root,"joint-phone-summary.json").writeText(summary.toString(2)+"\n")}
    private fun step(name:String){phase=name;summary.put("phase",name);save()}
    private fun requireThat(value:Boolean,code:String){if(!value)SyncV2Codec.fail(code)}
    private fun publicHttp(f:JSONObject,path:String,post:Boolean=false):JSONObject {
        requireThat(f.getString("baseUrl")=="http://127.0.0.1:18942","isolated_backend_required")
        val builder=Request.Builder().url(f.getString("baseUrl")+path)
        val headers=f.getJSONObject("headers");for(k in headers.keys())builder.header(k,headers.getString(k))
        if(post)builder.post("{}".toRequestBody("application/json".toMediaType()))
        val http=OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
        return http.newCall(builder.build()).execute().use { r ->
            requireThat(r.code==200,"bootstrap_http_${r.code}")
            SyncV2Wire.parse(r.body!!.bytes())
        }
    }
    private fun prepareRestore(f:JSONObject,client:SyncV2CloudClient,target:String,account:JSONObject):JSONObject {
        val request=JSONObject().put("scope",client.scope).put("requestId",SyncV2Codec.hex(SyncV2Codec.random(16))).put("targetCommitOid",target)
            .put("expectedAccountRevision",account.getString("revision")).put("expectedRollbackGeneration",account.getString("rollbackGeneration"))
        val builder=Request.Builder().url(f.getString("syncBaseUrl")+"/history/prepare")
        val headers=f.getJSONObject("headers");for(k in headers.keys())builder.header(k,headers.getString(k))
        val http=OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
        return http.newCall(builder.post(SyncV2Codec.canonical(request).toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
            val value=SyncV2Wire.parse(r.body!!.bytes())
            if(!r.isSuccessful)throw SyncV2CloudError(value.getJSONObject("error").getString("code"))
            value
        }
    }
    private fun uploadCommit(client:SyncV2CloudClient,signed:JSONObject):String {
        val oid=SyncV2Codec.oid("commit",signed.getJSONObject("body"));val bytes=SyncV2Codec.utf8(SyncV2Codec.canonical(signed))
        fun id()=SyncV2Codec.hex(SyncV2Codec.random(16))
        val reservation=client.reserve(id(),listOf(oid to bytes.size)).getString("reservationId")
        var at=0
        while(at<bytes.size) {
            val part=bytes.copyOfRange(at,minOf(at+65536,bytes.size));val response=client.put(oid,at,reservation,id(),part)
            client.verifyReceipt(response.getJSONObject("receipt"),oid,bytes.size.toLong(),(at+part.size).toLong(),System.currentTimeMillis());at+=part.size
        }
        val complete=client.complete(oid,reservation,id(),bytes.size)
        client.verifyReceipt(complete.getJSONObject("receipt"),oid,bytes.size.toLong(),bytes.size.toLong(),System.currentTimeMillis())
        return oid
    }
    private fun controlRequest(client:SyncV2CloudClient,projection:SyncV2Projection,signer:SyncV2Signing,authors:SyncV2Authors,
        window:JSONObject,tree:String,generation:String,rollbackTarget:String?=null):JSONObject {
        val refs=window.getJSONArray("refs");val own=(0 until refs.length()).map{refs.getJSONObject(it)}.singleOrNull{it.getString("deviceId")==signer.deviceId}
        val old=own?.getString("commitOid")
        val floor=if(old==null)"0" else authors.header(SyncV2Wire.parse(client.raw(old),262144,true)).getString("deviceSequence")
        val account=window.getJSONObject("account")
        val parents=if(rollbackTarget!=null && rollbackTarget!=old)JSONArray().put(rollbackTarget)else JSONArray()
        val body=JSONObject().put("version",2).put("scope",client.scope).put("authorDeviceId",signer.deviceId).put("deviceSequence",projection.reserveDeviceSequence(floor))
            .put("previousOwnCommit",old?:JSONObject.NULL).put("mergedParents",parents).put("treeRoot",tree).put("requiredFeatures",JSONArray()).put("createdAtMillis",System.currentTimeMillis())
            .put("observedRefs",JSONArray()).put("indexComplete",true).put("accountParent",account.get("commitOid")).put("rollbackGeneration",generation)
            .put("operation",JSONObject().put("kind",if(rollbackTarget==null)"normal" else "rollback").put("rollbackTarget",rollbackTarget?:JSONObject.NULL).put("resolvesGeneration",JSONObject.NULL))
        val oid=uploadCommit(client,signer.commit(body));val requestId=SyncV2Codec.hex(SyncV2Codec.random(16));val now=System.currentTimeMillis()
        return JSONObject().put("account",SyncV2CloudClient.refUpdate(signer,client.scope,"refs/account/current",account.getString("commitOid"),account.getString("revision"),oid,requestId,now))
            .put("device",SyncV2CloudClient.refUpdate(signer,client.scope,"refs/devices/${signer.deviceId}/index",old,own?.getString("refRevision")?:"0",oid,requestId,now)).put("relayGrant",JSONObject.NULL)
    }
    /** Drops exactly one successful publish response AFTER the real backend has accepted it. */
    private class LostResponseProxy {
        private val upstream=OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
        private val server=okhttp3.mockwebserver.MockWebServer()
        val dropped=AtomicInteger();val successfulPublishes=AtomicInteger()
        @Volatile var acceptedRequest:JSONObject?=null
        val routes=java.util.concurrent.ConcurrentHashMap<String,AtomicInteger>()
        init {
            java.util.logging.Logger.getLogger(okhttp3.mockwebserver.MockWebServer::class.java.name).level=java.util.logging.Level.OFF
            server.dispatcher=object:okhttp3.mockwebserver.Dispatcher(){override fun dispatch(incoming:okhttp3.mockwebserver.RecordedRequest):okhttp3.mockwebserver.MockResponse {
                val path=incoming.requestUrl!!.encodedPath
                routes.computeIfAbsent(if(path.contains("/objects/"))"objects" else path.substringAfter("/sync/v2/")){AtomicInteger()}.incrementAndGet()
                val raw=incoming.body.readByteArray()
                val builder=Request.Builder().url("http://127.0.0.1:18942"+incoming.path)
                for((k,v)in incoming.headers)if(k.lowercase() !in setOf("host","content-length","connection","transfer-encoding"))builder.header(k,v)
                builder.method(incoming.method!!,if(incoming.method in setOf("GET","HEAD"))null else raw.toRequestBody(incoming.getHeader("Content-Type")?.toMediaType()))
                return upstream.newCall(builder.build()).execute().use { response ->
                    val bytes=response.body?.bytes()?:ByteArray(0)
                    val forwarded=okhttp3.mockwebserver.MockResponse().setResponseCode(response.code).setBody(okio.Buffer().write(bytes))
                    for((k,v)in response.headers)if(k.lowercase() !in setOf("content-length","transfer-encoding","connection"))forwarded.setHeader(k,v)
                    if(path.endsWith("/account/publish") && response.isSuccessful) {
                        successfulPublishes.incrementAndGet();acceptedRequest=SyncV2Wire.parse(raw)
                        if(dropped.compareAndSet(0,1))forwarded.setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST)
                    }
                    forwarded
                }
            }}
            server.start(java.net.InetAddress.getByName("127.0.0.1"),0)
        }
        val base get()=server.url("/functions/v1/sync/v2/")
        fun close(){server.shutdown()}
    }
    @Test fun aServiceBootstrapAndCapabilitiesPinWithoutDeviceRosterDependency() {
        requireThat(System.getenv("WATCHRSS_P2_PHONE_JOINT")=="1","joint_not_enabled")
        val f=SyncV2Wire.parse(File("/home/wilf/data/OPPO_Develop/.codex-tmp/cloud-v2/integration/phone.json").readBytes())
        val bootstrap=publicHttp(f,"/functions/v1/cloud/bootstrap",true)
        val pinFile=File.createTempFile("phone-p2-service-pin-",".json")
        try {
            val received=bootstrap.getJSONObject("syncV2")
            val trust=SyncV2ServiceTrust.fromBootstrap(f.getString("accountId"),received,null){pinFile.writeText(SyncV2Codec.canonical(it))}
            val restored=SyncV2ServiceTrust.fromBootstrap(f.getString("accountId"),received,SyncV2Wire.parse(pinFile.readBytes())){SyncV2Codec.fail("pin_rewritten")}
            val headers=f.getJSONObject("headers")
            val session=SyncV2CloudClient.Session(f.getString("userId"),f.getString("legacyDeviceId"),f.getString("legacyLibraryEpoch"),f.getString("legacyContentGeneration"),SyncV2Codec.scope(f.getJSONObject("scope")),f.getString("syncDeviceId"),headers.getString("Authorization").removePrefix("Bearer "),"",headers.getString("x-watchrss-device-authorization"))
            val client=SyncV2CloudClient((f.getString("syncBaseUrl")+"/").toHttpUrl(),{session},restored,true)
            client.capabilities();requireThat(trust.keyId=="b73621a8ae71467db3a89da4c5ba659d","service_key_changed")
            val changed=JSONObject(received.toString()).put("receiptKeyId","ff".repeat(16))
            val failure=runCatching{SyncV2ServiceTrust.fromBootstrap(f.getString("accountId"),changed,SyncV2Wire.parse(pinFile.readBytes())){SyncV2Codec.fail("pin_rewritten")}}.exceptionOrNull()
            requireThat(failure is SyncV2CloudError && failure.code=="service_key_changed","key_change_was_accepted")
            File(root,"joint-phone-c3.json").writeText(JSONObject().put("status","passed").put("receiptKeyId",trust.keyId).put("bootstrapCapabilitiesMatch",true).put("persistentPinReload",true).put("replacementRejected",true).put("rosterRequired",false).toString(2)+"\n")
        }finally{pinFile.delete()}
    }
    @Test fun phoneRealBackendC3C4PublishReadConcurrencyLostResponseAndOldGeneration() {
        requireThat(System.getenv("WATCHRSS_P2_PHONE_JOINT")=="1","joint_not_enabled")
        val fixture=File("/home/wilf/data/OPPO_Develop/.codex-tmp/cloud-v2/integration/phone.json")
        val f=SyncV2Wire.parse(fixture.readBytes());requireThat(f.getBoolean("syntheticOnly") && f.getString("platform")=="phone","fixture_scope_invalid")
        val local=File((RuntimeEnvironment.getApplication() as Context).cacheDir,"p2-live-${System.nanoTime()}").also{it.mkdirs()}
        val wrap=SyncV2Codec.unhex(f.getJSONObject("crypto").getString("contentKeyHex"),32)
        var room:PhoneCompanionDatabase?=null;var proxy:LostResponseProxy?=null
        try {
            step("bootstrap-c3")
            val bootstrap=publicHttp(f,"/functions/v1/cloud/bootstrap",true)
            requireThat(bootstrap.getString("libraryEpoch")==f.getString("legacyLibraryEpoch") && bootstrap.getString("contentGeneration")==f.getString("legacyContentGeneration"),"scope_mismatch")
            val commitments=bootstrap.getJSONArray("keyCommitments")
            requireThat((0 until commitments.length()).any{val c=commitments.getJSONObject(it);c.getInt("keyVersion")==1 && c.getString("commitment")==CloudProtocolV2.commitment(wrap,f.getString("userId"),f.getString("legacyLibraryEpoch"),1)},"authentication_required")
            val approved=bootstrap.getJSONArray("devices").let { a -> (0 until a.length()).map{a.getJSONObject(it)}.filter { d ->
                d.isNull("revokedAt") && d.optInt("attestationKeyVersion")==1 && d.optString("attestation")==CloudProtocolV2.attestation(wrap,f.getString("userId"),f.getString("legacyLibraryEpoch"),d.getString("deviceId"),Base64.getDecoder().decode(d.getString("publicKeySpki")))
            }.map{it.getString("deviceId")}.toSet() }
            requireThat(approved.size==4,"approved_roster_invalid")
            val pin=File(local,"service-pin.json")
            var trust=SyncV2ServiceTrust.fromBootstrap(f.getString("accountId"),bootstrap.getJSONObject("syncV2"),null){pin.writeText(SyncV2Codec.canonical(it))}
            trust=SyncV2ServiceTrust.fromBootstrap(f.getString("accountId"),bootstrap.getJSONObject("syncV2"),SyncV2Wire.parse(pin.readBytes())){SyncV2Codec.fail("pin_rewritten")}
            requireThat(trust.keyId=="b73621a8ae71467db3a89da4c5ba659d","service_key_changed")
            proxy=LostResponseProxy()
            val headers=f.getJSONObject("headers")
            val session=SyncV2CloudClient.Session(f.getString("userId"),f.getString("legacyDeviceId"),f.getString("legacyLibraryEpoch"),f.getString("legacyContentGeneration"),SyncV2Codec.scope(f.getJSONObject("scope")),f.getString("syncDeviceId"),headers.getString("Authorization").removePrefix("Bearer "),"",headers.getString("x-watchrss-device-authorization"))
            val client=SyncV2CloudClient(proxy.base,{session},trust,true)
            client.capabilities();record("c3",true);record("receiptKeyId",trust.keyId)
            step("identity-and-c4")
            val descriptor=client.descriptor();val descriptorBody=descriptor.getJSONObject("descriptor")
            val capsuleBytes=client.recovery(descriptor.getString("recoveryOid"))
            requireThat(SyncV2Codec.oid("id-key-recovery",SyncV2Wire.parse(capsuleBytes,8192,true))==descriptor.getString("recoveryOid"),"hash_mismatch")
            val identity=SyncV2Identity.recover(SyncV2Wire.parse(capsuleBytes,8192,true),descriptorBody,wrap,f.getString("accountId"),f.getJSONObject("scope"),descriptor.getString("descriptorOid"))
            requireThat(identity.entity("article","joint-identity-probe")==SyncV2Codec.entity(SyncV2Codec.unhex(f.getJSONObject("crypto").getString("idKeyHex"),32),identity.repoId,"article","joint-identity-probe"),"identity_conflict")
            val window=client.negotiate();val bindings=client.signingKeys(window.getString("windowId"));val authors=client.authors(identity){it in approved}
            val list=bindings.getJSONArray("bindings");requireThat(list.length()==4,"binding_roster_invalid")
            for(i in 0 until list.length())authors.accept(list.getJSONObject(i),bindings.getString("revocationRevision"))
            record("c4Bindings",list.length())
            val signing=f.getJSONObject("signingKey");val signer=SyncV2Signing.fromPrivate(SyncV2Codec.unhex(signing.getString("privateKeyHex"),32),session.deviceId,signing.getString("keyId"))
            requireThat(signer.publicHex==authors.publicKey(signer.deviceId,signer.keyId),"identity_conflict")
            room=Room.databaseBuilder(RuntimeEnvironment.getApplication(),PhoneCompanionDatabase::class.java,File(local,"phone.db").absolutePath).allowMainThreadQueries().build()
            val localKey=SyncV2Codec.random(32);val projection=SyncV2Projection(room.openHelper.writableDatabase,identity,localKey,true).also{it.install()}
            val sync=SyncV2CloudSync(client,identity,projection,{it in approved},{version->requireThat(version==1L,"unsupported_feature");wrap})
            step("initial-authenticated-read")
            sync.readCurrent()
            // Seed after observing this run's account baseline; identities are unique to this phone run.
            val run="phone-${System.nanoTime()}";val instant=System.currentTimeMillis();val sql=room.openHelper.writableDatabase
            runBlocking {room.phoneArticleDao().upsert(PhoneArticleEntity("$run-rss","phone-source","https://example.invalid/$run/rss","Phone synthetic RSS","synthetic","summary",null,"synthetic body",null,"synthetic-hash",instant,instant,false,0,0,"https://example.invalid/$run/feed","synthetic feed",true,instant,0,false,0,0,false,0))}
            val book=JSONObject().put("bookId","$run-book").put("fingerprint","synthetic-book-fingerprint-$run").put("title","Phone synthetic book").put("chapterCount",1).put("deletedChapters",JSONArray()).put("deletionClock",0).put("deleted",false).put("deletedAt",0).put("updatedAt",instant).put("sourceDeviceId","phone-source")
            sql.execSQL("INSERT INTO imported_books VALUES(?,?,'synthetic-hash',?)",arrayOf<Any>("$run-book",SyncV2Codec.canonical(book),instant))
            sql.execSQL("INSERT INTO notes VALUES(?,NULL,'Phone synthetic note','synthetic note','synthetic note','same-hash','same-hash','synthetic note',0,?,?,'phone-source',0,0)",arrayOf<Any>("$run-note",instant,instant))
            for(domain in SyncV2Schema.domains)while(!projection.scanPage(domain.table).complete){}
            record("localSeedRows",3)
            step("publish-with-cas-retry-and-loss")
            var result:JSONObject?=null;var conflicts=0
            repeat(20) {
                if(result==null) {
                    sync.readCurrent()
                    try{result=sync.publishIndex(signer,wrap,1)}catch(e:SyncV2CloudError){if(e.code!="ref_conflict")throw e;conflicts++}
                }
            }
            val accepted=result?:SyncV2Codec.fail("cas_retry_exhausted")
            requireThat(proxy.dropped.get()==1,"response_loss_not_injected")
            val request=proxy.acceptedRequest?:SyncV2Codec.fail("accepted_request_missing")
            val commitOid=request.getJSONObject("account").getJSONObject("body").getString("newCommitOid")
            val replay=client.publish(request)
            requireThat(replay.getString("serverRevision")==accepted.getString("serverRevision") && SyncV2Codec.canonical(replay.getJSONObject("account"))==SyncV2Codec.canonical(accepted.getJSONObject("account")),"duplicate_logical_publish")
            record("casConflicts",conflicts);record("publishedCommitOid",commitOid);record("publishServerRevision",accepted.getString("serverRevision"));record("lostResponses",proxy.dropped.get());record("idempotentReplay",true)
            step("foreign-current-index-read")
            var foreign=false;val until=System.nanoTime()+120_000_000_000L
            while(!foreign && System.nanoTime()<until) {
                sync.readCurrent()
                val state=projection.state();val account=state.body.getJSONObject("account")
                val current=SyncV2Wire.parse(client.raw(account.getString("oid")),262144,true)
                authors.header(current)
                foreign=current.getJSONObject("body").getString("authorDeviceId")!=signer.deviceId
                if(!foreign)Thread.sleep(250)
            }
            requireThat(foreign,"foreign_publish_not_observed")
            val currentRows=projection.cloudRows();requireThat(currentRows.isNotEmpty(),"authenticated_index_empty")
            requireThat(projection.state().body.isNull("appliedAccount"),"object_ack_is_not_apply")
            record("foreignCurrentAuthenticated",true);record("authenticatedRows",currentRows.size);record("bodyWorkerCalls",0);record("businessAppliedAck",false)
            step("old-generation-rejection")
            var staleRejected=false;var generationConflicts=0
            repeat(20) {
                if(!staleRejected) {
                    val latest=client.negotiate();val accountNow=latest.getJSONObject("account");val generation=accountNow.getString("rollbackGeneration")
                    val current=authors.header(SyncV2Wire.parse(client.raw(accountNow.getString("commitOid")),262144,true))
                    if(SyncV2Codec.d64(generation).signum()==0) {
                        requireThat(!current.isNull("accountParent"),"rollback_history_missing")
                        val target=current.getString("accountParent");val targetBody=authors.header(SyncV2Wire.parse(client.raw(target),262144,true))
                        try {
                            prepareRestore(f,client,target,accountNow)
                            client.publish(controlRequest(client,projection,signer,authors,latest,targetBody.getString("treeRoot"),SyncV2Codec.next(generation),target))
                            record("syntheticRollbackCreated",true)
                        }catch(e:SyncV2CloudError){if(e.code!="ref_conflict")throw e;generationConflicts++}
                    }else {
                        val old=(SyncV2Codec.d64(generation)-java.math.BigInteger.ONE).toString()
                        val stale=controlRequest(client,projection,signer,authors,latest,current.getString("treeRoot"),old)
                        try {client.publish(stale);SyncV2Codec.fail("backend_accepted_old_generation")}
                        catch(e:SyncV2CloudError) {
                            when(e.code){"rollback_conflict"->{staleRejected=true;record("rejectedOldGeneration",old);record("generation",generation)};"ref_conflict"->generationConflicts++;else->throw e}
                        }
                    }
                }
            }
            requireThat(staleRejected,"generation_retry_exhausted");record("oldGenerationRejected",true);record("generationCasConflicts",generationConflicts)
            val after=(SyncV2Codec.d64(accepted.getString("serverRevision"))-java.math.BigInteger.ONE).toString()
            var cursor=after;var matchingEvents=0
            repeat(16){val events=client.events(cursor,0);val a=events.getJSONArray("events");for(i in 0 until a.length())if(a.getJSONObject(i).optString("commitOid")==commitOid)matchingEvents++
                cursor=events.getString("cursor");if(!events.getBoolean("hasMore"))return@repeat}
            requireThat(matchingEvents==1,"duplicate_logical_publish_event");record("logicalPublishEvents",matchingEvents)
            record("routes",JSONObject(proxy.routes.mapValues{it.value.get()}));record("status","passed")
        }catch(t:Throwable) {
            val raw=t.message.orEmpty();val safe=if(Regex("[a-z][a-z0-9_]{0,80}").matches(raw))raw else t.javaClass.simpleName
            summary.put("status","failed").put("errorPhase",phase).put("errorCode",safe);save()
            throw AssertionError("joint phase=$phase code=$safe")
        }finally {room?.close();proxy?.close();wrap.fill(0);local.deleteRecursively()}
    }
}
