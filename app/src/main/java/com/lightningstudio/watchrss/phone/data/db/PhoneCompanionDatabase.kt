package com.lightningstudio.watchrss.phone.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lightningstudio.watchrss.phone.data.model.ImportedContentIds

@Database(
    entities = [
        PhoneSavedItemEntity::class,
        PhoneArticleEntity::class,
        PhoneRssSourceEntity::class,
        SyncChangeLogEntity::class,
        SyncPeerStateEntity::class
    ],
    version = 7,
    exportSchema = false
)
abstract class PhoneCompanionDatabase : RoomDatabase() {
    abstract fun phoneSavedItemDao(): PhoneSavedItemDao
    abstract fun phoneArticleDao(): PhoneArticleDao
    abstract fun phoneRssSourceDao(): PhoneRssSourceDao
    abstract fun syncChangeLogDao(): SyncChangeLogDao
    abstract fun syncPeerStateDao(): SyncPeerStateDao

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

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE phone_articles ADD COLUMN independentSaved INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE phone_articles ADD COLUMN independentChangedAt INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE phone_articles ADD COLUMN independentSortOrder INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE phone_articles ADD COLUMN rssSourceUrl TEXT")
                database.execSQL("ALTER TABLE phone_articles ADD COLUMN rssSourceTitle TEXT")
                database.execSQL(
                    """
                    UPDATE phone_articles
                    SET independentSaved = 1,
                        independentChangedAt = updatedAt,
                        independentSortOrder = updatedAt
                    WHERE deleted = 0 AND favoriteSaved = 0 AND watchLaterSaved = 0
                    """.trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS index_phone_articles_independentSaved_independentSortOrder ON phone_articles(independentSaved, independentSortOrder)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_phone_articles_rssSourceUrl_updatedAt ON phone_articles(rssSourceUrl, updatedAt)")
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS phone_rss_sources (
                        url TEXT NOT NULL PRIMARY KEY,
                        sourceDeviceId TEXT NOT NULL,
                        title TEXT NOT NULL,
                        description TEXT NOT NULL,
                        siteUrl TEXT,
                        imageUrl TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        sortOrder INTEGER NOT NULL,
                        deleted INTEGER NOT NULL,
                        deletedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS index_phone_rss_sources_deleted_sortOrder ON phone_rss_sources(deleted, sortOrder)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                val importedRoot = ImportedContentIds.ROOT_SOURCE_URL.replace("'", "''")
                database.execSQL(
                    """
                    DELETE FROM phone_articles
                    WHERE rssSourceUrl = '$importedRoot'
                      AND (
                          length(COALESCE(contentHtml, '')) + length(contentText) > 180000
                          OR length(contentText) > 120000
                          OR length(COALESCE(contentHtml, '')) > 120000
                      )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE phone_rss_sources ADD COLUMN isPinned INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE phone_articles ADD COLUMN syncBodyHash TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE phone_articles ADD COLUMN syncBodyByteCount INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE phone_articles ADD COLUMN syncChunkSize INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE phone_articles ADD COLUMN syncChunkHashesJson TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE phone_articles ADD COLUMN syncMetadataHash TEXT NOT NULL DEFAULT ''")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_phone_articles_syncBodyHash ON phone_articles(syncBodyHash)")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS sync_change_log (
                        seq INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        kind TEXT NOT NULL,
                        entityId TEXT NOT NULL,
                        changedAt INTEGER NOT NULL,
                        originDeviceId TEXT NOT NULL,
                        reason TEXT NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS index_sync_change_log_kind_entityId ON sync_change_log(kind, entityId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_sync_change_log_seq ON sync_change_log(seq)")
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS sync_peer_state (
                        peerDeviceId TEXT NOT NULL PRIMARY KEY,
                        lastLocalSeqAckedByPeer INTEGER NOT NULL DEFAULT 0,
                        lastRemoteSeqApplied INTEGER NOT NULL DEFAULT 0,
                        lastFullSyncAt INTEGER NOT NULL DEFAULT 0,
                        lastProtocolVersion INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
            }
        }
    }
}
