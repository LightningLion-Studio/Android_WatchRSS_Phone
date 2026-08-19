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

    @Test
    fun `actual sync retries one fast socket io failure after listener rearm delay`() {
        val recovery = PhoneBluetoothSyncClient.sdpSyncConnectFailureRecovery(
            elapsedMs = 300L,
            completedFastFailureRetries = 0,
            retryableIoFailure = true
        )

        assertEquals(1_500L, recovery.delayMs)
        assertTrue(recovery.retrySameCandidate)
    }

    @Test
    fun `actual sync does not retry non io failures`() {
        val recovery = PhoneBluetoothSyncClient.sdpSyncConnectFailureRecovery(
            elapsedMs = 100L,
            completedFastFailureRetries = 0,
            retryableIoFailure = false
        )

        assertEquals(0L, recovery.delayMs)
        assertFalse(recovery.retrySameCandidate)
    }

    @Test
    fun `actual sync does not retry the same fast failure forever`() {
        val recovery = PhoneBluetoothSyncClient.sdpSyncConnectFailureRecovery(
            elapsedMs = 200L,
            completedFastFailureRetries = 1,
            retryableIoFailure = true
        )

        assertEquals(0L, recovery.delayMs)
        assertFalse(recovery.retrySameCandidate)
    }
}
