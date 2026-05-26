package com.lightningstudio.watchrss.phone.data.model

object ImportedContentIds {
    const val ROOT_SOURCE_URL = "https://watchrss.local/import-content"
    const val ROOT_SOURCE_TITLE = "导入内容"
    const val EPUB_SOURCE_ROOT_URL = "https://watchrss.local/import-epub"

    fun txtArticleUrl(contentKey: String): String {
        return "$ROOT_SOURCE_URL/txt/$contentKey"
    }

    fun epubSourceUrl(bookKey: String): String {
        return "$EPUB_SOURCE_ROOT_URL/$bookKey"
    }

    fun epubChapterUrl(bookKey: String, chapterIndex: Int, chapterKey: String): String {
        val index = chapterIndex.toString().padStart(4, '0')
        return "${epubSourceUrl(bookKey)}/chapter/$index-$chapterKey"
    }

    fun isImportedContentUrl(url: String?): Boolean {
        val normalized = url?.trim()?.lowercase() ?: return false
        return normalized.startsWith(ROOT_SOURCE_URL) ||
            normalized.startsWith(EPUB_SOURCE_ROOT_URL)
    }

    fun isImportedEpubSourceUrl(url: String?): Boolean {
        val normalized = url?.trim()?.lowercase() ?: return false
        return normalized.startsWith(EPUB_SOURCE_ROOT_URL) ||
            normalized.startsWith("$ROOT_SOURCE_URL/epub/")
    }
}
