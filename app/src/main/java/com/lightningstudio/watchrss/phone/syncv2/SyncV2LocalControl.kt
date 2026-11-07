package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONArray
import org.json.JSONObject

/** Local device-ref staging and rank0 demand, never account publication or business apply.
 * One host writer owns the scope store. Closure IO happens before the short durable CAS.
 * accountSnapshot must come from the host's authenticated accepted account state. */
internal class SyncV2LocalControl(private val session:SyncV2LocalSession,private val identity:SyncV2Identity,
    private val authors:SyncV2Authors,private val store:SyncV2ContentStore,private val key:(Long)->ByteArray,
    private val accountSnapshot:()->JSONObject,private val now:()->Long={System.currentTimeMillis()}) {
    private fun state()=store.load("local-control")?:JSONObject().put("refs",JSONArray()).put("serverRevision","0").put("requests",JSONObject()).put("demands",JSONArray())
    fun snapshot():JSONObject {session.requireProof();val s=state()
        return JSONObject().put("refs",s.get("refs")).put("serverRevision",s.get("serverRevision")).put("historyFloorRevision","0").put("checkpointOid",JSONObject.NULL).put("account",accountSnapshot())}
    fun refUpdate(request:JSONObject):JSONObject {
        session.requireFraming()
        SyncV2Codec.exact(request,if(request.has("relayGrant"))setOf("body","signature","relayGrant")else setOf("body","signature"))
        if(request.has("relayGrant") && !request.isNull("relayGrant"))SyncV2Codec.fail("relay_denied")
        val b=request.getJSONObject("body");SyncV2Codec.exact(b,setOf("version","scope","ref","expectedOid","expectedRevision","newCommitOid","requestId","issuedAtMillis","expiresAtMillis"))
        if(b.get("version")!=2)SyncV2Codec.fail("invalid_schema")
        if(SyncV2Codec.scope(b.getJSONObject("scope"))!=identity.scopeJson)SyncV2Codec.fail("scope_mismatch")
        val author=session.peerDeviceId?:SyncV2Codec.fail("authentication_required")
        if(b.get("ref")!="refs/devices/$author/index")SyncV2Codec.fail("relay_denied")
        val pub=authors.publicKey(author);val rid=SyncV2LocalProtocol.id(b.getString("requestId"));val oid=SyncV2Wire.oid(b.getString("newCommitOid"),"commit")
        SyncV2Codec.d64(b.getString("expectedRevision"));if(!b.isNull("expectedOid"))SyncV2Wire.oid(b.getString("expectedOid"),"commit")
        if(!SyncV2Identity.verifySignature(pub,SyncV2Codec.lp(SyncV2Codec.utf8("wrss.sync.v2/ref-update-sign"),SyncV2LocalProtocol.bytes(b)),request.getString("signature")))SyncV2Codec.fail("signature_invalid")
        val hash=SyncV2Codec.hash(b)
        fun prior(s:JSONObject):JSONObject?=s.getJSONObject("requests").optJSONObject(rid)?.let{if(it.get("hash")!=hash)SyncV2Codec.fail("idempotency_conflict");it.getJSONObject("result")}
        synchronized(this){prior(state())?.let{return it}}
        val issued=SyncV2Wire.uint(b,"issuedAtMillis");val expires=SyncV2Wire.uint(b,"expiresAtMillis");val time=now()
        if(expires<=issued || expires-issued>300000 || issued>time+30000 || expires<time-30000)SyncV2Codec.fail("relay_denied")
        val signed=SyncV2Wire.parse(store.objectBytes(oid),262144,true);val commit=authors.header(signed,false)
        if(SyncV2Codec.oid("commit",commit)!=oid)SyncV2Codec.fail("hash_mismatch")
        if(commit.get("authorDeviceId")!=author || commit.opt("previousOwnCommit")!=b.opt("expectedOid"))SyncV2Codec.fail("ref_conflict")
        SyncV2ControlClosure(identity,authors,key).verify(signed,store::objectBytes){parent->store.load("local-refs/$parent")?:SyncV2Codec.fail("closure_incomplete")}
        return synchronized(this) {
            session.requireFraming();val s=state();prior(s)?.let{return@synchronized it}
            val account=accountSnapshot()
            if(commit.get("rollbackGeneration")!=account.get("rollbackGeneration"))SyncV2Codec.fail("rollback_conflict")
            if(commit.opt("accountParent")!=account.opt("commitOid"))SyncV2Codec.fail("ref_conflict")
            val refs=s.getJSONArray("refs");val entries=(0 until refs.length()).map{refs.getJSONObject(it)}
            val old=entries.singleOrNull{it.get("deviceId")==author}
            if((old?.opt("commitOid")?:JSONObject.NULL)!=b.opt("expectedOid") || (old?.getString("refRevision")?:"0")!=b.getString("expectedRevision"))SyncV2Codec.fail("ref_conflict")
            if(old!=null){val previous=authors.header(SyncV2Wire.parse(store.objectBytes(old.getString("commitOid")),262144,true));if(SyncV2Codec.d64(commit.getString("deviceSequence"))<=SyncV2Codec.d64(previous.getString("deviceSequence")))SyncV2Codec.fail("replay_detected")}
            val revision=SyncV2Codec.next(old?.getString("refRevision")?:"0");val result=JSONObject().put("version",2).put("scope",identity.scope).put("ref",b.get("ref")).put("commitOid",oid).put("refRevision",revision)
            if(s.getJSONObject("requests").length()>=512)SyncV2Codec.fail("quota_exceeded")
            s.put("refs",JSONArray((entries.filter{it.get("deviceId")!=author}+JSONObject().put("deviceId",author).put("commitOid",oid).put("refRevision",revision)).sortedBy{it.getString("deviceId")}))
            s.put("serverRevision",SyncV2Codec.next(s.getString("serverRevision")));s.getJSONObject("requests").put(rid,JSONObject().put("hash",hash).put("result",result))
            store.save("local-control",s);result
        }
    }
    @Synchronized fun demand(body:JSONObject):JSONObject {
        session.requireFraming();SyncV2Codec.exact(body,setOf("scope","entityKey","bodyRevision","requesterDeviceId","demandId","demandAtMillis","expiresAtMillis"))
        if(SyncV2Codec.scope(body.getJSONObject("scope"))!=identity.scopeJson)SyncV2Codec.fail("scope_mismatch")
        if(body.get("requesterDeviceId")!=session.peerDeviceId)SyncV2Codec.fail("authentication_required")
        SyncV2Codec.unhex(body.getString("entityKey"),32);SyncV2LocalProtocol.id(body.getString("bodyRevision"));SyncV2LocalProtocol.id(body.getString("demandId"))
        val start=SyncV2Wire.uint(body,"demandAtMillis");val end=SyncV2Wire.uint(body,"expiresAtMillis");val time=now()
        if(end<=time || start>time+30000 || end<=start || end-start>600000)SyncV2Codec.fail("invalid_schema")
        val s=state();val saved=s.getJSONArray("demands");val entries=(0 until saved.length()).map{saved.getJSONObject(it)}.filter{it.getLong("expiresAtMillis")>time}
        val duplicate=entries.singleOrNull{it.get("demandId")==body.get("demandId")}
        if(duplicate!=null && SyncV2Codec.canonical(duplicate)!=SyncV2Codec.canonical(body))SyncV2Codec.fail("idempotency_conflict")
        val next=entries.filter{it.get("entityKey")!=body.get("entityKey") || it.get("bodyRevision")!=body.get("bodyRevision")}.toMutableList()
        val same=entries.filter{it.get("entityKey")==body.get("entityKey") && it.get("bodyRevision")==body.get("bodyRevision")}
        next.add((same+body).maxBy{it.getLong("demandAtMillis")});if(next.size>32)SyncV2Codec.fail("quota_exceeded")
        s.put("demands",JSONArray(next.sortedWith(compareByDescending<JSONObject>{it.getLong("demandAtMillis")}.thenBy{it.getString("entityKey")}.thenBy{it.getString("bodyRevision")})))
        store.save("local-control",s);return JSONObject().put("accepted",true)
    }
    fun demands():List<JSONObject> {session.requireFraming();val a=state().getJSONArray("demands");return (0 until a.length()).map{a.getJSONObject(it)}.filter{it.getLong("expiresAtMillis")>now()}}
}
