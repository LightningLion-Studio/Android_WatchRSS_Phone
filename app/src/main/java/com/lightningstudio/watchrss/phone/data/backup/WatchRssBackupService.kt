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
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WatchRssBackupService(
    context: Context,
    private val database: PhoneCompanionDatabase,
    private val repository: PhoneCompanionRepository,
    private val deviceId: String
) {
    private val appContext = context.applicationContext
    private val contentResolver = appContext.contentResolver

    // ── 专有格式备份（WRSS）──

    suspend fun exportTo(uriString: String): BackupSummary = withContext(Dispatchers.IO) {
        val uri = parseUri(uriString)
        val output = contentResolver.openOutputStream(uri, "w")
            ?: error("无法创建 WRSS 文件")
        output.use { exportTo(it) }
    }

    suspend fun exportTo(output: OutputStream): BackupSummary = withContext(Dispatchers.IO) {
        val snapshot = collectSnapshot()
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
            var changedAppMetaCount = 0

            database.withTransaction {
                when (mode) {
                    BackupImportMode.REPLACE -> {
                        database.phoneRssSourceDao().deleteAll()
                        database.phoneSavedItemDao().deleteAll()
                        database.appMetaDao().deleteAll()
                        if (normalizedSources.isNotEmpty()) {
                            database.phoneRssSourceDao().upsertAll(normalizedSources)
                        }
                        changedSourceCount = normalizedSources.size
                        changedArticleCount = repository.replaceArticlesFromBackup(snapshot.articles)
                        if (snapshot.savedItems.isNotEmpty()) {
                            database.phoneSavedItemDao().upsertAll(snapshot.savedItems)
                        }
                        changedSavedItemCount = snapshot.savedItems.size
                        if (snapshot.appMeta.isNotEmpty()) {
                            snapshot.appMeta.forEach { database.appMetaDao().set(it) }
                        }
                        changedAppMetaCount = snapshot.appMeta.size
                    }

                    BackupImportMode.MERGE -> {
                        changedSourceCount = mergeSources(normalizedSources)
                        changedArticleCount = repository.mergeArticlesFromBackup(snapshot.articles)
                        changedSavedItemCount = mergeSavedItems(snapshot.savedItems)
                        // 对于元数据，合并模式使用 setIfAbsent，保留本地已有的值
                        snapshot.appMeta.forEach { meta ->
                            database.appMetaDao().setIfAbsent(meta)
                            changedAppMetaCount++
                        }
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
                appMetaCount = snapshot.appMeta.size,
                changedSourceCount = changedSourceCount,
                changedArticleCount = changedArticleCount,
                changedSavedItemCount = changedSavedItemCount,
                changedAppMetaCount = changedAppMetaCount
            )
        }

    // ── 人类可读格式导出（JSON）──

    suspend fun exportHumanReadable(uriString: String): BackupSummary = withContext(Dispatchers.IO) {
        val uri = parseUri(uriString)
        val output = contentResolver.openOutputStream(uri, "w")
            ?: error("无法创建导出文件")
        output.use { exportHumanReadable(it) }
    }

    suspend fun exportHumanReadable(output: OutputStream): BackupSummary = withContext(Dispatchers.IO) {
        val snapshot = collectSnapshot()
        val json = buildHumanReadableJson(snapshot)
        output.write(json.toString(2).toByteArray(Charsets.UTF_8))
        snapshot.summary()
    }

    // ── 内部方法 ──

    private suspend fun collectSnapshot(): WatchRssBackupSnapshot {
        return WatchRssBackupSnapshot(
            exportedAt = System.currentTimeMillis(),
            appVersion = currentAppVersion(),
            dataStructureVersion = WatchRssBackupArchive.CURRENT_DATA_STRUCTURE_VERSION,
            sources = database.phoneRssSourceDao().getAllForSync(),
            articles = repository.getArticlesForBackup(),
            savedItems = database.phoneSavedItemDao().getAll(),
            appMeta = database.appMetaDao().getAll()
        )
    }

    private fun buildHumanReadableJson(snapshot: WatchRssBackupSnapshot): JSONObject {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        return JSONObject().apply {
            put("format", "watchrss-human-readable-export")
            put("exportedAt", dateFormat.format(Date(snapshot.exportedAt)))
            put("appVersion", snapshot.appVersion)
            put("dataStructureVersion", snapshot.dataStructureVersion)

            // 元数据
            val metaArray = JSONArray()
            snapshot.appMeta.forEach { meta ->
                metaArray.put(JSONObject().apply {
                    put("key", meta.key)
                    put("value", meta.value)
                    if (meta.key == "first_use_at") {
                        meta.value.toLongOrNull()?.let {
                            put("valueReadable", dateFormat.format(Date(it)))
                        }
                    }
                })
            }
            put("appMeta", metaArray)

            // RSS 源
            val sourcesArray = JSONArray()
            snapshot.sources.forEach { source ->
                sourcesArray.put(JSONObject().apply {
                    put("title", source.title)
                    put("url", source.url)
                    put("description", source.description)
                    putNullable("siteUrl", source.siteUrl)
                    putNullable("imageUrl", source.imageUrl)
                    put("isPinned", source.isPinned)
                    put("createdAt", dateFormat.format(Date(source.createdAt)))
                    put("articleCount", snapshot.articles.count { it.rssSourceUrl == source.url })
                })
            }
            put("rssSources", sourcesArray)

            // 收藏
            val favoritesArray = JSONArray()
            snapshot.articles.filter { it.favoriteSaved && !it.deleted }.forEach { article ->
                favoritesArray.put(articleToHumanReadableJson(article, dateFormat))
            }
            put("favorites", favoritesArray)

            // 稍后阅读
            val watchLaterArray = JSONArray()
            snapshot.articles.filter { it.watchLaterSaved && !it.deleted }.forEach { article ->
                watchLaterArray.put(articleToHumanReadableJson(article, dateFormat))
            }
            put("watchLater", watchLaterArray)

            // 独立文章（非 RSS、非收藏、非稍后阅读）
            val independentArray = JSONArray()
            snapshot.articles.filter {
                it.independentSaved && !it.deleted &&
                    !it.favoriteSaved && !it.watchLaterSaved
            }.forEach { article ->
                independentArray.put(articleToHumanReadableJson(article, dateFormat))
            }
            put("independentArticles", independentArray)

            // 全部文章（不含正文，仅元数据摘要）
            val allArticlesArray = JSONArray()
            snapshot.articles.filter { !it.deleted }.forEach { article ->
                allArticlesArray.put(JSONObject().apply {
                    put("title", article.title)
                    put("url", article.url)
                    put("siteName", article.siteName)
                    put("excerpt", article.excerpt)
                    putNullable("imageUrl", article.imageUrl)
                    put("importedAt", dateFormat.format(Date(article.importedAt)))
                    put("isFavorite", article.favoriteSaved)
                    put("isWatchLater", article.watchLaterSaved)
                    put("readingProgress", (article.readingProgress * 100).toInt())
                    put("contentLength", article.contentText.length)
                })
            }
            put("allArticles", allArticlesArray)

            // 保存项（从手表同步的收藏/稍后阅读）
            val savedItemsArray = JSONArray()
            snapshot.savedItems.forEach { item ->
                savedItemsArray.put(JSONObject().apply {
                    put("type", item.type)
                    put("title", item.title)
                    put("link", item.link)
                    put("summary", item.summary)
                    put("channelTitle", item.channelTitle)
                    put("pubDate", item.pubDate)
                    put("syncedAt", dateFormat.format(Date(item.syncedAt)))
                })
            }
            put("savedItems", savedItemsArray)

            // 统计信息
            put("statistics", JSONObject().apply {
                put("totalSources", snapshot.sources.size)
                put("totalArticles", snapshot.articles.count { !it.deleted })
                put("totalFavorites", snapshot.articles.count { it.favoriteSaved && !it.deleted })
                put("totalWatchLater", snapshot.articles.count { it.watchLaterSaved && !it.deleted })
                put("totalSavedItems", snapshot.savedItems.size)
            })
        }
    }

    private fun articleToHumanReadableJson(
        article: com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity,
        dateFormat: SimpleDateFormat
    ): JSONObject = JSONObject().apply {
        put("title", article.title)
        put("url", article.url)
        put("siteName", article.siteName)
        put("excerpt", article.excerpt)
        putNullable("imageUrl", article.imageUrl)
        put("importedAt", dateFormat.format(Date(article.importedAt)))
        put("contentText", article.contentText)
        article.contentHtml?.let { put("contentHtml", it) }
        put("readingProgress", (article.readingProgress * 100).toInt())
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

    private fun JSONObject.putNullable(name: String, value: String?) {
        put(name, value ?: JSONObject.NULL)
    }
}
