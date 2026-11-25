package com.lightningstudio.watchrss.phone.features

internal enum class EvaluateAt { SESSION, LAUNCH }

internal data class RegisteredFeature(
    val key: String,
    val defaultPercent: Int,
    val defaultSalt: String,
    val evaluateAt: EvaluateAt,
    val releaseDebugOverride: Boolean = false
) {
    val defaults get() = FeatureRollout(defaultPercent, defaultSalt)
}

internal object FeatureRegistry {
    val b2 = RegisteredFeature("b2", 20, "b2-2026-11", EvaluateAt.SESSION)
    val syncHeadPoll = RegisteredFeature("sync_head_poll_v1", 100, "sync-head-poll-v1", EvaluateAt.LAUNCH)
    val entries = listOf(b2, syncHeadPoll)
}
