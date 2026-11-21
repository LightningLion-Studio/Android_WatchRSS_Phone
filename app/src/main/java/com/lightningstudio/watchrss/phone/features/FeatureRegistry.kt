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
    val entries = listOf(b2)
}
