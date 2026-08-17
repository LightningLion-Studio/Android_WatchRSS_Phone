package com.lightningstudio.watchrss.phone

import com.lightningstudio.watchrss.phone.onboarding.OnboardingDraftStore
import com.lightningstudio.watchrss.phone.onboarding.OnboardingProfileStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

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
    fun `onboarding answers are excluded from every Android backup path`() {
        val onboardingPreferences = setOf(
            "${OnboardingProfileStore.PREFERENCES_NAME}.xml",
            "${OnboardingDraftStore.PREFERENCES_NAME}.xml"
        )

        assertTrue(
            excludedSharedPreferences(File("src/main/res/xml/backup_rules.xml"))
                .containsAll(onboardingPreferences)
        )

        val modernRules = File("src/main/res/xml/data_extraction_rules.xml")
        listOf("cloud-backup", "device-transfer").forEach { section ->
            assertTrue(
                "$section must exclude the onboarding profile and draft",
                excludedSharedPreferences(modernRules, section).containsAll(onboardingPreferences)
            )
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

    private fun excludedSharedPreferences(file: File, section: String? = null): Set<String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val root = section?.let { name ->
            document.getElementsByTagName(name).item(0)
                ?: error("Missing <$name> in ${file.path}")
        } ?: document.documentElement

        return (0 until root.childNodes.length).asSequence()
            .map { index -> root.childNodes.item(index) }
            .filter { it.nodeName == "exclude" }
            .filter { it.attributes.getNamedItem("domain")?.nodeValue == "sharedpref" }
            .mapNotNull { it.attributes.getNamedItem("path")?.nodeValue }
            .toSet()
    }
}
