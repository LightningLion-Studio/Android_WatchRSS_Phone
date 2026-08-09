package com.lightningstudio.watchrss.phone

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatAlignJustify
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatClear
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.FormatColorText
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.StrikethroughS
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import com.lightningstudio.watchrss.phone.data.note.NoteAssetStore
import com.lightningstudio.watchrss.phone.data.note.NoteEntity
import com.lightningstudio.watchrss.phone.data.note.NoteRepository
import com.lightningstudio.watchrss.phone.ui.reader.ReaderBackgroundSurface
import com.lightningstudio.watchrss.phone.ui.reader.ReaderTextRole
import com.lightningstudio.watchrss.phone.ui.reader.readerTextStyle
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.model.ImageData
import com.mohamedrejeb.richeditor.model.ImageLoader
import com.mohamedrejeb.richeditor.model.LocalImageLoader
import com.mohamedrejeb.richeditor.model.RichSpanStyle
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.BasicRichTextEditor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

private val Heading1Style = SpanStyle(fontSize = 2.em, fontWeight = FontWeight.Bold)
private val Heading2Style = SpanStyle(fontSize = 1.5.em, fontWeight = FontWeight.Bold)
private val Heading3Style = SpanStyle(fontSize = 1.17.em, fontWeight = FontWeight.Bold)
private val HeadingStyles = listOf(Heading1Style, Heading2Style, Heading3Style)

