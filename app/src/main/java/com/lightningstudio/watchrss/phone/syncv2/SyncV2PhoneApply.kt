package com.lightningstudio.watchrss.phone.syncv2

import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject

/** Room business rows and the projection state commit on the same SQLite connection.
 * Immutable original files are never deleted here. Unknown/new bodies remain placeholders. */
internal class SyncV2PhoneApply(private val db:SupportSQLiteDatabase) {
    private fun scalar(table:String,key:String,id:String,columns:String="*"):JSONObject? = db.query("SELECT $columns FROM `$table` WHERE `$key`=?",arrayOf(id)).use{c->
        if(!c.moveToFirst())null else JSONObject().also{v->for(i in 0 until c.columnCount)v.put(c.getColumnName(i),when {
            c.isNull(i)->JSONObject.NULL
            c.getType(i)==android.database.Cursor.FIELD_TYPE_INTEGER->c.getLong(i)
            c.getType(i)==android.database.Cursor.FIELD_TYPE_FLOAT->c.getDouble(i)
            else->c.getString(i)
        })}
    }
    private fun upsert(table:String,keys:List<String>,values:JSONObject) {
        val columns=db.query("PRAGMA table_info(`$table`)").use{c->buildList{while(c.moveToNext())add(Triple(c.getString(1),c.getString(2),c.getInt(3)!=0))}}
        val supplied=values.keys().asSequence().toSet()
        // A complete new Room row uses the existing storage types. Unknown wire fields never
        // become columns; projection sidecars retain their authenticated representation.
        fun argument(v:Any?):Any?=when(v){JSONObject.NULL->null;is Boolean->if(v)1L else 0L;else->v}
        val existing=db.query("SELECT 1 FROM `$table` WHERE ${keys.joinToString(" AND "){"`$it`=?"}}",keys.map{argument(values.get(it))}.toTypedArray()).use{it.moveToFirst()}
        if(existing) {
            val updates=columns.map{it.first}.filter{it in supplied && it !in keys}
            if(updates.isNotEmpty())db.execSQL("UPDATE `$table` SET ${updates.joinToString(","){"`$it`=?"}} WHERE ${keys.joinToString(" AND "){"`$it`=?"}}",(updates+keys).map{argument(values.get(it))}.toTypedArray())
            return
        }
        val row=columns.associate{(name,type,required)->name to when {
            name in supplied->values.get(name)
            !required->JSONObject.NULL
            type=="TEXT"->""
            else->0L
        }}
        val names=row.keys.toList();val updates=names.filter{it !in keys}
        db.execSQL("INSERT INTO `$table` (${names.joinToString(","){"`$it`"}}) VALUES(${names.joinToString(","){"?"}}) ON CONFLICT(${keys.joinToString(","){"`$it`"}}) DO UPDATE SET ${updates.joinToString(","){"`$it`=excluded.`$it`"}}",names.map { name ->when(val v=row[name]) {JSONObject.NULL->null;is Boolean->if(v)1L else 0L;else->v}}.toTypedArray())
    }
    fun validate(rows:List<JSONObject>) {
        val ids=mutableSetOf<String>()
        for(row in rows) {
            val meta=row.getJSONObject("meta");val value=row.getJSONObject("value");val kind=meta.getString("kind")
            if(!ids.add(meta.getString("entityKey")) || SyncV2Business.logicalId(kind,value)!=meta.getString("logicalId"))SyncV2Codec.fail("identity_conflict")
            val split=SyncV2Business.split(kind,meta.getString("logicalId"),meta.getString("entityKey"),value,null)
            SyncV2Business.join(kind,split.meta,split.state)
            if(kind!="rss-state" && SyncV2Schema.domains.none{it.kind==kind})SyncV2Codec.fail("unsupported_feature")
        }
    }
    /** Call only from accountWriter, after preparation and its final whole-state CAS. */
    fun apply(before:List<JSONObject>,target:List<JSONObject>) {
        check(db.inTransaction());validate(target)
        val keep=target.map{it.getJSONObject("meta").getString("entityKey")}.toSet()
        for(row in before) {
            val meta=row.getJSONObject("meta");if(meta.getString("entityKey") in keep)continue
            if(meta.getString("kind")=="rss-state")continue
            val d=SyncV2Schema.domains.single{it.kind==meta.getString("kind")};val id=meta.getString("logicalId")
            db.execSQL("DELETE FROM `${d.table}` WHERE (${d.key})=?",arrayOf(id))
            if(d.kind=="note")db.execSQL("DELETE FROM note_conflicts WHERE noteId=?",arrayOf(id))
            if(d.kind=="book")db.execSQL("DELETE FROM imported_book_chapters WHERE bookId=?",arrayOf(id))
        }
        val notes=target.filter{it.getJSONObject("meta").getString("kind")=="note"}
        for(record in target) {
            val meta=record.getJSONObject("meta");val kind=meta.getString("kind");val v=JSONObject(record.getJSONObject("value").toString())
            when(kind) {
                "article"->{
                    val old=scalar("phone_articles","articleId",v.getString("articleId"),"contentHash")
                    if(old==null || old.optString("contentHash")!=v.optString("contentHash")) {
                        v.put("contentText","").put("contentHtml",JSONObject.NULL)
                        for(k in listOf("syncBodyHash","syncChunkHashesJson","syncMetadataHash"))v.put(k,"")
                        for(k in listOf("syncBodyByteCount","syncChunkSize"))v.put(k,0)
                    }
                    upsert("phone_articles",listOf("articleId"),v)
                }
                "source"->upsert("phone_rss_sources",listOf("url"),v)
                "folder"->upsert("note_folders",listOf("folderId"),v)
                "book"->{
                    val old=scalar("imported_books","bookId",v.getString("bookId"))
                    val row=JSONObject().put("bookId",v.get("bookId")).put("stateJson",SyncV2LocalJson.canonical(v))
                    if(old==null)row.put("packageSha256","").put("packageBytes",0)
                    upsert("imported_books",listOf("bookId"),row)
                }
                "book-reading"->upsert("imported_book_reading",listOf("bookId","chapterIndex"),JSONObject().put("bookId",v.get("bookId")).put("chapterIndex",v.get("chapterIndex")).put("stateJson",SyncV2LocalJson.canonical(v)))
                "note"->{
                    val conflicts=v.optJSONObject("noteConflicts")?:SyncV2Codec.fail("unsupported_feature")
                    upsert("notes",listOf("noteId"),v)
                    // A confirmed cloud choice may replace the current conflict channel. The
                    // discarded local channel is part of the encrypted minimal recovery value.
                    db.execSQL("DELETE FROM note_conflicts WHERE noteId=?",arrayOf(v.getString("noteId")))
                    val a=conflicts.getJSONArray("records");for(i in 0 until a.length())upsert("note_conflicts",listOf("conflictId"),a.getJSONObject(i))
                }
                "asset"->{
                    val id=v.getString("assetId");val old=scalar("note_assets","assetId",id)
                    val linked=notes.firstOrNull{r->com.lightningstudio.watchrss.phone.connection.bluetooth.NoteAssetSyncPayload.referencedStorageKeys(r.getJSONObject("value").getString("markdown")).any{it.substringBeforeLast('.')==id}}
                    val storage=linked?.let{r->com.lightningstudio.watchrss.phone.connection.bluetooth.NoteAssetSyncPayload.referencedStorageKeys(r.getJSONObject("value").getString("markdown")).first{it.substringBeforeLast('.')==id}}?:"syncv2-${meta.getString("entityKey") }"
                    val row=JSONObject().put("assetId",id).put("mimeType",v.get("mime")).put("displayName",v.get("originalFilename"))
                        .put("createdAt",v.get("updatedAt")).put("deleted",v.get("deleted")).put("deletedAt",v.get("deletedAt"))
                    if(old==null)row.put("noteId",linked?.getJSONObject("value")?.getString("noteId")?:"").put("storageKey",storage)
                        .put("sha256","syncv2:${meta.getString("entityKey")}").put("byteCount",0).put("isOriginal",false)
                    upsert("note_assets",listOf("assetId"),row)
                }
            }
        }
        SyncV2RssApply(db,before+target).apply(target)
    }
}
