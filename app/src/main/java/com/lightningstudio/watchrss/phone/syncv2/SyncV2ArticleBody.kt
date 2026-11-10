package com.lightningstudio.watchrss.phone.syncv2

import androidx.sqlite.db.SupportSQLiteDatabase
import com.lightningstudio.watchrss.phone.data.local.articleContentMarkerFileName
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Byte mapping pinned to WatchRSS_WeChat 27a71ac, article-body.js.
 * Text wins over HTML; UTF-8 is unchanged. Books/assets never produce a recipe in P6. */
internal object SyncV2ArticleBody {
    fun revision(entity:String,digest:String):String = SyncV2Codec.hex(SyncV2Codec.sha(SyncV2Codec.utf8(
        SyncV2Codec.canonical(JSONArray().put("wrss.sync.v2/article-body").put(entity).put(digest))))).take(32)
    fun placeholder(kind:String):JSONObject?=if(kind in setOf("book","asset"))
        JSONObject().put("placeholder",true).put("reason","body_not_synced").put("keepLocalBody",true) else null

    class Source(private val readPart:(Long,Int)->ByteArray,override val size:Long,private val check:()->Unit):SyncV2ContentExport.Source {
        val sha256:String
        init {
            if(size !in 1..536870912L)SyncV2Codec.fail("object_too_large")
            val digest=MessageDigest.getInstance("SHA-256");var at=0L
            while(at<size){val raw=read(at,minOf(65536L,size-at).toInt());digest.update(raw);at+=raw.size}
            sha256=SyncV2Codec.hex(digest.digest())
        }
        override val version get()=sha256
        override fun read(offset:Long,maxBytes:Int):ByteArray {
            check();val result=readPart(offset,maxBytes);check()
            if(result.size!=maxBytes)SyncV2Codec.fail("body_revision_changed")
            return result
        }
    }
    fun source(db:SupportSQLiteDatabase,files:File,articleId:String,check:()->Unit):Source? {
        check()
        // Only fetch a bounded prefix. A long DB body must never fill one CursorWindow.
        val fields=db.controlQuery("SELECT substr(contentText,1,256),length(CAST(contentText AS BLOB)),substr(contentHtml,1,256),length(CAST(contentHtml AS BLOB)) FROM phone_articles WHERE articleId=?",arrayOf(articleId)).use {
            if(!it.moveToFirst())return null
            val textBytes=it.getLong(1)
            if(textBytes>0)Triple("contentText",it.getString(0),textBytes)
            else Triple("contentHtml",if(it.isNull(2))""else it.getString(2),it.getLong(3))
        }
        val marker=articleContentMarkerFileName(fields.second)
        if(marker!=null) {
            val directory=File(files,"imported_text").canonicalFile;val file=File(directory,marker).canonicalFile
            if(file.parentFile!=directory || !file.isFile)return null
            val size=file.length();val modified=file.lastModified()
            return Source({offset,n->ByteArray(n).also{raw->RandomAccessFile(file,"r").use{it.seek(offset);it.readFully(raw)}}},size) {
                check();if(file.length()!=size || file.lastModified()!=modified)SyncV2Codec.fail("body_revision_changed")
            }
        }
        val field=fields.first
        val size=fields.third
        if(size==0L)return null
        return Source({offset,n->db.controlQuery("SELECT substr(CAST($field AS BLOB),?,?) FROM phone_articles WHERE articleId=?",arrayOf<Any>(offset+1,n,articleId)).use {
            if(!it.moveToFirst() || it.isNull(0))SyncV2Codec.fail("body_revision_changed");it.getBlob(0)
        }},size,check)
    }
}
