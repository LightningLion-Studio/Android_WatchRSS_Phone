package com.lightningstudio.watchrss.phone.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "llm_token_usage",
    indices = [
        Index(value = ["createdAt"]),
        Index(value = ["provider", "model"]),
        Index(value = ["remoteKey"], unique = true)
    ]
)
data class PhoneLlmTokenUsageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val remoteKey: String? = null,
    val provider: String = "",
    val model: String = "",
    val requestId: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
    val reasoningTokens: Int? = null,
    val cachedPromptTokens: Int? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val promptTokenCount: Int? = null,
    val candidatesTokenCount: Int? = null,
    val totalTokenCount: Int? = null
)

@Entity(tableName = "llm_token_usage_sync_state")
data class PhoneTokenUsageSyncState(@PrimaryKey val peerDeviceId:String,val usageEpoch:String,val afterId:Long)
