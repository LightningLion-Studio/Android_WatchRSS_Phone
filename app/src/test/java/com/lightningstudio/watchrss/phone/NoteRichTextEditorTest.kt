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
    fun `aligned table spaces survive rich editor round trip`() {
        val markdown = """
            Option  Type  Default  Description

            `outer_padding`  int  `32`  Legacy fallback for all four edge paddings.
            `max_preview_scale`  float  `0.95`  Maximum preview scale.
        """.trimIndent()
        val editor = RichTextState().setMarkdown(markdown.toNoteEditorMarkup())

        val saved = editor.toNoteStorageMarkup()

        assertTrue(saved.contains("Option  Type  Default  Description"))
        assertTrue(saved.contains("`outer_padding`  int  `32`  Legacy fallback"))
        assertFalse(saved.contains("`outer_padding` int `32` Legacy fallback"))
    }

    @Test
    fun `preview markup remains authoritative when saving without editing`() {
        val original = "| Option | Type |\n| --- | --- |\n| `outer_padding` | int |"

        val saved = selectNoteStorageMarkup(
            previewMode = true,
            previewMarkup = original,
            editorMarkup = "Option Type outer_padding int"
        )

        assertEquals(original, saved)
    }

    @Test
    fun `hymission style markdown table becomes a preview table`() {
        val markdown = """
            Before

            | Lua function | Legacy dispatcher | Arguments | Behavior | Why it exists |
            | --- | :--- | :---: | ---: | --- |
            | `hl.plugin.hymission.toggle(args?)` | `hymission:toggle` | Optional scope | Opens overview | Entry point |

            After
        """.trimIndent()

        val blocks = parseNotePreviewBlocks(markdown)
        val table = blocks.filterIsInstance<NotePreviewBlock.Table>().single().table

        assertEquals(5, table.columnCount)
        assertEquals(2, table.rows.size)
        assertEquals(NoteTableAlignment.Start, table.alignments[0])
        assertEquals(NoteTableAlignment.Center, table.alignments[2])
        assertEquals(NoteTableAlignment.End, table.alignments[3])
        assertEquals("Before", (blocks.first() as NotePreviewBlock.RichText).markup.trim())
        assertEquals("After", (blocks.last() as NotePreviewBlock.RichText).markup.trim())
        assertTrue(markdown.containsMarkdownTable())
    }

    @Test
    fun `tab separated rows become a preview table`() {
        val tsv = "Name\tType\tDescription\nlayout_engine\tstring\tGeometry solver\nniri_mode\tbool\tOverflow mode"

        val table = parseNotePreviewBlocks(tsv)
            .filterIsInstance<NotePreviewBlock.Table>()
            .single()
            .table

        assertEquals(3, table.columnCount)
        assertEquals(3, table.rows.size)
        assertEquals("layout_engine", table.rows[1][0])
        assertTrue(tsv.containsMarkdownTable())
    }

    @Test
    fun `pipe and tab content inside code fences stays code`() {
        val markdown = """
            ```text
            A\tB\tC
            | Header | Value |
            | --- | --- |
            ```
        """.trimIndent()

        val blocks = parseNotePreviewBlocks(markdown)

        assertEquals(1, blocks.size)
        assertTrue(blocks.single() is NotePreviewBlock.RichText)
        assertFalse(markdown.containsMarkdownTable())
    }

    @Test
    fun `inserted local image stays in portable note markup`() {
        val editor = RichTextState().setText("")
        editor.insertImagePlaceholder("assets/example.jpg", "示例", widthSp = 320f, heightSp = 180f)

        val saved = editor.toNoteStorageMarkup()

        assertTrue(saved.contains("![示例]"))
        assertTrue(saved.contains("assets/example.jpg"))
        val reloaded = RichTextState().setMarkdown(saved.toNoteEditorMarkup())
        assertTrue(reloaded.annotatedString.text.contains("🖼 示例"))
        assertFalse(reloaded.annotatedString.text.contains('\uFFFD'))
    }

    @Test
    fun `inserting image preserves every following paragraph`() {
        val original = "Before image\nFirst line after\nSecond line after"
        val editor = RichTextState().setText(original)
        editor.selection = TextRange("Before image\n".length)

        editor.insertImagePlaceholder("assets/example.jpg", "示例", widthSp = 320f, heightSp = 180f)

        val saved = editor.toNoteStorageMarkup()
        assertTrue(saved.contains("Before image"))
        assertTrue(saved.contains("First line after"))
        assertTrue(saved.contains("Second line after"))
        assertFalse(saved.contains("econd line afte<"))
    }

    @Test
    fun `portable markdown image becomes an external preview block`() {
        val blocks = parseNotePreviewBlocks("Before\n![示例](assets/example.jpg)\nAfter")

        val image = blocks.filterIsInstance<NotePreviewBlock.Image>().single()
        assertEquals("assets/example.jpg", image.path)
        assertEquals("示例", image.description)
        assertTrue((blocks.first() as NotePreviewBlock.RichText).markup.contains("Before"))
        assertTrue((blocks.last() as NotePreviewBlock.RichText).markup.contains("After"))
        assertTrue("Before\n![示例](assets/example.jpg)\nAfter".shouldOpenInNotePreview())
    }

    @Test
    fun `html preview image keeps persisted dimensions`() {
        val blocks = parseNotePreviewBlocks(
            "<p>Before</p><img src=\"assets/example.jpg\" width=\"320\" height=\"180\" alt=\"示例\"></img>"
        )

        val image = blocks.filterIsInstance<NotePreviewBlock.Image>().single()
        assertEquals(320f, image.widthSp)
        assertEquals(180f, image.heightSp)
    }

    @Test
    fun `plain text stays editable while persisted images reopen in preview`() {
        assertFalse("普通正文".shouldOpenInNotePreview())
        assertTrue("<p>前文</p><img src=\"assets/example.jpg\" alt=\"示例\"><p>后文</p>".shouldOpenInNotePreview())
    }

    @Test
    fun `image display size keeps aspect ratio within editor bounds`() {
        assertEquals(
            320f to 180f,
            fitNoteImageDisplaySize(1920, 1080, maxWidthSp = 320f, maxHeightSp = 480f)
        )
        assertEquals(
            270f to 480f,
            fitNoteImageDisplaySize(1080, 1920, maxWidthSp = 320f, maxHeightSp = 480f)
        )
    }

    @Test
    fun `image dimensions are stored as integers for library reload`() {
        val html = "<p><img src=\"assets/example.jpg\" width=\"320.0\" height=\"180.0\"></img></p>"

        val normalized = html.normalizeNoteImageDimensionAttributes()

        assertTrue(normalized.contains("width=\"320\""))
        assertTrue(normalized.contains("height=\"180\""))
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
