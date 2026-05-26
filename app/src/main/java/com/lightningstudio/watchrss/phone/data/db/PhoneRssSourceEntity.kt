package com.lightningstudio.watchrss.phone.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "phone_rss_sources",
    indices = [
        Index(value = ["deleted", "sortOrder"])
    ]
)
data class PhoneRssSourceEntity(
    @PrimaryKey val url: String,
    val sourceDeviceId: String,
    val title: String,
    val description: String,
    val siteUrl: String?,
    val imageUrl: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val sortOrder: Long,
    val isPinned: Boolean,
    val deleted: Boolean,
    val deletedAt: Long
)
