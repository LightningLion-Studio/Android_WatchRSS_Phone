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
    fun status(cloudAlreadyEnabled: Boolean = false): String {
        if (cloudAlreadyEnabled) return "已启用（已开启云同步）"
        val state = if (enabled) "已启用" else "未启用"
        val source = when {
            override != null -> "强制"
            remote != null -> "云控"
            else -> "包内默认"
        }
        return "$state（$source）"
    }

    val cloudValue: String
        get() {
            val rollout = remote ?: return "未取到此开关的云端值，使用包内默认"
            val enabledText = if (remoteEnabled == true) "开" else "关"
            val hitText = if (bucketHit == true) "命中" else "未命中"
            val forced = when (rollout.forced) {
                FeatureOverride.ON -> " · 云端强制开"
                FeatureOverride.OFF -> " · 云端强制关"
                null -> ""
            }
            return "$enabledText · 放量 ${rollout.percent}% · $hitText$forced"
        }
}
