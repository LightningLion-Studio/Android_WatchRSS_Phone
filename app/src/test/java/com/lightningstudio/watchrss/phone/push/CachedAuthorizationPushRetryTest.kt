package com.lightningstudio.watchrss.phone.push

import com.lightningstudio.watchrss.phone.account.AuthorizationReadyNotifier
import org.junit.Assert.assertEquals
import org.junit.Test

class CachedAuthorizationPushRetryTest {
    @Test
    fun `authorized reconcile retries deferred upload when session recovers without state transition`() {
        var hasUsableSession = false
        var uploadedRegId: String? = null
        var uploadAttempts = 0
        val retry = PendingPushUploadRetry(
            hasRequiredConsent = { true },
            upload = {
                uploadAttempts += 1
                hasUsableSession
            },
            markUploaded = { uploadedRegId = it }
        )
        val notifier = AuthorizationReadyNotifier {
            retry.retry("reg-cached", uploadedRegId, enabled = true)
        }

        // Cold start restores a valid cached lease, but the account session is not ready.
        notifier.afterReconcile(isAuthorized = true)
        assertEquals(1, uploadAttempts)
        assertEquals(null, uploadedRegId)

        // Session recovery keeps app access Authorized, so there is no state transition.
        hasUsableSession = true
        notifier.afterReconcile(isAuthorized = true)
        assertEquals(2, uploadAttempts)
        assertEquals("reg-cached", uploadedRegId)

        // Further reconciliations are harmless after the regId has been persisted.
        notifier.afterReconcile(isAuthorized = true)
        assertEquals(2, uploadAttempts)
    }
}
