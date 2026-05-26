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
               deleted, deletedAt
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
               deleted, deletedAt
        FROM phone_articles
        WHERE deleted = 0 AND independentSaved = 1
        ORDER BY independentSortOrder DESC, independentChangedAt DESC, title ASC
        """
    )
    fun observeIndependent(): Flow<List<PhoneArticleEntity>>

    @Query(
        """
        SELECT articleId, sourceDeviceId, url, title, siteName, excerpt,
               NULL AS contentHtml, '' AS contentText, imageUrl, contentHash,
               importedAt, updatedAt, independentSaved, independentChangedAt,
               independentSortOrder, rssSourceUrl, rssSourceTitle,
               favoriteSaved, favoriteChangedAt, favoriteSortOrder,
               watchLaterSaved, watchLaterChangedAt, watchLaterSortOrder,
               deleted, deletedAt
        FROM phone_articles
        WHERE deleted = 0 AND rssSourceUrl IS NOT NULL AND rssSourceUrl != ''
        ORDER BY updatedAt DESC, importedAt DESC, title ASC
        """
    )
    fun observeRssArticles(): Flow<List<PhoneArticleEntity>>

    @Query(
        """
        SELECT articleId, sourceDeviceId, url, title, siteName, excerpt,
               NULL AS contentHtml, '' AS contentText, imageUrl, contentHash,
               importedAt, updatedAt, independentSaved, independentChangedAt,
               independentSortOrder, rssSourceUrl, rssSourceTitle,
               favoriteSaved, favoriteChangedAt, favoriteSortOrder,
               watchLaterSaved, watchLaterChangedAt, watchLaterSortOrder,
               deleted, deletedAt
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
               deleted, deletedAt
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

    @Query("UPDATE phone_articles SET title = :title, updatedAt = :updatedAt WHERE articleId = :articleId")
    suspend fun updateTitle(articleId: String, title: String, updatedAt: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(article: PhoneArticleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(articles: List<PhoneArticleEntity>)

    @Query("DELETE FROM phone_articles WHERE rssSourceUrl = :rssSourceUrl")
    suspend fun deleteByRssSourceUrl(rssSourceUrl: String)
}
