package com.lightningstudio.watchrss.phone

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.core.content.ContextCompat
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.lightningstudio.watchrss.phone.ui.MainScreen
import com.lightningstudio.watchrss.phone.ui.theme.WatchRssPhoneTheme
import com.lightningstudio.watchrss.phone.viewmodel.MainViewModel
import com.lightningstudio.watchrss.phone.viewmodel.MainViewModelFactory

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory(
            (application as PhoneCompanionApplication).container.repository,
            (application as PhoneCompanionApplication).container.guidedSessionManager
        )
    }

    private var pendingAudioAction: (() -> Unit)? = null
    private var pendingWifiAction: (() -> Unit)? = null

    private val barcodeLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@registerForActivityResult
        viewModel.connectWithQr(contents)
    }

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                launchQrScanner()
            }
        }

    private val audioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                pendingAudioAction?.invoke()
            }
            pendingAudioAction = null
        }

    private val wifiPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) {
                pendingWifiAction?.invoke()
            }
            pendingWifiAction = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.i(TAG, "=== WatchRSS Phone App Started ===")
        Log.i(TAG, "Package: $packageName")
        runCatching { packageManager.getPackageInfo(packageName, 0) }
            .onSuccess { packageInfo ->
                Log.i(TAG, "Version Code: ${packageInfo.longVersionCode}")
                Log.i(TAG, "Version Name: ${packageInfo.versionName}")
            }
            .onFailure { throwable ->
                Log.w(TAG, "Failed to resolve version info: ${throwable.message}")
            }
        Log.i(TAG, "===================================")

        setContent {
            WatchRssPhoneTheme {
                val state by viewModel.uiState.collectAsState()
                MainScreen(
                    uiState = state,
                    onScanQr = { ensureCameraAndScan() },
                    onRssUrlChange = viewModel::updateRssUrlInput,
                    onSendRss = viewModel::sendRemoteUrl,
                    onSendPureSoundRss = viewModel::sendPureSoundRemoteUrl,
                    onReceivePureSoundSync = { ensureRecordAudioPermission(viewModel::receivePureSoundSync) },
                    onStartGuidedRemoteInput = { ensureGuidedWifiPermissions(viewModel::startGuidedRemoteInput) },
                    onStartGuidedFavorites = { ensureGuidedWifiPermissions(viewModel::startGuidedFavoritesSync) },
                    onStartGuidedWatchLater = { ensureGuidedWifiPermissions(viewModel::startGuidedWatchLaterSync) },
                    onStopGuidedSession = viewModel::stopGuidedSession,
                    onSyncFavorites = { viewModel.syncSavedItems(com.lightningstudio.watchrss.phone.data.model.PhoneSavedItemType.FAVORITE) },
                    onSyncWatchLater = { viewModel.syncSavedItems(com.lightningstudio.watchrss.phone.data.model.PhoneSavedItemType.WATCH_LATER) },
                    onDismissMessage = viewModel::clearMessage
                )
            }
        }
    }

    private fun ensureCameraAndScan() {
        cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
    }

    private fun ensureRecordAudioPermission(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            action()
            return
        }
        pendingAudioAction = action
        audioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
    }

    private fun ensureGuidedWifiPermissions(action: () -> Unit) {
        val permissions = buildList {
            add(android.Manifest.permission.ACCESS_FINE_LOCATION)
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                add(android.Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            action()
            return
        }
        pendingWifiAction = action
        wifiPermissionsLauncher.launch(missing.toTypedArray())
    }

    private fun launchQrScanner() {
        val options = ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt("扫描手表上的 WatchRSS 二维码")
            .setBeepEnabled(false)
            .setOrientationLocked(false)
            .setCaptureActivity(CaptureActivity::class.java)
        options.addExtra(Intents.Scan.MISSING_CAMERA_PERMISSION, true)
        barcodeLauncher.launch(options)
    }

    companion object {
        private const val TAG = "WatchRSS_Main"
    }
}
