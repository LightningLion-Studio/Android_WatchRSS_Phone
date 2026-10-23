package com.lightningstudio.watchrss.phone.cloud

import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
import com.lightningstudio.watchrss.phone.data.model.ImportedContentIds
import org.json.JSONObject

/** Public entries are disposable cache; only user interactions leave the device. */
internal object CloudContentPolicy {
    const val MIN_SNAPSHOT_INTERVAL_MS = 10 * 60 * 1000L
    val interactionNumbers = listOf("readingPositionBytes", "readingPositionChangedAt", "favoriteChangedAt", "favoriteSortOrder", "watchLaterChangedAt", "watchLaterSortOrder", "deletedAt")
    val interactionBooleans = listOf("isRead", "favoriteSaved", "watchLaterSaved", "deleted")
    fun publicRss(value: JSONObject): Boolean = value.optString("rssSourceUrl").let {
        it.isNotBlank() && it != "null" && !ImportedContentIds.isImportedContentUrl(it) && !ImportedContentIds.isImportedContentUrl(value.optString("url"))
    }
    fun interacted(value: JSONObject): Boolean = value.optBoolean("isRead") || value.optDouble("readingProgress", 0.0) > 0 ||
        interactionNumbers.any { value.optLong(it) > 0 && it !in setOf("favoriteSortOrder", "watchLaterSortOrder", "deletedAt") } ||
        value.optBoolean("favoriteSaved") || value.optBoolean("watchLaterSaved")
    fun rssState(value: JSONObject, sourceId: String): JSONObject {
        require(sourceId.isNotBlank())
        return JSONObject().put("sourceId", sourceId)
            .put("articleIdHash", CloudSnapshotCodec.sha256(value.getString("articleId").toByteArray(Charsets.UTF_8)))
            .put("sourceDeviceId", value.optString("sourceDeviceId", ""))
            .put("readingPositionContentHash", value.optString("readingPositionContentHash", ""))
            .put("readingProgress", value.optDouble("readingProgress", 0.0)).apply {
                interactionNumbers.forEach { put(it, value.optLong(it)) }
                interactionBooleans.forEach { put(it, value.optBoolean(it)) }
            }
    }
    fun privateBody(row: PhoneArticleEntity): Boolean = !row.deleted && (!publicRss(JSONObject().put("url", row.url).put("rssSourceUrl", row.rssSourceUrl)) || row.favoriteSaved || row.watchLaterSaved)
    fun privateAppMeta(key: String) = key != "first_use_at" && !key.startsWith("public_rss_source:") && !key.startsWith("rss_identity_alias:") && !key.startsWith("ble_") && !key.startsWith("book_identity_alias:")
    fun shouldPublish(changed: Boolean, manual: Boolean, lastPublished: Long, now: Long): Boolean =
        changed && (manual || lastPublished == 0L || now - lastPublished >= MIN_SNAPSHOT_INTERVAL_MS)
}
