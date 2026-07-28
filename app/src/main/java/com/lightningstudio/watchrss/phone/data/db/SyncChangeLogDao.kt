package com.lightningstudio.watchrss.phone.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncChangeLogDao {
    @Insert
    suspend fun insert(change: SyncChangeLogEntity): Long

    @Query("SELECT COALESCE(MAX(seq), 0) FROM sync_change_log")
    suspend fun maxSeq(): Long

    @Query("SELECT COALESCE(MAX(seq), 0) FROM sync_change_log")
    fun observeMaxSeq(): Flow<Long>

    @Query(
        """
        SELECT entityId
        FROM sync_change_log
        WHERE kind = :kind AND seq > :afterSeq
        GROUP BY entityId
        ORDER BY MIN(seq) ASC
        """
    )
    suspend fun entityIdsChangedAfter(kind: String, afterSeq: Long): List<String>

    @Query(
        """
        SELECT entityId, MAX(changedAt) AS changedAt
        FROM sync_change_log
        WHERE kind = :kind AND entityId IN (:entityIds)
        GROUP BY entityId
        """
    )
    suspend fun maxChangedAtByEntityIds(kind: String, entityIds: List<String>): List<SyncChangeLogEntityState>

    @Query("DELETE FROM sync_change_log")
    suspend fun deleteAll()
}

data class SyncChangeLogEntityState(
    val entityId: String,
    val changedAt: Long
)
