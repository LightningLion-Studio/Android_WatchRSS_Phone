package com.lightningstudio.watchrss.phone

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.StrikethroughS
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.rememberAsyncImagePainter
import com.lightningstudio.watchrss.phone.data.note.NoteAssetStore
import com.lightningstudio.watchrss.phone.data.note.NoteEntity
import com.lightningstudio.watchrss.phone.data.note.NoteRepository
import com.lightningstudio.watchrss.phone.ui.reader.LocalReaderPresetRuntime
import com.lightningstudio.watchrss.phone.ui.reader.ReaderBackgroundSurface
import com.lightningstudio.watchrss.phone.ui.reader.ReaderTextRole
import com.lightningstudio.watchrss.phone.ui.reader.readerTextStyle
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.model.ImageData
import com.mohamedrejeb.richeditor.model.ImageLoader
import com.mohamedrejeb.richeditor.model.LocalImageLoader
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.BasicRichTextEditor
import com.mohamedrejeb.richeditor.ui.BasicRichText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import kotlin.math.roundToInt

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
    var previewMarkup by remember(note?.noteId) { mutableStateOf(note?.markdown.orEmpty()) }
    var previewMode by remember(note?.noteId) {
        mutableStateOf(note?.markdown?.shouldOpenInNotePreview() == true)
    }
    var focusEditorAfterPreview by remember(note?.noteId) { mutableStateOf(false) }
    var viewerImage by remember(note?.noteId) { mutableStateOf<NotePreviewBlock.Image?>(null) }
    val editorNoteId = remember(note?.noteId) { note?.noteId ?: UUID.randomUUID().toString() }
    val editor = rememberRichTextState()
    val editorScrollState = rememberScrollState()
    val editorFocusRequester = remember { FocusRequester() }
    var editorFocused by remember(note?.noteId) { mutableStateOf(false) }
    var editorTextLayout by remember(note?.noteId) {
        mutableStateOf<TextLayoutResult?>(null)
    }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val restoreEditorFocus = {
        editorFocusRequester.requestFocus()
        keyboardController?.show()
        Unit
    }
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val imageStore = remember(context) { NoteAssetStore(context) }
    val imageLoader = remember(context) { NoteImageLoader(context.filesDir) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            val asset = imageStore.importImage(editorNoteId, uri, keepOriginal)
            val registeredAsset = repository.registerAsset(asset.entity)
            asset.additionalAssets.forEach { repository.registerAsset(it) }
            if (registeredAsset.storageKey != asset.entity.storageKey) {
                imageStore.discardImportedAsset(asset.entity.storageKey)
            }
            val (imageWidthSp, imageHeightSp) = fitNoteImageDisplaySize(
                pixelWidth = asset.pixelWidth,
                pixelHeight = asset.pixelHeight,
                maxWidthSp = (
                    (configuration.screenWidthDp - 72).coerceAtLeast(160) /
                        density.fontScale
                    ).coerceAtMost(560f),
                maxHeightSp = 640f / density.fontScale
            )
            editor.insertImagePlaceholder(
                "assets/${registeredAsset.storageKey}",
                asset.entity.displayName,
                imageWidthSp,
                imageHeightSp
            )
            previewMarkup = editor.toNoteStorageMarkup()
            focusManager.clearFocus()
            keyboardController?.hide()
            previewMode = true
        }
    }

    LaunchedEffect(editor, note?.noteId) {
        val markup = note?.markdown.orEmpty()
        val editorMarkup = markup.toNoteEditorMarkup()
        if (markup.isNoteRichHtmlMarkup()) editor.setHtml(editorMarkup) else editor.setMarkdown(editorMarkup)
        editor.selection = TextRange.Zero
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
    LaunchedEffect(previewMode, focusEditorAfterPreview) {
        if (!previewMode && focusEditorAfterPreview) {
            focusEditorAfterPreview = false
            restoreEditorFocus()
        }
    }

    val bodyTextStyle = readerTextStyle(ReaderTextRole.BODY)
    val codeTextStyle = readerTextStyle(ReaderTextRole.CODE)
    val editorTextStyle = bodyTextStyle.copy(
        fontSynthesis = FontSynthesis.All,
        textAlign = TextAlign.Justify
    )
    val codeBackgroundColor = Color(
        LocalReaderPresetRuntime.current.preset.codeBackgroundColorArgb
    )
    LaunchedEffect(editor, codeTextStyle.color, codeBackgroundColor) {
        editor.applyNoteCodeStyle(
            textColor = codeTextStyle.color,
            backgroundColor = codeBackgroundColor
        )
    }
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
            readOnly = previewMode,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        )

        if (previewMode) {
            NotePreviewToolbar(
                onEdit = {
                    previewMode = false
                    focusEditorAfterPreview = true
                },
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            NoteFormattingToolbar(
                editor = editor,
                readerTextColor = bodyTextStyle.color,
                onRestoreEditorFocus = restoreEditorFocus,
                onPreview = {
                    previewMarkup = editor.toNoteStorageMarkup()
                    focusManager.clearFocus()
                    keyboardController?.hide()
                    previewMode = true
                },
                onEditLink = { linkDialogVisible = true },
                onInsertTable = { tableDialogVisible = true },
                onInsertImage = { imagePicker.launch("image/*") },
                modifier = Modifier.fillMaxWidth()
            )
        }

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
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val editorViewportHeight = maxHeight
                    if (previewMode) {
                        NoteMarkdownPreview(
                            markup = previewMarkup,
                            textStyle = editorTextStyle,
                            textColor = bodyTextStyle.color,
                            codeTextColor = codeTextStyle.color,
                            codeBackgroundColor = codeBackgroundColor,
                            imageLoader = imageLoader,
                            onImageClick = { viewerImage = it },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        val editorContentPadding = 20.dp
                        val editorContentPaddingPx = with(density) {
                            editorContentPadding.toPx()
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(editorScrollState)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = editorViewportHeight)
                            ) {
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
                                            .fillMaxWidth()
                                            .heightIn(min = editorViewportHeight)
                                            .focusRequester(editorFocusRequester)
                                            .onFocusChanged { editorFocused = it.isFocused }
                                            .padding(
                                                horizontal = editorContentPadding,
                                                vertical = 16.dp
                                            ),
                                        onTextLayout = { editorTextLayout = it }
                                    )
                                }
                                if (!editorFocused) {
                                    Box(
                                        modifier = Modifier
                                            .matchParentSize()
                                            .pointerInput(editorTextLayout) {
                                                awaitEachGesture {
                                                    val down = awaitFirstDown(
                                                        requireUnconsumed = false,
                                                        pass = PointerEventPass.Initial
                                                    )
                                                    val up = waitForUpOrCancellation(
                                                        pass = PointerEventPass.Initial
                                                    )
                                                    if (
                                                        up != null &&
                                                        (up.position - down.position).getDistance() <=
                                                        viewConfiguration.touchSlop
                                                    ) {
                                                        val layout = editorTextLayout
                                                        if (layout != null) {
                                                            val textPosition = Offset(
                                                                x = (up.position.x - editorContentPaddingPx)
                                                                    .coerceAtLeast(0f),
                                                                y = (up.position.y - with(density) {
                                                                    16.dp.toPx()
                                                                }).coerceAtLeast(0f)
                                                            )
                                                            editor.selection = TextRange(
                                                                layout.getOffsetForPosition(textPosition)
                                                            )
                                                        }
                                                        editorFocusRequester.requestFocus()
                                                        keyboardController?.show()
                                                    }
                                                }
                                            }
                                    )
                                }
                            }
                        }
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
                        val editorMarkup = editor.toNoteStorageMarkup()
                        repository.save(
                            editorNoteId,
                            title,
                            selectNoteStorageMarkup(
                                previewMode = previewMode,
                                previewMarkup = previewMarkup,
                                editorMarkup = editorMarkup
                            ),
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
                previewMarkup = editor.toNoteStorageMarkup()
                tableDialogVisible = false
                focusManager.clearFocus()
                keyboardController?.hide()
                previewMode = true
            },
            onDismiss = {
                tableDialogVisible = false
                restoreEditorFocus()
            }
        )
    }
    viewerImage?.let { image ->
        NoteImageViewerDialog(
            image = image,
            imageLoader = imageLoader,
            onDismiss = { viewerImage = null }
        )
    }
}

