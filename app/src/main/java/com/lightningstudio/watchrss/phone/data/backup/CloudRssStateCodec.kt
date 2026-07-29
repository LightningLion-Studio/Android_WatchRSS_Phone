package com.lightningstudio.watchrss.phone.data.backup

import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
import com.lightningstudio.watchrss.phone.data.db.PhoneRssSourceEntity
import org.json.JSONArray
import org.json.JSONObject

internal data class CloudRssArticleState(
    val articleId: String,
    val sourceDeviceId: String,
    val url: String,
    val title: String,
    val siteName: String,
    val excerpt: String,
    val imageUrl: String?,
    val contentHash: String,
    val importedAt: Long,
    val updatedAt: Long,
    val rssSourceUrl: String?,
    val rssSourceTitle: String?,
    val independentSaved: Boolean,
    val independentChangedAt: Long,
    val independentSortOrder: Long,
    val favoriteSaved: Boolean,
    val favoriteChangedAt: Long,
    val favoriteSortOrder: Long,
    val watchLaterSaved: Boolean,
    val watchLaterChangedAt: Long,
    val watchLaterSortOrder: Long,
    val deleted: Boolean,
    val deletedAt: Long,
    val readingProgress: Float,
    val isRead: Boolean
)

internal object CloudRssStateCodec {
    private const val FORMAT = "watchrss-rss-state"
    private const val VERSION = 1

    fun encode(
        articles: List<PhoneArticleEntity>,
        exportedAt: Long,
        sources: List<PhoneRssSourceEntity> = emptyList()
    ): ByteArray =
        JSONObject().apply {
            put("format", FORMAT)
            put("version", VERSION)
            put("exportedAt", exportedAt)
            put("articles", JSONArray().apply {
                articles.forEach { article ->
                    put(JSONObject().apply {
                        put("articleId", article.articleId)
                        put("sourceDeviceId", article.sourceDeviceId)
                        put("url", article.url)
                        put("title", article.title)
                        put("siteName", article.siteName)
                        put("excerpt", article.excerpt)
                        put("imageUrl", article.imageUrl ?: JSONObject.NULL)
                        put("contentHash", article.contentHash)
                        put("importedAt", article.importedAt)
                        put("updatedAt", article.updatedAt)
                        put("rssSourceUrl", article.rssSourceUrl ?: JSONObject.NULL)
                        put("rssSourceTitle", article.rssSourceTitle ?: JSONObject.NULL)
                        put("independentSaved", article.independentSaved)
                        put("independentChangedAt", article.independentChangedAt)
                        put("independentSortOrder", article.independentSortOrder)
                        put("favoriteSaved", article.favoriteSaved)
                        put("favoriteChangedAt", article.favoriteChangedAt)
                        put("favoriteSortOrder", article.favoriteSortOrder)
                        put("watchLaterSaved", article.watchLaterSaved)
                        put("watchLaterChangedAt", article.watchLaterChangedAt)
                        put("watchLaterSortOrder", article.watchLaterSortOrder)
                        put("deleted", article.deleted)
                        put("deletedAt", article.deletedAt)
                        put("readingProgress", article.readingProgress.toDouble())
                        put("isRead", article.isRead)
                    })
                }
            })
            put("sources", JSONArray().apply {
                sources.forEach { source ->
                    put(JSONObject().apply {
                        put("url", source.url)
                        put("sourceDeviceId", source.sourceDeviceId)
                        put("title", source.title)
                        put("description", source.description)
                        put("siteUrl", source.siteUrl ?: JSONObject.NULL)
                        put("imageUrl", source.imageUrl ?: JSONObject.NULL)
                        put("createdAt", source.createdAt)
                        put("updatedAt", source.updatedAt)
                        put("sortOrder", source.sortOrder)
                        put("isPinned", source.isPinned)
                        put("deleted", source.deleted)
                        put("deletedAt", source.deletedAt)
                        put("useOriginalContent", source.useOriginalContent)
                        put(
                            "continuePlaybackInBackground",
                            source.continuePlaybackInBackground
                        )
                    })
                }
            })
        }.toString().toByteArray(Charsets.UTF_8)

    fun decode(bytes: ByteArray): List<CloudRssArticleState> {
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        require(root.getString("format") == FORMAT && root.getInt("version") == VERSION) {
            "云端RSS状态格式不受支持"
        }
        val articles = root.getJSONArray("articles")
        return buildList {
            for (index in 0 until articles.length()) {
                val json = articles.getJSONObject(index)
                add(
                    CloudRssArticleState(
                        articleId = json.getString("articleId"),
                        sourceDeviceId = json.optString("sourceDeviceId"),
                        url = json.optString("url"),
                        title = json.optString("title"),
                        siteName = json.optString("siteName"),
                        excerpt = json.optString("excerpt"),
                        imageUrl = json.nullableString("imageUrl"),
                        contentHash = json.optString("contentHash"),
                        importedAt = json.optLong("importedAt"),
                        updatedAt = json.optLong("updatedAt"),
                        rssSourceUrl = json.nullableString("rssSourceUrl"),
                        rssSourceTitle = json.nullableString("rssSourceTitle"),
                        independentSaved = json.optBoolean("independentSaved"),
                        independentChangedAt = json.optLong("independentChangedAt"),
                        independentSortOrder = json.optLong("independentSortOrder"),
                        favoriteSaved = json.optBoolean("favoriteSaved"),
                        favoriteChangedAt = json.optLong("favoriteChangedAt"),
                        favoriteSortOrder = json.optLong("favoriteSortOrder"),
                        watchLaterSaved = json.optBoolean("watchLaterSaved"),
                        watchLaterChangedAt = json.optLong("watchLaterChangedAt"),
                        watchLaterSortOrder = json.optLong("watchLaterSortOrder"),
                        deleted = json.optBoolean("deleted"),
                        deletedAt = json.optLong("deletedAt"),
                        readingProgress = json.optDouble("readingProgress")
                            .toFloat()
                            .coerceIn(0f, 1f),
                        isRead = json.optBoolean("isRead")
                    )
                )
            }
        }
    }

    fun decodeSources(bytes: ByteArray): List<PhoneRssSourceEntity> {
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        require(root.getString("format") == FORMAT && root.getInt("version") == VERSION) {
            "云端RSS状态格式不受支持"
        }
        val sources = root.optJSONArray("sources") ?: JSONArray()
        return buildList {
            for (index in 0 until sources.length()) {
                val json = sources.getJSONObject(index)
                add(
                    PhoneRssSourceEntity(
                        url = json.getString("url"),
                        sourceDeviceId = json.optString("sourceDeviceId"),
                        title = json.optString("title"),
                        description = json.optString("description"),
                        siteUrl = json.nullableString("siteUrl"),
                        imageUrl = json.nullableString("imageUrl"),
                        createdAt = json.optLong("createdAt"),
                        updatedAt = json.optLong("updatedAt"),
                        sortOrder = json.optLong("sortOrder"),
                        isPinned = json.optBoolean("isPinned"),
                        deleted = json.optBoolean("deleted"),
                        deletedAt = json.optLong("deletedAt"),
                        useOriginalContent = json.optBoolean("useOriginalContent"),
                        continuePlaybackInBackground =
                            json.optBoolean("continuePlaybackInBackground")
                    ).also {
                        it.syncedSettingsIncluded =
                            json.has("useOriginalContent") ||
                                json.has("continuePlaybackInBackground")
                    }
                )
            }
        }
    }

    private fun JSONObject.nullableString(name: String): String? =
        if (!has(name) || isNull(name)) null else getString(name)
}
