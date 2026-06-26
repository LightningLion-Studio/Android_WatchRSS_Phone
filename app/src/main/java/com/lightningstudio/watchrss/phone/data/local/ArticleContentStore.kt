package com.lightningstudio.watchrss.phone.data.local

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.ceil

interface ArticleContentStore {
    fun markerFor(articleId: String): String
    fun isMarker(value: String): Boolean
    fun storeText(articleId: String, text: String): String
    fun loadText(marker: String): String?
    fun textChunkHandle(marker: String, chunkBytes: Int = ARTICLE_TEXT_CHUNK_BYTES): StoredTextChunkHandle?
    fun loadTextChunk(marker: String, chunkIndex: Int, chunkBytes: Int = ARTICLE_TEXT_CHUNK_BYTES): String?
}

data class StoredTextChunkHandle(
    val marker: String,
    val byteLength: Long,
    val chunkBytes: Int,
    val chunkCount: Int
)

class FileArticleContentStore(context: Context) : ArticleContentStore {
    private val directory = File(context.applicationContext.filesDir, "imported_text")

    override fun markerFor(articleId: String): String {
        return "$ARTICLE_CONTENT_MARKER_PREFIX${safeFileName(articleId)}.txt"
    }

    override fun isMarker(value: String): Boolean {
        return isArticleContentMarker(value)
    }

    override fun storeText(articleId: String, text: String): String {
        if (!directory.exists()) {
            directory.mkdirs()
        }
        val marker = markerFor(articleId)
        File(directory, marker.removePrefix(ARTICLE_CONTENT_MARKER_PREFIX)).writeText(text, Charsets.UTF_8)
        return marker
    }

    override fun loadText(marker: String): String? {
        if (!isMarker(marker)) return null
        val fileName = marker.removePrefix(ARTICLE_CONTENT_MARKER_PREFIX)
        return runCatching {
            File(directory, fileName).takeIf { it.isFile }?.readText(Charsets.UTF_8)
        }.getOrNull()
    }

    override fun textChunkHandle(marker: String, chunkBytes: Int): StoredTextChunkHandle? {
        if (chunkBytes <= 0) return null
        val file = fileFor(marker)?.takeIf { it.isFile } ?: return null
        val byteLength = file.length()
        return StoredTextChunkHandle(
            marker = marker,
            byteLength = byteLength,
            chunkBytes = chunkBytes,
            chunkCount = ceil(byteLength.toDouble() / chunkBytes.toDouble()).toInt().coerceAtLeast(1)
        )
    }

    override fun loadTextChunk(marker: String, chunkIndex: Int, chunkBytes: Int): String? {
        if (chunkIndex < 0 || chunkBytes <= 0) return null
        val file = fileFor(marker)?.takeIf { it.isFile } ?: return null
        return runCatching {
            RandomAccessFile(file, "r").use { reader ->
                val byteLength = reader.length()
                val nominalStart = chunkIndex.toLong() * chunkBytes
                if (nominalStart >= byteLength) return@use null
                val nominalEnd = (nominalStart + chunkBytes).coerceAtMost(byteLength)
                val start = reader.adjustUtf8Boundary(nominalStart, byteLength)
                val end = reader.adjustUtf8Boundary(nominalEnd, byteLength)
                if (end <= start) return@use ""
                val bytes = ByteArray((end - start).toInt())
                reader.seek(start)
                reader.readFully(bytes)
                String(bytes, Charsets.UTF_8)
            }
        }.getOrNull()
    }

    private fun RandomAccessFile.adjustUtf8Boundary(requested: Long, byteLength: Long): Long {
        var position = requested.coerceIn(0L, byteLength)
        if (position <= 0L || position >= byteLength) return position
        seek(position)
        var value = read()
        while (position > 0L && value >= 0 && (value and UTF8_CONTINUATION_MASK) == UTF8_CONTINUATION_PREFIX) {
            position -= 1L
            seek(position)
            value = read()
        }
        return position
    }

    private fun fileFor(marker: String): File? {
        if (!isMarker(marker)) return null
        val fileName = marker.removePrefix(ARTICLE_CONTENT_MARKER_PREFIX)
        return File(directory, fileName)
    }

    private fun safeFileName(articleId: String): String {
        return articleId.replace(Regex("""[^A-Za-z0-9._-]"""), "_")
            .take(MAX_FILE_NAME_CHARS)
            .ifBlank { "article" }
    }

    companion object {
        private const val MAX_FILE_NAME_CHARS = 96
        private const val UTF8_CONTINUATION_MASK = 0xC0
        private const val UTF8_CONTINUATION_PREFIX = 0x80
    }
}

fun isArticleContentMarker(value: String?): Boolean {
    return value?.startsWith(ARTICLE_CONTENT_MARKER_PREFIX) == true
}

const val ARTICLE_CONTENT_MARKER_PREFIX = "watchrss-local-text:"
const val ARTICLE_TEXT_CHUNK_BYTES = 2 * 1024
