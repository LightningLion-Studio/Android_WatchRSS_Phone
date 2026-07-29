package com.lightningstudio.watchrss.phone

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lightningstudio.watchrss.phone.data.reader.ReaderBackgroundFit
import com.lightningstudio.watchrss.phone.data.reader.ReaderBackgroundAssetEntity
import com.lightningstudio.watchrss.phone.data.reader.ReaderBackgroundType
import com.lightningstudio.watchrss.phone.data.reader.ReaderFontSynthesis
import com.lightningstudio.watchrss.phone.data.reader.ReaderFontAssetEntity
import com.lightningstudio.watchrss.phone.data.reader.ReaderHyphenation
import com.lightningstudio.watchrss.phone.data.reader.ReaderLineBreakMode
import com.lightningstudio.watchrss.phone.data.reader.ReaderPreset
import com.lightningstudio.watchrss.phone.data.reader.ReaderPresetRepository
import com.lightningstudio.watchrss.phone.data.reader.ReaderRenderMode
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
import com.lightningstudio.watchrss.phone.ui.theme.WatchRssPhoneTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.util.UUID
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
    val presets by repository.presets.collectAsStateWithLifecycle()
    val active by repository.activePreset.collectAsStateWithLifecycle()
    val fonts by repository.fonts.collectAsStateWithLifecycle()
    val backgrounds by repository.backgrounds.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var page by rememberSaveable { mutableStateOf(SettingsPage.ROOT) }
    var draft by remember { mutableStateOf<ReaderPreset?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var renamePreset by remember { mutableStateOf<ReaderPreset?>(null) }
    var deletePreset by remember { mutableStateOf<ReaderPreset?>(null) }
    var renameFont by remember { mutableStateOf<ReaderFontAssetEntity?>(null) }
    var deleteFont by remember { mutableStateOf<ReaderFontAssetEntity?>(null) }
    var fontDetails by remember { mutableStateOf<ReaderFontAssetEntity?>(null) }
    var syncAfterPermission by remember { mutableStateOf(false) }
    var paneTransition by remember { mutableStateOf<SettingsPaneTransition?>(null) }
    var paneTransitionProgress by remember { mutableFloatStateOf(1f) }
    var showSystemFontPicker by remember { mutableStateOf(false) }
    var systemFontsLoading by remember { mutableStateOf(false) }
    var systemFonts by remember { mutableStateOf<List<SystemReaderFont>>(emptyList()) }

    fun syncNow() {
        scope.launch {
            message = "正在探测手表…"
            message = runCatching {
                val devices = container.bluetoothSyncManager.probeLibrarySyncTargets()
                require(devices.isNotEmpty()) { "未找到可同步的手表" }
                require(devices.size == 1) { "发现多块手表，请在资料库同步页选择目标" }
                container.bluetoothSyncManager.syncReaderPresets(devices.single().address)
                "预设、整个字体库和引用背景已同步"
            }.getOrElse { it.message ?: "同步失败" }
        }
    }
    val bluetoothPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (syncAfterPermission && result.values.all { it }) syncNow()
            syncAfterPermission = false
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

    fun backFrom(source: SettingsPage) {
        if (source == SettingsPage.ROOT) {
            onFinish()
        } else {
            navigateTo(source.parent())
        }
    }

    val back = { backFrom(page) }
    PredictiveBackHandler(enabled = page != SettingsPage.ROOT) { events ->
        events.collect { }
        back()
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
                    modifier = Modifier.padding(padding),
                    onNew = {
                        draft = ReaderPreset.darkDefault(
                            id = UUID.randomUUID().toString(),
                            name = "新预设"
                        )
                        navigateTo(SettingsPage.EDITOR)
                    },
                    onApply = repository::setActivePreset,
                    onEdit = {
                        draft = it
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
                    onDelete = { deletePreset = it }
                    ,
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
                            onDraftChange = { draft = it },
                            onOpenCategoryTypography = {
                                navigateTo(SettingsPage.CATEGORY_TYPOGRAPHY)
                            },
                            onImportFont = {
                                fontPicker.launch(
                                    arrayOf(
                                        "font/ttf",
                                        "font/otf",
                                        "application/x-font-ttf",
                                        "application/x-font-opentype"
                                    )
                                )
                            },
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
                            onSave = { apply, saveAs ->
                                scope.launch {
                                    val result = runCatching {
                                        if (saveAs) {
                                            repository.saveAsNew(current, current.name).also {
                                                draft = it
                                            }
                                        } else {
                                            repository.savePreset(current, applyAfterSave = apply)
                                        }
                                    }
                                    message = result.fold(
                                        onSuccess = {
                                            if (apply) repository.setActivePreset(it.id)
                                            draft = it
                                            if (saveAs) "已另存为“${it.name}”" else "已保存"
                                        },
                                        onFailure = { it.message ?: "保存失败" }
                                    )
                                }
                            },
                            onApply = {
                                repository.setActivePreset(current.id)
                                message = "已应用到本机阅读器"
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
                            onDraftChange = { draft = it },
                            onImportFont = {
                                fontPicker.launch(
                                    arrayOf(
                                        "font/ttf",
                                        "font/otf",
                                        "application/x-font-ttf",
                                        "application/x-font-opentype"
                                    )
                                )
                            }
                        )
                    }
                }
                SettingsPage.FONTS -> FontLibrary(
                    fonts = fonts,
                    fontFile = repository::fontFile,
                    modifier = Modifier.padding(padding),
                    onImportFile = {
                        fontPicker.launch(
                            arrayOf(
                                "font/ttf",
                                "font/otf",
                                "application/x-font-ttf",
                                "application/x-font-opentype"
                            )
                        )
                    },
                    onImportSystem = ::openSystemFontPicker,
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

    AdaptiveWindowScope(modifier = Modifier.fillMaxSize()) { windowInfo ->
        if (leadingPane != null && windowInfo.isMediumOrExpanded) {
            SettingsPaneStack(
                windowInfo = windowInfo,
                page = page,
                transition = paneTransition,
                transitionProgress = paneTransitionProgress,
                leadingPane = leadingPane,
                renderPage = renderPage
            )
        } else {
            renderPage(page)
        }
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
                        "缓存、分享、抖音 Cookie、AI 总结、引导与性能工具",
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
    modifier: Modifier,
    onNew: () -> Unit,
    onApply: (String) -> Unit,
    onEdit: (ReaderPreset) -> Unit,
    onDuplicate: (ReaderPreset) -> Unit,
    onRename: (ReaderPreset) -> Unit,
    onDelete: (ReaderPreset) -> Unit,
    onSync: () -> Unit
) {
    SettingsColumn(modifier) {
        Button(onClick = onNew, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Add, contentDescription = null)
            Text(" 新建预设")
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
        presets.forEach { preset ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(preset.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (preset.id == activeId) "正在本机使用" else "未应用",
                                color = if (preset.id == activeId) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                        Button(onClick = { onApply(preset.id) }) { Text("应用") }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton(onClick = { onEdit(preset) }) { Text("编辑") }
                        TextButton(onClick = { onDuplicate(preset) }) { Text("复制") }
                        TextButton(onClick = { onRename(preset) }) { Text("重命名") }
                        IconButton(onClick = { onDelete(preset) }) {
                            Icon(Icons.Default.Delete, contentDescription = "删除")
                        }
                    }
                }
            }
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
    onDraftChange: (ReaderPreset) -> Unit,
    onOpenCategoryTypography: () -> Unit,
    onImportFont: () -> Unit,
    onImportBackground: (ReaderBackgroundType) -> Unit,
    onSave: (apply: Boolean, saveAs: Boolean) -> Unit,
    onApply: () -> Unit
) {
    val body = draft.body
    val background = draft.background
    val selectedFont = fonts.firstOrNull { it.id == body.fontAssetId }
    val fontFaces = remember(selectedFont?.metadataJson) {
        selectedFont?.fontFaceOptions().orEmpty()
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
        NumericSlider("字重", body.fontWeight.toFloat(), 100f..900f, "") {
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
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = { onSave(false, false) }, Modifier.weight(1f)) {
                Text("保存")
            }
            OutlinedButton(onClick = { onSave(false, true) }, Modifier.weight(1f)) {
                Text("另存为")
            }
        }
        Button(onClick = onApply, modifier = Modifier.fillMaxWidth()) {
            Text("单独应用当前已保存版本")
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
            "各分类先按正文生成默认比例，再叠加这里的设置。返回预设编辑页后统一保存。",
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
            "此页只修改草稿，不会自动覆盖已保存预设。",
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
                            "调整设置时，样张会立即更新；草稿仍不会自动覆盖已经保存的预设。",
                        style = readerTextStyle(ReaderTextRole.BODY)
                    )
                    Text(
                        "“真正舒服的排版，往往是在你忘记排版本身的时候。”",
                        style = readerTextStyle(ReaderTextRole.QUOTE)
                    )
                    Text(
                        "reader.apply(preset)\nrender(title, body, quote, code, link)",
                        style = readerTextStyle(ReaderTextRole.CODE)
                    )
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
        "预览随草稿更新；只有保存后才写入预设。",
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
            NumericSlider(
                "相对字号",
                value.fontScale ?: defaults.fontScale ?: 1f,
                0.5f..2.5f,
                "×"
            ) {
                onChange(value.copy(fontScale = it, fontSizeSp = null))
            }
            NumericSlider("字重", effective.fontWeight.toFloat(), 100f..900f, "") {
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
                        contentPadding = PaddingValues(vertical = 4.dp)
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
                                modifier = Modifier
                                    .fillMaxWidth()
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
private fun FontLibrary(
    fonts: List<ReaderFontAssetEntity>,
    fontFile: (String?) -> File?,
    modifier: Modifier,
    onImportFile: () -> Unit,
    onImportSystem: () -> Unit,
    onDetails: (ReaderFontAssetEntity) -> Unit,
    onRename: (ReaderFontAssetEntity) -> Unit,
    onDelete: (ReaderFontAssetEntity) -> Unit
) {
    SettingsColumn(modifier) {
        Text(
            "字体文件独立存储；同步时传输整个字体库，而不只传预设引用。",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(onClick = onImportFile, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Add, contentDescription = null)
            Text(" 导入 TTF / OTF / TTC")
        }
        OutlinedButton(onClick = onImportSystem, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.FontDownload, contentDescription = null)
            Text(" 从系统字体选择")
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
    var provider by remember { mutableStateOf(initialAi.provider) }
    var model by remember { mutableStateOf(initialAi.model) }
    var baseUrl by remember { mutableStateOf(initialAi.baseUrl) }
    var apiKey by remember { mutableStateOf(aiStore.apiKey()) }
    var cookie by remember { mutableStateOf(aiStore.douyinCookie()) }
    var prompt by remember { mutableStateOf(initialAi.prompt) }
    var connectionResult by remember { mutableStateOf<String?>(null) }
    fun saveAiConfig() {
        aiStore.saveConfig(
            com.lightningstudio.watchrss.phone.data.ai.PhoneAiConfig(
                enabled = aiEnabled,
                autoSummarize = autoSummary,
                showTokenUsage = tokenUsage,
                provider = provider,
                model = model,
                baseUrl = baseUrl,
                prompt = prompt
            )
        )
        aiStore.saveApiKey(apiKey)
    }
    SettingsColumn(modifier) {
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
        SectionTitle("AI 总结")
        ToggleRow("启用 AI 总结", aiEnabled) {
            aiEnabled = it
            saveAiConfig()
        }
        ToggleRow("切换文章后自动总结", autoSummary) {
            autoSummary = it
            saveAiConfig()
        }
        ToggleRow("显示 Token 用量", tokenUsage) {
            tokenUsage = it
            saveAiConfig()
        }
        OutlinedTextField(
            value = provider,
            onValueChange = { provider = it },
            label = { Text("供应商") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("OpenAI 兼容 Base URL") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text("模型") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("API Key（本机加密）") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            label = { Text("提示词") },
            minLines = 4,
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = ::saveAiConfig) { Text("保存配置") }
            OutlinedButton(onClick = {
                saveAiConfig()
                connectionResult = "测试中…"
                scope.launch {
                    connectionResult = runCatching {
                        container.aiSummaryService.testConnection()
                    }.getOrElse { it.message ?: "连接失败" }
                }
            }) { Text("连通测试") }
        }
        connectionResult?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        Text(
            "API Key 与供应商配置使用本机加密存储，不进入预设、蓝牙、备份或云同步。",
            style = MaterialTheme.typography.bodySmall
        )
        SectionTitle("其他")
        SettingsEntry("新手引导", "重新查看手机操作引导", Icons.Default.Edit) {
            context.startActivity(Intent(context, GuideActivity::class.java))
        }
        SettingsEntry("备案信息", "在关于页面查看", Icons.Default.Settings) {
            context.startActivity(Intent(context, AboutActivity::class.java))
        }
        if (BuildConfig.DEBUG) {
            Text("Debug 性能工具", fontWeight = FontWeight.SemiBold)
            Text(
                "调试构建显示；正式构建不会暴露性能入口。",
                style = MaterialTheme.typography.bodySmall
            )
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
private fun ToggleRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun NumericSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    unit: String,
    onValue: (Float) -> Unit
) {
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
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .padding(end = 10.dp)
                .height(40.dp)
                .weight(0.2f)
                .background(Color(color), RoundedCornerShape(8.dp))
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
