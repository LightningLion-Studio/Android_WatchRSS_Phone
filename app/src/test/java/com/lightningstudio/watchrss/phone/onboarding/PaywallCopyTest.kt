package com.lightningstudio.watchrss.phone.onboarding

import com.lightningstudio.watchrss.phone.account.AppAccessSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaywallCopyTest {

    private val summary = AppAccessSummary(
        purchaseCount = 2,
        capacity = 3,
        occupied = 3,
        deviceStatus = "purchase_required"
    )

    @Test
    fun `full tier echoes plan name scene target commitment and article`() {
        val profile = OnboardingProfile(
            planName = "睡前充电计划",
            primaryScene = "睡前躺平",
            dailyTarget = "3",
            commitmentDays = "30",
            importedArticleTitle = "论持久战",
            answeredCount = 9
        )
        val copy = OnboardingProfileBuilder.paywallCopyFor(summary, profile)
        assertEquals("你的阅读计划已准备好", copy.title)
        assertTrue(copy.detail.contains("睡前躺平"))
        assertTrue(copy.detail.contains("每天 3 篇"))
        assertTrue(copy.detail.contains("坚持 30 天"))
        assertTrue(copy.detail.contains("《论持久战》"))
        assertTrue(copy.detail.contains("一次支付 ¥6 获取手机版设备授权包"))
        assertTrue(copy.detail.contains("哔哩哔哩"))
        assertTrue(copy.detail.contains("抖音"))
        assertTrue(copy.detail.contains("小说阅读"))
        assertTrue(copy.detail.contains("备忘录"))
        assertFalse(copy.detail.contains("完整体验"))
        assertFalse(copy.detail.contains("就差最后一步"))
        assertFalse(copy.detail.contains("已购买"))
        assertFalse(copy.detail.contains("剩余"))
        assertEquals("前往网页支付", copy.actionLabel)
    }

    @Test
    fun `full tier tolerates missing optional fields`() {
        val profile = OnboardingProfile(
            planName = "计划",
            answeredCount = 6
        )
        val copy = OnboardingProfileBuilder.paywallCopyFor(summary, profile)
        assertEquals("你的阅读计划已准备好", copy.title)
        assertTrue(copy.detail.contains("你的碎片时间"))
        assertTrue(copy.detail.contains("每天 若干 篇"))
        assertTrue(copy.detail.contains("坚持 每一天 天"))
        assertFalse(copy.detail.contains("第一篇文章"))
    }

    @Test
    fun `partial tier reports saved count`() {
        val profile = OnboardingProfile(planName = "", answeredCount = 3)
        val copy = OnboardingProfileBuilder.paywallCopyFor(summary, profile)
        assertEquals("你的阅读计划已保存", copy.title)
        assertTrue(copy.detail.contains("3 个定制项已保存"))
    }

    @Test
    fun `none tier applies loss framing and leaks no user content`() {
        val copy = OnboardingProfileBuilder.paywallCopyFor(summary, null)
        assertEquals("了解手机版设备授权", copy.title)
        assertTrue(copy.detail.contains("小说阅读"))
        assertTrue(copy.detail.contains("备忘录"))
        assertTrue(copy.detail.contains("哔哩哔哩"))
        assertTrue(copy.detail.contains("抖音"))
        assertFalse(copy.detail.contains("完整体验"))
        assertFalse(copy.detail.contains("已购买"))
        assertFalse(copy.detail.contains("剩余"))

        val emptyProfile = OnboardingProfile(answeredCount = 0)
        val emptyCopy = OnboardingProfileBuilder.paywallCopyFor(summary, emptyProfile)
        assertEquals(copy, emptyCopy)
        assertFalse(emptyCopy.detail.contains("《"))
    }

    @Test
    fun `purchase copy does not expose account counters`() {
        val roomy = summary.copy(occupied = 1)
        val copy = OnboardingProfileBuilder.paywallCopyFor(roomy, null)
        assertFalse(copy.detail.contains("已购买"))
        assertFalse(copy.detail.contains("剩余"))

        val over = summary.copy(occupied = 9)
        val overCopy = OnboardingProfileBuilder.paywallCopyFor(over, null)
        assertEquals(copy, overCopy)
    }
}
