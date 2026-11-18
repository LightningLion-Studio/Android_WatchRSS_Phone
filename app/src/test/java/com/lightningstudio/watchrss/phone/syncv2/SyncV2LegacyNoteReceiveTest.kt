package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONArray
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
class SyncV2LegacyNoteReceiveTest {
    private fun SyncV2P5Fixture.publishLegacyBatch(values: List<JSONObject>) {
        stateHook = { id, state ->
            if (id == "legacy") {
                state.getJSONArray("requiredFeatures").remove(0)
                state.getJSONObject("payload").remove("noteConflicts")
            }
        }
        val oid = commit(values, current.getString("commitOid"), "0", "normal", null)
        setCurrent(oid, SyncV2Codec.next(current.getString("revision")))
        stateHook = null
    }

    @Test fun legacyMiniNoteDoesNotAbortBatchOrEraseKnownConflictFacts() = SyncV2P5Fixture().use { w ->
        val legacy = w.note("original", "old body").put("noteId", "legacy")
        val conflict = JSONObject().put("conflictId", "resolved").put("noteId", "legacy")
            .put("baseMarkdown", "base").put("localMarkdown", "mine").put("remoteMarkdown", "theirs")
            .put("remoteDeviceId", "mini").put("createdAt", 1).put("resolvedAt", 2)
        val original = JSONObject(legacy.toString())
        original.getJSONObject("noteConflicts").getJSONArray("records").put(conflict)
        val first = w.commit(listOf(original), w.initial, "0", "normal", null)
        w.setCurrent(first, "3")
        w.start()

        val schema = javaClass.getResourceAsStream("/syncv2-business-schema.json")!!
            .bufferedReader().use { JSONObject(it.readText()).getJSONArray("records") }
        val extra = (0 until schema.length()).map { schema.getJSONObject(it) }
            .filter { it.getString("kind") in setOf("book", "asset") }
            .map { it.getJSONObject("value") }
        assertEquals(2, extra.size)
        val updated = JSONObject(legacy.toString()).put("title", "Legacy mini editor")
            .put("markdown", "new legacy body").put("plainText", "new legacy body")
        w.publishLegacyBatch(listOf(updated, w.note("ordinary note")) + extra)
        w.actions().use { it.refresh() }
        w.drain()

        assertEquals(2, w.sql.query("SELECT COUNT(*) FROM notes").use { it.moveToFirst(); it.getInt(0) })
        assertEquals("new legacy body", w.sql.query("SELECT markdown FROM notes WHERE noteId='legacy'").use { it.moveToFirst(); it.getString(0) })
        assertEquals(2, w.sql.query("SELECT resolvedAt FROM note_conflicts WHERE conflictId='resolved'").use { it.moveToFirst(); it.getInt(0) })
        assertEquals(1, w.sql.query("SELECT COUNT(*) FROM imported_books").use { it.moveToFirst(); it.getInt(0) })
        assertEquals(1, w.sql.query("SELECT COUNT(*) FROM note_assets").use { it.moveToFirst(); it.getInt(0) })
        val authenticated = w.projection.cloudRows().single { it.getJSONObject("meta").getString("logicalId") == "legacy" }
        assertFalse(authenticated.getJSONObject("state").getJSONObject("payload").has("noteConflicts"))
    }

    @Test fun unmodifiedLegacyNoteReusesItsAuthenticatedState() = SyncV2P5Fixture().use { w ->
        w.publishLegacyBatch(listOf(w.note("Legacy mini editor", "legacy body").put("noteId", "legacy")))
        w.start()
        w.reopen()
        val entity = w.identity.entity("note", "legacy")
        val remote = w.projection.cloudRows().single().getJSONObject("row")
        val candidate = w.projection.candidate("note", "legacy")!!
        val value = SyncV2Business.join("note", candidate.first, candidate.second)
        assertEquals("legacy body", value.getString("markdown"))
        assertFalse(value.has("noteConflicts"))

        w.projection.prepareWireIndex(w.f.signer, w.key, 1).use { wire ->
            val row = SyncV2Index.read(w.identity.scope, wire.tree.root, wire.objects::getValue).single()
            assertEquals(entity, row.getString("entityKey"))
            assertEquals(remote.getString("metaOid"), row.getString("metaOid"))
            assertEquals(remote.getString("stateOid"), row.getString("stateOid"))
        }
    }

    @Test fun undeclaredChannelInAuthenticatedCatalogDoesNotThrowWhenOpeningChoice() = SyncV2P5Fixture().use { w ->
        w.start()
        w.edit()
        w.publishLegacyBatch(listOf(w.note(), w.note("Legacy mini editor").put("noteId", "legacy")))
        val actions = w.actions()
        actions.use {
            it.refresh()
            val channels = w.projection.accountCatalog.channelRows("cloud")
            val legacy = channels.getValue(w.identity.entity("note", "legacy"))
            assertEquals("legacy", legacy.getString("identity"))
            assertFalse(legacy.has("noteConflicts"))
            // Enter an actual pending choice by rolling back to the legacy snapshot.
            w.target = w.current.getString("commitOid")
            w.publishLegacyBatch(listOf(w.note("Later"), w.note("Legacy mini editor").put("noteId", "legacy")))
            w.rollbackRemote()
            it.refresh()
            val choice = it.pendingChoice()
            assertNotNull(choice)
            assertFalse(choice!!.canMerge)
            assertEquals("missing_base", choice.reason)
            assertEquals("offline\nline\n", w.text())
        }
    }
}
