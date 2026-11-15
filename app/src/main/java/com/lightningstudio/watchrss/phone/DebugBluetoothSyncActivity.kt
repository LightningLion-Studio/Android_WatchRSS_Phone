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
                val container = (application as PhoneCompanionApplication).container
                runDebugBluetoothSyncAction(
                    container.bluetoothSyncManager, action, PhoneSavedItemType.valueOf(typeName),
                    intent.getStringExtra(EXTRA_URL) ?: "https://example.com/feed.xml", deviceAddress
                ) { request ->
                    withContext(Dispatchers.IO) {
                        PhoneBluetoothSyncClient(applicationContext, container.bluetoothDebugLog).exchange(
                            request = request, deviceAddress = deviceAddress, deviceNameHint = deviceNameHint)
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
