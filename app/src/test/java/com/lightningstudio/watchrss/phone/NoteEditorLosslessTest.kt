package com.lightningstudio.watchrss.phone

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import com.lightningstudio.watchrss.phone.connection.bluetooth.NoteSyncPayload
import com.lightningstudio.watchrss.phone.data.note.MarkdownNoteCodec
import com.lightningstudio.watchrss.phone.data.note.NoteCloudStateCodec
import com.lightningstudio.watchrss.phone.data.note.NoteEntity
import com.mohamedrejeb.richeditor.model.RichTextState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

internal object NoteLosslessSamples {
    fun userVocabBytes(): ByteArray = checkNotNull(
        NoteLosslessSamples::class.java.getResourceAsStream("/notes/user-sample-vocab.txt")
    ) { "Missing verbatim user vocabulary regression vector" }.use { it.readBytes() }

    val all = listOf(
        "real user vocabulary" to userVocabBytes().toString(Charsets.UTF_8),
        "screenshot vocabulary" to "1. republic 2. postpone3. data 4. charge5. standout\n6. credit 7. intend8. politician 9. responsibility\n10. container11.家庭的，家用的;家务的12.脑力的;有才智\n\n1. deadly; dying; death 2. broaden\n3. experimental4. trial 5. limited; limitless; limitation 6. sufficiently; sufficiency 7. wisdom; wisely 8. conductor; conduction; conductive\n9. illustration10. surrounding; surroundings 11. indication; indicator 12. accidental; accidentally 13. intelligence 14. favourable; favourite 15. electricity; electric; electrical 16. theoretical; theoretically17. atomic18.",
        "discontinuous numbering" to "1. one\n6. six\n10. ten",
        "non one start" to "6. six\n10. ten",
        "no space after dot" to "6.six\n10.ten",
        "multiple lists" to "1. one\n6. six\n\nBetween\n\n4. four\n10. ten",
        "two leading spaces" to "  6. six\n  10. ten",
        "tab" to "\t6. six\nA\tB",
        "nbsp" to "6.\u00a0six\nA\u00a0\u00a0B",
        "crlf" to "6. six\r\n10. ten\r\n",
        "cr" to "6. six\r10. ten\r",
        "lf" to "6. six\n10. ten\n",
        "mixed line endings" to "first\r\nsecond\rthird\nlast",
        "blank lines" to "\n\nfirst\n\n\nlast\n\n",
        "no terminal lf" to "first\nlast",
        "terminal lf" to "first\nlast\n",
        "only blank lines" to "\r\n\r\n",
        "empty body with title" to "",
        "markdown image" to "Before\n![示例](assets/example.jpg)\nAfter",
        "html image" to "<p>Before</p><img src=\"assets/example.jpg\" width=\"320\" height=\"180\" alt=\"示例\"></img>",
        "html" to "<p style=\"color: #d84a4a;\">red <strong>bold</strong></p>",
        "markdown" to "# Heading\n\n**bold** and *italic*\n\n- one\n- two",
        "markdown table" to "| A | B |\n| --- | --- |\n| 6. six | 10. ten |",
        "code fence" to "```text\n6. six\n10. ten\n```",
        "repeated spaces" to "Option  Type  Default\ntext   with    spaces"
    )
}

