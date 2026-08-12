package com.lightningstudio.watchrss.phone.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.lightningstudio.watchrss.phone.data.db.PhoneCompanionDatabase
import com.lightningstudio.watchrss.phone.data.db.PhoneRssSourceEntity
import com.lightningstudio.watchrss.phone.data.db.PhoneSavedItemEntity
import com.lightningstudio.watchrss.phone.data.model.ImportedContentIds
import com.lightningstudio.watchrss.phone.data.repo.PhoneCompanionRepository
import com.lightningstudio.watchrss.phone.connection.bluetooth.LibrarySyncPayload
import com.lightningstudio.watchrss.phone.data.reader.ReaderPresetRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WatchRssBackupService(
    context: Context,
    private val database: PhoneCompanionDatabase,
    private val repository: PhoneCompanionRepository,
    private val readerPresetRepository: ReaderPresetRepository,
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

    suspend fun createCloudPayload(): CloudBackupPayload = withContext(Dispatchers.IO) {
        CloudBackupPayload(
            privateLibraryArchive = createCloudPrivateArchive(),
            rssStateJson = createCloudRssState()
        )
    }

    suspend fun createCloudPrivateArchive(): ByteArray = withContext(Dispatchers.IO) {
        createCloudPrivateArchiveFile().readBytes()
    }

    suspend fun createCloudPrivateArchiveFile(): File = withContext(Dispatchers.IO) {
        val exportedAt = System.currentTimeMillis()
        val allArticles = repository.getArticlesForBackup()
        val privateArticles = allArticles.filter { article ->
            ImportedContentIds.isImportedContentUrl(article.url) ||
                ImportedContentIds.isImportedContentUrl(article.rssSourceUrl) ||
                article.rssSourceUrl.isNullOrBlank()
        }
        val privateSnapshot = WatchRssBackupSnapshot(
            exportedAt = exportedAt,
            appVersion = currentAppVersion(),
            dataStructureVersion = WatchRssBackupArchive.CURRENT_DATA_STRUCTURE_VERSION,
            sources = database.phoneRssSourceDao().getAllForSync(),
            articles = privateArticles,
            savedItems = emptyList(),
            readerSnapshot = readerPresetRepository.exportSnapshot(),
            appMeta = database.appMetaDao().getAll()
        ).let {
            it.copy(readerResources = readerResources(requireNotNull(it.readerSnapshot)))
        }
        val directory = File(appContext.cacheDir, "cloud-staging").apply { mkdirs() }
        val archive = File(directory, "private-library.wrss")
        archive.outputStream().buffered().use { output ->
            WatchRssBackupArchive.write(privateSnapshot, output)
        }
        archive
    }

    suspend fun createCloudRssState(): ByteArray = withContext(Dispatchers.IO) {
        CloudRssStateCodec.encode(
            articles = repository.getArticlesForBackup(),
            exportedAt = System.currentTimeMillis(),
            sources = database.phoneRssSourceDao().getAllForSync()
        )
    }

    suspend fun createCloudRelayLibrary(): ByteArray = withContext(Dispatchers.IO) {
        val articles = repository.getArticlesForBackup().map { article ->
            val privateBody =
                ImportedContentIds.isImportedContentUrl(article.url) ||
                    ImportedContentIds.isImportedContentUrl(article.rssSourceUrl) ||
                    article.rssSourceUrl.isNullOrBlank()
            if (privateBody) article else article.copy(contentHtml = null, contentText = "")
        }
        LibrarySyncPayload.buildResponse(
            deviceId = deviceId,
            articles = articles,
            rssSources = database.phoneRssSourceDao().getAllForSync()
        ).toString().toByteArray(Charsets.UTF_8)
    }

    suspend fun restoreCloudRelayLibrary(payload: ByteArray): Int =
        withContext(Dispatchers.IO) {
            val json = org.json.JSONObject(payload.toString(Charsets.UTF_8))
            val articles = LibrarySyncPayload.parseArticles(json)
            val sources = LibrarySyncPayload.parseRssSources(json)
            repository.mergeArticlesFromSync(articles) +
                repository.mergeRssSourcesFromSync(sources)
        }

    suspend fun inspectCloudPayload(payload: CloudBackupPayload): CloudRestorePreview =
        withContext(Dispatchers.IO) {
            val privateSnapshot = WatchRssBackupArchive.read(
                ByteArrayInputStream(payload.privateLibraryArchive)
            )
            CloudRestorePreview(
                sourceCount = privateSnapshot.sources.size,
                privateArticleCount = privateSnapshot.articles.size,
                rssStateCount = CloudRssStateCodec.decode(payload.rssStateJson).size,
                exportedAt = privateSnapshot.exportedAt
            )
        }

    fun inspectCloudRssState(payload: ByteArray): Pair<Int, Int> =
        CloudRssStateCodec.decodeSources(payload).size to CloudRssStateCodec.decode(payload).size

    fun inspectCloudPrivateLibrary(payload: ByteArray): BackupPreview =
        inspect(ByteArrayInputStream(payload))

    fun inspectCloudRelayLibrary(payload: ByteArray): Pair<Int, Int> {
        val json = org.json.JSONObject(payload.toString(Charsets.UTF_8))
        return LibrarySyncPayload.parseRssSources(json).size to
            LibrarySyncPayload.parseArticles(json).size
    }

    suspend fun restoreCloudPayload(
        payload: CloudBackupPayload,
        mode: BackupImportMode = BackupImportMode.MERGE
    ): CloudRestoreResult = withContext(Dispatchers.IO) {
        val privateImport = restorePrivateLibrary(payload.privateLibraryArchive, mode)
        val (applied, pending) = applyCloudRssState(payload.rssStateJson)
        CloudRestoreResult(
            privateImport = privateImport,
            appliedRssStates = applied,
            pendingRssStates = pending
        )
    }

    suspend fun restorePrivateLibrary(
        archive: ByteArray,
        mode: BackupImportMode = BackupImportMode.MERGE
    ): BackupImportResult = withContext(Dispatchers.IO) {
        if (mode == BackupImportMode.REPLACE) createSafetySnapshot()
        importFrom(
            ByteArrayInputStream(archive),
            mode,
            resetSyncHistory = false
        )
    }

    suspend fun applyCloudRssState(stateJson: ByteArray): Pair<Int, Int> =
        withContext(Dispatchers.IO) {
        val states = CloudRssStateCodec.decode(stateJson)
        repository.mergeRssSourcesFromSync(CloudRssStateCodec.decodeSources(stateJson))
        var applied = 0
        database.withTransaction {
            states.forEach { incoming ->
                val current = database.phoneArticleDao().getById(incoming.articleId)
                    ?: return@forEach
                database.phoneArticleDao().upsert(current.mergeCloudState(incoming))
                applied += 1
            }
        }
        applied to (states.size - applied)
    }

    suspend fun createSafetySnapshot(): File = withContext(Dispatchers.IO) {
        val directory = File(appContext.filesDir, "cloud-safety").apply { mkdirs() }
        SafetySnapshotRetention.prune(directory)
        val outputFile = File(directory, "before-restore-${System.currentTimeMillis()}.wrss")
        val temporary = File(directory, "${outputFile.name}.part")
        try {
            temporary.outputStream().use { exportTo(it) }
            SafetySnapshotRetention.commit(temporary, outputFile)
        } finally {
            temporary.delete()
        }
        SafetySnapshotRetention.prune(directory)
        outputFile
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

    suspend fun importFrom(
        input: InputStream,
        mode: BackupImportMode,
        resetSyncHistory: Boolean = true
    ): BackupImportResult =
        withContext(Dispatchers.IO) {
            val snapshot = WatchRssBackupArchive.read(
                input,
                readerPresetRepository.resourceStore
            )
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
                if (resetSyncHistory) {
                    database.syncChangeLogDao().deleteAll()
                    database.syncPeerStateDao().deleteAll()
                }
            }
            snapshot.readerSnapshot?.let { readerPresetRepository.mergeRemote(
                presets = it.presets,
                fonts = it.fonts,
                backgrounds = it.backgrounds,
                deletions = it.deletions
            ) }
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

    private suspend fun readerResources(
        snapshot: com.lightningstudio.watchrss.phone.data.reader.ReaderPresetSnapshot
    ): List<ReaderBackupResource> = buildList {
        snapshot.fonts.filterNot { it.deleted }.forEach { font ->
            readerPresetRepository.resourceStore.fontFile(font.fileName)?.let { file ->
                add(ReaderBackupResource("font", font.fileName, font.sha256, font.byteCount, file))
            }
        }
        snapshot.backgrounds.filterNot { it.deleted }.forEach { background ->
            readerPresetRepository.resourceStore.backgroundFile(background.masterFileName)?.let { file ->
                add(
                    ReaderBackupResource(
                        "background",
                        background.masterFileName,
                        background.sha256,
                        background.byteCount,
                        file
                    )
                )
            }
            val variants = runCatching { org.json.JSONObject(background.variantsJson) }.getOrNull()
            listOf("watch", "watchPoster").forEach { key ->
                variants?.optJSONObject(key)?.let { variant ->
                    val fileName = variant.optString("fileName")
                    val hash = variant.optString("sha256")
                    val byteCount = variant.optLong("byteCount")
                    readerPresetRepository.resourceStore.variantFile(fileName)?.let { file ->
                        if (hash.length == 64 && file.length() == byteCount) {
                            add(ReaderBackupResource("variant", fileName, hash, byteCount, file))
                        }
                    }
                }
            }
        }
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
        val readerSnapshot = readerPresetRepository.exportSnapshot()
        return WatchRssBackupSnapshot(
            exportedAt = System.currentTimeMillis(),
            appVersion = currentAppVersion(),
            dataStructureVersion = WatchRssBackupArchive.CURRENT_DATA_STRUCTURE_VERSION,
            sources = database.phoneRssSourceDao().getAllForSync(),
            articles = repository.getArticlesForBackup(),
            savedItems = database.phoneSavedItemDao().getAll(),
            readerSnapshot = readerSnapshot,
            readerResources = readerResources(readerSnapshot),
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

    private fun com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity.mergeCloudState(
        incoming: CloudRssArticleState
    ): com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity {
        val favoriteRemote = stateWins(
            incoming.favoriteChangedAt,
            incoming.sourceDeviceId,
            favoriteChangedAt,
            sourceDeviceId
        )
        val watchLaterRemote = stateWins(
            incoming.watchLaterChangedAt,
            incoming.sourceDeviceId,
            watchLaterChangedAt,
            sourceDeviceId
        )
        val independentRemote = stateWins(
            incoming.independentChangedAt,
            incoming.sourceDeviceId,
            independentChangedAt,
            sourceDeviceId
        )
        val deletionRemote = stateWins(
            incoming.deletedAt,
            incoming.sourceDeviceId,
            deletedAt,
            sourceDeviceId
        )
        return copy(
            independentSaved = if (independentRemote) incoming.independentSaved else independentSaved,
            independentChangedAt = if (independentRemote) incoming.independentChangedAt else independentChangedAt,
            independentSortOrder = if (independentRemote) incoming.independentSortOrder else independentSortOrder,
            favoriteSaved = if (favoriteRemote) incoming.favoriteSaved else favoriteSaved,
            favoriteChangedAt = if (favoriteRemote) incoming.favoriteChangedAt else favoriteChangedAt,
            favoriteSortOrder = if (favoriteRemote) incoming.favoriteSortOrder else favoriteSortOrder,
            watchLaterSaved = if (watchLaterRemote) incoming.watchLaterSaved else watchLaterSaved,
            watchLaterChangedAt = if (watchLaterRemote) incoming.watchLaterChangedAt else watchLaterChangedAt,
            watchLaterSortOrder = if (watchLaterRemote) incoming.watchLaterSortOrder else watchLaterSortOrder,
            deleted = if (deletionRemote) incoming.deleted else deleted,
            deletedAt = if (deletionRemote) incoming.deletedAt else deletedAt,
            readingProgress = if (incoming.readingPositionChangedAt > readingPositionChangedAt) {
                incoming.readingProgress
            } else {
                readingProgress
            },
            readingPositionBytes = if (incoming.readingPositionChangedAt > readingPositionChangedAt) {
                incoming.readingPositionBytes
            } else {
                readingPositionBytes
            },
            readingPositionContentHash = if (incoming.readingPositionChangedAt > readingPositionChangedAt) {
                incoming.readingPositionContentHash
            } else {
                readingPositionContentHash
            },
            readingPositionChangedAt = maxOf(
                readingPositionChangedAt,
                incoming.readingPositionChangedAt
            ),
            isRead = isRead || incoming.isRead
        )
    }

    private fun stateWins(
        incomingAt: Long,
        incomingDevice: String,
        currentAt: Long,
        currentDevice: String
    ): Boolean = incomingAt > currentAt ||
        (incomingAt == currentAt && incomingDevice > currentDevice)

    private fun JSONObject.putNullable(name: String, value: String?) {
        put(name, value ?: JSONObject.NULL)
    }
}
