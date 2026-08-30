package com.lightningstudio.watchrss.phone.connection.bluetooth

import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
import com.lightningstudio.watchrss.phone.data.db.PhoneRssSourceEntity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LibrarySyncPayloadTest {
    @Test
    fun protocolV14_keepsV13WatchCompatibility() {
        assertEquals(14, LibrarySyncPayload.PROTOCOL_VERSION)
        assertEquals(13, LibrarySyncPayload.MIN_SUPPORTED_WATCH_PROTOCOL_VERSION)
        assertTrue(
            LibrarySyncPayload.buildProbeRequest("phone")
                .getBoolean(BluetoothSyncProtocol.FIELD_SUPPORTS_PERSISTENT_SESSION)
        )
    }

    @Test
    fun manifestFrames_splitAndReassemblePayloadLargerThanTransportFrame() {
        val manifest = JSONArray().also { array ->
            repeat(5_600) { index ->
                array.put(
                    JSONObject()
                        .put("articleId", "article-$index")
                        .put("contentHash", "hash-$index")
                        .put("metadataHash", "m".repeat(512))
                )
            }
        }
        val payload = JSONObject().apply {
            put("version", LibrarySyncPayload.PROTOCOL_VERSION)
            put("action", BluetoothSyncProtocol.ACTION_SYNC_LIBRARY)
            put("phase", LibrarySyncPayload.PHASE_MANIFEST)
            put("deviceId", "phone")
            put("sentAt", 123L)
            put("articleManifest", manifest)
            put("rssSources", JSONArray().put(JSONObject().put("url", "https://example.com/feed")))
        }

        assertTrue(BluetoothSyncProtocol.encodedSize(payload) > BluetoothSyncProtocol.MAX_FRAME_BYTES)
        val frames = LibrarySyncPayload.buildManifestFrames(payload)
        assertTrue(frames.size > 1)
        assertTrue(frames.all { BluetoothSyncProtocol.encodedSize(it) <= BluetoothSyncProtocol.MAX_FRAME_BYTES })

        val combined = LibrarySyncPayload.combineManifestFrames(frames)
        assertEquals(5_600, combined.getJSONArray("articleManifest").length())
        assertEquals(1, combined.getJSONArray("rssSources").length())
        assertEquals("phone", combined.getString("deviceId"))
    }

    @Test
    fun cursorHandshake_roundTripsDirectionalProgress() {
        val cursor = LibrarySyncCursor(
            localMaxSeq = 120L,
            lastRemoteSeqApplied = 78L,
            lastLocalSeqAckedByPeer = 91L
        )

        val request = LibrarySyncPayload.buildCursorRequest("phone-device", cursor)
        val response = LibrarySyncPayload.buildCursorResponse("watch-device", cursor)

        assertEquals(LibrarySyncPayload.PROTOCOL_VERSION, request.getInt("version"))
        assertEquals(BluetoothSyncProtocol.ACTION_SYNC_LIBRARY, request.getString("action"))
        assertEquals(LibrarySyncPayload.PHASE_CURSOR, request.getString("phase"))
        assertTrue(LibrarySyncPayload.supportsManifestBatches(request))
        assertEquals("phone-device", request.getString("deviceId"))
        assertEquals(cursor, LibrarySyncPayload.parseCursor(request))
        assertTrue(response.getBoolean("success"))
        assertTrue(LibrarySyncPayload.supportsManifestBatches(response))
        assertEquals("watch-device", response.getString("deviceId"))
        assertEquals(cursor, LibrarySyncPayload.parseCursor(response))
    }

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
            deletedAt = 0L,
            readingProgress = 0.42f,
            readingPositionBytes = 420L,
            readingPositionContentHash = "hash",
            readingPositionChangedAt = 23L
        )

        val request = LibrarySyncPayload.buildArticlesRequest("phone", listOf(article))
        val parsed = LibrarySyncPayload.parseArticles(request).single()

        assertEquals(article.contentHtml, parsed.contentHtml)
        assertEquals(article.contentText, parsed.contentText)
        assertTrue(parsed.independentSaved)
        assertTrue(parsed.favoriteSaved)
        assertEquals(article.readingProgress, parsed.readingProgress, 0.0001f)
        assertEquals(article.readingPositionBytes, parsed.readingPositionBytes)
        assertEquals(article.readingPositionContentHash, parsed.readingPositionContentHash)
        assertEquals(article.readingPositionChangedAt, parsed.readingPositionChangedAt)
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
            deletedAt = 0L,
            readingProgress = 0.36f,
            readingPositionBytes = 360L,
            readingPositionContentHash = "hash",
            readingPositionChangedAt = 24L
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
            ),
            cursor = LibrarySyncCursor(
                localMaxSeq = 9L,
                lastRemoteSeqApplied = 5L,
                lastLocalSeqAckedByPeer = 7L
            )
        )
        val manifest = LibrarySyncPayload.parseArticleManifest(request).single()
        val parsedSource = LibrarySyncPayload.parseRssSources(request).single()
        val changeSequence = LibrarySyncPayload.parseChangeSequence(request)
        val cursor = LibrarySyncPayload.parseCursor(request)

        assertEquals(0, LibrarySyncPayload.parseArticles(request).size)
        assertEquals(article.articleId, manifest.articleId)
        assertEquals(article.contentHash, manifest.contentHash)
        assertEquals(article.favoriteChangedAt, manifest.favoriteChangedAt)
        assertEquals(article.readingProgress, manifest.readingProgress, 0.0001f)
        assertEquals(article.readingPositionBytes, manifest.readingPositionBytes)
        assertEquals(article.readingPositionContentHash, manifest.readingPositionContentHash)
        assertEquals(article.readingPositionChangedAt, manifest.readingPositionChangedAt)
        assertEquals(source.title, parsedSource.title)
        assertEquals(source.isPinned, parsedSource.isPinned)
        assertTrue(LibrarySyncPayload.supportsChangeSequences(request))
        assertTrue(LibrarySyncPayload.supportsMetadataOnlyArticles(request))
        assertEquals(7L, changeSequence.fromSeqExclusive)
        assertEquals(9L, changeSequence.toSeqInclusive)
        assertEquals(false, changeSequence.fullSnapshot)
        assertEquals(9L, cursor.localMaxSeq)
        assertEquals(5L, cursor.lastRemoteSeqApplied)
        assertEquals(7L, cursor.lastLocalSeqAckedByPeer)
        assertEquals(LibrarySyncPayload.PROTOCOL_VERSION, request.getInt("version"))
        assertEquals("syncLibrary", request.getString("action"))
        assertEquals("manifest", request.getString("phase"))
    }

    @Test
    fun protocolFeatureChecks_acceptVersion8FeaturePayloadsAfterV10Upgrade() {
        val payload = JSONObject().apply {
            put("version", 8)
            put("supportsChangeSequences", true)
            put("supportsMetadataOnlyArticles", true)
        }

        assertTrue(LibrarySyncPayload.supportsChangeSequences(payload))
        assertTrue(LibrarySyncPayload.supportsMetadataOnlyArticles(payload))
    }

    @Test
    fun buildProbeRequest_marksCurrentProbeCapability() {
        val request = LibrarySyncPayload.buildProbeRequest("phone")
        val response = JSONObject().apply {
            put("success", true)
            put("version", LibrarySyncPayload.PROTOCOL_VERSION)
            put("action", BluetoothSyncProtocol.ACTION_SYNC_LIBRARY)
            put("phase", LibrarySyncPayload.PHASE_PROBE)
        }

        assertEquals(LibrarySyncPayload.PROTOCOL_VERSION, request.getInt("version"))
        assertEquals("syncLibrary", request.getString("action"))
        assertEquals(LibrarySyncPayload.PHASE_PROBE, request.getString("phase"))
        assertTrue(LibrarySyncPayload.supportsChangeSequences(request))
        assertTrue(LibrarySyncPayload.supportsMetadataOnlyArticles(request))
        assertTrue(LibrarySyncPayload.isProbeResponse(response))
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
        assertBatchWireByteHints(frames)

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
            deletedAt = 0L,
            readingProgress = 0.37f
        )
        val currentRemote = ArticleSyncManifestEntry(
            articleId = "article-1",
            contentHash = "hash",
            updatedAt = 20L,
            independentChangedAt = 0L,
            favoriteChangedAt = 30L,
            watchLaterChangedAt = 0L,
            deletedAt = 0L,
            readingProgress = 0.37f
        )
        val staleRemote = currentRemote.copy(favoriteChangedAt = 29L)
        val staleProgressRemote = currentRemote.copy(readingProgress = 0.21f)

        assertEquals(
            emptyList<PhoneArticleEntity>(),
            LibrarySyncPayload.filterArticlesNeedingSync(listOf(article), listOf(currentRemote))
        )
        assertEquals(
            listOf(article),
            LibrarySyncPayload.filterArticlesNeedingSync(listOf(article), listOf(staleRemote))
        )
        assertEquals(
            listOf(article),
            LibrarySyncPayload.filterArticlesNeedingSync(listOf(article), listOf(staleProgressRemote))
        )
    }

    @Test
    fun filterArticlesNeedingSync_usesPositionTimestampSoBackwardReadingWins() {
        val article = testArticle("article-backward", "正文").copy(
            readingProgress = 0.2f,
            readingPositionBytes = 200L,
            readingPositionContentHash = "hash-article-backward",
            readingPositionChangedAt = 200L
        )
        val olderRemote = ArticleSyncManifestEntry(
            articleId = article.articleId,
            contentHash = article.contentHash,
            updatedAt = article.updatedAt,
            independentChangedAt = article.independentChangedAt,
            favoriteChangedAt = article.favoriteChangedAt,
            watchLaterChangedAt = article.watchLaterChangedAt,
            deletedAt = article.deletedAt,
            readingProgress = 0.8f,
            readingPositionBytes = 800L,
            readingPositionContentHash = article.contentHash,
            readingPositionChangedAt = 100L
        )

        assertEquals(
            listOf(article),
            LibrarySyncPayload.filterArticlesNeedingSync(listOf(article), listOf(olderRemote))
        )
        assertEquals(
            emptyList<PhoneArticleEntity>(),
            LibrarySyncPayload.filterArticlesNeedingSync(
                listOf(article),
                listOf(
                    olderRemote.copy(
                        readingProgress = 0.2f,
                        readingPositionBytes = 200L,
                        readingPositionChangedAt = 200L
                    )
                )
            )
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
    fun chunkedBodyRequest_reusesChunksOnlyAtMatchingIndexes() {
        val remoteManifest = remoteManifestWithChunks(
            articleId = "article-positional",
            chunkHashes = listOf("remote-only", "shared")
        )
        val localManifest = remoteManifest.copy(
            bodyHash = "local-body",
            chunkHashes = listOf("shared", "local-only")
        )

        val requests = LibrarySyncPayload.buildBodyRequestsForRemoteArticles(
            localManifest = listOf(localManifest),
            remoteManifest = listOf(remoteManifest)
        )

        assertEquals(listOf(0, 1), requests.single().chunkIndexes)
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
    fun chunkedBodyRequest_requestsFullBodyWhenLocalManifestIsDeletedTombstone() {
        val remoteManifest = testArticle(
            articleId = "article-restored",
            contentText = "正文"
        ).toManifestEntry().copy(
            bodySyncMode = ARTICLE_BODY_SYNC_MODE_FULL,
            updatedAt = 100L,
            independentChangedAt = 100L,
            deleted = false,
            deletedAt = 50L
        )
        val localManifest = remoteManifest.copy(
            updatedAt = 50L,
            independentChangedAt = 50L,
            deleted = true,
            deletedAt = 50L
        )

        val requests = LibrarySyncPayload.buildBodyRequestsForRemoteArticles(
            localManifest = listOf(localManifest),
            remoteManifest = listOf(remoteManifest),
            supportsMetadataOnlyArticles = true
        )

        assertEquals(remoteManifest.chunkHashes.indices.toList(), requests.single().chunkIndexes)
        assertEquals(false, requests.single().metadataOnly)
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
    fun chunkedBodyRequest_requestsAllChunksForNewSavedArticle() {
        val remoteManifest = testArticle(
            articleId = "article-saved",
            contentText = "正文"
        ).toManifestEntry().copy(bodySyncMode = ARTICLE_BODY_SYNC_MODE_SAVED)

        val requests = LibrarySyncPayload.buildBodyRequestsForRemoteArticles(
            localManifest = emptyList(),
            remoteManifest = listOf(remoteManifest),
            supportsMetadataOnlyArticles = true
        )

        assertEquals(remoteManifest.chunkHashes.indices.toList(), requests.single().chunkIndexes)
        assertEquals(false, requests.single().metadataOnly)
    }

    @Test
    fun chunkedBodyRequest_requestsMetadataOnlyWhenSameBodyHasNewerMetadata() {
        val localManifest = testArticle(
            articleId = "article-saved-metadata",
            contentText = "同一正文"
        ).toManifestEntry().copy(
            updatedAt = 20L,
            independentChangedAt = 20L,
            metadataHash = "local-metadata",
            bodySyncMode = ARTICLE_BODY_SYNC_MODE_SAVED
        )
        val remoteManifest = localManifest.copy(
            updatedAt = 30L,
            independentChangedAt = 30L,
            metadataHash = "remote-metadata"
        )

        val request = LibrarySyncPayload.buildBodyRequestsForRemoteArticles(
            localManifest = listOf(localManifest),
            remoteManifest = listOf(remoteManifest),
            supportsMetadataOnlyArticles = true
        ).single()

        assertEquals(emptyList<Int>(), request.chunkIndexes)
        assertTrue(request.metadataOnly)
    }

    @Test
    fun chunkedBodyRequest_requestsAllChunksForSavedArticleWhenLocalBodyCannotBeReused() {
        val remoteManifest = remoteManifestWithChunks("saved-mismatch", listOf("a", "b")).copy(
            bodySyncMode = ARTICLE_BODY_SYNC_MODE_SAVED,
            updatedAt = 30L,
            metadataHash = "remote-metadata"
        )
        val matchingLocal = remoteManifest.copy(
            updatedAt = 20L,
            metadataHash = "local-metadata"
        )
        val unusableLocals = listOf(
            matchingLocal.copy(bodyAvailable = false),
            matchingLocal.copy(bodyHash = "different-body"),
            matchingLocal.copy(bodyByteCount = remoteManifest.bodyByteCount + 1L),
            matchingLocal.copy(chunkSize = remoteManifest.chunkSize + 1),
            matchingLocal.copy(chunkHashes = listOf("a", "different"))
        )

        unusableLocals.forEach { localManifest ->
            val request = LibrarySyncPayload.buildBodyRequestsForRemoteArticles(
                localManifest = listOf(localManifest),
                remoteManifest = listOf(remoteManifest),
                supportsMetadataOnlyArticles = true
            ).single()

            assertEquals(false, request.metadataOnly)
            assertEquals(remoteManifest.chunkHashes.indices.toList(), request.chunkIndexes)
        }
    }

    @Test
    fun chunkedBodyRequest_requestsChunksForFullBodyWhenPeerSupportsMetadataOnly() {
        val remoteManifest = testArticle(
            articleId = "article-full",
            contentText = "正文"
        ).toManifestEntry().copy(bodySyncMode = ARTICLE_BODY_SYNC_MODE_FULL)

        val requests = LibrarySyncPayload.buildBodyRequestsForRemoteArticles(
            localManifest = emptyList(),
            remoteManifest = listOf(remoteManifest),
            supportsMetadataOnlyArticles = true
        )

        assertEquals(remoteManifest.chunkHashes.indices.toList(), requests.single().chunkIndexes)
        assertEquals(false, requests.single().metadataOnly)
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
        assertEquals(metadata.bodyHash, parsed.bodyHash)
        assertEquals(metadata.bodyByteCount, parsed.bodyByteCount)
        assertEquals(metadata.chunkSize, parsed.chunkSize)
        assertEquals(metadata.chunkHashes, parsed.chunkHashes)
    }

    @Test
    fun chunkedBodyRequest_metadataOnlyFallsBackToFullBodyAfterManifestDrift() {
        val manifestArticle = testArticle(
            articleId = "metadata-only-drift",
            contentText = "manifest body"
        )
        val manifestMetadata = ArticleSyncBody.metadataFor(manifestArticle)
        val currentArticle = manifestArticle.copy(contentText = "current body")
        val currentMetadata = ArticleSyncBody.metadataFor(currentArticle)
        val frames = LibrarySyncPayload.buildChunkedArticleRequestFrames(
            deviceId = "phone",
            articles = listOf(currentArticle),
            articleRequests = listOf(
                ArticleBodyRequest(
                    articleId = currentArticle.articleId,
                    bodyHash = manifestMetadata.bodyHash,
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

        assertEquals(false, parsed.metadataOnly)
        assertEquals(currentMetadata.bodyHash, parsed.bodyHash)
        assertEquals(currentMetadata.chunkHashes.indices.toList(), parsed.chunks.map { it.index })
        assertEquals(currentArticle.contentText, ArticleSyncBody.rebuildBody(null, parsed).second)
    }

    @Test
    fun chunkedBodyRequest_rebuildsMetadataWhenCachedChunkHashIsStale() {
        val original = testArticle(
            articleId = "stale-body-cache",
            contentText = "A".repeat(4096)
        )
        val cachedMetadata = ArticleSyncBody.metadataFor(original)
        val changed = original.copy(contentText = "B".repeat(4096))
        val currentMetadata = ArticleSyncBody.metadataFor(changed)
        assertEquals(cachedMetadata.bodyByteCount, currentMetadata.bodyByteCount)

        val payload = ArticleSyncBody.payloadForRequest(
            article = changed,
            request = ArticleBodyRequest(
                articleId = changed.articleId,
                bodyHash = cachedMetadata.bodyHash,
                chunkIndexes = cachedMetadata.chunkHashes.indices.toList()
            ),
            cachedMetadata = cachedMetadata
        )
        val rebuilt = ArticleSyncBody.rebuildBody(null, payload)

        assertEquals(currentMetadata.bodyHash, payload.bodyHash)
        assertEquals(currentMetadata.chunkHashes, payload.chunkHashes)
        assertEquals(changed.contentText, rebuilt.second)
    }

    @Test
    fun cachedBodyMetadata_isRejectedWhenOnlyBodyContentChanges() {
        val original = testArticle(
            articleId = "stale-body-metadata",
            contentText = "A".repeat(4096)
        )
        val cached = ArticleSyncBody.metadataFor(original)
        val changed = original.copy(
            contentText = "B".repeat(4096),
            syncBodyHash = cached.bodyHash,
            syncBodyByteCount = cached.bodyByteCount,
            syncChunkSize = cached.chunkSize,
            syncChunkHashesJson = JSONArray(cached.chunkHashes).toString(),
            syncMetadataHash = cached.metadataHash
        )

        assertEquals(null, ArticleSyncBody.cachedMetadataFor(changed))
        assertEquals(ArticleSyncBody.metadataFor(changed), ArticleSyncBody.currentMetadataFor(changed))
    }

    @Test
    fun rebuildBody_doesNotTrustStaleStoredLocalHash() {
        val remoteArticle = testArticle(
            articleId = "stale-local-body-hash",
            contentText = "远端正文".repeat(4096)
        )
        val metadata = ArticleSyncBody.metadataFor(remoteArticle)
        val payload = ArticleSyncBody.payloadForRequest(
            article = remoteArticle,
            request = ArticleBodyRequest(
                articleId = remoteArticle.articleId,
                bodyHash = metadata.bodyHash,
                chunkIndexes = metadata.chunkHashes.indices.toList()
            ),
            cachedMetadata = metadata
        )
        val localArticle = remoteArticle.copy(
            contentText = "错误的本地正文",
            syncBodyHash = metadata.bodyHash
        )

        val rebuilt = ArticleSyncBody.rebuildBody(localArticle, payload)

        assertEquals(remoteArticle.contentText, rebuilt.second)
    }

    @Test
    fun rebuildBody_rejectsConflictingBodyByteCount() {
        val article = testArticle(
            articleId = "body-byte-count-conflict",
            contentText = "正文".repeat(4096)
        )
        val metadata = ArticleSyncBody.metadataFor(article)
        val payload = ArticleSyncBody.payloadForRequest(
            article = article,
            request = ArticleBodyRequest(
                articleId = article.articleId,
                bodyHash = metadata.bodyHash,
                chunkIndexes = metadata.chunkHashes.indices.toList()
            ),
            cachedMetadata = metadata
        ).copy(bodyByteCount = metadata.bodyByteCount + 1L)

        assertIllegalArgumentContains("长度不匹配") {
            ArticleSyncBody.rebuildBody(null, payload)
        }
    }

    @Test
    fun chunkedBodyRequest_sendsFullBodyForFullArticleWhenPeerRequestsReusableBody() {
        val article = testArticle(
            articleId = "full-body-reuse",
            contentText = "正文".repeat(4096)
        ).copy(independentSaved = true)
        val metadata = ArticleSyncBody.metadataFor(article)
        val frames = LibrarySyncPayload.buildChunkedArticleRequestFrames(
            deviceId = "phone",
            articles = listOf(article),
            articleRequests = listOf(
                ArticleBodyRequest(
                    articleId = article.articleId,
                    bodyHash = metadata.bodyHash,
                    chunkIndexes = emptyList(),
                    metadataOnly = false
                )
            ),
            bodyRequests = emptyList(),
            useBatches = true
        )

        val parsed = LibrarySyncPayload.parseChunkedArticles(
            LibrarySyncPayload.combineArticlePayloads(frames)
        ).single()
        val rebuilt = ArticleSyncBody.rebuildBody(null, parsed)

        assertEquals(false, parsed.metadataOnly)
        assertEquals(metadata.chunkHashes.indices.toList(), parsed.chunks.map { it.index })
        assertEquals(article.contentText, rebuilt.second)
    }

    @Test
    fun chunkedBodyRequest_expandsEmptyExplicitBodyRequestForSavedArticle() {
        val article = testArticle(
            articleId = "saved-empty-request",
            contentText = pseudoRandomText(seed = 321, length = ArticleSyncBody.CHUNK_SIZE_BYTES * 2)
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
                    metadataOnly = false
                )
            ),
            bodyRequests = emptyList(),
            useBatches = true
        )

        val parsed = LibrarySyncPayload.parseChunkedArticles(
            LibrarySyncPayload.combineArticlePayloads(frames)
        ).single()

        assertEquals(false, parsed.metadataOnly)
        assertEquals(metadata.chunkHashes.indices.toList(), parsed.chunks.map { it.index })
    }

    @Test
    fun payloadForRequest_sendsCurrentFullBodyWhenRequestHashIsBlankOrStale() {
        val article = testArticle(
            articleId = "body-drift",
            contentText = pseudoRandomText(seed = 654, length = ArticleSyncBody.CHUNK_SIZE_BYTES * 2)
        )
        val metadata = ArticleSyncBody.metadataFor(article)

        listOf("", "stale-manifest-body-hash").forEach { requestedBodyHash ->
            val payload = ArticleSyncBody.payloadForRequest(
                article = article,
                request = ArticleBodyRequest(
                    articleId = article.articleId,
                    bodyHash = requestedBodyHash,
                    chunkIndexes = listOf(0),
                    metadataOnly = false
                ),
                cachedMetadata = metadata
            )

            assertEquals(metadata.bodyHash, payload.bodyHash)
            assertEquals(metadata.chunkHashes.indices.toList(), payload.chunks.map { it.index })
        }
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
        assertBatchWireByteHints(frames)
        assertEquals(article.contentText, rebuilt.second)
    }

    @Test
    fun chunkedResponse_sendsProgressHeaderBeforeLargeBodyFrames() {
        val article = testArticle(
            articleId = "response-huge",
            contentText = pseudoRandomText(
                seed = 7,
                length = BluetoothSyncProtocol.MAX_FRAME_BYTES + ArticleSyncBody.CHUNK_SIZE_BYTES
            )
        )
        val metadata = ArticleSyncBody.metadataFor(article)

        val frames = LibrarySyncPayload.buildChunkedResponseFrames(
            deviceId = "phone",
            articles = listOf(article),
            articleRequests = listOf(
                ArticleBodyRequest(
                    articleId = article.articleId,
                    bodyHash = metadata.bodyHash,
                    chunkIndexes = metadata.chunkHashes.indices.toList()
                )
            ),
            useBatches = true
        )
        val combined = LibrarySyncPayload.combineArticlePayloads(frames)
        val parsed = LibrarySyncPayload.parseChunkedArticles(combined).single()
        val rebuilt = ArticleSyncBody.rebuildBody(null, parsed)

        assertTrue(frames.size > 2)
        assertResponseProgressHeader(frames, totalArticles = 1)
        assertTrue(frames.all { BluetoothSyncProtocol.encodedSize(it) <= BluetoothSyncProtocol.MAX_FRAME_BYTES })
        assertBatchWireByteHints(frames)
        assertEquals(article.contentText, rebuilt.second)
    }

    @Test
    fun wireParser_rejectsPositionalChunkHashGapsAndNonStrings() {
        val article = testArticle(articleId = "strict-hashes", contentText = "正文")
        val payload = LibrarySyncPayload.buildManifestRequest("phone", listOf(article))
        val item = payload.getJSONArray("articleManifest").getJSONObject(0)

        listOf<Any>("", 7).forEach { invalid ->
            item.put("chunkHashes", JSONArray().put("first").put(invalid).put("third"))
            assertIllegalArgumentContains("strict-hashes.chunkHashes[1]") {
                LibrarySyncPayload.parseArticleManifest(payload)
            }
        }
    }

    @Test
    fun wireParser_rejectsConflictingBodyByteCountAcrossArticleFrames() {
        val article = testArticle(articleId = "byte-count-frames", contentText = "正文")
        val metadata = ArticleSyncBody.metadataFor(article)
        val frames = LibrarySyncPayload.buildChunkedResponseFrames(
            deviceId = "phone",
            articles = listOf(article),
            articleRequests = listOf(ArticleBodyRequest(article.articleId, metadata.bodyHash, listOf(0))),
            useBatches = true
        )
        val item = LibrarySyncPayload.combineArticlePayloads(frames)
            .getJSONArray("articles")
            .getJSONObject(0)
        val conflicting = JSONObject(item.toString()).apply {
            getJSONObject("body").put("bodyByteCount", metadata.bodyByteCount + 1L)
        }

        assertIllegalArgumentContains("byte-count-frames") {
            LibrarySyncPayload.parseChunkedArticles(JSONArray().put(item).put(conflicting))
        }
    }

    @Test
    fun wireParser_allowsOnlyIdenticalDuplicateChunks() {
        val article = testArticle(articleId = "duplicate-chunk", contentText = "正文")
        val metadata = ArticleSyncBody.metadataFor(article)
        val frames = LibrarySyncPayload.buildChunkedResponseFrames(
            deviceId = "phone",
            articles = listOf(article),
            articleRequests = listOf(ArticleBodyRequest(article.articleId, metadata.bodyHash, listOf(0))),
            useBatches = true
        )
        val item = LibrarySyncPayload.combineArticlePayloads(frames)
            .getJSONArray("articles")
            .getJSONObject(0)
        val identical = JSONObject(item.toString())
        assertEquals(
            1,
            LibrarySyncPayload.parseChunkedArticles(JSONArray().put(item).put(identical))
                .single()
                .chunks
                .size
        )

        val conflicting = JSONObject(item.toString()).apply {
            getJSONObject("body").getJSONArray("chunks").getJSONObject(0).put("data", "AA==")
        }
        assertIllegalArgumentContains("重复分块冲突：duplicate-chunk#0") {
            LibrarySyncPayload.parseChunkedArticles(JSONArray().put(item).put(conflicting))
        }
    }

    @Test
    fun wireParser_rejectsNegativeAndNonIntegerBodyRequestIndexes() {
        listOf<Any>(-1, 1.0).forEach { invalid ->
            val request = JSONObject().apply {
                put(
                    "bodyRequests",
                    JSONArray().put(
                        JSONObject()
                            .put("articleId", "bad-request-index")
                            .put("chunkIndexes", JSONArray().put(invalid))
                    )
                )
            }
            assertIllegalArgumentContains("bad-request-index.chunkIndexes[0]") {
                LibrarySyncPayload.parseBodyRequests(request)
            }
        }
    }

    @Test
    fun bodyResponder_rejectsOutOfBoundsRequestedChunkIndex() {
        val article = testArticle(articleId = "out-of-bounds", contentText = "正文")
        val metadata = ArticleSyncBody.metadataFor(article)

        assertIllegalArgumentContains("out-of-bounds#${metadata.chunkHashes.size}") {
            ArticleSyncBody.payloadForRequest(
                article,
                ArticleBodyRequest(
                    articleId = article.articleId,
                    bodyHash = metadata.bodyHash,
                    chunkIndexes = listOf(metadata.chunkHashes.size)
                ),
                metadata
            )
        }
    }

    @Test
    fun bodyRequester_rejectsMalformedManifestButAcceptsCanonicalEmptyShape() {
        val valid = remoteManifestWithChunks("manifest-shape", listOf("chunk"))
        listOf(
            valid.copy(bodyHash = ""),
            valid.copy(bodyByteCount = -1L),
            valid.copy(chunkSize = 0),
            valid.copy(chunkHashes = emptyList())
        ).forEach { invalid ->
            assertIllegalArgumentContains("manifest-shape") {
                LibrarySyncPayload.buildBodyRequestsForRemoteArticles(emptyList(), listOf(invalid))
            }
        }

        val canonicalEmpty = valid.copy(bodyByteCount = 0L, chunkHashes = listOf("empty-chunk"))
        assertEquals(
            listOf(0),
            LibrarySyncPayload.buildBodyRequestsForRemoteArticles(emptyList(), listOf(canonicalEmpty))
                .single()
                .chunkIndexes
        )
    }

    private fun assertResponseProgressHeader(frames: List<JSONObject>, totalArticles: Int) {
        val header = frames.first()
        assertEquals(0, header.getInt("batchIndex"))
        assertEquals(frames.size, header.getInt("batchCount"))
        assertEquals(totalArticles, header.getInt("totalArticles"))
        assertEquals(0, header.getJSONArray("articles").length())
        assertTrue(header.has(LibrarySyncPayload.FIELD_BATCH_WIRE_BYTES))
        assertTrue(header.has(LibrarySyncPayload.FIELD_BATCH_TOTAL_WIRE_BYTES))
        frames.drop(1).forEachIndexed { index, frame ->
            assertEquals(index + 1, frame.getInt("batchIndex"))
            assertEquals(frames.size, frame.getInt("batchCount"))
        }
    }

    private fun assertBatchWireByteHints(frames: List<JSONObject>) {
        val hintedTotalWireBytes = frames.sumOf { it.getLong(LibrarySyncPayload.FIELD_BATCH_WIRE_BYTES) }
        frames.forEach { frame ->
            val actualWireBytes = BluetoothSyncProtocol.wireSize(frame)
            val hintedWireBytes = frame.getLong(LibrarySyncPayload.FIELD_BATCH_WIRE_BYTES)
            val delta = if (hintedWireBytes > actualWireBytes) {
                hintedWireBytes - actualWireBytes
            } else {
                actualWireBytes - hintedWireBytes
            }
            assertTrue("batchWireBytes should stay close to actual wire bytes", delta <= maxOf(16 * 1024L, actualWireBytes / 5L))
            assertEquals(
                hintedTotalWireBytes,
                frame.getLong(LibrarySyncPayload.FIELD_BATCH_TOTAL_WIRE_BYTES)
            )
        }
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

    private fun assertIllegalArgumentContains(expected: String, block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException containing $expected")
        } catch (exception: IllegalArgumentException) {
            assertTrue(exception.message.orEmpty().contains(expected))
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
            chunkSize = 1,
            chunkHashes = chunkHashes,
            metadataHash = "metadata-$articleId"
        )
    }
}
