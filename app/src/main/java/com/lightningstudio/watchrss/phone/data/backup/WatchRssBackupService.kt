package com.lightningstudio.watchrss.phone.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.lightningstudio.watchrss.phone.data.db.PhoneCompanionDatabase
import com.lightningstudio.watchrss.phone.data.db.PhoneRssSourceEntity
import com.lightningstudio.watchrss.phone.data.db.PhoneSavedItemEntity
import com.lightningstudio.watchrss.phone.data.repo.PhoneCompanionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

class WatchRssBackupService(
    context: Context,
    private val database: PhoneCompanionDatabase,
    private val repository: PhoneCompanionRepository,
    private val deviceId: String
) {
    private val appContext = context.applicationContext
    private val contentResolver = appContext.contentResolver

    suspend fun exportTo(uriString: String): BackupSummary = withContext(Dispatchers.IO) {
        val uri = parseUri(uriString)
        val output = contentResolver.openOutputStream(uri, "w")
            ?: error("无法创建 WRSS 文件")
        output.use { exportTo(it) }
    }

    suspend fun exportTo(output: OutputStream): BackupSummary = withContext(Dispatchers.IO) {
        val snapshot = WatchRssBackupSnapshot(
            exportedAt = System.currentTimeMillis(),
            appVersion = currentAppVersion(),
            sources = database.phoneRssSourceDao().getAllForSync(),
            articles = repository.getArticlesForBackup(),
            savedItems = database.phoneSavedItemDao().getAll()
        )
        WatchRssBackupArchive.write(snapshot, output)
        snapshot.summary()
    }

    suspend fun inspect(uriString: String): BackupPreview = withContext(Dispatchers.IO) {
        val uri = parseUri(uriString)
        val input = contentResolver.openInputStream(uri)
            ?: error("无法读取 WRSS 文件")
        input.use(::inspect)
    }

    fun inspect(input: InputStream): BackupPreview = WatchRssBackupArchive.read(input).preview()

    suspend fun importFrom(uriString: String, mode: BackupImportMode): BackupImportResult =
        withContext(Dispatchers.IO) {
            val uri = parseUri(uriString)
            val input = contentResolver.openInputStream(uri)
                ?: error("无法读取 WRSS 文件")
            input.use { importFrom(it, mode) }
        }

    suspend fun importFrom(input: InputStream, mode: BackupImportMode): BackupImportResult =
        withContext(Dispatchers.IO) {
            val snapshot = WatchRssBackupArchive.read(input)
            val normalizedSources = snapshot.sources.map { source ->
                source.copy(sourceDeviceId = deviceId)
            }
            var changedSourceCount = 0
            var changedArticleCount = 0
            var changedSavedItemCount = 0

            database.withTransaction {
                when (mode) {
                    BackupImportMode.REPLACE -> {
                        database.phoneRssSourceDao().deleteAll()
                        database.phoneSavedItemDao().deleteAll()
                        if (normalizedSources.isNotEmpty()) {
                            database.phoneRssSourceDao().upsertAll(normalizedSources)
                        }
                        changedSourceCount = normalizedSources.size
                        changedArticleCount = repository.replaceArticlesFromBackup(snapshot.articles)
                        if (snapshot.savedItems.isNotEmpty()) {
                            database.phoneSavedItemDao().upsertAll(snapshot.savedItems)
                        }
                        changedSavedItemCount = snapshot.savedItems.size
                    }

                    BackupImportMode.MERGE -> {
                        changedSourceCount = mergeSources(normalizedSources)
                        changedArticleCount = repository.mergeArticlesFromBackup(snapshot.articles)
                        changedSavedItemCount = mergeSavedItems(snapshot.savedItems)
                    }
                }
                database.syncChangeLogDao().deleteAll()
                database.syncPeerStateDao().deleteAll()
            }
            repository.pruneUnreferencedArticleContent()

            BackupImportResult(
                mode = mode,
                sourceCount = snapshot.sources.size,
                articleCount = snapshot.articles.size,
                savedItemCount = snapshot.savedItems.size,
                changedSourceCount = changedSourceCount,
                changedArticleCount = changedArticleCount,
                changedSavedItemCount = changedSavedItemCount
            )
        }

    private suspend fun mergeSources(incoming: List<PhoneRssSourceEntity>): Int {
        var changed = 0
        incoming.forEach { backup ->
            val current = database.phoneRssSourceDao().getByUrl(backup.url)
            val backupChangedAt = maxOf(backup.updatedAt, backup.deletedAt)
            val currentChangedAt = current?.let { maxOf(it.updatedAt, it.deletedAt) } ?: Long.MIN_VALUE
            if (current == null || backupChangedAt > currentChangedAt) {
                database.phoneRssSourceDao().upsert(backup)
                changed += 1
            }
        }
        return changed
    }

    private suspend fun mergeSavedItems(incoming: List<PhoneSavedItemEntity>): Int {
        val currentByKey = database.phoneSavedItemDao().getAll()
            .associateBy { it.type to it.stableKey }
        val changed = incoming.filter { backup ->
            val current = currentByKey[backup.type to backup.stableKey]
            current == null || backup.syncedAt > current.syncedAt
        }
        if (changed.isNotEmpty()) {
            database.phoneSavedItemDao().upsertAll(changed)
        }
        return changed.size
    }

    private fun parseUri(uriString: String): Uri {
        require(uriString.isNotBlank()) { "文件地址无效" }
        return Uri.parse(uriString)
    }

    private fun currentAppVersion(): String {
        return runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName.orEmpty()
        }.getOrDefault("")
    }
}
