package com.lightningstudio.watchrss.phone.syncv2

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lightningstudio.watchrss.phone.BuildConfig
import org.json.JSONArray
import org.json.JSONObject

/** One SQLite writer; content reads and account authentication occur outside this class's transactions.
 * P1 creates candidates; opt-in P5 account actions commit business apply in this same writer.
 * Default product gates remain closed.
 */
internal class SyncV2Projection(
    private val db: SupportSQLiteDatabase, private val identity: SyncV2Identity,
    localKey: ByteArray, private val enabled: Boolean = BuildConfig.SYNC_OBJECTS_V2_LOCAL_PROJECTION,
    private val fault: (String) -> Unit = {},
    private val noteSegments: Boolean = BuildConfig.SYNC_OBJECTS_V2_NOTE_SEGMENTS
) {
    /** Server announcement captured by the cloud layer; the write gate is the AND of both (25.8). */
    internal var noteSegmentsServerAnnounced = false
    private val key=localKey.copyOf()
    val scope=identity.scopeJson
    init { require(key.size==32) }
    private fun gate() { if(!enabled) SyncV2Codec.fail("feature_disabled") }
    private fun ensureActive() {
        gate()
        val on=db.controlQuery("SELECT enabled FROM syncv2_control WHERE id=1 AND active_scope=?",arrayOf(scope)).use { it.moveToFirst() && it.getInt(0)==1 }
        if(!on) SyncV2Codec.fail("feature_disabled")
    }
    private fun requireWorkerContext(){if(db.inTransaction())SyncV2Codec.fail("projection_in_business_transaction")}
    fun install() {
        requireWorkerContext();gate(); SyncV2Schema.install(db)
        db.beginTransaction()
        try {
            val prior=db.controlQuery("SELECT descriptor_pin,account_id,identity FROM syncv2_state WHERE scope=?",arrayOf(scope)).use { c ->
                if(c.moveToFirst()) Triple(c.getString(0),c.getString(1),c.getBlob(2)) else null
            }
            if(prior!=null) {
                if(prior.first!=identity.descriptorOid || prior.second!=identity.accountId) SyncV2Codec.fail("identity_conflict")
                val record=JSONObject(SyncV2Codec.openLocal(key,scope,"identity",prior.third))
                if(record.getString("descriptorOid")!=identity.descriptorOid) SyncV2Codec.fail("identity_conflict")
                // Also compare the installed K_id, not just the server-provided descriptor name.
                if(SyncV2Codec.entity(SyncV2Codec.unhex(record.getString("idKeyHex"),32),identity.repoId,"article","identity-check")!=identity.entity("article","identity-check")) SyncV2Codec.fail("identity_conflict")
            } else {
                val body=JSONObject().put("rollbackGeneration","0").put("account",JSONObject.NULL)
                    .put("pendingChoice",JSONObject.NULL).put("editBase",JSONObject.NULL).put("received",JSONObject())
                    .put("applied",JSONObject()).put("activeGeneration",JSONObject.NULL).put("appliedAccount",JSONObject.NULL).put("keepDiscardedLocalChanges7Days",true).put("p5CatalogSchema",6)
                val edits=SyncV2BusinessWitness.initialEdits(db)
                val unknown=edits.getBoolean("unknownV2Base")
                body.put("bootstrapEdits",edits).put("unknownEditBase",unknown).put("businessStamp",SyncV2BusinessWitness.read(db).encoded())
                db.execSQL("INSERT INTO syncv2_state VALUES(?,?,?,?,?,?,?)",arrayOf(scope,"0",if(unknown)"1"else"0",seal("state",body),identity.encryptedRecord(key),identity.descriptorOid,identity.accountId))
            }
            synchronizeBusinessInTransaction(readState(),SyncV2BusinessWitness.read(db))
            db.execSQL("UPDATE syncv2_control SET enabled=1,active_scope=?,failure_code=NULL WHERE id=1",arrayOf(scope))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun pause() { ensureActive(); db.execSQL("UPDATE syncv2_control SET enabled=0 WHERE id=1") }
    private fun seal(purpose: String, value: Any?) = SyncV2Codec.sealLocal(key,scope,purpose,value)
    private fun open(purpose: String, bytes: ByteArray)=JSONObject(SyncV2Codec.openLocal(key,scope,purpose,bytes))
    data class State(val version: String,val revision: String,val body: JSONObject)
    private fun readState():State = db.controlQuery("SELECT state_version,local_revision,body FROM syncv2_state WHERE scope=?",arrayOf(scope)).use { c ->
        check(c.moveToFirst());State(c.getString(0),c.getString(1),open("state",c.getBlob(2)))
    }
    fun state(): State {
        requireWorkerContext();ensureActive()
        val stamp=SyncV2BusinessWitness.read(db)
        val old=readState()
        if(old.body.optString("businessStamp")==stamp.encoded())return old
        db.beginTransaction()
        try {
            synchronizeBusinessInTransaction(readState(),SyncV2BusinessWitness.read(db))
            db.setTransactionSuccessful()
        } finally {db.endTransaction()}
        return readState()
    }
    /** A projection observation of a PREVIOUSLY committed business revision; never a business hook. */
    private fun synchronizeBusinessInTransaction(st:State,stamp:SyncV2BusinessWitness.Stamp) {
        if(st.body.optString("businessStamp")==stamp.encoded())return
        val following=SyncV2Codec.next(st.version)
        val previous=SyncV2BusinessWitness.decode(st.body.optString("businessStamp"))
        val continuous=previous!=null && previous.sameSource(stamp) && SyncV2Codec.d64(stamp.revision)>SyncV2Codec.d64(previous.revision)
        val delta=if(continuous)SyncV2Codec.d64(stamp.revision)-SyncV2Codec.d64(previous!!.revision)else java.math.BigInteger.ONE
        val revision=(SyncV2Codec.d64(st.revision)+delta).also{if(it>SyncV2Codec.maxD64)SyncV2Codec.fail("counter_exhausted")}.toString()
        if(!continuous)st.body.put("businessEpoch",SyncV2Codec.next(st.body.optString("businessEpoch","0")))
        val base=st.body.optJSONObject("appliedAccount")?:st.body.optJSONObject("account")
        if(st.body.isNull("editBase")) {
            if(continuous && base!=null && !st.body.optBoolean("unknownEditBase"))st.body.put("editBase",JSONObject().put("accountOid",base.getString("oid")).put("generation",base.getString("generation")))
            else st.body.put("unknownEditBase",true)
        }
        // Without an authenticated per-operation base, a post-rollback legacy/local write is unknown.
        if(!continuous || st.body.getString("rollbackGeneration")!="0" && st.body.optJSONObject("appliedAccount")==null)st.body.put("unknownEditBase",true)
        st.body.put("businessStamp",stamp.encoded())
        refreshPending(st.body)
        db.execSQL("UPDATE syncv2_state SET state_version=?,local_revision=?,body=? WHERE scope=? AND state_version=?",arrayOf(following,revision,seal("state",st.body),scope,st.version))
        if(db.controlQuery("SELECT changes()").use{it.moveToFirst();it.getInt(0)}!=1)SyncV2Codec.fail("choice_stale")
    }
    private fun <T> writer(expected: String?=null, body: (State)->T): T {
        state() // Consume only the small business witness, outside the projection transaction.
        db.beginTransaction() // Android uses an immediate/exclusive SQLite writer, never a read-deferred txn.
        var started=false;var failure:Throwable?=null
        try {
            val old=readState(); val following=SyncV2Codec.next(old.version) // before callbacks and fault hooks
            if(expected!=null && old.version!=expected) SyncV2Codec.fail("choice_stale")
            if(old.body.optString("businessStamp")!=SyncV2BusinessWitness.read(db).encoded())SyncV2Codec.fail("choice_stale")
            accountCatalog.begin(old.body)
            started=true
            fault("begin")
            val result=body(old)
            accountCatalog.finish(old.body)
            fault("before-state")
            db.execSQL("UPDATE syncv2_state SET state_version=?,body=? WHERE scope=? AND state_version=?",arrayOf(following,seal("state",old.body),scope,old.version))
            if(db.controlQuery("SELECT changes()").use { it.moveToFirst(); it.getInt(0) }!=1) SyncV2Codec.fail("choice_stale")
            fault("before-commit"); db.setTransactionSuccessful(); return result
        } catch(e:Throwable) {
            val primary=if(e is android.database.sqlite.SQLiteFullException)IllegalStateException("storage_pressure",e)else e
            failure=primary;throw primary
        } finally {
            // SQLITE_FULL may already have rolled back the native transaction. Android's
            // second ROLLBACK can fail with "no transaction"; preserve the causal failure.
            try{db.endTransaction()}catch(e:Throwable){
                val primary=failure
                if(primary!=null){if(primary!==e)primary.addSuppressed(e)}
                else throw if(e is android.database.sqlite.SQLiteFullException)IllegalStateException("storage_pressure",e)else e
            }
            if(started)try{fault("after-transaction")}catch(e:Throwable){
                val primary=failure;if(primary==null)throw e else if(primary!==e)primary.addSuppressed(e)
            }
        }
    }
    /** Authenticated-window boundary. Caller must finish HeadPins verification before this call. */
    private fun authenticateHeads(observed:JSONObject,headers:List<JSONObject>,before:State,verifyHeader:(JSONObject)->JSONObject,
        readHeader:((String)->JSONObject)?):SyncV2HeadPins.Proof {
        val upgraded=before.body.optString("headerAuthentication")==SyncV2HeadPins.schema
        val saved=before.body.optJSONArray("knownControlHeaders")?:JSONArray()
        val accountHeaders=before.body.optJSONArray("knownAccountParents")?:JSONArray()
        val known=(0 until saved.length()).map{saved.getJSONObject(it)}+(0 until accountHeaders.length()).map{accountHeaders.getJSONObject(it).getJSONObject("header")}
        val proof=SyncV2HeadPins.authenticate(identity,observed,headers,if(upgraded)before.body.optJSONObject("account")else null,
            verifyHeader,{oid->accountCatalog.header(oid)?.getJSONObject("header")?:readHeader?.invoke(oid)?:SyncV2Codec.fail("object_missing")},if(upgraded)before.body.optJSONObject("knownAuthors")?:JSONObject()else JSONObject(),known)
        val previous=before.body.optJSONObject("account")
        if(!upgraded && previous!=null && (0 until proof.path.length()).none{proof.path.getJSONObject(it).getString("oid")==previous.getString("oid")})SyncV2Codec.fail("replay_detected")
        return proof
    }
    private fun retainControlHeaders(body:JSONObject,proof:SyncV2HeadPins.Proof) {
        val previous=body.optJSONArray("knownControlHeaders")?:JSONArray();val saved=linkedMapOf<String,JSONObject>()
        for(i in 0 until previous.length())previous.getJSONObject(i).let{saved[SyncV2Codec.oid("commit",it.getJSONObject("body"))]=it}
        for(header in proof.headers)saved[SyncV2Codec.oid("commit",header.getJSONObject("body"))]=header
        body.put("knownControlHeaders",JSONArray(saved.values.toList())).put("headerAuthentication",SyncV2HeadPins.schema)
    }
    fun observeVerified(window: JSONObject, headers: List<JSONObject>, verifyHeader:(JSONObject)->JSONObject = identity::verifyHeader,readHeader:((String)->JSONObject)?=null) {
        val observed=validAccount(window)
        val before=state()
        val proof=authenticateHeads(observed,headers,before,verifyHeader,readHeader)
        writer(before.version) { st ->
            observeInto(st,observed,proof.path);retainControlHeaders(st.body,proof)
        }
    }
    private fun observeInto(st:State,observed:JSONObject,proof:JSONArray) {
            val old=st.body.optJSONObject("account")
            if(old!=null && (SyncV2Codec.d64(observed.getString("generation"))<SyncV2Codec.d64(old.getString("generation")) ||
                    SyncV2Codec.d64(observed.getString("revision"))<SyncV2Codec.d64(old.getString("revision")))) SyncV2Codec.fail("replay_detected")
            val authors=st.body.optJSONObject("knownAuthors")?:JSONObject()
            val actualPathAuthors=mutableSetOf<String>()
            for(i in 0 until proof.length()) {
                val item=proof.getJSONObject(i);val head=item.getJSONObject("header").getJSONObject("body");val author=head.getString("authorDeviceId")
                if(!actualPathAuthors.add(author))continue
                val prior=authors.optJSONObject(author)
                val seq=head.getString("deviceSequence")
                if(prior!=null && (SyncV2Codec.d64(seq)<SyncV2Codec.d64(prior.getString("sequence")) || (seq==prior.getString("sequence") && item.getString("oid")!=prior.getString("oid"))))SyncV2Codec.fail("replay_detected")
                authors.put(author,JSONObject().put("sequence",seq).put("oid",item.getString("oid")))
            }
            st.body.put("knownAuthors",authors)
            val archive=st.body.optJSONArray("knownAccountParents")?:JSONArray()
            val known=(0 until archive.length()).map{archive.getJSONObject(it).getString("oid")}.toSet()
            for(i in 0 until proof.length())if(proof.getJSONObject(i).getString("oid") !in known)archive.put(proof.getJSONObject(i))
            st.body.put("knownAccountParents",archive)
            st.body.put("account",observed).put("rollbackGeneration",observed.getString("generation"))
            st.body.put("lastSeenAccountOid",observed.getString("oid")).put("lastSeenAccountRevision",observed.getString("revision")).put("lastSeen",observed.getString("generation"))
            if(st.body.isNull("editBase") && st.revision!=st.body.optString("acknowledgedLocalRevision","0")) {
                val oldest=db.controlQuery("SELECT base_state FROM syncv2_outbox WHERE scope=? ORDER BY seq LIMIT 1",arrayOf(scope)).use{if(it.moveToFirst())open("state",it.getBlob(0)).optJSONObject("account") else null}
                if(oldest!=null) st.body.put("editBase",JSONObject().put("accountOid",oldest.getString("oid")).put("generation",oldest.getString("generation")))
                else st.body.put("unknownEditBase",true)
            }
            refreshPending(st.body)
            val base=st.body.optJSONObject("editBase")
            if(base!=null && SyncV2Codec.d64(base.getString("generation"))<SyncV2Codec.d64(observed.getString("generation"))) pending(st.body,observed.getString("generation"))
    }
    /** Authenticate and stage one note at a time. The round retains descriptors only. */
    internal fun verifyCloud(closure:SyncV2ControlClosure,envelope:JSONObject,get:(String)->ByteArray,
        reference:(String)->JSONObject):SyncV2ControlClosure.Verified {
        val objects=SyncV2Spool(db);val bodies=SyncV2Spool(db)
        try {
            val verified=closure.verifyStaged(envelope,get,reference,stage={staged->
                for((oid,bytes)in staged.objects)objects[oid]=bytes
                bodies[staged.entityKey]=seal("cloud-row",staged.body.apply{remove(SyncV2NoteWiring.PULL_STAGED_KEY)})
                fault("note-pull-staged")
            },objectsStore={SyncV2Spool(db)})
            objects.putAll(verified.objects)
            val rows=verified.rows.mapRows{r->if(r.optBoolean(SyncV2NoteWiring.PULL_STAGED_KEY))
                open("cloud-row",bodies.getValue(r.getJSONObject("meta").getString("entityKey"))) else r}
            return SyncV2ControlClosure.Verified.create(verified.commitOid,rows,objects){bodies.close();objects.close()}
        } catch(e:Throwable) {bodies.close();objects.close();throw e}
    }
    /** P2 owns only current authenticated control content; never sends business applied ACK. */
    fun installCloudIndex(window:JSONObject,headers:List<JSONObject>,verified:SyncV2ControlClosure.Verified,
        verifyHeader:(JSONObject)->JSONObject,serverRevision:String?=null,readHeader:((String)->JSONObject)?=null) {
        requireWorkerContext();val observed=validAccount(window);val before=state()
        if(verified.commitOid!=observed.getString("oid"))SyncV2Codec.fail("hash_mismatch")
        val proof=authenticateHeads(observed,headers,before,verifyHeader,readHeader)
        writer(before.version) { st ->
            observeInto(st,observed,proof.path);retainControlHeaders(st.body,proof)
            accountReplaceMetadata(st,observed,verified.rows,verified.objects,serverRevision?:st.body.optString("metadataServerRevision","0"))
            // pendingChoice and all local edits remain; P1 product apply gate is not relaxed here.
            fault("cloud-index-ready")
        }
    }
    fun reuseCloudIndex(window:JSONObject,serverRevision:String):Boolean {
        val observed=validAccount(window);val before=state();val accepted=before.body.optJSONObject("account")?:return false
        val metadata=before.body.optJSONObject("metadataAccount")?:return false
        if(before.body.optString("metadataSchema")!=SyncV2HeadPins.schema || metadata.getString("oid")!=observed.getString("oid") || accepted.getString("oid")!=observed.getString("oid"))return false
        if(observed.getString("generation")!=accepted.getString("generation"))SyncV2Codec.fail("replay_detected")
        val count=db.controlQuery("SELECT COUNT(*) FROM syncv2_cloud_rows WHERE scope=?",arrayOf(scope)).use{it.moveToFirst();it.getInt(0)}
        if(count!=before.body.getInt("metadataRows"))return false
        writer(before.version) { st ->
            observeInto(st,observed,JSONArray());SyncV2Codec.d64(serverRevision)
            st.body.put("metadataAccount",observed).put("metadataServerRevision",serverRevision)
        }
        return true
    }
    /** Paging descriptors never SELECTs the original body. */
    fun cloudRowsPage(after:String="",limit:Int=64):List<JSONObject> {
        require(limit in 1..64);ensureActive()
        return db.controlQuery("SELECT body FROM syncv2_cloud_rows WHERE scope=? AND entity_key>? ORDER BY entity_key LIMIT ?",arrayOf<Any>(scope,after,limit)).use { c -> buildList {while(c.moveToNext())add(open("cloud-row",c.getBlob(0)))} }
    }
    fun wireContentRow(entity:String):JSONObject? {
        SyncV2Codec.unhex(entity,32);ensureActive()
        return db.controlQuery("SELECT body FROM syncv2_wire_candidates WHERE scope=? AND entity_key=?",arrayOf(scope,entity)).use{if(it.moveToFirst())open("wire-candidate",it.getBlob(0)).getJSONObject("row")else null}
    }
    /** CPU, HTTP and file work finishes before this short writer. No business body is replaced. */
    fun attachContentRecipe(captured:JSONObject,recipeOid:String,recipe:JSONObject) {
        SyncV2Wire.oid(recipeOid,"recipe");SyncV2ContentCodec.recipe(recipe)
        val entity=captured.getString("entityKey")
        writer { _ ->
            val value=db.controlQuery("SELECT body FROM syncv2_wire_candidates WHERE scope=? AND entity_key=?",arrayOf(scope,entity)).use{if(it.moveToFirst())open("wire-candidate",it.getBlob(0))else SyncV2Codec.fail("body_revision_changed")}
            val row=value.getJSONObject("row")
            if(row.getBoolean("deleted") || listOf("entityKey","bodyRevision","rowSequence").any{row.get(it)!=captured.get(it)} || recipe.get("entityKey")!=entity || recipe.get("bodyRevision")!=row.get("bodyRevision"))SyncV2Codec.fail("body_revision_changed")
            row.put("recipeOid",recipeOid).put("promiseId",JSONObject.NULL).put("opaqueContentId",recipe.get("opaqueContentId")).put("contentBytes",recipe.get("totalPlainBytes"))
                .put("rowSequence",SyncV2Codec.next(row.getString("rowSequence")))
            db.execSQL("UPDATE syncv2_wire_candidates SET body=? WHERE scope=? AND entity_key=?",arrayOf(seal("wire-candidate",value),scope,entity))
        }
    }
    fun cloudRows(ids:Set<String>?=null):List<JSONObject> {
        ensureActive();val selected=selection(ids)
        val entities=db.controlQuery("SELECT entity_key FROM syncv2_cloud_rows WHERE scope=?${selected.first} ORDER BY entity_key",selected.second).use{c->buildList{while(c.moveToNext())add(c.getString(0))}}
        return entities.mapRows{entity->db.controlQuery("SELECT body FROM syncv2_cloud_rows WHERE scope=? AND entity_key=?",arrayOf(scope,entity)).use{c->if(!c.moveToFirst())SyncV2Codec.fail("closure_incomplete");open("cloud-row",c.getBlob(0))}}
    }
    fun saveSigningBindings(bindings:JSONArray,revocationRevision:String) = writer { st ->
        SyncV2Codec.d64(revocationRevision)
        val old=st.body.optString("revocationRevision","0")
        if(SyncV2Codec.d64(revocationRevision)<SyncV2Codec.d64(old))SyncV2Codec.fail("replay_detected")
        val oldBindings=st.body.optJSONArray("signingBindings")?:JSONArray()
        val known=linkedMapOf<String,JSONObject>()
        for(list in listOf(oldBindings,bindings))for(i in 0 until list.length()) {
            val value=list.getJSONObject(i);val b=value.getJSONObject("body");val device=b.getString("deviceId")
            val previous=known[device]?.getJSONObject("body")
            if(previous!=null && (previous.get("keyId")!=b.get("keyId") || previous.get("publicKey")!=b.get("publicKey")))SyncV2Codec.fail("identity_conflict")
            known[device]=value
        }
        st.body.put("signingBindings",JSONArray(known.toSortedMap().values.toList())).put("revocationRevision",revocationRevision)
    }
    fun cloudObjects():Map<String,ByteArray> {
        ensureActive();val ids=db.controlQuery("SELECT oid FROM syncv2_cloud_objects WHERE scope=?",arrayOf(scope)).use{c->buildSet{while(c.moveToNext())add(c.getString(0))}}
        return object:AbstractMap<String,ByteArray>() {
            override val keys get()=ids
            override fun get(key:String):ByteArray?=if(key !in ids)null else accountObject(key)
            override val entries get()=ids.map{oid->object:Map.Entry<String,ByteArray>{override val key=oid;override val value get()=accountObject(oid)}}.toSet()
        }
    }
    fun prepareOwnSigningBinding(signer:SyncV2Signing,now:Long,revision:String):JSONObject {
        state().body.optJSONObject("ownSigningBinding")?.let { old ->
            val b=old.getJSONObject("body")
            if(b.getString("deviceId")!=signer.deviceId || b.getString("keyId")!=signer.keyId || b.getString("publicKey")!=SyncV2Codec.b64(SyncV2Codec.unhex(signer.publicHex,65)))SyncV2Codec.fail("identity_conflict")
            return JSONObject(old.toString())
        }
        val binding=identity.signingBinding(signer,now,revision)
        writer { st ->
            val old=st.body.optJSONObject("ownSigningBinding")
            if(old!=null && SyncV2Codec.canonical(old)!=SyncV2Codec.canonical(binding))SyncV2Codec.fail("choice_stale")
            st.body.put("ownSigningBinding",binding)
        }
        return JSONObject(binding.toString())
    }
    fun reserveDeviceSequence(floor:String="0"):String = writer { st ->
        val old=st.body.optString("reservedDeviceSequence","0")
        val maximum=maxOf(SyncV2Codec.d64(old),SyncV2Codec.d64(floor)).toString()
        SyncV2Codec.next(maximum).also {st.body.put("reservedDeviceSequence",it)}
    }
    fun persistPublish(request:JSONObject,expectedVersion:String) = writer(expectedVersion) { st ->
        if(!st.body.isNull("pendingChoice"))SyncV2Codec.fail("choice_pending")
        val bytes=SyncV2Codec.canonical(request)
        val id=request.getJSONObject("account").getJSONObject("body").getString("requestId")
        val pending=st.body.optJSONObject("pendingPublish")
        if(pending!=null && SyncV2Codec.canonical(pending)!=bytes)SyncV2Codec.fail("idempotency_conflict")
        SyncV2Codec.unhex(id,16);st.body.put("pendingPublish",JSONObject(bytes))
    }
    fun pendingPublish():JSONObject? = state().body.optJSONObject("pendingPublish")?.let {JSONObject(it.toString())}
    /** Definitive CAS rejection invalidates only this signed attempt, never its edits or sealed objects. */
    fun rejectPublish(request:JSONObject,code:String) = writer { st ->
        if(code !in setOf("ref_conflict","rollback_conflict"))SyncV2Codec.fail("invalid_schema")
        val pending=st.body.optJSONObject("pendingPublish")?:return@writer
        if(SyncV2Codec.canonical(pending)!=SyncV2Codec.canonical(request))SyncV2Codec.fail("choice_stale")
        st.body.remove("pendingPublish")
    }
    fun confirmPublish(request:JSONObject,response:JSONObject) = writer { st ->
        val pending=st.body.optJSONObject("pendingPublish")?:SyncV2Codec.fail("choice_stale")
        if(SyncV2Codec.canonical(pending)!=SyncV2Codec.canonical(request))SyncV2Codec.fail("choice_stale")
        val oid=request.getJSONObject("account").getJSONObject("body").getString("newCommitOid")
        if(response.getJSONObject("account").getString("commitOid")!=oid || response.getJSONObject("device").getString("commitOid")!=oid)SyncV2Codec.fail("invalid_schema")
        st.body.put("publishedReceipt",response).remove("pendingPublish")
        // Signature+ancestry must still authenticate account observation. Never clear edits here.
    }
    fun saveEventCursor(cursor:String) = writer { st ->
        SyncV2Codec.d64(cursor)
        if(SyncV2Codec.d64(cursor)<SyncV2Codec.d64(st.body.optString("eventCursor","0")))SyncV2Codec.fail("replay_detected")
        st.body.put("eventCursor",cursor)
    }
    internal fun objectSpool()=SyncV2Spool(db)
    internal var lastWireIndex:WireIndex?=null
    data class WireIndex(val tree:SyncV2Index.Tree,val objects:Map<String,ByteArray>,val refs:List<JSONObject>,val reused:Set<String> = emptySet(),val retiredTrees:Set<String> = emptySet(),val noteMetadata:Map<String,JSONObject> = emptyMap(),val reservations:List<AutoCloseable> = emptyList()):AutoCloseable {
        override fun close(){reservations.forEach{it.close()};(objects as? AutoCloseable)?.close()}
    }
    fun prepareWireIndex(signer:SyncV2Signing,contentKey:ByteArray,keyVersion:Long):WireIndex {
        val before=state();val rows=mutableListOf<JSONObject>();val objects=SyncV2Spool(db);val refs=mutableListOf<JSONObject>();val reservations=mutableListOf<AutoCloseable>();val prepared=SyncV2Spool(db)
        try {
        // At most one candidate is decrypted at a time. No article/book body SELECT occurs here.
        val candidates=db.controlQuery("SELECT entity_key FROM syncv2_candidates WHERE scope=? ORDER BY entity_key",arrayOf(scope)).use{c->buildList{while(c.moveToNext())add(c.getString(0))}}
        val current=cloudRows().indexRows()
        val currentObjects=cloudObjects()
        val currentRefs=mutableMapOf<String,JSONObject>().also{refsByState->for(oid in currentObjects.keys)if(oid.startsWith("state-refs:")){val ref=SyncV2Wire.parse(currentObjects.getValue(oid),262144,true);refsByState[ref.getJSONObject("body").getString("stateOid")]=ref}}
        for(candidateId in candidates) {
            val c=db.controlQuery("SELECT entity_key,kind,logical_id,meta,state,source_hash FROM syncv2_candidates WHERE scope=? AND entity_key=?",arrayOf(scope,candidateId)).use{c->c.moveToFirst();arrayOf<Any>(c.getString(0),c.getString(1),c.getString(2),c.getBlob(3),c.getBlob(4),c.getString(5))}
            val entity=c[0] as String;val hash=c[5] as String
            val remote=current[entity]
            if(c[1]=="note"&&remote?.has(SyncV2NoteWiring.ISOLATED_KEY)==true) {
                val row=remote.getJSONObject("row");rows.add(row)
                for(field in listOf("metaOid","stateOid")){val oid=row.getString(field);currentObjects[oid]?.let{objects[oid]=it}}
                currentRefs[row.getString("stateOid")]?.let{ref->refs.add(ref);objects[SyncV2Codec.oid("state-refs",ref)]=SyncV2Codec.utf8(SyncV2Codec.canonical(ref))}
                // A matching pre-isolation local wire cache can still supply authenticated raw
                // children. Never open a business value or replace the opaque received row.
                val cache=db.controlQuery("SELECT body FROM syncv2_wire_candidates WHERE scope=? AND entity_key=?",arrayOf(scope,entity)).use{if(it.moveToFirst())open("wire-candidate",it.getBlob(0))else null}
                if(cache!=null&&listOf("metaOid","stateOid").all{cache.getJSONObject("row").get(it)==row.get(it)}) {
                    cache.optJSONObject("extraObjects")?.let{extra->for(oid in extra.keys())objects[oid]=SyncV2Codec.decode64(extra.getString(oid))}
                    cache.optJSONArray("childRefs")?.let{list->for(i in 0 until list.length())refs.add(list.getJSONObject(i))}
                }
                continue
            }
            val meta=open("meta",c[3] as ByteArray);val state=open("candidate-state",c[4] as ByteArray)
            if(state.has("projectionRemoved"))SyncV2Codec.fail("unsupported_feature")
            // Revalidate persisted candidates before reusing a pre-upgrade sealed cache.
            SyncV2Business.join(c[1] as String,meta,state)
            val prior=db.controlQuery("SELECT body FROM syncv2_wire_candidates WHERE scope=? AND entity_key=?",arrayOf(scope,entity)).use{if(it.moveToFirst())open("wire-candidate",it.getBlob(0))else null}
            val bodyBinding=SyncV2Codec.hash(JSONObject().put("kind",c[1]).put("logicalId",c[2])
                .put("bodyHash",meta.getJSONObject("payload").opt("contentHash")?:meta.getJSONObject("payload").opt("fingerprint")?:JSONObject.NULL)
                .put("assetVersion",if(c[1]=="asset")state.getJSONObject("payload").opt("updatedAt")?:JSONObject.NULL else JSONObject.NULL)
                .put("deleted",state.getJSONObject("payload").optBoolean("deleted",false)))
            val value=if(prior!=null && prior.getString("sourceHash")==hash && prior.getLong("keyVersion")==keyVersion)prior else if(remote!=null && SyncV2Codec.canonical(remote.getJSONObject("meta"))==SyncV2Codec.canonical(meta) && SyncV2Codec.canonical(remote.getJSONObject("state"))==SyncV2Codec.canonical(state)) {
                val row=remote.getJSONObject("row")
                JSONObject().put("sourceHash",hash).put("bodyBinding",bodyBinding).put("keyVersion",keyVersion).put("row",row)
                    .put("meta",SyncV2Codec.b64(currentObjects[row.getString("metaOid")]?:SyncV2Codec.fail("closure_incomplete")))
                    .put("state",SyncV2Codec.b64(currentObjects[row.getString("stateOid")]?:SyncV2Codec.fail("closure_incomplete")))
                    .put("refs",currentRefs[row.getString("stateOid")]?:SyncV2Codec.fail("closure_incomplete")).put("postRefs",false)
            }else if(c[1]=="note" && remote?.has(SyncV2NoteWiring.ISOLATED_KEY)==true) {
                // R3: an isolated remote note is carried back verbatim under every choice. The
                // authenticated row bytes are reused byte-identically; no re-seal and no tombstone.
                val row=remote.getJSONObject("row")
                JSONObject().put("sourceHash",hash).put("bodyBinding",bodyBinding).put("keyVersion",keyVersion).put("row",row)
                    .put("meta",SyncV2Codec.b64(currentObjects[row.getString("metaOid")]?:SyncV2Codec.fail("closure_incomplete")))
                    .put("state",SyncV2Codec.b64(currentObjects[row.getString("stateOid")]?:SyncV2Codec.fail("closure_incomplete")))
                    .put("refs",currentRefs[row.getString("stateOid")]?:SyncV2Codec.fail("closure_incomplete")).put("postRefs",false)
            }else if(c[1]=="note" && remote?.optJSONObject("payload")==null && SyncV2NoteSegments.measure(entity,state.getJSONObject("payload"),state.getJSONArray("requiredFeatures")).shortPlainBytes>SyncV2NoteSegments.LIMIT) {
                // 25.8 write gate: an oversized note is segmented only when the product switch is on;
                // a closed gate ends this publish attempt with feature_disabled and keeps the body.
                if(!noteSegments||!noteSegmentsServerAnnounced)SyncV2Codec.fail("feature_disabled")
                val sealedMeta=SyncV2Envelope.seal(identity,contentKey,keyVersion,"meta",SyncV2Codec.utf8(SyncV2Codec.canonical(meta)))
                val wired=SyncV2NoteWiring.seal(identity,signer,contentKey,keyVersion,db,scope,entity,state,noteSegments,null,
                    remote?.optString(SyncV2NoteWiring.REVISION_KEY)?.takeIf{it.matches(Regex("[0-9a-f]{32}"))},
                    remote?.optJSONArray(SyncV2NoteWiring.SEGMENTS_KEY)?.let{a->(0 until a.length()).map{a.getString(it)}}?:emptyList(),
                    remote?.optJSONObject(SyncV2NoteWiring.REFS_MAPS_KEY),outboxOwned(entity))?:SyncV2Codec.fail("invalid_schema")
                reservations.add(wired.reservation)
                val installed=db.controlQuery("SELECT body FROM syncv2_p5_bindings WHERE scope=? AND entity_key=?",arrayOf(scope,entity)).use{if(it.moveToFirst())accountOpen("binding",it.getBlob(0)).optJSONObject("row")else null}
                val priorRow=listOfNotNull(prior?.optJSONObject("row"),installed,remote?.optJSONObject("row")).maxByOrNull{SyncV2Codec.d64(it.getString("rowSequence"))}
                val row=JSONObject().put("entityKey",entity).put("metaOid",sealedMeta.oid).put("stateOid",wired.parentOid).put("addedAtMillis",priorRow?.optLong("addedAtMillis")?:0)
                    .put("rowSequence",SyncV2Codec.next(priorRow?.optString("rowSequence")?:"0"))
                    .put("deleted",state.getJSONObject("payload").optBoolean("deleted",false))
                    .put("bodyRevision",JSONObject.NULL).put("promiseId",JSONObject.NULL).put("recipeOid",JSONObject.NULL)
                    .put("opaqueContentId",JSONObject.NULL).put("contentBytes",JSONObject.NULL).put("requiredFeatures",JSONArray())
                JSONObject().put("sourceHash",hash).put("bodyBinding",bodyBinding).put("keyVersion",keyVersion).put("row",row)
                    .put("meta",SyncV2Codec.b64(sealedMeta.bytes)).put("state",SyncV2Codec.b64(wired.parentBytes))
                    .put("refs",wired.parentRefs).put("childRefs",JSONArray(wired.childRefs)).put("extraObjects",JSONObject().also{extra->for((oid,bytes)in wired.objects)extra.put(oid,SyncV2Codec.b64(bytes))})
            }else {
                val sealedMeta=SyncV2Envelope.seal(identity,contentKey,keyVersion,"meta",SyncV2Codec.utf8(SyncV2Codec.canonical(meta)))
                val sealedState=SyncV2Envelope.seal(identity,contentKey,keyVersion,"state",SyncV2Codec.utf8(SyncV2Codec.canonical(state)))
                val body=(c[1] as String) in setOf("article","book","asset")
                val installed=db.controlQuery("SELECT body FROM syncv2_p5_bindings WHERE scope=? AND entity_key=?",arrayOf(scope,entity)).use{if(it.moveToFirst())accountOpen("binding",it.getBlob(0)).optJSONObject("row")else null}
                val priorRow=listOfNotNull(prior?.optJSONObject("row"),installed,remote?.optJSONObject("row")).maxByOrNull{SyncV2Codec.d64(it.getString("rowSequence"))}
                val row=JSONObject().put("entityKey",entity).put("metaOid",sealedMeta.oid).put("stateOid",sealedState.oid).put("addedAtMillis",priorRow?.optLong("addedAtMillis")?:0)
                    .put("rowSequence",SyncV2Codec.next(priorRow?.optString("rowSequence")?:"0"))
                    .put("deleted",state.getJSONObject("payload").optBoolean("deleted",false))
                    .put("bodyRevision",if(body)SyncV2Codec.hex(SyncV2Codec.random(16))else JSONObject.NULL)
                    .put("promiseId",if(body)SyncV2Codec.hex(SyncV2Codec.random(16))else JSONObject.NULL).put("recipeOid",JSONObject.NULL)
                    .put("opaqueContentId",JSONObject.NULL).put("contentBytes",JSONObject.NULL).put("requiredFeatures",JSONArray())
                if(prior?.optString("bodyBinding")==bodyBinding && prior.getLong("keyVersion")==keyVersion) {
                    val old=prior.getJSONObject("row")
                    for(field in listOf("bodyRevision","promiseId","recipeOid","opaqueContentId","contentBytes"))row.put(field,old.get(field))
                }
                JSONObject().put("sourceHash",hash).put("bodyBinding",bodyBinding).put("keyVersion",keyVersion).put("row",row)
                    .put("meta",SyncV2Codec.b64(sealedMeta.bytes)).put("state",SyncV2Codec.b64(sealedState.bytes))
                    .put("refs",SyncV2Index.refs(identity,signer,sealedState.oid))
            }
            val row=value.getJSONObject("row");rows.add(row)
            objects[row.getString("metaOid")]=SyncV2Codec.decode64(value.getString("meta"));objects[row.getString("stateOid")]=SyncV2Codec.decode64(value.getString("state"))
            value.optJSONObject("extraObjects")?.let{extra->for(oid in extra.keys())objects[oid]=SyncV2Codec.decode64(extra.getString(oid))}
            // Registration order matters against the real backend: a segmented parent's refs table
            // may only register after every child segment's own refs table exists (children gate).
            value.optJSONArray("childRefs")?.let{list->for(i in 0 until list.length()) {
                val child=list.getJSONObject(i);refs.add(child)
                objects[SyncV2Codec.oid("state-refs",child)]=SyncV2Codec.utf8(SyncV2Codec.canonical(child))
            }}
            val signed=value.getJSONObject("refs");if(value.opt("postRefs")!=false)refs.add(signed)
            objects[SyncV2Codec.oid("state-refs",signed)]=SyncV2Codec.utf8(SyncV2Codec.canonical(signed))
            if(value.opt("postRefs")!=false && SyncV2Codec.canonical(value)!=SyncV2Codec.canonical(prior))prepared[entity]=seal("wire-candidate",value)
            if(c[1]=="note")fault("note-publish-staged")
        }
        val tree=SyncV2Index.build(identity.scope,rows);objects.putAll(tree.objects)
        try {if(prepared.isNotEmpty())writer(before.version) { _ -> for((entity,value)in prepared)db.execSQL("INSERT OR REPLACE INTO syncv2_wire_candidates VALUES(?,?,?)",arrayOf(scope,entity,value)) }} finally {prepared.close()}
        val built=WireIndex(tree,objects,refs,objects.keys.intersect(currentObjects.keys))
        reservations.forEach{it.close()}
        // The caller owns each returned index. Preparing another index must not
        // invalidate bytes that a concurrent/previous preparation still references.
        lastWireIndex=built
        return built
        }catch(e:Throwable){prepared.close();reservations.forEach{it.close()};objects.close();throw e}
    }
    private fun outboxOwned(entity: String) = SyncV2NoteWiring.outboxOwned(db, scope, entity)
    /** F1/W3 exposure: an isolated note is unopenable on this device until its row re-verifies. */
    fun noteIsolation(): JSONObject = readState().body.optJSONObject("noteIsolation") ?: JSONObject()
    /** P5 shares the existing SQLite writer and secure local key; no second state journal. */
    internal val accountDatabase:SupportSQLiteDatabase get()=db
    private val catalog by lazy{SyncV2AccountCatalog(db,scope,::accountSeal,::accountOpen){readState().body}}
    internal val accountCatalog get()=catalog
    internal fun accountKnownHistoricalOid(oid:String)=accountCatalog.header(oid)?.optBoolean("accountPath")==true
    internal fun accountCacheRow(value:JSONObject)=seal("cloud-row",value)
    internal fun accountObject(oid:String):ByteArray = db.controlQuery("SELECT body FROM syncv2_cloud_objects WHERE scope=? AND oid=?",arrayOf(scope,oid)).use{c->if(!c.moveToFirst())SyncV2Codec.fail("closure_incomplete");c.getBlob(0).also{if(oid.substringAfter(":sha256:")!=SyncV2Codec.hex(SyncV2Codec.sha(it)))SyncV2Codec.fail("hash_mismatch")}}
    internal fun accountEnsureCatalog() {
        val st=state();if(st.body.optInt("p5CatalogSchema")==6)return
        accountWriter(st.version){current->
            val catalog=accountCatalog
            for(table in listOf("syncv2_p5_catalog","syncv2_p5_channels","syncv2_p5_ref_index"))db.execSQL("DELETE FROM $table WHERE scope=?",arrayOf(scope))
            db.execSQL("DELETE FROM syncv2_p5_selector_nodes WHERE scope=? AND kind!='headers'",arrayOf(scope))
            catalog.reset()
            for(side in listOf("candidate","cloud","binding")) {
                val table=when(side){"candidate"->"syncv2_candidates";"cloud"->"syncv2_cloud_rows";else->"syncv2_p5_bindings"}
                db.execSQL("DELETE FROM syncv2_p5_channels WHERE scope=? AND side=?",arrayOf(scope,side))
                db.controlQuery(if(side=="candidate")"SELECT meta,state FROM $table WHERE scope=?"else"SELECT body FROM $table WHERE scope=?",arrayOf(scope)).use{c->while(c.moveToNext()) {
                    val record=if(side=="candidate")JSONObject().put("meta",open("meta",c.getBlob(0))).put("state",open("candidate-state",c.getBlob(1)))else if(side=="cloud")open("cloud-row",c.getBlob(0))else accountOpen("binding",c.getBlob(0))
                    if(side=="cloud")catalog.setCloudRow(record,null)else catalog.put(side,record)
                }}
            }
            db.controlQuery("SELECT oid,body FROM syncv2_cloud_objects WHERE scope=? AND oid LIKE 'state-refs:%'",arrayOf(scope)).use{c->while(c.moveToNext()){val bytes=c.getBlob(1);if(c.getString(0)!=SyncV2Codec.oid("state-refs",SyncV2Wire.parse(bytes,262144,true)))SyncV2Codec.fail("hash_mismatch");val value=SyncV2Wire.parse(bytes,262144,true);catalog.putRefs(value.getJSONObject("body").getString("stateOid"),c.getString(0))}}
            val rows=cloudRows();if(rows.isNotEmpty() || current.body.has("metadataAccount"))current.body.put("metadataTreeRoot",SyncV2Index.build(identity.scope,rows.map{it.getJSONObject("row")}).root)
            current.body.put("p5CatalogSchema",6)
        }
    }
    private fun selection(ids:Set<String>?):Pair<String,Array<out Any>> {
        if(ids==null)return "" to arrayOf(scope)
        if(ids.isEmpty())return " AND 0" to arrayOf(scope)
        return " AND entity_key IN (${ids.joinToString(","){"?"}})" to (listOf(scope)+ids.sorted()).toTypedArray()
    }
    internal fun accountSeal(purpose:String,value:Any?)=seal("p5/$purpose",JSONObject().put("json",SyncV2LocalJson.canonical(value)))
    internal fun accountOpen(purpose:String,bytes:ByteArray)=JSONObject(open("p5/$purpose",bytes).getString("json"))
    internal fun recoveryKey():ByteArray=SyncV2Codec.hmac(key,SyncV2Codec.lp(SyncV2Codec.utf8("wrss.sync.v2/local-recovery-key"),SyncV2Codec.utf8(scope)))
    internal fun <T> accountWriter(expected:String,action:(State)->T):T=try{writer(expected,action)}
        catch(_:android.database.sqlite.SQLiteFullException){SyncV2Codec.fail("storage_pressure")}
        catch(_:android.database.sqlite.SQLiteDiskIOException){SyncV2Codec.fail("storage_pressure")}
    internal fun accountCapture(requirePending:Boolean=true):ChoiceCapture {
        val st=state();val a=current(st)
        if(requirePending && st.body.isNull("pendingChoice"))SyncV2Codec.fail("choice_stale")
        return ChoiceCapture(st.version,a.getString("oid"),a.getString("revision"),a.getString("generation"),st.revision)
    }
    internal fun accountCheck(st:State,c:ChoiceCapture,requirePending:Boolean=true) {
        ChoiceCapture.restore(c.json());val a=current(st)
        if(c.stateVersion!=st.version || c.localChangeRevision!=st.revision || c.accountOid!=a.getString("oid") || c.accountRevision!=a.getString("revision") || c.generation!=a.getString("generation"))SyncV2Codec.fail("choice_stale")
        if(requirePending && st.body.optJSONObject("pendingChoice")?.optString("generation")!=c.generation)SyncV2Codec.fail("choice_stale")
    }
    /** Values are always from the current local projection, never a browsable old cloud cache. */
    internal fun accountLocalRows(ids:Set<String>?=null):List<JSONObject> {
        if(!db.inTransaction())state();val selected=selection(ids)
        val keys=db.controlQuery("SELECT entity_key FROM syncv2_candidates WHERE scope=?${selected.first} ORDER BY entity_key",selected.second).use{c->buildList{while(c.moveToNext())add(c.getString(0))}}
        val live=keys.filter{entity->db.controlQuery("SELECT state FROM syncv2_candidates WHERE scope=? AND entity_key=?",arrayOf(scope,entity)).use{c->c.moveToFirst();!open("candidate-state",c.getBlob(0)).has("projectionRemoved")}}
        val records=live.mapRows{entity->db.controlQuery("SELECT kind,meta,state FROM syncv2_candidates WHERE scope=? AND entity_key=?",arrayOf(scope,entity)).use{c->
            if(!c.moveToFirst())SyncV2Codec.fail("closure_incomplete")
            val m=open("meta",c.getBlob(1));val state=open("candidate-state",c.getBlob(2))
            JSONObject().put("meta",m).put("state",state).put("value",SyncV2Business.join(c.getString(0),m,state))
        }}
        val rss=SyncV2RssApply(db,records)
        return records.mapRows{record->if(record.getJSONObject("meta").getString("kind")=="rss-state") {
            val meta=record.getJSONObject("meta");val entity=meta.getString("entityKey")
            val prior=db.controlQuery("SELECT body FROM syncv2_p5_bindings WHERE scope=? AND entity_key=?",arrayOf(scope,entity)).use{if(it.moveToFirst())accountOpen("binding",it.getBlob(0))else null}
            val footprint=prior?.optJSONObject("localValue");val current=rss.footprint(record.getJSONObject("value"))
            if(footprint!=null && current!=null) {
                val value=JSONObject(record.getJSONObject("value").toString())
                for(field in current.keys())if(SyncV2LocalJson.canonical(current.opt(field))!=SyncV2LocalJson.canonical(footprint.opt(field)))value.put(field,current.get(field))
                if(SyncV2LocalJson.canonical(value)!=SyncV2LocalJson.canonical(record.getJSONObject("value"))) {
                    record.put("p5Base",record.getJSONObject("value")).put("value",value)
                    val pair=SyncV2Business.split("rss-state",meta.getString("logicalId"),entity,value,null);record.put("meta",pair.meta).put("state",pair.state)
                }
            }
        }
        record}
    }
    internal fun accountWireRowFor(record:JSONObject):JSONObject? {
        val m=record.getJSONObject("meta");val entity=m.getString("entityKey");val kind=m.getString("kind")
        val prior=db.controlQuery("SELECT body FROM syncv2_wire_candidates WHERE scope=? AND entity_key=?",arrayOf(scope,entity)).use{if(it.moveToFirst())open("wire-candidate",it.getBlob(0))else null}?:return null
        val binding=SyncV2Codec.hash(JSONObject().put("kind",kind).put("logicalId",m.getString("logicalId"))
            .put("bodyHash",m.getJSONObject("payload").opt("contentHash")?:m.getJSONObject("payload").opt("fingerprint")?:JSONObject.NULL)
            .put("assetVersion",if(kind=="asset")record.getJSONObject("state").getJSONObject("payload").opt("updatedAt")?:JSONObject.NULL else JSONObject.NULL)
            .put("deleted",record.getJSONObject("value").optBoolean("deleted",false)))
        return if(prior.optString("bodyBinding")==binding)prior.getJSONObject("row")else null
    }
    internal fun accountBase(entity:String):Pair<Boolean,JSONObject?> = db.controlQuery("SELECT meta,state FROM syncv2_edit_base WHERE scope=? AND entity_key=?",arrayOf(scope,entity)).use{c->
        if(!c.moveToFirst())false to null else {val meta=open("meta",c.getBlob(0));true to if(meta.optBoolean("p5Absent"))null else SyncV2Business.join(meta.getString("kind"),meta,open("candidate-state",c.getBlob(1)))}
    }
    internal fun accountBases():Map<String,JSONObject?> = db.controlQuery("SELECT entity_key,meta,state FROM syncv2_edit_base WHERE scope=?",arrayOf(scope)).use{c->buildMap {
        while(c.moveToNext()){val meta=open("meta",c.getBlob(1));put(c.getString(0),if(meta.optBoolean("p5Absent"))null else SyncV2Business.join(meta.getString("kind"),meta,open("candidate-state",c.getBlob(2))))}
    }}
    /** Called only after business apply in this SAME writer. Generated business logs become the
     * new authenticated baseline; original legacy transport cursors are deliberately untouched. */
    internal fun accountBinding(entity:String):JSONObject?=db.controlQuery("SELECT body FROM syncv2_p5_bindings WHERE scope=? AND entity_key=?",arrayOf(scope,entity)).use{c->if(c.moveToFirst())accountOpen("binding",c.getBlob(0))else null}
    internal fun accountBindings(ids:Set<String>?=null):Map<String,JSONObject> {
        val selected=selection(ids)
        val savedKeys=db.controlQuery("SELECT entity_key FROM syncv2_p5_bindings WHERE scope=?${selected.first}",selected.second).use{c->buildSet{while(c.moveToNext())add(c.getString(0))}}
        return object:AbstractMap<String,JSONObject>() {
            override val keys get()=savedKeys
            override fun get(key:String)=if(key in savedKeys)accountBinding(key)else null
            override val entries get()=savedKeys.map{key->object:Map.Entry<String,JSONObject>{override val key=key;override val value get()=accountBinding(key)?:SyncV2Codec.fail("closure_incomplete")}}.toSet()
        }
    }
    internal fun accountApplied(st:State,account:JSONObject,rows:List<JSONObject>,requestId:String,
        beforeRows:List<JSONObject>?=null,rssApplied:SyncV2RssApply?=null,acknowledgedRevision:String=st.revision,virtualBaseline:Boolean=false,sparseIds:Set<String>?=null) {
        check(db.inTransaction());if(SyncV2Codec.d64(acknowledgedRevision)>SyncV2Codec.d64(st.revision))SyncV2Codec.fail("choice_stale")
        val stamp=SyncV2BusinessWitness.read(db);val encoded=stamp.encoded();val bindings=accountBindings(sparseIds)
        val before=(beforeRows?:accountLocalRows()).indexRows()
        val desired=rows.map{it.getJSONObject("meta").getString("entityKey")}.toSet()
        for(entity in (bindings.keys+before.keys)-desired) {
            for(table in listOf("syncv2_candidates","syncv2_provenance","syncv2_wire_candidates","syncv2_p5_bindings"))db.execSQL("DELETE FROM $table WHERE scope=? AND entity_key=?",arrayOf(scope,entity))
            SyncV2CandidateTree(db,scope).remove(entity);accountCatalog.remove("candidate",entity);accountCatalog.remove("binding",entity)
        }
        val rss=rssApplied?:if(rows.any{it.getJSONObject("meta").getString("kind")=="rss-state"})SyncV2RssApply(db,rows)else null
        val baselineRss=if(virtualBaseline)rss?.baselineStates(rows).orEmpty()else emptyMap()
        fun same(a:Any?,b:Any?)=SyncV2LocalJson.canonical(a)==SyncV2LocalJson.canonical(b)
        for(value in rows) {
            if(value.has(SyncV2NoteWiring.ISOLATED_KEY))continue
            val m=value.getJSONObject("meta");val entity=m.getString("entityKey");val prior=bindings[entity];val previous=before[entity]
            val d=SyncV2Schema.domains.firstOrNull{it.kind==m.getString("kind")}?:if(m.getString("kind")=="rss-state")SyncV2Schema.Domain("p5-public-state","rss-state","logical_id")else SyncV2Codec.fail("unsupported_feature")
            val id=m.getString("logicalId")
            val source=JSONObject(value.toString()).apply{remove("p5Base");remove("localValue")}
            val oldSource=prior?.let{JSONObject(it.toString()).apply{remove("localValue")}}
            fun semantics(r:JSONObject?):JSONObject?=r?.let{JSONObject(it.toString()).apply {
                remove("wire");optJSONObject("row")?.let{it.remove("metaOid");it.remove("stateOid")}
            }}
            val physicalRssChange=d.kind=="article" && id in rss?.changedArticleIds.orEmpty()
            if(physicalRssChange || !same(semantics(source),semantics(oldSource))) {
                val footprint=if(!physicalRssChange && prior!=null && same(prior.optJSONObject("value"),value.getJSONObject("value")))prior.opt("localValue")?:JSONObject.NULL
                    else if(d.kind=="rss-state")rss!!.footprint(value.getJSONObject("value"))?:JSONObject.NULL
                    else db.controlQuery("SELECT ${d.key} AS projection_key,${columns(d)} FROM `${d.table}` WHERE (${d.key})=?",arrayOf(id)).use{c->if(c.moveToFirst())readStoredRecord(d,c)else if(virtualBaseline)JSONObject(value.getJSONObject("value").toString())else SyncV2Codec.fail("closure_incomplete")}
                // Successful-prefix reconciliation keeps tail bodies in place. This virtual
                // Room footprint represents the published baseline, before tail overlays.
                if(virtualBaseline && footprint is JSONObject) {
                    val target=value.getJSONObject("value")
                    for(field in footprint.keys().asSequence().toList())if(target.has(field))footprint.put(field,target.get(field))
                    if(d.kind=="article")baselineRss[id]?.let{state->for(field in state.keys())if(footprint.has(field))footprint.put(field,state.get(field))}
                    if(d.kind=="asset")footprint.put("sourceDeviceId","")
                }
                db.execSQL("INSERT OR REPLACE INTO syncv2_p5_bindings VALUES(?,?,?)",arrayOf(scope,entity,accountSeal("binding",source.put("localValue",footprint))))
            }
            accountCatalog.put("binding",value)
            if(previous==null || !same(previous.getJSONObject("meta"),m) || !same(previous.getJSONObject("state"),value.getJSONObject("state")))
                project(d,id,value.getJSONObject("value"),acknowledgedRevision,ownerBody=st.body,forkEdits=false)else accountCatalog.put("candidate",value)
        }
        st.body.put("businessStamp",encoded).put("acknowledgedLocalRevision",acknowledgedRevision)
            .put("unknownEditBase",false).put("tailChoicePending",false).put("contentPendingIds",JSONArray()).put("editBase",JSONObject.NULL).put("appliedAccount",account)
            .put("activeGeneration",account.getString("generation")).put("appliedRequestId",requestId)
        // Derived scan work is already represented by the committed target. Keep unchanged
        // candidate/tree pages and wire caches; never force a complete post-apply rebuild.
        db.execSQL("DELETE FROM syncv2_edit_base WHERE scope=?",arrayOf(scope))
        db.execSQL("DELETE FROM syncv2_change_log WHERE scope=? AND (length(revision)<length(?) OR (length(revision)=length(?) AND revision<=? COLLATE BINARY))",arrayOf(scope,acknowledgedRevision,acknowledgedRevision,acknowledgedRevision))
        db.execSQL("DELETE FROM syncv2_outbox WHERE scope=? AND seq<=?",arrayOf<Any>(scope,st.body.optLong("tailSeq",0)))
        for(d in SyncV2Schema.domains) {
            val bind=binding(d,"p1-local-v1")
            db.execSQL("INSERT INTO syncv2_scan(scope,domain,binding,r0,r1,page_key,tail_seq,complete,business_stamp) VALUES(?,?,?,?,?,'',0,1,?) ON CONFLICT(scope,domain) DO UPDATE SET binding=excluded.binding,r0=excluded.r0,r1=excluded.r1,page_key='',complete=1,business_stamp=excluded.business_stamp",arrayOf(scope,d.table,bind,st.revision,st.revision,encoded))
        }
        for(key in listOf("projectionRebuildRequired","tailR1Seq","reconcileStamp","reconcileDomain","reconcileSemantics"))st.body.remove(key)
        st.body.put("reconciledStamp",encoded).put("reconciledSemantics",reconciliationSemantics())
        pruneNoteForks()
        refreshPending(st.body)
    }
    internal fun accountRetainTail(st:State,published:JSONObject,rows:List<JSONObject>,changed:Set<String>) {
        check(db.inTransaction());if(changed.isEmpty() && st.revision==st.body.optString("acknowledgedLocalRevision","0"))return
        val target=rows.indexRows()
        for(entity in changed) {
            val r=target[entity]
            if(r?.has(SyncV2NoteWiring.ISOLATED_KEY)==true)continue
            if(r!=null) {
                val m=r.getJSONObject("meta");val d=SyncV2Schema.domains.firstOrNull{it.kind==m.getString("kind")}?:if(m.getString("kind")=="rss-state")SyncV2Schema.Domain("p5-public-state","rss-state","logical_id")else SyncV2Codec.fail("unsupported_feature")
                project(d,m.getString("logicalId"),r.getJSONObject("value"),st.revision,published,ownerBody=st.body)
            }else {
                val old=db.controlQuery("SELECT kind,logical_id,meta,state FROM syncv2_candidates WHERE scope=? AND entity_key=?",arrayOf(scope,entity)).use{c->if(c.moveToFirst())listOf(c.getString(0),c.getString(1),c.getBlob(2),c.getBlob(3))else null}?:continue
                db.execSQL("INSERT OR REPLACE INTO syncv2_edit_base VALUES(?,?,?,?,?)",arrayOf(scope,entity,seal("edit-base",JSONObject().put("accountOid",published.getString("oid")).put("generation",published.getString("generation"))),old[2],old[3]))
                val d=SyncV2Schema.domains.firstOrNull{it.kind==old[0]}?:SyncV2Codec.fail("unsupported_feature")
                markRemoved(d,old[1] as String,st.revision)
            }
        }
        st.body.put("editBase",JSONObject().put("accountOid",published.getString("oid")).put("generation",published.getString("generation"))).put("tailChoicePending",true)
        refreshPending(st.body)
    }
    internal fun accountProof(account:JSONObject,headers:List<JSONObject>,verify:(JSONObject)->JSONObject,read:((String)->JSONObject)?=null):SyncV2HeadPins.Proof =
        authenticateHeads(validAccount(account),headers,state(),verify,read)
    internal fun accountAdopt(st:State,account:JSONObject,proof:SyncV2HeadPins.Proof) {
        observeInto(st,validAccount(account),proof.path);retainControlHeaders(st.body,proof)
    }
    /** A metadata-only publication cannot authorize discarding its sole unuploaded body. */
    internal fun accountProtectPendingBodies(expected:String,ids:Set<String>) {
        if(ids.isEmpty())return
        writer(expected){st->accountProtectPendingBodies(st,ids)}
    }
    internal fun accountProtectPendingBodies(st:State,ids:Set<String>) {
        if(ids.isEmpty())return
        check(db.inTransaction())
        db.execSQL("UPDATE syncv2_state SET local_revision=? WHERE scope=?",arrayOf(SyncV2Codec.next(st.revision),scope))
        st.body.put("contentPendingIds",JSONArray(ids.sorted())).put("unknownEditBase",true)
        st.body.optJSONObject("appliedAccount")?.let{a->st.body.put("editBase",JSONObject().put("accountOid",a.getString("oid")).put("generation",a.getString("generation")))}
        refreshPending(st.body)
    }
    internal fun accountChangedIds():Set<String> = db.controlQuery("SELECT DISTINCT entity_key FROM syncv2_change_log WHERE scope=?",arrayOf(scope)).use{c->buildSet{while(c.moveToNext())add(c.getString(0))}}
    /** Retain the exact locally signed P5 row for the existing P3 content attach/C10 path.
     * Foreign historical rows never become locally authored state-refs. */
    internal fun accountStoreOwnWireRows(rows:List<JSONObject>,objects:Map<String,ByteArray>,changedOids:Set<String> = objects.keys) {
        val references=objects.filterKeys{it.startsWith("state-refs:")}.values.map{SyncV2Wire.parse(it,262144,true)}.associateBy{it.getJSONObject("body").getString("stateOid")}
        for(record in rows) {
            if(record.has(SyncV2NoteWiring.ISOLATED_KEY))continue // verbatim remote rows are never owned wire rows
            val m=record.getJSONObject("meta");val entity=m.getString("entityKey");val row=record.getJSONObject("row");val kind=m.getString("kind")
            val old=db.controlQuery("SELECT body FROM syncv2_wire_candidates WHERE scope=? AND entity_key=?",arrayOf(scope,entity)).use{if(it.moveToFirst())open("wire-candidate",it.getBlob(0))else null}
            if(row.getString("metaOid") !in changedOids && row.getString("stateOid") !in changedOids && old==null)continue
            val hash=db.controlQuery("SELECT source_hash FROM syncv2_candidates WHERE scope=? AND entity_key=?",arrayOf(scope,entity)).use{if(it.moveToFirst())it.getString(0)else SyncV2Codec.fail("closure_incomplete")}
            if(old?.optString("sourceHash")==hash && SyncV2Codec.canonical(old.getJSONObject("row"))==SyncV2Codec.canonical(row))continue
            val meta=objects[row.getString("metaOid")]?:SyncV2Codec.fail("closure_incomplete");val state=objects[row.getString("stateOid")]?:SyncV2Codec.fail("closure_incomplete")
            val length=java.nio.ByteBuffer.wrap(meta,4,4).int;val version=SyncV2Wire.parse(meta.copyOfRange(8,8+length),4096,true).getLong("keyVersion")
            val binding=SyncV2Codec.hash(JSONObject().put("kind",kind).put("logicalId",m.getString("logicalId"))
                .put("bodyHash",m.getJSONObject("payload").opt("contentHash")?:m.getJSONObject("payload").opt("fingerprint")?:JSONObject.NULL)
                .put("assetVersion",if(kind=="asset")record.getJSONObject("state").getJSONObject("payload").opt("updatedAt")?:JSONObject.NULL else JSONObject.NULL)
                .put("deleted",record.getJSONObject("value").optBoolean("deleted",false)))
            val value=JSONObject().put("sourceHash",hash).put("bodyBinding",binding).put("keyVersion",version).put("row",row).put("meta",SyncV2Codec.b64(meta)).put("state",SyncV2Codec.b64(state))
                .put("refs",references[row.getString("stateOid")]?:SyncV2Codec.fail("closure_incomplete")).put("postRefs",false)
            db.execSQL("INSERT OR REPLACE INTO syncv2_wire_candidates VALUES(?,?,?)",arrayOf(scope,entity,seal("wire-candidate",value)))
        }
    }
    internal fun accountReplaceMetadata(st:State,account:JSONObject,rows:List<JSONObject>,objects:Map<String,ByteArray>,serverRevision:String) {
        SyncV2Codec.d64(serverRevision)
        // Cross-entity passes use descriptors only. Opening the encrypted full row for
        // every index/ref/key pass creates a body-sized garbage peak per pass and note.
        val descriptors=rows.map{r->JSONObject().also{out->for(key in listOf("meta","row",SyncV2NoteWiring.ISOLATED_KEY,SyncV2NoteWiring.SEGMENTS_KEY,SyncV2NoteWiring.REFS_MAPS_KEY,SyncV2NoteWiring.REVISION_KEY))if(r.has(key))out.put(key,r.get(key))}}
        val previousObjects=cloudObjects()
        // Overlay only keys. Raw bytes are point-read and released for each OID.
        val available=object:AbstractMap<String,ByteArray>() {
            override val keys get()=previousObjects.keys+objects.keys
            override fun get(key:String)=objects[key]?:previousObjects[key]
            override val entries get()=keys.map{oid->object:Map.Entry<String,ByteArray>{override val key=oid;override val value get()=getValue(oid)}}.toSet()
        }
        val root=objects[account.getString("oid")]?.let{SyncV2Wire.parse(it,262144,true).getJSONObject("body").getString("treeRoot")}
            ?:objects.entries.firstOrNull{(oid,bytes)->oid.startsWith("tree:") && SyncV2Wire.parse(bytes,262144,true).getString("prefix")==""}?.key
            ?:SyncV2Codec.fail("closure_incomplete")
        val needed=mutableSetOf<String>()
        val indexed=SyncV2Index.read(identity.scope,root,{oid->needed.add(oid);available[oid]?:SyncV2Codec.fail("closure_incomplete")})
        if(SyncV2Codec.canonical(JSONArray(indexed))!=SyncV2Codec.canonical(JSONArray(descriptors.map{it.getJSONObject("row")}.sortedBy{it.getString("entityKey")})))SyncV2Codec.fail("hash_mismatch")
        for(r in descriptors)for(field in listOf("metaOid","stateOid")) {
            val oid=r.getJSONObject("row").getString(field)
            if(!r.has(SyncV2NoteWiring.ISOLATED_KEY)||oid in available.keys)needed.add(oid)
        }
        // Segmented rows keep their authenticated children and refs; verbatim reuse depends on them.
        // An isolated row fetched before the read gate opened may legitimately lack children locally;
        // keep only objects that are actually present so the round never dies on them (F1).
        val states=mutableSetOf<String>();val requiredStates=mutableSetOf<String>()
        for(r in descriptors) {
            val ids=listOf(r.getJSONObject("row").getString("stateOid"))+
                (r.optJSONArray(SyncV2NoteWiring.SEGMENTS_KEY)?.let{a->(0 until a.length()).map{a.getString(it)}}?:emptyList())
            states.addAll(ids.filter{it in available.keys})
            if(!r.has(SyncV2NoteWiring.ISOLATED_KEY))requiredStates.addAll(ids)
        }
        val referenced=mutableSetOf<String>();val refOids=mutableMapOf<String,String>()
        for(oid in available.keys)if(oid.startsWith("state-refs:")) {
            val state=SyncV2Wire.parse(available.getValue(oid),262144,true).getJSONObject("body").getString("stateOid")
            if(state in states){needed.add(oid);referenced.add(state);refOids[state]=oid}
        }
        // Best-effort isolated refs never become an install prerequisite (review 8).
        if(!referenced.containsAll(requiredStates))SyncV2Codec.fail("closure_incomplete")
        needed+=states
        for(oid in needed) {
            val bytes=available[oid]?:SyncV2Codec.fail("closure_incomplete")
            if(previousObjects[oid]?.contentEquals(bytes)!=true)db.execSQL("INSERT OR REPLACE INTO syncv2_cloud_objects VALUES(?,?,?)",arrayOf(scope,oid,bytes))
        }
        for(oid in previousObjects.keys-needed)db.execSQL("DELETE FROM syncv2_cloud_objects WHERE scope=? AND oid=?",arrayOf(scope,oid))
        fun normalized(r:JSONObject)=JSONObject().also{out->for(key in r.keys())if(key !in setOf("value","wire","p5Base","localValue"))out.put(key,r.get(key))}
        val previous=db.controlQuery("SELECT entity_key FROM syncv2_cloud_rows WHERE scope=?",arrayOf(scope)).use{c->buildSet{while(c.moveToNext())add(c.getString(0))}}
        val keep=descriptors.map{it.getJSONObject("meta").getString("entityKey")}.toSet()
        // The local wire cache survives replacement. It is needed for byte-identical reuse (3).
        for(entity in previous-keep)db.execSQL("DELETE FROM syncv2_cloud_rows WHERE scope=? AND entity_key=?",arrayOf(scope,entity))
        for(r in rows) {
            val entity=r.getJSONObject("meta").getString("entityKey");val value=normalized(r)
            val old=db.controlQuery("SELECT body FROM syncv2_cloud_rows WHERE scope=? AND entity_key=?",arrayOf(scope,entity)).use{if(it.moveToFirst())open("cloud-row",it.getBlob(0))else null}
            if(SyncV2Codec.canonical(old)!=SyncV2Codec.canonical(value))db.execSQL("INSERT OR REPLACE INTO syncv2_cloud_rows VALUES(?,?,?)",arrayOf(scope,entity,seal("cloud-row",value)))
            if(value.has(SyncV2NoteWiring.ISOLATED_KEY))accountCatalog.remove("cloud",entity)
            else accountCatalog.setCloudRow(value,null)
            SyncV2NoteWiring.observeBindings(db,scope,listOf(r)){received->
                val m=received.getJSONObject("meta");val c=candidate(m.getString("kind"),m.getString("logicalId"))
                c!=null&&(SyncV2Codec.canonical(c.first)!=SyncV2Codec.canonical(m)||SyncV2Codec.canonical(c.second)!=SyncV2Codec.canonical(received.getJSONObject("state")))
            }
        }
        for(entity in previous-keep)accountCatalog.remove("cloud",entity)
        for((state,oid)in refOids)accountCatalog.putRefs(state,oid)
        // Absence clears an isolation mark, without tombstoning the lifetime binding (Q1).
        val isolated=JSONObject();for(r in descriptors)if(r.has(SyncV2NoteWiring.ISOLATED_KEY))
            isolated.put(r.getJSONObject("meta").getString("entityKey"),JSONObject().put("reason",r.getString(SyncV2NoteWiring.ISOLATED_KEY))
                .put("stateOid",r.getJSONObject("row").getString("stateOid")).put("noteRevision",r.opt(SyncV2NoteWiring.REVISION_KEY)?:JSONObject.NULL))
        val priorIsolation=st.body.optJSONObject("noteIsolation")?:JSONObject()
        st.body.put("noteIsolation",isolated)
        // R2: capture edits that were already projected before the remote row became
        // unopenable. Ordinary reconciliation sees an unchanged candidate and would
        // otherwise skip it, allowing a later account choice to acknowledge it away.
        val notes=SyncV2Schema.domains.single{it.table=="notes"}
        for(entity in isolated.keys())if(!priorIsolation.has(entity)&&SyncV2NoteWiring.outboxOwned(db,scope,entity)) {
            val id=db.controlQuery("SELECT logical_id FROM syncv2_candidates WHERE scope=? AND entity_key=?",arrayOf(scope,entity)).use{c->if(c.moveToFirst())c.getString(0)else SyncV2Codec.fail("closure_incomplete")}
            val local=db.controlQuery("SELECT noteId AS projection_key,${columns(notes)} FROM notes WHERE noteId=?",arrayOf(id)).use{c->
                if(!c.moveToFirst())SyncV2Codec.fail("closure_incomplete")
                readRecord(notes,c)
            }
            project(notes,id,local,st.revision,st.body.optJSONObject("account"),true,st.body,st.body,forceFork=true)
        }
        st.body.optJSONObject("noteForkSources")?.let{sources->for(entity in sources.keys().asSequence().toList())if(!isolated.has(entity))sources.remove(entity)}
        st.body.put("metadataTreeRoot",root)
        st.body.put("metadataAccount",account).put("metadataStatus","metadata-ready").put("metadataSchema",SyncV2HeadPins.schema)
            .put("metadataRows",rows.size).put("metadataServerRevision",serverRevision)
    }
    internal fun accountFilesObserved(token:JSONObject) {
        val st=state();val old=st.body.optJSONObject("accountFileRevision")
        if(old!=null && SyncV2Codec.canonical(old)==SyncV2Codec.canonical(token))return
        writer(st.version){current->
            if(old!=null) {
                val revision=SyncV2Codec.next(current.revision)
                db.execSQL("UPDATE syncv2_state SET local_revision=? WHERE scope=?",arrayOf(revision,scope))
                if(current.body.isNull("editBase"))current.body.optJSONObject("appliedAccount")?.let{a->current.body.put("editBase",JSONObject().put("accountOid",a.getString("oid")).put("generation",a.getString("generation")))}
                current.body.put("unknownEditBase",true);refreshPending(current.body)
            }
            current.body.put("accountFileRevision",token)
        }
    }
    internal fun accountContact(utc:Long,forceRebuild:Boolean=false) {
        val st=state();val before=st.body.optLong("lastAccountContactUtc",utc)
        if(utc<before)SyncV2Codec.fail("trusted_time_unavailable")
        val rebuild=forceRebuild || utc-before>=604800000L
        if(!rebuild && st.body.has("lastAccountContactUtc") && utc==before)return
        writer(st.version){current->
            current.body.put("lastAccountContactUtc",utc)
            if(rebuild){current.body.put("projectionRebuildRequired",true);db.execSQL("DELETE FROM syncv2_scan WHERE scope=?",arrayOf(scope))}
        }
    }
    private fun validAccount(a: JSONObject): JSONObject {
        SyncV2Codec.exact(a,setOf("oid","revision","generation"))
        if(listOf("oid","revision","generation").any{ a.get(it) !is String }) SyncV2Codec.fail("choice_stale")
        SyncV2Codec.commitOid(a.getString("oid"))
        if(SyncV2Codec.d64(a.getString("revision")).signum()<=0) SyncV2Codec.fail("choice_stale")
        SyncV2Codec.d64(a.getString("generation")); return JSONObject(a.toString())
    }
    private fun current(st: State): JSONObject = try {
        SyncV2Codec.d64(st.version); SyncV2Codec.d64(st.revision)
        val a=validAccount(st.body.getJSONObject("account"))
        if(st.body.get("rollbackGeneration") !is String)SyncV2Codec.fail("choice_stale")
        if(a.getString("generation")!=st.body.getString("rollbackGeneration")) SyncV2Codec.fail("choice_stale")
        for((name,v) in listOf("lastSeenAccountOid" to a.getString("oid"),"lastSeenAccountRevision" to a.getString("revision"),"lastSeen" to a.getString("generation")))
            if(st.body.has(name) && st.body.get(name)!=v) SyncV2Codec.fail("choice_stale")
        a
    } catch (_: Exception) { SyncV2Codec.fail("choice_stale") }
    fun captureLegacyChoice(window: JSONObject): ChoiceCapture {
        ensureActive();val st=readState(); val a=current(st)
        if(SyncV2Codec.canonical(a)!=SyncV2Codec.canonical(window)) SyncV2Codec.fail("choice_stale")
        return ChoiceCapture(st.version,a.getString("oid"),a.getString("revision"),a.getString("generation"),st.revision).also { checkCapture(st,it) }
    }
    private fun checkCapture(st: State,capture: ChoiceCapture) {
        val a=current(st); ChoiceCapture.restore(capture.json())
        if(st.body.optString("businessStamp")!=SyncV2BusinessWitness.peek(db)?.encoded())SyncV2Codec.fail("choice_stale")
        val pending=st.body.optJSONObject("pendingChoice")?:SyncV2Codec.fail("choice_stale")
        if(pending.get("generation") !is String)SyncV2Codec.fail("choice_stale")
        if(capture.stateVersion!=st.version || capture.localChangeRevision!=st.revision || capture.accountOid!=a.getString("oid") ||
            capture.accountRevision!=a.getString("revision") || capture.generation!=a.getString("generation") || capture.generation!=pending.getString("generation")) SyncV2Codec.fail("choice_stale")
    }
    /** Validates at both preparation and commit boundaries, but product apply is intentionally closed. */
    fun validateChoice(capture: ChoiceCapture, prepare: ()->Unit = {}) {
        ensureActive();checkCapture(readState(),capture); prepare()
        db.beginTransaction()
        try { checkCapture(readState(),capture) } finally { db.endTransaction() }
    }
    /** Prepare a minimal encrypted recovery candidate, then recheck the same Capture.
     * It is not a committed recovery reference and cannot authorize discarding the inbox.
     */
    fun prepareLegacyCloud(capture: ChoiceCapture,peer:String,version:Int,cursor:String,cloud:JSONObject,now:Long):JSONObject {
        requireWorkerContext();ensureActive();val before=readState();checkCapture(before,capture)
        if(before.body.optBoolean("unknownEditBase"))SyncV2Codec.fail("merge_unavailable")
        val base=before.body.optJSONObject("editBase")?:SyncV2Codec.fail("merge_unavailable")
        val intent=db.controlQuery("SELECT body FROM syncv2_legacy_inbox WHERE scope=? AND peer=? AND version=? AND cursor=? AND route='pendingChoice'",arrayOf<Any>(scope,peer,version,cursor)).use{if(it.moveToFirst())open("legacy",it.getBlob(0))else SyncV2Codec.fail("choice_stale")}
        if(intent.getJSONObject("body").has("legacy-frame"))SyncV2Codec.fail("unsupported_feature")
        val local=JSONObject(intent.getJSONObject("body").toString());val removed=intent.getJSONArray("removed")
        val captured=local.keys().asSequence().toMutableSet()
        for(i in 0 until removed.length()){val id=removed.getString(i);captured.add(id);local.remove(id)}
        val delta=SyncV2Recovery.delta(local,cloud,base,captured,intent.getJSONObject("bodyRefsByEntity"))
        // Actual recipe/blob closure is a P2 port. P1 must not claim a dangling reference is protected.
        if(delta.getJSONArray("bodyRefs").length()!=0)SyncV2Codec.fail("closure_incomplete")
        val copy=SyncV2Recovery.prepare(delta,key,now,capture.localChangeRevision,capture.accountOid,emptySet())
        validateChoice(capture)
        return copy
    }
    private var legacyCloudResolver:((ChoiceCapture)->JSONObject)?=null
    internal fun bindLegacyCloudResolver(resolve:(ChoiceCapture)->JSONObject){legacyCloudResolver=resolve}
    fun resolveLegacyCloud(capture: ChoiceCapture):JSONObject {
        state();checkCapture(readState(),capture)
        return (legacyCloudResolver?:SyncV2Codec.fail("feature_disabled"))(capture)
    }
    private fun legacyHash(value:JSONObject)=SyncV2Codec.hex(SyncV2Codec.sha(SyncV2Codec.utf8(SyncV2LocalJson.canonical(value))))
    private fun sealLegacy(value:JSONObject):ByteArray {
        // Same existing local envelope/AAD; only the private business-model serializer
        // admits Room fractional progress. No extra quoted copy or wire-J extension.
        val nonce=SyncV2Codec.random(12)
        return nonce+SyncV2Codec.encrypt(key,nonce,SyncV2Codec.utf8("wrss.sync.v2/local/legacy/$scope"),SyncV2Codec.utf8(SyncV2LocalJson.canonical(value)))
    }
    private fun openLegacy(bytes:ByteArray):JSONObject=open("legacy",bytes).let{if(it.length()==1 && it.opt("json") is String)JSONObject(it.getString("json"))else it}
    internal fun accountLegacyEntries(selected:Set<String>?=null):List<JSONObject> {
        val out=mutableListOf<JSONObject>()
        db.controlQuery("SELECT peer,version,cursor,body_hash,body,arrival FROM syncv2_legacy_inbox WHERE scope=? AND route='pendingChoice' AND applied=0 ORDER BY arrival,rowid",arrayOf(scope)).use{c->while(c.moveToNext()) {
            val key=SyncV2Codec.canonical(JSONArray().put(c.getString(0)).put(c.getInt(1)).put(c.getString(2)))
            if(selected!=null && key !in selected)continue
            val intent=openLegacy(c.getBlob(4));if(legacyHash(intent)!=c.getString(3))SyncV2Codec.fail("hash_mismatch")
            out.add(JSONObject().put("key",key).put("hash",c.getString(3)).put("intent",intent).put("arrival",c.getLong(5)))
            if(out.size==64)break
        }}
        if(selected!=null && (selected.size>64 || out.map{it.getString("key")}.toSet()!=selected))SyncV2Codec.fail("choice_stale")
        return out
    }
    internal fun accountResolveLegacy(st:State,selected:JSONArray,choice:String) {
        check(db.inTransaction());val streams=mutableSetOf<Pair<String,Int>>()
        for(i in 0 until selected.length()) {
            val item=selected.getJSONObject(i);val key=JSONArray(item.getString("key"));val peer=key.getString(0);val version=key.getInt(1);val cursor=key.getString(2)
            val old=db.controlQuery("SELECT body_hash,route,applied FROM syncv2_legacy_inbox WHERE scope=? AND peer=? AND version=? AND cursor=?",arrayOf(scope,peer,version,cursor)).use{c->if(!c.moveToFirst())SyncV2Codec.fail("choice_stale");Triple(c.getString(0),c.getString(1),c.getInt(2))}
            if(old.first!=item.getString("hash") || old.second!="pendingChoice" || old.third!=0)SyncV2Codec.fail("choice_stale")
            val discard=choice=="cloud";val route=if(discard)"discarded-cloud"else"resolve-$choice"
            val marker=JSONObject().put("resolved",true).put("bodyHash",old.first).put("selection",choice)
            db.execSQL("UPDATE syncv2_legacy_inbox SET route=?,applied=?,body=? WHERE scope=? AND peer=? AND version=? AND cursor=?",arrayOf(route,if(discard)0 else 1,sealLegacy(marker),scope,peer,version,cursor))
            if(!discard)streams.add(peer to version)
        }
        for((peer,version)in streams) {
            var frontier:String?=null;var arrival=0L
            db.controlQuery("SELECT cursor,applied,arrival FROM syncv2_legacy_inbox WHERE scope=? AND peer=? AND version=? ORDER BY arrival",arrayOf<Any>(scope,peer,version)).use{c->while(c.moveToNext()){if(c.getInt(1)==0)break;frontier=c.getString(0);arrival=c.getLong(2)}}
            if(frontier!=null) {
                val stream=SyncV2Codec.canonical(JSONArray().put(peer).put(version));val old=st.body.getJSONObject("applied").optString(stream,"")
                val previous=db.controlQuery("SELECT arrival FROM syncv2_legacy_inbox WHERE scope=? AND peer=? AND version=? AND cursor=?",arrayOf(scope,peer,version,old)).use{c->if(c.moveToFirst())c.getLong(0)else 0L}
                if(arrival>=previous)st.body.getJSONObject("applied").put(stream,frontier)
            }
        }
        refreshPending(st.body)
    }
    private fun pending(body: JSONObject,generation: String) {
        val keys=body.optJSONObject("pendingChoice")?.optJSONArray("inboxKeys")?:JSONArray()
        body.put("pendingChoice",JSONObject().put("generation",generation).put("inboxKeys",keys))
    }
    private fun refreshPending(body: JSONObject) {
        val keys=JSONArray()
        db.controlQuery("SELECT peer,version,cursor FROM syncv2_legacy_inbox WHERE scope=? AND route='pendingChoice' AND applied=0 ORDER BY arrival,rowid",arrayOf(scope)).use { c ->
            while(c.moveToNext())keys.put(JSONArray().put(c.getString(0)).put(c.getInt(1)).put(c.getString(2)))
        }
        val editPending=body.optBoolean("tailChoicePending") || (body.optJSONArray("contentPendingIds")?.length()?:0)>0 || (body.optBoolean("unknownEditBase") && body.getString("rollbackGeneration")!="0") || (body.optJSONObject("editBase")?.let { b -> SyncV2Codec.d64(b.getString("generation"))<SyncV2Codec.d64(body.getString("rollbackGeneration")) }?:false)
        body.put("pendingChoice",if(keys.length()>0 || editPending) JSONObject().put("generation",body.getString("rollbackGeneration")).put("inboxKeys",keys) else JSONObject.NULL)
    }
    data class LegacyInput(val peer: String,val version: Int,val cursor: String,val body: JSONObject,val removed: List<String> = emptyList(),val bodyRefs: JSONObject = JSONObject())
    /** Receives a local projection copy only. Never acknowledges business apply or changes the BLE cursor. */
    fun receiveLegacy(input: LegacyInput, affected: Set<String>?, rollbackKnown: Boolean): String {
        requireWorkerContext();ensureActive()
        if(input.version !in setOf(13,14) || input.peer.isEmpty() || input.cursor.isEmpty() || input.removed.distinct()!=input.removed || input.removed.any(input.body::has)) SyncV2Codec.fail("invalid_schema")
        val intent=JSONObject().put("body",input.body).put("removed",JSONArray(input.removed.sorted())).put("bodyRefsByEntity",input.bodyRefs)
        if(SyncV2Codec.utf8(SyncV2LocalJson.canonical(intent)).size>1048576)SyncV2Codec.fail("object_too_large")
        val hash=legacyHash(intent)
        val prior=db.controlQuery("SELECT body_hash,route FROM syncv2_legacy_inbox WHERE scope=? AND peer=? AND version=? AND cursor=?",arrayOf<Any>(scope,input.peer,input.version,input.cursor)).use { c ->
            if(c.moveToFirst())c.getString(0) to c.getString(1) else null
        }
        if(prior!=null) { if(prior.first!=hash)SyncV2Codec.fail("idempotency_conflict"); return prior.second }
        return writer { st ->
            db.controlQuery("SELECT body_hash,route FROM syncv2_legacy_inbox WHERE scope=? AND peer=? AND version=? AND cursor=?",arrayOf<Any>(scope,input.peer,input.version,input.cursor)).use { c ->
                if(c.moveToFirst()) {if(c.getString(0)!=hash)SyncV2Codec.fail("idempotency_conflict");return@writer c.getString(1)}
            }
            val ids=input.body.keys().asSequence().toSet()+input.removed
            val barrier=rollbackKnown || st.body.getString("rollbackGeneration")!="0"
            val route=if(barrier && (affected==null || ids.any{it in affected})) "pendingChoice" else "normal"
            val revision=SyncV2Codec.next(st.revision)
            val arrival=db.controlQuery("SELECT COALESCE(MAX(arrival),0)+1 FROM syncv2_legacy_inbox WHERE scope=? AND peer=? AND version=?",arrayOf<Any>(scope,input.peer,input.version)).use { it.moveToFirst();it.getLong(0) }
            fault("before-inbox")
            db.execSQL("INSERT INTO syncv2_legacy_inbox VALUES(?,?,?,?,?,?,?,?,0)",arrayOf<Any>(scope,input.peer,input.version,input.cursor,arrival,hash,sealLegacy(intent),route))
            db.execSQL("UPDATE syncv2_state SET local_revision=? WHERE scope=?",arrayOf(revision,scope))
            val stream=SyncV2Codec.canonical(JSONArray().put(input.peer).put(input.version))
            st.body.getJSONObject("received").put(stream,input.cursor)
            refreshPending(st.body); fault("after-inbox"); route
        }
    }
    private val legacyPlanner=SyncV2LegacyMerge()
    private fun legacyPlanningState():JSONObject {
        val st=readState();val rows=JSONObject();val inbox=JSONObject();val scan=JSONArray()
        db.controlQuery("SELECT kind,logical_id,meta,state FROM syncv2_candidates WHERE scope=? ORDER BY entity_key",arrayOf(scope)).use { c ->
            while(c.moveToNext()) {
                val state=open("candidate-state",c.getBlob(3));if(state.has("projectionRemoved"))SyncV2Codec.fail("merge_not_safe")
                val value=SyncV2Business.join(c.getString(0),open("meta",c.getBlob(2)),state)
                value.put("identity",c.getString(1));rows.put(identity.entity(c.getString(0),c.getString(1)),value)
            }
        }
        db.controlQuery("SELECT peer,version,cursor,body,body_hash,route,applied,arrival FROM syncv2_legacy_inbox WHERE scope=? ORDER BY rowid",arrayOf(scope)).use { c ->
            while(c.moveToNext()) {
                val value=openLegacy(c.getBlob(3))
                if(value.optBoolean("resolved")) {
                    if(value.optString("bodyHash")!=c.getString(4) || c.getString(5)=="pendingChoice")SyncV2Codec.fail("merge_not_safe")
                    inbox.put(SyncV2Codec.canonical(JSONArray().put(c.getString(0)).put(c.getInt(1)).put(c.getString(2))),value.put("route",c.getString(5)).put("applied",c.getInt(6)!=0).put("arrival",c.getLong(7)))
                    continue
                }
                if(legacyHash(value)!=c.getString(4))SyncV2Codec.fail("merge_not_safe")
                val ids=(value.getJSONObject("body").keys().asSequence().toList()+(0 until value.getJSONArray("removed").length()).map{value.getJSONArray("removed").getString(it)}).distinct().sorted()
                value.put("entityKeys",JSONArray(ids)).put("removedEntityKeys",value.getJSONArray("removed"))
                val intent=JSONObject().put("body",value.getJSONObject("body")).put("entityKeys",value.getJSONArray("entityKeys")).put("removedEntityKeys",value.getJSONArray("removedEntityKeys")).put("bodyRefsByEntity",value.getJSONObject("bodyRefsByEntity"))
                value.put("persistedHash",c.getString(4)).put("hash",SyncV2Codec.hash(intent)).put("route",c.getString(5)).put("applied",c.getInt(6)!=0).put("arrival",c.getLong(7))
                if(!value.has("removedEntityKeys"))value.put("removedEntityKeys",value.getJSONArray("removed"))
                inbox.put(SyncV2Codec.canonical(JSONArray().put(c.getString(0)).put(c.getInt(1)).put(c.getString(2))),value)
            }
        }
        db.controlQuery("SELECT domain,binding,r0,r1,page_key,tail_seq,complete,business_stamp FROM syncv2_scan WHERE scope=? ORDER BY domain",arrayOf(scope)).use { c ->
            while(c.moveToNext())scan.put(JSONArray().also {a->for(i in 0 until c.columnCount)a.put(if(c.isNull(i))JSONObject.NULL else c.getString(i))})
        }
        val result=JSONObject(st.body.toString()).put("stateVersion",st.version).put("localChangeRevision",st.revision).put("rows",rows).put("legacyInbox",inbox).put("scan",scan)
        result.optJSONObject("pendingChoice")?.let { p ->val old=p.getJSONArray("inboxKeys");p.put("inboxKeys",JSONArray((0 until old.length()).map{SyncV2Codec.canonical(old.get(it))})) }
        return result
    }
    fun planLegacyMerge(peer:String,version:Int,cursor:String):SyncV2LegacyMerge.Proof {
        state();return legacyPlanner.plan(legacyPlanningState(),SyncV2Codec.canonical(JSONArray().put(peer).put(version).put(cursor)))
    }
    fun resolveLegacyMerge(proof:SyncV2LegacyMerge.Proof) {
        requireWorkerContext();state();legacyPlanner.check(legacyPlanningState(),proof.inboxKey,proof)
        fault("legacy-merge-prepared")
        writer(proof.capture.stateVersion) { st ->
            val latest=legacyPlanningState();val candidate=legacyPlanner.check(latest,proof.inboxKey,proof)
            // The trusted planner must prove the complete current business state is unchanged.
            if(SyncV2Codec.canonical(candidate)!=SyncV2Codec.canonical(latest.getJSONObject("rows")))SyncV2Codec.fail("merge_not_safe")
            val key=JSONArray(proof.inboxKey);val peer=key.getString(0);val version=key.getInt(1);val cursor=key.getString(2)
            db.execSQL("UPDATE syncv2_legacy_inbox SET applied=1,route='resolve-merge' WHERE scope=? AND peer=? AND version=? AND cursor=?",arrayOf(scope,peer,version,cursor))
            var frontier:String?=null
            db.controlQuery("SELECT cursor,applied FROM syncv2_legacy_inbox WHERE scope=? AND peer=? AND version=? ORDER BY arrival",arrayOf<Any>(scope,peer,version)).use { c ->while(c.moveToNext()){if(c.getInt(1)==0)break;frontier=c.getString(0)} }
            if(frontier!=null)st.body.getJSONObject("applied").put(SyncV2Codec.canonical(JSONArray().put(peer).put(version)),frontier)
            refreshPending(st.body)
            // Local idempotent proof is distinct from cloud receipts and from the old transport ACK.
            fault("legacy-merge-committed")
        }
        legacyPlanner.consume(proof)
    }
    private fun noteResolutionState(noteId:String):JSONObject {
        val st=readState();val d=SyncV2Schema.domains.single{it.table=="notes"}
        val value=db.controlQuery("SELECT noteId AS projection_key,${columns(d)} FROM notes WHERE noteId=?",arrayOf(noteId)).use {c->if(c.moveToFirst())readRecord(d,c)else SyncV2Codec.fail("notes_stale")}
        val conflicts=value.getJSONObject("noteConflicts");value.remove("noteConflicts")
        val allTexts=buildString {append(value.getString("markdown"));append(value.getString("baseMarkdown"));append(SyncV2Codec.canonical(conflicts))}
        val hasAssets=db.controlQuery("SELECT 1 FROM note_assets WHERE noteId=? LIMIT 1",arrayOf(noteId)).use{it.moveToFirst()}
        return JSONObject(st.body.toString()).put("stateVersion",st.version).put("localChangeRevision",st.revision)
            .put("note",value).put("conflicts",conflicts).put("entityKey",identity.entity("note",noteId)).put("outbox",JSONArray())
            .put("resolutionBodyClosuresReady",!hasAssets && !allTexts.contains("assets/"))
    }
    fun captureNoteChoice(noteId:String):SyncV2NoteResolution.Capture {state();return SyncV2NoteResolution.capture(noteResolutionState(noteId))}
    /** Separate v2 entry; the old note UI and old business behavior remain unchanged. */
    fun resolveNoteChoice(capture:SyncV2NoteResolution.Capture,choice:String,manual:String?,now:Long,device:String,reservationDirectory:java.io.File) {
        requireWorkerContext();state();val before=noteResolutionState(capture.noteId)
        val (next,budget)=SyncV2NoteResolution.prepare(before,capture,choice,manual,now,device) { note ->
            note.put("plainText",com.lightningstudio.watchrss.phone.data.note.MarkdownNoteCodec.toPlainText(note.getString("markdown")))
        }
        // Materialize a real held reservation, not just a free-space boolean. P3 supplies attachment closure ports.
        reservationDirectory.mkdirs();val file=java.io.File.createTempFile("syncv2-reserve-",".tmp",reservationDirectory)
        try {
            try {java.io.FileOutputStream(file).use {out->val block=ByteArray(8192);var remaining=budget.requiredBytes
                while(remaining>0){val n=minOf(block.size.toLong(),remaining).toInt();out.write(block,0,n);remaining-=n};out.fd.sync()}}
            catch(_:java.io.IOException){SyncV2Codec.fail("storage_pressure")}
            fault("note-resolution-prepared")
            writer(capture.sync.stateVersion) { st ->
                val current=noteResolutionState(capture.noteId);SyncV2NoteResolution.guard(current,capture)
                if(SyncV2Codec.hash(current)!=SyncV2Codec.hash(before))SyncV2Codec.fail("choice_stale")
                val note=next.getJSONObject("note")
                db.execSQL("UPDATE notes SET markdown=?,plainText=?,contentHash=?,baseContentHash=?,baseMarkdown=?,updatedAt=?,modifiedBy=? WHERE noteId=?",arrayOf(note.getString("markdown"),note.getString("plainText"),note.getString("contentHash"),note.getString("baseContentHash"),note.getString("baseMarkdown"),note.getLong("updatedAt"),note.getString("modifiedBy"),capture.noteId))
                for(id in capture.openConflictIds)db.execSQL("UPDATE note_conflicts SET resolvedAt=? WHERE conflictId=? AND noteId=?",arrayOf<Any>(maxOf(1,now),id,capture.noteId))
                db.execSQL("UPDATE syncv2_state SET local_revision=? WHERE scope=?",arrayOf(next.getString("localChangeRevision"),scope))
                st.body.put("businessStamp",SyncV2BusinessWitness.read(db).encoded())
                val business=JSONObject(note.toString()).put("noteConflicts",next.getJSONObject("conflicts"))
                project(SyncV2Schema.domains.single{it.table=="notes"},capture.noteId,business,next.getString("localChangeRevision"),st.body.optJSONObject("account"),true,st.body,st.body)
                fault("note-resolution-written")
            }
        } finally {file.delete()}
    }
    data class PageResult(val domain: String,val rows: Int,val bytes: Int,val complete: Boolean,val tail: Boolean)
    fun status():String {
        val body=state().body
        return if(!body.isNull("pendingChoice"))"awaiting-user" else "local-only"
    }
    data class WindowProgress(val rows:Int,val tailEntries:Int,val pages:Int,val paused:Boolean)
    /** One phone window: at most 290 seconds of work, reserving the final 10 seconds of 300.
     * A fixed page quota also supports small foreground execution windows.
     */
    fun runWindow(maxPages:Int=16,budgetMillis:Long=290000,now:()->Long={android.os.SystemClock.elapsedRealtime()}):WindowProgress {
        require(maxPages in 1..4096 && budgetMillis in 1..290000)
        ensureActive();val start=now();var rows=0;var pages=0;var tail=0
        fun paused()=pages>=maxPages || now()-start>=budgetMillis || now()<start
        for(domain in SyncV2Schema.domains) {
            // Check time BEFORE observing each domain, including completed domains.
            // A business write at that boundary must enter the same durable fixed pass.
            if(paused())return WindowProgress(rows,tail,pages,true)
            // Completed durable domains do not consume pages: counting them again
            // would starve later domains in a one-page foreground window.
            val stamp=state().body.getString("businessStamp")
            if(completedScan(domain.table,"p1-local-v1",stamp))continue
            while(true) {
                val page=scanPage(domain.table);rows+=page.rows;pages++
                if(page.complete)break
                if(paused())return WindowProgress(rows,tail,pages,true)
            }
        }
        while(!paused()) {
            val n=catchUp();tail+=n;pages++
            // A drained fixed tail alone says nothing about the reconciliation's persisted page.
            // Yield with continuation when that pass (or a newer business revision) still needs work.
            if(!state().body.has("tailR1Seq")) {
                val complete=isWindowComplete();val st=state()
                if(complete && st.body.optBoolean("projectionRebuildRequired"))writer(st.version){it.body.remove("projectionRebuildRequired")}
                return WindowProgress(rows,tail,pages,!complete)
            }
        }
        return WindowProgress(rows,tail,pages,true)
    }
    /** Observe completion in one short SQLite snapshot; never acknowledge only an old tail R1.
     * A business write between state() and this snapshot also requires another window.
     */
    internal fun isWindowComplete():Boolean {
        state()
        db.beginTransaction()
        try {
            val body=readState().body
            val current=SyncV2BusinessWitness.peek(db)?.encoded()?:return false
            if(body.optString("businessStamp")!=current || body.has("tailR1Seq") || body.has("reconcileStamp") ||
                body.optString("reconciledStamp")!=current || body.optString("reconciledSemantics")!=reconciliationSemantics())return false
            val queued=db.controlQuery("SELECT COALESCE(MAX(seq),0) FROM syncv2_outbox WHERE scope=?",arrayOf(scope)).use{it.moveToFirst();it.getLong(0)}
            return queued<=body.optLong("tailSeq",0)
        } finally {db.endTransaction()}
    }
    private fun reconciliationSemantics()=SyncV2Codec.hash(JSONArray(SyncV2Schema.domains.map{binding(it,"p1-local-v1")}))
    private fun binding(domain: SyncV2Schema.Domain, capability: String): String = SyncV2Codec.hash(JSONObject()
        .put("schema",SyncV2Schema.semanticBinding(db,listOf(domain.table)+when(domain.table){"notes"->listOf("note_conflicts");"phone_articles"->listOf("article_register_provenance");"imported_books"->listOf("imported_book_chapters");else->emptyList()}))
        .put("scope",identity.scope).put("descriptor",identity.descriptorOid).put("account",identity.accountId).put("capability",capability)
        .put("businessEpoch",readState().body.optString("businessEpoch","0")))
    private fun completedScan(domainName:String,capability:String,stamp:String):Boolean {
        val domain=SyncV2Schema.domains.single{it.table==domainName};val bind=binding(domain,capability)
        return db.controlQuery("SELECT complete,binding,business_stamp FROM syncv2_scan WHERE scope=? AND domain=?",arrayOf(scope,domainName)).use{c->c.moveToFirst() && c.getInt(0)==1 && c.getString(1)==bind && c.getString(2)==stamp}
    }
    /** Stable keyset page; no OFFSET or SELECT * against article bodies. Maximum 64 records / 1 MiB. */
    fun scanPage(domainName:String,capability:String="p1-local-v1",limit:Int=64,byteBudget:Int=1048576):PageResult =
        scanPageAt(domainName,capability,limit,byteBudget,null)
    private fun scanPageAt(domainName:String,capability:String,limit:Int,byteBudget:Int,targetStamp:String?):PageResult {
        val initial=state();require(limit in 1..64 && byteBudget in 262144..1048576)
        val target=targetStamp?:initial.body.getString("businessStamp")
        val domain=SyncV2Schema.domains.single{it.table==domainName};val bind=binding(domain,capability)
        val reusable=db.controlQuery("SELECT complete,binding,business_stamp FROM syncv2_scan WHERE scope=? AND domain=?",arrayOf(scope,domainName)).use {
            it.moveToFirst() && it.getInt(0)==1 && it.getString(1)==bind && it.getString(2)==target
        }
        if(reusable)return PageResult(domainName,0,0,true,false)
        return writer {st->
            val cp=db.controlQuery("SELECT binding,r0,page_key,complete,business_stamp FROM syncv2_scan WHERE scope=? AND domain=?",arrayOf(scope,domainName)).use {c->
                if(c.moveToFirst())listOf(c.getString(0),c.getString(1),c.getString(2),c.getInt(3).toString(),c.getString(4))else null
            }
            val oldStamp=cp?.let{SyncV2BusinessWitness.decode(it[4])};val wanted=SyncV2BusinessWitness.decode(target)?:SyncV2Codec.fail("business_witness_unavailable")
            val restart=cp==null || cp[0]!=bind || oldStamp==null || !oldStamp.sameSource(wanted) || SyncV2Codec.d64(oldStamp.revision)>SyncV2Codec.d64(wanted.revision) || (cp[3]=="1" && cp[4]!=target)
            if(restart)db.execSQL("INSERT OR REPLACE INTO syncv2_scan(scope,domain,binding,r0,r1,page_key,tail_seq,complete,business_stamp) VALUES(?,?,?,?,NULL,'',0,0,?)",arrayOf(scope,domainName,bind,st.revision,target))
            else if(cp!![3]=="1")return@writer PageResult(domainName,0,0,true,false)
            val last=if(restart)""else cp!![2]
            val ids=sourceKeys(domain,last,limit+1)
            var rows=0;var bytes=0;var keyAt=last;var exhausted=true
            for(id in ids) {
                if(rows==limit){exhausted=false;break}
                val value=db.controlQuery("SELECT ${domain.key} AS projection_key,${columns(domain)} FROM `${domain.table}` WHERE (${domain.key})=?",arrayOf(id)).use{c->if(c.moveToFirst())readRecord(domain,c)else null}
                val size=value?.let{SyncV2Codec.utf8(it.toString()).size}?:0
                // C7 25.1: long note rows are legitimate local data; the row-size gate for them is
                // the 2 MiB sealed-note budget, not the ordinary page byte budget.
                if(size.toLong()>(if(domain.table=="notes")SyncV2NoteSegments.maxNoteBytes+65536 else byteBudget.toLong()))SyncV2Codec.fail("object_too_large")
                if(rows>0 && bytes+size>byteBudget){exhausted=false;break}
                if(value!=null)project(domain,id,value,st.revision,st.body.optJSONObject("appliedAccount")?:st.body.optJSONObject("account"),true,st.body,st.body)
                else markRemoved(domain,id,st.revision)
                keyAt=id;rows++;bytes+=size
            }
            fault("before-checkpoint")
            db.execSQL("UPDATE syncv2_scan SET page_key=?,complete=?,r1=? WHERE scope=? AND domain=?",arrayOf<Any?>(keyAt,if(exhausted)1 else 0,if(exhausted)st.revision else null,scope,domainName))
            fault("after-checkpoint");PageResult(domainName,rows,bytes,exhausted,false)
        }
    }
    /** Include prior candidate keys so physical deletions during a closed/missing witness are seen. */
    private fun sourceKeys(d:SyncV2Schema.Domain,after:String,limit:Int):List<String> = db.controlQuery(
        "SELECT projection_key FROM (SELECT (${d.key}) AS projection_key FROM `${d.table}` UNION SELECT logical_id AS projection_key FROM syncv2_candidates WHERE scope=? AND kind=?) WHERE projection_key COLLATE BINARY > ? ORDER BY projection_key COLLATE BINARY LIMIT ?",arrayOf<Any>(scope,d.kind,after,limit)).use{c->buildList{while(c.moveToNext())add(c.getString(0))}}
    /** Outbox is now a DERIVED projection queue, written only by workers after business commit.
     * A fixed reconciliation pass handles the compact witness's lack of per-entity history.
     */
    fun catchUp(limit:Int=64):Int {
        require(limit in 1..64)
        var before=state()
        val max=db.controlQuery("SELECT COALESCE(MAX(seq),0) FROM syncv2_outbox WHERE scope=?",arrayOf(scope)).use{it.moveToFirst();it.getLong(0)}
        if(before.body.has("tailR1Seq") || max>before.body.optLong("tailSeq",0))return drainDerivedQueue(limit)
        val current=before.body.getString("businessStamp")
        val semantics=reconciliationSemantics()
        if(!before.body.has("reconcileStamp") && before.body.optString("reconciledStamp")==current && before.body.optString("reconciledSemantics")==semantics)return 0
        if(!before.body.has("reconcileStamp"))writer{it.body.put("reconcileStamp",current).put("reconcileSemantics",semantics).put("reconcileDomain",0)}
        before=state();val target=before.body.getString("reconcileStamp")
        var index=before.body.getInt("reconcileDomain")
        while(index<SyncV2Schema.domains.size) {
            val page=scanPageAt(SyncV2Schema.domains[index].table,"p1-local-v1",limit,1048576,target)
            if(page.complete){index++;val next=index;writer{it.body.put("reconcileDomain",next)}}
            if(page.rows>0)return page.rows
        }
        writer{it.body.put("reconciledStamp",target).put("reconciledSemantics",it.body.getString("reconcileSemantics")).remove("reconcileStamp");it.body.remove("reconcileDomain");it.body.remove("reconcileSemantics")}
        // A newer business revision remains for the next fixed pass; never claim it was applied.
        return 0
    }
    private fun drainDerivedQueue(limit:Int):Int = writer {st->
        val max=db.controlQuery("SELECT COALESCE(MAX(seq),0) FROM syncv2_outbox WHERE scope=?",arrayOf(scope)).use{it.moveToFirst();it.getLong(0)}
        val end=if(st.body.has("tailR1Seq"))st.body.getLong("tailR1Seq")else max.also{st.body.put("tailR1Seq",it)}
        val entries=mutableListOf<Array<Any>>()
        db.controlQuery("SELECT seq,revision,table_name,logical_id,base_state FROM syncv2_outbox WHERE scope=? AND seq>? AND seq<=? ORDER BY seq LIMIT ?",arrayOf<Any>(scope,st.body.optLong("tailSeq",0),end,limit)).use {c->
            while(c.moveToNext())entries.add(arrayOf(c.getLong(0),c.getString(1),c.getString(2),c.getString(3),c.getBlob(4)))
        }
        for(e in entries) {
            val table=e[2] as String
            val d=SyncV2Schema.domains.first{it.table==when(table){"note_conflicts"->"notes";"imported_book_chapters"->"imported_books";"article_register_provenance"->"phone_articles";else->table}}
            val id=e[3] as String;val eventAccount=open("state",e[4] as ByteArray).optJSONObject("account")
            db.controlQuery("SELECT ${d.key} AS projection_key,${columns(d)} FROM `${d.table}` WHERE (${d.key})=?",arrayOf(id)).use{c->
                if(c.moveToFirst())project(d,id,readRecord(d,c),st.revision,eventAccount,ownerBody=st.body)else markRemoved(d,id,st.revision)
            }
            fault("after-tail-entry");st.body.put("tailSeq",e[0])
        }
        if(entries.isEmpty() || entries.last()[0] as Long==end)st.body.remove("tailR1Seq")
        refreshPending(st.body);entries.size
    }
    private fun columns(d: SyncV2Schema.Domain): String = when(d.table) {
        // guid is valid wire metadata but is not stored by PhoneArticleEntity.
        "phone_articles" -> SyncV2Business.articleFields.filterNot{it in setOf("guid","rssIndexRetired","bookIndexRetired","registerSources")}.sorted().joinToString(","){"`$it`"}
        "phone_rss_sources" -> (SyncV2Business.sourceFields-setOf("sourceId")).sorted().joinToString(","){"`$it`"}
        "imported_books", "imported_book_reading" -> "stateJson"
        "notes" -> SyncV2Business.noteFields.sorted().joinToString(","){"`$it`"}
        "note_folders" -> SyncV2Business.folderFields.sorted().joinToString(","){"`$it`"}
        "note_assets" -> "assetId,mimeType,displayName,createdAt,deleted,deletedAt"
        else -> SyncV2Codec.fail("unsupported_feature")
    }
    private fun readRecord(d:SyncV2Schema.Domain,c:Cursor):JSONObject {
        val raw=readStoredRecord(d,c);val id=c.getString(0)
        val binding=db.controlQuery("SELECT body FROM syncv2_p5_bindings WHERE scope=? AND entity_key=?",arrayOf(scope,identity.entity(d.kind,id))).use{if(it.moveToFirst())accountOpen("binding",it.getBlob(0))else null}?:return raw
        val footprint=binding.optJSONObject("localValue")?:return raw
        val value=JSONObject(binding.getJSONObject("value").toString())
        for(field in raw.keys())if(SyncV2LocalJson.canonical(raw.opt(field))!=SyncV2LocalJson.canonical(footprint.opt(field)))value.put(field,raw.get(field))
        return value
    }
    private fun readStoredRecord(d: SyncV2Schema.Domain,c: Cursor): JSONObject {
        if(d.table=="imported_books" || d.table=="imported_book_reading") return JSONObject(c.getString(c.getColumnIndexOrThrow("stateJson")))
        val out=JSONObject()
        val bool=setOf("independentSaved","favoriteSaved","watchLaterSaved","deleted","isRead","isPinned","pinned","useOriginalContent","continuePlaybackInBackground")
        for(i in 1 until c.columnCount) {
            val name=c.getColumnName(i)
            out.put(name,when {
                c.isNull(i) -> JSONObject.NULL
                name in bool -> c.getInt(i)!=0
                c.getType(i)==Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                c.getType(i)==Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                else -> c.getString(i)
            })
        }
        if(d.table=="note_assets") {
            // Preserve identity; source paths, byte hashes and note linkage stay in encrypted provenance.
            return JSONObject().put("assetId",out.get("assetId")).put("mime",out.get("mimeType")).put("originalFilename",out.get("displayName"))
                .put("updatedAt",out.get("createdAt")).put("sourceDeviceId","").put("deleted",out.get("deleted")).put("deletedAt",out.get("deletedAt"))
        }
        if(d.table=="notes") {
            val records=JSONArray()
            db.controlQuery("SELECT conflictId,noteId,baseMarkdown,localMarkdown,remoteMarkdown,remoteDeviceId,createdAt,resolvedAt FROM note_conflicts WHERE noteId=?",arrayOf(out.getString("noteId"))).use { rows ->
                while(rows.moveToNext()) {
                    if(records.length()==1024)SyncV2Codec.fail("object_too_large")
                    val r=JSONObject();for(i in 0 until rows.columnCount)r.put(rows.getColumnName(i),if(i>=6)rows.getLong(i)else rows.getString(i));records.put(r)
                }
            }
            out.put("noteConflicts",SyncV2NoteConflicts.phone(records,out.getString("noteId")))
        }
        return out
    }
    private fun project(d: SyncV2Schema.Domain,id: String,value: JSONObject,revision: String,eventAccount: JSONObject?=null,enqueue:Boolean=false,sourceState:JSONObject?=null,ownerBody:JSONObject?=null,forkEdits:Boolean=true,forceFork:Boolean=false) {
        var id2=id; var value2=value
        val owner=ownerBody?:readState().body
        if(d.kind=="note"&&forkEdits) owner.optJSONObject("noteIsolation")?.let{iso->
            val source=identity.entity(d.kind,id)
            if(iso.has(source)) {
                val sourceHash=SyncV2Codec.hash(value)
                if(owner.optJSONObject("noteForkSources")?.optString(source)==sourceHash)return
                // An unchanged local cache is not an edit. Reconciliation must not create
                // a phantom fork merely because the remote row became isolated.
                val prior=db.controlQuery("SELECT meta,state FROM syncv2_candidates WHERE scope=? AND entity_key=?",arrayOf(scope,source)).use{c->if(c.moveToFirst())open("meta",c.getBlob(0)) to open("candidate-state",c.getBlob(1))else null}
                if(prior!=null&&!forceFork) {
                    val incoming=SyncV2Business.split(d.kind,id,source,value,null)
                    if(SyncV2Codec.canonical(prior.first.getJSONObject("payload"))==SyncV2Codec.canonical(incoming.meta.getJSONObject("payload"))&&
                        SyncV2Codec.canonical(prior.second)==SyncV2Codec.canonical(incoming.state))return
                }
                // R2: an isolated note keeps carrying its remote row verbatim; any local edit is
                // re-homed under a fresh logical id so both copies survive (never the isolated row).
                val forkId=db.controlQuery("SELECT fork_logical_id FROM syncv2_note_forks WHERE scope=? AND entity_key=?",arrayOf(scope,source)).use{if(it.moveToFirst())it.getString(0) else null}
                    ?:("syncv2-fork-"+SyncV2Codec.hex(SyncV2Codec.random(16)))
                db.execSQL("INSERT OR REPLACE INTO syncv2_note_forks VALUES(?,?,?)",arrayOf(scope,source,forkId))
                val forked=JSONObject(value.toString()).put("noteId",forkId)
                    .put("noteConflicts",SyncV2NoteConflicts.phone(JSONArray(),forkId))
                forked.put("title",forkTitle(value.getString("title"),forkId))
                // Use the same Room upsert as ordinary local notes. Projection journaling keeps
                // the original edit pending until its fork is actually published.
                SyncV2PhoneApply(db).upsert("notes",listOf("noteId"),forked)
                val sources=owner.optJSONObject("noteForkSources")?:JSONObject().also{owner.put("noteForkSources",it)}
                sources.put(source,sourceHash) // Survives tracking cleanup; unchanged observations are not new edits.
                id2=forkId;value2=forked
            }
        }
        if(d.kind=="note"&&value2.optBoolean("deleted"))db.execSQL("DELETE FROM syncv2_note_forks WHERE scope=? AND fork_logical_id=?",arrayOf(scope,id2))
        val entity=identity.entity(d.kind,id2)
        val library=db.controlQuery("SELECT library_id FROM syncv2_control WHERE id=1").use{it.moveToFirst();it.getString(0)}
        val prior=db.controlQuery("SELECT entity_key FROM syncv2_identity_map WHERE library_id=? AND repo_id=? AND kind=? AND logical_id=?",arrayOf(library,identity.repoId,d.kind,id2)).use{if(it.moveToFirst())it.getString(0) else null}
        if(prior!=null && prior!=entity) SyncV2Codec.fail("identity_conflict")
        fault("before-map")
        db.execSQL("INSERT OR IGNORE INTO syncv2_identity_map VALUES(?,?,?,?,?,2)",arrayOf(library,identity.repoId,d.kind,id2,entity))
        fault("after-map")
        // Historical importedAt is not proof of first personal-library addition. Preserve it encrypted.
        val objects=SyncV2Business.split(d.kind,id2,entity,value2,null)
        val installed=db.controlQuery("SELECT body FROM syncv2_p5_bindings WHERE scope=? AND entity_key=?",arrayOf(scope,entity)).use{if(it.moveToFirst())accountOpen("binding",it.getBlob(0))else null}
        installed?.optJSONObject("meta")?.optString("addedAtSource")?.takeIf{it in setOf("original","unknown")}?.let{objects.meta.put("addedAtSource",it)}
        val provenance=provenance(d,if(d.kind=="note"&&id2!=id)"" else id)
        val sourceHash=SyncV2Codec.hash(JSONObject().put("meta",objects.meta).put("state",objects.state).put("provenance",provenance))
        val previous=db.controlQuery("SELECT source_hash FROM syncv2_candidates WHERE scope=? AND entity_key=?",arrayOf(scope,entity)).use{if(it.moveToFirst())it.getString(0) else null}
        if(previous==sourceHash) return
        accountCatalog.put("candidate",JSONObject().put("meta",objects.meta).put("state",objects.state))
        val base=eventAccount?.let { JSONObject().put("accountOid",it.getString("oid")).put("generation",it.getString("generation")) }
        if(base!=null) {
            db.execSQL("INSERT OR IGNORE INTO syncv2_edit_base SELECT scope,entity_key,?,meta,state FROM syncv2_candidates WHERE scope=? AND entity_key=?",arrayOf(seal("edit-base",base),scope,entity))
            if(previous==null)db.execSQL("INSERT OR IGNORE INTO syncv2_edit_base VALUES(?,?,?,?,?)",arrayOf(scope,entity,seal("edit-base",base),seal("meta",JSONObject().put("p5Absent",true)),seal("candidate-state",JSONObject())))
        }
        val metaBytes=seal("meta",objects.meta);val stateBytes=seal("candidate-state",objects.state)
        db.execSQL("INSERT OR REPLACE INTO syncv2_candidates VALUES(?,?,?,?,?,?,?,?)",arrayOf(scope,entity,d.kind,id2,revision,metaBytes,stateBytes,sourceHash))
        db.execSQL("INSERT OR REPLACE INTO syncv2_provenance VALUES(?,?,?)",arrayOf(scope,entity,seal("provenance",provenance)))
        SyncV2CandidateTree(db,scope).update(JSONObject().put("entityKey",entity).put("metaCandidateSha256",SyncV2Codec.hex(SyncV2Codec.sha(metaBytes))).put("stateCandidateSha256",SyncV2Codec.hex(SyncV2Codec.sha(stateBytes))).put("sourceHash",sourceHash).put("rowSequence",revision).put("addedAtMillis",0))
        fault("before-log")
        db.execSQL("INSERT OR IGNORE INTO syncv2_change_log VALUES(?,?,?)",arrayOf(scope,revision,entity))
        fault("after-log")
        if(enqueue)db.execSQL("INSERT INTO syncv2_outbox(scope,revision,table_name,logical_id,operation,base_state) VALUES(?,?,?,?,?,?)",arrayOf(scope,revision,d.table,id2,"OBSERVE",seal("state",sourceState?:JSONObject())))
    }
    private fun forkTitle(title:String,forkId:String):String {
        val existing=db.controlQuery("SELECT title FROM notes WHERE noteId=?",arrayOf(forkId)).use{if(it.moveToFirst())it.getString(0)else null}
        if(existing!=null)return existing
        val base=title+" 副本";var next=base;var number=2
        while(db.controlQuery("SELECT 1 FROM notes WHERE title=? LIMIT 1",arrayOf(next)).use{it.moveToFirst()})next=base+" "+number++
        return next
    }
    private fun pruneNoteForks() {
        val forks=db.controlQuery("SELECT entity_key,fork_logical_id FROM syncv2_note_forks WHERE scope=?",arrayOf(scope)).use{c->buildList{while(c.moveToNext())add(c.getString(0) to c.getString(1))}}
        for((entity,id)in forks) {
            val deleted=db.controlQuery("SELECT deleted FROM notes WHERE noteId=?",arrayOf(id)).use{!it.moveToFirst()||it.getInt(0)!=0}
            val published=accountBinding(identity.entity("note",id))!=null&&!SyncV2NoteWiring.outboxOwned(db,scope,identity.entity("note",id))
            if(deleted||published)db.execSQL("DELETE FROM syncv2_note_forks WHERE scope=? AND entity_key=?",arrayOf(scope,entity))
        }
    }
    /** Status surface for the later UI: the forked logical id of an isolated note, when present. */
    fun noteFork(logicalId:String):String? {
        val entity=identity.entity("note",logicalId);ensureActive()
        return db.controlQuery("SELECT fork_logical_id FROM syncv2_note_forks WHERE scope=? AND entity_key=?",arrayOf(scope,entity)).use{if(it.moveToFirst())it.getString(0) else null}
    }
    /** R2 explicit fork entry: re-homes the current Room content of an isolated note under a new id. */
    fun forkIsolatedNote(entity:String,logicalId:String,nowMillis:Long):JSONObject {
        ensureActive()
        if(readState().body.optJSONObject("noteIsolation")?.has(entity)!=true)SyncV2Codec.fail("invalid_schema")
        val domain=SyncV2Schema.domains.single{it.table=="notes"}
        val row=db.controlQuery("SELECT noteId AS projection_key,${columns(domain)} FROM notes WHERE noteId=?",arrayOf(logicalId)).use{c->
            if(!c.moveToFirst())SyncV2Codec.fail("invalid_schema")
            readRecord(domain,c)
        }
        val st=state();val revision=SyncV2Codec.next(st.revision)
        val forkId=noteFork(logicalId)?:("syncv2-fork-"+SyncV2Codec.hex(SyncV2Codec.random(16)))
        accountWriter(st.version){ current ->
            db.execSQL("UPDATE syncv2_state SET local_revision=? WHERE scope=?",arrayOf(revision,scope))
            current.body.put("unknownEditBase",true)
            db.execSQL("INSERT OR REPLACE INTO syncv2_note_forks VALUES(?,?,?)",arrayOf(scope,entity,forkId))
            val value=JSONObject(row.toString()).put("noteId",forkId).put("title",forkTitle(row.getString("title"),forkId))
                .put("noteConflicts",SyncV2NoteConflicts.phone(JSONArray(),forkId))
            SyncV2PhoneApply(db).upsert("notes",listOf("noteId"),value)
            project(domain,forkId,value,revision,current.body.optJSONObject("account"),true,current.body,current.body)
            val sources=current.body.optJSONObject("noteForkSources")?:JSONObject().also{current.body.put("noteForkSources",it)}
            sources.put(entity,SyncV2Codec.hash(row))

        }
        return JSONObject().put("logicalId",forkId).put("entityKey",identity.entity("note",forkId))
    }
    /** Bounded encrypted source sidecars retain note conflicts and legacy register ancestry verbatim.
     * These are local candidates, not new wire payload extensions.
     */
    private fun provenance(d: SyncV2Schema.Domain,id:String):JSONObject {
        val result=JSONObject()
        val statements=when(d.table) {
            "notes"->listOf("conflicts" to "SELECT * FROM note_conflicts WHERE noteId=? ORDER BY conflictId LIMIT 1025")
            "phone_articles"->listOf("register" to "SELECT * FROM article_register_provenance WHERE articleId=?")
            "note_assets"->listOf("asset" to "SELECT * FROM note_assets WHERE assetId=?")
            "imported_books"->listOf("chapters" to "SELECT * FROM imported_book_chapters WHERE bookId=? ORDER BY chapterIndex LIMIT 257")
            else->emptyList()
        }
        for((name,sql)in statements) {
            val rows=JSONArray()
            db.controlQuery(sql,arrayOf(id)).use {c->while(c.moveToNext()) {
                if(rows.length()==(if(name=="conflicts")1024 else 256))SyncV2Codec.fail("object_too_large")
                val value=JSONObject();for(i in 0 until c.columnCount)value.put(c.getColumnName(i),if(c.isNull(i))JSONObject.NULL else if(c.getType(i)==Cursor.FIELD_TYPE_INTEGER)c.getLong(i) else c.getString(i))
                rows.put(value)
            }}
            result.put(name,rows)
        }
        if(SyncV2Codec.utf8(SyncV2Codec.canonical(result)).size>262144)SyncV2Codec.fail("object_too_large")
        return result
    }
    fun sourceProvenance(kind:String,id:String):JSONObject? {
        ensureActive()
        return db.controlQuery("SELECT body FROM syncv2_provenance WHERE scope=? AND entity_key=?",arrayOf(scope,identity.entity(kind,id))).use{if(it.moveToFirst())open("provenance",it.getBlob(0))else null}
    }
    private fun markRemoved(d: SyncV2Schema.Domain,id: String,revision: String) {
        val entity=identity.entity(d.kind,id)
        accountCatalog.remove("candidate",entity)
        val prior=db.controlQuery("SELECT meta,state FROM syncv2_candidates WHERE scope=? AND entity_key=?",arrayOf(scope,entity)).use{if(it.moveToFirst())it.getBlob(0) to it.getBlob(1) else null}
        if(prior!=null && open("candidate-state",prior.second).optBoolean("projectionRemoved"))return
        val account=readState().body.optJSONObject("appliedAccount")?:readState().body.optJSONObject("account")
        if(prior!=null && account!=null)db.execSQL("INSERT OR IGNORE INTO syncv2_edit_base VALUES(?,?,?,?,?)",arrayOf(scope,entity,seal("edit-base",JSONObject().put("accountOid",account.getString("oid")).put("generation",account.getString("generation"))),prior.first,prior.second))
        val meta=prior?.first?:seal("meta",JSONObject().put("version",2).put("entityKey",entity).put("kind",d.kind).put("logicalId",id).put("addedAtSource","unknown").put("requiredFeatures",JSONArray()).put("payload",JSONObject()))
        val value=(prior?.let{open("candidate-state",it.second)}?:JSONObject().put("version",2).put("entityKey",entity).put("requiredFeatures",JSONArray()).put("payload",JSONObject()).put("noteSegments",JSONArray())).put("projectionRemoved",true)
        val sealed=seal("candidate-state",value);val hash=SyncV2Codec.hash(value)
        val library=db.controlQuery("SELECT library_id FROM syncv2_control WHERE id=1").use{it.moveToFirst();it.getString(0)}
        db.execSQL("INSERT OR IGNORE INTO syncv2_identity_map VALUES(?,?,?,?,?,2)",arrayOf(library,identity.repoId,d.kind,id,entity))
        db.execSQL("INSERT OR REPLACE INTO syncv2_candidates VALUES(?,?,?,?,?,?,?,?)",arrayOf(scope,entity,d.kind,id,revision,meta,sealed,hash))
        SyncV2CandidateTree(db,scope).update(JSONObject().put("entityKey",entity).put("metaCandidateSha256",SyncV2Codec.hex(SyncV2Codec.sha(meta))).put("stateCandidateSha256",SyncV2Codec.hex(SyncV2Codec.sha(sealed))).put("sourceHash",hash).put("rowSequence",revision).put("addedAtMillis",0))
        db.execSQL("INSERT OR IGNORE INTO syncv2_change_log VALUES(?,?,?)",arrayOf(scope,revision,entity))
    }
    fun candidate(kind: String,id: String): Pair<JSONObject,JSONObject>? {
        ensureActive()
        return db.controlQuery("SELECT meta,state FROM syncv2_candidates WHERE scope=? AND entity_key=?",arrayOf(scope,identity.entity(kind,id))).use{c->
            if(c.moveToFirst())open("meta",c.getBlob(0)) to open("candidate-state",c.getBlob(1)) else null
        }
    }
}
