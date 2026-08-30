package com.lightningstudio.watchrss.phone.connection.bluetooth

import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.Random
import java.util.zip.GZIPOutputStream

class ArticleSyncBodyCodecTest {
    @Test
    fun canonicalBodyEncoding_matchesCrossDeviceGoldenVectors() {
        val vectors = listOf(
            GoldenBodyVector(
                name = "null",
                contentHtml = null,
                contentText = "",
                compressedBase64 = "H4sIAAAAAAAA/6tWSs7PK0nNKwlJrShRslJSqgUAkqgnsRIAAAA=",
                bodyHash = "960eb990ae900cb658d6a115eb8ceef4238485666990b85b5edae12454668822",
                bodyByteCount = 38L,
                metadataHash = "40ac1e47633053b2b47d611eb20ed06e38c91b6c9bd26f5df5c877bff7635d98"
            ),
            GoldenBodyVector(
                name = "empty-html",
                contentHtml = "",
                contentText = "slash / backslash \\ quote \" controls:\u0000\u0001\b\u000C\n\r\t " +
                    "Unicode: 中文 Ελληνικά العربية 😀",
                compressedBase64 = "H4sIAAAAAAAA/6tWSs7PK0nNK/Eoyc1RslJS0oEJhKRWlAAFinMSizMUYvQVkhKTs6GcGIXC0vySVIUYJQWQ4qL8nGKrmFIDIACThjFJMWkxeTFFMSUKoXmZyfkpqVYKT3asfTatXeHc1HO7gXD7ub3ndp7bdW6Nwo3lN1tu7Lyx8caKm103Vip8mD+jQakWAEDbYpiWAAAA",
                bodyHash = "55055df952a866507690b7e45ebbdc8a9456c86337c58d138fc2ffa5a6bf6907",
                bodyByteCount = 153L,
                metadataHash = "ef05e4abfcde89a5b87318faf712433e8dd4b63209856beb89be39a9b0ddc451"
            ),
            GoldenBodyVector(
                name = "html",
                contentHtml = "<article data-path=\"/a/b\" title=\"q\\\\\"\">正文 😀<br /></article>",
                contentText = "正文 / path \\ \" 😀",
                compressedBase64 = "H4sIAAAAAAAA/6tWSs7PK0nNK/Eoyc1RslKySSwqyUzOSVVISSxJ1C1ILMmwjVGK0U+M0U+KUVIoySzJSQUKFMaAgFKMkt2ztYufTWtX+DB/RoNNUpFCjL6dDVA1xAw7JR2Y6SGpFSVA06GqY/QVQCYrxMQoAA0F6VWqBQBO1pvJiQAAAA==",
                bodyHash = "0095afc64424683fa1a484b181afc10e935aa702c3f95132d916dba584e15651",
                bodyByteCount = 133L,
                metadataHash = "2327b7df256bd8b94b18114b3c57e962fb6131f9ea3b50bc4cc5740eed9cb9be"
            )
        )

        vectors.forEach { vector ->
            val article = article(
                "golden-${vector.name}",
                vector.contentHtml,
                vector.contentText
            ).copy(
                sourceDeviceId = "golden-device"
            )
            val metadata = ArticleSyncBody.metadataFor(article)
            val payload = ArticleSyncBody.payloadForRequest(
                article,
                ArticleBodyRequest(article.articleId, bodyHash = "", chunkIndexes = emptyList())
            )
            val bytes = payload.chunks.single().bytes

            assertEquals(vector.compressedBase64, Base64.getEncoder().encodeToString(bytes))
            assertEquals(vector.bodyHash, metadata.bodyHash)
            assertEquals(vector.bodyByteCount, metadata.bodyByteCount)
            assertEquals(ArticleSyncBody.CHUNK_SIZE_BYTES, metadata.chunkSize)
            assertEquals(listOf(vector.bodyHash), metadata.chunkHashes)
            assertEquals(vector.metadataHash, metadata.metadataHash)
            assertEquals(metadata.bodyHash, payload.bodyHash)
            assertEquals(metadata.bodyByteCount, payload.bodyByteCount)
            assertEquals(metadata.chunkSize, payload.chunkSize)
            assertEquals(metadata.chunkHashes, payload.chunkHashes)
            assertEquals(
                vector.contentHtml to vector.contentText,
                ArticleSyncBody.rebuildBody(null, payload)
            )
        }
    }

