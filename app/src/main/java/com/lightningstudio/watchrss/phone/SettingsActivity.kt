package com.lightningstudio.watchrss.phone

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FontDownload
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StrikethroughS
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lightningstudio.watchrss.phone.account.AccountEnvironment
import com.lightningstudio.watchrss.phone.account.RemoteEnvironment
import com.lightningstudio.watchrss.phone.account.RemoteEnvironmentStore
import com.lightningstudio.watchrss.phone.data.reader.ReaderBackgroundFit
import com.lightningstudio.watchrss.phone.connection.bluetooth.previewResourceSignature
import com.lightningstudio.watchrss.phone.data.reader.WatchBackgroundPreparationException
import com.lightningstudio.watchrss.phone.data.reader.ReaderBackgroundAssetEntity
import com.lightningstudio.watchrss.phone.data.reader.ReaderBackgroundType
import com.lightningstudio.watchrss.phone.data.reader.ReaderFontSynthesis
import com.lightningstudio.watchrss.phone.data.reader.ReaderFontAssetEntity
import com.lightningstudio.watchrss.phone.data.reader.ReaderHyphenation
import com.lightningstudio.watchrss.phone.data.reader.ReaderLineBreakMode
import com.lightningstudio.watchrss.phone.data.reader.ReaderPreset
import com.lightningstudio.watchrss.phone.data.reader.ReaderPresetCodec
import com.lightningstudio.watchrss.phone.data.reader.PreparedReaderPresetImport
import com.lightningstudio.watchrss.phone.data.reader.READER_PRESET_PACKAGE_EXTENSION
import com.lightningstudio.watchrss.phone.data.reader.READER_PRESET_PACKAGE_MIME_TYPE
import com.lightningstudio.watchrss.phone.data.reader.ReaderPresetLibraryImportChoice
import com.lightningstudio.watchrss.phone.data.reader.ReaderPresetPackageScope
import com.lightningstudio.watchrss.phone.data.reader.ReaderPresetRepository
import com.lightningstudio.watchrss.phone.data.reader.ReaderPresetSelection
import com.lightningstudio.watchrss.phone.data.reader.ReaderPresetSingleImportChoice
import com.lightningstudio.watchrss.phone.data.reader.ReaderPresetUndoEntry
import com.lightningstudio.watchrss.phone.data.reader.ReaderRenderMode
import com.lightningstudio.watchrss.phone.data.reader.ReaderThemeMode
import com.lightningstudio.watchrss.phone.data.reader.ReaderTextAlignment
import com.lightningstudio.watchrss.phone.data.reader.ReaderTextStyleOverride
import com.lightningstudio.watchrss.phone.data.reader.ReaderTypographyRole
import com.lightningstudio.watchrss.phone.data.reader.SystemReaderFont
import com.lightningstudio.watchrss.phone.ui.AdaptiveContentFrame
import com.lightningstudio.watchrss.phone.ui.AdaptiveReaderOpenThreePane
import com.lightningstudio.watchrss.phone.ui.AdaptiveReaderReturnThreePane
import com.lightningstudio.watchrss.phone.ui.AdaptiveTwoPane
import com.lightningstudio.watchrss.phone.ui.AdaptiveWindowInfo
import com.lightningstudio.watchrss.phone.ui.AdaptiveWindowScope
import com.lightningstudio.watchrss.phone.ui.reader.LocalReaderPresetRuntime
import com.lightningstudio.watchrss.phone.ui.reader.ReaderBackgroundSurface
import com.lightningstudio.watchrss.phone.ui.reader.ReaderPresetRuntime
import com.lightningstudio.watchrss.phone.ui.reader.ReaderTextRole
import com.lightningstudio.watchrss.phone.ui.reader.readerTextStyle
import com.lightningstudio.watchrss.phone.ui.PREDICTIVE_BACK_CANCEL_ANIMATION_MS
import com.lightningstudio.watchrss.phone.ui.PREDICTIVE_BACK_EXIT_ANIMATION_MS
import com.lightningstudio.watchrss.phone.ui.PREDICTIVE_BACK_EXIT_PROGRESS
import com.lightningstudio.watchrss.phone.ui.predictiveBackExitPreview
import com.lightningstudio.watchrss.phone.ui.theme.WatchRssPhoneTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository =
            (application as PhoneCompanionApplication).container.readerPresetRepository
        setContent {
            WatchRssPhoneTheme {
                ReaderSettingsHost(repository = repository, onFinish = ::finish)
            }
        }
    }

    companion object {
        fun createIntent(context: Context): Intent = Intent(context, SettingsActivity::class.java)
    }
}

private enum class SettingsPage {
    ROOT,
    PRESETS,
    EDITOR,
    CATEGORY_TYPOGRAPHY,
    FONTS,
    BACKGROUNDS,
    APP
}

private enum class FontSizeMode {
    RELATIVE,
    ABSOLUTE
}

private data class ImportedPresetApplyTarget(
    val id: String,
    val name: String,
    val warnings: List<String>
)

private data class SettingsPaneTransition(
    val from: SettingsPage,
    val to: SettingsPage
) {
    val isForward: Boolean
        get() = to.depth > from.depth
}

private val SettingsPage.depth: Int
    get() = when (this) {
        SettingsPage.ROOT -> 0
        SettingsPage.EDITOR,
        SettingsPage.CATEGORY_TYPOGRAPHY -> 2
        else -> 1
    }

