package com.lightningstudio.watchrss.phone.syncv2

import androidx.sqlite.db.SupportSQLiteDatabase
import com.lightningstudio.watchrss.phone.cloud.CloudRssSourceIdentity
import org.json.JSONObject

/** Reuses the existing sourceKey + SHA256(articleId) identity rule. Unknown public
 * articles remain authenticated state records; no synthetic article ID or body is invented. */
internal class SyncV2RssApply(private val db:SupportSQLiteDatabase,records:List<JSONObject>) {
    private val fields=SyncV2Business.rssStateFields-setOf("sourceId","articleIdHash")
    private val found=linkedMapOf<String,Pair<String,JSONObject>>()
    init {
        val sourceIds=records.filter{it.getJSONObject("meta").getString("kind")=="source"}.groupBy{it.getJSONObject("value").getString("url")}
        val cache=mutableMapOf<String,List<String>>()
        val columns=(listOf("articleId","rssSourceUrl")+fields.sorted()).distinct()
        db.query("SELECT ${columns.joinToString(","){"`$it`"}} FROM phone_articles WHERE rssSourceUrl IS NOT NULL ORDER BY articleId").use{c->while(c.moveToNext()) {
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
    fun apply(records:List<JSONObject>) {
        check(db.inTransaction())
        for(record in records)if(record.getJSONObject("meta").getString("kind")=="rss-state") {
            val v=record.getJSONObject("value");val row=found[v.getString("sourceId")+":"+v.getString("articleIdHash")]?:continue
            val present=fields.filter(v::has).sorted();if(present.isEmpty())continue
            db.execSQL("UPDATE phone_articles SET ${present.joinToString(","){"`$it`=?"}} WHERE articleId=?",(present.map{when(val value=v.get(it)){is Boolean->if(value)1L else 0L;JSONObject.NULL->null;else->value}}+row.first).toTypedArray())
        }
    }
}
