package com.lightningstudio.watchrss.phone.push

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OppoPushConsentGateTest {
    @Test
    fun `third party action only runs while required consent is granted`() {
        var hasRequiredConsent = false
        var actionRan = false
        val gate = OppoPushConsentGate { hasRequiredConsent }

        assertFalse(gate.runIfGranted { actionRan = true })
        assertFalse(actionRan)

        hasRequiredConsent = true
        assertTrue(gate.runIfGranted { actionRan = true })
        assertTrue(actionRan)

        actionRan = false
        hasRequiredConsent = false
        assertFalse(gate.runIfGranted { actionRan = true })
        assertFalse(actionRan)
    }
}