private fun SettingsPage.parent(): SettingsPage = when (this) {
    SettingsPage.ROOT -> SettingsPage.ROOT
    SettingsPage.EDITOR -> SettingsPage.PRESETS
    SettingsPage.CATEGORY_TYPOGRAPHY -> SettingsPage.EDITOR
    else -> SettingsPage.ROOT
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderSettingsHost(
    repository: ReaderPresetRepository,
    onFinish: () -> Unit,
    leadingPane: (@Composable () -> Unit)? = null
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val container = (context.applicationContext as PhoneCompanionApplication).container
    val transferService = container.readerPresetTransferService
    val presets by repository.presets.collectAsStateWithLifecycle()
    val active by repository.activePreset.collectAsStateWithLifecycle()
    val selection by repository.selection.collectAsStateWithLifecycle()
    val fonts by repository.fonts.collectAsStateWithLifecycle()
    val backgrounds by repository.backgrounds.collectAsStateWithLifecycle()
    val importUndoEntries by transferService.undoEntries.collectAsStateWithLifecycle()
    val isSystemDark = isSystemInDarkTheme()
    LaunchedEffect(isSystemDark) {
        repository.setSystemDark(isSystemDark)
    }
    val scope = rememberCoroutineScope()
    var page by rememberSaveable { mutableStateOf(SettingsPage.ROOT) }
    var draft by remember { mutableStateOf<ReaderPreset?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var renamePreset by remember { mutableStateOf<ReaderPreset?>(null) }
    var deletePreset by remember { mutableStateOf<ReaderPreset?>(null) }
    var renameFont by remember { mutableStateOf<ReaderFontAssetEntity?>(null) }
    var deleteFont by remember { mutableStateOf<ReaderFontAssetEntity?>(null) }
    var fontDetails by remember { mutableStateOf<ReaderFontAssetEntity?>(null) }
    var lastAutoSavedFingerprint by remember { mutableStateOf<String?>(null) }
    var undoHistory by remember { mutableStateOf<List<ReaderPreset>>(emptyList()) }
    var redoHistory by remember { mutableStateOf<List<ReaderPreset>>(emptyList()) }
    var lastHistoryAt by remember { mutableStateOf(0L) }
    var pendingPresetExport by remember { mutableStateOf<ReaderPreset?>(null) }
    var pendingLibraryExport by remember { mutableStateOf(false) }
    var preparedPresetImport by remember { mutableStateOf<PreparedReaderPresetImport?>(null) }
    var presetTransferBusy by remember { mutableStateOf(false) }
    var importedPresetApplyTarget by remember {
        mutableStateOf<ImportedPresetApplyTarget?>(null)
    }
    var confirmImportUndoAfterChanges by remember { mutableStateOf(false) }
    var undoNowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    var editorApplyTarget by remember { mutableStateOf<ReaderPreset?>(null) }
    var syncAfterPermission by remember { mutableStateOf(false) }
    var previewAfterPermission by remember { mutableStateOf(false) }
    var watchPreviewEnabled by remember { mutableStateOf(false) }
    var watchPreviewStarting by remember { mutableStateOf(false) }
    var watchPreviewStartJob by remember { mutableStateOf<Job?>(null) }
    var watchPreviewDeviceAddress by remember { mutableStateOf<String?>(null) }
    var watchPreviewSessionId by remember { mutableStateOf<String?>(null) }
    var watchPreviewStatus by remember { mutableStateOf("关闭") }
    var watchPreviewUpdates by remember { mutableStateOf<Channel<ReaderPreset>?>(null) }
    var watchPreviewJob by remember { mutableStateOf<Job?>(null) }
    var watchPreviewResourceSignature by remember { mutableStateOf("") }
    val watchPreviewStopRequested = remember { AtomicBoolean(false) }
    var paneTransition by remember { mutableStateOf<SettingsPaneTransition?>(null) }
    var paneTransitionProgress by remember { mutableFloatStateOf(1f) }
    var showFontImportSourcePicker by remember { mutableStateOf(false) }
    var showSystemFontPicker by remember { mutableStateOf(false) }
    var systemFontsLoading by remember { mutableStateOf(false) }
    var systemFonts by remember { mutableStateOf<List<SystemReaderFont>>(emptyList()) }

    LaunchedEffect(transferService) {
        while (true) {
            undoNowMillis = System.currentTimeMillis()
            transferService.refreshUndoHistory()
            delay(1_000L)
        }
    }

    fun beginEditing(preset: ReaderPreset) {
        draft = preset
        lastAutoSavedFingerprint = preset.editableFingerprint()
        undoHistory = emptyList()
        redoHistory = emptyList()
        lastHistoryAt = 0L
    }

    fun updateDraft(next: ReaderPreset) {
        val current = draft ?: run {
            draft = next
            return
        }
        if (current.editableFingerprint() == next.editableFingerprint()) return
        val now = System.currentTimeMillis()
        if (undoHistory.isEmpty() || now - lastHistoryAt >= 350L) {
            undoHistory = (undoHistory + current).takeLast(100)
        }
        redoHistory = emptyList()
        lastHistoryAt = now
        draft = next
    }

    fun undoDraft() {
        val current = draft ?: return
        val previous = undoHistory.lastOrNull() ?: return
        undoHistory = undoHistory.dropLast(1)
        redoHistory = (redoHistory + current).takeLast(100)
        lastHistoryAt = 0L
        draft = previous
    }

    fun redoDraft() {
        val current = draft ?: return
        val next = redoHistory.lastOrNull() ?: return
        redoHistory = redoHistory.dropLast(1)
        undoHistory = (undoHistory + current).takeLast(100)
        lastHistoryAt = 0L
        draft = next
    }

    fun syncNow() {
        scope.launch {
            message = "正在探测手表…"
            message = runCatching {
                val targets = container.bluetoothSyncManager.probeLibrarySyncTargets()
                val devices = targets.devices
                require(devices.isNotEmpty()) { "未找到可同步的手表" }
                require(devices.size == 1) { "发现多块手表，请在资料库同步页选择目标" }
                val lease = targets.sessionLease
                try {
                    container.bluetoothSyncManager.syncReaderPresets(
                        deviceAddress = devices.single().address,
                        syncSession = lease
                    )
                    lease?.complete("settings-reader-sync-complete")
                } catch (throwable: Throwable) {
                    lease?.runCatching { abort("settings-reader-sync-abort") }
                    throw throwable
                } finally {
                    lease?.close()
                }
                "预设、整个字体库和引用背景已同步"
            }.getOrElse { it.message ?: "同步失败" }
        }
    }

    fun stopWatchPreview(showStatus: Boolean = true) {
        val updates = watchPreviewUpdates
        val job = watchPreviewJob
        watchPreviewStopRequested.set(true)
        watchPreviewStartJob?.cancel()
        watchPreviewStartJob = null
        watchPreviewEnabled = false
        watchPreviewStarting = false
        watchPreviewDeviceAddress = null
        watchPreviewSessionId = null
        watchPreviewUpdates = null
        watchPreviewJob = null
        if (showStatus) watchPreviewStatus = "关闭"
        updates?.close()
        if (job != null) {
            scope.launch {
                if (withTimeoutOrNull(2_000L) { job.join() } == null) job.cancel()
            }
        }
    }

    fun startWatchPreview() {
        val current = draft ?: return
        if (watchPreviewStarting || watchPreviewEnabled) return
        watchPreviewStarting = true
        watchPreviewStatus = "正在连接手表…"
        watchPreviewStopRequested.set(false)
        watchPreviewStartJob = scope.launch {
            runCatching {
                val targets = container.bluetoothSyncManager.probeLibrarySyncTargets()
                val devices = targets.devices
                require(devices.isNotEmpty()) { "未找到可预览的手表" }
                require(devices.size == 1) { "发现多块手表，请先只保留目标手表连接" }
                val device = devices.single()
                targets.sessionLease?.let { lease ->
                    runCatching { lease.complete("settings-preview-probe-complete") }
                    lease.close()
                }
                val updates = Channel<ReaderPreset>(Channel.CONFLATED)
                val firstConnection = CompletableDeferred<String>()
                val previewAddress = device.readerPreviewAddress
                watchPreviewDeviceAddress = previewAddress
                watchPreviewUpdates = updates
                val job = scope.launch preview@{
                    var connectionFailures = 0
                    while (!watchPreviewStopRequested.get() && watchPreviewUpdates === updates) {
                        val attemptSessionId = UUID.randomUUID().toString()
                        watchPreviewSessionId = attemptSessionId
                        val attemptPreset = draft ?: current
                        runCatching {
                            container.bluetoothSyncManager.streamReaderPresetPreview(
                                deviceAddress = previewAddress,
                                sessionId = attemptSessionId,
                                initialPreset = attemptPreset,
                                updates = updates,
                                onConnected = { deviceName ->
                                    connectionFailures = 0
                                    if (!firstConnection.isCompleted) {
                                        firstConnection.complete(deviceName)
                                    }
                                },
                                onStage = { stage ->
                                    if (watchPreviewUpdates === updates) watchPreviewStatus = stage
                                },
                                onApplied = {
                                    scope.launch {
                                        if (watchPreviewUpdates === updates) watchPreviewStatus = "手表已更新"
                                    }
                                }
                            )
                        }.onFailure {
                            if (it is kotlinx.coroutines.CancellationException) throw it
                            if (watchPreviewUpdates !== updates) return@preview
                            if (it is WatchBackgroundPreparationException) {
                                if (!firstConnection.isCompleted) firstConnection.completeExceptionally(it)
                                watchPreviewStatus = it.message ?: "背景预处理失败"
                                watchPreviewEnabled = false
                                watchPreviewStopRequested.set(true)
                                updates.close()
                            } else if (!watchPreviewStopRequested.get()) {
                                connectionFailures += 1
                                if (!firstConnection.isCompleted && connectionFailures >= 3) {
                                    firstConnection.completeExceptionally(it)
                                    return@preview
                                }
                                watchPreviewStatus = "连接中断，正在重新连接…"
                                delay(500L)
                            }
                        }
                    }
                }
                watchPreviewJob = job
                // Connection operations already have their own timeout. Preparation and transfer
                // can legitimately exceed 20 seconds; stopping cancels this wait via job completion.
                job.invokeOnCompletion { cause ->
                    if (!firstConnection.isCompleted) firstConnection.completeExceptionally(
                        cause ?: kotlinx.coroutines.CancellationException("预览已停止")
                    )
                }
                val deviceName = firstConnection.await()
                watchPreviewEnabled = true
                watchPreviewResourceSignature = (draft ?: current).previewResourceSignature()
                watchPreviewStatus = "正在“${deviceName.ifBlank { device.name }}”上预览"
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                // Do not cancel this start job while reporting its own failure.
                watchPreviewStartJob = null
                stopWatchPreview(showStatus = false)
                watchPreviewStatus = it.message ?: "无法开启手表预览"
            }
            watchPreviewStarting = false
        }
    }
    val bluetoothPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (syncAfterPermission && result.values.all { it }) syncNow()
            if (previewAfterPermission && result.values.all { it }) startWatchPreview()
            syncAfterPermission = false
            previewAfterPermission = false
        }
    fun requestSync() {
        val missing = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.BLUETOOTH_CONNECT
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            emptyArray()
        }
        if (missing.isEmpty()) syncNow() else {
            syncAfterPermission = true
            bluetoothPermissionLauncher.launch(missing)
        }
    }

    fun requestWatchPreview(enabled: Boolean) {
        if (!enabled) {
            stopWatchPreview()
            return
        }
        val missing = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.BLUETOOTH_CONNECT
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            emptyArray()
        }
        if (missing.isEmpty()) startWatchPreview() else {
            previewAfterPermission = true
            bluetoothPermissionLauncher.launch(missing)
        }
    }

    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            message = runCatching {
                persistReadPermission(context, uri)
                repository.importFont(uri)
                "字体已导入并按内容去重"
            }.getOrElse { it.message ?: "字体导入失败" }
        }
    }
    val presetExportPicker =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument(READER_PRESET_PACKAGE_MIME_TYPE)
        ) { uri ->
            val preset = pendingPresetExport
            pendingPresetExport = null
            if (uri == null || preset == null) return@rememberLauncherForActivityResult
            scope.launch {
                message = runCatching {
                    transferService.exportSingle(preset.id, uri)
                    "已导出“${preset.name}”，包含引用资源"
                }.getOrElse { it.message ?: "预设包导出失败" }
            }
        }
    val presetLibraryExportPicker =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument(READER_PRESET_PACKAGE_MIME_TYPE)
        ) { uri ->
            if (uri == null) {
                pendingLibraryExport = false
                return@rememberLauncherForActivityResult
            }
            scope.launch {
                message = runCatching {
                    transferService.exportLibrary(uri)
                    pendingLibraryExport = false
                    "已导出全部预设、字体和背景资源"
                }.getOrElse {
                    pendingLibraryExport = false
                    it.message ?: "全部预设导出失败"
                }
            }
        }
    val presetImportPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            scope.launch {
                presetTransferBusy = true
                runCatching {
                    persistReadPermission(context, uri)
                    transferService.inspect(uri)
                }.onSuccess { prepared ->
                    preparedPresetImport?.let(transferService::discard)
                    preparedPresetImport = prepared
                }.onFailure {
                    message = it.message ?: "无法读取预设包"
                }
                presetTransferBusy = false
            }
        }
    fun openFontFilePicker() {
        fontPicker.launch(
            arrayOf(
                "font/ttf",
                "font/otf",
                "font/collection",
                "application/x-font-ttf",
                "application/x-font-opentype",
                "application/x-font-ttc"
            )
        )
    }
    val backgroundPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            scope.launch {
                message = runCatching {
                    persistReadPermission(context, uri)
                    repository.importBackground(uri)
                    "背景资源已导入"
                }.getOrElse { it.message ?: "背景导入失败" }
            }
        }

    fun openSystemFontPicker() {
        showSystemFontPicker = true
        if (systemFonts.isNotEmpty() || systemFontsLoading) return
        systemFontsLoading = true
        scope.launch {
            runCatching { repository.availableSystemFonts() }
                .onSuccess { systemFonts = it }
                .onFailure {
                    showSystemFontPicker = false
                    message = it.message ?: "无法读取系统字体"
                }
            systemFontsLoading = false
        }
    }

    fun importSinglePreset(choice: ReaderPresetSingleImportChoice) {
        val prepared = preparedPresetImport ?: return
        presetTransferBusy = true
        scope.launch {
            runCatching { transferService.importSingle(prepared, choice) }
                .onSuccess { result ->
                    preparedPresetImport = null
                    importedPresetApplyTarget = ImportedPresetApplyTarget(
                        id = result.importedPresetIds.single(),
                        name = result.importedPresetNames.single(),
                        warnings = result.warnings
                    )
                }
                .onFailure {
                    preparedPresetImport = null
                    message = it.message ?: "预设导入失败"
                }
            presetTransferBusy = false
        }
    }

    fun importPresetLibrary(choice: ReaderPresetLibraryImportChoice) {
        val prepared = preparedPresetImport ?: return
        presetTransferBusy = true
        scope.launch {
            runCatching { transferService.importLibrary(prepared, choice) }
                .onSuccess { result ->
                    preparedPresetImport = null
                    val action = if (choice == ReaderPresetLibraryImportChoice.MERGE) {
                        "合并"
                    } else {
                        "替换"
                    }
                    message = buildString {
                        append("已${action}${result.importedPresetIds.size}个预设")
                        if (result.warnings.isNotEmpty()) {
                            append("\n")
                            append(result.warnings.joinToString("\n"))
                        }
                    }
                }
                .onFailure {
                    preparedPresetImport = null
                    message = it.message ?: "预设库导入失败"
                }
            presetTransferBusy = false
        }
    }

    fun undoLatestImport(force: Boolean = false) {
        presetTransferBusy = true
        scope.launch {
            runCatching { transferService.undoLatest(force) }
                .onSuccess { result ->
                    if (result.requiresConfirmation) {
                        confirmImportUndoAfterChanges = true
                    } else if (result.restoredLabel != null) {
                        confirmImportUndoAfterChanges = false
                        message = "已撤销：${result.restoredLabel}"
                    } else {
                        message = "没有可撤销的导入操作"
                    }
                }
                .onFailure { message = it.message ?: "撤销导入失败" }
            presetTransferBusy = false
        }
    }

    fun navigateTo(target: SettingsPage) {
        if (target == page || paneTransition != null) return
        val source = page
        val shouldMovePanes = leadingPane != null &&
            kotlin.math.abs(target.depth - source.depth) == 1
        page = target
        if (!shouldMovePanes) return

        val activeTransition = SettingsPaneTransition(source, target)
        paneTransition = activeTransition
        paneTransitionProgress = 0f
        scope.launch {
            animate(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = 480,
                    easing = FastOutSlowInEasing
                )
            ) { value, _ ->
                paneTransitionProgress = value
            }
            if (paneTransition == activeTransition) {
                paneTransition = null
                paneTransitionProgress = 1f
            }
        }
    }

    LaunchedEffect(draft) {
        val current = draft ?: return@LaunchedEffect
        val fingerprint = current.editableFingerprint()
        if (fingerprint == lastAutoSavedFingerprint) return@LaunchedEffect
        delay(350)
        val saved = runCatching { repository.savePreset(current) }
            .onFailure { message = it.message ?: "自动保存失败" }
            .getOrNull() ?: return@LaunchedEffect
        if (draft?.id == current.id && draft?.editableFingerprint() == fingerprint) {
            lastAutoSavedFingerprint = fingerprint
            draft = saved
        }
    }

    LaunchedEffect(
        watchPreviewEnabled,
        watchPreviewStarting,
        draft?.editableFingerprint()
    ) {
        if (!watchPreviewEnabled && !watchPreviewStarting) return@LaunchedEffect
        val current = draft ?: return@LaunchedEffect
        watchPreviewUpdates?.trySend(current)
    }
    LaunchedEffect(watchPreviewEnabled, draft?.previewResourceSignature()) {
        if (!watchPreviewEnabled) return@LaunchedEffect
        val signature = draft?.previewResourceSignature().orEmpty()
        if (signature == watchPreviewResourceSignature) return@LaunchedEffect
        watchPreviewResourceSignature = signature
        watchPreviewStatus = "正在传输预览资源…"
    }
    LaunchedEffect(page) {
        if (
            page != SettingsPage.EDITOR &&
            page != SettingsPage.CATEGORY_TYPOGRAPHY &&
            (watchPreviewEnabled || watchPreviewStarting)
        ) {
            stopWatchPreview()
        }
    }

    fun backFrom(source: SettingsPage) {
        if (source == SettingsPage.ROOT) {
            onFinish()
        } else {
            navigateTo(source.parent())
        }
    }

    var rootBackProgress by remember { mutableFloatStateOf(0f) }
    PredictiveBackHandler(
        enabled = page != SettingsPage.ROOT || leadingPane != null
    ) { events ->
        val source = page
        if (source == SettingsPage.ROOT) {
            try {
                events.collect { event ->
                    rootBackProgress = event.progress.coerceIn(0f, 1f)
                }
                animate(
                    initialValue = rootBackProgress,
                    targetValue = PREDICTIVE_BACK_EXIT_PROGRESS,
                    animationSpec = tween(PREDICTIVE_BACK_EXIT_ANIMATION_MS)
                ) { value, _ ->
                    rootBackProgress = value
                }
                onFinish()
            } catch (exception: CancellationException) {
                animate(
                    initialValue = rootBackProgress,
                    targetValue = 0f,
                    animationSpec = tween(PREDICTIVE_BACK_CANCEL_ANIMATION_MS)
                ) { value, _ ->
                    rootBackProgress = value
                }
            }
            return@PredictiveBackHandler
        }

        val target = source.parent()
        val predictiveTransition = SettingsPaneTransition(source, target)
        paneTransition = predictiveTransition
        paneTransitionProgress = 0f
        try {
            events.collect { event ->
                paneTransitionProgress = event.progress.coerceIn(0f, 1f)
            }
            val remainingDuration = (
                320 * (1f - paneTransitionProgress.coerceIn(0f, 1f))
                ).roundToInt().coerceAtLeast(1)
            animate(
                initialValue = paneTransitionProgress,
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = remainingDuration,
                    easing = FastOutSlowInEasing
                )
            ) { value, _ ->
                paneTransitionProgress = value
            }
            page = target
            paneTransition = null
            paneTransitionProgress = 1f
        } catch (exception: CancellationException) {
            animate(
                initialValue = paneTransitionProgress,
                targetValue = 0f,
                animationSpec = tween(PREDICTIVE_BACK_CANCEL_ANIMATION_MS)
            ) { value, _ ->
                paneTransitionProgress = value
            }
            if (paneTransition == predictiveTransition) {
                paneTransition = null
                paneTransitionProgress = 1f
            }
        }
    }

    val renderPage: @Composable (SettingsPage) -> Unit = { renderedPage ->
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            when (renderedPage) {
                                SettingsPage.ROOT -> "设置"
                                SettingsPage.PRESETS -> "阅读器与预设"
                                SettingsPage.EDITOR -> "编辑预设"
                                SettingsPage.CATEGORY_TYPOGRAPHY -> "分类字体设定"
                                SettingsPage.FONTS -> "字体库"
                                SettingsPage.BACKGROUNDS -> "背景资源"
                                SettingsPage.APP -> "应用功能"
                            }
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { backFrom(renderedPage) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        if (renderedPage == SettingsPage.EDITOR ||
                            renderedPage == SettingsPage.CATEGORY_TYPOGRAPHY
                        ) {
                            IconButton(
                                onClick = ::undoDraft,
                                enabled = undoHistory.isNotEmpty()
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "撤销")
                            }
                            IconButton(
                                onClick = ::redoDraft,
                                enabled = redoHistory.isNotEmpty()
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "重做")
                            }
                        }
                    }
                )
            }
        ) { padding ->
            when (renderedPage) {
                SettingsPage.ROOT -> SettingsRoot(
                    modifier = Modifier.padding(padding),
                    onOpenPresets = { navigateTo(SettingsPage.PRESETS) },
                    onOpenFonts = { navigateTo(SettingsPage.FONTS) },
                    onOpenBackgrounds = { navigateTo(SettingsPage.BACKGROUNDS) },
                    onOpenApp = { navigateTo(SettingsPage.APP) }
                )
                SettingsPage.PRESETS -> PresetManager(
                    presets = presets,
                    activeId = active.id,
                    selection = selection,
                    repository = repository,
                    modifier = Modifier.padding(padding),
                    onNew = {
                        scope.launch {
                            val template = if (isSystemDark) {
                                ReaderPreset.darkDefault(name = "新预设")
                            } else {
                                ReaderPreset.lightDefault(name = "新预设")
                            }
                            message = runCatching {
                                repository.saveAsNew(template, "新预设")
                            }.fold(
                                onSuccess = {
                                    beginEditing(it)
                                    navigateTo(SettingsPage.EDITOR)
                                    null
                                },
                                onFailure = { it.message ?: "新建预设失败" }
                            )
                        }
                    },
                    onThemeMode = repository::setThemeMode,
                    onLightPreset = repository::setLightPreset,
                    onDarkPreset = repository::setDarkPreset,
                    onEdit = {
                        beginEditing(it)
                        navigateTo(SettingsPage.EDITOR)
                    },
                    onDuplicate = {
                        scope.launch {
                            message = runCatching {
                                repository.duplicate(it.id)
                                "已复制“${it.name}”"
                            }.getOrElse { error -> error.message ?: "复制失败" }
                        }
                    },
                    onRename = { renamePreset = it },
                    onDelete = { deletePreset = it },
                    undoEntries = importUndoEntries,
                    undoNowMillis = undoNowMillis,
                    transferBusy = presetTransferBusy || pendingLibraryExport,
                    onImport = {
                        presetImportPicker.launch(
                            arrayOf(
                                READER_PRESET_PACKAGE_MIME_TYPE,
                                "application/zip",
                                "application/json",
                                "*/*"
                            )
                        )
                    },
                    onExportAll = {
                        pendingLibraryExport = true
                        presetLibraryExportPicker.launch(
                            "WatchRSS-reader-presets-${System.currentTimeMillis()}" +
                                READER_PRESET_PACKAGE_EXTENSION
                        )
                    },
                    onExportPreset = { preset ->
                        pendingPresetExport = preset
                        presetExportPicker.launch(
                            "${preset.safeExportName()}$READER_PRESET_PACKAGE_EXTENSION"
                        )
                    },
                    onUndoImport = { undoLatestImport() },
                    onSync = ::requestSync
                )
                SettingsPage.EDITOR -> {
                    draft?.let { current ->
                        PresetEditor(
                            draft = current,
                            fonts = fonts,
                            backgrounds = backgrounds,
                            repository = repository,
                            modifier = Modifier.padding(padding),
                            watchPreviewEnabled = watchPreviewEnabled,
                            watchPreviewStarting = watchPreviewStarting,
                            watchPreviewStatus = watchPreviewStatus,
                            onWatchPreviewChanged = ::requestWatchPreview,
                            onDraftChange = ::updateDraft,
                            onOpenCategoryTypography = {
                                navigateTo(SettingsPage.CATEGORY_TYPOGRAPHY)
                            },
                            onImportFont = { showFontImportSourcePicker = true },
                            onImportBackground = { type ->
                                backgroundPicker.launch(
                                    arrayOf(
                                        if (type == ReaderBackgroundType.IMAGE) {
                                            "image/*"
                                        } else {
                                            "video/*"
                                        }
                                    )
                                )
                            },
                            onExport = {
                                pendingPresetExport = current
                                presetExportPicker.launch(
                                    "${current.safeExportName()}$READER_PRESET_PACKAGE_EXTENSION"
                                )
                            },
                            onApply = {
                                scope.launch {
                                    message = runCatching {
                                        val saved = repository.savePreset(current)
                                        lastAutoSavedFingerprint = saved.editableFingerprint()
                                        draft = saved
                                        if (selection.darkFollowsLight) {
                                            repository.setLightPreset(saved.id)
                                            "已应用"
                                        } else {
                                            editorApplyTarget = saved
                                            null
                                        }
                                    }.getOrElse { it.message ?: "应用失败" }
                                }
                            }
                        )
                    }
                }
                SettingsPage.CATEGORY_TYPOGRAPHY -> {
                    draft?.let { current ->
                        CategoryTypographyEditor(
                            draft = current,
                            fonts = fonts,
                            repository = repository,
                            modifier = Modifier.padding(padding),
                            onDraftChange = ::updateDraft,
                            onImportFont = { showFontImportSourcePicker = true }
                        )
                    }
                }
                SettingsPage.FONTS -> FontLibrary(
                    fonts = fonts,
                    fontFile = repository::fontFile,
                    modifier = Modifier.padding(padding),
                    onImport = { showFontImportSourcePicker = true },
                    onDetails = { fontDetails = it },
                    onRename = { renameFont = it },
                    onDelete = { deleteFont = it }
                )
                SettingsPage.BACKGROUNDS -> AssetLibrary(
                    title = "原图/原视频按 SHA-256 去重。视频片段最长 60 秒，手表版本由同步时派生。",
                    entries = backgrounds.map {
                        "${it.displayName}\n${it.kind} · ${it.width}×${it.height} · ${formatBytes(it.byteCount)}"
                    },
                    importLabel = "导入图片或视频",
                    modifier = Modifier.padding(padding),
                    onImport = { backgroundPicker.launch(arrayOf("image/*", "video/*")) }
                )
                SettingsPage.APP -> AppFeatureSettings(Modifier.padding(padding))
            }
        }
    }

    AdaptiveWindowScope(
        modifier = Modifier
            .fillMaxSize()
            .predictiveBackExitPreview(rootBackProgress)
    ) { windowInfo ->
        if (leadingPane != null && windowInfo.isMediumOrExpanded) {
            SettingsPaneStack(
                windowInfo = windowInfo,
                page = page,
                transition = paneTransition,
                transitionProgress = paneTransitionProgress,
                leadingPane = leadingPane,
                renderPage = renderPage
            )
        } else if (paneTransition != null) {
            SettingsSinglePaneTransition(
                transition = paneTransition!!,
                progress = paneTransitionProgress,
                renderPage = renderPage
            )
        } else {
            AnimatedContent(
                targetState = page,
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds(),
                transitionSpec = {
                    val direction = if (targetState.parent() == initialState) {
                        1
                    } else {
                        -1
                    }
                    (
                        slideInHorizontally(
                            animationSpec = tween(
                                durationMillis = 320,
                                easing = FastOutSlowInEasing
                            )
                        ) { fullWidth -> fullWidth * direction } +
                            fadeIn(
                                animationSpec = tween(
                                    durationMillis = 220,
                                    delayMillis = 60
                                )
                            )
                        ) togetherWith (
                        slideOutHorizontally(
                            animationSpec = tween(
                                durationMillis = 320,
                                easing = FastOutSlowInEasing
                            )
                        ) { fullWidth -> -fullWidth * direction } +
                            fadeOut(animationSpec = tween(durationMillis = 160))
                        )
                },
                label = "settings-compact-page"
            ) { renderedPage ->
                renderPage(renderedPage)
            }
        }
    }

    if (showFontImportSourcePicker) {
        FontImportSourceDialog(
            onDismiss = { showFontImportSourcePicker = false },
            onSystemFont = {
                showFontImportSourcePicker = false
                openSystemFontPicker()
            },
            onCustomFile = {
                showFontImportSourcePicker = false
                openFontFilePicker()
            }
        )
    }

    editorApplyTarget?.let { preset ->
        AlertDialog(
            onDismissRequest = { editorApplyTarget = null },
            title = { Text("应用“${preset.name}”") },
            text = { Text("选择要替换的阅读器预设槽位。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        repository.setLightPreset(preset.id)
                        editorApplyTarget = null
                    }
                ) { Text("应用于浅色") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { editorApplyTarget = null }) { Text("取消") }
                    TextButton(
                        onClick = {
                            repository.setDarkPreset(preset.id)
                            editorApplyTarget = null
                        }
                    ) { Text("应用于深色") }
                }
            }
        )
    }

    if (showSystemFontPicker) {
        SystemFontPickerDialog(
            fonts = systemFonts,
            loading = systemFontsLoading,
            onDismiss = { showSystemFontPicker = false },
            onSelect = { font ->
                showSystemFontPicker = false
                scope.launch {
                    message = runCatching {
                        val imported = repository.importSystemFont(font)
                        "已将“${imported.displayName}”复制到字体库"
                    }.getOrElse { it.message ?: "系统字体导入失败" }
                }
            }
        )
    }

    fontDetails?.let { font ->
        AlertDialog(
            onDismissRequest = { fontDetails = null },
            title = { Text(font.displayName) },
            text = {
                Text(
                    "字体家族：${font.familyName}\n" +
                        "字体面：${font.faceCount}\n" +
                        "格式：${font.mimeType}\n" +
                        "大小：${formatBytes(font.byteCount)}\n" +
                        "文件：${font.fileName}\n" +
                        "SHA-256：${font.sha256}"
                )
            },
            confirmButton = {
                TextButton(onClick = { fontDetails = null }) { Text("关闭") }
            }
        )
    }
    renameFont?.let { font ->
        TextInputDialog(
            title = "重命名字体",
            initial = font.displayName,
            onDismiss = { renameFont = null },
            onConfirm = { name ->
                renameFont = null
                scope.launch {
                    message = runCatching {
                        repository.renameFont(font.id, name)
                        "字体已重命名"
                    }.getOrElse { it.message ?: "重命名失败" }
                }
            }
        )
    }
    deleteFont?.let { font ->
        val usageNames = repository.fontUsageNames(font.id)
        AlertDialog(
            onDismissRequest = { deleteFont = null },
            title = { Text("删除“${font.displayName}”？") },
            text = {
                Text(
                    if (usageNames.isEmpty()) {
                        "删除会生成同步墓碑，并从手机和手表字体库移除。"
                    } else {
                        "以下预设正在使用该字体：${usageNames.joinToString("、")}。\n\n" +
                            "删除后这些预设会改用系统默认字体，并同步此变更。"
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteFont = null
                        draft = null
                        scope.launch {
                            repository.deleteFont(font.id)
                            message = "字体已删除"
                        }
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteFont = null }) { Text("取消") }
            }
        )
    }

    preparedPresetImport?.let { prepared ->
        PresetImportDialog(
            prepared = prepared,
            busy = presetTransferBusy,
            onDismiss = {
                transferService.discard(prepared)
                preparedPresetImport = null
            },
            onSingleOverwrite = {
                importSinglePreset(ReaderPresetSingleImportChoice.OVERWRITE)
            },
            onSingleCopy = {
                importSinglePreset(ReaderPresetSingleImportChoice.COPY)
            },
            onLibraryMerge = {
                importPresetLibrary(ReaderPresetLibraryImportChoice.MERGE)
            },
            onLibraryReplace = {
                importPresetLibrary(ReaderPresetLibraryImportChoice.REPLACE)
            }
        )
    }

    importedPresetApplyTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { importedPresetApplyTarget = null },
            title = { Text("已导入“${target.name}”") },
            text = {
                Text(
                    buildString {
                        append("选择是否立即应用到本机阅读器。")
                        if (target.warnings.isNotEmpty()) {
                            append("\n\n")
                            append(target.warnings.joinToString("\n"))
                        }
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        repository.setLightPreset(target.id)
                        importedPresetApplyTarget = null
                    }
                ) { Text("应用于浅色") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { importedPresetApplyTarget = null }) {
                        Text("暂不应用")
                    }
                    TextButton(
                        onClick = {
                            repository.setDarkPreset(target.id)
                            importedPresetApplyTarget = null
                        }
                    ) { Text("应用于深色") }
                }
            }
        )
    }

    if (confirmImportUndoAfterChanges) {
        AlertDialog(
            onDismissRequest = { confirmImportUndoAfterChanges = false },
            title = { Text("预设库在导入后已有修改") },
            text = { Text("继续撤销会恢复到该次导入前的阅读器预设库，并覆盖之后的手工修改。") },
            confirmButton = {
                TextButton(
                    onClick = { undoLatestImport(force = true) },
                    enabled = !presetTransferBusy
                ) { Text("仍然撤销") }
            },
            dismissButton = {
                TextButton(onClick = { confirmImportUndoAfterChanges = false }) {
                    Text("取消")
                }
            }
        )
    }

    message?.let {
        AlertDialog(
            onDismissRequest = { message = null },
            confirmButton = { TextButton(onClick = { message = null }) { Text("知道了") } },
            text = { Text(it) }
        )
    }
    renamePreset?.let { preset ->
        TextInputDialog(
            title = "重命名预设",
            initial = preset.name,
            onDismiss = { renamePreset = null },
            onConfirm = { name ->
                renamePreset = null
                scope.launch {
                    message = runCatching {
                        repository.rename(preset.id, name)
                        "已重命名"
                    }.getOrElse { it.message ?: "重命名失败" }
                }
            }
        )
    }
    deletePreset?.let { preset ->
        AlertDialog(
            onDismissRequest = { deletePreset = null },
            title = { Text("删除“${preset.name}”？") },
            text = { Text("删除会产生同步墓碑；若它正在使用，本机立即回退到安全样式。") },
            confirmButton = {
                TextButton(onClick = {
                    deletePreset = null
                    scope.launch {
                        repository.deletePreset(preset.id)
                        message = "预设已删除"
                    }
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deletePreset = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun PresetImportDialog(
    prepared: PreparedReaderPresetImport,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSingleOverwrite: () -> Unit,
    onSingleCopy: () -> Unit,
    onLibraryMerge: () -> Unit,
    onLibraryReplace: () -> Unit
) {
    val preview = prepared.preview
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = {
            Text(
                if (preview.scope == ReaderPresetPackageScope.SINGLE) {
                    "导入阅读器预设"
                } else {
                    "导入全部预设"
                }
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "预设 ${preview.presetCount} 个 · 字体 ${preview.fontCount} 个 · " +
                        "背景 ${preview.backgroundCount} 个"
                )
                Text(
                    "文件大小：${formatBytes(preview.packageBytes ?: preview.resourceBytes)}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    preview.presetNames.take(5).joinToString("、") +
                        if (preview.presetNames.size > 5) " 等" else "",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (preview.hasSingleIdConflict) {
                    Text(
                        "本机已有同一预设，可覆盖原项或另存为新预设。",
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                preview.warnings.forEach { warning ->
                    Text(
                        warning,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (preview.scope == ReaderPresetPackageScope.LIBRARY) {
                    Text(
                        "合并会保留本机独有内容；替换只影响预设、字体和背景库。两种操作均可在五分钟内撤销。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (busy) Text("正在处理…", color = MaterialTheme.colorScheme.primary)
            }
        },
        confirmButton = {
            when (preview.scope) {
                ReaderPresetPackageScope.SINGLE -> TextButton(
                    onClick = onSingleOverwrite,
                    enabled = !busy
                ) {
                    Text(if (preview.hasSingleIdConflict) "覆盖原预设" else "导入")
                }
                ReaderPresetPackageScope.LIBRARY -> TextButton(
                    onClick = onLibraryMerge,
                    enabled = !busy
                ) { Text("合并") }
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
                if (preview.scope == ReaderPresetPackageScope.SINGLE &&
                    preview.hasSingleIdConflict
                ) {
                    TextButton(onClick = onSingleCopy, enabled = !busy) {
                        Text("另存为新预设")
                    }
                }
                if (preview.scope == ReaderPresetPackageScope.LIBRARY) {
                    TextButton(onClick = onLibraryReplace, enabled = !busy) {
                        Text("替换", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    )
}

@Composable
private fun SettingsSinglePaneTransition(
    transition: SettingsPaneTransition,
    progress: Float,
    renderPage: @Composable (SettingsPage) -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
    ) {
        val transitionProgress = progress.coerceIn(0f, 1f)
        if (transition.isForward) {
            Box(
                modifier = Modifier
                    .offset(x = -(maxWidth * 0.12f * transitionProgress))
                    .fillMaxSize()
            ) {
                renderPage(transition.from)
            }
            Box(
                modifier = Modifier
                    .offset(x = maxWidth * (1f - transitionProgress))
                    .fillMaxSize()
            ) {
                renderPage(transition.to)
            }
        } else {
            Box(
                modifier = Modifier
                    .offset(x = -(maxWidth * 0.12f * (1f - transitionProgress)))
                    .fillMaxSize()
            ) {
                renderPage(transition.to)
            }
            Box(
                modifier = Modifier
                    .offset(x = maxWidth * transitionProgress)
                    .fillMaxSize()
            ) {
                renderPage(transition.from)
            }
        }
    }
}

@Composable
private fun SettingsPaneStack(
    windowInfo: AdaptiveWindowInfo,
    page: SettingsPage,
    transition: SettingsPaneTransition?,
    transitionProgress: Float,
    leadingPane: @Composable () -> Unit,
    renderPage: @Composable (SettingsPage) -> Unit
) {
    if (transition == null) {
        if (page == SettingsPage.EDITOR || page == SettingsPage.CATEGORY_TYPOGRAPHY) {
            renderPage(page)
            return
        }
        AdaptiveTwoPane(
            windowInfo = windowInfo,
            horizontalPadding = 0.dp,
            paneSpacing = 0.dp,
            startPane = {
                when (page.depth) {
                    0 -> leadingPane()
                    1 -> renderPage(SettingsPage.ROOT)
                    else -> renderPage(SettingsPage.PRESETS)
                }
            },
            endPane = {
                when (page.depth) {
                    0 -> renderPage(SettingsPage.ROOT)
                    else -> renderPage(page)
                }
            }
        )
        return
    }

    if (transition.from.depth == transition.to.depth) {
        SettingsSinglePaneTransition(
            transition = transition,
            progress = transitionProgress,
            renderPage = renderPage
        )
        return
    }

    val deeperPage = if (transition.from.depth > transition.to.depth) {
        transition.from
    } else {
        transition.to
    }
    if (deeperPage == SettingsPage.EDITOR) {
        SettingsEditorPageTransition(
            windowInfo = windowInfo,
            progress = if (transition.isForward) {
                transitionProgress
            } else {
                1f - transitionProgress
            },
            renderPage = renderPage
        )
        return
    }
    val startPane: @Composable () -> Unit
    val movingPane: @Composable () -> Unit
    val enteringPane: @Composable () -> Unit
    if (deeperPage.depth == 1) {
        startPane = leadingPane
        movingPane = { renderPage(SettingsPage.ROOT) }
        enteringPane = { renderPage(deeperPage) }
    } else {
        startPane = { renderPage(SettingsPage.ROOT) }
        movingPane = { renderPage(SettingsPage.PRESETS) }
        enteringPane = { renderPage(SettingsPage.EDITOR) }
    }

    if (transition.isForward) {
        AdaptiveReaderOpenThreePane(
            windowInfo = windowInfo,
            progress = transitionProgress,
            horizontalPadding = 0.dp,
            paneSpacing = 0.dp,
            startPane = startPane,
            movingPane = movingPane,
            readerPane = enteringPane
        )
    } else {
        AdaptiveReaderReturnThreePane(
            windowInfo = windowInfo,
            progress = transitionProgress,
            horizontalPadding = 0.dp,
            paneSpacing = 0.dp,
            startPane = startPane,
            movingPane = movingPane,
            readerPane = enteringPane
        )
    }
}

@Composable
private fun SettingsEditorPageTransition(
    windowInfo: AdaptiveWindowInfo,
    progress: Float,
    renderPage: @Composable (SettingsPage) -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
    ) {
        val editorProgress = progress.coerceIn(0f, 1f)
        Box(
            modifier = Modifier
                .offset(x = -(maxWidth * editorProgress))
                .width(maxWidth)
                .fillMaxHeight()
        ) {
            AdaptiveTwoPane(
                windowInfo = windowInfo,
                horizontalPadding = 0.dp,
                paneSpacing = 0.dp,
                startPane = { renderPage(SettingsPage.ROOT) },
                endPane = { renderPage(SettingsPage.PRESETS) }
            )
        }
        Box(
            modifier = Modifier
                .offset(x = maxWidth * (1f - editorProgress))
                .width(maxWidth)
                .fillMaxHeight()
        ) {
            renderPage(SettingsPage.EDITOR)
        }
    }
}

@Composable
private fun SettingsRoot(
    modifier: Modifier,
    onOpenPresets: () -> Unit,
    onOpenFonts: () -> Unit,
    onOpenBackgrounds: () -> Unit,
    onOpenApp: () -> Unit
) {
    AdaptiveWindowScope(modifier = modifier.fillMaxSize()) { windowInfo ->
        AdaptiveContentFrame(
            windowInfo = windowInfo,
            mediumMaxWidth = 680.dp,
            expandedMaxWidth = 760.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        horizontal = if (windowInfo.isMediumOrExpanded) 32.dp else 20.dp,
                        vertical = 20.dp
                    ),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Text(
                    "阅读体验",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingsEntry(
                        "阅读器与预设",
                        "实时草稿预览、管理和设备独立应用",
                        Icons.Default.Edit,
                        onOpenPresets
                    )
                    SettingsEntry(
                        "字体库",
                        "TTF、OTF、TTC 与可变字体轴",
                        Icons.Default.FontDownload,
                        onOpenFonts
                    )
                    SettingsEntry(
                        "背景资源",
                        "纯色、图片和最长 60 秒视频",
                        Icons.Default.Image,
                        onOpenBackgrounds
                    )
                    SettingsEntry(
                        "应用功能",
                        if (BuildConfig.DEBUG) {
                            "缓存、分享、抖音 Cookie、AI 总结、引导与性能工具"
                        } else {
                            "缓存、分享、抖音 Cookie 与数据管理"
                        },
                        Icons.Default.Settings,
                        onOpenApp
                    )
                }
                Text(
                    "预设应用状态仅保存在当前设备；内容和资源可以同步到手表。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun PresetManager(
    presets: List<ReaderPreset>,
    activeId: String,
    selection: ReaderPresetSelection,
    repository: ReaderPresetRepository,
    modifier: Modifier,
    onNew: () -> Unit,
    onThemeMode: (ReaderThemeMode) -> Unit,
    onLightPreset: (String?) -> Unit,
    onDarkPreset: (String?) -> Unit,
    onEdit: (ReaderPreset) -> Unit,
    onDuplicate: (ReaderPreset) -> Unit,
    onRename: (ReaderPreset) -> Unit,
    onDelete: (ReaderPreset) -> Unit,
    undoEntries: List<ReaderPresetUndoEntry>,
    undoNowMillis: Long,
    transferBusy: Boolean,
    onImport: () -> Unit,
    onExportAll: () -> Unit,
    onExportPreset: (ReaderPreset) -> Unit,
    onUndoImport: () -> Unit,
    onSync: () -> Unit
) {
    var applyTarget by remember { mutableStateOf<ReaderPreset?>(null) }
    var cardMenuPresetId by remember { mutableStateOf<String?>(null) }
    SettingsColumn(modifier) {
        Button(onClick = onNew, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Add, contentDescription = null)
            Text(" 新建预设")
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onImport,
                enabled = !transferBusy,
                modifier = Modifier.weight(1f)
            ) { Text("导入预设包") }
            OutlinedButton(
                onClick = onExportAll,
                enabled = !transferBusy,
                modifier = Modifier.weight(1f)
            ) { Text("导出全部") }
        }
        undoEntries.lastOrNull()?.let { latest ->
            val remainingSeconds = ((latest.expiresAt - undoNowMillis + 999L) / 1_000L)
                .coerceAtLeast(0L)
            ElevatedCard(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("可撤销 ${undoEntries.size} 次导入", fontWeight = FontWeight.SemiBold)
                        Text(
                            "最近：${latest.label} · 剩余 ${remainingSeconds / 60}分" +
                                "${(remainingSeconds % 60).toString().padStart(2, '0')}秒",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = onUndoImport, enabled = !transferBusy) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null)
                        Text(" 撤销")
                    }
                }
            }
        }
        OutlinedButton(onClick = onSync, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Sync, contentDescription = null)
            Text(" 立即同步到手表")
        }
        Text(
            "“正在使用”只保存在本机；预设内容、重命名和删除会参与同步。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        PresetAssignmentSection(
            presets = presets,
            selection = selection,
            repository = repository,
            onThemeMode = onThemeMode,
            onLightPreset = onLightPreset,
            onDarkPreset = onDarkPreset
        )
        presets.forEach { preset ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(preset.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                buildList {
                                    if (preset.id == activeId) add("当前显示")
                                    if (preset.id == selection.lightPresetId) add("浅色预设")
                                    if (!selection.darkFollowsLight &&
                                        preset.id == selection.darkPresetId
                                    ) {
                                        add("深色预设")
                                    }
                                }.joinToString(" · ").ifBlank { "未应用" },
                                color = if (preset.id == activeId) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                        Button(
                            onClick = {
                                if (selection.darkFollowsLight) {
                                    onLightPreset(preset.id)
                                } else {
                                    applyTarget = preset
                                }
                            }
                        ) { Text("应用") }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton(onClick = { onEdit(preset) }) { Text("编辑") }
                        TextButton(onClick = { onDuplicate(preset) }) { Text("复制") }
                        TextButton(onClick = { onRename(preset) }) { Text("重命名") }
                        Box {
                            IconButton(onClick = { cardMenuPresetId = preset.id }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "更多")
                            }
                            DropdownMenu(
                                expanded = cardMenuPresetId == preset.id,
                                onDismissRequest = { cardMenuPresetId = null }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("导出此预设") },
                                    onClick = {
                                        cardMenuPresetId = null
                                        onExportPreset(preset)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("删除") },
                                    onClick = {
                                        cardMenuPresetId = null
                                        onDelete(preset)
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Delete, contentDescription = null)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    applyTarget?.let { preset ->
        AlertDialog(
            onDismissRequest = { applyTarget = null },
            title = { Text("应用“${preset.name}”") },
            text = { Text("选择要替换的阅读器预设槽位。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onLightPreset(preset.id)
                        applyTarget = null
                    }
                ) { Text("应用于浅色") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { applyTarget = null }) { Text("取消") }
                    TextButton(
                        onClick = {
                            onDarkPreset(preset.id)
                            applyTarget = null
                        }
                    ) { Text("应用于深色") }
                }
            }
        )
    }
}

@Composable
private fun PresetAssignmentSection(
    presets: List<ReaderPreset>,
    selection: ReaderPresetSelection,
    repository: ReaderPresetRepository,
    onThemeMode: (ReaderThemeMode) -> Unit,
    onLightPreset: (String?) -> Unit,
    onDarkPreset: (String?) -> Unit
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("自动应用", style = MaterialTheme.typography.titleMedium)
            Text(
                "选择阅读器使用浅色、深色或跟随系统。槽位选择只保存在本机。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    ReaderThemeMode.DARK,
                    ReaderThemeMode.LIGHT,
                    ReaderThemeMode.SYSTEM
                ).forEach { mode ->
                    FilterChip(
                        selected = selection.mode == mode,
                        onClick = { onThemeMode(mode) },
                        label = {
                            Text(
                                when (mode) {
                                    ReaderThemeMode.DARK -> "深色"
                                    ReaderThemeMode.LIGHT -> "浅色"
                                    ReaderThemeMode.SYSTEM -> "跟随系统"
                                }
                            )
                        }
                    )
                }
            }
            PresetPreviewDropdown(
                label = "浅色预设",
                presets = presets,
                selectedId = selection.lightPresetId,
                allowFollowLight = false,
                repository = repository,
                onSelected = onLightPreset
            )
            PresetPreviewDropdown(
                label = "深色预设",
                presets = presets,
                selectedId = selection.darkPresetId,
                allowFollowLight = true,
                followsLight = selection.darkFollowsLight,
                repository = repository,
                onSelected = onDarkPreset
            )
        }
    }
}

@Composable
private fun PresetPreviewDropdown(
    label: String,
    presets: List<ReaderPreset>,
    selectedId: String?,
    allowFollowLight: Boolean,
    repository: ReaderPresetRepository,
    followsLight: Boolean = false,
    onSelected: (String?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = presets.firstOrNull { it.id == selectedId }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label)
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            if (allowFollowLight && followsLight) {
                Text("跟随浅色", Modifier.weight(1f))
            } else if (selected != null) {
                PresetSingleLinePreview(
                    preset = selected,
                    repository = repository,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                )
            } else {
                Text("请选择预设", Modifier.weight(1f))
            }
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "展开")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            if (allowFollowLight) {
                DropdownMenuItem(
                    text = { Text("跟随浅色") },
                    onClick = {
                        expanded = false
                        onSelected(null)
                    }
                )
            }
            presets.forEach { preset ->
                DropdownMenuItem(
                    text = {
                        PresetSingleLinePreview(
                            preset = preset,
                            repository = repository,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelected(preset.id)
                    }
                )
            }
        }
    }
}

@Composable
private fun PresetSingleLinePreview(
    preset: ReaderPreset,
    repository: ReaderPresetRepository,
    modifier: Modifier = Modifier
) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalReaderPresetRuntime provides ReaderPresetRuntime(
            preset = preset,
            fontFile = repository::fontFile,
            backgroundFile = repository::backgroundFile
        )
    ) {
        ReaderBackgroundSurface(
            modifier = modifier.clip(RoundedCornerShape(10.dp))
        ) {
            Text(
                "${preset.name} · 阅读正文预览",
                style = readerTextStyle(ReaderTextRole.BODY),
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(horizontal = 12.dp)
            )
        }
    }
}

@Composable
private fun PresetEditor(
    draft: ReaderPreset,
    fonts: List<ReaderFontAssetEntity>,
    backgrounds: List<ReaderBackgroundAssetEntity>,
    repository: ReaderPresetRepository,
    modifier: Modifier,
    watchPreviewEnabled: Boolean,
    watchPreviewStarting: Boolean,
    watchPreviewStatus: String,
    onWatchPreviewChanged: (Boolean) -> Unit,
    onDraftChange: (ReaderPreset) -> Unit,
    onOpenCategoryTypography: () -> Unit,
    onImportFont: () -> Unit,
    onImportBackground: (ReaderBackgroundType) -> Unit,
    onExport: () -> Unit,
    onApply: () -> Unit
) {
    val body = draft.body
    val background = draft.background
    val selectedFont = fonts.firstOrNull { it.id == body.fontAssetId }
    val fontFaces = remember(selectedFont?.metadataJson) {
        selectedFont?.fontFaceOptions().orEmpty()
    }
    val fontWeightRange = remember(selectedFont?.metadataJson, body.fontFaceIndex) {
        selectedFont?.variableAxisRange(body.fontFaceIndex, "wght")
    }
    LaunchedEffect(selectedFont?.id, selectedFont?.faceCount, body.fontFaceIndex) {
        if (selectedFont != null &&
            body.fontFaceIndex !in 0 until selectedFont.faceCount.coerceAtLeast(1)
        ) {
            onDraftChange(draft.copy(body = body.copy(fontFaceIndex = 0)))
        }
    }
    var previewExpanded by rememberSaveable(draft.id) { mutableStateOf(false) }
    if (previewExpanded) {
        Dialog(
            onDismissRequest = { previewExpanded = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            ReaderPresetPreview(
                preset = draft,
                repository = repository,
                modifier = Modifier.fillMaxSize(),
                expanded = true,
                onToggleExpanded = { previewExpanded = false }
            )
        }
    }
    PresetEditorLayout(
        modifier = modifier,
        preview = { previewModifier ->
            ReaderPresetPreview(
                preset = draft,
                repository = repository,
                modifier = previewModifier,
                expanded = false,
                onToggleExpanded = { previewExpanded = true }
            )
        }
    ) {
        OutlinedTextField(
            value = draft.name,
            onValueChange = { onDraftChange(draft.copy(name = it)) },
            label = { Text("预设名称") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            ListItem(
                headlineContent = { Text("手表实时预览") },
                supportingContent = {
                    Text(watchPreviewStatus)
                },
                trailingContent = {
                    Switch(
                        checked = watchPreviewEnabled || watchPreviewStarting,
                        onCheckedChange = onWatchPreviewChanged
                    )
                },
                modifier = Modifier.clickable {
                    onWatchPreviewChanged(!(watchPreviewEnabled || watchPreviewStarting))
                }
            )
        }
        SectionTitle("正文")
        ResourceDropdownRow(
            label = "字体",
            values = listOf(null to "系统字体") + fonts.map { it.id to it.displayName },
            selected = body.fontAssetId,
            importLabel = "导入字体",
            onImport = onImportFont,
            onSelected = {
                onDraftChange(
                    draft.copy(body = body.copy(fontAssetId = it, fontFaceIndex = 0))
                )
            }
        )
        if (fontFaces.size > 1) {
            ChoiceRow(
                label = "字体面",
                values = fontFaces,
                selected = body.fontFaceIndex.toString(),
                onSelected = { index ->
                    onDraftChange(
                        draft.copy(
                            body = body.copy(fontFaceIndex = index?.toIntOrNull() ?: 0)
                        )
                    )
                }
            )
        }
        NumericSlider("文字大小", body.fontSizeSp, 10f..64f, "sp") {
            onDraftChange(draft.copy(body = body.copy(fontSizeSp = it)))
        }
        NumericSlider(
            label = "字重",
            value = body.fontWeight.toFloat(),
            range = fontWeightRange ?: (100f..900f),
            unit = "",
            supported = selectedFont == null || fontWeightRange != null
        ) {
            onDraftChange(draft.copy(body = body.copy(fontWeight = it.roundToInt())))
        }
        TextStyleToolbar(
            italic = body.italic,
            underline = body.underline,
            strikethrough = body.strikethrough,
            onItalic = {
                onDraftChange(draft.copy(body = body.copy(italic = it)))
            },
            onUnderline = {
                onDraftChange(draft.copy(body = body.copy(underline = it)))
            },
            onStrikethrough = {
                onDraftChange(draft.copy(body = body.copy(strikethrough = it)))
            }
        )
        ColorField("文字颜色", body.colorArgb) {
            onDraftChange(draft.copy(body = body.copy(colorArgb = it)))
        }
        OutlinedTextField(
            value = body.variationSettings,
            onValueChange = {
                onDraftChange(draft.copy(body = body.copy(variationSettings = it)))
            },
            label = { Text("可变字体轴，例如 'wght' 650") },
            modifier = Modifier.fillMaxWidth()
        )
        SectionTitle("排版")
        NumericSlider("行高", body.lineHeightEm, 0.8f..3f, "em") {
            onDraftChange(draft.copy(body = body.copy(lineHeightEm = it)))
        }
        NumericSlider("字距", body.letterSpacingEm, -0.1f..0.5f, "em") {
            onDraftChange(draft.copy(body = body.copy(letterSpacingEm = it)))
        }
        NumericSlider("段距", body.paragraphSpacingDp, 0f..64f, "dp") {
            onDraftChange(draft.copy(body = body.copy(paragraphSpacingDp = it)))
        }
        NumericSlider("首行缩进", body.firstLineIndentEm, 0f..4f, "em") {
            onDraftChange(draft.copy(body = body.copy(firstLineIndentEm = it)))
        }
        NumericSlider("页边距", body.horizontalPaddingDp, 0f..64f, "dp") {
            onDraftChange(draft.copy(body = body.copy(horizontalPaddingDp = it)))
        }
        EnumChoice("对齐", ReaderTextAlignment.entries, body.alignment) {
            onDraftChange(draft.copy(body = body.copy(alignment = it)))
        }
        EnumChoice("断行", ReaderLineBreakMode.entries, body.lineBreakMode) {
            onDraftChange(draft.copy(body = body.copy(lineBreakMode = it)))
        }
        EnumChoice("连字符", ReaderHyphenation.entries, body.hyphenation) {
            onDraftChange(draft.copy(body = body.copy(hyphenation = it)))
        }
        SectionTitle("字体渲染")
        EnumChoice("文字运动", ReaderRenderMode.entries, body.renderMode) {
            onDraftChange(draft.copy(body = body.copy(renderMode = it)))
        }
        EnumChoice("字体合成", ReaderFontSynthesis.entries, body.fontSynthesis) {
            onDraftChange(draft.copy(body = body.copy(fontSynthesis = it)))
        }
        Text(
            "系统默认、静态清晰、线性平滑分别映射到 Compose TextMotion；字体合成映射到 FontSynthesis。",
            style = MaterialTheme.typography.bodySmall
        )
        SectionTitle("背景")
        EnumChoice("类型", ReaderBackgroundType.entries, background.type) {
            val selectedKind = backgrounds
                .firstOrNull { asset -> asset.id == background.assetId }
                ?.kind
            onDraftChange(
                draft.copy(
                    background = background.copy(
                        type = it,
                        assetId = background.assetId.takeIf { assetId ->
                            assetId != null && selectedKind == it.name
                        }
                    )
                )
            )
        }
        if (background.type == ReaderBackgroundType.SOLID) {
            ColorField("纯色背景", background.colorArgb) {
                onDraftChange(draft.copy(background = background.copy(colorArgb = it)))
            }
        } else {
            val matchingBackgrounds = backgrounds.filter { it.kind == background.type.name }
            ResourceDropdownRow(
                label = if (background.type == ReaderBackgroundType.IMAGE) {
                    "图片背景"
                } else {
                    "视频背景"
                },
                values = listOf(null to "不选择") + matchingBackgrounds.map {
                    it.id to it.displayName
                },
                selected = background.assetId,
                importLabel = if (background.type == ReaderBackgroundType.IMAGE) {
                    "导入图片"
                } else {
                    "导入视频"
                },
                onImport = { onImportBackground(background.type) },
                onSelected = {
                    onDraftChange(draft.copy(background = background.copy(assetId = it)))
                }
            )
            EnumChoice("适配方式", ReaderBackgroundFit.entries, background.fit) {
                onDraftChange(draft.copy(background = background.copy(fit = it)))
            }
            NumericSlider("水平焦点", background.focusX, 0f..1f, "") {
                onDraftChange(draft.copy(background = background.copy(focusX = it)))
            }
            NumericSlider("垂直焦点", background.focusY, 0f..1f, "") {
                onDraftChange(draft.copy(background = background.copy(focusY = it)))
            }
            NumericSlider("缩放", background.zoom, 0.25f..8f, "×") {
                onDraftChange(draft.copy(background = background.copy(zoom = it)))
            }
            NumericSlider("旋转", background.rotationDegrees, -180f..180f, "°") {
                onDraftChange(draft.copy(background = background.copy(rotationDegrees = it)))
            }
            NumericSlider("模糊", background.blurDp, 0f..64f, "dp") {
                onDraftChange(draft.copy(background = background.copy(blurDp = it)))
            }
            NumericSlider("亮度", background.brightness, 0f..2f, "×") {
                onDraftChange(draft.copy(background = background.copy(brightness = it)))
            }
            NumericSlider("饱和度", background.saturation, 0f..2f, "×") {
                onDraftChange(draft.copy(background = background.copy(saturation = it)))
            }
            ColorField("遮罩颜色", background.overlayColorArgb) {
                onDraftChange(draft.copy(background = background.copy(overlayColorArgb = it)))
            }
            NumericSlider("遮罩透明度", background.overlayOpacity, 0f..1f, "") {
                onDraftChange(draft.copy(background = background.copy(overlayOpacity = it)))
            }
        }
        ColorField("强调色", draft.accentColorArgb) {
            onDraftChange(draft.copy(accentColorArgb = it))
        }
        ColorField("代码背景", draft.codeBackgroundColorArgb) {
            onDraftChange(draft.copy(codeBackgroundColorArgb = it))
        }
        Text(
            "用于文章与备忘录中的代码块和行内代码；始终使用纯色圆角背景，不绘制边框。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (background.type == ReaderBackgroundType.VIDEO) {
            SectionTitle("视频")
            NumericSlider(
                "片段开始",
                background.videoTrimStartMs / 1000f,
                0f..60f,
                "秒"
            ) {
                onDraftChange(
                    draft.copy(
                        background = background.copy(videoTrimStartMs = (it * 1000).toLong())
                    )
                )
            }
            NumericSlider(
                "片段结束",
                background.videoTrimEndMs / 1000f,
                0f..120f,
                "秒"
            ) {
                onDraftChange(
                    draft.copy(
                        background = background.copy(videoTrimEndMs = (it * 1000).toLong())
                    )
                )
            }
            NumericSlider("播放速度", background.videoSpeed, 0.25f..4f, "×") {
                onDraftChange(draft.copy(background = background.copy(videoSpeed = it)))
            }
            ToggleRow("循环播放", background.loop) {
                onDraftChange(draft.copy(background = background.copy(loop = it)))
            }
        }
        SectionTitle("分类字体")
        CategoryTypographyEntry(
            enabled = draft.categoryTypographyEnabled,
            onEnabledChange = {
                onDraftChange(draft.copy(categoryTypographyEnabled = it))
            },
            onOpen = onOpenCategoryTypography
        )
        Text(
            "调整会实时保存。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = onExport, Modifier.weight(1f)) {
                Text("导出")
            }
            Button(onClick = onApply, Modifier.weight(1f)) {
                Text("应用")
            }
        }
    }
}

@Composable
private fun CategoryTypographyEntry(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onOpen: () -> Unit
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text("分类字体设定") },
            supportingContent = {
                Text(
                    if (enabled) {
                        "已启用，可分别调整标题、副标题、引用、代码和链接"
                    } else {
                        "使用内置分类比例；开启后可进入调整"
                    }
                )
            },
            trailingContent = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Switch(
                        checked = enabled,
                        onCheckedChange = onEnabledChange
                    )
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = if (enabled) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                        }
                    )
                }
            },
            modifier = Modifier.clickable(enabled = enabled, onClick = onOpen)
        )
    }
}

