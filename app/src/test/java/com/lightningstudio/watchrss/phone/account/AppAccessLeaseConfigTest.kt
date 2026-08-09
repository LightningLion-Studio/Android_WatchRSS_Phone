package com.lightningstudio.watchrss.phone.account

import com.lightningstudio.watchrss.phone.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

class AppAccessLeaseConfigTest {
    @Test
    fun `production lease public key is bundled and parseable`() {
        assertPublicKey(
            pem = BuildConfig.WATCHRSS_APP_ACCESS_PUBLIC_KEY,
            expectedFingerprint = "668f2241499a050db4e992b6fb6c0c77b1b3ce04cdaff723ada78ef540019ac7"
        )
    }

    @Test
    fun `testing lease public key is bundled and parseable`() {
        assertPublicKey(
            pem = BuildConfig.WATCHRSS_TEST_APP_ACCESS_PUBLIC_KEY,
            expectedFingerprint = "49ec974028828965e0943210bf95d0d4526920800d812d5e628efb5a5a1d5a61"
        )
    }

    private fun assertPublicKey(pem: String, expectedFingerprint: String) {
        assertTrue(pem.isNotBlank())

        val der = Base64.getMimeDecoder().decode(
            pem.removePrefix("-----BEGIN PUBLIC KEY-----")
                .removeSuffix("-----END PUBLIC KEY-----")
        )
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))

        assertTrue(key is ECPublicKey)
        assertEquals(
            expectedFingerprint,
            MessageDigest.getInstance("SHA-256")
                .digest(der)
                .joinToString("") { "%02x".format(it) }
        )
    }
}
