package com.lightningstudio.watchrss.phone.features

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureRolloutPolicyTest {
    private val defaults = FeatureRegistry.b2.defaults
    private val installId = "install-0"

    @Test
    fun sharedBackendVectorsAreStableAndRolloutOnlyGrows() {
        for ((id, expected) in listOf("install-0" to 9, "install-1" to 41, "install-2" to 78)) {
            repeat(3) { assertEquals(expected, FeatureRolloutPolicy.bucket(defaults.salt, id)) }
            for (percent in 0..100) {
                assertEquals(expected < percent, FeatureRolloutPolicy.decide(
                    defaults.copy(percent = percent), id, true, 121, null
                ).enabled)
            }
        }
        assertEquals(57, FeatureRolloutPolicy.bucket("b2-2026-12", "install-1"))
        assertEquals(15, FeatureRolloutPolicy.bucket(defaults.salt, "安装-🛰"))
    }

    @Test
    fun forcedDecisionTakesPrecedenceOverBucket() {
        assertEquals(FeatureDecision(false, RolloutSource.FORCED_OFF), decide(defaults.copy(forced = FeatureOverride.OFF)))
        assertEquals(FeatureDecision(true, RolloutSource.FORCED_ON), decide(defaults.copy(percent = 1, forced = FeatureOverride.ON)))
        assertEquals(FeatureDecision(false, RolloutSource.DEBUG), decide(defaults.copy(forced = FeatureOverride.ON), FeatureOverride.OFF))
        assertEquals(FeatureDecision(true, RolloutSource.DEBUG), decide(defaults.copy(forced = FeatureOverride.OFF), FeatureOverride.ON))
    }

    @Test
    fun localOverrideBypassesCloudBoundsButRequiresEligibility() {
        val forced = defaults.copy(forced = FeatureOverride.ON)
        assertTrue(decide(forced.copy(percent = 0), FeatureOverride.ON).enabled)
        assertFalse(decide(forced.copy(percent = 0)).enabled)
        assertTrue(decide(forced.copy(minAppVersionCode = 125), FeatureOverride.ON).enabled)
        assertFalse(decide(forced.copy(minAppVersionCode = 125)).enabled)
        assertTrue(decide(forced.copy(maxAppVersionCode = 120), FeatureOverride.ON).enabled)
        assertFalse(decide(forced.copy(maxAppVersionCode = 120)).enabled)
        assertTrue(decide(forced.copy(minAppVersionCode = 121, maxAppVersionCode = 121)).enabled)
        assertEquals(FeatureDecision(false, RolloutSource.INELIGIBLE),
            FeatureRolloutPolicy.decide(forced, installId, false, 121, FeatureOverride.ON))
    }

    private fun decide(rollout: FeatureRollout, debug: FeatureOverride? = null) =
        FeatureRolloutPolicy.decide(rollout, installId, true, 121, debug)
}