@Composable
private fun CategoryTypographyEditor(
    draft: ReaderPreset,
    fonts: List<ReaderFontAssetEntity>,
    repository: ReaderPresetRepository,
    modifier: Modifier,
    onDraftChange: (ReaderPreset) -> Unit,
    onImportFont: () -> Unit
) {
    var previewExpanded by rememberSaveable(draft.id) { mutableStateOf(false) }
    if (previewExpanded) {
        Dialog(
            onDismissRequest = { previewExpanded = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            ReaderPresetPreview(
                preset = draft,
                repository = repository,
                modifier = Modifier.fillMaxSize(),
                expanded = true,
                onToggleExpanded = { previewExpanded = false }
            )
        }
    }
    PresetEditorLayout(
        modifier = modifier,
        preview = { previewModifier ->
            ReaderPresetPreview(
                preset = draft,
                repository = repository,
                modifier = previewModifier,
                expanded = false,
                onToggleExpanded = { previewExpanded = true }
            )
        }
    ) {
        Text(
            "各分类先按正文生成默认比例，再叠加这里的设置；调整会实时保存。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        RoleOverrideEditor(
            label = "标题",
            role = ReaderTypographyRole.TITLE,
            value = draft.title,
            preset = draft,
            fonts = fonts,
            onImportFont = onImportFont,
            onChange = { onDraftChange(draft.copy(title = it)) }
        )
        RoleOverrideEditor(
            label = "副标题",
            role = ReaderTypographyRole.SUBTITLE,
            value = draft.subtitle,
            preset = draft,
            fonts = fonts,
            onImportFont = onImportFont,
            onChange = { onDraftChange(draft.copy(subtitle = it)) }
        )
        RoleOverrideEditor(
            label = "引用",
            role = ReaderTypographyRole.QUOTE,
            value = draft.quote,
            preset = draft,
            fonts = fonts,
            onImportFont = onImportFont,
            onChange = { onDraftChange(draft.copy(quote = it)) }
        )
        RoleOverrideEditor(
            label = "代码",
            role = ReaderTypographyRole.CODE,
            value = draft.code,
            preset = draft,
            fonts = fonts,
            onImportFont = onImportFont,
            onChange = { onDraftChange(draft.copy(code = it)) }
        )
        RoleOverrideEditor(
            label = "链接",
            role = ReaderTypographyRole.LINK,
            value = draft.link,
            preset = draft,
            fonts = fonts,
            onImportFont = onImportFont,
            onChange = { onDraftChange(draft.copy(link = it)) }
        )
        Text(
            "此页的调整会实时保存，可使用顶部按钮撤销或重做。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ReaderPresetPreview(
    preset: ReaderPreset,
    repository: ReaderPresetRepository,
    modifier: Modifier,
    expanded: Boolean,
    onToggleExpanded: () -> Unit
) {
    val body = preset.body
    val previewScroll = rememberScrollState()
    androidx.compose.runtime.CompositionLocalProvider(
        LocalReaderPresetRuntime provides ReaderPresetRuntime(
            preset = preset,
            fontFile = repository::fontFile,
            backgroundFile = repository::backgroundFile
        )
    ) {
        Box(
            modifier = modifier
                .clip(if (expanded) RoundedCornerShape(0.dp) else RoundedCornerShape(20.dp))
                .background(Color.Black)
        ) {
            ReaderBackgroundSurface(Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(previewScroll)
                        .padding(
                            horizontal = body.horizontalPaddingDp.dp.coerceAtLeast(20.dp),
                            vertical = if (expanded) 56.dp else 36.dp
                        ),
                    verticalArrangement = Arrangement.spacedBy(
                        body.paragraphSpacingDp.dp.coerceAtLeast(12.dp)
                    )
                ) {
                    Text(
                        "在腕上，也能安静地读完一篇文章",
                        style = readerTextStyle(ReaderTextRole.TITLE)
                    )
                    Text(
                        "阅读器预设实时样张 · 2026 年 7 月 29 日",
                        style = readerTextStyle(ReaderTextRole.SUBTITLE)
                    )
                    Text(
                        "清晨的光线越过窗沿，落在尚未读完的书页上。好的阅读界面不会抢走注意力，" +
                            "它只负责让文字保持清楚、节奏自然，并在不同尺寸的屏幕上留出恰当的呼吸感。",
                        style = readerTextStyle(ReaderTextRole.BODY)
                    )
                    Text(
                        "这段较长的正文用于观察字体、字号、字重、字距、行高、首行缩进、对齐方式和断行效果。" +
                            "调整设置时，样张会立即更新并自动保存。",
                        style = readerTextStyle(ReaderTextRole.BODY)
                    )
                    Text(
                        "“真正舒服的排版，往往是在你忘记排版本身的时候。”",
                        style = readerTextStyle(ReaderTextRole.QUOTE)
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(preset.codeBackgroundColorArgb))
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Text(
                            "reader.apply(preset)\nrender(title, body, quote, code, link)",
                            style = readerTextStyle(ReaderTextRole.CODE)
                        )
                    }
                    Text(
                        "链接与强调色示例：继续阅读完整内容",
                        style = readerTextStyle(ReaderTextRole.LINK)
                    )
                    Text(
                        "当背景换成图片或视频时，还可以在这里检查遮罩、亮度、饱和度与文字颜色之间的对比。" +
                            "展开到全屏后，样张会保留当前位置，方便连续检查更长的阅读内容。",
                        style = readerTextStyle(ReaderTextRole.BODY)
                    )
                    Spacer(Modifier.height(48.dp))
                }
            }
            IconButton(
                onClick = onToggleExpanded,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .background(
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                        RoundedCornerShape(14.dp)
                    )
            ) {
                Icon(
                    if (expanded) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                    contentDescription = if (expanded) "退出全屏预览" else "全屏预览"
                )
            }
        }
    }
}

@Composable
private fun PresetEditorLayout(
    modifier: Modifier,
    preview: @Composable (Modifier) -> Unit,
    controls: @Composable ColumnScope.() -> Unit
) {
    val configuration = LocalConfiguration.current
    AdaptiveWindowScope(modifier = modifier.fillMaxSize()) { windowInfo ->
        val useSideBySide =
            configuration.smallestScreenWidthDp >= 600 && windowInfo.width >= 600.dp
        if (useSideBySide) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    PresetPreviewHeading()
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        preview(Modifier.fillMaxSize())
                    }
                    PresetPreviewHint()
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = controls
                )
            }
        } else {
            AdaptiveContentFrame(
                windowInfo = windowInfo,
                mediumMaxWidth = 680.dp,
                expandedMaxWidth = 760.dp
            ) {
                val previewHeight = (windowInfo.height * 0.34f).coerceIn(180.dp, 300.dp)
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    PresetPreviewHeading()
                    preview(
                        Modifier
                            .fillMaxWidth()
                            .height(previewHeight)
                    )
                    PresetPreviewHint()
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        content = controls
                    )
                }
            }
        }
    }
}

