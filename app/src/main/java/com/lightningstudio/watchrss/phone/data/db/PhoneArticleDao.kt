package com.lightningstudio.watchrss.phone.data.db

import androidx.room.Dao
import androidx.room.Upsert
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.lightningstudio.watchrss.phone.data.local.ARTICLE_CONTENT_MARKER_PREFIX
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Full body columns must never share a CursorWindow row. Legacy inline rows can exceed 2 MiB.
internal const val ARTICLE_METADATA_COLUMNS = """articleId, sourceDeviceId, url, title, siteName, excerpt,
    NULL AS contentHtml, '' AS contentText, imageUrl, contentHash,
    importedAt, updatedAt, independentSaved, independentChangedAt, independentSortOrder,
    rssSourceUrl, rssSourceTitle, favoriteSaved, favoriteChangedAt, favoriteSortOrder,
    watchLaterSaved, watchLaterChangedAt, watchLaterSortOrder, deleted, deletedAt,
    syncBodyHash, syncBodyByteCount, syncChunkSize, syncChunkHashesJson, syncMetadataHash,
    readingProgress, isRead, readingPositionBytes, readingPositionContentHash, readingPositionChangedAt"""
private const val BODY_CHUNK_BYTES = 65_536

@Dao
interface PhoneArticleDao {
    @Query("SELECT EXISTS(SELECT 1 FROM phone_articles)") suspend fun hasAny(): Boolean
    @Transaction
    suspend fun upsertRssIdentityRows(articles: List<PhoneArticleEntity>, retiredIds: List<String>) {
        upsertAll(articles)
        if (retiredIds.isNotEmpty()) deleteByIds(retiredIds)
    }

    @Query("""UPDATE phone_articles SET sourceDeviceId=:source,isRead=:read,readingProgress=:progress,
        readingPositionBytes=:bytes,readingPositionContentHash=:hash,readingPositionChangedAt=:clock,
        favoriteSaved=:favorite,favoriteChangedAt=:favoriteClock,favoriteSortOrder=:favoriteOrder,
        watchLaterSaved=:later,watchLaterChangedAt=:laterClock,watchLaterSortOrder=:laterOrder,syncMetadataHash=''
        WHERE articleId=:id""")
    suspend fun projectBookReading(id:String,source:String,read:Boolean,progress:Float,bytes:Long,hash:String,clock:Long,
        favorite:Boolean,favoriteClock:Long,favoriteOrder:Long,later:Boolean,laterClock:Long,laterOrder:Long)

    @Query("SELECT " + ARTICLE_METADATA_COLUMNS + """ FROM phone_articles WHERE articleId>:after AND deleted=0 AND
        (url LIKE 'https://watchrss.local/import-%' OR rssSourceUrl LIKE 'https://watchrss.local/import-%'
         OR (rssSourceUrl IS NULL OR rssSourceUrl='') OR favoriteSaved=1 OR watchLaterSaved=1)
        ORDER BY articleId LIMIT :limit""")
    suspend fun privateCloudBodyMetadataPage(after: String, limit: Int): List<PhoneArticleEntity>

    @Transaction
    suspend fun privateCloudBodyPage(after: String, limit: Int): List<PhoneArticleEntity> =
        privateCloudBodyMetadataPage(after, limit).map { withStoredBody(it) }

    @Query("SELECT " + ARTICLE_METADATA_COLUMNS + " FROM phone_articles WHERE length(excerpt)>280")
    suspend fun longCloudExcerptMetadata(): List<PhoneArticleEntity>

    @Transaction
    suspend fun longCloudExcerpts(): List<PhoneArticleEntity> = longCloudExcerptMetadata().map { withStoredBody(it) }

    @Query("""UPDATE phone_articles SET excerpt=:excerpt, contentText=:text, contentHtml=:html,
        updatedAt=MAX(updatedAt,:clock), sourceDeviceId=:device, syncMetadataHash=''
        WHERE articleId=:id AND contentHash=:hash AND excerpt=:oldExcerpt
        AND contentText=:oldText AND COALESCE(contentHtml,'')=COALESCE(:oldHtml,'')""")
    suspend fun compactCloudExcerpt(id: String, hash: String, oldExcerpt: String, oldText: String,
        oldHtml: String?, excerpt: String, text: String, html: String?, clock: Long, device: String): Int

