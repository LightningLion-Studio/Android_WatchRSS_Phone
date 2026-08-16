package com.lightningstudio.watchrss.phone.tips

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TipCodecsTest {

    @Test
    fun `tip states roundtrip preserves everything`() {
        val states = mapOf(
            "sync_manual" to TipState(dismissed = true, lastShownAtMillis = 1755000000000L, showCount = 2),
            "token_usage" to TipState()
        )
        val restored = TipCodecs.tipStatesFromJson(TipCodecs.tipStatesToJson(states).toString())
        assertEquals(states, restored)
    }

    @Test
    fun `event counts roundtrip preserves lifetime and daily buckets`() {
        val lifetime = mapOf("app_launch" to 3L, "sync_completed" to 1L)
        val daily = mapOf("sync_completed" to mapOf("2026-08-16" to 1L))
        val restored = TipCodecs.eventCountsFromJson(
            TipCodecs.eventCountsToJson(lifetime, daily).toString()
        )
        assertEquals(lifetime to daily, restored)
    }

    @Test
    fun `corrupt or version mismatched json yields null`() {
        assertNull(TipCodecs.tipStatesFromJson("{not json"))
        assertNull(TipCodecs.tipStatesFromJson(""))
        assertNull(TipCodecs.tipStatesFromJson("""{"schemaVersion":99}"""))
        assertNull(TipCodecs.eventCountsFromJson("{not json"))
        assertNull(TipCodecs.eventCountsFromJson("""{"schemaVersion":99}"""))
    }

    @Test
    fun `missing blocks decode to empty structures`() {
        assertEquals(emptyMap<String, TipState>(), TipCodecs.tipStatesFromJson("""{"schemaVersion":1}"""))
        assertEquals(
            emptyMap<String, Long>() to emptyMap<String, Map<String, Long>>(),
            TipCodecs.eventCountsFromJson("""{"schemaVersion":1}""")
        )
    }
}
