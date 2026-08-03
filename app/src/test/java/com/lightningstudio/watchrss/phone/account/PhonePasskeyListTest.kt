package com.lightningstudio.watchrss.phone.account

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhonePasskeyListTest {
    @Test
    fun parsesRegisteredPasskeyMetadataWithoutCredentialPayload() {
        val passkeys = parseRegisteredPasskeys(
            JSONObject(
                """
                {
                  "passkeys": [
                    {
                      "credentialId": "credential-id",
                      "displayName": "Android Passkey",
                      "createdAt": 1754000000000,
                      "lastUsedAt": null
                    }
                  ]
                }
                """.trimIndent()
            )
        )

        assertEquals(1, passkeys.size)
        assertEquals("credential-id", passkeys.single().credentialId)
        assertEquals("Android Passkey", passkeys.single().displayName)
        assertEquals(1_754_000_000_000, passkeys.single().createdAtMillis)
        assertNull(passkeys.single().lastUsedAtMillis)
    }
}
