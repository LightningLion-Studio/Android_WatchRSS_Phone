package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONArray
import org.json.JSONObject

/** C23: stateless grant validation. Approval is cloud author approval, not local pairing.
 * Original signed commit/ref bytes are retained; the phone never signs for the author. */
internal class SyncV2Relay(private val identity:SyncV2Identity,private val authors:SyncV2Authors,
    private val client:SyncV2CloudClient,private val store:SyncV2ContentStore,
    private val enabled:Boolean=com.lightningstudio.watchrss.phone.BuildConfig.SYNC_OBJECTS_V2_RELAY,
    private val signals:()->SyncV2TransportSignals={SyncV2TransportSignals()},private val allowChargingCellular:()->Boolean={true}) {
    private fun proof()=SyncV2RelayProof(identity,authors,client.deviceId)
    fun validate(grant:JSONObject,commit:JSONObject,update:JSONObject,now:Long):JSONObject {
        if(!enabled)SyncV2Codec.fail("feature_disabled")
        if(SyncV2Codec.scope(client.scope)!=identity.scopeJson)SyncV2Codec.fail("scope_mismatch")
        return proof().validate(grant,commit,update,now)
    }
    fun forward(commit:JSONObject,device:JSONObject,grant:JSONObject,account:JSONObject?,now:Long,
        budget:SyncV2ContentWorker.Budget=SyncV2ContentWorker.Budget()):JSONObject=client.withinBudget(budget.millis) {
        if(!enabled)SyncV2Codec.fail("feature_disabled")
        if(SyncV2Codec.scope(client.scope)!=identity.scopeJson)SyncV2Codec.fail("scope_mismatch")
        val publication=account?.let{JSONObject().put("account",it).put("device",device).put("relayGrant",grant)}
        val b=if(publication==null)validate(grant,commit,device,now)else proof().publication(publication,commit,now)
        // Persist the exact original signed payload before the network call. No author bearer.
        val job=JSONObject().put("commit",commit).put("device",device).put("account",account?:JSONObject.NULL).put("relayGrant",grant)
        val key="relay/${b.getString("authorDeviceId")}/${device.getJSONObject("body").getString("requestId")}";val old=store.load(key)
        fun core(value:JSONObject)=JSONObject(value.toString()).apply{remove("relayGrant")}
        if(old!=null && SyncV2Codec.canonical(core(old))!=SyncV2Codec.canonical(core(job)))SyncV2Codec.fail("idempotency_conflict")
        if(old==null || SyncV2Codec.canonical(old)!=SyncV2Codec.canonical(job))store.save(key,job)
        client.withinBudget(budget.millis-budget.reserveMillis){uploadOriginals(b,commit,now,budget.bytes)}
        if(publication==null)validate(grant,commit,device,now)else proof().publication(publication,commit,now)
        val request=if(account==null)JSONObject(device.toString()).put("relayGrant",grant)
            else JSONObject().put("account",account).put("device",device).put("relayGrant",grant)
        val result=if(account==null)client.stage(request) else client.publish(request)
        store.save("relay-result/${b.getString("grantId")}",result);result
    }
    /** Read-only resolution of a response lost before the local queue result became durable. */
    fun lookupQueued(author:String,requestId:String,oid:String,generation:String):JSONObject? {
        if(!enabled)SyncV2Codec.fail("feature_disabled")
        if(SyncV2Codec.scope(client.scope)!=identity.scopeJson)SyncV2Codec.fail("scope_mismatch")
        authors.publicKey(author);authors.publicKey(client.deviceId)
        client.capabilities();val lookup=client.requestResult(requestId)
        return when(lookup.getString("status")) {
            "unknown"->{if(!lookup.isNull("response"))SyncV2Codec.fail("invalid_schema");null}
            "succeeded"->lookup.getJSONObject("response").also{verifyResult(it,author,oid,generation)}
            else->SyncV2Codec.fail("invalid_schema")
        }
    }
    fun forwardQueued(request:JSONObject,now:Long,budget:SyncV2ContentWorker.Budget=SyncV2ContentWorker.Budget()):JSONObject {
        val oid=SyncV2Wire.oid(request.getJSONObject("relayGrant").getJSONObject("body").getString("commitOid"),"commit")
        val commit=SyncV2Wire.parse(store.objectBytes(oid),262144,true)
        val b=proof().publication(request,commit,now)
        if(b.getLong("expiresAtMillis")<=now)SyncV2Codec.fail("relay_denied")
        return forward(commit,request.getJSONObject("device"),request.getJSONObject("relayGrant"),request.getJSONObject("account"),now,budget)
            .also{verifyResult(it,b.getString("authorDeviceId"),oid,commit.getJSONObject("body").getString("rollbackGeneration"))}
    }
    fun resumeQueued(request:JSONObject,now:Long,budget:SyncV2ContentWorker.Budget=SyncV2ContentWorker.Budget()):JSONObject=client.withinBudget(budget.millis) {
        val b=request.getJSONObject("relayGrant").getJSONObject("body");val oid=SyncV2Wire.oid(b.getString("commitOid"),"commit")
        val commit=SyncV2Wire.parse(store.objectBytes(oid),262144,true);val body=authors.header(commit,false)
        if(SyncV2Codec.oid("commit",body)!=oid)SyncV2Codec.fail("hash_mismatch")
        lookupQueued(b.getString("authorDeviceId"),request.getJSONObject("account").getJSONObject("body").getString("requestId"),oid,body.getString("rollbackGeneration"))
            ?:forwardQueued(request,now,budget)
    }
    private fun verifyResult(result:JSONObject,author:String,oid:String,generation:String) {
        if(result.opt("version")!=2 || SyncV2Codec.scope(result.getJSONObject("scope"))!=identity.scopeJson)SyncV2Codec.fail("scope_mismatch")
        val account=result.getJSONObject("account");val device=result.getJSONObject("device")
        if(account.get("commitOid")!=oid || device.get("commitOid")!=oid || device.get("ref")!="refs/devices/$author/index")SyncV2Codec.fail("idempotency_conflict")
        if(account.get("rollbackGeneration")!=generation)SyncV2Codec.fail("replay_detected")
        for(value in listOf(account.getString("revision"),device.getString("revision"),result.getString("serverRevision")))if(SyncV2Codec.d64(value).signum()==0)SyncV2Codec.fail("invalid_schema")
    }
    private fun uploadOriginals(grant:JSONObject,commit:JSONObject,now:Long,maxBytes:Long) {
        val capabilities=client.capabilities()
        if(capabilities.getJSONObject("flags").opt("relay")!=true)SyncV2Codec.fail("feature_disabled")
        val window=client.negotiate().getString("windowId")
        val oid=grant.getString("commitOid")
        val oids=(SyncV2Wire.strings(grant.getJSONArray("objectOids"),512)+oid).distinct().sorted()
        var sent=0L
        for(objectId in oids) {
            val raw=if(objectId==oid)SyncV2LocalProtocol.bytes(commit)else store.objectBytes(objectId)
            if(objectId!=oid && objectId!="${objectId.substringBefore(':')}:sha256:${SyncV2Codec.hex(SyncV2Codec.sha(raw))}")SyncV2Codec.fail("hash_mismatch")
            val status=client.verifiedHaveWant(window,listOf(objectId to raw.size),now).getValue(objectId)
            if(status.getString("status")=="present")continue
            val content=objectId.substringBefore(':') in setOf("blob","recipe")
            fun eligible(){if(content && !signals().eligible(allowChargingCellular()).getBoolean("cloudAllowed"))SyncV2Codec.fail("transport_wait")}
            eligible()
            val request=SyncV2Codec.hash(org.json.JSONArray().put(grant.get("grantId")).put(objectId)).take(32)
            val reservation=client.reserve(request,listOf(objectId to raw.size),if(content)"content" else "control").getString("reservationId")
            // A reserve replay is identity only. C11 requires a fresh durable offset query.
            val current=client.verifiedHaveWant(window,listOf(objectId to raw.size),now).getValue(objectId)
            var offset=current.getInt("durableOffset")
            while(offset<raw.size) {
                val part=raw.copyOfRange(offset,minOf(raw.size,offset+65536))
                eligible();if(sent+part.size>maxBytes)SyncV2Codec.fail("budget_paused")
                val partRequest=SyncV2Codec.hash(org.json.JSONArray().put(grant.get("grantId")).put(objectId).put("part").put(offset)).take(32)
                val response=client.put(objectId,offset,reservation,partRequest,part);val end=offset+part.size
                client.verifyReceipt(response.getJSONObject("receipt"),objectId,raw.size.toLong(),end.toLong(),now)
                offset=end;sent+=part.size;Thread.yield()
            }
            val completeRequest=SyncV2Codec.hash(org.json.JSONArray().put(grant.get("grantId")).put(objectId).put("complete")).take(32)
            val response=client.complete(objectId,reservation,completeRequest,raw.size)
            client.verifyReceipt(response.getJSONObject("receipt"),objectId,raw.size.toLong(),raw.size.toLong(),now)
        }
    }
}
