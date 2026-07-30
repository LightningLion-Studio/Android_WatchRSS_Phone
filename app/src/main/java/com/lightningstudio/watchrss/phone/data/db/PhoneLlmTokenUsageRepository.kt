package com.lightningstudio.watchrss.phone.data.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONObject

class PhoneLlmTokenUsageRepository(
    private val dao: PhoneLlmTokenUsageDao
) {
    fun observeRecent(limit: Int = 200): Flow<List<PhoneLlmTokenUsageEntity>> =
        dao.observeRecent(limit)

    fun observeStatistics(): Flow<PhoneLlmTokenUsageStatisticsPojo> =
        dao.observeStatistics()

    fun observeDaily(sinceDays: Int = 7): Flow<List<PhoneLlmTokenUsageDailyPojo>> {
        val bucketMs = 24 * 60 * 60 * 1000L
        val since = System.currentTimeMillis() - sinceDays * bucketMs
        return dao.observeDaily(since, bucketMs)
    }

    suspend fun replaceRecords(records: List<JSONObject>) = withContext(Dispatchers.IO) {
        dao.deleteAll()
        records.forEach { json ->
            dao.insert(
                PhoneLlmTokenUsageEntity(
                    provider = json.optString("provider"),
                    model = json.optString("model"),
                    requestId = json.optString("requestId"),
                    createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                    promptTokens = json.optInt("promptTokens", -1).takeIf { it >= 0 },
                    completionTokens = json.optInt("completionTokens", -1).takeIf { it >= 0 },
                    totalTokens = json.optInt("totalTokens", -1).takeIf { it >= 0 },
                    reasoningTokens = json.optInt("reasoningTokens", -1).takeIf { it >= 0 },
                    cachedPromptTokens = json.optInt("cachedPromptTokens", -1).takeIf { it >= 0 },
                    inputTokens = json.optInt("inputTokens", -1).takeIf { it >= 0 },
                    outputTokens = json.optInt("outputTokens", -1).takeIf { it >= 0 },
                    promptTokenCount = json.optInt("promptTokenCount", -1).takeIf { it >= 0 },
                    candidatesTokenCount = json.optInt("candidatesTokenCount", -1).takeIf { it >= 0 },
                    totalTokenCount = json.optInt("totalTokenCount", -1).takeIf { it >= 0 }
                )
            )
        }
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        dao.deleteAll()
    }
}
