package com.lightningstudio.watchrss.phone.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "phone_articles",
    indices = [
        Index(value = ["url"]),
        Index(value = ["independentSaved", "independentSortOrder"]),
        Index(value = ["rssSourceUrl", "updatedAt"]),
        Index(value = ["favoriteSaved", "favoriteSortOrder"]),
        Index(value = ["watchLaterSaved", "watchLaterSortOrder"]),
        Index(value = ["syncBodyHash"])
    ]
)
data class PhoneArticleEntity(
    @PrimaryKey val articleId: String,
    val sourceDeviceId: String,
    val url: String,
    val title: String,
    val siteName: String,
    val excerpt: String,
    val contentHtml: String?,
    val contentText: String,
    val imageUrl: String?,
    val contentHash: String,
    val importedAt: Long,
    val updatedAt: Long,
    val independentSaved: Boolean,
    val independentChangedAt: Long,
    val independentSortOrder: Long,
    val rssSourceUrl: String?,
    val rssSourceTitle: String?,
    val favoriteSaved: Boolean,
    val favoriteChangedAt: Long,
    val favoriteSortOrder: Long,
    val watchLaterSaved: Boolean,
    val watchLaterChangedAt: Long,
    val watchLaterSortOrder: Long,
    val deleted: Boolean,
    val deletedAt: Long,
    val syncBodyHash: String = "",
    val syncBodyByteCount: Long = 0L,
    val syncChunkSize: Int = 0,
    val syncChunkHashesJson: String = "",
    val syncMetadataHash: String = ""
)
