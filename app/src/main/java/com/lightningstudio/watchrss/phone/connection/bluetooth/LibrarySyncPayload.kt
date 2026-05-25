package com.lightningstudio.watchrss.phone.connection.bluetooth

import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
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
    val merged: Int
)

object LibrarySyncPayload {
    const val PROTOCOL_VERSION = 2

    fun buildRequest(deviceId: String, articles: List<PhoneArticleEntity>): JSONObject {
        return JSONObject().apply {
            put("version", PROTOCOL_VERSION)
            put("action", BluetoothSyncProtocol.ACTION_SYNC_LIBRARY)
            put("deviceId", deviceId)
            put("sentAt", System.currentTimeMillis())
            put("articles", articles.toJsonArray())
        }
    }

    fun buildResponse(
        deviceId: String,
        articles: List<PhoneArticleEntity>,
        stats: JSONObject? = null
    ): JSONObject {
        return JSONObject().apply {
            put("success", true)
            put("version", PROTOCOL_VERSION)
            put("action", BluetoothSyncProtocol.ACTION_SYNC_LIBRARY)
            put("deviceId", deviceId)
            put("sentAt", System.currentTimeMillis())
            put("articles", articles.toJsonArray())
            if (stats != null) {
                put("stats", stats)
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

    private fun List<PhoneArticleEntity>.toJsonArray(): JSONArray {
        return JSONArray().also { array ->
            forEach { article ->
                array.put(article.toJson())
            }
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
}