@Composable
internal fun NoteEditor(
    note: NoteEntity?,
    repository: NoteRepository,
    onSaved: () -> Unit,
    scope: CoroutineScope,
    syncCloud: suspend () -> Unit,
    modifier: Modifier = Modifier
) {
    var title by remember(note?.noteId) { mutableStateOf(note?.title.orEmpty()) }
    var keepOriginal by remember(note?.noteId) { mutableStateOf(false) }
    var linkDialogVisible by remember { mutableStateOf(false) }
    var tableDialogVisible by remember { mutableStateOf(false) }
    val editorNoteId = remember(note?.noteId) { note?.noteId ?: UUID.randomUUID().toString() }
    val editor = rememberRichTextState()
    val editorFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val restoreEditorFocus = {
        editorFocusRequester.requestFocus()
        keyboardController?.show()
        Unit
    }
    val context = LocalContext.current
    val imageStore = remember(context) { NoteAssetStore(context) }
    val imageLoader = remember(context) { NoteImageLoader(context.filesDir) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            val asset = imageStore.importImage(editorNoteId, uri, keepOriginal)
            repository.registerAsset(asset.entity)
            asset.additionalAssets.forEach { repository.registerAsset(it) }
            editor.insertImage(asset.markdownPath, asset.entity.displayName)
            restoreEditorFocus()
        }
    }

    LaunchedEffect(editor, note?.noteId) {
        val markup = note?.markdown.orEmpty()
        if (markup.isNoteRichHtmlMarkup()) editor.setHtml(markup) else editor.setMarkdown(markup)
        var previousText = editor.annotatedString.text
        snapshotFlow { editor.annotatedString.text to editor.selection }
            .collectLatest { (currentText, selection) ->
                if (
                    shouldExitHeadingAfterEdit(
                        previousText = previousText,
                        currentText = currentText,
                        selection = selection,
                        currentStyle = editor.currentSpanStyle
                    )
                ) {
                    HeadingStyles.forEach(editor::removeSpanStyle)
                }
                previousText = currentText
            }
    }

    val bodyTextStyle = readerTextStyle(ReaderTextRole.BODY)
    val editorTextStyle = bodyTextStyle.copy(
        fontSynthesis = FontSynthesis.All,
        textAlign = TextAlign.Justify
    )
    val editorShape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("标题") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        )

        NoteFormattingToolbar(
            editor = editor,
            readerTextColor = bodyTextStyle.color,
            onRestoreEditorFocus = restoreEditorFocus,
            onEditLink = { linkDialogVisible = true },
            onInsertTable = { tableDialogVisible = true },
            onInsertImage = { imagePicker.launch("image/*") },
            modifier = Modifier.fillMaxWidth()
        )

        ReaderBackgroundSurface(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(editorShape)
        ) {
            Surface(
                color = Color.Transparent,
                contentColor = bodyTextStyle.color,
                shape = editorShape,
                border = BorderStroke(1.dp, bodyTextStyle.color.copy(alpha = 0.22f)),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(Modifier.fillMaxSize()) {
                    if (editor.annotatedString.text.isEmpty()) {
                        Text(
                            text = "开始记录…",
                            style = editorTextStyle,
                            color = bodyTextStyle.color.copy(alpha = 0.52f),
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
                        )
                    }
                    CompositionLocalProvider(LocalImageLoader provides imageLoader) {
                        BasicRichTextEditor(
                            state = editor,
                            textStyle = editorTextStyle,
                            modifier = Modifier
                                .fillMaxSize()
                                .focusRequester(editorFocusRequester)
                                .padding(horizontal = 20.dp, vertical = 16.dp)
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Switch(checked = keepOriginal, onCheckedChange = { keepOriginal = it })
            Spacer(Modifier.width(8.dp))
            Text(
                text = "插图保留原图",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            Button(
                onClick = {
                    scope.launch {
                        repository.save(
                            editorNoteId,
                            title,
                            editor.toNoteStorageMarkup(),
                            note?.folderId,
                            note?.pinned ?: false
                        )
                        // The encrypted cloud service remains the authority for account checks.
                        runCatching { syncCloud() }
                        onSaved()
                    }
                }
            ) {
                Icon(Icons.Default.Save, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("保存")
            }
        }
    }

    if (linkDialogVisible) {
        LinkEditorDialog(
            editor = editor,
            onDismiss = {
                linkDialogVisible = false
                restoreEditorFocus()
            }
        )
    }
    if (tableDialogVisible) {
        TableInsertDialog(
            onConfirm = { rows, columns ->
                editor.addTextAfterSelection(markdownTable(rows, columns))
                tableDialogVisible = false
                restoreEditorFocus()
            },
            onDismiss = {
                tableDialogVisible = false
                restoreEditorFocus()
            }
        )
    }
}

@Composable
private fun NoteFormattingToolbar(
    editor: RichTextState,
    readerTextColor: Color,
    onRestoreEditorFocus: () -> Unit,
    onEditLink: () -> Unit,
    onInsertTable: () -> Unit,
    onInsertImage: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spanStyle = editor.currentSpanStyle
    val paragraphStyle = editor.currentParagraphStyle
    val heading = HeadingStyles.indexOfFirst { it.fontSize == spanStyle.fontSize } + 1
    val paragraphAlignment = paragraphStyle.textAlign.takeUnless { it == TextAlign.Unspecified }
        ?: TextAlign.Justify
    val applyAndRestoreFocus: (() -> Unit) -> Unit = { action ->
        action()
        onRestoreEditorFocus()
    }

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp
    ) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            item {
                HeadingToolButton("P", "正文", heading == 0) {
                    applyAndRestoreFocus { HeadingStyles.forEach(editor::removeSpanStyle) }
                }
            }
            items(3) { index ->
                val level = index + 1
                HeadingToolButton("H$level", "$level 级标题", heading == level) {
                    applyAndRestoreFocus {
                        val target = HeadingStyles[index]
                        HeadingStyles.filterNot { it == target }.forEach(editor::removeSpanStyle)
                        if (heading == level) editor.removeSpanStyle(target)
                        else editor.addSpanStyle(target)
                    }
                }
            }
            item { ToolbarDivider() }
            item {
                EditorToolButton(
                    label = "粗体",
                    selected = spanStyle.fontWeight?.weight?.let { it > FontWeight.Normal.weight } == true,
                    onClick = {
                        applyAndRestoreFocus {
                            editor.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold))
                        }
                    }
                ) { Icon(Icons.Default.FormatBold, contentDescription = null) }
            }
            item {
                EditorToolButton(
                    label = "斜体",
                    selected = spanStyle.fontStyle == FontStyle.Italic,
                    onClick = {
                        applyAndRestoreFocus {
                            editor.toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic))
                        }
                    }
                ) { Icon(Icons.Default.FormatItalic, contentDescription = null) }
            }
            item {
                EditorToolButton(
                    label = "下划线",
                    selected = spanStyle.textDecoration?.contains(TextDecoration.Underline) == true,
                    onClick = {
                        applyAndRestoreFocus {
                            editor.toggleSpanStyle(
                                SpanStyle(textDecoration = TextDecoration.Underline)
                            )
                        }
                    }
                ) { Icon(Icons.Default.FormatUnderlined, contentDescription = null) }
            }
            item {
                EditorToolButton(
                    label = "删除线",
                    selected = spanStyle.textDecoration?.contains(TextDecoration.LineThrough) == true,
                    onClick = {
                        applyAndRestoreFocus {
                            editor.toggleSpanStyle(
                                SpanStyle(textDecoration = TextDecoration.LineThrough)
                            )
                        }
                    }
                ) { Icon(Icons.Default.StrikethroughS, contentDescription = null) }
            }
            item {
                EditorToolButton(
                    label = "行内代码",
                    selected = editor.isCodeSpan,
                    onClick = { applyAndRestoreFocus(editor::toggleCodeSpan) }
                ) { Icon(Icons.Default.Code, contentDescription = null) }
            }
            item { ToolbarDivider() }
            item {
                TextColorToolButton(
                    editor = editor,
                    readerTextColor = readerTextColor,
                    onApplied = onRestoreEditorFocus
                )
            }
            item {
                EditorToolButton(
                    label = "高亮",
                    selected = spanStyle.background != Color.Unspecified,
                    onClick = {
                        applyAndRestoreFocus {
                            editor.toggleHighlight(readerTextColor.copy(alpha = 0.22f))
                        }
                    }
                ) { Icon(Icons.Default.FormatColorFill, contentDescription = null) }
            }
            item { ToolbarDivider() }
            item {
                EditorToolButton(
                    label = "无序列表",
                    selected = editor.isUnorderedList,
                    onClick = { applyAndRestoreFocus(editor::toggleUnorderedList) }
                ) { Icon(Icons.AutoMirrored.Filled.FormatListBulleted, contentDescription = null) }
            }
            item {
                EditorToolButton(
                    label = "有序列表",
                    selected = editor.isOrderedList,
                    onClick = { applyAndRestoreFocus(editor::toggleOrderedList) }
                ) { Icon(Icons.Default.FormatListNumbered, contentDescription = null) }
            }
            item { ToolbarDivider() }
            item {
                AlignmentToolButton("左对齐", paragraphAlignment == TextAlign.Left) {
                    applyAndRestoreFocus { editor.setParagraphAlignment(TextAlign.Left) }
                }
            }
            item {
                AlignmentToolButton("居中", paragraphAlignment == TextAlign.Center) {
                    applyAndRestoreFocus { editor.setParagraphAlignment(TextAlign.Center) }
                }
            }
            item {
                AlignmentToolButton("右对齐", paragraphAlignment == TextAlign.Right) {
                    applyAndRestoreFocus { editor.setParagraphAlignment(TextAlign.Right) }
                }
            }
            item {
                AlignmentToolButton("两端对齐", paragraphAlignment == TextAlign.Justify) {
                    applyAndRestoreFocus { editor.setParagraphAlignment(TextAlign.Justify) }
                }
            }
            item { ToolbarDivider() }
            item {
                EditorToolButton(
                    label = if (editor.isLink) "编辑链接" else "插入链接",
                    selected = editor.isLink,
                    onClick = onEditLink
                ) { Icon(Icons.Default.Link, contentDescription = null) }
            }
            item {
                EditorToolButton(label = "插入表格", onClick = onInsertTable) {
                    Icon(Icons.Default.TableChart, contentDescription = null)
                }
            }
            item {
                EditorToolButton(label = "插入图片", onClick = onInsertImage) {
                    Icon(Icons.Default.Image, contentDescription = null)
                }
            }
            item {
                EditorToolButton(
                    label = "清除格式",
                    onClick = {
                        applyAndRestoreFocus {
                            editor.clearSpanStyles()
                            editor.removeUnorderedList()
                            editor.removeOrderedList()
                            editor.removeParagraphStyle(editor.currentParagraphStyle)
                        }
                    }
                ) { Icon(Icons.Default.FormatClear, contentDescription = null) }
            }
        }
    }
}

