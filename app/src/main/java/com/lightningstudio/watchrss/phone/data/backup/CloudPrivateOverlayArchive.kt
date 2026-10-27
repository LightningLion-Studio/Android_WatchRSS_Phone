package com.lightningstudio.watchrss.phone.data.backup

import com.lightningstudio.watchrss.phone.cloud.*
import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** Existing WRSS v3 codec, restricted to one original article including its HTML. */
internal object CloudPrivateOverlayArchive {
    fun signature(row:PhoneArticleEntity):String = CloudSnapshotCodec.sha256(PhoneCloudRecordCodec.canonical(JSONObject()
        .put("articleId",row.articleId).put("url",row.url).put("title",row.title).put("contentHash",row.contentHash)
        .put("importedAt",row.importedAt).put("rssSourceUrl",row.rssSourceUrl ?: JSONObject.NULL)
        .put("text",CloudSnapshotCodec.sha256(row.contentText.toByteArray())).put("html",row.contentHtml?.let{CloudSnapshotCodec.sha256(it.toByteArray())} ?: JSONObject.NULL)).toByteArray())
    fun encode(row:PhoneArticleEntity, priorRaw:String? = null):ByteArray {
        val snapshot=WatchRssBackupSnapshot(1,"",3,emptyList(),listOf(CloudPrivateArchive.article(row)),emptyList(),cloudContentOnly=true)
        val encoded=ByteArrayOutputStream().also{WatchRssBackupArchive.write(snapshot,it)}.toByteArray()
        val output=ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(output).use { zip -> java.util.zip.ZipInputStream(encoded.inputStream()).use { input ->
            while(true) {val entry=input.nextEntry ?: break;val bytes=input.readBytes()
                if(entry.name=="app_meta.json") {require(JSONArray(bytes.toString(Charsets.UTF_8)).length()==0);continue}
                val content=if(entry.name=="articles.json" && priorRaw!=null) {
                    val known=JSONArray(bytes.toString(Charsets.UTF_8)).getJSONObject(0)
                    ("["+CloudRawDocument.parse(priorRaw).merge(emptyList(),known).source+"]").toByteArray()
                }else bytes
                zip.putNextEntry(java.util.zip.ZipEntry(entry.name).apply{time=0});zip.write(content);zip.closeEntry()
            }
        }}
        return output.toByteArray().also{validate(it,row.articleId)}
    }
    fun rawArticles(archive:ByteArray):Map<String,String> {
        java.util.zip.ZipInputStream(archive.inputStream()).use { input ->
            while(true) {val entry=input.nextEntry ?: break
                if(entry.name!="articles.json")continue
                val raw=input.readBytes().toString(Charsets.UTF_8)
                val document=CloudRawDocument.parse("{\"rows\":"+raw+"}")
                return requireNotNull(document.node(listOf("rows"))?.elements).associate { node ->
                    val text=document.source.substring(node.start,node.end)
                    JSONObject(text).getString("articleId") to text
                }
            }
        }
        error("missing_article_metadata")
    }
    fun validate(bytes:ByteArray,id:String):WatchRssBackupSnapshot {
        val metadata=rawArticles(bytes)
        require(metadata.size==1 && metadata.containsKey(id))
        val row=JSONObject(metadata.getValue(id))
        val allowed=setOf("manifest.json","sources.json","saved_items.json","articles.json","app_meta.json")+
            listOf("contentTextEntry","contentHtmlEntry").mapNotNull{key->if(row.has(key)&&!row.isNull(key))row.getString(key)else null}
        java.util.zip.ZipInputStream(bytes.inputStream()).use { input ->
            while(true) {val entry=input.nextEntry ?: break;require(entry.name in allowed) { "micro_archive_extra_entry" }}
        }
        val snapshot=WatchRssBackupArchive.read(bytes.inputStream(),tolerateCloudEntries=true)
        try {
            require(snapshot.cloudContentOnly && snapshot.dataStructureVersion==3 && snapshot.articles.size==1 && snapshot.articles.single().articleId==id)
            require(snapshot.sources.isEmpty() && snapshot.savedItems.isEmpty() && snapshot.appMeta.isEmpty() && snapshot.readerSnapshot==null && snapshot.readerResources.isEmpty() && snapshot.bookPackages.isEmpty())
            require(snapshot.readWarnings.isEmpty() && !snapshot.articles.single().deleted)
            require(snapshot.articles.single().contentText.isNotBlank() || !snapshot.articles.single().contentHtml.isNullOrBlank())
            return snapshot
        } catch(error:Exception) { snapshot.bookPackages.forEach{it.file.delete()};throw error }
    }
}
