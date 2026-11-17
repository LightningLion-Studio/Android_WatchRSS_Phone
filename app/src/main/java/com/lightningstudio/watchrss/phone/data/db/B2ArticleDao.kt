package com.lightningstudio.watchrss.phone.data.db

import androidx.room.Dao
import androidx.room.Query

/** Bounded v14 body access and metadata-only writes for B2 transactions. */
@Dao
interface B2ArticleDao {
    @Query("""SELECT articleId FROM phone_articles WHERE (:after IS NULL OR articleId > :after)
        AND (independentSaved OR favoriteSaved OR watchLaterSaved OR deleted OR independentChangedAt > 0
            OR favoriteChangedAt > 0 OR watchLaterChangedAt > 0 OR url LIKE 'https://watchrss.local/import-%'
            OR rssSourceUrl LIKE 'https://watchrss.local/import-%' OR url LIKE 'watchrss://import/online-v1/%'
            OR rssSourceUrl LIKE 'watchrss://import/online-v1/%')
        ORDER BY articleId LIMIT 32""")
    suspend fun binaryArticleIds(after: String?): List<String>

    @Query("SELECT " + ARTICLE_METADATA_COLUMNS + """ FROM phone_articles WHERE rssSourceUrl=:url AND deleted=0
        AND (:after IS NULL OR articleId>:after) ORDER BY articleId LIMIT 32""")
    suspend fun binarySourceArticlePage(url: String, after: String?): List<PhoneArticleEntity>

    @Query("""UPDATE phone_articles SET
        sourceDeviceId=:sourceDeviceId, url=:url, title=:title,
        siteName=:siteName, excerpt=:excerpt, imageUrl=:imageUrl,
        contentHash=:contentHash, importedAt=:importedAt, updatedAt=:updatedAt,
        independentSaved=:independentSaved, independentChangedAt=:independentChangedAt, independentSortOrder=:independentSortOrder,
        rssSourceUrl=:rssSourceUrl, rssSourceTitle=:rssSourceTitle, favoriteSaved=:favoriteSaved,
        favoriteChangedAt=:favoriteChangedAt, favoriteSortOrder=:favoriteSortOrder, watchLaterSaved=:watchLaterSaved,
        watchLaterChangedAt=:watchLaterChangedAt, watchLaterSortOrder=:watchLaterSortOrder, deleted=:deleted,
        deletedAt=:deletedAt, syncBodyHash=:syncBodyHash, syncBodyByteCount=:syncBodyByteCount,
        syncChunkSize=:syncChunkSize, syncChunkHashesJson=:syncChunkHashesJson, syncMetadataHash=:syncMetadataHash,
        readingProgress=:readingProgress, isRead=:isRead, readingPositionBytes=:readingPositionBytes,
        readingPositionContentHash=:readingPositionContentHash, readingPositionChangedAt=:readingPositionChangedAt
        WHERE articleId=:articleId""")
    suspend fun updateBinaryArticleKeepingBody(
        articleId: String, sourceDeviceId: String, url: String, title: String, siteName: String,
        excerpt: String, imageUrl: String?, contentHash: String, importedAt: Long, updatedAt: Long,
        independentSaved: Boolean, independentChangedAt: Long, independentSortOrder: Long,
        rssSourceUrl: String?, rssSourceTitle: String?, favoriteSaved: Boolean, favoriteChangedAt: Long,
        favoriteSortOrder: Long, watchLaterSaved: Boolean, watchLaterChangedAt: Long, watchLaterSortOrder: Long,
        deleted: Boolean, deletedAt: Long, syncBodyHash: String, syncBodyByteCount: Long, syncChunkSize: Int,
        syncChunkHashesJson: String, syncMetadataHash: String, readingProgress: Float, isRead: Boolean,
        readingPositionBytes: Long, readingPositionContentHash: String, readingPositionChangedAt: Long
    )

    suspend fun updateBinaryArticleKeepingBody(row: PhoneArticleEntity) = with(row) {
        updateBinaryArticleKeepingBody(articleId, sourceDeviceId, url, title, siteName, excerpt, imageUrl,
            contentHash, importedAt, updatedAt, independentSaved, independentChangedAt, independentSortOrder,
            rssSourceUrl, rssSourceTitle, favoriteSaved, favoriteChangedAt, favoriteSortOrder, watchLaterSaved,
            watchLaterChangedAt, watchLaterSortOrder, deleted, deletedAt, syncBodyHash, syncBodyByteCount,
            syncChunkSize, syncChunkHashesJson, syncMetadataHash, readingProgress, isRead, readingPositionBytes,
            readingPositionContentHash, readingPositionChangedAt)
    }

    @Query("UPDATE phone_articles SET articleId=:canonical, syncMetadataHash='' WHERE articleId=:legacy")
    suspend fun rekeyBinaryArticle(legacy: String, canonical: String)
}