    @Query("""UPDATE phone_articles SET contentText=:text, contentHtml=:html
        WHERE articleId=:id AND contentHash=:hash AND deleted=0
        AND contentText=:expectedText AND (contentHtml IS NULL OR contentHtml='')""")
    suspend fun fillMissingCloudBody(id: String, hash: String, text: String, html: String?, expectedText: String): Int

    @Query("""SELECT articleId FROM phone_articles WHERE deleted=0 AND contentText=''
        AND (contentHtml IS NULL OR contentHtml='') AND url LIKE 'https://watchrss.local/%'
        ORDER BY articleId""")
    suspend fun missingPrivateBodyIds(): List<String>

    @Query("""SELECT articleId, sourceDeviceId, url, title, siteName, excerpt,
               NULL AS contentHtml, '' AS contentText, imageUrl, contentHash,
               importedAt, updatedAt, independentSaved, independentChangedAt,
               independentSortOrder, rssSourceUrl, rssSourceTitle,
               favoriteSaved, favoriteChangedAt, favoriteSortOrder,
               watchLaterSaved, watchLaterChangedAt, watchLaterSortOrder,
               deleted, deletedAt, syncBodyHash, syncBodyByteCount,
               syncChunkSize, syncChunkHashesJson, syncMetadataHash, readingProgress, isRead,
               readingPositionBytes, readingPositionContentHash, readingPositionChangedAt
        FROM phone_articles WHERE articleId > :after ORDER BY articleId LIMIT :limit""")
    suspend fun cloudRecordPage(after: String, limit: Int): List<PhoneArticleEntity>

    @Query(
        """
        SELECT articleId, sourceDeviceId, url, title, siteName, excerpt,
               NULL AS contentHtml, '' AS contentText, imageUrl, contentHash,
               importedAt, updatedAt, independentSaved, independentChangedAt,
               independentSortOrder, rssSourceUrl, rssSourceTitle,
               favoriteSaved, favoriteChangedAt, favoriteSortOrder,
               watchLaterSaved, watchLaterChangedAt, watchLaterSortOrder,
               deleted, deletedAt, syncBodyHash, syncBodyByteCount,
               syncChunkSize, syncChunkHashesJson, syncMetadataHash, readingProgress, isRead,
               readingPositionBytes, readingPositionContentHash, readingPositionChangedAt
        FROM phone_articles
        WHERE deleted = 0
        ORDER BY updatedAt DESC, importedAt DESC
        LIMIT :limit
        """
    )
    fun observeRecent(limit: Int): Flow<List<PhoneArticleEntity>>

    @Query(
        """
        SELECT articleId, sourceDeviceId, url, title, siteName, excerpt,
               NULL AS contentHtml, '' AS contentText, imageUrl, contentHash,
               importedAt, updatedAt, independentSaved, independentChangedAt,
               independentSortOrder, rssSourceUrl, rssSourceTitle,
               favoriteSaved, favoriteChangedAt, favoriteSortOrder,
               watchLaterSaved, watchLaterChangedAt, watchLaterSortOrder,
               deleted, deletedAt, syncBodyHash, syncBodyByteCount,
               syncChunkSize, syncChunkHashesJson, syncMetadataHash, readingProgress, isRead,
               readingPositionBytes, readingPositionContentHash, readingPositionChangedAt
        FROM phone_articles
        WHERE deleted = 0 AND independentSaved = 1
        ORDER BY independentSortOrder DESC, independentChangedAt DESC, title ASC
        """
    )
    fun observeIndependent(): Flow<List<PhoneArticleEntity>>

