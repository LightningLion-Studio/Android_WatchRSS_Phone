package com.lightningstudio.watchrss.phone.data.repo

import com.lightningstudio.watchrss.phone.connection.bluetooth.ArticleSyncBody
import com.lightningstudio.watchrss.phone.connection.bluetooth.ArticleBodyMetadata
import com.lightningstudio.watchrss.phone.connection.bluetooth.ArticleBodyRequest
import com.lightningstudio.watchrss.phone.connection.bluetooth.ArticleSyncManifestEntry
import com.lightningstudio.watchrss.phone.connection.bluetooth.ARTICLE_BODY_SYNC_MODE_FULL
import com.lightningstudio.watchrss.phone.connection.bluetooth.ARTICLE_BODY_SYNC_MODE_SAVED
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
import com.lightningstudio.watchrss.phone.data.db.AppMetaDao
import com.lightningstudio.watchrss.phone.data.db.AppMetaEntity
import com.lightningstudio.watchrss.phone.data.db.SyncPeerStateDao
import com.lightningstudio.watchrss.phone.data.db.SyncPeerStateEntity
import com.lightningstudio.watchrss.phone.data.importer.ImportedLocalContent
import com.lightningstudio.watchrss.phone.data.importer.ImportedRssSource
import com.lightningstudio.watchrss.phone.data.importer.LocalContentImportKind
import com.lightningstudio.watchrss.phone.data.importer.LocalContentImporter
import com.lightningstudio.watchrss.phone.data.importer.ImportedWebArticle
import com.lightningstudio.watchrss.phone.data.importer.RssSourceImporter
import com.lightningstudio.watchrss.phone.data.importer.WebArticleImporter
import com.lightningstudio.watchrss.phone.data.local.ARTICLE_TEXT_CHUNK_BYTES
import com.lightningstudio.watchrss.phone.data.local.ArticleContentStore
import com.lightningstudio.watchrss.phone.data.model.ImportedContentIds
import com.lightningstudio.watchrss.phone.data.model.PhoneSavedItemType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.json.JSONArray
import java.lang.Long.max
import java.net.URI
import java.text.Normalizer
import kotlin.math.roundToLong

data class PhoneRssSourceImportResult(
    val source: PhoneRssSourceEntity,
    val articleCount: Int
)

data class PhoneLocalContentImportResult(
    val source: PhoneRssSourceEntity,
    val articleCount: Int,
    val kind: LocalContentImportKind
)

enum class TxtUpdateRelation {
    IDENTICAL,
    APPEND_ONLY,
    OLDER_VERSION,
    POSSIBLE_REVISION
}

data class PhoneTxtUpdateCandidate(
    val articleId: String,
    val existingTitle: String,
    val newTitle: String,
    val relation: TxtUpdateRelation,
    val nameSimilarity: Float,
    val oldByteCount: Long,
    val newByteCount: Long,
    val inheritedProgress: Float,
    val inheritedPositionBytes: Long,
    val approximateProgress: Boolean
)

