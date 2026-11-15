package com.lightningstudio.watchrss.phone

import com.lightningstudio.watchrss.phone.connection.bluetooth.*
import com.lightningstudio.watchrss.phone.data.model.PhoneSavedItemType
import org.json.JSONObject

/** Data actions use the public manager entry; raw transport is limited to ping. */
internal suspend fun runDebugBluetoothSyncAction(
    manager: PhoneBluetoothSyncManager, action: String, type: PhoneSavedItemType,
    url: String, deviceAddress: String?, diagnostic: suspend (JSONObject) -> BluetoothSyncExchange
): String {
    val result = when (action) {
        BluetoothSyncProtocol.ACTION_SYNC_LIBRARY -> {
            manager.requirePhoneLogin()
            val targets = manager.probeLibrarySyncTargets()
            val target = targets.devices.singleOrNull { deviceAddress == null || it.address == deviceAddress || it.bluetoothAddress == deviceAddress }
                ?: run { targets.sessionLease?.close(); error("请指定一块可同步手表") }
            val lease = targets.sessionLease?.takeIf { it.device.address == target.address }
            if (lease == null) targets.sessionLease?.close()
            manager.syncAll(target, reusableSession = lease)
        }
        BluetoothSyncProtocol.ACTION_REMOTE_INPUT -> manager.sendRemoteInput(url, deviceAddress)
        BluetoothSyncProtocol.ACTION_PULL_SAVED_ITEMS -> manager.syncSavedItems(type, deviceAddress)
        else -> {
            require(action == BluetoothSyncProtocol.ACTION_PING) { "调试入口不支持此操作" }
            val exchange = diagnostic(JSONObject().put("version",1).put("action",action).put("nonce",System.currentTimeMillis().toString()))
            return "Bluetooth diagnostic complete\ndevice=${exchange.deviceName} ${exchange.deviceAddress}\nresponse=${exchange.response}"
        }
    }
    return "Bluetooth sync debug complete\ndevice=${result.deviceName}\nimportedCount=${result.importedCount}"
}
