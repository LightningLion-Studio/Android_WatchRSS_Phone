package com.lightningstudio.watchrss.phone.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PhoneRssSourceDao {
    @Query(
        """
        SELECT * FROM phone_rss_sources
        WHERE deleted = 0
          AND url NOT LIKE :importedContentPrefix
        ORDER BY isPinned DESC, sortOrder DESC, updatedAt DESC, title ASC
        """
    )
    fun observeActive(importedContentPrefix: String): Flow<List<PhoneRssSourceEntity>>

    @Query("SELECT * FROM phone_rss_sources WHERE url = :url LIMIT 1")
    suspend fun getByUrl(url: String): PhoneRssSourceEntity?

    @Query("SELECT * FROM phone_rss_sources")
    suspend fun getAllForSync(): List<PhoneRssSourceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(source: PhoneRssSourceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(sources: List<PhoneRssSourceEntity>)
}
