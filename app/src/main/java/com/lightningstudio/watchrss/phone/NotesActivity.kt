package com.lightningstudio.watchrss.phone

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lightningstudio.watchrss.phone.data.note.NoteEntity
import com.lightningstudio.watchrss.phone.data.note.NoteConflictEntity
import com.lightningstudio.watchrss.phone.data.note.NoteRepository
import com.lightningstudio.watchrss.phone.data.note.NoteImportExportService
import com.lightningstudio.watchrss.phone.ui.AdaptiveReadingPane
import com.lightningstudio.watchrss.phone.ui.AdaptiveWindowInfo
import com.lightningstudio.watchrss.phone.ui.AdaptiveWindowScope
import com.lightningstudio.watchrss.phone.ui.PREDICTIVE_BACK_CANCEL_ANIMATION_MS
import com.lightningstudio.watchrss.phone.ui.reader.ProvideReaderPreset
import com.lightningstudio.watchrss.phone.ui.theme.WatchRssPhoneTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope

private const val NOTE_PANE_TRANSITION_MS = 480
private const val NOTE_SWITCH_FADE_MS = 220
private const val NOTE_SWITCH_FADE_OUT_MS = 90
private const val NEW_NOTE_CONTENT_KEY = "__new_note__"

class NotesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as PhoneCompanionApplication).container
        val repository = container.noteRepository
        setContent {
            WatchRssPhoneTheme {
                ProvideReaderPreset(container.readerPresetRepository) {
                    NotesScreen(
                        repository,
                        ::finish,
                        lifecycleScope,
                        container.cloudSyncService::syncNow
                    )
                }
            }
        }
    }
    companion object { fun createIntent(context: Context) = Intent(context, NotesActivity::class.java) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotesScreen(
    repository: NoteRepository,
    onBack: () -> Unit,
    scope: CoroutineScope,
    syncCloud: suspend () -> Unit
) {
    val notes by repository.observeNotes().collectAsStateWithLifecycle(emptyList())
    val conflicts by repository.observeConflicts().collectAsStateWithLifecycle(emptyList())
    var selected by remember { mutableStateOf<NoteEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var selectedConflict by remember { mutableStateOf<NoteConflictEntity?>(null) }
    var editorFullscreen by remember { mutableStateOf(false) }
    var detailProgress by remember { mutableFloatStateOf(1f) }
    var detailTransitioning by remember { mutableStateOf(false) }
    var detailAnimationJob by remember { mutableStateOf<Job?>(null) }
    var fullscreenBackProgress by remember { mutableFloatStateOf(0f) }
    val navigationScope = rememberCoroutineScope()
    val context = LocalContext.current
    val transfer = remember(context, repository) { NoteImportExportService(context, repository) }
    val zipExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            context.contentResolver.openOutputStream(uri)?.use { it.write(transfer.exportZip()) }
        }
    }
    val zipImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                val count = context.contentResolver.openInputStream(uri)
                    ?.use { transfer.importZip(it.readBytes()) }
                    ?: error("无法读取 ZIP 文件")
                require(count > 0) { "ZIP 中没有 Markdown（.md）笔记" }
                runCatching { syncCloud() }
                count
            }.onSuccess { count ->
                Toast.makeText(context, "已导入 $count 条备忘录", Toast.LENGTH_SHORT).show()
            }.onFailure { throwable ->
                Toast.makeText(context, "ZIP 导入失败：${throwable.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
            }
        }
    }
    val markdownImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                val fileName = context.queryDisplayName(uri)
                    ?: uri.lastPathSegment?.substringAfterLast('/')
                    ?: "未命名.md"
                val mimeType = context.contentResolver.getType(uri)
                val bytes = context.contentResolver.openInputStream(uri)
                    ?.use { it.readBytes() }
                    ?: error("无法读取 Markdown 文件")
                val note = transfer.importMarkdown(fileName, mimeType, bytes)
                runCatching { syncCloud() }
                note
            }.onSuccess { note ->
                Toast.makeText(context, "已导入备忘录：${note.title}", Toast.LENGTH_SHORT).show()
            }.onFailure { throwable ->
                Toast.makeText(context, "Markdown 导入失败：${throwable.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
            }
        }
    }
    val directoryImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val count = transfer.importDirectory(uri)
                require(count > 0) { "所选目录中没有 Markdown（.md）笔记" }
                runCatching { syncCloud() }
                count
            }.onSuccess { count ->
                Toast.makeText(context, "已导入 $count 条备忘录", Toast.LENGTH_SHORT).show()
            }.onFailure { throwable ->
                Toast.makeText(context, "目录导入失败：${throwable.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
            }
        }
    }
    AdaptiveWindowScope(Modifier.fillMaxSize()) { windowInfo ->
        val editorOpen = selected != null || creating
        val finishEditor = {
            selected = null
            creating = false
            editorFullscreen = false
            detailProgress = 1f
            detailTransitioning = false
            fullscreenBackProgress = 0f
        }
        val animateDetailClosed: suspend () -> Unit = {
            detailTransitioning = true
            val remainingDuration = (NOTE_PANE_TRANSITION_MS * detailProgress.coerceIn(0f, 1f))
                .toInt()
                .coerceAtLeast(1)
            animate(
                initialValue = detailProgress,
                targetValue = 0f,
                animationSpec = tween(
                    durationMillis = remainingDuration,
                    easing = FastOutSlowInEasing
                )
            ) { value, _ -> detailProgress = value }
            finishEditor()
        }
        val closeEditor: () -> Unit = {
            detailAnimationJob?.cancel()
            detailAnimationJob = navigationScope.launch {
                if (windowInfo.isMediumOrExpanded && editorFullscreen) {
                    editorFullscreen = false
                } else {
                    animateDetailClosed()
                }
                detailAnimationJob = null
            }
        }

        PredictiveBackHandler(enabled = editorOpen) { backEvents ->
            detailAnimationJob?.cancel()
            detailAnimationJob = null
            val collapseFullscreen = windowInfo.isMediumOrExpanded && editorFullscreen
            try {
                if (collapseFullscreen) {
                    fullscreenBackProgress = 0f
                    backEvents.collect { event ->
                        fullscreenBackProgress = event.progress.coerceIn(0f, 1f)
                    }
                    animate(
                        initialValue = fullscreenBackProgress,
                        targetValue = 1f,
                        animationSpec = tween(NOTE_PANE_TRANSITION_MS, easing = FastOutSlowInEasing)
                    ) { value, _ -> fullscreenBackProgress = value }
                    editorFullscreen = false
                    delay(NOTE_PANE_TRANSITION_MS.toLong() + 32L)
                    fullscreenBackProgress = 0f
                } else {
                    detailTransitioning = true
                    backEvents.collect { event ->
                        detailProgress = 1f - event.progress.coerceIn(0f, 1f)
                    }
                    animateDetailClosed()
                }
            } catch (exception: CancellationException) {
                if (collapseFullscreen) {
                    animate(
                        initialValue = fullscreenBackProgress,
                        targetValue = 0f,
                        animationSpec = tween(PREDICTIVE_BACK_CANCEL_ANIMATION_MS)
                    ) { value, _ -> fullscreenBackProgress = value }
                } else {
                    animate(
                        initialValue = detailProgress,
                        targetValue = 1f,
                        animationSpec = tween(PREDICTIVE_BACK_CANCEL_ANIMATION_MS)
                    ) { value, _ -> detailProgress = value }
                    detailTransitioning = false
                }
            }
        }

        val openNote: (NoteEntity) -> Unit = { note ->
            if (editorOpen) {
                selected = note
                creating = false
            } else {
                fullscreenBackProgress = 0f
                editorFullscreen = false
                selected = note
                creating = false
                detailProgress = 0f
                detailTransitioning = true
                detailAnimationJob?.cancel()
                detailAnimationJob = navigationScope.launch {
                    animate(
                        initialValue = 0f,
                        targetValue = 1f,
                        animationSpec = tween(NOTE_PANE_TRANSITION_MS, easing = FastOutSlowInEasing)
                    ) { value, _ -> detailProgress = value }
                    detailTransitioning = false
                    detailAnimationJob = null
                }
            }
        }
        val openNewNote: () -> Unit = {
            if (editorOpen) {
                selected = null
                creating = true
            } else {
                fullscreenBackProgress = 0f
                editorFullscreen = false
                selected = null
                creating = true
                detailProgress = 0f
                detailTransitioning = true
                detailAnimationJob?.cancel()
                detailAnimationJob = navigationScope.launch {
                    animate(
                        initialValue = 0f,
                        targetValue = 1f,
                        animationSpec = tween(NOTE_PANE_TRANSITION_MS, easing = FastOutSlowInEasing)
                    ) { value, _ -> detailProgress = value }
                    detailTransitioning = false
                    detailAnimationJob = null
                }
            }
        }
        val listPane: @Composable () -> Unit = {
            NoteListPage(
                notes = notes,
                conflicts = conflicts,
                onBack = onBack,
                onOpen = openNote,
                onNew = openNewNote,
                onOpenConflict = { selectedConflict = conflicts.firstOrNull() },
                onImportDirectory = { directoryImport.launch(null) },
                onImportMarkdown = {
                    markdownImport.launch(arrayOf("text/markdown", "text/x-markdown", "text/plain", "application/octet-stream", "*/*"))
                },
                onImportZip = { zipImport.launch(arrayOf("application/zip", "application/x-zip-compressed")) },
                onExportZip = { zipExport.launch("watchrss-notes.zip") }
            )
        }
        val editorPane: @Composable (Boolean) -> Unit = { fullscreenLayerActive ->
            val contentKey = selected?.noteId ?: NEW_NOTE_CONTENT_KEY
            AnimatedContent(
                targetState = contentKey,
                transitionSpec = {
                    fadeIn(
                        animationSpec = tween(
                            durationMillis = NOTE_SWITCH_FADE_MS - NOTE_SWITCH_FADE_OUT_MS,
                            delayMillis = NOTE_SWITCH_FADE_OUT_MS
                        )
                    ) togetherWith fadeOut(
                        animationSpec = tween(durationMillis = NOTE_SWITCH_FADE_OUT_MS)
                    )
                },
                label = "note-content-switch"
            ) { renderedKey ->
                val renderedNote = if (renderedKey == NEW_NOTE_CONTENT_KEY) {
                    null
                } else {
                    notes.firstOrNull { it.noteId == renderedKey }
                }
                NoteEditorPage(
                    note = renderedNote,
                    repository = repository,
                    scope = scope,
                    syncCloud = syncCloud,
                    showFullscreenControl = windowInfo.isMediumOrExpanded,
                    fullscreen = fullscreenLayerActive,
                    onToggleFullscreen = { editorFullscreen = !editorFullscreen },
                    onBack = closeEditor,
                    onSaved = {
                        detailAnimationJob?.cancel()
                        detailAnimationJob = null
                        finishEditor()
                    }
                )
            }
        }

        if (!editorOpen) {
            listPane()
        } else if (detailTransitioning || detailProgress < 0.999f) {
            NoteDetailPaneTransition(
                windowInfo = windowInfo,
                progress = detailProgress,
                listPane = listPane,
                editorPane = { editorPane(false) }
            )
        } else if (windowInfo.isMediumOrExpanded) {
            AdaptiveReadingPane(
                windowInfo = windowInfo,
                fullscreen = editorFullscreen,
                predictiveBackProgress = 0f,
                fullscreenBackProgress = fullscreenBackProgress,
                startPane = listPane,
                readerPane = editorPane
            )
        } else {
            editorPane(true)
        }
    }
    selectedConflict?.let { conflict ->
        NoteConflictDialog(
            conflict = conflict,
            onResolve = { markdown ->
                scope.launch {
                    repository.resolveConflict(conflict, markdown)
                    runCatching { syncCloud() }
                    selectedConflict = null
                }
            },
            onDismiss = { selectedConflict = null }
        )
    }
}

