package com.lightningstudio.watchrss.phone.rollout

import com.lightningstudio.watchrss.phone.features.FeatureDecision
import com.lightningstudio.watchrss.phone.features.RolloutSource
import com.lightningstudio.watchrss.phone.features.FeatureRegistry
import org.json.JSONObject

internal enum class B2Cohort(val wire: String) {
    ON("b2_on"), OFF("b2_off"), INELIGIBLE("ineligible"),
    FORCED_ON("forced_on"), FORCED_OFF("forced_off"), DEBUG("debug_override")
}

internal data class B2RolloutDecision(
    val enabled: Boolean,
    val cohort: B2Cohort,
    val configVersion: Long,
    val configStale: Boolean = false
) {
    constructor(decision: FeatureDecision, configVersion: Long, configStale: Boolean) : this(decision.enabled, when (decision.source) {
        RolloutSource.BUCKET -> if (decision.enabled) B2Cohort.ON else B2Cohort.OFF
        RolloutSource.INELIGIBLE -> B2Cohort.INELIGIBLE
        RolloutSource.FORCED_ON -> B2Cohort.FORCED_ON
        RolloutSource.FORCED_OFF -> B2Cohort.FORCED_OFF
        RolloutSource.DEBUG -> B2Cohort.DEBUG
        RolloutSource.STALE -> B2Cohort.OFF
    }, configVersion, configStale)
}

/** Only a validated D2 offer is eligible. A v14 discovery response can advertise legacy B2. */
internal suspend fun ClientConfigRepository.decideB2(
    buildAndModeEligible: Boolean,
    guardedOffer: JSONObject?
): B2RolloutDecision {
    val guardedVersion = guardedOffer?.opt("guardedLibraryVersion")
    val eligible = buildAndModeEligible && (guardedVersion == 1 || guardedVersion == 1L) &&
        guardedOffer?.optJSONObject("libraryBinary") != null
    val config = snapshotForSession(waitForRefresh = eligible)
    return B2RolloutDecision(decide(config, FeatureRegistry.b2, eligible), config.version, config.stale)
}
