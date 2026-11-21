package com.lightningstudio.watchrss.phone.features

import java.security.MessageDigest

internal enum class FeatureOverride(val wire: String) { ON("on"), OFF("off") }

internal enum class RolloutSource { BUCKET, INELIGIBLE, FORCED_ON, FORCED_OFF, DEBUG }

internal data class FeatureDecision(val enabled: Boolean, val source: RolloutSource)

internal data class FeatureRollout(
    val percent: Int,
    val salt: String,
    val minAppVersionCode: Int = 0,
    val maxAppVersionCode: Int? = null,
    val forced: FeatureOverride? = null
)

internal object FeatureRolloutPolicy {
    fun bucket(salt: String, installId: String): Int {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest("$salt:$installId".toByteArray(Charsets.UTF_8))
        return (((hash[0].toInt() and 0xff) shl 8) or (hash[1].toInt() and 0xff)) % 100
    }

    fun decide(
        rollout: FeatureRollout,
        installId: String,
        eligible: Boolean,
        appVersionCode: Int,
        debugOverride: FeatureOverride?
    ): FeatureDecision {
        if (!eligible) return FeatureDecision(false, RolloutSource.INELIGIBLE)
        if (rollout.percent == 0 ||
            appVersionCode !in rollout.minAppVersionCode..(rollout.maxAppVersionCode ?: Int.MAX_VALUE)) {
            return FeatureDecision(false, RolloutSource.BUCKET)
        }
        if (debugOverride != null) return FeatureDecision(debugOverride == FeatureOverride.ON, RolloutSource.DEBUG)
        // The server resolves overlapping forced lists to a single value, with off taking priority.
        return when (rollout.forced) {
            FeatureOverride.OFF -> FeatureDecision(false, RolloutSource.FORCED_OFF)
            FeatureOverride.ON -> FeatureDecision(true, RolloutSource.FORCED_ON)
            null -> FeatureDecision(bucket(rollout.salt, installId) < rollout.percent, RolloutSource.BUCKET)
        }
    }
}
