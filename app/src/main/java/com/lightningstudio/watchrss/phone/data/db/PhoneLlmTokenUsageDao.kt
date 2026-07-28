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
