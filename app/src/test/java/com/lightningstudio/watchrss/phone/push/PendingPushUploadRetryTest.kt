package com.lightningstudio.watchrss.phone.push

import org.junit.Assert.assertEquals
import org.junit.Test

class PendingPushUploadRetryTest {
    @Test
    fun `authorization event retries an earlier deferred upload in the same process`() {
        var authorized = false
        val attempts = mutableListOf<String>()
        val uploaded = mutableListOf<String>()
        val retry = PendingPushUploadRetry(
            hasRequiredConsent = { true },
            upload = { regId ->
                attempts += regId
                authorized
            },
            markUploaded = { uploaded += it }
        )

        assertEquals(
            PendingPushUploadResult.DEFERRED,
            retry.retry("reg-1", uploaded.lastOrNull(), enabled = true)
        )

        authorized = true
        assertEquals(
            PendingPushUploadResult.UPLOADED,
            retry.retry("reg-1", uploaded.lastOrNull(), enabled = true)
        )
        assertEquals(listOf("reg-1", "reg-1"), attempts)
        assertEquals(listOf("reg-1"), uploaded)

        assertEquals(
            PendingPushUploadResult.SKIPPED,
            retry.retry("reg-1", uploaded.lastOrNull(), enabled = true)
        )
        assertEquals(2, attempts.size)
    }

    @Test
    fun `retry remains blocked without privacy consent or while push is disabled`() {
        var hasConsent = false
        var attempts = 0
        val retry = PendingPushUploadRetry(
            hasRequiredConsent = { hasConsent },
            upload = {
                attempts += 1
                true
            },
            markUploaded = {}
        )

        assertEquals(
            PendingPushUploadResult.SKIPPED,
            retry.retry("reg-1", null, enabled = true)
        )
        hasConsent = true
        assertEquals(
            PendingPushUploadResult.SKIPPED,
            retry.retry("reg-1", null, enabled = false)
        )
        assertEquals(0, attempts)
    }
}
