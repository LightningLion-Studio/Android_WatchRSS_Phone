package com.lightningstudio.watchrss.phone.connection.bluetooth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveWatchProbeShortCircuitTest {
    @Test
    fun `reachable active watch stops remaining probes`() {
        assertTrue(
            shouldStopAfterActiveWatchProbe(
                candidateAddress = "A8:88:CE:88:E9:6E",
                activeWatchAddresses = setOf("A8:88:CE:88:E9:6E"),
                reachable = true
            )
        )
    }

    @Test
    fun `failed active watch continues to fallback candidates`() {
        assertFalse(
            shouldStopAfterActiveWatchProbe(
                candidateAddress = "A8:88:CE:88:E9:6E",
                activeWatchAddresses = setOf("A8:88:CE:88:E9:6E"),
                reachable = false
            )
        )
    }

    @Test
    fun `reachable inactive watch does not hide other candidates`() {
        assertFalse(
            shouldStopAfterActiveWatchProbe(
                candidateAddress = "68:85:A4:6F:02:9E",
                activeWatchAddresses = setOf("A8:88:CE:88:E9:6E"),
                reachable = true
            )
        )
    }
}
