package com.lightningstudio.watchrss.phone.rollout

import com.lightningstudio.watchrss.phone.features.FeatureOverride
import com.lightningstudio.watchrss.phone.features.FeatureRollout

internal data class ClientConfigDebugSnapshot(
    val config: ClientConfig,
    val fetchedAt: Long?,
    val refreshFailed: Boolean,
    val features: List<ClientFeatureDebugState>
) {
    val configStatus: String
        get() = when {
            fetchedAt == null -> "未取到，使用包内默认"
            refreshFailed -> "未取到新配置，使用上次成功的缓存"
            config.stale -> "使用上次成功的缓存"
            else -> "已取到配置"
        }
}

internal data class ClientFeatureDebugState(
    val key: String,
    val remote: FeatureRollout?,
    val bucketHit: Boolean?,
    val remoteEnabled: Boolean?,
    val override: FeatureOverride?,
    val enabled: Boolean
) {
    val status: String
        get() {
            val rollout = remote ?: return if (override == null) "包内默认" else "本机强制 · 云端值未取到"
            val hit = if (bucketHit == true) "命中" else "未命中"
            val rolloutStatus = "放量 ${rollout.percent}% · $hit"
            if (override == null) return "云控 · $rolloutStatus"
            val cloudEnabled = if (remoteEnabled == true) "开" else "关"
            return "本机强制 · 云端为 $cloudEnabled（$rolloutStatus）"
        }
}
