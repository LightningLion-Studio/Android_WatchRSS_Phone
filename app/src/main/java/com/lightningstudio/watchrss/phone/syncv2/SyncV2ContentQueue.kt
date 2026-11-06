package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONArray
import org.json.JSONObject

/** Durable, bounded metadata pages. No original body IO and no business writer or account lock.
 * A host window asks for one page, runs a bounded content worker and returns to the OS.
 * Completed recent imports keep their priority slots; this queue does not promote the third file. */
internal class SyncV2ContentQueue(private val store:SyncV2ContentStore) {
    data class Job(val row:JSONObject,val kind:String,val importedFile:Boolean,val addedAtMillis:Long,
        val parentKind:String?=null,val parentEntity:String?=null) {
        fun key()=row.getString("entityKey")+"/"+row.getString("bodyRevision")
    }
    fun enqueue(job:Job) {
        recover()
        val row=job.row;SyncV2Codec.unhex(row.getString("entityKey"),32);SyncV2Codec.unhex(row.getString("bodyRevision"),16)
        if(row.getBoolean("deleted"))SyncV2Codec.fail("body_revision_changed")
        val name="job/${job.key()}";val old=store.load(name)
        if(old!=null) {
            if(old.getJSONObject("row").get("rowSequence")!=row.get("rowSequence"))store.save(name,old.put("row",row))
            return
        }
        val head=store.load("queue")?:JSONObject().put("pages",1).put("cursor",0)
        var pageIndex=head.getInt("pages")-1
        var page=store.load("queue/$pageIndex")?:JSONObject().put("keys",JSONArray())
        // Write a recoverable insertion intent before publishing its page. Re-enqueue is idempotent.
        val body=JSONObject().put("row",row).put("kind",job.kind).put("importedFile",job.importedFile).put("addedAtMillis",job.addedAtMillis)
            .put("parentKind",job.parentKind?:JSONObject.NULL).put("parentEntity",job.parentEntity?:JSONObject.NULL).put("complete",false)
        val bytes=SyncV2Codec.utf8(SyncV2Codec.canonical(body)).size
        if(bytes>65536)SyncV2Codec.fail("object_too_large")
        if(page.getJSONArray("keys").length()>=64 || page.optInt("bytes",0)+bytes>65536) {
            pageIndex++;head.put("pages",pageIndex+1);page=JSONObject().put("keys",JSONArray())
        }
        page.put("bytes",page.optInt("bytes",0)+bytes)
        val keys=page.getJSONArray("keys");keys.put(job.key())
        val intent=JSONObject().put("jobKey",name).put("job",body).put("head",head).put("pageIndex",pageIndex).put("page",page)
        store.save("queue-insert",intent);recover();
    }
    private fun recover() {
        val intent=store.load("queue-insert")?:return
        if(intent.optBoolean("applied",false))return
        store.save(intent.getString("jobKey"),intent.getJSONObject("job"));store.save("queue/${intent.getInt("pageIndex")}",intent.getJSONObject("page"))
        store.save("queue",intent.getJSONObject("head"));store.save("queue-insert",JSONObject().put("applied",true))
    }
    fun page():List<JSONObject> {
        recover();val head=store.load("queue")?:return emptyList()
        val cursor=head.getInt("cursor");val keys=store.load("queue/$cursor")!!.getJSONArray("keys")
        val result=store.metadata((0 until keys.length()).map{"job/${keys.getString(it)}"}).map{it?:SyncV2Codec.fail("closure_incomplete")}
        store.save("queue",head.put("cursor",(cursor+1)%head.getInt("pages")))
        return result
    }
    fun complete(entity:String,revision:String) {
        val name="job/$entity/$revision";val job=store.load(name)?:SyncV2Codec.fail("body_revision_changed")
        store.save(name,job.put("complete",true))
    }
    fun demand(entity:String,revision:String,at:Long,expires:Long,now:Long) {
        SyncV2Codec.unhex(entity,32);SyncV2Codec.unhex(revision,16)
        if(at<0 || kotlin.math.abs(at-now)>30000 || expires<=now || expires-now>600000)SyncV2Codec.fail("invalid_schema")
        val old=store.load("demands")?:JSONObject().put("entries",JSONArray());val entries=old.getJSONArray("entries");val live=JSONArray()
        var previous:JSONObject?=null
        for(i in 0 until entries.length()) {
            val d=entries.getJSONObject(i);if(d.getLong("expiresAtMillis")<=now)continue
            if(d.getString("entityKey")==entity && d.getString("bodyRevision")==revision)previous=d else live.put(d)
        }
        if(live.length()>=32)SyncV2Codec.fail("rate_limited")
        val current=JSONObject().put("entityKey",entity).put("bodyRevision",revision).put("demandAtMillis",at).put("expiresAtMillis",expires)
        live.put(if(previous!=null && previous.getLong("demandAtMillis")>at)previous else current)
        store.save("demands",JSONObject().put("entries",live))
    }
    fun localPriorities(jobs:List<JSONObject>,recentUniverse:List<SyncV2ContentPriority.Item>,now:Long,includeDemands:Boolean=true):List<Pair<JSONObject,Int>> {
        val demand=store.load("demands")?.getJSONArray("entries")?:JSONArray()
        val active=(0 until demand.length()).map{demand.getJSONObject(it)}.filter{it.getLong("expiresAtMillis")>now}
        val items=jobs.map { j -> val row=j.getJSONObject("row");val d=if(!includeDemands)null else active.firstOrNull{it.get("entityKey")==row.get("entityKey") && it.get("bodyRevision")==row.get("bodyRevision")}
            SyncV2ContentPriority.Item(row.getString("entityKey"),j.getString("kind"),j.getLong("addedAtMillis"),j.getBoolean("importedFile"),j.getBoolean("complete"),row.getBoolean("deleted"),
                if(j.isNull("parentKind"))null else j.getString("parentKind"),if(j.isNull("parentEntity"))null else j.getString("parentEntity"),d?.getLong("demandAtMillis"),d?.getLong("expiresAtMillis"))
        }
        val universe=recentUniverse.filter{u->items.none{it.entityKey==u.entityKey}}+items
        val ranks=SyncV2ContentPriority.order(universe,now).associate{it.first.entityKey to it.second}
        return jobs.mapNotNull{j->ranks[j.getJSONObject("row").getString("entityKey")]?.let{j to it}}
    }
}
