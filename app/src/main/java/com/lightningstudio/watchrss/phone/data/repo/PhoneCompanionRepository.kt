package com.lightningstudio.watchrss.phone.data.repo

import com.lightningstudio.watchrss.phone.data.db.PhoneArticleDao
import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
import com.lightningstudio.watchrss.phone.data.db.PhoneSavedItemDao
import com.lightningstudio.watchrss.phone.data.db.PhoneSavedItemEntity
import com.lightningstudio.watchrss.phone.data.importer.ImportedWebArticle
import com.lightningstudio.watchrss.phone.data.importer.WebArticleImporter
import com.lightningstudio.watchrss.phone.data.model.PhoneSavedItemType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.lang.Long.max
import java.net.URI

class PhoneCompanionRepository(
    private val savedItemDao: PhoneSavedItemDao,
    private val articleDao: PhoneArticleDao,
    private val deviceId: String,
    private val webArticleImporter: suspend (String) -> ImportedWebArticle = { input ->
        WebArticleImporter().importUrl(input)
    }
) {
    fun observeSavedItems(type: PhoneSavedItemType): Flow<List<PhoneSavedItemEntity>> {
        return savedItemDao.observeByType(type.name)
    }

    fun observeSavedArticles(type: PhoneSavedItemType): Flow<List<PhoneArticleEntity>> {
        return when (type) {
            PhoneSavedItemType.FAVORITE -> articleDao.observeFavorites()
            PhoneSavedItemType.WATCH_LATER -> articleDao.observeWatchLater()
        }
    }

    fun observeRecentArticles(limit: Int = 20): Flow<List<PhoneArticleEntity>> {
        return articleDao.observeRecent(limit)
    }

    fun observeArticle(articleId: String): Flow<PhoneArticleEntity?> {
        return articleDao.observeById(articleId)
    }

    suspend fun importWebArticle(input: String, type: PhoneSavedItemType): PhoneArticleEntity =
        withContext(Dispatchers.IO) {
            val imported = webArticleImporter(input)
            saveImportedArticle(imported, type)
        }

    suspend fun toggleSaved(article: PhoneArticleEntity, type: PhoneSavedItemType): PhoneArticleEntity =
        withContext(Dispatchers.IO) {
            val current = articleDao.getById(article.articleId) ?: article
            val now = System.currentTimeMillis()
            val updated = when (type) {
                PhoneSavedItemType.FAVORITE -> current.copy(
                    favoriteSaved = !current.favoriteSaved,
                    favoriteChangedAt = now,
                    favoriteSortOrder = if (!current.favoriteSaved) now else current.favoriteSortOrder,
                    updatedAt = now,
                    sourceDeviceId = deviceId,
                    deleted = false
                )
                PhoneSavedItemType.WATCH_LATER -> current.copy(
                    watchLaterSaved = !current.watchLaterSaved,
                    watchLaterChangedAt = now,
                    watchLaterSortOrder = if (!current.watchLaterSaved) now else current.watchLaterSortOrder,
                    updatedAt = now,
                    sourceDeviceId = deviceId,
                    deleted = false
                )
            }.markDeletedIfEmpty(now)
            articleDao.upsert(updated)
            updated
        }

    suspend fun getArticlesForSync(): List<PhoneArticleEntity> = withContext(Dispatchers.IO) {
        articleDao.getAllForSync()
    }

    suspend fun mergeArticlesFromSync(incoming: List<PhoneArticleEntity>): Int = withContext(Dispatchers.IO) {
        var merged = 0
        incoming.forEach { remote ->
            val local = articleDao.getById(remote.articleId)
            val next = if (local == null) {
                remote
            } else {
                mergeArticle(local, remote)
            }
            if (local != next) {
                articleDao.upsert(next)
                merged += 1
            }
        }
        merged
    }

    suspend fun replaceSavedItems(type: PhoneSavedItemType, data: JSONArray): Int {
        val syncedAt = System.currentTimeMillis()
        val entities = buildList {
            for (index in 0 until data.length()) {
                val item = data.optJSONObject(index) ?: continue
                val link = item.optString("link").trim()
                if (link.isBlank()) continue
                val remoteId = item.optLong("id")
                val title = item.optString("title").trim()
                val summary = item.optString("summary").trim()
                val channelTitle = item.optString("channelTitle").trim()
                val stableKey = when {
                    remoteId > 0L -> remoteId.toString()
                    link.isNotBlank() -> link
                    else -> "${type.name}-$index"
                }
                add(
                    PhoneSavedItemEntity(
                        type = type.name,
                        stableKey = stableKey,
                        remoteId = remoteId,
                        title = title.ifBlank { link },
                        link = link,
                        summary = summary,
                        channelTitle = channelTitle.ifBlank { hostLabel(link) },
                        pubDate = item.optString("pubDate"),
                        syncedAt = syncedAt
                    )
                )
            }
        }
        savedItemDao.deleteByType(type.name)
        savedItemDao.upsertAll(entities)
        entities.forEach { entity ->
            val imported = ImportedWebArticle(
                articleId = WebArticleImporter.stableArticleId(entity.link),
                url = entity.link,
                title = entity.title,
                siteName = entity.channelTitle,
                excerpt = entity.summary,
                contentHtml = null,
                contentText = entity.summary,
                imageUrl = null,
                contentHash = WebArticleImporter.sha256(entity.summary.ifBlank { entity.link })
            )
            saveImportedArticle(imported, type, timestamp = syncedAt)
        }
        return entities.size
    }

    private suspend fun saveImportedArticle(
        imported: ImportedWebArticle,
        type: PhoneSavedItemType,
        timestamp: Long = System.currentTimeMillis()
    ): PhoneArticleEntity {
        val current = articleDao.getById(imported.articleId)
        val base = current ?: PhoneArticleEntity(
            articleId = imported.articleId,
            sourceDeviceId = deviceId,
            url = imported.url,
            title = imported.title,
            siteName = imported.siteName,
            excerpt = imported.excerpt,
            contentHtml = imported.contentHtml,
            contentText = imported.contentText,
            imageUrl = imported.imageUrl,
            contentHash = imported.contentHash,
            importedAt = timestamp,
            updatedAt = timestamp,
            favoriteSaved = false,
            favoriteChangedAt = 0L,
            favoriteSortOrder = 0L,
            watchLaterSaved = false,
            watchLaterChangedAt = 0L,
            watchLaterSortOrder = 0L,
            deleted = false,
            deletedAt = 0L
        )
        val withContent = base.copy(
            sourceDeviceId = deviceId,
            url = imported.url,
            title = imported.title,
            siteName = imported.siteName,
            excerpt = imported.excerpt,
            contentHtml = imported.contentHtml,
            contentText = imported.contentText,
            imageUrl = imported.imageUrl,
            contentHash = imported.contentHash,
            updatedAt = timestamp,
            deleted = false
        )
        val saved = when (type) {
            PhoneSavedItemType.FAVORITE -> withContent.copy(
                favoriteSaved = true,
                favoriteChangedAt = timestamp,
                favoriteSortOrder = timestamp
            )
            PhoneSavedItemType.WATCH_LATER -> withContent.copy(
                watchLaterSaved = true,
                watchLaterChangedAt = timestamp,
                watchLaterSortOrder = timestamp
            )
        }
        articleDao.upsert(saved)
        return saved
    }

    private fun mergeArticle(local: PhoneArticleEntity, remote: PhoneArticleEntity): PhoneArticleEntity {
        val metadata = if (remote.updatedAt > local.updatedAt) remote else local
        val favoriteFromRemote = remote.isStateNewerThan(local, PhoneSavedItemType.FAVORITE)
        val watchLaterFromRemote = remote.isStateNewerThan(local, PhoneSavedItemType.WATCH_LATER)
        val favoriteSaved = if (favoriteFromRemote) remote.favoriteSaved else local.favoriteSaved
        val favoriteChangedAt = if (favoriteFromRemote) remote.favoriteChangedAt else local.favoriteChangedAt
        val favoriteSortOrder = if (favoriteFromRemote) remote.favoriteSortOrder else local.favoriteSortOrder
        val watchLaterSaved = if (watchLaterFromRemote) remote.watchLaterSaved else local.watchLaterSaved
        val watchLaterChangedAt = if (watchLaterFromRemote) remote.watchLaterChangedAt else local.watchLaterChangedAt
        val watchLaterSortOrder = if (watchLaterFromRemote) remote.watchLaterSortOrder else local.watchLaterSortOrder
        val remoteDeletedNewer = remote.deletedAt > local.deletedAt ||
            (remote.deletedAt == local.deletedAt && remote.deleted && remote.sourceDeviceId > local.sourceDeviceId)
        val deleted = when {
            favoriteSaved || watchLaterSaved -> false
            remoteDeletedNewer -> remote.deleted
            else -> local.deleted
        }
        val deletedAt = max(local.deletedAt, remote.deletedAt)
        return metadata.copy(
            favoriteSaved = favoriteSaved,
            favoriteChangedAt = favoriteChangedAt,
            favoriteSortOrder = favoriteSortOrder,
            watchLaterSaved = watchLaterSaved,
            watchLaterChangedAt = watchLaterChangedAt,
            watchLaterSortOrder = watchLaterSortOrder,
            deleted = deleted,
            deletedAt = deletedAt
        )
    }

    private fun PhoneArticleEntity.isStateNewerThan(
        other: PhoneArticleEntity,
        type: PhoneSavedItemType
    ): Boolean {
        val ownChangedAt = when (type) {
            PhoneSavedItemType.FAVORITE -> favoriteChangedAt
            PhoneSavedItemType.WATCH_LATER -> watchLaterChangedAt
        }
        val otherChangedAt = when (type) {
            PhoneSavedItemType.FAVORITE -> other.favoriteChangedAt
            PhoneSavedItemType.WATCH_LATER -> other.watchLaterChangedAt
        }
        return ownChangedAt > otherChangedAt ||
            (ownChangedAt == otherChangedAt && sourceDeviceId > other.sourceDeviceId)
    }

    private fun PhoneArticleEntity.markDeletedIfEmpty(timestamp: Long): PhoneArticleEntity {
        if (favoriteSaved || watchLaterSaved) return copy(deleted = false)
        return copy(deleted = true, deletedAt = timestamp)
    }

    private fun hostLabel(link: String): String {
        return runCatching { URI(link).host.orEmpty().removePrefix("www.") }
            .getOrDefault("")
            .trim()
    }
}
