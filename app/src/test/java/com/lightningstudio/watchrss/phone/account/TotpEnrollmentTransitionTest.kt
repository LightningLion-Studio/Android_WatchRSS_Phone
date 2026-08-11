package com.lightningstudio.watchrss.phone.account

import com.lightningstudio.watchrss.phone.missingTwoFactorMethods
import com.lightningstudio.watchrss.phone.shouldAutoEnableTwoFactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TotpEnrollmentTransitionTest {
    @Test
    fun `pending enable continues after a second method is available`() {
        val status = AccountSecurityStatus(
            twoFactorEnabled = false,
            availableMethods = setOf("sms", "totp")
        )
        assertTrue(shouldAutoEnableTwoFactor(true, status))
        assertFalse(shouldAutoEnableTwoFactor(false, status))
    }

    @Test
    fun `one method is insufficient and missing choices exclude sms`() {
        val status = AccountSecurityStatus(false, setOf("sms"))
        assertFalse(shouldAutoEnableTwoFactor(true, status))
        assertEquals(
            listOf("password", "totp", "passkey"),
            missingTwoFactorMethods(status.availableMethods)
        )
    }
}
