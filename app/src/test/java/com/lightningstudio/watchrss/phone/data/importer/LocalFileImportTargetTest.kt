package com.lightningstudio.watchrss.phone.data.importer

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalFileImportTargetTest {
    @Test fun `md extension routes to notes despite generic mime`() {
        assertEquals(
            LocalFileImportTarget.MARKDOWN_NOTE,
            classifyLocalFileImport("第一导入.md", "application/octet-stream")
        )
        assertEquals(
            LocalFileImportTarget.MARKDOWN_NOTE,
            classifyLocalFileImport("README.MD", "text/plain")
        )
    }

    @Test fun `markdown mime routes extensionless document to notes`() {
        assertEquals(
            LocalFileImportTarget.MARKDOWN_NOTE,
            classifyLocalFileImport("未命名文件", "text/markdown")
        )
    }

    @Test fun `txt extension remains imported content even with wrong markdown mime`() {
        assertEquals(
            LocalFileImportTarget.LOCAL_CONTENT,
            classifyLocalFileImport("小说.txt", "text/markdown")
        )
    }

    @Test fun `ordinary text and epub preserve existing import routing`() {
        assertEquals(
            LocalFileImportTarget.LOCAL_CONTENT,
            classifyLocalFileImport("文章.txt", "text/plain")
        )
        assertEquals(
            LocalFileImportTarget.LOCAL_CONTENT,
            classifyLocalFileImport("book.epub", "application/octet-stream")
        )
    }

    @Test fun `filename becomes imported note title`() {
        assertEquals("第一导入", markdownTitleFromFileName("下载/第一导入.md"))
    }
}
