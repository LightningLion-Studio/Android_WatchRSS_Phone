package com.lightningstudio.watchrss.phone.connection.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SdpProbeFailureRecoveryTest {
    @Test
    fun `fast failure waits for stack drain and retries same candidate once`() {
        val recovery = PhoneBluetoothSyncClient.sdpProbeFailureRecovery(
            elapsedMs = 120L,
            timedOut = false,
            completedFastFailureRetries = 0
        )

        assertEquals(1_500L, recovery.delayMs)
        assertTrue(recovery.retrySameCandidate)
    }

    @Test
    fun `second fast failure drains stack without retrying forever`() {
        val recovery = PhoneBluetoothSyncClient.sdpProbeFailureRecovery(
            elapsedMs = 80L,
            timedOut = false,
            completedFastFailureRetries = 1
        )

        assertEquals(1_500L, recovery.delayMs)
        assertFalse(recovery.retrySameCandidate)
    }

    @Test
    fun `local timeout uses the longer stack drain delay`() {
        val recovery = PhoneBluetoothSyncClient.sdpProbeFailureRecovery(
            elapsedMs = 7_000L,
            timedOut = true,
            completedFastFailureRetries = 0
        )

        assertEquals(1_500L, recovery.delayMs)
        assertFalse(recovery.retrySameCandidate)
    }

    @Test
    fun `completed page timeout only needs normal cooldown`() {
        val recovery = PhoneBluetoothSyncClient.sdpProbeFailureRecovery(
            elapsedMs = 5_220L,
            timedOut = false,
            completedFastFailureRetries = 0
        )

        assertEquals(250L, recovery.delayMs)
        assertFalse(recovery.retrySameCandidate)
    }
}
