package com.lightningstudio.watchrss.phone.account

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PasskeyCandidateVisibilityTest {
    @Test
    fun localCandidateWithoutRemoteFallback_isOffered() {
        assertTrue(
            shouldOfferPasskeyLogin(
                hasLocalCandidate = true,
                hasRemoteCandidate = false
            )
        )
    }

    @Test
    fun missingLocalCandidate_isHidden() {
        assertFalse(
            shouldOfferPasskeyLogin(
                hasLocalCandidate = false,
                hasRemoteCandidate = false
            )
        )
    }

    @Test
    fun remoteOnlyCredentialManagerFallback_isHidden() {
        assertFalse(
            shouldOfferPasskeyLogin(
                hasLocalCandidate = true,
                hasRemoteCandidate = true
            )
        )
    }
}
