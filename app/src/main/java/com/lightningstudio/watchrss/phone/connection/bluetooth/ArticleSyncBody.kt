package com.lightningstudio.watchrss.phone.connection.bluetooth

import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Reader
import java.io.Writer
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
    private const val MAX_DECOMPRESSED_TEXT_BYTES = 32 * 1024 * 1024
    private const val JSON_WRITE_BUFFER_CHARS = 16 * 1024
    private val HEX_DIGITS = "0123456789abcdef".toCharArray()

    fun metadataFor(article: PhoneArticleEntity): ArticleBodyMetadata {
        val output = BodyChunkOutputStream()
        writeEncodedBody(article.contentHtml, article.contentText, output)
        return output.metadata(metadataHashFor(article))
    }

    fun cachedMetadataFor(article: PhoneArticleEntity): ArticleBodyMetadata? {
        val metadata = cachedMetadataSnapshotFor(article) ?: return null
        return runCatching {
            validateCachedBodyMetadata(article, metadata)
            metadata
        }.getOrNull()
    }

    /**
     * Reads the persisted metadata without encoding the body. The returned snapshot is deliberately
     * unverified: callers must pass it to [payloadForRequest], which streams the current body and
     * validates the whole-body and per-chunk hashes before any cached chunk is sent.
     */
    internal fun cachedMetadataSnapshotFor(article: PhoneArticleEntity): ArticleBodyMetadata? {
        val metadata = ArticleBodyMetadata(
            bodyHash = article.syncBodyHash,
            bodyByteCount = article.syncBodyByteCount,
            chunkSize = article.syncChunkSize,
            chunkHashes = article.syncChunkHashesJson.toStringList(),
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
            ?.takeIf {
                it.metadataHash == metadataHashFor(article) &&
                    (
                        (
                            request.metadataOnly &&
                                request.bodyHash.isNotBlank() &&
                                request.bodyHash == it.bodyHash
                            ) ||
                            (
                                request.bodyHash.isNotBlank() &&
                                    !request.metadataOnly &&
                                    request.bodyHash == it.bodyHash &&
                                    request.chunkIndexes.isNotEmpty()
                                )
                        )
            }
            ?.let { metadata ->
                runCatching {
                    metadata.toPayload(
                        article = article,
                        chunks = if (request.metadataOnly) {
                            emptyList()
                        } else {
                            chunksForRequestWithCachedMetadata(article, request, metadata)
                        },
                        metadataOnly = request.metadataOnly
                    ).also {
                        if (request.metadataOnly) {
                            validateCachedBodyMetadata(article, metadata)
                        }
                    }
                }.getOrNull()
            }
            ?.let { return it }

        val currentMetadata = metadataFor(article)
        val metadata = cachedMetadata?.takeIf { it == currentMetadata } ?: currentMetadata
        val shouldSendFullBody = if (request.metadataOnly) {
            request.bodyHash.isBlank() || request.bodyHash != currentMetadata.bodyHash
        } else {
            request.chunkIndexes.isEmpty() ||
                request.bodyHash.isBlank() ||
                request.bodyHash != currentMetadata.bodyHash
        }
        if (!shouldSendFullBody && !request.metadataOnly) {
            requireValidRequestedChunkIndexes(request, currentMetadata)
        }
        val responseRequest = if (shouldSendFullBody) {
            request.copy(
                bodyHash = currentMetadata.bodyHash,
                chunkIndexes = currentMetadata.chunkHashes.indices.toList(),
                metadataOnly = false
            )
        } else {
            request
        }
        val chunks = if (responseRequest.metadataOnly) {
            emptyList()
        } else {
            chunksForRequestWithCachedMetadata(article, responseRequest, metadata)
        }
        return metadata.toPayload(article, chunks, metadataOnly = responseRequest.metadataOnly)
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

    private fun chunksForRequestWithCachedMetadata(
        article: PhoneArticleEntity,
        request: ArticleBodyRequest,
        metadata: ArticleBodyMetadata
    ): List<ArticleBodyChunk> {
        requireValidRequestedChunkIndexes(request, metadata)
        val output = CachedBodyChunkOutputStream(
            metadata = metadata,
            captureIndexes = request.chunkIndexes.toSet()
        )
        writeEncodedBody(article.contentHtml, article.contentText, output)
        return output.chunks()
    }

    private fun requireValidRequestedChunkIndexes(
        request: ArticleBodyRequest,
        metadata: ArticleBodyMetadata
    ) {
        val invalidIndex = request.chunkIndexes.firstOrNull { it !in metadata.chunkHashes.indices }
        require(invalidIndex == null) {
            "同步正文请求分块越界：${request.articleId}#$invalidIndex " +
                "chunkCount=${metadata.chunkHashes.size}"
        }
    }

    fun rebuildBody(
        localArticle: PhoneArticleEntity?,
        payload: ChunkedArticlePayload
    ): Pair<String?, String> {
        validatePayloadShape(payload)
        val localBodyBytes = localArticle
            ?.let { encodeBody(it.contentHtml, it.contentText) }
        if (
            localArticle != null &&
            localBodyBytes != null &&
            localBodyBytes.size.toLong() == payload.bodyByteCount &&
            sha256(localBodyBytes) == payload.bodyHash
        ) {
            return localArticle.contentHtml to localArticle.contentText
        }
        val sentByIndex = payload.chunks.associateBy { it.index }
        val chunkSize = payload.chunkSize
        val rebuilt = ByteArrayOutputStream()
        payload.chunkHashes.forEachIndexed { index, expectedHash ->
            val sent = sentByIndex[index]
            when {
                sent != null -> {
                    require(sent.bytes.size == expectedChunkByteCount(payload, index)) {
                        "同步正文分块长度不匹配：${payload.article.articleId}#$index"
                    }
                    require(sent.hash == expectedHash && sha256(sent.bytes) == expectedHash) {
                        "同步正文分块校验失败：${payload.article.articleId}#$index"
                    }
                    rebuilt.write(sent.bytes)
                }
                localBodyBytes != null && index < chunkCountFor(localBodyBytes, chunkSize) -> {
                    val start = index * chunkSize
                    val end = min(start + chunkSize, localBodyBytes.size)
                    val length = end - start
                    require(length == expectedChunkByteCount(payload, index)) {
                        "同步正文分块长度不匹配：${payload.article.articleId}#$index"
                    }
                    require(sha256(localBodyBytes, start, length) == expectedHash) {
                        "同步正文缺少分块：${payload.article.articleId}#$index"
                    }
                    rebuilt.write(localBodyBytes, start, length)
                }
                else -> error("同步正文缺少分块：${payload.article.articleId}#$index")
            }
        }
        val bodyBytes = rebuilt.toByteArray()
        require(bodyBytes.size.toLong() == payload.bodyByteCount) {
            "同步正文长度不匹配：${payload.article.articleId} " +
                "expected=${payload.bodyByteCount} actual=${bodyBytes.size}"
        }
        require(sha256(bodyBytes) == payload.bodyHash) {
            "同步正文整体校验失败：${payload.article.articleId}"
        }
        return decodeBody(bodyBytes)
    }

    private fun validatePayloadShape(payload: ChunkedArticlePayload) {
        require(payload.bodyHash.isNotBlank()) {
            "同步正文元数据缺少整体哈希：${payload.article.articleId}"
        }
        require(payload.bodyByteCount in 0L..Int.MAX_VALUE.toLong()) {
            "同步正文元数据大小无效：${payload.article.articleId}#${payload.bodyByteCount}"
        }
        require(payload.chunkSize > 0) {
            "同步正文元数据分块大小无效：${payload.article.articleId}#${payload.chunkSize}"
        }
        val expectedChunkCount = chunkCountFor(payload.bodyByteCount, payload.chunkSize)
        require(payload.chunkHashes.size.toLong() == expectedChunkCount) {
            "同步正文元数据分块数不匹配：${payload.article.articleId} " +
                "expected=$expectedChunkCount actual=${payload.chunkHashes.size}"
        }
        require(payload.chunkHashes.all { it.isNotBlank() }) {
            "同步正文元数据包含空分块哈希：${payload.article.articleId}"
        }
        require(payload.chunks.all { it.index in payload.chunkHashes.indices }) {
            "同步正文元数据包含越界分块：${payload.article.articleId}"
        }
    }

    private fun expectedChunkByteCount(payload: ChunkedArticlePayload, index: Int): Int {
        val chunkStart = index.toLong() * payload.chunkSize
        return minOf(payload.chunkSize.toLong(), payload.bodyByteCount - chunkStart).toInt()
    }

    fun encodeChunkData(bytes: ByteArray): String =
        Base64.getEncoder().encodeToString(bytes)

    fun decodeChunkData(value: String): ByteArray =
        Base64.getDecoder().decode(value)

    private fun encodeBody(contentHtml: String?, contentText: String): ByteArray {
        return ByteArrayOutputStream().also { output ->
            writeEncodedBody(contentHtml, contentText, output)
        }.toByteArray()
    }

    private fun writeEncodedBody(contentHtml: String?, contentText: String, output: OutputStream) {
        GZIPOutputStream(output).use { gzip ->
            BufferedWriter(
                OutputStreamWriter(gzip, Charsets.UTF_8),
                JSON_WRITE_BUFFER_CHARS
            ).use { writer ->
                writer.append('{')
                var needsComma = false
                if (contentHtml != null) {
                    writeJsonStringField(writer, "contentHtml", contentHtml)
                    needsComma = true
                }
                if (needsComma) writer.append(',')
                writeJsonStringField(writer, "contentText", contentText)
                writer.append('}')
            }
        }
    }

    private fun validateCachedBodyMetadata(
        article: PhoneArticleEntity,
        metadata: ArticleBodyMetadata
    ) {
        val output = CachedBodyChunkOutputStream(metadata, emptySet())
        writeEncodedBody(article.contentHtml, article.contentText, output)
        output.chunks()
    }

    private fun writeJsonStringField(writer: Writer, name: String, value: String) {
        writeJsonString(writer, name)
        writer.append(':')
        writeJsonString(writer, value)
    }

    private fun writeJsonString(writer: Writer, value: String) {
        writer.write('"'.code)
        var runStart = 0
        value.forEachIndexed { index, char ->
            val escape = when (char) {
                '\\' -> "\\\\"
                '"' -> "\\\""
                '/' -> "\\/"
                '\b' -> "\\b"
                '\u000C' -> "\\f"
                '\n' -> "\\n"
                '\r' -> "\\r"
                '\t' -> "\\t"
                else -> null
            }
            if (escape != null || char.code < 0x20) {
                if (runStart < index) {
                    writer.write(value, runStart, index - runStart)
                }
                if (escape != null) {
                    writer.write(escape)
                } else {
                    writer.write("\\u00")
                    writer.write(HEX_DIGITS[char.code ushr 4].code)
                    writer.write(HEX_DIGITS[char.code and 0x0f].code)
                }
                runStart = index + 1
            }
        }
        if (runStart < value.length) {
            writer.write(value, runStart, value.length - runStart)
        }
        writer.write('"'.code)
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
        var hasContentHtml = false
        var hasContentText = false
        cursor.expectObjectStart()
        var firstField = true
        while (true) {
            val marker = cursor.nextNonWhitespace()
            require(marker != -1) { "同步正文JSON对象未结束" }
            if (marker.toChar() == '}') break
            if (!firstField) {
                require(marker.toChar() == ',') { "同步正文JSON格式错误" }
            } else {
                cursor.unread(marker)
            }
            val name = cursor.readName()
            cursor.expect(':')
            when (name) {
                "contentHtml" -> {
                    require(!hasContentHtml) { "同步正文JSON字段重复：contentHtml" }
                    contentHtml = cursor.readNullableString()
                    hasContentHtml = true
                }
                "contentText" -> {
                    require(!hasContentText) { "同步正文JSON字段重复：contentText" }
                    contentText = cursor.readNullableString().orEmpty()
                    hasContentText = true
                }
                else -> cursor.skipValue()
            }
            firstField = false
        }
        require(hasContentText) { "同步正文JSON缺少字段：contentText" }
        require(cursor.nextNonWhitespace() == -1) { "同步正文JSON包含尾随数据" }
        return contentHtml to contentText
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

        fun skipValue(depth: Int = 0) {
            require(depth <= MAX_JSON_NESTING_DEPTH) { "同步正文JSON嵌套过深" }
            when (val marker = nextNonWhitespace()) {
                '"'.code -> readStringBody()
                '{'.code -> skipObject(depth + 1)
                '['.code -> skipArray(depth + 1)
                't'.code -> expectLiteral("rue")
                'f'.code -> expectLiteral("alse")
                'n'.code -> expectLiteral("ull")
                '-'.code, in '0'.code..'9'.code -> skipNumber(marker)
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
                    else -> {
                        require(char >= 0x20) { "同步正文JSON字符串包含控制字符" }
                        builder.append(char.toChar())
                    }
                }
            }
        }

        private fun skipObject(depth: Int) {
            var firstField = true
            while (true) {
                val marker = nextNonWhitespace()
                require(marker != -1) { "同步正文JSON对象未结束" }
                if (marker == '}'.code) return
                if (!firstField) {
                    require(marker == ','.code) { "同步正文JSON格式错误" }
                } else {
                    unread(marker)
                }
                readName()
                expect(':')
                skipValue(depth)
                firstField = false
            }
        }

        private fun skipArray(depth: Int) {
            var firstValue = true
            while (true) {
                val marker = nextNonWhitespace()
                require(marker != -1) { "同步正文JSON数组未结束" }
                if (marker == ']'.code) return
                if (!firstValue) {
                    require(marker == ','.code) { "同步正文JSON格式错误" }
                } else {
                    unread(marker)
                }
                skipValue(depth)
                firstValue = false
            }
        }

        private fun skipNumber(first: Int) {
            val value = buildString {
                append(first.toChar())
                while (true) {
                    val char = read()
                    if (char == -1 || char.toChar().isWhitespace()) break
                    if (char == ','.code || char == '}'.code || char == ']'.code) {
                        unread(char)
                        break
                    }
                    append(char.toChar())
                }
            }
            require(JSON_NUMBER.matches(value)) { "同步正文JSON数字格式错误" }
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
            const val MAX_JSON_NESTING_DEPTH = 128
            val JSON_NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
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

    private fun chunkCountFor(bytes: ByteArray, chunkSize: Int): Int {
        if (bytes.isEmpty()) return 1
        return ((bytes.size - 1) / chunkSize) + 1
    }

    private fun chunkCountFor(bodyByteCount: Long, chunkSize: Int): Long {
        if (bodyByteCount == 0L) return 1L
        return ((bodyByteCount - 1L) / chunkSize) + 1L
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
        if (
            metadataHash != metadataHashFor(article) ||
            bodyHash.isBlank() ||
            bodyByteCount <= 0L ||
            chunkSize <= 0 ||
            chunkHashes.isEmpty()
        ) {
            return false
        }
        val expectedChunkCount = ((bodyByteCount - 1L) / chunkSize.toLong()) + 1L
        return expectedChunkCount == chunkHashes.size.toLong()
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
        return digest.toHexString()
    }

    private class BodyChunkOutputStream : OutputStream() {
        private val bodyDigest = MessageDigest.getInstance("SHA-256")
        private var chunkDigest = MessageDigest.getInstance("SHA-256")
        private val chunkHashes = mutableListOf<String>()
        private var chunkByteCount = 0
        private var bodyByteCount = 0L
        private var bodyHash: String? = null

        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len <= 0) return
            var offset = off
            var remaining = len
            while (remaining > 0) {
                val count = min(remaining, CHUNK_SIZE_BYTES - chunkByteCount)
                bodyDigest.update(b, offset, count)
                chunkDigest.update(b, offset, count)
                chunkByteCount += count
                bodyByteCount += count
                offset += count
                remaining -= count
                if (chunkByteCount == CHUNK_SIZE_BYTES) {
                    finishChunk()
                }
            }
        }

        fun metadata(metadataHash: String): ArticleBodyMetadata {
            finish()
            return ArticleBodyMetadata(
                bodyHash = bodyHash.orEmpty(),
                bodyByteCount = bodyByteCount,
                chunkSize = CHUNK_SIZE_BYTES,
                chunkHashes = chunkHashes.toList(),
                metadataHash = metadataHash
            )
        }

        private fun finish() {
            if (bodyHash != null) return
            if (chunkByteCount > 0 || bodyByteCount == 0L) {
                finishChunk()
            }
            bodyHash = bodyDigest.digest().toHexString()
        }

        private fun finishChunk() {
            chunkHashes += chunkDigest.digest().toHexString()
            chunkByteCount = 0
            chunkDigest = MessageDigest.getInstance("SHA-256")
        }
    }

    private class CachedBodyChunkOutputStream(
        private val metadata: ArticleBodyMetadata,
        private val captureIndexes: Set<Int>
    ) : OutputStream() {
        init {
            require(metadata.chunkSize > 0) { "同步正文缓存分块大小无效：${metadata.chunkSize}" }
        }

        private val bodyDigest = MessageDigest.getInstance("SHA-256")
        private var chunkBuffer: ByteArrayOutputStream? = newChunkBuffer(0)
        private var chunkDigest = MessageDigest.getInstance("SHA-256")
        private val chunks = mutableListOf<ArticleBodyChunk>()
        private var chunkIndex = 0
        private var chunkByteCount = 0
        private var bodyByteCount = 0L
        private var finished = false

        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len <= 0) return
            var offset = off
            var remaining = len
            while (remaining > 0) {
                val count = min(remaining, metadata.chunkSize - chunkByteCount)
                bodyDigest.update(b, offset, count)
                chunkDigest.update(b, offset, count)
                chunkBuffer?.write(b, offset, count)
                chunkByteCount += count
                bodyByteCount += count
                offset += count
                remaining -= count
                if (chunkByteCount == metadata.chunkSize) {
                    finishChunk()
                }
            }
        }

        fun chunks(): List<ArticleBodyChunk> {
            finish()
            return chunks.toList()
        }

        private fun finish() {
            if (finished) return
            if (chunkByteCount > 0) {
                finishChunk()
            }
            require(bodyByteCount == metadata.bodyByteCount) {
                "同步正文缓存大小不匹配：expected=${metadata.bodyByteCount} actual=$bodyByteCount"
            }
            require(chunkIndex == metadata.chunkHashes.size) {
                "同步正文缓存分块数不匹配：expected=${metadata.chunkHashes.size} actual=$chunkIndex"
            }
            require(bodyDigest.digest().toHexString() == metadata.bodyHash) {
                "同步正文缓存整体校验失败"
            }
            finished = true
        }

        private fun finishChunk() {
            val expectedHash = metadata.chunkHashes.getOrNull(chunkIndex)
                ?: error("同步正文缓存缺少分块哈希：$chunkIndex")
            require(chunkDigest.digest().toHexString() == expectedHash) {
                "同步正文缓存分块校验失败：$chunkIndex"
            }
            val bytes = chunkBuffer?.toByteArray()
            if (bytes != null) {
                chunks += ArticleBodyChunk(
                    index = chunkIndex,
                    hash = expectedHash,
                    bytes = bytes
                )
            }
            chunkIndex += 1
            chunkByteCount = 0
            chunkDigest = MessageDigest.getInstance("SHA-256")
            chunkBuffer = newChunkBuffer(chunkIndex)
        }

        private fun newChunkBuffer(index: Int): ByteArrayOutputStream? {
            return if (index in captureIndexes) {
                ByteArrayOutputStream()
            } else {
                null
            }
        }
    }

    private fun ByteArray.toHexString(): String {
        val encoded = CharArray(size * 2)
        forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            encoded[index * 2] = HEX_DIGITS[value ushr 4]
            encoded[index * 2 + 1] = HEX_DIGITS[value and 0x0f]
        }
        return String(encoded)
    }
}