private class NoteImageLoader(private val filesDir: File) : ImageLoader {
    @Composable
    override fun load(model: Any): ImageData {
        val resolved = (model as? String)
            ?.takeIf { it.startsWith("assets/") }
            ?.let { File(filesDir, "notes/$it") }
            ?: model
        return ImageData(
            painter = rememberAsyncImagePainter(resolved),
            contentDescription = null
        )
    }
}

internal fun RichTextState.insertImage(markdownPath: String, description: String) {
    addTextAfterSelection("\n")
    addRichSpan(
        RichSpanStyle.Image(
            model = markdownPath,
            width = 20.em,
            height = 0.sp,
            contentDescription = description
        )
    )
    addTextAfterSelection("\n")
}

@Composable
private fun TableInsertDialog(
    onConfirm: (rows: Int, columns: Int) -> Unit,
    onDismiss: () -> Unit
) {
    var rows by remember { mutableStateOf(3) }
    var columns by remember { mutableStateOf(3) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.TableChart, contentDescription = null) },
        title = { Text("插入表格") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TableSizeSelector("行数", rows, 2..8) { rows = it }
                TableSizeSelector("列数", columns, 2..6) { columns = it }
                Text(
                    "首行为表头，之后可直接修改单元格内容。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(rows, columns) }) { Text("插入") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun TableSizeSelector(
    label: String,
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.width(64.dp))
        TextButton(onClick = { onValueChange((value - 1).coerceIn(range)) }) { Text("−") }
        Text(value.toString(), modifier = Modifier.width(32.dp), textAlign = TextAlign.Center)
        TextButton(onClick = { onValueChange((value + 1).coerceIn(range)) }) { Text("+") }
    }
}