private fun Context.queryDisplayName(uri: android.net.Uri): String? = runCatching {
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0) cursor.getString(index) else null
    }
}.getOrNull()?.takeIf { it.isNotBlank() }

@Composable
private fun NoteConflictDialog(
    conflict: NoteConflictEntity,
    onResolve: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var merged by remember(conflict.conflictId) { mutableStateOf(conflict.localMarkdown) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("笔记同步冲突") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("共同祖先", fontWeight = FontWeight.Bold)
                Text(conflict.baseMarkdown.take(500), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("本地版本", fontWeight = FontWeight.Bold)
                TextButton(onClick = { merged = conflict.localMarkdown }) { Text("使用本地版本") }
                Text("远端版本（${conflict.remoteDeviceId}）", fontWeight = FontWeight.Bold)
                TextButton(onClick = { merged = conflict.remoteMarkdown }) { Text("使用远端版本") }
                OutlinedTextField(merged, { merged = it }, label = { Text("合并后的 Markdown") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onResolve(merged) }) { Text("保存合并") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("稍后处理") }
        }
    )
}

@Composable
private fun NoteDetailPaneTransition(
    windowInfo: AdaptiveWindowInfo,
    progress: Float,
    listPane: @Composable () -> Unit,
    editorPane: @Composable () -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
    ) {
        val paneProgress = progress.coerceIn(0f, 1f)
        if (!windowInfo.isMediumOrExpanded) {
            Box(
                modifier = Modifier
                    .offset(x = -(maxWidth * 0.12f * paneProgress))
                    .fillMaxSize()
            ) {
                listPane()
            }
            Box(
                modifier = Modifier
                    .offset(x = maxWidth * (1f - paneProgress))
                    .fillMaxSize()
            ) {
                editorPane()
            }
            return@BoxWithConstraints
        }

        val horizontalPadding = if (windowInfo.isExpanded) 20.dp else 12.dp
        val spacing = if (windowInfo.isExpanded) 16.dp else 12.dp
        val targetStartPaneWidth = if (windowInfo.isExpanded) 384.dp else 320.dp
        val minEndPaneWidth = if (windowInfo.isExpanded) 420.dp else 320.dp
        val maxStartPaneWidth = (
            maxWidth - horizontalPadding * 2 - spacing - minEndPaneWidth
            ).coerceAtLeast(240.dp)
        val startPaneWidth = minOf(targetStartPaneWidth, maxStartPaneWidth)
        val endPaneWidth = (
            maxWidth - horizontalPadding * 2 - startPaneWidth - spacing
            ).coerceAtLeast(minEndPaneWidth)
        val endPaneX = horizontalPadding + startPaneWidth + spacing

        Box(
            modifier = Modifier
                .offset(x = lerpNotePaneDp(0.dp, horizontalPadding, paneProgress))
                .width(lerpNotePaneDp(maxWidth, startPaneWidth, paneProgress))
                .fillMaxHeight()
                .clipToBounds()
        ) {
            listPane()
        }
        Box(
            modifier = Modifier
                .offset(x = lerpNotePaneDp(maxWidth + spacing, endPaneX, paneProgress))
                .width(endPaneWidth)
                .fillMaxHeight()
                .clipToBounds()
        ) {
            editorPane()
        }
    }
}

