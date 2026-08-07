package com.lightningstudio.watchrss.phone.update

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneAnnouncementRepositoryTest {
    @Test fun parsesAndComparesAnnouncements() {
        val announcement = PhoneAnnouncementRepository.parse(JSONObject("""{
            "version":"1.2.0-6","changelog_md":"Changes","force_update":true,
            "download_url":"https://example.invalid/phone.apk"
        }"""))!!
        assertEquals("1.2.0-6", announcement.version)
        assertTrue(announcement.forceUpdate)
        assertTrue(PhoneAnnouncementRepository.compareVersions("1.2.0-6", "1.2.0-5") > 0)
    }
}
