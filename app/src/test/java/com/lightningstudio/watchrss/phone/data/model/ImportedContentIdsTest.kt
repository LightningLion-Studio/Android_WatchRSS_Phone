package com.lightningstudio.watchrss.phone.data.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportedContentIdsTest {
    @Test
    fun importedTextSource_matchesOnlyRootSource() {
        assertTrue(ImportedContentIds.isImportedTextSourceUrl(ImportedContentIds.ROOT_SOURCE_URL))
        assertFalse(ImportedContentIds.isImportedTextSourceUrl(ImportedContentIds.txtArticleUrl("txt-1")))
        assertFalse(ImportedContentIds.isImportedTextSourceUrl(ImportedContentIds.epubSourceUrl("book")))
        assertFalse(ImportedContentIds.isImportedTextSourceUrl("${ImportedContentIds.ROOT_SOURCE_URL}/epub/book"))
        assertFalse(ImportedContentIds.isImportedTextSourceUrl("https://example.com/feed.xml"))
    }
}