    @Query(
        """
        SELECT phone_articles.articleId AS articleId,
               phone_articles.sourceDeviceId AS sourceDeviceId,
               phone_articles.url AS url,
               phone_articles.title AS title,
               phone_articles.siteName AS siteName,
               phone_articles.excerpt AS excerpt,
               NULL AS contentHtml,
               '' AS contentText,
               phone_articles.imageUrl AS imageUrl,
               phone_articles.contentHash AS contentHash,
               phone_articles.importedAt AS importedAt,
               phone_articles.updatedAt AS updatedAt,
               phone_articles.independentSaved AS independentSaved,
               phone_articles.independentChangedAt AS independentChangedAt,
               phone_articles.independentSortOrder AS independentSortOrder,
               phone_articles.rssSourceUrl AS rssSourceUrl,
               phone_articles.rssSourceTitle AS rssSourceTitle,
               phone_articles.favoriteSaved AS favoriteSaved,
               phone_articles.favoriteChangedAt AS favoriteChangedAt,
               phone_articles.favoriteSortOrder AS favoriteSortOrder,
               phone_articles.watchLaterSaved AS watchLaterSaved,
               phone_articles.watchLaterChangedAt AS watchLaterChangedAt,
               phone_articles.watchLaterSortOrder AS watchLaterSortOrder,
               phone_articles.deleted AS deleted,
               phone_articles.deletedAt AS deletedAt,
               phone_articles.syncBodyHash AS syncBodyHash,
               phone_articles.syncBodyByteCount AS syncBodyByteCount,
               phone_articles.syncChunkSize AS syncChunkSize,
               phone_articles.syncChunkHashesJson AS syncChunkHashesJson,
               phone_articles.syncMetadataHash AS syncMetadataHash,
               phone_articles.readingProgress AS readingProgress,
               phone_articles.isRead AS isRead,
               phone_articles.readingPositionBytes AS readingPositionBytes,
               phone_articles.readingPositionContentHash AS readingPositionContentHash,
               phone_articles.readingPositionChangedAt AS readingPositionChangedAt
        FROM phone_articles
        LEFT JOIN phone_rss_sources ON phone_rss_sources.url = phone_articles.rssSourceUrl
        WHERE phone_articles.deleted = 0
          AND phone_articles.rssSourceUrl IS NOT NULL
          AND phone_articles.rssSourceUrl != ''
          AND phone_articles.rssSourceUrl != :importedTextSourceUrl
          AND phone_articles.url NOT LIKE :importedTextArticlePrefix
          AND COALESCE(phone_rss_sources.deleted, 0) = 0
        ORDER BY phone_articles.updatedAt DESC, phone_articles.importedAt DESC, phone_articles.title ASC
        """
    )
    fun observeRssArticles(
        importedTextSourceUrl: String,
        importedTextArticlePrefix: String
    ): Flow<List<PhoneArticleEntity>>

    @Query(
        """
        SELECT phone_articles.articleId AS articleId,
               phone_articles.sourceDeviceId AS sourceDeviceId,
               phone_articles.url AS url,
               phone_articles.title AS title,
               phone_articles.siteName AS siteName,
               phone_articles.excerpt AS excerpt,
               NULL AS contentHtml,
               '' AS contentText,
               phone_articles.imageUrl AS imageUrl,
               phone_articles.contentHash AS contentHash,
               phone_articles.importedAt AS importedAt,
               phone_articles.updatedAt AS updatedAt,
               phone_articles.independentSaved AS independentSaved,
               phone_articles.independentChangedAt AS independentChangedAt,
               phone_articles.independentSortOrder AS independentSortOrder,
               phone_articles.rssSourceUrl AS rssSourceUrl,
               phone_articles.rssSourceTitle AS rssSourceTitle,
               phone_articles.favoriteSaved AS favoriteSaved,
               phone_articles.favoriteChangedAt AS favoriteChangedAt,
               phone_articles.favoriteSortOrder AS favoriteSortOrder,
               phone_articles.watchLaterSaved AS watchLaterSaved,
               phone_articles.watchLaterChangedAt AS watchLaterChangedAt,
               phone_articles.watchLaterSortOrder AS watchLaterSortOrder,
               phone_articles.deleted AS deleted,
               phone_articles.deletedAt AS deletedAt,
               phone_articles.syncBodyHash AS syncBodyHash,
               phone_articles.syncBodyByteCount AS syncBodyByteCount,
               phone_articles.syncChunkSize AS syncChunkSize,
               phone_articles.syncChunkHashesJson AS syncChunkHashesJson,
               phone_articles.syncMetadataHash AS syncMetadataHash,
               phone_articles.readingProgress AS readingProgress,
               phone_articles.isRead AS isRead,
               phone_articles.readingPositionBytes AS readingPositionBytes,
               phone_articles.readingPositionContentHash AS readingPositionContentHash,
               phone_articles.readingPositionChangedAt AS readingPositionChangedAt
        FROM phone_articles
        LEFT JOIN phone_rss_sources ON phone_rss_sources.url = phone_articles.rssSourceUrl
        WHERE phone_articles.deleted = 0
          AND (
              phone_articles.rssSourceUrl = :importedTextSourceUrl
              OR phone_articles.url LIKE :importedTextArticlePrefix
          )
        ORDER BY phone_articles.rssSourceTitle ASC,
                 phone_articles.updatedAt DESC,
                 phone_articles.importedAt DESC,
                 phone_articles.title ASC
        """
    )
    fun observeImportedContentArticles(
        importedTextSourceUrl: String,
        importedTextArticlePrefix: String
    ): Flow<List<PhoneArticleEntity>>

