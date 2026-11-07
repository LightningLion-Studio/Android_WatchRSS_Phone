package com.lightningstudio.watchrss.phone.syncv2

import com.lightningstudio.watchrss.phone.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Opt-in P5 product actions. UI supplies the ORIGINAL Preview handle, never a live boolean.
 * All network/file/crypto work is outside the existing projection's SQLite writer. */
internal class SyncV2AccountActions(private val client:SyncV2CloudClient,private val identity:SyncV2Identity,
    private val projection:SyncV2Projection,private val signer:SyncV2Signing,private val contentKey:(Long)->ByteArray,
    private val approved:(String)->Boolean,private val files:File,private val enabled:Boolean=BuildConfig.SYNC_OBJECTS_V2_HISTORY_RESTORE,
    private val trustedNow:()->Long?={null},recoveryQuotaBytes:Long=32L*1024*1024,
    private val fault:(String)->Unit={}) {
    private val db get()=projection.accountDatabase
    private val scope get()=projection.scope
    private val recovery=SyncV2RecoveryStorage(projection,identity,files,recoveryQuotaBytes)
    private val business=SyncV2PhoneApply(db)
    private val cloud=SyncV2CloudSync(client,identity,projection,approved,contentKey,now={now()})
    private fun gate(){if(!enabled)SyncV2Codec.fail("feature_disabled")}
    private fun id()=SyncV2Codec.hex(SyncV2Codec.random(16))
    private fun copy(value:JSONObject)=JSONObject(value.toString())
    private fun now():Long=trustedNow()?.also{if(it !in 0..9007199254740991L)SyncV2Codec.fail("invalid_schema")}?:SyncV2Codec.fail("trusted_time_unavailable")
    private fun account(a:JSONObject)=JSONObject().put("oid",a.getString("commitOid")).put("revision",a.getString("revision")).put("generation",a.getString("rollbackGeneration"))
    private fun values(rows:List<JSONObject>,planner:Boolean=false)=JSONObject().also{out->for(r in rows) {
        val m=r.getJSONObject("meta");val v=copy(r.getJSONObject("value"));if(planner)v.put("identity",m.getString("logicalId"));out.put(m.getString("entityKey"),v)
    }}
    private fun cloudRows()=projection.cloudRows().map{r->copy(r).put("value",SyncV2Business.join(r.getJSONObject("meta").getString("kind"),r.getJSONObject("meta"),r.getJSONObject("state")))}
    private fun same(a:Any?,b:Any?)=SyncV2LocalJson.canonical(a)==SyncV2LocalJson.canonical(b)
    private fun complete(){if(!projection.isWindowComplete())SyncV2Codec.fail("projection_incomplete")}
    private fun noLegacy() {
        val p=projection.state().body.optJSONObject("pendingChoice")
        if((p?.optJSONArray("inboxKeys")?.length()?:0)>0)SyncV2Codec.fail("unsupported_feature")
    }
    /** Read metadata, then scan R0+tail without ever replacing original business rows.
     * Repeated host windows finish a large rebuild; the return value exposes continuation. */
    fun refresh():JSONObject {
        gate();syncFiles();val status=cloud.readCurrent();projection.accountContact(now());val progress=projection.runWindow()
        recovery.purge(trustedNow())
        if(!progress.paused)applyObserved()
        return state().put("status",status).put("projectionPaused",progress.paused)
    }
    fun poll():JSONObject {
        gate();syncFiles();val result=cloud.poll()
        projection.accountContact(now(),result.optBoolean("historyExpired"))
        val progress=projection.runWindow();if(!progress.paused)applyObserved();return result.put("projectionPaused",progress.paused)
    }
    /** A clean device follows the authenticated current account, including a missed rollback.
     * Local dirty/unknown rows always retain their original business state. */
    fun applyObserved():Boolean {
        gate();syncFiles();complete();val fileToken=fileCapture();val st=projection.state();val a=st.body.optJSONObject("account")?:return false
        if(!st.body.isNull("pendingAccountAction") || !st.body.isNull("pendingChoice") || st.body.optBoolean("unknownEditBase") || st.revision!=st.body.optString("acknowledgedLocalRevision","0"))return false
        if(same(st.body.optJSONObject("appliedAccount"),a))return false
        if(!same(st.body.optJSONObject("metadataAccount"),a))SyncV2Codec.fail("closure_incomplete")
        val before=projection.accountLocalRows();val target=cloudRows();business.validate(target)
        val endangered=endangeredBodies(before,target)
        if(endangered.isNotEmpty()) {
            fileGuard(fileToken){projection.accountProtectPendingBodies(st.version,endangered)}
            return false
        }
        fileGuard(fileToken){ projection.accountWriter(st.version){current->
            business.apply(before,target);fault("business-applied")
            projection.accountApplied(current,a,target,id())
            current.body.put("pendingAppliedAck",JSONObject().put("requestId",id()).put("commitOid",a.getString("oid")).put("deviceId",signer.deviceId).put("refRevision",a.getString("revision")).put("rollbackGeneration",a.getString("generation")))
        }
        }
        runCatching{flushAppliedAck()};return true
    }
    fun state():JSONObject {
        syncFiles();val st=projection.state();return JSONObject().put("account",st.body.optJSONObject("account")?:JSONObject.NULL)
            .put("lastSeen",st.body.optString("lastSeen","0")).put("stateVersion",st.version).put("localChangeRevision",st.revision)
            .put("pendingChoice",st.body.optJSONObject("pendingChoice")?:JSONObject.NULL).put("actionPending",!st.body.isNull("pendingAccountAction")).put("lastAction",st.body.optJSONObject("accountActionReceipt")?:JSONObject.NULL)
    }
    /** Register before presenting a capture; this setup action may advance stateVersion. */
    fun prepareSigning() {
        gate();client.capabilities();val window=client.negotiate();val keys=client.signingKeys(window.getString("windowId"));val bindings=keys.getJSONArray("bindings")
        if((0 until bindings.length()).none{bindings.getJSONObject(it).getJSONObject("body").getString("deviceId")==signer.deviceId})
            client.registerSigningKey(projection.prepareOwnSigningBinding(signer,now(),keys.getString("revocationRevision")))
        cloud.readCurrent()
    }
    data class PendingChoice(val accountOid:String,val accountRevision:String,val generation:String,val localChangeRevision:String,val canMerge:Boolean,val reason:String?,val preview:Preview)
    fun pendingChoice():PendingChoice? {
        gate();if(projection.state().body.isNull("pendingChoice"))return null
        val p=preview();return PendingChoice(p.capture.accountOid,p.capture.accountRevision,p.capture.generation,p.capture.localChangeRevision,p.canMerge,p.reason,p)
    }
    fun history(pageToken:String?=null):JSONObject {gate();client.capabilities();return client.history(pageToken)}
    fun keepDiscardedLocalChanges7Days():Boolean=projection.state().body.optBoolean("keepDiscardedLocalChanges7Days",true)
    fun setKeepDiscardedLocalChanges7Days(value:Boolean) {
        gate();val st=projection.state();projection.accountWriter(st.version){it.body.put("keepDiscardedLocalChanges7Days",value)}
    }
    fun recoveryCopies():JSONArray {gate();recovery.purge(trustedNow());return JSONArray(recovery.headers())}
    fun recoveryDelta(copyId:String):JSONObject {gate();recovery.purge(trustedNow());return recovery.open(copyId)}
    fun recoveryBodyPart(copyId:String,recipeOid:String,offset:Long,maxBytes:Int):ByteArray {gate();recovery.purge(trustedNow());return recovery.readPart(copyId,recipeOid,offset,maxBytes)}
    fun purgeRecovery(){gate();recovery.purge(trustedNow())}
    fun captureRollback():ChoiceCapture {gate();syncFiles();complete();return projection.accountCapture(false)}
    class Preview private constructor(val capture:ChoiceCapture,val canMerge:Boolean,val reason:String?) {
        companion object {internal fun create(c:ChoiceCapture,yes:Boolean,reason:String?)=Preview(c,yes,reason)}
    }
    private data class Plan(val local:String,val cloud:String,val input:String,val candidate:String?,val digest:String,val ids:Set<String>,val files:JSONObject)
    private val plans=java.util.Collections.synchronizedMap(java.util.WeakHashMap<Preview,Plan>())
    private fun rows(json:String)=JSONArray(json).let{a->(0 until a.length()).map{a.getJSONObject(it)}}
    /** One unique planner entry, including every note's conflict channel and authenticated bases. */
    fun preview():Preview {
        gate();syncFiles();complete();noLegacy();val capture=projection.accountCapture();val st=projection.state()
        projection.accountCheck(st,capture)
        val fileToken=fileCapture();val local=projection.accountLocalRows();val remote=cloudRows();val changed=projection.accountChangedIds()+st.body.optJSONArray("contentPendingIds").let{a->if(a==null)emptySet()else (0 until a.length()).map{a.getString(it)}.toSet()}+local.filter{it.has("p5Base")}.map{it.getJSONObject("meta").getString("entityKey")}
        val base=values(local,true);val localValues=values(local,true);val cloudValues=values(remote,true);val bases=projection.accountBases().toMutableMap().apply{for(r in local)if(r.has("p5Base"))putIfAbsent(r.getJSONObject("meta").getString("entityKey"),r.getJSONObject("p5Base"))}
        val ops=JSONArray();var missing=st.body.optBoolean("unknownEditBase")
        val byId=local.associateBy{it.getJSONObject("meta").getString("entityKey")}
        for(entity in changed.sorted()) {
            val l=localValues.optJSONObject(entity);val b=bases[entity]
            if(!bases.containsKey(entity)){missing=true;continue}
            if(b==null && l==null)continue
            if(b==null)base.remove(entity)else {
                val kind=byId[entity]?.getJSONObject("meta")?.getString("kind")?:remote.firstOrNull{it.getJSONObject("meta").getString("entityKey")==entity}?.getJSONObject("meta")?.getString("kind")
                if(kind==null){missing=true;continue}
                base.put(entity,copy(b).put("identity",SyncV2Business.logicalId(kind,b)))
            }
            val fields=if(l==null || b==null)emptyList()else (l.keys().asSequence().toSet()+b.keys().asSequence().toSet()).filter{it!="identity" && !same(l.opt(it),b.opt(it))}.sorted()
            val kind=when {l==null->"delete";b==null->"create";l.optJSONObject("lifecycle")?.getLong("generation")?.let{it>(b.optJSONObject("lifecycle")?.getLong("generation")?:0)}==true->"explicit-restore";else->"update"}
            ops.put(JSONObject().put("id",entity).put("kind",kind).put("fields",JSONArray(fields)).put("complete",l==null || byId[entity]?.let{recovery.contentReady(it)}==true))
        }
        val editBase=st.body.optJSONObject("editBase")
        val input=JSONObject().put("base",base).put("local",localValues).put("cloud",cloudValues).put("ops",ops)
        if(editBase!=null)input.put("noteConflictGenerations",JSONObject().put("base",editBase.getString("generation")).put("local",editBase.getString("generation")).put("cloud",capture.generation))
        val result=if(missing)JSONObject().put("canMerge",false).put("reason","missing_base")else SyncV2Planner.canMerge(input)
        var candidate:List<JSONObject>?=null;var reason=result.optString("reason").takeIf{it.isNotEmpty()}
        if(result.getBoolean("canMerge")) {
            try {
                val originals=(local+remote).associateBy{it.getJSONObject("meta").getString("entityKey")}
                candidate=result.getJSONObject("tree").let{tree->tree.keys().asSequence().sorted().map { entity ->
                    val original=originals[entity]?:SyncV2Codec.fail("identity_conflict");val m=original.getJSONObject("meta");val v=copy(tree.getJSONObject(entity)).apply{remove("identity")}
                    record(m.getString("kind"),m.getString("logicalId"),entity,v)
                }.toList()};business.validate(candidate);wireIndex(candidate,1) // final full wire/refs budget, before showing canMerge.
            }catch(e:Exception){if(e.message=="object_too_large")throw e;candidate=null;reason=when(e.message){"storage_pressure"->"storage_pressure";"closure_incomplete"->"missing_content";else->"unsupported_feature"}}
        }
        projection.accountCheck(projection.state(),capture)
        val handle=Preview.create(capture,candidate!=null,reason)
        plans[handle]=Plan(JSONArray(local).toString(),JSONArray(remote).toString(),input.toString(),candidate?.let{JSONArray(it).toString()},SyncV2Codec.hash(st.body),changed,fileToken)
        return handle
    }
    private fun syncFiles(){projection.accountFilesObserved(fileCapture())}
    private fun fileCapture():JSONObject {
        val token=com.lightningstudio.watchrss.phone.data.local.CloudExportFileWitness.captureForProjection(files)
        return JSONObject().put("notes",token.notes).put("privateFiles",token.privateFiles)
    }
    private fun <T> fileGuard(token:JSONObject,work:()->T):T = com.lightningstudio.watchrss.phone.data.local.CloudExportFileWitness.withProjectionCapture(files,
        com.lightningstudio.watchrss.phone.data.local.CloudExportFileWitness.Revision(token.getString("notes"),token.getString("privateFiles")),work)
    private fun record(kind:String,logicalId:String,entity:String,value:JSONObject):JSONObject {
        val pair=SyncV2Business.split(kind,logicalId,entity,value,null)
        return JSONObject().put("meta",pair.meta).put("state",pair.state).put("value",value)
    }
    private fun bodyIdentity(r:JSONObject):String {
        val m=r.getJSONObject("meta");val v=r.getJSONObject("value")
        return SyncV2Codec.hash(JSONObject().put("kind",m.getString("kind")).put("id",m.getString("logicalId"))
            .put("body",when(m.getString("kind")){"article"->v.opt("contentHash");"book"->v.opt("fingerprint");"asset"->v.opt("updatedAt");else->JSONObject.NULL}))
    }
    private fun endangeredBodies(before:List<JSONObject>,target:List<JSONObject>):Set<String> {
        val targets=target.associateBy{it.getJSONObject("meta").getString("entityKey")}
        return before.filter { r ->
            val m=r.getJSONObject("meta");val entity=m.getString("entityKey");val v=r.getJSONObject("value");val next=targets[entity]
            val binding=db.query("SELECT body FROM syncv2_p5_bindings WHERE scope=? AND entity_key=?",arrayOf(scope,entity)).use{if(it.moveToFirst())projection.accountOpen("binding",it.getBlob(0))else null}
            m.getString("kind") in setOf("article","book","asset") && !v.optBoolean("deleted") &&
                binding?.optJSONObject("row")?.isNull("recipeOid")==true && recovery.hasOriginalSource(r) &&
                (next==null || next.getJSONObject("value").optBoolean("deleted") || bodyIdentity(r)!=bodyIdentity(next))
        }.map{it.getJSONObject("meta").getString("entityKey")}.toSet()
    }
    private fun wireIndex(records:List<JSONObject>,version:Long):SyncV2Projection.WireIndex {
        val objects=linkedMapOf<String,ByteArray>();val refs=mutableListOf<JSONObject>();val rows=mutableListOf<JSONObject>()
        val candidates=cloudRows().associateBy{it.getJSONObject("meta").getString("entityKey")}
        for(r in records) {
            val m=r.getJSONObject("meta");val entity=m.getString("entityKey");val kind=m.getString("kind")
            val remote=candidates[entity]
            val known=db.query("SELECT body FROM syncv2_p5_bindings WHERE scope=? AND entity_key=?",arrayOf(scope,entity)).use{if(it.moveToFirst())projection.accountOpen("binding",it.getBlob(0))else null}
            val source=listOfNotNull(remote,known).firstOrNull{it.has("row") && bodyIdentity(it)==bodyIdentity(r)}
                ?:projection.accountWireRowFor(r)?.let{row->copy(r).put("row",row)}
            val meta=SyncV2Envelope.seal(identity,contentKey(version),version,"meta",SyncV2Codec.utf8(SyncV2Codec.canonical(m)))
            val state=SyncV2Envelope.seal(identity,contentKey(version),version,"state",SyncV2Codec.utf8(SyncV2Codec.canonical(r.getJSONObject("state"))))
            val needsBody=kind in setOf("article","book","asset")
            val priorRows=listOfNotNull(known?.optJSONObject("row"),remote?.optJSONObject("row"),projection.accountWireRowFor(r))
            val inherited=priorRows.maxByOrNull{SyncV2Codec.d64(it.getString("rowSequence"))}
            val addedAt=known?.optJSONObject("row")?.optLong("addedAtMillis")?:remote?.optJSONObject("row")?.optLong("addedAtMillis")?:0
            val row=JSONObject().put("entityKey",entity).put("metaOid",meta.oid).put("stateOid",state.oid).put("addedAtMillis",addedAt)
                .put("rowSequence",SyncV2Codec.next(inherited?.getString("rowSequence")?:"0")).put("deleted",r.getJSONObject("value").optBoolean("deleted",false))
                .put("bodyRevision",if(needsBody)SyncV2Codec.hex(SyncV2Codec.random(16))else JSONObject.NULL)
                .put("promiseId",if(needsBody)id()else JSONObject.NULL).put("recipeOid",JSONObject.NULL).put("opaqueContentId",JSONObject.NULL).put("contentBytes",JSONObject.NULL).put("requiredFeatures",JSONArray())
            if(source!=null)for(f in listOf("bodyRevision","promiseId","recipeOid","opaqueContentId","contentBytes"))row.put(f,source.getJSONObject("row").get(f))
            val signed=SyncV2Index.refs(identity,signer,state.oid)
            objects[meta.oid]=meta.bytes;objects[state.oid]=state.bytes;objects[SyncV2Codec.oid("state-refs",signed)]=SyncV2Codec.utf8(SyncV2Codec.canonical(signed));refs.add(signed);rows.add(row);r.put("row",row)
        }
        val tree=SyncV2Index.build(identity.scope,rows);objects.putAll(tree.objects);return SyncV2Projection.WireIndex(tree,objects,refs)
    }
    private data class Context(val window:JSONObject,val authors:SyncV2Authors,val own:String?,val ownRevision:String,val sequence:String)
    private fun context(capture:ChoiceCapture):Context {
        val caps=client.capabilities();if(caps.getJSONObject("flags").opt("historyRestore")!=true || caps.getJSONObject("flags").opt("indexWrite")!=true)throw SyncV2CloudError("feature_disabled",true,1000)
        val window=client.negotiate();if(window.getLong("expiresAtMillis")<=now())SyncV2Codec.fail("window_expired")
        if(!same(account(window.getJSONObject("account")),JSONObject().put("oid",capture.accountOid).put("revision",capture.accountRevision).put("generation",capture.generation)))SyncV2Codec.fail("choice_stale")
        val authors=client.authors(identity,approved);val saved=projection.state().body.optJSONArray("signingBindings")?:JSONArray()
        for(i in 0 until saved.length())authors.restorePinned(saved.getJSONObject(i))
        val keys=client.signingKeys(window.getString("windowId"));val bindings=keys.getJSONArray("bindings")
        for(i in 0 until bindings.length())authors.accept(bindings.getJSONObject(i),keys.getString("revocationRevision"))
        if(signer.deviceId!=client.deviceId || authors.publicKey(signer.deviceId,signer.keyId)!=signer.publicHex)SyncV2Codec.fail("authentication_required")
        var own:String?=null;var revision="0";val refs=window.getJSONArray("refs")
        for(i in 0 until refs.length())refs.getJSONObject(i).let{if(it.getString("deviceId")==signer.deviceId){own=it.getString("commitOid");revision=it.getString("refRevision")}}
        val sequence=own?.let{oid->authors.header(header(oid),false).also{if(it.getString("authorDeviceId")!=signer.deviceId || SyncV2Codec.oid("commit",it)!=oid)SyncV2Codec.fail("identity_conflict")}.getString("deviceSequence")}?:"0"
        return Context(window,authors,own,revision,sequence)
    }
    private fun header(oid:String):JSONObject {
        val page=client.ancestry(oid,null);if(page.get("head")!=oid || !page.isNull("stop"))SyncV2Codec.fail("ref_continuity_unavailable")
        val item=page.getJSONArray("headers").getJSONObject(0);val envelope=JSONObject().put("body",item.getJSONObject("body")).put("signature",item.getString("signature"))
        if(item.get("commitOid")!=oid || SyncV2Codec.oid("commit-header",envelope)!=item.get("headerOid"))SyncV2Codec.fail("hash_mismatch")
        return envelope
    }
    fun choose(preview:Preview,choice:String,keyVersion:Long=1):JSONObject {
        gate();syncFiles();val plan=plans[preview]?:SyncV2Codec.fail("choice_stale");val c=preview.capture
        projection.accountCheck(projection.state(),c);SyncV2Codec.next(c.stateVersion);if(SyncV2Codec.hash(projection.state().body)!=plan.digest)SyncV2Codec.fail("choice_stale")
        if(choice !in setOf("cloud","local","merge"))SyncV2Codec.fail("invalid_schema")
        if(choice=="merge" && !preview.canMerge)throw SyncV2CloudError("merge_unavailable",details=JSONObject().put("reason",preview.reason?:"missing_base"))
        if(choice=="merge") {
            val result=SyncV2Planner.canMerge(JSONObject(plan.input));if(!result.getBoolean("canMerge"))SyncV2Codec.fail("choice_stale")
        }
        fileGuard(plan.files){}
        val context=context(c);val local=rows(plan.local);val target=when(choice){"cloud"->rows(plan.cloud);"local"->local;else->rows(plan.candidate?:SyncV2Codec.fail("choice_stale"))}
        business.validate(target)
        val bundle=if(choice=="cloud" && keepDiscardedLocalChanges7Days()) {
            val base=projection.state().body.optJSONObject("editBase")?:JSONObject().put("accountOid",c.accountOid).put("generation",c.generation)
            recovery.prepare(local,rows(plan.cloud),base,plan.ids,c.localChangeRevision,c.accountOid,now())
        }else null
        if(choice=="local")for(r in local)if(!recovery.contentReady(r))SyncV2Codec.fail("closure_incomplete")
        val requestId=id();val action=JSONObject().put("id",requestId).put("kind",choice).put("capture",c.json()).put("serverTime",now()).put("fileCapture",plan.files)
        val index=if(choice=="cloud")null else wireIndex(target,keyVersion)
        val objects=index?.objects?.toMutableMap()?:linkedMapOf()
        if(index==null)action.put("request",JSONObject().put("requestId",requestId).put("choiceId",requestId).put("expectedAccountRevision",c.accountRevision).put("expectedRollbackGeneration",c.generation).put("selectedLocalChangeRevision",c.localChangeRevision))
        else publication(action,c,context,index,objects,if(choice=="local")"resolve-local"else"resolve-merge",null)
        stage(c,action,if(choice=="cloud")emptyList()else target,objects,bundle,true);plans.remove(preview);fault("action-staged");return resume()?:SyncV2Codec.fail("choice_stale")
    }
    fun rollback(capture:ChoiceCapture,targetOid:String):JSONObject {
        gate();syncFiles();complete();projection.accountCheck(projection.state(),capture,false);SyncV2Codec.next(capture.stateVersion);SyncV2Codec.commitOid(targetOid)
        val fileToken=fileCapture();val context=context(capture)
        val requestId=id();val prepared=client.prepareHistory(JSONObject().put("requestId",requestId).put("targetCommitOid",targetOid).put("expectedAccountRevision",capture.accountRevision).put("expectedRollbackGeneration",capture.generation))
        if(prepared.getLong("expiresAtMillis")<=now())SyncV2Codec.fail("snapshot_expired")
        val envelope=header(targetOid);val verified=SyncV2ControlClosure(identity,context.authors,contentKey).verify(envelope,{client.raw(it,context.window.getString("windowId"))}){p->client.reference(p,context.window.getString("windowId")).let{copy(it).apply{remove("version")}}}
        if(verified.commitOid!=targetOid || envelope.getJSONObject("body").getString("treeRoot")!=prepared.getString("treeRoot"))SyncV2Codec.fail("hash_mismatch")
        val target=verified.rows.map{copy(it).put("value",SyncV2Business.join(it.getJSONObject("meta").getString("kind"),it.getJSONObject("meta"),it.getJSONObject("state")))}
        business.validate(target)
        val tree=SyncV2Index.Tree(prepared.getString("treeRoot"),emptyMap())
        val index=SyncV2Projection.WireIndex(tree,verified.objects,emptyList());val objects=verified.objects.toMutableMap()
        val action=JSONObject().put("id",id()).put("kind","rollback").put("capture",capture.json()).put("serverTime",now()).put("restoreLease",prepared).put("fileCapture",fileToken)
        publication(action,capture,context,index,objects,"rollback",targetOid)
        stage(capture,action,target,objects,null,false);fault("action-staged");return resume()?:SyncV2Codec.fail("choice_stale")
    }
    private fun publication(action:JSONObject,c:ChoiceCapture,context:Context,index:SyncV2Projection.WireIndex,objects:MutableMap<String,ByteArray>,kind:String,target:String?) {
        val st=projection.state();val sequence=SyncV2Codec.next(maxOf(SyncV2Codec.d64(st.body.optString("reservedDeviceSequence","0")),SyncV2Codec.d64(context.sequence)).toString())
        val generation=if(kind=="rollback")SyncV2Codec.next(c.generation)else c.generation
        val body=JSONObject().put("version",2).put("scope",identity.scope).put("authorDeviceId",signer.deviceId).put("deviceSequence",sequence)
            .put("previousOwnCommit",context.own?:JSONObject.NULL).put("mergedParents",JSONArray(if(target!=null && target!=context.own)listOf(target)else emptyList<String>()))
            .put("treeRoot",index.tree.root).put("requiredFeatures",JSONArray()).put("createdAtMillis",now()).put("observedRefs",JSONArray()).put("indexComplete",true)
            .put("accountParent",c.accountOid).put("rollbackGeneration",generation).put("operation",JSONObject().put("kind",kind).put("rollbackTarget",target?:JSONObject.NULL).put("resolvesGeneration",if(kind=="rollback")JSONObject.NULL else generation))
        val envelope=signer.commit(body);val oid=SyncV2Codec.oid("commit",body);objects[oid]=SyncV2Codec.utf8(SyncV2Codec.canonical(envelope))
        val requestId=action.getString("id")
        val request=JSONObject().put("account",SyncV2CloudClient.refUpdate(signer,identity.scope,"refs/account/current",c.accountOid,c.accountRevision,oid,requestId,now()))
            .put("device",SyncV2CloudClient.refUpdate(signer,identity.scope,"refs/devices/${signer.deviceId}/index",context.own,context.ownRevision,oid,requestId,now())).put("relayGrant",JSONObject.NULL)
        action.put("request",request).put("envelope",envelope).put("sequence",sequence)
    }
    private fun stage(c:ChoiceCapture,action:JSONObject,rows:List<JSONObject>,objects:Map<String,ByteArray>,bundle:SyncV2RecoveryStorage.Bundle?,pending:Boolean) {
        val latest=projection.state();projection.accountCheck(latest,c,pending);SyncV2Codec.next(latest.version)
        fault("before-action-stage")
        fileGuard(action.getJSONObject("fileCapture")){ projection.accountWriter(c.stateVersion){st->
            projection.accountCheck(st,c,pending);if(!st.body.isNull("pendingAccountAction") || !st.body.isNull("pendingPublish"))SyncV2Codec.fail("choice_pending")
            val id=action.getString("id");action.put("actorDeviceId",signer.deviceId)
            for((oid,bytes)in objects)db.execSQL("INSERT INTO syncv2_p5_objects VALUES(?,?,?,?)",arrayOf(scope,id,oid,bytes))
            for(r in rows)db.execSQL("INSERT INTO syncv2_p5_rows VALUES(?,?,?,?)",arrayOf(scope,id,r.getJSONObject("meta").getString("entityKey"),projection.accountSeal("row",copy(r).apply{remove("p5Base")})))
            if(bundle!=null) {
                val copyBytes=SyncV2Codec.utf8(SyncV2Codec.canonical(bundle.copy));val pinBytes=projection.accountSeal("prepared-recovery-pin",bundle.pin)
                for((kind,bytes)in listOf("recovery-copy" to copyBytes,"recovery-pin" to pinBytes)) {
                    action.put(kind,JSONObject().put("bytes",bytes.size).put("sha256",SyncV2Codec.hex(SyncV2Codec.sha(bytes))))
                    for(offset in bytes.indices step 65536)db.execSQL("INSERT INTO syncv2_p5_objects VALUES(?,?,?,?)",arrayOf(scope,id,"$kind/${offset/65536}",bytes.copyOfRange(offset,minOf(offset+65536,bytes.size))))
                }
                for((oid,bytes)in bundle.objects)db.execSQL("INSERT INTO syncv2_p5_objects VALUES(?,?,?,?)",arrayOf(scope,id,"local-recovery/$oid",bytes))
            }
            if(action.has("sequence"))st.body.put("reservedDeviceSequence",action.getString("sequence"))
            action.put("expectedStateVersion",SyncV2Codec.next(st.version));st.body.put("pendingAccountAction",action)
        }}
    }
    private fun stagedObjects(id:String)=db.query("SELECT oid,body FROM syncv2_p5_objects WHERE scope=? AND action_id=?",arrayOf(scope,id)).use{c->buildMap{while(c.moveToNext())put(c.getString(0),c.getBlob(1))}}
    private fun stagedRows(id:String)=db.query("SELECT body FROM syncv2_p5_rows WHERE scope=? AND action_id=? ORDER BY entity_key",arrayOf(scope,id)).use{c->buildList{while(c.moveToNext())add(projection.accountOpen("row",c.getBlob(0)))}}
    private fun preparedRecovery(action:JSONObject,objects:Map<String,ByteArray>):SyncV2RecoveryStorage.Bundle? {
        if(!action.has("recovery-copy"))return null
        fun join(kind:String):ByteArray {
            val descriptor=action.getJSONObject(kind);val total=descriptor.getInt("bytes");val out=java.io.ByteArrayOutputStream()
            for(index in 0 until (total+65535)/65536){val part=objects["$kind/$index"]?:SyncV2Codec.fail("closure_incomplete");if(part.size!=minOf(65536,total-out.size()))SyncV2Codec.fail("closure_incomplete");out.write(part)}
            return out.toByteArray().also{if(SyncV2Codec.hex(SyncV2Codec.sha(it))!=descriptor.getString("sha256"))SyncV2Codec.fail("hash_mismatch")}
        }
        val copyBytes=join("recovery-copy");val copy=SyncV2Wire.parse(copyBytes,copyBytes.size,true);val pin=projection.accountOpen("prepared-recovery-pin",join("recovery-pin"))
        return SyncV2RecoveryStorage.Bundle(copy,pin,objects.filterKeys{it.startsWith("local-recovery/")}.mapKeys{it.key.removePrefix("local-recovery/")})
    }
    private fun upload(objects:Map<String,ByteArray>,actionId:String) {
        val window=client.negotiate();val windowId=window.getString("windowId")
        for(batch in objects.entries.filter{it.key.substringBefore(':') in setOf("meta","state","tree","commit")}.sortedBy{it.key}.chunked(64)) {
            val reservation=client.reserve(id(),batch.map{it.key to it.value.size}).getString("reservationId")
            val have=client.verifiedHaveWant(windowId,batch.map{it.key to it.value.size},now())
            for((oid,bytes)in batch) {
                val present=have.getValue(oid);if(present.getString("status")=="present")continue
                var offset=present.getInt("durableOffset")
                val previous=transferCheckpoint(actionId,oid)
                if(previous!=null && (previous.getInt("totalBytes")!=bytes.size || previous.getString("oid")!=oid))SyncV2Codec.fail("idempotency_conflict")
                if(previous!=null && previous.getInt("durableOffset")-offset>65536)throw SyncV2CloudError("offset_conflict",true,details=JSONObject().put("durableOffset",offset))
                if(offset%65536!=0 && offset!=bytes.size)SyncV2Codec.fail("invalid_schema")
                while(offset<bytes.size) {
                    val end=minOf(offset+65536,bytes.size);val response=client.put(oid,offset,reservation,id(),bytes.copyOfRange(offset,end))
                    if(response.getString("oid")!=oid || response.getLong("durableOffset")!=end.toLong())SyncV2Codec.fail("invalid_schema")
                    client.verifyReceipt(response.getJSONObject("receipt"),oid,bytes.size.toLong(),end.toLong(),now());offset=end
                    saveTransferCheckpoint(actionId,oid,bytes.size,offset);fault("control-frame:$offset")
                }
                val complete=client.complete(oid,reservation,id(),bytes.size);client.verifyReceipt(complete.getJSONObject("receipt"),oid,bytes.size.toLong(),bytes.size.toLong(),now())
                saveTransferCheckpoint(actionId,oid,bytes.size,bytes.size)
            }
        }
        for((oid,bytes)in objects)if(oid.startsWith("state-refs:"))client.stateRefs(SyncV2Wire.parse(bytes,262144,true))
    }
    /** Transport progress is a separate authenticated-byte journal. It cannot authorize
     * business apply or edit deletion, and never changes the captured account plan. */
    private fun transferCheckpoint(actionId:String,oid:String):JSONObject?=db.query("SELECT body FROM syncv2_p5_objects WHERE scope=? AND action_id=? AND oid=?",arrayOf(scope,actionId,"upload/$oid")).use{if(it.moveToFirst())projection.accountOpen("transfer",it.getBlob(0))else null}
    private fun saveTransferCheckpoint(actionId:String,oid:String,total:Int,offset:Int) {
        db.beginTransaction()
        try {
            val prior=transferCheckpoint(actionId,oid)
            if(prior==null || offset>prior.getInt("durableOffset"))db.execSQL("INSERT OR REPLACE INTO syncv2_p5_objects VALUES(?,?,?,?)",arrayOf(scope,actionId,"upload/$oid",projection.accountSeal("transfer",JSONObject().put("oid",oid).put("totalBytes",total).put("durableOffset",offset))))
            db.setTransactionSuccessful()
        } finally {db.endTransaction()}
    }
    /** Restarts replay exact signed bytes/request IDs. A changed whole state never clears edits. */
    fun resume():JSONObject? {
        gate();syncFiles();val st=projection.state();val action=st.body.optJSONObject("pendingAccountAction")?:return flushAppliedAck()
        if(action.getString("actorDeviceId")!=signer.deviceId || client.deviceId!=signer.deviceId)SyncV2Codec.fail("authentication_required")
        fileGuard(action.getJSONObject("fileCapture")){}
        if(st.version!=action.getString("expectedStateVersion") || st.revision!=action.getJSONObject("capture").getString("localChangeRevision"))SyncV2Codec.fail("choice_stale")
        SyncV2Codec.next(st.version)
        val caps=client.capabilities();if(caps.getJSONObject("flags").opt("historyRestore")!=true)throw SyncV2CloudError("feature_disabled",true,1000)
        val request=action.getJSONObject("request");val id=action.getString("id");val objects=stagedObjects(id);val kind=action.getString("kind")
        val response=try {
            if(kind=="cloud")client.chooseCloud(request)else {
                val known=client.requestResult(id)
                if(known.getString("status")=="succeeded")known.getJSONObject("response")else {upload(if(kind=="rollback")objects.filterKeys{it==request.getJSONObject("account").getJSONObject("body").getString("newCommitOid")}else objects,id);client.publish(request)}
            }
        }catch(e:SyncV2CloudError){
            if(e.code in setOf("ref_conflict","rollback_conflict","choice_stale","snapshot_expired","lease_expired")) {
                // A definitive server rejection invalidates this attempt, not any user data.
                clearRejected(id,e.code)
            };throw e
        }
        fault("server-confirmed")
        val observed=account(response.getJSONObject(if(kind=="cloud")"confirmedAccount"else"account"));val capture=ChoiceCapture.restore(action.getJSONObject("capture"))
        if(kind=="cloud") {
            if(observed.getString("oid")!=capture.accountOid || observed.getString("revision")!=capture.accountRevision || observed.getString("generation")!=capture.generation)SyncV2Codec.fail("choice_stale")
        }else {
            val b=action.getJSONObject("envelope").getJSONObject("body")
            if(observed.getString("oid")!=SyncV2Codec.oid("commit",b) || observed.getString("generation")!=b.getString("rollbackGeneration") || observed.getString("revision")!=SyncV2Codec.next(capture.accountRevision) || response.getJSONObject("device").getString("commitOid")!=observed.getString("oid"))SyncV2Codec.fail("invalid_schema")
        }
        // Only confirmedAccount authorizes cloud business staging. The top-level account
        // may be a later observation; it is neither a replacement capture nor an apply target.
        val latest=projection.state()
        if(latest.version!=action.getString("expectedStateVersion") || latest.revision!=capture.localChangeRevision)SyncV2Codec.fail("choice_stale")
        val target=if(kind=="cloud") {
            if(!same(latest.body.optJSONObject("metadataAccount"),observed))SyncV2Codec.fail("choice_stale")
            cloudRows()
        }else stagedRows(id)
        business.validate(target);val before=projection.accountLocalRows();val bundle=preparedRecovery(action,objects)
        val endangered=if(kind=="rollback")endangeredBodies(before,target)else emptySet()
        val proof=if(kind=="cloud")null else {
            val authors=client.authors(identity,approved);val saved=st.body.getJSONArray("signingBindings");for(i in 0 until saved.length())authors.restorePinned(saved.getJSONObject(i))
            projection.accountProof(observed,listOf(action.getJSONObject("envelope")),{authors.header(it)},{header(it)})
        }
        fileGuard(action.getJSONObject("fileCapture")){ projection.accountWriter(action.getString("expectedStateVersion")){current->
            val pending=current.body.optJSONObject("pendingAccountAction")?:SyncV2Codec.fail("choice_stale")
            if(!same(pending,action) || current.revision!=capture.localChangeRevision)SyncV2Codec.fail("choice_stale")
            if(proof!=null)projection.accountAdopt(current,observed,proof)
            projection.accountProtectPendingBodies(current,endangered)
            val hasEdits=current.revision!=current.body.optString("acknowledgedLocalRevision","0") || current.body.optBoolean("unknownEditBase") || !current.body.isNull("pendingChoice")
            val apply=kind!="rollback" || !hasEdits
            if(apply) {
                if(bundle!=null)recovery.commit(bundle)
                fault("recovery-pinned")
                business.apply(before,target);fault("business-applied")
                projection.accountApplied(current,observed,target,id)
                if(kind in setOf("local","merge"))projection.accountStoreOwnWireRows(target,objects)
                current.body.put("pendingAppliedAck",JSONObject().put("requestId",this.id()).put("commitOid",observed.getString("oid")).put("deviceId",signer.deviceId).put("refRevision",observed.getString("revision")).put("rollbackGeneration",observed.getString("generation")))
            }
            // Current metadata replaces the previous cache. No old snapshot browser is created.
            if(kind!="cloud") {
                projection.accountReplaceMetadata(current,observed,target,objects.filterKeys{it.substringBefore(':') in setOf("meta","state","tree","commit","state-refs")},response.getString("serverRevision"))
            }
            current.body.put("accountActionReceipt",JSONObject().put("id",id).put("kind",kind).put("account",observed)).remove("pendingAccountAction")
            clearStaged(id);fault("before-account-commit")
        }}
        fault("after-account-commit");runCatching{flushAppliedAck()};return response.put("localCommitted",true).put("appliedAckPending",!projection.state().body.isNull("pendingAppliedAck"))
    }
    private fun clearStaged(id:String) {for(t in listOf("syncv2_p5_objects","syncv2_p5_rows"))db.execSQL("DELETE FROM $t WHERE scope=? AND action_id=?",arrayOf(scope,id))}
    private fun clearRejected(id:String,code:String) {
        val st=projection.state();projection.accountWriter(st.version){current->
            if(current.body.optJSONObject("pendingAccountAction")?.optString("id")!=id)SyncV2Codec.fail("choice_stale")
            current.body.put("accountActionError",code).remove("pendingAccountAction");clearStaged(id)
        }
    }
    /** Explicit stale reconciliation queries the exact request, preserves all edits, then permits
     * a fresh authenticated read and a NEW user choice. It never auto-republishes the old tree. */
    fun reconcileStaleAction():JSONObject? {
        gate();client.capabilities();val st=projection.state();val action=st.body.optJSONObject("pendingAccountAction")?:return null
        val result=client.requestResult(action.getString("id"))
        projection.accountWriter(st.version){current->current.body.put("accountActionReconciliation",result).remove("pendingAccountAction");clearStaged(action.getString("id"))}
        return result
    }
    fun flushAppliedAck():JSONObject? {
        gate();val st=projection.state();val pending=st.body.optJSONObject("pendingAppliedAck")?:return null
        SyncV2Codec.next(st.version)
        client.capabilities();val response=client.applied(pending)
        projection.accountWriter(st.version){current->if(!same(current.body.optJSONObject("pendingAppliedAck"),pending))SyncV2Codec.fail("choice_stale");current.body.remove("pendingAppliedAck")}
        return response
    }
}
