package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONArray
import org.json.JSONObject

/** Single planning entry for account choices and the restricted legacy idempotent branch. */
internal object SyncV2Planner {
    fun canMerge(input:JSONObject):JSONObject {
        fun no(reason:String)=JSONObject().put("canMerge",false).put("reason",reason)
        if(input.optBoolean("unsupported"))return no("unsupported_feature")
        if(input.has("spaceAvailable") && input.get("spaceAvailable")==false)return no("storage_pressure")
        val base=input.getJSONObject("base");val local=input.getJSONObject("local");val cloud=JSONObject(input.getJSONObject("cloud").toString())
        val sets=try {SyncV2NoteConflicts.plannerSets(base,local,cloud,input.optJSONObject("noteConflictGenerations"))}catch(e:Exception) {
            return no(when(e.message){"note_conflict"->"note_conflict";"missing_base"->"missing_base";"conflict_identity_mismatch","identity_conflict"->"identity_conflict";else->"unsupported_feature"})
        }
        val ops=input.getJSONArray("ops")
        fun without(row:JSONObject)=JSONObject(row.toString()).apply{remove("noteConflicts")}
        fun same(a:Any?,b:Any?)=SyncV2Codec.canonical(a)==SyncV2Codec.canonical(b)
        for(i in 0 until ops.length()) {
            val op=ops.getJSONObject(i);val id=op.getString("id");val b=base.optJSONObject(id);val l=local.optJSONObject(id);val c=cloud.optJSONObject(id)
            val kind=op.getString("kind")
            if(kind !in setOf("create","update","delete","explicit-restore"))return no("unsupported_feature")
            if(op.opt("complete")!=true)return no("missing_content")
            if(kind=="create") {
                if(l==null)return no("missing_content")
                if(c!=null&&!same(without(c),without(l)))return no("identity_conflict")
                cloud.put(id,JSONObject(l.toString()));continue
            }
            if(b==null)return no("missing_base")
            if(kind=="explicit-restore") {
                if(l==null || !l.has("lifecycle") || c==null || !c.has("bookId") || l.getJSONObject("lifecycle").getLong("generation") <= (b.optJSONObject("lifecycle")?.optLong("generation")?:0))return no("delete_edit_conflict")
                try{cloud.put(id,SyncV2Business.mergeBook(c,l))}catch(_:Exception){return no("identity_conflict")};continue
            }
            if(c==null || c.optBoolean("deleted")) {if(kind=="delete")continue;return no("delete_edit_conflict")}
            if(kind=="delete") {if(!same(c,b))return no("delete_edit_conflict");c.put("deleted",true);continue}
            if(l==null)return no("missing_content")
            if(c.opt("identity")!=b.opt("identity") || l.opt("identity")!=b.opt("identity"))return no("identity_conflict")
            for(field in SyncV2Wire.strings(op.getJSONArray("fields"),1000,false)) {
                if(field=="noteConflicts")continue
                if(!b.has(field)||!l.has(field))return no("missing_base")
                if(field=="markdown") {
                    val result=SyncV2Business.mergeNote(b.getString(field),l.getString(field),c.getString(field))
                    if(result.has("conflict"))return no("note_conflict");c.put(field,result.getString("markdown"))
                } else if(same(c.opt(field),b.get(field)) || same(c.opt(field),l.get(field)))c.put(field,l.get(field)) else return no("identity_conflict")
            }
        }
        for((id,set)in sets) {
            val row=cloud.optJSONObject(id)
            if(row==null) {if(set.getJSONArray("records").length()!=0)return no("missing_content")}else row.put("noteConflicts",set)
        }
        return JSONObject().put("canMerge",true).put("tree",cloud)
    }
}

