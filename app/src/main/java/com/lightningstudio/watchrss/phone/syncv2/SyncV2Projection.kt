package com.lightningstudio.watchrss.phone.syncv2

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lightningstudio.watchrss.phone.BuildConfig
import org.json.JSONArray
import org.json.JSONObject

/** One SQLite writer; content reads and account authentication occur outside this class's transactions.
 * Public product writes remain closed: this adapter only creates local projection candidates.
 */
internal class SyncV2Projection(
    private val db: SupportSQLiteDatabase, private val identity: SyncV2Identity,
    localKey: ByteArray, private val enabled: Boolean = BuildConfig.SYNC_OBJECTS_V2_LOCAL_PROJECTION,
    private val fault: (String) -> Unit = {}
) {
    private val key=localKey.copyOf()
    val scope=identity.scopeJson
    init { require(key.size==32) }
    private fun gate() { if(!enabled) SyncV2Codec.fail("feature_disabled") }
    private fun ensureActive() {
        gate()
        val on=db.query("SELECT enabled FROM syncv2_control WHERE id=1 AND active_scope=?",arrayOf(scope)).use { it.moveToFirst() && it.getInt(0)==1 }
        if(!on) SyncV2Codec.fail("feature_disabled")
    }
    private fun requireWorkerContext(){if(db.inTransaction())SyncV2Codec.fail("projection_in_business_transaction")}
    fun install() {
        requireWorkerContext();gate(); SyncV2Schema.install(db)
        db.beginTransaction()
        try {
            val prior=db.query("SELECT descriptor_pin,account_id,identity FROM syncv2_state WHERE scope=?",arrayOf(scope)).use { c ->
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
                    .put("applied",JSONObject()).put("activeGeneration",JSONObject.NULL).put("appliedAccount",JSONObject.NULL).put("keepDiscardedLocalChanges7Days",true)
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
    private fun readState():State = db.query("SELECT state_version,local_revision,body FROM syncv2_state WHERE scope=?",arrayOf(scope)).use { c ->
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
        val base=st.body.optJSONObject("account")
        if(st.body.isNull("editBase")) {
            if(continuous && base!=null && !st.body.optBoolean("unknownEditBase"))st.body.put("editBase",JSONObject().put("accountOid",base.getString("oid")).put("generation",base.getString("generation")))
            else st.body.put("unknownEditBase",true)
        }
        // Without an authenticated per-operation base, a post-rollback legacy/local write is unknown.
        if(!continuous || st.body.getString("rollbackGeneration")!="0")st.body.put("unknownEditBase",true)
        st.body.put("businessStamp",stamp.encoded())
        refreshPending(st.body)
        db.execSQL("UPDATE syncv2_state SET state_version=?,local_revision=?,body=? WHERE scope=? AND state_version=?",arrayOf(following,revision,seal("state",st.body),scope,st.version))
        if(db.query("SELECT changes()").use{it.moveToFirst();it.getInt(0)}!=1)SyncV2Codec.fail("choice_stale")
    }
    private fun <T> writer(expected: String?=null, body: (State)->T): T {
        state() // Consume only the small business witness, outside the projection transaction.
        db.beginTransaction() // Android uses an immediate/exclusive SQLite writer, never a read-deferred txn.
        var started=false
        try {
            val old=readState(); val following=SyncV2Codec.next(old.version) // before callbacks and fault hooks
            if(expected!=null && old.version!=expected) SyncV2Codec.fail("choice_stale")
            if(old.body.optString("businessStamp")!=SyncV2BusinessWitness.read(db).encoded())SyncV2Codec.fail("choice_stale")
            started=true
            fault("begin")
            val result=body(old)
            fault("before-state")
            db.execSQL("UPDATE syncv2_state SET state_version=?,body=? WHERE scope=? AND state_version=?",arrayOf(following,seal("state",old.body),scope,old.version))
            if(db.query("SELECT changes()").use { it.moveToFirst(); it.getInt(0) }!=1) SyncV2Codec.fail("choice_stale")
            fault("before-commit"); db.setTransactionSuccessful(); return result
        } finally { db.endTransaction(); if(started)fault("after-transaction") }
    }
    /** Authenticated-window boundary. Caller must finish HeadPins verification before this call. */
    private fun authenticateHeads(observed:JSONObject,headers:List<JSONObject>,before:State,verifyHeader:(JSONObject)->JSONObject,
        readHeader:((String)->JSONObject)?):SyncV2HeadPins.Proof {
        val upgraded=before.body.optString("headerAuthentication")==SyncV2HeadPins.schema
        val saved=before.body.optJSONArray("knownControlHeaders")?:JSONArray()
        val accountHeaders=before.body.optJSONArray("knownAccountParents")?:JSONArray()
        val known=(0 until saved.length()).map{saved.getJSONObject(it)}+(0 until accountHeaders.length()).map{accountHeaders.getJSONObject(it).getJSONObject("header")}
        val proof=SyncV2HeadPins.authenticate(identity,observed,headers,if(upgraded)before.body.optJSONObject("account")else null,
            verifyHeader,readHeader,if(upgraded)before.body.optJSONObject("knownAuthors")?:JSONObject()else JSONObject(),known)
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
            if(st.body.isNull("editBase") && st.revision!="0") {
                val oldest=db.query("SELECT base_state FROM syncv2_outbox WHERE scope=? ORDER BY seq LIMIT 1",arrayOf(scope)).use{if(it.moveToFirst())open("state",it.getBlob(0)).optJSONObject("account") else null}
                if(oldest!=null) st.body.put("editBase",JSONObject().put("accountOid",oldest.getString("oid")).put("generation",oldest.getString("generation")))
                else st.body.put("unknownEditBase",true)
            }
            refreshPending(st.body)
            val base=st.body.optJSONObject("editBase")
            if(base!=null && SyncV2Codec.d64(base.getString("generation"))<SyncV2Codec.d64(observed.getString("generation"))) pending(st.body,observed.getString("generation"))
    }
    /** P2 owns only current authenticated control content; never sends business applied ACK. */
    fun installCloudIndex(window:JSONObject,headers:List<JSONObject>,verified:SyncV2ControlClosure.Verified,
        verifyHeader:(JSONObject)->JSONObject,serverRevision:String?=null,readHeader:((String)->JSONObject)?=null) {
        requireWorkerContext();val observed=validAccount(window);val before=state()
        if(verified.commitOid!=observed.getString("oid"))SyncV2Codec.fail("hash_mismatch")
        val proof=authenticateHeads(observed,headers,before,verifyHeader,readHeader)
        writer(before.version) { st ->
            observeInto(st,observed,proof.path);retainControlHeaders(st.body,proof)
            db.execSQL("DELETE FROM syncv2_cloud_rows WHERE scope=?",arrayOf(scope))
            db.execSQL("DELETE FROM syncv2_cloud_objects WHERE scope=?",arrayOf(scope))
            for((oid,bytes)in verified.objects) db.execSQL("INSERT INTO syncv2_cloud_objects VALUES(?,?,?)",arrayOf(scope,oid,bytes))
            for(value in verified.rows) db.execSQL("INSERT INTO syncv2_cloud_rows VALUES(?,?,?)",arrayOf(scope,value.getJSONObject("row").getString("entityKey"),seal("cloud-row",value)))
            st.body.put("metadataAccount",observed).put("metadataStatus","metadata-ready").put("metadataSchema",SyncV2HeadPins.schema).put("metadataRows",verified.rows.size)
            if(serverRevision!=null){SyncV2Codec.d64(serverRevision);st.body.put("metadataServerRevision",serverRevision)}
            // pendingChoice and all local edits remain; P1 product apply gate is not relaxed here.
            fault("cloud-index-ready")
        }
    }
    fun reuseCloudIndex(window:JSONObject,serverRevision:String):Boolean {
        val observed=validAccount(window);val before=state();val accepted=before.body.optJSONObject("account")?:return false
        val metadata=before.body.optJSONObject("metadataAccount")?:return false
        if(before.body.optString("metadataSchema")!=SyncV2HeadPins.schema || metadata.getString("oid")!=observed.getString("oid") || accepted.getString("oid")!=observed.getString("oid"))return false
        if(observed.getString("generation")!=accepted.getString("generation"))SyncV2Codec.fail("replay_detected")
        val count=db.query("SELECT COUNT(*) FROM syncv2_cloud_rows WHERE scope=?",arrayOf(scope)).use{it.moveToFirst();it.getInt(0)}
        if(count!=before.body.getInt("metadataRows"))return false
        writer(before.version) { st ->
            observeInto(st,observed,JSONArray());SyncV2Codec.d64(serverRevision)
            st.body.put("metadataAccount",observed).put("metadataServerRevision",serverRevision)
        }
        return true
    }
    fun cloudRows():List<JSONObject> {
        ensureActive();return db.query("SELECT body FROM syncv2_cloud_rows WHERE scope=? ORDER BY entity_key",arrayOf(scope)).use { c -> buildList {while(c.moveToNext())add(open("cloud-row",c.getBlob(0)))} }
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
        ensureActive();return db.query("SELECT oid,body FROM syncv2_cloud_objects WHERE scope=?",arrayOf(scope)).use{c->buildMap{while(c.moveToNext())put(c.getString(0),c.getBlob(1))}}
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
    data class WireIndex(val tree:SyncV2Index.Tree,val objects:Map<String,ByteArray>,val refs:List<JSONObject>)
    fun prepareWireIndex(signer:SyncV2Signing,contentKey:ByteArray,keyVersion:Long):WireIndex {
        val before=state();val rows=mutableListOf<JSONObject>();val objects=linkedMapOf<String,ByteArray>();val refs=mutableListOf<JSONObject>()
        // At most one candidate is decrypted at a time. No article/book body SELECT occurs here.
        val candidates=db.query("SELECT entity_key,kind,logical_id,meta,state,source_hash FROM syncv2_candidates WHERE scope=? ORDER BY entity_key",arrayOf(scope)).use { c ->
            buildList {while(c.moveToNext())add(arrayOf<Any>(c.getString(0),c.getString(1),c.getString(2),c.getBlob(3),c.getBlob(4),c.getString(5)))}
        }
        val prepared=mutableListOf<Pair<String,JSONObject>>()
        for(c in candidates) {
            val entity=c[0] as String;val hash=c[5] as String
            val meta=open("meta",c[3] as ByteArray);val state=open("candidate-state",c[4] as ByteArray)
            if(state.has("projectionRemoved"))SyncV2Codec.fail("unsupported_feature")
            // Revalidate persisted candidates before reusing a pre-upgrade sealed cache.
            SyncV2Business.join(c[1] as String,meta,state)
            val prior=db.query("SELECT body FROM syncv2_wire_candidates WHERE scope=? AND entity_key=?",arrayOf(scope,entity)).use{if(it.moveToFirst())open("wire-candidate",it.getBlob(0))else null}
            val value=if(prior!=null && prior.getString("sourceHash")==hash && prior.getLong("keyVersion")==keyVersion)prior else {
                val sealedMeta=SyncV2Envelope.seal(identity,contentKey,keyVersion,"meta",SyncV2Codec.utf8(SyncV2Codec.canonical(meta)))
                val sealedState=SyncV2Envelope.seal(identity,contentKey,keyVersion,"state",SyncV2Codec.utf8(SyncV2Codec.canonical(state)))
                val body=(c[1] as String) in setOf("article","book","asset")
                val row=JSONObject().put("entityKey",entity).put("metaOid",sealedMeta.oid).put("stateOid",sealedState.oid).put("addedAtMillis",0)
                    .put("rowSequence",SyncV2Codec.next(prior?.getJSONObject("row")?.getString("rowSequence")?:"0"))
                    .put("deleted",state.getJSONObject("payload").optBoolean("deleted",false))
                    .put("bodyRevision",if(body)SyncV2Codec.hex(SyncV2Codec.random(16))else JSONObject.NULL)
                    .put("promiseId",if(body)SyncV2Codec.hex(SyncV2Codec.random(16))else JSONObject.NULL).put("recipeOid",JSONObject.NULL)
                    .put("opaqueContentId",JSONObject.NULL).put("contentBytes",JSONObject.NULL).put("requiredFeatures",JSONArray())
                JSONObject().put("sourceHash",hash).put("keyVersion",keyVersion).put("row",row)
                    .put("meta",SyncV2Codec.b64(sealedMeta.bytes)).put("state",SyncV2Codec.b64(sealedState.bytes))
                    .put("refs",SyncV2Index.refs(identity,signer,sealedState.oid))
            }
            val row=value.getJSONObject("row");rows.add(row)
            objects[row.getString("metaOid")]=SyncV2Codec.decode64(value.getString("meta"));objects[row.getString("stateOid")]=SyncV2Codec.decode64(value.getString("state"))
            val signed=value.getJSONObject("refs");refs.add(signed)
            objects[SyncV2Codec.oid("state-refs",signed)]=SyncV2Codec.utf8(SyncV2Codec.canonical(signed))
            prepared.add(entity to value)
        }
        val tree=SyncV2Index.build(identity.scope,rows);objects.putAll(tree.objects)
        writer(before.version) { _ -> for((entity,value)in prepared)db.execSQL("INSERT OR REPLACE INTO syncv2_wire_candidates VALUES(?,?,?)",arrayOf(scope,entity,seal("wire-candidate",value))) }
        return WireIndex(tree,objects,refs)
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
        val intent=db.query("SELECT body FROM syncv2_legacy_inbox WHERE scope=? AND peer=? AND version=? AND cursor=? AND route='pendingChoice'",arrayOf<Any>(scope,peer,version,cursor)).use{if(it.moveToFirst())open("legacy",it.getBlob(0))else SyncV2Codec.fail("choice_stale")}
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
    fun resolveLegacyCloud(capture: ChoiceCapture): Nothing {
        ensureActive();checkCapture(readState(),capture); SyncV2Codec.fail("feature_disabled")
    }
    private fun pending(body: JSONObject,generation: String) {
        val keys=body.optJSONObject("pendingChoice")?.optJSONArray("inboxKeys")?:JSONArray()
        body.put("pendingChoice",JSONObject().put("generation",generation).put("inboxKeys",keys))
    }
    private fun refreshPending(body: JSONObject) {
        val keys=JSONArray()
        db.query("SELECT peer,version,cursor FROM syncv2_legacy_inbox WHERE scope=? AND route='pendingChoice' AND applied=0 ORDER BY arrival,rowid",arrayOf(scope)).use { c ->
            while(c.moveToNext())keys.put(JSONArray().put(c.getString(0)).put(c.getInt(1)).put(c.getString(2)))
        }
        val editPending=(body.optBoolean("unknownEditBase") && body.getString("rollbackGeneration")!="0") || (body.optJSONObject("editBase")?.let { b -> SyncV2Codec.d64(b.getString("generation"))<SyncV2Codec.d64(body.getString("rollbackGeneration")) }?:false)
        body.put("pendingChoice",if(keys.length()>0 || editPending) JSONObject().put("generation",body.getString("rollbackGeneration")).put("inboxKeys",keys) else JSONObject.NULL)
    }
    data class LegacyInput(val peer: String,val version: Int,val cursor: String,val body: JSONObject,val removed: List<String> = emptyList(),val bodyRefs: JSONObject = JSONObject())
    /** Receives a local projection copy only. Never acknowledges business apply or changes the BLE cursor. */
    fun receiveLegacy(input: LegacyInput, affected: Set<String>?, rollbackKnown: Boolean): String {
        requireWorkerContext();ensureActive()
        if(input.version !in setOf(13,14) || input.peer.isEmpty() || input.cursor.isEmpty() || input.removed.distinct()!=input.removed || input.removed.any(input.body::has)) SyncV2Codec.fail("invalid_schema")
        val intent=JSONObject().put("body",input.body).put("removed",JSONArray(input.removed.sorted())).put("bodyRefsByEntity",input.bodyRefs)
        if(SyncV2Codec.utf8(SyncV2Codec.canonical(intent)).size>1048576)SyncV2Codec.fail("object_too_large")
        val hash=SyncV2Codec.hash(intent)
        val prior=db.query("SELECT body_hash,route FROM syncv2_legacy_inbox WHERE scope=? AND peer=? AND version=? AND cursor=?",arrayOf<Any>(scope,input.peer,input.version,input.cursor)).use { c ->
            if(c.moveToFirst())c.getString(0) to c.getString(1) else null
        }
        if(prior!=null) { if(prior.first!=hash)SyncV2Codec.fail("idempotency_conflict"); return prior.second }
        return writer { st ->
            db.query("SELECT body_hash,route FROM syncv2_legacy_inbox WHERE scope=? AND peer=? AND version=? AND cursor=?",arrayOf<Any>(scope,input.peer,input.version,input.cursor)).use { c ->
                if(c.moveToFirst()) {if(c.getString(0)!=hash)SyncV2Codec.fail("idempotency_conflict");return@writer c.getString(1)}
            }
            val ids=input.body.keys().asSequence().toSet()+input.removed
            val barrier=rollbackKnown || st.body.getString("rollbackGeneration")!="0"
            val route=if(barrier && (affected==null || ids.any{it in affected})) "pendingChoice" else "normal"
            val revision=SyncV2Codec.next(st.revision)
            val arrival=db.query("SELECT COALESCE(MAX(arrival),0)+1 FROM syncv2_legacy_inbox WHERE scope=? AND peer=? AND version=?",arrayOf<Any>(scope,input.peer,input.version)).use { it.moveToFirst();it.getLong(0) }
            fault("before-inbox")
            db.execSQL("INSERT INTO syncv2_legacy_inbox VALUES(?,?,?,?,?,?,?,?,0)",arrayOf<Any>(scope,input.peer,input.version,input.cursor,arrival,hash,seal("legacy",intent),route))
            db.execSQL("UPDATE syncv2_state SET local_revision=? WHERE scope=?",arrayOf(revision,scope))
            val stream=SyncV2Codec.canonical(JSONArray().put(input.peer).put(input.version))
            st.body.getJSONObject("received").put(stream,input.cursor)
            refreshPending(st.body); fault("after-inbox"); route
        }
    }
    private val legacyPlanner=SyncV2LegacyMerge()
    private fun legacyPlanningState():JSONObject {
        val st=readState();val rows=JSONObject();val inbox=JSONObject();val scan=JSONArray()
        db.query("SELECT kind,logical_id,meta,state FROM syncv2_candidates WHERE scope=? ORDER BY entity_key",arrayOf(scope)).use { c ->
            while(c.moveToNext()) {
                val state=open("candidate-state",c.getBlob(3));if(state.has("projectionRemoved"))SyncV2Codec.fail("merge_not_safe")
                val value=SyncV2Business.join(c.getString(0),open("meta",c.getBlob(2)),state)
                value.put("identity",c.getString(1));rows.put(identity.entity(c.getString(0),c.getString(1)),value)
            }
        }
        db.query("SELECT peer,version,cursor,body,body_hash,route,applied,arrival FROM syncv2_legacy_inbox WHERE scope=? ORDER BY rowid",arrayOf(scope)).use { c ->
            while(c.moveToNext()) {
                val value=open("legacy",c.getBlob(3))
                if(SyncV2Codec.hash(value)!=c.getString(4))SyncV2Codec.fail("merge_not_safe")
                val ids=(value.getJSONObject("body").keys().asSequence().toList()+(0 until value.getJSONArray("removed").length()).map{value.getJSONArray("removed").getString(it)}).distinct().sorted()
                value.put("entityKeys",JSONArray(ids)).put("removedEntityKeys",value.getJSONArray("removed"))
                val intent=JSONObject().put("body",value.getJSONObject("body")).put("entityKeys",value.getJSONArray("entityKeys")).put("removedEntityKeys",value.getJSONArray("removedEntityKeys")).put("bodyRefsByEntity",value.getJSONObject("bodyRefsByEntity"))
                value.put("persistedHash",c.getString(4)).put("hash",SyncV2Codec.hash(intent)).put("route",c.getString(5)).put("applied",c.getInt(6)!=0).put("arrival",c.getLong(7))
                if(!value.has("removedEntityKeys"))value.put("removedEntityKeys",value.getJSONArray("removed"))
                inbox.put(SyncV2Codec.canonical(JSONArray().put(c.getString(0)).put(c.getInt(1)).put(c.getString(2))),value)
            }
        }
        db.query("SELECT domain,binding,r0,r1,page_key,tail_seq,complete,business_stamp FROM syncv2_scan WHERE scope=? ORDER BY domain",arrayOf(scope)).use { c ->
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
            db.query("SELECT cursor,applied FROM syncv2_legacy_inbox WHERE scope=? AND peer=? AND version=? ORDER BY arrival",arrayOf<Any>(scope,peer,version)).use { c ->while(c.moveToNext()){if(c.getInt(1)==0)break;frontier=c.getString(0)} }
            if(frontier!=null)st.body.getJSONObject("applied").put(SyncV2Codec.canonical(JSONArray().put(peer).put(version)),frontier)
            refreshPending(st.body)
            // Local idempotent proof is distinct from cloud receipts and from the old transport ACK.
            fault("legacy-merge-committed")
        }
        legacyPlanner.consume(proof)
    }
    private fun noteResolutionState(noteId:String):JSONObject {
        val st=readState();val d=SyncV2Schema.domains.single{it.table=="notes"}
        val value=db.query("SELECT noteId AS projection_key,${columns(d)} FROM notes WHERE noteId=?",arrayOf(noteId)).use {c->if(c.moveToFirst())readRecord(d,c)else SyncV2Codec.fail("notes_stale")}
        val conflicts=value.getJSONObject("noteConflicts");value.remove("noteConflicts")
        val allTexts=buildString {append(value.getString("markdown"));append(value.getString("baseMarkdown"));append(SyncV2Codec.canonical(conflicts))}
        val hasAssets=db.query("SELECT 1 FROM note_assets WHERE noteId=? LIMIT 1",arrayOf(noteId)).use{it.moveToFirst()}
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
                project(SyncV2Schema.domains.single{it.table=="notes"},capture.noteId,business,next.getString("localChangeRevision"),st.body.optJSONObject("account"),true,st.body)
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
            while(true) {
                if(paused())return WindowProgress(rows,tail,pages,true)
                val page=scanPage(domain.table);rows+=page.rows;pages++
                if(page.complete)break
            }
        }
        while(!paused()) {
            val n=catchUp();tail+=n;pages++
            // A drained fixed tail alone says nothing about the reconciliation's persisted page.
            // Yield with continuation when that pass (or a newer business revision) still needs work.
            if(!state().body.has("tailR1Seq"))return WindowProgress(rows,tail,pages,!isWindowComplete())
        }
        return WindowProgress(rows,tail,pages,true)
    }
    /** Observe completion in one short SQLite snapshot; never acknowledge only an old tail R1.
     * A business write between state() and this snapshot also requires another window.
     */
    private fun isWindowComplete():Boolean {
        state()
        db.beginTransaction()
        try {
            val body=readState().body
            val current=SyncV2BusinessWitness.peek(db)?.encoded()?:return false
            if(body.optString("businessStamp")!=current || body.has("tailR1Seq") || body.has("reconcileStamp") ||
                body.optString("reconciledStamp")!=current || body.optString("reconciledSemantics")!=reconciliationSemantics())return false
            val queued=db.query("SELECT COALESCE(MAX(seq),0) FROM syncv2_outbox WHERE scope=?",arrayOf(scope)).use{it.moveToFirst();it.getLong(0)}
            return queued<=body.optLong("tailSeq",0)
        } finally {db.endTransaction()}
    }
    private fun reconciliationSemantics()=SyncV2Codec.hash(JSONArray(SyncV2Schema.domains.map{binding(it,"p1-local-v1")}))
    private fun binding(domain: SyncV2Schema.Domain, capability: String): String = SyncV2Codec.hash(JSONObject()
        .put("schema",SyncV2Schema.semanticBinding(db,listOf(domain.table)+when(domain.table){"notes"->listOf("note_conflicts");"phone_articles"->listOf("article_register_provenance");"imported_books"->listOf("imported_book_chapters");else->emptyList()}))
        .put("scope",identity.scope).put("descriptor",identity.descriptorOid).put("account",identity.accountId).put("capability",capability)
        .put("businessEpoch",readState().body.optString("businessEpoch","0")))
    /** Stable keyset page; no OFFSET or SELECT * against article bodies. Maximum 64 records / 1 MiB. */
    fun scanPage(domainName:String,capability:String="p1-local-v1",limit:Int=64,byteBudget:Int=1048576):PageResult =
        scanPageAt(domainName,capability,limit,byteBudget,null)
    private fun scanPageAt(domainName:String,capability:String,limit:Int,byteBudget:Int,targetStamp:String?):PageResult {
        val initial=state();require(limit in 1..64 && byteBudget in 262144..1048576)
        val target=targetStamp?:initial.body.getString("businessStamp")
        val domain=SyncV2Schema.domains.single{it.table==domainName};val bind=binding(domain,capability)
        val reusable=db.query("SELECT complete,binding,business_stamp FROM syncv2_scan WHERE scope=? AND domain=?",arrayOf(scope,domainName)).use {
            it.moveToFirst() && it.getInt(0)==1 && it.getString(1)==bind && it.getString(2)==target
        }
        if(reusable)return PageResult(domainName,0,0,true,false)
        return writer {st->
            val cp=db.query("SELECT binding,r0,page_key,complete,business_stamp FROM syncv2_scan WHERE scope=? AND domain=?",arrayOf(scope,domainName)).use {c->
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
                val value=db.query("SELECT ${domain.key} AS projection_key,${columns(domain)} FROM `${domain.table}` WHERE (${domain.key})=?",arrayOf(id)).use{c->if(c.moveToFirst())readRecord(domain,c)else null}
                val size=value?.let{SyncV2Codec.utf8(it.toString()).size}?:0
                if(size>byteBudget)SyncV2Codec.fail("object_too_large")
                if(rows>0 && bytes+size>byteBudget){exhausted=false;break}
                if(value!=null)project(domain,id,value,st.revision,st.body.optJSONObject("account"),true,st.body)
                else markRemoved(domain,id,st.revision)
                keyAt=id;rows++;bytes+=size
            }
            fault("before-checkpoint")
            db.execSQL("UPDATE syncv2_scan SET page_key=?,complete=?,r1=? WHERE scope=? AND domain=?",arrayOf<Any?>(keyAt,if(exhausted)1 else 0,if(exhausted)st.revision else null,scope,domainName))
            fault("after-checkpoint");PageResult(domainName,rows,bytes,exhausted,false)
        }
    }
    /** Include prior candidate keys so physical deletions during a closed/missing witness are seen. */
    private fun sourceKeys(d:SyncV2Schema.Domain,after:String,limit:Int):List<String> = db.query(
        "SELECT projection_key FROM (SELECT (${d.key}) AS projection_key FROM `${d.table}` UNION SELECT logical_id AS projection_key FROM syncv2_candidates WHERE scope=? AND kind=?) WHERE projection_key COLLATE BINARY > ? ORDER BY projection_key COLLATE BINARY LIMIT ?",arrayOf<Any>(scope,d.kind,after,limit)).use{c->buildList{while(c.moveToNext())add(c.getString(0))}}
    /** Outbox is now a DERIVED projection queue, written only by workers after business commit.
     * A fixed reconciliation pass handles the compact witness's lack of per-entity history.
     */
    fun catchUp(limit:Int=64):Int {
        require(limit in 1..64)
        var before=state()
        val max=db.query("SELECT COALESCE(MAX(seq),0) FROM syncv2_outbox WHERE scope=?",arrayOf(scope)).use{it.moveToFirst();it.getLong(0)}
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
        val max=db.query("SELECT COALESCE(MAX(seq),0) FROM syncv2_outbox WHERE scope=?",arrayOf(scope)).use{it.moveToFirst();it.getLong(0)}
        val end=if(st.body.has("tailR1Seq"))st.body.getLong("tailR1Seq")else max.also{st.body.put("tailR1Seq",it)}
        val entries=mutableListOf<Array<Any>>()
        db.query("SELECT seq,revision,table_name,logical_id,base_state FROM syncv2_outbox WHERE scope=? AND seq>? AND seq<=? ORDER BY seq LIMIT ?",arrayOf<Any>(scope,st.body.optLong("tailSeq",0),end,limit)).use {c->
            while(c.moveToNext())entries.add(arrayOf(c.getLong(0),c.getString(1),c.getString(2),c.getString(3),c.getBlob(4)))
        }
        for(e in entries) {
            val table=e[2] as String
            val d=SyncV2Schema.domains.first{it.table==when(table){"note_conflicts"->"notes";"imported_book_chapters"->"imported_books";"article_register_provenance"->"phone_articles";else->table}}
            val id=e[3] as String;val eventAccount=open("state",e[4] as ByteArray).optJSONObject("account")
            db.query("SELECT ${d.key} AS projection_key,${columns(d)} FROM `${d.table}` WHERE (${d.key})=?",arrayOf(id)).use{c->
                if(c.moveToFirst())project(d,id,readRecord(d,c),st.revision,eventAccount)else markRemoved(d,id,st.revision)
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
    private fun readRecord(d: SyncV2Schema.Domain,c: Cursor): JSONObject {
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
            db.query("SELECT conflictId,noteId,baseMarkdown,localMarkdown,remoteMarkdown,remoteDeviceId,createdAt,resolvedAt FROM note_conflicts WHERE noteId=?",arrayOf(out.getString("noteId"))).use { rows ->
                while(rows.moveToNext()) {
                    if(records.length()==1024)SyncV2Codec.fail("object_too_large")
                    val r=JSONObject();for(i in 0 until rows.columnCount)r.put(rows.getColumnName(i),if(i>=6)rows.getLong(i)else rows.getString(i));records.put(r)
                }
            }
            out.put("noteConflicts",SyncV2NoteConflicts.phone(records,out.getString("noteId")))
        }
        return out
    }
    private fun project(d: SyncV2Schema.Domain,id: String,value: JSONObject,revision: String,eventAccount: JSONObject?=null,enqueue:Boolean=false,sourceState:JSONObject?=null) {
        val entity=identity.entity(d.kind,id)
        val library=db.query("SELECT library_id FROM syncv2_control WHERE id=1").use{it.moveToFirst();it.getString(0)}
        val prior=db.query("SELECT entity_key FROM syncv2_identity_map WHERE library_id=? AND repo_id=? AND kind=? AND logical_id=?",arrayOf(library,identity.repoId,d.kind,id)).use{if(it.moveToFirst())it.getString(0) else null}
        if(prior!=null && prior!=entity) SyncV2Codec.fail("identity_conflict")
        fault("before-map")
        db.execSQL("INSERT OR IGNORE INTO syncv2_identity_map VALUES(?,?,?,?,?,2)",arrayOf(library,identity.repoId,d.kind,id,entity))
        fault("after-map")
        // Historical importedAt is not proof of first personal-library addition. Preserve it encrypted.
        val objects=SyncV2Business.split(d.kind,id,entity,value,null)
        val provenance=provenance(d,id)
        val sourceHash=SyncV2Codec.hash(JSONObject().put("meta",objects.meta).put("state",objects.state).put("provenance",provenance))
        val previous=db.query("SELECT source_hash FROM syncv2_candidates WHERE scope=? AND entity_key=?",arrayOf(scope,entity)).use{if(it.moveToFirst())it.getString(0) else null}
        if(previous==sourceHash) return
        val base=eventAccount?.let { JSONObject().put("accountOid",it.getString("oid")).put("generation",it.getString("generation")) }
        if(base!=null) db.execSQL("INSERT OR IGNORE INTO syncv2_edit_base SELECT scope,entity_key,?,meta,state FROM syncv2_candidates WHERE scope=? AND entity_key=?",arrayOf(seal("edit-base",base),scope,entity))
        val metaBytes=seal("meta",objects.meta);val stateBytes=seal("candidate-state",objects.state)
        db.execSQL("INSERT OR REPLACE INTO syncv2_candidates VALUES(?,?,?,?,?,?,?,?)",arrayOf(scope,entity,d.kind,id,revision,metaBytes,stateBytes,sourceHash))
        db.execSQL("INSERT OR REPLACE INTO syncv2_provenance VALUES(?,?,?)",arrayOf(scope,entity,seal("provenance",provenance)))
        SyncV2CandidateTree(db,scope).update(JSONObject().put("entityKey",entity).put("metaCandidateSha256",SyncV2Codec.hex(SyncV2Codec.sha(metaBytes))).put("stateCandidateSha256",SyncV2Codec.hex(SyncV2Codec.sha(stateBytes))).put("sourceHash",sourceHash).put("rowSequence",revision).put("addedAtMillis",0))
        fault("before-log")
        db.execSQL("INSERT OR IGNORE INTO syncv2_change_log VALUES(?,?,?)",arrayOf(scope,revision,entity))
        fault("after-log")
        if(enqueue)db.execSQL("INSERT INTO syncv2_outbox(scope,revision,table_name,logical_id,operation,base_state) VALUES(?,?,?,?,?,?)",arrayOf(scope,revision,d.table,id,"OBSERVE",seal("state",sourceState?:JSONObject())))
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
            db.query(sql,arrayOf(id)).use {c->while(c.moveToNext()) {
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
        return db.query("SELECT body FROM syncv2_provenance WHERE scope=? AND entity_key=?",arrayOf(scope,identity.entity(kind,id))).use{if(it.moveToFirst())open("provenance",it.getBlob(0))else null}
    }
    private fun markRemoved(d: SyncV2Schema.Domain,id: String,revision: String) {
        val entity=identity.entity(d.kind,id)
        val prior=db.query("SELECT meta,state FROM syncv2_candidates WHERE scope=? AND entity_key=?",arrayOf(scope,entity)).use{if(it.moveToFirst())it.getBlob(0) to it.getBlob(1) else null}
        if(prior!=null && open("candidate-state",prior.second).optBoolean("projectionRemoved"))return
        val meta=prior?.first?:seal("meta",JSONObject().put("version",2).put("entityKey",entity).put("kind",d.kind).put("logicalId",id).put("addedAtSource","unknown").put("requiredFeatures",JSONArray()).put("payload",JSONObject()))
        val value=(prior?.let{open("candidate-state",it.second)}?:JSONObject().put("version",2).put("entityKey",entity).put("requiredFeatures",JSONArray()).put("payload",JSONObject()).put("noteSegments",JSONArray())).put("projectionRemoved",true)
        val sealed=seal("candidate-state",value);val hash=SyncV2Codec.hash(value)
        val library=db.query("SELECT library_id FROM syncv2_control WHERE id=1").use{it.moveToFirst();it.getString(0)}
        db.execSQL("INSERT OR IGNORE INTO syncv2_identity_map VALUES(?,?,?,?,?,2)",arrayOf(library,identity.repoId,d.kind,id,entity))
        db.execSQL("INSERT OR REPLACE INTO syncv2_candidates VALUES(?,?,?,?,?,?,?,?)",arrayOf(scope,entity,d.kind,id,revision,meta,sealed,hash))
        SyncV2CandidateTree(db,scope).update(JSONObject().put("entityKey",entity).put("metaCandidateSha256",SyncV2Codec.hex(SyncV2Codec.sha(meta))).put("stateCandidateSha256",SyncV2Codec.hex(SyncV2Codec.sha(sealed))).put("sourceHash",hash).put("rowSequence",revision).put("addedAtMillis",0))
        db.execSQL("INSERT OR IGNORE INTO syncv2_change_log VALUES(?,?,?)",arrayOf(scope,revision,entity))
    }
    fun candidate(kind: String,id: String): Pair<JSONObject,JSONObject>? {
        ensureActive()
        return db.query("SELECT meta,state FROM syncv2_candidates WHERE scope=? AND entity_key=?",arrayOf(scope,identity.entity(kind,id))).use{c->
            if(c.moveToFirst())open("meta",c.getBlob(0)) to open("candidate-state",c.getBlob(1)) else null
        }
    }
}
