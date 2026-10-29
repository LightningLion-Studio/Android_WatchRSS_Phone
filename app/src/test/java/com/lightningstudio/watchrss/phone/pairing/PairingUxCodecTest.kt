package com.lightningstudio.watchrss.phone.pairing

import com.lightningstudio.watchrss.phone.account.PhoneAccountHttpException
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class PairingUxCodecTest {
    private val fixture=JSONObject(requireNotNull(javaClass.getResource("/pairing/pairing-ux-wire-v1.json")).readText())
    private fun qr()=WatchPairingQr.parse(fixture.getString("qr"),fixture.getLong("now"))
    @Test fun productionCodecMatchesEverySharedPlaintextAndExactCiphertextIncludingSequences16And255() {
        val qr=qr();assertEquals(fixture.getString("sas"),PairingWireCodec.sas(qr,fixture.getString("userId")))
        val rows=fixture.getJSONArray("vectors")
        for(i in 0 until rows.length()) {
            val row=rows.getJSONObject(i);val expected=row.getJSONObject("wire");val seq=expected.getInt("seq");val direction=row.getString("direction")
            val wire=PairingWireCodec.sealText(qr,direction,seq,row.getString("plaintext"),Base64.getDecoder().decode(expected.getString("n")),255)
            for(key in listOf("seq","n","c"))assertEquals(row.getString("name"),expected.get(key),wire.get(key))
            assertEquals(JSONObject(row.getString("plaintext")).toString(),PairingWireCodec.open(qr,direction,seq,wire,255).toString())
            if(seq>15)assertThrows(IllegalArgumentException::class.java){PairingWireCodec.open(qr,direction,seq,wire)}
        }
        assertThrows(IllegalArgumentException::class.java){PairingWireCodec.seal(qr,"phone",256,JSONObject(),255)}
        assertThrows(IllegalArgumentException::class.java){PairingWireCodec.seal(qr,"phone",16,JSONObject())}
    }
    @Test fun classificationRequiresAnOwnership409AndNeverTreatsRevocationAsTransferable() {
        for(code in listOf("account_switch_requires_logout","device_owned_by_another_account","installation_owned_by_another_account","device_revoked","unknown")) {
            for(status in listOf(403,409,500)) {
                val error=PhoneAccountHttpException(status,JSONObject().put("error",code).toString())
                assertEquals(status==409 && code !in setOf("device_revoked","unknown"),PairingUxCodec.ownershipConflict(error))
            }
        }
    }
    @Test fun authenticatedPeerRejectionConsumesSequenceAndDoesNotLeakArbitraryRemoteMessage()=runBlocking {
        val qr=qr();var currentSeq=0;val sent=mutableListOf<Int>()
        val http=object:PairingHttp {
            override suspend fun call(method:String,path:String,body:String?,capability:String?,timeoutMillis:Long):JSONObject=when {
                path.endsWith("/claim")->JSONObject().put("claimed",true)
                path.endsWith("/request")->{currentSeq=JSONObject(body!!).getInt("seq");sent+=currentSeq;JSONObject()}
                else->JSONObject().put("wire",PairingWireCodec.seal(qr,"watch",currentSeq,
                    if(currentSeq==0)JSONObject().put("success",false).put("errorCode","pairing_rejected").put("message","SECRET-DO-NOT-SHOW")
                    else JSONObject().put("success",true)))
            }
        }
        val relay=WatchPairingRelay(qr,http,{},{true},{fixture.getLong("now")},sleep={})
        relay.connect()
        try{relay.request(JSONObject().put("action","prepare"));fail("must reject")}catch(error:PairingPeerException) {
            assertEquals("pairing_rejected",error.peerCode);assertFalse(error.message!!.contains("SECRET"))
        }
        assertTrue(relay.request(JSONObject().put("action","next")).getBoolean("success"));assertEquals(listOf(0,1),sent)
    }
    @Test fun relayBudgetCannotUpgradeWithoutAuthenticatedPrepareNegotiation()=runBlocking {
        val qr=qr();val prepare=JSONObject(fixture.getJSONArray("vectors").getJSONObject(1).getString("plaintext"))
        var seq=0
        val relay=WatchPairingRelay(qr,object:PairingHttp {
            override suspend fun call(method:String,path:String,body:String?,capability:String?,timeoutMillis:Long):JSONObject=when {
                path.endsWith("/claim")->JSONObject().put("claimed",true)
                path.endsWith("/request")->{seq=JSONObject(body!!).getInt("seq");JSONObject()}
                else->JSONObject().put("wire",PairingWireCodec.seal(qr,"watch",seq,if(seq==0)prepare else JSONObject().put("success",true),255))
            }
        },{},{true},{fixture.getLong("now")},sleep={})
        relay.connect();assertThrows(IllegalStateException::class.java){relay.negotiateUx(prepare)}
        relay.request(JSONObject().put("action","prepare"))
        assertThrows(PairingException::class.java){relay.negotiateUx(JSONObject(prepare.toString()).put("messageSequenceMax",15))}
        relay.negotiateUx(prepare)
        repeat(20) {relay.request(JSONObject().put("action","pairingStatus"))}
        assertEquals(20,seq)
        assertThrows(IllegalStateException::class.java){relay.negotiateUx(prepare)}
        Unit
    }
    @Test fun phoneProductionPrepareCodecAcceptsExactWatchSharedResponseAndRejectsIdentityOrSpkiMutation() {
        val data=JSONObject(requireNotNull(javaClass.getResource("/pairing/pairing-ux-v1.json")).readText())
        val context=data.getJSONObject("context")
        val qr=WatchPairingQr(context.getString("sessionId"),context.getString("deviceId"),context.getString("installId"),data.getLong("now")+300000,ByteArray(32){7},"a".repeat(64))
        val response=data.getJSONObject("prepareResponse")
        assertEquals(PairingConsentReceipt("pending","pending",false),PairingUxCodec.prepare(response,qr,context.getString("userId")))
        assertFalse(PairingUxCodec.canReplace(response))
        for(field in listOf("userId","deviceId","installId","publicKeySpki","sas")) {
            val bad=JSONObject(response.toString()).put(field,"invalid")
            assertThrows(Exception::class.java){PairingUxCodec.prepare(bad,qr,context.getString("userId"))}
        }
    }
}