/** Instance-private proof capability; its serialized digest is never accepted as a proof. */
internal class SyncV2LegacyMerge {
    class Proof private constructor(val inboxKey:String,val capture:ChoiceCapture,val sourceHash:String,val snapshotsHash:String,val candidateHash:String) {
        companion object {internal fun create(key:String,c:ChoiceCapture,s:String,i:String,t:String)=Proof(key,c,s,i,t)}
    }
    private val proofs=java.util.IdentityHashMap<Proof,String>()
    private fun inputs(st:JSONObject,key:String):JSONObject {
        val account=st.getJSONObject("account");SyncV2Codec.exact(account,setOf("oid","revision","generation"))
        SyncV2Codec.commitOid(SyncV2Wire.string(account,"oid"));if(SyncV2Codec.d64(SyncV2Wire.string(account,"revision")).signum()==0)SyncV2Codec.fail("choice_stale")
        if(account.getString("generation")!=st.getString("rollbackGeneration"))SyncV2Codec.fail("choice_stale")
        for((k,value)in listOf("lastSeenAccountOid" to account.getString("oid"),"lastSeenAccountRevision" to account.getString("revision"),"lastSeen" to account.getString("generation")))if(st.has(k)&&st.get(k)!=value)SyncV2Codec.fail("choice_stale")
        val pending=st.optJSONObject("pendingChoice")?:SyncV2Codec.fail("choice_stale")
        if(pending.optString("generation")!=account.getString("generation") || key !in SyncV2Wire.strings(pending.getJSONArray("inboxKeys"),1000000,false))SyncV2Codec.fail("choice_stale")
        val base=st.optJSONObject("editBase")?:SyncV2Codec.fail("merge_not_safe")
        if(base.getString("generation")!=account.getString("generation"))SyncV2Codec.fail("merge_not_safe")
        val entry=st.getJSONObject("legacyInbox").optJSONObject(key)?:SyncV2Codec.fail("choice_stale")
        if(entry.getString("route")!="pendingChoice" || entry.optBoolean("applied"))SyncV2Codec.fail("choice_stale")
        val rows=st.getJSONObject("rows");val incoming=entry.getJSONObject("body")
        val intent=JSONObject().put("body",incoming).put("entityKeys",entry.getJSONArray("entityKeys"))
            .put("removedEntityKeys",entry.optJSONArray("removedEntityKeys")?:JSONArray()).put("bodyRefsByEntity",entry.optJSONObject("bodyRefsByEntity")?:JSONObject())
        if(SyncV2Codec.hash(intent)!=entry.getString("hash"))SyncV2Codec.fail("merge_not_safe")
        if(entry.optJSONArray("removedEntityKeys")?.length()!=0)SyncV2Codec.fail("merge_not_safe")
        val candidate=JSONObject(rows.toString());val ops=JSONArray()
        for(id in incoming.keys()) {
            val current=rows.optJSONObject(id)?:SyncV2Codec.fail("merge_not_safe")
            val value=incoming.getJSONObject(id)
            if(value.has("noteConflicts")) {
                val set=SyncV2NoteConflicts.validate(value.getJSONObject("noteConflicts"),value.getString("identity"))
                if(set.getJSONArray("records").length()!=0 || !current.has("noteConflicts"))SyncV2Codec.fail("merge_not_safe")
            }
            fun without(row:JSONObject)=JSONObject(row.toString()).apply{remove("noteConflicts")}
            if(SyncV2Codec.canonical(without(current))!=SyncV2Codec.canonical(without(value)))SyncV2Codec.fail("merge_not_safe")
            candidate.put(id,JSONObject(value.toString()))
        }
        val g=account.getString("generation")
        return JSONObject().put("base",rows).put("local",rows).put("cloud",candidate).put("ops",ops).put("unsupported",false)
            .put("noteConflictGenerations",JSONObject().put("base",g).put("local",g).put("cloud",g))
    }
    fun plan(st:JSONObject,key:String):Proof {
        val input=try{inputs(st,key)}catch(e:IllegalStateException){throw e}catch(_:Exception){SyncV2Codec.fail("merge_not_safe")};val result=SyncV2Planner.canMerge(input)
        if(!result.getBoolean("canMerge") || SyncV2Codec.canonical(result.get("tree"))!=SyncV2Codec.canonical(st.get("rows")))SyncV2Codec.fail("merge_not_safe")
        val a=st.getJSONObject("account")
        val c=ChoiceCapture(st.getString("stateVersion"),a.getString("oid"),a.getString("revision"),a.getString("generation"),st.getString("localChangeRevision"))
        ChoiceCapture.restore(c.json())
        return Proof.create(key,c,SyncV2Codec.hash(st),SyncV2Codec.hash(input),SyncV2Codec.hash(result.get("tree"))).also{proofs[it]=SyncV2Codec.canonical(input)}
    }
    fun check(st:JSONObject,key:String,proof:Proof):JSONObject {
        val input=proofs[proof]?:SyncV2Codec.fail("choice_stale")
        if(proof.inboxKey!=key || SyncV2Codec.hash(st)!=proof.sourceHash || SyncV2Codec.hash(inputs(st,key))!=proof.snapshotsHash)SyncV2Codec.fail("choice_stale")
        val result=SyncV2Planner.canMerge(JSONObject(input))
        if(!result.getBoolean("canMerge") || SyncV2Codec.hash(result.get("tree"))!=proof.candidateHash)SyncV2Codec.fail("merge_not_safe")
        return result.getJSONObject("tree")
    }
    fun consume(proof:Proof){proofs.remove(proof)}
}
