package com.lightningstudio.watchrss.phone.connection.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.lightningstudio.watchrss.phone.connection.ip.PhoneIpSyncSession
import com.lightningstudio.watchrss.phone.connection.ip.PhoneIpSyncSessionRegistry
import com.lightningstudio.watchrss.phone.PhoneCompanionApplication
import com.lightningstudio.watchrss.phone.data.log.BluetoothDebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.resume

data class BluetoothSyncExchange(
    val deviceName: String,
    val deviceAddress: String,
    val request: JSONObject,
    val response: JSONObject
)

data class BluetoothLibrarySyncExchange(
    val deviceName: String,
    val deviceAddress: String,
    val cursorResponse: JSONObject? = null,
    val request: JSONObject,
    val manifestResponse: JSONObject,
    val articleRequestFrameCount: Int,
    val responseFrameCount: Int,
    val response: JSONObject
)

private data class LibraryFrameStats(
    val frameCount: Int,
    val totalBytes: Long,
    val totalWireBytes: Long,
    val maxFrameBytes: Int,
    val articleCount: Int
)

private data class LibraryResponseRead(
    val response: JSONObject,
    val stats: LibraryFrameStats
)

private class PreviewStreamStats {
    private var windowStartedAt = SystemClock.elapsedRealtime()
    private var sentFrames = 0
    private var sentBytes = 0L
    private var sendElapsedMs = 0L
    private var receivedFrames = 0
    private var receivedBytes = 0L
    private var receiveElapsedMs = 0L

    @Synchronized
    fun recordSend(bytes: Long, elapsedMs: Long): Map<String, Any?>? {
        sentFrames += 1
        sentBytes += bytes
        sendElapsedMs += elapsedMs
        return snapshotIfDue()
    }

    @Synchronized
    fun recordReceive(bytes: Long, elapsedMs: Long): Map<String, Any?>? {
        receivedFrames += 1
        receivedBytes += bytes
        receiveElapsedMs += elapsedMs
        return snapshotIfDue()
    }

    private fun snapshotIfDue(): Map<String, Any?>? {
        val now = SystemClock.elapsedRealtime()
        val durationMs = now - windowStartedAt
        if (durationMs < REPORT_INTERVAL_MS) return null
        val snapshot = mapOf(
            "durationMs" to durationMs,
            "sentFrames" to sentFrames,
            "sentFps" to framesPerSecond(sentFrames, durationMs),
            "sentBytes" to sentBytes,
            "averageSendMs" to average(sendElapsedMs, sentFrames),
            "receivedFrames" to receivedFrames,
            "receivedFps" to framesPerSecond(receivedFrames, durationMs),
            "receivedBytes" to receivedBytes,
            "averageReceiveMs" to average(receiveElapsedMs, receivedFrames)
        )
        windowStartedAt = now
        sentFrames = 0
        sentBytes = 0L
        sendElapsedMs = 0L
        receivedFrames = 0
        receivedBytes = 0L
        receiveElapsedMs = 0L
        return snapshot
    }

    private fun framesPerSecond(frames: Int, durationMs: Long): Double =
        if (durationMs <= 0L) 0.0 else frames * 1_000.0 / durationMs

    private fun average(total: Long, count: Int): Double =
        if (count <= 0) 0.0 else total.toDouble() / count

    private companion object {
        const val REPORT_INTERVAL_MS = 1_000L
    }
}

data class PhoneBluetoothWatchDevice(
    val name: String,
    val address: String,
    val uuidCount: Int,
    val remoteDeviceId: String = "",
    val bluetoothAddress: String = address,
    val supportsPersistentSession: Boolean = false
) {
    val readerPreviewAddress: String
        get() = bluetoothAddress.ifBlank { address }
}

data class PhoneBluetoothWatchProbeResult(
    val device: PhoneBluetoothWatchDevice,
    val reachable: Boolean,
    val message: String? = null,
    val capabilities: PhoneWatchCapabilities? = null
)

internal data class PhoneBluetoothWatchProbeBatch(
    val results: List<PhoneBluetoothWatchProbeResult>,
    val sessionLease: PhoneSyncSession? = null
)

data class PhoneWatchVideoDecoder(
    val name: String,
    val mime: String,
    val hardwareAccelerated: Boolean,
    val maxWidth: Int,
    val maxHeight: Int,
    val maxFrameRate: Double,
    val profileLevels: List<Pair<Int, Int>>
)

data class PhoneWatchCapabilities(
    val widthPx: Int,
    val heightPx: Int,
    val refreshRateHz: Double,
    val availableBytes: Long,
    val videoDecoders: List<PhoneWatchVideoDecoder>
) {
    val supportsReaderPresetSync: Boolean
        get() = widthPx > 0 && heightPx > 0
}

private data class ProbeIdentity(
    val deviceId: String,
    val capabilities: PhoneWatchCapabilities?,
    val ipUpgradeAccepted: Boolean,
    val supportsPersistentSession: Boolean,
    val persistentSessionAccepted: Boolean,
    val session: PhoneSyncSession? = null
)

private data class ProbedWatch(
    val result: PhoneBluetoothWatchProbeResult,
    val sessionLease: PhoneSyncSession? = null
)

internal class PhoneSyncSession internal constructor(
    internal val client: PhoneBluetoothSyncClient,
    device: PhoneBluetoothWatchDevice,
    internal val localDeviceId: String,
    internal var transport: Transport?,
    internal var persistentAccepted: Boolean,
    internal var negotiationPending: Boolean,
    internal var ipUpgradeExpected: Boolean
) : Closeable {
    var device: PhoneBluetoothWatchDevice = device
        internal set

    internal class Transport(
        val inputStream: InputStream,
        val outputStream: OutputStream,
        val owner: String,
        val closeOnLegacyFallback: Boolean,
        private val closeTransport: () -> Unit
    ) : Closeable {
        private var closed = false

        val isClosed: Boolean
            get() = closed

        override fun close() {
            if (closed) return
            closed = true
            closeTransport()
        }
    }

    internal var legacyFallback: Boolean = false
    internal val recoveryGate = SingleSessionRecoveryGate()
    private var closed: Boolean = false

    val isPersistent: Boolean
        get() = persistentAccepted && !closed

    suspend fun exchange(request: JSONObject, sessionId: String): BluetoothSyncExchange =
        client.exchangeInSession(this, request, sessionId)

    suspend fun exchangeLibrary(
        cursorRequest: JSONObject,
        buildManifestRequest: suspend (String, JSONObject?) -> JSONObject,
        buildArticleRequests: suspend (JSONObject, Boolean) -> List<JSONObject>,
        sessionId: String,
        onProgress: (PhoneBluetoothSyncProgress) -> Unit,
        applyResponse: suspend (BluetoothLibrarySyncExchange) -> Unit
    ): BluetoothLibrarySyncExchange = client.exchangeLibraryInSession(
        session = this,
        cursorRequest = cursorRequest,
        buildManifestRequest = buildManifestRequest,
        buildArticleRequests = buildArticleRequests,
        sessionId = sessionId,
        onProgress = onProgress,
        applyResponse = applyResponse
    )

    suspend fun complete(sessionId: String) {
        client.finishSession(this, BluetoothSyncProtocol.SESSION_PHASE_COMPLETE, sessionId)
    }

    suspend fun abort(sessionId: String) {
        client.finishSession(this, BluetoothSyncProtocol.SESSION_PHASE_ABORT, sessionId)
    }

    internal fun requestForExchange(request: JSONObject): JSONObject {
        if (!negotiationPending) return request
        return BluetoothSyncProtocol.withPersistentSessionRequest(request)
    }

    internal fun recordNegotiation(response: JSONObject) {
        if (!negotiationPending) return
        negotiationPending = false
        persistentAccepted = BluetoothSyncProtocol.acceptsPersistentSession(response)
        if (!persistentAccepted) {
            legacyFallback = true
            transport?.takeIf { it.closeOnLegacyFallback }?.close()
            if (transport?.isClosed == true) transport = null
        }
    }

    internal fun replaceWith(replacement: PhoneSyncSession) {
        transport?.close()
        transport = replacement.transport
        replacement.transport = null
        persistentAccepted = replacement.persistentAccepted
        negotiationPending = replacement.negotiationPending
        legacyFallback = replacement.legacyFallback
        ipUpgradeExpected = replacement.ipUpgradeExpected
        device = replacement.device
    }

    override fun close() {
        if (closed) return
        closed = true
        transport?.close()
        transport = null
    }
}

