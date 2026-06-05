package com.lightningstudio.watchrss.phone.connection.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.lightningstudio.watchrss.phone.data.log.BluetoothDebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

data class BluetoothSyncExchange(
    val deviceName: String,
    val deviceAddress: String,
    val request: JSONObject,
    val response: JSONObject
)

data class BluetoothLibrarySyncExchange(
    val deviceName: String,
    val deviceAddress: String,
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

data class PhoneBluetoothWatchDevice(
    val name: String,
    val address: String,
    val uuidCount: Int
)

data class PhoneBluetoothWatchProbeResult(
    val device: PhoneBluetoothWatchDevice,
    val reachable: Boolean,
    val message: String? = null
)

class PhoneBluetoothSyncClient(
    private val context: Context,
    private val debugLog: BluetoothDebugLog
) {
    @SuppressLint("MissingPermission")
    suspend fun probeLibrarySyncDevices(
        deviceId: String,
        sessionId: String = BluetoothDebugLog.newSessionId("bt-library-probe"),
        perDeviceTimeoutMs: Long = DEFAULT_DEVICE_PROBE_TIMEOUT_MS,
        onProbe: (completed: Int, total: Int, result: PhoneBluetoothWatchProbeResult) -> Unit = { _, _, _ -> }
    ): List<PhoneBluetoothWatchProbeResult> {
        val startedAt = SystemClock.elapsedRealtime()
        debugLog.appendEvent(
            event = "bt.library.probe.start",
            sessionId = sessionId,
            fields = mapOf("perDeviceTimeoutMs" to perDeviceTimeoutMs)
        )
        requireBluetoothConnectPermission()
        val adapter = context.getSystemService(BluetoothManager::class.java)
            ?.adapter
            ?: error("此设备没有蓝牙适配器")
        require(adapter.isEnabled) { "蓝牙未开启" }

        val bondedDevices = adapter.bondedDevices.orEmpty()
        logAdapterSnapshot(sessionId, adapter, bondedDevices)
        val allCandidates = probeCandidateWatchDevices(bondedDevices)
        val candidates = allCandidates.take(MAX_DEVICE_PROBE_CANDIDATES)
        debugLog.appendEvent(
            event = "bt.library.probe.candidates",
            sessionId = sessionId,
            fields = mapOf(
                "candidates" to allCandidates.size,
                "probedCandidates" to candidates.size,
                "skippedCandidates" to (allCandidates.size - candidates.size).coerceAtLeast(0),
                "cachedAddress" to cachedDeviceAddress().orEmpty()
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
            return emptyList()
        }

        val results = mutableListOf<PhoneBluetoothWatchProbeResult>()
        val cachedAddress = cachedDeviceAddress()
        candidates.forEachIndexed { index, device ->
            val result = probeLibrarySyncDevice(
                device = device,
                deviceId = deviceId,
                sessionId = "$sessionId-${index + 1}",
                timeoutMs = perDeviceTimeoutMs
            )
            results += result
            onProbe(index + 1, candidates.size, result)
            if (
                result.reachable &&
                cachedAddress != null &&
                device.address.equals(cachedAddress, ignoreCase = true)
            ) {
                debugLog.appendEvent(
                    event = "bt.library.probe.cached.hit",
                    sessionId = sessionId,
                    fields = deviceFields(device) + mapOf("elapsedMs" to elapsedSince(startedAt))
                )
                debugLog.appendEvent(
                    event = "bt.library.probe.complete",
                    sessionId = sessionId,
                    fields = mapOf(
                        "candidates" to candidates.size,
                        "reachable" to results.count { it.reachable },
                        "elapsedMs" to elapsedSince(startedAt),
                        "shortCircuited" to true
                    )
                )
                return results
            }
        }
        debugLog.appendEvent(
            event = "bt.library.probe.complete",
            sessionId = sessionId,
            fields = mapOf(
                "candidates" to candidates.size,
                "reachable" to results.count { it.reachable },
                "elapsedMs" to elapsedSince(startedAt)
            )
        )
        return results
    }

    @SuppressLint("MissingPermission")
    suspend fun exchange(
        request: JSONObject,
        deviceAddress: String? = null,
        deviceNameHint: String? = null,
        sessionId: String = BluetoothDebugLog.newSessionId("bt-quick"),
        rememberDeviceOnSuccess: Boolean = true
    ): BluetoothSyncExchange {
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
                "targetAddress" to deviceAddress.orEmpty()
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
                deviceAddress = deviceAddress,
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

    @SuppressLint("MissingPermission")
    suspend fun exchangeLibrary(
        manifestRequest: JSONObject? = null,
        buildManifestRequest: (suspend (BluetoothDevice) -> JSONObject)? = null,
        buildArticleRequests: suspend (JSONObject, Boolean) -> List<JSONObject>,
        deviceAddress: String? = null,
        deviceNameHint: String? = null,
        sessionId: String = BluetoothDebugLog.newSessionId("bt-library"),
        onProgress: (PhoneBluetoothSyncProgress) -> Unit = {},
        applyResponse: suspend (BluetoothLibrarySyncExchange) -> Unit = {},
        ackApplied: Boolean = true,
        rememberDeviceOnSuccess: Boolean = true
    ): BluetoothLibrarySyncExchange {
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
                "targetAddress" to deviceAddress.orEmpty()
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
                deviceAddress = deviceAddress,
                deviceNameHint = deviceNameHint
            )
            selectedDevice = device
            val request = manifestRequest
                ?: buildManifestRequest?.invoke(device)
                ?: error("缺少资料库同步请求")
            debugLog.appendEvent("bt.device.selected", sessionId, deviceFields(device))
            debugLog.appendEvent("bt.library.manifest.prepared", sessionId, payloadFields("manifestRequest", request))
            Log.i(TAG, "connecting library sync to name=${device.name} address=${device.address} uuid=${BluetoothSyncProtocol.SERVICE_UUID}")
            cancelDiscoveryLogged(adapter, sessionId)

            val createStartedAt = SystemClock.elapsedRealtime()
            socket = device.createRfcommSocketToServiceRecord(BluetoothSyncProtocol.SERVICE_UUID)
            debugLog.appendEvent(
                event = "bt.socket.create.success",
                sessionId = sessionId,
                fields = deviceFields(device) + mapOf("elapsedMs" to elapsedSince(createStartedAt))
            )
            connectLogged(socket, sessionId, device)
            report(PhoneBluetoothSyncStage.CONNECTING, 20)
            writeFrameLogged(socket, sessionId, "manifestRequest", request, tracker)
            report(PhoneBluetoothSyncStage.TRANSFERRING, 25)
            val manifestResponse = readFrameLogged(socket, sessionId, "manifestResponse", tracker)
            if (!manifestResponse.optBoolean("success", true)) {
                debugLog.appendEvent(
                    event = "bt.library.manifest.rejected",
                    sessionId = sessionId,
                    fields = payloadFields("manifestResponse", manifestResponse) +
                        mapOf("elapsedMs" to elapsedSince(totalStartedAt))
                )
                writeResponseAck(socket, sessionId, success = true, applied = true)
                return BluetoothLibrarySyncExchange(
                    deviceName = device.name.orEmpty(),
                    deviceAddress = device.address,
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
                        socket = socket,
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
            val responseRead = readLibraryResponse(socket, sessionId, onProgress, tracker)
            val response = responseRead.response
            report(PhoneBluetoothSyncStage.VERIFYING, 88)
            val exchange = BluetoothLibrarySyncExchange(
                deviceName = device.name.orEmpty(),
                deviceAddress = device.address,
                request = request,
                manifestResponse = manifestResponse,
                articleRequestFrameCount = articleRequestFrameCount,
                responseFrameCount = responseRead.stats.frameCount,
                response = response
            )
            try {
                applyResponse(exchange)
                writeResponseAck(socket, sessionId, success = true, applied = ackApplied)
                if (rememberDeviceOnSuccess) {
                    rememberSuccessfulDevice(device, sessionId)
                }
            } catch (throwable: Throwable) {
                writeResponseAck(
                    socket = socket,
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

    @SuppressLint("MissingPermission")
    private suspend fun probeLibrarySyncDevice(
        device: BluetoothDevice,
        deviceId: String,
        sessionId: String,
        timeoutMs: Long
    ): PhoneBluetoothWatchProbeResult {
        debugLog.appendEvent(
            event = "bt.library.probe.device.start",
            sessionId = sessionId,
            fields = deviceFields(device) + mapOf("timeoutMs" to timeoutMs)
        )
        val result = runCatching {
            probeLibrarySyncDeviceWithV9Fallback(
                device = device,
                deviceId = deviceId,
                sessionId = sessionId,
                fallbackTimeoutMs = timeoutMs
            )
        }
        val probe = result.fold(
            onSuccess = {
                PhoneBluetoothWatchProbeResult(
                    device = device.toWatchDevice(),
                    reachable = true
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
        debugLog.appendEvent(
            event = "bt.library.probe.device.complete",
            sessionId = sessionId,
            fields = deviceFields(device) + mapOf(
                "reachable" to probe.reachable,
                "message" to probe.message.orEmpty()
            )
        )
        return probe
    }

    private suspend fun probeLibrarySyncDeviceWithV9Fallback(
        device: BluetoothDevice,
        deviceId: String,
        sessionId: String,
        fallbackTimeoutMs: Long
    ) {
        val probeResult = runCatching {
            withTimeout(V9_PROBE_TIMEOUT_MS) {
                exchange(
                    request = LibrarySyncPayload.buildProbeRequest(deviceId),
                    deviceAddress = device.address,
                    sessionId = "$sessionId-v9",
                    rememberDeviceOnSuccess = false
                )
            }
        }
        val exchange = probeResult.getOrNull()
        if (exchange != null && LibrarySyncPayload.isProbeResponse(exchange.response)) {
            debugLog.appendEvent(
                event = "bt.library.probe.v9.success",
                sessionId = sessionId,
                fields = payloadFields("probeResponse", exchange.response)
            )
            return
        }
        if (exchange != null && exchange.response.optBoolean("success", false)) {
            debugLog.appendEvent(
                event = "bt.library.probe.v9.compat.success",
                sessionId = sessionId,
                fields = payloadFields("probeResponse", exchange.response)
            )
            return
        }
        probeResult.exceptionOrNull()?.let { throwable ->
            debugLog.appendEvent(
                event = "bt.library.probe.v9.fallback",
                sessionId = sessionId,
                fields = failureFields(throwable),
                throwable = throwable
            )
        }
        withTimeout(fallbackTimeoutMs) {
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
            )
        }
    }

    private fun readLibraryResponse(
        socket: BluetoothSocket,
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
            socket = socket,
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
            report(percentBetweenBytes(60, 84, responseWireBytesRead, responseTotalWireBytes))
        } else {
            report(percentBetween(60, 84, completed, batchCount))
        }
        while (completed < batchCount) {
            val index = completed
            val frame = readFrameLogged(
                socket = socket,
                sessionId = sessionId,
                label = batchLabel("libraryResponse", index, batchCount),
                byteTracker = byteTracker,
                onBytesTransferred = { bytes ->
                    responseWireBytesRead += bytes
                    if (responseTotalWireBytes != null) {
                        report(percentBetweenBytes(60, 84, responseWireBytesRead, responseTotalWireBytes))
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
                report(percentBetween(60, 84, completed, batchCount))
            }
        }
        report(84)
        return LibraryResponseRead(
            response = accumulator.toPayload(),
            stats = stats
        )
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
    private fun probeCandidateWatchDevices(devices: Set<BluetoothDevice>): List<BluetoothDevice> {
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
                if (cachedAddress != null && device.address.equals(cachedAddress, ignoreCase = true)) 0 else 1
            }
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

    private fun writeFrameLogged(
        socket: BluetoothSocket,
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
            BluetoothSyncProtocol.writeFrame(socket.outputStream, payload) { bytes ->
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
    ): JSONObject {
        val startedAt = SystemClock.elapsedRealtime()
        debugLog.appendEvent("bt.frame.read.start", sessionId, mapOf("label" to label))
        return try {
            BluetoothSyncProtocol.readFrame(socket.inputStream) { bytes ->
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

    private fun writeResponseAck(socket: BluetoothSocket, sessionId: String) {
        writeResponseAck(socket, sessionId, success = true, applied = true)
    }

    private fun writeResponseAck(
        socket: BluetoothSocket,
        sessionId: String,
        success: Boolean,
        applied: Boolean,
        message: String? = null
    ) {
        runCatching {
            writeFrameLogged(
                socket = socket,
                sessionId = sessionId,
                label = "ack",
                payload = JSONObject().apply {
                    put("action", BluetoothSyncProtocol.ACTION_ACK)
                    put("phase", BluetoothSyncProtocol.ACK_PHASE_APPLIED)
                    put("success", success)
                    put("applied", applied)
                    message?.takeIf { it.isNotBlank() }?.let { put("message", it) }
                }
            )
        }.onFailure { throwable ->
            Log.w(TAG, "response ack skipped: ${throwable.message}")
        }
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

    private suspend fun installSocketCancellationLogger(
        sessionId: String,
        owner: String,
        socketProvider: () -> BluetoothSocket?
    ) = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
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
    private fun BluetoothDevice.toWatchDevice(): PhoneBluetoothWatchDevice =
        PhoneBluetoothWatchDevice(
            name = name.orEmpty(),
            address = address,
            uuidCount = uuids?.size ?: 0
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
        private const val TAG = "WatchRSS_BtSyncClient"
        private const val PHASE_COMPLETE = "complete"
        private const val MAX_DEVICE_PROBE_CANDIDATES = 3
        private const val DEFAULT_DEVICE_PROBE_TIMEOUT_MS = 2_000L
        private const val V9_PROBE_TIMEOUT_MS = 700L
        private const val PREFS_NAME = "watchrss_bluetooth_sync"
        private const val KEY_LAST_DEVICE_ADDRESS = "last_successful_device_address"
        private val EMPTY_FRAME_STATS = LibraryFrameStats(
            frameCount = 0,
            totalBytes = 0L,
            totalWireBytes = 0L,
            maxFrameBytes = 0,
            articleCount = 0
        )
    }
}
