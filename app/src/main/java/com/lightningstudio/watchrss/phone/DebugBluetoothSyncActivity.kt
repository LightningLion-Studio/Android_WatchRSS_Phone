package com.lightningstudio.watchrss.phone

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.lightningstudio.watchrss.phone.connection.bluetooth.BluetoothSyncProtocol
import com.lightningstudio.watchrss.phone.connection.bluetooth.PhoneBluetoothSyncClient
import com.lightningstudio.watchrss.phone.data.model.PhoneSavedItemType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class DebugBluetoothSyncActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
            finish()
            return
        }

        val statusView = TextView(this).apply {
            textSize = 14f
            setPadding(24, 24, 24, 24)
            text = "Starting Bluetooth sync debug..."
        }
        setContentView(statusView)

        val missingPermission = missingBluetoothPermission()
        if (missingPermission != null) {
            val message = "Missing permission: $missingPermission"
            Log.e(TAG, message)
            statusView.text = message
            return
        }

        val action = intent.getStringExtra(EXTRA_ACTION).orEmpty().ifBlank {
            BluetoothSyncProtocol.ACTION_PING
        }
        val typeName = intent.getStringExtra(EXTRA_TYPE).orEmpty().ifBlank {
            PhoneSavedItemType.FAVORITE.name
        }
        val deviceAddress = intent.getStringExtra(EXTRA_DEVICE_ADDRESS)
        val deviceNameHint = intent.getStringExtra(EXTRA_DEVICE_NAME_HINT)

        lifecycleScope.launch {
            runCatching {
                if (action == BluetoothSyncProtocol.ACTION_SYNC_LIBRARY) {
                    val result = (application as PhoneCompanionApplication)
                        .container
                        .bluetoothSyncManager
                        .syncLibrary()
                    return@runCatching buildString {
                        appendLine("Bluetooth library sync debug complete")
                        appendLine("device=${result.deviceName}")
                        result.libraryStats?.let { stats ->
                            appendLine("sent=${stats.sent} received=${stats.received} merged=${stats.merged}")
                        }
                    }
                }
                val request = buildRequest(action, typeName)
                val container = (application as PhoneCompanionApplication).container
                val exchange = withContext(Dispatchers.IO) {
                    PhoneBluetoothSyncClient(
                        context = applicationContext,
                        debugLog = container.bluetoothDebugLog
                    ).exchange(
                        request = request,
                        deviceAddress = deviceAddress,
                        deviceNameHint = deviceNameHint
                    )
                }
                val importedCount = importSavedItemsIfNeeded(action, typeName, exchange.response)
                buildString {
                    appendLine("Bluetooth sync debug complete")
                    appendLine("device=${exchange.deviceName} ${exchange.deviceAddress}")
                    appendLine("request=${exchange.request}")
                    appendLine("response=${exchange.response}")
                    if (importedCount != null) {
                        appendLine("importedCount=$importedCount")
                    }
                }
            }.onSuccess { message ->
                Log.i(TAG, message)
                statusView.text = message
            }.onFailure { throwable ->
                val message = "Bluetooth sync debug failed: ${throwable.message}"
                Log.e(TAG, message, throwable)
                statusView.text = message
            }
        }
    }

    private fun buildRequest(action: String, typeName: String): JSONObject {
        return JSONObject().apply {
            put("version", 1)
            put("action", action)
            put("nonce", System.currentTimeMillis().toString())
            when (action) {
                BluetoothSyncProtocol.ACTION_REMOTE_INPUT -> {
                    put(
                        "url",
                        intent.getStringExtra(EXTRA_URL)
                            ?: "https://example.com/feed.xml"
                    )
                }

                BluetoothSyncProtocol.ACTION_PULL_SAVED_ITEMS -> {
                    put("type", typeName)
                }
            }
        }
    }

    private suspend fun importSavedItemsIfNeeded(
        action: String,
        typeName: String,
        response: JSONObject
    ): Int? {
        if (action != BluetoothSyncProtocol.ACTION_PULL_SAVED_ITEMS) return null
        require(response.optBoolean("success")) {
            response.optString("message").ifBlank { "手表返回同步失败" }
        }
        val type = PhoneSavedItemType.valueOf(typeName)
        val items = response.optJSONArray("items") ?: JSONArray()
        return (application as PhoneCompanionApplication)
            .container
            .repository
            .replaceSavedItems(type, items)
    }

    private fun missingBluetoothPermission(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return Manifest.permission.BLUETOOTH_CONNECT.takeIf {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    companion object {
        private const val TAG = "WatchRSS_DebugBtSync"
        private const val EXTRA_ACTION = "action"
        private const val EXTRA_TYPE = "type"
        private const val EXTRA_URL = "url"
        private const val EXTRA_DEVICE_ADDRESS = "device_address"
        private const val EXTRA_DEVICE_NAME_HINT = "device_name_hint"
    }
}
