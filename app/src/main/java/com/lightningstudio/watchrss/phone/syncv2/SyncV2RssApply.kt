package com.lightningstudio.watchrss.phone.syncv2

import androidx.sqlite.db.SupportSQLiteDatabase
import com.lightningstudio.watchrss.phone.cloud.CloudRssSourceIdentity
import org.json.JSONObject

/** Reuses the existing sourceKey + SHA256(articleId) identity rule. Unknown public
 * articles remain authenticated state records; no synthetic article ID or body is invented. */
internal class SyncV2RssApply(private val db:SupportSQLiteDatabase,records:List<JSONObject>) {
    private val fields=SyncV2Business.rssStateFields-setOf("sourceId","articleIdHash")
    private val found=linkedMapOf<String,Pair<String,JSONObject>>()
    val changedArticleIds=linkedSetOf<String>()
    init {
        val sourceIds=records.filter{it.getJSONObject("meta").getString("kind")=="source"}.groupBy{it.getJSONObject("value").getString("url")}
        val cache=mutableMapOf<String,List<String>>()
        val columns=(listOf("articleId","rssSourceUrl")+fields.sorted()).distinct()
        if(records.any{it.getJSONObject("meta").getString("kind")=="rss-state"})db.query("SELECT ${columns.joinToString(","){"`$it`"}} FROM phone_articles WHERE rssSourceUrl IS NOT NULL ORDER BY articleId").use{c->while(c.moveToNext()) {
            val id=c.getString(0);val url=c.getString(1)
            val ids=cache.getOrPut(url) {
                if(CloudRssSourceIdentity.isLocalOnly(url))emptyList()else runCatching{listOf(CloudRssSourceIdentity.sourceKey(url))+sourceIds[url].orEmpty().mapNotNull{it.getJSONObject("value").optString("sourceId").takeIf(String::isNotEmpty)}}.getOrDefault(emptyList())
            }
            val value=JSONObject()
            for(i in 2 until c.columnCount) {
                val name=c.getColumnName(i);value.put(name,when {
                    c.isNull(i)->JSONObject.NULL
                    name in setOf("isRead","favoriteSaved","watchLaterSaved","deleted")->c.getInt(i)!=0
                    c.getType(i)==android.database.Cursor.FIELD_TYPE_INTEGER->c.getLong(i)
                    c.getType(i)==android.database.Cursor.FIELD_TYPE_FLOAT->c.getDouble(i)
                    else->c.getString(i)
                })
            }
            val hash=SyncV2Codec.hex(SyncV2Codec.sha(SyncV2Codec.utf8(id)))
            for(source in ids)found["$source:$hash"]=id to value
        }}
    }
    fun footprint(value:JSONObject):JSONObject?=found[value.getString("sourceId")+":"+value.getString("articleIdHash")]?.second?.let{JSONObject(it.toString())}
    /** Virtual published footprints for prefix reconciliation; reuse this lookup and
     * already supplied records, with no per-entity public-library scan. */
    fun baselineStates(records:List<JSONObject>):Map<String,JSONObject> {
        val byKey=found.mapValues{it.value.first}.toMutableMap()
        val sources=records.filter{it.getJSONObject("meta").getString("kind")=="source"}.groupBy{it.getJSONObject("value").getString("url")}
        for(record in records)if(record.getJSONObject("meta").getString("kind")=="article") {
            val v=record.getJSONObject("value");val url=v.optString("rssSourceUrl").takeIf{it.isNotEmpty() && it!="null"}?:continue
            if(CloudRssSourceIdentity.isLocalOnly(url))continue
            val hash=SyncV2Codec.hex(SyncV2Codec.sha(SyncV2Codec.utf8(v.getString("articleId"))))
            val ids=runCatching{listOf(CloudRssSourceIdentity.sourceKey(url))+sources[url].orEmpty().mapNotNull{it.getJSONObject("value").optString("sourceId").takeIf(String::isNotEmpty)}}.getOrDefault(emptyList())
            for(id in ids)byKey["$id:$hash"]=v.getString("articleId")
        }
        val out=linkedMapOf<String,JSONObject>()
        for(record in records)if(record.getJSONObject("meta").getString("kind")=="rss-state") {
            val v=record.getJSONObject("value");val id=byKey[v.getString("sourceId")+":"+v.getString("articleIdHash")]?:continue
            val value=out.getOrPut(id){JSONObject()};for(field in fields)if(v.has(field))value.put(field,v.get(field))
        }
        return out
    }
    fun apply(records:List<JSONObject>,before:List<JSONObject> = emptyList()) {
        check(db.inTransaction())
        fun update(value:JSONObject,allowed:Set<String> = fields) {
            val row=found[value.getString("sourceId")+":"+value.getString("articleIdHash")]?:return
            val changed=allowed.filter{value.has(it) && SyncV2LocalJson.canonical(value.opt(it))!=SyncV2LocalJson.canonical(row.second.opt(it))}.sorted()
            if(changed.isEmpty())return
            db.execSQL("UPDATE phone_articles SET ${changed.joinToString(","){"`$it`=?"}} WHERE articleId=?",(changed.map{when(val v=value.get(it)){is Boolean->if(v)1L else 0L;JSONObject.NULL->null;else->v}}+row.first).toTypedArray())
            changedArticleIds.add(row.first)
            for(field in changed)row.second.put(field,value.get(field))
        }
        val states=records.filter{it.getJSONObject("meta").getString("kind")=="rss-state"}
        val present=states.map{it.getJSONObject("meta").getString("entityKey")}.toSet()
        val articles=records.filter{it.getJSONObject("meta").getString("kind")=="article"}.associateBy{it.getJSONObject("value").getString("articleId")}
        for(old in before)if(old.getJSONObject("meta").getString("kind")=="rss-state" && old.getJSONObject("meta").getString("entityKey") !in present) {
            val value=old.getJSONObject("value");val actual=found[value.getString("sourceId")+":"+value.getString("articleIdHash")]?:continue
            val personal=articles[actual.first]?.getJSONObject("value")
            val reset=JSONObject().put("sourceId",value.getString("sourceId")).put("articleIdHash",value.getString("articleIdHash"))
            // Removing an interaction state keeps the public article's original ID/body.
            // An explicit personal target remains authoritative for its own registers.
            for(field in fields-setOf("sourceDeviceId"))reset.put(field,if(personal?.has(field)==true)personal.get(field)else when(field) {
                "isRead","favoriteSaved","watchLaterSaved","deleted"->false
                "readingProgress"->0.0
                "readingPositionContentHash"->""
                else->0L
            })
            update(reset,fields-setOf("sourceDeviceId"))
        }
        for(record in states)update(record.getJSONObject("value"))
    }
}
