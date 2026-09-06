package com.lightningstudio.watchrss.phone.support

import org.junit.Assert.*
import org.junit.Test

class SupportLogPrivacyTest {
    @Test fun removesCredentialsWhileKeepingDiagnosticErrors() {
        val text = """sync failed status=500 Authorization: Bearer abc-secret
            {"access_token":"json-secret","password":"pass-secret"}
            request=https://example.com/sync?token=url-secret
            eyJhbGciOiJIUzI1NiJ9.payload.signature"""
        val result = redactSupportLog(text)
        for (secret in listOf("abc-secret", "json-secret", "pass-secret", "url-secret", "payload.signature")) assertFalse(result.contains(secret))
        assertTrue(result.contains("sync failed status=500"))
    }
}
