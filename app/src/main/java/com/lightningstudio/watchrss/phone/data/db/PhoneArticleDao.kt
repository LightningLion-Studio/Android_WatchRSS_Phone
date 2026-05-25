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
        SELECT * FROM phone_articles
        WHERE deleted = 0
        ORDER BY updatedAt DESC, importedAt DESC
        LIMIT :limit
        """
    )
    fun observeRecent(limit: Int): Flow<List<PhoneArticleEntity>>

    @Query(
        """
        SELECT * FROM phone_articles
        WHERE deleted = 0 AND favoriteSaved = 1
        ORDER BY favoriteSortOrder DESC, favoriteChangedAt DESC, title ASC
        """
    )
    fun observeFavorites(): Flow<List<PhoneArticleEntity>>

    @Query(
        """
        SELECT * FROM phone_articles
        WHERE deleted = 0 AND watchLaterSaved = 1
        ORDER BY watchLaterSortOrder DESC, watchLaterChangedAt DESC, title ASC
        """
    )
    fun observeWatchLater(): Flow<List<PhoneArticleEntity>>

    @Query("SELECT * FROM phone_articles WHERE articleId = :articleId LIMIT 1")
    suspend fun getById(articleId: String): PhoneArticleEntity?

    @Query("SELECT * FROM phone_articles WHERE articleId = :articleId LIMIT 1")
    fun observeById(articleId: String): Flow<PhoneArticleEntity?>

    @Query("SELECT * FROM phone_articles")
    suspend fun getAllForSync(): List<PhoneArticleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(article: PhoneArticleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(articles: List<PhoneArticleEntity>)
}
