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
    recoveryReads:SyncV2RecoveryStorage.SourceReads?=null,private val fault:(String)->Unit={},
    private val noteSegmentsRead:Boolean=BuildConfig.SYNC_OBJECTS_V2_NOTE_SEGMENTS,
    private val networkNow:()->Long=System::currentTimeMillis,
    private val currentKeyVersion:()->Long={1}):AutoCloseable {
    private val db get()=projection.accountDatabase
    private val scope get()=projection.scope
    private val recovery=SyncV2RecoveryStorage(projection,identity,files,recoveryQuotaBytes,recoveryReads)
    private val business=SyncV2PhoneApply(db)
    private val cloud=SyncV2CloudSync(client,identity,projection,approved,contentKey,now={trustedNow()?:networkNow()})
    @Volatile private var closed=false
    private fun gate(){if(closed)SyncV2Codec.fail("authentication_required");if(!enabled)SyncV2Codec.fail("feature_disabled")}
    /** 25.8 read gate: the product switch AND the server announcement, identical to CloudSync.
     * A rollback must never diverge from the pull entry when only one door is open. */
    private fun noteSegmentsReadGate():Boolean=noteSegmentsRead&&client.noteSegmentsRead()
    private fun id()=SyncV2Codec.hex(SyncV2Codec.random(16))
    private fun copy(value:JSONObject)=JSONObject(value.toString())
    private fun now():Long=trustedNow()?.also{if(it !in 0..9007199254740991L)SyncV2Codec.fail("invalid_schema")}?:SyncV2Codec.fail("trusted_time_unavailable")
    private fun account(a:JSONObject)=JSONObject().put("oid",a.getString("commitOid")).put("revision",a.getString("revision")).put("generation",a.getString("rollbackGeneration"))
    private fun values(rows:List<JSONObject>,planner:Boolean=false)=JSONObject().also{out->for(r in rows) {
        if(!r.has("value")||r.has(SyncV2NoteWiring.ISOLATED_KEY))continue
        val m=r.getJSONObject("meta");val v=copy(r.getJSONObject("value"));if(planner)v.put("identity",m.getString("logicalId"));out.put(m.getString("entityKey"),v)
    }}
    private fun cloudRows(ids:Set<String>?=null)=projection.cloudRows(ids).mapRows{r->
        // F1/W3: an isolated segmented row has no reassemblable body; it is never joined and never
        // offered to the planner. The stored bytes survive untouched for verbatim carry-back (R3).
        if(r.has(SyncV2NoteWiring.ISOLATED_KEY))r else r.put("value",SyncV2Business.join(r.getJSONObject("meta").getString("kind"),r.getJSONObject("meta"),r.getJSONObject("state")))
    }
    private fun same(a:Any?,b:Any?)=SyncV2LocalJson.canonical(a)==SyncV2LocalJson.canonical(b)
    private fun complete(){if(!projection.isWindowComplete())SyncV2Codec.fail("projection_incomplete")}
    init {if(enabled){projection.accountEnsureCatalog();projection.bindLegacyCloudResolver {capture->
        val preview=preview();if(!same(preview.capture.json(),capture.json()))SyncV2Codec.fail("choice_stale")
        if(JSONArray(plans[preview]?.legacySelected?:"[]").length()==0)SyncV2Codec.fail("unsupported_feature")
        choose(preview,"cloud")
    }}}
    private data class LegacyView(val rows:List<JSONObject>,val selected:JSONArray,val proposals:JSONArray,val ids:Set<String>,val missingBase:Boolean)
    private fun legacyView(actual:List<JSONObject>,remote:List<JSONObject>,keys:Set<String>?):LegacyView {
        if(keys!=null && keys.size>64)SyncV2Codec.fail("invalid_schema")
        val entries=projection.accountLegacyEntries(keys)
        if(entries.isEmpty())return LegacyView(actual,JSONArray(),JSONArray(),emptySet(),false)
        val known=remote.plusRows(actual).indexRows()
        val original=actual.indexRows();val rows=rowMap();for((entity,value)in original)rows[entity]=value
        val selected=JSONArray();val proposals=JSONArray();val ids=mutableSetOf<String>();var missing=false
        for(entry in entries) {
            val intent=entry.getJSONObject("intent")
            val attempt=runCatching {
                val body=intent.getJSONObject("body");if(body.has("legacy-frame"))SyncV2Codec.fail("unsupported_feature")
                val normalized=mutableListOf<JSONObject>()
                for(entity in body.keys()) {
                    SyncV2Codec.unhex(entity,32);val incoming=body.getJSONObject(entity)
                    val value=if(incoming.has("meta") && incoming.has("state"))SyncV2Business.join(incoming.getJSONObject("meta").getString("kind"),incoming.getJSONObject("meta"),incoming.getJSONObject("state"))else copy(incoming)
                    val kind=known[entity]?.getJSONObject("meta")?.getString("kind")?:when {
                        value.has("noteId")->"note";value.has("assetId")->"asset";value.has("bookId") && value.has("chapterIndex")->"book-reading"
                        value.has("bookId")->"book";value.has("articleIdHash") && value.has("sourceId")->"rss-state";value.has("articleId")->"article"
                        value.has("folderId")->"folder";value.has("url")->"source";else->SyncV2Codec.fail("unsupported_feature")
                    }
                    val logical=SyncV2Business.logicalId(kind,value)
                    if(value.has("identity") && value.getString("identity")!=logical)SyncV2Codec.fail("identity_conflict");value.remove("identity")
                    if(identity.entity(kind,logical)!=entity)SyncV2Codec.fail("identity_conflict")
                    val r=record(kind,logical,entity,value);known[entity]?.getJSONObject("meta")?.optString("addedAtSource")?.let{r.getJSONObject("meta").put("addedAtSource",it)}
                    normalized.add(r)
                }
                val removed=SyncV2Wire.strings(intent.getJSONArray("removed"),1000000,false).toSet();for(entity in removed)SyncV2Codec.unhex(entity,32)
                business.validate(normalized)
                Triple(normalized,removed,normalized.map{it.getJSONObject("meta").getString("entityKey")}.toSet()+removed)
            }
            if(attempt.isFailure){if(keys!=null)throw attempt.exceptionOrNull()!!;continue}
            val (normalized,removed,captured)=attempt.getOrThrow()
            for(r in normalized) {
                val entity=r.getJSONObject("meta").getString("entityKey")
                if(!same(original[entity]?.getJSONObject("value"),r.getJSONObject("value")))missing=true
                // A legacy input does not authenticate the generation of nonempty
                // conflict facts. Explicit cloud/local choices still retain both branches.
                if(r.getJSONObject("meta").getString("kind")=="note" && r.getJSONObject("value").getJSONObject("noteConflicts").getJSONArray("records").length()!=0)missing=true
                rows[entity]=r
            }
            for(entity in removed){if(original.containsKey(entity))missing=true;rows.remove(entity)}
            selected.put(JSONObject().put("key",entry.getString("key")).put("hash",entry.getString("hash")))
            proposals.put(JSONObject().put("rows",JSONArray(normalized)).put("ids",JSONArray(captured.sorted())))
            ids.addAll(captured)
        }
        return LegacyView(rows.keys.sorted().mapRows{rows.getValue(it)},selected,proposals,ids,missing)
    }
    /** Read metadata, then scan R0+tail without ever replacing original business rows.
     * Repeated host windows finish a large rebuild; the return value exposes continuation. */
    fun refresh(maxPages:Int=16,budgetMillis:Long=290000):JSONObject {
        gate();syncFiles();val status=cloud.readCurrent();trustedNow()?.let{projection.accountContact(it)};val progress=projection.runWindow(maxPages,budgetMillis)
        recovery.purge(trustedNow())
        if(!progress.paused)applyObserved()
        return state().put("status",status).put("projectionPaused",progress.paused)
    }
    fun poll():JSONObject {
        gate();syncFiles();val result=cloud.poll()
        trustedNow()?.let{projection.accountContact(it,result.optBoolean("historyExpired"))}
        val progress=projection.runWindow();if(!progress.paused)applyObserved();return result.put("projectionPaused",progress.paused)
    }
    /** A clean device follows the authenticated current account, including a missed rollback.
     * Local dirty/unknown rows always retain their original business state. */
    fun applyObserved():Boolean {
        gate();syncFiles();complete();val fileToken=fileCapture();val st=projection.state();val a=st.body.optJSONObject("account")?:return false
        val ownPublished=st.body.optJSONObject("publishedReceipt")?.optJSONObject("account")?.optString("commitOid")==a.getString("oid") &&
            st.body.optString("publishedLocalRevision")==st.revision
        if(!st.body.isNull("pendingAccountAction") || !st.body.isNull("pendingChoice") ||
            !ownPublished && (st.body.optBoolean("unknownEditBase") || st.revision!=st.body.optString("acknowledgedLocalRevision","0")))return false
        if(same(st.body.optJSONObject("appliedAccount"),a))return false
        if(!same(st.body.optJSONObject("metadataAccount"),a))SyncV2Codec.fail("closure_incomplete")
        val before=projection.accountLocalRows()
        val target=business.effective(before,cloudRows(),st.body.optJSONObject("appliedAccount")?.optString("generation")?.let{it!=a.getString("generation")}?:false)
        business.validate(target)
        val endangered=endangeredBodies(before,target)
        if(endangered.isNotEmpty()) {
            fileGuard(fileToken){projection.accountProtectPendingBodies(st.version,endangered)}
            return false
        }
        fileGuard(fileToken){ projection.accountWriter(st.version){current->
            val rss=business.apply(before,target);fault("business-applied")
            projection.accountApplied(current,a,target,id(),before,rss)
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
    /** F1/W3 status surface for the (later) UI: openable or note_unopenable with reason. */
    fun noteStatus(logicalId:String):JSONObject {
        val entity=identity.entity("note",logicalId)
        val isolation=projection.noteIsolation().optJSONObject(entity)
        return if(isolation==null)JSONObject().put("openable",true)
        else JSONObject().put("openable",false).put("error","note_unopenable").put("reason",isolation.getString("reason"))
            .put("stateOid",isolation.getString("stateOid")).put("forkedAs",projection.noteFork(logicalId)?:JSONObject.NULL)
    }
    /** R2: fork an isolated note's current local content under a fresh noteId. Both copies survive:
     * the isolated remote row keeps being carried verbatim while the fork is a new entity. */
    fun forkNote(logicalId:String):JSONObject {
        gate();val entity=identity.entity("note",logicalId)
        if(!projection.noteIsolation().has(entity))SyncV2Codec.fail("invalid_schema")
        val forked=projection.forkIsolatedNote(entity,logicalId,now())
        return JSONObject().put("forked",true).put("sourceLogicalId",logicalId).put("forkLogicalId",forked.getString("logicalId"))
            .put("forkEntityKey",forked.getString("entityKey")).put("noteRevision",JSONObject.NULL)
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
    class Preview private constructor(val capture:ChoiceCapture,val canMerge:Boolean,val reason:String?,keys:List<String>) {
        val selectedLegacyKeys:List<String> = java.util.Collections.unmodifiableList(ArrayList(keys))
        companion object {internal fun create(c:ChoiceCapture,yes:Boolean,reason:String?,keys:List<String> = emptyList())=Preview(c,yes,reason,keys)}
    }
    private data class Plan(val local:List<JSONObject>,val cloud:List<JSONObject>,val input:List<JSONObject>,val candidate:List<JSONObject>?,val digest:String,val ids:Set<String>,val files:JSONObject,val actual:List<JSONObject>,val actualIds:Set<String>,val legacySelected:String,val legacyProposals:String,val root:String?=null,val scopeIds:Set<String> = emptySet())
    private val plans=java.util.Collections.synchronizedMap(java.util.WeakHashMap<Preview,Plan>())
    private val transientRows=mutableListOf<AutoCloseable>()
    private fun rowMap()=SyncV2RowMap(db,{projection.accountSeal("preview-row",it)},{projection.accountOpen("preview-row",it)}).also{transientRows.add(it)}
    private val previewSpools=java.util.Collections.newSetFromMap(java.util.IdentityHashMap<SyncV2RowSpool,Boolean>())
    private fun rowSpool()=SyncV2RowSpool(db,{projection.accountSeal("preview-row",it)},{projection.accountOpen("preview-row",it)}).also{previewSpools.add(it)}
    private fun prunePreviewSpools() {
        val live=plans.values.flatMap{listOfNotNull(it.local,it.cloud,it.input,it.candidate,it.actual)}.map{System.identityHashCode(it)}.toSet()
        for(spool in previewSpools.toList())if(System.identityHashCode(spool) !in live){spool.close();previewSpools.remove(spool)}
    }
    override fun close(){closed=true;plans.values.toList().forEach(::release);plans.clear();previewSpools.forEach{it.close()};previewSpools.clear();transientRows.forEach{it.close()};transientRows.clear()}
    /** Preserve the complete-snapshot conflict-channel guard before accepting any entity's
     * operation result, while opening at most one entity's three snapshots at a time. */
    private fun channelGuard(inputs:List<JSONObject>):String? {
        for(input in inputs)try {
            SyncV2NoteConflicts.plannerSets(input.getJSONObject("base"),input.getJSONObject("local"),input.getJSONObject("cloud"),input.optJSONObject("noteConflictGenerations"))
        }catch(e:Exception){return when(e.message){"note_conflict"->"note_conflict";"missing_base"->"missing_base";"conflict_identity_mismatch","identity_conflict"->"identity_conflict";else->"unsupported_feature"}}
        return null
    }
    fun closePreview(preview:Preview) {plans.remove(preview)?.let(::release);prunePreviewSpools()}
    /** Summaries contain only titles, kinds and counts. No body or internal wire identity leaves here. */
    fun previewSummary(preview:Preview):JSONObject {
        gate();val plan=plans[preview]?:SyncV2Codec.fail("choice_stale")
        val baseline=projection.accountBindings()
        fun summary(records:List<JSONObject>,local:Boolean):JSONObject {
            val by=records.indexRows();val changed=(by.keys+baseline.keys).sorted().mapNotNull {id->
                val row=by[id];val base=baseline[id]
                if(row?.has(SyncV2NoteWiring.ISOLATED_KEY)==true)return@mapNotNull null
                if(local && id !in plan.ids)return@mapNotNull null
                if(row==null && base==null)return@mapNotNull null
                if(row!=null && base!=null && same(row.getJSONObject("meta"),base.getJSONObject("meta")) && same(row.getJSONObject("state"),base.getJSONObject("state")))return@mapNotNull null
                val meta=(row?:base!!).getJSONObject("meta")
                JSONObject().put("title",meta.getJSONObject("payload").optString("title",meta.getString("kind")))
                    .put("kind",if(row==null || row.optJSONObject("state")?.optJSONObject("payload")?.optBoolean("deleted")==true)"delete"else if(base==null)"add"else"edit")
            }
            return JSONObject().put("items",JSONArray(changed.take(50))).put("more",(changed.size-50).coerceAtLeast(0)).put("count",changed.size)
        }
        return JSONObject().put("local",summary(plan.local,true)).put("cloud",summary(plan.cloud,false)).put("canMerge",preview.canMerge).put("reason",preview.reason?:JSONObject.NULL)
    }
    fun restoreRecovery(copyId:String):JSONObject {
        gate();now();syncFiles();complete()
        val delta=recoveryDelta(copyId)
        // Private file bodies require their own staged restore workflow; never claim metadata
        // restoration reconstitutes an unavailable original file or release its recovery pin.
        if(delta.getJSONArray("bodyRefs").length()!=0)SyncV2Codec.fail("unsupported_feature")
        val before=projection.accountLocalRows();val rows=before.indexRows().toMutableMap()
        val changes=delta.getJSONArray("changes")
        for(i in 0 until changes.length()) {
            val change=changes.getJSONObject(i);val entity=change.getString("id")
            if(change.getString("kind")=="remove")rows.remove(entity) else {
                val value=change.getJSONObject("value")
                val kind=when {value.has("noteId")->"note";value.has("assetId")->"asset";value.has("folderId")->"folder";
                    value.has("sourceId")&&value.has("articleIdHash")->"rss-state";value.has("articleId")->"article";
                    value.has("bookId")&&value.has("chapterIndex")->"book-reading";value.has("bookId")->"book";value.has("url")->"source";else->SyncV2Codec.fail("unsupported_feature")}
                val logical=SyncV2Business.logicalId(kind,value)
                if(identity.entity(kind,logical)!=entity)SyncV2Codec.fail("identity_conflict")
                rows[entity]=record(kind,logical,entity,value)
            }
        }
        val target=rows.values.toList();business.validate(target)
        val st=projection.state()
        projection.accountWriter(st.version){business.apply(before,target)}
        projection.runWindow()
        return JSONObject().put("restoredEdits",changes.length())
    }
    private fun rows(stored:List<JSONObject>)=stored.mapRows{r->
        if(!r.has("value")&&!r.has(SyncV2NoteWiring.ISOLATED_KEY))r.put("value",SyncV2Business.join(r.getJSONObject("meta").getString("kind"),r.getJSONObject("meta"),r.getJSONObject("state")))
        r
    }
    private fun rowsJson(rows:List<JSONObject>):List<JSONObject> = rowSpool().also{spool->for(r in rows)spool.append(JSONObject().also{out->
        for(key in listOf("meta","state","row",SyncV2NoteWiring.ISOLATED_KEY,SyncV2NoteWiring.SEGMENTS_KEY,SyncV2NoteWiring.REFS_MAPS_KEY,SyncV2NoteWiring.REVISION_KEY))if(r.has(key))out.put(key,r.get(key))
    })}
    private fun release(plan:Plan) {for(rows in listOfNotNull(plan.local,plan.cloud,plan.input,plan.candidate,plan.actual).distinctBy{System.identityHashCode(it)})(rows as? AutoCloseable)?.close()}
    /** One unique planner entry, including all nonempty/unknown note conflict channels and authenticated bases. */
    fun preview(legacyKeys:Set<String>?=null):Preview {
        try {
        gate();syncFiles();complete()
        projection.state().body.optJSONObject("pendingAccountAction")?.takeIf{it.opt("publicationAttempted")==false}?.let(::checkActionCurrent)
        val capture=projection.accountCapture();val st=projection.state()
        projection.accountCheck(st,capture)
        val fileToken=fileCapture()
        val selectedEntries=projection.accountLegacyEntries(legacyKeys)
        val legacyIds=selectedEntries.flatMap{entry->val intent=entry.getJSONObject("intent");intent.getJSONObject("body").keys().asSequence().filter{Regex("[0-9a-f]{64}").matches(it)}.toList()+SyncV2Wire.strings(intent.getJSONArray("removed"),1000000,false)}.toSet()
        var scopeIds=projection.accountCatalog.differences()+projection.accountChangedIds()+legacyIds+st.body.optJSONArray("contentPendingIds").let{a->if(a==null)emptySet()else SyncV2Wire.strings(a,1000000,false).toSet()}
        // Public article footprints matter only for operations in the RSS domains.
        // An unrelated note/folder edit must not turn a large RSS library into a full scan.
        if(scopeIds.any{projection.accountCatalog.kindOf(it) !in setOf("note","folder","book","book-reading","asset")}) {
            val rss=projection.accountCatalog.typedIds("rss-state")
            if(rss.isNotEmpty())scopeIds=scopeIds+rss+projection.accountCatalog.typedIds("source")
        }
        val root=st.body.optString("metadataTreeRoot").takeIf{it.isNotEmpty() && scopeIds.size<=64 && !st.body.optBoolean("unknownEditBase") && same(st.body.optJSONObject("metadataAccount"),st.body.optJSONObject("account"))}
        val actual=projection.accountLocalRows(if(root!=null)scopeIds else null);val remote=cloudRows(if(root!=null)scopeIds else null);val legacy=legacyView(actual,remote,legacyKeys);val local=legacy.rows
        val actualIds=projection.accountChangedIds()+st.body.optJSONArray("contentPendingIds").let{a->if(a==null)emptySet()else (0 until a.length()).map{a.getString(it)}.toSet()}+actual.asSequence().filter{it.has("p5Base")}.map{it.getJSONObject("meta").getString("entityKey")}.toSet()
        val changed=actualIds+legacy.ids
        // Unchanged non-note entities have no operations and cannot affect the planner.
        // Include every nonempty/unknown note conflict channel in all snapshots.
        // Empty unchanged channels are authenticated by their absence in the selector.
        // F1/R3: isolated wire rows never enter planning or the planner tree. Every choice carries
        // the stored row back verbatim under its original metaOid/stateOid instead (never joined,
        // validated, re-sealed or dropped).
        val isolatedIds=remote.filterRows{it.has(SyncV2NoteWiring.ISOLATED_KEY)}.map{it.getJSONObject("meta").getString("entityKey")}.toSet()
        val planningIds=(changed-isolatedIds)+(local.asSequence()+remote.asSequence()).filter{!isolatedIds.contains(it.getJSONObject("meta").getString("entityKey"))&&it.getJSONObject("meta").getString("kind")=="note"}.map{it.getJSONObject("meta").getString("entityKey")}.toSet()
        // Planner semantics are independent per entity. The complete channel set is still
        // checked, but base/local/cloud bodies are opened for only one entity at a time.
        val localBy=local.indexRows();val remoteBy=remote.indexRows();val actualBy=actual.indexRows()
        val channels=if(root!=null)listOf("candidate","cloud").associateWith{projection.accountCatalog.channelRows(it)}else emptyMap()
        val inputs=rowSpool();val merged=rowSpool()
        var reason:String?=null
        var missing=st.body.optBoolean("unknownEditBase")||legacy.missingBase
        val editBase=st.body.optJSONObject("editBase")
        val entities=(planningIds+channels.values.flatMap{it.keys})-isolatedIds
        for(entity in entities.sorted()) {
            // Authenticate the selector's channel even when this entity already has a
            // full row. A missing channel must not be hidden by the row fallback.
            val channelValues=channels.mapValues{(_,rows)->rows[entity]}
            fun plannerValue(record:JSONObject?,side:String):JSONObject? {
                if(record!=null&&!record.has(SyncV2NoteWiring.ISOLATED_KEY))return values(listOf(record),true).optJSONObject(entity)
                return channelValues[side]
            }
            val localValue=plannerValue(localBy[entity],"candidate")
            val cloudValue=plannerValue(remoteBy[entity],"cloud")
            val base=JSONObject();val localOne=JSONObject();val cloudOne=JSONObject();val ops=JSONArray()
            if(localValue!=null){base.put(entity,localValue);localOne.put(entity,localValue)}
            if(cloudValue!=null)cloudOne.put(entity,cloudValue)
            if(entity in changed) {
                val captured=projection.accountBase(entity)
                val b=captured.second?:actualBy[entity]?.optJSONObject("p5Base")
                if(!captured.first&&b==null){missing=true;continue}
                val l=localValue
                if(b!=null||l!=null) {
                    if(b==null)base.remove(entity)else {
                        val kind=localBy[entity]?.getJSONObject("meta")?.getString("kind")?:remoteBy[entity]?.getJSONObject("meta")?.getString("kind")
                        if(kind==null){missing=true;continue}
                        base.put(entity,copy(b).put("identity",SyncV2Business.logicalId(kind,b)))
                    }
                    val fields=if(l==null||b==null)emptyList()else (l.keys().asSequence().toSet()+b.keys().asSequence().toSet()).filter{it!="identity"&&!same(l.opt(it),b.opt(it))}.sorted()
                    val kind=when{l==null->"delete";b==null->"create";l.optJSONObject("lifecycle")?.getLong("generation")?.let{it>(b.optJSONObject("lifecycle")?.getLong("generation")?:0)}==true->"explicit-restore";else->"update"}
                    ops.put(JSONObject().put("id",entity).put("kind",kind).put("fields",JSONArray(fields)).put("complete",l==null||localBy[entity]?.let{recovery.contentReady(it)}==true))
                }
            }
            val input=JSONObject().put("base",base).put("local",localOne).put("cloud",cloudOne).put("ops",ops)
            if(editBase!=null)input.put("noteConflictGenerations",JSONObject().put("base",editBase.getString("generation")).put("local",editBase.getString("generation")).put("cloud",capture.generation))
            inputs.append(input)
            val result=SyncV2Planner.canMerge(input)
            if(!result.getBoolean("canMerge")){if(reason==null)reason=result.getString("reason");continue}
            val tree=result.getJSONObject("tree")
            for(id in tree.keys())if(root==null||id in scopeIds) {
                val original=remoteBy[id]?:localBy[id]?:SyncV2Codec.fail("identity_conflict");val m=original.getJSONObject("meta")
                val v=copy(tree.getJSONObject(id)).apply{remove("identity")}
                merged.append(record(m.getString("kind"),m.getString("logicalId"),id,v).also{it.getJSONObject("meta").put("addedAtSource",m.getString("addedAtSource"))})
            }
        }
        channelGuard(inputs)?.let{reason=it}
        if(missing)reason="missing_base"
        var candidate:List<JSONObject>?=null
        if(reason==null) {
            val untouched=remote.indices.filter{remote[it].getJSONObject("meta").getString("entityKey") !in planningIds}.mapRows{snapshotRow(remote[it],"target")}
            candidate=object:AbstractList<JSONObject>(){override val size get()=untouched.size+merged.size;override fun get(index:Int)=if(index<untouched.size)untouched[index]else merged[index-untouched.size]}
            try {
                business.validate(candidate);client.capabilities().also{projection.noteSegmentsServerAnnounced=client.noteSegmentsWrite()}
                wireIndex(candidate,currentKeyVersion(),remote,root,scopeIds,client.authors(identity,approved)).also{it.close()}
            }catch(e:Exception){if(e.message=="object_too_large")throw e;candidate=null;reason=when(e.message){"storage_pressure"->"storage_pressure";"closure_incomplete"->"missing_content";else->"unsupported_feature"}}
        }
        projection.accountCheck(projection.state(),capture)
        val handle=Preview.create(capture,candidate!=null,reason,(0 until legacy.selected.length()).map{legacy.selected.getJSONObject(it).getString("key")})
        val localJson=rowsJson(local);val actualJson=if(legacy.selected.length()==0)localJson else rowsJson(actual)
        val candidateJson=candidate?.let{rowsJson(it)};merged.close()
        plans[handle]=Plan(localJson,rowsJson(remote),inputs,candidateJson,SyncV2Codec.hash(st.body),changed,fileToken,actualJson,actualIds,legacy.selected.toString(),legacy.proposals.toString(),root,scopeIds)
        return handle
        }finally{prunePreviewSpools();transientRows.forEach{it.close()};transientRows.clear()}
    }
    private fun syncFiles(){projection.accountFilesObserved(fileCapture())}
    private fun fileCapture():JSONObject {
        val token=com.lightningstudio.watchrss.phone.data.local.CloudExportFileWitness.captureForProjection(files)
        return JSONObject().put("notes",token.notes).put("privateFiles",token.privateFiles)
    }
    private fun <T> fileGuard(token:JSONObject,work:()->T):T = com.lightningstudio.watchrss.phone.data.local.CloudExportFileWitness.withProjectionCapture(files,
        com.lightningstudio.watchrss.phone.data.local.CloudExportFileWitness.Revision(token.getString("notes"),token.getString("privateFiles")),work)
    internal fun record(kind:String,logicalId:String,entity:String,value:JSONObject):JSONObject {
        // R2/F1: an isolated note is unopenable on this device; edits enter through forkNote().
        if(kind=="note"&&projection.noteIsolation().has(entity))SyncV2Codec.fail("note_unopenable")
        val pair=SyncV2Business.split(kind,logicalId,entity,value,null)
        return JSONObject().put("meta",pair.meta).put("state",pair.state).put("value",SyncV2Business.join(kind,pair.meta,pair.state))
    }
    private fun bodyIdentity(r:JSONObject):String {
        val m=r.getJSONObject("meta");val v=r.optJSONObject("value")?:JSONObject()
        return SyncV2Codec.hash(JSONObject().put("kind",m.getString("kind")).put("id",m.getString("logicalId"))
            .put("body",when(m.getString("kind")){"article"->v.opt("contentHash");"book"->v.opt("fingerprint");"asset"->v.opt("updatedAt");else->JSONObject.NULL}))
    }
    private fun endangeredBodies(before:List<JSONObject>,target:List<JSONObject>):Set<String> {
        val targets=target.indexRows()
        return before.asSequence().filter { r ->
            val m=r.getJSONObject("meta");val entity=m.getString("entityKey");val v=r.getJSONObject("value");val next=targets[entity]
            if(m.getString("kind") !in setOf("article","book","asset"))return@filter false
            val binding=db.controlQuery("SELECT body FROM syncv2_p5_bindings WHERE scope=? AND entity_key=?",arrayOf(scope,entity)).use{if(it.moveToFirst())projection.accountOpen("binding",it.getBlob(0))else null}
            m.getString("kind") in setOf("article","book","asset") && !v.optBoolean("deleted") &&
                binding?.optJSONObject("row")?.isNull("recipeOid")==true && recovery.hasOriginalSource(r) &&
                (next==null || next.getJSONObject("value").optBoolean("deleted") || bodyIdentity(r)!=bodyIdentity(next))
        }.map{it.getJSONObject("meta").getString("entityKey")}.toSet()
    }
    private fun wireIndex(records:List<JSONObject>,version:Long,current:List<JSONObject>?=null,sparseRoot:String?=null,scopeIds:Set<String> = emptySet(),
        authors:SyncV2Authors?=null):SyncV2Projection.WireIndex {
        val objects=SyncV2Spool(db);val refs=mutableListOf<JSONObject>();val rows=mutableListOf<JSONObject>();val noteMetadata=mutableMapOf<String,JSONObject>();val reservations=mutableListOf<AutoCloseable>()
        try {
        val candidates=(current?:projection.cloudRows()).indexRows()
        val currentObjects=if(sparseRoot==null)projection.cloudObjects()else emptyMap()
        val reused=mutableSetOf<String>()
        fun raw(oid:String):ByteArray {reused.add(oid);return currentObjects[oid]?:projection.accountObject(oid)}
        for(r in records) {
            val m=r.getJSONObject("meta");val entity=m.getString("entityKey");val kind=m.getString("kind");val remote=candidates[entity]
            if(kind=="note"&&remote?.has(SyncV2NoteWiring.ISOLATED_KEY)==true) {
                // R3: an isolated row is carried back verbatim under every choice, never re-sealed.
                val row=remote.getJSONObject("row")
                if(sparseRoot==null)for(field in listOf("metaOid","stateOid")) {
                    val oid=row.getString(field);if(oid !in currentObjects.keys)continue;val bytes=raw(oid)
                    if(oid.substringAfter(":sha256:")!=SyncV2Codec.hex(SyncV2Codec.sha(bytes)))SyncV2Codec.fail("hash_mismatch")
                    objects[oid]=bytes
                }
                noteMetadata[entity]=JSONObject().also{out->for(key in listOf(SyncV2NoteWiring.SEGMENTS_KEY,SyncV2NoteWiring.REFS_MAPS_KEY,SyncV2NoteWiring.REVISION_KEY))if(remote.has(key))out.put(key,remote.get(key))}
                rows.add(row);r.put("row",row).remove("wire");continue
            }
            if(kind !in setOf("article","book","asset") && remote!=null && same(remote.getJSONObject("meta"),m) && same(remote.getJSONObject("state"),r.getJSONObject("state"))) {
                val row=remote.getJSONObject("row")
                if(sparseRoot==null)for(field in listOf("metaOid","stateOid")) {
                    val oid=row.getString(field);val bytes=raw(oid)
                    if(oid.substringAfter(":sha256:")!=SyncV2Codec.hex(SyncV2Codec.sha(bytes)))SyncV2Codec.fail("hash_mismatch")
                    objects[oid]=bytes
                }
                noteMetadata[entity]=JSONObject().also{out->for(key in listOf(SyncV2NoteWiring.SEGMENTS_KEY,SyncV2NoteWiring.REFS_MAPS_KEY,SyncV2NoteWiring.REVISION_KEY))if(remote.has(key))out.put(key,remote.get(key))}
                rows.add(row);r.put("row",row).remove("wire");continue
            }
            val known=projection.accountBinding(entity)
            fun part(type:String,plain:JSONObject):String {
                if(remote!=null && same(remote.getJSONObject(type),plain)) {
                    val oid=remote.getJSONObject("row").getString(type+"Oid");val bytes=raw(oid)
                    if(oid!="$type:sha256:${SyncV2Codec.hex(SyncV2Codec.sha(bytes))}")SyncV2Codec.fail("hash_mismatch")
                    objects[oid]=bytes;return oid
                }
                val sealed=SyncV2Envelope.seal(identity,contentKey(version),version,type,SyncV2Codec.utf8(SyncV2Codec.canonical(plain)))
                objects[sealed.oid]=sealed.bytes
                if(type=="state") {
                    val signed=SyncV2Index.refs(identity,signer,sealed.oid);refs.add(signed)
                    objects[SyncV2Codec.oid("state-refs",signed)]=SyncV2Codec.utf8(SyncV2Codec.canonical(signed))
                }
                return sealed.oid
            }
            val metaOid=part("meta",m)
            val noteWire=if(kind=="note"&&authors!=null&&remote?.optJSONObject("payload")==null&&r.optJSONObject("value")!=null) {
                SyncV2NoteWiring.seal(identity,signer,contentKey(version),version,db,scope,entity,r.getJSONObject("state"),
                    client.noteSegmentsWrite(),authors,
                    remote?.optString(SyncV2NoteWiring.REVISION_KEY)?.takeIf{it.matches(Regex("[0-9a-f]{32}"))},
                    remote?.optJSONArray(SyncV2NoteWiring.SEGMENTS_KEY)?.let{a->(0 until a.length()).map{a.getString(it)}}?:emptyList(),
                    remote?.optJSONObject(SyncV2NoteWiring.REFS_MAPS_KEY),SyncV2NoteWiring.outboxOwned(db,scope,entity))
            } else null
            // Account choices publish complete selected snapshots. A local budget failure
            // aborts before publication; silently omitting it would erase its Room row/queue.
            val stateOid=noteWire?.parentOid?:part("state",r.getJSONObject("state"))
            noteWire?.let{w->
                reservations.add(w.reservation)
                objects.putAll(w.objects);refs.add(w.parentRefs);refs.addAll(w.childRefs)
                noteMetadata[entity]=JSONObject().put(SyncV2NoteWiring.SEGMENTS_KEY,JSONArray(w.segmentOids)).put(SyncV2NoteWiring.REVISION_KEY,w.noteRevision)
                    .put(SyncV2NoteWiring.REFS_MAPS_KEY,JSONObject().also{maps->for(ref in w.childRefs){val oid=ref.getJSONObject("body").getString("stateOid")
                        maps.put(oid,JSONObject().put("scope",identity.scope).put("parentOid",oid).put("refsOid",SyncV2Codec.oid("state-refs",ref))
                            .put("authorDeviceId",signer.deviceId).put("keyId",signer.keyId).put("totalBytes",SyncV2Codec.utf8(SyncV2Codec.canonical(ref)).size))}})
            }
            val cached=projection.accountWireRowFor(r)
            val bodySources=listOfNotNull(remote,known,cached?.let{copy(r).put("row",it)}).filter{it.has("row") && bodyIdentity(it)==bodyIdentity(r)}
            val source=bodySources.maxWithOrNull(compareBy<JSONObject>{!it.getJSONObject("row").isNull("recipeOid")}.thenBy{SyncV2Codec.d64(it.getJSONObject("row").getString("rowSequence"))})
            val priorRows=listOfNotNull(known?.optJSONObject("row"),remote?.optJSONObject("row"),cached)
            val inherited=priorRows.maxByOrNull{SyncV2Codec.d64(it.getString("rowSequence"))}
            val addedAt=known?.optJSONObject("row")?.optLong("addedAtMillis")?:remote?.optJSONObject("row")?.optLong("addedAtMillis")?:0
            val needsBody=kind in setOf("article","book","asset")
            val row=JSONObject().put("entityKey",entity).put("metaOid",metaOid).put("stateOid",stateOid).put("addedAtMillis",addedAt)
                .put("rowSequence",inherited?.getString("rowSequence")?:"0").put("deleted",r.getJSONObject("value").optBoolean("deleted",false))
                .put("bodyRevision",if(needsBody)SyncV2Codec.hex(SyncV2Codec.random(16))else JSONObject.NULL)
                .put("promiseId",if(needsBody)id()else JSONObject.NULL).put("recipeOid",JSONObject.NULL).put("opaqueContentId",JSONObject.NULL).put("contentBytes",JSONObject.NULL).put("requiredFeatures",JSONArray())
            if(source!=null)for(f in listOf("bodyRevision","promiseId","recipeOid","opaqueContentId","contentBytes"))row.put(f,source.getJSONObject("row").get(f))
            val unchanged=remote?.getJSONObject("row")?.let{old->same(JSONObject(row.toString()).apply{remove("rowSequence")},JSONObject(old.toString()).apply{remove("rowSequence")})}==true
            row.put("rowSequence",if(unchanged)remote!!.getJSONObject("row").getString("rowSequence")else SyncV2Codec.next(inherited?.getString("rowSequence")?:"0"))
            rows.add(row);r.put("row",row).remove("wire")
        }
        val readCache=mutableMapOf<String,ByteArray>() // tree pages only
        val edit=if(sparseRoot==null)null else SyncV2Index.edit(identity.scope,sparseRoot,scopeIds.associateWith{entity->rows.firstOrNull{it.getString("entityKey")==entity}}){oid->readCache.getOrPut(oid){projection.accountObject(oid)}}
        val tree=edit?.tree?:SyncV2Index.build(identity.scope,rows);objects.putAll(tree.objects)
        return SyncV2Projection.WireIndex(tree,objects,refs,if(sparseRoot==null)objects.keys.intersect(currentObjects.keys)else reused,edit?.retired?:emptySet(),noteMetadata,reservations)
        }catch(e:Throwable){reservations.forEach{it.close()};objects.close();throw e}
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
        prepareChoice(preview,choice,keyVersion)
        return resume()?:SyncV2Codec.fail("choice_stale")
    }
    private fun prepareChoice(preview:Preview,choice:String,keyVersion:Long) {
        gate();syncFiles();val plan=plans[preview]?:SyncV2Codec.fail("choice_stale");val c=preview.capture
        projection.accountCheck(projection.state(),c);SyncV2Codec.next(c.stateVersion);if(SyncV2Codec.hash(projection.state().body)!=plan.digest)SyncV2Codec.fail("choice_stale")
        if(choice !in setOf("cloud","local","merge"))SyncV2Codec.fail("invalid_schema")
        if(choice=="merge" && !preview.canMerge)throw SyncV2CloudError("merge_unavailable",details=JSONObject().put("reason",preview.reason?:"missing_base"))
        if(choice=="merge") {
            for(input in plan.input){val result=SyncV2Planner.canMerge(input);if(!result.getBoolean("canMerge"))SyncV2Codec.fail("choice_stale")}
        }
        fileGuard(plan.files){}
        val context=context(c);val local=rows(plan.local);val remote=rows(plan.cloud)
        // R3: every choice (cloud/local/merge) carries isolated rows back verbatim under their
        // original metaOid/stateOid; a same-entity local candidate never re-seals or replaces one.
        val isolatedRemote=remote.filterRows{it.has(SyncV2NoteWiring.ISOLATED_KEY)}
        val isolatedEntityKeys=isolatedRemote.map{it.getJSONObject("meta").getString("entityKey")}.toSet()
        val withoutIsolated=fun(rows:List<JSONObject>)=rows.filterRows{it.getJSONObject("meta").getString("entityKey") !in isolatedEntityKeys}
        val target=when(choice){"cloud"->remote;"local"->isolatedRemote.plusRows(withoutIsolated(local));else->isolatedRemote.plusRows(withoutIsolated(rows(plan.candidate?:SyncV2Codec.fail("choice_stale"))))}
        business.validate(target.filterRows{it.has("value")})
        val bundles=mutableListOf<SyncV2RecoveryStorage.Bundle>()
        if(choice=="cloud" && keepDiscardedLocalChanges7Days()) {
            val base=projection.state().body.optJSONObject("editBase")?:JSONObject().put("accountOid",c.accountOid).put("generation",c.generation)
            val seen=mutableSetOf<String>()
            fun prepare(input:List<JSONObject>,captured:Set<String>) {
                val byId=input.indexRows()
                val ids=captured.filter{entity->
                    val value=byId[entity]?.let{JSONObject().put("meta",it.getJSONObject("meta")).put("state",it.getJSONObject("state"))}
                    seen.add(entity+":"+SyncV2Codec.canonical(value))
                }.toSet()
                if(ids.isEmpty())return
                val bundle=recovery.prepare(input,remote,base,ids,c.localChangeRevision,c.accountOid,now())
                val key=projection.recoveryKey();val count=try{SyncV2Recovery.open(bundle.copy,key).getJSONArray("changes").length()}finally{key.fill(0)}
                if(count>0)bundles.add(bundle)
            }
            prepare(rows(plan.actual),plan.actualIds)
            val proposals=JSONArray(plan.legacyProposals)
            for(i in 0 until proposals.length()) {
                val proposal=proposals.getJSONObject(i);val records=proposal.getJSONArray("rows").let{a->(0 until a.length()).map{a.getJSONObject(it)}}
                prepare(records,SyncV2Wire.strings(proposal.getJSONArray("ids"),1000000,false).toSet())
            }
            recovery.checkBundles(bundles)
        }
        if(choice=="local") {
            val byEntity=remote.indexRows()
            for(r in local) {
                val prior=byEntity[r.getJSONObject("meta").getString("entityKey")]
                if((prior==null || !same(prior.getJSONObject("meta"),r.getJSONObject("meta")) || !same(prior.getJSONObject("state"),r.getJSONObject("state"))) && !recovery.contentReady(r))SyncV2Codec.fail("closure_incomplete")
            }
        }
        val requestId=id();val action=JSONObject().put("id",requestId).put("kind",choice).put("capture",c.json()).put("serverTime",now()).put("fileCapture",plan.files).put("legacySelected",JSONArray(plan.legacySelected))
        if(plan.root!=null)action.put("sparseBaseRoot",plan.root).put("sparseIds",JSONArray(plan.scopeIds.sorted()))
        val index=if(choice=="cloud")null else wireIndex(target,keyVersion,remote,plan.root,plan.scopeIds,context.authors)
        if(index!=null && plan.root!=null)action.put("retiredTrees",JSONArray(index.retiredTrees.sorted()))
        val objects=SyncV2Spool(db).apply{if(index!=null)putAll(index.objects.selectObjects{it !in index.reused})}
        try {
        if(index==null)action.put("request",JSONObject().put("requestId",requestId).put("choiceId",requestId).put("expectedAccountRevision",c.accountRevision).put("expectedRollbackGeneration",c.generation).put("selectedLocalChangeRevision",c.localChangeRevision))
        else publication(action,c,context,index,objects,if(choice=="local")"resolve-local"else"resolve-merge",null)
        val captured=if(choice=="cloud")null else if(plan.actual===plan.local)local else rows(plan.actual)
        val publishTarget=if(choice=="cloud")emptyList() else target.mapRows{r->
            val entity=r.getJSONObject("meta").getString("entityKey")
            val indexed=SyncV2Index.find(identity.scope,index!!.tree.root,entity){oid->index.objects[oid]?:projection.accountObject(oid)}?:SyncV2Codec.fail("closure_incomplete")
            r.put("row",indexed)
            index.noteMetadata[entity]?.let{metadata->for(key in metadata.keys())r.put(key,metadata.get(key))}
            r
        }
        stage(c,action,publishTarget,objects,bundles,true,captured);plans.remove(preview)?.let(::release);prunePreviewSpools();fault("action-staged")
        } finally {objects.close();index?.close()}
    }
    fun rollback(capture:ChoiceCapture,targetOid:String):JSONObject {
        gate();syncFiles();complete();projection.accountCheck(projection.state(),capture,false);SyncV2Codec.next(capture.stateVersion);SyncV2Codec.commitOid(targetOid)
        val fileToken=fileCapture();val context=context(capture)
        val requestId=id();val prepared=client.prepareHistory(JSONObject().put("requestId",requestId).put("targetCommitOid",targetOid).put("expectedAccountRevision",capture.accountRevision).put("expectedRollbackGeneration",capture.generation))
        if(prepared.getLong("expiresAtMillis")<=now())SyncV2Codec.fail("snapshot_expired")
        val envelope=header(targetOid);val verified=projection.verifyCloud(SyncV2ControlClosure(identity,context.authors,contentKey,noteSegmentsReadGate()),envelope,{client.raw(it,context.window.getString("windowId"))},{p->client.reference(p,context.window.getString("windowId")).let{copy(it).apply{remove("version")}}})
        verified.use {
        if(verified.commitOid!=targetOid || envelope.getJSONObject("body").getString("treeRoot")!=prepared.getString("treeRoot"))SyncV2Codec.fail("hash_mismatch")
        val target=verified.rows.mapRows{if(it.has(SyncV2NoteWiring.ISOLATED_KEY))copy(it)
            else copy(it).put("value",SyncV2Business.join(it.getJSONObject("meta").getString("kind"),it.getJSONObject("meta"),it.getJSONObject("state")))}
        business.validate(target.filterRows{it.has("value")})
        val tree=SyncV2Index.Tree(prepared.getString("treeRoot"),emptyMap())
        val index=SyncV2Projection.WireIndex(tree,verified.objects,emptyList());val objects=SyncV2Spool(db).apply{putAll(verified.objects)}
        try {
        val action=JSONObject().put("id",id()).put("kind","rollback").put("capture",capture.json()).put("serverTime",now()).put("restoreLease",prepared).put("fileCapture",fileToken)
        publication(action,capture,context,index,objects,"rollback",targetOid)
        stage(capture,action,target,objects,emptyList(),false);fault("action-staged")
        } finally {objects.close()}
        }
        return resume()?:SyncV2Codec.fail("choice_stale")
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
    private fun stage(c:ChoiceCapture,action:JSONObject,rows:List<JSONObject>,objects:Map<String,ByteArray>,bundles:List<SyncV2RecoveryStorage.Bundle>,pending:Boolean,capturedRows:List<JSONObject>?=null) {
        val latest=projection.state();projection.accountCheck(latest,c,pending);SyncV2Codec.next(latest.version)
        val baseKeys=if(action.has("sparseBaseRoot"))emptySet()else db.controlQuery("SELECT entity_key FROM syncv2_p5_bindings WHERE scope=?",arrayOf(scope)).use{c->buildSet{while(c.moveToNext())add(c.getString(0))}}
        val snapshots=linkedMapOf<String,List<JSONObject>>()
        if(action.getString("kind")!="cloud") {
            snapshots["target"]=rows
            if(action.getString("kind") in setOf("local","merge"))snapshots["capture"]=capturedRows?:projection.accountLocalRows()
        }
        val prepared=SyncV2Spool(db)
        try {
        action.put("baselineRequestId",latest.body.optString("appliedRequestId","")).put("snapshotFormat",2)
        for((role,input)in snapshots) {
            val wanted=input.indexRows()
            for(entity in baseKeys+wanted.keys) {
                val value=wanted[entity]?.let{snapshotRow(it,role)}
                val old=if(action.has("sparseBaseRoot"))null else projection.accountBinding(entity)?.let{snapshotRow(it,role)}
                val identical=if(role=="target" && value!=null && old!=null)same(snapshotRow(value,"capture"),snapshotRow(old,"capture"))else same(value,old)
                if(!identical)prepared["$role/$entity"]=projection.accountSeal("row",if(value==null)JSONObject().put("removed",true)else JSONObject().put("record",value))
            }
            prepared["$role/@manifest"]=projection.accountSeal("row",JSONObject().put("count",wanted.size).put("sha256",snapshotHash(input.mapRows{snapshotRow(it,role)})).put("baselineRequestId",action.getString("baselineRequestId")))
        }
        fault("before-action-stage")
        fileGuard(action.getJSONObject("fileCapture")){ projection.accountWriter(c.stateVersion){st->
            projection.accountCheck(st,c,pending);if(!st.body.isNull("pendingAccountAction") || !st.body.isNull("pendingPublish"))SyncV2Codec.fail("choice_pending")
            val id=action.getString("id");action.put("actorDeviceId",signer.deviceId).put("publicationAttempted",false)
            for((oid,bytes)in objects)db.execSQL("INSERT INTO syncv2_p5_objects VALUES(?,?,?,?)",arrayOf(scope,id,oid,bytes))
            for((key,value)in prepared)db.execSQL("INSERT INTO syncv2_p5_rows VALUES(?,?,?,?)",arrayOf(scope,id,key,value))
            action.put("recoveryCount",bundles.size)
            for((index,bundle)in bundles.withIndex()) {
                val suffix=if(index==0)""else"-$index"
                val copyBytes=SyncV2Codec.utf8(SyncV2Codec.canonical(bundle.copy));val pinBytes=projection.accountSeal("prepared-recovery-pin",bundle.pin)
                for((kind,bytes)in listOf("recovery-copy$suffix" to copyBytes,"recovery-pin$suffix" to pinBytes)) {
                    action.put(kind,JSONObject().put("bytes",bytes.size).put("sha256",SyncV2Codec.hex(SyncV2Codec.sha(bytes))))
                    for(offset in bytes.indices step 65536)db.execSQL("INSERT INTO syncv2_p5_objects VALUES(?,?,?,?)",arrayOf(scope,id,"$kind/${offset/65536}",bytes.copyOfRange(offset,minOf(offset+65536,bytes.size))))
                }
                for((oid,bytes)in bundle.objects)db.execSQL("INSERT INTO syncv2_p5_objects VALUES(?,?,?,?)",arrayOf(scope,id,"local-recovery$suffix/$oid",bytes))
            }
            if(action.has("sequence"))st.body.put("reservedDeviceSequence",action.getString("sequence"))
            action.put("expectedStateVersion",SyncV2Codec.next(st.version));st.body.put("pendingAccountAction",action)
        }}
        } finally {prepared.close()}
    }
    private fun stagedObjects(id:String):Map<String,ByteArray> {
        val oids=db.controlQuery("SELECT oid FROM syncv2_p5_objects WHERE scope=? AND action_id=?",arrayOf(scope,id)).use{c->buildSet{while(c.moveToNext())add(c.getString(0))}}
        return object:AbstractMap<String,ByteArray>() {
            override val keys get()=oids
            override fun get(key:String):ByteArray?=if(key !in oids)null else db.controlQuery("SELECT body FROM syncv2_p5_objects WHERE scope=? AND action_id=? AND oid=?",arrayOf(scope,id,key)).use{if(it.moveToFirst())it.getBlob(0)else null}
            override val entries get()=oids.map{key->object:Map.Entry<String,ByteArray>{override val key=key;override val value get()=getValue(key)}}.toSet()
        }
    }
    private fun snapshotRow(row:JSONObject,role:String):JSONObject = JSONObject().put("meta",row.getJSONObject("meta")).put("state",row.getJSONObject("state")).also{out->
        if(row.has("value"))out.put("value",row.getJSONObject("value"))
        // F1/R3: an isolated row survives the staged journal as the same verbatim wire row:
        // no value is materialized, and the reuse metadata (segments, refs maps, revision) stays attached.
        for(key in listOf(SyncV2NoteWiring.ISOLATED_KEY,SyncV2NoteWiring.SEGMENTS_KEY,SyncV2NoteWiring.REFS_MAPS_KEY,SyncV2NoteWiring.REVISION_KEY))if(row.has(key))out.put(key,row.get(key))
        if(role=="target" && row.has("row"))out.put("row",row.getJSONObject("row"))
    }
    private fun snapshotHash(rows:List<JSONObject>):String {
        // Hash the canonical array without allocating an additional whole-library string.
        val digest=java.security.MessageDigest.getInstance("SHA-256");digest.update('['.code.toByte())
        for((index,row)in rows.indices.sortedBy{rows[it].getJSONObject("meta").getString("entityKey")}.mapRows{rows[it]}.withIndex()) {
            if(index!=0)digest.update(','.code.toByte())
            digest.update(SyncV2Codec.utf8(SyncV2LocalJson.canonical(row)))
        }
        digest.update(']'.code.toByte());return SyncV2Codec.hex(digest.digest())
    }
    private fun stagedRows(id:String,role:String="target",expand:Boolean=false):List<JSONObject> {
        val entries=rowMap()
        val keys=db.controlQuery("SELECT entity_key FROM syncv2_p5_rows WHERE scope=? AND action_id=? ORDER BY entity_key",arrayOf(scope,id)).use{c->buildList{while(c.moveToNext())add(c.getString(0))}}
        for(entity in keys)db.controlQuery("SELECT body FROM syncv2_p5_rows WHERE scope=? AND action_id=? AND entity_key=?",arrayOf(scope,id,entity)).use{c->c.moveToFirst();entries[entity]=projection.accountOpen("row",c.getBlob(0))}
        val manifest=entries["$role/@manifest"]
        if(manifest==null) {
            if(role!="target")SyncV2Codec.fail("missing_base")
            return entries.keys.filter{'/' !in it}.mapRows{entries.getValue(it)} // Pre-repair pending actions remain recoverable.
        }
        if(manifest.getString("baselineRequestId")!=projection.state().body.optString("appliedRequestId",""))SyncV2Codec.fail("choice_stale")
        val pending=projection.state().body.optJSONObject("pendingAccountAction")?:SyncV2Codec.fail("choice_stale")
        val sparse=pending.has("sparseBaseRoot")
        val result=rowMap();if(!sparse)for((entity,value)in projection.accountBindings())result[entity]=snapshotRow(value,role)
        for((key,value)in entries)if(key.startsWith("$role/") && key!="$role/@manifest") {
            val entity=key.removePrefix("$role/");SyncV2Codec.unhex(entity,32)
            if(value.optBoolean("removed"))result.remove(entity)else {
                val record=value.getJSONObject("record");if(record.getJSONObject("meta").getString("entityKey")!=entity)SyncV2Codec.fail("identity_conflict")
                result[entity]=record
            }
        }
        if(role=="target" && !sparse) {
            val objects=objectOverlay(projection.cloudObjects(),stagedObjects(id))
            val action=projection.state().body.optJSONObject("pendingAccountAction")?:SyncV2Codec.fail("choice_stale")
            val root=action.getJSONObject("envelope").getJSONObject("body").getString("treeRoot")
            val indexed=SyncV2Index.read(identity.scope,root,{oid->objects[oid]?:client.raw(oid)})
            if(indexed.map{it.getString("entityKey")}.toSet()!=result.keys)SyncV2Codec.fail("hash_mismatch")
            for(row in indexed){val entity=row.getString("entityKey");result[entity]=result.getValue(entity).put("row",row)}
        }
        val rows=result.keys.sorted().mapRows{result.getValue(it)}
        if(rows.size!=manifest.getInt("count") || snapshotHash(rows)!=manifest.getString("sha256"))SyncV2Codec.fail("hash_mismatch")
        if(sparse && role=="target") {
            val objects=stagedObjects(id);val root=pending.getJSONObject("envelope").getJSONObject("body").getString("treeRoot");val cache=mutableMapOf<String,ByteArray>()
            for(record in rows){val indexed=SyncV2Index.find(identity.scope,root,record.getJSONObject("meta").getString("entityKey")){oid->objects[oid]?:cache.getOrPut(oid){runCatching{projection.accountObject(oid)}.getOrElse{client.raw(oid)}}};if(!same(indexed,record.getJSONObject("row")))SyncV2Codec.fail("hash_mismatch")}
        }
        if(sparse && expand) {
            val all=rowMap();for((entity,value)in projection.accountBindings())all[entity]=snapshotRow(value,role)
            val ids=SyncV2Wire.strings(pending.getJSONArray("sparseIds"),64,false).toSet();for(entity in ids)all.remove(entity);for(record in rows)all[record.getJSONObject("meta").getString("entityKey")]=record
            if(role=="target") {val objects=stagedObjects(id);val root=pending.getJSONObject("envelope").getJSONObject("body").getString("treeRoot");val indexed=SyncV2Index.read(identity.scope,root){oid->objects[oid]?:runCatching{projection.accountObject(oid)}.getOrElse{client.raw(oid)}};if(indexed.map{it.getString("entityKey")}.toSet()!=all.keys)SyncV2Codec.fail("hash_mismatch");for(row in indexed){val entity=row.getString("entityKey");all[entity]=all.getValue(entity).put("row",row)}}
            return all.keys.sorted().mapRows{all.getValue(it)}
        }
        return rows
    }
    private fun preparedRecoveries(action:JSONObject,objects:Map<String,ByteArray>):List<SyncV2RecoveryStorage.Bundle> {
        val count=action.optInt("recoveryCount",if(action.has("recovery-copy"))1 else 0)
        if(count !in 0..65)SyncV2Codec.fail("invalid_schema")
        fun join(kind:String):ByteArray {
            val descriptor=action.getJSONObject(kind);val total=descriptor.getInt("bytes");val out=java.io.ByteArrayOutputStream()
            for(index in 0 until (total+65535)/65536){val part=objects["$kind/$index"]?:SyncV2Codec.fail("closure_incomplete");if(part.size!=minOf(65536,total-out.size()))SyncV2Codec.fail("closure_incomplete");out.write(part)}
            return out.toByteArray().also{if(SyncV2Codec.hex(SyncV2Codec.sha(it))!=descriptor.getString("sha256"))SyncV2Codec.fail("hash_mismatch")}
        }
        return (0 until count).map{index->
            val suffix=if(index==0)""else"-$index"
            val copyBytes=join("recovery-copy$suffix");val copy=SyncV2Wire.parse(copyBytes,copyBytes.size,true);val pin=projection.accountOpen("prepared-recovery-pin",join("recovery-pin$suffix"))
            SyncV2RecoveryStorage.Bundle(copy,pin,objects.filterKeys{it.startsWith("local-recovery$suffix/")}.mapKeys{it.key.removePrefix("local-recovery$suffix/")})
        }
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
    private fun transferCheckpoint(actionId:String,oid:String):JSONObject?=db.controlQuery("SELECT body FROM syncv2_p5_objects WHERE scope=? AND action_id=? AND oid=?",arrayOf(scope,actionId,"upload/$oid")).use{if(it.moveToFirst())projection.accountOpen("transfer",it.getBlob(0))else null}
    private fun saveTransferCheckpoint(actionId:String,oid:String,total:Int,offset:Int) {
        db.beginTransaction()
        try {
            val prior=transferCheckpoint(actionId,oid)
            if(prior==null || offset>prior.getInt("durableOffset"))db.execSQL("INSERT OR REPLACE INTO syncv2_p5_objects VALUES(?,?,?,?)",arrayOf(scope,actionId,"upload/$oid",projection.accountSeal("transfer",JSONObject().put("oid",oid).put("totalBytes",total).put("durableOffset",offset))))
            db.setTransactionSuccessful()
        } finally {db.endTransaction()}
    }
    /** Only an explicit durable false proves that account publication was never attempted.
     * Old journals without the marker are conservatively retained as uncertain. */
    fun cancelUnsentAction(requestId:String?=null):JSONObject {
        gate();val st=projection.state();val action=st.body.optJSONObject("pendingAccountAction")
            ?:return JSONObject().put("cancelled",false).put("reason","no_action")
        if(action.getString("actorDeviceId")!=signer.deviceId)SyncV2Codec.fail("authentication_required")
        if(requestId!=null && requestId!=action.getString("id"))SyncV2Codec.fail("choice_stale")
        if(action.opt("publicationAttempted")!=false)return JSONObject().put("cancelled",false).put("reason","request_outcome_unknown")
        projection.accountWriter(st.version){current->
            val pending=current.body.optJSONObject("pendingAccountAction")?:SyncV2Codec.fail("choice_stale")
            if(!same(pending,action) || pending.opt("publicationAttempted")!=false)SyncV2Codec.fail("choice_stale")
            current.body.put("accountActionError","choice_stale").remove("pendingAccountAction");clearStaged(action.getString("id"))
        }
        return JSONObject().put("cancelled",true).put("reason","not_sent").put("requestId",action.getString("id"))
    }
    /** Explicit cancellation supersedes the UI intent. Uncertain signed bytes stay
     * detached: they may be queried, but are NEVER resent or applied automatically. */
    fun cancelAction(requestId:String?=null):JSONObject {
        gate();val st=projection.state();val action=st.body.optJSONObject("pendingAccountAction")?:return JSONObject().put("cancelled",false).put("reason","no_action")
        if(action.getString("actorDeviceId")!=signer.deviceId)SyncV2Codec.fail("authentication_required")
        if(requestId!=null && requestId!=action.getString("id"))SyncV2Codec.fail("choice_stale")
        if(action.opt("publicationAttempted")==false)return cancelUnsentAction(requestId)
        projection.accountWriter(st.version){current->
            if(!same(current.body.optJSONObject("pendingAccountAction"),action))SyncV2Codec.fail("choice_stale")
            db.execSQL("INSERT INTO syncv2_p5_detached VALUES(?,?,?)",arrayOf(scope,action.getString("id"),projection.accountSeal("detached-action",action)))
            current.body.put("accountActionError","choice_stale").remove("pendingAccountAction")
        }
        return JSONObject().put("cancelled",true).put("detached",true).put("reason","request_outcome_unknown").put("requestId",action.getString("id"))
    }
    fun reconcileDetachedAction(requestId:String):JSONObject {
        gate();SyncV2Codec.unhex(requestId,16)
        val action=db.controlQuery("SELECT body FROM syncv2_p5_detached WHERE scope=? AND action_id=?",arrayOf(scope,requestId)).use{if(!it.moveToFirst())SyncV2Codec.fail("choice_stale");projection.accountOpen("detached-action",it.getBlob(0))}
        if(action.getString("actorDeviceId")!=signer.deviceId)SyncV2Codec.fail("authentication_required")
        client.capabilities();val result=client.requestResult(requestId)
        if(result.getString("status")=="succeeded") {
            val response=result.getJSONObject("response");val capture=ChoiceCapture.restore(action.getJSONObject("capture"))
            if(action.getString("kind")=="cloud") {
                val confirmed=account(response.getJSONObject("confirmedAccount"));if(confirmed.getString("oid")!=capture.accountOid || confirmed.getString("revision")!=capture.accountRevision || confirmed.getString("generation")!=capture.generation)SyncV2Codec.fail("invalid_schema")
            }else {
                val b=action.getJSONObject("envelope").getJSONObject("body");val observed=account(response.getJSONObject("account"));val device=response.getJSONObject("device")
                if(observed.getString("oid")!=SyncV2Codec.oid("commit",b) || observed.getString("generation")!=b.getString("rollbackGeneration") || observed.getString("revision")!=SyncV2Codec.next(capture.accountRevision) || device.getString("commitOid")!=observed.getString("oid") || device.getString("ref")!="refs/devices/${signer.deviceId}/index")SyncV2Codec.fail("invalid_schema")
            }
            val st=projection.state();projection.accountWriter(st.version){
                db.execSQL("UPDATE syncv2_p5_detached SET body=? WHERE scope=? AND action_id=?",arrayOf(projection.accountSeal("detached-action",copy(action).put("observedResult",result)),scope,requestId))
            }
        }
        return result.put("detached",true).put("localCommitted",false)
    }
    private fun checkActionCurrent(action:JSONObject) {
        try {
            fileGuard(action.getJSONObject("fileCapture")){}
            val st=projection.state()
            if(st.version!=action.getString("expectedStateVersion") || st.revision!=action.getJSONObject("capture").getString("localChangeRevision"))SyncV2Codec.fail("choice_stale")
        }catch(e:Exception){if(e.message=="choice_stale" && action.opt("publicationAttempted")==false)cancelUnsentAction(action.getString("id"));throw e}
    }
    private fun markPublicationAttempted(action:JSONObject) {
        fault("before-publication-mark");syncFiles();checkActionCurrent(action)
        if(action.opt("publicationAttempted")==false) {
            projection.accountWriter(action.getString("expectedStateVersion")){current->
                if(!same(current.body.optJSONObject("pendingAccountAction"),action))SyncV2Codec.fail("choice_stale")
                action.put("publicationAttempted",true).put("expectedStateVersion",SyncV2Codec.next(current.version))
                current.body.put("pendingAccountAction",copy(action))
            }
        }
        fault("publication-attempted")
    }
    /** Restarts replay exact signed bytes/request IDs. A changed whole state never clears edits. */
    fun resume():JSONObject? {
        try { return resumeStaged() } finally {transientRows.forEach{it.close()};transientRows.clear()}
    }
    private fun resumeStaged():JSONObject? {
        gate();syncFiles();val st=projection.state();val action=st.body.optJSONObject("pendingAccountAction")?:return flushAppliedAck()
        if(action.getString("actorDeviceId")!=signer.deviceId || client.deviceId!=signer.deviceId)SyncV2Codec.fail("authentication_required")
        checkActionCurrent(action)
        SyncV2Codec.next(st.version)
        val caps=client.capabilities();if(caps.getJSONObject("flags").opt("historyRestore")!=true)throw SyncV2CloudError("feature_disabled",true,1000)
        val request=action.getJSONObject("request");val id=action.getString("id");val objects=stagedObjects(id);val kind=action.getString("kind")
        val response=try {
            if(kind=="cloud"){markPublicationAttempted(action);client.chooseCloud(request)}else {
                val known=client.requestResult(id)
                if(known.getString("status")=="succeeded")known.getJSONObject("response")else {upload(if(kind=="rollback")objects.selectObjects{it==request.getJSONObject("account").getJSONObject("body").getString("newCommitOid")}else objects,id);markPublicationAttempted(action);client.publish(request)}
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
        val sparseIds=action.optJSONArray("sparseIds")?.let{SyncV2Wire.strings(it,64,false).toSet()}
        val target=if(kind=="cloud") {
            if(!same(latest.body.optJSONObject("metadataAccount"),observed))SyncV2Codec.fail("choice_stale")
            cloudRows(sparseIds)
        }else stagedRows(id)
        business.validate(target);val before=projection.accountLocalRows(sparseIds);val bundles=preparedRecoveries(action,objects)
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
                for(bundle in bundles)recovery.commit(bundle)
                fault("recovery-pinned")
                val rss=business.apply(before,target);fault("business-applied")
                projection.accountApplied(current,observed,target,id,before,rss,sparseIds=sparseIds)
                projection.accountResolveLegacy(current,action.optJSONArray("legacySelected")?:JSONArray(),kind)
                current.body.put("pendingAppliedAck",JSONObject().put("requestId",this.id()).put("commitOid",observed.getString("oid")).put("deviceId",signer.deviceId).put("refRevision",observed.getString("revision")).put("rollbackGeneration",observed.getString("generation")))
            }
            // Current metadata replaces the previous cache. No old snapshot browser is created.
            if(kind!="cloud") {
                if(sparseIds==null)projection.accountReplaceMetadata(current,observed,target,objects.selectObjects{it.substringBefore(':') in setOf("meta","state","tree","commit","state-refs")},response.getString("serverRevision"))
                else SyncV2AccountSparseMetadata(projection).replace(current,observed,action.getJSONObject("envelope").getJSONObject("body").getString("treeRoot"),sparseIds,target,objects,SyncV2Wire.strings(action.getJSONArray("retiredTrees"),10000,false).toSet(),response.getString("serverRevision"))
                if(kind in setOf("local","merge"))projection.accountStoreOwnWireRows(target,if(sparseIds==null)projection.cloudObjects()else SyncV2AccountSparseMetadata(projection).objects(target,objects),objects.keys)
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
    /** A successful local/merge publication remains an immutable prefix. Only new tail
     * operations are rebased on it; unsupported tails keep both sides and the staged result. */
    fun reconcileStaleAction():JSONObject? {
        try{return reconcilePrefix()}finally{transientRows.forEach{it.close()};transientRows.clear()}
    }
    private fun reconcilePrefix():JSONObject? {
        gate();syncFiles();client.capabilities();var st=projection.state();val action=st.body.optJSONObject("pendingAccountAction")?:return null
        if(action.getString("actorDeviceId")!=signer.deviceId || client.deviceId!=signer.deviceId)SyncV2Codec.fail("authentication_required")
        val id=action.getString("id")
        if(action.opt("publicationAttempted")==false)return cancelUnsentAction(id).put("status","unknown")
        val result=client.requestResult(id);val status=result.getString("status");val kind=action.getString("kind")
        if(status=="pending")return result
        if(status!="succeeded" && kind in setOf("local","merge")) {
            val detached=cancelAction(id)
            return result.put("tailReconciliationPending",true).put("detached",detached.optBoolean("detached")).put("reason","request_outcome_unknown")
        }
        if(status!="succeeded" || kind !in setOf("local","merge")) {
            projection.accountWriter(st.version){current->current.body.put("accountActionReconciliation",result).remove("pendingAccountAction");clearStaged(id)}
            return result
        }
        val response=result.getJSONObject("response");val capture=ChoiceCapture.restore(action.getJSONObject("capture"));val published=account(response.getJSONObject("account"))
        val envelope=action.getJSONObject("envelope");val commit=envelope.getJSONObject("body")
        if(published.getString("oid")!=SyncV2Codec.oid("commit",commit) || published.getString("generation")!=commit.getString("rollbackGeneration") || published.getString("revision")!=SyncV2Codec.next(capture.accountRevision) || response.getJSONObject("device").getString("commitOid")!=published.getString("oid"))SyncV2Codec.fail("invalid_schema")
        val progress=projection.runWindow();if(progress.paused)return result.put("projectionPaused",true)
        st=projection.state();val fileToken=fileCapture()
        if(st.body.optBoolean("unknownEditBase") && !same(fileToken,action.getJSONObject("fileCapture")))return result.put("tailReconciliationPending",true).put("reason","missing_content")
        val target=stagedRows(id,expand=true);val captured=stagedRows(id,"capture",true);val actual=projection.accountLocalRows()
        val capturedBy=captured.indexRows();val actualBy=actual.indexRows();val targetBy=target.indexRows()
        val isolated=target.filterRows{it.has(SyncV2NoteWiring.ISOLATED_KEY)}
        val isolatedIds=isolated.map{it.getJSONObject("meta").getString("entityKey")}.toSet()
        val rebased=SyncV2RowSpool(db,{projection.accountSeal("prefix-row",it)},{projection.accountOpen("prefix-row",it)}).also{transientRows.add(it)}
        for(row in isolated)rebased.append(snapshotRow(row,"target"))
        val changed=mutableSetOf<String>();val generation=published.getString("generation")
        val prefixInputs=SyncV2RowSpool(db,{projection.accountSeal("prefix-input",it)},{projection.accountOpen("prefix-input",it)}).also{transientRows.add(it)}
        for(entity in ((capturedBy.keys+actualBy.keys+targetBy.keys)-isolatedIds).sorted()) {
            val capturedRow=capturedBy[entity];val actualRow=actualBy[entity];val targetRow=targetBy[entity]
            val bases=values(listOfNotNull(capturedRow),true);val local=values(listOfNotNull(actualRow),true);val cloudValues=values(listOfNotNull(targetRow),true)
            val base=bases.optJSONObject(entity);val value=local.optJSONObject(entity);val ops=JSONArray()
            if(!same(base,value)) {
                val fields=if(base==null||value==null)emptyList()else (base.keys().asSequence().toSet()+value.keys().asSequence().toSet()).filter{it!="identity"&&!same(base.opt(it),value.opt(it))}.sorted()
                val op=when{base==null->"create";value==null->"delete";value.optJSONObject("lifecycle")?.getLong("generation")?.let{it>(base.optJSONObject("lifecycle")?.getLong("generation")?:0)}==true->"explicit-restore";else->"update"}
                ops.put(JSONObject().put("id",entity).put("kind",op).put("fields",JSONArray(fields)).put("complete",value==null||actualRow?.let{recovery.contentReady(it)}==true))
            }
            prefixInputs.append(JSONObject().put("base",bases).put("local",local).put("cloud",cloudValues).put("ops",ops)
                .put("noteConflictGenerations",JSONObject().put("base",generation).put("local",generation).put("cloud",generation)))
        }
        channelGuard(prefixInputs)?.let{return result.put("tailReconciliationPending",true).put("reason",it)}
        for(input in prefixInputs) {
            val entity=(input.getJSONObject("base").keys().asSequence().toSet()+input.getJSONObject("local").keys().asSequence().toSet()+input.getJSONObject("cloud").keys().asSequence().toSet()).single()
            val targetRow=targetBy[entity];val actualRow=actualBy[entity];val capturedRow=capturedBy[entity]
            val plan=SyncV2Planner.canMerge(input)
            if(!plan.getBoolean("canMerge"))return result.put("tailReconciliationPending",true).put("reason",plan.getString("reason"))
            val tree=plan.getJSONObject("tree");val merged=tree.optJSONObject(entity)?.let{copy(it).apply{remove("identity")}}
            if(!same(targetRow?.optJSONObject("value"),merged))changed.add(entity)
            if(merged!=null) {
                val original=targetRow?:actualRow?:capturedRow?:SyncV2Codec.fail("identity_conflict");val m=original.getJSONObject("meta")
                rebased.append(record(m.getString("kind"),m.getString("logicalId"),entity,merged).also{it.getJSONObject("meta").put("addedAtSource",m.getString("addedAtSource"))})
            }
        }
        business.validate(rebased)
        val latest=projection.state();if(latest.version!=st.version || latest.revision!=st.revision)SyncV2Codec.fail("choice_stale")
        val authors=client.authors(identity,approved);val bindings=st.body.optJSONArray("signingBindings")?:JSONArray();for(i in 0 until bindings.length())authors.restorePinned(bindings.getJSONObject(i))
        authors.header(envelope)
        val observed=st.body.getJSONObject("account")
        val proof=if(SyncV2Codec.d64(observed.getString("revision"))<=SyncV2Codec.d64(published.getString("revision")))projection.accountProof(published,listOf(envelope),{authors.header(it)},{this.header(it)})else null
        val objects=stagedObjects(id)
        fileGuard(fileToken){projection.accountWriter(st.version){current->
            if(!same(current.body.optJSONObject("pendingAccountAction"),action) || current.revision!=st.revision)SyncV2Codec.fail("choice_stale")
            if(proof!=null)projection.accountAdopt(current,published,proof)
            val rss=business.apply(actual,rebased);fault("business-applied")
            projection.accountApplied(current,published,target,id,actual,rss,capture.localChangeRevision,virtualBaseline=true)
            if(proof!=null) {
                projection.accountReplaceMetadata(current,published,target,objects.selectObjects{it.substringBefore(':') in setOf("meta","state","tree","commit","state-refs")},response.getString("serverRevision"))
                projection.accountStoreOwnWireRows(target,projection.cloudObjects(),objects.keys)
            }
            projection.accountRetainTail(current,published,rebased,changed)
            projection.accountResolveLegacy(current,action.optJSONArray("legacySelected")?:JSONArray(),kind)
            current.body.put("pendingAppliedAck",JSONObject().put("requestId",this.id()).put("commitOid",published.getString("oid")).put("deviceId",signer.deviceId).put("refRevision",published.getString("revision")).put("rollbackGeneration",generation))
            current.body.put("accountActionReconciliation",result).put("accountActionReceipt",JSONObject().put("id",id).put("kind",kind).put("account",published).put("reconciled",true)).remove("pendingAccountAction")
            clearStaged(id);fault("before-account-commit")
        }}
        fault("after-account-commit");runCatching{flushAppliedAck()}
        return result.put("localCommitted",true).put("tailPreserved",true).put("appliedAckPending",!projection.state().body.isNull("pendingAppliedAck"))
    }
    fun flushAppliedAck():JSONObject? {
        gate();val st=projection.state();val pending=st.body.optJSONObject("pendingAppliedAck")?:return null
        SyncV2Codec.next(st.version)
        client.capabilities();val response=client.applied(pending)
        projection.accountWriter(st.version){current->if(!same(current.body.optJSONObject("pendingAppliedAck"),pending))SyncV2Codec.fail("choice_stale");current.body.remove("pendingAppliedAck")}
        return response
    }
}
