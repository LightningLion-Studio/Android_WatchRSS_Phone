package com.lightningstudio.watchrss.phone.syncv2

import com.lightningstudio.watchrss.phone.connection.bluetooth.B2RoomStore
import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.B2Files
import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.assetDescriptor
import kotlinx.coroutines.runBlocking
import com.lightningstudio.watchrss.phone.data.note.NoteAssetFiles
import com.lightningstudio.watchrss.phone.data.book.BookSync
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class SyncV2ResetMigrationTest {
    @Test fun followsNewHeadAndPublishesLocalOnlyMemo() = SyncV2P5Fixture(reset = true).use { w ->
        addNote(w, "only-on-b", "B only")
        w.projection.captureResetSources {}
        publishMigration(w)
        assertEquals(mapOf("note" to "base\nline\n", "only-on-b" to "B only"), notes(w))
        assertEquals(1, w.publishCalls)
    }

    @Test fun sameIdForkSurvivesRollbackAndRestartAndRetainsBothBodies() {
        var interrupt = true
        SyncV2P5Fixture(reset = true, projectionFault = {
            if (it == "reset-fork-committed" && interrupt) error("interrupted")
        }).use { w ->
            addNote(w, "note", "B edit")
            w.projection.captureResetSources {}
            cloud(w).readCurrent()
            assertEquals("interrupted", runCatching { w.projection.prepareResetPublication() }.exceptionOrNull()?.message)
            assertEquals(mapOf("note" to "B edit"), notes(w))

            interrupt = false
            w.reopen()
            assertTrue(w.projection.prepareResetPublication())
            val fork = notes(w).keys.single { it != "note" }
            assertNull(w.projection.candidate("note", "note"))
            w.reopen()
            publishMigration(w)
            assertEquals(mapOf("note" to "base\nline\n", fork to "B edit"), notes(w))
            assertEquals(1, w.publishCalls)
            assertFalse(w.projection.prepareResetPublication())
        }
    }

    @Test fun firstPublicationMergesSameIdsAndPreservesLocalPositionAndBodies() = SyncV2P5Fixture(reset = true).use { w ->
        val url = "https://example.invalid/feed"
        val source = JSONObject().put("url", url).put("title", "Cloud title").put("description", "Cloud description")
            .put("createdAt", 19).put("updatedAt", 20).put("sourceDeviceId", "cloud")
        val folder = JSONObject().put("folderId", "shared-folder").put("name", "Folder").put("sortOrder", 7)
            .put("updatedAt", 4).put("modifiedBy", "cloud").put("deleted", false).put("deletedAt", 0)
        val article = JSONObject().put("articleId", "shared-article").put("url", "https://example.invalid/article")
            .put("sourceDeviceId", "cloud").put("updatedAt", 20).put("title", "Cloud title").put("siteName", "Cloud site")
            .put("favoriteSaved", true).put("favoriteChangedAt", 20).put("readingProgress", 0.25).put("contentHash", "remote-body")
        val novelText = "Local book chapter"
        val fingerprint = BookSync.fingerprint(listOf(BookSync.chapterHash(1, "chapter", novelText)))
        val bookId = BookSync.id(fingerprint)
        val book = JSONObject().put("bookId", bookId).put("fingerprint", fingerprint).put("chapterCount", 1)
            .put("title", "Cloud book").put("deletedChapters", JSONArray()).put("deletionClock", 0)
            .put("deleted", false).put("deletedAt", 0).put("updatedAt", 20).put("sourceDeviceId", "cloud")
        val reading = JSONObject().put("bookId", bookId).put("chapterIndex", 1).put("sourceDeviceId", "cloud")
            .put("isRead", false).put("readingProgress", 0.1).put("readingPositionBytes", 0)
            .put("readingPositionContentHash", "").put("readingPositionChangedAt", 20)
            .put("watchLaterSaved", true).put("watchLaterChangedAt", 20)
        val head = w.commit(listOf(w.note(), source, folder, article, book, reading), w.initial, "0", "normal", null)
        w.setCurrent(head, "3")

        val apply = SyncV2PhoneApply(w.sql)
        apply.upsert("phone_rss_sources", listOf("url"), copy(source).put("description", "")
            .put("createdAt", 5).put("updatedAt", 30).put("sourceDeviceId", "phone").put("title", "Local title"))
        apply.upsert("note_folders", listOf("folderId"), copy(folder).put("name", "")
            .put("updatedAt", 30).put("modifiedBy", "phone").put("sortOrder", 2))
        apply.upsert("phone_articles", listOf("articleId"), copy(article).put("sourceDeviceId", "phone")
            .put("siteName", "").put("contentText", "Local article body").put("contentHash", "local-body")
            .put("readingProgress", 0.75).put("readingPositionBytes", 42)
            .put("readingPositionContentHash", "local-body").put("readingPositionChangedAt", 10)
            .put("favoriteSaved", false).put("favoriteChangedAt", 0).put("watchLaterSaved", true).put("watchLaterChangedAt", 30))
        val localBook = copy(book).put("sourceDeviceId", "phone").put("updatedAt", 30)
            .put("sourceUrl", "https://watchrss.local/import-txt-novel/test")
        apply.upsert("imported_books", listOf("bookId"), JSONObject().put("bookId", bookId).put("stateJson", localBook.toString()))
        val bodyRepository = SyncV2BodyRepository(w.sql, w.root)
        val bookBytes = (JSONObject().put("format", "watchrss-book").put("version", 1).put("title", "Local book")
            .put("chapterCount", 1).put("sourceUrl", localBook.getString("sourceUrl")).put("kind", "txt").toString() + "\n" +
            JSONObject().put("chapterIndex", 1).put("title", "chapter").put("contentText", novelText)
                .put("contentHtml", JSONObject.NULL).put("url", localBook.getString("sourceUrl") + "/chapter/1").toString() + "\n").toByteArray()
        w.sql.beginTransaction()
        try {
            bodyRepository.install("book", bookId, bookBytes)
            w.sql.setTransactionSuccessful()
        } finally {
            w.sql.endTransaction()
        }
        val localReading = copy(reading).put("sourceDeviceId", "phone").put("readingPositionBytes", 37)
            .put("readingPositionContentHash", "chapter-hash").put("readingPositionChangedAt", 10)
            .put("readingProgress", 0.8).put("watchLaterSaved", false).put("watchLaterChangedAt", 0)
        apply.upsert("imported_book_reading", listOf("bookId", "chapterIndex"), JSONObject()
            .put("bookId", bookId).put("chapterIndex", 1).put("stateJson", localReading.toString()))

        w.projection.captureResetSources {}
        publishMigration(w)
        assertEquals(1, w.publishCalls)
        assertEquals(w.current.getString("commitOid"), w.projection.state().body.getJSONObject("appliedAccount").getString("oid"))
        assertTrue(w.projection.state().body.isNull("pendingChoice"))
        val merged = w.projection.cloudRows().mapNotNull { row ->
            val meta = row.getJSONObject("meta")
            val kind = meta.getString("kind")
            if (kind == "article" && meta.getString("logicalId") != "shared-article") null
            else kind to SyncV2Business.join(kind, meta, row.getJSONObject("state"))
        }.toMap()
        assertEquals("Local title", merged.getValue("source").getString("title"))
        assertEquals("Cloud description", merged.getValue("source").getString("description"))
        assertEquals(5, merged.getValue("source").getInt("createdAt"))
        assertEquals("Folder", merged.getValue("folder").getString("name"))
        assertEquals(2, merged.getValue("folder").getInt("sortOrder"))
        val mergedArticle = merged.getValue("article")
        assertEquals("Cloud site", mergedArticle.getString("siteName"))
        assertEquals(42, mergedArticle.getInt("readingPositionBytes"))
        assertEquals(0.75, mergedArticle.getDouble("readingProgress"), 0.0)
        assertTrue(mergedArticle.getBoolean("favoriteSaved"))
        assertTrue(mergedArticle.getBoolean("watchLaterSaved"))
        val mergedReading = merged.getValue("book-reading")
        assertEquals(37, mergedReading.getInt("readingPositionBytes"))
        assertEquals(0.8, mergedReading.getDouble("readingProgress"), 0.0)
        assertTrue(mergedReading.getBoolean("watchLaterSaved"))
        assertEquals(localBook.getString("sourceUrl"), merged.getValue("book").getString("sourceUrl"))
        assertTrue(bookBytes.contentEquals(bodyRepository.file("book", bookId).readBytes()))
        w.sql.query("SELECT contentText FROM phone_articles WHERE articleId='shared-article'").use {
            assertTrue(it.moveToFirst())
            assertEquals("Local article body", it.getString(0))
        }
        w.reopen()
        w.actions().refresh()
        assertFalse(w.projection.prepareResetPublication())
    }

    private fun copy(row: JSONObject) = JSONObject(row.toString())

    @Test fun committedScanResumesAfterCancellation() = SyncV2P5Fixture(reset = true).use { w ->
        addNote(w, "only-on-b", "durable source")
        var checks = 0
        assertEquals("interrupted", runCatching {
            w.projection.captureResetSources { if (++checks == 2) error("interrupted") }
        }.exceptionOrNull()?.message)
        w.reopen()
        w.projection.captureResetSources {}
        assertEquals(1, count(w, "syncv2_reset_pending"))
        assertEquals(1, count(w, "syncv2_reset_migration"))
        assertEquals(mapOf("only-on-b" to "durable source"), notes(w))
    }

    @Test fun articleSnapshotKeepsBothLocalBodyFormats() = SyncV2P5Fixture(reset = true).use { w ->
        val text = "plain fixture"
        val html = "<p>formatted fixture</p>"
        val article = JSONObject().put("articleId", "local-article")
            .put("url", "https://watchrss.local/import-content/txt/local-article")
            .put("contentText", text).put("contentHtml", html)
            .put("contentHash", SyncV2ResetSources.revision(JSONObject().put("text", text)))
            .put("independentSaved", true)
        SyncV2PhoneApply(w.sql).upsert("phone_articles", listOf("articleId"), article)

        w.projection.captureResetSources {}
        for (body in listOf(text, html)) {
            val digest = SyncV2Codec.hex(SyncV2Codec.sha(SyncV2Codec.utf8(body)))
            assertEquals(body, File(w.root, "reset-depot/$digest").readText())
        }
        assertEquals(1, count(w, "syncv2_reset_pending"))
    }

    @Test fun opaqueLegacyFrameKeepsOriginalAndDoesNotAdvanceTransportCursor() = SyncV2P5Fixture().use { w ->
        val frame = JSONObject().put("legacy-frame", "old unconfirmed frame")
        assertEquals("pendingChoice", w.projection.receiveLegacy(
            SyncV2Projection.LegacyInput("peer", 14, "pending", frame), null, true
        ))
        val before = w.projection.accountLegacyEntries().single().toString()
        val target = w.identity.scope.put("repoId", "92".repeat(16)).let(SyncV2Codec::scope)
        assertEquals("reset_bluetooth_pending", runCatching {
            SyncV2ResetSources.migrateBluetooth(w.sql, w.root, w.projection.scope, target) { SyncV2Fixtures.localKey.copyOf() }
        }.exceptionOrNull()?.message)
        assertEquals(before, w.projection.accountLegacyEntries().single().toString())
        assertEquals(0, count(w, "syncv2_reset_dependency"))
        assertEquals(0, w.projection.state().body.getJSONObject("applied").length())
    }

    @Test fun matchingBluetoothAttachmentPromotesOriginalWithoutAcknowledgingOldFrame() =
        SyncV2P5Fixture().use { w ->
            val key = "pending.png"
            val bytes = "verified fixture bytes".toByteArray()
            val digest = SyncV2Codec.hex(SyncV2Codec.sha(bytes))
            addNote(w, "note", "![pending](assets/$key)")
            NoteAssetFiles.legacy(w.root, key).apply {
                parentFile!!.mkdirs()
                writeBytes(bytes)
            }
            w.sql.execSQL("INSERT INTO note_assets VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf("local-asset", "note", digest, key, "image/png", bytes.size, key, true, w.f.now, false, 0L))
            val link = JSONObject().put("descriptor", assetDescriptor(bytes, key)).put("archiveId", "55".repeat(32))
            w.sql.beginTransaction()
            try {
                w.projection.recordGuardedBluetooth("peer", 14, "pending-verified", link)
                w.sql.setTransactionSuccessful()
            } finally {
                w.sql.endTransaction()
            }
            val target = w.identity.scope.put("repoId", "91".repeat(16)).let(SyncV2Codec::scope)
            repeat(2) {
                SyncV2ResetSources.migrateBluetooth(w.sql, w.root, w.projection.scope, target) { SyncV2Fixtures.localKey.copyOf() }
            }
            assertEquals(2, count(w, "syncv2_reset_dependency"))
            assertEquals(1, w.projection.accountLegacyEntries().size)
            assertEquals(0, w.projection.state().body.getJSONObject("applied").length())
        }

    @Test fun incompleteBinaryReceivePinsOnlyCurrentAccountAndRetainsDurableChunk() =
        SyncV2P5Fixture().use { w ->
            val scope = "fixture-bluetooth-account"
            val job = "05".repeat(16)
            val bytes = "staged bytes".toByteArray()
            val descriptor = assetDescriptor(bytes, "unconfirmed.png")
            val chunk = B2Files(File(w.root, "library-binary-v2"))
                .chunk(scope, job, descriptor.getString("objectId"), 0).apply { writeBytes(bytes) }
            w.sql.execSQL("CREATE TABLE library_binary_records(scope TEXT,namespace TEXT,id TEXT,body TEXT)")
            w.sql.execSQL("INSERT INTO library_binary_records VALUES(?,?,?,?)", arrayOf(scope, "job", job,
                JSONObject().put("state", "TRANSFERRING")
                    .put("description", JSONObject().put("direction", "watchToPhone")).toString()))
            w.sql.execSQL("INSERT INTO library_binary_records VALUES(?,?,?,?)", arrayOf(scope, "object", "object",
                JSONObject().put("jobId", job).put("descriptor", descriptor).put("receipt", JSONObject.NULL).toString()))

            assertFalse(SyncV2ResetSources.pinUnconfirmedBluetooth(w.sql, w.root, "unrelated"))
            assertTrue(SyncV2ResetSources.pinUnconfirmedBluetooth(w.sql, w.root, scope))
            w.reopen()
            assertTrue(runBlocking { B2RoomStore(w.room).retainsForRecovery(scope, job) })
            assertEquals(bytes.toList(), chunk.readBytes().toList())
            assertEquals(2, count(w, "library_binary_records"))
        }

    private fun addNote(w: SyncV2P5Fixture, id: String, body: String) =
        SyncV2PhoneApply(w.sql).upsert("notes", listOf("noteId"), w.note(id, body).put("noteId", id))

    private fun cloud(w: SyncV2P5Fixture) =
        SyncV2CloudSync(w.f.client(), w.identity, w.projection, { it == "synthetic-phone" }, { w.key }, { w.f.now })

    private fun publishMigration(w: SyncV2P5Fixture) {
        val cloud = cloud(w)
        cloud.readCurrent()
        w.drain()
        assertTrue(w.projection.prepareResetPublication())
        w.drain()
        cloud.publishIndex(w.f.signer, w.key, 1, w.projection.state().revision, resetAdditions = true)
        w.actions().refresh()
    }

    private fun notes(w: SyncV2P5Fixture): Map<String, String> =
        w.sql.query("SELECT noteId,markdown FROM notes ORDER BY noteId").use { rows ->
            buildMap { while (rows.moveToNext()) put(rows.getString(0), rows.getString(1)) }
        }

    private fun count(w: SyncV2P5Fixture, table: String): Int =
        w.sql.query("SELECT COUNT(*) FROM $table").use { rows -> rows.moveToFirst(); rows.getInt(0) }
}
