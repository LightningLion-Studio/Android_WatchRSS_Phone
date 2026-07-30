package com.lightningstudio.watchrss.phone.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lightningstudio.watchrss.phone.data.model.ImportedContentIds
import com.lightningstudio.watchrss.phone.data.reader.ReaderBackgroundAssetEntity
import com.lightningstudio.watchrss.phone.data.reader.ReaderDeletionEntity
import com.lightningstudio.watchrss.phone.data.reader.ReaderFontAssetEntity
import com.lightningstudio.watchrss.phone.data.reader.ReaderPresetDao
import com.lightningstudio.watchrss.phone.data.reader.ReaderPresetEntity

@Database(
    entities = [
        PhoneSavedItemEntity::class,
        PhoneArticleEntity::class,
        PhoneRssSourceEntity::class,
        SyncChangeLogEntity::class,
        SyncPeerStateEntity::class,
        ReaderPresetEntity::class,
        ReaderFontAssetEntity::class,
        ReaderBackgroundAssetEntity::class,
        ReaderDeletionEntity::class,
        AppMetaEntity::class,
        PhoneLlmTokenUsageEntity::class
    ],
    version = 13,
    exportSchema = false
)
abstract class PhoneCompanionDatabase : RoomDatabase() {
    abstract fun phoneSavedItemDao(): PhoneSavedItemDao
    abstract fun phoneArticleDao(): PhoneArticleDao
    abstract fun phoneRssSourceDao(): PhoneRssSourceDao
    abstract fun syncChangeLogDao(): SyncChangeLogDao
    abstract fun syncPeerStateDao(): SyncPeerStateDao
    abstract fun readerPresetDao(): ReaderPresetDao
    abstract fun appMetaDao(): AppMetaDao
    abstract fun llmTokenUsageDao(): PhoneLlmTokenUsageDao

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

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE phone_articles ADD COLUMN readingProgress REAL NOT NULL DEFAULT 0"
                )
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE phone_articles ADD COLUMN isRead INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE phone_rss_sources ADD COLUMN useOriginalContent INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE phone_rss_sources ADD COLUMN continuePlaybackInBackground INTEGER NOT NULL DEFAULT 0"
                )
                database.createAppMetaTable()
                database.createLlmTokenUsageTable()
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.ensureSplitBaselineSchema()
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.ensureSplitBaselineSchema()
                database.createReaderPresetTables()
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.createAppMetaTable()
                database.createLlmTokenUsageTable()
                database.createReaderPresetTables()
                database.execSQL(
                    "ALTER TABLE phone_articles ADD COLUMN readingPositionBytes INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE phone_articles ADD COLUMN readingPositionContentHash TEXT NOT NULL DEFAULT ''"
                )
                database.execSQL(
                    "ALTER TABLE phone_articles ADD COLUMN readingPositionChangedAt INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.createAppMetaTable()
                database.createLlmTokenUsageTable()
                database.createReaderPresetTables()
            }
        }
    }
}

