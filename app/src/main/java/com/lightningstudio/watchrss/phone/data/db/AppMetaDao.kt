package com.lightningstudio.watchrss.phone.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AppMetaDao {
    @Query("SELECT value FROM app_meta WHERE key = :key LIMIT 1")
    suspend fun getString(key: String): String?

    @Query("SELECT value FROM app_meta WHERE key = :key LIMIT 1")
    fun observeString(key: String): Flow<String?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun set(entity: AppMetaEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun setIfAbsent(entity: AppMetaEntity)

    @Query("DELETE FROM app_meta")
    suspend fun deleteAll()
}
