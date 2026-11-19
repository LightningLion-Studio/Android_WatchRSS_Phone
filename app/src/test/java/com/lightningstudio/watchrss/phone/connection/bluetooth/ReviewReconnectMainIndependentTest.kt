package com.lightningstudio.watchrss.phone.connection.bluetooth

import androidx.room.Room
import com.lightningstudio.watchrss.phone.cloud.CloudSnapshotCodec
import com.lightningstudio.watchrss.phone.data.db.*
import com.lightningstudio.watchrss.phone.data.log.BluetoothDebugLog
import com.lightningstudio.watchrss.phone.data.note.*
import com.lightningstudio.watchrss.phone.data.reader.ReaderPresetRepository
import com.lightningstudio.watchrss.phone.data.repo.PhoneCompanionRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** Independent host probes: production manager, frame codec and schema 19; no radios. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class ReviewReconnectMainIndependentTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db = Room.inMemoryDatabaseBuilder(context, PhoneCompanionDatabase::class.java)
        .allowMainThreadQueries().build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val notes = NoteRepository(db.noteDao(), "phone", now = { 100 })
    private val tokens = PhoneLlmTokenUsageRepository(db.llmTokenUsageDao())
    private val repo = PhoneCompanionRepository(db.phoneSavedItemDao(), db.phoneArticleDao(),
        db.phoneRssSourceDao(), "phone", db.syncChangeLogDao(), db.syncPeerStateDao(), appMetaDao = db.appMetaDao())
    private val log = BluetoothDebugLog(context)
    private val device = PhoneBluetoothWatchDevice("watch", "11:22:33:44:55:66", 1, "watch", supportsPersistentSession = true)
    private val manager = PhoneBluetoothSyncManager(context, repo, notes,
        ReaderPresetRepository(context, db, db.readerPresetDao(), "phone", scope), tokens, "phone", log,
        buildAccountSyncRequest = { _, _, _ -> JSONObject().put("action", "syncAccount") },
        canSyncAccount = { true }, currentPhoneUserId = { "owner" })
    private val sessions = mutableListOf<PhoneSyncSession>()
    private val outputs = mutableListOf<ByteArrayOutputStream>()
    private val asset = File(context.filesDir, "notes/assets/review-reconnect.png")
    private var now = 0L
    private var reconnects = 0
    private val policy = LibraryTransferRecovery { now }

    @After fun close() { sessions.forEach { it.close() }; scope.cancel(); db.close(); asset.delete() }
    private fun ok(action: String, phase: String? = null) = JSONObject().put("success", true)
        .put("version", 14).put("deviceId", "watch").put("action", action)
        .apply { phase?.let { put("phase", it) } }
    private fun binding(noteDelta: Boolean) = ok("syncLibrary", "probe")
        .put("supportsReaderPresets", false).put("supportsNoteManifestV2", noteDelta)
        .put("supportsNoteAssetInventory", noteDelta)
        .put("account", JSONObject().put("bound", true)
            .put("userIdHash", CloudSnapshotCodec.sha256("owner".toByteArray())))
    private fun start(noteDelta: Boolean) = listOf(binding(noteDelta), ok("syncAccount"),
        LibrarySyncPayload.buildCursorResponse("watch", LibrarySyncCursor(0, 0, 0)),
        LibrarySyncPayload.buildManifestResponse("watch", emptyList()).put("supportsReaderPresets", false)
            .put("supportsNoteManifestV2", noteDelta).put("supportsNoteAssetInventory", noteDelta),
        ok("syncLibrary", "complete").put("articles", JSONArray()))
    private fun finish() = BluetoothSyncProtocol.buildSessionControlResponse(14, "complete")
    private fun noteReply(values: List<NoteEntity> = emptyList(), delta: Boolean = false) =
        NoteSyncPayload.manifest("watch", values).put("success", true).apply { if (delta) put("phase", "delta") }
    private fun session(responses: List<JSONObject>): PhoneSyncSession {
        val wire = ByteArrayOutputStream().also { out -> responses.forEach { BluetoothSyncProtocol.writeFrame(out, it) } }
        val output = ByteArrayOutputStream().also { outputs += it }
        return PhoneSyncSession(PhoneBluetoothSyncClient(context, log), device, "phone",
            PhoneSyncSession.Transport(ByteArrayInputStream(wire.toByteArray()), output, "host-review", false, {}),
            true, false, false).also { sessions += it }
    }
    private fun payloads(): List<JSONObject> = outputs.flatMap { out ->
        val input = ByteArrayInputStream(out.toByteArray())
        buildList { while (input.available() > 0) add(BluetoothSyncProtocol.readFrame(input)) }
    }.filter { it.optString("action") != "ack" }
    private fun usage(next: Long, id: Long = next, more: Boolean = true) = ok("syncLlmTokenUsage")
        .put("usageEpoch", "review-epoch").put("nextAfterId", next).put("hasMore", more).put("count", 1)
        .put("records", JSONArray().put(JSONObject().put("id", id).put("createdAt", id)
            .put("requestId", "review-$id").put("model", "synthetic").put("totalTokens", 3)))

    private suspend fun notesAcrossReconnects(fresh: Boolean): Throwable? {
        val seed = notes.save("local-image", "local", "![image](assets/review-reconnect.png)")
        asset.parentFile!!.mkdirs(); asset.writeBytes(byteArrayOf(1, 2, 3))
        fun responses(attempt: Int): List<JSONObject> {
            val remote = seed.copy(noteId = if (fresh) "remote-$attempt" else "remote-1", modifiedBy = "watch")
            val frames = mutableListOf<JSONObject>()
            frames += if (attempt == 1) start(true) else listOf(binding(true), ok("syncAccount"))
            frames += ok("syncNotes", "manifest").put("noteManifest", JSONArray()).put("assetManifest", JSONArray())
            frames += noteReply(listOf(remote), delta = true)
            // EOF at the first asset response. The note transaction has already committed.
            if (attempt == 5) {
                frames += ok("syncNoteAsset").put("alreadyPresent", true)
                frames += ok("syncLlmTokenUsage").put("records", JSONArray())
                frames += finish()
            }
            return frames
        }
        manager.reconnectSession = { _, _ ->
            reconnects++; now += 6000
            assertNotNull("previous note must be durably visible before reconnect", notes.note(if (fresh) "remote-$reconnects" else "remote-1"))
            session(responses(reconnects + 1))
        }
        val failure = runCatching { manager.syncAll(device, reusableSession = session(responses(1)), recoveryPolicy = policy) }.exceptionOrNull()
        println("REVIEW_NOTES fresh=$fresh reconnects=$reconnects notes=${notes.allNotes().size} noProgress=${policy.consecutiveNoProgress} failure=$failure")
        assertEquals(19, db.openHelper.readableDatabase.version)
        assertTrue(payloads().any { it.optString("action") == "syncNoteAsset" })
        return failure
    }
    @Test fun newCommittedNoteDeltaOnEveryReadyResetsNoProgress() = runBlocking<Unit> {
        val failure = notesAcrossReconnects(true)
        assertNull("distinct committed note rows must renew recovery: $failure", failure)
        assertEquals(4, reconnects)
        assertEquals(6, notes.allNotes().size)
    }
    @Test fun duplicateNoteDeltasStopWithoutDuplicatingRows() = runBlocking<Unit> {
        val failure = notesAcrossReconnects(false)
        assertTrue(failure is LibraryNoProgressLimit)
        assertEquals(3, reconnects)
        assertEquals(2, notes.allNotes().size)
    }
    private suspend fun usageAcrossReconnects(duplicate: Boolean): Throwable? {
        fun response(attempt: Int) = usage(attempt.toLong(), if (duplicate) 1 else attempt.toLong(), attempt < 5)
        val initial = session(start(false).map { it.put("supportsTokenUsageCursor", true) } + noteReply() + response(1))
        manager.reconnectSession = { _, _ ->
            reconnects++; now += 6000
            assertEquals(reconnects.toLong(), tokens.cursor("owner:watch")!!.afterId)
            assertEquals(if (duplicate) 1 else reconnects, tokens.observeRecent().first().size)
            val attempt = reconnects + 1
            session(listOf(binding(false).put("supportsTokenUsageCursor", true), ok("syncAccount"), response(attempt)) +
                if (attempt == 5) listOf(finish()) else emptyList())
        }
        val failure = runCatching { manager.syncAll(device, reusableSession = initial, recoveryPolicy = policy) }.exceptionOrNull()
        println("REVIEW_USAGE duplicate=$duplicate reconnects=$reconnects rows=${tokens.observeRecent().first().size} cursor=${tokens.cursor("owner:watch")} noProgress=${policy.consecutiveNoProgress} failure=$failure")
        return failure
    }
    @Test fun replayedUsageRowsWithAdvancingCursorMustNotCountAsNewBusinessChanges() = runBlocking<Unit> {
        val failure = usageAcrossReconnects(true)
        assertEquals(1, tokens.observeRecent().first().size)
        assertTrue(failure is LibraryNoProgressLimit)
        assertEquals(3, reconnects)
    }
    @Test fun newUsageRowsAcrossFourReadyIntervalsMustRemainLive() = runBlocking<Unit> {
        assertNull(usageAcrossReconnects(false))
        assertEquals(4, reconnects)
        assertEquals(5, tokens.observeRecent().first().size)
    }
}
