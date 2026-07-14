package com.lightningstudio.watchrss.phone.data.backup

import com.lightningstudio.watchrss.phone.data.db.AppMetaEntity
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
    val dataStructureVersion: Int,
    val sourceCount: Int,
    val articleCount: Int,
    val savedItemCount: Int,
    val appMetaCount: Int
)

data class BackupSummary(
    val exportedAt: Long,
    val sourceCount: Int,
    val articleCount: Int,
    val savedItemCount: Int,
    val appMetaCount: Int
)

data class BackupImportResult(
    val mode: BackupImportMode,
    val sourceCount: Int,
    val articleCount: Int,
    val savedItemCount: Int,
    val appMetaCount: Int,
    val changedSourceCount: Int,
    val changedArticleCount: Int,
    val changedSavedItemCount: Int,
    val changedAppMetaCount: Int
)

/**
 * 当备份文件的数据结构版本高于当前 APP 支持的版本时抛出，
 * 提示用户需要升级应用。
 */
class BackupVersionTooHighException(
    val backupVersion: Int,
    val currentVersion: Int
) : Exception(
    "备份文件的数据结构版本（v$backupVersion）高于当前应用支持的版本（v$currentVersion），" +
        "请从 https://github.com/LightningLion-Studio/Android_WatchRSS_Phone/releases 获取最新版本。"
)

internal data class WatchRssBackupSnapshot(
    val exportedAt: Long,
    val appVersion: String,
    val dataStructureVersion: Int,
    val sources: List<PhoneRssSourceEntity>,
    val articles: List<PhoneArticleEntity>,
    val savedItems: List<PhoneSavedItemEntity>,
    val appMeta: List<AppMetaEntity>
) {
    fun preview(): BackupPreview = BackupPreview(
        exportedAt = exportedAt,
        appVersion = appVersion,
        dataStructureVersion = dataStructureVersion,
        sourceCount = sources.size,
        articleCount = articles.size,
        savedItemCount = savedItems.size,
        appMetaCount = appMeta.size
    )

    fun summary(): BackupSummary = BackupSummary(
        exportedAt = exportedAt,
        sourceCount = sources.size,
        articleCount = articles.size,
        savedItemCount = savedItems.size,
        appMetaCount = appMeta.size
    )
}

const val WATCHRSS_BACKUP_MIME_TYPE = "application/vnd.watchrss.backup"
const val WATCHRSS_BACKUP_EXTENSION = ".wrss"
const val WATCHRSS_HUMAN_READABLE_EXTENSION = ".json"
const val GITHUB_RELEASES_URL = "https://github.com/LightningLion-Studio/Android_WatchRSS_Phone/releases"
