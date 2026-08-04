package com.lightningstudio.watchrss.phone

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.dp
import com.lightningstudio.watchrss.phone.data.note.NoteEntity
import com.lightningstudio.watchrss.phone.data.note.NoteRepository
import com.lightningstudio.watchrss.phone.data.note.NoteAssetStore
import com.lightningstudio.watchrss.phone.data.note.NoteImportExportService
import com.lightningstudio.watchrss.phone.ui.theme.WatchRssPhoneTheme
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditor
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope

class NotesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as PhoneCompanionApplication).container
        val repository = container.noteRepository
        val noteSync = container.noteBluetoothSyncManager
        setContent {
            WatchRssPhoneTheme {
                NotesScreen(repository, ::finish, lifecycleScope, { noteSync.sync() }, container.cloudSyncService::syncNow)
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
    syncNotes: suspend () -> Unit,
    syncCloud: suspend () -> Unit
) {
    val notes by repository.observeNotes().collectAsStateWithLifecycle(emptyList())
    var selected by remember { mutableStateOf<NoteEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val transfer = remember(context, repository) { NoteImportExportService(context, repository) }
    val zipExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            context.contentResolver.openOutputStream(uri)?.use { it.write(transfer.exportZip()) }
        }
    }
    val zipImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            context.contentResolver.openInputStream(uri)?.use { transfer.importZip(it.readBytes()) }
            runCatching { syncCloud() }
        }
    }
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(if (selected == null && !creating) "笔记" else "编辑笔记") },
                navigationIcon = { IconButton(onClick = { if (selected != null || creating) { selected = null; creating = false } else onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = {
                    if (selected == null && !creating) {
                        IconButton(onClick = { zipImport.launch(arrayOf("application/zip", "application/x-zip-compressed")) }) { Icon(Icons.Default.FileOpen, "导入 ZIP") }
                        IconButton(onClick = { zipExport.launch("watchrss-notes.zip") }) { Icon(Icons.Default.Save, "导出 ZIP") }
                    }
                }
            )
        },
        floatingActionButton = { if (selected == null && !creating) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { FloatingActionButton(onClick = { scope.launch { syncNotes() } }) { Icon(Icons.Default.Sync, "同步手表笔记") }; FloatingActionButton(onClick = { creating = true }) { Icon(Icons.Default.Add, "新建笔记") } } }
    ) { padding ->
        val note = selected
        if (note == null && !creating) NoteList(notes, { selected = it }, Modifier.padding(padding))
        else NoteEditor(note, repository, { selected = null; creating = false }, scope, syncCloud, Modifier.padding(padding))
    }
}

@Composable
private fun NoteList(notes: List<NoteEntity>, onOpen: (NoteEntity) -> Unit, modifier: Modifier = Modifier) = LazyColumn(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    items(notes, key = { it.noteId }) { note -> TextButton(onClick = { onOpen(note) }, modifier = Modifier.fillMaxWidth()) { Column(Modifier.fillMaxWidth()) { Text(note.title, fontWeight = FontWeight.Bold); Text(note.plainText.take(100), color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
}

@Composable
private fun NoteEditor(
    note: NoteEntity?,
    repository: NoteRepository,
    onSaved: () -> Unit,
    scope: CoroutineScope,
    syncCloud: suspend () -> Unit,
    modifier: Modifier = Modifier
) {
    var title by remember(note?.noteId) { mutableStateOf(note?.title.orEmpty()) }
    var keepOriginal by remember(note?.noteId) { mutableStateOf(false) }
    val editor = rememberRichTextState()
    val context = LocalContext.current
    val imageStore = remember(context) { NoteAssetStore(context) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val existing = note ?: return@rememberLauncherForActivityResult
        if (uri != null) scope.launch {
            val asset = imageStore.importImage(existing.noteId, uri, keepOriginal)
            repository.registerAsset(asset.entity)
            asset.additionalAssets.forEach { repository.registerAsset(it) }
            editor.addTextAfterSelection("![${asset.entity.displayName}](${asset.markdownPath})")
        }
    }
    LaunchedEffect(note?.noteId) { editor.setMarkdown(note?.markdown.orEmpty()) }
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(title, { title = it }, label = { Text("标题") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { editor.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold)) }) { Text("粗体") }
            TextButton(onClick = { editor.toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic)) }) { Text("斜体") }
            TextButton(onClick = editor::toggleUnorderedList) { Text("列表") }
            TextButton(onClick = editor::toggleCodeSpan) { Text("代码") }
            if (note != null) TextButton(onClick = { imagePicker.launch("image/*") }) { Text("图片") }
        }
        if (note != null) Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Switch(checked = keepOriginal, onCheckedChange = { keepOriginal = it })
            Text("同时保留原图")
        }
        RichTextEditor(state = editor, modifier = Modifier.weight(1f).fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = {
                scope.launch {
                    repository.save(note?.noteId, title, editor.toMarkdown(), note?.folderId, note?.pinned ?: false)
                    // The encrypted cloud service is the authority for account/session checks;
                    // a local edit must nevertheless request an immediate upload.
                    runCatching { syncCloud() }
                    onSaved()
                }
            }) { Text("保存") }
        }
    }
}
