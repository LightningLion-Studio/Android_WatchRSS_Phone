package com.lightningstudio.watchrss.phone.data.importer

enum class LocalFileImportTarget {
    MARKDOWN_NOTE,
    LOCAL_CONTENT,
    UNSUPPORTED
}

/** File extensions take precedence because Android document providers often report generic MIME types. */
fun classifyLocalFileImport(fileName: String, mimeType: String?): LocalFileImportTarget {
    val lowerName = fileName.trim().lowercase()
    val lowerMime = mimeType.orEmpty().trim().lowercase()
    return when {
        isMarkdownFileName(lowerName) -> LocalFileImportTarget.MARKDOWN_NOTE
        lowerName.endsWith(".txt") -> LocalFileImportTarget.LOCAL_CONTENT
        lowerName.endsWith(".epub") -> LocalFileImportTarget.LOCAL_CONTENT
        lowerMime == "text/markdown" || lowerMime == "text/x-markdown" -> {
            LocalFileImportTarget.MARKDOWN_NOTE
        }
        lowerMime.startsWith("text/") -> LocalFileImportTarget.LOCAL_CONTENT
        lowerMime == "application/epub+zip" -> LocalFileImportTarget.LOCAL_CONTENT
        else -> LocalFileImportTarget.UNSUPPORTED
    }
}

fun isMarkdownFileName(fileName: String): Boolean {
    val lowerName = fileName.trim().lowercase()
    return lowerName.endsWith(".md") || lowerName.endsWith(".markdown")
}

fun markdownTitleFromFileName(fileName: String): String =
    fileName.substringAfterLast('/').substringBeforeLast('.').trim()
