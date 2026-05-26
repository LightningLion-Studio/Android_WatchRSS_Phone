package com.lightningstudio.watchrss.phone.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PhoneArticleDao {
    @Query(
        """
        SELECT articleId, sourceDeviceId, url, title, siteName, excerpt,
               NULL AS contentHtml, '' AS contentText, imageUrl, contentHash,
               importedAt, updatedAt, independentSaved, independentChangedAt,
               independentSortOrder, rssSourceUrl, rssSourceTitle,
               favoriteSaved, favoriteChangedAt, favoriteSortOrder,
               watchLaterSaved, watchLaterChangedAt, watchLaterSortOrder,
               deleted, deletedAt, syncBodyHash, syncBodyByteCount,
               syncChunkSize, syncChunkHashesJson, syncMetadataHash
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
               syncChunkSize, syncChunkHashesJson, syncMetadataHash
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
               phone_articles.syncMetadataHash AS syncMetadataHash
        FROM phone_articles
        LEFT JOIN phone_rss_sources ON phone_rss_sources.url = phone_articles.rssSourceUrl
        WHERE phone_articles.deleted = 0
          AND phone_articles.rssSourceUrl IS NOT NULL
          AND phone_articles.rssSourceUrl != ''
          AND phone_articles.rssSourceUrl != :importedContentSourceUrl
          AND COALESCE(phone_rss_sources.deleted, 0) = 0
        ORDER BY phone_articles.updatedAt DESC, phone_articles.importedAt DESC, phone_articles.title ASC
        """
    )
    fun observeRssArticles(importedContentSourceUrl: String): Flow<List<PhoneArticleEntity>>

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
               phone_articles.syncMetadataHash AS syncMetadataHash
        FROM phone_articles
        LEFT JOIN phone_rss_sources ON phone_rss_sources.url = phone_articles.rssSourceUrl
        WHERE phone_articles.deleted = 0
          AND (
              phone_articles.rssSourceUrl = :importedContentSourceUrl
              OR phone_articles.url LIKE :importedTextArticlePrefix
          )
          AND COALESCE(phone_rss_sources.deleted, 0) = 0
        ORDER BY phone_articles.rssSourceTitle ASC,
                 phone_articles.updatedAt DESC,
                 phone_articles.importedAt DESC,
                 phone_articles.title ASC
        """
    )
    fun observeImportedContentArticles(
        importedContentSourceUrl: String,
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
               syncChunkSize, syncChunkHashesJson, syncMetadataHash
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
               syncChunkSize, syncChunkHashesJson, syncMetadataHash
        FROM phone_articles
        WHERE deleted = 0 AND watchLaterSaved = 1
        ORDER BY watchLaterSortOrder DESC, watchLaterChangedAt DESC, title ASC
        """
    )
    fun observeWatchLater(): Flow<List<PhoneArticleEntity>>

    @Query("SELECT * FROM phone_articles WHERE articleId = :articleId LIMIT 1")
    suspend fun getById(articleId: String): PhoneArticleEntity?

    @Query("SELECT * FROM phone_articles WHERE rssSourceUrl = :rssSourceUrl")
    suspend fun getByRssSourceUrl(rssSourceUrl: String): List<PhoneArticleEntity>

    @Query("SELECT * FROM phone_articles WHERE articleId = :articleId LIMIT 1")
    fun observeById(articleId: String): Flow<PhoneArticleEntity?>

    @Query("SELECT * FROM phone_articles")
    suspend fun getAllForSync(): List<PhoneArticleEntity>

    @Query("UPDATE phone_articles SET title = :title, updatedAt = :updatedAt, syncMetadataHash = '' WHERE articleId = :articleId")
    suspend fun updateTitle(articleId: String, title: String, updatedAt: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(article: PhoneArticleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(articles: List<PhoneArticleEntity>)

    @Query("DELETE FROM phone_articles WHERE rssSourceUrl = :rssSourceUrl")
    suspend fun deleteByRssSourceUrl(rssSourceUrl: String)
}
