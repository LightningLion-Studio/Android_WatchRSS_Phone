package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONArray
import org.json.JSONObject

/** Pure preparation only. A prepared copy never authorizes apply, ACK or source-byte deletion. */
internal object SyncV2Recovery {
    private const val period=604800000L
    fun delta(local:JSONObject,cloud:JSONObject,editBase:JSONObject,captured:Set<String>,refs:JSONObject):JSONObject {
        SyncV2Codec.exact(editBase,setOf("accountOid","generation"));SyncV2Codec.commitOid(editBase.getString("accountOid"));SyncV2Codec.d64(editBase.getString("generation"))
        val changes=JSONArray();val bodyRefs=sortedSetOf<String>()
        for(id in captured.sorted()) {
            if(local.has(id)==cloud.has(id) && SyncV2Codec.canonical(local.opt(id))==SyncV2Codec.canonical(cloud.opt(id)))continue
            val change=JSONObject().put("id",id).put("kind",if(local.has(id))"set" else "remove")
            if(local.has(id)) {
                change.put("value",local.get(id))
                val ids=if(refs.has(id))refs.getJSONArray(id)else JSONArray()
                for(i in 0 until ids.length()) {
                    val oid=ids.getString(i);if(!Regex("(recipe|blob):sha256:[a-f0-9]{64}").matches(oid))SyncV2Codec.fail("closure_incomplete")
                    bodyRefs.add(oid)
                }
                val recipe=local.optJSONObject(id)?.optString("recipeOid","")?:""
                if(recipe.isNotEmpty() && recipe !in bodyRefs)SyncV2Codec.fail("closure_incomplete")
            }
            changes.put(change)
        }
        return JSONObject().put("version",2).put("changes",changes).put("editBase",editBase).put("bodyRefs",JSONArray(bodyRefs.toList()))
    }
    fun prepare(delta:JSONObject,key:ByteArray,now:Long,revision:String,accountOid:String,existingIds:Set<String>):JSONObject {
        require(key.size==32)
        if(now !in 0..9007199254740991L-period)SyncV2Codec.fail("invalid_schema")
        SyncV2Codec.d64(revision);SyncV2Codec.commitOid(accountOid)
        var id:String
        do{id=SyncV2Codec.hex(SyncV2Codec.random(16))}while(id in existingIds)
        val header=JSONObject().put("domain","wrss.sync.v2/local-recovery").put("version",2).put("copyId",id)
            .put("createdAtMillis",now).put("expiresAtMillis",now+period).put("localChangeRevision",revision).put("accountOid",accountOid)
        val nonce=SyncV2Codec.random(12)
        return JSONObject().put("copyId",id).put("header",header).put("nonce",SyncV2Codec.b64(nonce))
            .put("ciphertext",SyncV2Codec.b64(SyncV2Codec.encrypt(key,nonce,SyncV2Codec.utf8(SyncV2Codec.canonical(header)),SyncV2Codec.utf8(SyncV2Codec.canonical(delta)))))
    }
    fun open(copy:JSONObject,key:ByteArray):JSONObject {
        SyncV2Codec.exact(copy,setOf("copyId","header","nonce","ciphertext"))
        val id=copy.getString("copyId");SyncV2Codec.unhex(id,16)
        val h=copy.getJSONObject("header")
        SyncV2Codec.exact(h,setOf("domain","version","copyId","createdAtMillis","expiresAtMillis","localChangeRevision","accountOid"))
        if(h.getString("copyId")!=id || h.get("version")!=2 || h.getString("domain")!="wrss.sync.v2/local-recovery" || h.getLong("expiresAtMillis")-h.getLong("createdAtMillis")!=period)SyncV2Codec.fail("invalid_schema")
        val raw=SyncV2Codec.decrypt(key,SyncV2Codec.decode64(copy.getString("nonce"),12),SyncV2Codec.utf8(SyncV2Codec.canonical(h)),SyncV2Codec.decode64(copy.getString("ciphertext")))
        return JSONObject(raw.toString(Charsets.UTF_8)).also{SyncV2Codec.exact(it,setOf("version","changes","editBase","bodyRefs"))}
    }
    fun pin(copy:JSONObject,delta:JSONObject)=JSONObject().put("copyId",copy.getString("copyId")).put("bodyRefs",delta.getJSONArray("bodyRefs")).put("expiresAtMillis",copy.getJSONObject("header").getLong("expiresAtMillis"))
    /** Returns IDs only. No automatic cleanup or physical deletion is exposed in P1. */
    fun expiredPlan(copies:JSONArray,pins:JSONArray,trustedNow:Long,lastTrustedNow:Long):Set<String> {
        val byId=linkedMapOf<String,JSONObject>();val expires=mutableMapOf<String,Long>()
        for(i in 0 until copies.length()) {
            val c=copies.getJSONObject(i);val id=c.getString("copyId");SyncV2Codec.unhex(id,16)
            val h=c.getJSONObject("header");if(h.getString("copyId")!=id || byId.put(id,c)!=null)SyncV2Codec.fail("invalid_schema")
            expires[id]=h.getLong("expiresAtMillis")
        }
        val pinIds=mutableSetOf<String>()
        for(i in 0 until pins.length()) {
            val p=pins.getJSONObject(i);SyncV2Codec.exact(p,setOf("copyId","bodyRefs","expiresAtMillis"))
            val id=p.getString("copyId");if(!pinIds.add(id) || expires[id]!=p.getLong("expiresAtMillis"))SyncV2Codec.fail("invalid_schema")
        }
        if(pinIds!=byId.keys)SyncV2Codec.fail("invalid_schema")
        if(trustedNow<lastTrustedNow || trustedNow<0)return emptySet()
        return expires.filterValues{it<=trustedNow}.keys
    }
}
