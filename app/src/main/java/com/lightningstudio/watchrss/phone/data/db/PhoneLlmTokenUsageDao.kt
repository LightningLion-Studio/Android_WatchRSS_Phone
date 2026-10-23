package com.lightningstudio.watchrss.phone.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

data class PhoneLlmTokenUsageStatisticsPojo(
    val totalCalls: Long = 0L,
    val totalPromptTokens: Long? = 0L,
    val totalCompletionTokens: Long? = 0L,
    val totalTokens: Long? = 0L
)

data class PhoneLlmTokenUsageDailyPojo(
    val dayTimestamp: Long = 0L,
    val totalTokens: Long? = 0L,
    val promptTokens: Long? = 0L,
    val completionTokens: Long? = 0L,
    val calls: Long = 0L
)

@Dao
interface PhoneLlmTokenUsageDao {
    @Insert
    suspend fun insert(entity: PhoneLlmTokenUsageEntity)

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE)
    suspend fun insertRemote(entity:PhoneLlmTokenUsageEntity):Long

    @Query("SELECT * FROM llm_token_usage_sync_state WHERE peerDeviceId=:peer LIMIT 1")
    suspend fun syncCursor(peer:String):PhoneTokenUsageSyncState?

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun saveSyncCursor(state:PhoneTokenUsageSyncState)

    @Query("SELECT * FROM llm_token_usage WHERE remoteKey IS NULL ORDER BY id")
    suspend fun legacyRecords():List<PhoneLlmTokenUsageEntity>

    @Query("DELETE FROM llm_token_usage WHERE remoteKey IS NULL")
    suspend fun deleteLegacyRecords()

    @Query("SELECT * FROM llm_token_usage WHERE remoteKey=:key LIMIT 1")
    suspend fun remoteRecord(key:String):PhoneLlmTokenUsageEntity?

    @Query("UPDATE llm_token_usage SET remoteKey=:key WHERE id=:id AND remoteKey IS NULL")
    suspend fun adoptLegacyRecord(id:Long,key:String):Int

    @androidx.room.Transaction
    suspend fun replaceLegacyRecords(rows:List<PhoneLlmTokenUsageEntity>) {
        if(legacyRecords().map { it.copy(id=0) }!=rows) { deleteLegacyRecords();rows.forEach { insert(it) } }
    }

    @androidx.room.Transaction
    suspend fun applyRemoteBatch(state:PhoneTokenUsageSyncState,rows:List<PhoneLlmTokenUsageEntity>):Int {
        var applied=0
        val legacy=if(rows.isEmpty())mutableListOf() else legacyRecords().toMutableList()
        rows.forEach { row ->
            val key=requireNotNull(row.remoteKey)
            if(remoteRecord(key)==null) {
                // Old latest-200 rows had no peer/id. Only adopt an exact provider event with
                // a real request ID; anonymous rows cannot safely be attributed to a peer.
                val previous=legacy.firstOrNull { row.requestId.isNotBlank() && it.copy(id=0,remoteKey=key)==row }
                if(previous!=null) {adoptLegacyRecord(previous.id,key);legacy.remove(previous)}
                else if(insertRemote(row)!=-1L)applied++
            }
        }
        if(syncCursor(state.peerDeviceId)!=state)saveSyncCursor(state)
        return applied
    }

    @Query("DELETE FROM llm_token_usage_sync_state")
    suspend fun deleteSyncState()

    @androidx.room.Transaction
    suspend fun clearRecordsAndCursor() { deleteAll();deleteSyncState() }

    @Query("DELETE FROM llm_token_usage")
    suspend fun deleteAll()

    @Query("SELECT * FROM llm_token_usage ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<PhoneLlmTokenUsageEntity>>

    @Query("""
        SELECT
            COUNT(*) AS totalCalls,
            COALESCE(SUM(promptTokens), 0) AS totalPromptTokens,
            COALESCE(SUM(completionTokens), 0) AS totalCompletionTokens,
            COALESCE(SUM(totalTokens), 0) AS totalTokens
        FROM llm_token_usage
    """)
    fun observeStatistics(): Flow<PhoneLlmTokenUsageStatisticsPojo>

    @Query("""
        SELECT
            (createdAt / :bucketMs) * :bucketMs AS dayTimestamp,
            COALESCE(SUM(totalTokens), 0) AS totalTokens,
            COALESCE(SUM(promptTokens), 0) AS promptTokens,
            COALESCE(SUM(completionTokens), 0) AS completionTokens,
            COUNT(*) AS calls
        FROM llm_token_usage
        WHERE createdAt >= :since
        GROUP BY dayTimestamp
        ORDER BY dayTimestamp ASC
    """)
    fun observeDaily(
        since: Long,
        bucketMs: Long = 86400000L
    ): Flow<List<PhoneLlmTokenUsageDailyPojo>>
}
