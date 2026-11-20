package com.lightningstudio.watchrss.phone.data.book

import androidx.room.*
import com.lightningstudio.watchrss.phone.BuildConfig
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
// Keep ordinary reads in one indexed query. Large values bypass CursorWindow
// by fetching bounded text slices in the same Room read transaction.
private const val BOOK_STATE_COLUMNS = "bookId,packageSha256,packageBytes,"
private const val BOOK_READING_COLUMNS = "bookId,chapterIndex,"
internal const val SMALL_BOOK_JSON =
    "CASE WHEN length(CAST(stateJson AS BLOB)) <= 262144 THEN stateJson ELSE '' END AS stateJson"
internal const val BOOK_JSON_SLICE = 65536

internal suspend fun readBookJson(read: suspend (offset: Int) -> String): String = buildString {
    var offset = 1
    while (true) {
        val slice = read(offset)
        append(slice)
        val codePoints = slice.codePointCount(0, slice.length)
        if (codePoints < BOOK_JSON_SLICE) break
        offset += codePoints
    }
}

@Dao interface BookDao {
    @Query("SELECT substr(stateJson,:offset,65536) FROM imported_books WHERE bookId=:id")
    suspend fun bookJsonSlice(id: String, offset: Int): String
    @Query("SELECT substr(stateJson,:offset,65536) FROM imported_book_reading WHERE bookId=:id AND chapterIndex=:index")
    suspend fun readingJsonSlice(id: String, index: Int, offset: Int): String
    private suspend fun completeBook(row: BookEntity) =
        if (row.stateJson.isNotEmpty()) row else row.copy(stateJson = readBookJson { bookJsonSlice(row.bookId, it) })
    private suspend fun completeReading(row: BookReadingEntity) =
        if (row.stateJson.isNotEmpty()) row else row.copy(stateJson = readBookJson { readingJsonSlice(row.bookId, row.chapterIndex, it) })

