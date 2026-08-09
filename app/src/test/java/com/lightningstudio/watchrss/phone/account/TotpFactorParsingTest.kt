package com.lightningstudio.watchrss.phone.account

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TotpFactorParsingTest {
    @Test
    fun `only totp factors are exposed`() {
        val factors = parseTotpFactors(
            JSONObject(
                """{"factors":[
                    {"id":"verified","factor_type":"totp","status":"verified","friendly_name":"腕上RSS"},
                    {"id":"pending","factor_type":"totp","status":"unverified"},
                    {"id":"phone","factor_type":"phone","status":"verified"}
                ]}"""
            )
        )
        assertEquals(2, factors.size)
        assertTrue(factors[0].verified)
        assertFalse(factors[1].verified)
    }

    @Test
    fun `normalizes mainland phone numbers for password login`() {
        assertEquals("+8613800138000", normalizeAccountPhone("138 0013 8000"))
        assertEquals("+8613800138000", normalizeAccountPhone("8613800138000"))
        assertThrows(IllegalArgumentException::class.java) {
            normalizeAccountPhone("+886912345678")
        }
    }
}