@Composable
private fun PresetPreviewHeading() {
    Text(
        "实时预览",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun PresetPreviewHint() {
    Text(
        "预览随调整更新，改动会实时保存。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun TextStyleToolbar(
    italic: Boolean,
    underline: Boolean,
    strikethrough: Boolean,
    onItalic: (Boolean) -> Unit,
    onUnderline: (Boolean) -> Unit,
    onStrikethrough: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StyleIconButton(
            selected = italic,
            contentDescription = "斜体",
            icon = Icons.Default.FormatItalic,
            onClick = { onItalic(!italic) }
        )
        StyleIconButton(
            selected = underline,
            contentDescription = "下划线",
            icon = Icons.Default.FormatUnderlined,
            onClick = { onUnderline(!underline) }
        )
        StyleIconButton(
            selected = strikethrough,
            contentDescription = "中轴线（删除线）",
            icon = Icons.Default.StrikethroughS,
            onClick = { onStrikethrough(!strikethrough) }
        )
    }
}

@Composable
private fun StyleIconButton(
    selected: Boolean,
    contentDescription: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .semantics { this.selected = selected }
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    Color.Transparent
                },
                RoundedCornerShape(14.dp)
            )
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (selected) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

@Composable
private fun RoleOverrideEditor(
    label: String,
    role: ReaderTypographyRole,
    value: ReaderTextStyleOverride,
    preset: ReaderPreset,
    fonts: List<ReaderFontAssetEntity>,
    onImportFont: () -> Unit,
    onChange: (ReaderTextStyleOverride) -> Unit
) {
    val body = preset.body
    val defaults = preset.categoryDefault(role)
    val effective = value.resolve(body, defaults)
    val selectedFontId = value.fontAssetId.takeIf { value.useOwnFont }
    val selectedFont = fonts.firstOrNull { it.id == selectedFontId }
    val fontFaces = remember(selectedFont?.metadataJson) {
        selectedFont?.fontFaceOptions().orEmpty()
    }
    val effectiveFont = if (value.useOwnFont) {
        selectedFont
    } else {
        fonts.firstOrNull { it.id == body.fontAssetId }
    }
    val effectiveFaceIndex = if (value.useOwnFont) {
        value.fontFaceIndex ?: 0
    } else {
        body.fontFaceIndex
    }
    val fontWeightRange = remember(effectiveFont?.metadataJson, effectiveFaceIndex) {
        effectiveFont?.variableAxisRange(effectiveFaceIndex, "wght")
    }
    val sizeMode = if (value.fontSizeSp != null) {
        FontSizeMode.ABSOLUTE
    } else {
        FontSizeMode.RELATIVE
    }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(label, fontWeight = FontWeight.SemiBold)
            ResourceDropdownRow(
                label = "字体",
                values = listOf(null to "继承正文字体") + fonts.map { it.id to it.displayName },
                selected = selectedFontId,
                importLabel = "导入字体",
                onImport = onImportFont,
                onSelected = {
                    onChange(
                        value.copy(
                            useOwnFont = it != null,
                            fontAssetId = it,
                            fontFaceIndex = if (it == null) null else 0
                        )
                    )
                }
            )
            if (selectedFont != null && fontFaces.size > 1) {
                ChoiceRow(
                    label = "字体面",
                    values = fontFaces,
                    selected = (value.fontFaceIndex ?: 0).toString(),
                    onSelected = {
                        onChange(value.copy(fontFaceIndex = it?.toIntOrNull() ?: 0))
                    }
                )
            }
            Text("字号模式", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = sizeMode == FontSizeMode.RELATIVE,
                    onClick = {
                        onChange(
                            value.copy(
                                fontScale = (effective.fontSizeSp / body.fontSizeSp)
                                    .coerceIn(0.5f, 2.5f),
                                fontSizeSp = null
                            )
                        )
                    },
                    label = { Text("相对") }
                )
                FilterChip(
                    selected = sizeMode == FontSizeMode.ABSOLUTE,
                    onClick = {
                        onChange(
                            value.copy(
                                fontSizeSp = effective.fontSizeSp.coerceIn(10f, 64f),
                                fontScale = null
                            )
                        )
                    },
                    label = { Text("绝对") }
                )
            }
            if (sizeMode == FontSizeMode.RELATIVE) {
                NumericSlider(
                    "相对字号",
                    value.fontScale ?: defaults.fontScale ?: 1f,
                    0.5f..2.5f,
                    "×"
                ) {
                    onChange(value.copy(fontScale = it, fontSizeSp = null))
                }
            } else {
                NumericSlider(
                    "绝对字号",
                    value.fontSizeSp ?: effective.fontSizeSp,
                    10f..64f,
                    "sp"
                ) {
                    onChange(value.copy(fontSizeSp = it, fontScale = null))
                }
            }
            NumericSlider(
                label = "字重",
                value = effective.fontWeight.toFloat(),
                range = fontWeightRange ?: (100f..900f),
                unit = "",
                supported = effectiveFont == null || fontWeightRange != null
            ) {
                onChange(value.copy(fontWeight = it.roundToInt()))
            }
            TextStyleToolbar(
                italic = effective.italic,
                underline = effective.underline,
                strikethrough = effective.strikethrough,
                onItalic = { onChange(value.copy(italic = it)) },
                onUnderline = { onChange(value.copy(underline = it)) },
                onStrikethrough = { onChange(value.copy(strikethrough = it)) }
            )
            ColorField("颜色", effective.colorArgb) {
                onChange(value.copy(colorArgb = it))
            }
            NumericSlider("行高", effective.lineHeightEm, 0.8f..3f, "em") {
                onChange(value.copy(lineHeightEm = it))
            }
            NumericSlider("字距", effective.letterSpacingEm, -0.1f..0.5f, "em") {
                onChange(value.copy(letterSpacingEm = it))
            }
            EnumChoice("对齐", ReaderTextAlignment.entries, effective.alignment) {
                onChange(value.copy(alignment = it))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    onChange(
                        value.copy(
                            fontScale = null,
                            fontSizeSp = null,
                            fontAssetId = null,
                            useOwnFont = false,
                            fontFaceIndex = null,
                            variationSettings = null,
                            fontWeight = null,
                            italic = null,
                            underline = null,
                            strikethrough = null,
                            colorArgb = null,
                            lineHeightEm = null,
                            letterSpacingEm = null,
                            alignment = null
                        )
                    )
                }) { Text("恢复分类默认") }
            }
        }
    }
}

