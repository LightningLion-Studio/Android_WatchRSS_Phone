package com.lightningstudio.watchrss.phone.data.db

import androidx.room.Entity

@Entity(
    tableName = "phone_saved_items",
    primaryKeys = ["type", "stableKey"]
)
data class PhoneSavedItemEntity(
    val type: String,
    val stableKey: String,
    val remoteId: Long,
    val title: String,
    val link: String,
    val summary: String,
    val channelTitle: String,
    val pubDate: String,
    val syncedAt: Long
)
