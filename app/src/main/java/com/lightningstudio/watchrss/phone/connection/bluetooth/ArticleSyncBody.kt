package com.lightningstudio.watchrss.phone.connection.bluetooth

import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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
    val chunkIndexes: List<Int>
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
    val chunks: List<ArticleBodyChunk>
)

object ArticleSyncBody {
    const val CHUNK_SIZE_BYTES = 128 * 1024
    private const val BODY_ENCODING_VERSION = 2

    fun metadataFor(article: PhoneArticleEntity): ArticleBodyMetadata {
        val bodyBytes = encodeBody(article.contentHtml, article.contentText)
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

    fun chunksForRequest(article: PhoneArticleEntity, request: ArticleBodyRequest): List<ArticleBodyChunk> {
        val bodyBytes = encodeBody(article.contentHtml, article.contentText)
        val chunkCount = chunkCountFor(bodyBytes)
        val indexes = request.chunkIndexes
            .distinct()
            .filter { it in 0 until chunkCount }
        return indexes.map { index ->
            val start = index * CHUNK_SIZE_BYTES
            val end = min(start + CHUNK_SIZE_BYTES, bodyBytes.size)
            val bytes = bodyBytes.copyOfRange(start, end)
            ArticleBodyChunk(
                index = index,
                hash = sha256(bodyBytes, start, end - start),
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
        val rawBody = runCatching { gunzip(bytes) }.getOrElse { bytes }
        val json = JSONObject(rawBody.toString(Charsets.UTF_8))
        return json.optString("contentHtml").ifBlank { null } to json.optString("contentText")
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { gzip ->
            gzip.write(bytes)
        }
        return out.toByteArray()
    }

    private fun gunzip(bytes: ByteArray): ByteArray =
        GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }

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

    private fun sha256(bytes: ByteArray): String {
        return sha256(bytes, 0, bytes.size)
    }

    private fun sha256(bytes: ByteArray, offset: Int, length: Int): String {
        val digest = MessageDigest.getInstance("SHA-256").apply {
            update(bytes, offset, length)
        }.digest()
        return digest.joinToString("") { "%02x".format(it) }
    }
}
