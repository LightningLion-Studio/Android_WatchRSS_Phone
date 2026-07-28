package com.lightningstudio.watchrss.phone.data.backup

data class CloudBackupPayload(
    val privateLibraryArchive: ByteArray,
    val rssStateJson: ByteArray
)

data class CloudRestorePreview(
    val sourceCount: Int,
    val privateArticleCount: Int,
    val rssStateCount: Int,
    val exportedAt: Long
)

data class CloudRestoreResult(
    val privateImport: BackupImportResult,
    val appliedRssStates: Int,
    val pendingRssStates: Int
)
