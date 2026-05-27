package com.lightningstudio.watchrss.phone.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sync_change_log",
    indices = [
        Index(value = ["kind", "entityId"]),
        Index(value = ["seq"])
    ]
)
data class SyncChangeLogEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0L,
    val kind: String,
    val entityId: String,
    val changedAt: Long,
    val originDeviceId: String,
    val reason: String,
    val createdAt: Long
)