private fun lerpNotePaneDp(start: androidx.compose.ui.unit.Dp, end: androidx.compose.ui.unit.Dp, progress: Float) =
    start + (end - start) * progress

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteListPage(
    notes: List<NoteEntity>,
    conflicts: List<NoteConflictEntity>,
    onBack: () -> Unit,
    onOpen: (NoteEntity) -> Unit,
    onNew: () -> Unit,
    onOpenConflict: () -> Unit,
    onImportDirectory: () -> Unit,
    onImportMarkdown: () -> Unit,
    onImportZip: () -> Unit,
    onExportZip: () -> Unit
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("笔记") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    if (conflicts.isNotEmpty()) {
                        TextButton(onClick = onOpenConflict) { Text("冲突 ${conflicts.size}") }
                    }
                    IconButton(onClick = onImportDirectory) {
                        Icon(Icons.Default.FolderOpen, "导入目录")
                    }
                    IconButton(onClick = onImportMarkdown) {
                        Icon(Icons.Default.Description, "导入 Markdown")
                    }
                    IconButton(onClick = onImportZip) {
                        Icon(Icons.Default.Archive, "导入 ZIP")
                    }
                    IconButton(onClick = onExportZip) {
                        Icon(Icons.Default.Save, "导出 ZIP")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNew) {
                Icon(Icons.Default.Add, "新建笔记")
            }
        }
    ) { padding ->
        NoteList(notes, onOpen, Modifier.padding(padding))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteEditorPage(
    note: NoteEntity?,
    repository: NoteRepository,
    scope: CoroutineScope,
    syncCloud: suspend () -> Unit,
    showFullscreenControl: Boolean,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("编辑笔记") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    if (showFullscreenControl) {
                        IconButton(onClick = onToggleFullscreen) {
                            Icon(
                                imageVector = if (fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                contentDescription = if (fullscreen) "退出全屏" else "全屏"
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        NoteEditor(
            note = note,
            repository = repository,
            onSaved = onSaved,
            scope = scope,
            syncCloud = syncCloud,
            modifier = Modifier.padding(padding)
        )
    }
}

@Composable
private fun NoteList(
    notes: List<NoteEntity>,
    onOpen: (NoteEntity) -> Unit,
    modifier: Modifier = Modifier
) = LazyColumn(
    modifier = modifier
        .fillMaxSize()
        .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
) {
    items(notes, key = { it.noteId }) { note ->
        val cardColors = CardDefaults.elevatedCardColors()
        ElevatedCard(
            onClick = { onOpen(note) },
            modifier = Modifier.fillMaxWidth(),
            colors = cardColors
        ) {
            ListItem(
                colors = ListItemDefaults.colors(
                    containerColor = cardColors.containerColor
                ),
                headlineContent = {
                    Text(
                        text = note.title,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                supportingContent = {
                    Text(
                        text = note.plainText.take(100),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                leadingContent = {
                    Icon(Icons.Default.Description, contentDescription = null)
                }
            )
        }
    }
}
