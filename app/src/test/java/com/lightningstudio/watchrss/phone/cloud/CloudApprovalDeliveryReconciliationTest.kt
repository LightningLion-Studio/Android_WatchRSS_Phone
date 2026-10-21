package com.lightningstudio.watchrss.phone.cloud

import com.lightningstudio.watchrss.phone.account.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class CloudApprovalDeliveryReconciliationTest {
    private val session = PhoneAccountSession("u","***","token","",Long.MAX_VALUE)
    private val requester = CloudProtocolV2.signingKey()
    private val nonce = ByteArray(32) { 7 }
    private fun target() = JSONObject().put("requestId","r1").put("deviceId","new")
        .put("publicKeySpki",requester.public.encoded.base64()).put("commitment",CloudProtocolV2.approvalCommitment(requester.public.encoded,nonce))
    private fun client(handle:(Request,JSONObject)->Pair<Int,JSONObject>) = PhoneCloudClient(AccountEnvironment(backendBaseUrl="https://test.invalid"),
        http=OkHttpClient.Builder().addInterceptor { chain ->
            val req=chain.request();val buffer=okio.Buffer();req.body?.writeTo(buffer)
            val (code,body)=handle(req,if(buffer.size>0)JSONObject(buffer.readUtf8())else JSONObject())
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("test")
                .body(body.toString().toResponseBody("application/json".toMediaType())).build()
        }.build()).also { it.useEpoch(session,"epoch") }

    @Test fun lostDeliverResponseGetsSameApprovalAndCompletesWithoutWrappingOrPostingAgain() = runBlocking {
        val paths=mutableListOf<String>();var committed=false;var wraps=0;var posts=0
        val c=client { req,_ ->
            paths+=req.url.encodedPath
            if(req.url.encodedPath.endsWith("/deliver")) { posts++;committed=true;throw IOException("response lost") }
            200 to target().put("status",if(committed)"delivered"else"revealed").put("requesterNonce",nonce.base64())
        }
        val f=CloudApprovalV2(c,"old",{session},{requester.public.encoded.base64()},{},{_,_,_->wraps++;"{}"},{_,_->})
        f.start(target());f.poll();assertEquals(0,wraps);f.confirmMatches()
        assertEquals(CloudApprovalPhase.DELIVERED,f.state.value.phase)
        assertEquals(1,wraps);assertEquals(1,posts)
        assertEquals(listOf("/functions/v1/cloud/approvals/v2/r1/deliver","/functions/v1/cloud/approvals/v2/r1"),paths.takeLast(2))
        assertThrows(IllegalStateException::class.java) { runBlocking { f.confirmMatches() } }
        assertEquals(1,posts)
    }
    @Test fun failedDeliverWithStillRevealedApprovalNeverReportsSuccess() = runBlocking {
        var gets=0;val lost=IOException("request lost")
        val c=client { req,_ ->
            if(req.url.encodedPath.endsWith("/deliver"))throw lost
            if(req.method=="GET")gets++
            200 to target().put("status","revealed").put("requesterNonce",nonce.base64())
        }
        val f=CloudApprovalV2(c,"old",{session},{requester.public.encoded.base64()},{},{_,_,_->"{}"},{_,_->})
        f.start(target());f.poll()
        try { f.confirmMatches();fail("uncommitted delivery accepted") } catch(error:IOException) { assertEquals("request lost",error.message) }
        assertEquals(2,gets);assertEquals(CloudApprovalPhase.COMPARE,f.state.value.phase)
    }
    @Test fun reconciliationNetworkFailureNeverClaimsADeliveredApproval() = runBlocking {
        var failed=false;var gets=0
        val c=client { req,_ ->
            if(req.url.encodedPath.endsWith("/deliver")) {failed=true;throw IOException("response lost")}
            if(req.method=="GET") {gets++;if(failed)throw IOException("cannot reconcile")}
            200 to target().put("status","revealed").put("requesterNonce",nonce.base64())
        }
        val f=CloudApprovalV2(c,"old",{session},{requester.public.encoded.base64()},{},{_,_,_->"{}"},{_,_->})
        f.start(target());f.poll()
        assertThrows(IOException::class.java) { runBlocking { f.confirmMatches() } }
        assertEquals(2,gets);assertEquals(CloudApprovalPhase.COMPARE,f.state.value.phase)
    }
    @Test fun protocolRejectionIsNotHiddenByAReconciliationRead() = runBlocking {
        var gets=0
        val c=client { req,_ ->
            if(req.url.encodedPath.endsWith("/deliver"))400 to JSONObject().put("error","approval_signature_invalid")
            else { if(req.method=="GET")gets++;200 to target().put("status","revealed").put("requesterNonce",nonce.base64()) }
        }
        val f=CloudApprovalV2(c,"old",{session},{requester.public.encoded.base64()},{},{_,_,_->"{}"},{_,_->})
        f.start(target());f.poll()
        assertThrows(CloudHttpException::class.java) { runBlocking { f.confirmMatches() } }
        assertEquals(1,gets);assertEquals(CloudApprovalPhase.COMPARE,f.state.value.phase)
    }
    @Test fun concurrentOwnerOrEpochChangeCannotConvertOldDeliveryIntoSuccess() = runBlocking {
        for(changeOwner in listOf(true,false)) {
            var owner=session;var committed=false;lateinit var c:PhoneCloudClient
            c=client { req,_ ->
                if(req.url.encodedPath.endsWith("/deliver")) {committed=true;throw IOException("lost")}
                if(committed) {if(changeOwner)owner=session.copy(userId="other")else c.useEpoch(session,"new-epoch")}
                200 to target().put("status",if(committed)"delivered"else"revealed").put("requesterNonce",nonce.base64())
            }
            val f=CloudApprovalV2(c,"old",{owner},{requester.public.encoded.base64()},{},{_,_,_->"{}"},{_,_->})
            f.start(target());f.poll()
            assertThrows(IllegalStateException::class.java) { runBlocking { f.confirmMatches() } }
            assertNotEquals(CloudApprovalPhase.DELIVERED,f.state.value.phase)
        }
    }
    @Test fun requesterRejectAfterSuccessfulAcceptanceOnlyClearsLocalSession() = runBlocking {
        val signer=CloudProtocolV2.signingKey();val approverNonce=ByteArray(32){9};var requesterNonce=ByteArray(0);var polls=0;var cancels=0;var saved=0
        val c=client { req,body -> 200 to when {
            req.url.encodedPath.endsWith("/v2") -> JSONObject().put("requestId","r1")
            req.url.encodedPath.endsWith("/reveal") -> {requesterNonce=body.getString("requesterNonce").base64Bytes();JSONObject()}
            req.url.encodedPath.endsWith("/cancel") -> {cancels++;JSONObject()}
            else -> {
                polls++
                JSONObject().put("status",if(polls==1)"accepted"else"delivered").put("approverSpki",signer.public.encoded.base64())
                    .put("approverDeviceId","old").put("approverNonce",approverNonce.base64()).apply { if(polls>1) {
                        val t=CloudProtocolV2.transcript("u","epoch","r1","new",requester.public.encoded,"old",signer.public.encoded,requesterNonce,approverNonce)
                        put("payload","{}");put("signature",CloudProtocolV2.sign(signer.private,t,"{}").base64())
                    } }
            }
        } }
        val f=CloudApprovalV2(c,"new",{session},{requester.public.encoded.base64()},{},{_,_,_->error("requester")},{_,_->saved++})
        f.begin();f.poll();f.poll();assertEquals(0,saved);f.confirmMatches();assertEquals(1,saved)
        assertEquals(CloudApprovalPhase.DELIVERED,f.state.value.phase);f.reject()
        assertEquals(0,cancels);assertEquals(CloudApprovalPhase.IDLE,f.state.value.phase);assertEquals(1,saved)
    }
}
