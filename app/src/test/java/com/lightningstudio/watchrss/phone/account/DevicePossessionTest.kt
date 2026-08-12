package com.lightningstudio.watchrss.phone.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DevicePossessionTest {
    @Test
    fun canonicalMessageBindsPurposeChallengeDeviceAndRequest() {
        val message = devicePossessionMessage(
            purpose = "refresh",
            challengeId = "00000000-0000-0000-0000-000000000001",
            nonce = "wrc_nonce",
            licenseDeviceId = "a".repeat(64),
            requestHash = "request-hash"
        )

        assertEquals(
            "watchrss-device-possession-v1\nrefresh\n" +
                "00000000-0000-0000-0000-000000000001\nwrc_nonce\n" +
                "${"a".repeat(64)}\nrequest-hash",
            message
        )
    }

    @Test
    fun requestHashUsesLengthDelimitedUtf8Fields() {
        val combined = devicePossessionRequestHash("ab", "c")
        val ambiguousAlternative = devicePossessionRequestHash("a", "bc")

        assertEquals(64, combined.length)
        assertTrue(combined != ambiguousAlternative)
        assertEquals(combined, devicePossessionRequestHash("ab", "c"))
    }

    @Test
    fun trustedClockRejectsRollbackAndUsesMonotonicProgress() {
        val anchor = 1_000_000L
        val anchorElapsed = 10_000L

        assertEquals(
            1_060_000L,
            trustedTimeDecision(anchor, anchorElapsed, 1_050_000L, 70_000L)
        )
        assertNull(
            trustedTimeDecision(
                anchorMillis = anchor,
                anchorElapsedMillis = anchorElapsed,
                wallMillis = 600_000L,
                elapsedMillis = 70_000L,
                rollbackToleranceMillis = 300_000L
            )
        )
        assertEquals(
            anchor,
            trustedTimeDecision(anchor, anchorElapsed, anchor, elapsedMillis = 5_000L)
        )
    }
}
