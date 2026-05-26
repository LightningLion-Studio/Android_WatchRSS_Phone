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
    val contentHash: String,
    val updatedAt: Long,
    val independentChangedAt: Long,
    val favoriteChangedAt: Long,
    val watchLaterChangedAt: Long,
    val deletedAt: Long
)

object LibrarySyncPayload {
    const val PROTOCOL_VERSION = 4

    fun buildRequest(
        deviceId: String,
        articles: List<PhoneArticleEntity>,
        rssSources: List<PhoneRssSourceEntity> = emptyList()
    ): JSONObject = buildManifestRequest(deviceId, articles, rssSources)

    fun buildManifestRequest(
        deviceId: String,
        articles: List<PhoneArticleEntity>,
        rssSources: List<PhoneRssSourceEntity> = emptyList()
    ): JSONObject {
        return JSONObject().apply {
            put("version", PROTOCOL_VERSION)
            put("action", BluetoothSyncProtocol.ACTION_SYNC_LIBRARY)
            put("phase", PHASE_MANIFEST)
            put("supportsArticleBatches", true)
            put("deviceId", deviceId)
            put("sentAt", System.currentTimeMillis())
            put("articleManifest", articles.toManifestJsonArray())
            put("rssSources", rssSources.toSourceJsonArray())
        }
    }

    fun buildManifestResponse(
        deviceId: String,
        articles: List<PhoneArticleEntity>,
        rssSources: List<PhoneRssSourceEntity> = emptyList(),
        stats: JSONObject? = null
    ): JSONObject {
        return JSONObject().apply {
            put("success", true)
            put("version", PROTOCOL_VERSION)
            put("action", BluetoothSyncProtocol.ACTION_SYNC_LIBRARY)
            put("phase", PHASE_MANIFEST)
            put("supportsArticleBatches", true)
            put("deviceId", deviceId)
            put("sentAt", System.currentTimeMillis())
            put("articleManifest", articles.toManifestJsonArray())
            put("rssSources", rssSources.toSourceJsonArray())
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

    fun combineArticlePayloads(frames: List<JSONObject>): JSONObject {
        if (frames.isEmpty()) return JSONObject()
        if (frames.size == 1 && !frames.first().optBoolean("success", true)) return frames.first()
        val first = frames.first()
        val articles = JSONArray()
        val sources = JSONArray()
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
                        contentHash = item.optString("contentHash").trim(),
                        updatedAt = item.optLong("updatedAt"),
                        independentChangedAt = item.optLong("independentChangedAt"),
                        favoriteChangedAt = item.optLong("favoriteChangedAt"),
                        watchLaterChangedAt = item.optLong("watchLaterChangedAt"),
                        deletedAt = item.optLong("deletedAt")
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
                article.deletedAt > remote.deletedAt
        }
    }

    fun parseRssSources(payload: JSONObject): List<PhoneRssSourceEntity> {
        return parseRssSources(payload.optJSONArray("rssSources") ?: JSONArray())
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
        articleItems.forEach { article ->
            val candidate = current + article
            val candidatePayload = buildPayload(candidate.toRawJsonArray(), 0, MAX_BATCH_COUNT_FOR_SIZING, totalArticles)
            val candidateSize = BluetoothSyncProtocol.encodedSize(candidatePayload)
            if (candidateSize > ARTICLE_BATCH_TARGET_BYTES && current.isNotEmpty()) {
                chunks += current
                current = mutableListOf(article)
            } else {
                current = candidate.toMutableList()
            }
            val singlePayload = buildPayload(listOf(article).toRawJsonArray(), 0, MAX_BATCH_COUNT_FOR_SIZING, totalArticles)
            val singleSize = BluetoothSyncProtocol.encodedSize(singlePayload)
            require(singleSize <= BluetoothSyncProtocol.MAX_FRAME_BYTES) {
                "单篇文章蓝牙消息过大：${article.optString("title").ifBlank { article.optString("url") }.take(40)}（$singleSize 字节）"
            }
        }
        if (current.isNotEmpty()) {
            chunks += current
        }

        val batchCount = chunks.size.coerceAtLeast(1)
        return chunks.mapIndexed { index, chunk ->
            buildPayload(chunk.toRawJsonArray(), index, batchCount, totalArticles)
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

    private fun PhoneArticleEntity.toManifestJson(): JSONObject {
        return JSONObject().apply {
            put("articleId", articleId)
            put("contentHash", contentHash)
            put("updatedAt", updatedAt)
            put("independentChangedAt", independentChangedAt)
            put("favoriteChangedAt", favoriteChangedAt)
            put("watchLaterChangedAt", watchLaterChangedAt)
            put("deletedAt", deletedAt)
        }
    }

    private fun PhoneArticleEntity.toJson(): JSONObject {
        return JSONObject().apply {
            put("articleId", articleId)
            put("sourceDeviceId", sourceDeviceId)
            put("url", url)
            put("title", title)
            put("siteName", siteName)
            put("excerpt", excerpt)
            putCompressedString("contentHtmlGzip", contentHtml)
            putCompressedString("contentTextGzip", contentText)
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
