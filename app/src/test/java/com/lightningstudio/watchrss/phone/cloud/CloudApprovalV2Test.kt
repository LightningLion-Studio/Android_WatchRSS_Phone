package com.lightningstudio.watchrss.phone.cloud

import com.lightningstudio.watchrss.phone.account.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CloudApprovalV2Test {
    private val session = PhoneAccountSession("u","***","token","",Long.MAX_VALUE)
    private val requester = CloudProtocolV2.signingKey()
    private val nonce = ByteArray(32) { 7 }
    private fun target() = JSONObject().put("requestId","r1").put("deviceId","new").put("displayName","new device")
        .put("publicKeySpki",requester.public.encoded.base64()).put("commitment",CloudProtocolV2.approvalCommitment(requester.public.encoded,nonce))
    private fun client(handle: (Request,JSONObject) -> JSONObject) = PhoneCloudClient(AccountEnvironment(backendBaseUrl="https://test.invalid"),http=OkHttpClient.Builder().addInterceptor { chain ->
        val req=chain.request();val buffer=okio.Buffer();req.body?.writeTo(buffer)
        val body=if(buffer.size>0) JSONObject(buffer.readUtf8()) else JSONObject()
        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("test")
            .body(handle(req,body).toString().toResponseBody("application/json".toMediaType())).build()
    }.build()).also { it.useEpoch(session,"epoch") }
    @Test fun discoverAndAcceptNeverWrapKeysAndUserConfirmationSignsExactPayload() = runBlocking {
        var accepted=JSONObject(); var delivered=JSONObject(); var wraps=0
        val client=client { req,body ->
            when {
                req.url.encodedPath.endsWith("/pending") -> JSONObject().put("requests",JSONArray().put(target()))
                req.url.encodedPath.endsWith("/accept") -> { accepted=body;JSONObject().put("status","accepted") }
                req.url.encodedPath.endsWith("/deliver") -> { delivered=body;JSONObject().put("status","delivered") }
                else -> target().put("status","revealed").put("requesterNonce",nonce.base64())
            }
        }
        val flow=CloudApprovalV2(client,"old",{session},{requester.public.encoded.base64()},{},{_,_,_->wraps++; "{\"exact\":  true}"},{_,_->})
        assertEquals(1,flow.discover().size);assertEquals(0,wraps)
        flow.start(target());assertEquals(0,wraps);assertEquals(CloudApprovalPhase.WAITING,flow.state.value.phase)
        flow.poll();assertEquals(0,wraps);assertEquals(8,flow.state.value.code!!.length)
        val t=CloudProtocolV2.transcript("u","epoch","r1","new",requester.public.encoded,"old",accepted.getString("approverSpki").base64Bytes(),nonce,accepted.getString("approverNonce").base64Bytes())
        assertEquals(CloudProtocolV2.code(t),flow.state.value.code)
        flow.confirmMatches();assertEquals(1,wraps);assertEquals(CloudApprovalPhase.DELIVERED,flow.state.value.phase)
        assertEquals("{\"exact\":  true}",delivered.getString("payload"))
        assertTrue(CloudProtocolV2.verify(accepted.getString("approverSpki").base64Bytes(),t,delivered.getString("payload"),delivered.getString("signature").base64Bytes()))
        try { flow.confirmMatches();fail("terminal state reused") } catch (_:IllegalStateException) { }
    }
    @Test fun wrongCommitmentCannotReachCodeOrDelivery() = runBlocking {
        var wraps=0
        val c=client { req,_ -> if(req.method=="GET") target().put("status","revealed").put("requesterNonce",ByteArray(32).base64()) else JSONObject() }
        val f=CloudApprovalV2(c,"old",{session},{requester.public.encoded.base64()},{},{_,_,_->wraps++;"{}"},{_,_->})
        f.start(target())
        try { f.poll();fail("commitment accepted") } catch (_:IllegalArgumentException) { }
        assertEquals(0,wraps);assertNull(f.state.value.code)
        f.reject();assertEquals(CloudApprovalPhase.CANCELLED,f.state.value.phase)
    }
    @Test fun requesterPinsApproverAndRejectsTamperedPayloadBeforeSaving() = runBlocking {
        val signing=CloudProtocolV2.signingKey();val approverNonce=ByteArray(32) { 9 };var n=ByteArray(0);var polls=0;var saved=0
        val c=client { req,body -> when {
            req.url.encodedPath.endsWith("/v2") -> JSONObject().put("requestId","r1")
            req.url.encodedPath.endsWith("/reveal") -> { n=body.getString("requesterNonce").base64Bytes();JSONObject() }
            else -> {
                polls++
                JSONObject().put("status",if(polls==1)"accepted" else "delivered").put("approverDeviceId","old")
                    .put("approverSpki",signing.public.encoded.base64()).put("approverNonce",approverNonce.base64()).apply {
                        if(polls>1) {
                            val t=CloudProtocolV2.transcript("u","epoch","r1","new",requester.public.encoded,"old",signing.public.encoded,n,approverNonce)
                            put("payload","{} ");put("signature",CloudProtocolV2.sign(signing.private,t,"{}").base64())
                        }
                    }
            }
        } }
        val f=CloudApprovalV2(c,"new",{session},{requester.public.encoded.base64()},{},{_,_,_->error("not approver")},{_,_->saved++})
        f.begin();f.poll();assertEquals(CloudApprovalPhase.COMPARE,f.state.value.phase)
        f.poll();assertEquals(0,saved) // delivered bytes may be cached before local confirmation.
        try { f.confirmMatches();fail("tampered payload") } catch (_:IllegalArgumentException) { }
        assertEquals(0,saved)
    }
    @Test fun requesterRequiresOwnConfirmationEvenForValidSignedDelivery() = runBlocking {
        val signing=CloudProtocolV2.signingKey();val approverNonce=ByteArray(32){9};var n=ByteArray(0);var polls=0;var saved=0
        val c=client { req,body -> when {
            req.url.encodedPath.endsWith("/v2") -> JSONObject().put("requestId","r1")
            req.url.encodedPath.endsWith("/reveal") -> { n=body.getString("requesterNonce").base64Bytes();JSONObject() }
            else -> {
                polls++
                JSONObject().put("status",if(polls==1)"accepted" else "delivered").put("approverDeviceId","old")
                    .put("approverSpki",signing.public.encoded.base64().trimEnd('=')) // R9 accepts the same DER with legacy padding.
                    .put("approverNonce",approverNonce.base64()).apply { if(polls>1) {
                        val t=CloudProtocolV2.transcript("u","epoch","r1","new",requester.public.encoded,"old",signing.public.encoded,n,approverNonce)
                        put("payload","{}");put("signature",CloudProtocolV2.sign(signing.private,t,"{}").base64())
                    } }
            }
        } }
        val f=CloudApprovalV2(c,"new",{session},{requester.public.encoded.base64()},{},{_,_,_->error("not approver")},{_,_->saved++})
        f.begin();f.poll();f.poll();assertEquals(0,saved);assertEquals(CloudApprovalPhase.COMPARE,f.state.value.phase)
        f.confirmMatches();assertEquals(1,saved);assertEquals(CloudApprovalPhase.DELIVERED,f.state.value.phase)
    }
    @Test fun accountRefreshIsAllowedButDifferentOwnerStopsAnApproval() = runBlocking {
        var current=session
        val c=client { _,_->JSONObject().put("status","pending").put("requestId","r1") }
        val f=CloudApprovalV2(c,"new",{current},{requester.public.encoded.base64()},{},{_,_,_->"{}"},{_,_->})
        f.begin();current=current.copy(accessToken="refreshed");f.poll()
        current=current.copy(userId="other")
        try { f.poll();fail("wrong owner") } catch (_:IllegalStateException) { }
    }
}
