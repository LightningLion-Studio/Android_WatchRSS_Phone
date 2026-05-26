package com.lightningstudio.watchrss.phone.data.model

object ImportedContentIds {
    const val ROOT_SOURCE_URL = "https://watchrss.local/import-content"
    const val ROOT_SOURCE_TITLE = "导入内容"

    fun txtArticleUrl(contentKey: String): String {
        return "$ROOT_SOURCE_URL/txt/$contentKey"
    }

    fun epubSourceUrl(bookKey: String): String {
        return "$ROOT_SOURCE_URL/epub/$bookKey"
    }

    fun epubChapterUrl(bookKey: String, chapterIndex: Int, chapterKey: String): String {
        val index = chapterIndex.toString().padStart(4, '0')
        return "${epubSourceUrl(bookKey)}/chapter/$index-$chapterKey"
    }

    fun isImportedContentUrl(url: String?): Boolean {
        return url?.trim()?.lowercase()?.startsWith(ROOT_SOURCE_URL) == true
    }
}
