package com.lightningstudio.watchrss.phone

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.lightningstudio.watchrss.phone.ui.MainScreen
import com.lightningstudio.watchrss.phone.ui.theme.WatchRssPhoneTheme
import com.lightningstudio.watchrss.phone.platform.PlatformLinkRouter
import com.lightningstudio.watchrss.phone.viewmodel.MainViewModel
import com.lightningstudio.watchrss.phone.viewmodel.MainViewModelFactory
import com.lightningstudio.watchrss.phone.viewmodel.SharedImportPromptUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeActivity : ComponentActivity() {
    companion object {
        private const val TAG = "腕上RSS_Home"
        private val URL_PATTERN = Regex("""https?://\S+""")

        fun createIntent(context: Context, inboundIntent: Intent? = null): Intent {
            return Intent(context, HomeActivity::class.java).apply {
                inboundIntent?.let { putExtras(it) }
            }
        }
    }

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
                viewModel.showSyncStatusMessage("已取消导出蓝牙日志")
                return@registerForActivityResult
            }
            lifecycleScope.launch {
                runCatching {
                    (application as PhoneCompanionApplication)
                        .container
                        .bluetoothDebugLog
                        .exportTo(contentResolver, uri)
                }.onSuccess { bytes ->
                    viewModel.showSyncStatusMessage("蓝牙日志已导出：$bytes 字节")
                }.onFailure { throwable ->
                    Log.e(TAG, "Failed to export bluetooth log", throwable)
                    viewModel.showSyncStatusError("蓝牙日志导出失败：${throwable.message ?: "未知错误"}")
                }
            }
        }

    private val importLocalContentLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) {
                viewModel.showMessage("已取消导入文件")
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
                    viewModel.showError("文件导入失败：${throwable.message ?: "未知错误"}")
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as PhoneCompanionApplication).container

        Log.i(TAG, "=== 腕上RSS 手机端 已启动 ===")
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

                LaunchedEffect(Unit) {
                    viewModel.toastEvent.collect { msg ->
                        Toast.makeText(this@HomeActivity, msg, Toast.LENGTH_SHORT).show()
                    }
                }

                MainScreen(
                    uiState = state,
                    onUrlChange = viewModel::updateUrlInput,
                    onImportArticle = viewModel::importIndependentArticle,
                    onAddRssSource = viewModel::addRssSource,
                    onImportSharedLinkAsArticle = viewModel::importSharedLinkAsIndependent,
                    onImportSharedLinkAsRss = viewModel::importSharedLinkAsRss,
                    onConfirmSharedFileImport = ::importSharedFile,
                    onDismissSharedImport = viewModel::dismissSharedImportPrompt,
                    onSyncLibrary = { ensureBluetoothPermissions(viewModel::syncLibraryByBluetooth) },
                    onChooseBluetoothDevice = viewModel::chooseBluetoothDeviceForSync,
                    onDismissBluetoothDevicePrompt = viewModel::dismissBluetoothDevicePrompt,
                    onExportBluetoothLog = ::exportBluetoothLog,
                    onOpenArticle = { article ->
                        val platform = PlatformLinkRouter.detect(article.url)
                        if (platform != null) {
                            startActivity(
                                PlatformWebViewActivity.createIntent(
                                    context = this@HomeActivity,
                                    title = article.title.ifBlank { article.url },
                                    url = article.url,
                                    articleId = article.articleId,
                                    initialReadingProgress = article.readingProgress
                                )
                            )
                        } else {
                            startActivity(ArticleReaderActivity.createIntent(this@HomeActivity, article.articleId))
                        }
                    },
                    canOpenArticleInline = { article -> article.articleId.isNotBlank() },
                    onLoadArticleForInlineReader = container.repository::getArticle,
                    onLoadImportedTextReaderForInlineReader = container.repository::getImportedTextReader,
                    onLoadImportedTextChunkForInlineReader = container.repository::loadImportedTextChunk,
                    onToggleFavorite = viewModel::toggleFavorite,
                    onToggleWatchLater = viewModel::toggleWatchLater,
                    onSaveArticleReadingProgress = { articleId, progress ->
                        container.repository.updateArticleReadingProgress(articleId, progress)
                    },
                    onMoveRssSourceToTop = viewModel::moveRssSourceToTop,
                    onReorderContentChannels = viewModel::reorderContentChannels,
                    onToggleRssSourcePinned = viewModel::toggleRssSourcePinned,
                    onDeleteRssSource = viewModel::deleteRssSource,
                    onRefreshAllRssSources = viewModel::refreshAllRssSources,
                    onRefreshRssSource = viewModel::refreshRssSource,
                    onDeleteArticle = viewModel::deleteArticle,
                    onClearImportedContent = viewModel::clearImportedContent,
                    onChooseConflictResolution = viewModel::chooseConflictResolution,
                    onShowManualConflictOptions = viewModel::showManualConflictOptions,
                    onDismissMessage = viewModel::clearMessage,
                    onImportFile = ::selectLocalFile
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

    private fun selectLocalFile() {
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

    private fun importSharedFile(prompt: SharedImportPromptUi) {
        val uri = runCatching { Uri.parse(prompt.uriString) }.getOrNull()
        if (uri == null) {
            viewModel.dismissSharedImportPrompt()
            viewModel.showError("文件地址无效")
            return
        }
        viewModel.dismissSharedImportPrompt()
        viewModel.showMessage("正在读取文件…")
        lifecycleScope.launch {
            runCatching {
                readSelectedLocalContent(uri, prompt.mimeType)
            }.onSuccess { file ->
                viewModel.importLocalContent(
                    fileName = file.fileName,
                    mimeType = file.mimeType,
                    bytes = file.bytes
                )
            }.onFailure { throwable ->
                Log.e(TAG, "Failed to read shared local content", throwable)
                viewModel.showError("文件导入失败：${throwable.message ?: "未知错误"}")
            }
        }
    }

    private suspend fun readSelectedLocalContent(
        uri: Uri,
        fallbackMimeType: String? = null
    ): SelectedLocalContent =
        withContext(Dispatchers.IO) {
            val fileName = queryDisplayName(uri)
                ?: uri.lastPathSegment?.substringAfterLast('/')
                ?: "未命名文件"
            val mimeType = contentResolver.getType(uri) ?: fallbackMimeType
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
        if (intent == null) return
        val url = extractInboundUrl(intent)
        val fileUri = extractInboundFileUri(intent)
        if (fileUri != null) {
            lifecycleScope.launch {
                var inspectError: Throwable? = null
                val handled = runCatching {
                    showInboundFilePrompt(fileUri, intent.type)
                }.onFailure { throwable ->
                    inspectError = throwable
                    Log.e(TAG, "Failed to inspect shared local content", throwable)
                }.getOrDefault(false)
                if (!handled) {
                    if (url != null) {
                        viewModel.showSharedLinkPrompt(url)
                    } else {
                        val errorMessage = inspectError?.message
                        viewModel.showError(
                            if (errorMessage.isNullOrBlank()) {
                                "只支持导入 TXT 或 EPUB 文件"
                            } else {
                                "无法读取文件：$errorMessage"
                            }
                        )
                    }
                }
            }
            return
        }
        if (url != null) {
            viewModel.showSharedLinkPrompt(url)
        }
    }

    private fun extractInboundUrl(intent: Intent?): String? {
        if (intent == null) return null
        val text = when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString?.takeIf { value ->
                value.startsWith("http://") || value.startsWith("https://")
            }
            Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                ?: firstClipText(intent)
            else -> null
        }
        return text?.lineSequence()
            ?.firstOrNull { line ->
                line.contains("http://") || line.contains("https://")
            }
            ?.let { line ->
                URL_PATTERN.find(line)?.value?.trimUrlTail() ?: line.trim()
            }
    }

    private fun extractInboundFileUri(intent: Intent): Uri? {
        return when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.takeIf(::isReadableLocalUri)
            Intent.ACTION_SEND -> streamUriExtra(intent)?.takeIf(::isReadableLocalUri)
                ?: firstClipUri(intent)?.takeIf(::isReadableLocalUri)
            else -> null
        }
    }

    private suspend fun showInboundFilePrompt(uri: Uri, intentMimeType: String?): Boolean {
        val file = withContext(Dispatchers.IO) {
            val fileName = queryDisplayName(uri)
                ?: uri.lastPathSegment?.substringAfterLast('/')
                ?: "未命名文件"
            val mimeType = contentResolver.getType(uri) ?: intentMimeType
            if (!isSupportedLocalContent(fileName, mimeType)) return@withContext null
            InboundLocalFile(
                fileName = fileName,
                mimeType = mimeType,
                uriString = uri.toString()
            )
        } ?: return false
        viewModel.showSharedFilePrompt(
            fileName = file.fileName,
            mimeType = file.mimeType,
            uriString = file.uriString
        )
        return true
    }

    private fun streamUriExtra(intent: Intent): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        }
    }

    private fun firstClipUri(intent: Intent): Uri? {
        val clipData = intent.clipData ?: return null
        return (0 until clipData.itemCount)
            .asSequence()
            .mapNotNull { index -> clipData.getItemAt(index).uri }
            .firstOrNull()
    }

    private fun firstClipText(intent: Intent): String? {
        val clipData = intent.clipData ?: return null
        return (0 until clipData.itemCount)
            .asSequence()
            .mapNotNull { index -> clipData.getItemAt(index).text?.toString() }
            .firstOrNull { it.isNotBlank() }
    }

    private fun isReadableLocalUri(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        return scheme == "content" || scheme == "file"
    }

    private fun isSupportedLocalContent(fileName: String, mimeType: String?): Boolean {
        val lowerName = fileName.lowercase()
        val lowerMime = mimeType.orEmpty().lowercase()
        return lowerName.endsWith(".txt") ||
            lowerName.endsWith(".epub") ||
            lowerMime.startsWith("text/") ||
            lowerMime == "application/epub+zip"
    }

    private fun String.trimUrlTail(): String =
        trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}', '>', '。', '，', '；', '：', '！', '？', '）', '】', '》')
}

private data class InboundLocalFile(
    val fileName: String,
    val mimeType: String?,
    val uriString: String
)
