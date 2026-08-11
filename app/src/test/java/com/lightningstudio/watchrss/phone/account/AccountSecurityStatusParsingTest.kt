package com.lightningstudio.watchrss.phone.account

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountSecurityStatusParsingTest {
    @Test
    fun `parses distinct available methods and explicit policy`() {
        val status = parseAccountSecurityStatus(
            JSONObject(
                """
                {
                  "twoFactorEnabled": true,
                  "availableMethods": ["sms", "totp", "totp", "passkey"],
                  "availableMethodCount": 3
                }
                """.trimIndent()
            )
        )

        assertTrue(status.twoFactorEnabled)
        assertEquals(setOf("sms", "totp", "passkey"), status.availableMethods)
        assertEquals(3, status.availableMethodCount)
    }
}