data class PhoneLocalContentImportInspection(
    val fileName: String,
    val mimeType: String?,
    val imported: ImportedLocalContent,
    val candidates: List<PhoneTxtUpdateCandidate>
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

data class PhoneLibrarySyncCursorSnapshot(
    val localMaxSeq: Long,
    val lastRemoteSeqApplied: Long,
    val lastLocalSeqAckedByPeer: Long
)

data class PhoneImportedTextReader(
    val marker: String,
    val byteLength: Long,
    val chunkCount: Int
)

private data class SyncHydratedArticle(
    val article: PhoneArticleEntity,
    val bodyAvailable: Boolean
)

private object NoopSyncChangeLogDao : SyncChangeLogDao {
    override suspend fun insert(change: SyncChangeLogEntity): Long = 0L
    override suspend fun maxSeq(): Long = 0L
    override fun observeMaxSeq(): Flow<Long> = flowOf(0L)
    override suspend fun entityIdsChangedAfter(kind: String, afterSeq: Long): List<String> = emptyList()
    override suspend fun maxChangedAtByEntityIds(
        kind: String,
        entityIds: List<String>
    ): List<SyncChangeLogEntityState> = emptyList()
    override suspend fun deleteAll() = Unit
}

private object NoopSyncPeerStateDao : SyncPeerStateDao {
    override suspend fun get(peerDeviceId: String): SyncPeerStateEntity? = null
    override suspend fun upsert(state: SyncPeerStateEntity) = Unit
    override suspend fun deleteAll() = Unit
}

private object NoopAppMetaDao : AppMetaDao {
    override suspend fun getString(key: String): String? = null
    override fun observeString(key: String): Flow<String?> = flowOf(null)
    override suspend fun set(entity: AppMetaEntity) = Unit
    override suspend fun setIfAbsent(entity: AppMetaEntity) = Unit
    override suspend fun getAll(): List<AppMetaEntity> = emptyList()
    override suspend fun deleteAll() = Unit
}

private const val CONTENT_CHANNEL_SORT_STEP = 10_000L
private const val KEY_FIRST_USE_AT = "first_use_at"

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
    private val articleContentStore: ArticleContentStore? = null,
    private val appMetaDao: AppMetaDao = NoopAppMetaDao
) {
    /** 仅用于 instrumented screenshot tests 直接操作真实数据库。 */
    val testArticleDao: PhoneArticleDao get() = articleDao
    /** 仅用于 instrumented screenshot tests 直接操作真实数据库。 */
    val testRssSourceDao: PhoneRssSourceDao get() = rssSourceDao
    /** 仅用于 instrumented screenshot tests 直接操作真实数据库。 */
    val testSavedItemDao: PhoneSavedItemDao get() = savedItemDao

    fun observeFirstUseAt(): Flow<Long?> {
        return appMetaDao.observeString(KEY_FIRST_USE_AT).map { it?.toLongOrNull() }
    }

    suspend fun recordFirstUseIfAbsent(timestampMillis: Long) {
        appMetaDao.setIfAbsent(AppMetaEntity(key = KEY_FIRST_USE_AT, value = timestampMillis.toString()))
    }

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
        return rssSourceDao.observeActive()
    }

    suspend fun getCloudEligibleRssSources(): List<PhoneRssSourceEntity> =
        withContext(Dispatchers.IO) {
            rssSourceDao.getAllForSync().filter {
                !it.deleted && !ImportedContentIds.isImportedContentUrl(it.url)
            }
        }

    suspend fun mergeCloudRssInventory(imported: ImportedRssSource): PhoneRssSourceImportResult =
        withContext(Dispatchers.IO) {
            saveImportedSource(imported, recordSyncChanges = false)
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
                article?.let { entity ->
                    val migrated = entity.migrateLegacyReadingPositionIfNeeded()
                    if (migrated.isFileBackedImportedText()) {
                        migrated
                    } else {
                        migrated.hydrateExternalText()
                    }
                }
            }
        }
    }

    suspend fun getArticle(articleId: String): PhoneArticleEntity? =
        withContext(Dispatchers.IO) {
            val article = articleDao.getById(articleId) ?: return@withContext null
            val migrated = article.migrateLegacyReadingPositionIfNeeded()
            if (migrated.isFileBackedImportedText()) {
                migrated
            } else {
                migrated.hydrateExternalText()
            }
        }

    suspend fun getRssSource(sourceUrl: String): PhoneRssSourceEntity? =
        withContext(Dispatchers.IO) {
            rssSourceDao.getByUrl(sourceUrl)
        }

    suspend fun getArticlesForBackup(): List<PhoneArticleEntity> =
        withContext(Dispatchers.IO) {
            articleDao.getAllForSync().map { it.hydrateExternalTextForBackup() }
        }

    suspend fun replaceArticlesFromBackup(incoming: List<PhoneArticleEntity>): Int =
        withContext(Dispatchers.IO) {
            val prepared = incoming.map { article ->
                article
                    .normalizeBackupArticle()
                    .withCurrentSyncMetadata()
                    .externalizeLargeLocalContent()
            }
            articleDao.deleteAll()
            if (prepared.isNotEmpty()) {
                articleDao.upsertAll(prepared)
            }
            prepared.size
        }

    suspend fun mergeArticlesFromBackup(incoming: List<PhoneArticleEntity>): Int =
        withContext(Dispatchers.IO) {
            var merged = 0
            incoming.forEach { rawBackup ->
                val backup = rawBackup.copy(sourceDeviceId = "")
                val storedLocal = articleDao.getById(backup.articleId)
                val local = storedLocal?.hydrateExternalTextForBackup()
                val mergedArticle = if (local == null) {
                    backup
                } else {
                    mergeArticleFromBackup(local, backup)
                }
                if (local != mergedArticle) {
                    val prepared = mergedArticle
                        .normalizeBackupArticle()
                        .withCurrentSyncMetadata()
                        .externalizeLargeLocalContent()
                    articleDao.upsert(prepared)
                    merged += 1
                }
            }
            merged
        }

    suspend fun pruneUnreferencedArticleContent() = withContext(Dispatchers.IO) {
        val store = articleContentStore ?: return@withContext
        val retainedMarkers = buildSet {
            articleDao.getAllForSync().forEach { article ->
                article.contentHtml?.takeIf(store::isMarker)?.let(::add)
                article.contentText.takeIf(store::isMarker)?.let(::add)
            }
        }
        store.prune(retainedMarkers)
    }

    suspend fun getImportedTextReader(articleId: String): PhoneImportedTextReader? =
        withContext(Dispatchers.IO) {
            val store = articleContentStore ?: return@withContext null
            val article = articleDao.getById(articleId) ?: return@withContext null
            if (!article.isFileBackedImportedText(store)) return@withContext null
            val marker = article.contentText.takeIf(store::isMarker) ?: return@withContext null
            val handle = store.textChunkHandle(marker, ARTICLE_TEXT_CHUNK_BYTES) ?: return@withContext null
            PhoneImportedTextReader(
                marker = handle.marker,
                byteLength = handle.byteLength,
                chunkCount = handle.chunkCount
            )
        }

    suspend fun loadImportedTextChunk(marker: String, chunkIndex: Int): String? =
        withContext(Dispatchers.IO) {
            articleContentStore?.loadTextChunk(marker, chunkIndex, ARTICLE_TEXT_CHUNK_BYTES)
        }

    suspend fun updateArticleReadingProgress(articleId: String, progress: Float) = withContext(Dispatchers.IO) {
        val article = articleDao.getById(articleId) ?: return@withContext
        val clamped = progress.coerceIn(0f, 1f)
        val byteLength = article.hydrateExternalText().contentText
            .toByteArray(Charsets.UTF_8)
            .size
            .toLong()
        val positionBytes = (byteLength.toDouble() * clamped.toDouble())
            .roundToLong()
            .coerceIn(0L, byteLength)
        val changedAt = System.currentTimeMillis()
        articleDao.updateReadingProgress(
            articleId = articleId,
            progress = clamped,
            positionBytes = positionBytes,
            positionContentHash = article.contentHash,
            positionChangedAt = changedAt
        )
        recordArticleChange(articleId, "readingProgress", changedAt)
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

    suspend fun refreshRssSource(sourceUrl: String): PhoneRssSourceImportResult =
        withContext(Dispatchers.IO) {
            val source = rssSourceDao.getByUrl(sourceUrl) ?: error("频道不存在")
            require(!ImportedContentIds.isImportedContentUrl(source.url)) {
                "本地导入频道无需从 RSS 源刷新"
            }
            val imported = rssSourceImporter(source.url).let { feed ->
                if (source.useOriginalContent) feed.withOriginalArticleContent() else feed
            }
            saveImportedSource(imported.copy(url = source.url))
        }

    private suspend fun ImportedRssSource.withOriginalArticleContent(): ImportedRssSource =
        coroutineScope {
            val permits = Semaphore(4)
            copy(
                items = items.map { item ->
                    async {
                        permits.withPermit {
                            runCatching { webArticleImporter(item.url) }
                                .fold(
                                    onSuccess = { original ->
                                        item.copy(
                                            excerpt = original.excerpt.ifBlank { item.excerpt },
                                            contentHtml = original.contentHtml,
                                            contentText = original.contentText,
                                            imageUrl = original.imageUrl ?: item.imageUrl
                                        )
                                    },
                                    onFailure = { item }
                                )
                        }
                    }
                }.awaitAll()
            )
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
                replaceExistingArticles = imported.kind != LocalContentImportKind.TXT
            )
            PhoneLocalContentImportResult(
                source = result.source,
                articleCount = result.articleCount,
                kind = imported.kind
            )
        }

    suspend fun inspectLocalContentImport(
        fileName: String,
        mimeType: String?,
        bytes: ByteArray
    ): PhoneLocalContentImportInspection = withContext(Dispatchers.IO) {
        val imported = localContentImporter(fileName, mimeType, bytes)
        if (imported.kind != LocalContentImportKind.TXT) {
            return@withContext PhoneLocalContentImportInspection(
                fileName = fileName,
                mimeType = mimeType,
                imported = imported,
                candidates = emptyList()
            )
        }
        val newItem = imported.source.items.single()
        val newText = newItem.contentText
        val newHash = WebArticleImporter.sha256(newText)
        val newByteCount = newText.toByteArray(Charsets.UTF_8).size.toLong()
        val candidates = articleDao.getByRssSourceUrl(ImportedContentIds.ROOT_SOURCE_URL)
            .asSequence()
            .filterNot { it.deleted }
            .filter { ImportedContentIds.isImportedTextArticleUrl(it.url) }
            .mapNotNull { existing ->
                val similarity = importedTxtNameSimilarity(existing.title, newItem.title)
                if (similarity < MIN_TXT_NAME_SIMILARITY && existing.contentHash != newHash) {
                    return@mapNotNull null
                }
                val oldText = existing.hydrateExternalText().contentText
                val relation = classifyTxtUpdate(
                    oldText = oldText,
                    newText = newText,
                    sameContent = existing.contentHash == newHash
                )
                if (similarity < MIN_TXT_NAME_SIMILARITY &&
                    relation == TxtUpdateRelation.POSSIBLE_REVISION
                ) {
                    return@mapNotNull null
                }
                val oldByteCount = oldText.toByteArray(Charsets.UTF_8).size.toLong()
                val storedPosition = existing.readingPositionBytes
                    .takeIf {
                        it > 0L &&
                            (
                                existing.readingPositionContentHash.isBlank() ||
                                    existing.readingPositionContentHash == existing.contentHash
                                )
                    }
                    ?: (oldByteCount.toDouble() * existing.readingProgress.toDouble())
                        .roundToLong()
                val mapped = mapTxtReadingPosition(
                    oldText = oldText,
                    newText = newText,
                    oldPositionBytes = storedPosition,
                    appendOnly = relation == TxtUpdateRelation.APPEND_ONLY
                )
                PhoneTxtUpdateCandidate(
                    articleId = existing.articleId,
                    existingTitle = existing.title,
                    newTitle = newItem.title,
                    relation = relation,
                    nameSimilarity = similarity,
                    oldByteCount = oldByteCount,
                    newByteCount = newByteCount,
                    inheritedProgress = if (newByteCount > 0L) {
                        (mapped.positionBytes.toDouble() / newByteCount.toDouble())
                            .toFloat()
                            .coerceIn(0f, 1f)
                    } else {
                        0f
                    },
                    inheritedPositionBytes = mapped.positionBytes,
                    approximateProgress = mapped.approximate
                )
            }
            .sortedWith(
                compareBy<PhoneTxtUpdateCandidate> {
                    when (it.relation) {
                        TxtUpdateRelation.IDENTICAL -> 0
                        TxtUpdateRelation.APPEND_ONLY -> 1
                        TxtUpdateRelation.OLDER_VERSION -> 2
                        TxtUpdateRelation.POSSIBLE_REVISION -> 3
                    }
                }.thenByDescending { it.nameSimilarity }
                    .thenByDescending { it.oldByteCount }
            )
            .toList()
        PhoneLocalContentImportInspection(
            fileName = fileName,
            mimeType = mimeType,
            imported = imported,
            candidates = candidates
        )
    }

    suspend fun confirmLocalContentImport(
        inspection: PhoneLocalContentImportInspection,
        replaceArticleId: String?
    ): PhoneLocalContentImportResult = withContext(Dispatchers.IO) {
        if (replaceArticleId == null || inspection.imported.kind != LocalContentImportKind.TXT) {
            val result = saveImportedSource(
                imported = inspection.imported.source,
                replaceExistingArticles = inspection.imported.kind != LocalContentImportKind.TXT
            )
            return@withContext PhoneLocalContentImportResult(
                source = result.source,
                articleCount = result.articleCount,
                kind = inspection.imported.kind
            )
        }
        val candidate = inspection.candidates.firstOrNull { it.articleId == replaceArticleId }
            ?: error("待覆盖的 TXT 候选已失效")
        val existing = articleDao.getById(replaceArticleId)
            ?.takeUnless { it.deleted }
            ?: error("待覆盖的 TXT 已不存在")
        val importedItem = inspection.imported.source.items.single()
        if (candidate.relation == TxtUpdateRelation.IDENTICAL) {
            return@withContext PhoneLocalContentImportResult(
                source = requireNotNull(rssSourceDao.getByUrl(ImportedContentIds.ROOT_SOURCE_URL)),
                articleCount = 0,
                kind = LocalContentImportKind.TXT
            )
        }
        val now = System.currentTimeMillis()
        val contentHash = WebArticleImporter.sha256(importedItem.contentText)
        val hydrated = existing.copy(
            sourceDeviceId = deviceId,
            title = importedItem.title,
            excerpt = importedItem.excerpt,
            contentHtml = null,
            contentText = importedItem.contentText,
            imageUrl = importedItem.imageUrl,
            contentHash = contentHash,
            updatedAt = now,
            deleted = false,
            deletedAt = 0L,
            readingProgress = candidate.inheritedProgress,
            readingPositionBytes = candidate.inheritedPositionBytes,
            readingPositionContentHash = contentHash,
            readingPositionChangedAt = now
        ).withCurrentSyncMetadata()
        val stored = articleContentStore?.let { store ->
            val marker = store.storeText(
                "${existing.articleId}-text-${contentHash.take(16)}",
                importedItem.contentText
            )
            hydrated.copy(contentText = marker)
        } ?: hydrated
        articleDao.upsert(stored)
        recordArticleChange(stored.articleId, "txtRevision", now)
        val source = rssSourceDao.getByUrl(ImportedContentIds.ROOT_SOURCE_URL)
            ?: error("导入内容频道不存在")
        PhoneLocalContentImportResult(
            source = source,
            articleCount = 1,
            kind = LocalContentImportKind.TXT
        )
    }

    /** Selects the pre-parsed chapterized alternative without decoding the file a second time. */
    suspend fun useTxtChapterImport(
        inspection: PhoneLocalContentImportInspection
    ): PhoneLocalContentImportInspection = withContext(Dispatchers.Default) {
        val chapterPlan = inspection.imported.txtChapterPlan
            ?: error("TXT 未识别到可用章节")
        val chapteredSource = LocalContentImporter().buildTxtChapterSource(chapterPlan)
            ?: error("TXT 章节正文不足")
        inspection.copy(
            imported = inspection.imported.copy(
                kind = LocalContentImportKind.TXT_CHAPTERS,
                source = chapteredSource
            ),
            candidates = emptyList()
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
                    sourceDeviceId = deviceId,
                    deleted = false
                )
                PhoneSavedItemType.WATCH_LATER -> current.copy(
                    watchLaterSaved = !current.watchLaterSaved,
                    watchLaterChangedAt = now,
                    watchLaterSortOrder = if (!current.watchLaterSaved) now else current.watchLaterSortOrder,
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
        val nextSortOrder = nextTopRssSourceSortOrder(now)
        val updated = source.copy(
                sourceDeviceId = deviceId,
                updatedAt = now,
                sortOrder = nextSortOrder,
                deleted = false,
                deletedAt = 0L
            )
        rssSourceDao.upsert(updated)
        recordRssSourceChange(updated.url, "sourceState", now)
    }

    suspend fun reorderRssSources(sourceUrlsInDisplayOrder: List<String>) = withContext(Dispatchers.IO) {
        reorderContentChannelsInternal(sourceUrlsInDisplayOrder, independentIndex = null)
    }

    suspend fun reorderContentChannels(
        sourceUrlsInDisplayOrder: List<String>,
        independentIndex: Int?
    ) = withContext(Dispatchers.IO) {
        reorderContentChannelsInternal(sourceUrlsInDisplayOrder, independentIndex)
    }

    private suspend fun reorderContentChannelsInternal(
        sourceUrlsInDisplayOrder: List<String>,
        independentIndex: Int?
    ) {
        val orderedUrls = sourceUrlsInDisplayOrder
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        if (orderedUrls.size < 2 && independentIndex == null) return
        if (ImportedContentIds.ROOT_SOURCE_URL in orderedUrls) {
            repairMissingImportedTextSourceIfNeeded(rssSourceDao.getAllForSync())
        }
        val sourcesByUrl = rssSourceDao.getAllForSync().associateBy { it.url }
        val requestedSources = orderedUrls.mapNotNull { url ->
            sourcesByUrl[url]?.takeUnless { it.deleted }
        }
        if (requestedSources.map { it.isPinned }.distinct().size > 1) return
        if (independentIndex != null && requestedSources.any { it.isPinned }) return
        val now = System.currentTimeMillis()
        val hasIndependentArticles = independentIndex != null &&
            articleDao.getAllForSync().any { it.independentSaved && !it.deleted }
        val itemCount = orderedUrls.size + if (hasIndependentArticles) 1 else 0
        if (itemCount < 2) return
        val normalizedIndependentIndex = if (hasIndependentArticles) {
            independentIndex?.coerceIn(0, itemCount - 1)
        } else {
            null
        }
        val baseSortOrder = now + itemCount * CONTENT_CHANNEL_SORT_STEP
        var sourceIndex = 0
        for (displayIndex in 0 until itemCount) {
            val sortOrder = baseSortOrder - displayIndex * CONTENT_CHANNEL_SORT_STEP
            if (displayIndex == normalizedIndependentIndex) {
                reorderIndependentArticles(sortOrder, now + displayIndex)
            } else {
                val url = orderedUrls.getOrNull(sourceIndex++) ?: continue
                val source = sourcesByUrl[url]?.takeUnless { it.deleted } ?: continue
                val updatedAt = now + displayIndex
                val updated = source.copy(
                    sourceDeviceId = deviceId,
                    updatedAt = updatedAt,
                    sortOrder = sortOrder,
                    deleted = false,
                    deletedAt = 0L
                )
                if (updated != source) {
                    rssSourceDao.upsert(updated)
                    recordRssSourceChange(updated.url, "sourceState", updatedAt)
                }
            }
        }
    }

    private suspend fun reorderIndependentArticles(rowSortOrder: Long, changedAt: Long) {
        val articles = articleDao.getAllForSync()
            .filter { it.independentSaved && !it.deleted }
            .sortedWith(
                compareByDescending<PhoneArticleEntity> { it.independentSortOrder }
                    .thenByDescending { it.independentChangedAt }
                    .thenByDescending { it.importedAt }
                    .thenBy { it.title }
            )
        articles.forEachIndexed { index, article ->
            val articleChangedAt = changedAt + index
            val updated = article.copy(
                sourceDeviceId = deviceId,
                independentChangedAt = articleChangedAt,
                independentSortOrder = rowSortOrder - index,
                deleted = false
            )
            if (updated != article) {
                articleDao.upsert(updated)
                recordArticleChange(updated.articleId, "sourceState", articleChangedAt)
            }
        }
    }

    suspend fun setRssSourcePinned(sourceUrl: String, pinned: Boolean) = withContext(Dispatchers.IO) {
        val source = rssSourceDao.getByUrl(sourceUrl) ?: return@withContext
        val now = System.currentTimeMillis()
        val nextSortOrder = if (pinned) nextTopRssSourceSortOrder(now) else source.sortOrder
        val updated = source.copy(
                sourceDeviceId = deviceId,
                updatedAt = now,
                sortOrder = nextSortOrder,
                isPinned = pinned,
                deleted = false,
                deletedAt = 0L
            )
        rssSourceDao.upsert(updated)
        recordRssSourceChange(updated.url, "sourceState", now)
    }

    suspend fun setRssSourceOriginalContentEnabled(sourceUrl: String, enabled: Boolean) =
        updateRssSourceSettings(sourceUrl) { copy(useOriginalContent = enabled) }

    suspend fun setRssSourceContinuePlaybackInBackground(
        sourceUrl: String,
        enabled: Boolean
    ) = updateRssSourceSettings(sourceUrl) {
        copy(continuePlaybackInBackground = enabled)
    }

    private suspend fun updateRssSourceSettings(
        sourceUrl: String,
        transform: PhoneRssSourceEntity.() -> PhoneRssSourceEntity
    ) = withContext(Dispatchers.IO) {
        val source = rssSourceDao.getByUrl(sourceUrl) ?: return@withContext
        val now = System.currentTimeMillis()
        val updated = source.transform().copy(
            sourceDeviceId = deviceId,
            updatedAt = now
        )
        if (updated == source) return@withContext
        rssSourceDao.upsert(updated)
        recordRssSourceChange(updated.url, "sourceSettings", now)
    }

    suspend fun clearRssSourceContent(sourceUrl: String): Int = withContext(Dispatchers.IO) {
        val articles = articleDao.getByRssSourceUrl(sourceUrl).filterNot { it.deleted }
        val now = System.currentTimeMillis()
        articles.forEachIndexed { index, article ->
            val deleted = article.markDeletedForStorage(now + index)
            articleDao.upsert(deleted)
            recordArticleChange(deleted.articleId, "delete", now + index)
        }
        articles.size
    }

    private suspend fun nextTopRssSourceSortOrder(now: Long): Long {
        val currentMax = rssSourceDao.getAllForSync()
            .filterNot { it.deleted }
            .maxOfOrNull { it.sortOrder } ?: 0L
        return maxOf(now, currentMax + CONTENT_CHANNEL_SORT_STEP)
    }

    suspend fun deleteRssSource(sourceUrl: String) = withContext(Dispatchers.IO) {
        val source = rssSourceDao.getByUrl(sourceUrl) ?: return@withContext
        val now = System.currentTimeMillis()
        if (ImportedContentIds.isImportedContentUrl(sourceUrl)) {
            articleDao.getByRssSourceUrl(sourceUrl)
                .filterNot { it.deleted }
                .forEachIndexed { index, article ->
                    val deletedArticle = article.markDeletedForStorage(now + index)
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
        val deleted = current.markDeletedForStorage(now)
        articleDao.upsert(deleted)
        recordArticleChange(deleted.articleId, "delete", now)
    }

    suspend fun clearImportedContent(): Int = withContext(Dispatchers.IO) {
        val importedArticles = articleDao.getByRssSourceUrl(ImportedContentIds.ROOT_SOURCE_URL)
            .filterNot { it.deleted }
        val now = System.currentTimeMillis()
        importedArticles.forEachIndexed { index, article ->
            val deleted = article.markDeletedForStorage(now + index)
            articleDao.upsert(deleted)
            recordArticleChange(deleted.articleId, "delete", now + index)
        }
        importedArticles.size
    }

    suspend fun getArticlesForSync(): List<PhoneArticleEntity> = withContext(Dispatchers.IO) {
        articleDao.getAllForSync()
            .filter { it.shouldSyncThroughLibrary() }
            .mapNotNull { it.articleForBodyExport() }
    }

    suspend fun getArticleManifestsForSync(): List<ArticleSyncManifestEntry> = withContext(Dispatchers.IO) {
        articleDao.getAllForSync()
            .filter { it.shouldSyncThroughLibrary() }
            .map { it.syncManifestEntryForExport() }
    }

    private suspend fun getArticleManifestsForSync(articleIds: Collection<String>): List<ArticleSyncManifestEntry> {
        val idSet = articleIds.toSet()
        if (idSet.isEmpty()) return emptyList()
        return articleDao.getAllForSync()
            .filter { it.articleId in idSet && it.shouldSyncThroughLibrary() }
            .map { it.syncManifestEntryForExport() }
    }

    suspend fun getArticlesForSync(articleIds: Collection<String>): List<PhoneArticleEntity> =
        withContext(Dispatchers.IO) {
            val idSet = articleIds.toSet()
            if (idSet.isEmpty()) return@withContext emptyList()
            articleDao.getAllForSync()
                .filter { it.articleId in idSet }
                .mapNotNull { it.articleForBodyExport() }
        }

    suspend fun getRssSourcesForSync(): List<PhoneRssSourceEntity> = withContext(Dispatchers.IO) {
        rssSourceDao.getAllForSync()
    }

    private suspend fun getRssSourcesForSync(sourceUrls: Collection<String>): List<PhoneRssSourceEntity> {
        val urlSet = sourceUrls.toSet()
        if (urlSet.isEmpty()) return emptyList()
        return rssSourceDao.getAllForSync()
            .filter { it.url in urlSet }
    }

    suspend fun getLibrarySyncCursor(peerDeviceId: String?): PhoneLibrarySyncCursorSnapshot =
        withContext(Dispatchers.IO) {
            val peerState = peerDeviceId
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { syncPeerStateDao.get(it) }
            PhoneLibrarySyncCursorSnapshot(
                localMaxSeq = syncChangeLogDao.maxSeq(),
                lastRemoteSeqApplied = peerState?.lastRemoteSeqApplied ?: 0L,
                lastLocalSeqAckedByPeer = peerState?.lastLocalSeqAckedByPeer ?: 0L
            )
        }

    suspend fun prepareLibrarySyncWindow(
        peerDeviceId: String,
        peerAppliedLocalSeq: Long? = null
    ): PhoneLibrarySyncWindow =
        withContext(Dispatchers.IO) {
            val normalizedPeerId = peerDeviceId.ifBlank { DEFAULT_LIBRARY_PEER_ID }
            val peerState = syncPeerStateDao.get(normalizedPeerId)
            val now = System.currentTimeMillis()
            val fullArticleManifest = getArticleManifestsForSync()
            repairMissingArticleChangeLogEntries(fullArticleManifest)
            val maxSeq = syncChangeLogDao.maxSeq()
            val cachedPeerAckedSeq = peerState?.lastLocalSeqAckedByPeer ?: 0L
            val peerAckedSeq = peerAppliedLocalSeq?.coerceAtLeast(0L) ?: cachedPeerAckedSeq
            val fullSnapshotReason = when {
                peerState == null -> "newPeer"
                peerState.lastProtocolVersion < CHANGE_SEQUENCE_PROTOCOL_VERSION -> "peerProtocol"
                peerAppliedLocalSeq != null && peerAckedSeq < cachedPeerAckedSeq -> "peerCursorBehind"
                peerAppliedLocalSeq != null && peerAckedSeq > maxSeq -> "peerCursorAhead"
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
        var repaired = 0
        repaired += if (repairMissingImportedTextSourceIfNeeded(rssSourceDao.getAllForSync())) 1 else 0
        val sources = rssSourceDao.getAllForSync()
            .filter { ImportedContentIds.isImportedContentUrl(it.url) }
        sources.forEach { source ->
            val liveArticles = if (ImportedContentIds.isImportedTextSourceUrl(source.url)) {
                liveImportedTextArticles()
            } else {
                articleDao.getByRssSourceUrl(source.url).filterNot { it.deleted }
            }
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

    private suspend fun repairMissingImportedTextSourceIfNeeded(
        sources: List<PhoneRssSourceEntity>
    ): Boolean {
        if (sources.any { it.url == ImportedContentIds.ROOT_SOURCE_URL }) return false
        val liveArticles = liveImportedTextArticles()
        if (liveArticles.isEmpty()) return false
        val latestArticleUpdate = liveArticles.maxOf { article ->
            maxOf(article.updatedAt, article.importedAt)
        }
        val createdAt = liveArticles.minOf { article ->
            listOf(article.importedAt, article.updatedAt)
                .filter { it > 0L }
                .minOrNull() ?: latestArticleUpdate
        }
        val source = PhoneRssSourceEntity(
            url = ImportedContentIds.ROOT_SOURCE_URL,
            sourceDeviceId = deviceId,
            title = ImportedContentIds.ROOT_SOURCE_TITLE,
            description = "",
            siteUrl = null,
            imageUrl = null,
            createdAt = createdAt,
            updatedAt = latestArticleUpdate,
            sortOrder = latestArticleUpdate,
            isPinned = false,
            deleted = false,
            deletedAt = 0L
        )
        rssSourceDao.upsert(source)
        recordRssSourceChange(source.url, "repairState", latestArticleUpdate)
        return true
    }

    private suspend fun liveImportedTextArticles(): List<PhoneArticleEntity> {
        return articleDao.getAllForSync().filter { article ->
            !article.deleted &&
                (
                    article.rssSourceUrl == ImportedContentIds.ROOT_SOURCE_URL ||
                        ImportedContentIds.isImportedTextArticleUrl(article.url)
                )
        }
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
                        if (remote.bodyAvailable) {
                            forcedRemoteRequests += remote.toFullBodyRequest()
                            mergeResolutions[remote.articleId] = if (resolution == PhoneSyncConflictResolution.MERGE_CONTENT) {
                                PhoneSyncConflictResolution.MERGE_CONTENT
                            } else {
                                PhoneSyncConflictResolution.KEEP_WATCH
                            }
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
            val retainsLocalBody =
                local != null &&
                remote.contentHtml.isNullOrBlank() &&
                remote.contentText.isBlank()
            val remoteWithBody = if (retainsLocalBody) {
                val localBody = requireNotNull(local)
                remote.copy(
                    contentHtml = localBody.contentHtml,
                    contentText = localBody.contentText,
                    syncBodyHash = localBody.syncBodyHash,
                    syncBodyByteCount = localBody.syncBodyByteCount,
                    syncChunkSize = localBody.syncChunkSize,
                    syncChunkHashesJson = localBody.syncChunkHashesJson,
                    syncMetadataHash = localBody.syncMetadataHash
                )
            } else {
                remote
            }
            val preparedRemote = if (retainsLocalBody) {
                remoteWithBody
            } else {
                remoteWithBody.withCurrentSyncMetadata()
            }
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
            val verifiedNext = next.withCurrentSyncMetadata()
            if (local != verifiedNext) {
                articleDao.upsert(verifiedNext.externalizeLargeLocalContent())
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
            val localByArticleId = mutableMapOf<String, PhoneArticleEntity?>()
            incoming.forEach { payload ->
                if (!localByArticleId.containsKey(payload.article.articleId)) {
                    localByArticleId[payload.article.articleId] = articleDao.getById(payload.article.articleId)
                }
            }
            incoming.filter { it.metadataOnly }.forEach { payload ->
                val local = localByArticleId[payload.article.articleId]
                    ?: error("同步正文仅元数据响应缺少本地正文：${payload.article.articleId}")
                val hydrated = local.hydrateExternalTextForSync()
                check(hydrated.bodyAvailable) {
                    "同步正文仅元数据响应的本地正文文件缺失：${payload.article.articleId}"
                }
                requireMetadataOnlyLocalBody(payload, hydrated.article)
            }
            var merged = 0
            incoming.forEach { payload ->
                val local = localByArticleId[payload.article.articleId]
                val hydratedLocal = local?.hydrateExternalTextForSync()
                val localHydrated = hydratedLocal?.article?.takeIf { hydratedLocal.bodyAvailable }
                val explicitUnavailableTombstone = payload.isExplicitUnavailableTombstone()
                val retainsLocalDeletedBody = payload.article.deleted &&
                    localHydrated != null
                val (contentHtml, contentText) = if (retainsLocalDeletedBody) {
                    if (!payload.metadataOnly && !explicitUnavailableTombstone) {
                        // Validate a full tombstone payload before retaining the verified local body.
                        ArticleSyncBody.rebuildBody(requireNotNull(localHydrated), payload)
                    }
                    requireNotNull(localHydrated).let { it.contentHtml to it.contentText }
                } else if (payload.article.deleted && explicitUnavailableTombstone) {
                    null to ""
                } else if (payload.article.deleted) {
                    require(!payload.metadataOnly) {
                        "同步删除仅元数据响应缺少本地正文：${payload.article.articleId}"
                    }
                    ArticleSyncBody.rebuildBody(null, payload)
                } else if (payload.metadataOnly) {
                    requireNotNull(localHydrated).let { it.contentHtml to it.contentText }
                } else {
                    ArticleSyncBody.rebuildBody(localHydrated, payload)
                }
                val preparedRemote = payload.article.copy(
                    contentHtml = contentHtml,
                    contentText = contentText,
                )
                if (!payload.metadataOnly && !explicitUnavailableTombstone && !retainsLocalDeletedBody) {
                    val actualRemoteMetadata = ArticleSyncBody.metadataFor(preparedRemote)
                    require(actualRemoteMetadata.bodyHash == payload.bodyHash) {
                        "同步正文整体校验失败：${payload.article.articleId}"
                    }
                    require(actualRemoteMetadata.bodyByteCount == payload.bodyByteCount) {
                        "同步正文长度校验失败：${payload.article.articleId}"
                    }
                    require(actualRemoteMetadata.chunkSize == payload.chunkSize) {
                        "同步正文分块大小校验失败：${payload.article.articleId}"
                    }
                    require(actualRemoteMetadata.chunkHashes == payload.chunkHashes) {
                        "同步正文分块元数据校验失败：${payload.article.articleId}"
                    }
                }
                val mergedArticle = if (localHydrated == null) {
                    preparedRemote
                } else {
                    mergeArticle(
                        local = localHydrated,
                        remote = preparedRemote,
                        conflictResolution = conflictResolutions[payload.article.articleId]
                    )
                }
                val next = if (
                    explicitUnavailableTombstone &&
                    !retainsLocalDeletedBody &&
                    mergedArticle.deleted
                ) {
                    mergedArticle.withUnavailableTombstoneBodyMetadata(clearBody = true)
                } else {
                    mergedArticle.withCurrentSyncMetadata()
                }
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
                val local = rssSourceDao.getByUrl(remote.url)
                val compatibleRemote = if (local != null && !remote.syncedSettingsIncluded) {
                    remote.copy(
                        useOriginalContent = local.useOriginalContent,
                        continuePlaybackInBackground = local.continuePlaybackInBackground
                    )
                } else {
                    remote
                }
                val next = if (local == null || compatibleRemote.isNewerThan(local)) {
                    compatibleRemote
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
            saveImportedArticle(
                imported,
                type = type,
                independent = false,
                timestamp = syncedAt,
                preserveExistingBody = true
            )
        }
        return entities.size
    }

    private suspend fun saveImportedArticle(
        imported: ImportedWebArticle,
        type: PhoneSavedItemType?,
        independent: Boolean,
        timestamp: Long = System.currentTimeMillis(),
        preserveExistingBody: Boolean = false
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
            contentHtml = if (preserveExistingBody && current != null) current.contentHtml else imported.contentHtml,
            contentText = if (preserveExistingBody && current != null) current.contentText else imported.contentText,
            imageUrl = imported.imageUrl,
            contentHash = if (preserveExistingBody && current != null) current.contentHash else imported.contentHash,
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
        val stored = if (preserveExistingBody && current != null) {
            val hydrated = saved.hydrateExternalTextForSync()
            if (hydrated.bodyAvailable) {
                saved.withSyncMetadata(ArticleSyncBody.currentMetadataFor(hydrated.article))
            } else {
                saved.copy(syncMetadataHash = ArticleSyncBody.metadataHashFor(saved))
            }
        } else {
            saved.withCurrentSyncMetadata().externalizeLargeLocalContent()
        }
        articleDao.upsert(stored)
        recordArticleChange(stored.articleId, "upsert", timestamp)
        return stored
    }

    private suspend fun saveImportedSource(
        imported: ImportedRssSource,
        replaceExistingArticles: Boolean = false,
        recordSyncChanges: Boolean = true
    ): PhoneRssSourceImportResult {
        val now = System.currentTimeMillis()
        val existing = rssSourceDao.getByUrl(imported.url)
        val existingArticles = articleDao.getByRssSourceUrl(imported.url)
        val existingByContentHash = existingArticles
            .filter { it.contentHash.isNotBlank() }
            .associateBy { it.contentHash }
        val existingByUrl = existingArticles.associateBy { it.url }
        val candidateSource = PhoneRssSourceEntity(
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
            deletedAt = 0L,
            useOriginalContent = existing?.useOriginalContent ?: false,
            continuePlaybackInBackground = existing?.continuePlaybackInBackground ?: false
        )
        val source = if (
            existing != null &&
            existing.title == candidateSource.title &&
            existing.description == candidateSource.description &&
            existing.siteUrl == candidateSource.siteUrl &&
            existing.imageUrl == candidateSource.imageUrl &&
            !existing.deleted
        ) {
            candidateSource.copy(
                sourceDeviceId = existing.sourceDeviceId,
                updatedAt = existing.updatedAt
            )
        } else {
            candidateSource
        }
        rssSourceDao.upsert(source)
        if (recordSyncChanges && source != existing) {
            recordRssSourceChange(source.url, "upsert", now)
        }
        val articles = imported.items.mapIndexed { index, item ->
            val timestamp = now - index
            val existingArticle = existingByUrl[item.url]
                ?: existingByContentHash[item.contentHash ?: WebArticleImporter.sha256(
                    item.contentHtml ?: item.contentText.ifBlank { item.url }
                )]
            val candidate = PhoneArticleEntity(
                articleId = WebArticleImporter.stableArticleId(item.url),
                sourceDeviceId = deviceId,
                url = item.url,
                title = item.title.ifBlank { item.url },
                siteName = source.title,
                excerpt = item.excerpt,
                contentHtml = item.contentHtml,
                contentText = item.contentText,
                imageUrl = item.imageUrl,
                contentHash = item.contentHash ?: WebArticleImporter.sha256(
                    item.contentHtml ?: item.contentText.ifBlank { item.url }
                ),
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
                .withSavedStateFrom(existingArticle)
                .withCurrentSyncMetadata()
                .externalizeLargeLocalContent()
            if (
                existingArticle != null &&
                existingArticle.copy(
                    sourceDeviceId = candidate.sourceDeviceId,
                    importedAt = candidate.importedAt,
                    updatedAt = candidate.updatedAt,
                    syncBodyHash = candidate.syncBodyHash,
                    syncBodyByteCount = candidate.syncBodyByteCount,
                    syncChunkSize = candidate.syncChunkSize,
                    syncChunkHashesJson = candidate.syncChunkHashesJson,
                    syncMetadataHash = candidate.syncMetadataHash
                ) == candidate
            ) {
                candidate.copy(
                    sourceDeviceId = existingArticle.sourceDeviceId,
                    importedAt = existingArticle.importedAt,
                    updatedAt = existingArticle.updatedAt
                )
            } else {
                candidate
            }
        }
        if (replaceExistingArticles) {
            articleDao.deleteByRssSourceUrl(imported.url)
        }
        if (articles.isNotEmpty()) {
            articleDao.upsertAll(articles)
            if (recordSyncChanges) {
                articles.forEach { article ->
                    val previous = existingByUrl[article.url]
                    if (previous != article) {
                        recordArticleChange(article.articleId, "upsert", article.updatedAt)
                    }
                }
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
            watchLaterSortOrder = existing.watchLaterSortOrder,
            readingProgress = existing.readingProgress,
            readingPositionBytes = existing.readingPositionBytes,
            readingPositionContentHash = existing.readingPositionContentHash,
            readingPositionChangedAt = existing.readingPositionChangedAt
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
        val remoteContentIsSelected =
            remote.contentHash == metadata.contentHash && local.contentHash != metadata.contentHash
        val localContentIsSelected =
            local.contentHash == metadata.contentHash && remote.contentHash != metadata.contentHash
        val remoteReadingPositionNewer = remoteContentIsSelected ||
            (!localContentIsSelected &&
                remote.readingPositionChangedAt > local.readingPositionChangedAt)
        val localReadingPositionNewer = localContentIsSelected ||
            (!remoteContentIsSelected &&
                local.readingPositionChangedAt > remote.readingPositionChangedAt)
        val readingProgress = when {
            remoteReadingPositionNewer -> remote.readingProgress
            localReadingPositionNewer -> local.readingProgress
            else -> maxOf(local.readingProgress, remote.readingProgress)
        }
        val readingPositionBytes = when {
            remoteReadingPositionNewer -> remote.readingPositionBytes
            localReadingPositionNewer -> local.readingPositionBytes
            remote.readingProgress > local.readingProgress -> remote.readingPositionBytes
            else -> local.readingPositionBytes
        }
        val readingPositionContentHash = when {
            remoteReadingPositionNewer -> remote.readingPositionContentHash
            localReadingPositionNewer -> local.readingPositionContentHash
            remote.readingProgress > local.readingProgress -> remote.readingPositionContentHash
            else -> local.readingPositionContentHash
        }
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
        val deletedAt = if (deleted) {
            max(local.deletedAt, remote.deletedAt)
        } else {
            0L
        }
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
            deletedAt = deletedAt,
            readingProgress = readingProgress,
            readingPositionBytes = readingPositionBytes,
            readingPositionContentHash = readingPositionContentHash,
            readingPositionChangedAt = when {
                remoteReadingPositionNewer -> remote.readingPositionChangedAt
                localReadingPositionNewer -> local.readingPositionChangedAt
                else -> maxOf(local.readingPositionChangedAt, remote.readingPositionChangedAt)
            },
            isRead = local.isRead || remote.isRead
        )
    }

    private fun mergeArticleFromBackup(
        local: PhoneArticleEntity,
        backup: PhoneArticleEntity
    ): PhoneArticleEntity {
        val merged = mergeArticleByLatest(local, backup)
        val metadata = if (backup.updatedAt > local.updatedAt) backup else local
        val rssSourceUrl = metadata.rssSourceUrl?.takeIf { it.isNotBlank() }
        val rssSourceTitle = metadata.rssSourceTitle?.takeIf { it.isNotBlank() }
        val isImportedContentArticle = ImportedContentIds.isImportedContentUrl(rssSourceUrl) ||
            ImportedContentIds.isImportedContentUrl(backup.url) ||
            ImportedContentIds.isImportedContentUrl(local.url)
        val backupDeletedNewer = backup.deletedAt > local.deletedAt
        val deleted = when {
            merged.favoriteSaved || merged.watchLaterSaved || merged.independentSaved -> false
            !rssSourceUrl.isNullOrBlank() && !isImportedContentArticle -> false
            backupDeletedNewer -> backup.deleted
            else -> local.deleted
        }
        return merged.copy(
            rssSourceUrl = rssSourceUrl,
            rssSourceTitle = rssSourceTitle,
            deleted = deleted,
            deletedAt = if (deleted) maxOf(local.deletedAt, backup.deletedAt) else 0L
        )
    }

    private fun mergeArticleContent(local: PhoneArticleEntity, remote: PhoneArticleEntity): PhoneArticleEntity {
        val base = when {
            !remote.deleted -> remote
            !local.deleted -> local
            else -> mergeArticleByLatest(local, remote)
        }
        val remoteReadingPositionWins = when {
            remote.contentHash == base.contentHash && local.contentHash != base.contentHash -> true
            local.contentHash == base.contentHash && remote.contentHash != base.contentHash -> false
            remote.readingPositionChangedAt > local.readingPositionChangedAt -> true
            local.readingPositionChangedAt > remote.readingPositionChangedAt -> false
            else -> remote.readingProgress > local.readingProgress
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
            deletedAt = 0L,
            readingProgress = if (remoteReadingPositionWins) {
                remote.readingProgress
            } else {
                local.readingProgress
            },
            readingPositionBytes = if (remoteReadingPositionWins) {
                remote.readingPositionBytes
            } else {
                local.readingPositionBytes
            },
            readingPositionContentHash = if (remoteReadingPositionWins) {
                remote.readingPositionContentHash
            } else {
                local.readingPositionContentHash
            },
            readingPositionChangedAt = if (remoteReadingPositionWins) {
                remote.readingPositionChangedAt
            } else {
                local.readingPositionChangedAt
            },
            isRead = local.isRead || remote.isRead
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
            return copy(deleted = false, deletedAt = 0L)
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

    private fun PhoneArticleEntity.markDeletedForStorage(timestamp: Long): PhoneArticleEntity {
        val tombstone = markDeletedByUser(timestamp)
        val hydrated = tombstone.hydrateExternalTextForSync()
        return if (hydrated.bodyAvailable) {
            tombstone.withSyncMetadata(ArticleSyncBody.metadataFor(hydrated.article))
        } else {
            tombstone.withUnavailableTombstoneBodyMetadata()
        }
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

    private suspend fun PhoneArticleEntity.ensureSyncMetadata(
        hydrated: PhoneArticleEntity = hydrateExternalText()
    ): PhoneArticleEntity {
        ArticleSyncBody.cachedMetadataFor(hydrated)?.let { cached ->
            if (hasSyncMetadata(cached)) return this
            val updated = withSyncMetadata(cached)
            articleDao.upsert(updated)
            return updated
        }
        val metadata = ArticleSyncBody.metadataFor(hydrated)
        if (hasSyncMetadata(metadata)) {
            return this
        }
        val updated = withSyncMetadata(metadata)
        articleDao.upsert(updated)
        return updated
    }

    private suspend fun PhoneArticleEntity.syncManifestEntryForExport(): ArticleSyncManifestEntry {
        val hydrated = hydrateExternalTextForSync()
        if (!hydrated.bodyAvailable) {
            val tombstone = if (deleted) withUnavailableTombstoneBodyMetadata() else this
            if (tombstone != this) articleDao.upsert(tombstone)
            return tombstone.toSyncManifestEntry(bodyAvailable = false)
        }
        return ensureSyncMetadata(hydrated.article).toSyncManifestEntry(bodyAvailable = true)
    }

    private fun PhoneArticleEntity.articleForBodyExport(): PhoneArticleEntity? {
        val hydrated = hydrateExternalTextForSync()
        if (!hydrated.bodyAvailable) return null
        return hydrated.article.withCurrentSyncMetadata()
    }

    private fun PhoneArticleEntity.withCurrentSyncMetadata(): PhoneArticleEntity {
        return withSyncMetadata(ArticleSyncBody.currentMetadataFor(this))
    }

    private fun PhoneArticleEntity.withUnavailableTombstoneBodyMetadata(
        clearBody: Boolean = false
    ): PhoneArticleEntity {
        require(deleted) { "仅删除记录可标记正文不可用：$articleId" }
        return copy(
            contentHtml = if (clearBody) null else contentHtml,
            contentText = if (clearBody) "" else contentText,
            syncBodyHash = "",
            syncBodyByteCount = 0L,
            syncChunkSize = 0,
            syncChunkHashesJson = "",
            syncMetadataHash = ArticleSyncBody.metadataHashFor(this)
        )
    }

    private fun PhoneArticleEntity.withSyncMetadata(metadata: ArticleBodyMetadata): PhoneArticleEntity {
        return copy(
            syncBodyHash = metadata.bodyHash,
            syncBodyByteCount = metadata.bodyByteCount,
            syncChunkSize = metadata.chunkSize,
            syncChunkHashesJson = metadata.chunkHashes.toJsonString(),
            syncMetadataHash = metadata.metadataHash
        )
    }

    private fun PhoneArticleEntity.hasSyncMetadata(metadata: ArticleBodyMetadata): Boolean {
        return syncBodyHash == metadata.bodyHash &&
            syncBodyByteCount == metadata.bodyByteCount &&
            syncChunkSize == metadata.chunkSize &&
            syncChunkHashesJson.toStringList() == metadata.chunkHashes &&
            syncMetadataHash == metadata.metadataHash
    }

    private fun PhoneArticleEntity.toSyncManifestEntry(bodyAvailable: Boolean = true): ArticleSyncManifestEntry {
        val metadata = ArticleSyncBody.cachedMetadataFor(this)
        return ArticleSyncManifestEntry(
            articleId = articleId,
            sourceDeviceId = sourceDeviceId,
            contentHash = contentHash,
            updatedAt = updatedAt,
            independentChangedAt = independentChangedAt,
            favoriteChangedAt = favoriteChangedAt,
            watchLaterChangedAt = watchLaterChangedAt,
            deletedAt = deletedAt,
            bodyHash = metadata?.bodyHash ?: syncBodyHash.ifBlank { contentHash },
            bodyByteCount = metadata?.bodyByteCount ?: syncBodyByteCount,
            chunkSize = metadata?.chunkSize ?: syncChunkSize,
            chunkHashes = metadata?.chunkHashes ?: syncChunkHashesJson.toStringList(),
            metadataHash = metadata?.metadataHash ?: syncMetadataHash.ifBlank {
                ArticleSyncBody.metadataHashFor(this)
            },
            bodyAvailable = bodyAvailable,
            bodySyncMode = bodySyncModeForSync(),
            readingProgress = readingProgress,
            readingPositionBytes = readingPositionBytes,
            readingPositionContentHash = readingPositionContentHash,
            readingPositionChangedAt = readingPositionChangedAt,
            isRead = isRead
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

    private fun PhoneArticleEntity.bodySyncModeForSync(): String {
        return if (
            independentSaved ||
            ImportedContentIds.isImportedContentUrl(url) ||
            ImportedContentIds.isImportedContentUrl(rssSourceUrl)
        ) {
            ARTICLE_BODY_SYNC_MODE_FULL
        } else {
            ARTICLE_BODY_SYNC_MODE_SAVED
        }
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

    private suspend fun PhoneArticleEntity.migrateLegacyReadingPositionIfNeeded(): PhoneArticleEntity {
        if (readingPositionBytes > 0L || readingProgress <= 0f) return this
        val byteLength = if (isFileBackedImportedText()) {
            articleContentStore
                ?.textChunkHandle(contentText, ARTICLE_TEXT_CHUNK_BYTES)
                ?.byteLength
                ?: 0L
        } else {
            hydrateExternalText().contentText.toByteArray(Charsets.UTF_8).size.toLong()
        }
        if (byteLength <= 0L) return this
        val positionBytes = (byteLength.toDouble() * readingProgress.toDouble())
            .roundToLong()
            .coerceIn(0L, byteLength)
        articleDao.updateReadingProgress(
            articleId = articleId,
            progress = readingProgress,
            positionBytes = positionBytes,
            positionContentHash = contentHash,
            positionChangedAt = 0L
        )
        recordArticleChange(articleId, "readingPositionMigration")
        return copy(
            readingPositionBytes = positionBytes,
            readingPositionContentHash = contentHash
        )
    }

    private fun PhoneArticleEntity.hydrateExternalTextForBackup(): PhoneArticleEntity {
        val store = articleContentStore ?: return this
        val displayTitle = title.ifBlank { articleId }
        val html = contentHtml?.let { value ->
            if (store.isMarker(value)) {
                store.loadText(value) ?: error("备份失败：文章“$displayTitle”的 HTML 正文文件缺失")
            } else {
                value
            }
        }
        val text = if (store.isMarker(contentText)) {
            store.loadText(contentText) ?: error("备份失败：文章“$displayTitle”的正文文件缺失")
        } else {
            contentText
        }
        return copy(contentHtml = html, contentText = text)
    }

    private fun PhoneArticleEntity.normalizeBackupArticle(): PhoneArticleEntity {
        return copy(
            sourceDeviceId = deviceId,
            syncBodyHash = "",
            syncBodyByteCount = 0L,
            syncChunkSize = 0,
            syncChunkHashesJson = "",
            syncMetadataHash = "",
            readingProgress = readingProgress.coerceIn(0f, 1f)
        )
    }

    private fun PhoneArticleEntity.isFileBackedImportedText(): Boolean {
        val store = articleContentStore ?: return false
        return isFileBackedImportedText(store)
    }

    private fun PhoneArticleEntity.isFileBackedImportedText(store: ArticleContentStore): Boolean {
        return ImportedContentIds.isImportedTextArticleUrl(url) && store.isMarker(contentText)
    }

    private fun PhoneArticleEntity.hydrateExternalTextForSync(): SyncHydratedArticle {
        val store = articleContentStore ?: return SyncHydratedArticle(this, bodyAvailable = true)
        var bodyAvailable = true
        val html = contentHtml?.let { value ->
            if (store.isMarker(value)) {
                store.loadText(value) ?: run {
                    bodyAvailable = false
                    value
                }
            } else {
                value
            }
        }
        val text = if (store.isMarker(contentText)) {
            store.loadText(contentText) ?: run {
                bodyAvailable = false
                contentText
            }
        } else {
            contentText
        }
        return SyncHydratedArticle(copy(contentHtml = html, contentText = text), bodyAvailable)
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

    private fun classifyTxtUpdate(
        oldText: String,
        newText: String,
        sameContent: Boolean
    ): TxtUpdateRelation {
        if (sameContent || oldText == newText) return TxtUpdateRelation.IDENTICAL
        val oldParagraphs = normalizedTxtParagraphs(oldText)
        val newParagraphs = normalizedTxtParagraphs(newText)
        return when {
            oldParagraphs.isNotEmpty() &&
                newParagraphs.size > oldParagraphs.size &&
                newParagraphs.subList(0, oldParagraphs.size) == oldParagraphs ->
                TxtUpdateRelation.APPEND_ONLY
            newParagraphs.isNotEmpty() &&
                oldParagraphs.size > newParagraphs.size &&
                oldParagraphs.subList(0, newParagraphs.size) == newParagraphs ->
                TxtUpdateRelation.OLDER_VERSION
            else -> TxtUpdateRelation.POSSIBLE_REVISION
        }
    }

    private fun normalizedTxtParagraphs(text: String): List<String> =
        text.lineSequence()
            .map { line ->
                Normalizer.normalize(line, Normalizer.Form.NFKC)
                    .replace(Regex("""\s+"""), " ")
                    .trim()
            }
            .filter { it.isNotBlank() }
            .toList()

    private fun importedTxtNameSimilarity(existingTitle: String, newTitle: String): Float {
        val left = normalizedTxtName(existingTitle)
        val right = normalizedTxtName(newTitle)
        if (left.isBlank() || right.isBlank()) return 0f
        if (left == right) return 1f
        if ((left.contains(right) || right.contains(left)) && minOf(left.length, right.length) >= 3) {
            return 0.9f
        }
        val leftPairs = left.windowed(2, 1, partialWindows = true).toSet()
        val rightPairs = right.windowed(2, 1, partialWindows = true).toSet()
        if (leftPairs.isEmpty() || rightPairs.isEmpty()) return 0f
        return (2f * leftPairs.intersect(rightPairs).size) /
            (leftPairs.size + rightPairs.size).toFloat()
    }

    private fun normalizedTxtName(value: String): String {
        var normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .substringBeforeLast('.', value)
            .lowercase()
            .trim()
        val suffixPatterns = listOf(
            Regex("""(?:更新至|更新到|截至|共)?\s*\d+\s*(?:[-~至到]\s*\d+\s*)?(?:章|回|节).*$"""),
            Regex("""(?:完整版|完结版|全本|精校版|修订版|最新版)$"""),
            Regex("""(?:副本|copy)\s*\d*$"""),
            Regex("""[\[(（]\s*\d+\s*[\])）]$"""),
            Regex("""(?:19|20)\d{2}[-_.年]\d{1,2}(?:[-_.月]\d{1,2}日?)?$""")
        )
        var changed: Boolean
        do {
            val before = normalized
            suffixPatterns.forEach { normalized = normalized.replace(it, "") }
            normalized = normalized.trim()
            changed = normalized != before
        } while (changed && normalized.isNotBlank())
        return normalized.replace(Regex("""[^\p{L}\p{N}]"""), "")
    }

    private data class MappedTxtPosition(
        val positionBytes: Long,
        val approximate: Boolean
    )

    private fun mapTxtReadingPosition(
        oldText: String,
        newText: String,
        oldPositionBytes: Long,
        appendOnly: Boolean
    ): MappedTxtPosition {
        val newByteCount = newText.toByteArray(Charsets.UTF_8).size.toLong()
        val clampedOldPosition = oldPositionBytes.coerceAtLeast(0L)
        if (appendOnly && newText.startsWith(oldText)) {
            return MappedTxtPosition(
                positionBytes = clampedOldPosition.coerceAtMost(newByteCount),
                approximate = false
            )
        }
        val oldCharIndex = utf8CharIndexAtByte(oldText, clampedOldPosition)
        val anchorStart = (oldCharIndex - TXT_POSITION_ANCHOR_RADIUS).coerceAtLeast(0)
        val anchorEnd = (oldCharIndex + TXT_POSITION_ANCHOR_RADIUS).coerceAtMost(oldText.length)
        val anchor = oldText.substring(anchorStart, anchorEnd).trim()
        if (anchor.length >= MIN_TXT_POSITION_ANCHOR_CHARS) {
            val first = newText.indexOf(anchor)
            if (first >= 0 && first == newText.lastIndexOf(anchor)) {
                val mappedCharIndex = (first + oldCharIndex - anchorStart)
                    .coerceIn(0, newText.length)
                return MappedTxtPosition(
                    positionBytes = newText.substring(0, mappedCharIndex)
                        .toByteArray(Charsets.UTF_8)
                        .size
                        .toLong(),
                    approximate = false
                )
            }
        }
        return MappedTxtPosition(
            positionBytes = clampedOldPosition.coerceAtMost(newByteCount),
            approximate = true
        )
    }

    private fun utf8CharIndexAtByte(text: String, byteOffset: Long): Int {
        val target = byteOffset.coerceAtLeast(0L)
        var low = 0
        var high = text.length
        while (low < high) {
            val middle = (low + high + 1) ushr 1
            val bytes = text.substring(0, middle).toByteArray(Charsets.UTF_8).size.toLong()
            if (bytes <= target) low = middle else high = middle - 1
        }
        return low
    }

    companion object {
        private const val CHANGE_SEQUENCE_PROTOCOL_VERSION = 13
        private const val DEFAULT_LIBRARY_PEER_ID = "watch"
        private const val FULL_SNAPSHOT_INTERVAL_MS = 7L * 24L * 60L * 60L * 1000L
        private const val SYNC_KIND_ARTICLE = "article"
        private const val SYNC_KIND_RSS_SOURCE = "rssSource"
        private const val MAX_INLINE_CONTENT_CHARS = 100_000
        private const val MAX_REPAIRED_TITLE_CHARS = 80
        private const val MIN_HTML_TOC_LINKS = 3
        private const val MIN_TXT_NAME_SIMILARITY = 0.48f
        private const val TXT_POSITION_ANCHOR_RADIUS = 96
        private const val MIN_TXT_POSITION_ANCHOR_CHARS = 48
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

internal fun requireMetadataOnlyLocalBody(
    payload: ChunkedArticlePayload,
    localArticle: PhoneArticleEntity?
): ArticleBodyMetadata {
    require(payload.metadataOnly) { "同步正文请求不是仅元数据模式：${payload.article.articleId}" }
    require(payload.chunks.isEmpty()) { "仅元数据同步包含正文分块：${payload.article.articleId}" }
    require(localArticle != null) { "仅元数据同步缺少可复用的本地正文：${payload.article.articleId}" }
    val actual = ArticleSyncBody.metadataFor(localArticle)
    require(payload.bodyHash == actual.bodyHash) {
        "同步正文仅元数据响应与本地正文不匹配：${payload.article.articleId}"
    }
    require(payload.bodyByteCount == actual.bodyByteCount) {
        "同步正文仅元数据响应长度不匹配：${payload.article.articleId}"
    }
    require(payload.chunkSize == actual.chunkSize && payload.chunkHashes == actual.chunkHashes) {
        "同步正文仅元数据响应分块清单不匹配：${payload.article.articleId}"
    }
    return actual.copy(metadataHash = ArticleSyncBody.metadataHashFor(payload.article))
}

internal fun ChunkedArticlePayload.isExplicitUnavailableTombstone(): Boolean {
    return article.deleted &&
        !metadataOnly &&
        bodyHash.isBlank() &&
        bodyByteCount == 0L &&
        chunkSize == 0 &&
        chunkHashes.isEmpty() &&
        chunks.isEmpty()
}