    @Query(
        """
        SELECT articleId, sourceDeviceId, url, title, siteName, excerpt,
               NULL AS contentHtml, '' AS contentText, imageUrl, contentHash,
               importedAt, updatedAt, independentSaved, independentChangedAt,
               independentSortOrder, rssSourceUrl, rssSourceTitle,
               favoriteSaved, favoriteChangedAt, favoriteSortOrder,
               watchLaterSaved, watchLaterChangedAt, watchLaterSortOrder,
               deleted, deletedAt, syncBodyHash, syncBodyByteCount,
               syncChunkSize, syncChunkHashesJson, syncMetadataHash, readingProgress, isRead,
               readingPositionBytes, readingPositionContentHash, readingPositionChangedAt
        FROM phone_articles
        WHERE deleted = 0 AND favoriteSaved = 1
        ORDER BY favoriteSortOrder DESC, favoriteChangedAt DESC, title ASC
        """
    )
    fun observeFavorites(): Flow<List<PhoneArticleEntity>>

    @Query(
        """
        SELECT articleId, sourceDeviceId, url, title, siteName, excerpt,
               NULL AS contentHtml, '' AS contentText, imageUrl, contentHash,
               importedAt, updatedAt, independentSaved, independentChangedAt,
               independentSortOrder, rssSourceUrl, rssSourceTitle,
               favoriteSaved, favoriteChangedAt, favoriteSortOrder,
               watchLaterSaved, watchLaterChangedAt, watchLaterSortOrder,
               deleted, deletedAt, syncBodyHash, syncBodyByteCount,
               syncChunkSize, syncChunkHashesJson, syncMetadataHash, readingProgress, isRead,
               readingPositionBytes, readingPositionContentHash, readingPositionChangedAt
        FROM phone_articles
        WHERE deleted = 0 AND watchLaterSaved = 1
        ORDER BY watchLaterSortOrder DESC, watchLaterChangedAt DESC, title ASC
        """
    )
    fun observeWatchLater(): Flow<List<PhoneArticleEntity>>

    @Query("SELECT " + ARTICLE_METADATA_COLUMNS + " FROM phone_articles WHERE articleId = :articleId LIMIT 1")
    suspend fun getBodyMetadataById(articleId: String): PhoneArticleEntity?

    @Transaction
    suspend fun getById(articleId: String): PhoneArticleEntity? = getBodyMetadataById(articleId)?.let { withStoredBody(it) }

    @Query("SELECT " + ARTICLE_METADATA_COLUMNS + " FROM phone_articles WHERE rssSourceUrl = :rssSourceUrl")
    suspend fun getBodyMetadataBySource(rssSourceUrl: String): List<PhoneArticleEntity>

    @Transaction
    suspend fun getByRssSourceUrl(rssSourceUrl: String): List<PhoneArticleEntity> =
        getBodyMetadataBySource(rssSourceUrl).map { withStoredBody(it) }

    @Query("SELECT " + ARTICLE_METADATA_COLUMNS + " FROM phone_articles WHERE articleId = :articleId LIMIT 1")
    fun observeBodyMetadataById(articleId: String): Flow<PhoneArticleEntity?>

    fun observeById(articleId: String): Flow<PhoneArticleEntity?> =
        observeBodyMetadataById(articleId).map { getById(articleId) }

    @Query("SELECT " + ARTICLE_METADATA_COLUMNS + " FROM phone_articles")
    suspend fun getAllBodyMetadata(): List<PhoneArticleEntity>

    @Transaction
    suspend fun getAllForSync(): List<PhoneArticleEntity> = getAllBodyMetadata().map { withStoredBody(it) }

