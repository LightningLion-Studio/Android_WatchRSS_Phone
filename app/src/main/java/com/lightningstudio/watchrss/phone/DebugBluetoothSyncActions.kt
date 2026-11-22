package com.lightningstudio.watchrss.phone

import com.lightningstudio.watchrss.phone.connection.bluetooth.*
import com.lightningstudio.watchrss.phone.data.model.PhoneSavedItemType
import org.json.JSONObject

/** Data actions always use the product manager; raw access is a diagnostic allowlist. */
internal suspend fun runDebugBluetoothSyncAction(
    manager: PhoneBluetoothSyncManager, action: String, type: PhoneSavedItemType,
    url: String, deviceAddress: String?, diagnostic: suspend (JSONObject) -> BluetoothSyncExchange
): String {
    val result = when (action) {
        BluetoothSyncProtocol.ACTION_SYNC_LIBRARY, BluetoothSyncProtocol.ACTION_CLEAR_LIBRARY -> {
            if (action != BluetoothSyncProtocol.ACTION_CLEAR_LIBRARY) manager.requirePhoneLogin()
            val syncLibrary = action != BluetoothSyncProtocol.ACTION_CLEAR_LIBRARY
            val targets = manager.probeLibrarySyncTargets(allowIpUpgrade = syncLibrary, guardedLibrarySync = syncLibrary)
            val target = targets.devices.singleOrNull { deviceAddress == null || it.address == deviceAddress }
                ?: run { targets.sessionLease?.close(); error("请指定一块可同步手表") }
            if (action == BluetoothSyncProtocol.ACTION_CLEAR_LIBRARY) manager.clearWatchLibrary(target.address, targets.sessionLease)
            else manager.syncAll(target, reusableSession = targets.sessionLease)
        }
        BluetoothSyncProtocol.ACTION_REMOTE_INPUT -> manager.sendRemoteInput(url, deviceAddress)
        BluetoothSyncProtocol.ACTION_PULL_SAVED_ITEMS -> manager.syncSavedItems(type, deviceAddress)
        else -> {
            require(action in setOf(BluetoothSyncProtocol.ACTION_PING, BluetoothSyncProtocol.ACTION_PULL_DEBUG_LOG)) { "调试入口不支持此操作" }
            val request = JSONObject().put("version", 1).put("action", action).put("nonce", System.currentTimeMillis().toString())
            val exchange = diagnostic(request)
            return "Bluetooth diagnostic complete\ndevice=${exchange.deviceName} ${exchange.deviceAddress}\nresponse=${exchange.response}"
        }
    }
    return buildString {
        appendLine("Bluetooth sync debug complete")
        appendLine("device=${result.deviceName}")
        result.libraryStats?.let { appendLine("sent=${it.sent} received=${it.received} merged=${it.merged}") }
        result.importedCount?.let { appendLine("importedCount=$it") }
    }
}
