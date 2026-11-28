package com.lightningstudio.watchrss.phone.syncv2

import com.lightningstudio.watchrss.phone.data.book.BookSync
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SyncV2BookProvenanceTest {
    @Test fun refreshAndReopenPreserveAll256Chapters() = refreshAndReopen(256)

    @Test fun refreshAndReopenPreserveAll257Chapters() = refreshAndReopen(257)

    @Test fun refreshAndReopenPreserveMappingLargerThanOneObject() = refreshAndReopen(2_048)

    @Test fun projectionPreserves100000Chapters() = SyncV2P5Fixture().use { fixture ->
        val id = insertBook(fixture, 100_000)
        assertTrue(fixture.projection.scanPage("imported_books").complete)
        assertChapters(fixture, id, 100_000)
        Unit
    }

    @Test fun pageSplitsOnByteLimitBeforeRowLimit() = SyncV2P5Fixture().use { fixture ->
        val id = insertBook(fixture, 256)
        val suffix = "章".repeat(320)
        fixture.sql.execSQL("UPDATE imported_book_chapters SET articleId=articleId || ? WHERE bookId=?", arrayOf(suffix, id))
        fixture.projection.scanPage("imported_books")
        assertChapters(fixture, id, 256, suffix)
        assertEquals(2, assertPageLimits(fixture))
    }

    @Test fun legacyMappingKeepsSourceHashAndUpgradesOnChapterEdit() = SyncV2P5Fixture().use { fixture ->
        val id = insertBook(fixture, 256)
        val projection = fixture.projection
        val entity = fixture.identity.entity("book", id)
        projection.scanPage("imported_books")
        val provenance = assertChapters(fixture, id, 256)
        val (meta, state) = checkNotNull(projection.candidate("book", id))
        val legacyHash = SyncV2Codec.hash(JSONObject().put("meta", meta).put("state", state).put("provenance", provenance))
        fixture.sql.query("SELECT source_hash FROM syncv2_candidates WHERE entity_key=?", arrayOf(entity)).use {
            assertTrue(it.moveToFirst())
            assertEquals(legacyHash, it.getString(0))
        }
        val legacyBody = SyncV2Codec.sealLocal(SyncV2Fixtures.localKey, projection.scope, "provenance", provenance)
        fixture.sql.execSQL("UPDATE syncv2_provenance SET body=? WHERE entity_key=?", arrayOf(legacyBody, entity))
        fixture.sql.execSQL("DELETE FROM syncv2_book_provenance WHERE entity_key=?", arrayOf(entity))
        fixture.reopen()
        assertChapters(fixture, id, 256)

        fixture.sql.execSQL("INSERT INTO imported_book_chapters VALUES(?,257,?)", arrayOf(id, BookSync.articleId(id, 257)))
        fixture.projection.scanPage("imported_books")
        assertChapters(fixture, id, 257)
        assertEquals(2, assertPageLimits(fixture))
    }

    @Test fun interruptedReplacementKeepsPreviousPagesAndRetryRemovesStalePages() {
        var interrupt = false
        SyncV2P5Fixture(projectionFault = { if (interrupt && it == "before-log") error("interrupted") }).use { fixture ->
            val id = insertBook(fixture, 257)
            fixture.projection.scanPage("imported_books")
            fixture.sql.execSQL("DELETE FROM imported_book_chapters WHERE bookId=? AND chapterIndex=257", arrayOf(id))
            interrupt = true
            assertEquals("interrupted", runCatching { fixture.projection.scanPage("imported_books") }.exceptionOrNull()?.message)
            assertChapters(fixture, id, 257)
            assertEquals(2, assertPageLimits(fixture))

            interrupt = false
            fixture.reopen()
            fixture.projection.scanPage("imported_books")
            assertChapters(fixture, id, 256)
            assertEquals(1, assertPageLimits(fixture))

            fixture.sql.execSQL("DELETE FROM imported_books WHERE bookId=?", arrayOf(id))
            fixture.projection.scanPage("imported_books")
            assertNull(fixture.projection.sourceProvenance("book", id))
            assertEquals(0, assertPageLimits(fixture))
        }
    }

    @Test fun resetSnapshotRetainsItsPagesAfterTheCurrentMappingChanges() = SyncV2P5Fixture(reset = true).use { fixture ->
        val id = insertBook(fixture, 2_048, deleted = true)
        val projection = fixture.projection
        projection.scanPage("imported_books")
        val original = assertChapters(fixture, id, 2_048)
        val record = fixture.sql.query("SELECT body FROM syncv2_reset_pending WHERE logical_id=?", arrayOf(id)).use {
            assertTrue(it.moveToFirst())
            assertTrue(it.getBlob(0).size < 262_144)
            projection.accountOpen("reset-pending", it.getBlob(0)).getJSONObject("provenance")
        }
        fixture.sql.execSQL("DELETE FROM imported_book_chapters WHERE bookId=? AND chapterIndex>256", arrayOf(id))
        projection.scanPage("imported_books")
        assertChapters(fixture, id, 256)
        val store = SyncV2BookProvenance(fixture.sql, projection.scope,
            { _, _ -> error("unexpected write") },
            { purpose, bytes -> JSONObject(SyncV2Codec.openLocal(SyncV2Fixtures.localKey, projection.scope, purpose, bytes)) })
        val snapshot = store.read(fixture.identity.entity("book", id), record)
        assertEquals(SyncV2Codec.canonical(original), SyncV2Codec.canonical(snapshot))
    }

    private fun refreshAndReopen(count: Int) = SyncV2P5Fixture().use { fixture ->
        val id = insertBook(fixture, count)
        fixture.actions().refresh()
        assertNotNull(fixture.projection.candidate("book", id))
        assertEquals((count + 255) / 256, assertPageLimits(fixture))
        val provenance = assertChapters(fixture, id, count)
        if (count == 2_048) {
            assertTrue(SyncV2Codec.utf8(SyncV2Codec.canonical(provenance)).size > 262_144)
        }
        fixture.reopen()
        assertFalse(fixture.projection.runWindow().paused)
        assertChapters(fixture, id, count)
        Unit
    }

    private fun assertChapters(fixture: SyncV2P5Fixture, id: String, count: Int, articleSuffix: String = ""): JSONObject {
        val provenance = checkNotNull(fixture.projection.sourceProvenance("book", id))
        val chapters = provenance.getJSONArray("chapters")
        assertEquals(count, chapters.length())
        for (i in 1..count) {
            val mapping = chapters.getJSONObject(i - 1)
            assertEquals(id, mapping.getString("bookId"))
            assertEquals(i, mapping.getInt("chapterIndex"))
            assertEquals(BookSync.articleId(id, i) + articleSuffix, mapping.getString("articleId"))
        }
        return provenance
    }

    private fun assertPageLimits(fixture: SyncV2P5Fixture): Int {
        val actualScope = fixture.projection.scope
        return fixture.sql.query("SELECT entity_key,source_hash,page,body FROM syncv2_book_provenance WHERE scope=?",
            arrayOf(actualScope)).use { rows ->
            while (rows.moveToNext()) {
                val bytes = rows.getBlob(3)
                assertTrue(bytes.size <= 262_144)
                val purpose = "book-provenance/${rows.getString(0)}/${rows.getString(1)}/${rows.getInt(2)}"
                val page = JSONObject(SyncV2Codec.openLocal(SyncV2Fixtures.localKey, actualScope, purpose, bytes))
                assertTrue(page.getJSONArray("chapters").length() in 1..256)
            }
            rows.count
        }
    }

    private fun insertBook(fixture: SyncV2P5Fixture, count: Int, deleted: Boolean = false): String {
        val fingerprint = "ab".repeat(32)
        val id = BookSync.id(fingerprint)
        val book = JSONObject().put("bookId", id).put("fingerprint", fingerprint)
            .put("chapterCount", count).put("title", "Book").put("deletedChapters", JSONArray())
            .put("deletionClock", 0).put("deleted", deleted).put("deletedAt", if (deleted) 1 else 0)
            .put("updatedAt", 1).put("sourceDeviceId", "synthetic-phone")
        val db = fixture.sql
        db.beginTransaction()
        try {
            db.execSQL("INSERT INTO imported_books(bookId,stateJson,packageSha256,packageBytes) VALUES(?,?,'hash',1)", arrayOf(id, book.toString()))
            db.compileStatement("INSERT INTO imported_book_chapters(bookId,chapterIndex,articleId) VALUES(?,?,?)").use { insert ->
                insert.bindString(1, id)
                for (i in count downTo 1) {
                    insert.bindLong(2, i.toLong())
                    insert.bindString(3, BookSync.articleId(id, i))
                    insert.executeInsert()
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return id
    }
}
