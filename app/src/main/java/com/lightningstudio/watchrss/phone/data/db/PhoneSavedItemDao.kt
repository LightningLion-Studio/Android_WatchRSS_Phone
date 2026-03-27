package com.lightningstudio.watchrss.phone.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PhoneSavedItemDao {
    @Query("SELECT * FROM phone_saved_items WHERE type = :type ORDER BY syncedAt DESC, title ASC")
    fun observeByType(type: String): Flow<List<PhoneSavedItemEntity>>

    @Query("DELETE FROM phone_saved_items WHERE type = :type")
    suspend fun deleteByType(type: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<PhoneSavedItemEntity>)
}