@RunWith(Parameterized::class)
class NoteEditorLosslessTest(private val name: String, private val original: String) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun samples(): List<Array<String>> = NoteLosslessSamples.all.map { (name, body) -> arrayOf(name, body) }
    }

    @Test fun `six opens previews title only saves and returns preserve original bytes without export`() {
        var stored = original
        repeat(6) {
            val draft = NoteEditorDraft(stored)
            draft.editor.selection = TextRange(draft.editor.annotatedString.length)
            // Title/pin/preview/return do not send a body edit event.
            assertFalse(draft.bodyEdited)
            assertArrayEquals(name, original.toByteArray(), draft.storageMarkup { error("Preview exported") }.toByteArray())
            stored = draft.storageMarkup { error("Metadata-only save exported") }
            assertArrayEquals(name, original.toByteArray(), stored.toByteArray())
        }
    }

    @Test fun `open selects source immediately whenever actual dependency loses bytes`() {
        val editor = RichTextState()
        val projected = original.toNoteEditorMarkup()
        if (original.isNoteRichHtmlMarkup()) editor.setHtml(projected) else editor.setMarkdown(projected)
        val losesBytes = !original.toByteArray().contentEquals(editor.toNoteStorageMarkup().toByteArray())

        assertEquals(name, losesBytes, NoteEditorDraft(original).sourceMode)
        if (name in setOf("real user vocabulary", "screenshot vocabulary", "discontinuous numbering", "non one start", "crlf", "cr", "nbsp")) {
            assertTrue("Known lossy input: $name", NoteEditorDraft(original).sourceMode)
        }
    }

    @Test fun `source edits preserve unmodified bytes including whitespace and line endings`() {
        val draft = NoteEditorDraft(original)
        if (draft.sourceMode) {
            draft.editSource(original) // Selection or IME composition alone is not a body edit.
            assertFalse(draft.bodyEdited)
            draft.editSource(original + "新输入")
            assertTrue(draft.bodyEdited)
            val stored = draft.storageMarkup { error("Source edit exported") }
            assertArrayEquals((original + "新输入").toByteArray(), stored.toByteArray())
            val reopened = NoteEditorDraft(stored, preferredSourceMode = true)
            assertTrue(reopened.sourceMode)
            assertArrayEquals((original + "新输入").toByteArray(), reopened.sourceText.toByteArray())
        }
    }

    @Test fun `six bluetooth and cloud codec round trips preserve original and ancestor bytes`() {
        var note = NoteEntity(
            noteId = "lossless", folderId = null, title = "手填标题", markdown = original, pinned = false,
            plainText = MarkdownNoteCodec.toPlainText(original), contentHash = MarkdownNoteCodec.sha256(original),
            baseContentHash = MarkdownNoteCodec.sha256(original), baseMarkdown = original,
            createdAt = 1L, updatedAt = 1L, modifiedBy = "test"
        )
        repeat(6) {
            note = NoteSyncPayload.decodeNotes(NoteSyncPayload.manifest("test", listOf(note))).single()
            assertArrayEquals(original.toByteArray(), note.markdown.toByteArray())
            note = NoteCloudStateCodec.decode(NoteCloudStateCodec.encode(listOf(note))).single()
            assertArrayEquals(original.toByteArray(), note.markdown.toByteArray())
            assertArrayEquals(original.toByteArray(), note.baseMarkdown.toByteArray())
            assertEquals("手填标题", note.title)
        }
    }
}

class NoteEditorRichEditsTest {
    private fun richDraft(): NoteEditorDraft {
        val original = RichTextState().setText("正文").toNoteStorageMarkup()
        return NoteEditorDraft(original).also { assertFalse(it.sourceMode) }
    }

    @Test fun `real rich text edit exports even before asynchronous observer runs`() {
        val draft = richDraft()
        draft.editor.selection = TextRange(draft.editor.annotatedString.length)
        draft.editor.addTextAfterSelection("新输入")
        assertEquals(draft.editor.annotatedString, NoteEditorDraft(draft.storageMarkup()).editor.annotatedString)
        assertTrue(draft.storageMarkup().contains("新输入"))
    }

    @Test fun `format only edit exports while selection and reader styling do not`() {
        val draft = richDraft()
        draft.editor.selection = TextRange(0, 2)
        draft.editor.config.codeSpanStrokeColor = androidx.compose.ui.graphics.Color.Transparent
        draft.storageMarkup { error("Selection or reader styling exported") }
        draft.editor.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold))
        draft.markBodyEdited()
        val reopened = NoteEditorDraft(draft.storageMarkup())
        reopened.editor.selection = TextRange(0, 2)
        assertEquals(FontWeight.Bold, reopened.editor.currentSpanStyle.fontWeight)
    }
}
