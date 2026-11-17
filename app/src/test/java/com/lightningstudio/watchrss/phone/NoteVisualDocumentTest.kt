@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.lightningstudio.watchrss.phone

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.mohamedrejeb.richeditor.model.RichTextState
import com.lightningstudio.watchrss.phone.connection.bluetooth.NoteAssetSyncPayload
import com.lightningstudio.watchrss.phone.data.note.MarkdownNoteCodec
import org.junit.Assert.*
import org.junit.Test

class NoteVisualDocumentTest {
    private fun assertProjectionSurvives(editor: RichTextState) {
        NoteVisualDocument.focusProjection(editor, focused = false)
        val original = editor.annotatedString
        var stored = NoteVisualDocument.rich(editor)
        repeat(6) {
            val opened = NoteEditorDraft(stored)
            assertFalse(opened.sourceMode)
            assertEquals("text", original.text, opened.editor.annotatedString.text)
            assertEquals("rendered span styles", original.spanStyles, opened.editor.annotatedString.spanStyles)
            assertEquals("rendered paragraph styles", original.paragraphStyles, opened.editor.annotatedString.paragraphStyles)
            for (index in original.text.indices) {
                val range = TextRange(index, index + 1)
                assertEquals("span at $index", editor.getSpanStyle(range), opened.editor.getSpanStyle(range))
                assertEquals("paragraph at $index", editor.getParagraphStyle(range), opened.editor.getParagraphStyle(range))
            }
            assertEquals(stored, opened.storageMarkup { error("An untouched visual document was exported") })
            stored = opened.storageMarkup()
        }
    }

    @Test fun exactTextWhitespaceBlankParagraphsAndEmStylesSurvive() {
        val editor = RichTextState().setText("Heading  \n\nbody \t\u00a0\n\n")
        editor.addSpanStyle(SpanStyle(fontSize = 2.em, fontWeight = FontWeight.Bold,
            fontStyle = FontStyle.Italic), TextRange(0, 7))
        assertProjectionSurvives(editor)
    }

    @Test fun replacingASpaceWithAParagraphBreakIsAnActualEditEvenWhenFlattenedTextMatches() {
        val draft = NoteEditorDraft("first last")
        assertFalse(draft.sourceMode)
        val flattened = draft.editor.annotatedString.text
        draft.editor.selection = TextRange(5, 6)
        draft.editor.onTextFieldValueChange(TextFieldValue("first\nlast", TextRange(6)))
        assertEquals(flattened, draft.editor.annotatedString.text)
        assertEquals("first\nlast", draft.editor.toText())
        assertEquals("first\nlast", NoteEditorDraft(draft.storageMarkup()).editor.toText())
    }

    @Test fun colorsFontUnitsDecorationsLocalesAndParagraphGeometrySurvive() {
        val editor = RichTextState().setText("Styled text\nNext line")
        editor.selection = TextRange(0, 6)
        editor.addSpanStyle(SpanStyle(color = Color(0xFFA04578), background = Color(0x33224455),
            fontSize = 1.17.em, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
            fontStyle = FontStyle.Italic, fontSynthesis = FontSynthesis.All, letterSpacing = 0.5.sp,
            textDecoration = TextDecoration.Underline + TextDecoration.LineThrough,
            fontFeatureSettings = "kern", localeList = LocaleList("zh-CN,en-US"),
            baselineShift = BaselineShift.Superscript, textGeometricTransform = TextGeometricTransform(1.1f, 0.2f),
            shadow = Shadow(Color(0xFF123456), Offset(1f, 2f), 3f)))
        editor.addParagraphStyle(ParagraphStyle(textAlign = TextAlign.Right, lineHeight = 26.sp,
            textIndent = TextIndent(3.sp, 8.sp)))
        assertProjectionSurvives(editor)
    }

    @Test fun linksCodeAndImagePlaceholdersSurviveWithoutBecomingMarkdownLists() {
        val editor = RichTextState().setText("6. literal\nLink Code\n")
        editor.addLinkToTextRange("https://example.invalid/", TextRange(11, 15))
        editor.selection = TextRange(16, 20)
        editor.toggleCodeSpan()
        editor.selection = TextRange(editor.annotatedString.length)
        editor.insertImagePlaceholder("assets/example.jpg", "示例", 320f, 180f)
        assertProjectionSurvives(editor)
    }

