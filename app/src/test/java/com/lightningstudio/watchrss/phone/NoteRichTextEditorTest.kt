package com.lightningstudio.watchrss.phone

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import com.mohamedrejeb.richeditor.model.RichTextState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteRichTextEditorTest {
    @Test
    fun `table template is standard markdown with requested dimensions`() {
        val table = markdownTable(rows = 4, columns = 2)

        assertTrue(table.contains("| 表头1 | 表头2 |"))
        assertTrue(table.contains("| --- | --- |"))
        assertEquals(5, table.lineSequence().filter(String::isNotBlank).count())
        val editor = RichTextState().setText("")
        editor.addTextAfterSelection(table)
        assertTrue(editor.toNoteStorageMarkup().contains("| --- | --- |"))
    }

    @Test
    fun `inserted local image stays in portable note markup`() {
        val editor = RichTextState().setText("")
        editor.insertImage("assets/example.jpg", "示例")

        val saved = editor.toNoteStorageMarkup()

        assertTrue(saved.contains("<img"))
        assertTrue(saved.contains("assets/example.jpg"))
        assertTrue(RichTextState().setHtml(saved).toHtml().contains("assets/example.jpg"))
    }

    @Test
    fun `single newline exits the active heading`() {
        assertTrue(
            shouldExitHeadingAfterEdit(
                previousText = "标题",
                currentText = "标题\n",
                selection = TextRange(3),
                currentStyle = SpanStyle(fontSize = 2.em, fontWeight = FontWeight.Bold)
            )
        )
    }

    @Test
    fun `newline keeps normal body formatting unchanged`() {
        assertFalse(
            shouldExitHeadingAfterEdit(
                previousText = "正文",
                currentText = "正文\n",
                selection = TextRange(3),
                currentStyle = SpanStyle(fontWeight = FontWeight.Bold)
            )
        )
    }

    @Test
    fun `collapsed cursor formatting applies to subsequently inserted text`() {
        val editor = RichTextState().setText("")
        editor.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold))
        editor.toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic))
        editor.addSpanStyle(SpanStyle(color = Color(0xFFD84A4A)))
        editor.addSpanStyle(SpanStyle(background = Color(0x33D84A4A)))

        editor.addTextAfterSelection("格式")
        val saved = editor.toNoteStorageMarkup()
        val restored = RichTextState().setHtml(saved)
        restored.selection = TextRange(0, 2)

        val style = restored.currentSpanStyle
        assertEquals(FontWeight.Bold, style.fontWeight)
        assertEquals(FontStyle.Italic, style.fontStyle)
        assertEquals(Color(0xFFD84A4A), style.color)
        assertEquals(Color(0x33D84A4A), style.background)
    }

    @Test
    fun `plain rich text stays markdown`() {
        val editor = RichTextState().setMarkdown("# 标题\n\n- 第一项\n- 第二项")

        val saved = editor.toNoteStorageMarkup()

        assertTrue(saved.startsWith("# 标题"))
        assertTrue(saved.contains("- 第一项"))
    }

    @Test
    fun `font color is stored as html and restored`() {
        val editor = RichTextState().setMarkdown("彩色文字")
        editor.selection = TextRange(0, 4)
        editor.addSpanStyle(SpanStyle(color = Color(0xFFD84A4A)))

        val saved = editor.toNoteStorageMarkup()
        val restored = RichTextState().setHtml(saved)

        assertTrue(saved.contains("color:"))
        assertEquals("彩色文字", restored.toText())
        assertTrue(restored.toHtml().contains("color:"))
    }

    @Test
    fun `hex color accepts rgb and rejects malformed values`() {
        assertEquals(Color(0xFF2879B8), parseHexColor("#2879B8"))
        assertEquals(Color(0xFFD84A4A), parseHexColor("d84a4a"))
        assertNull(parseHexColor("#12345"))
        assertNull(parseHexColor("#GG0000"))
    }
}
