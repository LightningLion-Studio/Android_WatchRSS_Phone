package com.lightningstudio.watchrss.phone.connection.bluetooth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveWatchProbeShortCircuitTest {
    @Test
    fun `reachable active watch stops remaining probes`() {
        assertTrue(
            shouldStopAfterPrioritizedWatchProbe(
                candidateAddress = "A8:88:CE:88:E9:6E",
                activeWatchAddresses = setOf("A8:88:CE:88:E9:6E"),
                cachedWatchAddress = null,
                reachable = true
            )
        )
    }

    @Test
    fun `failed active watch continues to fallback candidates`() {
        assertFalse(
            shouldStopAfterPrioritizedWatchProbe(
                candidateAddress = "A8:88:CE:88:E9:6E",
                activeWatchAddresses = setOf("A8:88:CE:88:E9:6E"),
                cachedWatchAddress = null,
                reachable = false
            )
        )
    }

    @Test
    fun `reachable cached watch stops remaining probes`() {
        assertTrue(
            shouldStopAfterPrioritizedWatchProbe(
                candidateAddress = "68:85:A4:6F:02:9E",
                activeWatchAddresses = emptySet(),
                cachedWatchAddress = "68:85:A4:6F:02:9E",
                reachable = true
            )
        )
    }

    @Test
    fun `reachable unprioritized watch does not hide other candidates`() {
        assertFalse(
            shouldStopAfterPrioritizedWatchProbe(
                candidateAddress = "68:85:A4:6F:02:9E",
                activeWatchAddresses = setOf("A8:88:CE:88:E9:6E"),
                cachedWatchAddress = "50:45:DA:8E:0F:0A",
                reachable = true
            )
        )
    }
}
