package com.lightningstudio.watchrss.phone.diagnostics

import androidx.sqlite.db.SupportSQLiteDatabase
import com.lightningstudio.watchrss.phone.data.book.BookPackageFiles
import com.lightningstudio.watchrss.phone.data.local.ArticleContentStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import android.database.Cursor

interface DiagnosticQuery {fun query(sql:String,args:Array<String> = emptyArray()):Cursor}

/** Read existing materialization only. Never invoke book registration, cloud or repair. */
class LibraryDiagnostics(private val db:DiagnosticQuery,private val directory:File,private val content:ArticleContentStore,private val rejections:Map<String,String> = emptyMap()) {
    constructor(db:SupportSQLiteDatabase,directory:File,content:ArticleContentStore,rejections:Map<String,String> = emptyMap()):this(object:DiagnosticQuery {override fun query(sql:String,args:Array<String>)=db.query(sql,args)},directory,content,rejections)
    private class HashBudgetExceeded : RuntimeException()

    private fun count(sql:String)=db.query(sql).use{if(it.moveToFirst())it.getLong(0)else 0L}
    private val single="(lower(a.url) LIKE 'https://watchrss.local/import-content/txt/%' OR (lower(a.url) GLOB 'watchrss://import/online-v1/*' AND length(a.url)=92 AND substr(lower(a.url),29) NOT GLOB '*[^a-f0-9]*'))"
    private val groups="SELECT min(a.articleId) first_id,count(*) chapters FROM phone_articles a WHERE a.deleted=0 AND (lower(a.url) LIKE 'https://watchrss.local/import-content%' OR lower(a.url) LIKE 'https://watchrss.local/import-epub%' OR lower(a.url) LIKE 'https://watchrss.local/import-txt-novel%' OR $single) AND NOT EXISTS(SELECT 1 FROM imported_book_chapters c WHERE c.articleId=a.articleId) GROUP BY CASE WHEN $single THEN a.url ELSE COALESCE(a.rssSourceUrl,CASE WHEN instr(a.url,'/chapter/')>0 THEN substr(a.url,1,instr(a.url,'/chapter/')-1) ELSE a.url END) END"
    private val live="CASE WHEN json_valid(stateJson) THEN COALESCE(json_extract(stateJson,'$.deleted'),0)<>1 ELSE 1 END"
    private fun pending(offset:Int,limit:Int,check:()->Unit):List<JSONObject> {
        check()
        return db.query("SELECT g.first_id,substr(CASE WHEN $single THEN a.title ELSE COALESCE(a.rssSourceTitle,a.title) END,1,512),g.chapters FROM ($groups) g JOIN phone_articles a ON a.articleId=g.first_id ORDER BY g.first_id LIMIT ? OFFSET ?",arrayOf(limit.toString(),offset.toString())).use{rows->buildList {while(rows.moveToNext()){check();add(DiagnosticFields.book("",rows.getString(1).orEmpty(),rows.getInt(2),0,null,"staged","registration_pending",articleReadable(rows.getString(0))))}}}
    }
    /** Bounded SQL projection and bounded streaming hash. Cursor never carries full state JSON. */
    fun books(offset:Int=0,limit:Int=12,checkCancelled:()->Unit={}):JSONArray=JSONArray().apply {
        require(offset>=0&&limit in 1..13)
        val deadline=System.nanoTime()+8_000_000_000L
        var hashBudget=64L*1024*1024
        fun active(){checkCancelled();check(!Thread.currentThread().isInterrupted&&System.nanoTime()<deadline){"diagnostics_budget_exceeded"}}
        active();val registered=count("SELECT count(*) FROM imported_books WHERE $live")
        active()
        val projection="CASE WHEN json_valid(stateJson) AND json_type(stateJson)='object' THEN json_object('title',substr(json_extract(stateJson,'$.title'),1,512),'fingerprint',CASE WHEN length(json_extract(stateJson,'$.fingerprint'))=64 THEN json_extract(stateJson,'$.fingerprint') END,'chapterCount',CAST(json_extract(stateJson,'$.chapterCount') AS INTEGER),'deleted',json_extract(stateJson,'$.deleted')) END"
        db.query("SELECT bookId,$projection stateJson,packageSha256,packageBytes FROM imported_books WHERE $live ORDER BY bookId LIMIT ? OFFSET ?",arrayOf(limit.toString(),offset.toString())).use{rows->while(rows.moveToNext()) {
            active();val id=rows.getString(0);val state=runCatching{JSONObject(rows.getString(1))}.getOrNull();val sha=rows.getString(2);val bytes=rows.getLong(3)
            var reason="none";var readable=false
            try {
                if(state==null||DiagnosticFields.id(id)==null)reason="metadata_invalid"
                else {
                    val revision = BookPackageFiles.Revision(sha, bytes)
                    val publication = BookPackageFiles.publication(id, db::query)
                    val candidates = BookPackageFiles.candidates(directory, id, revision, publication).filter { it.isFile }
                    reason = when {
                        candidates.isEmpty() -> "package_missing"
                        candidates.none { it.length() == bytes } -> "package_hash_mismatch"
                        bytes > hashBudget -> "hash_budget_exceeded"
                        else -> try {
                            val original = BookPackageFiles.resolve(directory, id, revision, publication) { read ->
                                active()
                                hashBudget -= read
                                if (hashBudget < 0) throw HashBudgetExceeded()
                            }
                            if (original == null) "package_hash_mismatch" else "none"
                        } catch (_: HashBudgetExceeded) {
                            "hash_budget_exceeded"
                        }
                    }
                    active();readable=chapterReadable(id)
                    if(reason=="none"&&!readable)reason="body_missing"
                }
            }catch(e:IllegalStateException){throw e}catch(_:Exception){reason="read_failed"}
            active()
            val wasRejected=reason=="package_missing"&&id.take(8) in rejections&&db.query("SELECT count(*) FROM imported_books WHERE substr(bookId,1,8)=?",arrayOf(id.take(8))).use{it.moveToFirst()&&it.getInt(0)==1}
            if(wasRejected)reason=requireNotNull(rejections[id.take(8)])
            put(DiagnosticFields.book(id,state?.optString("title").orEmpty(),state?.optInt("chapterCount")?:0,bytes,sha,if(wasRejected)"failed" else if(reason=="none")"ready" else if(reason in setOf("package_missing","hash_budget_exceeded"))"staged" else "failed",reason,readable,state))
        }}
        if(length()<limit)pending(maxOf(0,offset-registered.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()),limit-length(),::active).forEach{put(it)}
    }
    fun page(offset:Int=0,limit:Int=8,check:()->Unit={}):JSONObject {
        require(offset>=0&&limit in 1..12);val rows=books(offset,limit+1,check)
        return JSONObject().put("books",JSONArray().apply{repeat(minOf(rows.length(),limit)){put(rows.get(it))}}).put("offset",offset).put("nextOffset",if(rows.length()>limit)offset.toLong()+limit else JSONObject.NULL)
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
        memos=count("SELECT count(*) FROM notes WHERE deleted=0"),books=count("SELECT count(DISTINCT b.bookId) FROM imported_books b JOIN imported_book_chapters c ON c.bookId=b.bookId JOIN phone_articles a ON a.articleId=c.articleId WHERE a.deleted=0 AND CASE WHEN json_valid(b.stateJson) THEN COALESCE(json_extract(b.stateJson,'$.deleted'),0)=0 ELSE 1 END")+count("SELECT count(*) FROM ($groups)"))
}
