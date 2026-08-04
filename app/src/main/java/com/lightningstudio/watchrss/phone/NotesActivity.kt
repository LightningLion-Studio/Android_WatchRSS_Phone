package com.lightningstudio.watchrss.phone

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.dp
import com.lightningstudio.watchrss.phone.data.note.NoteEntity
import com.lightningstudio.watchrss.phone.data.note.NoteRepository
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
        val repository = (application as PhoneCompanionApplication).container.noteRepository
        val noteSync = (application as PhoneCompanionApplication).container.noteBluetoothSyncManager
        setContent { WatchRssPhoneTheme { NotesScreen(repository, ::finish, lifecycleScope, { noteSync.sync() }) } }
    }
    companion object { fun createIntent(context: Context) = Intent(context, NotesActivity::class.java) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotesScreen(repository: NoteRepository, onBack: () -> Unit, scope: CoroutineScope, syncNotes: suspend () -> Unit) {
    val notes by repository.observeNotes().collectAsStateWithLifecycle(emptyList())
    var selected by remember { mutableStateOf<NoteEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text(if (selected == null && !creating) "笔记" else "编辑笔记") }, navigationIcon = { IconButton(onClick = { if (selected != null || creating) { selected = null; creating = false } else onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } }) },
        floatingActionButton = { if (selected == null && !creating) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { FloatingActionButton(onClick = { scope.launch { syncNotes() } }) { Icon(Icons.Default.Sync, "同步手表笔记") }; FloatingActionButton(onClick = { creating = true }) { Icon(Icons.Default.Add, "新建笔记") } } }
    ) { padding ->
        val note = selected
        if (note == null && !creating) NoteList(notes, { selected = it }, Modifier.padding(padding))
        else NoteEditor(note, repository, { selected = null; creating = false }, scope, Modifier.padding(padding))
    }
}

@Composable
private fun NoteList(notes: List<NoteEntity>, onOpen: (NoteEntity) -> Unit, modifier: Modifier = Modifier) = LazyColumn(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    items(notes, key = { it.noteId }) { note -> TextButton(onClick = { onOpen(note) }, modifier = Modifier.fillMaxWidth()) { Column(Modifier.fillMaxWidth()) { Text(note.title, fontWeight = FontWeight.Bold); Text(note.plainText.take(100), color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
}

@Composable
private fun NoteEditor(note: NoteEntity?, repository: NoteRepository, onSaved: () -> Unit, scope: CoroutineScope, modifier: Modifier = Modifier) {
    var title by remember(note?.noteId) { mutableStateOf(note?.title.orEmpty()) }
    val editor = rememberRichTextState()
    LaunchedEffect(note?.noteId) { editor.setMarkdown(note?.markdown.orEmpty()) }
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(title, { title = it }, label = { Text("标题") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { editor.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold)) }) { Text("粗体") }
            TextButton(onClick = { editor.toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic)) }) { Text("斜体") }
            TextButton(onClick = editor::toggleUnorderedList) { Text("列表") }
            TextButton(onClick = editor::toggleCodeSpan) { Text("代码") }
        }
        RichTextEditor(state = editor, modifier = Modifier.weight(1f).fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = { scope.launch { repository.save(note?.noteId, title, editor.toMarkdown(), note?.folderId, note?.pinned ?: false); onSaved() } }) { Text("保存") } }
    }
}
