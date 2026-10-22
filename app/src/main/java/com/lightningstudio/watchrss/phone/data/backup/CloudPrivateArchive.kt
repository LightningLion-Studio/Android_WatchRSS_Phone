package com.lightningstudio.watchrss.phone.data.backup

import com.lightningstudio.watchrss.phone.cloud.CloudContentPolicy
import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity

/** User state belongs to records. Keep the private item's identity and original, fixed metadata baseline. */
internal object CloudPrivateArchive {
    fun reader(value: com.lightningstudio.watchrss.phone.data.reader.ReaderPresetSnapshot) = value.copy(
        presets=value.presets.sortedBy { it.id }, fonts=value.fonts.sortedBy { it.id }, backgrounds=value.backgrounds.sortedBy { it.id },
        deletions=value.deletions.sortedWith(compareBy({ it.kind },{ it.entityId })))
    fun article(row: PhoneArticleEntity) = row.copy(sourceDeviceId = "", updatedAt = 0,
        excerpt = "", imageUrl = null, siteName = "", rssSourceTitle = null,
        independentSaved = row.independentSaved, independentChangedAt = row.independentChangedAt, independentSortOrder = row.independentSortOrder,
        favoriteSaved = row.favoriteSaved, favoriteChangedAt = row.favoriteChangedAt, favoriteSortOrder = row.favoriteSortOrder,
        watchLaterSaved = row.watchLaterSaved, watchLaterChangedAt = row.watchLaterChangedAt, watchLaterSortOrder = row.watchLaterSortOrder,
        deletedAt = 0, readingProgress = 0f, isRead = false, readingPositionBytes = 0,
        readingPositionChangedAt = 0, readingPositionContentHash = "")
    fun project(snapshot: WatchRssBackupSnapshot): WatchRssBackupSnapshot {
        val articles = snapshot.articles.filter { CloudContentPolicy.privateBody(it) && (it.contentText.isNotBlank() || !it.contentHtml.isNullOrBlank()) }.map(::article).sortedBy { it.articleId }
        val reader = snapshot.readerSnapshot?.let(::reader)
        val versionClock = listOf(articles.maxOfOrNull { it.importedAt } ?: 1L,
            reader?.presets?.maxOfOrNull { it.updatedAt } ?: 1L, reader?.fonts?.maxOfOrNull { it.updatedAt } ?: 1L,
            reader?.backgrounds?.maxOfOrNull { it.updatedAt } ?: 1L, reader?.deletions?.maxOfOrNull { it.deletedAt } ?: 1L).max().coerceAtLeast(1L)
        return snapshot.copy(exportedAt = versionClock,
            appVersion = "", sources = emptyList(), readerSnapshot = reader, articles = articles, savedItems = emptyList(),
            appMeta = snapshot.appMeta.filter { CloudContentPolicy.privateAppMeta(it.key) }.sortedBy { it.key }, cloudContentOnly = true)
    }
}
