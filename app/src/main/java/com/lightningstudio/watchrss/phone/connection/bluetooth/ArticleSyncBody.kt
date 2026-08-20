package com.lightningstudio.watchrss.phone.connection.bluetooth

import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.min

data class ArticleBodyMetadata(
    val bodyHash: String,
    val bodyByteCount: Long,
    val chunkSize: Int,
    val chunkHashes: List<String>,
    val metadataHash: String
)

data class ArticleBodyRequest(
    val articleId: String,
    val bodyHash: String,
    val chunkIndexes: List<Int>,
    val metadataOnly: Boolean = false
)

data class ArticleBodyChunk(
    val index: Int,
    val hash: String,
    val bytes: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ArticleBodyChunk
        return index == other.index &&
            hash == other.hash &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = index
        result = 31 * result + hash.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}

data class ChunkedArticlePayload(
    val article: PhoneArticleEntity,
    val bodyHash: String,
    val bodyByteCount: Long,
    val chunkSize: Int,
    val chunkHashes: List<String>,
    val chunks: List<ArticleBodyChunk>,
    val metadataOnly: Boolean = false
)

object ArticleSyncBody {
    const val CHUNK_SIZE_BYTES = 128 * 1024
    private const val BODY_ENCODING_VERSION = 3

    fun metadataFor(article: PhoneArticleEntity): ArticleBodyMetadata {
        val bodyBytes = encodeBody(article.contentHtml, article.contentText)
        return metadataFor(article, bodyBytes)
    }

    fun cachedMetadataFor(article: PhoneArticleEntity): ArticleBodyMetadata? {
        val expectedMetadataHash = metadataHashFor(article)
        if (article.syncMetadataHash != expectedMetadataHash) return null
        val chunkHashes = article.syncChunkHashesJson.toStringList()
        val metadata = ArticleBodyMetadata(
            bodyHash = article.syncBodyHash,
            bodyByteCount = article.syncBodyByteCount,
            chunkSize = article.syncChunkSize,
            chunkHashes = chunkHashes,
            metadataHash = article.syncMetadataHash
        )
        return metadata.takeIf { it.isCurrentFor(article) }
    }

    fun currentMetadataFor(article: PhoneArticleEntity): ArticleBodyMetadata =
        cachedMetadataFor(article) ?: metadataFor(article)

    fun payloadForRequest(
        article: PhoneArticleEntity,
        request: ArticleBodyRequest,
        cachedMetadata: ArticleBodyMetadata? = cachedMetadataFor(article)
    ): ChunkedArticlePayload {
        cachedMetadata
            ?.takeIf { request.bodyHash.isBlank() || request.bodyHash == it.bodyHash }
            ?.let { metadata ->
                if (request.metadataOnly) {
                    return metadata.toPayload(article, emptyList(), metadataOnly = true)
                }
                runCatching {
                    metadata.toPayload(
                        article = article,
                        chunks = chunksForRequestWithMetadata(article, request, metadata),
                        metadataOnly = false
                    )
                }.getOrNull()
            }
            ?.let { return it }

        val bodyBytes = encodeBody(article.contentHtml, article.contentText)
        val metadata = metadataFor(article, bodyBytes)
        val chunks = if (request.metadataOnly) {
            emptyList()
        } else {
            chunksForRequestWithMetadata(bodyBytes, request, metadata)
        }
        return metadata.toPayload(article, chunks, metadataOnly = request.metadataOnly)
    }

    private fun metadataFor(
        article: PhoneArticleEntity,
        bodyBytes: ByteArray
    ): ArticleBodyMetadata {
        val chunkHashes = chunkHashesFor(bodyBytes)
        return ArticleBodyMetadata(
            bodyHash = sha256(bodyBytes),
            bodyByteCount = bodyBytes.size.toLong(),
            chunkSize = CHUNK_SIZE_BYTES,
            chunkHashes = chunkHashes,
            metadataHash = metadataHashFor(article)
        )
    }

