package com.lightningstudio.watchrss.phone.connection.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
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
    val articleRequestFrames: List<JSONObject>,
    val responseFrames: List<JSONObject>,
    val response: JSONObject
)

class PhoneBluetoothSyncClient(
    private val context: Context,
    private val debugLog: BluetoothDebugLog
) {
    @SuppressLint("MissingPermission")
    fun exchange(
        request: JSONObject,
        deviceAddress: String? = null,
        deviceNameHint: String? = null,
        sessionId: String = BluetoothDebugLog.newSessionId("bt-quick")
    ): BluetoothSyncExchange {
        val totalStartedAt = SystemClock.elapsedRealtime()
        var socket: BluetoothSocket? = null
        var selectedDevice: BluetoothDevice? = null
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
            writeResponseAck(socket, sessionId)
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
        onProgress: (PhoneBluetoothSyncProgress) -> Unit = {}
    ): BluetoothLibrarySyncExchange {
        val totalStartedAt = SystemClock.elapsedRealtime()
        var socket: BluetoothSocket? = null
        var selectedDevice: BluetoothDevice? = null
        onProgress(PhoneBluetoothSyncProgress(PhoneBluetoothSyncStage.CONNECTING, 5))
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
            onProgress(PhoneBluetoothSyncProgress(PhoneBluetoothSyncStage.CONNECTING, 20))
            writeFrameLogged(socket, sessionId, "manifestRequest", request)
            onProgress(PhoneBluetoothSyncProgress(PhoneBluetoothSyncStage.TRANSFERRING, 25))
            val manifestResponse = readFrameLogged(socket, sessionId, "manifestResponse")
            if (!manifestResponse.optBoolean("success", true)) {
                debugLog.appendEvent(
                    event = "bt.library.manifest.rejected",
                    sessionId = sessionId,
                    fields = payloadFields("manifestResponse", manifestResponse) +
                        mapOf("elapsedMs" to elapsedSince(totalStartedAt))
                )
                writeResponseAck(socket, sessionId)
                return BluetoothLibrarySyncExchange(
                deviceName = device.name.orEmpty(),
                deviceAddress = device.address,
                request = request,
                manifestResponse = manifestResponse,
                articleRequestFrames = emptyList(),
                responseFrames = listOf(manifestResponse),
                    response = manifestResponse
                )
            }
            val supportsArticleBatches = manifestResponse.optBoolean("supportsArticleBatches", false)
            val articleRequests = buildArticleRequests(manifestResponse, supportsArticleBatches)
            debugLog.appendEvent(
                event = "bt.library.articles.request.built",
                sessionId = sessionId,
                fields = batchFields("articlesRequest", articleRequests)
            )
            articleRequests.forEachIndexed { index, articleRequest ->
                onProgress(
                    PhoneBluetoothSyncProgress(
                        PhoneBluetoothSyncStage.TRANSFERRING,
                        percentBetween(30, 58, index, articleRequests.size)
                    )
                )
                writeFrameLogged(
                    socket = socket,
                    sessionId = sessionId,
                    label = batchLabel("articlesRequest", index, articleRequests.size),
                    payload = articleRequest
                )
                onProgress(
                    PhoneBluetoothSyncProgress(
                        PhoneBluetoothSyncStage.TRANSFERRING,
                        percentBetween(30, 58, index + 1, articleRequests.size)
                    )
                )
            }
            val responseFrames = readLibraryResponseFrames(socket, sessionId, onProgress)
            val response = LibrarySyncPayload.combineArticlePayloads(responseFrames)
            onProgress(PhoneBluetoothSyncProgress(PhoneBluetoothSyncStage.VERIFYING, 88))
            writeResponseAck(socket, sessionId)
            Log.i(TAG, "library exchange complete manifest=$manifestResponse response=$response")
            debugLog.appendEvent(
                event = "bt.library.complete",
                sessionId = sessionId,
                fields = deviceFields(device) + payloadFields("manifestResponse", manifestResponse) +
                    batchFields("articlesRequest", articleRequests) + batchFields("libraryResponse", responseFrames) +
                    payloadFields("combinedLibraryResponse", response) +
                    mapOf("elapsedMs" to elapsedSince(totalStartedAt))
            )
            return BluetoothLibrarySyncExchange(
                deviceName = device.name.orEmpty(),
                deviceAddress = device.address,
                request = request,
                manifestResponse = manifestResponse,
                articleRequestFrames = articleRequests,
                responseFrames = responseFrames,
                response = response
            )
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
            socket?.let { closeSocketLogged(it, sessionId, "library") }
        }
    }

    private fun readLibraryResponseFrames(
        socket: BluetoothSocket,
        sessionId: String,
        onProgress: (PhoneBluetoothSyncProgress) -> Unit
    ): List<JSONObject> {
        onProgress(PhoneBluetoothSyncProgress(PhoneBluetoothSyncStage.TRANSFERRING, 60))
        val first = readFrameLogged(socket, sessionId, "libraryResponse")
        val batchCount = first.optInt("batchCount", 1).coerceAtLeast(1)
        val frames = mutableListOf(first)
        onProgress(
            PhoneBluetoothSyncProgress(
                PhoneBluetoothSyncStage.TRANSFERRING,
                percentBetween(60, 84, frames.size, batchCount)
            )
        )
        while (frames.size < batchCount) {
            val index = frames.size
            frames += readFrameLogged(socket, sessionId, batchLabel("libraryResponse", index, batchCount))
            onProgress(
                PhoneBluetoothSyncProgress(
                    PhoneBluetoothSyncStage.TRANSFERRING,
                    percentBetween(60, 84, frames.size, batchCount)
                )
            )
        }
        return frames
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
        return devices
            .sortedBy { it.name.orEmpty() }
            .firstOrNull { device ->
                val name = device.name.orEmpty()
                name.contains("watch", ignoreCase = true) ||
                    name.contains("OPPO", ignoreCase = true)
            }
            ?: error("未找到已配对手表蓝牙设备")
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
        payload: JSONObject
    ) {
        val startedAt = SystemClock.elapsedRealtime()
        val fields = payloadFields(label, payload)
        debugLog.appendEvent("bt.frame.write.start", sessionId, mapOf("label" to label) + fields)
        try {
            BluetoothSyncProtocol.writeFrame(socket.outputStream, payload)
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
        label: String
    ): JSONObject {
        val startedAt = SystemClock.elapsedRealtime()
        debugLog.appendEvent("bt.frame.read.start", sessionId, mapOf("label" to label))
        return try {
            BluetoothSyncProtocol.readFrame(socket.inputStream).also { payload ->
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
        runCatching {
            writeFrameLogged(
                socket = socket,
                sessionId = sessionId,
                label = "ack",
                payload = JSONObject().apply {
                    put("action", BluetoothSyncProtocol.ACTION_ACK)
                    put("success", true)
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

    @SuppressLint("MissingPermission")
    private fun deviceFields(device: BluetoothDevice): Map<String, Any?> {
        return mapOf(
            "deviceName" to device.name.orEmpty(),
            "deviceAddress" to device.address,
            "deviceUuidCount" to (device.uuids?.size ?: 0)
        )
    }

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
            put("${prefix}TotalArticles", if (payload.has("totalArticles")) payload.optInt("totalArticles") else null)
        }
    }

    private fun batchFields(prefix: String, payloads: List<JSONObject>): Map<String, Any?> {
        val articleCount = payloads.sumOf { it.optJSONArray("articles")?.length() ?: 0 }
        val bytes = payloads.sumOf { payload ->
            runCatching { BluetoothSyncProtocol.encodedSize(payload) }.getOrDefault(0)
        }
        val maxBytes = payloads.maxOfOrNull { payload ->
            runCatching { BluetoothSyncProtocol.encodedSize(payload) }.getOrDefault(0)
        } ?: 0
        return mapOf(
            "${prefix}FrameCount" to payloads.size,
            "${prefix}TotalBytes" to bytes,
            "${prefix}MaxFrameBytes" to maxBytes,
            "${prefix}ArticleCount" to articleCount
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

    private fun elapsedSince(startedAt: Long): Long =
        SystemClock.elapsedRealtime() - startedAt

    companion object {
        private const val TAG = "WatchRSS_BtSyncClient"
    }
}
