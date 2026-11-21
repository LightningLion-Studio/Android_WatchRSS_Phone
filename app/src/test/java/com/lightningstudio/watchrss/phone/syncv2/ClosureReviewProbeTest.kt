package com.lightningstudio.watchrss.phone.syncv2

import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE, sdk=[35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ClosureReviewProbeTest {
    private fun asset(w: SyncV2P5Fixture, name: String, bytes: ByteArray) {
        File(w.root, "notes/assets/$name").apply {
            parentFile!!.mkdirs()
            writeBytes(bytes)
        }
    }

    private fun count(w: SyncV2P5Fixture, table: String): Int =
        w.sql.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }

    @Test
    fun switchAtWriterCommitBoundaryLeavesNoRegistrationOrStaging() {
        var armed = false
        var switchInjected = false
        val generation = SyncV2Lifetime.Generation()
        SyncV2Lifetime(generation) { true }.use { lifetime ->
            SyncV2P5Fixture(lifetime).use { w ->
                w.pending()
                val bytes = "fixture attachment".toByteArray()
                asset(w, "legacy.png", bytes)
                asset(w, "other.png", bytes)
                w.edit("![fixture](assets/legacy.png)\n![other](assets/other.png)")
                val projection = SyncV2Projection(w.sql, w.identity, SyncV2Fixtures.localKey, true,
                    fault = {
                        if (armed && it == "before-commit") {
                            armed = false
                            switchInjected = true
                            generation.invalidate()
                        }
                    }, lifetime = lifetime)
                projection.install()
                SyncV2AccountActions(w.f.client(), w.identity, projection, w.f.signer, { w.key },
                    { it == "synthetic-phone" }, w.root, true, { w.f.now },
                    fault = { if (it == "before-action-stage") armed = true }).use { actions ->
                    val result = runCatching { actions.choose(actions.preview(), "local") }
                    assertTrue("writer commit fault must run: ${result.exceptionOrNull()}", switchInjected)
                    assertEquals("authentication_required", result.exceptionOrNull()?.message)
                    assertEquals(0, w.publishCalls)
                    val counts = listOf("note_assets", SyncV2NoteAssetNames.aliases, "syncv2_p5_objects", "syncv2_p5_rows").map { count(w, it) }
                    assertEquals("counts after invalidation at the commit boundary", listOf(0, 0, 0, 0), counts)
                }
            }
        }
    }

    @Test
    fun differentLegacyStorageKeysWithSameBytesCanBeKept() = SyncV2P5Fixture().use { w ->
        w.pending()
        val bytes = "same fixture attachment".toByteArray()
        asset(w, "present.png", bytes)
        asset(w, "legacy.png", bytes)
        w.sql.execSQL(
            "INSERT INTO note_assets VALUES('asset','note',?,'present.png','image/png',?,'present.png',0,0,0,0)",
            arrayOf<Any>(SyncV2BodyCodec.hash(bytes), bytes.size)
        )
        val markdown = "![present](assets/present.png)\n![legacy](assets/legacy.png)"
        w.edit(markdown)
        w.actions().use { actions ->
            assertTrue(actions.choose(actions.preview(), "local").getBoolean("localCommitted"))
        }
        assertEquals(1, w.publishCalls)
        assertEquals(markdown, w.text())
        assertEquals(1, count(w, "note_assets"))
        w.sql.query("SELECT assetId,storageKey FROM note_assets").use {
            assertTrue(it.moveToFirst())
            assertEquals("asset", it.getString(0))
            assertEquals("present.png", it.getString(1))
        }
    }

    @Test
    fun segmentedNoteCanRegisterTwoNamesWithTheSameDigest() = SyncV2P5Fixture().use { w ->
        w.pending()
        val bytes = "same fixture attachment".toByteArray()
        asset(w, "first.png", bytes)
        asset(w, "second.png", bytes)
        val markdown = "![first](assets/first.png)\n![second](assets/second.png)\n" + "paragraph\n".repeat(29_000)
        w.edit(markdown)
        w.noteSegmentsAnnounced = true
        val client = w.f.client().also { it.noteSegmentsLocal = true }
        SyncV2AccountActions(client, w.identity, w.projection, w.f.signer, { w.key },
            { it == "synthetic-phone" }, w.root, true, { w.f.now }, noteSegmentsRead = true).use { actions ->
            assertTrue(actions.choose(actions.pendingChoice()!!.preview, "local").getBoolean("localCommitted"))
        }

        assertEquals(1, w.publishCalls)
        assertEquals(markdown, w.text())
        assertEquals(1, count(w, "note_assets"))
        val rows = w.projection.cloudRows()
        assertEquals(setOf("first.png", "second.png"), rows.filter { it.getJSONObject("meta").getString("kind") == "asset" }
            .map { it.getJSONObject("meta").getString("logicalId") }.toSet())
        val note = rows.single { it.getJSONObject("meta").getString("kind") == "note" }
        assertTrue(note.getJSONArray(SyncV2NoteWiring.SEGMENTS_KEY).length() > 0)
        for (name in listOf("first.png", "second.png")) {
            assertArrayEquals(bytes, File(w.root, "notes/assets/$name").readBytes())
        }
        assertTrue(SyncV2BodyRepository(w.sql, w.root).refreshReferences(w.f.now) {}.isEmpty())
        assertEquals(1, count(w, "note_assets"))
    }
}