    fun metadataHashFor(article: PhoneArticleEntity): String {
        val json = JSONObject().apply {
            put("bodyEncodingVersion", BODY_ENCODING_VERSION)
            put("articleId", article.articleId)
            put("sourceDeviceId", article.sourceDeviceId)
            put("url", article.url)
            put("title", article.title)
            put("siteName", article.siteName)
            put("excerpt", article.excerpt)
            put("imageUrl", article.imageUrl)
            put("importedAt", article.importedAt)
            put("updatedAt", article.updatedAt)
            put("independentSaved", article.independentSaved)
            put("independentChangedAt", article.independentChangedAt)
            put("independentSortOrder", article.independentSortOrder)
            put("rssSourceUrl", article.rssSourceUrl)
            put("rssSourceTitle", article.rssSourceTitle)
            put("favoriteSaved", article.favoriteSaved)
            put("favoriteChangedAt", article.favoriteChangedAt)
            put("favoriteSortOrder", article.favoriteSortOrder)
            put("watchLaterSaved", article.watchLaterSaved)
            put("watchLaterChangedAt", article.watchLaterChangedAt)
            put("watchLaterSortOrder", article.watchLaterSortOrder)
            put("deleted", article.deleted)
            put("deletedAt", article.deletedAt)
        }
        return sha256(json.toString().toByteArray(Charsets.UTF_8))
    }

    fun chunksForRequest(
        article: PhoneArticleEntity,
        request: ArticleBodyRequest,
        cachedMetadata: ArticleBodyMetadata? = cachedMetadataFor(article)
    ): List<ArticleBodyChunk> =
        payloadForRequest(article, request, cachedMetadata).chunks

    private fun chunksForRequestWithMetadata(
        article: PhoneArticleEntity,
        request: ArticleBodyRequest,
        metadata: ArticleBodyMetadata
    ): List<ArticleBodyChunk> {
        val bodyBytes = encodeBody(article.contentHtml, article.contentText)
        return chunksForRequestWithMetadata(bodyBytes, request, metadata)
    }

    private fun chunksForRequestWithMetadata(
        bodyBytes: ByteArray,
        request: ArticleBodyRequest,
        metadata: ArticleBodyMetadata
    ): List<ArticleBodyChunk> {
        val chunkSize = metadata.chunkSize.takeIf { it > 0 } ?: CHUNK_SIZE_BYTES
        require(bodyBytes.size.toLong() == metadata.bodyByteCount) {
            "同步正文缓存大小不匹配：expected=${metadata.bodyByteCount} actual=${bodyBytes.size}"
        }
        val chunkCount = chunkCountFor(bodyBytes, chunkSize)
        require(chunkCount == metadata.chunkHashes.size) {
            "同步正文缓存分块数不匹配：expected=${metadata.chunkHashes.size} actual=$chunkCount"
        }
        metadata.chunkHashes.forEachIndexed { index, expectedHash ->
            val start = index * chunkSize
            val end = min(start + chunkSize, bodyBytes.size)
            require(sha256(bodyBytes, start, end - start) == expectedHash) {
                "同步正文缓存分块校验失败：${request.articleId}#$index"
            }
        }
        val indexes = request.chunkIndexes
            .distinct()
            .filter { it in 0 until chunkCount }
        return indexes.map { index ->
            val start = index * chunkSize
            val end = min(start + chunkSize, bodyBytes.size)
            val bytes = bodyBytes.copyOfRange(start, end)
            ArticleBodyChunk(
                index = index,
                hash = metadata.chunkHashes[index],
                bytes = bytes
            )
        }
    }

