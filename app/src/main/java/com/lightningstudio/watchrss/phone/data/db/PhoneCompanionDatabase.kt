package com.lightningstudio.watchrss.phone.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [PhoneSavedItemEntity::class],
    version = 1,
    exportSchema = false
)
abstract class PhoneCompanionDatabase : RoomDatabase() {
    abstract fun phoneSavedItemDao(): PhoneSavedItemDao
}