    @Query("""SELECT EXISTS(SELECT 1 FROM phone_articles a WHERE lower(trim(a.url)) LIKE 'https://watchrss.local/import-%'
        AND NOT EXISTS(SELECT 1 FROM imported_book_chapters c WHERE c.articleId=a.articleId))""")
    suspend fun hasUnregisteredBookArticles(): Boolean
    @Query("SELECT c.bookId,a.rssSourceUrl AS sourceUrl FROM imported_book_chapters c JOIN phone_articles a ON a.articleId=c.articleId WHERE a.rssSourceUrl IS NOT NULL GROUP BY c.bookId,a.rssSourceUrl") suspend fun mappedSources():List<BookSourceRef>
    @Query("SELECT " + BOOK_STATE_COLUMNS + SMALL_BOOK_JSON + " FROM imported_books ORDER BY bookId")
    suspend fun allBookHeaders(): List<BookEntity>
    @Transaction suspend fun all(): List<BookEntity> = allBookHeaders().map { completeBook(it) }
    @Query("SELECT " + BOOK_STATE_COLUMNS + SMALL_BOOK_JSON + " FROM imported_books WHERE bookId > :after ORDER BY bookId LIMIT 1")
    suspend fun nextBookHeader(after: String): BookEntity?
    @Transaction suspend fun nextBinaryBook(after: String): BookEntity? = nextBookHeader(after)?.let { completeBook(it) }
    @Query("SELECT " + BOOK_READING_COLUMNS + SMALL_BOOK_JSON + " FROM (" +
        "SELECT *,SUM(length(CAST(stateJson AS BLOB))+1) OVER (ORDER BY chapterIndex) AS pageBytes," +
        "ROW_NUMBER() OVER (ORDER BY chapterIndex) AS rowIndex FROM (SELECT * FROM imported_book_reading " +
        "WHERE bookId=:id AND chapterIndex > :after ORDER BY chapterIndex LIMIT 100)) " +
        "WHERE pageBytes+1 <= 16777216 OR rowIndex=1 ORDER BY chapterIndex")
    suspend fun binaryReadingHeaders(id: String, after: Int): List<BookReadingEntity>
    @Transaction suspend fun binaryReadingPage(id: String, after: Int): List<BookReadingEntity> =
        binaryReadingHeaders(id, after).map { completeReading(it) }
    @Query("SELECT * FROM imported_book_chapters WHERE bookId=:id AND chapterIndex>:after ORDER BY chapterIndex LIMIT 100")
    suspend fun binaryChapterPage(id: String, after: Int): List<BookChapterEntity>
    @Query("SELECT COUNT(*) FROM imported_book_chapters c JOIN phone_articles a ON a.articleId=c.articleId WHERE c.bookId=:id")
    suspend fun binaryPresentChapters(id: String): Int
    @Query("SELECT articleId FROM phone_articles WHERE url=:url ORDER BY articleId LIMIT 1")
    suspend fun binaryArticleAtUrl(url: String): String?
    @Query("UPDATE phone_articles SET contentText=COALESCE(:text,contentText),contentHtml=COALESCE(:html,contentHtml),contentHash=:hash,syncBodyHash='',syncBodyByteCount=0,syncChunkSize=0,syncChunkHashesJson='',syncMetadataHash='' WHERE articleId=:id")
    suspend fun repairBinaryChapter(id: String, text: String?, html: String?, hash: String)
    @Query("SELECT " + BOOK_STATE_COLUMNS + SMALL_BOOK_JSON + " FROM imported_books WHERE bookId=:id")
    suspend fun bookHeader(id: String): BookEntity?
    @Transaction suspend fun get(id: String): BookEntity? = bookHeader(id)?.let { completeBook(it) }
    @Upsert suspend fun put(value:BookEntity)
    @Query("SELECT * FROM imported_book_chapters ORDER BY bookId,chapterIndex") suspend fun chapters():List<BookChapterEntity>
    @Query("SELECT * FROM imported_book_chapters WHERE bookId=:id ORDER BY chapterIndex") suspend fun chapters(id:String):List<BookChapterEntity>
    @Query("SELECT * FROM imported_book_chapters WHERE articleId=:id LIMIT 1") suspend fun chapterByArticle(id:String):BookChapterEntity?
    @Query("SELECT c.* FROM imported_book_chapters c JOIN phone_articles a ON a.articleId=c.articleId WHERE a.url=:url ORDER BY c.bookId LIMIT 1") suspend fun chapterByUrl(url:String):BookChapterEntity?
    @Query("SELECT * FROM imported_book_chapters WHERE bookId=:id AND chapterIndex=:index LIMIT 1") suspend fun chapterAt(id:String,index:Int):BookChapterEntity?
    @Query("SELECT DISTINCT a.rssSourceUrl FROM imported_book_chapters c JOIN phone_articles a ON a.articleId=c.articleId WHERE a.rssSourceUrl IS NOT NULL") suspend fun sourceUrls():List<String>
    @Query("SELECT * FROM imported_book_chapters WHERE bookId=:id AND chapterIndex IN (:indices)") suspend fun chapterBatch(id:String,indices:List<Int>):List<BookChapterEntity>
    @Query("SELECT " + BOOK_READING_COLUMNS + SMALL_BOOK_JSON + " FROM imported_book_reading WHERE bookId=:id AND chapterIndex IN (:indices)")
    suspend fun readingBatchHeaders(id: String, indices: List<Int>): List<BookReadingEntity>
    @Transaction suspend fun readingBatch(id: String, indices: List<Int>): List<BookReadingEntity> =
        readingBatchHeaders(id, indices).map { completeReading(it) }
    @Query("SELECT * FROM imported_book_chapters WHERE articleId IN (:ids)") suspend fun chaptersForArticles(ids:List<String>):List<BookChapterEntity>
    @Query("SELECT r.bookId,r.chapterIndex," + SMALL_BOOK_JSON + " FROM imported_book_reading r " +
        "JOIN imported_book_chapters c ON c.bookId=r.bookId AND c.chapterIndex=r.chapterIndex WHERE c.articleId IN (:ids)")
    suspend fun articleReadingHeaders(ids: List<String>): List<BookReadingEntity>
    @Transaction suspend fun readingForArticles(ids: List<String>): List<BookReadingEntity> =
        articleReadingHeaders(ids).map { completeReading(it) }
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putChapters(rows:List<BookChapterEntity>)
    @Query("SELECT " + BOOK_READING_COLUMNS + SMALL_BOOK_JSON + " FROM imported_book_reading WHERE bookId=:id ORDER BY chapterIndex")
    suspend fun bookReadingHeaders(id: String): List<BookReadingEntity>
    @Transaction suspend fun readingForBook(id: String): List<BookReadingEntity> = bookReadingHeaders(id).map { completeReading(it) }
    @Query("SELECT " + BOOK_READING_COLUMNS + SMALL_BOOK_JSON + " FROM imported_book_reading ORDER BY bookId,chapterIndex")
    suspend fun allReadingHeaders(): List<BookReadingEntity>
    @Transaction suspend fun reading(): List<BookReadingEntity> = allReadingHeaders().map { completeReading(it) }
    @Query("SELECT " + BOOK_READING_COLUMNS + SMALL_BOOK_JSON + " FROM imported_book_reading WHERE bookId=:id AND chapterIndex=:index")
    suspend fun readingHeader(id: String, index: Int): BookReadingEntity?
    @Transaction suspend fun reading(id: String, index: Int): BookReadingEntity? =
        readingHeader(id, index)?.let { completeReading(it) }
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
    internal fun binaryId(fingerprint: String, instance: String?) =
        if (instance == null) id(fingerprint) else hash("watchrss/book/instance-id/v1", fingerprint, instance)
    fun articleId(bookId:String,index:Int)=hash("watchrss/book/article/v1",bookId,index.toString())
    private fun hash(vararg strings:String)=CloudSnapshotCodec.sha256(CloudProtocolV2.lp(*strings))
    private fun copyObject(value: JSONObject): JSONObject = JSONObject().apply {
        value.keys().forEach { put(it, copyValue(value.get(it))) }
    }
    private fun copyValue(value: Any): Any = when (value) {
        is JSONObject -> copyObject(value)
        is JSONArray -> JSONArray().apply { for (i in 0 until value.length()) put(copyValue(value.get(i))) }
        else -> value
    }
    private fun copyManifest(value: JSONObject, binary: Boolean): JSONObject =
        if (binary) copyObject(value) else JSONObject(value.toString())

