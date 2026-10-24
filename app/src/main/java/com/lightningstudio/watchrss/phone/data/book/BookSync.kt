package com.lightningstudio.watchrss.phone.data.book

import androidx.room.*
import com.lightningstudio.watchrss.phone.cloud.CloudProtocolV2
import com.lightningstudio.watchrss.phone.cloud.CloudSnapshotCodec
import com.lightningstudio.watchrss.phone.cloud.PhoneCloudRecordCodec
import org.json.*
import java.security.MessageDigest

@Entity(tableName="imported_books")
data class BookEntity(@PrimaryKey val bookId:String,val stateJson:String,val packageSha256:String="",val packageBytes:Long=0)
@Entity(tableName="imported_book_chapters",primaryKeys=["bookId","chapterIndex"],indices=[Index(value=["articleId"],unique=true)])
data class BookChapterEntity(val bookId:String,val chapterIndex:Int,val articleId:String)
@Entity(tableName="imported_book_reading",primaryKeys=["bookId","chapterIndex"])
data class BookReadingEntity(val bookId:String,val chapterIndex:Int,val stateJson:String)
data class BookSourceRef(val bookId:String,val sourceUrl:String)
@Dao interface BookDao {
    @Query("SELECT c.bookId,a.rssSourceUrl AS sourceUrl FROM imported_book_chapters c JOIN phone_articles a ON a.articleId=c.articleId WHERE a.rssSourceUrl IS NOT NULL GROUP BY c.bookId,a.rssSourceUrl") suspend fun mappedSources():List<BookSourceRef>
    @Query("SELECT * FROM imported_books ORDER BY bookId") suspend fun all():List<BookEntity>
    @Query("SELECT * FROM imported_books WHERE bookId=:id") suspend fun get(id:String):BookEntity?
    @Upsert suspend fun put(value:BookEntity)
    @Query("SELECT * FROM imported_book_chapters ORDER BY bookId,chapterIndex") suspend fun chapters():List<BookChapterEntity>
    @Query("SELECT * FROM imported_book_chapters WHERE bookId=:id ORDER BY chapterIndex") suspend fun chapters(id:String):List<BookChapterEntity>
    @Query("SELECT * FROM imported_book_chapters WHERE articleId=:id LIMIT 1") suspend fun chapterByArticle(id:String):BookChapterEntity?
    @Query("SELECT c.* FROM imported_book_chapters c JOIN phone_articles a ON a.articleId=c.articleId WHERE a.url=:url ORDER BY c.bookId LIMIT 1") suspend fun chapterByUrl(url:String):BookChapterEntity?
    @Query("SELECT * FROM imported_book_chapters WHERE bookId=:id AND chapterIndex=:index LIMIT 1") suspend fun chapterAt(id:String,index:Int):BookChapterEntity?
    @Query("SELECT DISTINCT a.rssSourceUrl FROM imported_book_chapters c JOIN phone_articles a ON a.articleId=c.articleId WHERE a.rssSourceUrl IS NOT NULL") suspend fun sourceUrls():List<String>
    @Query("SELECT * FROM imported_book_chapters WHERE bookId=:id AND chapterIndex IN (:indices)") suspend fun chapterBatch(id:String,indices:List<Int>):List<BookChapterEntity>
    @Query("SELECT * FROM imported_book_reading WHERE bookId=:id AND chapterIndex IN (:indices)") suspend fun readingBatch(id:String,indices:List<Int>):List<BookReadingEntity>
    @Query("SELECT * FROM imported_book_chapters WHERE articleId IN (:ids)") suspend fun chaptersForArticles(ids:List<String>):List<BookChapterEntity>
    @Query("SELECT r.* FROM imported_book_reading r JOIN imported_book_chapters c ON c.bookId=r.bookId AND c.chapterIndex=r.chapterIndex WHERE c.articleId IN (:ids)") suspend fun readingForArticles(ids:List<String>):List<BookReadingEntity>
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putChapters(rows:List<BookChapterEntity>)
    @Query("SELECT * FROM imported_book_reading WHERE bookId=:id ORDER BY chapterIndex") suspend fun readingForBook(id:String):List<BookReadingEntity>
    @Query("SELECT * FROM imported_book_reading ORDER BY bookId,chapterIndex") suspend fun reading():List<BookReadingEntity>
    @Query("SELECT * FROM imported_book_reading WHERE bookId=:id AND chapterIndex=:index") suspend fun reading(id:String,index:Int):BookReadingEntity?
    @Upsert suspend fun putReading(row:BookReadingEntity)
    @Query("DELETE FROM imported_book_reading") suspend fun clearReading()
    @Query("DELETE FROM imported_book_chapters") suspend fun clearChapters()
    @Query("DELETE FROM imported_books") suspend fun clearBooks()
    @Transaction suspend fun clear() {clearReading();clearChapters();clearBooks()}
}

