package com.lightningstudio.watchrss.phone

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.lightningstudio.watchrss.phone.BuildConfig
import com.lightningstudio.watchrss.phone.data.backup.WATCHRSS_BACKUP_EXTENSION
import com.lightningstudio.watchrss.phone.data.backup.WATCHRSS_BACKUP_MIME_TYPE
import com.lightningstudio.watchrss.phone.data.importer.LocalFileImportTarget
import com.lightningstudio.watchrss.phone.data.importer.classifyLocalFileImport
import com.lightningstudio.watchrss.phone.data.note.NoteImportExportService
import com.lightningstudio.watchrss.phone.ui.MainScreen
import com.lightningstudio.watchrss.phone.tips.TipParameters
import com.lightningstudio.watchrss.phone.tips.TipParameterValues
import com.lightningstudio.watchrss.phone.tips.ui.TipOverlayHost
import com.lightningstudio.watchrss.phone.tips.ui.TipSuppressionState
import com.lightningstudio.watchrss.phone.ui.TxtUpdateDialog
import com.lightningstudio.watchrss.phone.ui.TxtChapterImportDialog
import com.lightningstudio.watchrss.phone.ui.reader.ProvideReaderPreset
import com.lightningstudio.watchrss.phone.ui.theme.WatchRssPhoneTheme
import com.lightningstudio.watchrss.phone.platform.PlatformLinkRouter
import com.lightningstudio.watchrss.phone.viewmodel.MainUiState
import com.lightningstudio.watchrss.phone.viewmodel.MainViewModel
import com.lightningstudio.watchrss.phone.viewmodel.MainViewModelFactory
import com.lightningstudio.watchrss.phone.viewmodel.SharedImportPromptUi
import com.lightningstudio.watchrss.phone.update.AppUpdateDownloader
import com.lightningstudio.watchrss.phone.update.AppUpdateState
import com.lightningstudio.watchrss.phone.update.PhoneAnnouncement
import com.lightningstudio.watchrss.phone.update.PhoneAnnouncementRepository
import com.lightningstudio.watchrss.phone.update.PhoneStoreVersionRepository
import com.lightningstudio.watchrss.phone.update.OppoMarketLauncher
import com.lightningstudio.watchrss.phone.update.shouldOfferStoreUpdate
import com.lightningstudio.watchrss.phone.review.ReviewMoment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        val container = (application as PhoneCompanionApplication).container
        MainViewModelFactory(
            container.repository,
            container.bluetoothSyncManager,
            container.llmTokenUsageRepository,
            container.usageTelemetry,
            container.backupService,
            container.tipManager
        )
    }

    private var pendingBluetoothAction: (() -> Unit)? = null
    private val announcementRepository by lazy { PhoneAnnouncementRepository(this) }
    private val appUpdateDownloader by lazy { AppUpdateDownloader(this) }
    private val noteImportService by lazy {
        NoteImportExportService(
            this,
            (application as PhoneCompanionApplication).container.noteRepository
        )
    }
    private val pendingAnnouncement = mutableStateOf<PhoneAnnouncement?>(null)
    private val reviewCoordinator by lazy {
        (application as PhoneCompanionApplication).container.oppoReviewCoordinator
    }
    private val pendingReviewPrompt = mutableStateOf<ReviewMoment?>(null)
    private val storeVersionRepository by lazy { PhoneStoreVersionRepository(this) }
    private val pendingStoreUpdate = mutableStateOf<Int?>(null)
    private var homeScreenStartedAt: Long = 0L

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

    private val exportBackupLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument(WATCHRSS_BACKUP_MIME_TYPE)) { uri ->
            if (uri == null) {
                viewModel.showMessage("已取消导出资料库")
                return@registerForActivityResult
            }
            viewModel.exportBackup(uri.toString())
        }

    private val importLocalContentLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) {
                viewModel.showMessage("已取消导入文件")
                return@registerForActivityResult
            }
            lifecycleScope.launch {
                runCatching {
                    val fileName = withContext(Dispatchers.IO) {
                        queryDisplayName(uri)
                            ?: uri.lastPathSegment?.substringAfterLast('/')
                            ?: "未命名文件"
                    }
                    val mimeType = contentResolver.getType(uri)
                    if (isWrssBackup(fileName, mimeType)) {
                        runCatching {
                            contentResolver.takePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        }
                        viewModel.inspectBackup(fileName, uri.toString())
                    } else {
                        val file = readSelectedLocalContent(uri)
                        importSelectedFile(file)
                    }
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
                ProvideReaderPreset(container.readerPresetRepository) {
                    val state by viewModel.uiState.collectAsState()
                    val updateState by appUpdateDownloader.state.collectAsState()
                    val tipSuppression = remember { TipSuppressionState() }

                    LaunchedEffect(Unit) {
                        viewModel.toastEvent.collect { msg ->
                            Toast.makeText(this@HomeActivity, msg, Toast.LENGTH_SHORT).show()
                        }
                    }

                    TipOverlayHost(
                        tipManager = container.tipManager,
                        parameters = rememberMainTipParameters(state, tipSuppression)
                    ) {
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
                    onOpenProfile = {
                        startActivity(ProfileActivity.createIntent(this@HomeActivity))
                    },
                    onOpenNotes = {
                        startActivity(NotesActivity.createIntent(this@HomeActivity))
                    },
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
                    onSetRssSourceOriginalContentEnabled =
                        viewModel::setRssSourceOriginalContentEnabled,
                    onSetRssSourceContinuePlaybackInBackground =
                        viewModel::setRssSourceContinuePlaybackInBackground,
                    onClearRssSourceContent = viewModel::clearRssSourceContent,
                    rssInventoryMode = container.cloudSyncService::rssInventoryMode,
                    onSetRssInventoryMode = container.cloudSyncService::setRssInventoryMode,
                    onDeleteRssSource = viewModel::deleteRssSource,
                    onRefreshAllRssSources = viewModel::refreshAllRssSources,
                    onRefreshRssSource = viewModel::refreshRssSource,
                    onDeleteArticle = viewModel::deleteArticle,
                    onExportBackup = ::exportBackup,
                    onImportBackup = viewModel::importBackup,
                    onRequestBackupReplace = viewModel::requestBackupReplaceConfirmation,
                    onDismissBackupImport = viewModel::dismissBackupImportPrompt,
                    onChooseConflictResolution = viewModel::chooseConflictResolution,
                    onShowManualConflictOptions = viewModel::showManualConflictOptions,
                    onDismissMessage = viewModel::clearMessage,
                        onImportFile = ::selectLocalFile,
                        tipSuppression = tipSuppression
                    )
                    }
                    state.txtChapterPrompt?.let { prompt ->
                        TxtChapterImportDialog(
                            prompt = prompt,
                            onChooseChapterImport = viewModel::chooseTxtChapterImport,
                            onDismiss = viewModel::dismissTxtChapterPrompt
                        )
                    }
                    pendingAnnouncement.value?.let { announcement ->
                        val downloading = updateState is AppUpdateState.Downloading
                        AlertDialog(
                            onDismissRequest = {
                                if (!announcement.forceUpdate && !downloading) {
                                    announcementRepository.dismiss(announcement.version)
                                    pendingAnnouncement.value = null
                                }
                            },
                            title = {
                                Text((if (announcement.forceUpdate) "需要更新 " else "发现新版本 ") + announcement.version)
                            },
                            text = {
                                Column {
                                    Text(formatChangelog(announcement.changelogMarkdown))
                                    when (val download = updateState) {
                                        is AppUpdateState.Downloading -> {
                                            Spacer(Modifier.height(12.dp))
                                            val total = download.totalBytes
                                            if (total != null) {
                                                LinearProgressIndicator(
                                                    progress = { (download.bytesRead.toFloat() / total).coerceIn(0f, 1f) }
                                                )
                                                Text("${download.bytesRead * 100 / total}%")
                                            } else {
                                                LinearProgressIndicator()
                                                Text("已下载 ${download.bytesRead / 1024} KB")
                                            }
                                        }
                                        is AppUpdateState.Failed -> Text(download.message)
                                        is AppUpdateState.Ready -> {
                                            Text("下载完成，正在打开系统安装器")
                                            LaunchedEffect(download.apk) {
                                                appUpdateDownloader.launchInstaller(download.apk)
                                            }
                                        }
                                        AppUpdateState.Idle -> Unit
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(
                                    enabled = !downloading,
                                    onClick = {
                                        appUpdateDownloader.resetFailure()
                                        lifecycleScope.launch {
                                            appUpdateDownloader.download(
                                                announcement.version,
                                                announcement.downloadUrl
                                            )
                                        }
                                    }
                                ) { Text(if (downloading) "下载中" else "下载并安装") }
                            },
                            dismissButton = if (announcement.forceUpdate || downloading) null else {
                                {
                                    TextButton(onClick = {
                                        announcementRepository.dismiss(announcement.version)
                                        pendingAnnouncement.value = null
                                    }) { Text("稍后") }
                                }
                            }
                        )
                    }
                    pendingStoreUpdate.value?.let { versionCode ->
                        AlertDialog(
                            onDismissRequest = { pendingStoreUpdate.value = null },
                            title = { Text("发现新版本") },
                            text = {
                                Text("新版本已上架 OPPO 软件商店，前往商店完成更新。")
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    pendingStoreUpdate.value = null
                                    launchStoreUpdateOrFallback(versionCode)
                                }) { Text("立即更新") }
                            },
                            dismissButton = {
                                TextButton(onClick = { pendingStoreUpdate.value = null }) {
                                    Text("稍后")
                                }
                            }
                        )
                    }
                    pendingReviewPrompt.value?.let { moment ->
                        AlertDialog(
                            onDismissRequest = { reviewCoordinator.onPromptDeclined(moment) },
                            title = { Text("喜欢「腕上RSS」吗？") },
                            text = {
                                Text("在 OPPO 软件商店给我们一个好评，帮助更多用户发现这款应用。")
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    reviewCoordinator.onPromptShown(moment)
                                    reviewCoordinator.launchComment(this@HomeActivity)
                                }) { Text("去评分") }
                            },
                            dismissButton = {
                                TextButton(onClick = {
                                    reviewCoordinator.onPromptDeclined(moment)
                                }) { Text("下次再说") }
                            }
                        )
                    }
                    state.txtUpdatePrompt?.let { prompt ->
                        TxtUpdateDialog(
                            prompt = prompt,
                            onConfirmReplace = viewModel::confirmTxtUpdate,
                            onImportAsNew = viewModel::importPendingTxtAsNew,
                            onDismiss = viewModel::dismissTxtUpdatePrompt
                        )
                    }
                }
            }
        }
        handleInboundIntent(intent)
        lifecycleScope.launch {
            val marketAvailable = OppoMarketLauncher.isAvailable(this@HomeActivity)
            val storeVersion = if (marketAvailable) storeVersionRepository.check() else null
            if (shouldOfferStoreUpdate(marketAvailable, storeVersion, BuildConfig.VERSION_CODE)) {
                pendingStoreUpdate.value = storeVersion
            } else {
                pendingAnnouncement.value = announcementRepository.check()
            }
        }
        lifecycleScope.launch {
            reviewCoordinator.pendingPrompt.collect { moment ->
                pendingReviewPrompt.value = moment
            }
        }
        ensureBluetoothPermissions {
            (application as PhoneCompanionApplication).startWatchBaseStationIfPermitted()
        }
    }

    private fun launchStoreUpdateOrFallback(versionCode: Int) {
        if (OppoMarketLauncher.launchUpdate(this, versionCode)) return

        Toast.makeText(this, "无法打开 OPPO 软件商店，正在检查 APK 更新", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            // The user explicitly requested this update, so a prior "later" dismissal
            // must not hide an otherwise usable APK fallback.
            val announcement = announcementRepository.check(includeDismissed = true)
            pendingAnnouncement.value = announcement
            if (announcement == null) {
                Toast.makeText(
                    this@HomeActivity,
                    "软件商店不可用，未能获取可用的 APK 更新",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        (appUpdateDownloader.state.value as? AppUpdateState.Ready)?.let { ready ->
            if (packageManager.canRequestPackageInstalls()) {
                appUpdateDownloader.launchInstaller(ready.apk)
            }
        }
        homeScreenStartedAt = SystemClock.elapsedRealtime()
        (application as PhoneCompanionApplication).container.usageTelemetry.recordScreenOpen("phone_home")
    }

    private fun formatChangelog(markdown: String): String = markdown
        .replace(Regex("(?m)^#{1,6}\\s*"), "")
        .replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
        .replace(Regex("`([^`]+)`"), "$1")
        .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")
        .trim()

    override fun onPause() {
        super.onPause()
        val startedAt = homeScreenStartedAt
        if (startedAt > 0L) {
            (application as PhoneCompanionApplication).container.usageTelemetry.recordScreenDuration(
                "phone_home",
                SystemClock.elapsedRealtime() - startedAt
            )
            homeScreenStartedAt = 0L
        }
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
                add(android.Manifest.permission.BLUETOOTH_ADVERTISE)
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

    private fun exportBackup() {
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        exportBackupLauncher.launch("WatchRSS-backup-$timestamp$WATCHRSS_BACKUP_EXTENSION")
    }

    private fun selectLocalFile() {
        importLocalContentLauncher.launch(
            arrayOf(
                "text/plain",
                "text/*",
                "application/epub+zip",
                WATCHRSS_BACKUP_MIME_TYPE,
                "application/zip",
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
                runCatching { importSelectedFile(file) }
                    .onFailure { throwable ->
                        Log.e(TAG, "Failed to import shared local content", throwable)
                        viewModel.showContentError("文件导入失败：${throwable.message ?: "未知错误"}")
                    }
            }.onFailure { throwable ->
                Log.e(TAG, "Failed to read shared local content", throwable)
                viewModel.showContentError("文件导入失败：${throwable.message ?: "未知错误"}")
            }
        }
    }

    private suspend fun importSelectedFile(file: SelectedLocalContent) {
        when (classifyLocalFileImport(file.fileName, file.mimeType)) {
            LocalFileImportTarget.MARKDOWN_NOTE -> {
                val note = noteImportService.importMarkdown(file.fileName, file.mimeType, file.bytes)
                runCatching {
                    (application as PhoneCompanionApplication).container.cloudSyncService.syncNow()
                }
                viewModel.showContentMessage("已导入备忘录：${note.title}")
            }
            LocalFileImportTarget.LOCAL_CONTENT -> viewModel.importLocalContent(
                fileName = file.fileName,
                mimeType = file.mimeType,
                bytes = file.bytes
            )
            LocalFileImportTarget.UNSUPPORTED -> error("只支持 Markdown（.md）、TXT 和 EPUB 文件")
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
                        viewModel.showContentError(
                            if (errorMessage.isNullOrBlank()) {
                                "只支持导入 Markdown（.md）、TXT 或 EPUB 文件"
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
            val importTarget = classifyLocalFileImport(fileName, mimeType)
            if (importTarget == LocalFileImportTarget.UNSUPPORTED) return@withContext null
            InboundLocalFile(
                fileName = fileName,
                mimeType = mimeType,
                uriString = uri.toString(),
                importTarget = importTarget
            )
        } ?: return false
        viewModel.showSharedFilePrompt(
            fileName = file.fileName,
            mimeType = file.mimeType,
            uriString = file.uriString,
            markdownNote = file.importTarget == LocalFileImportTarget.MARKDOWN_NOTE
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

    private fun isWrssBackup(fileName: String, mimeType: String?): Boolean {
        return fileName.endsWith(WATCHRSS_BACKUP_EXTENSION, ignoreCase = true) ||
            mimeType.equals(WATCHRSS_BACKUP_MIME_TYPE, ignoreCase = true)
    }

    private fun String.trimUrlTail(): String =
        trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}', '>', '。', '，', '；', '：', '！', '？', '）', '】', '》')
}

private data class InboundLocalFile(
    val fileName: String,
    val mimeType: String?,
    val uriString: String,
    val importTarget: LocalFileImportTarget
)

/** 首页 Tip 参数快照：从 MainUiState 与转场状态提取规则所需的状态。 */
@androidx.compose.runtime.Composable
private fun rememberMainTipParameters(
    state: MainUiState,
    tipSuppression: TipSuppressionState
): TipParameterValues {
    return androidx.compose.runtime.remember(
        state.llmTokenUsageStats,
        state.rssSources,
        state.rssArticles,
        state.independentArticles,
        state.importedContentArticles,
        tipSuppression.active
    ) {
        TipParameterValues.Builder()
            .put(
                TipParameters.TOKEN_STATS_EMPTY,
                state.llmTokenUsageStats == null || (state.llmTokenUsageStats.totalTokens ?: 0L) == 0L
            )
            .put(
                TipParameters.HAS_ANY_ARTICLE,
                state.rssArticles.isNotEmpty() ||
                    state.independentArticles.isNotEmpty() ||
                    state.importedContentArticles.isNotEmpty()
            )
            .put(
                TipParameters.HAS_NO_IMPORTS,
                state.rssSources.isEmpty() &&
                    state.independentArticles.isEmpty() &&
                    state.importedContentArticles.isEmpty()
            )
            .put(TipParameters.SUPPRESS_TIPS, tipSuppression.active)
            .build()
    }
}