    fun rebuildBody(
        localArticle: PhoneArticleEntity?,
        payload: ChunkedArticlePayload
    ): Pair<String?, String> {
        if (localArticle != null && localArticle.syncBodyHash == payload.bodyHash) {
            return localArticle.contentHtml to localArticle.contentText
        }
        val localBodyBytes = localArticle
            ?.let { encodeBody(it.contentHtml, it.contentText) }
        val sentByIndex = payload.chunks.associateBy { it.index }
        val chunkSize = payload.chunkSize.takeIf { it > 0 } ?: CHUNK_SIZE_BYTES
        val expectedSize = payload.bodyByteCount
            .coerceIn(0L, Int.MAX_VALUE.toLong())
            .toInt()
        val rebuilt = ByteArrayOutputStream(expectedSize)
        payload.chunkHashes.forEachIndexed { index, expectedHash ->
            val sent = sentByIndex[index]
            when {
                sent != null -> {
                    require(sent.hash == expectedHash && sha256(sent.bytes) == expectedHash) {
                        "同步正文分块校验失败：${payload.article.articleId}#$index"
                    }
                    rebuilt.write(sent.bytes)
                }
                localBodyBytes != null && index < chunkCountFor(localBodyBytes, chunkSize) -> {
                    val start = index * chunkSize
                    val end = min(start + chunkSize, localBodyBytes.size)
                    val length = end - start
                    require(sha256(localBodyBytes, start, length) == expectedHash) {
                        "同步正文缺少分块：${payload.article.articleId}#$index"
                    }
                    rebuilt.write(localBodyBytes, start, length)
                }
                else -> error("同步正文缺少分块：${payload.article.articleId}#$index")
            }
        }
        val bodyBytes = rebuilt.toByteArray()
        require(sha256(bodyBytes) == payload.bodyHash) {
            "同步正文整体校验失败：${payload.article.articleId}"
        }
        return decodeBody(bodyBytes)
    }

    fun encodeChunkData(bytes: ByteArray): String =
        Base64.getEncoder().encodeToString(bytes)

    fun decodeChunkData(value: String): ByteArray =
        Base64.getDecoder().decode(value)

    private fun encodeBody(contentHtml: String?, contentText: String): ByteArray {
        val rawBody = JSONObject().apply {
            put("contentHtml", contentHtml)
            put("contentText", contentText)
        }.toString().toByteArray(Charsets.UTF_8)
        return gzip(rawBody)
    }

    private fun decodeBody(bytes: ByteArray): Pair<String?, String> {
        val input = runCatching<InputStream> {
            GZIPInputStream(ByteArrayInputStream(bytes))
        }.getOrElse {
            ByteArrayInputStream(bytes)
        }
        return input.use { stream ->
            decodeBodyJson(
                InputStreamReader(
                    LimitedInputStream(stream, MAX_DECOMPRESSED_TEXT_BYTES),
                    Charsets.UTF_8
                )
            )
        }
    }

    private fun decodeBodyJson(reader: Reader): Pair<String?, String> {
        val cursor = BodyJsonCursor(reader)
        var contentHtml: String? = null
        var contentText = ""
        cursor.expectObjectStart()
        var firstField = true
        while (true) {
            val marker = cursor.nextNonWhitespace()
            if (marker == -1 || marker.toChar() == '}') break
            if (!firstField) {
                require(marker.toChar() == ',') { "同步正文JSON格式错误" }
            } else {
                cursor.unread(marker)
            }
            val name = cursor.readName()
            cursor.expect(':')
            val value = cursor.readNullableString()
            when (name) {
                "contentHtml" -> contentHtml = value?.ifBlank { null }
                "contentText" -> contentText = value.orEmpty()
            }
            firstField = false
        }
        return contentHtml to contentText
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { gzip ->
            gzip.write(bytes)
        }
        return out.toByteArray()
    }

