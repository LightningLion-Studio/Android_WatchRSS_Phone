package com.lightningstudio.watchrss.phone.cloud

import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity

/** Excerpts are index previews. Preserve legacy full descriptions as readable bodies. */
internal fun PhoneArticleEntity.compactCloudExcerpt(now: Long, deviceId: String): PhoneArticleEntity {
    if (excerpt.codePointCount(0, excerpt.length) <= 280) return this
    return copy(
        excerpt = excerpt.substring(0, excerpt.offsetByCodePoints(0, 280)),
        contentText = if (contentText.isBlank() && contentHtml.isNullOrBlank()) excerpt else contentText,
        updatedAt = maxOf(updatedAt, now) + 1,
        sourceDeviceId = deviceId
    )
}
