package com.lightningstudio.watchrss.phone.data.repo

import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity

/** Feed discovery must not reset user state or replace a cached full body with a summary. */
internal fun PhoneArticleEntity.withRssLocalStateFrom(existing: PhoneArticleEntity?): PhoneArticleEntity {
    if (existing == null) return this
    val keepBody = contentHash == existing.contentHash &&
        (existing.contentText.isNotBlank() || !existing.contentHtml.isNullOrBlank())
    return copy(
        importedAt = existing.importedAt,
        contentText = if (keepBody) existing.contentText else contentText,
        contentHtml = if (keepBody) existing.contentHtml else contentHtml,
        deleted = existing.deleted, deletedAt = existing.deletedAt,
        independentSaved = existing.independentSaved,
        independentChangedAt = existing.independentChangedAt,
        independentSortOrder = existing.independentSortOrder,
        favoriteSaved = existing.favoriteSaved, favoriteChangedAt = existing.favoriteChangedAt,
        favoriteSortOrder = existing.favoriteSortOrder,
        watchLaterSaved = existing.watchLaterSaved, watchLaterChangedAt = existing.watchLaterChangedAt,
        watchLaterSortOrder = existing.watchLaterSortOrder,
        isRead = existing.isRead, readingProgress = existing.readingProgress,
        readingPositionBytes = existing.readingPositionBytes,
        readingPositionContentHash = existing.readingPositionContentHash,
        readingPositionChangedAt = existing.readingPositionChangedAt
    )
}

/** A shared article keeps its existing active channel; removed channels can be reclaimed. */
internal fun PhoneArticleEntity.keepsOtherActiveRssOwner(feedUrl: String, activeSources: Set<String>): Boolean =
    rssSourceUrl != null && rssSourceUrl != feedUrl && rssSourceUrl in activeSources
