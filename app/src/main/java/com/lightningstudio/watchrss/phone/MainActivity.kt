package com.lightningstudio.watchrss.phone

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.core.content.ContextCompat
import com.lightningstudio.watchrss.phone.ui.MainScreen
import com.lightningstudio.watchrss.phone.ui.theme.WatchRssPhoneTheme
import com.lightningstudio.watchrss.phone.viewmodel.MainViewModel
import com.lightningstudio.watchrss.phone.viewmodel.MainViewModelFactory

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory(
            (application as PhoneCompanionApplication).container.repository,
            (application as PhoneCompanionApplication).container.bluetoothSyncManager
        )
    }

    private var pendingBluetoothAction: (() -> Unit)? = null

    private val bluetoothPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) {
                pendingBluetoothAction?.invoke()
            }
            pendingBluetoothAction = null
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
                    onUrlChange = viewModel::updateUrlInput,
                    onImportArticle = viewModel::importIndependentArticle,
                    onAddRssSource = viewModel::addRssSource,
                    onSyncLibrary = { ensureBluetoothPermissions(viewModel::syncLibraryByBluetooth) },
                    onOpenArticle = { article ->
                        startActivity(ArticleReaderActivity.createIntent(this, article.articleId))
                    },
                    onToggleFavorite = viewModel::toggleFavorite,
                    onToggleWatchLater = viewModel::toggleWatchLater,
                    onDismissMessage = viewModel::clearMessage
                )
            }
        }
        handleInboundIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleInboundIntent(intent)
    }

    private fun ensureBluetoothPermissions(action: () -> Unit) {
        val permissions = buildList {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                add(android.Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            action()
            return
        }
        pendingBluetoothAction = action
        bluetoothPermissionsLauncher.launch(missing.toTypedArray())
    }

    private fun handleInboundIntent(intent: Intent?) {
        val url = extractInboundUrl(intent) ?: return
        viewModel.updateUrlInput(url)
    }

    private fun extractInboundUrl(intent: Intent?): String? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        }?.lineSequence()
            ?.firstOrNull { line ->
                line.contains("http://") || line.contains("https://")
            }
            ?.let { line ->
                URL_PATTERN.find(line)?.value ?: line.trim()
            }
    }

    companion object {
        private const val TAG = "WatchRSS_Main"
        private val URL_PATTERN = Regex("""https?://\S+""")
    }
}
