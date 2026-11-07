package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONArray
import org.json.JSONObject

/** C29 original publication inbox and local rank0 demand, never account/business apply.
 * accountSnapshot comes from the host's authenticated accepted account state. Old staged
 * local ref records remain readable; new refUpdate requests only create relay jobs. */
internal class SyncV2LocalControl(private val session:SyncV2LocalSession,private val identity:SyncV2Identity,
    private val authors:SyncV2Authors,private val store:SyncV2ContentStore,
    private val accountSnapshot:()->JSONObject,private val now:()->Long={System.currentTimeMillis()},
    val relayJobs:SyncV2RelayJobs=SyncV2RelayJobs(identity,authors,store,session.selfDeviceId,now=now)) {
    private fun state()=store.load("local-control")?:JSONObject().put("refs",JSONArray()).put("serverRevision","0").put("requests",JSONObject()).put("demands",JSONArray())
    fun snapshot():JSONObject {session.requireProof();val s=state()
        return JSONObject().put("refs",s.get("refs")).put("serverRevision",s.get("serverRevision")).put("historyFloorRevision","0").put("checkpointOid",JSONObject.NULL).put("account",accountSnapshot())}
    /** C29 queues the exact dual-CAS publication. It never advances a local/account ref. */
    fun refUpdate(request:JSONObject):JSONObject=relayJobs.enqueue(session,request)
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