    // Called inside the enclosing read transaction, including metadata and every fragment.
    suspend fun withStoredBody(metadata: PhoneArticleEntity): PhoneArticleEntity = metadata.copy(
        contentHtml = readContentHtmlChunked(metadata.articleId),
        contentText = readContentTextChunked(metadata.articleId)
    )

    suspend fun readContentHtmlChunked(articleId: String): String? = readBodyChunks { start, count ->
        getContentHtmlChunk(articleId, start, count)
    }

    suspend fun readContentTextChunked(articleId: String): String = readBodyChunks { start, count ->
        getContentTextChunk(articleId, start, count)
    }.orEmpty()

    @Query("SELECT articleId FROM phone_articles")
    suspend fun getAllIds(): List<String>

    @Query("""SELECT articleId, sourceDeviceId, url, title, siteName, excerpt,
               NULL AS contentHtml,
               '' AS contentText,
               imageUrl, contentHash, importedAt, updatedAt, independentSaved,
               independentChangedAt, independentSortOrder, rssSourceUrl, rssSourceTitle,
               favoriteSaved, favoriteChangedAt, favoriteSortOrder,
               watchLaterSaved, watchLaterChangedAt, watchLaterSortOrder,
               deleted, deletedAt, syncBodyHash, syncBodyByteCount,
               syncChunkSize, syncChunkHashesJson, syncMetadataHash, readingProgress, isRead,
               readingPositionBytes, readingPositionContentHash, readingPositionChangedAt
        FROM phone_articles WHERE articleId IN (:ids)""")
    suspend fun bookMetadata(ids:List<String>):List<PhoneArticleEntity>

    @Query("""SELECT articleId, sourceDeviceId, url, title, siteName, excerpt,
               NULL AS contentHtml,
               '' AS contentText,
               imageUrl, contentHash, importedAt, updatedAt, independentSaved,
               independentChangedAt, independentSortOrder, rssSourceUrl, rssSourceTitle,
               favoriteSaved, favoriteChangedAt, favoriteSortOrder,
               watchLaterSaved, watchLaterChangedAt, watchLaterSortOrder,
               deleted, deletedAt, syncBodyHash, syncBodyByteCount,
               syncChunkSize, syncChunkHashesJson, syncMetadataHash, readingProgress, isRead,
               readingPositionBytes, readingPositionContentHash, readingPositionChangedAt
        FROM phone_articles WHERE lower(trim(url)) LIKE 'https://watchrss.local/import-%'
        AND NOT EXISTS(SELECT 1 FROM imported_book_chapters c WHERE c.articleId=phone_articles.articleId)
        ORDER BY articleId""")
    suspend fun unregisteredBookMetadata():List<PhoneArticleEntity>

    @Query(
        """
        SELECT articleId, sourceDeviceId, url, title, siteName, excerpt,
               substr(contentHtml, 1, 1024) AS contentHtml,
               substr(contentText, 1, 1024) AS contentText,
               imageUrl, contentHash, importedAt, updatedAt, independentSaved,
               independentChangedAt, independentSortOrder, rssSourceUrl, rssSourceTitle,
               favoriteSaved, favoriteChangedAt, favoriteSortOrder,
               watchLaterSaved, watchLaterChangedAt, watchLaterSortOrder,
               deleted, deletedAt, syncBodyHash, syncBodyByteCount,
               syncChunkSize, syncChunkHashesJson, syncMetadataHash, readingProgress, isRead,
               readingPositionBytes, readingPositionContentHash, readingPositionChangedAt
        FROM phone_articles
        """
    )
    suspend fun getAllMetadataForSync(): List<PhoneArticleEntity>

    @Query(
        """
        SELECT articleId, sourceDeviceId, url, title, siteName, excerpt,
               substr(contentHtml, 1, 1024) AS contentHtml,
               substr(contentText, 1, 1024) AS contentText,
               imageUrl, contentHash, importedAt, updatedAt, independentSaved,
               independentChangedAt, independentSortOrder, rssSourceUrl, rssSourceTitle,
               favoriteSaved, favoriteChangedAt, favoriteSortOrder,
               watchLaterSaved, watchLaterChangedAt, watchLaterSortOrder,
               deleted, deletedAt, syncBodyHash, syncBodyByteCount,
               syncChunkSize, syncChunkHashesJson, syncMetadataHash, readingProgress, isRead,
               readingPositionBytes, readingPositionContentHash, readingPositionChangedAt
        FROM phone_articles
        WHERE articleId IN (:articleIds)
        """
    )
    suspend fun getMetadataForSync(articleIds: List<String>): List<PhoneArticleEntity>

