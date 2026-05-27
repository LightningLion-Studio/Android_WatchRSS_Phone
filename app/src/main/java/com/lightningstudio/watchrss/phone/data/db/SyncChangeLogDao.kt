package com.lightningstudio.watchrss.phone.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface SyncChangeLogDao {
    @Insert
    suspend fun insert(change: SyncChangeLogEntity): Long

    @Query("SELECT COALESCE(MAX(seq), 0) FROM sync_change_log")
    suspend fun maxSeq(): Long

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
}
