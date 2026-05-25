package com.lightningstudio.watchrss.phone.connection.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONObject

data class BluetoothSyncExchange(
    val deviceName: String,
    val deviceAddress: String,
    val request: JSONObject,
    val response: JSONObject
)

class PhoneBluetoothSyncClient(
    private val context: Context
) {
    @SuppressLint("MissingPermission")
    fun exchange(
        request: JSONObject,
        deviceAddress: String? = null,
        deviceNameHint: String? = null
    ): BluetoothSyncExchange {
        requireBluetoothConnectPermission()
        val adapter = context.getSystemService(BluetoothManager::class.java)
            ?.adapter
            ?: error("此设备没有蓝牙适配器")
        require(adapter.isEnabled) { "蓝牙未开启" }

        val device = selectBondedWatchDevice(
            devices = adapter.bondedDevices.orEmpty(),
            deviceAddress = deviceAddress,
            deviceNameHint = deviceNameHint
        )
        Log.i(TAG, "connecting to name=${device.name} address=${device.address} uuid=${BluetoothSyncProtocol.SERVICE_UUID}")
        if (canCancelDiscovery()) {
            runCatching { adapter.cancelDiscovery() }
                .onFailure { Log.w(TAG, "cancelDiscovery skipped: ${it.message}") }
        }

        val socket = device.createRfcommSocketToServiceRecord(BluetoothSyncProtocol.SERVICE_UUID)
        try {
            socket.connect()
            BluetoothSyncProtocol.writeFrame(socket.outputStream, request)
            val response = BluetoothSyncProtocol.readFrame(socket.inputStream)
            runCatching {
                BluetoothSyncProtocol.writeFrame(
                    socket.outputStream,
                    JSONObject().apply {
                        put("action", BluetoothSyncProtocol.ACTION_ACK)
                        put("success", true)
                    }
                )
            }.onFailure { throwable ->
                Log.w(TAG, "response ack skipped: ${throwable.message}")
            }
            Log.i(TAG, "exchange complete response=$response")
            return BluetoothSyncExchange(
                deviceName = device.name.orEmpty(),
                deviceAddress = device.address,
                request = request,
                response = response
            )
        } finally {
            runCatching {
                socket.close()
            }.onFailure { throwable ->
                Log.w(TAG, "socket close ignored after exchange: ${throwable.message}")
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

    companion object {
        private const val TAG = "WatchRSS_BtSyncClient"
    }
}
