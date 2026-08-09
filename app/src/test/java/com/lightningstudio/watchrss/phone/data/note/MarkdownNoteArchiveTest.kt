package com.lightningstudio.watchrss.phone.data.note

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MarkdownNoteArchiveTest {
    @Test fun `zip round trip retains relative markdown assets`() {
        val archive = MarkdownNoteArchive.write(listOf(
            MarkdownArchiveEntry("work/todo.md", "# Todo".toByteArray()),
            MarkdownArchiveEntry("work/assets/cat.jpg", byteArrayOf(1, 2, 3))
        ))
        assertEquals(listOf("work/assets/cat.jpg", "work/todo.md"), MarkdownNoteArchive.read(archive).map { it.path })
    }

    @Test fun `rejects traversal paths`() {
        assertThrows(IllegalArgumentException::class.java) { MarkdownNoteArchive.safePath("../secret.md") }
    }

    @Test fun `directory import accepts markdown at root and arbitrary subdirectories`() {
        val entries = listOf(
            MarkdownArchiveEntry("第一导入.md", byteArrayOf(1)),
            MarkdownArchiveEntry("work/todo.MD", byteArrayOf(2)),
            MarkdownArchiveEntry("notes/assets/cat.jpg", byteArrayOf(3)),
            MarkdownArchiveEntry("readme.txt", byteArrayOf(4))
        )

        assertEquals(
            listOf("第一导入.md", "work/todo.MD"),
            markdownNoteEntries(entries).map { it.path }
        )
    }
}
