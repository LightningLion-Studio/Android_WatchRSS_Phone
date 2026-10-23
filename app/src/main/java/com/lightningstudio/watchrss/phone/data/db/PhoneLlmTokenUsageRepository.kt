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

    suspend fun cursor(peer:String):PhoneTokenUsageSyncState?=dao.syncCursor(peer)

    suspend fun applyIncremental(peer:String,response:JSONObject):Int=withContext(Dispatchers.IO) {
        require(response.get("usageEpoch") is String && response.get("hasMore") is Boolean)
        if(response.has("reset"))require(response.get("reset") is Boolean)
        val epoch=response.getString("usageEpoch");require(epoch.isNotBlank() && epoch.length<=160)
        val previous=dao.syncCursor(peer)
        val next=integer(response,"nextAfterId");require(next>=0)
        val values=response.getJSONArray("records")
        require(integer(response,"count")==values.length().toLong()) { "词元用量批次计数无效" }
        require(previous==null || previous.usageEpoch==epoch || response.optBoolean("reset")) { "词元用量代际变化未确认" }
        val rows=mutableListOf<PhoneLlmTokenUsageEntity>();var last=0L
        for(i in 0 until values.length()) {
            val json=values.getJSONObject(i);val id=integer(json,"id")
            require(id>last && id<=next) { "词元用量增量次序无效" };last=id
            val key=com.lightningstudio.watchrss.phone.cloud.CloudSnapshotCodec.sha256(com.lightningstudio.watchrss.phone.cloud.CloudProtocolV2.lp(peer,epoch,id.toString()))
            rows+=decode(json).copy(remoteKey=key)
        }
        require(next>=last && (previous?.usageEpoch!=epoch || next>=previous.afterId))
        if(response.getBoolean("hasMore"))require(values.length()>0 && next> (previous?.takeIf { it.usageEpoch==epoch }?.afterId?:0L))
        dao.applyRemoteBatch(PhoneTokenUsageSyncState(peer,epoch,next),rows)
    }

    suspend fun replaceRecords(records: List<JSONObject>) = withContext(Dispatchers.IO) {
        val rows=records.map(::decode)
        dao.replaceLegacyRecords(rows)
    }

    private fun integer(json:JSONObject,key:String):Long {
        val value=json.get(key);require(value is Int || value is Long) { "词元用量游标格式无效" }
        return (value as Number).toLong().also { require(it in 0..9_007_199_254_740_991L) }
    }

    private fun decode(json:JSONObject)=PhoneLlmTokenUsageEntity(
        provider=json.optString("provider"),model=json.optString("model"),requestId=json.optString("requestId"),
        createdAt=json.optLong("createdAt"),promptTokens=count(json,"promptTokens"),completionTokens=count(json,"completionTokens"),
        totalTokens=count(json,"totalTokens"),reasoningTokens=count(json,"reasoningTokens"),cachedPromptTokens=count(json,"cachedPromptTokens"),
        inputTokens=count(json,"inputTokens"),outputTokens=count(json,"outputTokens"),promptTokenCount=count(json,"promptTokenCount"),
        candidatesTokenCount=count(json,"candidatesTokenCount"),totalTokenCount=count(json,"totalTokenCount"))
    private fun count(json:JSONObject,key:String):Int?=json.optInt(key,-1).takeIf { it>=0 }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        dao.clearRecordsAndCursor()
    }
}