@Composable
private fun SystemFontPickerDialog(
    fonts: List<SystemReaderFont>,
    loading: Boolean,
    onDismiss: () -> Unit,
    onSelect: (SystemReaderFont) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    val filteredFonts = remember(fonts, query) {
        val keyword = query.trim()
        if (keyword.isEmpty()) {
            fonts
        } else {
            fonts.filter {
                it.displayName.contains(keyword, ignoreCase = true) ||
                    it.styles.contains(keyword, ignoreCase = true) ||
                    it.fileName.contains(keyword, ignoreCase = true)
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择系统字体") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "字体会复制到独立字体库，系统更新或设备差异不会影响已保存资源。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索字体或样式") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                when {
                    loading -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                    filteredFonts.isEmpty() -> Text(
                        if (fonts.isEmpty()) "设备没有可导入的系统字体" else "没有匹配的字体",
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                    else -> LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 480.dp),
                        contentPadding = PaddingValues(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(
                            items = filteredFonts,
                            key = SystemReaderFont::filePath
                        ) { font ->
                            ListItem(
                                headlineContent = { SystemFontName(font) },
                                supportingContent = {
                                    Text(
                                        "${font.styles} · ${font.faceCount} 面 · " +
                                            "${formatBytes(font.byteCount)} · ${font.sourceLabel}\n" +
                                            font.fileName
                                    )
                                },
                                colors = ListItemDefaults.colors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable { onSelect(font) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun SystemFontName(font: SystemReaderFont) {
    val fontFamily by produceState<FontFamily?>(
        initialValue = null,
        key1 = font.filePath
    ) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                FontFamily(
                    Typeface.Builder(File(font.filePath))
                        .setTtcIndex(0)
                        .build()
                )
            }.getOrNull()
        }
    }
    Text(
        text = font.displayName,
        fontFamily = fontFamily
    )
}

@Composable
private fun FontImportSourceDialog(
    onDismiss: () -> Unit,
    onSystemFont: () -> Unit,
    onCustomFile: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入字体") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ListItem(
                    headlineContent = { Text("系统字体") },
                    supportingContent = { Text("从当前设备已安装的字体中选择") },
                    leadingContent = {
                        Icon(Icons.Default.FontDownload, contentDescription = null)
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(onClick = onSystemFont)
                )
                ListItem(
                    headlineContent = { Text("自定义文件") },
                    supportingContent = { Text("选择 TTF、OTF 或 TTC 字体文件") },
                    leadingContent = {
                        Icon(Icons.Default.Add, contentDescription = null)
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(onClick = onCustomFile)
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun FontLibrary(
    fonts: List<ReaderFontAssetEntity>,
    fontFile: (String?) -> File?,
    modifier: Modifier,
    onImport: () -> Unit,
    onDetails: (ReaderFontAssetEntity) -> Unit,
    onRename: (ReaderFontAssetEntity) -> Unit,
    onDelete: (ReaderFontAssetEntity) -> Unit
) {
    SettingsColumn(modifier) {
        Text(
            "字体文件独立存储；同步时传输整个字体库，而不只传预设引用。",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Add, contentDescription = null)
            Text(" 导入字体")
        }
        if (fonts.isEmpty()) {
            Text("还没有字体", modifier = Modifier.padding(vertical = 24.dp))
        }
        fonts.forEach { font ->
            FontAssetCard(
                font = font,
                file = fontFile(font.id),
                onDetails = { onDetails(font) },
                onRename = { onRename(font) },
                onDelete = { onDelete(font) }
            )
        }
    }
}

@Composable
private fun FontAssetCard(
    font: ReaderFontAssetEntity,
    file: File?,
    onDetails: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onDetails)
    ) {
        ListItem(
            headlineContent = {
                FontAssetName(
                    name = font.displayName,
                    file = file
                )
            },
            supportingContent = {
                Text(
                    "${font.familyName} · ${font.faceCount} 面 · " +
                        formatBytes(font.byteCount)
                )
            },
            trailingContent = {
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "字体操作")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("详情") },
                            leadingIcon = {
                                Icon(Icons.Default.Info, contentDescription = null)
                            },
                            onClick = {
                                menuExpanded = false
                                onDetails()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("重命名") },
                            leadingIcon = {
                                Icon(Icons.Default.Edit, contentDescription = null)
                            },
                            onClick = {
                                menuExpanded = false
                                onRename()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("删除") },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            }
                        )
                    }
                }
            }
        )
    }
}

