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

        val request = LibrarySyncPayload.buildManifestRequest(
            deviceId = "phone",
            articles = listOf(article),
            rssSources = listOf(source),
            changeSequence = LibraryChangeSequence(
                fromSeqExclusive = 7L,
                toSeqInclusive = 9L,
                fullSnapshot = false,
                fallbackReason = ""
            )
        )
        val manifest = LibrarySyncPayload.parseArticleManifest(request).single()
        val parsedSource = LibrarySyncPayload.parseRssSources(request).single()
        val changeSequence = LibrarySyncPayload.parseChangeSequence(request)

        assertEquals(0, LibrarySyncPayload.parseArticles(request).size)
        assertEquals(article.articleId, manifest.articleId)
        assertEquals(article.contentHash, manifest.contentHash)
        assertEquals(article.favoriteChangedAt, manifest.favoriteChangedAt)
        assertEquals(source.title, parsedSource.title)
        assertEquals(source.isPinned, parsedSource.isPinned)
        assertTrue(LibrarySyncPayload.supportsChangeSequences(request))
        assertTrue(LibrarySyncPayload.supportsMetadataOnlyArticles(request))
        assertEquals(7L, changeSequence.fromSeqExclusive)
        assertEquals(9L, changeSequence.toSeqInclusive)
        assertEquals(false, changeSequence.fullSnapshot)
        assertEquals("syncLibrary", request.getString("action"))
        assertEquals("manifest", request.getString("phase"))
    }

    @Test
    fun buildManifestRequest_marksSavedListArticleAsMetadataOnlyCandidate() {
        val article = testArticle(
            articleId = "saved-list",
            contentText = "正文"
        ).copy(
            independentSaved = false,
            independentChangedAt = 0L,
            independentSortOrder = 0L,
            favoriteSaved = true,
            favoriteChangedAt = 30L,
            favoriteSortOrder = 30L
        )

        val request = LibrarySyncPayload.buildManifestRequest(
            deviceId = "phone",
            articles = listOf(article)
        )
        val manifest = LibrarySyncPayload.parseArticleManifest(request).single()

        assertEquals(ARTICLE_BODY_SYNC_MODE_SAVED, manifest.bodySyncMode)
        assertTrue(LibrarySyncPayload.supportsMetadataOnlyArticles(request))
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
    fun chunkedBodyRequest_sendsRequestedChunksAndRebuildsCompressedBody() {
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
        assertEquals(requests.single().chunkIndexes, parsed.chunks.map { it.index })
        assertEquals(newArticle.contentText, rebuilt.second)
    }

    @Test
    fun chunkedBodyRequest_requestsFullBodyWhenLocalChunkHashesAreMissing() {
        val article = testArticle(
            articleId = "article-1",
            contentText = "正文"
        )
        val remoteManifest = article.toManifestEntry()
        val localManifest = remoteManifest.copy(chunkHashes = emptyList())

        val requests = LibrarySyncPayload.buildBodyRequestsForRemoteArticles(
            localManifest = listOf(localManifest),
            remoteManifest = listOf(remoteManifest)
        )

        assertEquals(remoteManifest.chunkHashes.indices.toList(), requests.single().chunkIndexes)
    }

    @Test
    fun chunkedBodyRequest_requestsFullBodyWhenLocalBodyIsUnavailable() {
        val article = testArticle(
            articleId = "article-1",
            contentText = "正文"
        )
        val remoteManifest = article.toManifestEntry()
        val localManifest = remoteManifest.copy(bodyAvailable = false)

        val requests = LibrarySyncPayload.buildBodyRequestsForRemoteArticles(
            localManifest = listOf(localManifest),
            remoteManifest = listOf(remoteManifest)
        )

        assertEquals(remoteManifest.chunkHashes.indices.toList(), requests.single().chunkIndexes)
    }

    @Test
    fun chunkedBodyRequest_doesNotRequestUnavailableRemoteBody() {
        val article = testArticle(
            articleId = "article-1",
            contentText = "正文"
        )
        val remoteManifest = article.toManifestEntry().copy(bodyAvailable = false)

        val requests = LibrarySyncPayload.buildBodyRequestsForRemoteArticles(
            localManifest = emptyList(),
            remoteManifest = listOf(remoteManifest)
        )

        assertEquals(emptyList<ArticleBodyRequest>(), requests)
    }

    @Test
    fun chunkedBodyRequest_limitsBodyRequestsByWholeArticles() {
        val requests = LibrarySyncPayload.buildBodyRequestsForRemoteArticles(
            localManifest = emptyList(),
            remoteManifest = listOf(
                remoteManifestWithChunks("article-1", listOf("a", "b")),
                remoteManifestWithChunks("article-2", listOf("c", "d")),
                remoteManifestWithChunks("deleted", emptyList()).copy(deleted = true, deletedAt = 30L),
                remoteManifestWithChunks("article-3", listOf("e"))
            ),
            maxBodyRequestChunks = 3
        )

        assertEquals(listOf("article-1", "deleted", "article-3"), requests.map { it.articleId })
        assertEquals(listOf(0, 1), requests[0].chunkIndexes)
        assertEquals(emptyList<Int>(), requests[1].chunkIndexes)
        assertEquals(listOf(0), requests[2].chunkIndexes)
    }

    @Test
    fun chunkedBodyRequest_syncLimitRequestsEveryMissingBody() {
        val remoteManifest = (1..30).map { index ->
            remoteManifestWithChunks("article-$index", listOf("hash-$index"))
        }

        val requests = LibrarySyncPayload.buildBodyRequestsForRemoteArticles(
            localManifest = emptyList(),
            remoteManifest = remoteManifest,
            maxBodyRequestChunks = LibrarySyncPayload.MAX_BODY_REQUEST_CHUNKS_PER_SYNC
        )

        assertEquals(remoteManifest.map { it.articleId }, requests.map { it.articleId })
    }

    @Test
    fun chunkedBodyRequest_doesNotRequestBodyForDeletedRemoteArticle() {
        val article = testArticle(
            articleId = "article-deleted",
            contentText = "正文"
        )
        val remoteManifest = article.toManifestEntry().copy(
            deleted = true,
            deletedAt = 30L
        )

        val requests = LibrarySyncPayload.buildBodyRequestsForRemoteArticles(
            localManifest = emptyList(),
            remoteManifest = listOf(remoteManifest)
        )

        assertEquals(emptyList<Int>(), requests.single().chunkIndexes)
    }

    @Test
    fun chunkedBodyRequest_ignoresDeletedTombstoneMetadataDrift() {
        val remoteManifest = testArticle(
            articleId = "article-deleted",
            contentText = "正文"
        ).toManifestEntry().copy(
            updatedAt = 100L,
            deleted = true,
            deletedAt = 30L,
            metadataHash = "remote-metadata"
        )
        val localManifest = remoteManifest.copy(
            updatedAt = 1L,
            bodyHash = "local-body",
            chunkHashes = emptyList(),
            metadataHash = "local-metadata"
        )

        val requests = LibrarySyncPayload.buildBodyRequestsForRemoteArticles(
            localManifest = listOf(localManifest),
            remoteManifest = listOf(remoteManifest)
        )

        assertEquals(emptyList<ArticleBodyRequest>(), requests)
    }

    @Test
    fun chunkedBodyRequest_ignoresOlderRemoteMetadataDrift() {
        val localManifest = testArticle(
            articleId = "article-1",
            contentText = "正文"
        ).toManifestEntry().copy(
            updatedAt = 100L,
            metadataHash = "local-metadata"
        )
        val remoteManifest = localManifest.copy(
            updatedAt = 90L,
            metadataHash = "remote-metadata"
        )

        val requests = LibrarySyncPayload.buildBodyRequestsForRemoteArticles(
            localManifest = listOf(localManifest),
            remoteManifest = listOf(remoteManifest)
        )

        assertEquals(emptyList<ArticleBodyRequest>(), requests)
    }

    @Test
    fun chunkedBodyRequest_ignoresSameTimestampMetadataDrift() {
        val localManifest = testArticle(
            articleId = "article-1",
            contentText = "正文"
        ).toManifestEntry().copy(
            sourceDeviceId = "a-device",
            updatedAt = 100L,
            metadataHash = "local-metadata"
        )
        val remoteManifest = localManifest.copy(
            sourceDeviceId = "z-device",
            metadataHash = "remote-metadata"
        )

        val requests = LibrarySyncPayload.buildBodyRequestsForRemoteArticles(
            localManifest = listOf(localManifest),
            remoteManifest = listOf(remoteManifest)
        )

        assertEquals(emptyList<ArticleBodyRequest>(), requests)
    }

    @Test
    fun chunkedBodyRequest_requestsMetadataOnlyForSavedBodyWhenPeerSupportsIt() {
        val remoteManifest = testArticle(
            articleId = "article-saved",
            contentText = "正文"
        ).toManifestEntry().copy(bodySyncMode = ARTICLE_BODY_SYNC_MODE_SAVED)

        val requests = LibrarySyncPayload.buildBodyRequestsForRemoteArticles(
            localManifest = emptyList(),
            remoteManifest = listOf(remoteManifest),
            supportsMetadataOnlyArticles = true
        )

        assertEquals(emptyList<Int>(), requests.single().chunkIndexes)
        assertTrue(requests.single().metadataOnly)
    }

    @Test
    fun chunkedBodyRequest_requestsMetadataOnlyForFullBodyWhenPeerSupportsIt() {
        val remoteManifest = testArticle(
            articleId = "article-full",
            contentText = "正文"
        ).toManifestEntry().copy(bodySyncMode = ARTICLE_BODY_SYNC_MODE_FULL)

        val requests = LibrarySyncPayload.buildBodyRequestsForRemoteArticles(
            localManifest = emptyList(),
            remoteManifest = listOf(remoteManifest),
            supportsMetadataOnlyArticles = true
        )

        assertEquals(emptyList<Int>(), requests.single().chunkIndexes)
        assertTrue(requests.single().metadataOnly)
    }

    @Test
    fun chunkedBodyRequest_requestsChunksForSavedBodyWhenPeerDoesNotSupportMetadataOnly() {
        val remoteManifest = testArticle(
            articleId = "article-saved",
            contentText = "正文"
        ).toManifestEntry().copy(bodySyncMode = ARTICLE_BODY_SYNC_MODE_SAVED)

        val requests = LibrarySyncPayload.buildBodyRequestsForRemoteArticles(
            localManifest = emptyList(),
            remoteManifest = listOf(remoteManifest),
            supportsMetadataOnlyArticles = false
        )

        assertEquals(remoteManifest.chunkHashes.indices.toList(), requests.single().chunkIndexes)
        assertEquals(false, requests.single().metadataOnly)
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
                    chunkIndexes = emptyList(),
                    metadataOnly = true
                )
            ),
            bodyRequests = emptyList(),
            useBatches = true
        )

        val parsed = LibrarySyncPayload.parseChunkedArticles(
            LibrarySyncPayload.combineArticlePayloads(frames)
        ).single()

        assertEquals(emptyList<Int>(), parsed.chunks.map { it.index })
        assertTrue(parsed.metadataOnly)
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

    private fun remoteManifestWithChunks(articleId: String, chunkHashes: List<String>): ArticleSyncManifestEntry {
        return ArticleSyncManifestEntry(
            articleId = articleId,
            sourceDeviceId = "watch",
            contentHash = "content-$articleId",
            updatedAt = 20L,
            independentChangedAt = 20L,
            favoriteChangedAt = 0L,
            watchLaterChangedAt = 0L,
            deletedAt = 0L,
            bodyHash = "body-$articleId",
            bodyByteCount = chunkHashes.size.toLong(),
            chunkSize = ArticleSyncBody.CHUNK_SIZE_BYTES,
            chunkHashes = chunkHashes,
            metadataHash = "metadata-$articleId"
        )
    }
}
