package com.lightningstudio.watchrss.phone.tips

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TipRulesTest {

    private val parameters = TipParameterValues.Builder()
        .put("flag", true)
        .put("mode", "reader")
        .build()

    @Test
    fun `param boolean matches expected value`() {
        assertTrue(TipRules.param("flag").evaluate(parameters, emptyMap()))
        assertFalse(TipRules.param("flag", expected = false).evaluate(parameters, emptyMap()))
    }

    @Test
    fun `missing parameter key never satisfies param rules`() {
        assertFalse(TipRules.param("absent").evaluate(parameters, emptyMap()))
        // 键缺失时即使是 expected=false 也不满足
        assertFalse(TipRules.param("absent", expected = false).evaluate(parameters, emptyMap()))
        assertFalse(TipRules.paramEquals("absent", "reader").evaluate(parameters, emptyMap()))
    }

    @Test
    fun `param equals matches string value`() {
        assertTrue(TipRules.paramEquals("mode", "reader").evaluate(parameters, emptyMap()))
        assertFalse(TipRules.paramEquals("mode", "other").evaluate(parameters, emptyMap()))
    }

    @Test
    fun `event count respects min and max bounds`() {
        val events = mapOf("opened" to 3L)
        assertTrue(TipRules.eventAtLeast("opened", 2).evaluate(emptyParameters, events))
        assertFalse(TipRules.eventAtLeast("opened", 4).evaluate(emptyParameters, events))
        assertTrue(TipRules.eventNever("never").evaluate(emptyParameters, events))
        assertFalse(TipRules.eventNever("opened").evaluate(emptyParameters, events))
        assertTrue(
            TipRule.EventCount("opened", minCount = 3L, maxCount = 3L).evaluate(emptyParameters, events)
        )
    }

    @Test
    fun `missing event counts as zero`() {
        assertTrue(TipRules.eventNever("absent").evaluate(emptyParameters, emptyMap()))
        assertFalse(TipRules.eventAtLeast("absent", 1).evaluate(emptyParameters, emptyMap()))
    }

    @Test
    fun `allOf requires every rule and anyOf requires at least one`() {
        assertTrue(
            TipRules.allOf(TipRules.param("flag"), TipRules.eventNever("never"))
                .evaluate(parameters, emptyMap())
        )
        assertFalse(
            TipRules.allOf(TipRules.param("flag"), TipRules.eventAtLeast("never", 1))
                .evaluate(parameters, emptyMap())
        )
        assertTrue(
            TipRules.anyOf(TipRules.param("absent"), TipRules.param("flag"))
                .evaluate(parameters, emptyMap())
        )
        assertFalse(
            TipRules.anyOf(TipRules.param("absent"), TipRules.eventAtLeast("never", 1))
                .evaluate(parameters, emptyMap())
        )
    }

    private companion object {
        val emptyParameters = TipParameterValues.EMPTY
    }
}