    fun canonical(value:JSONObject)=PhoneCloudRecordCodec.canonical(value)
    fun integer(row:JSONObject,key:String):Long { val v=row.get(key);require(v is Int || v is Long);return (v as Number).toLong().also {require(it in 0..9_007_199_254_740_991L)} }
    /** Fixed subcodes only. Never include peer values or parser exception text. */
    fun validate(value: JSONObject) = validate(value, binary = false)
    internal fun validateBinary(value: JSONObject) = validate(value, binary = true)
    private fun validate(value:JSONObject, binary: Boolean) {
        fun check(ok:Boolean,reason:String){if(!ok)throw InvalidBookPackageException(reason)}
        fun number(key:String,reason:String):Long {
            val raw=value.opt(key)
            check(raw is Int || raw is Long,reason+"_type")
            return (raw as Number).toLong().also {check(it in 0..9_007_199_254_740_991L,reason+"_range")}
        }
        val fingerprint=value.opt("fingerprint")
        check(fingerprint is String && hex.matches(fingerprint),"manifest_fingerprint_invalid")
        val identity=value.opt("bookId")
        check(identity is String && hex.matches(identity),"manifest_book_id_invalid")
        val instance=value.opt("instanceId")
        if(!binary && instance is String && instance.matches(Regex("[0-9a-f]{32}")) &&
            identity==hash("watchrss/book/instance-id/v1",fingerprint as String,instance)) {
            throw InvalidBookPackageException("manifest_instance_identity_unsupported")
        }
        if (binary) {
            check(!value.has("instanceId") || instance is String && instance.matches(Regex("[0-9a-f]{32}")), "manifest_instance_invalid")
            if (value.has("lifecycle")) {
                val token = value.optJSONObject("lifecycle") ?: throw InvalidBookPackageException("manifest_lifecycle_invalid")
                check(token.keys().asSequence().toSet() == setOf("generation", "operationId", "startedAt") &&
                    integer(token, "generation") > 0 && integer(token, "startedAt") >= 0 &&
                    (token.opt("operationId") as? String)?.matches(Regex("[0-9a-f]{32}")) == true, "manifest_lifecycle_invalid")
            }
        }
        check(identity==binaryId(fingerprint as String, (instance as? String).takeIf { binary }),"manifest_identity_mismatch")
        val count=number("chapterCount","manifest_chapter_count")
        check(count in 1..100_000,"manifest_chapter_count_range")
        check(value.opt("title") is String,"manifest_title_type")
        check(value.opt("sourceDeviceId") is String,"manifest_source_device_id_type")
        check(value.opt("deleted") is Boolean,"manifest_deleted_type")
        number("deletionClock","manifest_deletion_clock")
        number("deletedAt","manifest_deleted_at")
        number("updatedAt","manifest_updated_at")
        for(key in listOf("sourceUrl","kind"))check(!value.has(key)||value.opt(key) is String,
            if(key=="sourceUrl")"manifest_source_url_type" else "manifest_kind_type")
        val deleted=value.opt("deletedChapters")
        check(deleted is JSONArray,"manifest_deleted_chapters_type")
        var previous=0L
        for(i in 0 until (deleted as JSONArray).length()) {
            val raw=deleted.opt(i)
            check(raw is Int || raw is Long,"manifest_deleted_chapter_type")
            val n=(raw as Number).toLong()
            check(n in 1..count,"manifest_deleted_chapter_range")
            check(n>previous,"manifest_deleted_chapter_order")
            previous=n
        }
    }
    internal fun usesBinaryCapture(state: JSONObject): Boolean {
        if (!BuildConfig.B2_ENABLED || (!state.has("instanceId") && !state.has("lifecycle"))) return false
        // A legacy identity can carry extra fields that only v1 can read.
        if (state.getString("bookId") != id(state.getString("fingerprint"))) return true
        return try {
            validateBookPackage { validateBinary(state) }
            true
        } catch (_: InvalidBookPackageException) {
            false
        }
    }