private fun SupportSQLiteDatabase.createReaderPresetTables() {
    execSQL(
        """
        CREATE TABLE IF NOT EXISTS reader_presets (
            id TEXT NOT NULL PRIMARY KEY,
            name TEXT NOT NULL,
            payloadJson TEXT NOT NULL,
            updatedAt INTEGER NOT NULL,
            modifiedBy TEXT NOT NULL,
            deleted INTEGER NOT NULL
        )
        """.trimIndent()
    )
    execSQL("CREATE INDEX IF NOT EXISTS index_reader_presets_deleted_name ON reader_presets(deleted, name)")
    execSQL("CREATE INDEX IF NOT EXISTS index_reader_presets_updatedAt ON reader_presets(updatedAt)")
    execSQL(
        """
        CREATE TABLE IF NOT EXISTS reader_font_assets (
            id TEXT NOT NULL PRIMARY KEY,
            sha256 TEXT NOT NULL,
            displayName TEXT NOT NULL,
            familyName TEXT NOT NULL,
            fileName TEXT NOT NULL,
            mimeType TEXT NOT NULL,
            byteCount INTEGER NOT NULL,
            faceCount INTEGER NOT NULL,
            metadataJson TEXT NOT NULL,
            updatedAt INTEGER NOT NULL,
            modifiedBy TEXT NOT NULL,
            deleted INTEGER NOT NULL
        )
        """.trimIndent()
    )
    execSQL("CREATE INDEX IF NOT EXISTS index_reader_font_assets_deleted_displayName ON reader_font_assets(deleted, displayName)")
    execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_reader_font_assets_sha256 ON reader_font_assets(sha256)")
    execSQL(
        """
        CREATE TABLE IF NOT EXISTS reader_background_assets (
            id TEXT NOT NULL PRIMARY KEY,
            sha256 TEXT NOT NULL,
            displayName TEXT NOT NULL,
            kind TEXT NOT NULL,
            mimeType TEXT NOT NULL,
            masterFileName TEXT NOT NULL,
            byteCount INTEGER NOT NULL,
            durationMs INTEGER NOT NULL,
            width INTEGER NOT NULL,
            height INTEGER NOT NULL,
            posterAssetId TEXT,
            variantsJson TEXT NOT NULL,
            updatedAt INTEGER NOT NULL,
            modifiedBy TEXT NOT NULL,
            deleted INTEGER NOT NULL
        )
        """.trimIndent()
    )
    execSQL("CREATE INDEX IF NOT EXISTS index_reader_background_assets_deleted_displayName ON reader_background_assets(deleted, displayName)")
    execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_reader_background_assets_sha256 ON reader_background_assets(sha256)")
    execSQL(
        """
        CREATE TABLE IF NOT EXISTS reader_deletions (
            kind TEXT NOT NULL,
            entityId TEXT NOT NULL,
            deletedAt INTEGER NOT NULL,
            deletedBy TEXT NOT NULL,
            PRIMARY KEY(kind, entityId)
        )
        """.trimIndent()
    )
    execSQL("CREATE INDEX IF NOT EXISTS index_reader_deletions_deletedAt ON reader_deletions(deletedAt)")
}

private fun SupportSQLiteDatabase.ensureSplitBaselineSchema() {
    addColumnIfMissing(
        table = "phone_articles",
        column = "isRead",
        definition = "INTEGER NOT NULL DEFAULT 0"
    )
    addColumnIfMissing(
        table = "phone_rss_sources",
        column = "useOriginalContent",
        definition = "INTEGER NOT NULL DEFAULT 0"
    )
    addColumnIfMissing(
        table = "phone_rss_sources",
        column = "continuePlaybackInBackground",
        definition = "INTEGER NOT NULL DEFAULT 0"
    )
    createAppMetaTable()
    createLlmTokenUsageTable()
}

private fun SupportSQLiteDatabase.addColumnIfMissing(
    table: String,
    column: String,
    definition: String
) {
    val exists = query("PRAGMA table_info(`$table`)").use { cursor ->
        val nameIndex = cursor.getColumnIndex("name")
        generateSequence { if (cursor.moveToNext()) cursor else null }
            .any { it.getString(nameIndex) == column }
    }
    if (!exists) {
        execSQL("ALTER TABLE `$table` ADD COLUMN `$column` $definition")
    }
}

private fun SupportSQLiteDatabase.createAppMetaTable() {
    execSQL(
        """
        CREATE TABLE IF NOT EXISTS app_meta (
            key TEXT NOT NULL PRIMARY KEY,
            value TEXT NOT NULL
        )
        """.trimIndent()
    )
}

private fun SupportSQLiteDatabase.createLlmTokenUsageTable() {
    execSQL(
        """
        CREATE TABLE IF NOT EXISTS llm_token_usage (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            provider TEXT NOT NULL,
            model TEXT NOT NULL,
            requestId TEXT NOT NULL,
            createdAt INTEGER NOT NULL,
            promptTokens INTEGER,
            completionTokens INTEGER,
            totalTokens INTEGER,
            reasoningTokens INTEGER,
            cachedPromptTokens INTEGER,
            inputTokens INTEGER,
            outputTokens INTEGER,
            promptTokenCount INTEGER,
            candidatesTokenCount INTEGER,
            totalTokenCount INTEGER
        )
        """.trimIndent()
    )
    execSQL(
        "CREATE INDEX IF NOT EXISTS index_llm_token_usage_createdAt " +
            "ON llm_token_usage(createdAt)"
    )
    execSQL(
        "CREATE INDEX IF NOT EXISTS index_llm_token_usage_provider_model " +
            "ON llm_token_usage(provider, model)"
    )
}