    @Test
    fun canonicalBodyEncoding_roundTripsWithoutHashDrift() {
        val bodies = listOf(
            null to "纯文本正文",
            "" to "",
            "<article><p>normal</p></article>" to "normal",
            "<article lang=\"zh\">你好 😀</article>" to "多语言 Ελληνικά العربية 😀\n第二行"
        )

        bodies.forEachIndexed { index, (contentHtml, contentText) ->
            val article = article("round-trip-$index", contentHtml, contentText)
            val sourceMetadata = ArticleSyncBody.metadataFor(article)
            val response = ArticleSyncBody.payloadForRequest(
                article,
                ArticleBodyRequest(article.articleId, bodyHash = "", chunkIndexes = emptyList())
            )
            val rebuilt = ArticleSyncBody.rebuildBody(localArticle = null, payload = response)
            val rebuiltArticle = article.copy(contentHtml = rebuilt.first, contentText = rebuilt.second)
            val rebuiltMetadata = ArticleSyncBody.metadataFor(rebuiltArticle)
            val rebuiltResponse = ArticleSyncBody.payloadForRequest(
                rebuiltArticle,
                ArticleBodyRequest(article.articleId, bodyHash = "", chunkIndexes = emptyList())
            )

            assertEquals(contentHtml, rebuilt.first)
            assertEquals(contentText, rebuilt.second)
            assertEquals(sourceMetadata.bodyHash, rebuiltMetadata.bodyHash)
            assertEquals(sourceMetadata.bodyByteCount, rebuiltMetadata.bodyByteCount)
            assertEquals(sourceMetadata.chunkHashes, rebuiltMetadata.chunkHashes)
            assertEquals(response.chunks.size, rebuiltResponse.chunks.size)
            response.chunks.zip(rebuiltResponse.chunks).forEach { (source, reencoded) ->
                assertTrue(source.bytes.contentEquals(reencoded.bytes))
            }
        }
    }

    @Test
    fun staleLargeBodyRequestFallsBackToEveryChunkAfterBodyShrinks() {
        val oldBody = ByteArray(ArticleSyncBody.CHUNK_SIZE_BYTES * 3).also {
            Random(20260830L).nextBytes(it)
        }
        val oldArticle = article(
            articleId = "stale-shrink",
            contentHtml = null,
            contentText = Base64.getEncoder().encodeToString(oldBody)
        )
        val oldMetadata = ArticleSyncBody.metadataFor(oldArticle)
        assertTrue(oldMetadata.chunkHashes.size > 2)
        val currentArticle = oldArticle.copy(contentText = "small current body")
        val currentMetadata = ArticleSyncBody.metadataFor(currentArticle)
        assertEquals(1, currentMetadata.chunkHashes.size)

        val payload = ArticleSyncBody.payloadForRequest(
            article = currentArticle,
            request = ArticleBodyRequest(
                articleId = currentArticle.articleId,
                bodyHash = oldMetadata.bodyHash,
                chunkIndexes = listOf(oldMetadata.chunkHashes.lastIndex)
            ),
            cachedMetadata = oldMetadata
        )

        assertEquals(currentMetadata.bodyHash, payload.bodyHash)
        assertEquals(currentMetadata.bodyByteCount, payload.bodyByteCount)
        assertEquals(currentMetadata.chunkHashes, payload.chunkHashes)
        assertEquals(listOf(0), payload.chunks.map { it.index })
        assertEquals(
            currentArticle.contentHtml to currentArticle.contentText,
            ArticleSyncBody.rebuildBody(null, payload)
        )
    }

    @Test
    fun metadataOnlyRequestFallsBackToFullBodyAfterManifestBodyDrifts() {
        val manifestArticle = article("metadata-only-drift", null, "manifest body")
        val manifestMetadata = ArticleSyncBody.metadataFor(manifestArticle)
        val currentArticle = manifestArticle.copy(contentText = "current body")
        val currentMetadata = ArticleSyncBody.metadataFor(currentArticle)

        val fallback = ArticleSyncBody.payloadForRequest(
            article = currentArticle,
            request = ArticleBodyRequest(
                articleId = currentArticle.articleId,
                bodyHash = manifestMetadata.bodyHash,
                chunkIndexes = emptyList(),
                metadataOnly = true
            ),
            cachedMetadata = manifestMetadata
        )

        assertEquals(false, fallback.metadataOnly)
        assertEquals(currentMetadata.bodyHash, fallback.bodyHash)
        assertEquals(currentMetadata.chunkHashes.indices.toList(), fallback.chunks.map { it.index })

        val unchanged = ArticleSyncBody.payloadForRequest(
            article = currentArticle,
            request = ArticleBodyRequest(
                articleId = currentArticle.articleId,
                bodyHash = currentMetadata.bodyHash,
                chunkIndexes = emptyList(),
                metadataOnly = true
            ),
            cachedMetadata = currentMetadata
        )
        assertTrue(unchanged.metadataOnly)
        assertTrue(unchanged.chunks.isEmpty())
        assertEquals(currentMetadata.bodyHash, unchanged.bodyHash)
    }