internal fun markdownTable(rows: Int, columns: Int): String {
    require(rows >= 2 && columns >= 2)
    val header = (1..columns).joinToString(" | ", prefix = "| ", postfix = " |") { "表头$it" }
    val separator = List(columns) { "---" }.joinToString(" | ", prefix = "| ", postfix = " |")
    val body = (1 until rows).joinToString("\n") { row ->
        (1..columns).joinToString(" | ", prefix = "| ", postfix = " |") { column -> "内容$row-$column" }
    }
    return "\n$header\n$separator\n$body\n"
}

@Composable
private fun AlignmentToolButton(label: String, selected: Boolean, onClick: () -> Unit) {
    val icon = when (label) {
        "左对齐" -> Icons.AutoMirrored.Filled.FormatAlignLeft
        "居中" -> Icons.Default.FormatAlignCenter
        "右对齐" -> Icons.AutoMirrored.Filled.FormatAlignRight
        else -> Icons.Default.FormatAlignJustify
    }
    EditorToolButton(label = label, selected = selected, onClick = onClick) {
        Icon(icon, contentDescription = null)
    }
}

private fun RichTextState.setParagraphAlignment(alignment: TextAlign) {
    val style = ParagraphStyle(textAlign = alignment)
    if (currentParagraphStyle.textAlign == alignment) removeParagraphStyle(style)
    else addParagraphStyle(style)
}

