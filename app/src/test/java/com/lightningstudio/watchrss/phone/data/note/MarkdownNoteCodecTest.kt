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
        assertEquals("# 标题\n\n正文\n", parsed.markdown)
    }

    @Test fun `ordinary markdown remains portable and creates a new note`() {
        val parsed = MarkdownNoteCodec.parse("# 外部笔记\n")
        assertNull(parsed.noteId)
        assertEquals("# 外部笔记\n", parsed.markdown)
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
