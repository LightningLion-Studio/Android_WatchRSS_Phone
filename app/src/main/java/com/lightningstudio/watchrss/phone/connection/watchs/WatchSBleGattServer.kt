package com.lightningstudio.watchrss.phone.connection.watchs

import android.Manifest
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
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** Called with a Main-dispatched scope. Binder callbacks post to that scope before touching state. */
@SuppressLint("MissingPermission") // Permissions are checked on start; platform calls also fail closed on revocation.
internal class WatchSBleGattServer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val enabled: () -> Boolean,
    private val protocol: (() -> Boolean) -> WatchSBleEndpoint
) : AutoCloseable {
    companion object {
        val SERVICE: UUID = UUID.fromString("7e57c201-1f7d-4f0b-9f3d-2d7d3a65b301")
        val WRITE: UUID = UUID.fromString("7e57c201-1f7d-4f0b-9f3d-2d7d3a65b302")
        val NOTIFY: UUID = UUID.fromString("7e57c201-1f7d-4f0b-9f3d-2d7d3a65b303")
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val TAG = "WatchSBleSync"
        private const val IDLE_MS = 30_000L
    }

    private class Link(val device: BluetoothDevice) {
        val packets = Channel<ByteArray>(16)
        var job: Job? = null
        var mtu = WatchSBleFrames.MIN_MTU
        var subscribed = false
        var notification: CompletableDeferred<Boolean>? = null
        @Volatile var alive = true
    }

    private val ready = CompletableDeferred<Boolean>()
    private var server: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var peer: Link? = null
    private val down = BluetoothGattCharacteristic(NOTIFY, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0)
    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i(TAG, "advertising")
            ready.complete(true)
        }

        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "advertise_failed code=$errorCode")
            ready.complete(false)
        }
    }

    private val callbacks = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            scope.launch {
                if (server == null || service.uuid != SERVICE) return@launch
                if (status != BluetoothGatt.GATT_SUCCESS || !enabled()) {
                    ready.complete(false)
                    return@launch
                }
                val started = runCatching {
                    requireNotNull(advertiser).startAdvertising(
                        AdvertiseSettings.Builder().setConnectable(true).build(),
                        AdvertiseData.Builder().addServiceUuid(ParcelUuid(SERVICE)).build(),
                        advertiseCallback
                    )
                }
                if (started.isFailure) ready.complete(false)
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            scope.launch {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (status != BluetoothGatt.GATT_SUCCESS || !enabled() || peer != null) {
                        runCatching { server?.cancelConnection(device) }
                        return@launch
                    }
                    if (server == null) return@launch
                    val link = Link(device)
                    peer = link
                    Log.i(TAG, "connected")
                    link.job = launch { serve(link) }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    peer?.takeIf { it.device == device }?.let(::disconnect)
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            scope.launch {
                peer?.takeIf { it.device == device }?.let {
                    it.mtu = mtu.coerceIn(WatchSBleFrames.MIN_MTU, WatchSBleFrames.MAX_MTU)
                    Log.i(TAG, "att_mtu=$mtu")
                }
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            scope.launch {
                peer?.takeIf { it.device == device }?.notification?.complete(status == BluetoothGatt.GATT_SUCCESS)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
        ) {
            scope.launch {
                val link = peer?.takeIf { it.device == device }
                val enable = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                val disable = value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
                val accepted = enabled() && link != null && descriptor.uuid == CCCD &&
                    descriptor.characteristic.uuid == NOTIFY && !preparedWrite && offset == 0 &&
                    responseNeeded && (disable || (enable && !link.subscribed))
                if (accepted) link!!.subscribed = enable
                respond(device, requestId, responseNeeded, accepted)
                if (accepted && disable) disconnect(checkNotNull(link))
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
        ) {
            scope.launch {
                val link = peer?.takeIf { it.device == device }
                val accepted = enabled() && link?.subscribed == true && characteristic.uuid == WRITE &&
                    !preparedWrite && responseNeeded && offset == 0 &&
                    value.size in WatchSBleFrames.HEADER..(link.mtu - 3) &&
                    link.packets.trySend(value.copyOf()).isSuccess
                respond(device, requestId, responseNeeded, accepted)
            }
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            scope.launch { respond(device, requestId, true, false) }
        }
    }

    private fun respond(device: BluetoothDevice, requestId: Int, requested: Boolean, accepted: Boolean) {
        if (requested) runCatching {
            server?.sendResponse(device, requestId,
                if (accepted) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE, 0, null)
        }
    }

    fun radioAvailable(): Boolean = runCatching {
        context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
    }.getOrDefault(false)

    suspend fun start(): Boolean {
        if (!enabled()) return false
        if (Build.VERSION.SDK_INT >= 31 && listOf(Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE).any {
                context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
            }) return false
        val opened = runCatching {
            val manager = context.getSystemService(BluetoothManager::class.java)
            advertiser = manager?.adapter?.bluetoothLeAdvertiser ?: return false
            val up = BluetoothGattCharacteristic(WRITE, BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE)
            down.addDescriptor(BluetoothGattDescriptor(CCCD, BluetoothGattDescriptor.PERMISSION_WRITE))
            val service = BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            service.addCharacteristic(up)
            service.addCharacteristic(down)
            val gatt = manager.openGattServer(context, callbacks) ?: return false
            server = gatt
            gatt.addService(service)
        }.getOrDefault(false)
        return opened && withTimeoutOrNull(5000) { ready.await() } == true
    }

    private suspend fun serve(link: Link) {
        val receiver = WatchSBleFrames.Receiver()
        val endpoint = protocol { link.alive && enabled() }
        var responseId = 1L
        var mtu = WatchSBleFrames.MIN_MTU
        try {
            while (currentCoroutineContext().isActive && enabled()) {
                val frame = withTimeout(IDLE_MS) { receive(link, mtu) }
                val accepted = receiver.accept(frame) ?: continue
                notify(link, WatchSBleFrames.encode(accepted.ack))
                val message = accepted.message ?: continue
                val reply = endpoint.exchange(frame.id, link.mtu, message)
                sendReply(link, endpoint, receiver, frame.id, responseId++, mtu, reply)
                if (reply.disconnect) break
                mtu = reply.nextMtu ?: mtu
                reply.pending?.let { request ->
                    val allowed = withTimeoutOrNull(endpoint.pendingRemaining(request)) {
                        awaitDecision(link, mtu, receiver, request)
                    }
                    val decision = endpoint.completePending(request, allowed)
                    sendReply(link, endpoint, receiver, frame.id, responseId++, mtu, decision)
                    if (decision.disconnect) return
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            Log.i(TAG, "session_ended")
        } finally {
            try { endpoint.close() } finally { disconnect(link) }
        }
    }

    private suspend fun sendReply(
        link: Link, endpoint: WatchSBleEndpoint, receiver: WatchSBleFrames.Receiver,
        requestId: Long, responseId: Long, mtu: Int, reply: WatchSBleEndpoint.Reply
    ) {
        WatchSBleFrames.send(responseId, reply.bytes, mtu, {
            check(endpoint.canSend())
            notify(link, it)
        }) { id, offset, timeout ->
            withTimeoutOrNull(timeout) { awaitAck(link, mtu, receiver, requestId, id, offset) } == true
        }
    }

    private suspend fun awaitDecision(
        link: Link, mtu: Int, receiver: WatchSBleFrames.Receiver, request: WatchSBleBindings.Request
    ): Boolean {
        while (true) {
            val outcome = select<Boolean?> {
                request.decision.onAwait { it }
                link.packets.onReceive { packet ->
                    val frame = runCatching { WatchSBleFrames.decode(packet, mtu) }.getOrNull()
                    if (frame != null && frame.id == 1L && frame.type != WatchSBleFrames.ACK) {
                        val repeat = receiver.accept(frame)
                        if (repeat != null) {
                            check(repeat.message == null)
                            notify(link, WatchSBleFrames.encode(repeat.ack))
                        }
                    }
                    null
                }
            }
            if (outcome != null) return outcome
        }
    }

    private suspend fun awaitAck(
        link: Link, mtu: Int, receiver: WatchSBleFrames.Receiver, requestId: Long, id: Long, offset: Int
    ): Boolean {
        while (true) {
            val frame = receive(link, mtu)
            if (frame.type == WatchSBleFrames.ACK) {
                if (frame.id == id && frame.offset == offset) return true
                continue
            }
            if (frame.id != requestId) continue
            val repeat = receiver.accept(frame) ?: continue
            check(repeat.message == null)
            notify(link, WatchSBleFrames.encode(repeat.ack))
        }
    }

    private suspend fun receive(link: Link, mtu: Int): WatchSBleFrames.Frame {
        while (true) {
            val packet = link.packets.receive()
            runCatching { WatchSBleFrames.decode(packet, mtu) }.getOrNull()?.let { return it }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun notify(link: Link, value: ByteArray) {
        check(link.alive && link.subscribed && enabled())
        val gatt = checkNotNull(server)
        val completion = CompletableDeferred<Boolean>()
        check(link.notification == null)
        link.notification = completion
        try {
            val sent = if (Build.VERSION.SDK_INT >= 33) {
                gatt.notifyCharacteristicChanged(link.device, down, false, value) == BluetoothStatusCodes.SUCCESS
            } else {
                down.value = value
                gatt.notifyCharacteristicChanged(link.device, down, false)
            }
            check(sent && withTimeout(3000) { completion.await() })
        } finally {
            link.notification = null
        }
    }

    private fun disconnect(link: Link) {
        if (peer !== link) return
        peer = null
        link.alive = false
        link.packets.cancel()
        link.job?.cancel()
        runCatching { server?.cancelConnection(link.device) }
    }

    override fun close() {
        peer?.let(::disconnect)
        runCatching { advertiser?.stopAdvertising(advertiseCallback) }
        advertiser = null
        runCatching { server?.close() }
        server = null
    }
}
