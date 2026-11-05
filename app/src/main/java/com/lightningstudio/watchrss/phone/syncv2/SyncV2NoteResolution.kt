package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONArray
import org.json.JSONObject

/** Pure preparation used by the SQLite writer; no UI token refresh or mutable capture objects. */
internal object SyncV2NoteResolution {
    class Capture internal constructor(val sync:ChoiceCapture,val noteId:String,val expectedUpdatedAt:Long,
        val selectedConflictId:String,private val openJson:String) {
        val openConflictIds:List<String> get()=SyncV2Wire.strings(JSONArray(openJson),1024)
    }
    data class Budget(val root:JSONObject,val rootPlainBytes:Long,val rootStoredBytesUpperBound:Long,
        val rootRefsBytesUpperBound:Long,val closureStoredBytes:Long,val journalWorstBytes:Long,val requiredBytes:Long) {
        fun json()=JSONObject().put("root",root).put("rootPlainBytes",rootPlainBytes).put("rootStoredBytesUpperBound",rootStoredBytesUpperBound)
            .put("rootRefsBytesUpperBound",rootRefsBytesUpperBound).put("closureStoredBytes",closureStoredBytes).put("journalWorstBytes",journalWorstBytes).put("requiredBytes",requiredBytes)
    }
    fun capture(st:JSONObject):Capture {
        sync(st,null);val note=st.getJSONObject("note");val id=note.getString("noteId");val sets=st.getJSONObject("conflicts")
        if(note.get("deleted")!=false)SyncV2Codec.fail("notes_stale")
        return Capture(ChoiceCapture(st.getString("stateVersion"),st.getJSONObject("account").getString("oid"),st.getJSONObject("account").getString("revision"),st.getString("rollbackGeneration"),st.getString("localChangeRevision")),
            id,SyncV2Wire.uint(note,"updatedAt"),SyncV2NoteConflicts.latest(sets,id),SyncV2Codec.canonical(JSONArray(SyncV2NoteConflicts.openIds(sets,id))))
    }
    fun guard(st:JSONObject,c:Capture) {
        sync(st,c.sync);val note=st.getJSONObject("note");val conflicts=st.getJSONObject("conflicts")
        if(note.get("deleted")!=false || note.getString("noteId")!=c.noteId || SyncV2Wire.uint(note,"updatedAt")!=c.expectedUpdatedAt ||
            SyncV2NoteConflicts.latest(conflicts,c.noteId)!=c.selectedConflictId || SyncV2NoteConflicts.openIds(conflicts,c.noteId)!=c.openConflictIds)SyncV2Codec.fail("notes_stale")
    }
    private fun sync(st:JSONObject,c:ChoiceCapture?) {
        try {
            val account=st.getJSONObject("account");SyncV2Codec.exact(account,setOf("oid","revision","generation"))
            SyncV2Codec.commitOid(SyncV2Wire.string(account,"oid"));if(SyncV2Codec.d64(SyncV2Wire.string(account,"revision")).signum()==0)throw IllegalArgumentException()
            for(k in listOf("stateVersion","localChangeRevision","rollbackGeneration"))SyncV2Codec.d64(SyncV2Wire.string(st,k))
            if(account.getString("generation")!=st.getString("rollbackGeneration") || st.getJSONObject("editBase").getString("generation")!=account.getString("generation"))throw IllegalArgumentException()
            SyncV2Codec.commitOid(st.getJSONObject("editBase").getString("accountOid"))
            for((key,value)in listOf("lastSeenAccountOid" to account.getString("oid"),"lastSeenAccountRevision" to account.getString("revision"),"lastSeen" to account.getString("generation")))if(st.has(key)&&st.get(key)!=value)throw IllegalArgumentException()
            if(c!=null) {
                ChoiceCapture.restore(c.json())
                if(c.stateVersion!=st.getString("stateVersion") || c.localChangeRevision!=st.getString("localChangeRevision") || c.accountOid!=account.getString("oid") || c.accountRevision!=account.getString("revision") || c.generation!=account.getString("generation"))throw IllegalArgumentException()
            }
        }catch(_:Exception){SyncV2Codec.fail("choice_stale")}
        if(!st.has("pendingChoice") || !st.isNull("pendingChoice"))SyncV2Codec.fail("choice_pending")
    }
    fun prepare(st:JSONObject,c:Capture,choice:String,manual:String?,now:Long,device:String,derive:((JSONObject)->Unit)?=null):Pair<JSONObject,Budget> {
        guard(st,c)
        if(now !in 0..9007199254740991L || choice !in setOf("local","remote","manual") || choice!="manual" && manual!=null)SyncV2Codec.fail("invalid_schema")
        SyncV2Codec.text(device,1024)
        val next=JSONObject(st.toString());val note=next.getJSONObject("note");val conflicts=next.getJSONObject("conflicts").getJSONArray("records")
        val selected=(0 until conflicts.length()).map{conflicts.getJSONObject(it)}.single{it.getString("conflictId")==c.selectedConflictId}
        val source=when(choice){"local"->note.getString("markdown");"remote"->selected.getString("remoteMarkdown");else->manual?:SyncV2Codec.fail("invalid_schema")}
        SyncV2Codec.text(source,245760)
        val body=if(choice=="local")source else source.replace("\r\n","\n").replace('\r','\n')
        val old=SyncV2Wire.uint(note,"updatedAt");if(old==9007199254740991L)SyncV2Codec.fail("counter_exhausted")
        val hash=SyncV2Codec.hex(SyncV2Codec.sha(SyncV2Codec.utf8(body)))
        note.put("markdown",body).put("baseMarkdown",body).put("contentHash",hash).put("baseContentHash",hash).put("updatedAt",maxOf(now,old+1)).put("modifiedBy",device)
        derive?.invoke(note) // Product codec supplies plainText before the mandatory final budget.
        for(i in 0 until conflicts.length()) {val r=conflicts.getJSONObject(i);if(r.getString("conflictId") in c.openConflictIds)r.put("resolvedAt",maxOf(1,now))}
        next.put("stateVersion",SyncV2Codec.next(st.getString("stateVersion"))).put("localChangeRevision",SyncV2Codec.next(st.getString("localChangeRevision")))
        next.getJSONArray("outbox").put(JSONObject().put("kind","note-resolution").put("noteId",c.noteId).put("resolvedConflictIds",JSONArray(c.openConflictIds)))
        return next to budget(st,next)
    }
    fun budget(before:JSONObject,next:JSONObject):Budget {
        val payload=JSONObject();val note=next.getJSONObject("note")
        for(key in note.keys())if(key !in setOf("noteId","title","folderId","createdAt"))payload.put(key,note.get(key))
        val root=SyncV2NoteConflicts.attach(JSONObject().put("version",2).put("entityKey",next.optString("entityKey","0".repeat(64)))
            .put("requiredFeatures",JSONArray()).put("payload",payload).put("noteSegments",JSONArray()),note.getString("noteId"),next.optString("entityKey","0".repeat(64)),next.getJSONObject("conflicts"))
        fun size(value:Any?)=SyncV2Codec.utf8(SyncV2Codec.canonical(value)).size.toLong()
        fun add(a:Long,b:Long):Long {if(b<0||a>9007199254740991L-b)SyncV2Codec.fail("object_too_large");return a+b}
        val rootSize=size(root)
        val scope=JSONObject().put("repoId","0".repeat(32)).put("libraryEpoch",SyncV2Codec.maxD64.toString()).put("contentGeneration",SyncV2Codec.maxD64.toString())
        val refsSize=size(JSONObject().put("body",JSONObject().put("scope",scope).put("stateOid","state:sha256:"+"0".repeat(64)).put("segmentOids",JSONArray())).put("signature","A".repeat(86)))
        var closure=add(rootSize,4120+refsSize);val seen=mutableMapOf<String,Long>()
        val closures=if(next.has("resolutionClosures"))next.get("resolutionClosures") as? JSONArray?:SyncV2Codec.fail("invalid_schema") else JSONArray()
        for(i in 0 until closures.length()) {
            val item=closures.getJSONObject(i);SyncV2Codec.exact(item,setOf("oid","storedBytes"));val oid=SyncV2Wire.string(item,"oid");SyncV2Wire.oid(oid)
            val bytes=SyncV2Wire.uint(item,"storedBytes")
            if(seen.containsKey(oid)) {if(seen[oid]!=bytes)SyncV2Codec.fail("closure_incomplete")}else{seen[oid]=bytes;closure=add(closure,bytes)}
        }
        if(next.has("resolutionBodyClosuresReady")) {
            if(next.get("resolutionBodyClosuresReady") !is Boolean)SyncV2Codec.fail("invalid_schema")
            if(!next.getBoolean("resolutionBodyClosuresReady"))SyncV2Codec.fail("closure_incomplete")
        }
        val journal=add(add(size(before),size(next)),8752);val required=add(closure,journal)
        if(next.has("resolutionAvailableBytes")&&SyncV2Wire.uint(next,"resolutionAvailableBytes")<required)SyncV2Codec.fail("storage_pressure")
        return Budget(root,rootSize,rootSize+4120,refsSize,closure,journal,required)
    }
}