@Composable
private fun NoteFormattingToolbar(
    editor: RichTextState,
    readerTextColor: Color,
    onRestoreEditorFocus: () -> Unit,
    onPreview: () -> Unit,
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
                EditorToolButton(label = "预览", onClick = onPreview) {
                    Icon(Icons.Default.Visibility, contentDescription = null)
                }
            }
            item { ToolbarDivider() }
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

@Composable
private fun NotePreviewToolbar(
    onEdit: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = "阅读预览",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("编辑")
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

internal fun RichTextState.insertImagePlaceholder(
    markdownPath: String,
    description: String,
    widthSp: Float = 320f,
    heightSp: Float = 240f
) {
    require(widthSp > 0f && heightSp > 0f)
    val insertionStart = selection.min
    val payload = NoteEditorImagePayload(
        path = markdownPath,
        description = description,
        widthSp = widthSp.roundToInt().coerceAtLeast(1),
        heightSp = heightSp.roundToInt().coerceAtLeast(1)
    )
    val label = "🖼 ${description.ifBlank { "图片" }.take(48)}"
    addTextAfterSelection(label)
    addLinkToTextRange(payload.toEditorUrl(), TextRange(insertionStart, insertionStart + label.length))
    selection = TextRange(insertionStart + label.length)
}

private fun String.escapeNoteHtmlAttribute(): String =
    replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

private data class NoteEditorImagePayload(
    val path: String,
    val description: String,
    val widthSp: Int,
    val heightSp: Int
) {
    fun toEditorUrl(): String {
        val bytes = listOf(path, description, widthSp.toString(), heightSp.toString())
            .joinToString("\u0000")
            .toByteArray(StandardCharsets.UTF_8)
        return NoteEditorImageUrlPrefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun toHtmlImage(): String = buildString {
        append("<img src=\"")
        append(path.escapeNoteHtmlAttribute())
        append("\" width=\"")
        append(widthSp.coerceAtLeast(1))
        append("\" height=\"")
        append(heightSp.coerceAtLeast(1))
        append("\" alt=\"")
        append(description.escapeNoteHtmlAttribute())
        append("\"></img>")
    }

    fun toMarkdownImage(): String =
        "![${description.escapeMarkdownLabel()}](${path.replace(" ", "%20")})"
}

private const val NoteEditorImageUrlPrefix = "watchrss-note-image:"

private fun String.escapeMarkdownLabel(): String = replace("\\", "\\\\").replace("]", "\\]")

private fun String.toNoteEditorImagePayload(): NoteEditorImagePayload? {
    if (!startsWith(NoteEditorImageUrlPrefix)) return null
    val encoded = removePrefix(NoteEditorImageUrlPrefix)
    val decoded = runCatching {
        String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8)
    }.getOrNull() ?: return null
    val fields = decoded.split('\u0000', limit = 4)
    if (fields.size != 4) return null
    return NoteEditorImagePayload(
        path = fields[0],
        description = fields[1],
        widthSp = fields[2].toIntOrNull()?.coerceAtLeast(1) ?: return null,
        heightSp = fields[3].toIntOrNull()?.coerceAtLeast(1) ?: return null
    )
}

private val NoteEditorHtmlLinkRegex = Regex(
    """(?is)<a\b[^>]*\bhref=[\"'](watchrss-note-image:[^\"']+)[\"'][^>]*>.*?</a>"""
)
private val NoteEditorMarkdownLinkRegex = Regex(
    """\[[^]]*]\((watchrss-note-image:[^)\s]+)\)"""
)
private val NoteMarkdownImageRegex = Regex(
    """!\[([^]]*)]\(([^)\s]+)(?:\s+[\"'][^\"']*[\"'])?\)"""
)

internal fun String.toNoteEditorMarkup(): String {
    val htmlConverted = NoteImageTagRegex.replace(this) { match ->
        val payload = match.value.toImagePayload() ?: return@replace match.value
        "<a href=\"${payload.toEditorUrl()}\">🖼 ${payload.description.escapeNoteHtmlAttribute()}</a>"
    }
    val imageConverted = NoteMarkdownImageRegex.replace(htmlConverted) { match ->
        val path = match.groupValues[2].replace("%20", " ")
        if (!path.startsWith("assets/")) return@replace match.value
        val payload = NoteEditorImagePayload(
            path = path,
            description = match.groupValues[1],
            widthSp = 320,
            heightSp = 240
        )
        "[🖼 ${payload.description.escapeMarkdownLabel()}](${payload.toEditorUrl()})"
    }
    return imageConverted.preserveNoteEditorRepeatedSpaces()
}

private fun String.preserveNoteEditorRepeatedSpaces(): String =
    replace(NOTE_EDITOR_REPEATED_SPACE_REGEX) { match ->
        NOTE_EDITOR_PRESERVED_SPACE.toString().repeat(match.value.length)
    }

private fun String.restoreNoteEditorRepeatedSpaces(): String =
    replace(NOTE_EDITOR_PRESERVED_SPACE, ' ')

private fun String.restoreNoteImagePlaceholders(): String {
    val htmlConverted = NoteEditorHtmlLinkRegex.replace(this) { match ->
        match.groupValues[1].toNoteEditorImagePayload()?.toHtmlImage() ?: match.value
    }
    return NoteEditorMarkdownLinkRegex.replace(htmlConverted) { match ->
        match.groupValues[1].toNoteEditorImagePayload()?.toMarkdownImage() ?: match.value
    }
}

private fun String.toImagePayload(): NoteEditorImagePayload? {
    val path = htmlAttribute("src") ?: return null
    if (!path.startsWith("assets/")) return null
    val description = htmlAttribute("alt").orEmpty()
    return NoteEditorImagePayload(
        path = path,
        description = description,
        widthSp = htmlAttribute("width")?.toFloatOrNull()?.roundToInt()?.coerceAtLeast(1) ?: 320,
        heightSp = htmlAttribute("height")?.toFloatOrNull()?.roundToInt()?.coerceAtLeast(1) ?: 240
    )
}

private fun String.htmlAttribute(name: String): String? =
    Regex("""(?is)\b${Regex.escape(name)}\s*=\s*[\"']([^\"']*)[\"']""")
        .find(this)
        ?.groupValues
        ?.get(1)

internal fun fitNoteImageDisplaySize(
    pixelWidth: Int,
    pixelHeight: Int,
    maxWidthSp: Float,
    maxHeightSp: Float
): Pair<Float, Float> {
    require(pixelWidth > 0 && pixelHeight > 0)
    require(maxWidthSp > 0f && maxHeightSp > 0f)
    val heightAtMaxWidth = maxWidthSp * pixelHeight / pixelWidth
    return if (heightAtMaxWidth <= maxHeightSp) {
        maxWidthSp to heightAtMaxWidth
    } else {
        maxHeightSp * pixelWidth / pixelHeight to maxHeightSp
    }
}

private val NoteImageTagRegex = Regex("""(?is)<img\b[^>]*>(?:\s*</img>)?""")
private val NoteImageDimensionRegex = Regex(
    """(?i)(\b(?:width|height)=[\"'])(\d+(?:\.\d+)?)([\"'])"""
)

internal fun String.normalizeNoteImageDimensionAttributes(): String =
    NoteImageTagRegex.replace(this) { imageTag ->
        NoteImageDimensionRegex.replace(imageTag.value) dimensionReplace@ { dimension ->
            val integerValue = dimension.groupValues[2]
                .toFloatOrNull()
                ?.roundToInt()
                ?.coerceAtLeast(0)
                ?: return@dimensionReplace dimension.value
            dimension.groupValues[1] + integerValue + dimension.groupValues[3]
        }
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

internal sealed interface NotePreviewBlock {
    data class RichText(val markup: String, val html: Boolean = false) : NotePreviewBlock
    data class Image(
        val path: String,
        val description: String,
        val widthSp: Float? = null,
        val heightSp: Float? = null
    ) : NotePreviewBlock
    data class Table(val table: NotePreviewTable) : NotePreviewBlock
}

internal data class NotePreviewTable(
    val rows: List<List<String>>,
    val headerRows: Int,
    val alignments: List<NoteTableAlignment>
) {
    val columnCount: Int = rows.maxOfOrNull { it.size } ?: 0
}

internal enum class NoteTableAlignment { Start, Center, End }

internal fun String.containsMarkdownTable(): Boolean =
    !isNoteRichHtmlMarkup() && parseNotePreviewBlocks(this).any { it is NotePreviewBlock.Table }

internal fun String.shouldOpenInNotePreview(): Boolean =
    parseNotePreviewBlocks(this).any {
        it is NotePreviewBlock.Image || it is NotePreviewBlock.Table
    }

/**
 * Splits portable Markdown into rich-text runs and independently scrollable tables. Besides GFM
 * pipe tables, pasted TSV blocks are accepted so tabular text does not collapse into one line.
 */
internal fun parseNotePreviewBlocks(markup: String): List<NotePreviewBlock> {
    if (markup.isNoteRichHtmlMarkup()) return parseHtmlNotePreviewBlocks(markup)
    return parseMarkdownImagePreviewBlocks(markup)
}

private fun parseHtmlNotePreviewBlocks(markup: String): List<NotePreviewBlock> {
    val result = mutableListOf<NotePreviewBlock>()
    var cursor = 0
    NoteImageTagRegex.findAll(markup).forEach { imageMatch ->
        val before = markup.substring(cursor, imageMatch.range.first)
        if (before.isNotBlank()) result += NotePreviewBlock.RichText(before, html = true)
        val payload = imageMatch.value.toImagePayload()
        if (payload != null) {
            result += NotePreviewBlock.Image(
                path = payload.path,
                description = payload.description,
                widthSp = imageMatch.value.htmlAttribute("width")?.toFloatOrNull(),
                heightSp = imageMatch.value.htmlAttribute("height")?.toFloatOrNull()
            )
        } else {
            result += NotePreviewBlock.RichText(imageMatch.value, html = true)
        }
        cursor = imageMatch.range.last + 1
    }
    val after = markup.substring(cursor)
    if (after.isNotBlank()) result += NotePreviewBlock.RichText(after, html = true)
    return result.ifEmpty { listOf(NotePreviewBlock.RichText(markup, html = true)) }
}

private fun parseMarkdownImagePreviewBlocks(markup: String): List<NotePreviewBlock> {
    val result = mutableListOf<NotePreviewBlock>()
    val richText = StringBuilder()
    var fence: MarkdownFence? = null

    fun flushRichText() {
        if (richText.isNotEmpty()) {
            result += parseMarkdownTablePreviewBlocks(richText.toString())
            richText.clear()
        }
    }

    markup.replace("\r\n", "\n").replace('\r', '\n').split('\n').forEachIndexed { index, line ->
        val fenceMarker = markdownFenceMarker(line)
        if (fence != null) {
            if (richText.isNotEmpty()) richText.append('\n')
            richText.append(line)
            if (fenceMarker?.closes(fence) == true) fence = null
            return@forEachIndexed
        }
        if (fenceMarker != null) {
            fence = fenceMarker
            if (richText.isNotEmpty()) richText.append('\n')
            richText.append(line)
            return@forEachIndexed
        }

        val matches = NoteMarkdownImageRegex.findAll(line).toList()
        if (matches.isEmpty()) {
            if (richText.isNotEmpty()) richText.append('\n')
            richText.append(line)
            return@forEachIndexed
        }

        if (index > 0 && richText.isNotEmpty()) richText.append('\n')
        var cursor = 0
        matches.forEach { imageMatch ->
            richText.append(line.substring(cursor, imageMatch.range.first))
            flushRichText()
            result += NotePreviewBlock.Image(
                path = imageMatch.groupValues[2].replace("%20", " "),
                description = imageMatch.groupValues[1]
            )
            cursor = imageMatch.range.last + 1
        }
        richText.append(line.substring(cursor))
    }
    flushRichText()
    return result
}

private fun parseMarkdownTablePreviewBlocks(markup: String): List<NotePreviewBlock> {
    val normalized = markup.replace("\r\n", "\n").replace('\r', '\n')
    val lines = normalized.split('\n')
    val result = mutableListOf<NotePreviewBlock>()
    val richText = StringBuilder()
    var index = 0
    var fence: MarkdownFence? = null

    fun appendRichTextLine(line: String) {
        if (richText.isNotEmpty()) richText.append('\n')
        richText.append(line)
    }

    fun flushRichText() {
        if (richText.isNotEmpty()) {
            result += NotePreviewBlock.RichText(richText.toString())
            richText.clear()
        }
    }

    while (index < lines.size) {
        val line = lines[index]
        val fenceMarker = markdownFenceMarker(line)
        if (fence != null) {
            appendRichTextLine(line)
            if (fenceMarker?.closes(fence) == true) fence = null
            index++
            continue
        }
        if (fenceMarker != null) {
            fence = fenceMarker
            appendRichTextLine(line)
            index++
            continue
        }

        val pipeTable = parsePipeTable(lines, index)
        if (pipeTable != null) {
            flushRichText()
            result += NotePreviewBlock.Table(pipeTable.table)
            index = pipeTable.nextLine
            continue
        }

        val tabTable = parseTabTable(lines, index)
        if (tabTable != null) {
            flushRichText()
            result += NotePreviewBlock.Table(tabTable.table)
            index = tabTable.nextLine
            continue
        }

        appendRichTextLine(line)
        index++
    }
    flushRichText()
    return result
}

private data class ParsedNoteTable(val table: NotePreviewTable, val nextLine: Int)

private fun parsePipeTable(lines: List<String>, start: Int): ParsedNoteTable? {
    if (start + 1 >= lines.size) return null
    val header = splitMarkdownPipeRow(lines[start]) ?: return null
    val separators = splitMarkdownPipeRow(lines[start + 1]) ?: return null
    if (header.size < 2 || separators.size != header.size) return null
    val alignments = separators.map(::parseTableSeparator)
    if (alignments.any { it == null }) return null

    val rows = mutableListOf(header)
    var index = start + 2
    while (index < lines.size) {
        val row = splitMarkdownPipeRow(lines[index]) ?: break
        if (row.isEmpty()) break
        rows += row.padTableRow(header.size)
        index++
    }
    return ParsedNoteTable(
        table = NotePreviewTable(
            rows = rows,
            headerRows = 1,
            alignments = alignments.filterNotNull()
        ),
        nextLine = index
    )
}

private fun parseTabTable(lines: List<String>, start: Int): ParsedNoteTable? {
    val first = splitTabRow(lines[start]) ?: return null
    val rows = mutableListOf(first)
    var index = start + 1
    while (index < lines.size) {
        val row = splitTabRow(lines[index]) ?: break
        rows += row
        index++
    }
    // One row with three or more columns is intentional enough to treat as a small TSV table;
    // two-column content needs at least a header and one data row to avoid catching prose tabs.
    if (rows.size < 2 && first.size < 3) return null
    val columns = rows.maxOf { it.size }
    return ParsedNoteTable(
        table = NotePreviewTable(
            rows = rows.map { it.padTableRow(columns) },
            headerRows = 1,
            alignments = List(columns) { NoteTableAlignment.Start }
        ),
        nextLine = index
    )
}

private fun splitMarkdownPipeRow(line: String): List<String>? {
    val trimmed = line.trim()
    if ('|' !in trimmed || trimmed.startsWith("    ")) return null
    val content = trimmed.removePrefix("|").removeSuffix("|")
    val cells = mutableListOf<String>()
    val cell = StringBuilder()
    var escaped = false
    content.forEach { character ->
        when {
            escaped -> {
                cell.append(character)
                escaped = false
            }
            character == '\\' -> escaped = true
            character == '|' -> {
                cells += cell.toString().trim()
                cell.clear()
            }
            else -> cell.append(character)
        }
    }
    if (escaped) cell.append('\\')
    cells += cell.toString().trim()
    return cells.takeIf { it.size >= 2 }
}

private fun splitTabRow(line: String): List<String>? {
    if ('\t' !in line || line.startsWith("    ")) return null
    return line.split('\t').map(String::trim).takeIf { it.size >= 2 }
}

private fun parseTableSeparator(value: String): NoteTableAlignment? {
    val trimmed = value.trim()
    if (!Regex(":?-{3,}:?").matches(trimmed)) return null
    return when {
        trimmed.startsWith(':') && trimmed.endsWith(':') -> NoteTableAlignment.Center
        trimmed.endsWith(':') -> NoteTableAlignment.End
        else -> NoteTableAlignment.Start
    }
}

private fun List<String>.padTableRow(size: Int): List<String> =
    take(size) + List((size - this.size).coerceAtLeast(0)) { "" }

private data class MarkdownFence(val marker: Char, val length: Int) {
    fun closes(open: MarkdownFence): Boolean = marker == open.marker && length >= open.length
}

private fun markdownFenceMarker(line: String): MarkdownFence? {
    val trimmed = line.trimStart()
    if (line.length - trimmed.length > 3) return null
    val marker = trimmed.firstOrNull()?.takeIf { it == '`' || it == '~' } ?: return null
    val length = trimmed.takeWhile { it == marker }.length
    return MarkdownFence(marker, length).takeIf { length >= 3 }
}

/** Shared read-only Markdown renderer for support content and note previews. */
@Composable
internal fun ReadOnlyMarkdown(markup: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val imageLoader = remember(context) { NoteImageLoader(context.filesDir) }
    var viewerImage by remember { mutableStateOf<NotePreviewBlock.Image?>(null) }
    NoteMarkdownPreview(
        markup = markup,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        textColor = MaterialTheme.colorScheme.onSurface,
        codeTextColor = MaterialTheme.colorScheme.onSurface,
        codeBackgroundColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        imageLoader = imageLoader,
        onImageClick = { viewerImage = it },
        modifier = modifier,
        scrollable = false
    )
    viewerImage?.let { image ->
        NoteImageViewerDialog(image, imageLoader, onDismiss = { viewerImage = null })
    }
}

@Composable
private fun NoteMarkdownPreview(
    markup: String,
    textStyle: androidx.compose.ui.text.TextStyle,
    textColor: Color,
    codeTextColor: Color,
    codeBackgroundColor: Color,
    imageLoader: ImageLoader,
    onImageClick: (NotePreviewBlock.Image) -> Unit,
    modifier: Modifier = Modifier,
    scrollable: Boolean = true
) {
    val blocks = remember(markup) { parseNotePreviewBlocks(markup) }
    val verticalScrollState = rememberScrollState()
    Column(
        modifier = modifier
            .then(if (scrollable) Modifier.verticalScroll(verticalScrollState) else Modifier)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        blocks.forEachIndexed { index, block ->
            androidx.compose.runtime.key(index, block) {
                when (block) {
                    is NotePreviewBlock.RichText -> NoteRichTextPreviewBlock(
                        markup = block.markup,
                        html = block.html,
                        textStyle = textStyle,
                        codeTextColor = codeTextColor,
                        codeBackgroundColor = codeBackgroundColor,
                        imageLoader = imageLoader
                    )
                    is NotePreviewBlock.Image -> NoteImagePreviewBlock(
                        image = block,
                        textStyle = textStyle,
                        imageLoader = imageLoader,
                        onClick = { onImageClick(block) }
                    )
                    is NotePreviewBlock.Table -> NoteTablePreview(
                        table = block.table,
                        textStyle = textStyle,
                        textColor = textColor,
                        codeTextColor = codeTextColor,
                        codeBackgroundColor = codeBackgroundColor
                    )
                }
            }
        }
    }
}

@Composable
private fun NoteRichTextPreviewBlock(
    markup: String,
    html: Boolean,
    textStyle: androidx.compose.ui.text.TextStyle,
    codeTextColor: Color,
    codeBackgroundColor: Color,
    imageLoader: ImageLoader
) {
    val state = remember(markup, codeTextColor, codeBackgroundColor) {
        RichTextState().also {
            it.applyNoteCodeStyle(codeTextColor, codeBackgroundColor)
            if (html) it.setHtml(markup) else it.setMarkdown(markup)
        }
    }
    CompositionLocalProvider(LocalImageLoader provides imageLoader) {
        BasicRichText(
            state = state,
            style = textStyle,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun NoteImagePreviewBlock(
    image: NotePreviewBlock.Image,
    textStyle: androidx.compose.ui.text.TextStyle,
    imageLoader: ImageLoader,
    onClick: () -> Unit
) {
    val data = imageLoader.load(image.path)
    if (data == null) {
        Text(
            text = "图片加载失败：${image.description.ifBlank { image.path }}",
            style = textStyle,
            color = MaterialTheme.colorScheme.error
        )
        return
    }
    val context = LocalContext.current
    val aspectRatio = remember(image.path, image.widthSp, image.heightSp) {
        noteImageAspectRatio(
            storedWidth = image.widthSp,
            storedHeight = image.heightSp,
            file = image.path
                .takeIf { it.startsWith("assets/") }
                ?.let { File(context.filesDir, "notes/$it") }
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            val stableImageModifier = if (aspectRatio != null) {
                val imageWidth = minOf(maxWidth, 640.dp * aspectRatio)
                Modifier
                    .width(imageWidth)
                    .aspectRatio(aspectRatio)
            } else {
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 640.dp)
            }
            androidx.compose.foundation.Image(
                painter = data.painter,
                contentDescription = data.contentDescription ?: image.description,
                alignment = data.alignment,
                contentScale = data.contentScale,
                modifier = stableImageModifier.clickable(
                    onClickLabel = "查看图片",
                    onClick = onClick
                )
            )
        }
        if (image.description.isNotBlank()) {
            Text(
                text = image.description,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun noteImageAspectRatio(
    storedWidth: Float?,
    storedHeight: Float?,
    file: File?
): Float? {
    if (storedWidth != null && storedHeight != null && storedWidth > 0f && storedHeight > 0f) {
        return (storedWidth / storedHeight).takeIf { it.isFinite() && it > 0f }
    }
    if (file?.isFile != true) return null
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, options)
    return if (options.outWidth > 0 && options.outHeight > 0) {
        options.outWidth.toFloat() / options.outHeight.toFloat()
    } else {
        null
    }
}

@Composable
private fun NoteImageViewerDialog(
    image: NotePreviewBlock.Image,
    imageLoader: ImageLoader,
    onDismiss: () -> Unit
) {
    var scale by remember(image.path) { mutableStateOf(1f) }
    var offset by remember(image.path) { mutableStateOf(Offset.Zero) }
    val data = imageLoader.load(image.path)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(image.path) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val nextScale = (scale * zoom).coerceIn(1f, 5f)
                        scale = nextScale
                        offset = if (nextScale == 1f) Offset.Zero else offset + pan
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (data != null) {
                androidx.compose.foundation.Image(
                    painter = data.painter,
                    contentDescription = data.contentDescription ?: image.description,
                    alignment = data.alignment,
                    contentScale = data.contentScale,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp)
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y
                        )
                )
            } else {
                Text("图片加载失败", color = Color.White)
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(18.dp)
                    .background(Color.Black.copy(alpha = 0.56f), CircleShape)
            ) {
                Icon(Icons.Default.Close, contentDescription = "关闭图片查看器", tint = Color.White)
            }
            if (image.description.isNotBlank()) {
                Text(
                    text = image.description,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .background(Color.Black.copy(alpha = 0.56f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun NoteTablePreview(
    table: NotePreviewTable,
    textStyle: androidx.compose.ui.text.TextStyle,
    textColor: Color,
    codeTextColor: Color,
    codeBackgroundColor: Color
) {
    if (table.columnCount == 0) return
    val horizontalScrollState = rememberScrollState()
    val cellWidth = 176.dp
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, textColor.copy(alpha = 0.22f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(horizontalScrollState)
        ) {
            Column(modifier = Modifier.width(cellWidth * table.columnCount)) {
                table.rows.forEachIndexed { rowIndex, row ->
                    Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                        row.padTableRow(table.columnCount).forEachIndexed { columnIndex, cell ->
                            val alignment = table.alignments.getOrNull(columnIndex)
                                ?: NoteTableAlignment.Start
                            Box(
                                modifier = Modifier
                                    .width(cellWidth)
                                    .fillMaxHeight()
                                    .background(
                                        if (rowIndex < table.headerRows) textColor.copy(alpha = 0.08f)
                                        else Color.Transparent
                                    )
                                    .border(0.5.dp, textColor.copy(alpha = 0.18f))
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                val state = remember(cell, codeTextColor, codeBackgroundColor) {
                                    RichTextState().also {
                                        it.applyNoteCodeStyle(codeTextColor, codeBackgroundColor)
                                        it.setMarkdown(cell)
                                    }
                                }
                                BasicRichText(
                                    state = state,
                                    style = textStyle.copy(
                                        textAlign = when (alignment) {
                                            NoteTableAlignment.Start -> TextAlign.Start
                                            NoteTableAlignment.Center -> TextAlign.Center
                                            NoteTableAlignment.End -> TextAlign.End
                                        },
                                        fontWeight = if (rowIndex < table.headerRows) {
                                            FontWeight.SemiBold
                                        } else {
                                            textStyle.fontWeight
                                        }
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
        }
    }
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

private fun RichTextState.applyNoteCodeStyle(textColor: Color, backgroundColor: Color) {
    config.codeSpanColor = textColor
    config.codeSpanBackgroundColor = backgroundColor
    config.codeSpanStrokeColor = Color.Transparent
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
    val editorMarkup = if (html.isNoteRichHtmlMarkup()) html else toMarkdown()
    return editorMarkup
        .restoreNoteEditorRepeatedSpaces()
        .restoreNoteImagePlaceholders()
        .normalizeNoteImageDimensionAttributes()
}

internal fun selectNoteStorageMarkup(
    previewMode: Boolean,
    previewMarkup: String,
    editorMarkup: String
): String = if (previewMode) previewMarkup else editorMarkup

private const val NOTE_EDITOR_PRESERVED_SPACE = '\u00A0'
private val NOTE_EDITOR_REPEATED_SPACE_REGEX = Regex(" {2,}")

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
