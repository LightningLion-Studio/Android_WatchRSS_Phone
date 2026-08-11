package com.lightningstudio.watchrss.phone.privacy

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneOobeStageTest {
    @Test
    fun `new user sees welcome before agreement`() {
        assertEquals(
            PhoneOobeStage.WELCOME,
            phoneOobeStage(page = 0, hasConsent = false, hasUsableSession = false)
        )
        assertEquals(
            PhoneOobeStage.AGREEMENT,
            phoneOobeStage(page = 1, hasConsent = false, hasUsableSession = false)
        )
    }

    @Test
    fun `consent is followed by mandatory login`() {
        assertEquals(
            PhoneOobeStage.ACCOUNT,
            phoneOobeStage(page = 1, hasConsent = true, hasUsableSession = false)
        )
    }

    @Test
    fun `only consent plus usable session completes onboarding`() {
        assertEquals(
            PhoneOobeStage.COMPLETE,
            phoneOobeStage(page = 1, hasConsent = true, hasUsableSession = true)
        )
    }

    @Test
    fun `app access is not enforced while oobe is still active`() {
        assertEquals(false, shouldEnforceAppAccess(hasRequiredConsent = false, isOobeComplete = false))
        assertEquals(false, shouldEnforceAppAccess(hasRequiredConsent = true, isOobeComplete = false))
        assertEquals(true, shouldEnforceAppAccess(hasRequiredConsent = true, isOobeComplete = true))
    }
}