class PhoneBluetoothSyncClient(
    private val context: Context,
    private val debugLog: BluetoothDebugLog
) {
    private val capabilitiesByAddress = mutableMapOf<String, PhoneWatchCapabilities>()
    private val connectionMutex = Mutex()

    fun capabilitiesFor(deviceAddress: String): PhoneWatchCapabilities? =
        synchronized(capabilitiesByAddress) { capabilitiesByAddress[deviceAddress] }
    @SuppressLint("MissingPermission")
    internal suspend fun probeLibrarySyncDevices(
        deviceId: String,
        sessionId: String = BluetoothDebugLog.newSessionId("bt-library-probe"),
        perDeviceTimeoutMs: Long = DEFAULT_DEVICE_PROBE_TIMEOUT_MS,
        onProbe: (completed: Int, total: Int, result: PhoneBluetoothWatchProbeResult) -> Unit = { _, _, _ -> }
    ): PhoneBluetoothWatchProbeBatch {
        val startedAt = SystemClock.elapsedRealtime()
        debugLog.appendEvent(
            event = "bt.library.probe.start",
            sessionId = sessionId,
            fields = mapOf("perDeviceTimeoutMs" to perDeviceTimeoutMs)
        )
        val previousIpSessions = PhoneIpSyncSessionRegistry.activeSessions()
        if (previousIpSessions.isNotEmpty()) {
            debugLog.appendEvent(
                event = "ip.library.probe.sessions.invalidated",
                sessionId = sessionId,
                fields = mapOf("count" to previousIpSessions.size)
            )
            // A Bluetooth-proxy WebSocket can remain locally open after the watch has already
            // reclaimed its background route. A manual sync therefore always revalidates the
            // paired watch over RFCOMM and obtains a fresh signed IP endpoint descriptor.
            PhoneIpSyncSessionRegistry.closeAll()
        }
        requireBluetoothConnectPermission()
        val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
            ?: error("此设备没有蓝牙适配器")
        val adapter = bluetoothManager.adapter ?: error("此设备没有蓝牙适配器")
        require(adapter.isEnabled) { "蓝牙未开启" }

        val bondedDevices = adapter.bondedDevices.orEmpty()
        logAdapterSnapshot(sessionId, adapter, bondedDevices)
        val activeWatchAddresses = activeWatchDeviceAddresses(
            bluetoothManager = bluetoothManager,
            adapter = adapter,
            bondedDevices = bondedDevices,
            sessionId = sessionId
        )
        val allCandidates = probeCandidateWatchDevices(
            devices = bondedDevices,
            activeWatchAddresses = activeWatchAddresses
        )
        val activeCandidates = allCandidates.filter { device ->
            device.address.uppercase() in activeWatchAddresses
        }
        val cachedAddress = cachedDeviceAddress()
        val cachedCandidates = allCandidates.filter { device ->
            cachedAddress != null && device.address.equals(cachedAddress, ignoreCase = true)
        }
        val prioritizedCandidates = (activeCandidates + cachedCandidates)
            .distinctBy { it.address.uppercase() }
        val probeWindow = rotatingProbeCandidateWindow(
            candidates = allCandidates,
            maxCandidates = MAX_DEVICE_PROBE_CANDIDATES,
            startOffset = probeCandidateOffset(),
            prioritizedCandidates = prioritizedCandidates
        )
        rememberProbeCandidateOffset(probeWindow.nextOffset)
        val candidates = probeWindow.candidates
        debugLog.appendEvent(
            event = "bt.library.probe.candidates",
            sessionId = sessionId,
            fields = mapOf(
                "candidates" to allCandidates.size,
                "probedCandidates" to candidates.size,
                "skippedCandidates" to (allCandidates.size - candidates.size).coerceAtLeast(0),
                "startOffset" to probeWindow.startOffset,
                "nextOffset" to probeWindow.nextOffset,
                "cachedAddress" to cachedAddress.orEmpty(),
                "activeCandidates" to activeCandidates.size,
                "activeAddresses" to activeCandidates.joinToString(",") { it.address },
                "prioritizedAddresses" to prioritizedCandidates.joinToString(",") { it.address },
                "probeOrder" to candidates.joinToString(",") { it.address }
            )
        )
        if (candidates.isEmpty()) {
            debugLog.appendEvent(
                event = "bt.library.probe.complete",
                sessionId = sessionId,
                fields = mapOf(
                    "candidates" to 0,
                    "reachable" to 0,
                    "elapsedMs" to elapsedSince(startedAt)
                )
            )
            return PhoneBluetoothWatchProbeBatch(emptyList())
        }

        val results = mutableListOf<PhoneBluetoothWatchProbeResult>()
        var sessionLease: PhoneSyncSession? = null
        var stoppedAfterPrioritizedReachable = false
        for ((index, device) in candidates.withIndex()) {
            val retainSession = shouldRetainProbeSession(
                candidateCount = candidates.size,
                candidateAddress = device.address,
                activeWatchAddresses = activeWatchAddresses,
                cachedWatchAddress = cachedAddress
            )
            val probed = probeLibrarySyncDevice(
                device = device,
                deviceId = deviceId,
                sessionId = "$sessionId-${index + 1}",
                timeoutMs = perDeviceTimeoutMs,
                retainSession = retainSession
            )
            val result = probed.result
            results += result
            sessionLease = probed.sessionLease ?: sessionLease
            onProbe(index + 1, candidates.size, result)
            if (
                shouldStopAfterPrioritizedWatchProbe(
                    candidateAddress = device.address,
                    activeWatchAddresses = activeWatchAddresses,
                    cachedWatchAddress = cachedAddress,
                    reachable = result.reachable
                )
            ) {
                stoppedAfterPrioritizedReachable = true
                debugLog.appendEvent(
                    event = "bt.library.probe.prioritized-reachable-stop",
                    sessionId = sessionId,
                    fields = deviceFields(device) + mapOf(
                        "active" to (device.address.uppercase() in activeWatchAddresses),
                        "cached" to device.address.equals(cachedAddress, ignoreCase = true),
                        "attemptedCandidates" to results.size,
                        "skippedCandidates" to (candidates.size - results.size).coerceAtLeast(0)
                    )
                )
                break
            }
        }
        debugLog.appendEvent(
            event = "bt.library.probe.complete",
            sessionId = sessionId,
            fields = mapOf(
                "candidates" to candidates.size,
                "attemptedCandidates" to results.size,
                "reachable" to results.count { it.reachable },
                "stoppedAfterPrioritizedReachable" to stoppedAfterPrioritizedReachable,
                "elapsedMs" to elapsedSince(startedAt)
            )
        )
        if (shouldReleaseProbeSession(results.count { it.reachable })) {
            sessionLease?.runCatching {
                complete("$sessionId-probe-release")
            }
            sessionLease?.close()
            sessionLease = null
        }
        return PhoneBluetoothWatchProbeBatch(results, sessionLease)
    }

    @SuppressLint("MissingPermission")
    suspend fun exchange(
        request: JSONObject,
        deviceAddress: String? = null,
        deviceNameHint: String? = null,
        sessionId: String = BluetoothDebugLog.newSessionId("bt-quick"),
        rememberDeviceOnSuccess: Boolean = true
    ): BluetoothSyncExchange = connectionMutex.withLock {
        exchangeUnlocked(
            request = request,
            deviceAddress = deviceAddress,
            deviceNameHint = deviceNameHint,
            sessionId = sessionId,
            rememberDeviceOnSuccess = rememberDeviceOnSuccess
        )
    }

    @SuppressLint("MissingPermission")
    internal suspend fun openPersistentSession(
        device: PhoneBluetoothWatchDevice,
        localDeviceId: String,
        sessionId: String = BluetoothDebugLog.newSessionId("sync-session-open")
    ): PhoneSyncSession? {
        if (!device.supportsPersistentSession) return null
        requireBluetoothConnectPermission()
        val adapter = context.getSystemService(BluetoothManager::class.java)
            ?.adapter
            ?: error("此设备没有蓝牙适配器")
        require(adapter.isEnabled) { "蓝牙未开启" }
        val bluetoothDevice = selectBondedWatchDevice(
            devices = adapter.bondedDevices.orEmpty(),
            deviceAddress = device.bluetoothAddress.ifBlank {
                device.address.takeUnless { it.startsWith(PhoneIpSyncSessionRegistry.IP_DEVICE_PREFIX) }
            },
            deviceNameHint = device.name
        )
        cancelDiscoveryLogged(adapter, sessionId)
        val identity = connectionMutex.withLock {
            probeDirectWithPersistentSessionSdpRecovery(
                device = bluetoothDevice,
                deviceId = localDeviceId,
                sessionId = sessionId
            )
        } ?: return null
        val rfcommSession = identity.session ?: return null
        val ipSession = identity.takeIf { it.ipUpgradeAccepted && it.deviceId.isNotBlank() }
            ?.let { awaitIpSession(it.deviceId, IP_UPGRADE_WAIT_MS) }
        val session = if (ipSession != null) {
            runCatching { rfcommSession.complete("$sessionId-rfcomm-upgrade") }
            rfcommSession.close()
            createIpPhoneSyncSession(bluetoothDevice, localDeviceId, identity, ipSession)
        } else {
            rfcommSession
        }
        rememberSuccessfulDevice(bluetoothDevice, sessionId)
        debugLog.appendEvent(
            event = "sync.session.opened",
            sessionId = sessionId,
            fields = mapOf(
                "deviceAddress" to session.device.address,
                "bluetoothAddress" to session.device.bluetoothAddress,
                "persistentAccepted" to session.persistentAccepted,
                "negotiationPending" to session.negotiationPending
            )
        )
        return session
    }

    internal suspend fun promoteLateIpSession(
        session: PhoneSyncSession,
        sessionId: String
    ): PhoneSyncSession {
        val remoteDeviceId = pendingLateIpUpgradeDeviceId(
            ipUpgradeExpected = session.ipUpgradeExpected,
            remoteDeviceId = session.device.remoteDeviceId,
            transportOwner = session.transport?.owner
        ) ?: return session
        debugLog.appendEvent(
            event = "sync.session.ip-upgrade.recheck",
            sessionId = sessionId,
            fields = mapOf(
                "bluetoothAddress" to session.device.bluetoothAddress,
                "remoteDeviceId" to remoteDeviceId,
                "waitMs" to LATE_IP_UPGRADE_WAIT_MS
            )
        )
        val ipSession = awaitIpSession(remoteDeviceId, LATE_IP_UPGRADE_WAIT_MS)
        if (ipSession == null) {
            debugLog.appendEvent(
                event = "sync.session.ip-upgrade.recheck-miss",
                sessionId = sessionId,
                fields = mapOf(
                    "bluetoothAddress" to session.device.bluetoothAddress,
                    "remoteDeviceId" to remoteDeviceId
                )
            )
            return session
        }
        debugLog.appendEvent(
            event = "sync.session.ip-upgrade.late-takeover",
            sessionId = sessionId,
            fields = mapOf(
                "bluetoothAddress" to session.device.bluetoothAddress,
                "remoteDeviceId" to remoteDeviceId,
                "route" to ipSession.routeKind.wireName,
                "remoteAddress" to ipSession.remoteAddress
            )
        )
        runCatching { session.complete("$sessionId-rfcomm-upgrade") }
            .onFailure { throwable ->
                debugLog.appendEvent(
                    event = "sync.session.ip-upgrade.rfcomm-complete-failed",
                    sessionId = sessionId,
                    fields = failureFields(throwable) + mapOf(
                        "bluetoothAddress" to session.device.bluetoothAddress,
                        "remoteDeviceId" to remoteDeviceId
                    ),
                    throwable = throwable
                )
            }
        session.close()
        return try {
            createIpPhoneSyncSession(
                device = session.device,
                localDeviceId = session.localDeviceId,
                ipSession = ipSession
            )
        } catch (throwable: Throwable) {
            ipSession.close()
            throw throwable
        }
    }

    internal suspend fun exchangeInSession(
        session: PhoneSyncSession,
        request: JSONObject,
        sessionId: String
    ): BluetoothSyncExchange {
        if (session.legacyFallback) {
            return exchange(
                request = request,
                deviceAddress = session.device.address,
                deviceNameHint = session.device.name,
                sessionId = sessionId
            )
        }
        return executeSessionOperation(session, sessionId, request.optString("action")) {
            connectionMutex.withLock {
                val transport = session.transport ?: error("持久同步连接已关闭")
                val wireRequest = session.requestForExchange(request)
                writeFrameLogged(transport.outputStream, sessionId, "sessionRequest", wireRequest)
                val response = readFrameLogged(transport.inputStream, sessionId, "sessionResponse")
                val ackFailure = writeResponseAck(
                    transport.outputStream,
                    sessionId,
                    success = true,
                    applied = true
                )
                session.recordNegotiation(response)
                ackFailure?.let { throwable ->
                    invalidateSessionTransportAfterCommittedResponse(
                        session = session,
                        sessionId = sessionId,
                        action = request.optString("action"),
                        throwable = throwable
                    )
                }
                BluetoothSyncExchange(
                    deviceName = session.device.name,
                    deviceAddress = session.device.address,
                    request = request,
                    response = response
                )
            }
        }
    }

    internal suspend fun exchangeLibraryInSession(
        session: PhoneSyncSession,
        cursorRequest: JSONObject,
        buildManifestRequest: suspend (String, JSONObject?) -> JSONObject,
        buildArticleRequests: suspend (JSONObject, Boolean) -> List<JSONObject>,
        sessionId: String,
        onProgress: (PhoneBluetoothSyncProgress) -> Unit,
        applyResponse: suspend (BluetoothLibrarySyncExchange) -> Unit
    ): BluetoothLibrarySyncExchange {
        if (session.legacyFallback) {
            return exchangeLibrary(
                cursorRequest = cursorRequest,
                buildManifestRequest = buildManifestRequest,
                buildArticleRequests = buildArticleRequests,
                deviceAddress = session.device.address,
                deviceNameHint = session.device.name,
                sessionId = sessionId,
                onProgress = onProgress,
                applyResponse = applyResponse
            )
        }
        return executeSessionOperation(
            session = session,
            sessionId = sessionId,
            action = BluetoothSyncProtocol.ACTION_SYNC_LIBRARY
        ) {
            connectionMutex.withLock {
                exchangeLibraryOnSessionTransport(
                    session = session,
                    cursorRequest = cursorRequest,
                    buildManifestRequest = buildManifestRequest,
                    buildArticleRequests = buildArticleRequests,
                    sessionId = sessionId,
                    onProgress = onProgress,
                    applyResponse = applyResponse
                )
            }
        }
    }

    internal suspend fun finishSession(
        session: PhoneSyncSession,
        phase: String,
        sessionId: String
    ) = withContext(Dispatchers.IO) {
        if (session.legacyFallback || session.transport == null) {
            session.close()
            return@withContext
        }
        try {
            connectionMutex.withLock {
                val transport = session.transport ?: return@withLock
                val request = session.requestForExchange(
                    BluetoothSyncProtocol.buildSessionControlRequest(
                        version = LibrarySyncPayload.PROTOCOL_VERSION,
                        phase = phase
                    )
                )
                writeFrameLogged(transport.outputStream, sessionId, "sessionFinishRequest", request)
                val response = readFrameLogged(
                    transport.inputStream,
                    sessionId,
                    "sessionFinishResponse"
                )
                require(response.optBoolean("success", false)) {
                    response.optString("message").ifBlank { "手表未确认结束同步会话" }
                }
                require(response.optString("action") == BluetoothSyncProtocol.ACTION_SYNC_SESSION) {
                    "手表返回了错误的同步会话结束响应"
                }
                require(response.optString("phase") == phase) { "手表同步会话结束阶段不匹配" }
                writeResponseAck(transport.outputStream, sessionId, success = true, applied = true)
                session.recordNegotiation(response)
            }
        } finally {
            debugLog.appendEvent(
                event = "sync.session.closed",
                sessionId = sessionId,
                fields = mapOf("phase" to phase, "deviceAddress" to session.device.address)
            )
            session.close()
        }
    }

    private suspend fun <T> executeSessionOperation(
        session: PhoneSyncSession,
        sessionId: String,
        action: String,
        block: suspend () -> T
    ): T {
        val cancellationHandle = installSessionCancellationLogger(session, sessionId, action)
        try {
            return block()
        } catch (throwable: Throwable) {
            if (
                throwable is CancellationException ||
                !isSessionTransportFailure(throwable) ||
                !session.recoveryGate.tryAcquire()
            ) {
                throw throwable
            }
            debugLog.appendEvent(
                event = "sync.session.recover",
                sessionId = sessionId,
                fields = failureFields(throwable) + mapOf("action" to action),
                throwable = throwable
            )
            session.transport?.close()
            session.transport = null
            val replacement = openPersistentSession(
                device = session.device,
                localDeviceId = session.localDeviceId,
                sessionId = "$sessionId-recovery"
            ) ?: throw throwable
            session.replaceWith(replacement)
            return block()
        } finally {
            cancellationHandle?.dispose()
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun streamReaderPresetPreview(
        initialRequest: JSONObject,
        deviceAddress: String,
        sessionId: String,
        nextRequest: suspend () -> JSONObject,
        onResponse: (deviceName: String, response: JSONObject) -> Unit
    ): BluetoothSyncExchange = connectionMutex.withLock {
        requireBluetoothConnectPermission()
        val adapter = context.getSystemService(BluetoothManager::class.java)
            ?.adapter
            ?: error("此设备没有蓝牙适配器")
        require(adapter.isEnabled) { "蓝牙未开启" }
        val device = selectBondedWatchDevice(
            devices = adapter.bondedDevices.orEmpty(),
            deviceAddress = deviceAddress,
            deviceNameHint = null
        )
        cancelDiscoveryLogged(adapter, sessionId)
        var socket: BluetoothSocket? = null
        val cancellationHandle = installSocketCancellationLogger(
            sessionId = sessionId,
            owner = "readerPreviewStream",
            socketProvider = { socket }
        )
        try {
            socket = device.createRfcommSocketToServiceRecord(BluetoothSyncProtocol.SERVICE_UUID)
            connectLogged(socket, sessionId, device)
            var request = initialRequest
            writeFrameLogged(socket, sessionId, "previewFrame", request)
            var response = readFrameLogged(socket, sessionId, "previewResponse")
            require(response.optBoolean("success")) {
                response.optString("message").ifBlank { "手表实时预览失败" }
            }
            onResponse(device.name.orEmpty(), response)
            coroutineScope {
                val previewStats = PreviewStreamStats()
                val sender = launch {
                    var lastWriteAt = SystemClock.elapsedRealtime()
                    while (true) {
                        request = nextRequest()
                        val remaining = PREVIEW_MIN_FRAME_INTERVAL_MS -
                            (SystemClock.elapsedRealtime() - lastWriteAt)
                        if (remaining > 0L) delay(remaining)
                        writePreviewFrame(socket, sessionId, request, previewStats)
                        lastWriteAt = SystemClock.elapsedRealtime()
                        val phase = request.optString("phase")
                        if (
                            phase == ReaderPresetPreviewPayload.PHASE_STOP ||
                            phase == ReaderPresetPreviewPayload.PHASE_RESOURCE_HANDOFF
                        ) {
                            return@launch
                        }
                    }
                }
                val receiver = launch {
                    while (true) {
                        response = readPreviewFrame(socket, sessionId, previewStats)
                        require(response.optBoolean("success")) {
                            response.optString("message").ifBlank { "手表实时预览失败" }
                        }
                        onResponse(device.name.orEmpty(), response)
                        val phase = response.optString("phase")
                        if (
                            phase == ReaderPresetPreviewPayload.PHASE_STOP ||
                            phase == ReaderPresetPreviewPayload.PHASE_RESOURCE_HANDOFF
                        ) {
                            return@launch
                        }
                    }
                }
                sender.invokeOnCompletion { cause ->
                    if (cause != null) runCatching { socket.close() }
                }
                receiver.invokeOnCompletion { cause ->
                    if (cause != null) runCatching { socket.close() }
                }
                joinAll(sender, receiver)
            }
            rememberSuccessfulDevice(device, sessionId)
            BluetoothSyncExchange(
                deviceName = device.name.orEmpty(),
                deviceAddress = device.address,
                request = request,
                response = response
            )
        } finally {
            cancellationHandle?.dispose()
            socket?.let { closeSocketLogged(it, sessionId, "readerPreviewStream") }
        }
    }

    private fun writePreviewFrame(
        socket: BluetoothSocket,
        sessionId: String,
        payload: JSONObject,
        stats: PreviewStreamStats
    ) {
        val startedAt = SystemClock.elapsedRealtime()
        var transferredBytes = 0L
        try {
            BluetoothSyncProtocol.writeFrame(socket.outputStream, payload) { bytes ->
                transferredBytes += bytes
            }
            stats.recordSend(
                bytes = transferredBytes,
                elapsedMs = elapsedSince(startedAt)
            )?.let { fields ->
                debugLog.appendEvent("bt.preview.stream.stats", sessionId, fields)
            }
        } catch (throwable: Throwable) {
            debugLog.appendEvent(
                event = "bt.preview.frame.write.failed",
                sessionId = sessionId,
                fields = failureFields(throwable),
                throwable = throwable
            )
            throw throwable
        }
    }

    private fun readPreviewFrame(
        socket: BluetoothSocket,
        sessionId: String,
        stats: PreviewStreamStats
    ): JSONObject {
        val startedAt = SystemClock.elapsedRealtime()
        var transferredBytes = 0L
        return try {
            BluetoothSyncProtocol.readFrame(socket.inputStream) { bytes ->
                transferredBytes += bytes
            }.also { payload ->
                stats.recordReceive(
                    bytes = transferredBytes,
                    elapsedMs = elapsedSince(startedAt)
                )?.let { fields ->
                    debugLog.appendEvent("bt.preview.stream.stats", sessionId, fields)
                }
            }
        } catch (throwable: Throwable) {
            debugLog.appendEvent(
                event = "bt.preview.frame.read.failed",
                sessionId = sessionId,
                fields = failureFields(throwable),
                throwable = throwable
            )
            throw throwable
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun exchangeUnlocked(
        request: JSONObject,
        deviceAddress: String?,
        deviceNameHint: String?,
        sessionId: String,
        rememberDeviceOnSuccess: Boolean
    ): BluetoothSyncExchange {
        val ipSession = PhoneIpSyncSessionRegistry.session(deviceAddress)
        if (ipSession != null) {
            try {
                return exchangeIpUnlocked(ipSession, request, sessionId)
            } catch (throwable: Throwable) {
                if (throwable is CancellationException || !isIpTransportFailure(throwable)) throw throwable
                debugLog.appendEvent(
                    event = "ip.exchange.fallback.bluetooth",
                    sessionId = sessionId,
                    fields = failureFields(throwable)
                )
            }
        }
        val bluetoothDeviceAddress = deviceAddress
            ?.takeUnless { it.startsWith(PhoneIpSyncSessionRegistry.IP_DEVICE_PREFIX) }
        val totalStartedAt = SystemClock.elapsedRealtime()
        var socket: BluetoothSocket? = null
        var selectedDevice: BluetoothDevice? = null
        val cancellationHandle = installSocketCancellationLogger(
            sessionId = sessionId,
            owner = "exchange",
            socketProvider = { socket }
        )
        debugLog.appendEvent(
            event = "bt.exchange.start",
            sessionId = sessionId,
            fields = payloadFields("request", request) + mapOf(
                "uuid" to BluetoothSyncProtocol.SERVICE_UUID,
                "deviceNameHint" to deviceNameHint.orEmpty(),
                "targetAddress" to bluetoothDeviceAddress.orEmpty(),
                "ipFallback" to (deviceAddress != bluetoothDeviceAddress)
            )
        )
        try {
            requireBluetoothConnectPermission()
            val adapter = context.getSystemService(BluetoothManager::class.java)
                ?.adapter
                ?: error("此设备没有蓝牙适配器")
            require(adapter.isEnabled) { "蓝牙未开启" }

            val bondedDevices = adapter.bondedDevices.orEmpty()
            logAdapterSnapshot(sessionId, adapter, bondedDevices)
            val device = selectBondedWatchDevice(
                devices = bondedDevices,
                deviceAddress = bluetoothDeviceAddress,
                deviceNameHint = deviceNameHint
            )
            selectedDevice = device
            debugLog.appendEvent("bt.device.selected", sessionId, deviceFields(device))
            Log.i(TAG, "connecting to name=${device.name} address=${device.address} uuid=${BluetoothSyncProtocol.SERVICE_UUID}")
            cancelDiscoveryLogged(adapter, sessionId)

            val createStartedAt = SystemClock.elapsedRealtime()
            socket = device.createRfcommSocketToServiceRecord(BluetoothSyncProtocol.SERVICE_UUID)
            debugLog.appendEvent(
                event = "bt.socket.create.success",
                sessionId = sessionId,
                fields = deviceFields(device) + mapOf("elapsedMs" to elapsedSince(createStartedAt))
            )
            connectLogged(socket, sessionId, device)
            writeFrameLogged(socket, sessionId, "request", request)
            val response = readFrameLogged(socket, sessionId, "response")
            writeResponseAck(socket, sessionId, success = true, applied = true)
            if (rememberDeviceOnSuccess) {
                rememberSuccessfulDevice(device, sessionId)
            }
            Log.i(TAG, "exchange complete response=$response")
            debugLog.appendEvent(
                event = "bt.exchange.complete",
                sessionId = sessionId,
                fields = deviceFields(device) + payloadFields("response", response) +
                    mapOf("elapsedMs" to elapsedSince(totalStartedAt))
            )
            return BluetoothSyncExchange(
                deviceName = device.name.orEmpty(),
                deviceAddress = device.address,
                request = request,
                response = response
            )
        } catch (throwable: Throwable) {
            debugLog.appendEvent(
                event = "bt.exchange.failed",
                sessionId = sessionId,
                fields = payloadFields("request", request) + (selectedDevice?.let { deviceFields(it) } ?: emptyMap()) +
                    mapOf(
                        "elapsedMs" to elapsedSince(totalStartedAt),
                        "errorClass" to throwable::class.java.name,
                        "message" to throwable.message.orEmpty()
                    ),
                throwable = throwable
            )
            throw throwable
        } finally {
            cancellationHandle?.dispose()
            socket?.let { closeSocketLogged(it, sessionId, "exchange") }
        }
    }

    private suspend fun exchangeIpUnlocked(
        ipSession: PhoneIpSyncSession,
        request: JSONObject,
        sessionId: String
    ): BluetoothSyncExchange {
        val startedAt = SystemClock.elapsedRealtime()
        val cancellationHandle = installIpCancellationLogger(ipSession, sessionId, "exchange")
        debugLog.appendEvent(
            event = "ip.exchange.start",
            sessionId = sessionId,
            fields = payloadFields("request", request) + mapOf(
                "watchDeviceId" to ipSession.watchDeviceId,
                "route" to ipSession.routeKind.wireName
            )
        )
        try {
            writeFrameLogged(ipSession.outputStream, sessionId, "request", request)
            val response = readFrameLogged(ipSession.inputStream, sessionId, "response")
            writeResponseAck(ipSession.outputStream, sessionId, success = true, applied = true)
            debugLog.appendEvent(
                event = "ip.exchange.complete",
                sessionId = sessionId,
                fields = payloadFields("response", response) +
                    mapOf("elapsedMs" to elapsedSince(startedAt))
            )
            return BluetoothSyncExchange(
                deviceName = "WatchRSS 手表",
                deviceAddress = PhoneIpSyncSessionRegistry.IP_DEVICE_PREFIX + ipSession.watchDeviceId,
                request = request,
                response = response
            )
        } catch (throwable: Throwable) {
            ipSession.close()
            debugLog.appendEvent(
                event = "ip.exchange.failed",
                sessionId = sessionId,
                fields = failureFields(throwable),
                throwable = throwable
            )
            throw throwable
        } finally {
            cancellationHandle?.dispose()
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun exchangeLibrary(
        cursorRequest: JSONObject? = null,
        manifestRequest: JSONObject? = null,
        buildManifestRequest: (suspend (String, JSONObject?) -> JSONObject)? = null,
        buildArticleRequests: suspend (JSONObject, Boolean) -> List<JSONObject>,
        deviceAddress: String? = null,
        deviceNameHint: String? = null,
        sessionId: String = BluetoothDebugLog.newSessionId("bt-library"),
        onProgress: (PhoneBluetoothSyncProgress) -> Unit = {},
        applyResponse: suspend (BluetoothLibrarySyncExchange) -> Unit = {},
        ackApplied: Boolean = true,
        rememberDeviceOnSuccess: Boolean = true
    ): BluetoothLibrarySyncExchange = connectionMutex.withLock {
        exchangeLibraryUnlocked(
            cursorRequest = cursorRequest,
            manifestRequest = manifestRequest,
            buildManifestRequest = buildManifestRequest,
            buildArticleRequests = buildArticleRequests,
            deviceAddress = deviceAddress,
            deviceNameHint = deviceNameHint,
            sessionId = sessionId,
            onProgress = onProgress,
            applyResponse = applyResponse,
            ackApplied = ackApplied,
            rememberDeviceOnSuccess = rememberDeviceOnSuccess
        )
    }

    @SuppressLint("MissingPermission")
    private suspend fun exchangeLibraryUnlocked(
        cursorRequest: JSONObject?,
        manifestRequest: JSONObject?,
        buildManifestRequest: (suspend (String, JSONObject?) -> JSONObject)?,
        buildArticleRequests: suspend (JSONObject, Boolean) -> List<JSONObject>,
        deviceAddress: String?,
        deviceNameHint: String?,
        sessionId: String,
        onProgress: (PhoneBluetoothSyncProgress) -> Unit,
        applyResponse: suspend (BluetoothLibrarySyncExchange) -> Unit,
        ackApplied: Boolean,
        rememberDeviceOnSuccess: Boolean
    ): BluetoothLibrarySyncExchange {
        val ipSession = PhoneIpSyncSessionRegistry.session(deviceAddress)
        if (ipSession != null) {
            try {
                return exchangeLibraryIpUnlocked(
                    ipSession = ipSession,
                    cursorRequest = cursorRequest,
                    manifestRequest = manifestRequest,
                    buildManifestRequest = buildManifestRequest,
                    buildArticleRequests = buildArticleRequests,
                    sessionId = sessionId,
                    onProgress = onProgress,
                    applyResponse = applyResponse,
                    ackApplied = ackApplied
                )
            } catch (throwable: Throwable) {
                if (throwable is CancellationException || !isIpTransportFailure(throwable)) throw throwable
                debugLog.appendEvent(
                    event = "ip.library.fallback.bluetooth",
                    sessionId = sessionId,
                    fields = failureFields(throwable)
                )
            }
        }
        val bluetoothDeviceAddress = deviceAddress
            ?.takeUnless { it.startsWith(PhoneIpSyncSessionRegistry.IP_DEVICE_PREFIX) }
        val totalStartedAt = SystemClock.elapsedRealtime()
        var socket: BluetoothSocket? = null
        var selectedDevice: BluetoothDevice? = null
        val tracker = ByteTransferTracker().apply { start() }
        var lastProgressStage: PhoneBluetoothSyncStage? = null
        var lastProgressPercent = -1
        fun report(stage: PhoneBluetoothSyncStage, percent: Int, force: Boolean = false) {
            val clampedPercent = percent.coerceIn(0, 100)
            if (!force && lastProgressStage == stage && lastProgressPercent == clampedPercent) return
            lastProgressStage = stage
            lastProgressPercent = clampedPercent
            onProgress(
                PhoneBluetoothSyncProgress(
                    stage = stage,
                    percent = clampedPercent,
                    bytesTransferred = tracker.bytesTransferred(),
                    bytesPerSecond = tracker.bytesPerSecond()
                )
            )
        }
        val cancellationHandle = installSocketCancellationLogger(
            sessionId = sessionId,
            owner = "library",
            socketProvider = { socket }
        )
        report(PhoneBluetoothSyncStage.CONNECTING, 5, force = true)
        debugLog.appendEvent(
            event = "bt.library.start",
            sessionId = sessionId,
            fields = (manifestRequest?.let { payloadFields("manifestRequest", it) } ?: emptyMap()) + mapOf(
                "uuid" to BluetoothSyncProtocol.SERVICE_UUID,
                "deviceNameHint" to deviceNameHint.orEmpty(),
                "targetAddress" to bluetoothDeviceAddress.orEmpty(),
                "ipFallback" to (deviceAddress != bluetoothDeviceAddress)
            )
        )
        try {
            requireBluetoothConnectPermission()
            val adapter = context.getSystemService(BluetoothManager::class.java)
                ?.adapter
                ?: error("此设备没有蓝牙适配器")
            require(adapter.isEnabled) { "蓝牙未开启" }

            val bondedDevices = adapter.bondedDevices.orEmpty()
            logAdapterSnapshot(sessionId, adapter, bondedDevices)
            val device = selectBondedWatchDevice(
                devices = bondedDevices,
                deviceAddress = bluetoothDeviceAddress,
                deviceNameHint = deviceNameHint
            )
            selectedDevice = device
            debugLog.appendEvent("bt.device.selected", sessionId, deviceFields(device))
            Log.i(TAG, "connecting library sync to name=${device.name} address=${device.address} uuid=${BluetoothSyncProtocol.SERVICE_UUID}")
            cancelDiscoveryLogged(adapter, sessionId)

            val connectedSocket = connectLibrarySocketWithSdpRecovery(
                device = device,
                sessionId = sessionId,
                onSocketChanged = { socket = it }
            )
            report(PhoneBluetoothSyncStage.CONNECTING, 20)
            val cursorResponse = cursorRequest?.let { cursorPayload ->
                writeFrameLogged(connectedSocket, sessionId, "cursorRequest", cursorPayload, tracker)
                readFrameLogged(connectedSocket, sessionId, "cursorResponse", tracker).also { response ->
                    require(response.optBoolean("success", false)) {
                        response.optString("message").ifBlank { "手表游标握手失败" }
                    }
                    requireSupportedLibraryProtocol(response)
                    require(response.optString("phase") == LibrarySyncPayload.PHASE_CURSOR) {
                        "手表未返回资料库游标"
                    }
                }
            }
            val peerDeviceId = cursorResponse?.optString("deviceId")?.trim().orEmpty()
                .ifBlank { device.address.ifBlank { device.name.orEmpty().ifBlank { "watch" } } }
            val request = manifestRequest
                ?: buildManifestRequest?.invoke(peerDeviceId, cursorResponse)
                ?: error("缺少资料库同步请求")
            debugLog.appendEvent("bt.library.manifest.prepared", sessionId, payloadFields("manifestRequest", request))
            val manifestRequests = if (cursorResponse?.let(LibrarySyncPayload::supportsManifestBatches) == true) {
                LibrarySyncPayload.buildManifestFrames(request)
            } else {
                listOf(request)
            }
            manifestRequests.forEachIndexed { index, frame ->
                writeFrameLogged(
                    connectedSocket,
                    sessionId,
                    batchLabel("manifestRequest", index, manifestRequests.size),
                    frame,
                    tracker
                )
            }
            report(PhoneBluetoothSyncStage.TRANSFERRING, 25)
            val manifestResponse = readManifestFrames(connectedSocket.inputStream, sessionId, tracker)
            if (!manifestResponse.optBoolean("success", true)) {
                debugLog.appendEvent(
                    event = "bt.library.manifest.rejected",
                    sessionId = sessionId,
                    fields = payloadFields("manifestResponse", manifestResponse) +
                        mapOf("elapsedMs" to elapsedSince(totalStartedAt))
                )
                writeResponseAck(connectedSocket, sessionId, success = true, applied = true)
                return BluetoothLibrarySyncExchange(
                    deviceName = device.name.orEmpty(),
                    deviceAddress = device.address,
                    cursorResponse = cursorResponse,
                    request = request,
                    manifestResponse = manifestResponse,
                    articleRequestFrameCount = 0,
                    responseFrameCount = 1,
                    response = manifestResponse
                )
            }
            val supportsArticleBatches = manifestResponse.optBoolean("supportsArticleBatches", false)
            var articleRequestStats = EMPTY_FRAME_STATS
            var articleRequestFrameCount = 0
            run {
                val articleRequests = buildArticleRequests(manifestResponse, supportsArticleBatches)
                articleRequestStats = frameStats(articleRequests)
                articleRequestFrameCount = articleRequests.size
                debugLog.appendEvent(
                    event = "bt.library.articles.request.built",
                    sessionId = sessionId,
                    fields = frameStatsFields("articlesRequest", articleRequestStats)
                )
                var requestWireBytesSent = 0L
                val requestTotalWireBytes = articleRequestStats.totalWireBytes.coerceAtLeast(1L)
                report(PhoneBluetoothSyncStage.TRANSFERRING, 30)
                articleRequests.forEachIndexed { index, articleRequest ->
                    writeFrameLogged(
                        socket = connectedSocket,
                        sessionId = sessionId,
                        label = batchLabel("articlesRequest", index, articleRequestFrameCount),
                        payload = articleRequest,
                        byteTracker = tracker,
                        onBytesTransferred = { bytes ->
                            requestWireBytesSent += bytes
                            report(
                                PhoneBluetoothSyncStage.TRANSFERRING,
                                percentBetweenBytes(30, 58, requestWireBytesSent, requestTotalWireBytes)
                            )
                        }
                    )
                }
                report(PhoneBluetoothSyncStage.TRANSFERRING, 58)
            }
            val responseRead = readLibraryResponse(connectedSocket.inputStream, sessionId, onProgress, tracker)
            val response = responseRead.response
            report(PhoneBluetoothSyncStage.VERIFYING, 88)
            val exchange = BluetoothLibrarySyncExchange(
                deviceName = device.name.orEmpty(),
                deviceAddress = device.address,
                cursorResponse = cursorResponse,
                request = request,
                manifestResponse = manifestResponse,
                articleRequestFrameCount = articleRequestFrameCount,
                responseFrameCount = responseRead.stats.frameCount,
                response = response
            )
            if (manifestResponse.optBoolean("supportsReceivedAck", false)) {
                writeResponseAck(
                    socket = connectedSocket,
                    sessionId = sessionId,
                    success = true,
                    applied = false,
                    phase = BluetoothSyncProtocol.ACK_PHASE_RECEIVED
                )?.let { throw it }
            }
            try {
                applyResponse(exchange)
                writeResponseAck(
                    connectedSocket,
                    sessionId,
                    success = true,
                    applied = ackApplied
                )?.let { throw it }
                if (rememberDeviceOnSuccess) {
                    rememberSuccessfulDevice(device, sessionId)
                }
            } catch (throwable: Throwable) {
                writeResponseAck(
                    socket = connectedSocket,
                    sessionId = sessionId,
                    success = false,
                    applied = false,
                    message = throwable.message
                )
                throw throwable
            }
            Log.i(TAG, "library exchange complete manifest=$manifestResponse response=$response")
            debugLog.appendEvent(
                event = "bt.library.complete",
                sessionId = sessionId,
                fields = deviceFields(device) + payloadFields("manifestResponse", manifestResponse) +
                    frameStatsFields("articlesRequest", articleRequestStats) +
                    frameStatsFields("libraryResponse", responseRead.stats) +
                    payloadFields("combinedLibraryResponse", response) +
                    mapOf("elapsedMs" to elapsedSince(totalStartedAt))
            )
            return exchange
        } catch (throwable: Throwable) {
            debugLog.appendEvent(
                event = "bt.library.failed",
                sessionId = sessionId,
                fields = (manifestRequest?.let { payloadFields("manifestRequest", it) } ?: emptyMap()) +
                    (selectedDevice?.let { deviceFields(it) } ?: emptyMap()) +
                    mapOf(
                        "elapsedMs" to elapsedSince(totalStartedAt),
                        "errorClass" to throwable::class.java.name,
                        "message" to throwable.message.orEmpty()
                    ),
                throwable = throwable
            )
            throw throwable
        } finally {
            cancellationHandle?.dispose()
            socket?.let { closeSocketLogged(it, sessionId, "library") }
        }
    }

    private suspend fun exchangeLibraryIpUnlocked(
        ipSession: PhoneIpSyncSession,
        cursorRequest: JSONObject?,
        manifestRequest: JSONObject?,
        buildManifestRequest: (suspend (String, JSONObject?) -> JSONObject)?,
        buildArticleRequests: suspend (JSONObject, Boolean) -> List<JSONObject>,
        sessionId: String,
        onProgress: (PhoneBluetoothSyncProgress) -> Unit,
        applyResponse: suspend (BluetoothLibrarySyncExchange) -> Unit,
        ackApplied: Boolean
    ): BluetoothLibrarySyncExchange {
        val totalStartedAt = SystemClock.elapsedRealtime()
        val tracker = ByteTransferTracker().apply { start() }
        var lastProgressStage: PhoneBluetoothSyncStage? = null
        var lastProgressPercent = -1
        fun report(stage: PhoneBluetoothSyncStage, percent: Int, force: Boolean = false) {
            val clamped = percent.coerceIn(0, 100)
            if (!force && lastProgressStage == stage && lastProgressPercent == clamped) return
            lastProgressStage = stage
            lastProgressPercent = clamped
            onProgress(
                PhoneBluetoothSyncProgress(
                    stage = stage,
                    percent = clamped,
                    bytesTransferred = tracker.bytesTransferred(),
                    bytesPerSecond = tracker.bytesPerSecond()
                )
            )
        }
        val cancellationHandle = installIpCancellationLogger(ipSession, sessionId, "library")
        val ipAddress = PhoneIpSyncSessionRegistry.IP_DEVICE_PREFIX + ipSession.watchDeviceId
        report(PhoneBluetoothSyncStage.CONNECTING, 5, force = true)
        debugLog.appendEvent(
            event = "ip.library.start",
            sessionId = sessionId,
            fields = (manifestRequest?.let { payloadFields("manifestRequest", it) } ?: emptyMap()) + mapOf(
                "watchDeviceId" to ipSession.watchDeviceId,
                "route" to ipSession.routeKind.wireName
            )
        )
        try {
            report(PhoneBluetoothSyncStage.CONNECTING, 20)
            val cursorResponse = cursorRequest?.let { cursorPayload ->
                writeFrameLogged(ipSession.outputStream, sessionId, "cursorRequest", cursorPayload, tracker)
                readFrameLogged(ipSession.inputStream, sessionId, "cursorResponse", tracker).also { response ->
                    require(response.optBoolean("success", false)) {
                        response.optString("message").ifBlank { "手表游标握手失败" }
                    }
                    requireSupportedLibraryProtocol(response)
                    require(response.optString("phase") == LibrarySyncPayload.PHASE_CURSOR) {
                        "手表未返回资料库游标"
                    }
                }
            }
            val peerDeviceId = cursorResponse?.optString("deviceId")?.trim().orEmpty()
                .ifBlank { ipSession.watchDeviceId }
            val request = manifestRequest
                ?: buildManifestRequest?.invoke(peerDeviceId, cursorResponse)
                ?: error("缺少资料库同步请求")
            val manifestRequests = if (cursorResponse?.let(LibrarySyncPayload::supportsManifestBatches) == true) {
                LibrarySyncPayload.buildManifestFrames(request)
            } else {
                listOf(request)
            }
            manifestRequests.forEachIndexed { index, frame ->
                writeFrameLogged(
                    ipSession.outputStream,
                    sessionId,
                    batchLabel("manifestRequest", index, manifestRequests.size),
                    frame,
                    tracker
                )
            }
            report(PhoneBluetoothSyncStage.TRANSFERRING, 25)
            val manifestResponse = readManifestFrames(
                ipSession.inputStream,
                sessionId,
                tracker
            )
            if (!manifestResponse.optBoolean("success", true)) {
                writeResponseAck(
                    ipSession.outputStream,
                    sessionId,
                    success = true,
                    applied = true
                )
                return BluetoothLibrarySyncExchange(
                    deviceName = "WatchRSS 手表",
                    deviceAddress = ipAddress,
                    cursorResponse = cursorResponse,
                    request = request,
                    manifestResponse = manifestResponse,
                    articleRequestFrameCount = 0,
                    responseFrameCount = 1,
                    response = manifestResponse
                )
            }
            val supportsArticleBatches = manifestResponse.optBoolean("supportsArticleBatches", false)
            val articleRequests = buildArticleRequests(manifestResponse, supportsArticleBatches)
            val articleRequestStats = frameStats(articleRequests)
            var requestWireBytesSent = 0L
            val requestTotalWireBytes = articleRequestStats.totalWireBytes.coerceAtLeast(1L)
            report(PhoneBluetoothSyncStage.TRANSFERRING, 30)
            articleRequests.forEachIndexed { index, articleRequest ->
                writeFrameLogged(
                    outputStream = ipSession.outputStream,
                    sessionId = sessionId,
                    label = batchLabel("articlesRequest", index, articleRequests.size),
                    payload = articleRequest,
                    byteTracker = tracker,
                    onBytesTransferred = { bytes ->
                        requestWireBytesSent += bytes
                        report(
                            PhoneBluetoothSyncStage.TRANSFERRING,
                            percentBetweenBytes(30, 58, requestWireBytesSent, requestTotalWireBytes)
                        )
                    }
                )
            }
            report(PhoneBluetoothSyncStage.TRANSFERRING, 58)
            val responseRead = readLibraryResponse(
                ipSession.inputStream,
                sessionId,
                onProgress,
                tracker
            )
            val response = responseRead.response
            report(PhoneBluetoothSyncStage.VERIFYING, 88)
            val exchange = BluetoothLibrarySyncExchange(
                deviceName = "WatchRSS 手表",
                deviceAddress = ipAddress,
                cursorResponse = cursorResponse,
                request = request,
                manifestResponse = manifestResponse,
                articleRequestFrameCount = articleRequests.size,
                responseFrameCount = responseRead.stats.frameCount,
                response = response
            )
            if (manifestResponse.optBoolean("supportsReceivedAck", false)) {
                writeResponseAck(
                    outputStream = ipSession.outputStream,
                    sessionId = sessionId,
                    success = true,
                    applied = false,
                    phase = BluetoothSyncProtocol.ACK_PHASE_RECEIVED
                )?.let { throw it }
            }
            try {
                applyResponse(exchange)
                writeResponseAck(
                    ipSession.outputStream,
                    sessionId,
                    success = true,
                    applied = ackApplied
                )?.let { throw it }
            } catch (throwable: Throwable) {
                writeResponseAck(
                    outputStream = ipSession.outputStream,
                    sessionId = sessionId,
                    success = false,
                    applied = false,
                    message = throwable.message
                )
                throw throwable
            }
            debugLog.appendEvent(
                event = "ip.library.complete",
                sessionId = sessionId,
                fields = payloadFields("manifestResponse", manifestResponse) +
                    frameStatsFields("articlesRequest", articleRequestStats) +
                    frameStatsFields("libraryResponse", responseRead.stats) +
                    mapOf("elapsedMs" to elapsedSince(totalStartedAt))
            )
            return exchange
        } catch (throwable: Throwable) {
            ipSession.close()
            debugLog.appendEvent(
                event = "ip.library.failed",
                sessionId = sessionId,
                fields = (manifestRequest?.let { payloadFields("manifestRequest", it) } ?: emptyMap()) + failureFields(throwable),
                throwable = throwable
            )
            throw throwable
        } finally {
            cancellationHandle?.dispose()
        }
    }

    private suspend fun exchangeLibraryOnSessionTransport(
        session: PhoneSyncSession,
        cursorRequest: JSONObject,
        buildManifestRequest: suspend (String, JSONObject?) -> JSONObject,
        buildArticleRequests: suspend (JSONObject, Boolean) -> List<JSONObject>,
        sessionId: String,
        onProgress: (PhoneBluetoothSyncProgress) -> Unit,
        applyResponse: suspend (BluetoothLibrarySyncExchange) -> Unit
    ): BluetoothLibrarySyncExchange {
        val transport = session.transport ?: error("持久同步连接已关闭")
        val startedAt = SystemClock.elapsedRealtime()
        val tracker = ByteTransferTracker().apply { start() }
        var lastProgressStage: PhoneBluetoothSyncStage? = null
        var lastProgressPercent = -1
        fun report(stage: PhoneBluetoothSyncStage, percent: Int, force: Boolean = false) {
            val clamped = percent.coerceIn(0, 100)
            if (!force && lastProgressStage == stage && lastProgressPercent == clamped) return
            lastProgressStage = stage
            lastProgressPercent = clamped
            onProgress(
                PhoneBluetoothSyncProgress(
                    stage = stage,
                    percent = clamped,
                    bytesTransferred = tracker.bytesTransferred(),
                    bytesPerSecond = tracker.bytesPerSecond()
                )
            )
        }
        report(PhoneBluetoothSyncStage.CONNECTING, 5, force = true)
        val wireCursorRequest = session.requestForExchange(cursorRequest)
        writeFrameLogged(
            transport.outputStream,
            sessionId,
            "sessionCursorRequest",
            wireCursorRequest,
            tracker
        )
        val cursorResponse = readFrameLogged(
            transport.inputStream,
            sessionId,
            "sessionCursorResponse",
            tracker
        ).also { response ->
            require(response.optBoolean("success", false)) {
                response.optString("message").ifBlank { "手表游标握手失败" }
            }
            requireSupportedLibraryProtocol(response)
            require(response.optString("phase") == LibrarySyncPayload.PHASE_CURSOR) {
                "手表未返回资料库游标"
            }
            session.recordNegotiation(response)
            require(!session.legacyFallback) { "手表未接受持久同步会话" }
        }
        val peerDeviceId = cursorResponse.optString("deviceId").trim()
            .ifBlank { session.device.remoteDeviceId.ifBlank { session.device.address } }
        val request = buildManifestRequest(peerDeviceId, cursorResponse)
        val manifestRequests = if (LibrarySyncPayload.supportsManifestBatches(cursorResponse)) {
            LibrarySyncPayload.buildManifestFrames(request)
        } else {
            listOf(request)
        }
        manifestRequests.forEachIndexed { index, frame ->
            writeFrameLogged(
                transport.outputStream,
                sessionId,
                batchLabel("sessionManifestRequest", index, manifestRequests.size),
                frame,
                tracker
            )
        }
        report(PhoneBluetoothSyncStage.TRANSFERRING, 25)
        val manifestResponse = readManifestFrames(transport.inputStream, sessionId, tracker)
        if (!manifestResponse.optBoolean("success", true)) {
            writeResponseAck(transport.outputStream, sessionId, success = true, applied = true)
            return BluetoothLibrarySyncExchange(
                deviceName = session.device.name,
                deviceAddress = session.device.address,
                cursorResponse = cursorResponse,
                request = request,
                manifestResponse = manifestResponse,
                articleRequestFrameCount = 0,
                responseFrameCount = 1,
                response = manifestResponse
            )
        }
        val supportsArticleBatches = manifestResponse.optBoolean("supportsArticleBatches", false)
        val articleRequests = buildArticleRequests(manifestResponse, supportsArticleBatches)
        val articleRequestStats = frameStats(articleRequests)
        var requestWireBytesSent = 0L
        val requestTotalWireBytes = articleRequestStats.totalWireBytes.coerceAtLeast(1L)
        report(PhoneBluetoothSyncStage.TRANSFERRING, 30)
        articleRequests.forEachIndexed { index, articleRequest ->
            writeFrameLogged(
                outputStream = transport.outputStream,
                sessionId = sessionId,
                label = batchLabel("sessionArticlesRequest", index, articleRequests.size),
                payload = articleRequest,
                byteTracker = tracker,
                onBytesTransferred = { bytes ->
                    requestWireBytesSent += bytes
                    report(
                        PhoneBluetoothSyncStage.TRANSFERRING,
                        percentBetweenBytes(30, 58, requestWireBytesSent, requestTotalWireBytes)
                    )
                }
            )
        }
        report(PhoneBluetoothSyncStage.TRANSFERRING, 58)
        val responseRead = readLibraryResponse(
            transport.inputStream,
            sessionId,
            onProgress,
            tracker
        )
        val exchange = BluetoothLibrarySyncExchange(
            deviceName = session.device.name,
            deviceAddress = session.device.address,
            cursorResponse = cursorResponse,
            request = request,
            manifestResponse = manifestResponse,
            articleRequestFrameCount = articleRequests.size,
            responseFrameCount = responseRead.stats.frameCount,
            response = responseRead.response
        )
        report(PhoneBluetoothSyncStage.VERIFYING, 88)
        if (manifestResponse.optBoolean("supportsReceivedAck", false)) {
            writeResponseAck(
                outputStream = transport.outputStream,
                sessionId = sessionId,
                success = true,
                applied = false,
                phase = BluetoothSyncProtocol.ACK_PHASE_RECEIVED
            )?.let { throw it }
        }
        try {
            applyResponse(exchange)
            writeResponseAck(
                transport.outputStream,
                sessionId,
                success = true,
                applied = true
            )?.let { throw it }
        } catch (throwable: Throwable) {
            writeResponseAck(
                outputStream = transport.outputStream,
                sessionId = sessionId,
                success = false,
                applied = false,
                message = throwable.message
            )
            throw throwable
        }
        debugLog.appendEvent(
            event = "sync.session.library.complete",
            sessionId = sessionId,
            fields = frameStatsFields("articlesRequest", articleRequestStats) +
                frameStatsFields("libraryResponse", responseRead.stats) +
                mapOf(
                    "deviceAddress" to session.device.address,
                    "elapsedMs" to elapsedSince(startedAt)
                )
        )
        return exchange
    }

    @SuppressLint("MissingPermission")
    private suspend fun probeLibrarySyncDevice(
        device: BluetoothDevice,
        deviceId: String,
        sessionId: String,
        timeoutMs: Long,
        retainSession: Boolean
    ): ProbedWatch {
        debugLog.appendEvent(
            event = "bt.library.probe.device.start",
            sessionId = sessionId,
            fields = deviceFields(device) + mapOf("timeoutMs" to timeoutMs)
        )
        val result = runCatching {
            probeLibrarySyncDeviceWithManifestFallback(
                device = device,
                deviceId = deviceId,
                sessionId = sessionId,
                fallbackTimeoutMs = timeoutMs
            )
        }
        val identity = result.getOrNull()
        val ipSession = identity
            ?.takeIf { it.ipUpgradeAccepted && it.deviceId.isNotBlank() }
            ?.let { awaitIpSession(it.deviceId, IP_UPGRADE_WAIT_MS) }
        var sessionLease = identity?.session
        if (ipSession != null && sessionLease != null) {
            runCatching { sessionLease.complete("$sessionId-rfcomm-upgrade") }
            sessionLease.close()
            sessionLease = if (retainSession) {
                createIpPhoneSyncSession(device, deviceId, identity, ipSession)
            } else {
                ipSession.close()
                null
            }
        } else if (!retainSession) {
            sessionLease?.let { lease ->
                runCatching { lease.complete("$sessionId-probe-complete") }
                lease.close()
            }
            sessionLease = null
            ipSession?.close()
        }
        val keepLegacyIpSession =
            retainSession && sessionLease == null && ipSession != null &&
                identity?.supportsPersistentSession == false
        val probe = result.fold(
            onSuccess = { identity ->
                PhoneBluetoothWatchProbeResult(
                    device = sessionLease?.device ?: ipSession?.takeIf { keepLegacyIpSession }?.let { session ->
                        PhoneBluetoothWatchDevice(
                            name = "${device.name.orEmpty()} (${session.routeKind.wireName})",
                            address = PhoneIpSyncSessionRegistry.IP_DEVICE_PREFIX + identity.deviceId,
                            uuidCount = device.uuids?.size ?: 0,
                            remoteDeviceId = identity.deviceId,
                            bluetoothAddress = device.address,
                            supportsPersistentSession = identity.supportsPersistentSession
                        )
                    } ?: device.toWatchDevice(
                        remoteDeviceId = identity.deviceId,
                        supportsPersistentSession = identity.supportsPersistentSession
                    ),
                    reachable = true,
                    message = if (sessionLease?.device?.address?.startsWith(
                            PhoneIpSyncSessionRegistry.IP_DEVICE_PREFIX
                        ) == true || keepLegacyIpSession
                    ) {
                        "已通过蓝牙升级到 IP"
                    } else {
                        null
                    },
                    capabilities = identity.capabilities
                )
            },
            onFailure = { throwable ->
                PhoneBluetoothWatchProbeResult(
                    device = device.toWatchDevice(),
                    reachable = false,
                    message = probeFailureMessage(throwable)
                )
            }
        )
        probe.capabilities?.let {
            synchronized(capabilitiesByAddress) {
                capabilitiesByAddress[device.address] = it
                if (probe.device.address.startsWith(PhoneIpSyncSessionRegistry.IP_DEVICE_PREFIX)) {
                    capabilitiesByAddress[probe.device.address] = it
                }
            }
        }
        debugLog.appendEvent(
            event = "bt.library.probe.device.complete",
            sessionId = sessionId,
            fields = deviceFields(device) + mapOf(
                "reachable" to probe.reachable,
                "message" to probe.message.orEmpty()
            )
        )
        return ProbedWatch(probe, sessionLease)
    }

    private suspend fun probeLibrarySyncDeviceWithManifestFallback(
        device: BluetoothDevice,
        deviceId: String,
        sessionId: String,
        fallbackTimeoutMs: Long
    ): ProbeIdentity {
        val direct = probeDirectWithPersistentSessionSdpRecovery(device, deviceId, sessionId)
        if (direct != null) {
            debugLog.appendEvent(
                event = "bt.library.probe.direct.success",
                sessionId = sessionId,
                fields = mapOf(
                    "deviceId" to direct.deviceId,
                    "persistentSessionAccepted" to direct.persistentSessionAccepted
                )
            )
            return direct
        }
        debugLog.appendEvent(
            event = "bt.library.probe.direct.compat.fallback",
            sessionId = sessionId,
            fields = emptyMap()
        )
        return withTimeout(fallbackTimeoutMs) {
            exchangeLibrary(
                manifestRequest = buildProbeManifestRequest(deviceId),
                buildArticleRequests = { _, _ ->
                    listOf(LibrarySyncPayload.buildEmptyArticlesProbeRequest(deviceId))
                },
                deviceAddress = device.address,
                sessionId = "$sessionId-manifest",
                onProgress = {},
                ackApplied = false,
                rememberDeviceOnSuccess = false
            ).manifestResponse.let {
                requireSupportedLibraryProtocol(it)
                ProbeIdentity(
                    it.optString("deviceId").trim(),
                    it.optJSONObject("watchCapabilities")?.toWatchCapabilities(),
                    it.optBoolean(FIELD_IP_UPGRADE_ACCEPTED, false),
                    it.optBoolean(BluetoothSyncProtocol.FIELD_SUPPORTS_PERSISTENT_SESSION, false),
                    false
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun probeDirectWithPersistentSessionSdpRecovery(
        device: BluetoothDevice,
        deviceId: String,
        sessionId: String
    ): ProbeIdentity? {
        var fastFailureRetries = 0
        while (true) {
            val attempt = fastFailureRetries + 1
            val attemptStartedAt = SystemClock.elapsedRealtime()
            val attemptSessionId = if (attempt == 1) {
                "$sessionId-direct"
            } else {
                "$sessionId-direct-retry${attempt - 1}"
            }
            val result = runCatching {
                withTimeout(DIRECT_PROBE_TIMEOUT_MS) {
                    probeDirectPersistentOnce(device, deviceId, attemptSessionId)
                }
            }
            if (result.isSuccess) return result.getOrThrow()
            val throwable = result.exceptionOrNull() ?: error("蓝牙探测失败但没有异常")
            if (throwable is CancellationException) currentCoroutineContext().ensureActive()
            val elapsedMs = elapsedSince(attemptStartedAt)
            val recovery = sdpProbeFailureRecovery(
                elapsedMs = elapsedMs,
                timedOut = throwable is TimeoutCancellationException,
                completedFastFailureRetries = fastFailureRetries
            )
            debugLog.appendEvent(
                event = "bt.library.probe.direct.failed",
                sessionId = sessionId,
                fields = failureFields(throwable) + mapOf(
                    "attempt" to attempt,
                    "elapsedMs" to elapsedMs,
                    "recoveryDelayMs" to recovery.delayMs,
                    "retrySameCandidate" to recovery.retrySameCandidate
                ),
                throwable = throwable
            )
            delay(recovery.delayMs)
            if (recovery.retrySameCandidate) {
                fastFailureRetries += 1
                continue
            }
            throw throwable
        }
    }

    @SuppressLint("MissingPermission")
    private fun probeDirectPersistentOnce(
        device: BluetoothDevice,
        deviceId: String,
        sessionId: String
    ): ProbeIdentity? {
        var socket: BluetoothSocket? = null
        return try {
            socket = device.createRfcommSocketToServiceRecord(BluetoothSyncProtocol.SERVICE_UUID)
            connectLogged(socket, sessionId, device)
            val request = BluetoothSyncProtocol.withPersistentSessionRequest(
                LibrarySyncPayload.buildProbeRequest(deviceId)
            ).apply {
                (context.applicationContext as? PhoneCompanionApplication)
                    ?.currentIpEndpointDescriptorForSync()
                    ?.let { put(FIELD_IP_ENDPOINT_DESCRIPTOR, it) }
            }
            writeFrameLogged(socket, sessionId, "probeRequest", request)
            val response = readFrameLogged(socket, sessionId, "probeResponse")
            writeResponseAck(socket, sessionId, success = true, applied = true)
            val connectedSocket = checkNotNull(socket)
            if (!response.optBoolean("success", false)) {
                closeSocketLogged(connectedSocket, sessionId, "probe-rejected")
                socket = null
                null
            } else {
                requireSupportedLibraryProtocol(response)
                val supportsPersistent = response.optBoolean(
                    BluetoothSyncProtocol.FIELD_SUPPORTS_PERSISTENT_SESSION,
                    false
                )
                val accepted = supportsPersistent && response.optBoolean(
                    BluetoothSyncProtocol.FIELD_PERSISTENT_SESSION_ACCEPTED,
                    false
                )
                ProbeIdentity(
                    deviceId = response.optString("deviceId").trim(),
                    capabilities = response.optJSONObject("watchCapabilities")?.toWatchCapabilities(),
                    ipUpgradeAccepted = response.optBoolean(FIELD_IP_UPGRADE_ACCEPTED, false),
                    supportsPersistentSession = supportsPersistent,
                    persistentSessionAccepted = accepted,
                    session = if (accepted) {
                        createBluetoothPhoneSyncSession(
                            device,
                            deviceId,
                            response,
                            connectedSocket
                        ).also {
                            socket = null
                        }
                    } else {
                        closeSocketLogged(connectedSocket, sessionId, "probe-legacy")
                        socket = null
                        null
                    }
                )
            }
        } finally {
            socket?.let { closeSocketLogged(it, sessionId, "probe") }
        }
    }

    private suspend fun probeDirectWithSdpRecovery(
        device: BluetoothDevice,
        deviceId: String,
        sessionId: String
    ): BluetoothSyncExchange {
        var fastFailureRetries = 0
        while (true) {
            val attempt = fastFailureRetries + 1
            val attemptStartedAt = SystemClock.elapsedRealtime()
            val attemptSessionId = if (attempt == 1) {
                "$sessionId-direct"
            } else {
                "$sessionId-direct-retry${attempt - 1}"
            }
            val result = runCatching {
                withTimeout(DIRECT_PROBE_TIMEOUT_MS) {
                    exchange(
                        request = LibrarySyncPayload.buildProbeRequest(deviceId).apply {
                            (context.applicationContext as? PhoneCompanionApplication)
                                ?.currentIpEndpointDescriptorForSync()
                                ?.let { put(FIELD_IP_ENDPOINT_DESCRIPTOR, it) }
                        },
                        deviceAddress = device.address,
                        sessionId = attemptSessionId,
                        rememberDeviceOnSuccess = false
                    )
                }
            }
            if (result.isSuccess) return result.getOrThrow()

            val throwable = result.exceptionOrNull() ?: error("蓝牙探测失败但没有异常")
            if (throwable is CancellationException) {
                currentCoroutineContext().ensureActive()
            }
            val elapsedMs = elapsedSince(attemptStartedAt)
            val recovery = sdpProbeFailureRecovery(
                elapsedMs = elapsedMs,
                timedOut = throwable is TimeoutCancellationException,
                completedFastFailureRetries = fastFailureRetries
            )
            debugLog.appendEvent(
                event = "bt.library.probe.direct.failed",
                sessionId = sessionId,
                fields = failureFields(throwable) + mapOf(
                    "attempt" to attempt,
                    "elapsedMs" to elapsedMs,
                    "recoveryDelayMs" to recovery.delayMs,
                    "retrySameCandidate" to recovery.retrySameCandidate
                ),
                throwable = throwable
            )
            delay(recovery.delayMs)
            if (recovery.retrySameCandidate) {
                fastFailureRetries += 1
                continue
            }
            throw throwable
        }
    }

    private suspend fun awaitIpSession(deviceId: String, timeoutMs: Long): PhoneIpSyncSession? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            PhoneIpSyncSessionRegistry.session(deviceId)?.let { return it }
            delay(IP_UPGRADE_POLL_INTERVAL_MS)
        }
        return PhoneIpSyncSessionRegistry.session(deviceId)
    }

    @SuppressLint("MissingPermission")
    private fun createBluetoothPhoneSyncSession(
        device: BluetoothDevice,
        localDeviceId: String,
        response: JSONObject,
        socket: BluetoothSocket
    ): PhoneSyncSession {
        val remoteDeviceId = response.optString("deviceId").trim()
        val watchDevice = device.toWatchDevice(
            remoteDeviceId = remoteDeviceId,
            supportsPersistentSession = true
        )
        return PhoneSyncSession(
            client = this,
            device = watchDevice,
            localDeviceId = localDeviceId,
            transport = PhoneSyncSession.Transport(
                inputStream = socket.inputStream,
                outputStream = socket.outputStream,
                owner = "rfcomm",
                closeOnLegacyFallback = true,
                closeTransport = { runCatching { socket.close() } }
            ),
            persistentAccepted = true,
            negotiationPending = false,
            ipUpgradeExpected = response.optBoolean(FIELD_IP_UPGRADE_ACCEPTED, false)
        )
    }

    @SuppressLint("MissingPermission")
    private fun createIpPhoneSyncSession(
        device: BluetoothDevice,
        localDeviceId: String,
        identity: ProbeIdentity,
        ipSession: PhoneIpSyncSession
    ): PhoneSyncSession = createIpPhoneSyncSession(
        device = PhoneBluetoothWatchDevice(
            name = device.name.orEmpty(),
            address = PhoneIpSyncSessionRegistry.IP_DEVICE_PREFIX + identity.deviceId,
            uuidCount = device.uuids?.size ?: 0,
            remoteDeviceId = identity.deviceId,
            bluetoothAddress = device.address,
            supportsPersistentSession = true
        ),
        localDeviceId = localDeviceId,
        ipSession = ipSession
    )

    private fun createIpPhoneSyncSession(
        device: PhoneBluetoothWatchDevice,
        localDeviceId: String,
        ipSession: PhoneIpSyncSession
    ): PhoneSyncSession {
        val remoteDeviceId = device.remoteDeviceId.ifBlank { ipSession.watchDeviceId }
        val watchDevice = device.copy(
            name = "${device.name} (${ipSession.routeKind.wireName})",
            address = PhoneIpSyncSessionRegistry.IP_DEVICE_PREFIX + remoteDeviceId,
            remoteDeviceId = remoteDeviceId,
            supportsPersistentSession = true
        )
        return PhoneSyncSession(
            client = this,
            device = watchDevice,
            localDeviceId = localDeviceId,
            transport = PhoneSyncSession.Transport(
                inputStream = ipSession.inputStream,
                outputStream = ipSession.outputStream,
                owner = "ip:${ipSession.routeKind.wireName}",
                closeOnLegacyFallback = false,
                closeTransport = { ipSession.close() }
            ),
            persistentAccepted = false,
            negotiationPending = true,
            ipUpgradeExpected = false
        )
    }

    private fun requireSupportedLibraryProtocol(payload: JSONObject) {
        val version = payload.optInt("version", 0)
        require(version >= LibrarySyncPayload.MIN_SUPPORTED_WATCH_PROTOCOL_VERSION) {
            "手表资料库同步协议为 v$version，至少需要 v${LibrarySyncPayload.MIN_SUPPORTED_WATCH_PROTOCOL_VERSION}；请先升级手表端"
        }
    }

    private fun readLibraryResponse(
        inputStream: InputStream,
        sessionId: String,
        onProgress: (PhoneBluetoothSyncProgress) -> Unit,
        byteTracker: ByteTransferTracker
    ): LibraryResponseRead {
        var lastPercent = -1
        fun report(percent: Int) {
            val clampedPercent = percent.coerceIn(0, 100)
            if (clampedPercent == lastPercent) return
            lastPercent = clampedPercent
            onProgress(
                PhoneBluetoothSyncProgress(
                    PhoneBluetoothSyncStage.TRANSFERRING,
                    clampedPercent,
                    byteTracker.bytesTransferred(),
                    byteTracker.bytesPerSecond()
                )
            )
        }

        report(60)
        var responseWireBytesRead = 0L
        val first = readFrameLogged(
            inputStream = inputStream,
            sessionId = sessionId,
            label = "libraryResponse",
            byteTracker = byteTracker,
            onBytesTransferred = { bytes ->
                responseWireBytesRead += bytes
            }
        )
        if (!first.optBoolean("success", true)) {
            return LibraryResponseRead(
                response = first,
                stats = frameStats(first)
            )
        }
        val batchCount = validateBatchFrame(
            frame = first,
            label = "libraryResponse",
            expectedPhase = PHASE_COMPLETE,
            expectedIndex = 0,
            expectedBatchCount = null
        )
        val accumulator = CombinedLibraryResponse(first)
        var stats = frameStats(first)
        var completed = 1
        val responseTotalWireBytes = first
            .optPositiveLong(LibrarySyncPayload.FIELD_BATCH_TOTAL_WIRE_BYTES)
            ?.takeIf { it >= responseWireBytesRead }
        if (responseTotalWireBytes != null) {
            report(percentBetweenBytes(60, 100, responseWireBytesRead, responseTotalWireBytes))
        } else {
            report(percentBetween(60, 100, completed, batchCount))
        }
        while (completed < batchCount) {
            val index = completed
            val frame = readFrameLogged(
                inputStream = inputStream,
                sessionId = sessionId,
                label = batchLabel("libraryResponse", index, batchCount),
                byteTracker = byteTracker,
                onBytesTransferred = { bytes ->
                    responseWireBytesRead += bytes
                    if (responseTotalWireBytes != null) {
                        report(percentBetweenBytes(60, 100, responseWireBytesRead, responseTotalWireBytes))
                    }
                }
            )
            validateBatchFrame(
                frame = frame,
                label = "libraryResponse",
                expectedPhase = PHASE_COMPLETE,
                expectedIndex = index,
                expectedBatchCount = batchCount
            )
            accumulator.append(frame)
            stats = stats + frameStats(frame)
            completed += 1
            if (responseTotalWireBytes == null) {
                report(percentBetween(60, 100, completed, batchCount))
            }
        }
        report(100)
        return LibraryResponseRead(
            response = accumulator.toPayload(),
            stats = stats
        )
    }

    private fun readManifestFrames(
        inputStream: InputStream,
        sessionId: String,
        byteTracker: ByteTransferTracker
    ): JSONObject {
        val first = readFrameLogged(
            inputStream = inputStream,
            sessionId = sessionId,
            label = "manifestResponse",
            byteTracker = byteTracker
        )
        if (!first.optBoolean("success", true)) return first
        val batchCount = LibrarySyncPayload.manifestBatchCount(first)
        if (batchCount <= 1) return first
        val frames = ArrayList<JSONObject>(batchCount)
        frames += first
        for (index in 1 until batchCount) {
            frames += readFrameLogged(
                inputStream = inputStream,
                sessionId = sessionId,
                label = batchLabel("manifestResponse", index, batchCount),
                byteTracker = byteTracker
            )
        }
        return LibrarySyncPayload.combineManifestFrames(frames)
    }

    private class CombinedLibraryResponse(first: JSONObject) {
        private val firstFrame = first
        private val articles = JSONArray()
        private val sources = JSONArray()
        private val bodyRequests = JSONArray()
        private var success = true

        init {
            append(first)
        }

        fun append(frame: JSONObject) {
            success = success && frame.optBoolean("success", true)
            copyArray(frame.optJSONArray("articles"), articles)
            copyArray(frame.optJSONArray("rssSources"), sources)
            copyArray(frame.optJSONArray("bodyRequests"), bodyRequests)
        }

        fun toPayload(): JSONObject {
            return JSONObject().apply {
                put("success", success)
                put("version", firstFrame.optInt("version", LibrarySyncPayload.PROTOCOL_VERSION))
                put("action", BluetoothSyncProtocol.ACTION_SYNC_LIBRARY)
                put("phase", firstFrame.optString("phase").ifBlank { PHASE_COMPLETE })
                put("deviceId", firstFrame.optString("deviceId"))
                put("sentAt", firstFrame.optLong("sentAt"))
                put("articles", articles)
                if (sources.length() > 0) {
                    put("rssSources", sources)
                }
                if (bodyRequests.length() > 0) {
                    put("bodyRequests", bodyRequests)
                }
                firstFrame.optJSONObject("stats")?.let { put("stats", it) }
                firstFrame.optString("message").takeIf { it.isNotBlank() }?.let { put("message", it) }
            }
        }

        private fun copyArray(source: JSONArray?, target: JSONArray) {
            if (source == null) return
            for (index in 0 until source.length()) {
                source.optJSONObject(index)?.let(target::put)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun selectBondedWatchDevice(
        devices: Set<BluetoothDevice>,
        deviceAddress: String?,
        deviceNameHint: String?
    ): BluetoothDevice {
        devices.forEach { device ->
            Log.i(
                TAG,
                "bonded device name=${device.name} address=${device.address} uuids=${device.uuids?.joinToString()}"
            )
        }
        val normalizedAddress = deviceAddress?.trim()?.uppercase()
        if (!normalizedAddress.isNullOrEmpty()) {
            return devices.firstOrNull { it.address.uppercase() == normalizedAddress }
                ?: error("未找到指定蓝牙设备：$deviceAddress")
        }
        val hint = deviceNameHint?.trim()?.lowercase().orEmpty()
        if (hint.isNotEmpty()) {
            devices.firstOrNull { it.name.orEmpty().lowercase().contains(hint) }?.let { return it }
        }
        cachedDeviceAddress()?.let { cachedAddress ->
            devices.firstOrNull { it.address.equals(cachedAddress, ignoreCase = true) }?.let { return it }
        }
        return sortedWatchDevices(devices).firstOrNull()
            ?: error("未找到已配对手表蓝牙设备")
    }

    @SuppressLint("MissingPermission")
    private fun sortedWatchDevices(devices: Set<BluetoothDevice>): List<BluetoothDevice> =
        devices
            .sortedBy { it.name.orEmpty() }
            .filter(::looksLikeWatchDevice)

    @SuppressLint("MissingPermission")
    private fun probeCandidateWatchDevices(
        devices: Set<BluetoothDevice>,
        activeWatchAddresses: Set<String>
    ): List<BluetoothDevice> {
        val cachedAddress = cachedDeviceAddress()
        val watchDevices = sortedWatchDevices(devices).toMutableList()
        if (!cachedAddress.isNullOrBlank()) {
            val cachedDevice = devices.firstOrNull { it.address.equals(cachedAddress, ignoreCase = true) }
            if (cachedDevice != null && watchDevices.none { it.address.equals(cachedDevice.address, ignoreCase = true) }) {
                watchDevices.add(0, cachedDevice)
            }
        }
        return watchDevices
            .distinctBy { it.address.uppercase() }
            .sortedBy { device ->
                when {
                    device.address.uppercase() in activeWatchAddresses -> 0
                    cachedAddress != null && device.address.equals(cachedAddress, ignoreCase = true) -> 1
                    else -> 2
                }
            }
    }

    @SuppressLint("MissingPermission")
    private suspend fun activeWatchDeviceAddresses(
        bluetoothManager: BluetoothManager,
        adapter: BluetoothAdapter,
        bondedDevices: Set<BluetoothDevice>,
        sessionId: String
    ): Set<String> {
        val watchAddresses = sortedWatchDevices(bondedDevices)
            .mapTo(mutableSetOf()) { it.address.uppercase() }
        if (watchAddresses.isEmpty()) return emptySet()

        val gattDevices = listOf(BluetoothProfile.GATT_SERVER, BluetoothProfile.GATT)
            .flatMap { profile ->
                runCatching { bluetoothManager.getConnectedDevices(profile) }
                    .onFailure { throwable ->
                        logActiveProfileFailure(sessionId, profile, throwable)
                    }
                    .getOrDefault(emptyList())
            }
        val profileDevices = coroutineScope {
            ACTIVE_WATCH_PROFILE_IDS
                .map { profile ->
                    async { connectedProfileDevices(adapter, profile, sessionId) }
                }
                .awaitAll()
                .flatten()
        }
        val activeAddresses = (gattDevices + profileDevices)
            .map { it.address.uppercase() }
            .filterTo(linkedSetOf()) { it in watchAddresses }
        debugLog.appendEvent(
            event = "bt.library.probe.active-devices",
            sessionId = sessionId,
            fields = mapOf(
                "activeCandidates" to activeAddresses.size,
                "activeAddresses" to activeAddresses.joinToString(",")
            )
        )
        return activeAddresses
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectedProfileDevices(
        adapter: BluetoothAdapter,
        profile: Int,
        sessionId: String
    ): List<BluetoothDevice> {
        val devices = withTimeoutOrNull(ACTIVE_WATCH_PROFILE_QUERY_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(
                        connectedProfile: Int,
                        proxy: BluetoothProfile
                    ) {
                        val connectedDevices = runCatching { proxy.connectedDevices }
                            .onFailure { throwable ->
                                logActiveProfileFailure(sessionId, connectedProfile, throwable)
                            }
                            .getOrDefault(emptyList())
                        runCatching { adapter.closeProfileProxy(connectedProfile, proxy) }
                        if (continuation.isActive) continuation.resume(connectedDevices)
                    }

                    override fun onServiceDisconnected(disconnectedProfile: Int) {
                        if (continuation.isActive) continuation.resume(emptyList())
                    }
                }
                val requested = runCatching {
                    adapter.getProfileProxy(context.applicationContext, listener, profile)
                }.onFailure { throwable ->
                    logActiveProfileFailure(sessionId, profile, throwable)
                }.getOrDefault(false)
                if (!requested && continuation.isActive) continuation.resume(emptyList())
            }
        }
        if (devices == null) {
            debugLog.appendEvent(
                event = "bt.library.probe.active-profile-timeout",
                sessionId = sessionId,
                fields = mapOf("profile" to bluetoothProfileName(profile))
            )
        }
        return devices.orEmpty()
    }

    private fun logActiveProfileFailure(
        sessionId: String,
        profile: Int,
        throwable: Throwable
    ) {
        debugLog.appendEvent(
            event = "bt.library.probe.active-profile-failed",
            sessionId = sessionId,
            fields = mapOf(
                "profile" to bluetoothProfileName(profile),
                "errorClass" to throwable::class.java.name,
                "message" to throwable.message.orEmpty()
            ),
            throwable = throwable
        )
    }

    private fun bluetoothProfileName(profile: Int): String =
        when (profile) {
            BluetoothProfile.GATT_SERVER -> "GATT_SERVER"
            BluetoothProfile.GATT -> "GATT"
            BluetoothProfile.A2DP -> "A2DP"
            BluetoothProfile.HEADSET -> "HEADSET"
            else -> profile.toString()
        }

    @SuppressLint("MissingPermission")
    private fun looksLikeWatchDevice(device: BluetoothDevice): Boolean {
        val name = device.name.orEmpty().lowercase()
        val classMajor = device.bluetoothClass?.majorDeviceClass
        return classMajor == BluetoothClass.Device.Major.WEARABLE ||
            name.contains("watch") ||
            name.contains("wear") ||
            name.contains("手表") ||
            name.contains("腕")
    }

    private fun requireBluetoothConnectPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
        require(granted) { "缺少 BLUETOOTH_CONNECT 权限" }
    }

    private fun canCancelDiscovery(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_SCAN
        ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    private fun logAdapterSnapshot(
        sessionId: String,
        adapter: BluetoothAdapter,
        devices: Set<BluetoothDevice>
    ) {
        val deviceSummaries = devices
            .sortedBy { it.name.orEmpty() }
            .joinToString(separator = "|") { device ->
                "${device.name.orEmpty()}@${device.address}#uuids=${device.uuids?.size ?: 0}"
            }
        debugLog.appendEvent(
            event = "bt.adapter.snapshot",
            sessionId = sessionId,
            fields = mapOf(
                "enabled" to adapter.isEnabled,
                "bondedCount" to devices.size,
                "bondedDevices" to deviceSummaries
            )
        )
    }

    @SuppressLint("MissingPermission")
    private fun cancelDiscoveryLogged(adapter: BluetoothAdapter, sessionId: String) {
        if (!canCancelDiscovery()) {
            debugLog.appendEvent(
                event = "bt.discovery.cancel.skipped",
                sessionId = sessionId,
                fields = mapOf("reason" to "missing BLUETOOTH_SCAN permission")
            )
            return
        }
        val startedAt = SystemClock.elapsedRealtime()
        runCatching { adapter.cancelDiscovery() }
            .onSuccess { canceled ->
                debugLog.appendEvent(
                    event = "bt.discovery.cancel.success",
                    sessionId = sessionId,
                    fields = mapOf(
                        "result" to canceled,
                        "elapsedMs" to elapsedSince(startedAt)
                    )
                )
            }
            .onFailure { throwable ->
                Log.w(TAG, "cancelDiscovery skipped: ${throwable.message}")
                debugLog.appendEvent(
                    event = "bt.discovery.cancel.failed",
                    sessionId = sessionId,
                    fields = mapOf(
                        "elapsedMs" to elapsedSince(startedAt),
                        "errorClass" to throwable::class.java.name,
                        "message" to throwable.message.orEmpty()
                    ),
                    throwable = throwable
                )
            }
    }

    @SuppressLint("MissingPermission")
    private fun connectLogged(socket: BluetoothSocket, sessionId: String, device: BluetoothDevice) {
        val startedAt = SystemClock.elapsedRealtime()
        debugLog.appendEvent("bt.socket.connect.start", sessionId, deviceFields(device))
        try {
            socket.connect()
            debugLog.appendEvent(
                event = "bt.socket.connect.success",
                sessionId = sessionId,
                fields = deviceFields(device) + mapOf("elapsedMs" to elapsedSince(startedAt))
            )
        } catch (throwable: Throwable) {
            debugLog.appendEvent(
                event = "bt.socket.connect.failed",
                sessionId = sessionId,
                fields = deviceFields(device) + mapOf(
                    "elapsedMs" to elapsedSince(startedAt),
                    "errorClass" to throwable::class.java.name,
                    "message" to throwable.message.orEmpty()
                ),
                throwable = throwable
            )
            throw throwable
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectLibrarySocketWithSdpRecovery(
        device: BluetoothDevice,
        sessionId: String,
        onSocketChanged: (BluetoothSocket?) -> Unit
    ): BluetoothSocket {
        var completedFastFailureRetries = 0
        while (true) {
            val attempt = completedFastFailureRetries + 1
            val createStartedAt = SystemClock.elapsedRealtime()
            val candidate = device.createRfcommSocketToServiceRecord(BluetoothSyncProtocol.SERVICE_UUID)
            onSocketChanged(candidate)
            debugLog.appendEvent(
                event = "bt.socket.create.success",
                sessionId = sessionId,
                fields = deviceFields(device) + mapOf(
                    "attempt" to attempt,
                    "elapsedMs" to elapsedSince(createStartedAt)
                )
            )

            val connectStartedAt = SystemClock.elapsedRealtime()
            val result = runCatching { connectLogged(candidate, sessionId, device) }
            if (result.isSuccess) {
                if (completedFastFailureRetries > 0) {
                    debugLog.appendEvent(
                        event = "bt.library.connect.recovered",
                        sessionId = sessionId,
                        fields = deviceFields(device) + mapOf("attempt" to attempt)
                    )
                }
                return candidate
            }

            currentCoroutineContext().ensureActive()
            val throwable = result.exceptionOrNull() ?: error("蓝牙连接失败但没有异常")
            val elapsedMs = elapsedSince(connectStartedAt)
            val recovery = sdpSyncConnectFailureRecovery(
                elapsedMs = elapsedMs,
                completedFastFailureRetries = completedFastFailureRetries,
                retryableIoFailure = throwable is IOException
            )
            debugLog.appendEvent(
                event = "bt.library.connect.recovery",
                sessionId = sessionId,
                fields = failureFields(throwable) + deviceFields(device) + mapOf(
                    "attempt" to attempt,
                    "elapsedMs" to elapsedMs,
                    "recoveryDelayMs" to recovery.delayMs,
                    "retrySameDevice" to recovery.retrySameCandidate
                ),
                throwable = throwable
            )
            if (!recovery.retrySameCandidate) throw throwable

            closeSocketLogged(candidate, sessionId, "library-connect-failed")
            onSocketChanged(null)
            completedFastFailureRetries += 1
            delay(recovery.delayMs)
        }
    }

    private fun writeFrameLogged(
        socket: BluetoothSocket,
        sessionId: String,
        label: String,
        payload: JSONObject,
        byteTracker: ByteTransferTracker? = null,
        onBytesTransferred: ((Long) -> Unit)? = null
    ) = writeFrameLogged(
        outputStream = socket.outputStream,
        sessionId = sessionId,
        label = label,
        payload = payload,
        byteTracker = byteTracker,
        onBytesTransferred = onBytesTransferred
    )

    private fun writeFrameLogged(
        outputStream: OutputStream,
        sessionId: String,
        label: String,
        payload: JSONObject,
        byteTracker: ByteTransferTracker? = null,
        onBytesTransferred: ((Long) -> Unit)? = null
    ) {
        val startedAt = SystemClock.elapsedRealtime()
        val fields = payloadFields(label, payload)
        debugLog.appendEvent("bt.frame.write.start", sessionId, mapOf("label" to label) + fields)
        try {
            BluetoothSyncProtocol.writeFrame(outputStream, payload) { bytes ->
                byteTracker?.add(bytes)
                onBytesTransferred?.invoke(bytes)
            }
            debugLog.appendEvent(
                event = "bt.frame.write.success",
                sessionId = sessionId,
                fields = mapOf("label" to label, "elapsedMs" to elapsedSince(startedAt)) + fields
            )
        } catch (throwable: Throwable) {
            debugLog.appendEvent(
                event = "bt.frame.write.failed",
                sessionId = sessionId,
                fields = mapOf(
                    "label" to label,
                    "elapsedMs" to elapsedSince(startedAt),
                    "errorClass" to throwable::class.java.name,
                    "message" to throwable.message.orEmpty()
                ) + fields,
                throwable = throwable
            )
            throw throwable
        }
    }

    private fun readFrameLogged(
        socket: BluetoothSocket,
        sessionId: String,
        label: String,
        byteTracker: ByteTransferTracker? = null,
        onBytesTransferred: ((Long) -> Unit)? = null
    ): JSONObject = readFrameLogged(
        inputStream = socket.inputStream,
        sessionId = sessionId,
        label = label,
        byteTracker = byteTracker,
        onBytesTransferred = onBytesTransferred
    )

    private fun readFrameLogged(
        inputStream: InputStream,
        sessionId: String,
        label: String,
        byteTracker: ByteTransferTracker? = null,
        onBytesTransferred: ((Long) -> Unit)? = null
    ): JSONObject {
        val startedAt = SystemClock.elapsedRealtime()
        debugLog.appendEvent("bt.frame.read.start", sessionId, mapOf("label" to label))
        return try {
            BluetoothSyncProtocol.readFrame(inputStream) { bytes ->
                byteTracker?.add(bytes)
                onBytesTransferred?.invoke(bytes)
            }.also { payload ->
                debugLog.appendEvent(
                    event = "bt.frame.read.success",
                    sessionId = sessionId,
                    fields = mapOf("label" to label, "elapsedMs" to elapsedSince(startedAt)) +
                        payloadFields(label, payload)
                )
            }
        } catch (throwable: Throwable) {
            debugLog.appendEvent(
                event = "bt.frame.read.failed",
                sessionId = sessionId,
                fields = mapOf(
                    "label" to label,
                    "elapsedMs" to elapsedSince(startedAt),
                    "errorClass" to throwable::class.java.name,
                    "message" to throwable.message.orEmpty()
                ),
                throwable = throwable
            )
            throw throwable
        }
    }

    private fun writeResponseAck(socket: BluetoothSocket, sessionId: String): Throwable? {
        return writeResponseAck(socket, sessionId, success = true, applied = true)
    }

    private fun writeResponseAck(
        socket: BluetoothSocket,
        sessionId: String,
        success: Boolean,
        applied: Boolean,
        phase: String = BluetoothSyncProtocol.ACK_PHASE_APPLIED,
        message: String? = null
    ): Throwable? = writeResponseAck(
        outputStream = socket.outputStream,
        sessionId = sessionId,
        success = success,
        applied = applied,
        phase = phase,
        message = message
    )

    private fun writeResponseAck(
        outputStream: OutputStream,
        sessionId: String,
        success: Boolean,
        applied: Boolean,
        phase: String = BluetoothSyncProtocol.ACK_PHASE_APPLIED,
        message: String? = null
    ): Throwable? {
        val failure = captureResponseAckFailure {
            writeFrameLogged(
                outputStream = outputStream,
                sessionId = sessionId,
                label = "ack",
                payload = JSONObject().apply {
                    put("action", BluetoothSyncProtocol.ACTION_ACK)
                    put("phase", phase)
                    put("success", success)
                    put("applied", applied)
                    message?.takeIf { it.isNotBlank() }?.let { put("message", it) }
                }
            )
        }
        failure?.let { throwable ->
            Log.w(TAG, "response ack skipped: ${throwable.message}")
        }
        return failure
    }

    private fun invalidateSessionTransportAfterCommittedResponse(
        session: PhoneSyncSession,
        sessionId: String,
        action: String,
        throwable: Throwable
    ) {
        debugLog.appendEvent(
            event = "sync.session.ack.failed.response-committed",
            sessionId = sessionId,
            fields = failureFields(throwable) + mapOf(
                "action" to action,
                "deviceAddress" to session.device.address
            ),
            throwable = throwable
        )
        session.transport?.close()
        session.transport = null
    }

    private fun closeSocketLogged(socket: BluetoothSocket, sessionId: String, owner: String) {
        val startedAt = SystemClock.elapsedRealtime()
        runCatching {
            socket.close()
        }.onSuccess {
            debugLog.appendEvent(
                event = "bt.socket.close.success",
                sessionId = sessionId,
                fields = mapOf("owner" to owner, "elapsedMs" to elapsedSince(startedAt))
            )
        }.onFailure { throwable ->
            Log.w(TAG, "socket close ignored after $owner exchange: ${throwable.message}")
            debugLog.appendEvent(
                event = "bt.socket.close.failed",
                sessionId = sessionId,
                fields = mapOf(
                    "owner" to owner,
                    "elapsedMs" to elapsedSince(startedAt),
                    "errorClass" to throwable::class.java.name,
                    "message" to throwable.message.orEmpty()
                ),
                throwable = throwable
            )
        }
    }

    @OptIn(InternalCoroutinesApi::class)
    private suspend fun installSessionCancellationLogger(
        session: PhoneSyncSession,
        sessionId: String,
        action: String
    ) = currentCoroutineContext()[Job]?.invokeOnCompletion(
        onCancelling = true,
        invokeImmediately = false
    ) { cause ->
        if (cause !is CancellationException) return@invokeOnCompletion
        val transport = session.transport ?: return@invokeOnCompletion
        runCatching { transport.close() }
        if (session.transport === transport) session.transport = null
        debugLog.appendEvent(
            event = "sync.session.close.cancelled",
            sessionId = sessionId,
            fields = mapOf(
                "action" to action,
                "owner" to transport.owner,
                "message" to cause.message.orEmpty()
            )
        )
    }

    @OptIn(InternalCoroutinesApi::class)
    private suspend fun installSocketCancellationLogger(
        sessionId: String,
        owner: String,
        socketProvider: () -> BluetoothSocket?
    ) = currentCoroutineContext()[Job]?.invokeOnCompletion(
        onCancelling = true,
        invokeImmediately = false
    ) { cause ->
        if (cause !is CancellationException) return@invokeOnCompletion
        val socket = socketProvider() ?: return@invokeOnCompletion
        val startedAt = SystemClock.elapsedRealtime()
        runCatching {
            socket.close()
        }.onSuccess {
            debugLog.appendEvent(
                event = "bt.socket.close.cancelled",
                sessionId = sessionId,
                fields = mapOf(
                    "owner" to owner,
                    "elapsedMs" to elapsedSince(startedAt),
                    "message" to cause.message.orEmpty()
                )
            )
        }.onFailure { throwable ->
            debugLog.appendEvent(
                event = "bt.socket.close.cancelled.failed",
                sessionId = sessionId,
                fields = mapOf(
                    "owner" to owner,
                    "elapsedMs" to elapsedSince(startedAt),
                    "errorClass" to throwable::class.java.name,
                    "message" to throwable.message.orEmpty()
                ),
                throwable = throwable
            )
        }
    }

    @OptIn(InternalCoroutinesApi::class)
    private suspend fun installIpCancellationLogger(
        ipSession: PhoneIpSyncSession,
        sessionId: String,
        owner: String
    ) = currentCoroutineContext()[Job]?.invokeOnCompletion(
        onCancelling = true,
        invokeImmediately = false
    ) { cause ->
        if (cause !is CancellationException) return@invokeOnCompletion
        runCatching { ipSession.close() }
        debugLog.appendEvent(
            event = "ip.session.close.cancelled",
            sessionId = sessionId,
            fields = mapOf(
                "owner" to owner,
                "watchDeviceId" to ipSession.watchDeviceId,
                "message" to cause.message.orEmpty()
            )
        )
    }

    private fun validateBatchFrame(
        frame: JSONObject,
        label: String,
        expectedPhase: String,
        expectedIndex: Int,
        expectedBatchCount: Int?
    ): Int {
        require(frame.optString("action") == BluetoothSyncProtocol.ACTION_SYNC_LIBRARY) {
            "$label 批次动作异常：${frame.optString("action")}"
        }
        require(frame.optString("phase") == expectedPhase) {
            "$label 批次阶段异常：${frame.optString("phase")}"
        }
        val batchCount = frame.optInt("batchCount", 1)
        require(batchCount in 1..LibrarySyncPayload.MAX_ARTICLE_REQUEST_BATCH_COUNT) {
            "$label 批次数异常：$batchCount"
        }
        expectedBatchCount?.let { expected ->
            require(batchCount == expected) {
                "$label 批次数不一致：$batchCount/$expected"
            }
        }
        if (batchCount > 1 || frame.has("batchIndex") || frame.has("batchCount")) {
            require(frame.has("batchIndex") && frame.has("batchCount")) {
                "$label 批次字段不完整"
            }
            require(frame.optInt("batchIndex") == expectedIndex) {
                "$label 批次序号异常：${frame.optInt("batchIndex")}，期望 $expectedIndex"
            }
        }
        return batchCount
    }

    private fun cachedDeviceAddress(): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_DEVICE_ADDRESS, null)
            ?.takeIf { it.isNotBlank() }

    private fun probeCandidateOffset(): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_PROBE_CANDIDATE_OFFSET, 0)

    private fun rememberProbeCandidateOffset(offset: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_PROBE_CANDIDATE_OFFSET, offset)
            .apply()
    }

    private fun buildProbeManifestRequest(deviceId: String): JSONObject =
        LibrarySyncPayload.buildManifestRequestFromEntries(
            deviceId = deviceId,
            articleManifest = emptyList(),
            rssSources = emptyList(),
            changeSequence = LibraryChangeSequence(
                fromSeqExclusive = 0L,
                toSeqInclusive = 0L,
                fullSnapshot = true,
                fallbackReason = "probe"
            )
        )

    private fun probeFailureMessage(throwable: Throwable): String =
        when (throwable) {
            is TimeoutCancellationException -> "探测超时"
            else -> throwable.message?.takeIf { it.isNotBlank() }
                ?: throwable::class.java.simpleName
        }

    private fun failureFields(throwable: Throwable): Map<String, Any?> =
        mapOf(
            "errorClass" to throwable::class.java.name,
            "message" to throwable.message.orEmpty()
        )

    private fun isIpTransportFailure(throwable: Throwable): Boolean {
        var current: Throwable? = throwable
        while (current != null) {
            if (current is IOException) return true
            if (current is IllegalStateException) {
                val message = current.message.orEmpty().lowercase()
                if (
                    message.contains("ip 同步连接已关闭") ||
                    message.contains("ip 同步通道已关闭") ||
                    message.contains("pipe closed") ||
                    message.contains("write end dead") ||
                    message.contains("read end dead")
                ) return true
            }
            current = current.cause
        }
        return false
    }

    private fun isSessionTransportFailure(throwable: Throwable): Boolean {
        if (isIpTransportFailure(throwable)) return true
        var current: Throwable? = throwable
        while (current != null) {
            val message = current.message.orEmpty().lowercase()
            if (
                message.contains("connection reset") ||
                message.contains("bluetooth socket failure") ||
                message.contains("socket closed") ||
                message.contains("持久同步连接已关闭")
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    @SuppressLint("MissingPermission")
    private fun rememberSuccessfulDevice(device: BluetoothDevice, sessionId: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_DEVICE_ADDRESS, device.address)
            .apply()
        debugLog.appendEvent("bt.device.cache.updated", sessionId, deviceFields(device))
    }

    private class ByteTransferTracker {
        private var totalBytes = 0L
        private var startTime = 0L

        fun start() {
            totalBytes = 0L
            startTime = SystemClock.elapsedRealtime()
        }

        fun add(bytes: Long) {
            totalBytes += bytes
        }

        fun bytesTransferred(): Long = totalBytes

        fun bytesPerSecond(): Long {
            val elapsed = SystemClock.elapsedRealtime() - startTime
            if (elapsed <= 0) return 0L
            return (totalBytes * 1000L) / elapsed
        }
    }

    @SuppressLint("MissingPermission")
    private fun deviceFields(device: BluetoothDevice): Map<String, Any?> {
        return mapOf(
            "deviceName" to device.name.orEmpty(),
            "deviceAddress" to device.address,
            "deviceUuidCount" to (device.uuids?.size ?: 0)
        )
    }

    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.toWatchDevice(
        remoteDeviceId: String = "",
        supportsPersistentSession: Boolean = false
    ): PhoneBluetoothWatchDevice =
        PhoneBluetoothWatchDevice(
            name = name.orEmpty(),
            address = address,
            uuidCount = uuids?.size ?: 0,
            remoteDeviceId = remoteDeviceId,
            supportsPersistentSession = supportsPersistentSession
        )

    private fun payloadFields(prefix: String, payload: JSONObject): Map<String, Any?> {
        return buildMap {
            put("${prefix}Bytes", runCatching { BluetoothSyncProtocol.encodedSize(payload) }.getOrDefault(-1))
            put("${prefix}Action", payload.optString("action").ifBlank { null })
            put("${prefix}Version", if (payload.has("version")) payload.optInt("version") else null)
            put("${prefix}Phase", payload.optString("phase").ifBlank { null })
            put("${prefix}Success", if (payload.has("success")) payload.optBoolean("success") else null)
            put("${prefix}Message", payload.optString("message").ifBlank { null })
            put("${prefix}ArticleManifestCount", payload.optJSONArray("articleManifest")?.length())
            put("${prefix}ArticleCount", payload.optJSONArray("articles")?.length())
            put("${prefix}BodyRequestCount", payload.optJSONArray("bodyRequests")?.length())
            put("${prefix}RssSourceCount", payload.optJSONArray("rssSources")?.length())
            put("${prefix}ItemCount", payload.optJSONArray("items")?.length())
            put("${prefix}Count", if (payload.has("count")) payload.optInt("count") else null)
            put("${prefix}Applied", if (payload.has("applied")) payload.optInt("applied") else null)
            put("${prefix}SourcesApplied", if (payload.has("sourcesApplied")) payload.optInt("sourcesApplied") else null)
            put("${prefix}BatchIndex", if (payload.has("batchIndex")) payload.optInt("batchIndex") else null)
            put("${prefix}BatchCount", if (payload.has("batchCount")) payload.optInt("batchCount") else null)
            put(
                "${prefix}BatchWireBytes",
                if (payload.has(LibrarySyncPayload.FIELD_BATCH_WIRE_BYTES)) {
                    payload.optLong(LibrarySyncPayload.FIELD_BATCH_WIRE_BYTES)
                } else {
                    null
                }
            )
            put(
                "${prefix}BatchTotalWireBytes",
                if (payload.has(LibrarySyncPayload.FIELD_BATCH_TOTAL_WIRE_BYTES)) {
                    payload.optLong(LibrarySyncPayload.FIELD_BATCH_TOTAL_WIRE_BYTES)
                } else {
                    null
                }
            )
            put("${prefix}TotalArticles", if (payload.has("totalArticles")) payload.optInt("totalArticles") else null)
        }
    }

    private fun frameStats(payloads: List<JSONObject>): LibraryFrameStats {
        return payloads.fold(EMPTY_FRAME_STATS) { stats, payload ->
            stats + frameStats(payload)
        }
    }

    private fun frameStats(payload: JSONObject): LibraryFrameStats {
        val bytes = runCatching { BluetoothSyncProtocol.encodedSize(payload) }.getOrDefault(0)
        return LibraryFrameStats(
            frameCount = 1,
            totalBytes = bytes.toLong(),
            totalWireBytes = BluetoothSyncProtocol.wireSize(payload),
            maxFrameBytes = bytes,
            articleCount = payload.optJSONArray("articles")?.length() ?: 0
        )
    }

    private operator fun LibraryFrameStats.plus(other: LibraryFrameStats): LibraryFrameStats {
        return LibraryFrameStats(
            frameCount = frameCount + other.frameCount,
            totalBytes = totalBytes + other.totalBytes,
            totalWireBytes = totalWireBytes + other.totalWireBytes,
            maxFrameBytes = maxOf(maxFrameBytes, other.maxFrameBytes),
            articleCount = articleCount + other.articleCount
        )
    }

    private fun frameStatsFields(prefix: String, stats: LibraryFrameStats): Map<String, Any?> {
        return mapOf(
            "${prefix}FrameCount" to stats.frameCount,
            "${prefix}TotalBytes" to stats.totalBytes,
            "${prefix}TotalWireBytes" to stats.totalWireBytes,
            "${prefix}MaxFrameBytes" to stats.maxFrameBytes,
            "${prefix}ArticleCount" to stats.articleCount
        )
    }

    private fun batchLabel(prefix: String, index: Int, count: Int): String {
        if (count <= 1) return prefix
        return "$prefix[${index + 1}/$count]"
    }

    private fun percentBetween(start: Int, end: Int, completed: Int, total: Int): Int {
        val safeTotal = total.coerceAtLeast(1)
        val ratio = completed.coerceIn(0, safeTotal).toFloat() / safeTotal.toFloat()
        return (start + ((end - start) * ratio)).toInt().coerceIn(0, 100)
    }

    private fun percentBetweenBytes(start: Int, end: Int, completedBytes: Long, totalBytes: Long): Int {
        val safeTotal = totalBytes.coerceAtLeast(1L)
        val ratio = completedBytes.coerceIn(0L, safeTotal).toDouble() / safeTotal.toDouble()
        return (start + ((end - start) * ratio)).toInt().coerceIn(0, 100)
    }

    private fun JSONObject.optPositiveLong(name: String): Long? =
        optLong(name, 0L).takeIf { it > 0L }

    private fun elapsedSince(startedAt: Long): Long =
        SystemClock.elapsedRealtime() - startedAt

    companion object {
        private const val PREVIEW_MIN_FRAME_INTERVAL_MS = 32L
        private const val TAG = "WatchRSS_BtSyncClient"
        private const val PHASE_COMPLETE = "complete"
        private const val MAX_DEVICE_PROBE_CANDIDATES = 9
        private const val DEFAULT_DEVICE_PROBE_TIMEOUT_MS = 2_000L
        private const val ACTIVE_WATCH_PROFILE_QUERY_TIMEOUT_MS = 750L
        // ColorOS reports PAGE_TIMEOUT after roughly 5.2 seconds. Closing the socket before that
        // leaves the global SDP slot busy and makes later watch probes fail without starting.
        private const val DIRECT_PROBE_TIMEOUT_MS = 7_000L
        private const val PROBE_FAILURE_COOLDOWN_MS = 250L
        private const val SDP_BUSY_FAST_FAILURE_THRESHOLD_MS = 1_000L
        private const val SDP_STACK_DRAIN_DELAY_MS = 1_500L
        private const val MAX_FAST_SDP_FAILURE_RETRIES = 1
        // The paired RFCOMM probe has already proved reachability. Keep the initial wait bounded;
        // a route that arrives after it is checked once more by remoteDeviceId before transfer.
        private const val IP_UPGRADE_WAIT_MS = 3_000L
        private const val LATE_IP_UPGRADE_WAIT_MS = 1_000L
        private const val IP_UPGRADE_POLL_INTERVAL_MS = 50L
        private const val FIELD_IP_ENDPOINT_DESCRIPTOR = "ipEndpointDescriptor"
        private const val FIELD_IP_UPGRADE_ACCEPTED = "ipUpgradeAccepted"
        private const val PREFS_NAME = "watchrss_bluetooth_sync"
        private const val KEY_LAST_DEVICE_ADDRESS = "last_successful_device_address"
        private const val KEY_PROBE_CANDIDATE_OFFSET = "probe_candidate_offset"
        private val ACTIVE_WATCH_PROFILE_IDS = listOf(
            BluetoothProfile.A2DP,
            BluetoothProfile.HEADSET
        )
        private val EMPTY_FRAME_STATS = LibraryFrameStats(
            frameCount = 0,
            totalBytes = 0L,
            totalWireBytes = 0L,
            maxFrameBytes = 0,
            articleCount = 0
        )

        internal fun sdpProbeFailureRecovery(
            elapsedMs: Long,
            timedOut: Boolean,
            completedFastFailureRetries: Int
        ): SdpProbeFailureRecovery {
            val fastFailure = !timedOut && elapsedMs < SDP_BUSY_FAST_FAILURE_THRESHOLD_MS
            return SdpProbeFailureRecovery(
                delayMs = if (timedOut || fastFailure) {
                    SDP_STACK_DRAIN_DELAY_MS
                } else {
                    PROBE_FAILURE_COOLDOWN_MS
                },
                retrySameCandidate = fastFailure &&
                    completedFastFailureRetries < MAX_FAST_SDP_FAILURE_RETRIES
            )
        }

        internal fun sdpSyncConnectFailureRecovery(
            elapsedMs: Long,
            completedFastFailureRetries: Int,
            retryableIoFailure: Boolean
        ): SdpProbeFailureRecovery {
            val recovery = sdpProbeFailureRecovery(
                elapsedMs = elapsedMs,
                timedOut = false,
                completedFastFailureRetries = completedFastFailureRetries
            )
            return recovery.copy(
                delayMs = if (retryableIoFailure && recovery.retrySameCandidate) {
                    recovery.delayMs
                } else {
                    0L
                },
                retrySameCandidate = retryableIoFailure && recovery.retrySameCandidate
            )
        }
    }
}

internal data class SdpProbeFailureRecovery(
    val delayMs: Long,
    val retrySameCandidate: Boolean
)

internal class SingleSessionRecoveryGate {
    private var acquired = false

    fun tryAcquire(): Boolean {
        if (acquired) return false
        acquired = true
        return true
    }
}

internal inline fun captureResponseAckFailure(writeAck: () -> Unit): Throwable? =
    runCatching(writeAck).exceptionOrNull()

internal fun pendingLateIpUpgradeDeviceId(
    ipUpgradeExpected: Boolean,
    remoteDeviceId: String,
    transportOwner: String?
): String? = remoteDeviceId.trim().takeIf {
    ipUpgradeExpected &&
        it.isNotEmpty() &&
        transportOwner == "rfcomm"
}

internal fun shouldRetainProbeSession(
    candidateCount: Int,
    candidateAddress: String,
    activeWatchAddresses: Set<String>,
    cachedWatchAddress: String?
): Boolean = candidateCount == 1 ||
    candidateAddress.uppercase() in activeWatchAddresses ||
    candidateAddress.equals(cachedWatchAddress, ignoreCase = true)

internal fun shouldReleaseProbeSession(reachableCount: Int): Boolean = reachableCount != 1

internal fun shouldStopAfterPrioritizedWatchProbe(
    candidateAddress: String,
    activeWatchAddresses: Set<String>,
    cachedWatchAddress: String?,
    reachable: Boolean
): Boolean = reachable && (
    candidateAddress.uppercase() in activeWatchAddresses ||
        candidateAddress.equals(cachedWatchAddress, ignoreCase = true)
)

internal data class RotatingProbeCandidateWindow<T>(
    val candidates: List<T>,
    val startOffset: Int,
    val nextOffset: Int
)

internal fun <T> rotatingProbeCandidateWindow(
    candidates: List<T>,
    maxCandidates: Int,
    startOffset: Int,
    prioritizedCandidates: List<T> = emptyList()
): RotatingProbeCandidateWindow<T> {
    if (candidates.isEmpty() || maxCandidates <= 0) {
        return RotatingProbeCandidateWindow(emptyList(), startOffset = 0, nextOffset = 0)
    }
    val candidateSet = candidates.toSet()
    val fixedCandidates = prioritizedCandidates
        .filter { it in candidateSet }
        .distinct()
        .take(maxCandidates)
    val fixedSet = fixedCandidates.toSet()
    val rotatingCandidates = candidates.filterNot { it in fixedSet }
    val rotatingCapacity = maxCandidates - fixedCandidates.size
    if (rotatingCapacity <= 0) {
        return RotatingProbeCandidateWindow(fixedCandidates, startOffset = 0, nextOffset = 0)
    }
    if (rotatingCandidates.size <= rotatingCapacity) {
        return RotatingProbeCandidateWindow(
            candidates = fixedCandidates + rotatingCandidates,
            startOffset = 0,
            nextOffset = 0
        )
    }
    val normalizedOffset = Math.floorMod(startOffset, rotatingCandidates.size)
    val selected = List(rotatingCapacity) { index ->
        rotatingCandidates[(normalizedOffset + index) % rotatingCandidates.size]
    }
    return RotatingProbeCandidateWindow(
        candidates = fixedCandidates + selected,
        startOffset = normalizedOffset,
        nextOffset = (normalizedOffset + rotatingCapacity) % rotatingCandidates.size
    )
}

private fun JSONObject.toWatchCapabilities(): PhoneWatchCapabilities {
    val decoders = optJSONArray("videoDecoders")
    return PhoneWatchCapabilities(
        widthPx = optInt("widthPx").coerceAtLeast(0),
        heightPx = optInt("heightPx").coerceAtLeast(0),
        refreshRateHz = optDouble("refreshRateHz", 0.0).coerceAtLeast(0.0),
        availableBytes = optLong("availableBytes", 0L).coerceAtLeast(0L),
        videoDecoders = buildList {
            for (index in 0 until (decoders?.length() ?: 0)) {
                val decoder = decoders?.optJSONObject(index) ?: continue
                val profiles = decoder.optJSONArray("profiles")
                add(
                    PhoneWatchVideoDecoder(
                        name = decoder.optString("name"),
                        mime = decoder.optString("mime"),
                        hardwareAccelerated = decoder.optBoolean("hardwareAccelerated"),
                        maxWidth = decoder.optInt("maxWidth"),
                        maxHeight = decoder.optInt("maxHeight"),
                        maxFrameRate = decoder.optDouble("maxFrameRate"),
                        profileLevels = buildList {
                            for (profileIndex in 0 until (profiles?.length() ?: 0)) {
                                val profile = profiles?.optJSONObject(profileIndex) ?: continue
                                add(profile.optInt("profile") to profile.optInt("level"))
                            }
                        }
                    )
                )
            }
        }
    )
}