@Composable
private fun HeadingToolButton(
    glyph: String,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    EditorToolButton(label = label, selected = selected, onClick = onClick) {
        Text(
            text = glyph,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun EditorToolButton(
    label: String,
    selected: Boolean = false,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    IconToggleButton(
        checked = selected,
        onCheckedChange = { onClick() },
        modifier = Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (selected) Modifier.background(MaterialTheme.colorScheme.secondaryContainer)
                else Modifier
            )
            .semantics { contentDescription = label }
    ) {
        content()
    }
}

@Composable
private fun ToolbarDivider() {
    VerticalDivider(
        modifier = Modifier
            .height(26.dp)
            .padding(horizontal = 5.dp),
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

private data class NoteTextColor(val label: String, val color: Color?)

@Composable
private fun TextColorToolButton(
    editor: RichTextState,
    readerTextColor: Color,
    onApplied: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var customDialogVisible by remember { mutableStateOf(false) }
    val currentColor = editor.currentSpanStyle.color
    val colors = remember(readerTextColor) {
        listOf(
            NoteTextColor("跟随阅读主题", null),
            NoteTextColor("主题文字色", readerTextColor),
            NoteTextColor("石墨", Color(0xFF30343B)),
            NoteTextColor("白色", Color(0xFFF5F7FA)),
            NoteTextColor("珊瑚红", Color(0xFFD84A4A)),
            NoteTextColor("琥珀", Color(0xFFD17B17)),
            NoteTextColor("松绿", Color(0xFF27856B)),
            NoteTextColor("湖蓝", Color(0xFF2879B8)),
            NoteTextColor("靛蓝", Color(0xFF5B61B9)),
            NoteTextColor("莓紫", Color(0xFF9A4D8C))
        )
    }

    Box {
        EditorToolButton(
            label = "字体颜色",
            selected = currentColor != Color.Unspecified,
            onClick = { expanded = true }
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.FormatColorText, contentDescription = null)
                Box(
                    Modifier
                        .width(18.dp)
                        .height(3.dp)
                        .background(
                            if (currentColor == Color.Unspecified) readerTextColor else currentColor,
                            CircleShape
                        )
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            colors.chunked(2).forEach { row ->
                Row(Modifier.padding(horizontal = 8.dp)) {
                    row.forEach { option ->
                        ColorMenuItem(
                            option = option,
                            selected = if (option.color == null) {
                                currentColor == Color.Unspecified
                            } else {
                                currentColor == option.color
                            },
                            onClick = {
                                editor.setTextColor(option.color)
                                expanded = false
                                onApplied()
                            }
                        )
                    }
                }
            }
            DropdownMenuItem(
                text = { Text("自定义颜色") },
                leadingIcon = { Icon(Icons.Default.FormatColorText, contentDescription = null) },
                onClick = {
                    expanded = false
                    customDialogVisible = true
                }
            )
        }
    }

    if (customDialogVisible) {
        CustomColorDialog(
            initialColor = currentColor.takeUnless { it == Color.Unspecified } ?: readerTextColor,
            onConfirm = {
                editor.setTextColor(it)
                customDialogVisible = false
                onApplied()
            },
            onDismiss = { customDialogVisible = false }
        )
    }
}

@Composable
private fun ColorMenuItem(option: NoteTextColor, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .width(148.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(option.color ?: MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (option.color == null) {
                Text("A", style = MaterialTheme.typography.labelSmall)
            } else if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = if (option.color.toArgb().luminanceIsDark()) Color.White else Color.Black,
                    modifier = Modifier.size(15.dp)
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(option.label, style = MaterialTheme.typography.bodySmall, maxLines = 1)
    }
}

private fun RichTextState.setTextColor(color: Color?) {
    val existing = currentSpanStyle.color
    if (existing != Color.Unspecified) removeSpanStyle(SpanStyle(color = existing))
    color?.let { addSpanStyle(SpanStyle(color = it)) }
}

private fun RichTextState.toggleHighlight(color: Color) {
    val existing = currentSpanStyle.background
    if (existing != Color.Unspecified) removeSpanStyle(SpanStyle(background = existing))
    else addSpanStyle(SpanStyle(background = color))
}

internal fun shouldExitHeadingAfterEdit(
    previousText: String,
    currentText: String,
    selection: TextRange,
    currentStyle: SpanStyle
): Boolean {
    if (!selection.collapsed || currentText.length != previousText.length + 1) return false
    val insertedIndex = selection.start - 1
    if (insertedIndex !in currentText.indices || currentText[insertedIndex] != '\n') return false
    if (currentText.removeRange(insertedIndex, insertedIndex + 1) != previousText) return false
    return HeadingStyles.any { it.fontSize == currentStyle.fontSize }
}

@Composable
private fun CustomColorDialog(
    initialColor: Color,
    onConfirm: (Color) -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember(initialColor) { mutableStateOf(initialColor.toHexRgb()) }
    val parsed = remember(value) { parseHexColor(value) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.FormatColorText, contentDescription = null) },
        title = { Text("自定义字体颜色") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(parsed ?: initialColor)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    )
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it.take(7) },
                        label = { Text("十六进制颜色") },
                        supportingText = { if (parsed == null) Text("请输入 #RRGGBB") },
                        isError = parsed == null,
                        singleLine = true
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let(onConfirm) }, enabled = parsed != null) {
                Text("应用")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun LinkEditorDialog(editor: RichTextState, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(editor.selectedLinkText.orEmpty()) }
    var url by remember { mutableStateOf(editor.selectedLinkUrl.orEmpty()) }
    val editingExistingLink = editor.isLink
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Link, contentDescription = null) },
        title = { Text(if (editingExistingLink) "编辑链接" else "插入链接") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!editingExistingLink && editor.selectedLinkText.isNullOrBlank()) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("显示文字") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("链接地址") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = url.isNotBlank() && (
                    editingExistingLink ||
                        !editor.selectedLinkText.isNullOrBlank() ||
                        text.isNotBlank()
                    ),
                onClick = {
                    when {
                        editingExistingLink -> editor.updateLink(url.trim())
                        !editor.selectedLinkText.isNullOrBlank() -> editor.addLinkToSelection(url.trim())
                        else -> editor.addLink(text.trim(), url.trim())
                    }
                    onDismiss()
                }
            ) { Text("完成") }
        },
        dismissButton = {
            Row {
                if (editingExistingLink) {
                    TextButton(onClick = { editor.removeLink(); onDismiss() }) { Text("移除链接") }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

/**
 * Standard formatting stays readable Markdown. Styles that Markdown cannot represent are
 * kept as inline HTML, which the editor's Markdown parser can read on the next edit.
 */
internal fun RichTextState.toNoteStorageMarkup(): String {
    val html = toHtml()
    return if (html.isNoteRichHtmlMarkup()) html else toMarkdown()
}

internal fun String.isNoteRichHtmlMarkup(): Boolean = listOf(
        "color:",
        "background:",
        "text-align:",
        "<img",
        "<sub>",
        "<sup>"
    ).any(::contains)

internal fun parseHexColor(value: String): Color? {
    val normalized = value.trim().removePrefix("#")
    if (normalized.length != 6 || normalized.any { it !in "0123456789abcdefABCDEF" }) return null
    return Color(0xFF000000 or normalized.toLong(16))
}

private fun Color.toHexRgb(): String = "#%06X".format(toArgb() and 0xFFFFFF)

private fun Int.luminanceIsDark(): Boolean {
    val red = (this shr 16) and 0xFF
    val green = (this shr 8) and 0xFF
    val blue = this and 0xFF
    return red * 299 + green * 587 + blue * 114 < 128_000
}
