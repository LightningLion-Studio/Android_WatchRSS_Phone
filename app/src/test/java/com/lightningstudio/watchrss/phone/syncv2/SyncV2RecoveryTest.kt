package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class SyncV2RecoveryTest {
    private val key=SyncV2Fixtures.localKey
    private val base=SyncV2Fixtures.vector("rc6-contracts").getJSONObject("editBase")
    private val account=SyncV2Fixtures.vector("rc6-contracts").getJSONObject("window").getString("oid")
    private fun same(a:Any?,b:Any?)=assertEquals(SyncV2Codec.canonical(a),SyncV2Codec.canonical(b))
    private fun failed(fn:()->Unit):Boolean=try{fn();false}catch(_:Exception){true}
    private fun fixed(name:String,id:String)=SyncV2Fixtures.vector(name).getJSONArray("cases").let {a->(0 until a.length()).map(a::getJSONObject).single{it.getString("id")==id}.getJSONObject("expected")}
    private fun copy(now:Long,old:Set<String> = emptySet()):Pair<JSONObject,JSONObject> {
        val root="recipe:sha256:"+"a".repeat(64);val blob="blob:sha256:"+"b".repeat(64)
        val refs=JSONObject().put("changed",JSONArray().put(blob).put(root))
        val delta=SyncV2Recovery.delta(JSONObject().put("changed",JSONObject().put("recipeOid",root)),JSONObject(),base,setOf("changed"),refs)
        val c=SyncV2Recovery.prepare(delta,key,now,"7",account,old)
        return c to SyncV2Recovery.pin(c,delta)
    }
    @Test fun minimalPartialDeltaMatchesC03AndC05() {
        val delta=SyncV2Recovery.delta(JSONObject("{changed:{title:'offline'}}"),JSONObject("{changed:{title:'cloud'},same:{title:'unchanged'}}"),base,setOf("changed"),JSONObject())
        same(fixed("rc4-review","C03").get("changes"),delta.getJSONArray("changes"))
        val root="recipe:sha256:"+"a".repeat(64)
        val withBody=SyncV2Recovery.delta(JSONObject().put("changed",JSONObject().put("title","offline").put("recipeOid",root)),JSONObject(),base,setOf("changed"),JSONObject().put("changed",JSONArray().put(root)))
        same(fixed("rc4-review","C05").get("bodyRefs"),withBody.get("bodyRefs"))
        val removed=SyncV2Recovery.delta(JSONObject(),JSONObject("{gone:{title:'cloud'},other:{title:'stay'}}"),base,setOf("gone"),JSONObject())
        assertEquals(1,removed.getJSONArray("changes").length());assertEquals("remove",removed.getJSONArray("changes").getJSONObject(0).getString("kind"))
    }
    @Test fun independentAesGcmAndStableIdBindingMatchE06() {
        val (copy,pin)=copy(1000);val header=copy.getJSONObject("header")
        val d=Cipher.getInstance("AES/GCM/NoPadding")
        d.init(Cipher.DECRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,Base64.getUrlDecoder().decode(copy.getString("nonce"))))
        d.updateAAD(SyncV2Codec.canonical(header).toByteArray())
        val independent=JSONObject(d.doFinal(Base64.getUrlDecoder().decode(copy.getString("ciphertext"))).toString(Charsets.UTF_8))
        val outer=JSONObject(copy.toString()).put("copyId","ff".repeat(16))
        val both=JSONObject(copy.toString()).put("copyId","ff".repeat(16)).also{it.getJSONObject("header").put("copyId","ff".repeat(16))}
        val other=copy(2000).first;val swapped=JSONObject(copy.toString()).put("nonce",other.get("nonce")).put("ciphertext",other.get("ciphertext"))
        val observed=JSONObject().put("independentPlaintextMatches",SyncV2Codec.canonical(independent)==SyncV2Codec.canonical(SyncV2Recovery.open(copy,key)))
            .put("outerMismatchRejected",failed{SyncV2Recovery.open(outer,key)}).put("bothIdsChangedRejected",failed{SyncV2Recovery.open(both,key)}).put("swappedCiphertextRejected",failed{SyncV2Recovery.open(swapped,key)})
        same(fixed("rc6-review","E06"),observed);same(independent.get("bodyRefs"),pin.get("bodyRefs"))
    }
    @Test fun stablePinPlanAcrossCompactionAppendAndRestartMatchesE07() {
        val (first,p1)=copy(1000);val (second,p2)=copy(2000,setOf(first.getString("copyId")))
        val expired=SyncV2Recovery.expiredPlan(JSONArray().put(first).put(second),JSONArray().put(p1).put(p2),604801500,2000)
        val stableSecond=expired==setOf(first.getString("copyId")) && !expired.contains(second.getString("copyId"))
        val (third,p3)=copy(604801501,setOf(first.getString("copyId"),second.getString("copyId")))
        val copies=JSONArray(JSONArray().put(third).put(second).toString());val pins=JSONArray(JSONArray().put(p2).put(p3).toString())
        val secondExpiry=SyncV2Recovery.expiredPlan(copies,pins,604802001,604801502)
        val observed=JSONObject().put("stableSecond",stableSecond).put("newIdUnique",setOf(first.getString("copyId"),second.getString("copyId"),third.getString("copyId")).size==3)
            .put("noPersistedIndex",!pins.toString().contains("copyIndex")).put("restartAssociation",SyncV2Recovery.open(second,key).getJSONArray("bodyRefs").toString()==p2.getJSONArray("bodyRefs").toString())
            .put("thirdSurvivesSecondExpiry",secondExpiry==setOf(second.getString("copyId"))).put("allExpiredTogether",SyncV2Recovery.expiredPlan(copies,pins,2*604800000L+1501,604802001).size==2)
        same(fixed("rc6-review","E07"),observed)
    }
    @Test fun invalidPinMetadataAndClockRollbackKeepAllReferences() {
        val (copy,pin)=copy(1000);val copies=JSONArray().put(copy);val pins=JSONArray().put(pin)
        assertTrue(SyncV2Recovery.expiredPlan(copies,pins,0,1000).isEmpty())
        val before=copies.toString()+pins.toString()
        assertTrue(failed{SyncV2Recovery.expiredPlan(copies,JSONArray().put(JSONObject(pin.toString()).put("copyIndex",0)),999999999,1000)})
        assertTrue(failed{SyncV2Recovery.expiredPlan(JSONArray().put(copy).put(copy),pins,999999999,1000)})
        assertEquals(before,copies.toString()+pins.toString())
    }
}
