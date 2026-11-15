package com.lightningstudio.watchrss.phone.syncv2

import androidx.sqlite.db.SupportSQLiteDatabase
import com.lightningstudio.watchrss.phone.data.book.BookSync
import org.json.JSONObject
import java.io.File

/** Original bytes are the offline queue. Downloaded SHA values never change business clocks. */
internal class SyncV2BodyRepository(private val db:SupportSQLiteDatabase,private val files:File,private val beforeInstall:(File)->Unit={}) {
    fun file(kind:String,id:String):File {
        SyncV2BodyCodec.name(kind,id)
        return File(files,if(kind=="asset")"notes/assets/$id"else"imported_books/$id.jsonl")
    }
    fun read(kind:String,id:String):ByteArray? {
        val f=file(kind,id);if(!f.isFile)return null
        if(f.length()>SyncV2BodyCodec.limit(kind))SyncV2Codec.fail("object_too_large")
        return f.readBytes().also{if(it.size>SyncV2BodyCodec.limit(kind))SyncV2Codec.fail("object_too_large")}
    }
    /** Discover referenced originals omitted by older private-UUID metadata. Absence never deletes. */
    fun refreshReferences(now:Long,checkOwner:()->Unit):List<String> {
        val errors=linkedSetOf<String>()
        for(id in references(db))try {
            checkOwner()
            val exists=db.controlQuery("SELECT 1 FROM note_assets WHERE storageKey=?",arrayOf(id)).use{it.moveToFirst()}
            if(exists)continue
            val raw=read("asset",id)?:SyncV2Codec.fail("cloud_note_asset_missing")
            val mime=when(id.substringAfterLast('.',"").lowercase()){ "png"->"image/png";"jpg","jpeg"->"image/jpeg";"webp"->"image/webp";"md"->"text/markdown";else->"application/octet-stream" }
            db.beginTransaction()
            try {
                checkOwner()
                if(!db.controlQuery("SELECT 1 FROM note_assets WHERE storageKey=?",arrayOf(id)).use{it.moveToFirst()})
                    SyncV2PhoneApply(db).upsert("note_assets",listOf("assetId"),JSONObject().put("assetId",java.util.UUID.randomUUID().toString()).put("noteId","")
                        .put("storageKey",id).put("mimeType",mime).put("displayName",id).put("sha256",SyncV2BodyCodec.hash(raw)).put("byteCount",raw.size)
                        .put("isOriginal",false).put("createdAt",now).put("deleted",false).put("deletedAt",0))
                db.setTransactionSuccessful()
            }finally{db.endTransaction()}
        }catch(e:Exception){checkOwner();errors.add(SyncV2BodyChannel.code(e))}
        return errors.toList()
    }
    fun validate(kind:String,id:String,value:JSONObject,raw:ByteArray) {
        SyncV2BodyCodec.name(kind,id);if(raw.size>SyncV2BodyCodec.limit(kind))SyncV2Codec.fail("object_too_large")
        if(value.getBoolean("deleted"))SyncV2Codec.fail("body_revision_changed")
        if(kind=="asset") {if(value.getString("assetId")!=id)SyncV2Codec.fail("source_identity_missing");return}
        val text=try{Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(raw)).toString()}catch(_:java.nio.charset.CharacterCodingException){SyncV2Codec.fail("book_package_invalid")}
        if(text.startsWith('\uFEFF') || !text.endsWith('\n') || text.contains("\r\n"))SyncV2Codec.fail("book_package_invalid")
        val lines=text.reader().buffered()
        try {
            val h=JSONObject(lines.readLine()?:SyncV2Codec.fail("book_package_invalid"))
            if(h.getString("format")!="watchrss-book" || h.get("version")!=1 || h.get("kind") !in setOf("txt","epub") || h.get("title") !is String || !bookUrl(h.getString("sourceUrl")))SyncV2Codec.fail("book_package_invalid")
            val count=SyncV2Wire.uint(h,"chapterCount",100000).toInt();if(count==0 || value.getInt("chapterCount")!=count)SyncV2Codec.fail("book_package_invalid")
            if(h.has("instanceId") && h.get("instanceId") !is String)SyncV2Codec.fail("book_package_invalid")
            val instance=h.optString("instanceId","");if(h.has("instanceId"))SyncV2Codec.unhex(instance,16)
            if(instance!=value.optString("instanceId",""))SyncV2Codec.fail("book_package_invalid")
            val hashes=ArrayList<String>(count)
            repeat(count){index->val c=JSONObject(lines.readLine()?:SyncV2Codec.fail("book_package_invalid"))
                if(SyncV2Wire.uint(c,"chapterIndex")!=index+1L || c.get("title") !is String || c.get("contentText") !is String || !bookUrl(c.getString("url")) || c.has("contentHtml")&&!c.isNull("contentHtml")&&c.get("contentHtml") !is String)SyncV2Codec.fail("book_package_invalid")
                hashes.add(BookSync.chapterHash(index+1,c.getString("title"),c.getString("contentText")))
            }
            if(lines.readLine()!=null)SyncV2Codec.fail("book_package_invalid")
            val fingerprint=BookSync.fingerprint(hashes)
            val bookId=if(instance.isEmpty())BookSync.id(fingerprint)else SyncV2BodyCodec.hash(SyncV2Codec.lp(SyncV2Codec.utf8("watchrss/book/instance-id/v1"),SyncV2Codec.utf8(fingerprint),SyncV2Codec.utf8(instance)))
            if(fingerprint!=value.getString("fingerprint") || id!=bookId || id!=value.getString("bookId"))SyncV2Codec.fail("book_package_invalid")
        } catch(e:org.json.JSONException){SyncV2Codec.fail("book_package_invalid")}finally{lines.close()}
    }
    /** Call under the account lifetime and projection writer, after full validation.
     * A crash can leave a verified original with an old cache marker; the next round repairs it. */
    fun install(kind:String,id:String,raw:ByteArray) {
        check(db.inTransaction());val target=file(kind,id);val hash=SyncV2BodyCodec.hash(raw)
        val bookState=if(kind=="book")db.controlQuery("SELECT stateJson FROM imported_books WHERE bookId=?",arrayOf(id)).use{c->if(!c.moveToFirst())SyncV2Codec.fail("body_revision_changed");JSONObject(c.getString(0))}else null
        if(bookState!=null)validate("book",id,bookState,raw)
        com.lightningstudio.watchrss.phone.data.local.ConditionalBodyFile.install(target,raw){beforeInstall(target)}
        if(kind=="asset")db.execSQL("UPDATE note_assets SET sha256=?,byteCount=? WHERE storageKey=? AND (sha256 IS NOT ? OR byteCount IS NOT ?)",arrayOf<Any>(hash,raw.size,id,hash,raw.size))
        else {
            materializeBook(id,raw,bookState!!)
            db.execSQL("UPDATE imported_books SET packageSha256=?,packageBytes=? WHERE bookId=? AND (packageSha256 IS NOT ? OR packageBytes IS NOT ?)",arrayOf<Any>(hash,raw.size,id,hash,raw.size))
        }
    }
    /** Derived chapter bodies use current Room state, never save/reading fields from the carrier. */
    private fun materializeBook(id:String,raw:ByteArray,state:JSONObject) {
        val deleted=state.optJSONArray("deletedChapters")?.let{a->(0 until a.length()).map{a.getInt(it)}.toSet()}?:emptySet()
        val content=com.lightningstudio.watchrss.phone.data.local.FileArticleContentStore(File(files,"imported_text"))
        raw.inputStream().bufferedReader(Charsets.UTF_8).use{lines->val header=JSONObject(lines.readLine()!!)
            repeat(state.getInt("chapterCount")){i->val chapter=JSONObject(lines.readLine()!!);val index=i+1
                val oldId=db.controlQuery("SELECT articleId FROM imported_book_chapters WHERE bookId=? AND chapterIndex=?",arrayOf<Any>(id,index)).use{c->if(c.moveToFirst())c.getString(0)else null}
                    ?:db.controlQuery("SELECT articleId FROM phone_articles WHERE url=? ORDER BY articleId LIMIT 1",arrayOf(chapter.getString("url"))).use{c->if(c.moveToFirst())c.getString(0)else null}
                val article=oldId?:BookSync.articleId(id,index)
                val old=db.controlQuery("SELECT substr(contentText,1,256),length(contentText) FROM phone_articles WHERE articleId=?",arrayOf(article)).use{c->if(c.moveToFirst())c.getString(0) to c.getLong(1)else null}
                val marker=old?.first?.let{com.lightningstudio.watchrss.phone.data.local.articleContentMarkerFileName(it)}
                val missing=old==null || old.second==0L || marker!=null&&!File(files,"imported_text/$marker").isFile
                val row=JSONObject().put("articleId",article)
                if(old==null) {
                    val tombstone=state.optBoolean("deleted") || index in deleted
                    row.put("sourceDeviceId",state.optString("sourceDeviceId")).put("url",chapter.getString("url")).put("title",chapter.getString("title"))
                        .put("siteName",state.optString("title",header.getString("title"))).put("rssSourceUrl",header.getString("sourceUrl")).put("rssSourceTitle",state.optString("title",header.getString("title")))
                        .put("importedAt",1).put("updatedAt",1).put("deleted",tombstone).put("deletedAt",if(tombstone)maxOf(state.optLong("deletedAt"),state.optLong("deletionClock"))else 0)
                    val reading=db.controlQuery("SELECT stateJson FROM imported_book_reading WHERE bookId=? AND chapterIndex=?",arrayOf<Any>(id,index)).use{c->if(c.moveToFirst())JSONObject(c.getString(0))else null}
                    if(reading!=null)for(field in listOf("isRead","readingProgress","readingPositionBytes","readingPositionContentHash","readingPositionChangedAt","favoriteSaved","favoriteChangedAt","favoriteSortOrder","watchLaterSaved","watchLaterChangedAt","watchLaterSortOrder"))if(reading.has(field))row.put(field,reading.get(field))
                }
                if(missing) {
                    val text=chapter.getString("contentText")
                    val textBytes=text.toByteArray(Charsets.UTF_8)
                    val stored=if(text.length>50000) {
                        // Hydration is a cache write: an immutable private marker must not bump the edit witness.
                        val marker=content.markerFor("syncv2-body-${SyncV2BodyCodec.hash(textBytes)}")
                        val target=File(files,"imported_text/${com.lightningstudio.watchrss.phone.data.local.articleContentMarkerFileName(marker)!!}")
                        com.lightningstudio.watchrss.phone.data.local.ConditionalBodyFile.install(target,textBytes)

                        marker
                    }else text
                    val missingHtml=old==null || db.controlQuery("SELECT contentHtml IS NULL OR length(contentHtml)=0 FROM phone_articles WHERE articleId=?",arrayOf(article)).use{it.moveToFirst() && it.getInt(0)!=0}
                    if(missingHtml)row.put("contentHtml",chapter.opt("contentHtml")?:JSONObject.NULL)
                    row.put("contentText",stored).put("contentHash",SyncV2BodyCodec.hash(textBytes))
                        .put("syncBodyHash","").put("syncBodyByteCount",0).put("syncChunkSize",0).put("syncChunkHashesJson","").put("syncMetadataHash","")
                }
                if(old==null || missing)SyncV2PhoneApply(db).upsert("phone_articles",listOf("articleId"),row)
                db.execSQL("INSERT INTO imported_book_chapters(bookId,chapterIndex,articleId) VALUES(?,?,?) ON CONFLICT(bookId,chapterIndex) DO UPDATE SET articleId=excluded.articleId WHERE articleId IS NOT excluded.articleId",arrayOf<Any>(id,index,article))
            }
        }
    }
    companion object {
        private fun bookUrl(url:String)=Regex("(?i)^https://watchrss\\.local/(?:import-txt-novel(?:/|$)|import-epub(?:/|$)|import-content/txt/)").containsMatchIn(url) ||
            Regex("(?i)watchrss://import/online-v1/[0-9a-f]{64}").matches(url) || Regex("watchrss://import/instance-v1/[a-f0-9]{32}(?:/|$)").find(url)?.range?.first==0
        /** Same literal link/image/HTML mapping as PodJS notes/cloud-assets.ts. */
        private val reference=Regex("""<(?:img|a)\b[^>]*\b(?:src|href)\s*=\s*(?:"assets/([^"<>]+)"|'assets/([^'<>]+)'|assets/([^\s<>]+))[^>]*>|!?\[[^\]]*\]\(assets/([^)]*)\)""",RegexOption.IGNORE_CASE)
        fun assetKeys(markdown:String):Set<String> = reference.findAll(markdown).mapNotNull{m->
            val id=m.groupValues.drop(1).firstOrNull{it.isNotEmpty()}
            id?.takeIf{Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,254}").matches(it)}
        }.toCollection(linkedSetOf())
        fun noteReferences(value:JSONObject):Set<String> {
            val refs=linkedSetOf<String>()
            for(k in listOf("markdown","baseMarkdown"))value.optString(k).let{refs.addAll(assetKeys(it))}
            value.optJSONObject("noteConflicts")?.optJSONArray("records")?.let{a->for(i in 0 until a.length())for(k in listOf("baseMarkdown","localMarkdown","remoteMarkdown"))refs.addAll(assetKeys(a.getJSONObject(i).optString(k)))}
            return refs
        }
        fun references(db:SupportSQLiteDatabase):Set<String> = buildSet {
            db.controlQuery("SELECT markdown,baseMarkdown FROM notes WHERE deleted=0").use{c->while(c.moveToNext())for(i in 0..1)addAll(assetKeys(c.getString(i)))}
            db.controlQuery("SELECT baseMarkdown,localMarkdown,remoteMarkdown FROM note_conflicts").use{c->while(c.moveToNext())for(i in 0..2)addAll(assetKeys(c.getString(i)))}
        }
    }
}
