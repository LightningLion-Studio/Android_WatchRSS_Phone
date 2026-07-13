package com.lightningstudio.watchrss.phone.connection.bluetooth

import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
import com.lightningstudio.watchrss.phone.data.db.PhoneRssSourceEntity
import com.lightningstudio.watchrss.phone.data.model.ImportedContentIds
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

const val ARTICLE_BODY_SYNC_MODE_FULL = "full"
const val ARTICLE_BODY_SYNC_MODE_SAVED = "saved"

data class LibrarySyncStats(
    val sent: Int,
    val received: Int,
    val merged: Int,
    val sourcesSent: Int = 0,
    val sourcesReceived: Int = 0,
    val sourcesMerged: Int = 0
)

data class ArticleSyncManifestEntry(
    val articleId: String,
    val sourceDeviceId: String = "",
    val contentHash: String,
    val updatedAt: Long,
    val independentChangedAt: Long,
    val favoriteChangedAt: Long,
    val watchLaterChangedAt: Long,
    val deletedAt: Long,
    val deleted: Boolean = deletedAt > 0L,
    val bodyHash: String = contentHash,
    val bodyByteCount: Long = 0L,
    val chunkSize: Int = 0,
    val chunkHashes: List<String> = emptyList(),
    val metadataHash: String = "",
    val bodyAvailable: Boolean = true,
    val bodySyncMode: String = ARTICLE_BODY_SYNC_MODE_FULL,
    val readingProgress: Float = 0f
)

data class LibraryChangeSequence(
    val fromSeqExclusive: Long,
    val toSeqInclusive: Long,
    val fullSnapshot: Boolean,
    val fallbackReason: String = ""
)

object LibrarySyncPayload {
    const val PROTOCOL_VERSION = 10
    const val LEGACY_PROTOCOL_VERSION = 4
    const val MAX_BODY_REQUEST_CHUNKS_PER_SYNC = Int.MAX_VALUE
    const val MAX_ARTICLE_REQUEST_BATCH_COUNT = 256
    const val FIELD_BATCH_WIRE_BYTES = "batchWireBytes"
    const val FIELD_BATCH_TOTAL_WIRE_BYTES = "batchTotalWireBytes"
    const val PHASE_MANIFEST = "manifest"
    const val PHASE_PROBE = "probe"
    const val PHASE_ARTICLES = "articles"
    const val PHASE_COMPLETE = "complete"
    private const val FIELD_SUPPORTS_TRANSFER_BYTE_PROGRESS = "supportsTransferByteProgress"

    fun buildProbeRequest(deviceId: String): JSONObject {
        return JSONObject().apply {
            put("version", PROTOCOL_VERSION)
            put("action", BluetoothSyncProtocol.ACTION_SYNC_LIBRARY)
            put("phase", PHASE_PROBE)
            put("supportsArticleBatches", true)
            put("supportsChunkedBodies", true)
            put("supportsChangeSequences", true)
            put("supportsMetadataOnlyArticles", true)
            put(FIELD_SUPPORTS_TRANSFER_BYTE_PROGRESS, true)
            put("deviceId", deviceId)
            put("sentAt", System.currentTimeMillis())
        }
    }

    fun isProbeResponse(payload: JSONObject): Boolean {
        return payload.optBoolean("success", false) &&
            payload.optString("action") == BluetoothSyncProtocol.ACTION_SYNC_LIBRARY &&
            payload.optString("phase") == PHASE_PROBE
    }

    fun buildRequest(
        deviceId: String,
        articles: List<PhoneArticleEntity>,
        rssSources: List<PhoneRssSourceEntity> = emptyList()
    ): JSONObject = buildManifestRequest(deviceId, articles, rssSources)

    fun buildManifestRequest(
        deviceId: String,
        articles: List<PhoneArticleEntity>,
        rssSources: List<PhoneRssSourceEntity> = emptyList(),
        changeSequence: LibraryChangeSequence? = null
    ): JSONObject {
        return JSONObject().apply {
            put("version", PROTOCOL_VERSION)
            put("action", BluetoothSyncProtocol.ACTION_SYNC_LIBRARY)
            put("phase", PHASE_MANIFEST)
            put("supportsArticleBatches", true)
            put("supportsChunkedBodies", true)
            put("supportsChangeSequences", true)
            put("supportsMetadataOnlyArticles", true)
            put(FIELD_SUPPORTS_TRANSFER_BYTE_PROGRESS, true)
            put("deviceId", deviceId)
            put("sentAt", System.currentTimeMillis())
            put("articleManifest", articles.toManifestJsonArray())
            put("rssSources", rssSources.toSourceJsonArray())
            putChangeSequence(changeSequence)
        }
    }

    fun buildManifestRequestFromEntries(
        deviceId: String,
        articleManifest: List<ArticleSyncManifestEntry>,
        rssSources: List<PhoneRssSourceEntity> = emptyList(),
        changeSequence: LibraryChangeSequence? = null
    ): JSONObject {
        return JSONObject().apply {
            put("version", PROTOCOL_VERSION)
            put("action", BluetoothSyncProtocol.ACTION_SYNC_LIBRARY)
            put("phase", PHASE_MANIFEST)
            put("supportsArticleBatches", true)
            put("supportsChunkedBodies", true)
            put("supportsChangeSequences", true)
            put("supportsMetadataOnlyArticles", true)
            put(FIELD_SUPPORTS_TRANSFER_BYTE_PROGRESS, true)
            put("deviceId", deviceId)
            put("sentAt", System.currentTimeMillis())
            put("articleManifest", articleManifest.toEntryJsonArray())
            put("rssSources", rssSources.toSourceJsonArray())
            putChangeSequence(changeSequence)
        }
    }

