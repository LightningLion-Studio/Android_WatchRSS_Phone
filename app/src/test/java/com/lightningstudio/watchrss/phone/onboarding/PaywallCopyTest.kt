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
        assertEquals("《睡前充电计划》就差最后一步", copy.title)
        assertTrue(copy.detail.contains("睡前躺平"))
        assertTrue(copy.detail.contains("每天 3 篇"))
        assertTrue(copy.detail.contains("坚持 30 天"))
        assertTrue(copy.detail.contains("《论持久战》"))
        assertTrue(copy.detail.contains("已购买 2 次"))
        assertTrue(copy.detail.contains("剩余 0 台"))
        assertEquals("前往网页支付", copy.actionLabel)
    }

    @Test
    fun `full tier tolerates missing optional fields`() {
        val profile = OnboardingProfile(
            planName = "计划",
            answeredCount = 6
        )
        val copy = OnboardingProfileBuilder.paywallCopyFor(summary, profile)
        assertEquals("《计划》就差最后一步", copy.title)
        assertTrue(copy.detail.contains("你的碎片时间"))
        assertTrue(copy.detail.contains("每天 若干 篇"))
        assertTrue(copy.detail.contains("坚持 每一天 天"))
        assertFalse(copy.detail.contains("第一篇文章"))
    }

    @Test
    fun `partial tier reports saved count`() {
        val profile = OnboardingProfile(planName = "", answeredCount = 3)
        val copy = OnboardingProfileBuilder.paywallCopyFor(summary, profile)
        assertEquals("你的阅读计划还差一点定制", copy.title)
        assertTrue(copy.detail.contains("3 个定制项已保存"))
    }

    @Test
    fun `none tier applies loss framing and leaks no user content`() {
        val copy = OnboardingProfileBuilder.paywallCopyFor(summary, null)
        assertEquals("当前账号没有可授权额度", copy.title)
        assertTrue(copy.detail.contains("未定制的阅读计划将无法在手机上继续"))
        assertTrue(copy.detail.contains("定制并继续"))

        val emptyProfile = OnboardingProfile(answeredCount = 0)
        val emptyCopy = OnboardingProfileBuilder.paywallCopyFor(summary, emptyProfile)
        assertEquals(copy, emptyCopy)
        assertFalse(emptyCopy.detail.contains("《"))
    }

    @Test
    fun `remaining slots are computed from capacity and occupied`() {
        val roomy = summary.copy(occupied = 1)
        val copy = OnboardingProfileBuilder.paywallCopyFor(roomy, null)
        assertTrue(copy.detail.contains("剩余 2 台"))

        val over = summary.copy(occupied = 9)
        val overCopy = OnboardingProfileBuilder.paywallCopyFor(over, null)
        assertTrue(overCopy.detail.contains("剩余 0 台"))
    }
}
