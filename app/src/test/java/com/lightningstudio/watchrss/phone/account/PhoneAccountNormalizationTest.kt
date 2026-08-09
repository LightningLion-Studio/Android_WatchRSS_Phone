package com.lightningstudio.watchrss.phone.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PhoneAccountNormalizationTest {
    @Test
    fun `mainland phone inputs always become plus 86 e164`() {
        listOf("13800138000", "8613800138000", "+86 138-0013-8000").forEach { input ->
            assertEquals("+8613800138000", normalizeAccountPhone(input))
        }
    }

    @Test
    fun `international phone is rejected until supported`() {
        assertThrows(IllegalArgumentException::class.java) {
            normalizeAccountPhone("+12025550100")
        }
    }
}
