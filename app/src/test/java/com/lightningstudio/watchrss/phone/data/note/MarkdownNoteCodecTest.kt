package com.lightningstudio.watchrss.phone.data.note

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownNoteCodecTest {
    @Test fun `exported note restores its stable identity`() {
        val note = NoteEntity("n-1", "work", "标题", "# 标题\n\n正文", "标题\n\n正文", "h", "h", "# 标题\n\n正文", false, 1, 2, "phone")
        val parsed = MarkdownNoteCodec.parse(MarkdownNoteCodec.export(note))
        assertEquals("n-1", parsed.noteId)
        assertEquals("work", parsed.folderId)
        assertEquals("标题", parsed.title)
        assertEquals(note.markdown, parsed.markdown)
    }

    @Test fun `ordinary markdown remains portable and creates a new note`() {
        val parsed = MarkdownNoteCodec.parse("# 外部笔记\n")
        assertNull(parsed.noteId)
        assertEquals("# 外部笔记\n", parsed.markdown)
    }

    @Test fun `NA-03 export parse preserves title and every body byte for six rounds`() {
        val bodies = com.lightningstudio.watchrss.phone.NoteLosslessSamples.all.map { it.second } +
            listOf("x\r\ny", "x\ry\r", "", "\n\n", "---\r\nunknown: value\r\n---\r\nbody")
        for (title in listOf("Manual title", "手填：标题 👨‍👩‍👧‍👦", "a\"b\\c\nnext\rline\tend", "  preserved  ")) {
            for (body in bodies) {
                var note = NoteEntity("stable", "folder", title, body, "", "h", "h", body, false, 1, 2, "phone")
                repeat(6) {
                    val parsed = MarkdownNoteCodec.parse(MarkdownNoteCodec.export(note))
                    assertEquals(title, parsed.title)
                    org.junit.Assert.assertArrayEquals(body.toByteArray(Charsets.UTF_8), parsed.markdown.toByteArray(Charsets.UTF_8))
                    assertEquals("stable", parsed.noteId)
                    assertEquals("folder", parsed.folderId)
                    note = note.copy(title = parsed.title!!, markdown = parsed.markdown)
                }
            }
        }
    }

    @Test fun `NA-03 envelope line endings never change body line endings`() {
        val body = "\r\nx\ry\n\nlast  "
        for (ending in listOf("\n", "\r\n", "\r")) {
            val header = listOf("---", "watchrss-note: 1", "id: n", "title: Legacy title", "---", "").joinToString(ending)
            val parsed = MarkdownNoteCodec.parse(header + body)
            assertEquals("Legacy title", parsed.title)
            assertEquals(body, parsed.markdown)
        }
        val external = "---\r\nother: value\r\n---\r\nx\ry"
        assertEquals(external, MarkdownNoteCodec.parse(external).markdown)
        assertEquals("plain\r\ntext\r", MarkdownNoteCodec.parse("plain\r\ntext\r").markdown)
        assertEquals("", MarkdownNoteCodec.parse("---\nwatchrss-note: 1\n---").markdown)
    }

    @Test fun `watch projection preserves image meaning without markdown syntax`() {
        assertEquals("标题\n[图片：猫]\n链接", MarkdownNoteCodec.toPlainText("# 标题\n![猫](assets/cat.jpg)\n[链接](https://example.com)"))
    }

    @Test fun `rich html projection removes style tags and keeps list text`() {
        val richHtml = """
            <p><span style="color: rgba(216, 74, 74, 1);">红色正文</span></p>
            <ol><li><b>第一项</b></li><li>第二项</li></ol>
        """.trimIndent()

        assertEquals("红色正文\n第一项\n第二项", MarkdownNoteCodec.toPlainText(richHtml))
    }

    @Test fun `plain markdown projection keeps intentional paragraph spacing`() {
        assertEquals("标题\n\n正文", MarkdownNoteCodec.toPlainText("# 标题\n\n正文"))
    }

    @Test fun `diff3 merges independent line edits`() {
        val result = MarkdownThreeWayMerge.merge("one\ntwo\nthree", "ONE\ntwo\nthree", "one\ntwo\nTHREE")
        assertTrue(result is MarkdownMergeResult.Merged)
        assertEquals("ONE\ntwo\nTHREE", (result as MarkdownMergeResult.Merged).markdown)
    }
}
