package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONArray
import org.json.JSONObject

/** C23: stateless grant validation. Approval is cloud author approval, not local pairing.
 * Original signed commit/ref bytes are retained; the phone never signs for the author. */
internal class SyncV2Relay(private val identity:SyncV2Identity,private val authors:SyncV2Authors,
    private val client:SyncV2CloudClient,private val store:SyncV2ContentStore,
    private val enabled:Boolean=com.lightningstudio.watchrss.phone.BuildConfig.SYNC_OBJECTS_V2_RELAY,
    private val signals:()->SyncV2TransportSignals={SyncV2TransportSignals()},private val allowChargingCellular:()->Boolean={true}) {
    fun validate(grant:JSONObject,commit:JSONObject,update:JSONObject,now:Long):JSONObject {
        if(!enabled)SyncV2Codec.fail("feature_disabled")
        SyncV2Codec.exact(grant,setOf("body","signature"));val b=grant.getJSONObject("body")
        SyncV2Codec.exact(b,setOf("version","scope","authorDeviceId","relayDeviceId","ref","commitOid","objectOids","expiresAtMillis","grantId"))
        if(b.get("version")!=2)SyncV2Codec.fail("invalid_schema")
        if(SyncV2Codec.scope(b.getJSONObject("scope"))!=identity.scopeJson || SyncV2Codec.scope(client.scope)!=identity.scopeJson)SyncV2Codec.fail("scope_mismatch")
        val author=SyncV2LocalProtocol.id(SyncV2Wire.string(b,"authorDeviceId"));val relay=SyncV2LocalProtocol.id(SyncV2Wire.string(b,"relayDeviceId"))
        SyncV2LocalProtocol.id(SyncV2Wire.string(b,"grantId"));authors.publicKey(relay)
        val pub=authors.publicKey(author)
        if(relay!=client.deviceId || author==relay || b.get("ref")!="refs/devices/$author/index")SyncV2Codec.fail("relay_denied")
        val expiry=SyncV2Wire.uint(b,"expiresAtMillis")
        if(expiry<now-30000 || expiry>now+630000)SyncV2Codec.fail("relay_denied")
        val oids=SyncV2Wire.strings(b.getJSONArray("objectOids"),512);oids.forEach{SyncV2Wire.oid(it)}
        if(!SyncV2Identity.verifySignature(pub,SyncV2Codec.lp(SyncV2Codec.utf8("wrss.sync.v2/relay-grant-sign"),SyncV2LocalProtocol.bytes(b)),SyncV2Wire.string(grant,"signature")))SyncV2Codec.fail("signature_invalid")
        val cb=authors.header(commit,false);val oid=SyncV2Codec.oid("commit",cb)
        if(cb.get("authorDeviceId")!=author || b.get("commitOid")!=oid)SyncV2Codec.fail("relay_denied")
        verifyUpdate(update,author,pub,oid,now)
        if(update.getJSONObject("body").get("ref")!=b.get("ref"))SyncV2Codec.fail("relay_denied")
        if(update.getJSONObject("body").opt("expectedOid")!=cb.opt("previousOwnCommit"))SyncV2Codec.fail("relay_denied")
        return b
    }
    private fun verifyUpdate(update:JSONObject,author:String,pub:String,oid:String,now:Long) {
        SyncV2Codec.exact(update,setOf("body","signature"));val b=update.getJSONObject("body")
        SyncV2Codec.exact(b,setOf("version","scope","ref","expectedOid","expectedRevision","newCommitOid","requestId","issuedAtMillis","expiresAtMillis"))
        if(b.get("version")!=2 || SyncV2Codec.scope(b.getJSONObject("scope"))!=identity.scopeJson)SyncV2Codec.fail("scope_mismatch")
        if(b.get("newCommitOid")!=oid || b.get("ref") !in setOf("refs/devices/$author/index","refs/account/current"))SyncV2Codec.fail("relay_denied")
        if(!b.isNull("expectedOid"))SyncV2Wire.oid(b.getString("expectedOid"),"commit")
        SyncV2Codec.d64(b.getString("expectedRevision"));SyncV2LocalProtocol.id(b.getString("requestId"))
        val issued=SyncV2Wire.uint(b,"issuedAtMillis");val expiry=SyncV2Wire.uint(b,"expiresAtMillis")
        if(expiry<=issued || expiry-issued>300000 || issued>now+30000 || expiry<now-30000)SyncV2Codec.fail("relay_denied")
        if(!SyncV2Identity.verifySignature(pub,SyncV2Codec.lp(SyncV2Codec.utf8("wrss.sync.v2/ref-update-sign"),SyncV2LocalProtocol.bytes(b)),b64(update)))SyncV2Codec.fail("signature_invalid")
    }
    private fun b64(value:JSONObject)=SyncV2Wire.string(value,"signature")
    fun forward(commit:JSONObject,device:JSONObject,grant:JSONObject,account:JSONObject?,now:Long,
        budget:SyncV2ContentWorker.Budget=SyncV2ContentWorker.Budget()):JSONObject=client.withinBudget(budget.millis) {
        val b=validate(grant,commit,device,now)
        if(account!=null) {
            verifyUpdate(account,b.getString("authorDeviceId"),authors.publicKey(b.getString("authorDeviceId")),b.getString("commitOid"),now)
            if(account.getJSONObject("body").get("ref")!="refs/account/current" || account.getJSONObject("body").get("requestId")!=device.getJSONObject("body").get("requestId"))SyncV2Codec.fail("relay_denied")
        }
        // Persist the exact original signed payload before the network call. No author bearer.
        val job=JSONObject().put("commit",commit).put("device",device).put("account",account?:JSONObject.NULL).put("relayGrant",grant)
        val key="relay/${b.getString("grantId")}";val old=store.load(key)
        if(old!=null && SyncV2Codec.canonical(old)!=SyncV2Codec.canonical(job))SyncV2Codec.fail("idempotency_conflict")
        if(old==null)store.save(key,job)
        client.withinBudget(budget.millis-budget.reserveMillis){uploadOriginals(b,commit,now,budget.bytes)}
        validate(grant,commit,device,now)
        val request=if(account==null)JSONObject(device.toString()).put("relayGrant",grant)
            else JSONObject().put("account",account).put("device",device).put("relayGrant",grant)
        val result=if(account==null)client.stage(request) else client.publish(request)
        store.save("relay-result/${b.getString("grantId")}",result);result
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
