package com.lightningstudio.watchrss.phone.syncv2

import com.lightningstudio.watchrss.phone.connection.bluetooth.B2RoomStore
import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.B2Files
import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.assetDescriptor
import kotlinx.coroutines.runBlocking
import com.lightningstudio.watchrss.phone.data.note.NoteAssetFiles
import java.io.File
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