@Composable
private fun FontAssetName(
    name: String,
    file: File?
) {
    val fontFamily by produceState<FontFamily?>(
        initialValue = null,
        key1 = file?.absolutePath
    ) {
        value = file?.let {
            withContext(Dispatchers.IO) {
                runCatching {
                    FontFamily(Typeface.Builder(it).setTtcIndex(0).build())
                }.getOrNull()
            }
        }
    }
    Text(text = name, fontFamily = fontFamily)
}

@Composable
private fun AssetLibrary(
    title: String,
    entries: List<String>,
    importLabel: String,
    secondaryImportLabel: String? = null,
    modifier: Modifier,
    onImport: () -> Unit,
    onSecondaryImport: (() -> Unit)? = null
) {
    SettingsColumn(modifier) {
        Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Add, contentDescription = null)
            Text(" $importLabel")
        }
        if (secondaryImportLabel != null && onSecondaryImport != null) {
            OutlinedButton(
                onClick = onSecondaryImport,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.FontDownload, contentDescription = null)
                Text(" $secondaryImportLabel")
            }
        }
        if (entries.isEmpty()) {
            Text("还没有资源", modifier = Modifier.padding(vertical = 24.dp))
        }
        entries.forEach {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Text(it, Modifier.padding(16.dp))
            }
        }
    }
}

