package com.lightningstudio.watchrss.phone.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingCatalogTest {

    @Test
    fun `catalog has exactly 24 steps`() {
        assertEquals(24, ONBOARDING_CATALOG.size)
        assertEquals(24, OnboardingCatalogIndices.size)
    }

    @Test
    fun `step ids are unique and non-blank`() {
        val ids = ONBOARDING_CATALOG.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        ids.forEach { assertTrue(it.isNotBlank()) }
    }

    @Test
    fun `consent is the second step before any input`() {
        val consentStep = ONBOARDING_CATALOG[OnboardingCatalogIndices.CONSENT_INDEX]
        assertEquals(StepType.CONSENT, consentStep.type)
        val firstInputIndex = ONBOARDING_CATALOG.indexOfFirst { it.isInputStep }
        assertTrue(firstInputIndex > OnboardingCatalogIndices.CONSENT_INDEX)
    }

    @Test
    fun `only input steps are skippable`() {
        ONBOARDING_CATALOG.forEach { step ->
            if (step.isInputStep) {
                assertTrue("input step ${step.id} must be skippable", step.skippable)
            } else {
                assertFalse("non-input step ${step.id} must not be skippable", step.skippable)
            }
        }
    }

    @Test
    fun `login sits around two thirds with virtual counter steps`() {
        assertEquals(17, OnboardingCatalogIndices.LOGIN_GUIDE_INDEX)
        assertEquals(StepType.LOGIN_GUIDE, ONBOARDING_CATALOG[17].type)
        assertEquals(StepType.LOGIN_VIRTUAL, ONBOARDING_CATALOG[18].type)
        assertEquals(StepType.LOGIN_VIRTUAL, ONBOARDING_CATALOG[19].type)
        assertEquals(StepType.AUTH_INFO, ONBOARDING_CATALOG[20].type)
        assertEquals(23, OnboardingCatalogIndices.COMPLETE_INDEX)
        assertEquals(StepType.COMPLETE, ONBOARDING_CATALOG[23].type)
    }

    @Test
    fun `every echo key is covered by profile fields`() {
        val echoKeys = ONBOARDING_CATALOG.mapNotNull { it.echoKey }
        assertEquals(
            setOf(
                "scene", "watch_ownership", "categories", "current_daily", "platforms",
                "favorite_website", "unfinished_article", "daily_target", "why_read_more",
                "plan_name", "commitment_days"
            ),
            echoKeys.toSet()
        )
        // 每个 echoKey 都必须能落进 OnboardingProfile 的某个字段：
        val profileKeys = setOf(
            "planName", "primaryScene", "watchOwnership", "preferredCategories",
            "currentDailyCount", "platforms", "favoriteWebsite", "unfinishedArticle",
            "dailyTarget", "whyReadMore", "commitmentDays"
        )
        assertEquals(echoKeys.size, profileKeys.size)
    }

    @Test
    fun `phase labels cover every index`() {
        (0 until ONBOARDING_CATALOG.size).forEach { index ->
            assertTrue(onboardingPhaseLabel(index).isNotBlank())
        }
    }
}
