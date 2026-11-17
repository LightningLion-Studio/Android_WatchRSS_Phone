package com.lightningstudio.watchrss.phone.connection.bluetooth

import androidx.room.Room
import com.lightningstudio.watchrss.phone.data.db.*
import com.lightningstudio.watchrss.phone.data.log.BluetoothDebugLog
import com.lightningstudio.watchrss.phone.data.note.NoteRepository
import com.lightningstudio.watchrss.phone.data.reader.ReaderPresetRepository
import com.lightningstudio.watchrss.phone.data.repo.PhoneCompanionRepository
import kotlinx.coroutines.*
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real main manager, framing and schema 19 SQLite. All transports are host fixtures. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class ReconnectMainSessionTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db = Room.inMemoryDatabaseBuilder(context, PhoneCompanionDatabase::class.java).allowMainThreadQueries().build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val repo = PhoneCompanionRepository(db.phoneSavedItemDao(), db.phoneArticleDao(), db.phoneRssSourceDao(), "phone",
        db.syncChangeLogDao(), db.syncPeerStateDao(), appMetaDao = db.appMetaDao())
    private val log = BluetoothDebugLog(context)
    private val client = PhoneBluetoothSyncClient(context, log)
    private val device = PhoneBluetoothWatchDevice("watch", "11:22:33:44:55:66", 1, "watch", supportsPersistentSession = true)
    private var owner: String? = null
    private val manager = PhoneBluetoothSyncManager(context, repo, NoteRepository(db.noteDao(), "phone"),
        ReaderPresetRepository(context, db, db.readerPresetDao(), "phone", scope), PhoneLlmTokenUsageRepository(db.llmTokenUsageDao()),
        "phone", log, currentPhoneUserId = { owner })
    private val requests = mutableListOf<String>()
    private val completeEntered = CountDownLatch(1)
    private val closeComplete = CountDownLatch(1)
    private var closed = 0

    @After fun close() { closeComplete.countDown(); scope.cancel(); db.close() }
    private fun ok(action: String, phase: String) = JSONObject().put("success", true).put("version", 14)
        .put("deviceId", "watch").put("action", action).put("phase", phase)
    private fun session(dropAt: String? = null, blockComplete: Boolean = false, changeOwner: Boolean = false): PhoneSyncSession {
        val pending = ArrayDeque<Byte>()
        var blocking = false
        val input = object : InputStream() {
            override fun read(): Int {
                if (blocking) { completeEntered.countDown(); check(closeComplete.await(5, TimeUnit.SECONDS)); throw IOException("closed") }
                return if (pending.isEmpty()) -1 else pending.removeFirst().toInt() and 255
            }
        }
        val output = object : ByteArrayOutputStream() {
            override fun flush() {
                if (size() == 0) return
                val q = BluetoothSyncProtocol.readFrame(toByteArray().inputStream()); reset()
                val action = q.optString("action"); val phase = q.optString("phase")
                if (action == "ack") return
                requests += "$action:$phase"
                if (phase == dropAt) throw IOException("socket closed")
                if (changeOwner && phase == "manifest") owner = "other"
                if (blockComplete && action == "syncSession") { blocking = true; return }
                val response = when (action) {
                    "syncLibrary" -> when (phase) {
                        "probe" -> ok(action, phase).put("supportsPersistentSession", true).put("persistentSessionAccepted", true)
                        "cursor" -> LibrarySyncPayload.buildCursorResponse("watch", LibrarySyncCursor(0, 0, 0))
                        "manifest" -> LibrarySyncPayload.buildManifestResponse("watch", emptyList()).put("supportsReaderPresets", false)
                            .put("supportsNoteManifestV2", false).put("supportsNoteAssetInventory", false)
                        "articles" -> ok(action, "complete").put("articles", JSONArray())
                        else -> error("unexpected $phase")
                    }
                    "syncNotes" -> NoteSyncPayload.manifest("watch", emptyList()).put("success", true)
                    "syncLlmTokenUsage" -> ok(action, phase).put("records", JSONArray())
                    "syncSession" -> BluetoothSyncProtocol.buildSessionControlResponse(14, phase)
                    else -> error("unexpected $action")
                }
                val wire = ByteArrayOutputStream().also { BluetoothSyncProtocol.writeFrame(it, response) }
                wire.toByteArray().forEach { pending.addLast(it) }
            }
        }
        return PhoneSyncSession(client, device, "phone", PhoneSyncSession.Transport(input, output, "rfcomm", false,
            closeTransport = { closed++; closeComplete.countDown() }), true, false, false)
    }

    @Test fun interruptedManifestReconnectsSameSessionAndStartsFreshCursor() = runBlocking<Unit> {
        var searches = 0
        manager.recoveryProbeDevices = { _, _ -> searches++; PhoneBluetoothWatchProbeBatch(
            listOf(PhoneBluetoothWatchProbeResult(device, true)), session()) }
        val result = manager.syncAll(device, reusableSession = session(dropAt = "manifest"))
        assertEquals(1, searches)
        assertEquals(0, result.libraryStats!!.merged)
        assertEquals(2, requests.count { it == "syncLibrary:cursor" })
        assertEquals(2, requests.count { it == "syncLibrary:manifest" })
        assertEquals(1, requests.count { it == "syncSession:complete" })
        assertEquals(19, db.openHelper.readableDatabase.version)
    }

    @Test fun threeSearchFailuresStopWithoutAnotherDataFrame() = runBlocking<Unit> {
        var searches = 0
        manager.recoveryProbeDevices = { _, _ -> searches++; PhoneBluetoothWatchProbeBatch(emptyList()) }
        val failure = runCatching { manager.syncAll(device, reusableSession = session(dropAt = "manifest")) }.exceptionOrNull()
        assertTrue("$failure", failure is LibraryReconnectLimit)
        assertEquals(3, searches)
        assertEquals(1, requests.count { it == "syncLibrary:cursor" })
        assertFalse(requests.contains("syncSession:complete"))
    }

    @Test fun accountChangeDuringIoNeverSearchesOrCompletes() = runBlocking<Unit> {
        var searches = 0
        manager.recoveryProbeDevices = { _, _ -> searches++; error("must not search") }
        val failure = runCatching { manager.syncAll(device, reusableSession = session(changeOwner = true)) }.exceptionOrNull()
        assertTrue("$failure", failure is IllegalStateException)
        assertEquals(0, searches)
        assertFalse(requests.contains("syncSession:complete"))
    }

    @Test fun completeCancellationReleasesSameClientMutexForNextTask() = runBlocking<Unit> {
        val job = launch(Dispatchers.Default) { manager.syncAll(device, reusableSession = session(blockComplete = true)) }
        try {
            assertTrue(withContext(Dispatchers.IO) { completeEntered.await(5, TimeUnit.SECONDS) })
            job.cancel(); withTimeout(1500) { job.join() }
            assertTrue(closed > 0)
            withTimeout(2000) { manager.syncAll(device, reusableSession = session()) }
        } finally { closeComplete.countDown(); job.cancelAndJoin() }
    }
}
