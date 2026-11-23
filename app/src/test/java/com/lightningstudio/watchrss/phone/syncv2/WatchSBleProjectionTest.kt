package com.lightningstudio.watchrss.phone.syncv2

import com.lightningstudio.watchrss.phone.BuildConfig
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
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
        val head = SyncV2Codec.canonical(fixture.projection.state().body.getJSONObject("appliedAccount"))
        val url = "https://example.com/watchs-feed.xml"
        val value = JSONObject().put("url", url).put("title", "Test feed").put("updatedAt", 20)
        val key = fixture.identity.entity("source", url)
        val source = WatchSBleRows.encode(WatchSBleRows.record("source", url, key, value))
        fixture.projection.watchSBlePut(head, source) {}

        val sourceId = WatchSBleRows.rssSourceKey(url)
        val hash = "12".repeat(32)
        val logical = "$sourceId:$hash"
        val stateKey = fixture.identity.entity("rss-state", logical)
        val state = JSONObject().put("sourceId", sourceId).put("articleIdHash", hash)
            .put("favoriteSaved", true).put("favoriteChangedAt", 30)
            .put("readingProgress", 0.625).put("readingPositionChangedAt", 10)
        val rss = WatchSBleRows.encode(WatchSBleRows.record("rss-state", logical, stateKey, state))
        fixture.projection.watchSBlePut(head, rss) {}
        assertEquals("Test feed", fixture.sql.query("SELECT title FROM phone_rss_sources WHERE url=?", arrayOf(url))
            .use { assertTrue(it.moveToFirst()); it.getString(0) })
        fixture.reopen()
        val ordered = listOf(key, stateKey).sorted()
        val first = fixture.projection.watchSBleNext(head, "")!!
        assertEquals(ordered[0], first.getString("entityKey"))
        val second = fixture.projection.watchSBleNext(head, ordered[0])!!
        assertEquals(ordered[1], second.getString("entityKey"))
        assertNull(fixture.projection.watchSBleNext(head, ordered[1]))
        val revision = fixture.projection.state().revision
        fixture.projection.watchSBlePut(head, rss) {}
        assertEquals(revision, fixture.projection.state().revision)
        assertThrows(IllegalStateException::class.java) {
            fixture.projection.watchSBlePut("{}", source) {}
        }
    }

    @Test fun cloudClosureReusesExactBleEnvelopeAndRejectsDamagedCache() = SyncV2P5Fixture().use { fixture ->
        assumeTrue(BuildConfig.WATCH_S_BLE_SYNC)
        fixture.start()
        val head = SyncV2Codec.canonical(fixture.projection.state().body.getJSONObject("appliedAccount"))
        val before = fixture.f.objects.keys.toSet()
        val next = fixture.commit(listOf(fixture.note("Next")), fixture.initial, "0", "normal", null)
        val newObjects = fixture.f.objects.keys - before
        val meta = newObjects.single { it.startsWith("meta:") }
        val state = newObjects.single { it.startsWith("state:") }
        val bytes = fixture.f.objects.getValue(meta)
        fixture.projection.watchSBleCacheObject(head, meta, bytes) {}
        fixture.projection.watchSBleCacheObject(head, state, fixture.f.objects.getValue(state)) {}
        fixture.sql.execSQL("UPDATE syncv2_watchs_cache SET body=zeroblob(length(body)) WHERE oid=?", arrayOf(state))
        fixture.reopen()
        assertArrayEquals(bytes, fixture.projection.watchSBleCachedObject(meta))
        assertNull(fixture.projection.watchSBleCachedObject(state))
        fixture.setCurrent(next, "3")
        fixture.requests.clear()
        fixture.actions().refresh()
        assertFalse(fixture.requests.any { it == "objects/$meta" })
        assertTrue(fixture.requests.any { it == "objects/$state" })
        assertEquals(next, fixture.projection.state().body.getJSONObject("account").getString("oid"))
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
