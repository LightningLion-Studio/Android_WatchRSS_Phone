package com.lightningstudio.watchrss.phone.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        PhoneSavedItemEntity::class,
        PhoneArticleEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class PhoneCompanionDatabase : RoomDatabase() {
    abstract fun phoneSavedItemDao(): PhoneSavedItemDao
    abstract fun phoneArticleDao(): PhoneArticleDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS phone_articles (
                        articleId TEXT NOT NULL PRIMARY KEY,
                        sourceDeviceId TEXT NOT NULL,
                        url TEXT NOT NULL,
                        title TEXT NOT NULL,
                        siteName TEXT NOT NULL,
                        excerpt TEXT NOT NULL,
                        contentHtml TEXT,
                        contentText TEXT NOT NULL,
                        imageUrl TEXT,
                        contentHash TEXT NOT NULL,
                        importedAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        favoriteSaved INTEGER NOT NULL,
                        favoriteChangedAt INTEGER NOT NULL,
                        favoriteSortOrder INTEGER NOT NULL,
                        watchLaterSaved INTEGER NOT NULL,
                        watchLaterChangedAt INTEGER NOT NULL,
                        watchLaterSortOrder INTEGER NOT NULL,
                        deleted INTEGER NOT NULL,
                        deletedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS index_phone_articles_url ON phone_articles(url)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_phone_articles_favoriteSaved_favoriteSortOrder ON phone_articles(favoriteSaved, favoriteSortOrder)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_phone_articles_watchLaterSaved_watchLaterSortOrder ON phone_articles(watchLaterSaved, watchLaterSortOrder)")
            }
        }
    }
}
