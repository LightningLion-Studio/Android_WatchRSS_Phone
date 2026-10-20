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
    fun `reserved test range accepts local and country prefixed inputs`() {
        listOf("20000000000", "8620000000000", "+86 200-0000-0000").forEach { input ->
            assertEquals("+8620000000000", normalizeAccountPhone(input))
        }
        listOf("20100000000", "2000000000", "200000000000", "2000000000x").forEach { input ->
            assertThrows(IllegalArgumentException::class.java) { normalizeAccountPhone(input) }
        }
    }

    @Test
    fun `international phone is rejected until supported`() {
        assertThrows(IllegalArgumentException::class.java) {
            normalizeAccountPhone("+12025550100")
        }
    }
}
