package com.lightningstudio.watchrss.phone.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingCodecsTest {

    private fun sampleDraft() = OnboardingDraft(
        stepIndex = 12,
        answers = mapOf(
            "scene" to listOf("通勤路上", "睡前躺平"),
            "plan_name" to listOf("睡前充电计划"),
            "daily_target" to listOf("3"),
            "daily_target_reason" to listOf("睡前看完正好")
        ),
        skipped = setOf("favorite_website", "unfinished_article"),
        importedArticleId = "a1b2c3",
        importedArticleTitle = "论持久战",
        startedAtMillis = 1720000000000L,
        updatedAtMillis = 1720000004300L
    )

    @Test
    fun `draft roundtrip preserves everything`() {
        val draft = sampleDraft()
        val restored = OnboardingCodecs.draftFromJson(OnboardingCodecs.draftToJson(draft).toString())
        assertEquals(draft, restored)
    }

    @Test
    fun `corrupt json yields null`() {
        assertNull(OnboardingCodecs.draftFromJson("{not json"))
        assertNull(OnboardingCodecs.draftFromJson(""))
        assertNull(OnboardingCodecs.draftFromJson("""{"schemaVersion":99}"""))
    }

    @Test
    fun `step index is coerced into catalog range on load`() {
        val draft = sampleDraft().copy(stepIndex = 999)
        val restored = OnboardingCodecs.draftFromJson(OnboardingCodecs.draftToJson(draft).toString())!!
        assertEquals(ONBOARDING_CATALOG.size - 1, restored.stepIndex)
    }

    @Test
    fun `blank answer values are dropped on load`() {
        val draft = sampleDraft().copy(answers = mapOf("scene" to listOf(" ", "睡前躺平")))
        val restored = OnboardingCodecs.draftFromJson(OnboardingCodecs.draftToJson(draft).toString())!!
        assertEquals(listOf("睡前躺平"), restored.answers["scene"])
    }

    @Test
    fun `profile roundtrip preserves everything`() {
        val profile = OnboardingProfileBuilder.buildProfile(sampleDraft(), completedAtMillis = 1720000100000L)
        val restored = OnboardingCodecs.profileFromJson(OnboardingCodecs.profileToJson(profile).toString())
        assertEquals(profile, restored)
    }

    @Test
    fun `profile with missing optional fields survives roundtrip`() {
        val profile = OnboardingProfile(answeredCount = 0, completedAtMillis = 1L)
        val restored = OnboardingCodecs.profileFromJson(OnboardingCodecs.profileToJson(profile).toString())
        assertEquals(profile, restored)
        assertTrue(restored!!.preferredCategories.isEmpty())
        assertNull(restored.importedArticleId)
    }
}