    @Test fun aSourceDocumentStaysSourceEvenWhenTheNewTextIsCanonicalMarkdown() {
        for (text in listOf("ordinary text", "# Heading", "1. one\n2. two", "\r\n\r", "")) {
            val original = text
            var stored = original
            repeat(6) {
                val opened = NoteEditorDraft(stored, preferredSourceMode = true)
                assertTrue(opened.sourceMode)
                assertEquals(text, opened.sourceText)
                stored = opened.storageMarkup { error("Source document exported") }
                assertEquals(original, stored)
            }
        }
    }

    @Test fun sourceEditsRetainImagesAndPlainTextProjection() {
        for (source in listOf("Before\n![示例](assets/example.jpg)\nAfter",
            "<p>one two three four</p><img src=\"assets/example.jpg\" alt=\"示例\">")) {
            val draft = NoteEditorDraft("6. numbered")
            draft.editSource(source)
            val stored = draft.storageMarkup()
            assertEquals(source, stored)
            assertEquals(setOf("example.jpg"), NoteAssetSyncPayload.referencedStorageKeys(stored))
            assertEquals(parseNotePreviewBlocks(source), parseNotePreviewBlocks(stored))
        }
        NoteLosslessSamples.all.forEach { (name, source) ->
            val draft = NoteEditorDraft("6. numbered")
            draft.editSource(source)
            assertEquals(name, MarkdownNoteCodec.toPlainText(source),
                MarkdownNoteCodec.toPlainText(draft.storageMarkup()))
        }
    }

    @Test fun externallyChangedFallbackNeverLoadsTheStaleNativeSnapshot() {
        val original = NoteVisualDocument.rich(RichTextState().setText("old visible"))
        val changed = original.replace("<p>old visible</p>", "<p>updated visible</p>")
        assertNotEquals(original, changed)
        val opened = NoteEditorDraft(changed)
        assertFalse(opened.sourceMode)
        assertTrue(opened.editor.toText().contains("updated visible"))
        assertFalse(opened.editor.toText().contains("old visible"))
        assertEquals(changed, opened.storageMarkup())
    }

    @Test fun malformedSnapshotKeepsItsVisibleFallbackAndNeverOpensAnEmptyEditor() {
        val original = "<div data-watchrss-editor-v1=\"invalid\">Visible fallback</div>"
        val opened = NoteEditorDraft(original)
        assertTrue(opened.editor.toText().contains("Visible fallback"))
        assertEquals(original, opened.storageMarkup())
    }

    @Test fun focusProjectionRetainsFormattingPopupSelection() {
        val editor = RichTextState().setText("Selected text")
        val selection = TextRange(0, 8)
        editor.selection = selection
        editor.addSpanStyle(SpanStyle(background = Color.Yellow))
        NoteVisualDocument.focusProjection(editor, false)
        assertEquals(selection, editor.selection)
        assertEquals(Color.Yellow, editor.getSpanStyle(selection).background)
        NoteVisualDocument.focusProjection(editor, true)
        assertEquals(selection, editor.selection)
    }

    @Test fun collapsingASelectionCannotLeaveAStaleTransparentHighlightMask() {
        val editor = RichTextState().setText("Highlighted")
        editor.selection = TextRange(0, 11)
        editor.addSpanStyle(SpanStyle(background = Color.Yellow))
        editor.selection = TextRange(11)
        assertTrue(editor.annotatedString.spanStyles.any { it.item.background == Color.Transparent })
        NoteVisualDocument.focusProjection(editor, false)
        assertFalse(editor.annotatedString.spanStyles.any { it.item.background == Color.Transparent })
        assertEquals(Color.Yellow, editor.getSpanStyle(TextRange(0, 11)).background)
    }

    @Test fun focusProjectionLeavesPendingCursorStylesAndImeCompositionAlone() {
        val editor = RichTextState().setText("text")
        editor.selection = TextRange(4)
        editor.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold))
        NoteVisualDocument.focusProjection(editor, false)
        NoteVisualDocument.focusProjection(editor, true)
        editor.onTextFieldValueChange(TextFieldValue("textB", TextRange(5)))
        assertEquals(FontWeight.Bold, editor.getSpanStyle(TextRange(4, 5)).fontWeight)

        editor.onTextFieldValueChange(TextFieldValue("textB", TextRange(0, 4), TextRange(0, 4)))
        val composing = editor.textFieldValue
        val projection = editor.annotatedString
        NoteVisualDocument.focusProjection(editor, false)
        NoteVisualDocument.focusProjection(editor, true)
        assertEquals(composing, editor.textFieldValue)
        assertEquals(projection, editor.annotatedString)
    }
}
