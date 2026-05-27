package com.lightningstudio.watchrss.phone.data.repo

import com.lightningstudio.watchrss.phone.connection.bluetooth.ArticleSyncBody
import com.lightningstudio.watchrss.phone.connection.bluetooth.ArticleBodyRequest
import com.lightningstudio.watchrss.phone.connection.bluetooth.ArticleSyncManifestEntry
import com.lightningstudio.watchrss.phone.connection.bluetooth.ChunkedArticlePayload
import com.lightningstudio.watchrss.phone.connection.bluetooth.PhoneSyncConflictPlan
import com.lightningstudio.watchrss.phone.connection.bluetooth.PhoneSyncConflictResolution
import com.lightningstudio.watchrss.phone.connection.bluetooth.PhoneSyncDeleteConflict
import com.lightningstudio.watchrss.phone.data.db.PhoneArticleDao
import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
import com.lightningstudio.watchrss.phone.data.db.PhoneRssSourceDao
import com.lightningstudio.watchrss.phone.data.db.PhoneRssSourceEntity
import com.lightningstudio.watchrss.phone.data.db.PhoneSavedItemDao
import com.lightningstudio.watchrss.phone.data.db.PhoneSavedItemEntity
import com.lightningstudio.watchrss.phone.data.db.SyncChangeLogDao
import com.lightningstudio.watchrss.phone.data.db.SyncChangeLogEntity
import com.lightningstudio.watchrss.phone.data.db.SyncChangeLogEntityState
import com.lightningstudio.watchrss.phone.data.db.SyncPeerStateDao
import com.lightningstudio.watchrss.phone.data.db.SyncPeerStateEntity
import com.lightningstudio.watchrss.phone.data.importer.ImportedLocalContent
import com.lightningstudio.watchrss.phone.data.importer.ImportedRssSource
import com.lightningstudio.watchrss.phone.data.importer.LocalContentImportKind
import com.lightningstudio.watchrss.phone.data.importer.LocalContentImporter
import com.lightningstudio.watchrss.phone.data.importer.ImportedWebArticle
import com.lightningstudio.watchrss.phone.data.importer.RssSourceImporter
import com.lightningstudio.watchrss.phone.data.importer.WebArticleImporter
import com.lightningstudio.watchrss.phone.data.local.ArticleContentStore
import com.lightningstudio.watchrss.phone.data.model.ImportedContentIds
import com.lightningstudio.watchrss.phone.data.model.PhoneSavedItemType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.json.JSONArray
import java.lang.Long.max
import java.net.URI

data class PhoneRssSourceImportResult(
    val source: PhoneRssSourceEntity,
    val articleCount: Int
)

data class PhoneLocalContentImportResult(
    val source: PhoneRssSourceEntity,
    val articleCount: Int,
    val kind: LocalContentImportKind
)

data class PhoneLibrarySyncWindow(
    val articleManifest: List<ArticleSyncManifestEntry>,
    val fullArticleManifest: List<ArticleSyncManifestEntry>,
    val rssSources: List<PhoneRssSourceEntity>,
    val fullSnapshot: Boolean,
    val fromSeqExclusive: Long,
    val toSeqInclusive: Long,
    val peerAckedSeq: Long,
    val fallbackReason: String
)

private object NoopSyncChangeLogDao : SyncChangeLogDao {
    override suspend fun insert(change: SyncChangeLogEntity): Long = 0L
    override suspend fun maxSeq(): Long = 0L
    override suspend fun entityIdsChangedAfter(kind: String, afterSeq: Long): List<String> = emptyList()
    override suspend fun maxChangedAtByEntityIds(
        kind: String,
        entityIds: List<String>
    ): List<SyncChangeLogEntityState> = emptyList()
}

private object NoopSyncPeerStateDao : SyncPeerStateDao {
    override suspend fun get(peerDeviceId: String): SyncPeerStateEntity? = null
    override suspend fun upsert(state: SyncPeerStateEntity) = Unit
}

