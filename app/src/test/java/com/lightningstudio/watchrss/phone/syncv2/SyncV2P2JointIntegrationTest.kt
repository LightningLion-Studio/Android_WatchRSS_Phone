package com.lightningstudio.watchrss.phone.syncv2

import android.content.Context
import androidx.room.Room
import com.lightningstudio.watchrss.phone.cloud.CloudProtocolV2
import com.lightningstudio.watchrss.phone.cloud.CloudHttpException
import com.lightningstudio.watchrss.phone.cloud.PhoneCloudClient
import com.lightningstudio.watchrss.phone.account.AccountEnvironment
import com.lightningstudio.watchrss.phone.account.PhoneAccountSession
import com.lightningstudio.watchrss.phone.data.book.BookSync
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
    /** Fixed fixture signing keys outlive each fresh test database, including rejected staged commits. */
    private fun reserveFixtureSequenceBlock(projection:SyncV2Projection,deviceId:String) {
        val file=File(root,"joint-phone-sequence-watermark.json")
        val previous=if(file.exists())SyncV2Wire.parse(file.readBytes()).also{requireThat(it.getString("deviceId")==deviceId,"fixture_sequence_owner")}.getString("reservedThrough")else "0"
        val base=maxOf(SyncV2Codec.d64(previous),java.math.BigInteger.valueOf(System.currentTimeMillis()))
        val ceiling=base+java.math.BigInteger.valueOf(4096)
        SyncV2Codec.d64(ceiling.toString())
        val value=JSONObject().put("deviceId",deviceId).put("reservedThrough",ceiling.toString())
        val temporary=File(root,"joint-phone-sequence-watermark.tmp")
        java.io.FileOutputStream(temporary).use{it.write(SyncV2Codec.utf8(SyncV2Codec.canonical(value)));it.fd.sync()}
        java.nio.file.Files.move(temporary.toPath(),file.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        // This run has fewer than 128 bounded sequence allocations. Unused numbers remain reserved.
        projection.reserveDeviceSequence(base.toString())
        record("fixtureSequenceBase",base.toString());record("fixtureSequenceReservedThrough",ceiling.toString())
    }
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
    private fun uploadObject(client:SyncV2CloudClient,oid:String,bytes:ByteArray) {
        fun id()=SyncV2Codec.hex(SyncV2Codec.random(16))
        val reservation=client.reserve(id(),listOf(oid to bytes.size)).getString("reservationId")
        var at=0
        while(at<bytes.size) {
            val part=bytes.copyOfRange(at,minOf(at+65536,bytes.size));val response=client.put(oid,at,reservation,id(),part)
            client.verifyReceipt(response.getJSONObject("receipt"),oid,bytes.size.toLong(),(at+part.size).toLong(),System.currentTimeMillis());at+=part.size
        }
        val complete=client.complete(oid,reservation,id(),bytes.size)
        client.verifyReceipt(complete.getJSONObject("receipt"),oid,bytes.size.toLong(),bytes.size.toLong(),System.currentTimeMillis())
    }
    private fun uploadCommit(client:SyncV2CloudClient,signed:JSONObject):String {
        val oid=SyncV2Codec.oid("commit",signed.getJSONObject("body"))
        uploadObject(client,oid,SyncV2Codec.utf8(SyncV2Codec.canonical(signed)));return oid
    }
    private fun controlRequest(client:SyncV2CloudClient,projection:SyncV2Projection,signer:SyncV2Signing,authors:SyncV2Authors,
        window:JSONObject,tree:String,generation:String,verify:(JSONObject)->Unit={}):JSONObject {
        val refs=window.getJSONArray("refs");val own=(0 until refs.length()).map{refs.getJSONObject(it)}.singleOrNull{it.getString("deviceId")==signer.deviceId}
        val old=own?.getString("commitOid")
        val floor=if(old==null)"0" else authors.header(SyncV2Wire.parse(client.raw(old),262144,true)).getString("deviceSequence")
        val account=window.getJSONObject("account")
        val parents=JSONArray()
        val body=JSONObject().put("version",2).put("scope",client.scope).put("authorDeviceId",signer.deviceId).put("deviceSequence",projection.reserveDeviceSequence(floor))
            .put("previousOwnCommit",old?:JSONObject.NULL).put("mergedParents",parents).put("treeRoot",tree).put("requiredFeatures",JSONArray()).put("createdAtMillis",System.currentTimeMillis())
            .put("observedRefs",JSONArray()).put("indexComplete",true).put("accountParent",account.get("commitOid")).put("rollbackGeneration",generation)
            .put("operation",JSONObject().put("kind","normal").put("rollbackTarget",JSONObject.NULL).put("resolvesGeneration",JSONObject.NULL))
        val signed=signer.commit(body);verify(signed)
        val oid=uploadCommit(client,signed);val requestId=SyncV2Codec.hex(SyncV2Codec.random(16));val now=System.currentTimeMillis()
        return JSONObject().put("account",SyncV2CloudClient.refUpdate(signer,client.scope,"refs/account/current",account.getString("commitOid"),account.getString("revision"),oid,requestId,now))
            .put("device",SyncV2CloudClient.refUpdate(signer,client.scope,"refs/devices/${signer.deviceId}/index",old,own?.getString("refRevision")?:"0",oid,requestId,now)).put("relayGrant",JSONObject.NULL)
    }
    /** User-authorized repair of this harness's two malformed synthetic identities only. */
    private fun repairPhoneSyntheticBooks(client:SyncV2CloudClient,identity:SyncV2Identity,projection:SyncV2Projection,signer:SyncV2Signing,authors:SyncV2Authors,key:ByteArray) {
        repeat(20) {
            val window=client.negotiate();val account=window.getJSONObject("account")
            if(account.isNull("commitOid"))return
            val header=authors.header(SyncV2Wire.parse(client.raw(account.getString("commitOid")),262144,true))
            val objects=linkedMapOf<String,ByteArray>()
            fun fetch(oid:String):ByteArray=objects.getOrPut(oid){client.raw(oid,window.getString("windowId"))}
            val rows=SyncV2Index.read(identity.scope,header.getString("treeRoot"),::fetch).toMutableList()
            val refs=linkedMapOf<String,JSONObject>();val newObjects=linkedMapOf<String,ByteArray>();var repaired=0
            for(i in rows.indices) {
                val row=rows[i];val meta=SyncV2Wire.parse(SyncV2Envelope.open(identity,key,row.getString("metaOid"),fetch(row.getString("metaOid")),1),262144,true)
                if(meta.getString("kind")!="book" || !Regex("phone-[0-9]+-book").matches(meta.getString("logicalId")))continue
                val oldId=meta.getString("logicalId");val run=oldId.removeSuffix("-book")
                val payload=meta.getJSONObject("payload")
                if(payload.optString("fingerprint")!="synthetic-book-fingerprint-$run")continue
                val state=SyncV2Wire.parse(SyncV2Envelope.open(identity,key,row.getString("stateOid"),fetch(row.getString("stateOid")),1),262144,true)
                requireThat(state.getJSONObject("payload").getString("sourceDeviceId")=="phone-source" && payload.getString("bookId")==oldId && row.getString("entityKey")==identity.entity("book",oldId),"repair_ownership_mismatch")
                requireThat(!row.isNull("bodyRevision") && !row.isNull("promiseId") && listOf("recipeOid","opaqueContentId","contentBytes").all{row.isNull(it)},"repair_body_mismatch")
                val value=JSONObject(payload.toString());for(k in state.getJSONObject("payload").keys()) {requireThat(!value.has(k),"repair_duplicate_field");value.put(k,state.getJSONObject("payload").get(k))}
                val fingerprint=BookSync.fingerprint(listOf(BookSync.chapterHash(1,"Phone synthetic chapter","synthetic book body $oldId")))
                val id=BookSync.id(fingerprint);value.put("fingerprint",fingerprint).put("bookId",id)
                val entity=identity.entity("book",id);val pair=SyncV2Business.split("book",id,entity,value,if(meta.getString("addedAtSource")=="original")row.getLong("addedAtMillis")else null)
                val m=SyncV2Envelope.seal(identity,key,1,"meta",SyncV2Codec.utf8(SyncV2Codec.canonical(pair.meta)))
                val s=SyncV2Envelope.seal(identity,key,1,"state",SyncV2Codec.utf8(SyncV2Codec.canonical(pair.state)))
                newObjects[m.oid]=m.bytes;newObjects[s.oid]=s.bytes
                val ref=SyncV2Index.refs(identity,signer,s.oid);refs[s.oid]=ref
                val raw=SyncV2Codec.utf8(SyncV2Codec.canonical(ref));objects[SyncV2Codec.oid("state-refs",ref)]=raw
                rows[i]=JSONObject(row.toString()).put("entityKey",entity).put("metaOid",m.oid).put("stateOid",s.oid).put("rowSequence","1")
                repaired++
            }
            if(repaired==0){record("syntheticBooksRepairAlreadyCurrent",true);return}
            val tree=SyncV2Index.build(identity.scope,rows);newObjects.putAll(tree.objects);objects.putAll(newObjects)
            for((oid,bytes)in newObjects)uploadObject(client,oid,bytes)
            for(ref in refs.values)client.stateRefs(ref)
            val request=controlRequest(client,projection,signer,authors,window,tree.root,account.getString("rollbackGeneration"),verify={ signed ->
                SyncV2ControlClosure(identity,authors){version->requireThat(version==1L,"unsupported_feature");key}.verify(signed,::fetch){parent ->
                    refs[parent]?.let{ref->JSONObject().put("scope",identity.scope).put("parentOid",parent).put("refsOid",SyncV2Codec.oid("state-refs",ref)).put("authorDeviceId",signer.deviceId).put("keyId",signer.keyId).put("totalBytes",SyncV2Codec.utf8(SyncV2Codec.canonical(ref)).size)}
                        ?:client.reference(parent,window.getString("windowId")).let{JSONObject(it.toString()).apply{remove("version")}}
                }
            })
            try {val result=client.publish(request);record("syntheticBooksRepaired",repaired);record("syntheticRepairRevision",result.getString("serverRevision"));return}
            catch(e:SyncV2CloudError){if(e.code!="ref_conflict")throw e}
        }
        SyncV2Codec.fail("repair_cas_retry_exhausted")
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
        root.mkdirs()
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
            val checkpoint=client.checkpoint()
            SyncV2Codec.exact(checkpoint,setOf("version","scope","body","signature"))
            val signed=JSONObject(checkpoint.toString()).apply{remove("version");remove("scope")}
            val body=restored.verify("wrss.sync.v2/checkpoint-sign",signed)
            SyncV2Codec.exact(body,setOf("scope","checkpointId","serverRevision","refs","account","historyFloorRevision","historyFloorMillis","windowId","issuedAtMillis","expiresAtMillis"))
            requireThat(SyncV2Codec.scope(body.getJSONObject("scope"))==session.scopeJson,"scope_mismatch")
            SyncV2Codec.unhex(body.getString("checkpointId"),16);SyncV2Codec.unhex(body.getString("windowId"),16)
            val revision=SyncV2Codec.d64(body.getString("serverRevision"));val floor=SyncV2Codec.d64(body.getString("historyFloorRevision"))
            requireThat(floor<=revision,"checkpoint_floor_invalid");SyncV2Wire.uint(body,"historyFloorMillis")
            val now=System.currentTimeMillis();val issued=SyncV2Wire.uint(body,"issuedAtMillis");val expires=SyncV2Wire.uint(body,"expiresAtMillis")
            requireThat(issued<=now+30000 && expires>now && expires>issued && expires-issued<=300000,"checkpoint_expired")
            val refs=body.getJSONArray("refs");requireThat(refs.length()<=64,"checkpoint_refs_invalid");var previous=""
            for(i in 0 until refs.length()) {
                val ref=refs.getJSONObject(i);SyncV2Codec.exact(ref,setOf("deviceId","commitOid","refRevision"))
                val device=ref.getString("deviceId");SyncV2Codec.unhex(device,16);requireThat(device>previous,"checkpoint_refs_order");previous=device
                SyncV2Wire.oid(ref.getString("commitOid"),"commit");requireThat(SyncV2Codec.d64(ref.getString("refRevision")).signum()>0,"checkpoint_ref_revision")
            }
            val account=body.getJSONObject("account");SyncV2Codec.exact(account,setOf("commitOid","revision","rollbackGeneration"))
            if(!account.isNull("commitOid"))SyncV2Wire.oid(account.getString("commitOid"),"commit")
            SyncV2Codec.d64(account.getString("revision"));SyncV2Codec.d64(account.getString("rollbackGeneration"))
            File(root,"joint-checkpoint-c5.json").writeText(JSONObject().put("status","passed").put("outerVersionScope",true).put("serviceSignatureVerified",true).put("refsSortedUnique",true).put("refs",refs.length()).put("serverRevision",revision.toString()).put("historyFloorRevision",floor.toString()).put("freshWindow",true).toString(2)+"\n")
            val changed=JSONObject(received.toString()).put("receiptKeyId","ff".repeat(16))
            val failure=runCatching{SyncV2ServiceTrust.fromBootstrap(f.getString("accountId"),changed,SyncV2Wire.parse(pinFile.readBytes())){SyncV2Codec.fail("pin_rewritten")}}.exceptionOrNull()
            requireThat(failure is SyncV2CloudError && failure.code=="service_key_changed","key_change_was_accepted")
            File(root,"joint-phone-c3.json").writeText(JSONObject().put("status","passed").put("receiptKeyId",trust.keyId).put("bootstrapCapabilitiesMatch",true).put("persistentPinReload",true).put("replacementRejected",true).put("rosterRequired",false).toString(2)+"\n")
        }finally{pinFile.delete()}
    }
    @Test fun cLegacyRecordsRequireUpgradeInsteadOfReturningEmptyLibrary() {
        requireThat(System.getenv("WATCHRSS_P2_PHONE_JOINT")=="1","joint_not_enabled")
        val f=SyncV2Wire.parse(File("/home/wilf/data/OPPO_Develop/.codex-tmp/cloud-v2/integration/phone.json").readBytes())
        requireThat(f.getString("baseUrl")=="http://127.0.0.1:18942","isolated_backend_required")
        val headers=f.getJSONObject("headers")
        val session=PhoneAccountSession(f.getString("userId"),"synthetic",headers.getString("Authorization").removePrefix("Bearer "),"",f.getLong("expiresAtMillis"))
        val legacy=PhoneCloudClient(AccountEnvironment(backendBaseUrl=f.getString("baseUrl"),supabaseAnonKey="",appAccessPublicKey=""),{headers.getString("x-watchrss-device-authorization").removePrefix("Bearer ")})
        legacy.currentDeviceId=f.getString("legacyDeviceId");legacy.useEpoch(session,f.getString("legacyLibraryEpoch"));legacy.useContentGeneration(session,f.getString("legacyContentGeneration"))
        val results=JSONArray()
        for(route in listOf("records","heads","snapshot")) {
            val error=runCatching {runBlocking{when(route) {
                "records"->legacy.recordPage(session,"0")
                "heads"->legacy.snapshotHeads(session)
                else->legacy.snapshot(session,"00000000-0000-4000-8000-000000000001")
            }}}.exceptionOrNull()
            val result=JSONObject().put("route",route).put("emptyLibraryReturned",error==null).put("status",if(error is CloudHttpException && error.status==426)"passed"else"failed")
            if(error is CloudHttpException)result.put("httpStatus",error.status)
            results.put(result);root.mkdirs();File(root,"joint-legacy-read-product.json").writeText(results.toString(2)+"\n")
            requireThat(error is CloudHttpException && error.status==426,"legacy_upgrade_required_missing")
            val upgrade=error as CloudHttpException
            val detail=SyncV2Wire.parse(upgrade.detail.toByteArray())
            requireThat(detail.getJSONObject("error").getString("code")=="upgrade_required","legacy_upgrade_code_missing")
            requireThat(upgrade.message=="请升级到最新版本以继续同步","legacy_upgrade_message_missing")
            result.put("upgradeMessage",upgrade.message).put("returnedNull",false)
            File(root,"joint-legacy-read-product.json").writeText(results.toString(2)+"\n")
        }
    }
    @Test fun phoneRealBackendC3C4PublishReadConcurrencyLostResponseAndWrongGeneration() {
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
            record("approvedDevices",approved.size)
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
            reserveFixtureSequenceBlock(projection,signer.deviceId)
            val sync=SyncV2CloudSync(client,identity,projection,{it in approved},{version->requireThat(version==1L,"unsupported_feature");wrap})
            step("repair-phone-synthetic-books")
            val repairClient=SyncV2CloudClient((f.getString("syncBaseUrl")+"/").toHttpUrl(),{session},trust,true)
            repairClient.capabilities();repairPhoneSyntheticBooks(repairClient,identity,projection,signer,authors,wrap)
            step("initial-authenticated-read")
            sync.readCurrent()
            // Seed after observing this run's account baseline; identities are unique to this phone run.
            val run="phone-${System.nanoTime()}";val instant=System.currentTimeMillis();val sql=room.openHelper.writableDatabase
            runBlocking {room.phoneArticleDao().upsert(PhoneArticleEntity("$run-rss","phone-source","https://example.invalid/$run/rss","Phone synthetic RSS","synthetic","summary",null,"synthetic body",null,"synthetic-hash",instant,instant,false,0,0,"https://example.invalid/$run/feed","synthetic feed",true,instant,0,false,0,0,false,0))}
            val fingerprint=BookSync.fingerprint(listOf(BookSync.chapterHash(1,"Phone synthetic chapter","synthetic book body $run")))
            val bookId=BookSync.id(fingerprint)
            val book=JSONObject().put("bookId",bookId).put("fingerprint",fingerprint).put("title","Phone synthetic book").put("chapterCount",1).put("deletedChapters",JSONArray()).put("deletionClock",0).put("deleted",false).put("deletedAt",0).put("updatedAt",instant).put("sourceDeviceId","phone-source")
            sql.execSQL("INSERT INTO imported_books VALUES(?,?,'synthetic-hash',?)",arrayOf<Any>(bookId,SyncV2Codec.canonical(book),instant))
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
            val expectedAuthors=(0 until list.length()).map{list.getJSONObject(it).getJSONObject("body").getString("deviceId")}.toSet()-signer.deviceId
            val foreignProofs=linkedMapOf<String,JSONObject>();val until=System.nanoTime()+180_000_000_000L
            while(foreignProofs.keys!=expectedAuthors && System.nanoTime()<until) {
                val auditWindow=client.negotiate();val refs=auditWindow.getJSONArray("refs")
                for(i in 0 until refs.length()) {
                    val ref=refs.getJSONObject(i);val author=ref.getString("deviceId")
                    if(author !in expectedAuthors || author in foreignProofs)continue
                    val peerOid=ref.getString("commitOid");val envelope=SyncV2Wire.parse(client.raw(peerOid),262144,true)
                    val header=authors.header(envelope)
                    requireThat(header.getString("authorDeviceId")==author && SyncV2Codec.oid("commit",header)==peerOid,"peer_commit_mismatch")
                    val verified=SyncV2ControlClosure(identity,authors){version->requireThat(version==1L,"unsupported_feature");wrap}.verify(envelope,{oid->client.raw(oid,auditWindow.getString("windowId"))}) { parent ->
                        client.reference(parent,auditWindow.getString("windowId")).let {JSONObject(it.toString()).apply{remove("version")}}
                    }
                    // Audit device refs without promoting them to account/current. Only authentication evidence survives.
                    val audit=File(local,"peer-audit-$author").also{it.mkdirs()}
                    for((oid,bytes)in verified.objects)java.io.FileOutputStream(File(audit,oid.substringAfter(":sha256:"))).use{out->out.write(bytes);out.fd.sync()}
                    val proof=JSONObject().put("authorDeviceId",author).put("commitOid",peerOid).put("treeRoot",header.getString("treeRoot"))
                        .put("objects",verified.objects.size).put("rows",verified.rows.size).put("closureAuthenticatedAndFsynced",true).put("businessAppliedAck",false)
                    foreignProofs[author]=proof
                    File(root,"joint-foreign-proofs.json").writeText(JSONArray(foreignProofs.values.toList()).toString(2)+"\n")
                    audit.deleteRecursively()
                }
                if(foreignProofs.keys!=expectedAuthors)Thread.sleep(500)
            }
            requireThat(foreignProofs.keys==expectedAuthors,"three_foreign_publishers_not_observed")
            sync.readCurrent()
            val currentRows=projection.cloudRows();requireThat(currentRows.isNotEmpty(),"authenticated_index_empty")
            requireThat(projection.state().body.isNull("appliedAccount"),"object_ack_is_not_apply")
            record("foreignPublishersAuthenticated",foreignProofs.size);record("authenticatedCurrentRows",currentRows.size);record("bodyWorkerCalls",0);record("businessAppliedAck",false)
            step("concurrent-signed-cas")
            val pool=java.util.concurrent.Executors.newFixedThreadPool(2)
            var raceVerified=false;var raceRetries=0
            try {repeat(20) {
                if(!raceVerified) {
                    val raceWindow=client.negotiate();val raceAccount=raceWindow.getJSONObject("account")
                    val tree=authors.header(SyncV2Wire.parse(client.raw(raceAccount.getString("commitOid")),262144,true)).getString("treeRoot")
                    val attempts=listOf(controlRequest(client,projection,signer,authors,raceWindow,tree,raceAccount.getString("rollbackGeneration")),
                        controlRequest(client,projection,signer,authors,raceWindow,tree,raceAccount.getString("rollbackGeneration")))
                    val gate=java.util.concurrent.CountDownLatch(1)
                    val futures=attempts.map { attempt -> pool.submit<Pair<JSONObject?,String?>> {
                        gate.await();try {client.publish(attempt) to null}catch(e:SyncV2CloudError){null to e.code}
                    }}
                    gate.countDown();val results=futures.map{it.get(45,java.util.concurrent.TimeUnit.SECONDS)}
                    val winners=results.indices.filter{results[it].first!=null}
                    if(winners.size==1) {
                        val winner=winners.single();val loser=1-winner
                        requireThat(results[loser].second in setOf("ref_conflict","cloud_account_busy","library_busy"),"unexpected_cas_race_error")
                        try {client.publish(attempts[loser]);SyncV2Codec.fail("two_cas_winners")}
                        catch(e:SyncV2CloudError){requireThat(e.code=="ref_conflict","cas_loser_not_stale")}
                        val replayRace=client.publish(attempts[winner])
                        requireThat(replayRace.getString("serverRevision")==results[winner].first!!.getString("serverRevision"),"duplicate_cas_winner")
                        record("concurrentCasWinnerRevision",replayRace.getString("serverRevision"));record("concurrentCasLoser","ref_conflict")
                        raceVerified=true
                    }else {
                        requireThat(winners.isEmpty() && results.all{it.second in setOf("ref_conflict","cloud_account_busy","library_busy")},"invalid_cas_race_result")
                        raceRetries++
                    }
                }
            }}finally{pool.shutdownNow()}
            requireThat(raceVerified,"cas_race_retry_exhausted");record("concurrentCasVerified",true);record("concurrentCasRetries",raceRetries)
            step("wrong-generation-rejection")
            val rejected=JSONArray();var generationConflicts=0
            for(wrong in listOf("1",SyncV2Codec.maxD64.toString())) {
                var refused=false
                repeat(20) {
                    if(!refused) {
                        val latest=client.negotiate();val accountNow=latest.getJSONObject("account")
                        requireThat(accountNow.getString("rollbackGeneration")=="0","p2_generation_changed")
                        val current=authors.header(SyncV2Wire.parse(client.raw(accountNow.getString("commitOid")),262144,true))
                        val stale=controlRequest(client,projection,signer,authors,latest,current.getString("treeRoot"),wrong)
                        try {client.publish(stale);SyncV2Codec.fail("backend_accepted_wrong_generation")}
                        catch(e:SyncV2CloudError) {
                            when(e.code) {"rollback_conflict"->{refused=true;rejected.put(wrong)};"ref_conflict"->generationConflicts++;else->throw e}
                        }
                        sync.readCurrent()
                        requireThat(projection.state().body.getString("rollbackGeneration")=="0","client_accepted_wrong_generation")
                    }
                }
                requireThat(refused,"generation_retry_exhausted")
            }
            record("wrongGenerationsRejected",rejected);record("generation", "0");record("generationCasConflicts",generationConflicts)
            record("postRollbackOldGenerationVerified",false);record("postRollbackDeferredTo","P3 authenticated body closure")
            val after=(SyncV2Codec.d64(accepted.getString("serverRevision"))-java.math.BigInteger.ONE).toString()
            var cursor=after;var matchingEvents=0
            var pages=0
            do {val events=client.events(cursor,0);val a=events.getJSONArray("events");for(i in 0 until a.length())if(a.getJSONObject(i).optString("commitOid")==commitOid)matchingEvents++
                cursor=events.getString("cursor");pages++;requireThat(pages<=16,"event_page_limit")
            }while(events.getBoolean("hasMore"))
            requireThat(matchingEvents==1,"duplicate_logical_publish_event");record("logicalPublishEvents",matchingEvents)
            record("routes",JSONObject(proxy.routes.mapValues{it.value.get()}));record("status","passed")
        }catch(t:Throwable) {
            val raw=t.message.orEmpty();val safe=if(Regex("[a-z][a-z0-9_]{0,80}").matches(raw))raw else t.javaClass.simpleName
            summary.put("status","failed").put("errorPhase",phase).put("errorCode",safe)
                .put("errorFrames",JSONArray(t.stackTrace.filter{it.className.startsWith("com.lightningstudio.watchrss.phone.syncv2")}.take(12).map{"${it.className.substringAfterLast('.')}::${it.methodName}:${it.lineNumber}"}))
                .put("routes",JSONObject(proxy?.routes?.mapValues{it.value.get()}?:emptyMap<String,Int>()));save()
            throw AssertionError("joint phase=$phase code=$safe")
        }finally {room?.close();proxy?.close();wrap.fill(0);local.deleteRecursively()}
    }
}
