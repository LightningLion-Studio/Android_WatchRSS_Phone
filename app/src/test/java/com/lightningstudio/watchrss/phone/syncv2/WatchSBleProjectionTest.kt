package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class WatchSBleProjectionTest {
    @Test fun committedRowsResumeAfterReopenAndReplayDoesNotWrite(): Unit = SyncV2P5Fixture().use { fixture ->
        fixture.start()
        val head = fixture.projection.watchSBleHead()
        val url = "https://example.com/watchs-feed.xml"
        val value = JSONObject().put("url", url).put("title", "Test feed").put("updatedAt", 20)
        val key = fixture.identity.entity("source", url)
        val source = WatchSBleRows.encode(WatchSBleRows.record("source", url, key, value))
        assertTrue(fixture.projection.watchSBlePut(head, source) {})

        val sourceId = WatchSBleRows.rssSourceKey(url)
        val hash = "12".repeat(32)
        val logical = "$sourceId:$hash"
        val stateKey = fixture.identity.entity("rss-state", logical)
        val state = JSONObject().put("sourceId", sourceId).put("articleIdHash", hash)
            .put("favoriteSaved", true).put("favoriteChangedAt", 30)
            .put("readingProgress", 0.625).put("readingPositionChangedAt", 10)
        val rss = WatchSBleRows.encode(WatchSBleRows.record("rss-state", logical, stateKey, state))
        assertTrue(fixture.projection.watchSBlePut(head, rss) {})
        assertEquals("Test feed", fixture.sql.query("SELECT title FROM phone_rss_sources WHERE url=?", arrayOf(url))
            .use { assertTrue(it.moveToFirst()); it.getString(0) })
        fixture.reopen()
        val first = fixture.projection.watchSBleNext(head, "")!!
        assertEquals("rss-state:$logical", first.cursor)
        assertEquals(rss.getString("entityKey"), first.row.getString("entityKey"))
        assertEquals(625000, first.row.getJSONObject("value").getInt("readingProgressMicros"))
        val second = fixture.projection.watchSBleNext(head, first.cursor)!!
        assertEquals("source:$url", second.cursor)
        assertEquals(source.getString("entityKey"), second.row.getString("entityKey"))
        assertNotEquals(key, second.row.getString("entityKey"))
        assertNull(fixture.projection.watchSBleNext(head, second.cursor))
        val revision = fixture.projection.state().revision
        assertFalse(fixture.projection.watchSBlePut(head, rss) {})
        assertEquals(revision, fixture.projection.state().revision)
        assertThrows(IllegalStateException::class.java) {
            fixture.projection.watchSBlePut("{}", source) {}
        }
    }

    @Test fun rejectsCloudEntityKeyAndRollsBackIfPairIsInvalidatedDuringWrite(): Unit = SyncV2P5Fixture().use { fixture ->
        fixture.start()
        val head = fixture.projection.watchSBleHead()
        val revision = fixture.projection.state().revision
        val url = "https://example.com/watchs-test.xml"
        val value = JSONObject().put("url", url).put("title", "Test feed").put("updatedAt", 20)
        val cloudKey = fixture.identity.entity("source", url)
        val wire = WatchSBleRows.encode(WatchSBleRows.record("source", url, cloudKey, value))
        val forged = JSONObject(wire.toString()).put("entityKey", cloudKey)
        assertEquals("identity_conflict", assertThrows(IllegalStateException::class.java) {
            fixture.projection.watchSBlePut(head, forged) {}
        }.message)
        var checks = 0
        assertThrows(IllegalStateException::class.java) {
            fixture.projection.watchSBlePut(head, wire) { check(++checks == 1) }
        }
        assertEquals(2, checks)
        assertEquals(revision, fixture.projection.state().revision)
        fixture.sql.query("SELECT 1 FROM phone_rss_sources WHERE url=?", arrayOf(url)).use {
            assertFalse(it.moveToFirst())
        }
    }

    @Test fun articleRegistersMergeIndependently() {
        val local = JSONObject().put("articleId", "a").put("url", "https://example.com")
            .put("sourceDeviceId", "phone").put("updatedAt", 20).put("title", "Phone title")
            .put("favoriteChangedAt", 30).put("favoriteSaved", true).put("readingPositionChangedAt", 10)
        val watch = JSONObject().put("articleId", "a").put("url", "https://example.com")
            .put("sourceDeviceId", "watch").put("updatedAt", 10).put("title", "Watch title")
            .put("readingPositionChangedAt", 40).put("readingProgress", 0.5)
        val merged = WatchSBleRows.merge("article", local, watch)
        assertEquals("Phone title", merged.getString("title"))
        assertTrue(merged.getBoolean("favoriteSaved"))
        assertEquals(0.5, merged.getDouble("readingProgress"), 0.0)
    }
}
