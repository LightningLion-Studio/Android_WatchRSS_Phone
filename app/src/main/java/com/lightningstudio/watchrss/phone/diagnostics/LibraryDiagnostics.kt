package com.lightningstudio.watchrss.phone.diagnostics

import androidx.sqlite.db.SupportSQLiteDatabase
import com.lightningstudio.watchrss.phone.data.local.ArticleContentStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import android.database.Cursor

interface DiagnosticQuery {fun query(sql:String,args:Array<String> = emptyArray()):Cursor}

/** Read existing materialization only. Never invoke book registration, cloud or repair. */
class LibraryDiagnostics(private val db:DiagnosticQuery,private val directory:File,private val content:ArticleContentStore,private val rejections:Map<String,String> = emptyMap()) {
    constructor(db:SupportSQLiteDatabase,directory:File,content:ArticleContentStore,rejections:Map<String,String> = emptyMap()):this(object:DiagnosticQuery {override fun query(sql:String,args:Array<String>)=db.query(sql,args)},directory,content,rejections)
    private fun count(sql:String)=db.query(sql).use{if(it.moveToFirst())it.getLong(0)else 0L}
    private fun pending(readBodies:Boolean=true):List<JSONObject> {
        val groups=linkedMapOf<String,MutableList<Triple<String,String,String>>>()
        db.query("SELECT a.articleId,a.url,a.rssSourceUrl,a.title,a.rssSourceTitle FROM phone_articles a WHERE a.deleted=0 AND (a.url LIKE 'https://watchrss.local/import-%' OR a.url LIKE 'watchrss://import/%') AND NOT EXISTS(SELECT 1 FROM imported_book_chapters c WHERE c.articleId=a.articleId) ORDER BY a.articleId").use{rows->while(rows.moveToNext()) {
            val url=rows.getString(1);if(!com.lightningstudio.watchrss.phone.data.model.ImportedContentIds.isImportedContentUrl(url))continue
            val single=com.lightningstudio.watchrss.phone.data.model.ImportedContentIds.isImportedTextArticleUrl(url)
            val key=if(single)url else rows.getString(2)?:url.substringBefore("/chapter/")
            groups.getOrPut(key){mutableListOf()}.add(Triple(rows.getString(0),rows.getString(3),if(single)rows.getString(3) else rows.getString(4)?:rows.getString(3)))
        }}
        return groups.values.map{group->val first=group.first();DiagnosticFields.book("",first.third,group.size,0,null,"staged","registration_pending",readBodies&&articleReadable(first.first))}
    }
    fun books():JSONArray=JSONArray().apply {
        val prefixCounts=mutableMapOf<String,Int>()
        db.query("SELECT bookId FROM imported_books").use{rows->while(rows.moveToNext()){val prefix=rows.getString(0).take(8);prefixCounts[prefix]=(prefixCounts[prefix]?:0)+1}}

        db.query("SELECT bookId,stateJson,packageSha256,packageBytes FROM imported_books ORDER BY bookId").use{rows->while(rows.moveToNext()) {
            val id=rows.getString(0);val state=runCatching{JSONObject(rows.getString(1))}.getOrNull();val sha=rows.getString(2);val bytes=rows.getLong(3)
            var reason="none";var readable=false
            try {
                if(state==null||DiagnosticFields.id(id)==null)reason="metadata_invalid"
                else if(!state.optBoolean("deleted")) {
                    val file=File(directory,"$id.jsonl")
                    reason=when { !file.isFile->"package_missing"; file.length()!=bytes->"package_hash_mismatch"; else->{val hash=MessageDigest.getInstance("SHA-256");file.inputStream().buffered().use{input->val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;hash.update(buffer,0,n)}};if(hash.digest().joinToString(""){"%02x".format(it)}==sha)"none" else "package_hash_mismatch"} }
                    readable=chapterReadable(id)
                    if(reason=="none"&&!readable)reason="body_missing"
                }
            }catch(_:Exception){reason="read_failed"}
            val wasRejected=reason=="package_missing"&&prefixCounts[id.take(8)]==1&&id.take(8) in rejections
            if(wasRejected)reason=requireNotNull(rejections[id.take(8)])
            if(state?.optBoolean("deleted")!=true)put(DiagnosticFields.book(id,state?.optString("title").orEmpty(),state?.optInt("chapterCount")?:0,bytes,sha,if(wasRejected)"failed" else if(reason=="none")"ready" else if(reason=="package_missing")"staged" else "failed",reason,readable,state))
        }}
        pending().forEach{put(it)}
    }
    private fun chapterReadable(id:String):Boolean {
        val article=db.query("SELECT articleId FROM imported_book_chapters WHERE bookId=? AND chapterIndex=1",arrayOf(id)).use{if(it.moveToFirst())it.getString(0)else null}?:return false
        return articleReadable(article)
    }
    private fun articleReadable(id:String)=db.query("SELECT substr(contentText,1,512),substr(contentHtml,1,512),deleted FROM phone_articles WHERE articleId=?",arrayOf(id)).use {
        if(!it.moveToFirst()||it.getInt(2)!=0)return@use false
        fun available(value:String)=if(content.isMarker(value)) content.loadTextChunk(value,0,4096)!=null else value.isNotEmpty()
        available(it.getString(0).orEmpty())||available(it.getString(1).orEmpty())
    }
    fun summary()=DiagnosticFields.summary(
        channels=count("SELECT count(*) FROM phone_rss_sources WHERE deleted=0"),articles=count("SELECT count(*) FROM phone_articles WHERE deleted=0"),
        favorites=count("SELECT count(*) FROM phone_articles WHERE deleted=0 AND favoriteSaved=1"),watchLater=count("SELECT count(*) FROM phone_articles WHERE deleted=0 AND watchLaterSaved=1"),
        memos=count("SELECT count(*) FROM notes WHERE deleted=0"),books=count("SELECT count(DISTINCT b.bookId) FROM imported_books b JOIN imported_book_chapters c ON c.bookId=b.bookId JOIN phone_articles a ON a.articleId=c.articleId WHERE a.deleted=0 AND CASE WHEN json_valid(b.stateJson) THEN COALESCE(json_extract(b.stateJson,'$.deleted'),0)=0 ELSE 1 END")+pending(false).size)
}