    @Test
    fun cachedMetadataSnapshot_readsPersistedShapeWithoutTrustingStaleBody() {
        val original = article(
            articleId = "snapshot",
            contentHtml = "<article>old</article>",
            contentText = "old"
        )
        val originalMetadata = ArticleSyncBody.metadataFor(original)
        val stale = original.copy(
            contentHtml = "<article>new body with different bytes</article>",
            contentText = "new body with different bytes",
            syncBodyHash = originalMetadata.bodyHash,
            syncBodyByteCount = originalMetadata.bodyByteCount,
            syncChunkSize = originalMetadata.chunkSize,
            syncChunkHashesJson = JSONArray(originalMetadata.chunkHashes).toString(),
            syncMetadataHash = originalMetadata.metadataHash
        )

        val snapshot = requireNotNull(ArticleSyncBody.cachedMetadataSnapshotFor(stale))
        assertEquals(originalMetadata, snapshot)

        val payload = ArticleSyncBody.payloadForRequest(
            article = stale,
            request = ArticleBodyRequest(
                articleId = stale.articleId,
                bodyHash = originalMetadata.bodyHash,
                chunkIndexes = originalMetadata.chunkHashes.indices.toList()
            ),
            cachedMetadata = snapshot
        )
        val currentMetadata = ArticleSyncBody.metadataFor(stale)

        assertEquals(currentMetadata.bodyHash, payload.bodyHash)
        assertEquals(currentMetadata.bodyByteCount, payload.bodyByteCount)
        assertEquals(currentMetadata.chunkHashes, payload.chunkHashes)
        assertEquals(currentMetadata.chunkHashes.indices.toList(), payload.chunks.map { it.index })
        assertEquals(
            stale.contentHtml to stale.contentText,
            ArticleSyncBody.rebuildBody(localArticle = null, payload = payload)
        )
    }

    @Test
    fun cachedMetadataSnapshot_rejectsInconsistentChunkShape() {
        val original = article("snapshot-shape", null, "body")
        val metadata = ArticleSyncBody.metadataFor(original)
        val invalid = original.copy(
            syncBodyHash = metadata.bodyHash,
            syncBodyByteCount = metadata.bodyByteCount + ArticleSyncBody.CHUNK_SIZE_BYTES,
            syncChunkSize = metadata.chunkSize,
            syncChunkHashesJson = JSONArray(metadata.chunkHashes).toString(),
            syncMetadataHash = metadata.metadataHash
        )

        assertEquals(null, ArticleSyncBody.cachedMetadataSnapshotFor(invalid))
    }

    @Test
    fun decoder_rejectsTruncatedAndTrailingJsonDocuments() {
        listOf(
            "{\"contentText\":\"unfinished\"",
            "{\"contentText\":\"ok\"} trailing",
            "{\"contentText\":\"first\"}{\"contentText\":\"second\"}"
        ).forEach { json ->
            assertDecodeRejected(json)
        }
    }

    @Test
    fun decoder_rejectsWrongContentFieldTypes() {
        listOf(
            "{\"contentHtml\":7,\"contentText\":\"ok\"}",
            "{\"contentHtml\":{},\"contentText\":\"ok\"}",
            "{\"contentHtml\":null,\"contentText\":false}",
            "{\"contentText\":[]}"
        ).forEach { json ->
            assertDecodeRejected(json)
        }
    }

    @Test
    fun decoder_skipsUnknownValidJsonValuesForForwardCompatibility() {
        val payload = payloadForJson(
            """{"future":{"nested":[1,true,null,{"value":"ok"}]},"contentHtml":"","contentText":"正文"}"""
        )

        val rebuilt = ArticleSyncBody.rebuildBody(null, payload)

        assertEquals("", rebuilt.first)
        assertEquals("正文", rebuilt.second)
    }

    private fun assertDecodeRejected(json: String) {
        try {
            ArticleSyncBody.rebuildBody(null, payloadForJson(json))
            fail("Expected body JSON to be rejected: $json")
        } catch (expected: RuntimeException) {
            assertTrue(expected.message.orEmpty().contains("同步正文JSON"))
        }
    }

    private fun payloadForJson(json: String): ChunkedArticlePayload {
        val bytes = gzip(json.toByteArray(Charsets.UTF_8))
        val hash = sha256(bytes)
        return ChunkedArticlePayload(
            article = article("malformed", null, "placeholder"),
            bodyHash = hash,
            bodyByteCount = bytes.size.toLong(),
            chunkSize = ArticleSyncBody.CHUNK_SIZE_BYTES,
            chunkHashes = listOf(hash),
            chunks = listOf(ArticleBodyChunk(index = 0, hash = hash, bytes = bytes))
        )
    }

    private fun article(
        articleId: String,
        contentHtml: String?,
        contentText: String
    ) = PhoneArticleEntity(
        articleId = articleId,
        sourceDeviceId = "phone",
        url = "https://example.com/$articleId",
        title = articleId,
        siteName = "example.com",
        excerpt = "",
        contentHtml = contentHtml,
        contentText = contentText,
        imageUrl = null,
        contentHash = "content-$articleId",
        importedAt = 1L,
        updatedAt = 2L,
        independentSaved = false,
        independentChangedAt = 0L,
        independentSortOrder = 0L,
        rssSourceUrl = null,
        rssSourceTitle = null,
        favoriteSaved = false,
        favoriteChangedAt = 0L,
        favoriteSortOrder = 0L,
        watchLaterSaved = false,
        watchLaterChangedAt = 0L,
        watchLaterSortOrder = 0L,
        deleted = false,
        deletedAt = 0L
    )

    private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { output ->
        GZIPOutputStream(output).use { it.write(bytes) }
    }.toByteArray()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private data class GoldenBodyVector(
        val name: String,
        val contentHtml: String?,
        val contentText: String,
        val compressedBase64: String,
        val bodyHash: String,
        val bodyByteCount: Long,
        val metadataHash: String
    )
}