@Composable
private fun AppFeatureSettings(modifier: Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val container = (context.applicationContext as PhoneCompanionApplication).container
    val aiStore = container.aiSettingsStore
    val initialAi = remember { aiStore.config() }
    val scope = rememberCoroutineScope()
    val prefs = remember {
        context.getSharedPreferences("phone_feature_settings", Context.MODE_PRIVATE)
    }
    var mediaPrefetch by remember { mutableStateOf(prefs.getBoolean("media_prefetch", true)) }
    var systemShare by remember { mutableStateOf(prefs.getBoolean("system_share", true)) }
    var aiEnabled by remember { mutableStateOf(initialAi.enabled) }
    var autoSummary by remember { mutableStateOf(initialAi.autoSummarize) }
    var tokenUsage by remember { mutableStateOf(initialAi.showTokenUsage) }
    var cookie by remember { mutableStateOf(aiStore.douyinCookie()) }
    val appAccessState by container.appAccessCoordinator.state.collectAsState()
    val hasPaidAiAccess = appAccessState is com.lightningstudio.watchrss.phone.account.AppAccessState.Authorized
    val remoteEnvironmentStore = remember { RemoteEnvironmentStore(context) }
    var remoteEnvironment by remember { mutableStateOf(remoteEnvironmentStore.active()) }
    val testEnvironmentConfigured = remember {
        AccountEnvironment.forRemoteEnvironment(RemoteEnvironment.TEST).isAuthConfigured
    }
    fun saveAiConfig() {
        aiStore.saveConfig(
            com.lightningstudio.watchrss.phone.data.ai.PhoneAiConfig(
                enabled = aiEnabled,
                autoSummarize = autoSummary,
                showTokenUsage = tokenUsage
            )
        )
    }
    SettingsColumn(modifier) {
        SectionTitle("推送")
        val pushStore = container.pushRegistrationStore
        var pushEnabled by remember { mutableStateOf(pushStore.isEnabled) }
        ToggleRow("接收推送通知", pushEnabled) {
            pushEnabled = it
            container.oppoPushCoordinator.setEnabled(it)
        }
        if (BuildConfig.DEBUG) {
            Text(
                "RegId: ${pushStore.regId ?: "未注册"}",
                style = MaterialTheme.typography.bodySmall
            )
            val pushCode = pushStore.lastRegisterCode
            Text(
                "注册状态码: $pushCode${if (pushCode == 0) "（已注册）" else ""}",
                style = MaterialTheme.typography.bodySmall
            )
        }
        SectionTitle("缓存与分享")
        ToggleRow("媒体预取", mediaPrefetch) {
            mediaPrefetch = it
            prefs.edit().putBoolean("media_prefetch", it).apply()
        }
        ToggleRow("使用系统分享面板", systemShare) {
            systemShare = it
            prefs.edit().putBoolean("system_share", it).apply()
        }
        SectionTitle("抖音")
        OutlinedTextField(
            value = cookie,
            onValueChange = { cookie = it },
            label = { Text("抖音 Cookie") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = {
                listOf("https://www.douyin.com", "https://douyin.com").forEach { origin ->
                    android.webkit.CookieManager.getInstance().setCookie(origin, cookie)
                }
                android.webkit.CookieManager.getInstance().flush()
                aiStore.saveDouyinCookie(cookie)
            },
            enabled = cookie.isNotBlank()
        ) { Text("写入平台 WebView") }
        if (BuildConfig.DEBUG) {
            SectionTitle("AI 总结")
            ToggleRow("启用 AI 总结", aiEnabled) {
                aiEnabled = it
                saveAiConfig()
            }
            ToggleRow("切换文章后自动总结", autoSummary) {
                autoSummary = it
                saveAiConfig()
            }
            ToggleRow("显示词元用量", tokenUsage) {
                tokenUsage = it
                saveAiConfig()
            }
            Text(
                "摘要由腕上RSS服务统一生成；官方 RSS 会复用已生成的摘要。",
                style = MaterialTheme.typography.bodySmall
            )
        }
        SectionTitle("其他")
        SettingsEntry("数据管理", "备份、恢复和导出数据", Icons.Default.Settings) {
            context.startActivity(DataManagementActivity.createIntent(context))
        }
        if (BuildConfig.DEBUG) {
            SettingsEntry("新手引导", "重新查看首次使用引导", Icons.Default.Edit) {
                context.startActivity(PhoneOobeActivity.createIntent(context, replayFromStart = true))
            }
            ToggleRow("调试：始终显示新手提示", container.tipManager.debugShowAll) {
                container.tipManager.debugShowAll = it
            }
            SettingsEntry("重置新手提示", "清除所有提示的显示与关闭记录", Icons.Default.Sync) {
                container.tipManager.resetTips()
                Toast.makeText(context, "新手提示已重置", Toast.LENGTH_SHORT).show()
            }
        }
        SettingsEntry("备案信息", "在关于页面查看", Icons.Default.Settings) {
            context.startActivity(Intent(context, AboutActivity::class.java))
        }
        SettingsEntry("去商店评分", "跳转 OPPO 软件商店评论页", Icons.Default.Star) {
            (context as? android.app.Activity)?.let { activity ->
                container.oppoReviewCoordinator.launchComment(activity)
            }
        }
        if (BuildConfig.DEBUG) {
            SectionTitle("远端环境")
            ToggleRow(
                label = "使用测试环境",
                checked = remoteEnvironment == RemoteEnvironment.TEST,
                enabled = testEnvironmentConfigured || remoteEnvironment == RemoteEnvironment.TEST
            ) { useTestEnvironment ->
                val selected = if (useTestEnvironment) {
                    RemoteEnvironment.TEST
                } else {
                    RemoteEnvironment.PRODUCTION
                }
                if (remoteEnvironmentStore.select(selected)) {
                    remoteEnvironment = selected
                    (context.applicationContext as PhoneCompanionApplication)
                        .restartAfterRemoteEnvironmentChange()
                }
            }
            Text(
                if (testEnvironmentConfigured) {
                    "当前：${remoteEnvironment.displayName}。切换后应用自动重启；账号、Passkey 与云端状态互相隔离。"
                } else {
                    "测试环境尚未配置；当前固定使用生产环境。"
                },
                style = MaterialTheme.typography.bodySmall
            )
            Text("Debug 性能工具", fontWeight = FontWeight.SemiBold)
            Text(
                "调试构建显示；正式构建不会暴露性能入口。",
                style = MaterialTheme.typography.bodySmall
            )
            SettingsEntry(
                "BLE 视频串流",
                "向 RTOS 手表串流 4:3 视频，可选清晰或流畅优先",
                Icons.Default.Sync
            ) {
                context.startActivity(Intent(context, BleBandwidthTestActivity::class.java))
            }
        }
    }
}

@Composable
private fun SettingsColumn(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    AdaptiveWindowScope(modifier = modifier.fillMaxSize()) { windowInfo ->
        AdaptiveContentFrame(
            windowInfo = windowInfo,
            mediumMaxWidth = 760.dp,
            expandedMaxWidth = 760.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        horizontal = if (windowInfo.isMediumOrExpanded) 32.dp else 20.dp,
                        vertical = 20.dp
                    ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content
            )
        }
    }
}

@Composable
private fun SettingsEntry(
    title: String,
    supporting: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(supporting) },
            leadingContent = {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            trailingContent = {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null
                )
            }
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChecked: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChecked,
            enabled = enabled
        )
    }
}

@Composable
private fun NumericSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    unit: String,
    supported: Boolean = true,
    onValue: (Float) -> Unit
) {
    if (!supported) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(label, Modifier.weight(1f))
            OutlinedTextField(
                value = "不支持",
                onValueChange = {},
                enabled = false,
                singleLine = true,
                modifier = Modifier.width(132.dp)
            )
        }
        return
    }
    var rawValue by remember(label) { mutableStateOf(formatNumericValue(value)) }
    var isEditing by remember(label) { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    fun commitInput() {
        val parsed = rawValue.replace(',', '.').toFloatOrNull()
        if (parsed == null) {
            rawValue = formatNumericValue(value)
        } else {
            val constrained = parsed.coerceIn(range)
            onValue(constrained)
            rawValue = formatNumericValue(constrained)
        }
    }
    LaunchedEffect(value, isEditing) {
        if (!isEditing) rawValue = formatNumericValue(value)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(label, Modifier.weight(1f))
            OutlinedTextField(
                value = rawValue,
                onValueChange = { input ->
                    if (input.matches(Regex("-?[0-9]*([.,][0-9]*)?"))) {
                        rawValue = input
                        input.replace(',', '.').toFloatOrNull()
                            ?.takeIf { it in range }
                            ?.let(onValue)
                    }
                },
                modifier = Modifier
                    .width(132.dp)
                    .onFocusChanged { state ->
                        if (isEditing && !state.isFocused) commitInput()
                        isEditing = state.isFocused
                    },
                suffix = if (unit.isBlank()) null else {
                    { Text(unit) }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        commitInput()
                        focusManager.clearFocus()
                    }
                )
            )
        }
        Slider(value = value.coerceIn(range), onValueChange = onValue, valueRange = range)
    }
}

private fun formatNumericValue(value: Float): String =
    if (value.isFinite() && kotlin.math.abs(value - value.roundToInt()) < 0.0001f) {
        value.roundToInt().toString()
    } else {
        "%.3f".format(java.util.Locale.ROOT, value)
            .trimEnd('0')
            .trimEnd('.')
    }

private fun ReaderPreset.editableFingerprint(): String =
    ReaderPresetCodec.encode(copy(updatedAt = 0L, modifiedBy = "", deleted = false))

private fun ReaderPreset.safeExportName(): String =
    name.trim()
        .replace(Regex("""[\\/:*?"<>|]"""), "_")
        .ifBlank { "reader-preset" }

@Composable
private fun <T> EnumChoice(
    label: String,
    values: List<T>,
    selected: T,
    onSelected: (T) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            values.forEach { value ->
                FilterChip(
                    selected = value == selected,
                    onClick = { onSelected(value) },
                    label = { Text(enumLabel(value)) }
                )
            }
        }
    }
}

