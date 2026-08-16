package com.lightningstudio.watchrss.phone.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnboardingProfileBuilderTest {

    private fun fullAnswers() = mapOf(
        "scene" to listOf("通勤路上", "睡前躺平"),
        "watch_ownership" to listOf("有 OPPO 手表"),
        "categories" to listOf("科技", "财经", "生活"),
        "current_daily" to listOf("5"),
        "platforms" to listOf("RSS 订阅", "网页文章"),
        "favorite_website" to listOf("https://example.com"),
        "unfinished_article" to listOf("一篇没看完的长文"),
        "daily_target" to listOf("3"),
        "why_read_more" to listOf("想戒掉无意识刷短视频"),
        "plan_name" to listOf("睡前充电计划"),
        "commitment_days" to listOf("30"),
        "daily_target_reason" to listOf("睡前看完正好")
    )

    private fun draftWith(answers: Map<String, List<String>>) = OnboardingDraft(
        stepIndex = 20,
        answers = answers,
        importedArticleId = "a1b2c3",
        importedArticleTitle = "论持久战"
    )

    @Test
    fun `answered count includes all answered keys plus imported article`() {
        assertEquals(12, OnboardingProfileBuilder.answeredCount(draftWith(fullAnswers())))
    }

    @Test
    fun `answered count ignores blank answers and skipped keys`() {
        val draft = draftWith(mapOf("scene" to listOf(" ", ""), "plan_name" to listOf("名字")))
        // 只有 plan_name 一个有效答案 + 已导入文章 = 2
        assertEquals(2, OnboardingProfileBuilder.answeredCount(draft))
    }

    @Test
    fun `answers are trimmed and truncated to step max chars`() {
        val longWebsite = "https://example.com/" + "a".repeat(100)
        val draft = draftWith(
            mapOf("favorite_website" to listOf("  $longWebsite  "))
        )
        val profile = OnboardingProfileBuilder.buildProfile(draft)
        assertEquals(80, profile.favoriteWebsite.length)
        assertEquals(longWebsite.trim().take(80), profile.favoriteWebsite)
    }

    @Test
    fun `multi select answers are capped at eight items`() {
        val draft = draftWith(
            mapOf("categories" to (1..12).map { "类别$it" })
        )
        val profile = OnboardingProfileBuilder.buildProfile(draft)
        assertEquals(8, profile.preferredCategories.size)
    }

    @Test
    fun `numbers are clamped to step range and invalid numbers dropped`() {
        val draft = draftWith(
            mapOf(
                "daily_target" to listOf("99"),      // 范围 1..50 → 50
                "commitment_days" to listOf("999"),  // 范围 1..365 → 365
                "current_daily" to listOf("abc")     // 非法 → 空
            )
        )
        val profile = OnboardingProfileBuilder.buildProfile(draft)
        assertEquals("50", profile.dailyTarget)
        assertEquals("365", profile.commitmentDays)
        assertEquals("", profile.currentDailyCount)
    }

    @Test
    fun `tier is none when profile is null or empty`() {
        assertEquals(ProfileTier.NONE, OnboardingProfileBuilder.onboardingTier(null))
        assertEquals(
            ProfileTier.NONE,
            OnboardingProfileBuilder.onboardingTier(OnboardingProfile(answeredCount = 0))
        )
    }

    @Test
    fun `tier is partial for one to five answers without plan name`() {
        assertEquals(
            ProfileTier.PARTIAL,
            OnboardingProfileBuilder.onboardingTier(OnboardingProfile(answeredCount = 3))
        )
        // 即使达到 6 项，没有计划名也不算 FULL
        assertEquals(
            ProfileTier.PARTIAL,
            OnboardingProfileBuilder.onboardingTier(OnboardingProfile(answeredCount = 6))
        )
    }

    @Test
    fun `tier is full only with plan name and at least six answers`() {
        assertEquals(
            ProfileTier.FULL,
            OnboardingProfileBuilder.onboardingTier(
                OnboardingProfile(planName = "睡前充电计划", answeredCount = 6)
            )
        )
        assertEquals(
            ProfileTier.PARTIAL,
            OnboardingProfileBuilder.onboardingTier(
                OnboardingProfile(planName = "睡前充电计划", answeredCount = 5)
            )
        )
    }

    @Test
    fun `all skipped input yields none tier`() {
        val draft = OnboardingDraft(
            stepIndex = 20,
            answers = emptyMap(),
            skipped = setOf(
                "favorite_website", "unfinished_article", "daily_target",
                "why_read_more", "plan_name", "commitment_days", "first_import"
            )
        )
        val profile = OnboardingProfileBuilder.buildProfile(draft)
        assertEquals(ProfileTier.NONE, OnboardingProfileBuilder.onboardingTier(profile))
        assertEquals(0, profile.answeredCount)
    }

    @Test
    fun `profile carries imported article fields and drops blank title`() {
        val profile = OnboardingProfileBuilder.buildProfile(draftWith(fullAnswers()))
        assertEquals("a1b2c3", profile.importedArticleId)
        assertEquals("论持久战", profile.importedArticleTitle)

        val blank = OnboardingProfileBuilder.buildProfile(
            draftWith(fullAnswers()).copy(importedArticleTitle = "   ")
        )
        assertNull(blank.importedArticleTitle)
    }
}
