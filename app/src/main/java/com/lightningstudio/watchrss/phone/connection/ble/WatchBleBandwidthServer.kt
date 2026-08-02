package com.lightningstudio.watchrss.phone.connection.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import com.lightningstudio.watchrss.phone.connection.bili.BiliBaseStationRequest
import com.lightningstudio.watchrss.phone.connection.bili.BiliPlaybackRequest
import com.lightningstudio.watchrss.phone.connection.bili.BiliRealtimeTranscoder
import com.lightningstudio.watchrss.phone.connection.bili.PhoneBiliGateway
import com.lightningstudio.watchrss.phone.connection.bili.failureResponse
import com.lightningstudio.watchrss.phone.connection.bili.successResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@SuppressLint("MissingPermission")
internal class WatchBleBandwidthServer(
    context: Context
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val bluetoothManager =
        appContext.getSystemService(BluetoothManager::class.java)
    private val pendingAcks = ConcurrentHashMap<Int, CompletableDeferred<ControlMessage.Ack>>()
    private val trialCounter = AtomicInteger(0)
    private val notificationLock = Any()
    private val rpcLock = Any()
    private val baseStationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val outboundMutex = Mutex()
    private val biliGateway = PhoneBiliGateway()
    private val realtimeTranscoder = BiliRealtimeTranscoder(appContext)
    private val localAudioProbeServer = LocalAudioProbeServer(appContext, ::reportStatus)

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var connectedDevice: BluetoothDevice? = null
    private var pendingNotification: CompletableDeferred<Int>? = null
    private var activeNotificationCharacteristic: BluetoothGattCharacteristic? = null
    private var notificationsEnabled = false
    private var advertising = false
    private var mtu = BleBandwidthProtocol.DEFAULT_MTU
    private var nextServiceToRegister = 0
    private var incomingRpc: IncomingRpc? = null
    private var videoStreamJob: Job? = null

    @Volatile
    private var statusListener: ((String) -> Unit)? = null

    @Volatile
    var lastStatus: String = "BLE 服务尚未启动"
        private set

    @Volatile
    var watchReady: Boolean = false
        private set

    @Volatile
    var videoReady: Boolean = false
        private set

    @Volatile
    var baseReady: Boolean = false
        private set

    private fun createDuplexDataCharacteristic(uuid: java.util.UUID) =
        BluetoothGattCharacteristic(
            uuid,
            BluetoothGattCharacteristic.PROPERTY_READ or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_READ or
                BluetoothGattCharacteristic.PERMISSION_WRITE
        ).apply {
            addDescriptor(
                BluetoothGattDescriptor(
                    BleBandwidthProtocol.CCCD_UUID,
                    BluetoothGattDescriptor.PERMISSION_READ or
                        BluetoothGattDescriptor.PERMISSION_WRITE
                )
            )
        }

    private fun createService(
        serviceUuid: java.util.UUID,
        dataCharacteristic: BluetoothGattCharacteristic,
        controlCharacteristic: BluetoothGattCharacteristic? = null
    ) = BluetoothGattService(
        serviceUuid,
        BluetoothGattService.SERVICE_TYPE_PRIMARY
    ).apply {
        addCharacteristic(dataCharacteristic)
        controlCharacteristic?.let(::addCharacteristic)
    }

    private val v1DataCharacteristic =
        createDuplexDataCharacteristic(BleBandwidthProtocol.V1_DATA_UUID)
    private val v1ControlCharacteristic = BluetoothGattCharacteristic(
        BleBandwidthProtocol.V1_CONTROL_UUID,
        BluetoothGattCharacteristic.PROPERTY_WRITE or
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
        BluetoothGattCharacteristic.PERMISSION_WRITE
    )
    private val v2DataCharacteristic =
        createDuplexDataCharacteristic(BleBandwidthProtocol.V2_DATA_UUID)
    private val dataCharacteristic =
        createDuplexDataCharacteristic(BleBandwidthProtocol.DATA_UUID)

    private val servicesToRegister = listOf(
        createService(
            BleBandwidthProtocol.V1_SERVICE_UUID,
            v1DataCharacteristic,
            v1ControlCharacteristic
        ),
        createService(
            BleBandwidthProtocol.V2_SERVICE_UUID,
            v2DataCharacteristic
        ),
        createService(
            BleBandwidthProtocol.SERVICE_UUID,
            dataCharacteristic
        )
    )

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                reportStatus("BLE 服务 ${service.uuid} 注册失败：$status")
                return
            }
            nextServiceToRegister += 1
            if (nextServiceToRegister < servicesToRegister.size) {
                addNextService()
            } else {
                reportStatus("兼容服务 V1 / V2 / V3 已注册")
                startAdvertising()
            }
        }

        override fun onConnectionStateChange(
            device: BluetoothDevice,
            status: Int,
            newState: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS &&
                newState == BluetoothProfile.STATE_CONNECTED
            ) {
                // Android terminates a connectable legacy advertisement once a
                // central connects. Reflect that state so a later disconnect
                // can make this server discoverable again.
                advertising = false
                connectedDevice = device
                mtu = BleBandwidthProtocol.DEFAULT_MTU
                activeNotificationCharacteristic = null
                notificationsEnabled = false
                watchReady = false
                videoReady = false
                baseReady = false
                reportStatus("手表已连接，等待订阅测速通道")
                return
            }

            if (connectedDevice?.address == device.address) {
                connectedDevice = null
                activeNotificationCharacteristic = null
                notificationsEnabled = false
                watchReady = false
                videoReady = false
                baseReady = false
                synchronized(rpcLock) { incomingRpc = null }
                videoStreamJob?.cancel()
                videoStreamJob = null
                mtu = BleBandwidthProtocol.DEFAULT_MTU
                failPending("手表 BLE 已断开：status=$status")
                reportStatus("等待手表重新连接")
                restartAdvertising()
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            if (connectedDevice?.address == device.address) {
                this@WatchBleBandwidthServer.mtu = mtu
                reportStatus("手表已连接，MTU $mtu，等待 READY")
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor
        ) {
            val value = if (notificationsEnabled) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            }
            gattServer?.sendResponse(
                device,
                requestId,
                BluetoothGatt.GATT_SUCCESS,
                offset,
                value
            )
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            val valid =
                characteristic.uuid in BleBandwidthProtocol.DATA_UUIDS && offset == 0
            gattServer?.sendResponse(
                device,
                requestId,
                if (valid) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE,
                offset,
                if (valid) byteArrayOf() else null
            )
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            val valid = descriptor.uuid == BleBandwidthProtocol.CCCD_UUID &&
                !preparedWrite &&
                offset == 0
            if (valid) {
                notificationsEnabled =
                    value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                if (notificationsEnabled) {
                    activeNotificationCharacteristic = descriptor.characteristic
                } else {
                    activeNotificationCharacteristic = null
                    watchReady = false
                    videoReady = false
                    baseReady = false
                }
            }
            if (responseNeeded) {
                gattServer?.sendResponse(
                    device,
                    requestId,
                    if (valid) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE,
                    offset,
                    null
                )
            }
            if (valid) {
                reportStatus(
                    if (notificationsEnabled) {
                        "测速通知已订阅，等待手表 READY"
                    } else {
                        "手表取消了测速通知订阅"
                    }
                )
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            val control = if (
                characteristic.uuid in BleBandwidthProtocol.CONTROL_UUIDS &&
                !preparedWrite &&
                offset == 0
            ) {
                BleBandwidthProtocol.decodeControl(value)
            } else {
                null
            }
            if (responseNeeded) {
                gattServer?.sendResponse(
                    device,
                    requestId,
                    if (control == null) BluetoothGatt.GATT_FAILURE else BluetoothGatt.GATT_SUCCESS,
                    offset,
                    null
                )
            }
            when (control) {
                ControlMessage.Ready -> {
                    watchReady = notificationsEnabled
                    videoReady = false
                    reportStatus(
                        if (watchReady) {
                            "WatchRSS 自有 BLE 已就绪，可以开始测试"
                        } else {
                            "收到 READY，但手表尚未订阅通知"
                        }
                    )
                }

                ControlMessage.VideoReady -> {
                    videoReady = notificationsEnabled
                    watchReady = false
                    reportStatus(
                        if (videoReady) {
                            "手表视频播放器已就绪，可以开始串流"
                        } else {
                            "收到 VIDEO READY，但手表尚未订阅通知"
                        }
                    )
                }

                ControlMessage.BaseReady -> {
                    baseReady = notificationsEnabled
                    watchReady = false
                    videoReady = false
                    reportStatus(
                        if (baseReady) {
                            "手表 B 站客户端已连接，手机基站就绪"
                        } else {
                            "收到 BASE READY，但手表尚未订阅通知"
                        }
                    )
                }

                is ControlMessage.RpcBegin -> beginRpc(control)
                is ControlMessage.RpcData -> appendRpc(control)
                is ControlMessage.RpcEnd -> finishRpc(control)

                is ControlMessage.Ack -> pendingAcks[control.trialId]?.complete(control)
                null -> Unit
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            synchronized(notificationLock) {
                pendingNotification?.complete(status)
                pendingNotification = null
            }
        }
    }

    private fun beginRpc(message: ControlMessage.RpcBegin) {
        if (!baseReady || message.sizeBytes !in 1..MAX_RPC_REQUEST_BYTES) return
        synchronized(rpcLock) {
            incomingRpc = IncomingRpc(
                requestId = message.requestId,
                bytes = ByteArray(message.sizeBytes)
            )
        }
    }

    private fun appendRpc(message: ControlMessage.RpcData) {
        synchronized(rpcLock) {
            val incoming = incomingRpc ?: return
            if (incoming.requestId != message.requestId ||
                incoming.sequence != message.sequence ||
                incoming.offset + message.payload.size > incoming.bytes.size
            ) {
                incomingRpc = null
                return
            }
            message.payload.copyInto(incoming.bytes, incoming.offset)
            incoming.offset += message.payload.size
            incoming.sequence += 1
            for (byte in message.payload) {
                incoming.checksum =
                    (incoming.checksum + (byte.toInt() and 0xff)) and 0xffff_ffffL
            }
        }
    }

    private fun finishRpc(message: ControlMessage.RpcEnd) {
        val completed = synchronized(rpcLock) {
            val incoming = incomingRpc
            incomingRpc = null
            incoming?.takeIf {
                it.requestId == message.requestId &&
                    it.offset == it.bytes.size &&
                    it.checksum == message.checksum
            }
        } ?: return
        baseStationScope.launch { handleRpc(completed.bytes) }
    }

    private suspend fun handleRpc(bytes: ByteArray) {
        var requestId = 0
        val response = runCatching {
            val request = BiliBaseStationRequest.decode(bytes)
            requestId = request.id
            if (request.method == "video.start") {
                startRealtimeVideo(
                    BiliPlaybackRequest(
                        url = request.params.getString("url"),
                        referer = request.params.getString("referer"),
                        durationMs = request.params.optLong("durationMs"),
                        cookieHeader = request.cookieHeader
                    )
                )
                successResponse(request.id, org.json.JSONObject().put("started", true))
            } else if (request.method == "video.stop") {
                videoStreamJob?.cancel()
                videoStreamJob = null
                successResponse(request.id, org.json.JSONObject().put("stopped", true))
            } else {
                successResponse(
                    request.id,
                    biliGateway.execute(request.method, request.params, request.cookieHeader)
                )
            }
        }.getOrElse { error -> failureResponse(requestId, error) }
        outboundMutex.withLock {
            sendPayload(
                response,
                requestId,
                BleBandwidthProtocol.PAYLOAD_KIND_RPC
            )
        }
    }

    private fun startRealtimeVideo(request: BiliPlaybackRequest) {
        videoStreamJob?.cancel()
        videoStreamJob = baseStationScope.launch {
            delay(250)
            runCatching {
                realtimeTranscoder.stream(request) { frameIndex, payload ->
                    outboundMutex.withLock {
                        sendPayload(
                            payload,
                            frameIndex,
                            BleBandwidthProtocol.PAYLOAD_KIND_VIDEO
                        )
                    }
                }
            }.onFailure { error ->
                if (error !is kotlinx.coroutines.CancellationException) {
                    Log.e(TAG, "Bilibili realtime stream failed", error)
                }
            }
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            advertising = true
            reportStatus("正在广播 WatchRSS BLE，等待手表扫描连接")
        }

        override fun onStartFailure(errorCode: Int) {
            advertising = false
            reportStatus("WatchRSS BLE 广播失败：$errorCode")
        }
    }

    @Synchronized
    fun start() {
        if (gattServer != null) {
            if (connectedDevice == null && !advertising) startAdvertising()
            return
        }
        val adapter = bluetoothManager.adapter
            ?: error("手机没有蓝牙适配器")
        require(adapter.isEnabled) { "请先开启手机蓝牙" }
        advertiser = adapter.bluetoothLeAdvertiser
            ?: error("手机不支持 BLE 广播")
        gattServer = bluetoothManager.openGattServer(appContext, gattCallback)
            ?: error("无法创建 BLE GATT Server")
        nextServiceToRegister = 0
        addNextService()
        reportStatus("正在注册 WatchRSS BLE 兼容服务")
    }

    private fun addNextService() {
        val service = servicesToRegister[nextServiceToRegister]
        require(gattServer?.addService(service) == true) {
            "无法注册 WatchRSS BLE 服务 ${service.uuid}"
        }
    }

    @Synchronized
    fun bindStatusListener(listener: (String) -> Unit) {
        statusListener = listener
        listener(lastStatus)
    }

    @Synchronized
    fun unbindStatusListener(listener: (String) -> Unit) {
        if (statusListener === listener) {
            statusListener = null
        }
    }

    private fun reportStatus(message: String) {
        lastStatus = message
        Log.i(TAG, message)
        statusListener?.invoke(message)
    }

    private fun startAdvertising() {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(BleBandwidthProtocol.SERVICE_UUID))
            .addManufacturerData(
                BleBandwidthProtocol.ADVERTISEMENT_MANUFACTURER_ID,
                BleBandwidthProtocol.ADVERTISEMENT_MARKER
            )
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .setIncludeTxPowerLevel(false)
            .build()
        advertiser?.startAdvertising(settings, data, scanResponse, advertiseCallback)
    }

    @Synchronized
    private fun restartAdvertising() {
        if (gattServer == null || connectedDevice != null) return
        if (advertising) {
            runCatching { advertiser?.stopAdvertising(advertiseCallback) }
        }
        advertising = false
        startAdvertising()
    }

    suspend fun runBenchmark(
        repetitions: Int = 2,
        onTrial: (BleBandwidthTrialResult) -> Unit
    ): List<BleBandwidthTrialResult> {
        require(watchReady) { "请先在手表打开“手机手表带宽测试”页面" }
        val results = mutableListOf<BleBandwidthTrialResult>()
        for (size in BleBandwidthProtocol.testSizesBytes) {
            for (repetition in 1..repetitions) {
                val result = runTrial(size, repetition)
                results += result
                onTrial(result)
                delay(250)
            }
        }
        return results
    }

    suspend fun streamBundledVideo(
        videoId: String,
        profile: BleVideoProfile,
        onFrame: (frameIndex: Int, frameCount: Int, skippedFrames: Int) -> Unit
    ) {
        require(videoReady) { "请先在手表打开“蓝牙串流视频”页面" }
        require(videoId in BUNDLED_VIDEO_IDS) { "未知测试视频：$videoId" }
        // Quality mode sends the measured-width hybrid stream at 6 FPS.
        // Smooth mode sends the fixed 44 x 70, 5-bit Hanzi stream at 12 FPS.
        // Both formats may use independent LZ4 blocks when that saves enough
        // bytes; the watch auto-detects the frame marker.
        val stream = appContext.assets.open(
            "bluetooth-video/$videoId${profile.assetSuffix}"
        )
            .use { it.readBytes() }
        require(stream.size >= 272 && stream.copyOfRange(0, 4).decodeToString() == "WVS1") {
            "蓝牙视频资源损坏"
        }
        val sourceFps = stream[5].toInt() and 0xff
        val frameCount = readAssetUInt16(stream, 12)
        val dictionarySize = stream[14].toInt() and 0xff
        var offset = 16 + dictionarySize * 4
        val frameOffsets = IntArray(frameCount)
        val frameSizes = IntArray(frameCount)
        for (frameIndex in 0 until frameCount) {
            require(offset + 2 <= stream.size) { "蓝牙视频帧索引损坏：$frameIndex" }
            val size = readAssetUInt16(stream, offset)
            offset += 2
            require(offset + size <= stream.size) { "蓝牙视频帧损坏：$frameIndex" }
            frameOffsets[frameIndex] = offset
            frameSizes[frameIndex] = size
            offset += size
        }

        val startedAt = SystemClock.elapsedRealtime()
        var lastFrameIndex = -1
        var skippedFrames = 0
        while (lastFrameIndex + 1 < frameCount) {
            require(videoReady) { "手表已退出蓝牙视频页面" }
            val elapsedMs = SystemClock.elapsedRealtime() - startedAt
            val frameIndex = minOf(
                frameCount - 1,
                ((elapsedMs * sourceFps) / 1000L).toInt()
            )
            if (frameIndex <= lastFrameIndex) {
                delay(10)
                continue
            }
            skippedFrames += (frameIndex - lastFrameIndex - 1).coerceAtLeast(0)
            val payloadOffset = frameOffsets[frameIndex]
            val payloadSize = frameSizes[frameIndex]
            val frameSendStartedAt = SystemClock.elapsedRealtime()
            sendVideoFrame(stream, payloadOffset, payloadSize, frameIndex)
            onFrame(frameIndex, frameCount, skippedFrames)
            if ((frameIndex + 1) % 25 == 0) {
                reportStatus("BLE 视频帧：${frameIndex + 1}/$frameCount · 跳过 $skippedFrames")
            }
            lastFrameIndex = frameIndex
            val minimumFrameMs = 1000L / profile.targetFps
            val bitrateBudgetMs =
                (payloadSize * 8_000L + BLE_VIDEO_TARGET_BPS - 1) / BLE_VIDEO_TARGET_BPS
            val frameBudgetMs = maxOf(minimumFrameMs, bitrateBudgetMs)
            val spentMs = SystemClock.elapsedRealtime() - frameSendStartedAt
            delay((frameBudgetMs - spentMs).coerceAtLeast(0))
        }
    }

    suspend fun sendLocalAudioProbeUrl(
        videoId: String = "bad-apple",
        profile: BleVideoProfile = BleVideoProfile.SMOOTH
    ): String {
        require(videoReady) { "请先在手表打开“蓝牙串流视频”页面" }
        require(videoId in BUNDLED_VIDEO_IDS) { "未知测试视频：$videoId" }
        val url = localAudioProbeServer.start(videoId, profile)
        sendPayload(
            url.toByteArray(Charsets.US_ASCII),
            0,
            BleBandwidthProtocol.PAYLOAD_KIND_AUDIO_URL
        )
        // Let the watch finish parsing the descriptor and start its native
        // HTTP audio player before the first burst of video notifications.
        delay(BLE_VIDEO_START_GRACE_MS)
        require(videoReady) { "手表在 BLE 视频启动前已退出" }
        reportStatus("已发送本机 HTTP 地址：$url")
        return url
    }

    private suspend fun sendVideoFrame(
        source: ByteArray,
        sourceOffset: Int,
        sourceSize: Int,
        frameIndex: Int
    ) {
        val trialId = trialCounter.updateAndGet { current ->
            if (current >= 0xffff) 1 else current + 1
        }
        sendPayloadEnvelope(
            trialId,
            source,
            sourceOffset,
            sourceSize,
            frameIndex,
            BleBandwidthProtocol.PAYLOAD_KIND_VIDEO
        )
    }

    private suspend fun sendPayload(payload: ByteArray, index: Int, payloadKind: Int) {
        val trialId = trialCounter.updateAndGet { current ->
            if (current >= 0xffff) 1 else current + 1
        }
        sendPayloadEnvelope(trialId, payload, 0, payload.size, index, payloadKind)
    }

    private suspend fun sendPayloadEnvelope(
        trialId: Int,
        source: ByteArray,
        sourceOffset: Int,
        sourceSize: Int,
        index: Int,
        payloadKind: Int
    ) {
        val maxPayload = BleBandwidthProtocol.maxPayloadBytes(mtu)
        var offset = 0
        var sequence = 0
        var checksum = 0L
        sendNotification(
            BleBandwidthProtocol.encodeBegin(
                trialId,
                sourceSize,
                index,
                payloadKind
            )
        )
        while (offset < sourceSize) {
            if (payloadKind == BleBandwidthProtocol.PAYLOAD_KIND_VIDEO) {
                require(videoReady && notificationsEnabled) { "手表已退出 BLE 视频页面" }
            }
            val count = minOf(maxPayload, sourceSize - offset)
            val packet = BleBandwidthProtocol.encodeData(
                trialId, sequence, source, sourceOffset + offset, count
            )
            checksum = BleBandwidthProtocol.updateChecksum(checksum, packet)
            sendNotification(packet)
            offset += count
            sequence += 1
            if (payloadKind == BleBandwidthProtocol.PAYLOAD_KIND_VIDEO && offset < sourceSize) {
                // Spread each frame across its selected 83 or 166 ms slot.
                // Without this yield, notifications arrive as one JS-event
                // burst and can freeze the constrained RTOS renderer.
                delay(BLE_VIDEO_PACKET_INTERVAL_MS)
            }
        }
        // RPC responses and video frames are independent envelopes. The watch
        // drops a damaged message and recovers at the next BEGIN.
        sendNotification(BleBandwidthProtocol.encodeEnd(trialId, sourceSize, checksum))
    }

    private fun readAssetUInt16(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or
            ((source[offset + 1].toInt() and 0xff) shl 8)

    private suspend fun runTrial(
        sizeBytes: Int,
        repetition: Int
    ): BleBandwidthTrialResult {
        require(watchReady) { "手表尚未 READY" }
        val trialId = trialCounter.updateAndGet { current ->
            if (current >= 0xffff) 1 else current + 1
        }
        val ackDeferred = CompletableDeferred<ControlMessage.Ack>()
        pendingAcks[trialId] = ackDeferred
        val activeMtu = mtu
        val maxPayload = BleBandwidthProtocol.maxPayloadBytes(activeMtu)
        var offset = 0
        var sequence = 0
        var checksum = 0L
        val startedAt = SystemClock.elapsedRealtime()

        try {
            reportStatus("${sizeBytes / 1024} KiB #$repetition：正在发送")
            sendNotification(BleBandwidthProtocol.encodeBegin(trialId, sizeBytes, repetition))
            while (offset < sizeBytes) {
                val count = minOf(maxPayload, sizeBytes - offset)
                val packet = BleBandwidthProtocol.encodeData(
                    trialId = trialId,
                    sequence = sequence,
                    absoluteOffset = offset,
                    payloadLength = count
                )
                checksum = BleBandwidthProtocol.updateChecksum(checksum, packet)
                sendNotification(packet)
                offset += count
                sequence += 1
            }
            sendNotification(BleBandwidthProtocol.encodeEnd(trialId, sizeBytes, checksum))

            val ack = withTimeout(180_000) { ackDeferred.await() }
            require(ack.status == BleBandwidthProtocol.ACK_OK) {
                "手表校验失败 status=${ack.status} expectedSeq=${ack.expectedSequence}"
            }
            require(ack.receivedBytes == sizeBytes.toLong()) {
                "手表确认 ${ack.receivedBytes} bytes，预期 $sizeBytes"
            }
            require(ack.checksum == checksum) {
                "手表 checksum=${ack.checksum}，手机 checksum=$checksum"
            }
            return BleBandwidthTrialResult(
                sizeBytes = sizeBytes,
                repetition = repetition,
                elapsedMs = SystemClock.elapsedRealtime() - startedAt,
                mtu = activeMtu,
                packetCount = sequence
            )
        } finally {
            pendingAcks.remove(trialId)
        }
    }

    private suspend fun sendNotification(value: ByteArray) {
        val server = requireNotNull(gattServer) { "GATT Server 未启动" }
        val device = requireNotNull(connectedDevice) { "手表 BLE 未连接" }
        val characteristic = requireNotNull(activeNotificationCharacteristic) {
            "手表未选择通知特征"
        }
        require(notificationsEnabled) { "手表未订阅通知" }

        val completion = CompletableDeferred<Int>()
        synchronized(notificationLock) {
            check(pendingNotification == null) { "上一包通知仍在发送" }
            pendingNotification = completion
        }
        val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            server.notifyCharacteristicChanged(
                device,
                characteristic,
                false,
                value
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = value
            @Suppress("DEPRECATION")
            server.notifyCharacteristicChanged(device, characteristic, false)
        }
        if (!accepted) {
            synchronized(notificationLock) {
                if (pendingNotification === completion) pendingNotification = null
            }
            error("BLE 通知未进入发送队列")
        }
        val status = withTimeout(15_000) { completion.await() }
        require(status == BluetoothGatt.GATT_SUCCESS) {
            "BLE 通知发送失败：$status"
        }
    }

    private fun failPending(message: String) {
        val error = IllegalStateException(message)
        pendingAcks.values.forEach { it.completeExceptionally(error) }
        pendingAcks.clear()
        synchronized(notificationLock) {
            pendingNotification?.completeExceptionally(error)
            pendingNotification = null
        }
    }

    override fun close() {
        failPending("BLE 测试页已关闭")
        localAudioProbeServer.close()
        watchReady = false
        videoReady = false
        baseReady = false
        synchronized(rpcLock) { incomingRpc = null }
        videoStreamJob?.cancel()
        videoStreamJob = null
        connectedDevice = null
        activeNotificationCharacteristic = null
        notificationsEnabled = false
        if (advertising) {
            runCatching { advertiser?.stopAdvertising(advertiseCallback) }
        }
        advertising = false
        advertiser = null
        runCatching { gattServer?.clearServices() }
        runCatching { gattServer?.close() }
        gattServer = null
    }

    private data class IncomingRpc(
        val requestId: Int,
        val bytes: ByteArray,
        var offset: Int = 0,
        var sequence: Int = 0,
        var checksum: Long = 0
    )

    companion object {
        private const val TAG = "WatchRSS_OwnBleBandwidth"
        private const val MAX_RPC_REQUEST_BYTES = 64 * 1024
        // Per-profile cadence is selected by the user. This cap only prevents
        // a large compressed frame from being emitted as a tight GATT burst.
        private const val BLE_VIDEO_TARGET_BPS = 240_000L
        private const val BLE_VIDEO_PACKET_INTERVAL_MS = 8L
        private const val BLE_VIDEO_START_GRACE_MS = 750L
        private val BUNDLED_VIDEO_IDS = setOf(
            "bad-apple", "bad-apple-blur", "rickroll", "rickroll-blur"
        )

        @Volatile
        private var processInstance: WatchBleBandwidthServer? = null

        fun processInstance(context: Context): WatchBleBandwidthServer =
            processInstance ?: synchronized(this) {
                processInstance ?: WatchBleBandwidthServer(
                    context.applicationContext
                ).also { processInstance = it }
            }
    }

}
