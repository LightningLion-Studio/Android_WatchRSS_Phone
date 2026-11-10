package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONArray
import org.json.JSONObject

/** Index work has its own HTTP calls. No content worker or auth-polling mutex is acquired. */
internal class SyncV2CloudSync(private val client:SyncV2CloudClient,private val identity:SyncV2Identity,
    private val projection:SyncV2Projection,approvedLegacyDevice:(String)->Boolean,private val contentKey:(Long)->ByteArray,
    private val now:()->Long=System::currentTimeMillis) {
    private val authors=client.authors(identity,approvedLegacyDevice)
    /** Capabilities are the only source for the C7 server announcement (25.8 negotiation). */
    private fun capabilities():JSONObject=client.capabilities().also{projection.noteSegmentsServerAnnounced=client.noteSegmentsWrite()}
    internal fun clientCapabilities():JSONObject=capabilities()
    init {
        val saved=projection.state().body.optJSONArray("signingBindings")?:JSONArray()
        for(i in 0 until saved.length())authors.restorePinned(saved.getJSONObject(i))
    }
    private fun fresh(window:JSONObject) {
        SyncV2CloudClient.window(window)
        if(window.getLong("expiresAtMillis")<=now() || window.getLong("expiresAtMillis")>now()+300000)SyncV2Codec.fail("window_expired")
    }
    private fun keys(window:String):JSONObject {
        val response=client.signingKeys(window);val revision=SyncV2Wire.string(response,"revocationRevision");SyncV2Codec.d64(revision)
        val bindings=response.getJSONArray("bindings");if(bindings.length()>64)SyncV2Codec.fail("invalid_schema")
        for(i in 0 until bindings.length())authors.accept(bindings.getJSONObject(i),revision)
        projection.saveSigningBindings(bindings,revision)
        return response
    }
    fun readCurrent():String {
        capabilities() // Every error, including busy, ends this round. No catch-and-continue.
        // Use the authenticated checkpoint's own fixed window. A later negotiate may legitimately race it.
        val window=client.checkpoint(now()).getJSONObject("body");fresh(window);val id=window.getString("windowId")
        keys(id)
        val account=window.getJSONObject("account")
        if(account.isNull("commitOid")) {
            if(projection.state().body.optJSONObject("account")!=null)SyncV2Codec.fail("replay_detected")
            return "empty-account"
        }
        val observation=JSONObject().put("oid",account.getString("commitOid")).put("revision",account.getString("revision")).put("generation",account.getString("rollbackGeneration"))
        val known=projection.state().body
        val prior=known.optJSONObject("account")
        if(prior!=null) {
            if(SyncV2Codec.d64(observation.getString("generation"))<SyncV2Codec.d64(prior.getString("generation")) || SyncV2Codec.d64(observation.getString("revision"))<SyncV2Codec.d64(prior.getString("revision")))SyncV2Codec.fail("replay_detected")
            val accepted=known.optJSONArray("knownAccountParents")?:JSONArray()
            if(observation.getString("oid")!=prior.getString("oid") && ((0 until accepted.length()).any{accepted.getJSONObject(it).getString("oid")==observation.getString("oid")} || projection.accountKnownHistoricalOid(observation.getString("oid"))))SyncV2Codec.fail("replay_detected")
        }
        if(projection.reuseCloudIndex(observation,window.getString("serverRevision")))return if(projection.status()=="awaiting-user")"awaiting-user" else "metadata-ready"
        val head=account.getString("commitOid");val pin=projection.state().body.optJSONObject("account")?.getString("oid")
        val headers=mutableListOf<JSONObject>();var at:String?=head;val visited=mutableSetOf<String>()
        // Even when head==pin, fetch the current header to authenticate the full control closure.
        if(head==pin)headers.add(SyncV2Wire.parse(client.raw(head),262144,true))
        else while(at!=pin) {
            if(at==null || !visited.add(at) || headers.size>=4096)SyncV2Codec.fail("ref_continuity_unavailable")
            val page=client.ancestry(at,pin)
            if(page.get("head")!=at || (if(page.isNull("stop"))null else page.getString("stop"))!=pin)SyncV2Codec.fail("ref_continuity_unavailable")
            val values=page.getJSONArray("headers");if(values.length() !in 1..64)SyncV2Codec.fail("ref_continuity_unavailable")
            for(i in 0 until values.length()) {
                val v=values.getJSONObject(i);SyncV2Codec.exact(v,setOf("commitOid","headerOid","body","signature"))
                val envelope=JSONObject().put("body",v.getJSONObject("body")).put("signature",v.getString("signature"))
                val body=authors.header(envelope)
                if(v.getString("commitOid")!=at || SyncV2Codec.oid("commit",body)!=at || SyncV2Codec.oid("commit-header",envelope)!=v.getString("headerOid"))SyncV2Codec.fail("hash_mismatch")
                headers.add(envelope);at=if(body.isNull("accountParent"))null else body.getString("accountParent")
                if(at==pin && i!=values.length()-1)SyncV2Codec.fail("invalid_schema")
            }
            val next=if(page.isNull("nextOid"))null else page.getString("nextOid")
            if(next!=at || page.get("complete")!=(at==pin))SyncV2Codec.fail("ref_continuity_unavailable")
        }
        val closure=SyncV2ControlClosure(identity,authors,contentKey,client.noteSegmentsRead())
        val verified=closure.verify(headers.first(),{oid->client.raw(oid,id)}) { parent ->
            client.reference(parent,id).let { JSONObject(it.toString()).apply { remove("version") } }
        }
        fresh(window)
        projection.installCloudIndex(observation,headers,verified,authors::header,window.getString("serverRevision")) { oid ->
            // Permanent signed headers remain available after historical content has expired.
            val page=client.ancestry(oid,null);val values=page.getJSONArray("headers")
            if(page.getString("head")!=oid || !page.isNull("stop") || values.length() !in 1..64)SyncV2Codec.fail("ref_continuity_unavailable")
            val first=values.getJSONObject(0);SyncV2Codec.exact(first,setOf("commitOid","headerOid","body","signature"))
            val envelope=JSONObject().put("body",first.getJSONObject("body")).put("signature",first.getString("signature"))
            if(first.getString("commitOid")!=oid || SyncV2Codec.oid("commit-header",envelope)!=first.getString("headerOid"))SyncV2Codec.fail("hash_mismatch")
            fresh(window);envelope
        }
        return if(projection.status()=="awaiting-user")"awaiting-user" else "metadata-ready"
    }
    fun resumePublish():JSONObject? {
        val request=projection.pendingPublish()?:return null
        capabilities() // A restart has not authenticated this HTTP client's capabilities yet.
        val response=publishAttempt(request);projection.confirmPublish(request,response);return response
    }
    private fun publishAttempt(request:JSONObject):JSONObject = try{client.publish(request)}catch(e:SyncV2CloudError) {
        if(e.code in setOf("ref_conflict","rollback_conflict"))projection.rejectPublish(request,e.code)
        throw e
    }
    fun poll():JSONObject {
        capabilities()
        val before=projection.state().body
        val cursor=before.optString("eventCursor","0")
        val events=try{client.events(cursor)}catch(e:SyncV2CloudError){
            if(e.code!="history_expired")throw e
            val status=readCurrent()
            val rebound=projection.state().body.optString("metadataServerRevision",cursor)
            projection.saveEventCursor(rebound)
            return JSONObject().put("status",status).put("historyExpired",true).put("cursor",rebound)
        };fresh(events)
        val current=before.optJSONObject("account");val cloud=events.getJSONObject("account")
        // Account generation survives event retention. Always authenticate the current head before cursor advance.
        if(current==null || cloud.optString("commitOid")!=current.getString("oid") || cloud.getString("rollbackGeneration")!=current.getString("generation"))readCurrent()
        projection.saveEventCursor(SyncV2Wire.string(events,"cursor"));return events
    }
    /** P2 normal publication only. Rollback/choice/history write paths remain closed. */
    fun publishIndex(signer:SyncV2Signing,contentKey:ByteArray,keyVersion:Long):JSONObject {
        resumePublish()?.let{return it}
        val caps=capabilities()
        if(caps.getJSONObject("flags").get("indexWrite")!=true)throw SyncV2CloudError("feature_disabled",true,1000)
        if(signer.deviceId!=client.deviceId)SyncV2Codec.fail("authentication_required")
        var window=client.negotiate();fresh(window)
        val registrations=keys(window.getString("windowId"))
        val bindings=registrations.getJSONArray("bindings")
        if((0 until bindings.length()).none{bindings.getJSONObject(it).getJSONObject("body").getString("deviceId")==signer.deviceId}) {
            client.registerSigningKey(projection.prepareOwnSigningBinding(signer,now(),registrations.getString("revocationRevision")))
            window=client.negotiate();fresh(window);keys(window.getString("windowId"))
        }
        val st=projection.state()
        if(!st.body.isNull("pendingChoice"))SyncV2Codec.fail("choice_pending")
        val account=window.getJSONObject("account");val accepted=st.body.optJSONObject("account")
        if(!account.isNull("commitOid") && (accepted==null || accepted.getString("oid")!=account.getString("commitOid") || accepted.getString("generation")!=account.getString("rollbackGeneration")))throw SyncV2CloudError("ref_conflict",true)
        if(!account.isNull("commitOid") && st.body.optJSONObject("metadataAccount")?.optString("oid")!=account.getString("commitOid"))SyncV2Codec.fail("closure_incomplete")
        val localIndex=projection.prepareWireIndex(signer,contentKey,keyVersion)
        // Without an authenticated operation base, a differing local row cannot overwrite a cloud row.
        // Missing local rows are retained from cloud, never interpreted as deletions.
        val mergedRows=SyncV2Index.read(identity.scope,localIndex.tree.root,localIndex.objects::getValue).associateBy{it.getString("entityKey")}.toMutableMap()
        val mergedObjects=projection.cloudObjects().toMutableMap().apply{putAll(localIndex.objects)}
        val newRefs=localIndex.refs.toMutableList()
        for(cloud in projection.cloudRows()) {
            val meta=cloud.getJSONObject("meta");val entity=meta.getString("entityKey")
            if(cloud.has(SyncV2NoteWiring.ISOLATED_KEY)) {
                // F1/R3: an isolated row is already the authenticated verbatim row; the normal
                // publish keeps it untouched and never interprets the local candidate against it.
                mergedRows[entity]=cloud.getJSONObject("row")
                continue
            }
            val remoteState=cloud.getJSONObject("state");val row=cloud.getJSONObject("row")
            val candidate=projection.candidate(meta.getString("kind"),meta.getString("logicalId"))
            var preservesConflictFacts=false
            if(candidate!=null && (SyncV2Codec.canonical(candidate.first)!=SyncV2Codec.canonical(meta) || SyncV2Codec.canonical(candidate.second)!=SyncV2Codec.canonical(remoteState))) {
                if(meta.getString("kind")=="note" && SyncV2Codec.canonical(candidate.first)==SyncV2Codec.canonical(meta) && st.body.optString("activeGeneration")==account.getString("rollbackGeneration")) {
                    val local=SyncV2Business.join("note",candidate.first,candidate.second);val remote=SyncV2Business.join("note",meta,remoteState)
                    val merged=SyncV2NoteConflicts.merge(local.getJSONObject("noteConflicts"),remote.getJSONObject("noteConflicts"),meta.getString("logicalId"))
                    val localSet=local.remove("noteConflicts");remote.remove("noteConflicts")
                    preservesConflictFacts=SyncV2LocalJson.canonical(local)==SyncV2LocalJson.canonical(remote) && SyncV2Codec.canonical(localSet)==SyncV2Codec.canonical(merged)
                }
                if(!preservesConflictFacts)SyncV2Codec.fail("merge_unavailable")
            }
            val ready=mergedRows[entity]
            val fulfillsPromise=ready!=null && !row.isNull("promiseId") && ready.isNull("promiseId") && !ready.isNull("recipeOid") &&
                listOf("bodyRevision","metaOid","stateOid").all{ready.get(it)==row.get(it)} && SyncV2Codec.d64(ready.getString("rowSequence"))>SyncV2Codec.d64(row.getString("rowSequence"))
            mergedRows[entity]=if(fulfillsPromise || preservesConflictFacts)ready?:SyncV2Codec.fail("closure_incomplete")else row
        }
        val tree=SyncV2Index.build(identity.scope,mergedRows.values.toList());mergedObjects.putAll(tree.objects)
        val reachable=mutableSetOf<String>();reachable.addAll(tree.objects.keys)
        for(row in mergedRows.values){reachable.add(row.getString("metaOid"));reachable.add(row.getString("stateOid"))}
        // A segmented parent's sealed children are reachable only through its refs table: the
        // closure walk carries child refs and child bytes (25.4/25.7), iterating to a fixpoint.
        var carry=true
        while(carry) {
            carry=false
            for(ref in newRefs)if(ref.getJSONObject("body").getString("stateOid") in reachable) {
                if(reachable.add(SyncV2Codec.oid("state-refs",ref))) {
                    val children=ref.getJSONObject("body").getJSONArray("segmentOids")
                    for(i in 0 until children.length())reachable.add(children.getString(i))
                    carry=true
                }
            }
            for((oid,bytes)in mergedObjects)if(oid.startsWith("state-refs:")&&oid !in reachable) {
                val ref=SyncV2Wire.parse(bytes,262144,true)
                if(ref.getJSONObject("body").getString("stateOid") in reachable) {
                    reachable.add(oid)
                    val children=ref.getJSONObject("body").getJSONArray("segmentOids")
                    for(i in 0 until children.length())reachable.add(children.getString(i))
                    carry=true
                }
            }
        }
        val index=SyncV2Projection.WireIndex(tree,mergedObjects.filterKeys{it in reachable},newRefs.filter{it.getJSONObject("body").getString("stateOid") in reachable})
        val refs=window.getJSONArray("refs");var own:String?=null;var revision="0"
        for(i in 0 until refs.length()) {val ref=refs.getJSONObject(i);if(ref.getString("deviceId")==signer.deviceId) {own=ref.getString("commitOid");revision=ref.getString("refRevision")}}
        val ownSequence=if(own==null)"0" else {
            val header=SyncV2Wire.parse(client.raw(own),262144,true);val b=authors.header(header,false)
            if(b.getString("authorDeviceId")!=signer.deviceId || SyncV2Codec.oid("commit",b)!=own)SyncV2Codec.fail("identity_conflict")
            b.getString("deviceSequence")
        }
        val body=JSONObject().put("version",2).put("scope",identity.scope).put("authorDeviceId",signer.deviceId)
            .put("deviceSequence",projection.reserveDeviceSequence(ownSequence)).put("previousOwnCommit",own?:JSONObject.NULL).put("mergedParents",JSONArray())
            .put("treeRoot",index.tree.root).put("requiredFeatures",JSONArray()).put("createdAtMillis",now()).put("observedRefs",JSONArray())
            .put("indexComplete",true).put("accountParent",account.get("commitOid")).put("rollbackGeneration",account.getString("rollbackGeneration"))
            .put("operation",JSONObject().put("kind","normal").put("rollbackTarget",JSONObject.NULL).put("resolvesGeneration",JSONObject.NULL))
        val signed=signer.commit(body);val oid=SyncV2Codec.oid("commit",body)
        val objects=index.objects.toMutableMap();objects[oid]=SyncV2Codec.utf8(SyncV2Codec.canonical(signed))
        // Each reservation is bounded. Control upload never requests a content slot.
        val currentOids=projection.cloudObjects().keys
        val toUpload=objects.entries.filter{it.key !in currentOids && it.key.substringBefore(':') in setOf("meta","state","tree","commit")}.sortedBy{it.key}
        for(batch in toUpload.chunked(64)) {
            val reserve=client.reserve(randomId(),batch.map{it.key to it.value.size});val reservation=reserve.getString("reservationId")
            // C11 also applies to control objects: only a current authenticated status
            // query may establish an offset, never an idempotent reserve response.
            val entries=client.verifiedHaveWant(window.getString("windowId"),batch.map{it.key to it.value.size},now())
            for(item in batch) {
                val prior=entries.getValue(item.key)
                if(prior.getString("status")=="present")continue
                var offset=SyncV2Wire.uint(prior,"durableOffset",item.value.size.toLong()).toInt()
                if(offset%65536!=0 && offset!=item.value.size)SyncV2Codec.fail("invalid_schema")
                while(offset<item.value.size) {
                    val part=item.value.copyOfRange(offset,minOf(offset+65536,item.value.size))
                    val response=client.put(item.key,offset,reservation,randomId(),part)
                    if(response.getString("oid")!=item.key || SyncV2Wire.uint(response,"durableOffset")!=(offset+part.size).toLong())SyncV2Codec.fail("invalid_schema")
                    client.verifyReceipt(response.getJSONObject("receipt"),item.key,item.value.size.toLong(),(offset+part.size).toLong(),now())
                    offset+=part.size
                }
                val complete=client.complete(item.key,reservation,randomId(),item.value.size)
                client.verifyReceipt(complete.getJSONObject("receipt"),item.key,item.value.size.toLong(),item.value.size.toLong(),now())
            }
        }
        for(ref in index.refs)if(SyncV2Codec.oid("state-refs",ref) !in currentOids)client.stateRefs(ref)
        fresh(window)
        val publishState=projection.state()
        if(publishState.revision!=st.revision)SyncV2Codec.fail("choice_stale")
        val id=randomId();val old=if(account.isNull("commitOid"))null else account.getString("commitOid")
        val request=JSONObject().put("account",SyncV2CloudClient.refUpdate(signer,identity.scope,"refs/account/current",old,account.getString("revision"),oid,id,now()))
            .put("device",SyncV2CloudClient.refUpdate(signer,identity.scope,"refs/devices/${signer.deviceId}/index",own,revision,oid,id,now())).put("relayGrant",JSONObject.NULL)
        projection.persistPublish(request,publishState.version)
        val response=publishAttempt(request);projection.confirmPublish(request,response);return response
    }
    private fun randomId()=SyncV2Codec.hex(SyncV2Codec.random(16))
}
