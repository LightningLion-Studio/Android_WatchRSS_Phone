package com.lightningstudio.watchrss.phone.connection.bluetooth

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.room.Room
import com.lightningstudio.watchrss.phone.BuildConfig
import com.lightningstudio.watchrss.phone.account.AccountEnvironment
import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.B2Negotiation
import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.B2Wire
import com.lightningstudio.watchrss.phone.connection.ip.IpTransportKind
import com.lightningstudio.watchrss.phone.connection.ip.PhoneIpSyncSession
import com.lightningstudio.watchrss.phone.connection.ip.PhoneIpSyncSessionRegistry
import com.lightningstudio.watchrss.phone.data.ble.BleDomainStore
import com.lightningstudio.watchrss.phone.data.db.PhoneCompanionDatabase
import com.lightningstudio.watchrss.phone.data.log.BluetoothDebugLog
import com.lightningstudio.watchrss.phone.data.note.NoteRepository
import com.lightningstudio.watchrss.phone.data.reader.ReaderPresetRepository
import com.lightningstudio.watchrss.phone.data.repo.PhoneCompanionRepository
import com.lightningstudio.watchrss.phone.data.telemetry.BtSyncDevice
import com.lightningstudio.watchrss.phone.data.telemetry.BtSyncSession
import com.lightningstudio.watchrss.phone.data.telemetry.BtSyncTrigger
import com.lightningstudio.watchrss.phone.features.FeatureOverride
import com.lightningstudio.watchrss.phone.features.FeatureRegistry
import com.lightningstudio.watchrss.phone.rollout.ClientConfigRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class PhoneBluetoothRolloutRoutingTest {
    private class BusinessFrameReached : IllegalArgumentException("fixture reached first business frame")
    private enum class Start { IP, LATE_IP, RECONNECT, OPEN }
    private data class Seen(val transport: String, val request: JSONObject)
    private val context: Context = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val db = Room.inMemoryDatabaseBuilder(context, PhoneCompanionDatabase::class.java)
        .allowMainThreadQueries().build()
    private val log = BluetoothDebugLog(context)
    private val client = PhoneBluetoothSyncClient(context, log)
    private val watch = PhoneBluetoothWatchDevice("watch", "11:22:33:44:55:66", 1, "watch",
        supportsPersistentSession = true)
    private val firstBusiness = JSONObject().put("version", 14).put("action", "syncAccount")
        .put("phase", "apply").put("fixture", "fixed-account-payload")
    private val seen = mutableListOf<Seen>()
    private val opens = mutableListOf<Boolean>()
    private val events = mutableListOf<JSONObject>()
    private val manager = PhoneBluetoothSyncManager(
        context,
        PhoneCompanionRepository(db.phoneSavedItemDao(), db.phoneArticleDao(), db.phoneRssSourceDao(),
            "phone", db.syncChangeLogDao(), db.syncPeerStateDao()),
        NoteRepository(db.noteDao(), "phone"),
        ReaderPresetRepository(context, db, db.readerPresetDao(), "phone", scope),
        PhoneLlmTokenUsageRepository(db.llmTokenUsageDao()), "phone", log,
        buildAccountSyncRequest = { _, _, _ -> firstBusiness },
        canSyncAccount = { true }, currentPhoneUserId = { "owner" },
        b2Database = { db }, b2AccountScope = { "scope" }, sessionInitScope = scope
    ).apply {
        binaryWindowKey = { "scope:0" }
    }

    @After fun close() {
        PhoneIpSyncSessionRegistry.closeAll()
        scope.cancel()
        db.close()
    }

    @Test fun zeroPercentKeepsReusedIp() = runBlocking {
        runRoute(Start.IP, percent = 0, expectedCohort = "b2_off")
        assertEquals(listOf("ip"), businessTransports())
        assertTrue(opens.isEmpty())
        assertNoGuardedOffer()
    }

    @Test fun zeroPercentAllowsIpOnInitialOpen() = runBlocking {
        runRoute(Start.OPEN, percent = 0, expectedCohort = "b2_off")
        assertEquals(listOf(true), opens)
        assertEquals(listOf("ip"), businessTransports())
        assertNoGuardedOffer()
    }

    @Test fun zeroPercentAllowsLateIpUpgrade() = runBlocking {
        runRoute(Start.LATE_IP, percent = 0, expectedCohort = "b2_off")
        assertEquals(listOf("ip"), businessTransports())
        assertTrue(opens.isEmpty())
        assertEquals("complete", seen.first().request.getString("phase"))
        assertNoGuardedOffer()
    }

    @Test fun zeroPercentKeepsBaselineReconnectAfterIpLoss() = runBlocking {
        runRoute(Start.RECONNECT, percent = 0, expectedCohort = "b2_off")
        assertEquals(listOf("ip", "rfcomm"), businessTransports())
        assertEquals(listOf(false), opens)
        assertNoGuardedOffer()
    }

    @Test fun discoveryCohortSurvivesConfigChangeAndReconnect() = runBlocking {
        runRoute(Start.RECONNECT, percent = 1, expectedCohort = "b2_off", changeAfterDiscovery = true)
        assertEquals(listOf("ip", "rfcomm"), businessTransports())
        assertEquals(listOf(false), opens)
        assertNoGuardedOffer()
    }

    @Test fun forcedOffAtHundredPercentReusesIp() = runBlocking {
        runRoute(Start.IP, percent = 100, forced = "off", expectedCohort = "forced_off")
        assertEquals(listOf("ip"), businessTransports())
        assertTrue(opens.isEmpty())
        assertNoGuardedOffer()
    }

    @Test fun expiredHundredPercentReusesIp() = runBlocking {
        runRoute(Start.IP, percent = 100, expired = true, expectedCohort = "b2_off")
        assertEquals(listOf("ip"), businessTransports())
        assertTrue(opens.isEmpty())
        assertNoGuardedOffer()
    }

    @Test fun hundredPercentKeepsRfcommForAValidatedOfferBeforeLateIpUpgrade() = runBlocking {
        runRoute(Start.LATE_IP, percent = 100, expectedCohort = "b2_on")
        val enabled = BuildConfig.B2_ENABLED
        assertEquals(if (enabled) listOf("rfcomm") else listOf("ip"), businessTransports())
        assertTrue(opens.isEmpty())
        val offers = seen.filter { it.request.has("supportedGuardedLibraryVersions") }
        assertEquals(if (enabled) listOf("rfcomm") else emptyList<String>(), offers.map { it.transport })
        if (enabled) assertEquals("probe", seen.first().request.getString("phase"))
    }

    @Test fun hundredPercentKeepsAnAlreadyLeasedIpSessionOnV1() = runBlocking {
        runRoute(Start.IP, percent = 100, expectedCohort = "ineligible")
        assertEquals(listOf("ip"), businessTransports())
        assertTrue(opens.isEmpty())
        assertNoGuardedOffer()
    }

    @Test fun olderWatchWithoutGuardedCapabilityUsesLegacyBusinessFrame() = runBlocking {
        runRoute(Start.OPEN, percent = 100, oldPeer = true, expectedCohort = "ineligible")
        assertEquals(listOf("rfcomm"), businessTransports())
        assertEquals(listOf(true), opens)
        assertEquals(if (BuildConfig.B2_ENABLED) 1 else 0,
            seen.count { it.request.has("supportedGuardedLibraryVersions") })
    }

    private suspend fun runRoute(start: Start, percent: Int, forced: String? = null,
        expired: Boolean = false, oldPeer: Boolean = false, expectedCohort: String,
        changeAfterDiscovery: Boolean = false) {
        val config = installRollout(percent, forced, expired)
        val discovery = probe(oldPeer)
        manager.openLibrarySession = { _, _, allowIpUpgrade, keepRfcomm ->
            opens += allowIpUpgrade
            val rfcomm = session("rfcomm", discovery)
            if (start == Start.OPEN && allowIpUpgrade && !oldPeer && keepRfcomm?.invoke(rfcomm, "fixture") != true) {
                rfcomm.close()
                session("ip:fixture", discovery)
            } else rfcomm
        }
        manager.reconnectDelay = {}
        val reusable = when (start) {
            Start.IP, Start.RECONNECT -> session("ip:fixture", discovery, drop = start == Start.RECONNECT)
            Start.LATE_IP -> {
                registerLateIp(discovery)
                session("rfcomm", discovery, lateUpgrade = true)
            }
            Start.OPEN -> null
        }
        val telemetry = BtSyncSession(
            BtSyncDevice("rollout-fixture", "fixture", 121, "fixture", "fixture", "15", 35),
            BtSyncTrigger.MANUAL, { events += it.data }, { 42L }, { 42L }
        )
        val rollout = if (changeAfterDiscovery) {
            manager.probeDevices = { _, _, _ ->
                PhoneBluetoothWatchProbeBatch(listOf(PhoneBluetoothWatchProbeResult(watch, true)), reusable)
            }
            manager.probeLibrarySyncTargets(libraryMode = LibrarySyncMode.MERGE).rollout.also {
                config.setDebugOverride(FeatureRegistry.b2, FeatureOverride.ON)
            }
        } else null
        val failure = runCatching {
            withContext(telemetry) { manager.syncAll(watch, reusableSession = reusable, sessionRollout = rollout) }
        }.exceptionOrNull()
        assertTrue("Unexpected exit: $failure", failure is BusinessFrameReached)
        assertTrue(businessFrames().isNotEmpty())
        businessFrames().forEach { assertEquals(firstBusiness.toString(), it.request.toString()) }
        val event = events.single()
        assertEquals(if (BuildConfig.B2_ENABLED) expectedCohort else "ineligible", event.getString("cohort"))
        assertEquals("v1", event.getString("protocol"))
        assertEquals(expired, event.optBoolean("configStale"))
        if (start == Start.RECONNECT) assertEquals(1, event.getJSONObject("transfer").getInt("disconnects"))
        println("RC_WIRE ${start.name}/$percent/${forced.orEmpty()}/$expired/$oldPeer " +
            seen.joinToString(" ") { "${it.transport}:${it.request}" })
    }

    private fun businessFrames() = seen.filter { it.request.optString("action") == "syncAccount" }
    private fun businessTransports() = businessFrames().map { it.transport }
    private fun assertNoGuardedOffer() {
        assertFalse(seen.any { it.request.has("supportedGuardedLibraryVersions") })
    }

    private fun installRollout(percent: Int, forced: String?, expired: Boolean): ClientConfigRepository {
        val environment = AccountEnvironment.active(context)
        val boot = 12
        val config = JSONObject().put("configVersion", 42).put("ttlSeconds", 600)
            .put("features", JSONObject().put("b2", JSONObject()
                .put("percent", percent).put("salt", "b2-2026-11")
                .put("minAppVersionCode", 0).put("forced", forced ?: JSONObject.NULL)))
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, boot)
        context.getSharedPreferences("watchrss_client_config${environment.storageSuffix}", Context.MODE_PRIVATE)
            .edit().putString("install_id", "rollout-fixture").putString("last_config", config.toString())
            .putLong("fetched_at", System.currentTimeMillis())
            .putLong("fetched_elapsed_at_download", SystemClock.elapsedRealtime())
            .putInt("fetched_boot_at_download", if (expired) boot - 1 else boot).commit()
        val repository = ClientConfigRepository(context, environment, "rollout-fixture", scope)
        manager.b2RolloutConfig = { repository }
        return repository
    }

    private fun response(action: String, phase: String) = JSONObject().put("success", true)
        .put("version", 14).put("action", action).put("phase", phase).put("deviceId", "watch")

    private fun probe(oldPeer: Boolean) = response("syncLibrary", "probe").asLegacyPodJsProbe()
        .put("persistentSessionAccepted", true)
        .put("account", JSONObject().put("bound", true).put("userIdHash", BleDomainStore.hash("owner")))
        .apply { if (oldPeer) put("supportsReaderAssets", false).put("supportsReaderPresets", false)
            .put("supportsResourceChunkAck", false) }

    private fun reply(request: JSONObject, discovery: JSONObject, transport: String, drop: Boolean): JSONObject? {
        val action = request.getString("action")
        val phase = request.optString("phase")
        if (action == "ack") return null
        seen += Seen(transport, request)
        if (action == "syncAccount") {
            if (drop) throw IOException("bt socket closed")
            throw BusinessFrameReached()
        }
        if (action == "syncSession") return response(action, phase)
        check(action == "syncLibrary" && phase == "probe")
        return JSONObject(discovery.toString()).apply {
            if (request.has("supportedGuardedLibraryVersions") && discovery.optBoolean("supportsReaderAssets")) {
                put("guardedLibraryVersion", 1)
                put("libraryPeer", JSONObject().put("implementation", "podjs").put("appVersion", "2.0.2"))
                put("libraryBinary", B2Negotiation.capability(setOf(B2_NOTE_ASSET), setOf(B2_NOTE_ASSET), B2Wire.s2Limits()))
            }
        }
    }

    private fun session(owner: String, discovery: JSONObject, drop: Boolean = false,
        lateUpgrade: Boolean = false): PhoneSyncSession {
        val incoming = ArrayDeque<Byte>()
        val output = object : ByteArrayOutputStream() {
            override fun flush() {
                if (size() == 0) return
                val bytes = toByteArray()
                reset()
                val request = BluetoothSyncProtocol.readFrame(ByteArrayInputStream(bytes))
                val transport = if (owner.startsWith("ip:")) "ip" else owner
                reply(request, discovery, transport, drop)?.let { answer ->
                    ByteArrayOutputStream().also { BluetoothSyncProtocol.writeFrame(it, answer) }
                        .toByteArray().forEach(incoming::addLast)
                }
            }
        }
        val input = object : InputStream() {
            override fun read(): Int = if (incoming.isEmpty()) -1 else incoming.removeFirst().toInt() and 255
        }
        return PhoneSyncSession(client, watch, "phone", PhoneSyncSession.Transport(input, output, owner, false, {}),
            true, false, lateUpgrade).also { it.bindingProbe = discovery }
    }

    private fun registerLateIp(discovery: JSONObject) {
        val pending = ByteArrayOutputStream()
        lateinit var peer: PhoneIpSyncSession
        peer = PhoneIpSyncSession("watch", "fixture", IpTransportKind.WIFI_LAN, "192.0.2.1", { bytes ->
            pending.write(bytes)
            val wire = pending.toByteArray()
            if (wire.size >= 4 && wire.size == java.nio.ByteBuffer.wrap(wire).int + 4) {
                pending.reset()
                val request = BluetoothSyncProtocol.readFrame(ByteArrayInputStream(wire))
                reply(request, discovery, "ip", drop = false)?.let { answer ->
                    val output = ByteArrayOutputStream()
                    BluetoothSyncProtocol.writeFrame(output, answer)
                    peer.acceptBinary(output.toByteArray())
                }
            }
        }, {})
        PhoneIpSyncSessionRegistry.register(peer)
    }
}