private fun ReaderFontAssetEntity.fontFaceOptions(): List<Pair<String?, String>> =
    runCatching {
        val faces = JSONObject(metadataJson).optJSONArray("faces")
        buildList {
            repeat(faces?.length() ?: 0) { position ->
                val face = faces?.optJSONObject(position) ?: return@repeat
                val index = face.optInt("index", position)
                val name = face.optString("fullName").ifBlank {
                    listOf(
                        face.optString("family"),
                        face.optString("subfamily")
                    ).filter(String::isNotBlank).joinToString(" ")
                }.ifBlank {
                    "字体面 ${index + 1}"
                }
                add(index.toString() to "$index · $name")
            }
        }
    }.getOrDefault(
        List(faceCount.coerceAtLeast(1)) { index ->
            index.toString() to "$index · 字体面 ${index + 1}"
        }
    )

private fun ReaderFontAssetEntity.variableAxisRange(
    faceIndex: Int,
    axisTag: String
): ClosedFloatingPointRange<Float>? = runCatching {
    val faces = JSONObject(metadataJson).optJSONArray("faces") ?: return@runCatching null
    val face = (0 until faces.length())
        .asSequence()
        .mapNotNull(faces::optJSONObject)
        .firstOrNull { it.optInt("index", 0) == faceIndex }
        ?: faces.optJSONObject(faceIndex)
        ?: return@runCatching null
    val axes = face.optJSONArray("axes") ?: return@runCatching null
    val axis = (0 until axes.length())
        .asSequence()
        .mapNotNull(axes::optJSONObject)
        .firstOrNull { it.optString("tag").equals(axisTag, ignoreCase = true) }
        ?: return@runCatching null
    val minimum = axis.optDouble("minimum").toFloat()
    val maximum = axis.optDouble("maximum").toFloat()
    if (!minimum.isFinite() || !maximum.isFinite() || minimum >= maximum) {
        null
    } else {
        minimum..maximum
    }
}.getOrNull()

@Composable
private fun ResourceDropdownRow(
    label: String,
    values: List<Pair<String?, String>>,
    selected: String?,
    importLabel: String,
    onImport: () -> Unit,
    onSelected: (String?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = values.firstOrNull { it.first == selected }?.second
        ?: values.firstOrNull()?.second.orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.weight(1f)) {
                OutlinedButton(
                    onClick = { expanded = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        selectedName,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "展开")
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    values.forEach { (id, name) ->
                        DropdownMenuItem(
                            text = { Text(name) },
                            onClick = {
                                expanded = false
                                onSelected(id)
                            }
                        )
                    }
                }
            }
            OutlinedButton(onClick = onImport) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text(" $importLabel")
            }
        }
    }
}

@Composable
private fun ChoiceRow(
    label: String,
    values: List<Pair<String?, String>>,
    selected: String?,
    onSelected: (String?) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label)
        values.forEach { (id, name) ->
            FilterChip(
                selected = id == selected,
                onClick = { onSelected(id) },
                label = { Text(name) }
            )
        }
    }
}

@Composable
private fun ColorField(label: String, color: Long, onColor: (Long) -> Unit) {
    var raw by remember(color) { mutableStateOf("#%08X".format(color)) }
    var pickerOpen by remember { mutableStateOf(false) }
    var colorBeforePicker by remember { mutableLongStateOf(color) }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val pickerWidth = (maxWidth * 0.92f).coerceAtMost(420.dp)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .padding(end = 10.dp)
                    .height(40.dp)
                    .weight(0.2f)
                    .background(Color(color), RoundedCornerShape(8.dp))
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline,
                        shape = RoundedCornerShape(8.dp)
                    )
                    .clip(RoundedCornerShape(8.dp))
                    .semantics { contentDescription = "打开${label}调色盘" }
                    .clickable {
                        if (pickerOpen) {
                            pickerOpen = false
                        } else {
                            colorBeforePicker = color
                            pickerOpen = true
                        }
                    }
            )
            OutlinedTextField(
                value = raw,
                onValueChange = { value ->
                    raw = value
                    parseArgb(value)?.let(onColor)
                },
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.weight(0.8f)
            )
        }
        if (pickerOpen) {
            Popup(
                alignment = Alignment.Center,
                onDismissRequest = { pickerOpen = false },
                properties = PopupProperties(
                    focusable = true,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true,
                    clippingEnabled = true
                )
            ) {
                ColorPickerPanel(
                    label = label,
                    initialColor = color,
                    modifier = Modifier.width(pickerWidth),
                    onColorChanged = onColor,
                    onCancel = {
                        pickerOpen = false
                        onColor(colorBeforePicker)
                    },
                    onDone = { pickerOpen = false }
                )
            }
        }
    }
}

@Composable
private fun ColorPickerPanel(
    label: String,
    initialColor: Long,
    modifier: Modifier = Modifier,
    onColorChanged: (Long) -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit
) {
    val initialArgb = initialColor.toInt()
    val initialHsv = remember {
        FloatArray(3).also { android.graphics.Color.colorToHSV(initialArgb, it) }
    }
    var hue by remember { mutableFloatStateOf(initialHsv[0]) }
    var saturation by remember { mutableFloatStateOf(initialHsv[1]) }
    var brightness by remember { mutableFloatStateOf(initialHsv[2]) }
    var alpha by remember {
        mutableFloatStateOf(android.graphics.Color.alpha(initialArgb) / 255f)
    }
    val selectedArgb = remember(hue, saturation, brightness, alpha) {
        android.graphics.Color.HSVToColor(
            (alpha * 255f).roundToInt().coerceIn(0, 255),
            floatArrayOf(hue, saturation, brightness)
        ).toLong() and 0xFFFFFFFFL
    }

    ElevatedCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "$label · 调色盘",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            SaturationBrightnessPalette(
                hue = hue,
                saturation = saturation,
                brightness = brightness,
                onChanged = { nextSaturation, nextBrightness ->
                    saturation = nextSaturation
                    brightness = nextBrightness
                    onColorChanged(
                        hsvToArgb(hue, nextSaturation, nextBrightness, alpha)
                    )
                }
            )
            RainbowHueSlider(
                value = hue,
                onValueChange = {
                    hue = it
                    onColorChanged(hsvToArgb(it, saturation, brightness, alpha))
                }
            )
            ColorGradientSlider(
                label = "饱和度",
                value = saturation,
                valueText = "${(saturation * 100).roundToInt()}%",
                colors = listOf(
                    Color.hsv(hue, 0f, brightness),
                    Color.hsv(hue, 1f, brightness)
                ),
                thumbColor = Color.hsv(hue, saturation, brightness),
                onValueChange = {
                    saturation = it
                    onColorChanged(hsvToArgb(hue, it, brightness, alpha))
                }
            )
            ColorGradientSlider(
                label = "明度",
                value = brightness,
                valueText = "${(brightness * 100).roundToInt()}%",
                colors = listOf(
                    Color.Black,
                    Color.hsv(hue, saturation, 1f)
                ),
                thumbColor = Color.hsv(hue, saturation, brightness),
                onValueChange = {
                    brightness = it
                    onColorChanged(hsvToArgb(hue, saturation, it, alpha))
                }
            )
            ColorComponentSlider(
                label = "透明度",
                value = alpha,
                range = 0f..1f,
                valueText = "${(alpha * 100).roundToInt()}%",
                onValueChange = {
                    alpha = it
                    onColorChanged(hsvToArgb(hue, saturation, brightness, it))
                }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    Modifier
                        .width(52.dp)
                        .height(36.dp)
                        .background(Color(selectedArgb), RoundedCornerShape(8.dp))
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outline,
                            RoundedCornerShape(8.dp)
                        )
                )
                Text(
                    "#%08X".format(selectedArgb),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onCancel) { Text("取消") }
                TextButton(onClick = onDone) { Text("完成") }
            }
        }
    }
}

private fun hsvToArgb(
    hue: Float,
    saturation: Float,
    brightness: Float,
    alpha: Float
): Long = android.graphics.Color.HSVToColor(
    (alpha * 255f).roundToInt().coerceIn(0, 255),
    floatArrayOf(hue, saturation, brightness)
).toLong() and 0xFFFFFFFFL

@Composable
private fun RainbowHueSlider(
    value: Float,
    onValueChange: (Float) -> Unit
) {
    var sliderSize by remember { mutableStateOf(IntSize.Zero) }
    fun updateFromPosition(position: Offset) {
        if (sliderSize.width == 0) return
        onValueChange((position.x / sliderSize.width * 360f).coerceIn(0f, 360f))
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("色相", style = MaterialTheme.typography.bodySmall)
            Text("${value.roundToInt()}°", style = MaterialTheme.typography.bodySmall)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(32.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color.Red,
                            Color.Yellow,
                            Color.Green,
                            Color.Cyan,
                            Color.Blue,
                            Color.Magenta,
                            Color.Red
                        )
                    )
                )
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                .onSizeChanged { sliderSize = it }
                .semantics { contentDescription = "色相彩虹拉杆" }
                .pointerInput(sliderSize) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        updateFromPosition(down.position)
                        down.consume()
                        do {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            updateFromPosition(change.position)
                            change.consume()
                        } while (change.pressed)
                    }
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val center = Offset(value / 360f * size.width, size.height / 2f)
                drawCircle(
                    color = Color.Black.copy(alpha = 0.5f),
                    radius = 11.dp.toPx(),
                    center = center
                )
                drawCircle(color = Color.White, radius = 9.dp.toPx(), center = center)
                drawCircle(
                    color = Color.hsv(value, 1f, 1f),
                    radius = 6.dp.toPx(),
                    center = center
                )
            }
        }
    }
}

@Composable
private fun ColorGradientSlider(
    label: String,
    value: Float,
    valueText: String,
    colors: List<Color>,
    thumbColor: Color,
    onValueChange: (Float) -> Unit
) {
    var sliderSize by remember { mutableStateOf(IntSize.Zero) }
    fun updateFromPosition(position: Offset) {
        if (sliderSize.width == 0) return
        onValueChange((position.x / sliderSize.width).coerceIn(0f, 1f))
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text(valueText, style = MaterialTheme.typography.bodySmall)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(32.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.horizontalGradient(colors))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                .onSizeChanged { sliderSize = it }
                .semantics { contentDescription = "$label 色彩拉杆" }
                .pointerInput(sliderSize) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        updateFromPosition(down.position)
                        down.consume()
                        do {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            updateFromPosition(change.position)
                            change.consume()
                        } while (change.pressed)
                    }
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val center = Offset(value * size.width, size.height / 2f)
                drawCircle(
                    color = Color.Black.copy(alpha = 0.5f),
                    radius = 11.dp.toPx(),
                    center = center
                )
                drawCircle(color = Color.White, radius = 9.dp.toPx(), center = center)
                drawCircle(color = thumbColor, radius = 6.dp.toPx(), center = center)
            }
        }
    }
}

@Composable
private fun SaturationBrightnessPalette(
    hue: Float,
    saturation: Float,
    brightness: Float,
    onChanged: (saturation: Float, brightness: Float) -> Unit
) {
    var paletteSize by remember { mutableStateOf(IntSize.Zero) }
    fun updateFromPosition(position: Offset) {
        if (paletteSize.width == 0 || paletteSize.height == 0) return
        onChanged(
            (position.x / paletteSize.width).coerceIn(0f, 1f),
            (1f - position.y / paletteSize.height).coerceIn(0f, 1f)
        )
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.hsv(hue, 1f, 1f))
            .background(
                Brush.horizontalGradient(listOf(Color.White, Color.Transparent))
            )
            .background(
                Brush.verticalGradient(listOf(Color.Transparent, Color.Black))
            )
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
            .onSizeChanged { paletteSize = it }
            .pointerInput(paletteSize) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    updateFromPosition(down.position)
                    down.consume()
                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        updateFromPosition(change.position)
                        change.consume()
                    } while (change.pressed)
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(saturation * size.width, (1f - brightness) * size.height)
            drawCircle(Color.Black.copy(alpha = 0.55f), radius = 8.dp.toPx(), center = center)
            drawCircle(Color.White, radius = 6.dp.toPx(), center = center)
            drawCircle(
                Color.hsv(hue, saturation, brightness),
                radius = 4.dp.toPx(),
                center = center
            )
        }
    }
}

@Composable
private fun ColorComponentSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    onValueChange: (Float) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text(valueText, style = MaterialTheme.typography.bodySmall)
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = range)
    }
}

@Composable
private fun TextInputDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }, enabled = value.trim().isNotEmpty()) {
                Text("确定")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private fun persistReadPermission(context: Context, uri: android.net.Uri) {
    runCatching {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
    }
}

private fun parseArgb(value: String): Long? {
    val hex = value.trim().removePrefix("#")
    val normalized = if (hex.length == 6) "FF$hex" else hex
    if (normalized.length != 8) return null
    return normalized.toLongOrNull(16)
}

private fun enumLabel(value: Any?): String = when (value) {
    ReaderTextAlignment.START -> "左对齐"
    ReaderTextAlignment.CENTER -> "居中"
    ReaderTextAlignment.JUSTIFY -> "两端对齐"
    ReaderLineBreakMode.SYSTEM -> "系统"
    ReaderLineBreakMode.SIMPLE -> "简单"
    ReaderLineBreakMode.PARAGRAPH -> "段落"
    ReaderHyphenation.NONE -> "关闭"
    ReaderHyphenation.AUTO -> "自动"
    ReaderRenderMode.SYSTEM -> "系统默认"
    ReaderRenderMode.READABILITY -> "静态清晰"
    ReaderRenderMode.LINEAR_SMOOTH -> "线性平滑"
    ReaderFontSynthesis.ENABLED -> "允许合成"
    ReaderFontSynthesis.DISABLED -> "禁用合成"
    ReaderBackgroundType.SOLID -> "纯色"
    ReaderBackgroundType.IMAGE -> "图片"
    ReaderBackgroundType.VIDEO -> "视频"
    ReaderBackgroundFit.CROP -> "裁剪"
    ReaderBackgroundFit.FIT -> "适应"
    ReaderBackgroundFit.FILL -> "拉伸"
    else -> value.toString()
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MiB".format(bytes / (1024f * 1024f))
    bytes >= 1024L -> "%.1f KiB".format(bytes / 1024f)
    else -> "$bytes B"
}
