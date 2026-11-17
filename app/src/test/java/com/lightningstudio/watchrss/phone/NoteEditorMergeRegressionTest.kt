package com.lightningstudio.watchrss.phone

import com.lightningstudio.watchrss.phone.data.note.MarkdownMergeResult
import com.lightningstudio.watchrss.phone.data.note.MarkdownThreeWayMerge
import com.mohamedrejeb.richeditor.model.RichTextState
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import org.junit.Assert.*
import org.junit.Test

/** RVNP-02: legacy controls and the current editor save paths. */
class NoteEditorMergeRegressionTest {
    private val base = "6. alpha\n10. beta\n20. gamma"
    private val left = "6. ALPHA\n10. beta\n20. gamma"
    private val right = "6. alpha\n10. beta\n20. GAMMA"
    private val expected = "6. ALPHA\n10. beta\n20. GAMMA"

    private fun merged(base: String, left: String, right: String): String {
        val result = MarkdownThreeWayMerge.merge(base, left, right)
        assertTrue("Independent paragraph edits must auto-merge: $result", result is MarkdownMergeResult.Merged)
        return (result as MarkdownMergeResult.Merged).markdown
    }
    private fun sourceEdit(text: String) = NoteEditorDraft(base).also {
        assertTrue(it.sourceMode)
        it.editSource(text)
    }.storageMarkup()
    private fun legacy(text: String) = RichTextState().setText(text).toNoteStorageMarkup()
    private fun snapshot(text: String) = NoteVisualDocument.rich(RichTextState().setText(text))

    @Test fun reviewLegacySourceControl() {
        assertEquals(expected, merged(base, left, right))
    }
    @Test fun reviewSourceEditorIndependentEdits() {
        val body = merged(base, sourceEdit(left), sourceEdit(right))
        val reopened = NoteEditorDraft(body)
        assertTrue(reopened.sourceMode)
        assertEquals(expected, reopened.sourceText)
    }
    @Test fun reviewLegacyRichControl() {
        assertEquals(legacy("ALPHA\nbeta\nGAMMA"), merged(legacy("alpha\nbeta\ngamma"),
            legacy("ALPHA\nbeta\ngamma"), legacy("alpha\nbeta\nGAMMA")))
    }
    @Test fun firstEditsOfCanonicalLegacyRichParagraphsStillAutoMerge() {
        val ancestor = legacy("alpha\nbeta\ngamma")
        fun edit(first: Boolean): String {
            val draft = NoteEditorDraft(ancestor)
            assertFalse("Control must exercise legacy rich mode", draft.sourceMode)
            val start = if (first) 0 else draft.editor.annotatedString.text.indexOf("gamma")
            draft.editor.replaceTextRange(TextRange(start, start + 5), if (first) "ALPHA" else "GAMMA")
            return draft.storageMarkup()
        }
        assertEquals("ALPHA\nbeta\nGAMMA", NoteEditorDraft(merged(ancestor, edit(true), edit(false))).editor.toText())
    }
    @Test fun reviewRichEditorIndependentEdits() {
        val body = merged(snapshot("alpha\nbeta\ngamma"), snapshot("ALPHA\nbeta\ngamma"),
            snapshot("alpha\nbeta\nGAMMA"))
        assertEquals(snapshot("ALPHA\nbeta\nGAMMA"), body)
        assertEquals("ALPHA\nbeta\nGAMMA", NoteEditorDraft(body).editor.toText())
    }

    @Test fun firstLegacySourceEditKeepsUntouchedLinesAndUsesNoDocumentAttribute() {
        val body = sourceEdit(left)
        assertEquals(base.lines().drop(1), body.lines().drop(1))
        assertFalse(body.contains("data-watchrss-editor-v1="))
        assertEquals(left, NoteEditorDraft(body).sourceText)
    }

    @Test fun sourceModeRemainsPinnedWhenOneIndependentEditBecomesCanonicalMarkdown() {
        val ancestor = "6. alpha\nbeta\ngamma"
        fun edit(text: String) = NoteEditorDraft(ancestor).also {
            assertTrue(it.sourceMode); it.editSource(text)
        }.storageMarkup()
        val body = merged(ancestor, edit("alpha\nbeta\ngamma"), edit("6. alpha\nbeta\nGAMMA"))
        val reopened = NoteEditorDraft(body, preferredSourceMode = true)
        assertTrue(reopened.sourceMode)
        assertEquals("alpha\nbeta\nGAMMA", reopened.sourceText)
        assertEquals(reopened.sourceText, body)
    }

    @Test fun sourceEditsAndNonAdjacentInsertionStillMerge() {
        val body = merged(base, sourceEdit("prefix\n$base"), sourceEdit(right))
        assertEquals("prefix\n$right", body)
    }

    @Test fun richSpanAndParagraphStyleEditsStayLocalAndBothSurviveMerge() {
        val text = "alpha\nbeta\ngamma"
        val original = snapshot(text)
        val a = RichTextState().setText(text).also {
            it.selection = TextRange(0, 5)
            it.addSpanStyle(SpanStyle(fontWeight = FontWeight.Bold))
        }
        val b = RichTextState().setText(text).also {
            it.selection = TextRange(11, 16)
            it.addParagraphStyle(ParagraphStyle(textAlign = TextAlign.Center))
        }
        val encodedA = NoteVisualDocument.rich(a)
        val encodedB = NoteVisualDocument.rich(b)
        assertEquals(3, original.lines().size)
        assertEquals(original.lines().drop(1), encodedA.lines().drop(1))
        assertEquals(original.lines().take(2), encodedB.lines().take(2))
        val body = merged(original, encodedA, encodedB)
        val opened = NoteEditorDraft(body)
        assertFalse(opened.sourceMode)
        assertEquals(text, opened.editor.toText())
        assertEquals(FontWeight.Bold, opened.editor.getSpanStyle(TextRange(0, 5)).fontWeight)
        assertEquals(body, opened.storageMarkup())
    }

    @Test fun overlappingNativeParagraphEditsRemainConflicts() {
        assertEquals(MarkdownMergeResult.Conflict, MarkdownThreeWayMerge.merge(snapshot("alpha\nbeta\ngamma"),
            snapshot("ONE\nbeta\ngamma"), snapshot("TWO\nbeta\ngamma")))
    }

    @Test fun sourceEditingInternalMarkupKeepsItsLiteralSourceAppearance() {
        val literal = snapshot("Visible native paragraph")
        val draft = NoteEditorDraft(base)
        assertTrue(draft.sourceMode)
        draft.editSource(literal)
        var stored = draft.storageMarkup()
        assertEquals(literal, stored)
        repeat(6) {
            val opened = NoteEditorDraft(stored, preferredSourceMode = draft.sourceMode)
            assertTrue(opened.sourceMode)
            assertEquals(literal, opened.sourceText)
            stored = opened.storageMarkup()
        }
    }
}
