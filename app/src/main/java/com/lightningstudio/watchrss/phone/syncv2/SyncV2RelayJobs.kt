package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONArray
import org.json.JSONObject

/** C29 local inbox. A single authenticated, encrypted atomic record contains the complete
 * original publication, its durable receipt, pending order and object pins. No directory scan,
 * split index/job transaction, bearer, account apply or cleanup authorization is involved.
 * One host writer/queue instance owns this scope; network work runs outside its short writer. */
internal class SyncV2RelayJobs(private val identity:SyncV2Identity,private val authors:SyncV2Authors,
    private val store:SyncV2ContentStore,private val holderDeviceId:String,
    private val enabled:Boolean=com.lightningstudio.watchrss.phone.BuildConfig.SYNC_OBJECTS_V2_RELAY,
    private val now:()->Long={System.currentTimeMillis()},private val fault:(String)->Unit={}) {
    private fun state()=(store.load("relay-inbox")?:JSONObject().put("version",1).put("jobs",JSONArray())).also {
        SyncV2Codec.exact(it,setOf("version","jobs"));if(it.get("version")!=1 || it.getJSONArray("jobs").length()>32)SyncV2Codec.fail("invalid_schema")
    }
    private fun save(s:JSONObject) {
        if(SyncV2LocalProtocol.bytes(s).size>900000)SyncV2Codec.fail("quota_exceeded")
        store.save("relay-inbox",s)
    }
    private fun core(request:JSONObject)=SyncV2Codec.hash(JSONObject().put("account",request.get("account")).put("device",request.get("device")))
    private fun jobs(s:JSONObject)=s.getJSONArray("jobs").let{a->(0 until a.length()).map{a.getJSONObject(it)}}
    fun enqueue(session:SyncV2LocalSession,value:JSONObject):JSONObject {
        if(!enabled)SyncV2Codec.fail("feature_disabled")
        session.requireFraming()
        if(SyncV2Codec.scope(session.scope)!=identity.scopeJson || session.selfDeviceId!=holderDeviceId)SyncV2Codec.fail("scope_mismatch")
        val request=SyncV2Wire.parse(SyncV2LocalProtocol.bytes(value),262144,true)
        SyncV2Codec.exact(request,setOf("account","device","relayGrant"))
        val grant=request.getJSONObject("relayGrant").getJSONObject("body")
        val oid=SyncV2Wire.oid(grant.getString("commitOid"),"commit")
        val commit=SyncV2Wire.parse(store.objectBytes(oid),262144,true)
        val proof=SyncV2RelayProof(identity,authors,holderDeviceId)
        val b=proof.publication(request,commit,now())
        if(b.getLong("expiresAtMillis")<=now())SyncV2Codec.fail("relay_denied")
        val oids=(SyncV2Wire.strings(b.getJSONArray("objectOids"),512)+oid).distinct().sorted()
        // Object IO is bounded by the frozen 512-object grant and never occupies the writer.
        for(objectId in oids) {
            val raw=store.objectBytes(objectId)
            if(objectId.startsWith("commit:")) {
                val signed=SyncV2Wire.parse(raw,262144,true);authors.header(signed)
                if(SyncV2Codec.oid("commit",signed.getJSONObject("body"))!=objectId)SyncV2Codec.fail("hash_mismatch")
            }else if(objectId!="${objectId.substringBefore(':')}:sha256:${SyncV2Codec.hex(SyncV2Codec.sha(raw))}")SyncV2Codec.fail("hash_mismatch")
        }
        val author=b.getString("authorDeviceId");val rid=request.getJSONObject("account").getJSONObject("body").getString("requestId")
        val key="$author/$rid";val logicalHash=core(request);val wireHash=SyncV2Codec.hash(request)
        return synchronized(this) {
            session.requireFraming();proof.publication(request,commit,now())
            if(b.getLong("expiresAtMillis")<=now())SyncV2Codec.fail("relay_denied")
            val s=state();val list=jobs(s);val prior=list.singleOrNull{it.getString("key")==key}
            if(prior!=null && prior.get("coreHash")!=logicalHash)SyncV2Codec.fail("idempotency_conflict")
            if(prior!=null && prior.get("wireHash")==wireHash)return@synchronized prior.getJSONObject("ack")
            if(prior==null && list.size>=32)SyncV2Codec.fail("quota_exceeded")
            val ack=JSONObject().put("kind","relay-queued").put("requestId",rid).put("grantId",b.get("grantId"))
            val record=prior?:JSONObject().put("key",key).put("authorDeviceId",author).put("requestId",rid).put("coreHash",logicalHash)
                .put("commitOid",oid).put("generation",commit.getJSONObject("body").get("rollbackGeneration")).put("state","queued").put("result",JSONObject.NULL).put("protectedOids",JSONArray())
            val pins=SyncV2Wire.strings(record.getJSONArray("protectedOids"),2048)
            val retained=(pins+oids).distinct().sorted();if(retained.size>2048)SyncV2Codec.fail("quota_exceeded")
            record.put("body",request).put("wireHash",wireHash).put("ack",ack).put("grantId",b.get("grantId")).put("protectedOids",JSONArray(retained)).put("lastError",JSONObject.NULL)
            if(record.getString("state")!="succeeded")record.put("state","queued")
            if(prior==null)s.getJSONArray("jobs").put(record)
            save(s);ack
        }
    }
    /** Exactly one persisted job per OS window; the caller owns scheduling and backoff.
     * Successful cloud lookup is checked before grant expiry to recover a lost final response. */
    fun resumeNext(relay:SyncV2Relay,budget:SyncV2ContentWorker.Budget=SyncV2ContentWorker.Budget()):JSONObject? {
        if(!enabled)SyncV2Codec.fail("feature_disabled")
        val captured=synchronized(this){jobs(state()).firstOrNull{it.getString("state")!="succeeded"}}?:return null
        val result:JSONObject
        try {
            result=relay.resumeQueued(captured.getJSONObject("body"),now(),budget)
        }catch(t:Exception) {
            val code=if(t is SyncV2CloudError)t.code else if(t is IllegalStateException && Regex("[a-z][a-z0-9_]{0,63}").matches(t.message?:""))t.message!! else "temporarily_unavailable"
            // C18: known invalid authorization cannot mutate persistent sync/queue state.
            if(code in setOf("authentication_required","device_revoked","scope_mismatch","service_key_changed"))throw t
            synchronized(this) {
                val s=state();val current=jobs(s).single{it.get("key")==captured.get("key")}
                if(current.get("wireHash")==captured.get("wireHash") && current.getString("state")!="succeeded") {
                    current.put("state",if(code in setOf("budget_paused","transport_wait","temporarily_unavailable","cloud_account_busy","library_busy"))"queued" else "blocked").put("lastError",code);save(s)
                }
            }
            throw t
        }
        fault("before-result")
        return synchronized(this) {
            val s=state();val current=jobs(s).single{it.get("key")==captured.get("key")}
            if(current.get("coreHash")!=captured.get("coreHash"))SyncV2Codec.fail("choice_stale")
            if(current.getString("state")=="succeeded" && SyncV2Codec.canonical(current.get("result"))!=SyncV2Codec.canonical(result))SyncV2Codec.fail("idempotency_conflict")
            current.put("state","succeeded").put("result",result).put("lastError",JSONObject.NULL);save(s)
            JSONObject().put("kind","cloud-published").put("requestId",current.get("requestId")).put("result",result)
        }
    }
    @Synchronized fun pending():Int=jobs(state()).count{it.getString("state")!="succeeded"}
    @Synchronized fun protectedObjects():Set<String> =jobs(state()).flatMap{SyncV2Wire.strings(it.getJSONArray("protectedOids"),2048)}.toSet()
}