    @Query(
        """
        SELECT CASE WHEN contentHtml IS NULL THEN NULL
            ELSE COALESCE(substr(CAST(contentHtml AS BLOB), :startByte, :byteCount), X'') END
        FROM phone_articles
        WHERE articleId = :articleId
        LIMIT 1
        """
    )
    suspend fun getContentHtmlChunk(
        articleId: String,
        startByte: Int,
        byteCount: Int
    ): ByteArray?

    @Query(
        """
        SELECT COALESCE(substr(CAST(contentText AS BLOB), :startByte, :byteCount), X'')
        FROM phone_articles
        WHERE articleId = :articleId
        LIMIT 1
        """
    )
    suspend fun getContentTextChunk(
        articleId: String,
        startByte: Int,
        byteCount: Int
    ): ByteArray?

    @Query(
        """
        UPDATE phone_articles
        SET syncBodyHash = :bodyHash,
            syncBodyByteCount = :bodyByteCount,
            syncChunkSize = :chunkSize,
            syncChunkHashesJson = :chunkHashesJson,
            syncMetadataHash = :metadataHash
        WHERE articleId = :articleId
        """
    )
    suspend fun updateSyncMetadata(
        articleId: String,
        bodyHash: String,
        bodyByteCount: Long,
        chunkSize: Int,
        chunkHashesJson: String,
        metadataHash: String
    )

    @Query("UPDATE phone_articles SET title = :title, updatedAt = :updatedAt, syncMetadataHash = '' WHERE articleId = :articleId")
    suspend fun updateTitle(articleId: String, title: String, updatedAt: Long)

    @Query(
        """
        UPDATE phone_articles
        SET readingProgress = :progress,
            readingPositionBytes = :positionBytes,
            readingPositionContentHash = :positionContentHash,
            readingPositionChangedAt = :positionChangedAt,
            isRead = CASE WHEN :progress > 0 THEN 1 ELSE isRead END
        WHERE articleId = :articleId
        """
    )
    suspend fun updateReadingProgress(
        articleId: String,
        progress: Float,
        positionBytes: Long,
        positionContentHash: String,
        positionChangedAt: Long
    )

    @Query(
        """SELECT CASE WHEN substr(contentText,1,length(:prefix)) = :prefix THEN contentText END
        FROM phone_articles WHERE articleId = :articleId"""
    )
    suspend fun contentTextMarker(articleId: String, prefix: String = ARTICLE_CONTENT_MARKER_PREFIX): String?

    @Query("SELECT length(CAST(contentText AS BLOB)) FROM phone_articles WHERE articleId = :articleId")
    suspend fun contentTextByteLength(articleId: String): Long

    @Query(
        """UPDATE phone_articles SET sourceDeviceId = :source, isRead = :read,
        readingPositionChangedAt = max(:now, readingPositionChangedAt + 1)
        WHERE articleId = :articleId"""
    )
    suspend fun updateReadState(articleId: String, source: String, read: Boolean, now: Long): Int

    @Transaction
    suspend fun setReadState(articleId: String, source: String, read: Boolean, now: Long): Long? {
        if (updateReadState(articleId, source, read, now) == 0) return null
        return getBodyMetadataById(articleId)?.readingPositionChangedAt
    }

    @Query(
        """UPDATE phone_articles SET sourceDeviceId = :source,
        favoriteSaved = :saved, favoriteChangedAt = :now, favoriteSortOrder = :sortOrder,
        deleted = :deleted, deletedAt = :deletedAt WHERE articleId = :articleId"""
    )
    suspend fun updateFavoriteState(articleId: String, source: String, saved: Boolean, now: Long,
        sortOrder: Long, deleted: Boolean, deletedAt: Long)

    @Query(
        """UPDATE phone_articles SET sourceDeviceId = :source,
        watchLaterSaved = :saved, watchLaterChangedAt = :now, watchLaterSortOrder = :sortOrder,
        deleted = :deleted, deletedAt = :deletedAt WHERE articleId = :articleId"""
    )
    suspend fun updateWatchLaterState(articleId: String, source: String, saved: Boolean, now: Long,
        sortOrder: Long, deleted: Boolean, deletedAt: Long)

