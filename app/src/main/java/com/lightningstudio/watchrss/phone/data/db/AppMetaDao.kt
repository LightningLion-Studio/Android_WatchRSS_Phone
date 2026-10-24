package com.lightningstudio.watchrss.phone.data.db

import androidx.room.Dao
import androidx.room.Upsert
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

    @Upsert
    suspend fun set(entity: AppMetaEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun setIfAbsent(entity: AppMetaEntity)

    @Query("SELECT * FROM app_meta")
    suspend fun getAll(): List<AppMetaEntity>

    @Query("DELETE FROM app_meta")
    suspend fun deleteAll()
}
