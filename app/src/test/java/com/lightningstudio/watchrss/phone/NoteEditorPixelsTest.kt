package com.lightningstudio.watchrss.phone

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import com.lightningstudio.watchrss.phone.connection.bluetooth.NoteSyncPayload
import com.lightningstudio.watchrss.phone.data.note.NoteCloudStateCodec
import com.lightningstudio.watchrss.phone.data.note.MarkdownNoteCodec
import com.lightningstudio.watchrss.phone.data.note.MarkdownNoteArchive
import com.lightningstudio.watchrss.phone.data.note.MarkdownArchiveEntry
import com.lightningstudio.watchrss.phone.data.note.NoteEntity
import com.lightningstudio.watchrss.phone.data.note.NoteRepository
import com.lightningstudio.watchrss.phone.data.note.NoteRepositoryTest
import com.lightningstudio.watchrss.phone.ui.theme.WatchRssPhoneTheme
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Actual NoteEditor -> actual Save button/NoteRepository -> a new composition and new draft.
 * The oracle is the live editor image before saving, never a re-render of exported markup.
 * Focus/caret/selection decorations are cleared equally; viewport, density, preset and scroll offsets match.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS-w400dp-h900dp-notnight-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NoteEditorPixelsTest {
    @get:Rule val ui = createComposeRule()
    private val dao = NoteRepositoryTest.FakeNoteDao()
    private val repository = NoteRepository(dao, "pixel-test", now = { 123L })
    private var visible by mutableStateOf(false)
    private var generation by mutableStateOf(0)
    private var note by mutableStateOf<NoteEntity?>(null)
    private var scroll = ScrollState(0)
    private var syncCycles = 0
    private lateinit var focus: FocusManager
    private val output = File("build/outputs/notes-pixels").apply { mkdirs() }

    private fun entity(markdown: String) = NoteEntity("pixel-note", null, "像素测试", markdown,
        MarkdownNoteCodec.toPlainText(markdown), MarkdownNoteCodec.sha256(markdown),
        MarkdownNoteCodec.sha256(markdown), markdown, false, 1L, 1L, "pixel-test")

    private fun mount(markdown: String) {
        note = entity(markdown)
        visible = true
        ui.setContent {
            WatchRssPhoneTheme(dynamicColor = false) {
                focus = LocalFocusManager.current
                val scope = rememberCoroutineScope()
                Box(Modifier.size(400.dp, 800.dp)) {
                    if (visible) key(generation) {
                        NoteEditor(note, repository, onSaved = { visible = false }, scope, syncCloud = {
                            val outgoing = repository.allNotes()
                            val cloud = NoteCloudStateCodec.decode(NoteCloudStateCodec.encode(outgoing))
                            val wire = JSONObject(NoteSyncPayload.manifest("pixel-test", cloud).toString())
                            NoteSyncPayload.decodeNotes(wire).forEach { repository.applyRemote(it, "pixel-peer") }
                            syncCycles++
                        }, editorScrollState = scroll)
                    }
                }
            }
        }
        ui.waitForIdle()
    }

    private fun open(markdown: String) {
        ui.runOnIdle {
            dao.notes.clear()
            dao.editorModes.clear()
            note = entity(markdown)
            scroll = ScrollState(0)
            generation++
            visible = true
        }
        ui.waitForIdle()
    }

    private fun pixels(label: String): Bitmap {
        val file = File(output, "$label.png")
        ui.onNodeWithTag("note_editor_body").captureRoboImage(file,
            roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
        return checkNotNull(BitmapFactory.decodeFile(file.absolutePath))
    }

    private data class Frames(val maxScroll: Int, val offsets: List<Int>, val bitmaps: List<Bitmap>)

    private fun captureDocument(label: String): Frames {
        ui.runOnIdle { focus.clearFocus(force = true) }
        ui.waitForIdle()
        val maximum = ui.runOnIdle { scroll.maxValue }
        val height = ui.onNodeWithTag("note_editor_body").fetchSemanticsNode().boundsInRoot.height.toInt()
        val offsets = ((0..maximum step (height - 32).coerceAtLeast(1)).toList() + maximum).distinct()
        val bitmaps = offsets.mapIndexed { index, offset ->
            ui.runOnIdle { runBlocking { scroll.scrollTo(offset) } }
            ui.waitForIdle()
            pixels("${label}_page$index")
        }
        return Frames(maximum, offsets, bitmaps)
    }

    private fun saveAndReopen() {
        val previousSyncs = syncCycles
        ui.onNodeWithText("保存").performClick()
        ui.waitUntil { !visible }
        assertEquals("Both codecs and local apply must finish", previousSyncs + 1, syncCycles)
        ui.runOnIdle {
            note = dao.notes.getValue("pixel-note")
            scroll = ScrollState(0)
            generation++
            visible = true
        }
        ui.waitForIdle()
    }

    private fun pixelArray(bitmap: Bitmap): IntArray = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    private fun assertSamePixels(label: String, before: Frames, after: Frames) {
        assertEquals("$label full document scroll extent", before.maxScroll, after.maxScroll)
        assertEquals("$label all document viewports", before.offsets, after.offsets)
        before.bitmaps.zip(after.bitmaps).forEachIndexed { index, (first, second) ->
            assertEquals("$label width", first.width, second.width)
            assertEquals("$label height", first.height, second.height)
            val a = pixelArray(first)
            val b = pixelArray(second)
            val changed = a.indices.count { a[it] != b[it] }
            File(output, "comparisons.jsonl").appendText(JSONObject().put("case", label).put("page", index)
                .put("width", first.width).put("height", first.height).put("maxScroll", before.maxScroll)
                .put("comparedPixels", a.size).put("differentPixels", changed).toString() + "\n")
            if (changed != 0) {
                val diff = Bitmap.createBitmap(first.width, first.height, Bitmap.Config.ARGB_8888)
                diff.setPixels(IntArray(a.size) { if (a[it] != b[it]) android.graphics.Color.RED else a[it] },
                    0, first.width, 0, 0, first.width, first.height)
                File(output, "${label}_page${index}_diff.png").outputStream().use { diff.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            assertEquals("$label page $index must have exactly zero changed ARGB pixels", 0, changed)
        }
    }

    private fun sixRoundTrips(label: String): Frames {
        val before = captureDocument("${label}_edited")
        repeat(6) { round ->
            saveAndReopen()
            val after = captureDocument("${label}_reopened${round + 1}")
            assertSamePixels("${label}_round${round + 1}", before, after)
        }
        return before
    }

    private fun input() = ui.onNodeWithTag("note_body_input")
    private fun editableText(): String = input().fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text
    private fun typeAtEnd(text: String) {
        input().performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        input().performTextInputSelection(TextRange(editableText().length))
        input().performTextInput(text)
        ui.waitForIdle()
    }
    private fun select(start: Int, end: Int) {
        input().performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        input().performTextInputSelection(TextRange(start, end))
    }
    private fun tool(label: String) {
        ui.onNodeWithTag("note_formatting_toolbar").performScrollToNode(hasContentDescription(label))
        ui.onNodeWithContentDescription(label).performClick()
        ui.waitForIdle()
    }

    @Test fun allRcaAndRealUserVectorsMatchEveryPixelAcrossSixReopensAndAllScrollPositions() {
        mount("")
        NoteLosslessSamples.all.forEachIndexed { index, (_, markdown) ->
            open(markdown)
            sixRoundTrips("vector_$index")
            assertEquals("Untouched source must also stay authoritative", markdown, dao.notes.getValue("pixel-note").markdown)
        }
    }

    @Test @Config(qualifiers = "zh-rCN-w360dp-h720dp-notnight-xhdpi")
    fun userVocabularyAlsoMatchesAtNarrowWidthHighDensityAndLargerFont() {
        RuntimeEnvironment.setFontScale(1.3f)
        mount(NoteLosslessSamples.userVocabBytes().toString(Charsets.UTF_8))
        val frames = sixRoundTrips("vocabulary_narrow_large_font")
        assertTrue("Long text must exercise scrolling", frames.maxScroll > 0)
        assertTrue("All text, not just the first screen, must be captured", frames.bitmaps.size > 1)
        open("")
        typeAtEnd("Scaled heading")
        select(0, editableText().length)
        tool("1 级标题")
        sixRoundTrips("heading_narrow_large_font")
    }

    @Test fun newlyTypedNumberedLinesKeepTheirVisibleNumbersAndIndentation() {
        mount("")
        typeAtEnd("6. six\n10. ten")
        assertTrue(editableText().contains("6. six"))
        sixRoundTrips("typed_literal_numbers")
    }

    @Test fun NA11PastedHeadingLinkNumbersAndHardbreakKeepTheirActualEditorPixels() {
        mount("")
        for ((name, text) in listOf("heading" to "# a", "link" to "[a](https://example.invalid)",
            "numbers" to "1. a\n6. b", "hardbreak" to "a  \nb")) {
            open("")
            typeAtEnd(text)
            assertEquals(text.replace('\n', ' '), editableText())
            sixRoundTrips("NA11_paste_$name")
        }
    }

    @Test fun NA03FileRoundTripsPreserveSourceAndStyledEditorPixelsAndManualTitle() {
        mount("")
        for ((name, source) in listOf("vocabulary" to NoteLosslessSamples.userVocabBytes().toString(Charsets.UTF_8),
            "crlf" to "x\r\ny", "rich" to "")) {
            open(source)
            if (name == "rich") {
                typeAtEnd("# a\nBody")
                select(0, 3)
                tool("1 级标题")
            }
            ui.onNode(hasSetTextAction() and hasText("像素测试")).performTextReplacement("Manual title")
            val before = captureDocument("NA03_${name}_edited")
            repeat(6) { round ->
                saveAndReopen()
                val saved = dao.notes.getValue("pixel-note")
                val archive = MarkdownNoteArchive.write(listOf(MarkdownArchiveEntry("notes/pixel-note.md",
                    MarkdownNoteCodec.export(saved).toByteArray(Charsets.UTF_8))))
                val bytes = MarkdownNoteArchive.read(archive).single().bytes
                val imported = runBlocking { repository.importMarkdown(bytes.toString(Charsets.UTF_8), "Filename fallback") }
                assertEquals("Manual title", imported.title)
                assertArrayEquals(saved.markdown.toByteArray(Charsets.UTF_8), imported.markdown.toByteArray(Charsets.UTF_8))
                ui.runOnIdle {
                    note = imported
                    scroll = ScrollState(0)
                    generation++
                }
                ui.waitForIdle()
                assertSamePixels("NA03_${name}_round${round + 1}", before,
                    captureDocument("NA03_${name}_reopened${round + 1}"))
            }
        }
    }

    @Test fun enterContinuesTheListAndDeletingAnItemPreservesTheVisibleRemainingList() {
        mount("")
        typeAtEnd("1."); typeAtEnd(" "); typeAtEnd("first"); typeAtEnd("\n")
        typeAtEnd("second"); typeAtEnd("\n"); typeAtEnd("third")
        assertTrue("IME Enter must actually continue the list", editableText().contains("3. "))
        sixRoundTrips("continued_numbered_list")
        val text = editableText()
        select(text.indexOf("2. "), text.indexOf("3. "))
        input().performTextInput("")
        ui.waitForIdle()
        assertFalse(editableText().contains("second"))
        assertTrue(editableText().contains("third"))
        sixRoundTrips("deleted_numbered_item")
        val first = editableText().indexOf("first")
        select(first, first + "first".length)
        tool("粗体")
        sixRoundTrips("bold_numbered_item")
    }

    @Test fun unorderedListsAndTheirIndentationStayIdenticalAfterEditing() {
        mount("")
        typeAtEnd("alpha\nbeta")
        select(0, editableText().length)
        tool("无序列表")
        sixRoundTrips("unordered_list")
        val start = editableText().indexOf("alpha")
        select(start, start + 5)
        tool("斜体")
        sixRoundTrips("unordered_italic")
    }

    @Test fun persistedImagePlaceholderKeepsItsActualEditorAppearance() {
        val editor = com.mohamedrejeb.richeditor.model.RichTextState().setText("Before\nAfter")
        editor.selection = TextRange("Before".length)
        editor.insertImagePlaceholder("assets/example.jpg", "示例", 320f, 180f)
        mount(NoteVisualDocument.rich(editor))
        sixRoundTrips("rich_image_placeholder")
    }

    @Test fun paragraphHeadingsBoldAndItalicPreserveTheirActualRenderedStyles() {
        mount("")
        typeAtEnd("Heading\nBody")
        for ((label, description) in listOf("h1" to "1 级标题", "h2" to "2 级标题", "h3" to "3 级标题",
            "bold" to "粗体", "italic" to "斜体", "paragraph" to "正文")) {
            select(0, "Heading".length)
            tool(description)
            sixRoundTrips("format_$label")
        }
    }

    @Test fun colorsHighlightCodeDecorationsAndAlignmentPreservePixels() {
        mount("")
        typeAtEnd("Colored text\nSecond paragraph")
        select(0, "Colored text".length)
        tool("字体颜色")
        ui.onNodeWithText("珊瑚红").performClick()
        sixRoundTrips("coral_text")
        for ((name, description) in listOf("highlight" to "高亮", "underline" to "下划线", "strike" to "删除线",
            "code" to "行内代码", "center" to "居中", "right" to "右对齐", "left" to "左对齐", "justify" to "两端对齐")) {
            select(0, "Colored text".length)
            tool(description)
            sixRoundTrips("extra_$name")
        }
    }

    @Test fun editingSourceIntoRoundTrippableMarkdownKeepsTheSameSourceAppearance() {
        mount("6. six\n10. ten")
        input().performTextReplacement("# Heading")
        sixRoundTrips("source_heading_literal")
        input().performTextReplacement("ordinary text")
        sixRoundTrips("source_ordinary_literal")
        input().performTextReplacement("# Header\n  6. line  \n\n10.line\tend\u00a0")
        sixRoundTrips("source_spaces_blank_lines_tabs")
    }

    @Test fun titleAndPreviewDoNotChangeTheUserVocabularyEditorOrStoredBody() {
        val original = NoteLosslessSamples.userVocabBytes().toString(Charsets.UTF_8)
        mount(original)
        val before = captureDocument("title_preview_original")
        for (description in listOf("正文", "1 级标题", "2 级标题", "3 级标题", "粗体", "斜体")) {
            ui.onNodeWithTag("note_formatting_toolbar").performScrollToNode(hasContentDescription(description))
            ui.onNodeWithContentDescription(description).assertIsNotEnabled()
        }
        ui.onNode(hasSetTextAction() and hasText("像素测试")).performTextReplacement("只改标题")
        tool("预览")
        ui.onNodeWithText("编辑").performClick()
        assertSamePixels("title_preview_return", before, captureDocument("title_preview_return"))
        sixRoundTrips("title_preview_save")
        assertEquals(original, dao.notes.getValue("pixel-note").markdown)
        assertEquals("只改标题", dao.notes.getValue("pixel-note").title)
    }

    @Test fun richBlankLinesAndTrailingSpacesStayIdenticalAfterEditing() {
        mount("")
        typeAtEnd("first  \n\nlast \t\u00a0")
        sixRoundTrips("rich_blank_lines_and_whitespace")
    }

    @Test fun replacingASpaceWithAParagraphBreakAndJoiningItAgainPreservesPixels() {
        mount("first last")
        assertFalse(NoteEditorDraft("first last").sourceMode)
        select(5, 6)
        input().performTextInput("\n")
        sixRoundTrips("replace_space_with_paragraph")
        assertEquals("first\nlast", NoteEditorDraft(dao.notes.getValue("pixel-note").markdown).editor.toText())
        select(5, 6)
        input().performTextInput("")
        input().performTextInput(" ")
        sixRoundTrips("join_paragraph_with_space")
        assertEquals("first last", NoteEditorDraft(dao.notes.getValue("pixel-note").markdown).editor.toText())
    }

    @Test fun collapsedCursorFormattingStillAppliesToNewInputAfterFocusChanges() {
        mount("")
        typeAtEnd("plain ")
        tool("粗体")
        typeAtEnd("bold")
        typeAtEnd("\n")
        typeAtEnd("continued")
        sixRoundTrips("cursor_bold_typing")
        val saved = NoteEditorDraft(dao.notes.getValue("pixel-note").markdown)
        assertEquals(androidx.compose.ui.text.font.FontWeight.Bold,
            saved.editor.getSpanStyle(TextRange(6, 10)).fontWeight)
        typeAtEnd(" next")
        sixRoundTrips("reopened_cursor_typing")
    }

    @Test fun pixelOracleDetectsActualInputAndActualFormattingChanges() {
        mount("")
        val empty = captureDocument("oracle_empty")
        typeAtEnd("Visible actual text")
        val typed = captureDocument("oracle_typed")
        assertFalse("Input must change the captured pixels", pixelArray(empty.bitmaps.first()).contentEquals(pixelArray(typed.bitmaps.first())))
        select(0, "Visible actual text".length)
        tool("粗体")
        val formatted = captureDocument("oracle_formatted")
        assertFalse("Formatting must change the captured glyphs", pixelArray(typed.bitmaps.first()).contentEquals(pixelArray(formatted.bitmaps.first())))
        sixRoundTrips("oracle_bold_round_trip")
    }
}
