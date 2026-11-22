package com.lightningstudio.watchrss.phone.connection.bluetooth

import androidx.room.Room
import com.lightningstudio.watchrss.phone.BuildConfig
import com.lightningstudio.watchrss.phone.cloud.CloudSnapshotCodec
import com.lightningstudio.watchrss.phone.connection.ip.IpTransportKind
import com.lightningstudio.watchrss.phone.connection.ip.PhoneIpSyncSession
import com.lightningstudio.watchrss.phone.connection.ip.PhoneIpSyncSessionRegistry
import com.lightningstudio.watchrss.phone.data.db.PhoneCompanionDatabase
import com.lightningstudio.watchrss.phone.data.db.PhoneLlmTokenUsageRepository
import com.lightningstudio.watchrss.phone.data.log.BluetoothDebugLog
import com.lightningstudio.watchrss.phone.data.note.NoteRepository
import com.lightningstudio.watchrss.phone.data.reader.ReaderPresetRepository
import com.lightningstudio.watchrss.phone.data.repo.PhoneCompanionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class LateOldPeerIpUpgradeTest {
    @Test fun authenticatedOldPeerIpRouteSurvivesRfcommEof() = runBlocking<Unit> {
        val withoutWindow = tryLateRoute(null)
        val windowKey = if (BuildConfig.SYNC_V2) "authenticated-window" else null
        val withWindow = tryLateRoute(windowKey)
        println("without window: $withoutWindow")
        println("with window: $withWindow")
        assertEquals(0, withoutWindow.guardedProbes)
        assertTrue("control must send a frame over the registered IP link", withoutWindow.ipWrites > 0)
        assertEquals("registered IP transport must receive the first business frame: $withWindow",
            withoutWindow.ipWrites, withWindow.ipWrites)
        assertEquals("the dead RFCOMM link must not be queried before IP takeover",
            0, withWindow.guardedProbes)
    }

    private data class Observation(val guardedProbes: Int, val ipWrites: Int, val failure: String?)

    private suspend fun tryLateRoute(windowKey: String?): Observation {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, PhoneCompanionDatabase::class.java)
            .allowMainThreadQueries().build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repository = PhoneCompanionRepository(db.phoneSavedItemDao(), db.phoneArticleDao(),
            db.phoneRssSourceDao(), "phone", db.syncChangeLogDao(), db.syncPeerStateDao())
        val log = BluetoothDebugLog(context)
        val client = PhoneBluetoothSyncClient(context, log)
        val device = PhoneBluetoothWatchDevice("old-watch", "11:22:33:44:55:66", 1, "watch", "11:22:33:44:55:66", true)
        var guardedProbes = 0
        val rfcommOutput = object : ByteArrayOutputStream() {
            override fun flush() {
                if (size() == 0) return
                val frame = BluetoothSyncProtocol.readFrame(ByteArrayInputStream(toByteArray()))
                reset()
                if (frame.has("supportedGuardedLibraryVersions")) guardedProbes++
            }
        }
        val session = PhoneSyncSession(client, device, "phone",
            PhoneSyncSession.Transport(ByteArrayInputStream(byteArrayOf()), rfcommOutput, "rfcomm", false, {}),
            persistentAccepted = true, negotiationPending = false, ipUpgradeExpected = true)
        session.bindingProbe = JSONObject().put("success", true).put("action", "syncLibrary")
            .put("phase", "probe").put("version", 14).put("deviceId", "watch")
            .asLegacyPodJsProbe(readerResources = false).put("ipUpgradeAccepted", true)
            .put("account", JSONObject().put("bound", true)
                .put("userIdHash", CloudSnapshotCodec.sha256("owner".toByteArray())))
        var ipWrites = 0
        val ip = PhoneIpSyncSession("watch", "authenticated-late-hello", IpTransportKind.WIFI_LAN,
            "192.0.2.1", { ipWrites++; error("probe stops at the first IP write") }, {})
        PhoneIpSyncSessionRegistry.register(ip)
        try {
            val manager = PhoneBluetoothSyncManager(context, repository, NoteRepository(db.noteDao(), "phone"),
                ReaderPresetRepository(context, db, db.readerPresetDao(), "phone", scope),
                PhoneLlmTokenUsageRepository(db.llmTokenUsageDao()), "phone", log,
                canSyncAccount = { true }, currentPhoneUserId = { "owner" }, b2Database = { db },
                b2AccountScope = { "a".repeat(64) })
            manager.binaryWindowKey = { windowKey }
            manager.reconnectDelay = {}
            manager.reconnectSession = { _, _ -> error("probe stops at the first reconnect") }
            val failure = runCatching { manager.syncAll(device, reusableSession = session) }.exceptionOrNull()
            return Observation(guardedProbes, ipWrites, failure?.let { "${it.javaClass.simpleName}: ${it.message}" })
        } finally {
            session.close()
            ip.close()
            scope.cancel()
            db.close()
        }
    }
}
