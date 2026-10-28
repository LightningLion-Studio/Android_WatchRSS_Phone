package com.lightningstudio.watchrss.phone.data.model

import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity

/** A saved feed placeholder is missing content, not an authoritative empty original. */
internal fun PhoneArticleEntity.isMissingSavedRssBody(): Boolean =
    isRetainedPublicRss() && contentText.isBlank() && contentHtml.isNullOrBlank()

private fun PhoneArticleEntity.isRetainedPublicRss(): Boolean =
    !deleted && !independentSaved && (favoriteSaved || watchLaterSaved) &&
        !rssSourceUrl.isNullOrBlank() && !ImportedContentIds.isImportedContentUrl(rssSourceUrl) &&
        !ImportedContentIds.isImportedContentUrl(url)

internal fun PhoneArticleEntity.hasSameSavedRssIdentityAndClock(other: PhoneArticleEntity): Boolean =
    isRetainedPublicRss() && !other.deleted && !other.independentSaved &&
        articleId == other.articleId && url.isNotBlank() && url == other.url &&
        rssSourceUrl == other.rssSourceUrl && updatedAt == other.updatedAt

/** Never use this exception for private/imported versions, a newer empty version or a tombstone. */
internal fun PhoneArticleEntity.canRecoverSavedRssBodyFrom(body: PhoneArticleEntity): Boolean =
    isMissingSavedRssBody() && hasSameSavedRssIdentityAndClock(body) &&
        body.contentHash.isNotBlank() && (body.contentText.isNotBlank() || !body.contentHtml.isNullOrBlank())
