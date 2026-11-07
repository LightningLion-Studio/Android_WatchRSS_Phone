package com.lightningstudio.watchrss.phone.syncv2

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Explicit opt-in to the authorized 18962 server. Uses a phone-owned fresh synthetic
 * account, including its own revocation victims. Never mutates the four shared fixtures. */
class SyncV2P4JointIntegrationTest {
    private fun id()=SyncV2Codec.hex(SyncV2Codec.random(16))
    @Test fun phoneRelayCustodyAndBackendRefusalMatrix() {
        val root=File(System.getenv("WATCHRSS_P4_PHONE_JOINT")?:error("explicit P4 fixture required"))
        val evidence=File(System.getenv("WRSS_P4_EVIDENCE")?:error("explicit evidence directory required"))
        val owner=SyncV2Wire.parse(File(root,".owner.json").readBytes())
        check(owner.getBoolean("phoneP4Owned") && owner.getInt("backendPort")==18962)
        val fixtures=listOf("phone","watch","mini","cli").associateWith{p->SyncV2Wire.parse(File(root,"$p.json").readBytes())}
        val phone=fixtures.getValue("phone");val watch=fixtures.getValue("watch")
        check(fixtures.values.all{it.getBoolean("syntheticOnly") && it.getString("baseUrl")=="http://127.0.0.1:18962"})
        val http=OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).callTimeout(10,TimeUnit.SECONDS).build()
        val request=Request.Builder().url("http://127.0.0.1:18962/functions/v1/cloud/bootstrap").post("{}".toRequestBody("application/json".toMediaType()))
        val headers=phone.getJSONObject("headers");for(k in headers.keys())request.header(k,headers.getString(k))
        val boot=http.newCall(request.build()).execute().use{check(it.code==200){"synthetic bootstrap failed"};SyncV2Wire.parse(it.body!!.bytes())}
        val trust=SyncV2ServiceTrust.fromBootstrap(phone.getString("accountId"),boot.getJSONObject("syncV2"),phone.getJSONObject("servicePin")){error("service pin mutation")}
        val contentKey=SyncV2Codec.unhex(phone.getJSONObject("crypto").getString("contentKeyHex"),32)
        val approved=boot.getJSONArray("devices").let{a->(0 until a.length()).map{a.getJSONObject(it)}.filter{it.isNull("revokedAt")}.map{it.getString("deviceId")}.toSet()}
        val scope=phone.getJSONObject("scope")
        fun client(platform:String):SyncV2CloudClient {val f=fixtures.getValue(platform);val h=f.getJSONObject("headers")
            val s=SyncV2CloudClient.Session(f.getString("userId"),f.getString("legacyDeviceId"),f.getString("legacyLibraryEpoch"),f.getString("legacyContentGeneration"),SyncV2Codec.scope(scope),f.getString("syncDeviceId"),h.getString("Authorization").removePrefix("Bearer "),"",if(h.has("x-watchrss-device-authorization"))h.getString("x-watchrss-device-authorization")else null)
            return SyncV2CloudClient("http://127.0.0.1:18962/functions/v1/sync/v2/".toHttpUrl(),{s},trust,true,http).also{it.capabilities()}
        }
        val cloud=client("phone");val authorClient=client("watch")
        val descriptor=cloud.descriptor();val capsule=SyncV2Wire.parse(cloud.recovery(descriptor.getString("recoveryOid")),8192,true)
        val identity=SyncV2Identity.recover(capsule,descriptor.getJSONObject("descriptor"),contentKey,phone.getString("accountId"),scope,descriptor.getString("descriptorOid"))
        val window=cloud.negotiate();val keys=cloud.signingKeys(window.getString("windowId"));val authors=cloud.authors(identity){it in approved}
        val bindings=keys.getJSONArray("bindings");for(i in 0 until bindings.length())authors.accept(bindings.getJSONObject(i),keys.getString("revocationRevision"))
        fun signer(platform:String):SyncV2Signing {val f=fixtures.getValue(platform);val k=f.getJSONObject("signingKey");return SyncV2Signing.fromPrivate(SyncV2Codec.unhex(k.getString("privateKeyHex"),32),f.getString("syncDeviceId"),k.getString("keyId"))}
        val phoneSigner=signer("phone");val authorSigner=signer("watch")
        val dir=Files.createTempDirectory(root.toPath(),"jvm-store-").toFile();val localKey=SyncV2Codec.random(32)
        val result=JSONObject().put("backendPort",18962).put("phoneOwnedSyntheticAccount",true).put("sharedFixturesMutated",false).put("physicalDevices",false)
        val errors=JSONObject()
        fun save(){evidence.mkdirs();File(evidence,"joint-summary.json").writeText(result.put("backendRejections",errors).toString(2)+"\n")}
        fun reject(name:String,code:String,block:()->Unit) {val failure=runCatching(block).exceptionOrNull();assertTrue("$name must return $code",failure is SyncV2CloudError && failure.code==code);errors.put(name,code);save()}
        fun mutate(operation:String) {
            val helper=listOf(File("src/test/python/syncv2_p4_fixture.py"),File("app/src/test/python/syncv2_p4_fixture.py")).first{it.isFile}.absolutePath
            val command=listOf("python3",helper,operation,"--environment","/home/wilf/data/OPPO_Develop/.codex-tmp/cloud-v2/integration-p4","--scratch",root.absolutePath)
            val p=ProcessBuilder(command).redirectErrorStream(true).start();p.inputStream.use{it.readBytes()};check(p.waitFor()==0){"owned synthetic fixture update failed"}
        }
        fun upload(oid:String,raw:ByteArray,channel:String="control") {
            val rid=id();val reservation=cloud.reserve(rid,listOf(oid to raw.size),channel).getString("reservationId")
            for(offset in raw.indices step 65536){val part=raw.copyOfRange(offset,minOf(raw.size,offset+65536));val r=cloud.put(oid,offset,reservation,id(),part);cloud.verifyReceipt(r.getJSONObject("receipt"),oid,raw.size.toLong(),(offset+part.size).toLong(),System.currentTimeMillis())}
            val r=cloud.complete(oid,reservation,id(),raw.size);cloud.verifyReceipt(r.getJSONObject("receipt"),oid,raw.size.toLong(),raw.size.toLong(),System.currentTimeMillis())
        }
        try {
            val store=SyncV2ContentStore(dir,identity.scopeJson,localKey)
            val tree=SyncV2Index.build(scope,emptyList());tree.objects.forEach{(oid,raw)->store.putObject(oid,raw)}
            val now=System.currentTimeMillis()
            val cb=JSONObject().put("version",2).put("scope",scope).put("authorDeviceId",authorSigner.deviceId).put("deviceSequence","1").put("previousOwnCommit",JSONObject.NULL).put("mergedParents",JSONArray())
                .put("treeRoot",tree.root).put("requiredFeatures",JSONArray()).put("createdAtMillis",now).put("observedRefs",JSONArray()).put("indexComplete",true).put("accountParent",JSONObject.NULL).put("rollbackGeneration","0")
                .put("operation",JSONObject().put("kind","normal").put("rollbackTarget",JSONObject.NULL).put("resolvesGeneration",JSONObject.NULL))
            val previousRef=window.getJSONArray("refs").let{refs->(0 until refs.length()).map{refs.getJSONObject(it)}.singleOrNull{it.getString("deviceId")==authorSigner.deviceId}}
            val previous=previousRef?.getString("commitOid");val ownRevision=previousRef?.getString("refRevision")?:"0"
            val floor=previous?.let{SyncV2Codec.d64(authors.header(SyncV2Wire.parse(cloud.raw(it),262144,true)).getString("deviceSequence"))}?:java.math.BigInteger.ZERO
            val watermark=File(root,"phone-joint-author-sequence.json")
            val reserved=if(watermark.exists())SyncV2Codec.d64(SyncV2Wire.parse(watermark.readBytes()).getString("reservedThrough"))else java.math.BigInteger.ZERO
            val sequence=(maxOf(floor,reserved,java.math.BigInteger.valueOf(System.currentTimeMillis()))+java.math.BigInteger.ONE).toString()
            java.io.FileOutputStream(watermark).use{it.write(SyncV2LocalProtocol.bytes(JSONObject().put("reservedThrough",SyncV2Codec.next(SyncV2Codec.next(sequence)))));it.fd.sync()}
            val accountState=window.getJSONObject("account");val priorAccount=if(accountState.isNull("commitOid"))null else accountState.getString("commitOid")
            cb.put("previousOwnCommit",previous?:JSONObject.NULL).put("deviceSequence",sequence).put("accountParent",priorAccount?:JSONObject.NULL).put("rollbackGeneration",accountState.get("rollbackGeneration"))
            val signed=authorSigner.commit(cb);val oid=SyncV2Codec.oid("commit",cb);val rid=id();val ref="refs/devices/${authorSigner.deviceId}/index"
            val device=SyncV2CloudClient.refUpdate(authorSigner,scope,ref,previous,ownRevision,oid,rid,now)
            val account=SyncV2CloudClient.refUpdate(authorSigner,scope,"refs/account/current",priorAccount,accountState.getString("revision"),oid,rid,now)
            val grantBody=JSONObject().put("version",2).put("scope",scope).put("authorDeviceId",authorSigner.deviceId).put("relayDeviceId",phoneSigner.deviceId).put("ref",ref).put("commitOid",oid)
                .put("objectOids",JSONArray(listOf(oid,tree.root).sorted())).put("expiresAtMillis",now+600000).put("grantId",id())
            fun grant(body:JSONObject)=authorSigner.signed("wrss.sync.v2/relay-grant-sign",body)
            val validGrant=grant(grantBody)
            val relay=SyncV2Relay(identity,authors,cloud,store,true)
            relay.forward(signed,device,validGrant,account,now)
            assertEquals(oid,cloud.account().getJSONObject("account").getString("commitOid"));result.put("phoneSessionPublishesOriginalWatchAuthor",true)
            val before=cloud.account().getJSONObject("account").getString("revision")
            relay.forward(signed,device,validGrant,account,now)
            assertEquals(before,cloud.account().getJSONObject("account").getString("revision"));result.put("relayIdempotentAndOriginalSignatures",true)
            // Each rejection reaches the real backend, rather than stopping at the local guard.
            fun stage(g:JSONObject)=cloud.stage(JSONObject(device.toString()).put("relayGrant",g))
            reject("expired-grant","relay_denied"){stage(grant(JSONObject(grantBody.toString()).put("expiresAtMillis",now-60000).put("grantId",id())))}
            reject("long-grant","relay_denied"){stage(grant(JSONObject(grantBody.toString()).put("expiresAtMillis",now+700000).put("grantId",id())))}
            reject("wrong-grant-scope","scope_mismatch"){stage(grant(JSONObject(grantBody.toString()).put("scope",JSONObject(scope.toString()).put("contentGeneration","2")).put("grantId",id())))}
            reject("wrong-grant-relay","relay_denied"){stage(grant(JSONObject(grantBody.toString()).put("relayDeviceId",fixtures.getValue("cli").getString("syncDeviceId")).put("grantId",id())))}
            reject("wrong-grant-signature","signature_invalid"){stage(JSONObject(validGrant.toString()).put("signature",phoneSigner.sign(byteArrayOf(1))))}
            val unsupported=JSONObject(cb.toString()).put("deviceSequence",SyncV2Codec.next(sequence)).put("previousOwnCommit",oid).put("accountParent",oid).put("requiredFeatures",JSONArray(listOf("unknown-feature.v1")))
            val unsupportedOid=SyncV2Codec.oid("commit",unsupported)
            val unsupportedSigned=JSONObject().put("body",unsupported).put("signature",authorSigner.sign(SyncV2Codec.lp(SyncV2Codec.utf8("wrss.sync.v2/commit-sign"),SyncV2Codec.utf8(unsupportedOid))))
            upload(unsupportedOid,SyncV2LocalProtocol.bytes(unsupportedSigned))
            val unsupportedUpdate=SyncV2CloudClient.refUpdate(authorSigner,scope,ref,oid,SyncV2Codec.next(ownRevision),unsupportedOid,id(),now)
            val unsupportedGrant=grant(JSONObject(grantBody.toString()).put("commitOid",unsupportedOid).put("objectOids",JSONArray(listOf(unsupportedOid,tree.root).sorted())).put("grantId",id()))
            reject("missing-feature","unsupported_feature"){cloud.stage(JSONObject(unsupportedUpdate.toString()).put("relayGrant",unsupportedGrant))}
            // Correct product AEAD/recipe/ordered refs, retained locally before holder signs.
            val exporter=SyncV2ContentExport(identity,contentKey,1,store)
            val binding=SyncV2ContentExport.Binding("11".repeat(32),id(),exporter.authenticationBinding)
            val source=object:SyncV2ContentExport.Source {override val size=270000L;override val version="p4-joint-original";override fun read(offset:Long,maxBytes:Int)=ByteArray(maxBytes){((it+offset)%251).toByte()}}
            while(!exporter.step(binding,source)){}
            val body=exporter.descriptor(binding)!!;val recipe=body.getJSONObject("recipe");val recipeOid=body.getString("recipeOid");val blobs=JSONArray();val blocks=recipe.getJSONArray("blocks")
            for(i in 0 until blocks.length()){val b=blocks.getJSONObject(i).getString("oid");blobs.put(b);upload(b,store.objectBytes(b),"content")};upload(recipeOid,store.objectBytes(recipeOid),"content")
            val refs=SyncV2ContentCodec.refs(identity,phoneSigner,recipeOid,blobs);cloud.recipeRefs(refs)
            val w=cloud.negotiate().getString("windowId");val mapping=cloud.reference(recipeOid,w).apply{remove("version")};val raw=cloud.raw(mapping.getString("refsOid"),w)
            val sid=id();val memberIds=fixtures.values.map{it.getString("syncDeviceId")}.toSet()
            fun local(self:String)=SyncV2LocalSession(scope,sid,self,{contentKey},{if(it in memberIds)"active" else "unknown"},{true},{},SyncV2LocalProtocol.Gates(true))
            val a=local(phoneSigner.deviceId);val b=local(authorSigner.deviceId);val ha=a.hello();val hb=b.hello();a.receiveHello(hb);b.receiveHello(ha);a.receiveProofBody(b.proofBody());b.receiveProofBody(a.proofBody());val switch=id();a.acceptReady(b.prepare(a.beginSwitch(switch)));b.commitRequest(JSONObject().put("switchId",switch));b.finalAckWritten(switch);a.acceptCommit(JSONObject().put("switchId",switch).put("committed",true))
            SyncV2LocalObjects(a,store,contentKey={contentKey}).registerRefs(identity,authors,recipeOid,blobs,mapping,raw)
            val pinBody=JSONObject().put("version",2).put("scope",scope).put("pinId",id()).put("holderDeviceId",phoneSigner.deviceId).put("claimantDeviceId",authorSigner.deviceId).put("entityKey",binding.entity).put("bodyRevision",binding.revision)
                .put("recipeOid",recipeOid).put("blobOidsHash",SyncV2Codec.hash(blobs)).put("createdAtMillis",System.currentTimeMillis())
            val custody=SyncV2Custody(identity,store,phoneSigner,authors){contentKey};val pin=custody.persist(a,pinBody)
            val first=cloud.custody(pin);assertEquals(SyncV2Codec.canonical(first),SyncV2Codec.canonical(cloud.custody(pin)))
            val retrieved=authorClient.custody(pinBody.getString("pinId"));assertEquals("active",retrieved.getString("state"));SyncV2Custody.verify(JSONObject().put("body",retrieved.get("body")).put("signature",retrieved.get("signature")),scope,authors)
            result.put("phoneLocalPinBeforeBackendReceipt",true).put("custodyBackendReadAndRetry",true)
            reject("custody-overwrite","idempotency_conflict"){cloud.custody(phoneSigner.signed("wrss.sync.v2/custody-pin-sign",JSONObject(pinBody.toString()).put("createdAtMillis",pinBody.getLong("createdAtMillis")+1)))}
            reject("custody-wrong-digest","hash_mismatch"){cloud.custody(phoneSigner.signed("wrss.sync.v2/custody-pin-sign",JSONObject(pinBody.toString()).put("pinId",id()).put("blobOidsHash","ff".repeat(32))))}
            reject("custody-missing-recipe","closure_incomplete"){cloud.custody(phoneSigner.signed("wrss.sync.v2/custody-pin-sign",JSONObject(pinBody.toString()).put("pinId",id()).put("recipeOid","recipe:sha256:"+"ff".repeat(32))))}
            reject("custody-wrong-holder-signature","signature_invalid"){cloud.custody(authorSigner.signed("wrss.sync.v2/custody-pin-sign",JSONObject(pinBody.toString()).put("pinId",id())))}
            assertEquals(SyncV2Codec.canonical(first.get("result")),SyncV2Codec.canonical(cloud.custody(pin).get("result")))
            mutate("remove-author-cloud-key");reject("author-without-cloud-key","relay_denied"){stage(validGrant)}
            mutate("revoke-author");reject("revoked-author","device_revoked"){stage(validGrant)}
            mutate("revoke-relay");reject("revoked-relay","device_revoked"){stage(validGrant)}
            result.put("status","passed").put("backendSourceCommit","6c26228113e65e028e3e753c426259c445098462");save()
        }finally{contentKey.fill(0);localKey.fill(0);dir.deleteRecursively();http.connectionPool.evictAll();http.dispatcher.executorService.shutdown()}
    }
}
