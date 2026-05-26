package com.lightningstudio.watchrss.phone

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.lightningstudio.watchrss.phone.ui.MainScreen
import com.lightningstudio.watchrss.phone.ui.theme.WatchRssPhoneTheme
import com.lightningstudio.watchrss.phone.viewmodel.MainViewModel
import com.lightningstudio.watchrss.phone.viewmodel.MainViewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    private val exportBluetoothLogLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri == null) {
                viewModel.showMessage("已取消导出蓝牙日志")
                return@registerForActivityResult
            }
            lifecycleScope.launch {
                runCatching {
                    (application as PhoneCompanionApplication)
                        .container
                        .bluetoothDebugLog
                        .exportTo(contentResolver, uri)
                }.onSuccess { bytes ->
                    viewModel.showMessage("蓝牙日志已导出：$bytes 字节")
                }.onFailure { throwable ->
                    Log.e(TAG, "Failed to export bluetooth log", throwable)
                    viewModel.showError("蓝牙日志导出失败：${throwable.message ?: "未知错误"}")
                }
            }
        }

    private val importLocalContentLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) {
                viewModel.showMessage("已取消导入小说")
                return@registerForActivityResult
            }
            lifecycleScope.launch {
                runCatching {
                    readSelectedLocalContent(uri)
                }.onSuccess { file ->
                    viewModel.importLocalContent(
                        fileName = file.fileName,
                        mimeType = file.mimeType,
                        bytes = file.bytes
                    )
                }.onFailure { throwable ->
                    Log.e(TAG, "Failed to read local content", throwable)
                    viewModel.showError("小说导入失败：${throwable.message ?: "未知错误"}")
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as PhoneCompanionApplication).container

        Log.i(TAG, "=== WatchRSS Phone App Started ===")
        container.bluetoothDebugLog.append("MainActivity started")
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
                    onImportLocalContent = ::selectLocalContent,
                    onAddRssSource = viewModel::addRssSource,
                    onSyncLibrary = { ensureBluetoothPermissions(viewModel::syncLibraryByBluetooth) },
                    onExportBluetoothLog = ::exportBluetoothLog,
                    onOpenArticle = { article ->
                        startActivity(ArticleReaderActivity.createIntent(this, article.articleId))
                    },
                    onToggleFavorite = viewModel::toggleFavorite,
                    onToggleWatchLater = viewModel::toggleWatchLater,
                    onMoveRssSourceToTop = viewModel::moveRssSourceToTop,
                    onToggleRssSourcePinned = viewModel::toggleRssSourcePinned,
                    onDeleteRssSource = viewModel::deleteRssSource,
                    onDeleteArticle = viewModel::deleteArticle,
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

    private fun exportBluetoothLog() {
        val fileName = "watchrss-phone-bluetooth-${System.currentTimeMillis()}.txt"
        exportBluetoothLogLauncher.launch(fileName)
    }

    private fun selectLocalContent() {
        importLocalContentLauncher.launch(
            arrayOf(
                "text/plain",
                "text/*",
                "application/epub+zip",
                "application/octet-stream",
                "*/*"
            )
        )
    }

    private suspend fun readSelectedLocalContent(uri: Uri): SelectedLocalContent =
        withContext(Dispatchers.IO) {
            val fileName = queryDisplayName(uri)
                ?: uri.lastPathSegment?.substringAfterLast('/')
                ?: "未命名文件"
            val mimeType = contentResolver.getType(uri)
            val bytes = contentResolver.openInputStream(uri)
                ?.use { input -> input.readBytes() }
                ?: error("无法读取文件")
            SelectedLocalContent(fileName, mimeType, bytes)
        }

    private fun queryDisplayName(uri: Uri): String? {
        return runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                }
        }.getOrNull()?.takeIf { it.isNotBlank() }
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

private data class SelectedLocalContent(
    val fileName: String,
    val mimeType: String?,
    val bytes: ByteArray
)
