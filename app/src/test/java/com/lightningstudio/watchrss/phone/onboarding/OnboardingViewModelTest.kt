package com.lightningstudio.watchrss.phone.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingViewModelTest {

    @Test
    fun `back from auth info skips all login virtual steps`() {
        val result = resolveOnboardingBackNavigation(
            OnboardingCatalogIndices.LOGIN_GUIDE_INDEX + 3
        )

        assertEquals(OnboardingCatalogIndices.LOGIN_GUIDE_INDEX, result.targetStepIndex)
        assertFalse(result.shouldRecordDropped)
    }

    @Test
    fun `back from a restored virtual step still reaches login guide`() {
        val result = resolveOnboardingBackNavigation(
            OnboardingCatalogIndices.LOGIN_GUIDE_INDEX + 2
        )

        assertEquals(OnboardingCatalogIndices.LOGIN_GUIDE_INDEX, result.targetStepIndex)
        assertFalse(result.shouldRecordDropped)
    }

    @Test
    fun `back from first step requests exit and dropped telemetry`() {
        val result = resolveOnboardingBackNavigation(0)

        assertNull(result.targetStepIndex)
        assertTrue(result.shouldRecordDropped)
    }

    @Test
    fun `ordinary back moves one step without dropped telemetry`() {
        val result = resolveOnboardingBackNavigation(1)

        assertEquals(0, result.targetStepIndex)
        assertFalse(result.shouldRecordDropped)
    }
}
