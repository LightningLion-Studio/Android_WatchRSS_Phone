package com.lightningstudio.watchrss.phone.syncv2

import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONObject

/** Derived selectors maintained by the SAME projection writer. They never witness a
 * business write or authorize an ACK. Selected records still pass their local AEAD;
 * reused wire rows are anchored by the authenticated current Merkle root. */
internal class SyncV2AccountCatalog(private val db:SupportSQLiteDatabase,private val scope:String,
    private val seal:(String,Any?)->ByteArray,private val open:(String,ByteArray)->JSONObject,private val state:()->JSONObject) {
    private val roots=mutableMapOf<String,String?>()
    private val kinds=listOf("differences","candidate-notes","cloud-notes","candidate/rss-state","cloud/rss-state","candidate/source","cloud/source","headers")
    private val writeTrees=mutableMapOf<String,SyncV2SelectorTree>()
    private fun tree(kind:String)=writeTrees.getOrPut(kind){SyncV2SelectorTree(db,scope,kind)}
    fun begin(body:JSONObject){writeTrees.clear();for(kind in kinds)roots[kind]=body.optString("p5Selector/$kind").takeIf{it.isNotEmpty() && it!="null"}}
    fun finish(body:JSONObject){archiveHeaders(body);for(kind in kinds)body.put("p5Selector/$kind",roots[kind]?:JSONObject.NULL)}
    fun reset(){writeTrees.clear();for(kind in kinds)if(kind!="headers")roots[kind]=null}
    private fun root(kind:String)=if(db.inTransaction())roots[kind]else state().optString("p5Selector/$kind").takeIf{it.isNotEmpty() && it!="null"}
    private fun entry(entity:String):JSONObject?=db.query("SELECT auth FROM syncv2_p5_catalog WHERE scope=? AND entity_key=?",arrayOf(scope,entity)).use{c->
        if(!c.moveToFirst())null else {if(c.isNull(0))SyncV2Codec.fail("closure_incomplete");open("catalog-entry",c.getBlob(0)).also{if(it.getString("entityKey")!=entity)SyncV2Codec.fail("identity_conflict")}}
    }
    private fun persist(value:JSONObject) {
        val entity=value.getString("entityKey");val needed=value.get("candidate")!=value.get("cloud") || value.get("candidate")!=value.get("binding")
        fun field(name:String)=value.get(name).let{if(it===JSONObject.NULL)null else it}
        db.execSQL("INSERT OR REPLACE INTO syncv2_p5_catalog(scope,entity_key,kind,candidate_hash,cloud_hash,binding_hash,cloud_row_hash,refs_oid,needs_choice,auth) VALUES(?,?,?,?,?,?,?,?,?,?)",arrayOf(scope,entity,value.getString("kind"),field("candidate"),field("cloud"),field("binding"),field("rowHash"),field("refs"),if(needed)1 else 0,seal("catalog-entry",value)))
        roots["differences"]=tree("differences").set(roots["differences"],entity,if(needed)""else null)
    }
    fun digest(record:JSONObject)=SyncV2Codec.hash(JSONObject().put("meta",record.getJSONObject("meta")).put("state",record.getJSONObject("state")))
    fun put(side:String,record:JSONObject) {
        check(db.inTransaction());check(side in setOf("candidate","cloud","binding"))
        val meta=record.getJSONObject("meta");val entity=meta.getString("entityKey");val kind=meta.getString("kind")
        val value=entry(entity)?:JSONObject().put("entityKey",entity).put("kind",kind).put("candidate",JSONObject.NULL).put("cloud",JSONObject.NULL).put("binding",JSONObject.NULL).put("rowHash",JSONObject.NULL).put("refs",JSONObject.NULL)
        if(value.getString("kind")!=kind)SyncV2Codec.fail("identity_conflict")
        val hash=if(record.getJSONObject("state").optBoolean("projectionRemoved"))null else digest(record)
        value.put(side,hash?:JSONObject.NULL);persist(value)
        if(kind in setOf("rss-state","source") && side!="binding")roots["$side/$kind"]=tree("$side/$kind").set(roots["$side/$kind"],entity,hash)
        if(kind=="note" && hash!=null && side!="binding") {
            val conflicts=SyncV2NoteConflicts.read(record.getJSONObject("state"),meta.getString("logicalId"),entity)
            // Empty unchanged channels contribute no conflict facts to the unique
            // planner. Their absence is authenticated by this selector root. Keep
            // every nonempty AND unknown channel, so global merge guards still run.
            if(conflicts!=null && conflicts.getJSONArray("records").length()==0) {
                roots["$side-notes"]=tree("$side-notes").set(roots["$side-notes"],entity,null)
                db.execSQL("DELETE FROM syncv2_p5_channels WHERE scope=? AND side=? AND entity_key=?",arrayOf(scope,side,entity));return
            }
            val channel=JSONObject().put("entityKey",entity).put("digest",hash).put("identity",meta.getString("logicalId")).put("noteConflicts",conflicts?:JSONObject.NULL)
            val bytes=seal("channel",channel)
            db.execSQL("INSERT OR REPLACE INTO syncv2_p5_channels VALUES(?,?,?,?)",arrayOf(scope,side,entity,bytes))
            roots["$side-notes"]=tree("$side-notes").set(roots["$side-notes"],entity,SyncV2Codec.hex(SyncV2Codec.sha(bytes)))
        }else {
            if(kind=="note" && side!="binding")roots["$side-notes"]=tree("$side-notes").set(roots["$side-notes"],entity,null)
            db.execSQL("DELETE FROM syncv2_p5_channels WHERE scope=? AND side=? AND entity_key=?",arrayOf(scope,side,entity))
        }
    }
    fun remove(side:String,entity:String) {
        check(db.inTransaction());check(side in setOf("candidate","cloud","binding"));val value=entry(entity)?:return
        value.put(side,JSONObject.NULL);if(side=="cloud")value.put("rowHash",JSONObject.NULL).put("refs",JSONObject.NULL);persist(value)
        if(value.getString("kind") in setOf("rss-state","source") && side!="binding"){val kind=value.getString("kind");roots["$side/$kind"]=tree("$side/$kind").set(roots["$side/$kind"],entity,null)}
        if(value.getString("kind")=="note" && side!="binding")roots["$side-notes"]=tree("$side-notes").set(roots["$side-notes"],entity,null)
        db.execSQL("DELETE FROM syncv2_p5_channels WHERE scope=? AND side=? AND entity_key=?",arrayOf(scope,side,entity))
    }
    fun kindOf(entity:String)=entry(entity)?.getString("kind")
    fun typedIds(kind:String):Set<String> {check(kind in setOf("rss-state","source"));return listOf("candidate","cloud").flatMap{side->SyncV2SelectorTree(db,scope,"$side/$kind").read(root("$side/$kind"),65).keys}.toSet()}
    fun differences()=SyncV2SelectorTree(db,scope,"differences").read(root("differences"),65).keys
    fun channels(side:String):JSONObject {
        check(side in setOf("candidate","cloud"));val out=JSONObject()
        val entries=SyncV2SelectorTree(db,scope,"$side-notes").read(root("$side-notes"))
        for((entity,hash)in entries) {
            val bytes=db.query("SELECT body FROM syncv2_p5_channels WHERE scope=? AND side=? AND entity_key=?",arrayOf(scope,side,entity)).use{if(!it.moveToFirst())SyncV2Codec.fail("closure_incomplete");it.getBlob(0)}
            if(SyncV2Codec.hex(SyncV2Codec.sha(bytes))!=hash)SyncV2Codec.fail("hash_mismatch")
            val value=open("channel",bytes);if(value.getString("entityKey")!=entity)SyncV2Codec.fail("identity_conflict")
            if(value.isNull("noteConflicts"))SyncV2Codec.fail("unsupported_feature")
            out.put(entity,JSONObject().put("identity",value.getString("identity")).put("noteConflicts",value.getJSONObject("noteConflicts")))
        };return out
    }
    fun header(oid:String):JSONObject? {
        val hash=if(db.inTransaction())tree("headers").find(root("headers"),oid.substringAfter(":sha256:"))else SyncV2SelectorTree(db,scope,"headers").find(root("headers"),oid.substringAfter(":sha256:"))
        if(hash==null)return null
        val bytes=db.query("SELECT body FROM syncv2_p5_header_archive WHERE scope=? AND oid=?",arrayOf(scope,oid)).use{if(!it.moveToFirst())SyncV2Codec.fail("closure_incomplete");it.getBlob(0)}
        if(SyncV2Codec.hex(SyncV2Codec.sha(bytes))!=hash)SyncV2Codec.fail("hash_mismatch")
        return open("header-archive",bytes).also{if(it.getString("oid")!=oid || SyncV2Codec.oid("commit",it.getJSONObject("header").getJSONObject("body"))!=oid)SyncV2Codec.fail("identity_conflict")}
    }
    private fun archiveHeaders(body:JSONObject) {
        val parents=body.optJSONArray("knownAccountParents");val headers=body.optJSONArray("knownControlHeaders")
        val entries=linkedMapOf<String,JSONObject>()
        if(headers!=null)for(i in 0 until headers.length()){val h=headers.getJSONObject(i);val oid=SyncV2Codec.oid("commit",h.getJSONObject("body"));entries[oid]=JSONObject().put("oid",oid).put("header",h).put("accountPath",false)}
        if(parents!=null)for(i in 0 until parents.length()){val p=parents.getJSONObject(i);entries[p.getString("oid")]=JSONObject().put("oid",p.getString("oid")).put("header",p.getJSONObject("header")).put("accountPath",true)}
        val digest=SyncV2Codec.hash(org.json.JSONArray(entries.values.toList()))
        if(body.optString("p5HeaderCacheDigest")!=digest) {
            for((oid,entry)in entries) {
                val old=header(oid)
                if(old!=null && (old.getBoolean("accountPath") || !entry.getBoolean("accountPath")))continue
                val bytes=seal("header-archive",entry)
                db.execSQL("INSERT OR REPLACE INTO syncv2_p5_header_archive VALUES(?,?,?)",arrayOf(scope,oid,bytes))
                roots["headers"]=tree("headers").set(roots["headers"],oid.substringAfter(":sha256:"),SyncV2Codec.hex(SyncV2Codec.sha(bytes)))
            }
        }
        if(parents!=null)body.put("knownAccountParents",org.json.JSONArray((maxOf(0,parents.length()-2) until parents.length()).map{parents.getJSONObject(it)}))
        if(headers!=null)body.put("knownControlHeaders",org.json.JSONArray((maxOf(0,headers.length()-2) until headers.length()).map{headers.getJSONObject(it)}))
        val retained=linkedMapOf<String,JSONObject>();val hp=body.optJSONArray("knownControlHeaders");val pp=body.optJSONArray("knownAccountParents")
        if(hp!=null)for(i in 0 until hp.length()){val h=hp.getJSONObject(i);val oid=SyncV2Codec.oid("commit",h.getJSONObject("body"));retained[oid]=JSONObject().put("oid",oid).put("header",h).put("accountPath",false)}
        if(pp!=null)for(i in 0 until pp.length()){val p=pp.getJSONObject(i);retained[p.getString("oid")]=JSONObject().put("oid",p.getString("oid")).put("header",p.getJSONObject("header")).put("accountPath",true)}
        body.put("p5HeaderCacheDigest",SyncV2Codec.hash(org.json.JSONArray(retained.values.toList())))
    }
    fun cloudRowHash(entity:String)=entry(entity)?.optString("rowHash")?.takeIf{it.isNotEmpty()&&it!="null"}
    fun setCloudRow(record:JSONObject,refsOid:String?) {
        check(db.inTransaction());val entity=record.getJSONObject("meta").getString("entityKey")
        put("cloud",record);val value=entry(entity)?:SyncV2Codec.fail("closure_incomplete")
        persist(value.put("rowHash",SyncV2Codec.hash(record)).put("refs",refsOid?:JSONObject.NULL))
    }
    fun refs(parent:String)=db.query("SELECT body FROM syncv2_p5_ref_index WHERE scope=? AND parent_oid=?",arrayOf(scope,parent)).use{c->if(!c.moveToFirst())null else {
        if(c.isNull(0))SyncV2Codec.fail("closure_incomplete");val value=open("refs-map",c.getBlob(0));if(value.getString("parent")!=parent)SyncV2Codec.fail("identity_conflict");value.getString("oid")
    }}
    fun putRefs(parent:String,oid:String){check(db.inTransaction());db.execSQL("INSERT OR REPLACE INTO syncv2_p5_ref_index VALUES(?,?,?,?)",arrayOf(scope,parent,oid,seal("refs-map",JSONObject().put("parent",parent).put("oid",oid))))}
}