    fun buildManifestResponse(
        deviceId: String,
        articles: List<PhoneArticleEntity>,
        rssSources: List<PhoneRssSourceEntity> = emptyList(),
        stats: JSONObject? = null,
        changeSequence: LibraryChangeSequence? = null
    ): JSONObject {
        return JSONObject().apply {
            put("success", true)
            put("version", PROTOCOL_VERSION)
            put("action", BluetoothSyncProtocol.ACTION_SYNC_LIBRARY)
            put("phase", PHASE_MANIFEST)
            put("supportsArticleBatches", true)
            put("supportsChunkedBodies", true)
            put("supportsChangeSequences", true)
            put("supportsMetadataOnlyArticles", true)
            put(FIELD_SUPPORTS_TRANSFER_BYTE_PROGRESS, true)
            put("deviceId", deviceId)
            put("sentAt", System.currentTimeMillis())
            put("articleManifest", articles.toManifestJsonArray())
            put("rssSources", rssSources.toSourceJsonArray())
            putChangeSequence(changeSequence)
            if (stats != null) {
                put("stats", stats)
            }
        }
    }

    fun buildArticlesRequest(
        deviceId: String,
        articles: List<PhoneArticleEntity>,
        batchIndex: Int? = null,
        batchCount: Int? = null,
        totalArticles: Int? = null
    ): JSONObject {
        return JSONObject().apply {
            put("version", PROTOCOL_VERSION)
            put("action", BluetoothSyncProtocol.ACTION_SYNC_LIBRARY)
            put("phase", PHASE_ARTICLES)
            put("deviceId", deviceId)
            put("sentAt", System.currentTimeMillis())
            put("articles", articles.toJsonArray())
            putBatchFields(batchIndex, batchCount, totalArticles)
        }
    }

    fun buildArticleRequestFrames(
        deviceId: String,
        articles: List<PhoneArticleEntity>,
        useBatches: Boolean
    ): List<JSONObject> {
        if (!useBatches) {
            return listOf(buildArticlesRequest(deviceId, articles))
        }
        return buildArticleFrames(
            articleItems = articles.asSequence().map { it.toJson() },
            totalArticles = articles.size
        ) { array, batchIndex, batchCount, totalArticles ->
            JSONObject().apply {
                put("version", PROTOCOL_VERSION)
                put("action", BluetoothSyncProtocol.ACTION_SYNC_LIBRARY)
                put("phase", PHASE_ARTICLES)
                put("deviceId", deviceId)
                put("sentAt", System.currentTimeMillis())
                put("articles", array)
                putBatchFields(batchIndex, batchCount, totalArticles)
            }
        }
    }

    fun buildChunkedArticleRequestFrames(
        deviceId: String,
        articles: List<PhoneArticleEntity>,
        articleRequests: List<ArticleBodyRequest>,
        bodyRequests: List<ArticleBodyRequest>,
        useBatches: Boolean
    ): List<JSONObject> {
        val requestById = articleRequests.associateBy { it.articleId }
        val articleItems = articles.asSequence().flatMap { article ->
            article.toChunkedJsonItemSequence(requestById[article.articleId])
        }
        if (!useBatches) {
            return listOf(buildChunkedArticlesRequest(deviceId, articleItems.toList(), bodyRequests))
        }
        return buildArticleFrames(
            articleItems = articleItems,
            totalArticles = null
        ) { array, batchIndex, batchCount, totalArticles ->
            JSONObject().apply {
                put("version", PROTOCOL_VERSION)
                put("action", BluetoothSyncProtocol.ACTION_SYNC_LIBRARY)
                put("phase", PHASE_ARTICLES)
                put("deviceId", deviceId)
                put("sentAt", System.currentTimeMillis())
                put("articles", array)
                if (batchIndex == 0) {
                    put("bodyRequests", bodyRequests.toBodyRequestJsonArray())
                }
                putBatchFields(batchIndex, batchCount, totalArticles)
            }
        }
    }

    fun buildEmptyArticlesProbeRequest(deviceId: String): JSONObject =
        buildChunkedArticlesRequest(
            deviceId = deviceId,
            articleItems = emptyList(),
            bodyRequests = emptyList()
        )

    fun buildChunkedResponseFrames(
        deviceId: String,
        articles: List<PhoneArticleEntity>,
        articleRequests: List<ArticleBodyRequest>,
        stats: JSONObject? = null,
        useBatches: Boolean
    ): List<JSONObject> {
        val requestById = articleRequests.associateBy { it.articleId }
        val articleItems = articles.asSequence().flatMap { article ->
            article.toChunkedJsonItemSequence(requestById[article.articleId])
        }
        if (!useBatches) {
            return listOf(buildChunkedResponse(deviceId, articleItems.toList(), stats))
        }
        return buildArticleFrames(
            articleItems = articleItems,
            totalArticles = null
        ) { array, batchIndex, batchCount, totalArticles ->
            JSONObject().apply {
                put("success", true)
                put("version", PROTOCOL_VERSION)
                put("action", BluetoothSyncProtocol.ACTION_SYNC_LIBRARY)
                put("phase", PHASE_COMPLETE)
                put("deviceId", deviceId)
                put("sentAt", System.currentTimeMillis())
                put("articles", array)
                putBatchFields(batchIndex, batchCount, totalArticles)
                if (batchIndex == 0 && stats != null) {
                    put("stats", stats)
                }
            }
        }.withResponseProgressHeader(
            deviceId = deviceId,
            totalArticles = articles.size,
            stats = stats
        )
    }

    fun combineArticlePayloads(frames: List<JSONObject>): JSONObject {
        if (frames.isEmpty()) return JSONObject()
        if (frames.size == 1 && !frames.first().optBoolean("success", true)) return frames.first()
        val first = frames.first()
        val articles = JSONArray()
        val sources = JSONArray()
        val bodyRequests = JSONArray()
        frames.forEach { frame ->
            frame.optJSONArray("articles")?.let { source ->
                for (index in 0 until source.length()) {
                    source.optJSONObject(index)?.let(articles::put)
                }
            }
            frame.optJSONArray("rssSources")?.let { source ->
                for (index in 0 until source.length()) {
                    source.optJSONObject(index)?.let(sources::put)
                }
            }
            frame.optJSONArray("bodyRequests")?.let { source ->
                for (index in 0 until source.length()) {
                    source.optJSONObject(index)?.let(bodyRequests::put)
                }
            }
        }
        return JSONObject().apply {
            put("success", frames.all { it.optBoolean("success", true) })
            put("version", first.optInt("version", PROTOCOL_VERSION))
            put("action", BluetoothSyncProtocol.ACTION_SYNC_LIBRARY)
            put("phase", first.optString("phase").ifBlank { PHASE_COMPLETE })
            put("deviceId", first.optString("deviceId"))
            put("sentAt", first.optLong("sentAt"))
            put("articles", articles)
            if (sources.length() > 0) {
                put("rssSources", sources)
            }
            if (bodyRequests.length() > 0) {
                put("bodyRequests", bodyRequests)
            }
            first.optJSONObject("stats")?.let { put("stats", it) }
            first.optString("message").takeIf { it.isNotBlank() }?.let { put("message", it) }
        }
    }

