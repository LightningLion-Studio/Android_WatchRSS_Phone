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
    fun install() {
        gate(); SyncV2Schema.install(db)
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
                db.execSQL("INSERT INTO syncv2_state VALUES(?,?,?,?,?,?,?)",arrayOf(scope,"0","0",seal("state",body),identity.encryptedRecord(key),identity.descriptorOid,identity.accountId))
            }
            db.execSQL("UPDATE syncv2_control SET enabled=1,active_scope=?,failure_code=NULL WHERE id=1",arrayOf(scope))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun pause() { ensureActive(); db.execSQL("UPDATE syncv2_control SET enabled=0 WHERE id=1") }
    private fun seal(purpose: String, value: Any?) = SyncV2Codec.sealLocal(key,scope,purpose,value)
    private fun open(purpose: String, bytes: ByteArray)=JSONObject(SyncV2Codec.openLocal(key,scope,purpose,bytes))
    data class State(val version: String,val revision: String,val body: JSONObject)
    fun state(): State {
        ensureActive()
        return db.query("SELECT state_version,local_revision,body FROM syncv2_state WHERE scope=?",arrayOf(scope)).use { c ->
            check(c.moveToFirst()); State(c.getString(0),c.getString(1),open("state",c.getBlob(2)))
        }
    }
    private fun <T> writer(expected: String?=null, body: (State)->T): T {
        ensureActive()
        db.beginTransaction() // Android uses an immediate/exclusive SQLite writer, never a read-deferred txn.
        var started=false
        try {
            val old=state(); val following=SyncV2Codec.next(old.version) // before callbacks and fault hooks
            if(expected!=null && old.version!=expected) SyncV2Codec.fail("choice_stale")
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
    fun observeVerified(window: JSONObject, headers: List<JSONObject>) {
        val observed=validAccount(window)
        val before=state()
        val proof=SyncV2HeadPins.verify(identity,observed,headers,before.body.optJSONObject("account"))
        writer(before.version) { st ->
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
        val st=state(); val a=current(st)
        if(SyncV2Codec.canonical(a)!=SyncV2Codec.canonical(window)) SyncV2Codec.fail("choice_stale")
        return ChoiceCapture(st.version,a.getString("oid"),a.getString("revision"),a.getString("generation"),st.revision).also { checkCapture(st,it) }
    }
    private fun checkCapture(st: State,capture: ChoiceCapture) {
        val a=current(st); ChoiceCapture.restore(capture.json())
        val pending=st.body.optJSONObject("pendingChoice")?:SyncV2Codec.fail("choice_stale")
        if(pending.get("generation") !is String)SyncV2Codec.fail("choice_stale")
        if(capture.stateVersion!=st.version || capture.localChangeRevision!=st.revision || capture.accountOid!=a.getString("oid") ||
            capture.accountRevision!=a.getString("revision") || capture.generation!=a.getString("generation") || capture.generation!=pending.getString("generation")) SyncV2Codec.fail("choice_stale")
    }
    /** Validates at both preparation and commit boundaries, but product apply is intentionally closed. */
    fun validateChoice(capture: ChoiceCapture, prepare: ()->Unit = {}) {
        checkCapture(state(),capture); prepare()
        db.beginTransaction()
        try { checkCapture(state(),capture) } finally { db.endTransaction() }
    }
    /** Prepare a minimal encrypted recovery candidate, then recheck the same Capture.
     * It is not a committed recovery reference and cannot authorize discarding the inbox.
     */
    fun prepareLegacyCloud(capture: ChoiceCapture,peer:String,version:Int,cursor:String,cloud:JSONObject,now:Long):JSONObject {
        val before=state();checkCapture(before,capture)
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
        checkCapture(state(),capture); SyncV2Codec.fail("feature_disabled")
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
        ensureActive()
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
            // Do not start a new R1 chase in this execution window.
            if(!state().body.has("tailR1Seq"))return WindowProgress(rows,tail,pages,false)
        }
        return WindowProgress(rows,tail,pages,true)
    }
    private fun binding(domain: SyncV2Schema.Domain, capability: String): String = SyncV2Codec.hash(JSONObject()
        .put("schema",SyncV2Schema.semanticBinding(db,listOf(domain.table)+when(domain.table){"notes"->listOf("note_conflicts");"phone_articles"->listOf("article_register_provenance");"imported_books"->listOf("imported_book_chapters");else->emptyList()}))
        .put("scope",identity.scope).put("descriptor",identity.descriptorOid).put("account",identity.accountId).put("capability",capability))
    /** Stable keyset page; no OFFSET or SELECT * against article bodies. Maximum 64 records / 1 MiB. */
    fun scanPage(domainName: String, capability: String="p1-local-v1", limit: Int=64, byteBudget: Int=1048576): PageResult {
        ensureActive(); require(limit in 1..64 && byteBudget in 262144..1048576)
        val domain=SyncV2Schema.domains.single { it.table==domainName }
        val bind=binding(domain,capability)
        val reusable=db.query("SELECT complete,binding FROM syncv2_scan WHERE scope=? AND domain=?",arrayOf(scope,domainName)).use { it.moveToFirst() && it.getInt(0)==1 && it.getString(1)==bind }
        if(reusable)return PageResult(domainName,0,0,true,false)
        return writer { st ->
            val checkpoint=db.query("SELECT binding,r0,page_key,complete FROM syncv2_scan WHERE scope=? AND domain=?",arrayOf(scope,domainName)).use { c ->
                if(c.moveToFirst()) listOf(c.getString(0),c.getString(1),c.getString(2),c.getInt(3).toString()) else null
            }
            val restart=checkpoint==null || checkpoint[0]!=bind
            if(restart) {
                db.execSQL("INSERT OR REPLACE INTO syncv2_scan VALUES(?,?,?,?,NULL,'',0,0)",arrayOf(scope,domainName,bind,st.revision))
            } else if(checkpoint!![3]=="1") return@writer PageResult(domainName,0,0,true,false)
            val last=if(restart)"" else checkpoint!![2]
            val columns=columns(domain)
            var rows=0; var bytes=0; var keyAt=last; var exhausted=true
            db.query("SELECT ${domain.key} AS projection_key,$columns FROM `${domain.table}` WHERE (${domain.key}) COLLATE BINARY > ? ORDER BY (${domain.key}) COLLATE BINARY LIMIT ?",arrayOf(last,limit+1)).use { c ->
                while(c.moveToNext()) {
                    if(rows==limit) { exhausted=false; break }
                    val id=c.getString(0); val record=readRecord(domain,c)
                    val size=SyncV2Codec.utf8(record.toString()).size
                    if(size>byteBudget)SyncV2Codec.fail("object_too_large")
                    if(rows>0 && bytes+size>byteBudget){ exhausted=false;break }
                    project(domain,id,record,st.revision)
                    keyAt=id; rows++; bytes+=size
                }
            }
            fault("before-checkpoint")
            db.execSQL("UPDATE syncv2_scan SET page_key=?,complete=?,r1=? WHERE scope=? AND domain=?",arrayOf<Any?>(keyAt,if(exhausted)1 else 0,if(exhausted)st.revision else null,scope,domainName))
            fault("after-checkpoint"); PageResult(domainName,rows,bytes,exhausted,false)
        }
    }
    /** Fixed R1 watermark per pass. Entries after R1 stay durable for the following execution window. */
    fun catchUp(limit: Int=64): Int {
        require(limit in 1..64)
        val before=state()
        val maxPending=db.query("SELECT COALESCE(MAX(seq),0) FROM syncv2_outbox WHERE scope=?",arrayOf(scope)).use {it.moveToFirst();it.getLong(0)}
        if(!before.body.has("tailR1Seq") && maxPending==before.body.optLong("tailSeq",0))return 0
        return writer { st ->
            val max=db.query("SELECT COALESCE(MAX(seq),0) FROM syncv2_outbox WHERE scope=?",arrayOf(scope)).use{it.moveToFirst();it.getLong(0)}
            val entries=mutableListOf<Array<Any>>()
            val from=st.body.optLong("tailSeq",0)
            val end=if(st.body.has("tailR1Seq"))st.body.getLong("tailR1Seq") else max.also{st.body.put("tailR1Seq",it)}
            db.query("SELECT seq,revision,table_name,logical_id,operation,base_state FROM syncv2_outbox WHERE scope=? AND seq>? AND seq<=? ORDER BY seq LIMIT ?",arrayOf<Any>(scope,from,end,limit)).use { c ->
                while(c.moveToNext()) entries.add(arrayOf(c.getLong(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getBlob(5)))
            }
            for(e in entries) {
                val table=e[2] as String
                val domain=SyncV2Schema.domains.first {it.table==when(table){"note_conflicts"->"notes";"imported_book_chapters"->"imported_books";"article_register_provenance"->"phone_articles";else->table}}
                val id=e[3] as String
                val eventState=open("state",e[5] as ByteArray)
                val eventAccount=eventState.optJSONObject("account")
                if(st.body.isNull("editBase") && eventAccount!=null) st.body.put("editBase",JSONObject().put("accountOid",eventAccount.getString("oid")).put("generation",eventAccount.getString("generation")))
                db.query("SELECT ${domain.key} AS projection_key,${columns(domain)} FROM `${domain.table}` WHERE (${domain.key})=?",arrayOf(id)).use { c ->
                    if(c.moveToFirst()) project(domain,id,readRecord(domain,c),e[1] as String,eventAccount)
                    else markRemoved(domain,id,e[1] as String)
                }
                fault("after-tail-entry"); st.body.put("tailSeq",e[0])
            }
            if(entries.isEmpty() || (entries.last()[0] as Long)==end)st.body.remove("tailR1Seq")
            refreshPending(st.body)
            entries.size
        }
    }
    private fun columns(d: SyncV2Schema.Domain): String = when(d.table) {
        "phone_articles" -> SyncV2Business.articleFields.filterNot{it in setOf("rssIndexRetired","bookIndexRetired","registerSources")}.sorted().joinToString(","){"`$it`"}
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
        return out
    }
    private fun project(d: SyncV2Schema.Domain,id: String,value: JSONObject,revision: String,eventAccount: JSONObject?=null) {
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
    }
    /** Bounded encrypted source sidecars retain note conflicts and legacy register ancestry verbatim.
     * These are local candidates, not new wire payload extensions.
     */
    private fun provenance(d: SyncV2Schema.Domain,id:String):JSONObject {
        val result=JSONObject()
        val statements=when(d.table) {
            "notes"->listOf("conflicts" to "SELECT * FROM note_conflicts WHERE noteId=? ORDER BY conflictId LIMIT 257")
            "phone_articles"->listOf("register" to "SELECT * FROM article_register_provenance WHERE articleId=?")
            "note_assets"->listOf("asset" to "SELECT * FROM note_assets WHERE assetId=?")
            "imported_books"->listOf("chapters" to "SELECT * FROM imported_book_chapters WHERE bookId=? ORDER BY chapterIndex LIMIT 257")
            else->emptyList()
        }
        for((name,sql)in statements) {
            val rows=JSONArray()
            db.query(sql,arrayOf(id)).use {c->while(c.moveToNext()) {
                if(rows.length()==256)SyncV2Codec.fail("unsupported_feature")
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
