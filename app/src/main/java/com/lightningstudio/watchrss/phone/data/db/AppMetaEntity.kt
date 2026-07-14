package com.lightningstudio.watchrss.phone.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 应用级键值元数据表，用于存储首次使用时间等里程碑信息，
 * 为未来的勋章墙功能提供持久化基础。
 */
@Entity(tableName = "app_meta")
data class AppMetaEntity(
    @PrimaryKey val key: String,
    val value: String
)