/** R44 is shared by Bluetooth and cloud. No transport-specific content identity. */
object BookSync {
    private val hex=Regex("[0-9a-f]{64}")
    fun normalized(value:String)=value.replace("\r\n","\n").replace('\r','\n')
    fun chapterHash(index:Int,title:String,text:String)=hash("watchrss/book/chapter/v1",index.toString(),normalized(title),normalized(text))
    fun fingerprint(hashes:List<String>):String { require(hashes.isNotEmpty() && hashes.all(hex::matches));return hash("watchrss/book/fingerprint/v1",hashes.size.toString(),*hashes.toTypedArray()) }
    fun id(fingerprint:String)=hash("watchrss/book/id/v1",fingerprint)
    fun articleId(bookId:String,index:Int)=hash("watchrss/book/article/v1",bookId,index.toString())
    private fun hash(vararg strings:String)=CloudSnapshotCodec.sha256(CloudProtocolV2.lp(*strings))
    fun canonical(value:JSONObject)=PhoneCloudRecordCodec.canonical(value)
    fun integer(row:JSONObject,key:String):Long { val v=row.get(key);require(v is Int || v is Long);return (v as Number).toLong().also {require(it in 0..9_007_199_254_740_991L)} }
    fun validate(value:JSONObject) {
        val fingerprint=value.getString("fingerprint");require(hex.matches(fingerprint) && value.getString("bookId")==id(fingerprint))
        val count=integer(value,"chapterCount");require(count in 1..100_000)
        require(value.get("title") is String && value.get("sourceDeviceId") is String && value.get("deleted") is Boolean)
        listOf("deletionClock","deletedAt","updatedAt").forEach {integer(value,it)}
        listOf("sourceUrl","kind").forEach {if(value.has(it)) require(value.get(it) is String)}
        val deleted=value.getJSONArray("deletedChapters");var previous=0L
        for(i in 0 until deleted.length()) { val n=integer(JSONObject().put("i",deleted.get(i)),"i");require(n>previous && n<=count);previous=n }
    }
    fun deletions(state:JSONObject):Set<Int> = state.getJSONArray("deletedChapters").let{a->(0 until a.length()).map{a.getInt(it)}.toSet()}
    fun merge(local:JSONObject?,remote:JSONObject):JSONObject {
        validate(remote);if(local==null)return JSONObject(remote.toString()).apply{remove("contentAvailable")}
        validate(local);require(local.getString("bookId")==remote.getString("bookId") && local.getInt("chapterCount")==remote.getInt("chapterCount"))
        val winner=compareValuesBy(remote,local,{it.getLong("updatedAt")},{it.getString("sourceDeviceId")},{it.getString("title")},{it.optString("sourceUrl")},{it.optString("kind")})>0
        return JSONObject((if(winner)remote else local).toString()).apply {
            remove("contentAvailable");put("deletedChapters",JSONArray((deletions(local)+deletions(remote)).sorted()))
            put("deletionClock",maxOf(local.getLong("deletionClock"),remote.getLong("deletionClock")))
            put("deletedAt",maxOf(local.getLong("deletedAt"),remote.getLong("deletedAt")))
            put("deleted",local.getBoolean("deleted")||remote.getBoolean("deleted"))
        }
    }
    fun validateReading(value:JSONObject) {
        require(value.getString("bookId").matches(hex));require(integer(value,"chapterIndex") in 1..100_000)
        require(value.get("isRead") is Boolean && value.get("sourceDeviceId") is String)
        integer(value,"readingPositionBytes");integer(value,"readingPositionChangedAt")
        require(value.get("readingProgress") is Number && value.getDouble("readingProgress") in 0.0..1.0)
        require(value.get("readingPositionContentHash") is String)
        for (prefix in listOf("favorite", "watchLater")) if (value.has("${prefix}Saved")) {
            require(value.get("${prefix}Saved") is Boolean)
            integer(value, "${prefix}ChangedAt"); integer(value, "${prefix}SortOrder")
            if(value.has("${prefix}SourceDeviceId"))require(value.get("${prefix}SourceDeviceId") is String)
        }
    }
    private fun normalizedReading(value:JSONObject)=JSONObject(value.toString()).apply {
        put("readingProgress",kotlin.math.floor(getDouble("readingProgress")*1_000_000.0+0.5)/1_000_000.0)
        for(prefix in listOf("favorite","watchLater"))if(has("${prefix}Saved") && !has("${prefix}SourceDeviceId"))put("${prefix}SourceDeviceId",getString("sourceDeviceId"))
    }
    fun mergeReading(localValue:JSONObject?,remoteValue:JSONObject):JSONObject {
        validateReading(remoteValue);localValue?.let(::validateReading)
        val remote=normalizedReading(remoteValue);val local=localValue?.let(::normalizedReading)
        if(local==null)return remote
        validateReading(local)
        val order=compareValuesBy(remote,local,{it.getLong("readingPositionChangedAt")},{it.getString("sourceDeviceId")},
            {if(it.getBoolean("isRead"))0 else 1},{it.getLong("readingPositionBytes")},{it.getDouble("readingProgress")},{it.getString("readingPositionContentHash")})
        require(local.getString("bookId") == remote.getString("bookId") && local.getInt("chapterIndex") == remote.getInt("chapterIndex"))
        return JSONObject((if(order>0)remote else local).toString()).apply {
            for (prefix in listOf("favorite", "watchLater")) {
                val field = "${prefix}Saved"
                val winner = when {
                    !remote.has(field) -> local
                    !local.has(field) -> remote
                    compareValuesBy(remote, local, {it.getLong("${prefix}ChangedAt")}, {it.optString("${prefix}SourceDeviceId",it.getString("sourceDeviceId"))},
                        {if (it.getBoolean(field)) 0 else 1}, {it.getLong("${prefix}SortOrder")}) > 0 -> remote
                    else -> local
                }
                if (winner.has(field)) {
                    listOf(field,"${prefix}ChangedAt","${prefix}SortOrder").forEach {put(it,winner.get(it))}
                    put("${prefix}SourceDeviceId",winner.optString("${prefix}SourceDeviceId",winner.getString("sourceDeviceId")))
                }
            }
        }
    }
}
