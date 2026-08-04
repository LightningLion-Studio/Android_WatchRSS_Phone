package com.lightningstudio.watchrss.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PasskeyPhoneProbeTest {
    @Test
    fun acceptsMainlandPhoneAfterAllDigitsAreEntered() {
        assertEquals("13800138000", phoneForPasskeyProbe("138 0013 8000"))
        assertEquals("+8613800138000", phoneForPasskeyProbe("+86 138-0013-8000"))
    }

    @Test
    fun keepsProbeHiddenForIncompleteOrInvalidPhone() {
        assertNull(phoneForPasskeyProbe("1380013"))
        assertNull(phoneForPasskeyProbe("23800138000"))
        assertNull(phoneForPasskeyProbe(""))
    }
}
