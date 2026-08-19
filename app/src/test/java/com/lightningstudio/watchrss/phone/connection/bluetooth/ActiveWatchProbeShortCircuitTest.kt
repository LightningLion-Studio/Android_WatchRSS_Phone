package com.lightningstudio.watchrss.phone.connection.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun `active cached and sole candidates retain probe sessions`() {
        assertTrue(
            shouldRetainProbeSession(
                candidateCount = 3,
                candidateAddress = "active",
                activeWatchAddresses = setOf("ACTIVE"),
                cachedWatchAddress = null
            )
        )
        assertTrue(
            shouldRetainProbeSession(
                candidateCount = 3,
                candidateAddress = "cached",
                activeWatchAddresses = emptySet(),
                cachedWatchAddress = "CACHED"
            )
        )
        assertTrue(
            shouldRetainProbeSession(
                candidateCount = 1,
                candidateAddress = "only",
                activeWatchAddresses = emptySet(),
                cachedWatchAddress = null
            )
        )
    }

    @Test
    fun `manual multi watch selection retains no probe session`() {
        assertFalse(
            shouldRetainProbeSession(
                candidateCount = 2,
                candidateAddress = "first",
                activeWatchAddresses = emptySet(),
                cachedWatchAddress = null
            )
        )
        assertTrue(shouldReleaseProbeSession(reachableCount = 2))
        assertFalse(shouldReleaseProbeSession(reachableCount = 1))
    }

    @Test
    fun `session recovery can be claimed only once`() {
        val gate = SingleSessionRecoveryGate()

        assertTrue(gate.tryAcquire())
        assertFalse(gate.tryAcquire())
    }

    @Test
    fun `late IP upgrade uses remote device id only after watch accepted upgrade`() {
        assertEquals(
            "watch-device-id",
            pendingLateIpUpgradeDeviceId(
                ipUpgradeExpected = true,
                remoteDeviceId = " watch-device-id ",
                transportOwner = "rfcomm"
            )
        )
        assertNull(
            pendingLateIpUpgradeDeviceId(
                ipUpgradeExpected = false,
                remoteDeviceId = "watch-device-id",
                transportOwner = "rfcomm"
            )
        )
        assertNull(
            pendingLateIpUpgradeDeviceId(
                ipUpgradeExpected = true,
                remoteDeviceId = "watch-device-id",
                transportOwner = "ip:wifiLan"
            )
        )
    }
}
