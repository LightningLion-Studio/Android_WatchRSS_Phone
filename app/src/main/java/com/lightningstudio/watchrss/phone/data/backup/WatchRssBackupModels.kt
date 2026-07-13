package com.lightningstudio.watchrss.phone.data.backup

import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
import com.lightningstudio.watchrss.phone.data.db.PhoneRssSourceEntity
import com.lightningstudio.watchrss.phone.data.db.PhoneSavedItemEntity

enum class BackupImportMode {
    MERGE,
    REPLACE
}

data class BackupPreview(
    val exportedAt: Long,
    val appVersion: String,
    val sourceCount: Int,
    val articleCount: Int,
    val savedItemCount: Int
)

data class BackupSummary(
    val exportedAt: Long,
    val sourceCount: Int,
    val articleCount: Int,
    val savedItemCount: Int
)

data class BackupImportResult(
    val mode: BackupImportMode,
    val sourceCount: Int,
    val articleCount: Int,
    val savedItemCount: Int,
    val changedSourceCount: Int,
    val changedArticleCount: Int,
    val changedSavedItemCount: Int
)

internal data class WatchRssBackupSnapshot(
    val exportedAt: Long,
    val appVersion: String,
    val sources: List<PhoneRssSourceEntity>,
    val articles: List<PhoneArticleEntity>,
    val savedItems: List<PhoneSavedItemEntity>
) {
    fun preview(): BackupPreview = BackupPreview(
        exportedAt = exportedAt,
        appVersion = appVersion,
        sourceCount = sources.size,
        articleCount = articles.size,
        savedItemCount = savedItems.size
    )

    fun summary(): BackupSummary = BackupSummary(
        exportedAt = exportedAt,
        sourceCount = sources.size,
        articleCount = articles.size,
        savedItemCount = savedItems.size
    )
}

const val WATCHRSS_BACKUP_MIME_TYPE = "application/vnd.watchrss.backup"
const val WATCHRSS_BACKUP_EXTENSION = ".wrss"