class PhoneCompanionRepository(
    private val savedItemDao: PhoneSavedItemDao,
    private val articleDao: PhoneArticleDao,
    private val rssSourceDao: PhoneRssSourceDao,
    private val deviceId: String,
    private val syncChangeLogDao: SyncChangeLogDao = NoopSyncChangeLogDao,
    private val syncPeerStateDao: SyncPeerStateDao = NoopSyncPeerStateDao,
    private val webArticleImporter: suspend (String) -> ImportedWebArticle = { input ->
        WebArticleImporter().importUrl(input)
    },
    private val rssSourceImporter: suspend (String) -> ImportedRssSource = { input ->
        RssSourceImporter().importUrl(input)
    },
    private val localContentImporter: suspend (String, String?, ByteArray) -> ImportedLocalContent = { fileName, mimeType, bytes ->
        LocalContentImporter().importFile(fileName, mimeType, bytes)
    },
    private val articleContentStore: ArticleContentStore? = null
) {
    fun observeSavedItems(type: PhoneSavedItemType): Flow<List<PhoneSavedItemEntity>> {
        return savedItemDao.observeByType(type.name)
    }

    fun observeSavedArticles(type: PhoneSavedItemType): Flow<List<PhoneArticleEntity>> {
        return when (type) {
            PhoneSavedItemType.FAVORITE -> articleDao.observeFavorites()
            PhoneSavedItemType.WATCH_LATER -> articleDao.observeWatchLater()
        }
    }

    fun observeRecentArticles(limit: Int = 20): Flow<List<PhoneArticleEntity>> {
        return articleDao.observeRecent(limit)
    }

    fun observeIndependentArticles(): Flow<List<PhoneArticleEntity>> {
        return articleDao.observeIndependent()
    }

    fun observeImportedContentArticles(): Flow<List<PhoneArticleEntity>> {
        return articleDao.observeImportedContentArticles(
            importedTextSourceUrl(),
            importedTextArticlePrefix()
        )
    }

    fun observeRssSources(): Flow<List<PhoneRssSourceEntity>> {
        return rssSourceDao.observeActive(
            importedTextSourceUrl()
        )
    }

    fun observeRssArticles(): Flow<List<PhoneArticleEntity>> {
        return articleDao.observeRssArticles(
            importedTextSourceUrl(),
            importedTextArticlePrefix()
        )
    }

    fun observeArticle(articleId: String): Flow<PhoneArticleEntity?> {
        return articleDao.observeById(articleId).map { article ->
            withContext(Dispatchers.IO) {
                article?.hydrateExternalText()
            }
        }
    }

    suspend fun importWebArticle(input: String): PhoneArticleEntity =
        withContext(Dispatchers.IO) {
            val imported = webArticleImporter(input)
            saveImportedArticle(imported, type = null, independent = true)
        }

    suspend fun addRssSource(input: String): PhoneRssSourceImportResult =
        withContext(Dispatchers.IO) {
            val imported = rssSourceImporter(input)
            saveImportedSource(imported)
        }

    suspend fun importLocalContent(
        fileName: String,
        mimeType: String?,
        bytes: ByteArray
    ): PhoneLocalContentImportResult =
        withContext(Dispatchers.IO) {
            val imported = localContentImporter(fileName, mimeType, bytes)
            val result = saveImportedSource(
                imported = imported.source,
                replaceExistingArticles = imported.kind == LocalContentImportKind.EPUB
            )
            PhoneLocalContentImportResult(
                source = result.source,
                articleCount = result.articleCount,
                kind = imported.kind
            )
        }

    suspend fun toggleSaved(article: PhoneArticleEntity, type: PhoneSavedItemType): PhoneArticleEntity =
        withContext(Dispatchers.IO) {
            val current = articleDao.getById(article.articleId) ?: article
            val now = System.currentTimeMillis()
            val updated = when (type) {
                PhoneSavedItemType.FAVORITE -> current.copy(
                    favoriteSaved = !current.favoriteSaved,
                    favoriteChangedAt = now,
                    favoriteSortOrder = if (!current.favoriteSaved) now else current.favoriteSortOrder,
                    updatedAt = now,
                    sourceDeviceId = deviceId,
                    deleted = false
                )
                PhoneSavedItemType.WATCH_LATER -> current.copy(
                    watchLaterSaved = !current.watchLaterSaved,
                    watchLaterChangedAt = now,
                    watchLaterSortOrder = if (!current.watchLaterSaved) now else current.watchLaterSortOrder,
                    updatedAt = now,
                    sourceDeviceId = deviceId,
                    deleted = false
                )
            }.markDeletedIfEmpty(now)
            articleDao.upsert(updated)
            recordArticleChange(updated.articleId, "sourceState", now)
            updated
        }

    suspend fun moveRssSourceToTop(sourceUrl: String) = withContext(Dispatchers.IO) {
        val source = rssSourceDao.getByUrl(sourceUrl) ?: return@withContext
        val now = System.currentTimeMillis()
        val updated = source.copy(
                sourceDeviceId = deviceId,
                updatedAt = now,
                sortOrder = now,
                deleted = false,
                deletedAt = 0L
            )
        rssSourceDao.upsert(updated)
        recordRssSourceChange(updated.url, "sourceState", now)
    }

    suspend fun setRssSourcePinned(sourceUrl: String, pinned: Boolean) = withContext(Dispatchers.IO) {
        val source = rssSourceDao.getByUrl(sourceUrl) ?: return@withContext
        val now = System.currentTimeMillis()
        val updated = source.copy(
                sourceDeviceId = deviceId,
                updatedAt = now,
                sortOrder = if (pinned) now else source.sortOrder,
                isPinned = pinned,
                deleted = false,
                deletedAt = 0L
            )
        rssSourceDao.upsert(updated)
        recordRssSourceChange(updated.url, "sourceState", now)
    }

    suspend fun deleteRssSource(sourceUrl: String) = withContext(Dispatchers.IO) {
        val source = rssSourceDao.getByUrl(sourceUrl) ?: return@withContext
        val now = System.currentTimeMillis()
        if (ImportedContentIds.isImportedContentUrl(sourceUrl)) {
            articleDao.getByRssSourceUrl(sourceUrl)
                .filterNot { it.deleted }
                .forEachIndexed { index, article ->
                    val deletedArticle = article.markDeletedByUser(now + index)
                    articleDao.upsert(deletedArticle)
                    recordArticleChange(deletedArticle.articleId, "delete", now + index)
                }
        }
        val deletedSource = source.copy(
                sourceDeviceId = deviceId,
                updatedAt = now,
                isPinned = false,
                deleted = true,
                deletedAt = now
            )
        rssSourceDao.upsert(deletedSource)
        recordRssSourceChange(deletedSource.url, "delete", now)
    }

    suspend fun deleteArticle(articleId: String) = withContext(Dispatchers.IO) {
        val current = articleDao.getById(articleId) ?: return@withContext
        val now = System.currentTimeMillis()
        val deleted = current.markDeletedByUser(now)
        articleDao.upsert(deleted)
        recordArticleChange(deleted.articleId, "delete", now)
    }

    suspend fun clearImportedContent(): Int = withContext(Dispatchers.IO) {
        val importedArticles = articleDao.getByRssSourceUrl(ImportedContentIds.ROOT_SOURCE_URL)
            .filterNot { it.deleted }
        val now = System.currentTimeMillis()
        importedArticles.forEachIndexed { index, article ->
            val deleted = article.markDeletedByUser(now + index)
            articleDao.upsert(deleted)
            recordArticleChange(deleted.articleId, "delete", now + index)
        }
        importedArticles.size
    }

    suspend fun getArticlesForSync(): List<PhoneArticleEntity> = withContext(Dispatchers.IO) {
        articleDao.getAllForSync()
            .filter { it.shouldSyncThroughLibrary() }
            .map { it.hydrateExternalText() }
    }

    suspend fun getArticleManifestsForSync(): List<ArticleSyncManifestEntry> = withContext(Dispatchers.IO) {
        articleDao.getAllForSync()
            .filter { it.shouldSyncThroughLibrary() }
            .map { article ->
                if (article.needsSyncMetadataRefresh()) {
                    article.ensureSyncMetadata()
                } else {
                    article
                }
            }
            .map { it.toSyncManifestEntry() }
    }

    private suspend fun getArticleManifestsForSync(articleIds: Collection<String>): List<ArticleSyncManifestEntry> {
        val idSet = articleIds.toSet()
        if (idSet.isEmpty()) return emptyList()
        return articleDao.getAllForSync()
            .filter { it.articleId in idSet && it.shouldSyncThroughLibrary() }
            .map { article ->
                if (article.needsSyncMetadataRefresh()) {
                    article.ensureSyncMetadata()
                } else {
                    article
                }
            }
            .map { it.toSyncManifestEntry() }
    }

    suspend fun getArticlesForSync(articleIds: Collection<String>): List<PhoneArticleEntity> =
        withContext(Dispatchers.IO) {
            val idSet = articleIds.toSet()
            if (idSet.isEmpty()) return@withContext emptyList()
            articleDao.getAllForSync()
                .filter { it.articleId in idSet }
                .map { it.hydrateExternalText().withCurrentSyncMetadata() }
        }

    suspend fun getRssSourcesForSync(): List<PhoneRssSourceEntity> = withContext(Dispatchers.IO) {
        rssSourceDao.getAllForSync()
            .filterNot { ImportedContentIds.isImportedTextSourceUrl(it.url) }
    }

    private suspend fun getRssSourcesForSync(sourceUrls: Collection<String>): List<PhoneRssSourceEntity> {
        val urlSet = sourceUrls.toSet()
        if (urlSet.isEmpty()) return emptyList()
        return rssSourceDao.getAllForSync()
            .filter { it.url in urlSet }
            .filterNot { ImportedContentIds.isImportedTextSourceUrl(it.url) }
    }

    suspend fun prepareLibrarySyncWindow(peerDeviceId: String): PhoneLibrarySyncWindow =
        withContext(Dispatchers.IO) {
            val normalizedPeerId = peerDeviceId.ifBlank { DEFAULT_LIBRARY_PEER_ID }
            val peerState = syncPeerStateDao.get(normalizedPeerId)
            val now = System.currentTimeMillis()
            val fullArticleManifest = getArticleManifestsForSync()
            repairMissingArticleChangeLogEntries(fullArticleManifest)
            val maxSeq = syncChangeLogDao.maxSeq()
            val peerAckedSeq = peerState?.lastLocalSeqAckedByPeer ?: 0L
            val fullSnapshotReason = when {
                peerState == null -> "newPeer"
                peerState.lastProtocolVersion < CHANGE_SEQUENCE_PROTOCOL_VERSION -> "peerProtocol"
                peerState.lastFullSyncAt <= 0L -> "noFullSnapshot"
                now - peerState.lastFullSyncAt >= FULL_SNAPSHOT_INTERVAL_MS -> "periodicFull"
                else -> ""
            }
            if (fullSnapshotReason.isNotBlank()) {
                return@withContext PhoneLibrarySyncWindow(
                    articleManifest = fullArticleManifest,
                    fullArticleManifest = fullArticleManifest,
                    rssSources = getRssSourcesForSync(),
                    fullSnapshot = true,
                    fromSeqExclusive = 0L,
                    toSeqInclusive = maxSeq,
                    peerAckedSeq = peerAckedSeq,
                    fallbackReason = fullSnapshotReason
                )
            }

            val changedArticleIds = syncChangeLogDao.entityIdsChangedAfter(
                kind = SYNC_KIND_ARTICLE,
                afterSeq = peerAckedSeq
            )
            val changedSourceUrls = syncChangeLogDao.entityIdsChangedAfter(
                kind = SYNC_KIND_RSS_SOURCE,
                afterSeq = peerAckedSeq
            )
            PhoneLibrarySyncWindow(
                articleManifest = getArticleManifestsForSync(changedArticleIds),
                fullArticleManifest = fullArticleManifest,
                rssSources = getRssSourcesForSync(changedSourceUrls),
                fullSnapshot = false,
                fromSeqExclusive = peerAckedSeq,
                toSeqInclusive = maxSeq,
                peerAckedSeq = peerAckedSeq,
                fallbackReason = ""
            )
        }

    suspend fun markLibrarySyncSuccess(
        peerDeviceId: String,
        localSeqToInclusive: Long,
        remoteSeqToInclusive: Long,
        remoteProtocolVersion: Int,
        fullSnapshot: Boolean
    ) = withContext(Dispatchers.IO) {
        val normalizedPeerId = peerDeviceId.ifBlank { DEFAULT_LIBRARY_PEER_ID }
        val now = System.currentTimeMillis()
        val current = syncPeerStateDao.get(normalizedPeerId)
        syncPeerStateDao.upsert(
            SyncPeerStateEntity(
                peerDeviceId = normalizedPeerId,
                lastLocalSeqAckedByPeer = maxOf(
                    current?.lastLocalSeqAckedByPeer ?: 0L,
                    localSeqToInclusive
                ),
                lastRemoteSeqApplied = maxOf(
                    current?.lastRemoteSeqApplied ?: 0L,
                    remoteSeqToInclusive
                ),
                lastFullSyncAt = if (fullSnapshot) now else current?.lastFullSyncAt ?: 0L,
                lastProtocolVersion = remoteProtocolVersion,
                updatedAt = now
            )
        )
    }

    private suspend fun repairMissingArticleChangeLogEntries(
        articleManifest: List<ArticleSyncManifestEntry>
    ) {
        val candidates = articleManifest
            .asSequence()
            .filterNot { it.deleted }
            .filter { it.latestOperationAt() > 0L }
            .distinctBy { it.articleId }
            .toList()
        if (candidates.isEmpty()) return

        val loggedChangedAt = syncChangeLogDao.maxChangedAtByEntityIds(
            kind = SYNC_KIND_ARTICLE,
            entityIds = candidates.map { it.articleId }
        ).associate { it.entityId to it.changedAt }
        val now = System.currentTimeMillis()
        candidates.forEach { article ->
            val changedAt = article.latestOperationAt()
            if (changedAt <= (loggedChangedAt[article.articleId] ?: 0L)) return@forEach
            syncChangeLogDao.insert(
                SyncChangeLogEntity(
                    kind = SYNC_KIND_ARTICLE,
                    entityId = article.articleId,
                    changedAt = changedAt,
                    originDeviceId = article.sourceDeviceId.ifBlank { deviceId },
                    reason = "repairState",
                    createdAt = now
                )
            )
        }
    }

    suspend fun repairImportedContentTitles(): Int = withContext(Dispatchers.IO) {
        val sources = rssSourceDao.getAllForSync()
            .filter { it.url.isImportedEpubSourceUrl() }
        var repaired = 0
        sources.forEach { source ->
            val articles = articleDao.getByRssSourceUrl(source.url)
                .map { it.hydrateExternalText() }
                .sortedByDescending { it.importedAt }
            val updates = inferImportedEpubTitleUpdates(articles)
            updates.forEach { (articleId, title) ->
                val updatedAt = System.currentTimeMillis() + repaired
                articleDao.updateTitle(
                    articleId = articleId,
                    title = title,
                    updatedAt = updatedAt
                )
                recordArticleChange(articleId, "metadata", updatedAt)
                repaired += 1
            }
        }
        repaired
    }

    suspend fun repairImportedContentSourceStates(): Int = withContext(Dispatchers.IO) {
        val sources = rssSourceDao.getAllForSync()
            .filter { ImportedContentIds.isImportedContentUrl(it.url) }
        var repaired = 0
        sources.forEach { source ->
            val liveArticles = articleDao.getByRssSourceUrl(source.url)
                .filterNot { it.deleted }
            if (liveArticles.isEmpty() || !source.deleted) return@forEach
            val latestArticleUpdate = liveArticles.maxOf { article ->
                maxOf(article.updatedAt, article.importedAt)
            }
            val repairedSource = source.copy(
                    sourceDeviceId = deviceId,
                    updatedAt = maxOf(source.updatedAt, latestArticleUpdate),
                    deleted = false,
                    deletedAt = 0L
                )
            rssSourceDao.upsert(repairedSource)
            recordRssSourceChange(repairedSource.url, "sourceState", repairedSource.updatedAt)
            repaired += 1
        }
        repaired
    }

    suspend fun findDeleteConflicts(remoteManifest: List<ArticleSyncManifestEntry>): List<PhoneSyncDeleteConflict> =
        withContext(Dispatchers.IO) {
            remoteManifest.mapNotNull { remote ->
                val local = articleDao.getById(remote.articleId) ?: return@mapNotNull null
                buildDeleteConflict(local, remote)
            }
        }

    suspend fun prepareDeleteConflictResolutions(
        remoteManifest: List<ArticleSyncManifestEntry>,
        resolutions: Map<String, PhoneSyncConflictResolution>
    ): PhoneSyncConflictPlan = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val outgoingArticleIds = linkedSetOf<String>()
        val forcedRemoteRequests = mutableListOf<ArticleBodyRequest>()
        val suppressedRemoteArticleIds = linkedSetOf<String>()
        val mergeResolutions = linkedMapOf<String, PhoneSyncConflictResolution>()
        remoteManifest.forEachIndexed { index, remote ->
            val local = articleDao.getById(remote.articleId)?.hydrateExternalText() ?: return@forEachIndexed
            if (buildDeleteConflict(local, remote) == null) return@forEachIndexed
            val resolution = resolutions[remote.articleId] ?: PhoneSyncConflictResolution.KEEP_LATEST
            when (deleteConflictAction(local, remote, resolution)) {
                ConflictAction.PHONE -> {
                    val updated = local.stampKeptByConflict(now + index)
                    articleDao.upsert(updated.withCurrentSyncMetadata().externalizeLargeLocalContent())
                    recordArticleChange(updated.articleId, "conflictResolution", now + index)
                    outgoingArticleIds += updated.articleId
                    suppressedRemoteArticleIds += updated.articleId
                }
                ConflictAction.WATCH -> {
                    suppressedRemoteArticleIds += remote.articleId
                    if (remote.deleted) {
                        val updated = local.applyRemoteDelete(remote)
                        if (updated != local) {
                            articleDao.upsert(updated.withCurrentSyncMetadata().externalizeLargeLocalContent())
                        }
                    } else {
                        forcedRemoteRequests += remote.toFullBodyRequest()
                        mergeResolutions[remote.articleId] = if (resolution == PhoneSyncConflictResolution.MERGE_CONTENT) {
                            PhoneSyncConflictResolution.MERGE_CONTENT
                        } else {
                            PhoneSyncConflictResolution.KEEP_WATCH
                        }
                    }
                }
                ConflictAction.DELETE -> {
                    val updated = local.markDeletedByUser(now + index)
                    articleDao.upsert(updated.withCurrentSyncMetadata().externalizeLargeLocalContent())
                    recordArticleChange(updated.articleId, "conflictResolution", now + index)
                    outgoingArticleIds += updated.articleId
                    suppressedRemoteArticleIds += updated.articleId
                }
            }
        }
        PhoneSyncConflictPlan(
            outgoingArticleIds = outgoingArticleIds,
            forcedRemoteRequests = forcedRemoteRequests,
            suppressedRemoteArticleIds = suppressedRemoteArticleIds,
            mergeResolutions = mergeResolutions
        )
    }

    suspend fun mergeArticlesFromSync(
        incoming: List<PhoneArticleEntity>,
        conflictResolutions: Map<String, PhoneSyncConflictResolution> = emptyMap()
    ): Int = withContext(Dispatchers.IO) {
        var merged = 0
        incoming.forEach { remote ->
            val local = articleDao.getById(remote.articleId)
            val preparedRemote = remote.withCurrentSyncMetadata()
            val next = if (local == null) {
                preparedRemote
            } else {
                val localHydrated = local.hydrateExternalText()
                mergeArticle(
                    local = localHydrated,
                    remote = preparedRemote,
                    conflictResolution = conflictResolutions[remote.articleId]
                )
            }
            if (local != next) {
                articleDao.upsert(next.externalizeLargeLocalContent())
                merged += 1
            }
        }
        merged
    }

    suspend fun mergeChunkedArticlesFromSync(
        incoming: List<ChunkedArticlePayload>,
        conflictResolutions: Map<String, PhoneSyncConflictResolution> = emptyMap()
    ): Int =
        withContext(Dispatchers.IO) {
            var merged = 0
            incoming.forEach { payload ->
                val local = articleDao.getById(payload.article.articleId)
                val localHydrated = local?.hydrateExternalText()
                val (contentHtml, contentText) = if (payload.article.deleted) {
                    localHydrated?.contentHtml to localHydrated?.contentText.orEmpty()
                } else {
                    ArticleSyncBody.rebuildBody(localHydrated, payload)
                }
                val preparedRemote = payload.article.copy(
                    contentHtml = contentHtml,
                    contentText = contentText,
                    syncBodyHash = payload.bodyHash,
                    syncBodyByteCount = payload.bodyByteCount,
                    syncChunkSize = payload.chunkSize,
                    syncChunkHashesJson = payload.chunkHashes.toJsonString(),
                    syncMetadataHash = ArticleSyncBody.metadataHashFor(payload.article)
                )
                val next = if (localHydrated == null) {
                    preparedRemote
                } else {
                    mergeArticle(
                        local = localHydrated,
                        remote = preparedRemote,
                        conflictResolution = conflictResolutions[payload.article.articleId]
                    )
                }.copy(
                    syncBodyHash = payload.bodyHash,
                    syncBodyByteCount = payload.bodyByteCount,
                    syncChunkSize = payload.chunkSize,
                    syncChunkHashesJson = payload.chunkHashes.toJsonString(),
                    syncMetadataHash = ArticleSyncBody.metadataHashFor(payload.article)
                )
                if (local != next) {
                    articleDao.upsert(next.externalizeLargeLocalContent())
                    merged += 1
                }
            }
            merged
        }

    suspend fun mergeRssSourcesFromSync(incoming: List<PhoneRssSourceEntity>): Int =
        withContext(Dispatchers.IO) {
            var merged = 0
            incoming.forEach { remote ->
                if (ImportedContentIds.isImportedTextSourceUrl(remote.url)) return@forEach
                val local = rssSourceDao.getByUrl(remote.url)
                val next = if (local == null || remote.isNewerThan(local)) {
                    remote
                } else {
                    local
                }
                if (local != next) {
                    if (next.deleted && ImportedContentIds.isImportedContentUrl(next.url)) {
                        articleDao.getByRssSourceUrl(next.url)
                            .filterNot { it.deleted }
                            .forEachIndexed { index, article ->
                                articleDao.upsert(article.applyRemoteSourceDelete(next, index))
                            }
                    }
                    rssSourceDao.upsert(next)
                    merged += 1
                }
            }
            merged
        }

    suspend fun replaceSavedItems(type: PhoneSavedItemType, data: JSONArray): Int {
        val syncedAt = System.currentTimeMillis()
        val entities = buildList {
            for (index in 0 until data.length()) {
                val item = data.optJSONObject(index) ?: continue
                val link = item.optString("link").trim()
                if (link.isBlank()) continue
                val remoteId = item.optLong("id")
                val title = item.optString("title").trim()
                val summary = item.optString("summary").trim()
                val channelTitle = item.optString("channelTitle").trim()
                val stableKey = when {
                    remoteId > 0L -> remoteId.toString()
                    link.isNotBlank() -> link
                    else -> "${type.name}-$index"
                }
                add(
                    PhoneSavedItemEntity(
                        type = type.name,
                        stableKey = stableKey,
                        remoteId = remoteId,
                        title = title.ifBlank { link },
                        link = link,
                        summary = summary,
                        channelTitle = channelTitle.ifBlank { hostLabel(link) },
                        pubDate = item.optString("pubDate"),
                        syncedAt = syncedAt
                    )
                )
            }
        }
        savedItemDao.deleteByType(type.name)
        savedItemDao.upsertAll(entities)
        entities.forEach { entity ->
            val imported = ImportedWebArticle(
                articleId = WebArticleImporter.stableArticleId(entity.link),
                url = entity.link,
                title = entity.title,
                siteName = entity.channelTitle,
                excerpt = entity.summary,
                contentHtml = null,
                contentText = entity.summary,
                imageUrl = null,
                contentHash = WebArticleImporter.sha256(entity.summary.ifBlank { entity.link })
            )
            saveImportedArticle(imported, type = type, independent = false, timestamp = syncedAt)
        }
        return entities.size
    }

    private suspend fun saveImportedArticle(
        imported: ImportedWebArticle,
        type: PhoneSavedItemType?,
        independent: Boolean,
        timestamp: Long = System.currentTimeMillis()
    ): PhoneArticleEntity {
        val current = articleDao.getById(imported.articleId)
        val base = current ?: PhoneArticleEntity(
            articleId = imported.articleId,
            sourceDeviceId = deviceId,
            url = imported.url,
            title = imported.title,
            siteName = imported.siteName,
            excerpt = imported.excerpt,
            contentHtml = imported.contentHtml,
            contentText = imported.contentText,
            imageUrl = imported.imageUrl,
            contentHash = imported.contentHash,
            importedAt = timestamp,
            updatedAt = timestamp,
            independentSaved = false,
            independentChangedAt = 0L,
            independentSortOrder = 0L,
            rssSourceUrl = null,
            rssSourceTitle = null,
            favoriteSaved = false,
            favoriteChangedAt = 0L,
            favoriteSortOrder = 0L,
            watchLaterSaved = false,
            watchLaterChangedAt = 0L,
            watchLaterSortOrder = 0L,
            deleted = false,
            deletedAt = 0L
        )
        val withContent = base.copy(
            sourceDeviceId = deviceId,
            url = imported.url,
            title = imported.title,
            siteName = imported.siteName,
            excerpt = imported.excerpt,
            contentHtml = imported.contentHtml,
            contentText = imported.contentText,
            imageUrl = imported.imageUrl,
            contentHash = imported.contentHash,
            updatedAt = timestamp,
            deleted = false
        )
        val withIndependent = if (independent) {
            withContent.copy(
                independentSaved = true,
                independentChangedAt = timestamp,
                independentSortOrder = timestamp
            )
        } else {
            withContent
        }
        val saved = when (type) {
            PhoneSavedItemType.FAVORITE -> withContent.copy(
                favoriteSaved = true,
                favoriteChangedAt = timestamp,
                favoriteSortOrder = timestamp
            )
            PhoneSavedItemType.WATCH_LATER -> withContent.copy(
                watchLaterSaved = true,
                watchLaterChangedAt = timestamp,
                watchLaterSortOrder = timestamp
            )
            null -> withIndependent
        }
        val stored = saved.withCurrentSyncMetadata().externalizeLargeLocalContent()
        articleDao.upsert(stored)
        recordArticleChange(stored.articleId, "upsert", timestamp)
        return stored
    }

    private suspend fun saveImportedSource(
        imported: ImportedRssSource,
        replaceExistingArticles: Boolean = false
    ): PhoneRssSourceImportResult {
        val now = System.currentTimeMillis()
        val existing = rssSourceDao.getByUrl(imported.url)
        val existingArticles = if (replaceExistingArticles) {
            articleDao.getByRssSourceUrl(imported.url)
        } else {
            emptyList()
        }
        val existingByContentHash = existingArticles
            .filter { it.contentHash.isNotBlank() }
            .associateBy { it.contentHash }
        val existingByUrl = existingArticles.associateBy { it.url }
        val source = PhoneRssSourceEntity(
            url = imported.url,
            sourceDeviceId = deviceId,
            title = imported.title.ifBlank { hostLabel(imported.url) },
            description = imported.description,
            siteUrl = imported.siteUrl,
            imageUrl = imported.imageUrl,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            sortOrder = existing?.sortOrder?.takeIf { it > 0L } ?: now,
            isPinned = existing?.isPinned ?: false,
            deleted = false,
            deletedAt = 0L
        )
        rssSourceDao.upsert(source)
        recordRssSourceChange(source.url, "upsert", now)
        val articles = imported.items.mapIndexed { index, item ->
            val timestamp = now - index
            PhoneArticleEntity(
                articleId = WebArticleImporter.stableArticleId(item.url),
                sourceDeviceId = deviceId,
                url = item.url,
                title = item.title.ifBlank { item.url },
                siteName = source.title,
                excerpt = item.excerpt,
                contentHtml = item.contentHtml,
                contentText = item.contentText,
                imageUrl = item.imageUrl,
                contentHash = WebArticleImporter.sha256(item.contentHtml ?: item.contentText.ifBlank { item.url }),
                importedAt = timestamp,
                updatedAt = timestamp,
                independentSaved = false,
                independentChangedAt = 0L,
                independentSortOrder = 0L,
                rssSourceUrl = source.url,
                rssSourceTitle = source.title,
                favoriteSaved = false,
                favoriteChangedAt = 0L,
                favoriteSortOrder = 0L,
                watchLaterSaved = false,
                watchLaterChangedAt = 0L,
                watchLaterSortOrder = 0L,
                deleted = false,
                deletedAt = 0L
            )
                .withSavedStateFrom(existingByUrl[item.url] ?: existingByContentHash[WebArticleImporter.sha256(item.contentHtml ?: item.contentText.ifBlank { item.url })])
                .withCurrentSyncMetadata()
                .externalizeLargeLocalContent()
        }
        if (replaceExistingArticles) {
            articleDao.deleteByRssSourceUrl(imported.url)
        }
        if (articles.isNotEmpty()) {
            articleDao.upsertAll(articles)
            articles.forEach { article ->
                recordArticleChange(article.articleId, "upsert", article.updatedAt)
            }
        }
        return PhoneRssSourceImportResult(source, articles.size)
    }

    private fun PhoneArticleEntity.withSavedStateFrom(existing: PhoneArticleEntity?): PhoneArticleEntity {
        if (existing == null) return this
        return copy(
            favoriteSaved = existing.favoriteSaved,
            favoriteChangedAt = existing.favoriteChangedAt,
            favoriteSortOrder = existing.favoriteSortOrder,
            watchLaterSaved = existing.watchLaterSaved,
            watchLaterChangedAt = existing.watchLaterChangedAt,
            watchLaterSortOrder = existing.watchLaterSortOrder
        )
    }

    private suspend fun recordArticleChange(
        articleId: String,
        reason: String,
        changedAt: Long = System.currentTimeMillis()
    ) {
        if (articleId.isBlank()) return
        syncChangeLogDao.insert(
            SyncChangeLogEntity(
                kind = SYNC_KIND_ARTICLE,
                entityId = articleId,
                changedAt = changedAt,
                originDeviceId = deviceId,
                reason = reason,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    private suspend fun recordRssSourceChange(
        sourceUrl: String,
        reason: String,
        changedAt: Long = System.currentTimeMillis()
    ) {
        if (sourceUrl.isBlank() || ImportedContentIds.isImportedTextSourceUrl(sourceUrl)) return
        syncChangeLogDao.insert(
            SyncChangeLogEntity(
                kind = SYNC_KIND_RSS_SOURCE,
                entityId = sourceUrl,
                changedAt = changedAt,
                originDeviceId = deviceId,
                reason = reason,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    private fun inferImportedEpubTitleUpdates(
        articles: List<PhoneArticleEntity>
    ): Map<String, String> {
        if (articles.isEmpty()) return emptyMap()
        val updates = linkedMapOf<String, String>()
        val tocArticle = articles.firstOrNull { article ->
            article.contentHtml.orEmpty().contains("<a", ignoreCase = true) &&
                (article.title.isHtmlTocTitle() || extractTocLinkTitles(article.contentHtml).size >= MIN_HTML_TOC_LINKS)
        }
        if (tocArticle != null && tocArticle.title.isHtmlTocTitle()) {
            updates[tocArticle.articleId] = "目录"
        }
        val tocIndex = tocArticle?.let { articles.indexOf(it) } ?: -1
        val tocTitles = tocArticle?.contentHtml?.let(::extractTocLinkTitles).orEmpty()
        if (tocIndex >= 0 && tocTitles.isNotEmpty()) {
            val candidates = articles.drop(tocIndex + 1)
                .filter { it.title.isGenericImportedTitle() }
            candidates.zip(tocTitles).forEach { (article, title) ->
                updates[article.articleId] = title
            }
        }
        articles.forEach { article ->
            if (article.articleId in updates || !article.title.isGenericImportedTitle()) return@forEach
            val fallback = firstHtmlHeading(article.contentHtml).takeIf { it.isMeaningfulImportedTitle() }
                ?: firstTitleFromText(article.contentText).takeIf { it.isMeaningfulImportedTitle() }
                ?: firstTitleFromText(Jsoup.parse(article.contentHtml.orEmpty()).text()).takeIf { it.isMeaningfulImportedTitle() }
            if (fallback != null) {
                updates[article.articleId] = fallback
            }
        }
        return updates
    }

    private fun extractTocLinkTitles(contentHtml: String?): List<String> {
        if (contentHtml.isNullOrBlank()) return emptyList()
        return Jsoup.parseBodyFragment(contentHtml)
            .select("a[href]")
            .mapNotNull { anchor ->
                cleanupImportedTitle(anchor.text()).takeIf { it.isMeaningfulImportedTitle() }
            }
            .distinct()
    }

    private fun firstHtmlHeading(contentHtml: String?): String {
        if (contentHtml.isNullOrBlank()) return ""
        return cleanupImportedTitle(
            Jsoup.parseBodyFragment(contentHtml)
                .selectFirst("h1,h2,h3,.sgc-toc-title")
                ?.text()
        )
    }

    private fun firstTitleFromText(text: String?): String {
        val line = cleanupImportedTitle(
            text.orEmpty()
            .lineSequence()
            .firstOrNull { it.isNotBlank() }
        )
        if (line.length <= MAX_REPAIRED_TITLE_CHARS) return line
        return line.substringBefore(' ')
            .take(MAX_REPAIRED_TITLE_CHARS)
            .trim()
    }

    private fun cleanupImportedTitle(value: String?): String {
        return value.orEmpty()
            .replace('\u00A0', ' ')
            .replace(Regex("""^\s*[§•·・\-–—>»]+"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun String?.isHtmlTocTitle(): Boolean {
        val normalized = cleanupImportedTitle(this).lowercase().replace(Regex("""\s+"""), "")
        return normalized == "contents" ||
            normalized == "toc" ||
            normalized == "tableofcontents" ||
            normalized == "目录"
    }

    private fun String?.isGenericImportedTitle(): Boolean {
        val normalized = cleanupImportedTitle(this).lowercase().replace(Regex("""\s+"""), "")
        return normalized in GENERIC_IMPORTED_TITLES
    }

    private fun String?.isMeaningfulImportedTitle(): Boolean {
        val value = cleanupImportedTitle(this)
        return value.isNotBlank() &&
            value.length <= MAX_REPAIRED_TITLE_CHARS &&
            !value.isGenericImportedTitle()
    }

    private fun String.isImportedEpubSourceUrl(): Boolean {
        return ImportedContentIds.isImportedEpubSourceUrl(this)
    }

    private enum class ConflictAction {
        PHONE,
        WATCH,
        DELETE
    }

    private fun mergeArticle(
        local: PhoneArticleEntity,
        remote: PhoneArticleEntity,
        conflictResolution: PhoneSyncConflictResolution? = null
    ): PhoneArticleEntity {
        return when (conflictResolution) {
            null,
            PhoneSyncConflictResolution.KEEP_LATEST -> mergeArticleByLatest(local, remote)
            PhoneSyncConflictResolution.KEEP_PHONE -> local.stampKeptByConflict(
                timestamp = maxOf(local.latestOperationAt(), remote.latestOperationAt()) + 1L
            )
            PhoneSyncConflictResolution.KEEP_WATCH -> remote
            PhoneSyncConflictResolution.DELETE_CONTENT -> mergeArticleByLatest(local, remote).markDeletedByUser(
                timestamp = maxOf(local.latestOperationAt(), remote.latestOperationAt()) + 1L
            )
            PhoneSyncConflictResolution.MERGE_CONTENT -> mergeArticleContent(local, remote)
        }
    }

    private fun mergeArticleByLatest(local: PhoneArticleEntity, remote: PhoneArticleEntity): PhoneArticleEntity {
        val metadata = if (remote.updatedAt > local.updatedAt) remote else local
        val favoriteFromRemote = remote.isStateNewerThan(local, PhoneSavedItemType.FAVORITE)
        val watchLaterFromRemote = remote.isStateNewerThan(local, PhoneSavedItemType.WATCH_LATER)
        val independentFromRemote = remote.isIndependentStateNewerThan(local)
        val favoriteSaved = if (favoriteFromRemote) remote.favoriteSaved else local.favoriteSaved
        val favoriteChangedAt = if (favoriteFromRemote) remote.favoriteChangedAt else local.favoriteChangedAt
        val favoriteSortOrder = if (favoriteFromRemote) remote.favoriteSortOrder else local.favoriteSortOrder
        val watchLaterSaved = if (watchLaterFromRemote) remote.watchLaterSaved else local.watchLaterSaved
        val watchLaterChangedAt = if (watchLaterFromRemote) remote.watchLaterChangedAt else local.watchLaterChangedAt
        val watchLaterSortOrder = if (watchLaterFromRemote) remote.watchLaterSortOrder else local.watchLaterSortOrder
        val independentSaved = if (independentFromRemote) remote.independentSaved else local.independentSaved
        val independentChangedAt = if (independentFromRemote) remote.independentChangedAt else local.independentChangedAt
        val independentSortOrder = if (independentFromRemote) remote.independentSortOrder else local.independentSortOrder
        val rssSourceUrl = remote.rssSourceUrl?.takeIf { it.isNotBlank() }
            ?: local.rssSourceUrl?.takeIf { it.isNotBlank() }
        val rssSourceTitle = remote.rssSourceTitle?.takeIf { it.isNotBlank() }
            ?: local.rssSourceTitle?.takeIf { it.isNotBlank() }
        val remoteDeletedNewer = remote.deletedAt > local.deletedAt ||
            (remote.deletedAt == local.deletedAt && remote.deleted && remote.sourceDeviceId > local.sourceDeviceId)
        val isImportedContentArticle = ImportedContentIds.isImportedContentUrl(rssSourceUrl) ||
            ImportedContentIds.isImportedContentUrl(remote.url) ||
            ImportedContentIds.isImportedContentUrl(local.url)
        val deleted = when {
            favoriteSaved || watchLaterSaved || independentSaved -> false
            !rssSourceUrl.isNullOrBlank() && !isImportedContentArticle -> false
            remoteDeletedNewer -> remote.deleted
            else -> local.deleted
        }
        val deletedAt = max(local.deletedAt, remote.deletedAt)
        return metadata.copy(
            independentSaved = independentSaved,
            independentChangedAt = independentChangedAt,
            independentSortOrder = independentSortOrder,
            rssSourceUrl = rssSourceUrl,
            rssSourceTitle = rssSourceTitle,
            favoriteSaved = favoriteSaved,
            favoriteChangedAt = favoriteChangedAt,
            favoriteSortOrder = favoriteSortOrder,
            watchLaterSaved = watchLaterSaved,
            watchLaterChangedAt = watchLaterChangedAt,
            watchLaterSortOrder = watchLaterSortOrder,
            deleted = deleted,
            deletedAt = deletedAt
        )
    }

    private fun mergeArticleContent(local: PhoneArticleEntity, remote: PhoneArticleEntity): PhoneArticleEntity {
        val base = when {
            !remote.deleted -> remote
            !local.deleted -> local
            else -> mergeArticleByLatest(local, remote)
        }
        val favoriteSaved = local.favoriteSaved || remote.favoriteSaved
        val watchLaterSaved = local.watchLaterSaved || remote.watchLaterSaved
        val independentSaved = local.independentSaved || remote.independentSaved
        return base.copy(
            independentSaved = independentSaved,
            independentChangedAt = maxOf(local.independentChangedAt, remote.independentChangedAt),
            independentSortOrder = maxOf(local.independentSortOrder, remote.independentSortOrder),
            rssSourceUrl = base.rssSourceUrl?.takeIf { it.isNotBlank() }
                ?: local.rssSourceUrl?.takeIf { it.isNotBlank() }
                ?: remote.rssSourceUrl?.takeIf { it.isNotBlank() },
            rssSourceTitle = base.rssSourceTitle?.takeIf { it.isNotBlank() }
                ?: local.rssSourceTitle?.takeIf { it.isNotBlank() }
                ?: remote.rssSourceTitle?.takeIf { it.isNotBlank() },
            favoriteSaved = favoriteSaved,
            favoriteChangedAt = maxOf(local.favoriteChangedAt, remote.favoriteChangedAt),
            favoriteSortOrder = maxOf(local.favoriteSortOrder, remote.favoriteSortOrder),
            watchLaterSaved = watchLaterSaved,
            watchLaterChangedAt = maxOf(local.watchLaterChangedAt, remote.watchLaterChangedAt),
            watchLaterSortOrder = maxOf(local.watchLaterSortOrder, remote.watchLaterSortOrder),
            deleted = false,
            deletedAt = 0L
        )
    }

    private fun buildDeleteConflict(
        local: PhoneArticleEntity,
        remote: ArticleSyncManifestEntry
    ): PhoneSyncDeleteConflict? {
        if (local.deleted == remote.deleted) return null
        if (local.deletedAt <= 0L && remote.deletedAt <= 0L) return null
        return PhoneSyncDeleteConflict(
            articleId = local.articleId,
            title = local.title.ifBlank { local.url },
            url = local.url,
            phoneDeleted = local.deleted,
            watchDeleted = remote.deleted
        )
    }

    private fun deleteConflictAction(
        local: PhoneArticleEntity,
        remote: ArticleSyncManifestEntry,
        resolution: PhoneSyncConflictResolution
    ): ConflictAction {
        return when (resolution) {
            PhoneSyncConflictResolution.DELETE_CONTENT -> ConflictAction.DELETE
            PhoneSyncConflictResolution.MERGE_CONTENT -> if (local.deleted && !remote.deleted) {
                ConflictAction.WATCH
            } else {
                ConflictAction.PHONE
            }
            PhoneSyncConflictResolution.KEEP_PHONE -> if (local.deleted) {
                ConflictAction.DELETE
            } else {
                ConflictAction.PHONE
            }
            PhoneSyncConflictResolution.KEEP_WATCH -> ConflictAction.WATCH
            PhoneSyncConflictResolution.KEEP_LATEST -> {
                val remoteNewer = remote.isNewerThan(local)
                when {
                    remoteNewer -> ConflictAction.WATCH
                    local.deleted -> ConflictAction.DELETE
                    else -> ConflictAction.PHONE
                }
            }
        }
    }

    private fun PhoneArticleEntity.stampKeptByConflict(timestamp: Long): PhoneArticleEntity {
        return copy(
            sourceDeviceId = deviceId,
            updatedAt = timestamp,
            independentChangedAt = if (independentSaved || independentChangedAt > 0L) {
                timestamp
            } else {
                independentChangedAt
            },
            independentSortOrder = if (independentSaved) timestamp else independentSortOrder,
            favoriteChangedAt = if (favoriteSaved || favoriteChangedAt > 0L) {
                timestamp
            } else {
                favoriteChangedAt
            },
            favoriteSortOrder = if (favoriteSaved) timestamp else favoriteSortOrder,
            watchLaterChangedAt = if (watchLaterSaved || watchLaterChangedAt > 0L) {
                timestamp
            } else {
                watchLaterChangedAt
            },
            watchLaterSortOrder = if (watchLaterSaved) timestamp else watchLaterSortOrder,
            deleted = false,
            deletedAt = 0L
        )
    }

    private fun PhoneArticleEntity.applyRemoteDelete(remote: ArticleSyncManifestEntry): PhoneArticleEntity {
        val timestamp = remote.deletedAt.takeIf { it > 0L } ?: remote.latestOperationAt()
        return copy(
            sourceDeviceId = remote.sourceDeviceId.ifBlank { sourceDeviceId },
            updatedAt = maxOf(remote.updatedAt, timestamp),
            independentSaved = false,
            independentChangedAt = remote.independentChangedAt,
            independentSortOrder = 0L,
            favoriteSaved = false,
            favoriteChangedAt = remote.favoriteChangedAt,
            favoriteSortOrder = 0L,
            watchLaterSaved = false,
            watchLaterChangedAt = remote.watchLaterChangedAt,
            watchLaterSortOrder = 0L,
            deleted = true,
            deletedAt = timestamp
        )
    }

    private fun ArticleSyncManifestEntry.toFullBodyRequest(): ArticleBodyRequest {
        return ArticleBodyRequest(
            articleId = articleId,
            bodyHash = bodyHash,
            chunkIndexes = chunkHashes.indices.toList()
        )
    }

    private fun ArticleSyncManifestEntry.isNewerThan(local: PhoneArticleEntity): Boolean {
        val remoteChangedAt = latestOperationAt()
        val localChangedAt = local.latestOperationAt()
        return remoteChangedAt > localChangedAt ||
            (remoteChangedAt == localChangedAt && sourceDeviceId > local.sourceDeviceId)
    }

    private fun PhoneArticleEntity.latestOperationAt(): Long {
        return maxOf(updatedAt, independentChangedAt, favoriteChangedAt, watchLaterChangedAt, deletedAt)
    }

    private fun ArticleSyncManifestEntry.latestOperationAt(): Long {
        return maxOf(updatedAt, independentChangedAt, favoriteChangedAt, watchLaterChangedAt, deletedAt)
    }

    private fun PhoneArticleEntity.isStateNewerThan(
        other: PhoneArticleEntity,
        type: PhoneSavedItemType
    ): Boolean {
        val ownChangedAt = when (type) {
            PhoneSavedItemType.FAVORITE -> favoriteChangedAt
            PhoneSavedItemType.WATCH_LATER -> watchLaterChangedAt
        }
        val otherChangedAt = when (type) {
            PhoneSavedItemType.FAVORITE -> other.favoriteChangedAt
            PhoneSavedItemType.WATCH_LATER -> other.watchLaterChangedAt
        }
        return ownChangedAt > otherChangedAt ||
            (ownChangedAt == otherChangedAt && sourceDeviceId > other.sourceDeviceId)
    }

    private fun PhoneArticleEntity.isIndependentStateNewerThan(other: PhoneArticleEntity): Boolean {
        return independentChangedAt > other.independentChangedAt ||
            (independentChangedAt == other.independentChangedAt && sourceDeviceId > other.sourceDeviceId)
    }

    private fun PhoneArticleEntity.markDeletedIfEmpty(timestamp: Long): PhoneArticleEntity {
        if (favoriteSaved || watchLaterSaved || independentSaved || !rssSourceUrl.isNullOrBlank()) {
            return copy(deleted = false)
        }
        return copy(deleted = true, deletedAt = timestamp)
    }

    private fun PhoneArticleEntity.markDeletedByUser(timestamp: Long): PhoneArticleEntity {
        return copy(
            sourceDeviceId = deviceId,
            updatedAt = timestamp,
            independentSaved = false,
            independentChangedAt = if (independentSaved || independentChangedAt > 0L) {
                timestamp
            } else {
                independentChangedAt
            },
            independentSortOrder = 0L,
            favoriteSaved = false,
            favoriteChangedAt = if (favoriteSaved || favoriteChangedAt > 0L) {
                timestamp
            } else {
                favoriteChangedAt
            },
            favoriteSortOrder = 0L,
            watchLaterSaved = false,
            watchLaterChangedAt = if (watchLaterSaved || watchLaterChangedAt > 0L) {
                timestamp
            } else {
                watchLaterChangedAt
            },
            watchLaterSortOrder = 0L,
            deleted = true,
            deletedAt = timestamp
        )
    }

    private fun PhoneArticleEntity.applyRemoteSourceDelete(
        source: PhoneRssSourceEntity,
        offset: Int
    ): PhoneArticleEntity {
        val timestamp = (source.deletedAt.takeIf { it > 0L } ?: source.updatedAt) + offset
        return copy(
            sourceDeviceId = source.sourceDeviceId.ifBlank { sourceDeviceId },
            updatedAt = maxOf(updatedAt, timestamp),
            independentSaved = false,
            independentChangedAt = if (independentChangedAt > 0L) timestamp else independentChangedAt,
            independentSortOrder = 0L,
            favoriteSaved = false,
            favoriteChangedAt = if (favoriteChangedAt > 0L) timestamp else favoriteChangedAt,
            favoriteSortOrder = 0L,
            watchLaterSaved = false,
            watchLaterChangedAt = if (watchLaterChangedAt > 0L) timestamp else watchLaterChangedAt,
            watchLaterSortOrder = 0L,
            deleted = true,
            deletedAt = timestamp
        )
    }

    private suspend fun PhoneArticleEntity.ensureSyncMetadata(): PhoneArticleEntity {
        val hydrated = hydrateExternalText()
        val metadata = ArticleSyncBody.metadataFor(hydrated)
        if (
            syncBodyHash == metadata.bodyHash &&
            syncBodyByteCount == metadata.bodyByteCount &&
            syncChunkSize == metadata.chunkSize &&
            syncChunkHashesJson.toStringList() == metadata.chunkHashes &&
            syncMetadataHash == metadata.metadataHash
        ) {
            return this
        }
        val updated = withSyncMetadata(metadata)
        articleDao.upsert(updated)
        return updated
    }

    private fun PhoneArticleEntity.withCurrentSyncMetadata(): PhoneArticleEntity {
        return withSyncMetadata(ArticleSyncBody.metadataFor(this))
    }

    private fun PhoneArticleEntity.withSyncMetadata(metadata: com.lightningstudio.watchrss.phone.connection.bluetooth.ArticleBodyMetadata): PhoneArticleEntity {
        return copy(
            syncBodyHash = metadata.bodyHash,
            syncBodyByteCount = metadata.bodyByteCount,
            syncChunkSize = metadata.chunkSize,
            syncChunkHashesJson = metadata.chunkHashes.toJsonString(),
            syncMetadataHash = metadata.metadataHash
        )
    }

    private fun PhoneArticleEntity.toSyncManifestEntry(): ArticleSyncManifestEntry {
        return ArticleSyncManifestEntry(
            articleId = articleId,
            sourceDeviceId = sourceDeviceId,
            contentHash = contentHash,
            updatedAt = updatedAt,
            independentChangedAt = independentChangedAt,
            favoriteChangedAt = favoriteChangedAt,
            watchLaterChangedAt = watchLaterChangedAt,
            deletedAt = deletedAt,
            bodyHash = syncBodyHash.ifBlank { contentHash },
            bodyByteCount = syncBodyByteCount,
            chunkSize = syncChunkSize,
            chunkHashes = syncChunkHashesJson.toStringList(),
            metadataHash = syncMetadataHash.ifBlank { ArticleSyncBody.metadataHashFor(this) }
        )
    }

    private fun PhoneArticleEntity.needsSyncMetadataRefresh(): Boolean {
        if (deleted) return false
        return syncBodyHash.isBlank() ||
            syncBodyByteCount <= 0L ||
            syncChunkSize <= 0 ||
            syncChunkHashesJson.toStringList().isEmpty() ||
            syncMetadataHash.isBlank() ||
            syncMetadataHash != ArticleSyncBody.metadataHashFor(this)
    }

    private fun PhoneArticleEntity.shouldSyncThroughLibrary(): Boolean {
        return independentSaved ||
            favoriteSaved ||
            watchLaterSaved ||
            deleted ||
            ImportedContentIds.isImportedContentUrl(rssSourceUrl) ||
            ImportedContentIds.isImportedContentUrl(url) ||
            independentChangedAt > 0L ||
            favoriteChangedAt > 0L ||
            watchLaterChangedAt > 0L
    }

    private fun PhoneArticleEntity.externalizeLargeLocalContent(): PhoneArticleEntity {
        val store = articleContentStore ?: return this
        if (!shouldExternalizeLocalContent(store)) return this
        val html = contentHtml?.let { value ->
            if (value.isNotBlank() && !store.isMarker(value) && shouldExternalizeField(value)) {
                store.storeText("$articleId-html", value)
            } else {
                value
            }
        }
        val text = if (contentText.isNotBlank() && !store.isMarker(contentText) && shouldExternalizeField(contentText)) {
            store.storeText("$articleId-text", contentText)
        } else {
            contentText
        }
        return copy(
            contentHtml = html,
            contentText = text
        )
    }

    private fun PhoneArticleEntity.hydrateExternalText(): PhoneArticleEntity {
        val store = articleContentStore ?: return this
        val html = contentHtml?.let { value ->
            if (store.isMarker(value)) store.loadText(value) else value
        }
        val text = if (store.isMarker(contentText)) {
            store.loadText(contentText) ?: excerpt
        } else {
            contentText
        }
        return copy(contentHtml = html, contentText = text)
    }

    private fun PhoneArticleEntity.shouldExternalizeLocalContent(store: ArticleContentStore): Boolean {
        if (!ImportedContentIds.isImportedContentUrl(url)) return false
        val html = contentHtml.orEmpty()
        val totalChars = html.length + contentText.length
        return totalChars > MAX_INLINE_CONTENT_CHARS ||
            shouldExternalizeField(html, store) ||
            shouldExternalizeField(contentText, store)
    }

    private fun shouldExternalizeField(value: String, store: ArticleContentStore? = null): Boolean {
        if (value.isBlank()) return false
        if (store?.isMarker(value) == true) return false
        return value.length > MAX_INLINE_CONTENT_CHARS / 2
    }

    private fun PhoneRssSourceEntity.isNewerThan(other: PhoneRssSourceEntity): Boolean {
        return updatedAt > other.updatedAt ||
            (updatedAt == other.updatedAt && sourceDeviceId > other.sourceDeviceId)
    }

    private fun importedTextSourceUrl(): String = ImportedContentIds.ROOT_SOURCE_URL

    private fun importedTextArticlePrefix(): String = "${ImportedContentIds.ROOT_SOURCE_URL}/txt/%"

    private fun List<String>.toJsonString(): String {
        return JSONArray().also { array ->
            forEach(array::put)
        }.toString()
    }

    private fun String.toStringList(): List<String> {
        if (isBlank()) return emptyList()
        val array = runCatching { JSONArray(this) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                array.optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun hostLabel(link: String): String {
        return runCatching { URI(link).host.orEmpty().removePrefix("www.") }
            .getOrDefault("")
            .trim()
    }

    companion object {
        private const val CHANGE_SEQUENCE_PROTOCOL_VERSION = 6
        private const val DEFAULT_LIBRARY_PEER_ID = "watch"
        private const val FULL_SNAPSHOT_INTERVAL_MS = 7L * 24L * 60L * 60L * 1000L
        private const val SYNC_KIND_ARTICLE = "article"
        private const val SYNC_KIND_RSS_SOURCE = "rssSource"
        private const val MAX_INLINE_CONTENT_CHARS = 100_000
        private const val MAX_REPAIRED_TITLE_CHARS = 80
        private const val MIN_HTML_TOC_LINKS = 3
        private val GENERIC_IMPORTED_TITLES = setOf(
            "unknown",
            "untitled",
            "untitleddocument",
            "未知",
            "无标题",
            "未命名",
            "正文",
            "contents",
            "toc",
            "tableofcontents"
        )
    }
}
