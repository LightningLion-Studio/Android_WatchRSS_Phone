package com.lightningstudio.watchrss.phone.pairing

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PairingRelayTest {
    private val d=PairingTestData
    private data class Call(val method:String,val path:String,val body:String?,val cap:String?)
    @Test fun claimAndRequestRetriesReuseSameCapabilitySequenceNonceAndCiphertext() = runBlocking {
        val calls=mutableListOf<Call>();var claims=0;var requests=0;var polls=0;val qr=d.qr()
        val http=object:PairingHttp {override suspend fun call(method:String,path:String,body:String?,capability:String?,timeoutMillis:Long):JSONObject {
            calls+=Call(method,path,body,capability)
            return when {
                path.endsWith("/claim") -> {if(claims++==0)throw IOException("lost");if(claims==2)throw PairingHttpException(503,"upstream");JSONObject().put("claimed",true)}
                path.endsWith("/request") -> {if(requests++==0)throw IOException("lost");val message=JSONObject(body!!);assertEquals(0,message.getInt("seq"));assertEquals("prepare",PairingWireCodec.open(qr,"phone",0,message.getJSONObject("wire")).getString("action"));JSONObject().put("accepted",true)}
                path.contains("/response?") -> {polls++;JSONObject().put("wire",if(polls==1)JSONObject.NULL else PairingWireCodec.seal(qr,"watch",0,JSONObject().put("success",true)))}
                else -> JSONObject().put("closed",true)
            }
        }}
        val relay=WatchPairingRelay(qr,http,{},{true},{d.now},sleep={},random={ByteArray(it){2}})
        relay.connect();assertTrue(relay.request(JSONObject().put("action","prepare")).getBoolean("success"));relay.close()
        assertEquals(3,claims);assertEquals(2,requests)
        assertEquals(1,calls.filter{it.path.endsWith("/claim")}.map{it.body}.distinct().size)
        assertEquals(1,calls.filter{it.path.endsWith("/request")}.map{it.body to it.cap}.distinct().size)
        assertEquals(1,calls.count{it.path.endsWith("/close")})
        assertFalse(calls.toString().contains(d.fixture.getJSONObject("descriptor").getString("secret")))
        assertTrue(qr.secret.all{it==0.toByte()})
    }
    @Test fun callerRetryAfterAmbiguousRequestStillUsesTheOriginalWireAndAcceptsNoNewMessage() = runBlocking {
        var sends=0;val bodies=mutableListOf<String>();val qr=d.qr()
        val http=object:PairingHttp {override suspend fun call(method:String,path:String,body:String?,capability:String?,timeoutMillis:Long):JSONObject = when {
            path.endsWith("/claim")->JSONObject().put("claimed",true)
            path.endsWith("/request")->{bodies+=body!!;if(++sends<=3)throw IOException("offline");JSONObject()}
            else->JSONObject().put("wire",PairingWireCodec.seal(qr,"watch",0,JSONObject().put("success",true)))
        }}
        val relay=WatchPairingRelay(qr,http,{},{true},{d.now},sleep={})
        relay.connect();val message=JSONObject().put("action","prepare")
        try{relay.request(message);fail("offline")}catch(_:IOException){}
        try{relay.request(JSONObject().put("action","different"));fail("changed pending request")}catch(_:IllegalArgumentException){}
        relay.request(message);assertEquals(4,sends);assertEquals(1,bodies.distinct().size)
    }
    @Test fun conflictsExpiryAndMalformedWireAreNeverRetriedOrInventedAsSuccess() = runBlocking {
        for(status in listOf(409,410,404)) {
            var calls=0;val http=object:PairingHttp {override suspend fun call(method:String,path:String,body:String?,capability:String?,timeoutMillis:Long):JSONObject {calls++;throw PairingHttpException(status,"pairing_invalid")}}
            val relay=WatchPairingRelay(d.qr(),http,{},{false},{d.now},sleep={})
            assertThrows(PairingHttpException::class.java){runBlocking{relay.connect()}};assertEquals(1,calls)
        }
    }
    @Test fun r16OversizeIsRejectedBeforeAnyRelayRequestCanBeSent() = runBlocking {
        var requests=0;val http=object:PairingHttp {override suspend fun call(method:String,path:String,body:String?,capability:String?,timeoutMillis:Long):JSONObject {if(path.endsWith("/request"))requests++;return JSONObject().put("claimed",true)}}
        val relay=WatchPairingRelay(d.qr(),http,{},{true},{d.now},sleep={});relay.connect()
        assertThrows(PairingException::class.java){runBlocking{relay.request(JSONObject().put("action","key").put("payload","x".repeat(13000)))}}
        assertEquals(0,requests)
    }
    @Test fun ownerChangeOrCancelRejectsLateResponseAndNeverClosesAnotherUsersSession() = runBlocking {
        var owner="a";var closes=0;val qr=d.qr()
        val http=object:PairingHttp {override suspend fun call(method:String,path:String,body:String?,capability:String?,timeoutMillis:Long):JSONObject {
            if(path.endsWith("/close"))closes++
            if(path.endsWith("/request"))owner="b"
            return JSONObject().put("claimed",true)
        }}
        val relay=WatchPairingRelay(qr,http,{if(owner!="a")throw PairingInvalidated()},{owner=="a"},{d.now},sleep={})
        relay.connect();assertThrows(PairingInvalidated::class.java){runBlocking{relay.request(JSONObject())}}
        relay.close();assertEquals(0,closes);assertTrue(qr.secret.all{it==0.toByte()})
    }
    @Test fun wrongResponseSequenceOrAuthenticationNeverAdvancesRequestState() = runBlocking {
        var wrong=true;val qr=d.qr();var sends=0
        val http=object:PairingHttp {override suspend fun call(method:String,path:String,body:String?,capability:String?,timeoutMillis:Long):JSONObject = when {
            path.endsWith("/claim")->JSONObject().put("claimed",true)
            path.endsWith("/request")->{sends++;JSONObject()}
            else->JSONObject().put("wire",PairingWireCodec.seal(qr,"watch",if(wrong)1 else 0,JSONObject().put("success",true)))
        }}
        val relay=WatchPairingRelay(qr,http,{},{true},{d.now},sleep={});relay.connect();val message=JSONObject().put("action","prepare")
        assertThrows(IllegalArgumentException::class.java){runBlocking{relay.request(message)}}
        wrong=false;assertTrue(relay.request(message).getBoolean("success"));assertEquals(2,sends)
    }
}
