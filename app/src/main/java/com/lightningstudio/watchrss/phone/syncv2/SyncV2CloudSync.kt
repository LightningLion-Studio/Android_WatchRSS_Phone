package com.lightningstudio.watchrss.phone.syncv2

import com.lightningstudio.watchrss.phone.data.local.CloudExportFileWitness
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Index work has its own HTTP calls. No content worker or auth-polling mutex is acquired. */
internal class SyncV2CloudSync(private val client:SyncV2CloudClient,private val identity:SyncV2Identity,
    private val projection:SyncV2Projection,approvedLegacyDevice:(String)->Boolean,private val contentKey:(Long)->ByteArray,
    private val now:()->Long=System::currentTimeMillis,
    private val files:File?=null) {
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
        val expires=window.getLong("expiresAtMillis")
        var current=now()
        // A checkpoint anchors time at receipt, so transport latency can leave it behind
        // a later negotiate window. Refresh authenticated time before rejecting its horizon.
        if(expires>current+300000) {client.checkpoint(current);current=now()}
        if(expires<=current || expires>current+300000)SyncV2Codec.fail("window_expired")
    }
    private data class SigningKeys(val response:JSONObject,val fingerprint:String)
    private fun keys(window:String):SigningKeys {
        val response=client.signingKeys(window);val revision=SyncV2Wire.string(response,"revocationRevision");SyncV2Codec.d64(revision)
        val bindings=response.getJSONArray("bindings");if(bindings.length()>64)SyncV2Codec.fail("invalid_schema")
        val current=sortedMapOf<String,JSONObject>()
        for(i in 0 until bindings.length()) {
            val binding=bindings.getJSONObject(i)
            authors.accept(binding,revision)
            val body=binding.getJSONObject("body")
            // Mapping signatures may be freshly generated for each response.
            val mapping=binding.optJSONObject("deviceMapping")?.getJSONObject("body")
            if(current.put(body.getString("deviceId"),JSONObject().put("binding",body).put("mapping",mapping))!=null)
                SyncV2Codec.fail("invalid_schema")
        }
        val fingerprint=SyncV2Codec.hash(JSONObject().put("revision",revision).put("bindings",JSONArray(current.values.toList())))
        projection.saveSigningBindings(bindings,revision,fingerprint)
        return SigningKeys(response,fingerprint)
    }

    internal data class PublishedDelta(
        val commitOid: String,
        val baseRoot: String,
        val delta: SyncV2Index.Delta,
        val objects: Map<String, ByteArray>
    )

    internal data class ReadResult(val status:String,val reused:Boolean,val serverRevision:String)

    fun readCurrent():String = readCurrentResult().status

    internal fun readCurrentResult(published: PublishedDelta? = null):ReadResult {
        capabilities() // Every error, including busy, ends this round. No catch-and-continue.
        // Use the authenticated checkpoint's own fixed window. A later negotiate may legitimately race it.
        val window=client.checkpoint(now()).getJSONObject("body");fresh(window);val id=window.getString("windowId")
        val signing=keys(id)
        val serverRevision=window.getString("serverRevision")
        val noteSegmentsRead=client.noteSegmentsRead()
        val account=window.getJSONObject("account")
        if(account.isNull("commitOid")) {
            if(projection.state().body.optJSONObject("account")!=null)SyncV2Codec.fail("replay_detected")
            return ReadResult("empty-account",false,serverRevision)
        }
        val observation=JSONObject().put("oid",account.getString("commitOid")).put("revision",account.getString("revision")).put("generation",account.getString("rollbackGeneration"))
        val known=projection.state().body
        val prior=known.optJSONObject("account")
        if(prior!=null) {
            if(SyncV2Codec.d64(observation.getString("generation"))<SyncV2Codec.d64(prior.getString("generation")) || SyncV2Codec.d64(observation.getString("revision"))<SyncV2Codec.d64(prior.getString("revision")))SyncV2Codec.fail("replay_detected")
            val accepted=known.optJSONArray("knownAccountParents")?:JSONArray()
            if(observation.getString("oid")!=prior.getString("oid") && ((0 until accepted.length()).any{accepted.getJSONObject(it).getString("oid")==observation.getString("oid")} || projection.accountKnownHistoricalOid(observation.getString("oid"))))SyncV2Codec.fail("replay_detected")
        }
        fun result(reused:Boolean)=ReadResult(
            if(projection.status()=="awaiting-user")"awaiting-user" else "metadata-ready",reused,serverRevision
        )
        fresh(window)
        if(projection.reuseCloudIndex(observation,serverRevision,signing.fingerprint,noteSegmentsRead))return result(true)
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
        val closure=SyncV2ControlClosure(identity,authors,contentKey,noteSegmentsRead)
        fun readHeader(oid: String): JSONObject {
            // Permanent signed headers remain available after historical content has expired.
            val page=client.ancestry(oid,null);val values=page.getJSONArray("headers")
            if(page.getString("head")!=oid || !page.isNull("stop") || values.length() !in 1..64)SyncV2Codec.fail("ref_continuity_unavailable")
            val first=values.getJSONObject(0);SyncV2Codec.exact(first,setOf("commitOid","headerOid","body","signature"))
            val envelope=JSONObject().put("body",first.getJSONObject("body")).put("signature",first.getString("signature"))
            if(first.getString("commitOid")!=oid || SyncV2Codec.oid("commit-header",envelope)!=first.getString("headerOid"))SyncV2Codec.fail("hash_mismatch")
            fresh(window)
            return envelope
        }
        val localPublication = published?.takeIf { it.commitOid == head }
        fun readObject(oid: String): ByteArray = localPublication?.objects?.get(oid)
            ?: if (projection.cloudObjectExists(oid)) projection.accountObject(oid) else client.raw(oid,id)
        fun readReference(parent: String): JSONObject = client.reference(parent,id).let {
            JSONObject(it.toString()).apply { remove("version") }
        }
        val base = projection.state()
        val signed = authors.header(headers.first())
        val oldRoot = projection.sparseReadRoot(base, signed, observation.getString("generation"), signing.fingerprint, noteSegmentsRead)
        val delta = oldRoot?.let {
            localPublication?.takeIf { publication ->
                publication.baseRoot == it && publication.delta.edit.tree.root == signed.getString("treeRoot")
            }?.delta ?: SyncV2Index.delta(identity.scope, it, signed.getString("treeRoot"), projection::accountObject, ::readObject)
        }
        if (delta != null) {
            closure.verifyStaged(headers.first(), ::readObject, ::readReference, null, delta = delta).use { verified ->
                // Newly isolated notes need the full install path to preserve pending local edits.
                if (verified.rows.none { it.has(SyncV2NoteWiring.ISOLATED_KEY) }) {
                    fresh(window)
                    projection.installCloudDelta(observation, headers, base, delta, verified, authors::header,
                        serverRevision, signing.fingerprint, noteSegmentsRead, ::readHeader)
                    return result(false)
                }
            }
        }
        val verified=projection.verifyCloud(closure,headers.first(),{oid->client.raw(oid,id)},::readReference,
            SyncV2ControlFetch.Remote({oids->client.batch(id,oids)}){parents->
                client.references(parents,id).mapValues{(_,v)->v?.let{JSONObject(it.toString()).apply{remove("version")}}}
            })
        verified.use {
            fresh(window)
            projection.installCloudIndex(observation,headers,verified,authors::header,serverRevision,
                readHeader=::readHeader,signingFingerprint=signing.fingerprint,noteSegmentsRead=noteSegmentsRead)
        }
        return result(false)
    }
    fun resumePublish():JSONObject? {
        val request=projection.pendingPublish()?:return null
        capabilities() // A restart has not authenticated this HTTP client's capabilities yet.
        val result=client.requestResult(request.getJSONObject("account").getJSONObject("body").getString("requestId"))
        if(result.getString("status")=="succeeded") {
            val response=result.getJSONObject("response");projection.confirmPublish(request,response);return response
        }
        val state=projection.state()
        if(state.body.optString("pendingPublishLocalRevision")!=state.revision)SyncV2Codec.fail("source_changed")
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
            val read=readCurrentResult()
            val rebound=if(read.status=="empty-account")cursor else read.serverRevision
            projection.saveEventCursor(rebound)
            return JSONObject().put("status",read.status).put("historyExpired",true).put("cursor",rebound)
        };fresh(events)
        val current=before.optJSONObject("account");val cloud=events.getJSONObject("account")
        // Account generation survives event retention. Always authenticate the current head before cursor advance.
        if(current==null || cloud.optString("commitOid")!=current.getString("oid") || cloud.getString("rollbackGeneration")!=current.getString("generation"))readCurrent()
        projection.saveEventCursor(SyncV2Wire.string(events,"cursor"));return events
    }
    /** P2 normal publication only. Rollback/choice/history write paths remain closed. */
    fun publishIndex(signer:SyncV2Signing,contentKey:ByteArray,keyVersion:Long,scannedRevision:String?=null,
        resetAdditions:Boolean=false, extraIds:Set<String> = emptySet(),
        prepareContent: ((Set<String>?, Set<String>) -> Boolean)? = null,
        observePublished: ((PublishedDelta?) -> Unit)? = null):JSONObject {
        resumePublish()?.let { observePublished?.invoke(null); return it }
        fun sourceCurrent() {
            if(scannedRevision!=null && (projection.state().revision!=scannedRevision || !projection.isWindowComplete()))SyncV2Codec.fail("source_changed")
        }
        sourceCurrent()
        val caps=capabilities()
        if(caps.getJSONObject("flags").get("indexWrite")!=true)throw SyncV2CloudError("feature_disabled",true,1000)
        if(signer.deviceId!=client.deviceId)SyncV2Codec.fail("authentication_required")
        var window=client.negotiate();fresh(window)
        val registrations=keys(window.getString("windowId")).response
        val bindings=registrations.getJSONArray("bindings")
        if((0 until bindings.length()).none{bindings.getJSONObject(it).getJSONObject("body").getString("deviceId")==signer.deviceId}) {
            client.registerSigningKey(projection.prepareOwnSigningBinding(signer,now(),registrations.getString("revocationRevision")))
            window=client.negotiate();fresh(window);keys(window.getString("windowId"))
        }
        sourceCurrent()
        val st=projection.state()
        if(!st.body.isNull("pendingChoice"))SyncV2Codec.fail("choice_pending")
        val account=window.getJSONObject("account");val accepted=st.body.optJSONObject("account")
        if(!account.isNull("commitOid") && (accepted==null || accepted.getString("oid")!=account.getString("commitOid") || accepted.getString("generation")!=account.getString("rollbackGeneration")))throw SyncV2CloudError("ref_conflict",true)
        if(!account.isNull("commitOid") && st.body.optJSONObject("metadataAccount")?.optString("oid")!=account.getString("commitOid"))SyncV2Codec.fail("closure_incomplete")
        val sparseRoot = if (resetAdditions) null else projection.sparsePublicationRoot(st)
        val ids = if (sparseRoot == null) null else
            (projection.publicationIds() + projection.publicationMergeIds() + extraIds).takeIf { it.size <= 64 }
        val fileRevision = files?.let(CloudExportFileWitness::captureForProjection)
        val recovery = files?.let { SyncV2RecoveryStorage(projection,identity,it) }
        val localIndex = projection.prepareWireIndex(signer, contentKey, keyVersion, resetAdditions, ids,
            recovery?.let { storage -> { record, prior ->
                storage.publicationFailure(record,prior)?.also { if (!storage.isMissingOriginal(it)) throw it }
            } })
        try {
        val preparedBodies = if (prepareContent?.invoke(ids, localIndex.heldOriginals) == true) {
            sourceCurrent()
            projection.wirePublicationIds()
        } else emptySet()
        // The body workers change row references only. The first preparation's sealed
        // metadata and state remain valid and are reused for the final tree.
        val mergedRows = localIndex.proposedRows.associate { row ->
            val key = row.getString("entityKey")
            val prepared = if (key in preparedBodies && !row.getBoolean("deleted")) projection.wireContentRow(key) else null
            key to if (prepared != null && listOf("metaOid", "stateOid").all { prepared.get(it) == row.get(it) } &&
                SyncV2Codec.d64(prepared.getString("rowSequence")) > SyncV2Codec.d64(row.getString("rowSequence"))) {
                prepared
            } else row
        }.toMutableMap()
        val previousWire = mutableMapOf<String, JSONObject>()
        val cloudObjects = if (ids == null) projection.cloudObjects() else emptyMap()
        val mergedObjects = objectOverlay(cloudObjects, localIndex.objects)
        val newRefs=localIndex.refs.toMutableList()
        for(cloud in projection.cloudRows(ids)) {
            val meta=cloud.getJSONObject("meta");val entity=meta.getString("entityKey")
            val row = cloud.getJSONObject("row")
            if (ids != null) previousWire[entity] = row
            if(entity in localIndex.heldOriginals || cloud.has(SyncV2NoteWiring.ISOLATED_KEY)) {
                // An isolated note or a held local version cannot replace this authenticated row.
                mergedRows[entity]=row
                continue
            }
            val remoteState=cloud.getJSONObject("state")
            if (resetAdditions && meta.getString("kind") != "note" && entity in mergedRows) {
                // The planned wire row already merges this candidate with this authenticated head.
                continue
            }
            val candidate=projection.candidate(meta.getString("kind"),meta.getString("logicalId"))
            var preservesConflictFacts=false
            var localEdit=false
            if(candidate!=null && (SyncV2Codec.canonical(candidate.first)!=SyncV2Codec.canonical(meta) || SyncV2Codec.canonical(candidate.second)!=SyncV2Codec.canonical(remoteState))) {
                if (resetAdditions) SyncV2Codec.fail("source_changed")
                if(meta.getString("kind")=="note" && SyncV2Codec.canonical(candidate.first)==SyncV2Codec.canonical(meta) && st.body.optString("activeGeneration")==account.getString("rollbackGeneration")) {
                    val local=SyncV2Business.join("note",candidate.first,candidate.second);val remote=SyncV2Business.join("note",meta,remoteState)
                    val localSet=SyncV2NoteConflicts.declared(local)
                    val remoteSet=SyncV2NoteConflicts.declared(remote)
                    local.remove("noteConflicts");remote.remove("noteConflicts")
                    if(localSet!=null && (remoteSet!=null || localSet.getJSONArray("records").length()>0)) {
                        val merged=if(remoteSet==null)localSet else SyncV2NoteConflicts.merge(localSet,remoteSet,meta.getString("logicalId"))
                        preservesConflictFacts=SyncV2LocalJson.canonical(local)==SyncV2LocalJson.canonical(remote) && SyncV2Codec.canonical(localSet)==SyncV2Codec.canonical(merged)
                    }
                }
                val base=projection.accountBase(entity)
                val original=base.second?.let{value->SyncV2Business.project(meta.getString("kind"),meta.getString("logicalId"),entity,value,null).also {
                    it.meta.put("addedAtSource",meta.getString("addedAtSource"))
                }}
                localEdit=st.body.optJSONObject("editBase")?.optString("accountOid")==account.getString("commitOid") &&
                    base.first && original!=null && SyncV2Codec.canonical(original.meta)==SyncV2Codec.canonical(meta) && SyncV2Codec.canonical(original.state)==SyncV2Codec.canonical(remoteState)
                if(!preservesConflictFacts && !localEdit)SyncV2Codec.fail("merge_unavailable")
            }
            val ready=mergedRows[entity]
            val fulfillsPromise=ready!=null && !row.isNull("promiseId") && ready.isNull("promiseId") && !ready.isNull("recipeOid") &&
                listOf("bodyRevision","metaOid","stateOid").all{ready.get(it)==row.get(it)} && SyncV2Codec.d64(ready.getString("rowSequence"))>SyncV2Codec.d64(row.getString("rowSequence"))
            // A locally verified P6 original may replace a random placeholder without editing business state.
            // This witness is device-local encrypted cache data; it never enters the wire.
            val bindsIndependent=ready!=null && meta.getString("kind") in setOf("asset","book") && candidate!=null &&
                SyncV2Codec.canonical(candidate.first)==SyncV2Codec.canonical(meta) && SyncV2Codec.canonical(candidate.second)==SyncV2Codec.canonical(remoteState) &&
                !row.getBoolean("deleted") && row.isNull("recipeOid") && ready.isNull("recipeOid") && !ready.isNull("promiseId") &&
                ready.getString("bodyRevision")==projection.independentBodyRevision(entity) && ready.get("bodyRevision")!=row.get("bodyRevision") &&
                listOf("metaOid","stateOid").all{ready.get(it)==row.get(it)} && SyncV2Codec.d64(ready.getString("rowSequence"))>SyncV2Codec.d64(row.getString("rowSequence"))
            mergedRows[entity]=if(bindsIndependent || fulfillsPromise || preservesConflictFacts || localEdit)ready?:SyncV2Codec.fail("closure_incomplete")else row
        }
        val edit = if (ids == null) null
            else SyncV2Index.edit(identity.scope, requireNotNull(sparseRoot), mergedRows, projection::accountObject)
        val tree = edit?.tree ?: SyncV2Index.build(identity.scope, mergedRows.values.toList())
        val newObjects = objectOverlay(localIndex.objects, tree.objects)
        val available = objectOverlay(mergedObjects, tree.objects)
        val reachable=mutableSetOf<String>();reachable.addAll(tree.objects.keys)
        for(row in mergedRows.values){reachable.add(row.getString("metaOid"));reachable.add(row.getString("stateOid"))}
        // New child refs and ciphertext are reachable through their registered parent state.
        val refCandidates = if (ids == null) available else newObjects
        val refsByState = refCandidates.keys.filter { it.startsWith("state-refs:") }.map { oid ->
            oid to SyncV2Wire.parse(refCandidates.getValue(oid),262144,true).getJSONObject("body")
        }.groupBy { it.second.getString("stateOid") }
        val states = ArrayDeque(reachable.filter { it.startsWith("state:") })
        while (states.isNotEmpty()) {
            for ((oid, body) in refsByState[states.removeFirst()].orEmpty()) {
                if (!reachable.add(oid)) continue
                val children = body.getJSONArray("segmentOids")
                for (i in 0 until children.length()) {
                    val child = children.getString(i)
                    if (reachable.add(child)) states.addLast(child)
                }
            }
        }
        val uploadObjects = newObjects.selectObjects { it in reachable }
        val reachableRefs = newRefs.filter { it.getJSONObject("body").getString("stateOid") in reachable }
        val refs=window.getJSONArray("refs");var own:String?=null;var revision="0"
        for(i in 0 until refs.length()) {val ref=refs.getJSONObject(i);if(ref.getString("deviceId")==signer.deviceId) {own=ref.getString("commitOid");revision=ref.getString("refRevision")}}
        val ownSequence=if(own==null)"0" else {
            val header=SyncV2Wire.parse(client.raw(own),262144,true);val b=authors.header(header,false)
            if(b.getString("authorDeviceId")!=signer.deviceId || SyncV2Codec.oid("commit",b)!=own)SyncV2Codec.fail("identity_conflict")
            b.getString("deviceSequence")
        }
        val body=JSONObject().put("version",2).put("scope",identity.scope).put("authorDeviceId",signer.deviceId)
            .put("deviceSequence",projection.reserveDeviceSequence(ownSequence)).put("previousOwnCommit",own?:JSONObject.NULL).put("mergedParents",JSONArray())
            .put("treeRoot",tree.root).put("requiredFeatures",JSONArray()).put("createdAtMillis",now()).put("observedRefs",JSONArray())
            .put("indexComplete",true).put("accountParent",account.get("commitOid")).put("rollbackGeneration",account.getString("rollbackGeneration"))
            .put("operation",JSONObject().put("kind","normal").put("rollbackTarget",JSONObject.NULL).put("resolvesGeneration",JSONObject.NULL))
        val signed=signer.commit(body);val oid=SyncV2Codec.oid("commit",body)
        val objects = objectOverlay(uploadObjects, mapOf(oid to SyncV2Codec.utf8(SyncV2Codec.canonical(signed))))
        // Each reservation is bounded. Control upload never requests a content slot.
        val toUpload = objects.keys.filter {
            it.substringBefore(':') in setOf("meta","state","tree","commit") && !projection.cloudObjectExists(it)
        }.sorted().map { key -> object: Map.Entry<String, ByteArray> {
            override val key = key
            override val value get() = objects.getValue(key)
        } }
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
        for(ref in reachableRefs)if(!projection.cloudObjectExists(SyncV2Codec.oid("state-refs",ref)))client.stateRefs(ref)
        fresh(window)
        val publishState=projection.state()
        if(publishState.revision!=st.revision)SyncV2Codec.fail("choice_stale")
        val id=randomId();val old=if(account.isNull("commitOid"))null else account.getString("commitOid")
        val request=JSONObject().put("account",SyncV2CloudClient.refUpdate(signer,identity.scope,"refs/account/current",old,account.getString("revision"),oid,id,now()))
            .put("device",SyncV2CloudClient.refUpdate(signer,identity.scope,"refs/devices/${signer.deviceId}/index",own,revision,oid,id,now())).put("relayGrant",JSONObject.NULL)
        fun persist() = projection.persistPublish(request,publishState.version,localIndex.heldOriginals)
        if (files != null && fileRevision != null) CloudExportFileWitness.withProjectionCapture(files,fileRevision,::persist)
        else persist()
        val response = publishAttempt(request)
        projection.confirmPublish(request,response)
        if (observePublished != null) {
            val delta = edit?.let {
                val changes = mergedRows.filter { (entity, row) ->
                    SyncV2Codec.canonical(row) != SyncV2Codec.canonical(previousWire[entity])
                }
                PublishedDelta(oid, requireNotNull(sparseRoot), SyncV2Index.Delta(changes, it), objects)
            }
            observePublished(delta)
        }
        return response
        } finally {localIndex.close()}
    }
    private fun randomId()=SyncV2Codec.hex(SyncV2Codec.random(16))
}
