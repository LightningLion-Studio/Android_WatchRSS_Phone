package com.lightningstudio.watchrss.phone.data.backup

import com.lightningstudio.watchrss.phone.data.model.canRecoverSavedRssBodyFrom
import com.lightningstudio.watchrss.phone.data.model.hasSameSavedRssIdentityAndClock

internal fun com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity.mergeCloudState(
    incoming: CloudRssArticleState
): com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity {
    val favoriteRemote = stateWins(
        incoming.favoriteChangedAt,
        incoming.sourceDeviceId,
        favoriteChangedAt,
        sourceDeviceId
    )
    val watchLaterRemote = stateWins(
        incoming.watchLaterChangedAt,
        incoming.sourceDeviceId,
        watchLaterChangedAt,
        sourceDeviceId
    )
    val independentRemote = stateWins(
        incoming.independentChangedAt,
        incoming.sourceDeviceId,
        independentChangedAt,
        sourceDeviceId
    )
    val deletionRemote = stateWins(
        incoming.deletedAt,
        incoming.sourceDeviceId,
        deletedAt,
        sourceDeviceId
    )
    val metadataRemote = stateWins(incoming.updatedAt, incoming.sourceDeviceId, updatedAt, sourceDeviceId)
    val metadataOnly = incoming.toMetadataArticle()
    val retainOriginal = metadataOnly.canRecoverSavedRssBodyFrom(this)
    val bodyChanged = metadataRemote && incoming.contentHash != contentHash && !retainOriginal
    val sameClockSavedRss = hasSameSavedRssIdentityAndClock(metadataOnly) || metadataOnly.hasSameSavedRssIdentityAndClock(this)
    return copy(
        sourceDeviceId = if (metadataRemote) incoming.sourceDeviceId else sourceDeviceId,
        url = if (metadataRemote) incoming.url else url,
        title = if (metadataRemote) incoming.title else title,
        siteName = if (metadataRemote) incoming.siteName else siteName,
        excerpt = if (metadataRemote) incoming.excerpt else excerpt,
        imageUrl = if (metadataRemote) incoming.imageUrl else imageUrl,
        contentHash = if (metadataRemote && !retainOriginal) incoming.contentHash else contentHash,
        updatedAt = maxOf(updatedAt, incoming.updatedAt),
        rssSourceUrl = if (metadataRemote) incoming.rssSourceUrl else rssSourceUrl,
        rssSourceTitle = if (metadataRemote) incoming.rssSourceTitle else rssSourceTitle,
        contentText = if (bodyChanged) "" else contentText,
        contentHtml = if (bodyChanged) null else contentHtml,
        syncBodyHash = if (bodyChanged) "" else syncBodyHash,
        syncBodyByteCount = if (bodyChanged) 0 else syncBodyByteCount,
        syncChunkHashesJson = if (bodyChanged) "[]" else syncChunkHashesJson,
        syncMetadataHash = if (bodyChanged) "" else syncMetadataHash,
        independentSaved = if (independentRemote) incoming.independentSaved else independentSaved,
        independentChangedAt = if (independentRemote) incoming.independentChangedAt else independentChangedAt,
        independentSortOrder = if (independentRemote) incoming.independentSortOrder else independentSortOrder,
        favoriteSaved = if (favoriteRemote) incoming.favoriteSaved else favoriteSaved,
        favoriteChangedAt = if (favoriteRemote) incoming.favoriteChangedAt else favoriteChangedAt,
        favoriteSortOrder = if (favoriteRemote) incoming.favoriteSortOrder else favoriteSortOrder,
        watchLaterSaved = if (watchLaterRemote) incoming.watchLaterSaved else watchLaterSaved,
        watchLaterChangedAt = if (watchLaterRemote) incoming.watchLaterChangedAt else watchLaterChangedAt,
        watchLaterSortOrder = if (watchLaterRemote) incoming.watchLaterSortOrder else watchLaterSortOrder,
        deleted = if (deletionRemote) incoming.deleted else deleted,
        deletedAt = if (deletionRemote) incoming.deletedAt else deletedAt,
        readingProgress = if (incoming.readingPositionChangedAt > readingPositionChangedAt) {
            incoming.readingProgress
        } else {
            readingProgress
        },
        readingPositionBytes = if (incoming.readingPositionChangedAt > readingPositionChangedAt) {
            incoming.readingPositionBytes
        } else {
            readingPositionBytes
        },
        readingPositionContentHash = if (incoming.readingPositionChangedAt > readingPositionChangedAt) {
            incoming.readingPositionContentHash
        } else {
            readingPositionContentHash
        },
        readingPositionChangedAt = maxOf(
            readingPositionChangedAt,
            incoming.readingPositionChangedAt
        ),
        isRead = when {
            sameClockSavedRss && readingPositionChangedAt > 0 && incoming.readingPositionChangedAt <= 0 -> isRead
            sameClockSavedRss && incoming.readingPositionChangedAt > 0 && readingPositionChangedAt <= 0 -> incoming.isRead
            else -> mergeReadFlag(isRead, readingPositionChangedAt, updatedAt, sourceDeviceId,
                incoming.isRead, incoming.readingPositionChangedAt, incoming.updatedAt, incoming.sourceDeviceId)
        }
    )
}

private fun stateWins(
    incomingAt: Long,
    incomingDevice: String,
    currentAt: Long,
    currentDevice: String
): Boolean = incomingAt > currentAt ||
    (incomingAt == currentAt && incomingDevice > currentDevice)

/** Reading position and read/unread are one LWW register; old clients fall back to updatedAt. */
internal fun mergeReadFlag(local: Boolean, localAt: Long, localUpdated: Long, localDevice: String,
                           remote: Boolean, remoteAt: Long, remoteUpdated: Long, remoteDevice: String): Boolean {
    val a = localAt.takeIf { it > 0 } ?: localUpdated
    val b = remoteAt.takeIf { it > 0 } ?: remoteUpdated
    return when {
        b > a -> remote
        a > b -> local
        remoteDevice > localDevice -> remote
        localDevice > remoteDevice -> local
        else -> local && remote
    }
}
