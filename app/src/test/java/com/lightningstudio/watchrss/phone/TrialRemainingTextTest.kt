package com.lightningstudio.watchrss.phone

import org.junit.Assert.assertEquals
import org.junit.Test

class TrialRemainingTextTest {
    @Test
    fun `expired or missing deadline is closed`() {
        assertEquals("试用已到期", trialRemainingText(null, 1_000L))
        assertEquals("试用已到期", trialRemainingText(1_000L, 1_000L))
    }

    @Test
    fun `remaining time rounds up without extending a day`() {
        val now = 1_000L
        assertEquals("试用剩余 3 天 0 小时", trialRemainingText(now + 72L * 60L * 60L * 1_000L, now))
        assertEquals("试用剩余 1 分钟", trialRemainingText(now + 1L, now))
    }
}
