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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@SuppressLint("MissingPermission")
internal class WatchBleBandwidthServer(
    context: Context,
    private val onStatus: (String) -> Unit
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val bluetoothManager =
        appContext.getSystemService(BluetoothManager::class.java)
    private val pendingAcks = ConcurrentHashMap<Int, CompletableDeferred<ControlMessage.Ack>>()
    private val trialCounter = AtomicInteger(0)
    private val notificationLock = Any()

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var connectedDevice: BluetoothDevice? = null
    private var pendingNotification: CompletableDeferred<Int>? = null
    private var notificationsEnabled = false
    private var advertising = false
    private var mtu = BleBandwidthProtocol.DEFAULT_MTU

    @Volatile
    var watchReady: Boolean = false
        private set

    private val dataCharacteristic = BluetoothGattCharacteristic(
        BleBandwidthProtocol.DATA_UUID,
        BluetoothGattCharacteristic.PROPERTY_NOTIFY,
        BluetoothGattCharacteristic.PERMISSION_READ
    ).apply {
        addDescriptor(
            BluetoothGattDescriptor(
                BleBandwidthProtocol.CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or
                    BluetoothGattDescriptor.PERMISSION_WRITE
            )
        )
    }

    private val controlCharacteristic = BluetoothGattCharacteristic(
        BleBandwidthProtocol.CONTROL_UUID,
        BluetoothGattCharacteristic.PROPERTY_WRITE or
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
        BluetoothGattCharacteristic.PERMISSION_WRITE
    )

    private val service = BluetoothGattService(
        BleBandwidthProtocol.SERVICE_UUID,
        BluetoothGattService.SERVICE_TYPE_PRIMARY
    ).apply {
        addCharacteristic(dataCharacteristic)
        addCharacteristic(controlCharacteristic)
    }

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onStatus("BLE 服务注册失败：$status")
                return
            }
            startAdvertising()
        }

        override fun onConnectionStateChange(
            device: BluetoothDevice,
            status: Int,
            newState: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS &&
                newState == BluetoothProfile.STATE_CONNECTED
            ) {
                connectedDevice = device
                mtu = BleBandwidthProtocol.DEFAULT_MTU
                notificationsEnabled = false
                watchReady = false
                onStatus("手表已连接，等待订阅测速通道")
                return
            }

            if (connectedDevice?.address == device.address) {
                connectedDevice = null
                notificationsEnabled = false
                watchReady = false
                mtu = BleBandwidthProtocol.DEFAULT_MTU
                failPending("手表 BLE 已断开：status=$status")
                onStatus("等待手表重新连接")
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            if (connectedDevice?.address == device.address) {
                this@WatchBleBandwidthServer.mtu = mtu
                onStatus("手表已连接，MTU $mtu，等待 READY")
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
                if (!notificationsEnabled) watchReady = false
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
                onStatus(
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
                characteristic.uuid == BleBandwidthProtocol.CONTROL_UUID &&
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
                    onStatus(
                        if (watchReady) {
                            "WatchRSS 自有 BLE 已就绪，可以开始测试"
                        } else {
                            "收到 READY，但手表尚未订阅通知"
                        }
                    )
                }

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

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            advertising = true
            onStatus("正在广播 WatchRSS BLE，等待手表扫描连接")
        }

        override fun onStartFailure(errorCode: Int) {
            advertising = false
            onStatus("WatchRSS BLE 广播失败：$errorCode")
        }
    }

    fun start() {
        if (gattServer != null) return
        val adapter = bluetoothManager.adapter
            ?: error("手机没有蓝牙适配器")
        require(adapter.isEnabled) { "请先开启手机蓝牙" }
        advertiser = adapter.bluetoothLeAdvertiser
            ?: error("手机不支持 BLE 广播")
        gattServer = bluetoothManager.openGattServer(appContext, gattCallback)
            ?: error("无法创建 BLE GATT Server")
        require(gattServer?.addService(service) == true) {
            "无法注册 WatchRSS BLE 服务"
        }
        onStatus("正在注册 WatchRSS BLE 服务")
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
            .build()
        advertiser?.startAdvertising(settings, data, advertiseCallback)
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
            onStatus("${sizeBytes / 1024} KiB #$repetition：正在发送")
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
        require(notificationsEnabled) { "手表未订阅通知" }

        val completion = CompletableDeferred<Int>()
        synchronized(notificationLock) {
            check(pendingNotification == null) { "上一包通知仍在发送" }
            pendingNotification = completion
        }
        val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            server.notifyCharacteristicChanged(
                device,
                dataCharacteristic,
                false,
                value
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            dataCharacteristic.value = value
            @Suppress("DEPRECATION")
            server.notifyCharacteristicChanged(device, dataCharacteristic, false)
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
        watchReady = false
        connectedDevice = null
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
}
