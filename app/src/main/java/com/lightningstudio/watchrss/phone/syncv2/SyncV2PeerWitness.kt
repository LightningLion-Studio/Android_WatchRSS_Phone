package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONObject

/** Independent peer observation; deliberately has no projection/accepted-pin writer. */
internal class SyncV2PeerWitness(private val identity:SyncV2Identity,private val authors:SyncV2Authors,
    private val store:SyncV2ContentStore,private val enabled:Boolean=com.lightningstudio.watchrss.phone.BuildConfig.SYNC_OBJECTS_V2_WITNESS) {
    fun receive(session:SyncV2LocalSession,body:JSONObject):JSONObject {
        if(!enabled)SyncV2Codec.fail("feature_disabled")
        session.requireFeature("witness");SyncV2Codec.exact(body,setOf("scope","head","headers"))
        if(SyncV2Codec.scope(body.getJSONObject("scope"))!=identity.scopeJson)SyncV2Codec.fail("scope_mismatch")
        if(SyncV2LocalProtocol.bytes(body).size>262144)SyncV2Codec.fail("object_too_large")
        val head=SyncV2Wire.oid(body.getString("head"),"commit");val items=body.getJSONArray("headers")
        if(items.length()>64)SyncV2Codec.fail("object_too_large")
        val headers=(0 until items.length()).map{i->
            val h=items.getJSONObject(i);SyncV2Codec.exact(h,setOf("commitOid","headerOid","body","signature"))
            val signed=JSONObject().put("body",h.get("body")).put("signature",h.get("signature"))
            val b=authors.header(signed)
            if(h.get("commitOid")!=SyncV2Codec.oid("commit",b) || h.get("headerOid")!="commit-header:sha256:${SyncV2Codec.hex(SyncV2Codec.sha(SyncV2LocalProtocol.bytes(signed)))}")SyncV2Codec.fail("hash_mismatch")
            signed
        }
        val signed=headers.singleOrNull{SyncV2Codec.oid("commit",it.getJSONObject("body"))==head}?:SyncV2Codec.fail("object_missing")
        observe(session,JSONObject().put("oid",head).put("generation",signed.getJSONObject("body").get("rollbackGeneration")),headers)
        return JSONObject().put("accepted",true)
    }
    @Synchronized fun observe(session:SyncV2LocalSession,window:JSONObject,headers:List<JSONObject>):JSONObject {
        if(!enabled)SyncV2Codec.fail("feature_disabled")
        session.requireFraming()
        if(SyncV2Codec.scope(session.scope)!=identity.scopeJson)SyncV2Codec.fail("scope_mismatch")
        val name="peer-witness/${session.peerDeviceId}";val old=store.load(name)
        val pin=old?.getJSONObject("pin")
        val known=old?.getJSONArray("headers")?.let{a->(0 until a.length()).map{a.getJSONObject(it)}}?:emptyList()
        val authorsPin=old?.getJSONObject("knownAuthors")?:JSONObject()
        val proof=SyncV2HeadPins.authenticate(identity,window,headers,pin,{authors.header(it)},knownAuthors=authorsPin,knownHeaders=known)
        val accepted=JSONObject(authorsPin.toString())
        for(i in proof.path.length()-1 downTo 0) {
            val p=proof.path.getJSONObject(i);val b=p.getJSONObject("header").getJSONObject("body")
            accepted.put(b.getString("authorDeviceId"),JSONObject().put("sequence",b.getString("deviceSequence")).put("oid",p.getString("oid")))
        }
        val next=JSONObject().put("pin",JSONObject(window.toString())).put("knownAuthors",accepted).put("headers",org.json.JSONArray(proof.headers))
        store.save(name,next);return JSONObject(next.toString())
    }
}
