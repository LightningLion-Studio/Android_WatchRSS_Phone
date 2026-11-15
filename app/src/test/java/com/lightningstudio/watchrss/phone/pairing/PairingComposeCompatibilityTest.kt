package com.lightningstudio.watchrss.phone.pairing

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PairingComposeCompatibilityTest {
    private val d=PairingTestData
    private fun receipt(maximum:Int)=JSONObject().put("success",true).put("userId",d.user).put("deviceId",d.device)
        .put("installId",d.install).put("publicKeySpki",d.spki).put("pairingUxVersion",1).put("messageSequenceMax",maximum)
        .put("capabilities",JSONObject().put("pairingConfirmV1",true).put("pairingReplaceAccountV1",false))
        .put("confirmation",JSONObject().put("phone","pending").put("watch","pending")).put("accountConflict",false)
    @Test fun onlyExplicitFrozenComposeBudgetWithBothConfirmationCapabilityAndNoReplacementIsAccepted() {
        val frozen=receipt(15)
        assertEquals(PairingConsentReceipt("pending","pending",false),PairingUxCodec.prepare(frozen,d.qr(),d.user))
        assertFalse(PairingUxCodec.canReplace(frozen))
        for(bad in listOf(
            receipt(15).apply{getJSONObject("capabilities").put("pairingConfirmV1",false)},
            receipt(15).apply{getJSONObject("capabilities").remove("pairingConfirmV1")},
            receipt(15).apply{getJSONObject("capabilities").put("pairingReplaceAccountV1",true)},
            receipt(15).apply{getJSONObject("capabilities").remove("pairingReplaceAccountV1")},
            receipt(15).put("messageSequenceMax","15"),receipt(16),receipt(254),receipt(256),
            receipt(15).apply{remove("messageSequenceMax")}
        )) assertThrows(PairingException::class.java){PairingUxCodec.prepare(bad,d.qr(),d.user)}
    }
    @Test fun authenticatedFifteenBudgetCannotSendSequenceSixteenAndReservesAllThreeFinalActions()=runBlocking {
        val qr=d.qr();var seq=0;val actions=mutableListOf<String>()
        val relay=WatchPairingRelay(qr,object:PairingHttp {
            override suspend fun call(method:String,path:String,body:String?,capability:String?,timeoutMillis:Long):JSONObject=when {
                path.endsWith("/claim")->JSONObject().put("claimed",true)
                path.endsWith("/request")->{val b=JSONObject(body!!);seq=b.getInt("seq");actions+=PairingWireCodec.open(qr,"phone",seq,b.getJSONObject("wire"),15).getString("action");JSONObject()}
                else->JSONObject().put("wire",PairingWireCodec.seal(qr,"watch",seq,if(seq==0)receipt(15) else JSONObject().put("success",true),15))
            }
        },{},{false},{d.now},sleep={})
        relay.connect();val prepared=relay.request(JSONObject().put("action","prepare"));relay.negotiateUx(prepared)
        repeat(12){relay.request(JSONObject().put("action","pairingStatus"))}
        for(action in listOf("configure","key","finish"))relay.request(JSONObject().put("action",action))
        assertEquals(15,seq);assertEquals(16,actions.size);assertEquals(listOf("configure","key","finish"),actions.takeLast(3))
        assertThrows(IllegalStateException::class.java){runBlocking{relay.request(JSONObject().put("action","extra"))}}
        Unit
    }
    @Test fun authenticated255BudgetIsPinnedAndCannotBeRenegotiatedToFifteen()=runBlocking {
        val qr=d.qr();val modern=receipt(255);var seq=0
        val relay=WatchPairingRelay(qr,object:PairingHttp {
            override suspend fun call(method:String,path:String,body:String?,capability:String?,timeoutMillis:Long):JSONObject=when {
                path.endsWith("/claim")->JSONObject().put("claimed",true)
                path.endsWith("/request")->{seq=JSONObject(body!!).getInt("seq");JSONObject()}
                else->JSONObject().put("wire",PairingWireCodec.seal(qr,"watch",seq,if(seq==0)modern else JSONObject().put("success",true),255))
            }
        },{},{false},{d.now},sleep={})
        relay.connect();relay.negotiateUx(relay.request(JSONObject().put("action","prepare")))
        assertThrows(IllegalStateException::class.java){relay.negotiateUx(receipt(15))}
        assertFalse(qr.active)
        assertThrows(PairingInvalidated::class.java){runBlocking{relay.request(JSONObject().put("action","pairingStatus"))}}
        assertEquals(0,seq)
        Unit
    }
}