    fun buildResponse(
        deviceId: String,
        articles: List<PhoneArticleEntity>,
        rssSources: List<PhoneRssSourceEntity> = emptyList(),
        stats: JSONObject? = null
    ): JSONObject {
        return JSONObject().apply {
            put("success", true)
            put("version", PROTOCOL_VERSION)
            put("action", BluetoothSyncProtocol.ACTION_SYNC_LIBRARY)
            put("phase", PHASE_COMPLETE)
            put("deviceId", deviceId)
            put("sentAt", System.currentTimeMillis())
            put("articles", articles.toJsonArray())
            put("rssSources", rssSources.toSourceJsonArray())
            if (stats != null) {
                put("stats", stats)
            }
        }
    }

    fun parseArticleManifest(payload: JSONObject): List<ArticleSyncManifestEntry> {
        return parseArticleManifest(payload.optJSONArray("articleManifest") ?: JSONArray())
    }

    fun parseArticleManifest(array: JSONArray): List<ArticleSyncManifestEntry> {
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val articleId = item.optString("articleId").trim()
                if (articleId.isBlank()) continue
                add(
                    ArticleSyncManifestEntry(
                        articleId = articleId,
                        sourceDeviceId = item.optString("sourceDeviceId").trim(),
                        contentHash = item.optString("contentHash").trim(),
                        updatedAt = item.optLong("updatedAt"),
                        independentChangedAt = item.optLong("independentChangedAt"),
                        favoriteChangedAt = item.optLong("favoriteChangedAt"),
                        watchLaterChangedAt = item.optLong("watchLaterChangedAt"),
                        deletedAt = item.optLong("deletedAt"),
                        deleted = item.optBoolean("deleted", item.optLong("deletedAt") > 0L),
                        bodyHash = item.optString("bodyHash").trim().ifBlank {
                            item.optString("contentHash").trim()
                        },
                        bodyByteCount = item.optLong("bodyByteCount"),
                        chunkSize = item.optInt("chunkSize"),
                        chunkHashes = item.optStringArray("chunkHashes"),
                        metadataHash = item.optString("metadataHash").trim(),
                        bodyAvailable = item.optBoolean("bodyAvailable", true),
                        bodySyncMode = item.optString("bodySyncMode")
                            .trim()
                            .ifBlank { ARTICLE_BODY_SYNC_MODE_FULL },
                        readingProgress = item.optDouble("readingProgress", 0.0)
                            .toFloat()
                            .coerceIn(0f, 1f)
                    )
                )
            }
        }
    }

    fun buildBodyRequestsForRemoteArticles(
        localManifest: List<ArticleSyncManifestEntry>,
        remoteManifest: List<ArticleSyncManifestEntry>,
        maxBodyRequestChunks: Int = Int.MAX_VALUE,
        supportsMetadataOnlyArticles: Boolean = false
    ): List<ArticleBodyRequest> {
        val localById = localManifest.associateBy { it.articleId }
        return remoteManifest.mapNotNull { remote ->
            val local = localById[remote.articleId]
            val remoteMetadataNewer = local == null ||
                remote.updatedAt > local.updatedAt
            val needsMetadata = if (remote.deleted) {
                local == null ||
                    !local.deleted ||
                    remote.deletedAt > local.deletedAt ||
                    remote.independentChangedAt > local.independentChangedAt ||
                    remote.favoriteChangedAt > local.favoriteChangedAt ||
                    remote.watchLaterChangedAt > local.watchLaterChangedAt
            } else {
                local == null ||
                    (remote.metadataHash != local.metadataHash && remoteMetadataNewer) ||
                    remote.updatedAt > local.updatedAt ||
                    remote.independentChangedAt > local.independentChangedAt ||
                    remote.favoriteChangedAt > local.favoriteChangedAt ||
                    remote.watchLaterChangedAt > local.watchLaterChangedAt ||
                    remote.deletedAt > local.deletedAt ||
                    remote.deleted != local.deleted ||
                    remote.readingProgress.isMeaningfullyAheadOf(local.readingProgress)
            }
            val hasReusableLocalBody = local?.canReuseLocalBodyFor(remote) == true
            val shouldRequestMetadataOnlyBody = remote.shouldRequestMetadataOnlyBody(
                supportsMetadataOnlyArticles = supportsMetadataOnlyArticles
            )
            val metadataOnly = needsMetadata && shouldRequestMetadataOnlyBody
            if (!remote.deleted && !remote.bodyAvailable && !hasReusableLocalBody) {
                return@mapNotNull null
            }
            val needsBody = !remote.deleted &&
                remote.bodyAvailable &&
                !hasReusableLocalBody &&
                !shouldRequestMetadataOnlyBody
            if (!needsMetadata && !needsBody) return@mapNotNull null
            val localHashes = if (local?.canReuseLocalChunksFor(remote) == true) {
                local.chunkHashes.toSet()
            } else {
                emptySet()
            }
            val chunkIndexes = if (needsBody) {
                remote.chunkHashes.mapIndexedNotNull { index, hash ->
                    index.takeIf { hash !in localHashes }
                }
            } else {
                emptyList()
            }
            ArticleBodyRequest(
                articleId = remote.articleId,
                bodyHash = remote.bodyHash,
                chunkIndexes = chunkIndexes,
                metadataOnly = metadataOnly
            )
        }.limitBodyRequestChunks(maxBodyRequestChunks)
    }

    private fun ArticleSyncManifestEntry.shouldRequestMetadataOnlyBody(
        supportsMetadataOnlyArticles: Boolean
    ): Boolean {
        return supportsMetadataOnlyArticles &&
            !deleted &&
            bodyAvailable &&
            bodySyncMode == ARTICLE_BODY_SYNC_MODE_SAVED
    }

    private fun ArticleSyncManifestEntry.canReuseLocalBodyFor(
        remote: ArticleSyncManifestEntry
    ): Boolean {
        return !deleted &&
            bodyAvailable &&
            remote.bodyAvailable &&
            bodyHash.isNotBlank() &&
            bodyHash == remote.bodyHash &&
            bodyByteCount == remote.bodyByteCount &&
            chunkSize > 0 &&
            chunkSize == remote.chunkSize &&
            chunkHashes.isNotEmpty() &&
            chunkHashes == remote.chunkHashes
    }

    private fun ArticleSyncManifestEntry.canReuseLocalChunksFor(
        remote: ArticleSyncManifestEntry
    ): Boolean {
        return !deleted &&
            bodyAvailable &&
            remote.bodyAvailable &&
            chunkSize > 0 &&
            chunkSize == remote.chunkSize &&
            chunkHashes.isNotEmpty()
    }

    fun parseBodyRequests(payload: JSONObject): List<ArticleBodyRequest> {
        val array = payload.optJSONArray("bodyRequests") ?: JSONArray()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val articleId = item.optString("articleId").trim()
                if (articleId.isBlank()) continue
                add(
                    ArticleBodyRequest(
                        articleId = articleId,
                        bodyHash = item.optString("bodyHash").trim(),
                        chunkIndexes = item.optIntArray("chunkIndexes"),
                        metadataOnly = item.optBoolean("metadataOnly", false)
                    )
                )
            }
        }
    }

    fun filterArticlesNeedingSync(
        localArticles: List<PhoneArticleEntity>,
        remoteManifest: List<ArticleSyncManifestEntry>
    ): List<PhoneArticleEntity> {
        val remoteById = remoteManifest.associateBy { it.articleId }
        return localArticles.filter { article ->
            val remote = remoteById[article.articleId] ?: return@filter true
            article.contentHash != remote.contentHash ||
                article.updatedAt > remote.updatedAt ||
                article.independentChangedAt > remote.independentChangedAt ||
                article.favoriteChangedAt > remote.favoriteChangedAt ||
                article.watchLaterChangedAt > remote.watchLaterChangedAt ||
                article.deletedAt > remote.deletedAt ||
                article.deleted != remote.deleted ||
                article.readingProgress.isMeaningfullyAheadOf(remote.readingProgress)
        }
    }

    fun parseRssSources(payload: JSONObject): List<PhoneRssSourceEntity> {
        return parseRssSources(payload.optJSONArray("rssSources") ?: JSONArray())
    }

    fun supportsChangeSequences(payload: JSONObject): Boolean {
        return payload.optBoolean("supportsChangeSequences", false) &&
            payload.optInt("version") >= CHANGE_SEQUENCE_PROTOCOL_VERSION
    }

    fun supportsMetadataOnlyArticles(payload: JSONObject): Boolean {
        return payload.optBoolean("supportsMetadataOnlyArticles", false) &&
            payload.optInt("version") >= METADATA_ONLY_ARTICLES_PROTOCOL_VERSION
    }

    fun parseChangeSequence(payload: JSONObject): LibraryChangeSequence {
        val range = payload.optJSONObject("changeSeqRange") ?: JSONObject()
        return LibraryChangeSequence(
            fromSeqExclusive = range.optLong("fromExclusive"),
            toSeqInclusive = range.optLong("toInclusive"),
            fullSnapshot = payload.optBoolean("fullSnapshot", true),
            fallbackReason = payload.optString("fallbackReason").trim()
        )
    }

    fun parseRssSources(array: JSONArray): List<PhoneRssSourceEntity> {
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val url = item.optString("url").trim()
                if (url.isBlank()) continue
                add(
                    PhoneRssSourceEntity(
                        url = url,
                        sourceDeviceId = item.optString("sourceDeviceId").ifBlank {
                            item.optString("deviceId")
                        },
                        title = item.optString("title").trim().ifBlank { url },
                        description = item.optString("description").trim(),
                        siteUrl = item.optString("siteUrl").trim().ifBlank { null },
                        imageUrl = item.optString("imageUrl").trim().ifBlank { null },
                        createdAt = item.optLong("createdAt"),
                        updatedAt = item.optLong("updatedAt"),
                        sortOrder = item.optLong("sortOrder"),
                        isPinned = item.optBoolean("isPinned"),
                        deleted = item.optBoolean("deleted"),
                        deletedAt = item.optLong("deletedAt")
                    )
                )
            }
        }
    }

    fun parseArticles(payload: JSONObject): List<PhoneArticleEntity> {
        return parseArticles(payload.optJSONArray("articles") ?: JSONArray())
    }

    fun parseChunkedArticles(payload: JSONObject): List<ChunkedArticlePayload> {
        return parseChunkedArticles(payload.optJSONArray("articles") ?: JSONArray())
    }

    fun parseChunkedArticles(array: JSONArray): List<ChunkedArticlePayload> {
        val byArticleId = linkedMapOf<String, ChunkedArticlePayload>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val article = parseArticles(JSONArray().put(item)).firstOrNull() ?: continue
            val body = item.optJSONObject("body") ?: JSONObject()
            val chunks = body.optJSONArray("chunks") ?: JSONArray()
            val payload = ChunkedArticlePayload(
                article = article,
                bodyHash = body.optString("bodyHash").trim().ifBlank { article.contentHash },
                bodyByteCount = body.optLong("bodyByteCount"),
                chunkSize = body.optInt("chunkSize"),
                chunkHashes = body.optStringArray("chunkHashes"),
                metadataOnly = body.optBoolean("metadataOnly", false),
                chunks = buildList {
                    for (chunkIndex in 0 until chunks.length()) {
                        val chunk = chunks.optJSONObject(chunkIndex) ?: continue
                        val encoded = chunk.optString("data").takeIf { it.isNotBlank() } ?: continue
                        add(
                            ArticleBodyChunk(
                                index = chunk.optInt("index"),
                                hash = chunk.optString("hash").trim(),
                                bytes = ArticleSyncBody.decodeChunkData(encoded)
                            )
                        )
                    }
                }
            )
            val existing = byArticleId[article.articleId]
            byArticleId[article.articleId] = if (existing == null) {
                payload
            } else {
                require(
                    existing.bodyHash == payload.bodyHash &&
                        existing.chunkSize == payload.chunkSize &&
                        existing.chunkHashes == payload.chunkHashes &&
                        existing.metadataOnly == payload.metadataOnly
                ) {
                    "同步正文分块元数据冲突：${article.articleId}"
                }
                existing.copy(
                    article = payload.article,
                    chunks = (existing.chunks + payload.chunks)
                        .distinctBy { it.index }
                        .sortedBy { it.index }
                )
            }
        }
        return byArticleId.values.toList()
    }

    fun parseArticles(array: JSONArray): List<PhoneArticleEntity> {
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val articleId = item.optString("articleId").trim()
                val url = item.optString("url").trim()
                if (articleId.isBlank() || url.isBlank()) continue
                add(
                    PhoneArticleEntity(
                        articleId = articleId,
                        sourceDeviceId = item.optString("sourceDeviceId").ifBlank {
                            item.optString("deviceId")
                        },
                        url = url,
                        title = item.optString("title").trim().ifBlank { url },
                        siteName = item.optString("siteName").trim(),
                        excerpt = item.optString("excerpt").trim(),
                        contentHtml = item.optCompressedString("contentHtmlGzip"),
                        contentText = item.optCompressedString("contentTextGzip").orEmpty(),
                        imageUrl = item.optString("imageUrl").trim().ifBlank { null },
                        contentHash = item.optString("contentHash").trim(),
                        importedAt = item.optLong("importedAt"),
                        updatedAt = item.optLong("updatedAt"),
                        independentSaved = item.optBoolean(
                            "independentSaved",
                            !item.optBoolean("favoriteSaved") &&
                                !item.optBoolean("watchLaterSaved") &&
                                !item.optBoolean("deleted")
                        ),
                        independentChangedAt = item.optLong("independentChangedAt"),
                        independentSortOrder = item.optLong("independentSortOrder"),
                        rssSourceUrl = item.optString("rssSourceUrl").trim().ifBlank { null },
                        rssSourceTitle = item.optString("rssSourceTitle").trim().ifBlank { null },
                        favoriteSaved = item.optBoolean("favoriteSaved"),
                        favoriteChangedAt = item.optLong("favoriteChangedAt"),
                        favoriteSortOrder = item.optLong("favoriteSortOrder"),
                        watchLaterSaved = item.optBoolean("watchLaterSaved"),
                        watchLaterChangedAt = item.optLong("watchLaterChangedAt"),
                        watchLaterSortOrder = item.optLong("watchLaterSortOrder"),
                        deleted = item.optBoolean("deleted"),
                        deletedAt = item.optLong("deletedAt"),
                        readingProgress = item.optDouble("readingProgress", 0.0)
                            .toFloat()
                            .coerceIn(0f, 1f)
                    )
                )
            }
        }
    }

    private fun List<PhoneRssSourceEntity>.toSourceJsonArray(): JSONArray {
        return JSONArray().also { array ->
            forEach { source ->
                array.put(source.toJson())
            }
        }
    }

    private fun List<PhoneArticleEntity>.toManifestJsonArray(): JSONArray {
        return JSONArray().also { array ->
            forEach { article ->
                array.put(article.toManifestJson())
            }
        }
    }

    private fun List<PhoneArticleEntity>.toJsonArray(): JSONArray {
        return JSONArray().also { array ->
            forEach { article ->
                array.put(article.toJson())
            }
        }
    }

    private fun List<JSONObject>.toRawJsonArray(): JSONArray {
        return JSONArray().also { array ->
            forEach(array::put)
        }
    }

    private data class SizedArticleItem(
        val payload: JSONObject,
        val estimatedBytes: Long
    )

    private fun buildArticleFrames(
        articleItems: Sequence<JSONObject>,
        totalArticles: Int?,
        buildPayload: (JSONArray, Int, Int, Int) -> JSONObject
    ): List<JSONObject> {
        val chunks = mutableListOf<List<SizedArticleItem>>()
        var current = mutableListOf<SizedArticleItem>()
        var currentBytes = 0L
        var articleItemCount = 0
        articleItems.forEach { article ->
            articleItemCount += 1
            val item = SizedArticleItem(
                payload = article,
                estimatedBytes = article.estimatedArticleItemBytes()
            )
            val articleSize = item.estimatedBytes
            if (current.isNotEmpty() && currentBytes + articleSize > ARTICLE_BATCH_TARGET_BYTES) {
                chunks += current
                current = mutableListOf(item)
                currentBytes = articleSize
            } else {
                current.add(item)
                currentBytes += articleSize
            }
        }
        if (current.isNotEmpty()) {
            chunks += current
        }
        val resolvedTotalArticles = totalArticles ?: articleItemCount
        if (chunks.isEmpty()) {
            return listOf(buildPayload(JSONArray(), 0, 1, resolvedTotalArticles))
                .withBatchWireByteHints()
        }

        while (true) {
            val batchCount = chunks.size.coerceAtLeast(1)
            val payloads = chunks.mapIndexed { index, chunk ->
                buildPayload(chunk.toPayloadJsonArray(), index, batchCount, resolvedTotalArticles)
            }
            val estimatedPayloadBytes = chunks.map { chunk ->
                estimateArticleFramePayloadBytes(
                    articleBytes = chunk.sumOf { it.estimatedBytes },
                    itemCount = chunk.size
                )
            }
            val oversizedIndex = estimatedPayloadBytes.indexOfFirst { payloadBytes ->
                payloadBytes > BluetoothSyncProtocol.MAX_FRAME_BYTES
            }
            if (oversizedIndex < 0) return payloads.withEstimatedBatchWireByteHints(estimatedPayloadBytes)
            val oversized = chunks[oversizedIndex]
            require(oversized.size > 1) {
                val item = oversized.first()
                val payloadSize = estimatedPayloadBytes[oversizedIndex]
                "单篇文章蓝牙消息过大：${item.payload.optString("title").ifBlank { item.payload.optString("url") }.take(40)}（约 $payloadSize 字节）"
            }
            val midpoint = oversized.size / 2
            chunks[oversizedIndex] = oversized.take(midpoint)
            chunks.add(oversizedIndex + 1, oversized.drop(midpoint))
        }
    }

    private fun List<JSONObject>.withBatchWireByteHints(): List<JSONObject> {
        if (isEmpty()) return this
        return withEstimatedBatchWireByteHints(map { BluetoothSyncProtocol.encodedSize(it).toLong() })
    }

    private fun List<JSONObject>.withEstimatedBatchWireByteHints(estimatedPayloadBytes: List<Long>): List<JSONObject> {
        if (isEmpty()) return this
        require(size == estimatedPayloadBytes.size) {
            "蓝牙批次大小估算数量不匹配：payloads=$size estimates=${estimatedPayloadBytes.size}"
        }
        val wireBytes = estimatedPayloadBytes.map { payloadBytes ->
            payloadBytes + ESTIMATED_BATCH_WIRE_HINT_BYTES + BluetoothSyncProtocol.LENGTH_PREFIX_BYTES
        }
        val totalWireBytes = wireBytes.sum()
        forEachIndexed { index, payload ->
            payload.put(FIELD_BATCH_WIRE_BYTES, wireBytes[index])
            payload.put(FIELD_BATCH_TOTAL_WIRE_BYTES, totalWireBytes)
        }
        return this
    }

    private fun List<JSONObject>.withResponseProgressHeader(
        deviceId: String,
        totalArticles: Int?,
        stats: JSONObject?
    ): List<JSONObject> {
        if (!needsResponseProgressHeader()) return this
        val batchCount = size + 1
        val header = JSONObject().apply {
            put("success", true)
            put("version", PROTOCOL_VERSION)
            put("action", BluetoothSyncProtocol.ACTION_SYNC_LIBRARY)
            put("phase", PHASE_COMPLETE)
            put("deviceId", deviceId)
            put("sentAt", System.currentTimeMillis())
            put("articles", JSONArray())
            putBatchFields(batchIndex = 0, batchCount = batchCount, totalArticles = totalArticles)
            stats?.let { put("stats", it) }
        }
        val frames = ArrayList<JSONObject>(batchCount)
        frames += header
        val estimatedPayloadBytes = ArrayList<Long>(batchCount)
        estimatedPayloadBytes += BluetoothSyncProtocol.encodedSize(header).toLong()
        forEachIndexed { index, payload ->
            payload.putBatchFields(batchIndex = index + 1, batchCount = batchCount, totalArticles = totalArticles)
            frames += payload
            estimatedPayloadBytes += payload.estimatedPayloadBytesFromHint()
        }
        return frames.withEstimatedBatchWireByteHints(estimatedPayloadBytes)
    }

    private fun List<JSONObject>.needsResponseProgressHeader(): Boolean =
        size > 1 || any { payload ->
            payload.optLong(FIELD_BATCH_WIRE_BYTES, -1L)
                .takeIf { it > 0L }
                ?.let { it > RESPONSE_PROGRESS_HEADER_MIN_BODY_BYTES }
                ?: (BluetoothSyncProtocol.encodedSize(payload) > RESPONSE_PROGRESS_HEADER_MIN_BODY_BYTES)
        }

    private fun JSONObject.estimatedPayloadBytesFromHint(): Long {
        val hintedWireBytes = optLong(FIELD_BATCH_WIRE_BYTES, -1L)
        if (hintedWireBytes > 0L) {
            return (hintedWireBytes - BluetoothSyncProtocol.LENGTH_PREFIX_BYTES - ESTIMATED_BATCH_WIRE_HINT_BYTES)
                .coerceAtLeast(0L)
        }
        return BluetoothSyncProtocol.encodedSize(this).toLong()
    }

    private fun List<SizedArticleItem>.toPayloadJsonArray(): JSONArray {
        return JSONArray().also { array ->
            forEach { item -> array.put(item.payload) }
        }
    }

    private fun JSONObject.estimatedArticleItemBytes(): Long {
        val body = optJSONObject("body") ?: return BluetoothSyncProtocol.encodedSize(this).toLong()
        val chunks = body.optJSONArray("chunks") ?: return BluetoothSyncProtocol.encodedSize(this).toLong()
        var dataBytes = 0L
        for (index in 0 until chunks.length()) {
            dataBytes += chunks.optJSONObject(index)?.optString("data")?.length ?: 0
        }
        if (dataBytes <= 0L) return BluetoothSyncProtocol.encodedSize(this).toLong()
        if (dataBytes <= EXACT_CHUNKED_ARTICLE_SIZE_MAX_BYTES) {
            return BluetoothSyncProtocol.encodedSize(this).toLong()
        }
        return dataBytes + ESTIMATED_CHUNKED_ARTICLE_JSON_OVERHEAD_BYTES
    }

    private fun estimateArticleFramePayloadBytes(articleBytes: Long, itemCount: Int): Long {
        val arrayCommaBytes = (itemCount - 1).coerceAtLeast(0).toLong()
        return articleBytes + arrayCommaBytes + ESTIMATED_FRAME_JSON_OVERHEAD_BYTES
    }

    private fun JSONObject.putBatchFields(
        batchIndex: Int?,
        batchCount: Int?,
        totalArticles: Int?
    ) {
        if (batchIndex != null && batchCount != null) {
            put("batchIndex", batchIndex)
            put("batchCount", batchCount)
        }
        if (totalArticles != null) {
            put("totalArticles", totalArticles)
        }
    }

    private fun JSONObject.putChangeSequence(changeSequence: LibraryChangeSequence?) {
        if (changeSequence == null) return
        put("supportsChangeSequences", true)
        put("fullSnapshot", changeSequence.fullSnapshot)
        put("fallbackReason", changeSequence.fallbackReason)
        put(
            "changeSeqRange",
            JSONObject().apply {
                put("fromExclusive", changeSequence.fromSeqExclusive)
                put("toInclusive", changeSequence.toSeqInclusive)
            }
        )
    }

    private fun PhoneArticleEntity.toManifestJson(): JSONObject {
        return JSONObject().apply {
            put("articleId", articleId)
            put("sourceDeviceId", sourceDeviceId)
            put("contentHash", contentHash)
            put("updatedAt", updatedAt)
            put("independentChangedAt", independentChangedAt)
            put("favoriteChangedAt", favoriteChangedAt)
            put("watchLaterChangedAt", watchLaterChangedAt)
            put("deletedAt", deletedAt)
            put("deleted", deleted)
            put("bodyHash", syncBodyHash.ifBlank { contentHash })
            put("bodyByteCount", syncBodyByteCount)
            put("chunkSize", syncChunkSize)
            put("chunkHashes", JSONArray(syncChunkHashesJson.optJsonStringList()))
            put("metadataHash", syncMetadataHash.ifBlank { ArticleSyncBody.metadataHashFor(this@toManifestJson) })
            put("bodyAvailable", true)
            put("bodySyncMode", bodySyncModeForSync())
            put("readingProgress", readingProgress.coerceIn(0f, 1f))
        }
    }

    private fun List<ArticleSyncManifestEntry>.toEntryJsonArray(): JSONArray {
        return JSONArray().also { array ->
            forEach { entry ->
                array.put(entry.toJson())
            }
        }
    }

    private fun ArticleSyncManifestEntry.toJson(): JSONObject {
        return JSONObject().apply {
            put("articleId", articleId)
            put("sourceDeviceId", sourceDeviceId)
            put("contentHash", contentHash)
            put("updatedAt", updatedAt)
            put("independentChangedAt", independentChangedAt)
            put("favoriteChangedAt", favoriteChangedAt)
            put("watchLaterChangedAt", watchLaterChangedAt)
            put("deletedAt", deletedAt)
            put("deleted", deleted)
            put("bodyHash", bodyHash)
            put("bodyByteCount", bodyByteCount)
            put("chunkSize", chunkSize)
            put("chunkHashes", JSONArray(chunkHashes))
            put("metadataHash", metadataHash)
            put("bodyAvailable", bodyAvailable)
            put("bodySyncMode", bodySyncMode)
            put("readingProgress", readingProgress.coerceIn(0f, 1f))
        }
    }

    private fun PhoneArticleEntity.toJson(includeBody: Boolean = true): JSONObject {
        return JSONObject().apply {
            put("articleId", articleId)
            put("sourceDeviceId", sourceDeviceId)
            put("url", url)
            put("title", title)
            put("siteName", siteName)
            put("excerpt", excerpt)
            if (includeBody) {
                putCompressedString("contentHtmlGzip", contentHtml)
                putCompressedString("contentTextGzip", contentText)
            }
            put("imageUrl", imageUrl)
            put("contentHash", contentHash)
            put("importedAt", importedAt)
            put("updatedAt", updatedAt)
            put("independentSaved", independentSaved)
            put("independentChangedAt", independentChangedAt)
            put("independentSortOrder", independentSortOrder)
            put("rssSourceUrl", rssSourceUrl)
            put("rssSourceTitle", rssSourceTitle)
            put("favoriteSaved", favoriteSaved)
            put("favoriteChangedAt", favoriteChangedAt)
            put("favoriteSortOrder", favoriteSortOrder)
            put("watchLaterSaved", watchLaterSaved)
            put("watchLaterChangedAt", watchLaterChangedAt)
            put("watchLaterSortOrder", watchLaterSortOrder)
            put("deleted", deleted)
            put("deletedAt", deletedAt)
            put("readingProgress", readingProgress.coerceIn(0f, 1f))
        }
    }

    private fun PhoneArticleEntity.toChunkedJsonItemSequence(request: ArticleBodyRequest?): Sequence<JSONObject> = sequence {
        val article = this@toChunkedJsonItemSequence
        val metadata = ArticleSyncBody.currentMetadataFor(article)
        val bodyRequest = request.resolvedBodyRequestFor(article, metadata)
        val payload = ArticleSyncBody.payloadForRequest(article, bodyRequest, metadata)
        if (payload.chunks.isEmpty()) {
            yield(article.toChunkedJson(payload, emptyList()))
        } else {
            payload.chunks.forEach { chunk ->
                yield(article.toChunkedJson(payload, listOf(chunk)))
            }
        }
    }

    private fun ArticleBodyRequest?.resolvedBodyRequestFor(
        article: PhoneArticleEntity,
        metadata: ArticleBodyMetadata
    ): ArticleBodyRequest {
        if (this == null) {
            return ArticleBodyRequest(
                articleId = article.articleId,
                bodyHash = metadata.bodyHash,
                chunkIndexes = metadata.chunkHashes.indices.toList()
            )
        }
        if (
            !metadataOnly &&
            chunkIndexes.isEmpty() &&
            article.bodySyncModeForSync() == ARTICLE_BODY_SYNC_MODE_FULL
        ) {
            return copy(
                bodyHash = bodyHash.ifBlank { metadata.bodyHash },
                chunkIndexes = metadata.chunkHashes.indices.toList()
            )
        }
        return this
    }

    private fun PhoneArticleEntity.toChunkedJson(
        payload: ChunkedArticlePayload,
        chunks: List<ArticleBodyChunk>
    ): JSONObject {
        return toJson(includeBody = false).apply {
            put(
                "body",
                JSONObject().apply {
                    put("bodyHash", payload.bodyHash)
                    put("bodyByteCount", payload.bodyByteCount)
                    put("chunkSize", payload.chunkSize)
                    put("chunkHashes", JSONArray(payload.chunkHashes))
                    put("metadataOnly", payload.metadataOnly)
                    put(
                        "chunks",
                        JSONArray().also { array ->
                            chunks.forEach { chunk ->
                                array.put(
                                    JSONObject().apply {
                                        put("index", chunk.index)
                                        put("hash", chunk.hash)
                                        put("data", ArticleSyncBody.encodeChunkData(chunk.bytes))
                                    }
                                )
                            }
                        }
                    )
                }
            )
        }
    }

    private fun buildChunkedArticlesRequest(
        deviceId: String,
        articleItems: List<JSONObject>,
        bodyRequests: List<ArticleBodyRequest>
    ): JSONObject {
        return JSONObject().apply {
            put("version", PROTOCOL_VERSION)
            put("action", BluetoothSyncProtocol.ACTION_SYNC_LIBRARY)
            put("phase", PHASE_ARTICLES)
            put("deviceId", deviceId)
            put("sentAt", System.currentTimeMillis())
            put("articles", articleItems.toRawJsonArray())
            put("bodyRequests", bodyRequests.toBodyRequestJsonArray())
        }
    }

    private fun buildChunkedResponse(
        deviceId: String,
        articleItems: List<JSONObject>,
        stats: JSONObject?
    ): JSONObject {
        return JSONObject().apply {
            put("success", true)
            put("version", PROTOCOL_VERSION)
            put("action", BluetoothSyncProtocol.ACTION_SYNC_LIBRARY)
            put("phase", PHASE_COMPLETE)
            put("deviceId", deviceId)
            put("sentAt", System.currentTimeMillis())
            put("articles", articleItems.toRawJsonArray())
            if (stats != null) {
                put("stats", stats)
            }
        }
    }

    private fun List<ArticleBodyRequest>.toBodyRequestJsonArray(): JSONArray {
        return JSONArray().also { array ->
            forEach { request ->
                array.put(
                    JSONObject().apply {
                        put("articleId", request.articleId)
                        put("bodyHash", request.bodyHash)
                        put("chunkIndexes", JSONArray(request.chunkIndexes))
                        put("metadataOnly", request.metadataOnly)
                    }
                )
            }
        }
    }

    private fun List<ArticleBodyRequest>.limitBodyRequestChunks(maxChunks: Int): List<ArticleBodyRequest> {
        if (maxChunks == Int.MAX_VALUE) return this
        if (maxChunks <= 0) return filter { it.chunkIndexes.isEmpty() }
        var usedChunks = 0
        val limited = mutableListOf<ArticleBodyRequest>()
        for (request in this) {
            val chunkCount = request.chunkIndexes.size
            if (chunkCount == 0) {
                limited += request
                continue
            }
            if (usedChunks == 0 && chunkCount > maxChunks) {
                limited += request
                usedChunks += chunkCount
                continue
            }
            if (usedChunks + chunkCount > maxChunks) continue
            limited += request
            usedChunks += chunkCount
        }
        return limited
    }

    private fun PhoneArticleEntity.bodySyncModeForSync(): String {
        return if (
            independentSaved ||
            ImportedContentIds.isImportedContentUrl(url) ||
            ImportedContentIds.isImportedContentUrl(rssSourceUrl)
        ) {
            ARTICLE_BODY_SYNC_MODE_FULL
        } else {
            ARTICLE_BODY_SYNC_MODE_SAVED
        }
    }

    private fun Float.isMeaningfullyAheadOf(other: Float): Boolean {
        return coerceIn(0f, 1f) > other.coerceIn(0f, 1f) + READING_PROGRESS_SYNC_EPSILON
    }

    private fun JSONObject.optStringArray(name: String): List<String> {
        val array = optJSONArray(name) ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                array.optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun JSONObject.optIntArray(name: String): List<Int> {
        val array = optJSONArray(name) ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                add(array.optInt(index))
            }
        }
    }

    private fun String.optJsonStringList(): List<String> {
        if (isBlank()) return emptyList()
        val array = runCatching { JSONArray(this) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                array.optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun PhoneRssSourceEntity.toJson(): JSONObject {
        return JSONObject().apply {
            put("url", url)
            put("sourceDeviceId", sourceDeviceId)
            put("title", title)
            put("description", description)
            put("siteUrl", siteUrl)
            put("imageUrl", imageUrl)
            put("createdAt", createdAt)
            put("updatedAt", updatedAt)
            put("sortOrder", sortOrder)
            put("isPinned", isPinned)
            put("deleted", deleted)
            put("deletedAt", deletedAt)
        }
    }

    private fun JSONObject.putCompressedString(name: String, value: String?) {
        val safe = value?.takeIf { it.isNotBlank() } ?: return
        put(name, Base64.getEncoder().encodeToString(gzip(safe)))
    }

    private fun JSONObject.optCompressedString(name: String): String? {
        val encoded = optString(name).takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            gunzip(Base64.getDecoder().decode(encoded))
        }.getOrNull()
    }

    private fun gzip(value: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { gzip ->
            gzip.write(value.toByteArray(Charsets.UTF_8))
        }
        return out.toByteArray()
    }

    private fun gunzip(bytes: ByteArray): String {
        return GZIPInputStream(ByteArrayInputStream(bytes)).use { gzip ->
            gzip.readBytes().toString(Charsets.UTF_8)
        }
    }

    private const val ARTICLE_BATCH_TARGET_BYTES = 512 * 1024
    private const val RESPONSE_PROGRESS_HEADER_MIN_BODY_BYTES = 16 * 1024
    private const val EXACT_CHUNKED_ARTICLE_SIZE_MAX_BYTES = 64 * 1024L
    private const val ESTIMATED_CHUNKED_ARTICLE_JSON_OVERHEAD_BYTES = 8 * 1024L
    private const val ESTIMATED_FRAME_JSON_OVERHEAD_BYTES = 2 * 1024L
    private const val ESTIMATED_BATCH_WIRE_HINT_BYTES = 160L
    private const val CHANGE_SEQUENCE_PROTOCOL_VERSION = 8
    private const val METADATA_ONLY_ARTICLES_PROTOCOL_VERSION = 8
    private const val READING_PROGRESS_SYNC_EPSILON = 0.001f
}
