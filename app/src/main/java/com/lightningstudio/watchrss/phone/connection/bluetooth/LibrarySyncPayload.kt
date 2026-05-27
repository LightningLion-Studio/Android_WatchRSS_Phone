package com.lightningstudio.watchrss.phone.connection.bluetooth

import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
import com.lightningstudio.watchrss.phone.data.db.PhoneRssSourceEntity
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

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
    val metadataHash: String = ""
)

data class LibraryChangeSequence(
    val fromSeqExclusive: Long,
    val toSeqInclusive: Long,
    val fullSnapshot: Boolean,
    val fallbackReason: String = ""
)

object LibrarySyncPayload {
    const val PROTOCOL_VERSION = 6
    const val LEGACY_PROTOCOL_VERSION = 4
    const val MAX_BODY_REQUEST_CHUNKS_PER_SYNC = 24

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
            articleItems = articles.map { it.toJson() },
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
        val articleItems = articles.flatMap { article ->
            article.toChunkedJsonItems(requestById[article.articleId])
        }
        if (!useBatches) {
            return listOf(buildChunkedArticlesRequest(deviceId, articleItems, bodyRequests))
        }
        return buildArticleFrames(
            articleItems = articleItems,
            totalArticles = articleItems.size
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

    fun buildChunkedResponseFrames(
        deviceId: String,
        articles: List<PhoneArticleEntity>,
        articleRequests: List<ArticleBodyRequest>,
        stats: JSONObject? = null,
        useBatches: Boolean
    ): List<JSONObject> {
        val requestById = articleRequests.associateBy { it.articleId }
        val articleItems = articles.flatMap { article ->
            article.toChunkedJsonItems(requestById[article.articleId])
        }
        if (!useBatches) {
            return listOf(buildChunkedResponse(deviceId, articleItems, stats))
        }
        return buildArticleFrames(
            articleItems = articleItems,
            totalArticles = articleItems.size
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
        }
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
                        metadataHash = item.optString("metadataHash").trim()
                    )
                )
            }
        }
    }

    fun buildBodyRequestsForRemoteArticles(
        localManifest: List<ArticleSyncManifestEntry>,
        remoteManifest: List<ArticleSyncManifestEntry>,
        maxBodyRequestChunks: Int = Int.MAX_VALUE
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
                    remote.deleted != local.deleted
            }
            val hasReusableLocalBody = local != null &&
                remote.bodyHash == local.bodyHash &&
                local.chunkHashes.isNotEmpty()
            val needsBody = !remote.deleted && !hasReusableLocalBody
            if (!needsMetadata && !needsBody) return@mapNotNull null
            val localHashes = local?.chunkHashes.orEmpty().toSet()
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
                chunkIndexes = chunkIndexes
            )
        }.limitBodyRequestChunks(maxBodyRequestChunks)
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
                        chunkIndexes = item.optIntArray("chunkIndexes")
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
                article.deleted != remote.deleted
        }
    }

    fun parseRssSources(payload: JSONObject): List<PhoneRssSourceEntity> {
        return parseRssSources(payload.optJSONArray("rssSources") ?: JSONArray())
    }

    fun supportsChangeSequences(payload: JSONObject): Boolean {
        return payload.optBoolean("supportsChangeSequences", false) &&
            payload.optInt("version") >= PROTOCOL_VERSION
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
                        existing.chunkHashes == payload.chunkHashes
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
                        deletedAt = item.optLong("deletedAt")
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

    private fun buildArticleFrames(
        articleItems: List<JSONObject>,
        totalArticles: Int,
        buildPayload: (JSONArray, Int, Int, Int) -> JSONObject
    ): List<JSONObject> {
        if (articleItems.isEmpty()) {
            return listOf(buildPayload(JSONArray(), 0, 1, totalArticles))
        }

        val chunks = mutableListOf<List<JSONObject>>()
        var current = mutableListOf<JSONObject>()
        var currentBytes = 0
        val articleSizes = articleItems.map(BluetoothSyncProtocol::encodedSize)
        articleItems.forEachIndexed { index, article ->
            val articleSize = articleSizes[index]
            if (current.isNotEmpty() && currentBytes + articleSize > ARTICLE_BATCH_TARGET_BYTES) {
                chunks += current
                current = mutableListOf(article)
                currentBytes = articleSize
            } else {
                current.add(article)
                currentBytes += articleSize
            }
        }
        if (current.isNotEmpty()) {
            chunks += current
        }

        while (true) {
            val batchCount = chunks.size.coerceAtLeast(1)
            val payloads = chunks.mapIndexed { index, chunk ->
                buildPayload(chunk.toRawJsonArray(), index, batchCount, totalArticles)
            }
            val oversizedIndex = payloads.indexOfFirst { payload ->
                BluetoothSyncProtocol.encodedSize(payload) > BluetoothSyncProtocol.MAX_FRAME_BYTES
            }
            if (oversizedIndex < 0) return payloads
            val oversized = chunks[oversizedIndex]
            require(oversized.size > 1) {
                val item = oversized.first()
                val payloadSize = BluetoothSyncProtocol.encodedSize(payloads[oversizedIndex])
                "单篇文章蓝牙消息过大：${item.optString("title").ifBlank { item.optString("url") }.take(40)}（$payloadSize 字节）"
            }
            val midpoint = oversized.size / 2
            chunks[oversizedIndex] = oversized.take(midpoint)
            chunks.add(oversizedIndex + 1, oversized.drop(midpoint))
        }
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
        }
    }

    private fun PhoneArticleEntity.toChunkedJsonItems(request: ArticleBodyRequest?): List<JSONObject> {
        val metadata = ArticleSyncBody.metadataFor(this)
        val bodyRequest = request ?: ArticleBodyRequest(
            articleId = articleId,
            bodyHash = metadata.bodyHash,
            chunkIndexes = metadata.chunkHashes.indices.toList()
        )
        val chunks = ArticleSyncBody.chunksForRequest(this, bodyRequest)
        if (chunks.isEmpty()) {
            return listOf(toChunkedJson(metadata, emptyList()))
        }
        return chunks.map { chunk -> toChunkedJson(metadata, listOf(chunk)) }
    }

    private fun PhoneArticleEntity.toChunkedJson(
        metadata: ArticleBodyMetadata,
        chunks: List<ArticleBodyChunk>
    ): JSONObject {
        return toJson(includeBody = false).apply {
            put(
                "body",
                JSONObject().apply {
                    put("bodyHash", metadata.bodyHash)
                    put("bodyByteCount", metadata.bodyByteCount)
                    put("chunkSize", metadata.chunkSize)
                    put("chunkHashes", JSONArray(metadata.chunkHashes))
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

    private const val PHASE_MANIFEST = "manifest"
    private const val PHASE_ARTICLES = "articles"
    private const val PHASE_COMPLETE = "complete"
    private const val ARTICLE_BATCH_TARGET_BYTES = BluetoothSyncProtocol.MAX_FRAME_BYTES - 128 * 1024
    private const val MAX_BATCH_COUNT_FOR_SIZING = 9999
}