    private class BodyJsonCursor(
        private val reader: Reader
    ) {
        private var buffered: Int = NO_BUFFER

        fun expectObjectStart() {
            expect('{')
        }

        fun expect(expected: Char) {
            val actual = nextNonWhitespace()
            require(actual == expected.code) { "同步正文JSON格式错误" }
        }

        fun readName(): String {
            val marker = nextNonWhitespace()
            require(marker == '"'.code) { "同步正文JSON字段格式错误" }
            return readStringBody()
        }

        fun readNullableString(): String? {
            return when (val marker = nextNonWhitespace()) {
                '"'.code -> readStringBody()
                'n'.code -> {
                    expectLiteral("ull")
                    null
                }
                else -> error("同步正文JSON值格式错误：$marker")
            }
        }

        fun nextNonWhitespace(): Int {
            while (true) {
                val char = read()
                if (char == -1 || !char.toChar().isWhitespace()) return char
            }
        }

        fun unread(char: Int) {
            buffered = char
        }

        private fun readStringBody(): String {
            val builder = StringBuilder()
            while (true) {
                val char = read()
                require(char != -1) { "同步正文JSON字符串未结束" }
                when (char.toChar()) {
                    '"' -> return builder.toString()
                    '\\' -> builder.append(readEscapedChar())
                    else -> builder.append(char.toChar())
                }
            }
        }

        private fun readEscapedChar(): Char {
            val escaped = read()
            require(escaped != -1) { "同步正文JSON转义未结束" }
            return when (escaped.toChar()) {
                '"' -> '"'
                '\\' -> '\\'
                '/' -> '/'
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> readUnicodeEscape()
                else -> error("同步正文JSON转义格式错误：${escaped.toChar()}")
            }
        }

        private fun readUnicodeEscape(): Char {
            var value = 0
            repeat(4) {
                val char = read()
                require(char != -1) { "同步正文JSON Unicode转义未结束" }
                value = (value shl 4) + char.toChar().digitToInt(16)
            }
            return value.toChar()
        }

        private fun expectLiteral(value: String) {
            value.forEach { expected ->
                require(read() == expected.code) { "同步正文JSON字面量格式错误" }
            }
        }

        private fun read(): Int {
            if (buffered != NO_BUFFER) {
                val char = buffered
                buffered = NO_BUFFER
                return char
            }
            return reader.read()
        }

        private companion object {
            const val NO_BUFFER = -2
        }
    }

    private class LimitedInputStream(
        private val upstream: InputStream,
        private val maxBytes: Int
    ) : InputStream() {
        private var totalBytes = 0

        override fun read(): Int {
            val value = upstream.read()
            if (value >= 0) count(1)
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = upstream.read(buffer, offset, length)
            if (read > 0) count(read)
            return read
        }

        private fun count(bytes: Int) {
            totalBytes += bytes
            if (totalBytes > maxBytes) {
                throw IllegalArgumentException("解压内容过大")
            }
        }
    }

    private fun chunkHashesFor(bytes: ByteArray): List<String> {
        val chunkCount = chunkCountFor(bytes)
        return buildList(chunkCount) {
            repeat(chunkCount) { index ->
                val start = index * CHUNK_SIZE_BYTES
                val end = min(start + CHUNK_SIZE_BYTES, bytes.size)
                add(sha256(bytes, start, end - start))
            }
        }
    }

    private fun chunkCountFor(bytes: ByteArray, chunkSize: Int = CHUNK_SIZE_BYTES): Int {
        if (bytes.isEmpty()) return 1
        return ((bytes.size - 1) / chunkSize) + 1
    }

    private fun ArticleBodyMetadata.toPayload(
        article: PhoneArticleEntity,
        chunks: List<ArticleBodyChunk>,
        metadataOnly: Boolean
    ): ChunkedArticlePayload =
        ChunkedArticlePayload(
            article = article,
            bodyHash = bodyHash,
            bodyByteCount = bodyByteCount,
            chunkSize = chunkSize,
            chunkHashes = chunkHashes,
            chunks = chunks,
            metadataOnly = metadataOnly
        )

    private fun ArticleBodyMetadata.isCurrentFor(article: PhoneArticleEntity): Boolean {
        return metadataHash == metadataHashFor(article) &&
            bodyHash.isNotBlank() &&
            bodyByteCount > 0L &&
            chunkSize > 0 &&
            chunkHashes.isNotEmpty()
    }

    private fun String.toStringList(): List<String> {
        if (isBlank()) return emptyList()
        val array = runCatching { JSONArray(this) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                array.optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun sha256(bytes: ByteArray): String {
        return sha256(bytes, 0, bytes.size)
    }

    private fun sha256(bytes: ByteArray, offset: Int, length: Int): String {
        val digest = MessageDigest.getInstance("SHA-256").apply {
            update(bytes, offset, length)
        }.digest()
        return digest.joinToString("") { "%02x".format(it) }
    }

    private const val MAX_DECOMPRESSED_TEXT_BYTES = 32 * 1024 * 1024
}
