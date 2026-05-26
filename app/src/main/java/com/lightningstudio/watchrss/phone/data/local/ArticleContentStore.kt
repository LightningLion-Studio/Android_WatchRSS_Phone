package com.lightningstudio.watchrss.phone.data.local

import android.content.Context
import java.io.File

interface ArticleContentStore {
    fun markerFor(articleId: String): String
    fun isMarker(value: String): Boolean
    fun storeText(articleId: String, text: String): String
    fun loadText(marker: String): String?
}

class FileArticleContentStore(context: Context) : ArticleContentStore {
    private val directory = File(context.applicationContext.filesDir, "imported_text")

    override fun markerFor(articleId: String): String {
        return "$MARKER_PREFIX${safeFileName(articleId)}.txt"
    }

    override fun isMarker(value: String): Boolean {
        return value.startsWith(MARKER_PREFIX)
    }

    override fun storeText(articleId: String, text: String): String {
        if (!directory.exists()) {
            directory.mkdirs()
        }
        val marker = markerFor(articleId)
        File(directory, marker.removePrefix(MARKER_PREFIX)).writeText(text, Charsets.UTF_8)
        return marker
    }

    override fun loadText(marker: String): String? {
        if (!isMarker(marker)) return null
        val fileName = marker.removePrefix(MARKER_PREFIX)
        return runCatching {
            File(directory, fileName).takeIf { it.isFile }?.readText(Charsets.UTF_8)
        }.getOrNull()
    }

    private fun safeFileName(articleId: String): String {
        return articleId.replace(Regex("""[^A-Za-z0-9._-]"""), "_")
            .take(MAX_FILE_NAME_CHARS)
            .ifBlank { "article" }
    }

    companion object {
        private const val MARKER_PREFIX = "watchrss-local-text:"
        private const val MAX_FILE_NAME_CHARS = 96
    }
}