    fun deletions(state:JSONObject):Set<Int> = state.getJSONArray("deletedChapters").let{a->(0 until a.length()).map{a.getInt(it)}.toSet()}
    internal fun compareLifecycle(a: JSONObject, b: JSONObject): Int {
        val x = a.optJSONObject("lifecycle")
        val y = b.optJSONObject("lifecycle")
        val generation = (x?.getLong("generation") ?: 0).compareTo(y?.getLong("generation") ?: 0)
        return if (generation != 0) generation else x?.optString("operationId").orEmpty().compareTo(y?.optString("operationId").orEmpty())
    }
    fun merge(local:JSONObject?,remote:JSONObject):JSONObject = merge(local, remote, binary = false)
    internal fun merge(local:JSONObject?,remote:JSONObject, binary: Boolean):JSONObject {
        validate(remote, binary);if(local==null)return copyManifest(remote, binary).apply{remove("contentAvailable")}
        validate(local, binary)
        if (local.getString("bookId") != remote.getString("bookId") || local.getInt("chapterCount") != remote.getInt("chapterCount"))
            throw InvalidBookPackageException("manifest_local_identity_conflict")
        var left: JSONObject = local
        var right: JSONObject = remote
        if (binary) {
            val token = compareLifecycle(left, right)
            if (token != 0) {
                val current = if (token > 0) left else right
                val other = if (token > 0) right else left
                val startedAt = current.getJSONObject("lifecycle").getLong("startedAt")
                val eligible = legacyTombstonesAfter(other, startedAt)
                    ?: return copyObject(current).apply { remove("contentAvailable") }
                if (token > 0) right = eligible else left = eligible
            }
        }
        val winner=compareValuesBy(right,left,{it.getLong("updatedAt")},{it.getString("sourceDeviceId")},{it.getString("title")},{it.optString("sourceUrl")},{it.optString("kind")})>0
        return copyManifest(if(winner) right else left, binary).apply {
            remove("contentAvailable");put("deletedChapters",JSONArray((deletions(left)+deletions(right)).sorted()))
            put("deletionClock",maxOf(left.getLong("deletionClock"),right.getLong("deletionClock")))
            put("deletedAt",maxOf(left.getLong("deletedAt"),right.getLong("deletedAt")))
            put("deleted",left.getBoolean("deleted")||right.getBoolean("deleted"))
            if (binary && !has("lifecycle")) {
                val lifecycle = local.optJSONObject("lifecycle") ?: remote.optJSONObject("lifecycle")
                if (lifecycle != null) put("lifecycle", copyObject(lifecycle))
            }
        }
    }
    private fun legacyTombstonesAfter(state: JSONObject, startedAt: Long): JSONObject? {
        if (state.has("lifecycle") || state.has("instanceId")) return null
        val deletedAt = state.getLong("deletedAt")
        val chapterClock = state.getLong("deletionClock")
        val hasChapters = state.getJSONArray("deletedChapters").length() > 0
        val whole = state.getBoolean("deleted") && deletedAt > startedAt
        val chapters = hasChapters && chapterClock > startedAt
        if (!whole && !chapters) return null
        return copyObject(state).apply {
            put("deleted", whole)
            put("deletedAt", if (whole) deletedAt else 0L)
            if (!chapters) {
                put("deletedChapters", JSONArray())
                // v1 whole-only tombstones can mirror deletedAt in deletionClock.
                put("deletionClock", if (whole && !hasChapters) minOf(chapterClock, deletedAt) else 0L)
            }
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