    @Transaction
    suspend fun toggleSavedState(article: PhoneArticleEntity, source: String, favorite: Boolean, now: Long): PhoneArticleEntity {
        val existing = getBodyMetadataById(article.articleId)
        val current = existing ?: article
        val toggled = if (favorite) current.copy(
            favoriteSaved = !current.favoriteSaved,
            favoriteChangedAt = now,
            favoriteSortOrder = if (current.favoriteSaved) current.favoriteSortOrder else now
        ) else current.copy(
            watchLaterSaved = !current.watchLaterSaved,
            watchLaterChangedAt = now,
            watchLaterSortOrder = if (current.watchLaterSaved) current.watchLaterSortOrder else now
        )
        val deleted = !toggled.favoriteSaved && !toggled.watchLaterSaved &&
            !toggled.independentSaved && toggled.rssSourceUrl.isNullOrBlank()
        val updated = toggled.copy(sourceDeviceId = source, deleted = deleted, deletedAt = if (deleted) now else 0)
        when {
            existing == null -> upsert(updated)
            favorite -> updateFavoriteState(article.articleId, source, updated.favoriteSaved, now,
                updated.favoriteSortOrder, deleted, updated.deletedAt)
            else -> updateWatchLaterState(article.articleId, source, updated.watchLaterSaved, now,
                updated.watchLaterSortOrder, deleted, updated.deletedAt)
        }
        return updated
    }

    @Transaction
    suspend fun saveReadingPosition(
        articleId: String,
        progress: Float,
        now: Long,
        positionAt: suspend (PhoneArticleEntity) -> Long
    ): Long? {
        val article = getBodyMetadataById(articleId) ?: return null
        val position = positionAt(article)
        if (article.readingProgress == progress && article.readingPositionBytes == position &&
            article.readingPositionContentHash == article.contentHash && (progress <= 0 || article.isRead)) return null
        val clock = maxOf(now, article.readingPositionChangedAt + 1)
        updateReadingProgress(articleId, progress, position, article.contentHash, clock)
        return clock
    }

    @Upsert
    suspend fun upsert(article: PhoneArticleEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM phone_articles WHERE independentSaved = 1 AND deleted = 0)")
    suspend fun hasIndependentSaved(): Boolean

    /** Writes the columns that a channel reorder changes. The article body is not read or written. */
    @Query(
        """UPDATE phone_articles SET sourceDeviceId = :sourceDeviceId, independentChangedAt = :changedAt,
        independentSortOrder = :sortOrder, deleted = 0 WHERE articleId = :articleId"""
    )
    suspend fun updateIndependentOrder(articleId: String, sourceDeviceId: String, changedAt: Long, sortOrder: Long)

    @Upsert
    suspend fun upsertAll(articles: List<PhoneArticleEntity>)

    @Query("DELETE FROM phone_articles WHERE rssSourceUrl = :rssSourceUrl")
    suspend fun deleteByRssSourceUrl(rssSourceUrl: String)

    @Query("DELETE FROM phone_articles WHERE articleId IN (:articleIds)")
    suspend fun deleteByIds(articleIds: List<String>)

    @Query("DELETE FROM phone_articles")
    suspend fun deleteAll()
}

/**
 * Read at most 64 KiB per window, then decode the complete UTF-8 byte sequence.
 * BLOB substr preserves embedded NULs and avoids both UTF-16/code-point offset drift
 * and decoding a multibyte character split across two fragments.
 */
private suspend fun readBodyChunks(read: suspend (Int, Int) -> ByteArray?): String? {
    val first = read(1, BODY_CHUNK_BYTES) ?: return null
    if (first.size < BODY_CHUNK_BYTES) return first.toString(Charsets.UTF_8)
    val result = ByteArrayOutputStream().apply { write(first) }
    var start = first.size + 1
    while (true) {
        val chunk = read(start, BODY_CHUNK_BYTES) ?: break
        result.write(chunk)
        if (chunk.size < BODY_CHUNK_BYTES) break
        start += chunk.size
    }
    return result.toString(Charsets.UTF_8.name())
}
