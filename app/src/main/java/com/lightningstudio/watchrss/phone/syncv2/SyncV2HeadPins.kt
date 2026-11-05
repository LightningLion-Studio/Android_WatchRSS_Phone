package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger

/** Pure verification outside the SQLite writer. Signed header pages are supplied by the caller. */
internal object SyncV2HeadPins {
    private val fields=setOf("version","scope","authorDeviceId","deviceSequence","previousOwnCommit","mergedParents","treeRoot","requiredFeatures","createdAtMillis","observedRefs","indexComplete","accountParent","rollbackGeneration","operation")
    fun validateBody(b: JSONObject) {
        SyncV2Codec.exact(b,fields)
        for(f in listOf("authorDeviceId","deviceSequence","rollbackGeneration","treeRoot")) SyncV2Wire.string(b,f)
        for(f in listOf("accountParent","previousOwnCommit")) if(!b.isNull(f)) SyncV2Wire.string(b,f)
        if(b.get("version")!=2 || b.get("indexComplete")!=true) SyncV2Codec.fail("invalid_schema")
        SyncV2Codec.scope(b.getJSONObject("scope")); SyncV2Codec.unhex(b.getString("authorDeviceId"),16)
        if(SyncV2Codec.d64(b.getString("deviceSequence"))<BigInteger.ONE) SyncV2Codec.fail("invalid_schema")
        SyncV2Codec.d64(b.getString("rollbackGeneration"))
        for(f in listOf("accountParent","previousOwnCommit"))if(!b.isNull(f))SyncV2Codec.commitOid(b.getString(f))
        if(!Regex("tree:sha256:[a-f0-9]{64}").matches(b.getString("treeRoot")))SyncV2Codec.fail("invalid_schema")
        val created=b.get("createdAtMillis"); if(created !is Int && created !is Long)SyncV2Codec.fail("invalid_schema")
        if((created as Number).toLong() !in 0..9007199254740991L)SyncV2Codec.fail("invalid_schema")
        val op=b.getJSONObject("operation"); SyncV2Codec.exact(op,setOf("kind","rollbackTarget","resolvesGeneration"))
        when(SyncV2Wire.string(op,"kind")) {
            "normal" -> if(!op.isNull("rollbackTarget") || !op.isNull("resolvesGeneration"))SyncV2Codec.fail("invalid_schema")
            "rollback" -> {SyncV2Codec.commitOid(SyncV2Wire.string(op,"rollbackTarget"));if(!op.isNull("resolvesGeneration"))SyncV2Codec.fail("invalid_schema")}
            "resolve-local","resolve-merge" -> if(!op.isNull("rollbackTarget") || SyncV2Wire.string(op,"resolvesGeneration")!=b.getString("rollbackGeneration"))SyncV2Codec.fail("invalid_schema")
            else -> SyncV2Codec.fail("invalid_schema")
        }
        val parents=b.getJSONArray("mergedParents"); val parentList=(0 until parents.length()).map{(parents.get(it) as? String ?: SyncV2Codec.fail("invalid_schema")).also(SyncV2Codec::commitOid)}
        if(parentList.size>64 || parentList!=parentList.distinct().sorted() || parentList.contains(b.optString("previousOwnCommit")))SyncV2Codec.fail("invalid_schema")
        val features=b.getJSONArray("requiredFeatures"); val featureList=(0 until features.length()).map { features.get(it) as? String ?: SyncV2Codec.fail("invalid_schema") }
        if(featureList.size>32 || featureList!=featureList.distinct().sorted() || featureList.any{!Regex("[a-z0-9][a-z0-9.-]{0,63}").matches(it)})SyncV2Codec.fail("invalid_schema")
        if(featureList.isNotEmpty())SyncV2Codec.fail("unsupported_feature")
        val refs=b.getJSONArray("observedRefs"); var prev=""
        if(refs.length()>64)SyncV2Codec.fail("invalid_schema")
        for(i in 0 until refs.length()) {
            val r=refs.getJSONObject(i);SyncV2Codec.exact(r,setOf("deviceId","commitOid","refRevision"))
            val id=SyncV2Wire.string(r,"deviceId");SyncV2Codec.unhex(id,16);if(id<=prev)SyncV2Codec.fail("invalid_schema");prev=id
            SyncV2Codec.commitOid(SyncV2Wire.string(r,"commitOid"));if(SyncV2Codec.d64(SyncV2Wire.string(r,"refRevision"))<BigInteger.ONE)SyncV2Codec.fail("invalid_schema")
        }
    }
    fun verify(identity: SyncV2Identity,window: JSONObject,headers: List<JSONObject>,pin: JSONObject?, verifyHeader: (JSONObject)->JSONObject = identity::verifyHeader): JSONArray {
        // Bounded pages; archive only control headers, never historical tree/business payloads.
        if(headers.size>4096)SyncV2Codec.fail("object_too_large")
        val stop=pin?.getString("oid"); var at: String?=window.getString("oid")
        if(at==stop) {
            if(window.getString("generation")!=pin!!.getString("generation"))SyncV2Codec.fail("replay_detected")
            return JSONArray()
        }
        val seen=mutableSetOf<String>(); val archive=JSONArray(); val newer=mutableMapOf<String,BigInteger>()
        var generation: BigInteger?=null
        for(envelope in headers) {
            if(at==stop)break // Unrelated signatures must not advance author watermarks.
            val body=verifyHeader(envelope); val oid=SyncV2Codec.oid("commit",body)
            if(oid!=at || !seen.add(oid))SyncV2Codec.fail("ref_continuity_unavailable")
            if(archive.length()==0 && body.getString("rollbackGeneration")!=window.getString("generation"))SyncV2Codec.fail("replay_detected")
            val author=body.getString("authorDeviceId"); val seq=SyncV2Codec.d64(body.getString("deviceSequence"))
            if(newer[author]?.let{seq>=it}==true)SyncV2Codec.fail("replay_detected")
            newer[author]=seq
            val g=SyncV2Codec.d64(body.getString("rollbackGeneration"));if(generation?.let{g>it}==true)SyncV2Codec.fail("replay_detected");generation=g
            archive.put(JSONObject().put("oid",oid).put("header",envelope))
            at=if(body.isNull("accountParent"))null else body.getString("accountParent")
        }
        if(at!=stop)SyncV2Codec.fail("ref_continuity_unavailable")
        return archive
    }
}
