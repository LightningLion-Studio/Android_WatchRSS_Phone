package com.lightningstudio.watchrss.phone

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lightningstudio.watchrss.phone.data.note.NoteEntity
import com.lightningstudio.watchrss.phone.data.note.NoteConflictEntity
import com.lightningstudio.watchrss.phone.data.note.NoteRepository
import com.lightningstudio.watchrss.phone.data.note.NoteImportExportService
import com.lightningstudio.watchrss.phone.ui.reader.ProvideReaderPreset
import com.lightningstudio.watchrss.phone.ui.theme.AppListCard
import com.lightningstudio.watchrss.phone.ui.theme.WatchRssPhoneTheme
import com.lightningstudio.watchrss.phone.ui.theme.roundedClickable
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope

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
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(if (selected == null && !creating) "笔记" else "编辑笔记") },
                navigationIcon = { IconButton(onClick = { if (selected != null || creating) { selected = null; creating = false } else onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = {
                    if (selected == null && !creating) {
                        if (conflicts.isNotEmpty()) TextButton(onClick = { selectedConflict = conflicts.first() }) { Text("冲突 ${conflicts.size}") }
                        IconButton(onClick = { directoryImport.launch(null) }) { Icon(Icons.Default.FolderOpen, "导入目录") }
                        IconButton(onClick = { markdownImport.launch(arrayOf("text/markdown", "text/x-markdown", "text/plain", "application/octet-stream", "*/*")) }) { Icon(Icons.Default.Description, "导入 Markdown") }
                        IconButton(onClick = { zipImport.launch(arrayOf("application/zip", "application/x-zip-compressed")) }) { Icon(Icons.Default.Archive, "导入 ZIP") }
                        IconButton(onClick = { zipExport.launch("watchrss-notes.zip") }) { Icon(Icons.Default.Save, "导出 ZIP") }
                    }
                }
            )
        },
        floatingActionButton = {
            if (selected == null && !creating) {
                FloatingActionButton(onClick = { creating = true }) {
                    Icon(Icons.Default.Add, "新建笔记")
                }
            }
        }
    ) { padding ->
        val note = selected
        if (note == null && !creating) NoteList(notes, { selected = it }, Modifier.padding(padding))
        else NoteEditor(note, repository, { selected = null; creating = false }, scope, syncCloud, Modifier.padding(padding))
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
        AppListCard(
            modifier = Modifier.fillMaxWidth(),
            interactionModifier = Modifier.roundedClickable(
                shape = RoundedCornerShape(12.dp),
                onClick = { onOpen(note) }
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(note.title, fontWeight = FontWeight.Bold)
                Text(
                    note.plainText.take(100),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
