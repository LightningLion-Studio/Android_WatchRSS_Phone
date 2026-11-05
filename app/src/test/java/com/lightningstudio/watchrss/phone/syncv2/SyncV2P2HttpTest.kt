package com.lightningstudio.watchrss.phone.syncv2

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SyncV2P2HttpTest {
    private val servers=mutableListOf<MockWebServer>()
    private val scope=SyncV2Fixtures.identity().scope
    private val account="12".repeat(16)
    private val session=SyncV2CloudClient.Session("12121212-1212-1212-1212-121212121212","old-approved-phone","11111111-1111-1111-1111-111111111111","22222222-2222-2222-2222-222222222222",SyncV2Codec.scope(scope),"aa".repeat(16),"synthetic-bearer","synthetic-anon")
    private fun signer()=SyncV2Signing.fromPrivate(ByteArray(31)+byteArrayOf(1),"aa".repeat(16),"ab".repeat(16))
    private fun bootstrap():JSONObject {
        val algorithm=AlgorithmIdentifier(ASN1ObjectIdentifier("1.2.840.10045.2.1"),ASN1ObjectIdentifier("1.2.840.10045.3.1.7"))
        val spki=SubjectPublicKeyInfo(algorithm,SyncV2Codec.unhex(signer().publicHex,65)).encoded
        return JSONObject().put("algorithm","ES256").put("receiptKeyId","cd".repeat(16)).put("receiptSigningPublicKey",SyncV2Codec.b64(spki))
    }
    private fun trust()=SyncV2ServiceTrust.fromBootstrap(account,bootstrap(),null){}
    private fun dto()=JSONObject().put("version",2).put("scope",scope)
    private fun caps()=dto().put("minVersion",2).put("maxVersion",2).put("maxPartBytes",65536).put("historyDays",7).put("features",JSONArray())
        .put("receiptKeyId",bootstrap().getString("receiptKeyId")).put("receiptSigningPublicKey",bootstrap().getString("receiptSigningPublicKey"))
        .put("flags",JSONObject().put("read",true).put("indexWrite",true).put("contentWrite",false).put("relay",false).put("gc",false).put("historyRestore",false))
    private fun response(value:JSONObject)=MockResponse().setHeader("Content-Type","application/json").setBody(value.toString())
    private fun server(fn:(RecordedRequest)->MockResponse):MockWebServer=MockWebServer().also{s->s.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest)=if(request.requestUrl!!.encodedPath.endsWith("capabilities"))response(caps())else fn(request)};s.start();servers.add(s)}
    private fun client(s:MockWebServer,owner:()->SyncV2CloudClient.Session={session},enabled:Boolean=true)=SyncV2CloudClient(s.url("/functions/v1/sync/v2/"),owner,trust(),enabled)
    private fun code(action:()->Unit):String?=try{action();null}catch(e:SyncV2CloudError){e.code}catch(e:IllegalStateException){e.message}
    @After fun close(){servers.forEach{it.shutdown()}}
    @Test fun c1OldDeviceHeaderAndC2SixRawHeadersUseExactFrozenNames() {
        val s=server {response(dto().put("oid","tree:sha256:"+"1".repeat(64)).put("durableOffset",3))};val c=client(s);c.capabilities();s.takeRequest()
        val oid="tree:sha256:"+"1".repeat(64);c.put(oid,0,"22".repeat(16),"33".repeat(16),byteArrayOf(1,2,3))
        val request=s.takeRequest();assertEquals("old-approved-phone",request.getHeader("x-watchrss-cloud-device-id"));assertEquals("application/octet-stream",request.getHeader("Content-Type"))
        val expected=mapOf("repo-id" to scope.getString("repoId"),"library-epoch" to "1","content-generation" to "2","reservation-id" to "22".repeat(16),"request-id" to "33".repeat(16),"part-sha256" to SyncV2Codec.hex(SyncV2Codec.sha(byteArrayOf(1,2,3))))
        for((k,v)in expected)assertEquals(v,request.getHeader("x-watchrss-sync-$k"))
        assertEquals(session.legacyEpoch,request.getHeader("x-watchrss-library-epoch"));assertEquals(session.legacyGeneration,request.getHeader("x-watchrss-content-generation"))
        assertEquals(account,session.accountId)
    }
    @Test fun c1SeparateDeviceAuthorizationHeaderIsPreserved() {
        val s=server{response(dto())};val c=client(s,{session.copy(deviceAuthorization="synthetic-device-session-proof")});c.capabilities()
        val r=s.takeRequest();assertEquals("synthetic-device-session-proof",r.getHeader("x-watchrss-device-authorization"));assertEquals(session.legacyDeviceId,r.getHeader("x-watchrss-cloud-device-id"))
    }
    @Test fun exactMutationSchemasDoNotGainTopLevelVersionOrSignedWrapperScope() {
        val s=server{r->if(r.requestUrl!!.encodedPath.endsWith("negotiate"))response(dto().put("windowId","11".repeat(16)).put("expiresAtMillis",1000).put("serverRevision","0").put("historyFloorRevision","0").put("account",JSONObject().put("commitOid",JSONObject.NULL).put("revision","0").put("rollbackGeneration","0")))else response(dto())}
        val c=client(s);c.capabilities();s.takeRequest();c.negotiate()
        val negotiation=SyncV2Wire.parse(s.takeRequest().body.readByteArray());assertEquals(setOf("scope","knownRefs","features"),negotiation.keys().asSequence().toSet())
        val request=JSONObject().put("account",JSONObject().put("body",JSONObject().put("requestId","11".repeat(16)))).put("device",JSONObject()).put("relayGrant",JSONObject.NULL)
        c.publish(request);assertEquals(SyncV2Codec.canonical(request),s.takeRequest().body.readUtf8())
    }
    @Test fun oldEpochOrAccountChangeEndsRoundAndDefaultGateIsClosed() {
        var owner=session;val s=server {response(dto())};val c=client(s,{owner});c.capabilities();s.takeRequest()
        owner=owner.copy(legacyGeneration="33333333-3333-3333-3333-333333333333")
        assertEquals("scope_mismatch",code{c.negotiate()});assertEquals(1,s.requestCount)
        assertEquals("feature_disabled",code{client(s,enabled=false).capabilities()})
        assertEquals(1,s.requestCount)
        assertEquals("isolated_backend_required",code{SyncV2CloudClient(okhttp3.HttpUrl.Builder().scheme("https").host("example.invalid").encodedPath("/functions/v1/sync/v2/").build(),{session},trust(),true)})
    }
    @Test fun c3BootstrapPinAndCapabilitiesMustMatchAndNeverAutomaticallyRotate() {
        val initial=bootstrap();var pinned:JSONObject?=null;var writes=0
        SyncV2ServiceTrust.fromBootstrap(account,initial,pinned){pinned=it;writes++}
        val restored=SyncV2ServiceTrust.fromBootstrap(account,initial,pinned){writes++};restored.capabilities(caps());assertEquals(1,writes)
        val changed=JSONObject(initial.toString()).put("receiptKeyId","ef".repeat(16))
        assertEquals("service_key_changed",code{SyncV2ServiceTrust.fromBootstrap(account,changed,pinned){writes++}});assertEquals(1,writes)
        val badCaps=caps().put("receiptKeyId","ee".repeat(16));assertEquals("service_key_changed",code{restored.capabilities(badCaps)})
        assertEquals("service_key_changed",code{restored.capabilities(caps())})
        assertEquals("云同步服务密钥变化，请更新应用",SyncV2CloudError("service_key_changed").display)
    }
    @Test fun c4SigningRegistrationSendsOriginalTripleBytesWithoutWireExtensions() {
        val s=server{response(dto().put("registered",true))};val client=client(s);client.capabilities();s.takeRequest()
        val binding=SyncV2Fixtures.identity().signingBinding(signer(),100,"0");client.registerSigningKey(binding)
        assertEquals(SyncV2Codec.canonical(binding),s.takeRequest().body.readUtf8())
    }
    @Test fun c4PreservesRegistrationAndAuthenticatesServiceSignedLegacyDeviceMapping() {
        val identity=SyncV2Fixtures.identity();val trust=SyncV2ServiceTrust.fromBootstrap(identity.accountId,bootstrap(),null){}
        val original=identity.signingBinding(signer(),100,"0")
        val mapping=JSONObject().put("scope",scope).put("deviceId",signer().deviceId).put("keyId",signer().keyId).put("legacyDeviceId","old-approved-phone").put("approvalRevision","9007199254740993")
        fun binding(m:JSONObject=mapping)=JSONObject(original.toString()).put("deviceMapping",signer().signed("wrss.sync.v2/device-mapping",m))
        val authors=SyncV2Authors(identity,trust){it=="old-approved-phone"};authors.accept(binding(),"1")
        assertEquals(signer().publicHex,authors.publicKey(signer().deviceId))
        for(key in listOf("body","bindingProof","possessionSignature"))assertEquals(SyncV2Codec.canonical(original.get(key)),SyncV2Codec.canonical(binding().get(key)))
        val badScope=JSONObject(mapping.toString()).put("scope",JSONObject(scope.toString()).put("libraryEpoch","2"))
        assertEquals("authentication_required",code{SyncV2Authors(identity,trust){true}.accept(binding(badScope),"1")})
        assertEquals("device_revoked",code{SyncV2Authors(identity,trust){false}.accept(binding(),"1")})
        val forged=binding();forged.getJSONObject("deviceMapping").put("signature",SyncV2Codec.b64(ByteArray(64)))
        assertEquals("signature_invalid",code{SyncV2Authors(identity,trust){true}.accept(forged,"1")})
        val wrongDomain=JSONObject(original.toString()).put("deviceMapping",signer().signed("wrss.sync.v2/wrong-domain",mapping))
        assertEquals("signature_invalid",code{SyncV2Authors(identity,trust){true}.accept(wrongDomain,"1")})
    }
    @Test fun receiptAuthenticatesExactScopePrefixAndExpiration() {
        val oid="tree:sha256:"+"1".repeat(64)
        val body=JSONObject().put("version",2).put("scope",scope).put("oid",oid).put("totalBytes",100).put("durableOffset",64)
            .put("storeId","11".repeat(16)).put("receiptRevision","9007199254740993").put("createdAtMillis",100).put("expiresAtMillis",1000)
        val receipt=signer().signed("wrss.sync.v2/object-receipt-sign",body);trust().receipt(receipt,scope,oid,100,64,200)
        assertEquals("invalid_schema",code{trust().receipt(receipt,scope,oid,100,100,200)})
        assertEquals("replay_detected",code{trust().receipt(receipt,scope,oid,100,64,1000)})
        val bad=JSONObject(receipt.toString()).put("signature",SyncV2Codec.b64(ByteArray(64)))
        assertEquals("signature_invalid",code{trust().receipt(bad,scope,oid,100,64,200)})
    }
    @Test fun oversizedBatchFallsBackToRawGetOnceAndChecksEtag() {
        val oid="state-refs:sha256:"+"1".repeat(64);val calls=mutableListOf<String>()
        val s=server {r->calls.add(r.requestUrl!!.encodedPath);if(r.method=="POST")response(dto().put("objects",JSONArray()).put("nextOids",JSONArray().put(oid)))else {assertEquals("11".repeat(16),r.requestUrl!!.queryParameter("windowId"));MockResponse().setHeader("ETag",oid).setBody("raw-control")}}
        val c=client(s);c.capabilities();assertArrayEquals("raw-control".toByteArray(),c.objects("11".repeat(16),listOf(oid)).getValue(oid));assertEquals(2,calls.size)
    }
    @Test fun busyAndTransportAndUpgradeAreNotAuthorizationFailureOrEmptyLibrary() {
        for(error in listOf("cloud_account_busy","library_busy","upgrade_required","object_missing","history_expired")) {
            val s=server {response(JSONObject().put("error",JSONObject().put("code",error).put("retryable",false).put("retryAfterMillis",JSONObject.NULL).put("details",JSONObject()))).setResponseCode(if(error=="upgrade_required")426 else 409)}
            val c=client(s);c.capabilities();assertEquals(error,code{c.negotiate()});assertEquals(2,s.requestCount)
            assertFalse(SyncV2CloudError(error).display.contains("授权未完成"))
        }
    }
    @Test fun lostPublishResponseQueriesIdempotencyWithoutSecondLogicalPublish() {
        val logic=AtomicInteger();val requests=AtomicInteger();val answer=dto().put("account",JSONObject().put("commitOid","commit:sha256:"+"1".repeat(64)).put("revision","9007199254740993").put("rollbackGeneration","0"))
        val s=server {r->if(r.requestUrl!!.encodedPath.endsWith("account/publish")){logic.incrementAndGet();MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST)}else {requests.incrementAndGet();response(dto().put("status","succeeded").put("response",answer))}}
        val c=client(s);c.capabilities();val req=JSONObject().put("account",JSONObject().put("body",JSONObject().put("requestId","11".repeat(16))))
        val result=c.publish(req);assertEquals("9007199254740993",result.getJSONObject("account").getString("revision"));assertEquals(1,logic.get());assertEquals(1,requests.get())
    }
    @Test fun concurrentSignedPublishCasAndRetryProduceOneLogicalCommitWithExactD64() {
        val source=SyncV2Fixtures.vector("account-cas").getJSONArray("cases").getJSONObject(0).getJSONObject("input")
        val old=source.getString("expectedAccountOid");val next=source.getString("newOid");val revision="9007199254740992"
        val responses=mutableMapOf<String,JSONObject>();val hashes=mutableMapOf<String,String>();var logical=0;var current=old
        val lock=Any()
        val s=server {r->synchronized(lock) {
            val request=SyncV2Wire.parse(r.body.readByteArray());val accountUpdate=request.getJSONObject("account");val deviceUpdate=request.getJSONObject("device")
            for(update in listOf(accountUpdate,deviceUpdate))assertTrue(SyncV2Identity.verifySignature(signer().publicHex,SyncV2Codec.lp(SyncV2Codec.utf8("wrss.sync.v2/ref-update-sign"),SyncV2Codec.utf8(SyncV2Codec.canonical(update.getJSONObject("body")))),update.getString("signature")))
            val b=accountUpdate.getJSONObject("body");val id=b.getString("requestId");val hash=SyncV2Codec.hash(request)
            fun rejected(code:String)=response(JSONObject().put("error",JSONObject().put("code",code).put("retryable",code=="ref_conflict").put("retryAfterMillis",JSONObject.NULL).put("details",JSONObject()))).setResponseCode(409)
            if(responses.containsKey(id)) {if(hashes[id]!=hash)rejected("idempotency_conflict")else response(responses.getValue(id))}
            else if(b.getString("expectedOid")!=current || b.getString("expectedRevision")!=revision)rejected("ref_conflict")
            else {
                current=next;logical++
                val result=dto().put("account",JSONObject().put("commitOid",next).put("revision",SyncV2Codec.next(revision)).put("rollbackGeneration","0"))
                    .put("device",JSONObject().put("ref","refs/devices/${signer().deviceId}/index").put("commitOid",next).put("revision",SyncV2Codec.next(revision))).put("serverRevision",SyncV2Codec.next(revision))
                responses[id]=result;hashes[id]=hash;response(result)
            }
        }}
        val c=client(s);c.capabilities()
        fun request(id:String)=JSONObject().put("account",SyncV2CloudClient.refUpdate(signer(),scope,"refs/account/current",old,revision,next,id,100))
            .put("device",SyncV2CloudClient.refUpdate(signer(),scope,"refs/devices/${signer().deviceId}/index",old,revision,next,id,100)).put("relayGrant",JSONObject.NULL)
        val first=request("11".repeat(16));val second=request("22".repeat(16));val pool=Executors.newFixedThreadPool(2)
        try {
            val a=pool.submit<String>{code{c.publish(first)}?:"success"};val b=pool.submit<String>{code{c.publish(second)}?:"success"}
            assertEquals(setOf("success","ref_conflict"),setOf(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS)))
            val winner=if(responses.containsKey("11".repeat(16)))first else second
            assertEquals("9007199254740993",c.publish(winner).getJSONObject("account").getString("revision"));assertEquals(1,logical)
        }finally{pool.shutdownNow()}
    }
    @Test fun longEventsDoesNotHoldControlOrAuthorizationPollingLock() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val finished=CountDownLatch(1)
        fun window()=dto().put("windowId","11".repeat(16)).put("expiresAtMillis",1000).put("serverRevision","9007199254740993").put("historyFloorRevision","0")
            .put("account",JSONObject().put("commitOid",JSONObject.NULL).put("revision","0").put("rollbackGeneration","0"))
        val s=server {r->if(r.requestUrl!!.encodedPath.endsWith("events")){entered.countDown();release.await(5,TimeUnit.SECONDS);response(window().put("cursor","9007199254740993"))}else{finished.countDown();response(window())}}
        val c=client(s);c.capabilities();val pool=Executors.newFixedThreadPool(2)
        try {val events=pool.submit<JSONObject>{c.events("9007199254740992")};assertTrue(entered.await(2,TimeUnit.SECONDS));val fast=pool.submit<JSONObject>{c.negotiate()};assertTrue(finished.await(2,TimeUnit.SECONDS));assertFalse(events.isDone);fast.get(2,TimeUnit.SECONDS);release.countDown();events.get(2,TimeUnit.SECONDS)}finally{release.countDown();pool.shutdownNow()}
    }
}
