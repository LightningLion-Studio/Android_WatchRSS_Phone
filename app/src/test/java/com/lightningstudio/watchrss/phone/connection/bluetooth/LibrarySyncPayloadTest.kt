package com.lightningstudio.watchrss.phone.connection.bluetooth

import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
import com.lightningstudio.watchrss.phone.data.db.PhoneRssSourceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySyncPayloadTest {
    @Test
    fun buildArticlesRequest_roundTripsCompressedArticleContent() {
        val article = PhoneArticleEntity(
            articleId = "article-1",
            sourceDeviceId = "phone",
            url = "https://example.com/a",
            title = "标题",
            siteName = "example.com",
            excerpt = "摘要",
            contentHtml = "<article><p>正文</p></article>",
            contentText = "正文",
            imageUrl = "https://example.com/a.jpg",
            contentHash = "hash",
            importedAt = 10L,
            updatedAt = 20L,
            independentSaved = true,
            independentChangedAt = 22L,
            independentSortOrder = 22L,
            rssSourceUrl = "https://example.com/feed.xml",
            rssSourceTitle = "示例源",
            favoriteSaved = true,
            favoriteChangedAt = 21L,
            favoriteSortOrder = 21L,
            watchLaterSaved = false,
            watchLaterChangedAt = 0L,
            watchLaterSortOrder = 0L,
            deleted = false,
            deletedAt = 0L
        )

        val request = LibrarySyncPayload.buildArticlesRequest("phone", listOf(article))
        val parsed = LibrarySyncPayload.parseArticles(request).single()

        assertEquals(article.contentHtml, parsed.contentHtml)
        assertEquals(article.contentText, parsed.contentText)
        assertTrue(parsed.independentSaved)
        assertTrue(parsed.favoriteSaved)
        assertEquals("syncLibrary", request.getString("action"))
        assertEquals("articles", request.getString("phase"))
    }

    @Test
    fun buildManifestRequest_exchangesManifestAndSourcesWithoutArticleBodies() {
        val article = PhoneArticleEntity(
            articleId = "article-1",
            sourceDeviceId = "phone",
            url = "https://example.com/a",
            title = "标题",
            siteName = "example.com",
            excerpt = "摘要",
            contentHtml = "<article><p>正文</p></article>",
            contentText = "正文",
            imageUrl = "https://example.com/a.jpg",
            contentHash = "hash",
            importedAt = 10L,
            updatedAt = 20L,
            independentSaved = true,
            independentChangedAt = 22L,
            independentSortOrder = 22L,
            rssSourceUrl = "https://example.com/feed.xml",
            rssSourceTitle = "示例源",
            favoriteSaved = true,
            favoriteChangedAt = 21L,
            favoriteSortOrder = 21L,
            watchLaterSaved = false,
            watchLaterChangedAt = 0L,
            watchLaterSortOrder = 0L,
            deleted = false,
            deletedAt = 0L
        )
        val source = PhoneRssSourceEntity(
            url = "https://example.com/feed.xml",
            sourceDeviceId = "phone",
            title = "示例源",
            description = "源描述",
            siteUrl = "https://example.com",
            imageUrl = null,
            createdAt = 1L,
            updatedAt = 2L,
            sortOrder = 2L,
            isPinned = true,
            deleted = false,
            deletedAt = 0L
        )

        val request = LibrarySyncPayload.buildManifestRequest("phone", listOf(article), listOf(source))
        val manifest = LibrarySyncPayload.parseArticleManifest(request).single()
        val parsedSource = LibrarySyncPayload.parseRssSources(request).single()

        assertEquals(0, LibrarySyncPayload.parseArticles(request).size)
        assertEquals(article.articleId, manifest.articleId)
        assertEquals(article.contentHash, manifest.contentHash)
        assertEquals(article.favoriteChangedAt, manifest.favoriteChangedAt)
        assertEquals(source.title, parsedSource.title)
        assertEquals(source.isPinned, parsedSource.isPinned)
        assertEquals("syncLibrary", request.getString("action"))
        assertEquals("manifest", request.getString("phase"))
    }

    @Test
    fun buildArticleRequestFrames_splitsLargeArticleSetAndCombinesFrames() {
        val articles = (1..4).map { index ->
            testArticle(
                articleId = "article-$index",
                contentText = pseudoRandomText(seed = index, length = 700_000)
            )
        }

        val frames = LibrarySyncPayload.buildArticleRequestFrames(
            deviceId = "phone",
            articles = articles,
            useBatches = true
        )

        assertTrue(frames.size > 1)
        frames.forEachIndexed { index, frame ->
            assertTrue(BluetoothSyncProtocol.encodedSize(frame) <= BluetoothSyncProtocol.MAX_FRAME_BYTES)
            assertEquals(index, frame.getInt("batchIndex"))
            assertEquals(frames.size, frame.getInt("batchCount"))
            assertEquals(articles.size, frame.getInt("totalArticles"))
        }

        val combined = LibrarySyncPayload.combineArticlePayloads(frames)
        val parsed = LibrarySyncPayload.parseArticles(combined)
        assertEquals(articles.map { it.articleId }, parsed.map { it.articleId })
    }

    @Test
    fun filterArticlesNeedingSync_usesManifestTimestampsAndHash() {
        val article = PhoneArticleEntity(
            articleId = "article-1",
            sourceDeviceId = "phone",
            url = "https://example.com/a",
            title = "标题",
            siteName = "example.com",
            excerpt = "摘要",
            contentHtml = null,
            contentText = "正文",
            imageUrl = null,
            contentHash = "hash",
            importedAt = 10L,
            updatedAt = 20L,
            independentSaved = false,
            independentChangedAt = 0L,
            independentSortOrder = 0L,
            rssSourceUrl = null,
            rssSourceTitle = null,
            favoriteSaved = true,
            favoriteChangedAt = 30L,
            favoriteSortOrder = 30L,
            watchLaterSaved = false,
            watchLaterChangedAt = 0L,
            watchLaterSortOrder = 0L,
            deleted = false,
            deletedAt = 0L
        )
        val currentRemote = ArticleSyncManifestEntry(
            articleId = "article-1",
            contentHash = "hash",
            updatedAt = 20L,
            independentChangedAt = 0L,
            favoriteChangedAt = 30L,
            watchLaterChangedAt = 0L,
            deletedAt = 0L
        )
        val staleRemote = currentRemote.copy(favoriteChangedAt = 29L)

        assertEquals(
            emptyList<PhoneArticleEntity>(),
            LibrarySyncPayload.filterArticlesNeedingSync(listOf(article), listOf(currentRemote))
        )
        assertEquals(
            listOf(article),
            LibrarySyncPayload.filterArticlesNeedingSync(listOf(article), listOf(staleRemote))
        )
    }

    @Test
    fun chunkedBodyRequest_sendsOnlyChangedChunksAndRebuildsBody() {
        val chunkSize = ArticleSyncBody.CHUNK_SIZE_BYTES
        val oldArticle = testArticle(
            articleId = "article-large",
            contentText = "A".repeat(chunkSize) + "B".repeat(chunkSize) + "C".repeat(64)
        )
        val newArticle = oldArticle.copy(
            contentText = "A".repeat(chunkSize) + "D".repeat(chunkSize) + "C".repeat(64),
            updatedAt = oldArticle.updatedAt + 1
        )
        val localManifest = oldArticle.toManifestEntry()
        val remoteManifest = newArticle.toManifestEntry()

        val requests = LibrarySyncPayload.buildBodyRequestsForRemoteArticles(
            localManifest = listOf(localManifest),
            remoteManifest = listOf(remoteManifest)
        )
        val frames = LibrarySyncPayload.buildChunkedArticleRequestFrames(
            deviceId = "phone",
            articles = listOf(newArticle),
            articleRequests = requests,
            bodyRequests = emptyList(),
            useBatches = true
        )
        val parsed = LibrarySyncPayload.parseChunkedArticles(
            LibrarySyncPayload.combineArticlePayloads(frames)
        ).single()
        val rebuilt = ArticleSyncBody.rebuildBody(oldArticle, parsed)

        assertTrue(requests.single().chunkIndexes.isNotEmpty())
        assertTrue(requests.single().chunkIndexes.size < remoteManifest.chunkHashes.size)
        assertEquals(requests.single().chunkIndexes, parsed.chunks.map { it.index })
        assertEquals(newArticle.contentText, rebuilt.second)
    }

    @Test
    fun chunkedBodyRequest_withMetadataOnlyRequestSendsNoChunks() {
        val article = testArticle(
            articleId = "article-1",
            contentText = "正文"
        )
        val metadata = ArticleSyncBody.metadataFor(article)
        val frames = LibrarySyncPayload.buildChunkedArticleRequestFrames(
            deviceId = "phone",
            articles = listOf(article),
            articleRequests = listOf(
                ArticleBodyRequest(
                    articleId = article.articleId,
                    bodyHash = metadata.bodyHash,
                    chunkIndexes = emptyList()
                )
            ),
            bodyRequests = emptyList(),
            useBatches = true
        )

        val parsed = LibrarySyncPayload.parseChunkedArticles(
            LibrarySyncPayload.combineArticlePayloads(frames)
        ).single()

        assertEquals(emptyList<Int>(), parsed.chunks.map { it.index })
    }

    @Test
    fun chunkedBodyRequest_splitsHugeArticleAcrossFramesAndRebuildsBody() {
        val article = testArticle(
            articleId = "article-huge",
            contentText = pseudoRandomText(
                seed = 42,
                length = BluetoothSyncProtocol.MAX_FRAME_BYTES + ArticleSyncBody.CHUNK_SIZE_BYTES
            )
        )
        val metadata = ArticleSyncBody.metadataFor(article)

        val frames = LibrarySyncPayload.buildChunkedArticleRequestFrames(
            deviceId = "phone",
            articles = listOf(article),
            articleRequests = listOf(
                ArticleBodyRequest(
                    articleId = article.articleId,
                    bodyHash = metadata.bodyHash,
                    chunkIndexes = metadata.chunkHashes.indices.toList()
                )
            ),
            bodyRequests = emptyList(),
            useBatches = true
        )
        val combined = LibrarySyncPayload.combineArticlePayloads(frames)
        val parsed = LibrarySyncPayload.parseChunkedArticles(combined).single()
        val rebuilt = ArticleSyncBody.rebuildBody(null, parsed)

        assertTrue(frames.size > 1)
        assertTrue(frames.all { BluetoothSyncProtocol.encodedSize(it) <= BluetoothSyncProtocol.MAX_FRAME_BYTES })
        assertEquals(article.contentText, rebuilt.second)
    }

    private fun testArticle(
        articleId: String,
        contentText: String
    ): PhoneArticleEntity {
        return PhoneArticleEntity(
            articleId = articleId,
            sourceDeviceId = "phone",
            url = "https://example.com/$articleId",
            title = articleId,
            siteName = "example.com",
            excerpt = "摘要",
            contentHtml = null,
            contentText = contentText,
            imageUrl = null,
            contentHash = "hash-$articleId",
            importedAt = 10L,
            updatedAt = 20L,
            independentSaved = true,
            independentChangedAt = 20L,
            independentSortOrder = 20L,
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
    }

    private fun pseudoRandomText(seed: Int, length: Int): String {
        var value = seed
        return buildString(length) {
            repeat(length) {
                value = value * 1103515245 + 12345
                append((33 + ((value ushr 16) % 90)).toChar())
            }
        }
    }

    private fun PhoneArticleEntity.toManifestEntry(): ArticleSyncManifestEntry {
        val metadata = ArticleSyncBody.metadataFor(this)
        return ArticleSyncManifestEntry(
            articleId = articleId,
            sourceDeviceId = sourceDeviceId,
            contentHash = contentHash,
            updatedAt = updatedAt,
            independentChangedAt = independentChangedAt,
            favoriteChangedAt = favoriteChangedAt,
            watchLaterChangedAt = watchLaterChangedAt,
            deletedAt = deletedAt,
            bodyHash = metadata.bodyHash,
            bodyByteCount = metadata.bodyByteCount,
            chunkSize = metadata.chunkSize,
            chunkHashes = metadata.chunkHashes,
            metadataHash = metadata.metadataHash
        )
    }
}
