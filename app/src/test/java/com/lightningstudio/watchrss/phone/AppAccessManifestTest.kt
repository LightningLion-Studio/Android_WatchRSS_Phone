package com.lightningstudio.watchrss.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppAccessManifestTest {
    @Test
    fun `only gate activity is exported`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val exported = Regex("""<activity\s+android:name="([^"]+)"[\s\S]*?android:exported="true"""")
            .findAll(manifest).map { it.groupValues[1] }.toList()
        assertEquals(listOf(".MainActivity"), exported)
        assertTrue(manifest.contains("android:name=\".HomeActivity\"\n            android:exported=\"false\""))
        assertTrue(manifest.contains("android:scheme=\"watchrss\" android:host=\"payment-return\""))
    }

    @Test
    fun `authorization secrets are excluded from backup`() {
        val legacy = File("src/main/res/xml/backup_rules.xml").readText()
        val modern = File("src/main/res/xml/data_extraction_rules.xml").readText()
        listOf(
            "watchrss_account_session.xml",
            "watchrss_app_access.xml",
            "watchrss_phone_privacy_consent.xml"
        ).forEach { name ->
            assertTrue(legacy.contains(name))
            assertTrue(modern.contains(name))
        }
    }

    @Test
    fun `oobe and legal pages are internal activities`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        listOf(".PhoneOobeActivity", ".LegalDocumentActivity").forEach { activity ->
            assertTrue(
                manifest.contains(
                    "android:name=\"$activity\"\n            android:exported=\"false\""
                )
            )
        }
    }
}
