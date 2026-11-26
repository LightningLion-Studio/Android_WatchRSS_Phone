package com.lightningstudio.watchrss.phone.rollout

import com.lightningstudio.watchrss.phone.features.FeatureDecision
import com.lightningstudio.watchrss.phone.features.RolloutSource
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
    val cloudDecision: FeatureDecision?,
    val override: FeatureOverride?,
    val effective: FeatureDecision,
    val accessRetained: Boolean = false
) {
    val enabled: Boolean get() = accessRetained || effective.enabled
    val remoteEnabled: Boolean? get() = cloudDecision?.enabled

    val status: String
        get() = if (accessRetained) "已启用账号 · $policyStatus" else policyStatus

    private val policyStatus: String
        get() {
            val local = effective.source == RolloutSource.DEBUG
            val rollout = remote
            val cloud = cloudDecision
            if (rollout == null || cloud == null) {
                return if (local) "本机强制 · 云端值未取到" else "包内默认"
            }
            val hit = if (cloud.enabled) "命中" else "未命中"
            val source = when (cloud.source) {
                RolloutSource.FORCED_ON -> "云端强制开"
                RolloutSource.FORCED_OFF -> "云端强制关"
                RolloutSource.INELIGIBLE -> "未满足启用条件"
                RolloutSource.STALE -> "云端配置已过期"
                else -> "放量 ${rollout.percent}% · $hit"
            }
            if (local) {
                val cloudEnabled = if (cloud.enabled) "开" else "关"
                return "本机强制 · 云端为 $cloudEnabled（$source）"
            }
            return if (cloud.source == RolloutSource.BUCKET) "云控 · $source" else source
        }
}
