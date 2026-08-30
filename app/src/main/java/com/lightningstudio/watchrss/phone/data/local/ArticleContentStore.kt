package com.lightningstudio.watchrss.phone.data.local

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.math.ceil

interface ArticleContentStore {
    fun markerFor(articleId: String): String
    fun isMarker(value: String): Boolean
    fun storeText(articleId: String, text: String): String
    fun loadText(marker: String): String?
    fun textChunkHandle(marker: String, chunkBytes: Int = ARTICLE_TEXT_CHUNK_BYTES): StoredTextChunkHandle?
    fun loadTextChunk(marker: String, chunkIndex: Int, chunkBytes: Int = ARTICLE_TEXT_CHUNK_BYTES): String?
    fun prune(retainedMarkers: Set<String>) = Unit
}

data class StoredTextChunkHandle(
    val marker: String,
    val byteLength: Long,
    val chunkBytes: Int,
    val chunkCount: Int
)

class FileArticleContentStore internal constructor(
    private val directory: File
) : ArticleContentStore {
    constructor(context: Context) : this(File(context.applicationContext.filesDir, "imported_text"))

    override fun markerFor(articleId: String): String {
        return "$ARTICLE_CONTENT_MARKER_PREFIX${safeFileName(articleId)}.txt"
    }

    override fun isMarker(value: String): Boolean {
        return isArticleContentMarker(value)
    }

    override fun storeText(articleId: String, text: String): String {
        check(directory.isDirectory || directory.mkdirs()) { "无法创建正文存储目录" }
        val encoded = text.toByteArray(Charsets.UTF_8)
        val contentHash = encoded.sha256Hex()
        val marker = buildV2Marker(articleId, contentHash)
        val target = checkNotNull(fileFor(marker)) { "正文存储路径非法" }
        if (target.matchesContent(encoded.size.toLong(), contentHash)) return marker

        val temporary = Files.createTempFile(directory.toPath(), ".write-", ".part").toFile()
        try {
            FileOutputStream(temporary).use { output ->
                output.write(encoded)
                output.fd.sync()
            }
            check(temporary.length() == encoded.size.toLong()) { "正文临时文件校验失败" }
            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
            check(target.matchesContent(encoded.size.toLong(), contentHash)) { "正文文件校验失败" }
        } finally {
            runCatching { temporary.delete() }
        }
        return marker
    }

    override fun loadText(marker: String): String? {
        return runCatching {
            fileFor(marker)?.takeIf { it.isFile }?.readText(Charsets.UTF_8)
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

    override fun prune(retainedMarkers: Set<String>) {
        if (!directory.isDirectory) return
        val retainedFileNames = retainedMarkers
            .asSequence()
            .mapNotNull(::articleContentMarkerFileName)
            .toSet()
        directory.listFiles()?.forEach { file ->
            val managedFile = isArticleContentFileName(file.name) ||
                (file.name.startsWith(".write-") && file.name.endsWith(".part"))
            if (file.isFile && managedFile && file.name !in retainedFileNames) {
                runCatching { file.delete() }
            }
        }
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
        val fileName = articleContentMarkerFileName(marker) ?: return null
        val canonicalDirectory = runCatching { directory.canonicalFile }.getOrNull() ?: return null
        val canonicalTarget = runCatching { File(directory, fileName).canonicalFile }.getOrNull() ?: return null
        return canonicalTarget.takeIf { it.parentFile == canonicalDirectory }
    }

    private fun buildV2Marker(articleId: String, contentHash: String): String {
        val keyHash = articleId.toByteArray(Charsets.UTF_8).sha256Hex()
        return "$ARTICLE_CONTENT_MARKER_PREFIX$V2_FILE_NAME_PREFIX$keyHash-$contentHash.txt"
    }

    private fun File.matchesContent(expectedLength: Long, expectedHash: String): Boolean {
        if (!isFile || length() != expectedLength) return false
        return runCatching {
            inputStream().buffered().use { input ->
                val digest = MessageDigest.getInstance(SHA_256)
                val buffer = ByteArray(FILE_HASH_BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > 0) digest.update(buffer, 0, read)
                }
                digest.digest().toHex() == expectedHash
            }
        }.getOrDefault(false)
    }

    private fun safeFileName(articleId: String): String {
        return articleId.replace(Regex("""[^A-Za-z0-9._-]"""), "_")
            .take(MAX_FILE_NAME_CHARS)
            .ifBlank { "article" }
    }

    companion object {
        private const val MAX_FILE_NAME_CHARS = 96
        private const val FILE_HASH_BUFFER_BYTES = 8 * 1024
        private const val SHA_256 = "SHA-256"
        private const val UTF8_CONTINUATION_MASK = 0xC0
        private const val UTF8_CONTINUATION_PREFIX = 0x80
    }
}

fun isArticleContentMarker(value: String?): Boolean {
    return articleContentMarkerFileName(value) != null
}

private fun articleContentMarkerFileName(value: String?): String? {
    if (value == null || !value.startsWith(ARTICLE_CONTENT_MARKER_PREFIX)) return null
    val fileName = value.removePrefix(ARTICLE_CONTENT_MARKER_PREFIX)
    if ('/' in fileName || '\\' in fileName || ".." in fileName) return null
    return fileName.takeIf(::isArticleContentFileName)
}

private fun isArticleContentFileName(fileName: String): Boolean {
    return V2_FILE_NAME_REGEX.matches(fileName) || LEGACY_FILE_NAME_REGEX.matches(fileName)
}

private fun ByteArray.sha256Hex(): String = MessageDigest.getInstance("SHA-256").digest(this).toHex()

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }

private const val V2_FILE_NAME_PREFIX = "v2-"
private val V2_FILE_NAME_REGEX = Regex("""v2-[0-9a-f]{64}-[0-9a-f]{64}\.txt""")
private val LEGACY_FILE_NAME_REGEX = Regex("""[A-Za-z0-9._-]{1,96}\.txt""")

const val ARTICLE_CONTENT_MARKER_PREFIX = "watchrss-local-text:"
const val ARTICLE_TEXT_CHUNK_BYTES = 2 * 1024
