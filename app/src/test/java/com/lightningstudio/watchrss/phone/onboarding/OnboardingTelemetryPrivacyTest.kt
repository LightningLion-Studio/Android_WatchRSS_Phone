package com.lightningstudio.watchrss.phone.onboarding

import com.lightningstudio.watchrss.phone.data.telemetry.PhoneUsageTelemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 遥测零 PII 不变量：引导事件名是静态常量，不携带任何答案键、答案选项或答案值。
 * 事件内容（自由文本）永远不会进入 DailyTelemetryStore —— capture() 只保留
 * screen/durationMs 白名单属性，新事件构造时即不传任何属性。
 */
class OnboardingTelemetryPrivacyTest {

    private val onboardingEvents = listOf(
        PhoneUsageTelemetry.EVENT_ONBOARDING_STEP_COMPLETED,
        PhoneUsageTelemetry.EVENT_ONBOARDING_STEP_SKIPPED,
        PhoneUsageTelemetry.EVENT_ONBOARDING_IMPORT_SUCCEEDED,
        PhoneUsageTelemetry.EVENT_ONBOARDING_IMPORT_FAILED,
        PhoneUsageTelemetry.EVENT_ONBOARDING_COMPLETED,
        PhoneUsageTelemetry.EVENT_ONBOARDING_DROPPED
    )

    @Test
    fun `event names are distinct static constants`() {
        assertEquals(6, onboardingEvents.size)
        assertEquals(onboardingEvents.size, onboardingEvents.toSet().size)
        onboardingEvents.forEach { assertTrue(it.isNotBlank()) }
    }

    @Test
    fun `event names follow the onboarding prefix convention`() {
        onboardingEvents.forEach { event ->
            assertTrue("$event", event.matches(Regex("^onboarding_[a-z_]+$")))
        }
    }

    @Test
    fun `no event name contains any answer key`() {
        val echoKeys = ONBOARDING_CATALOG.mapNotNull { it.echoKey }
        onboardingEvents.forEach { event ->
            echoKeys.forEach { key ->
                assertTrue("$event contains answer key $key", !event.contains(key))
            }
        }
    }

    @Test
    fun `no event name contains any step option content`() {
        val options = ONBOARDING_CATALOG.flatMap { it.options }
        onboardingEvents.forEach { event ->
            options.forEach { option ->
                assertTrue("$event contains option $option", !event.contains(option))
            }
        }
    }
}
