package com.lightningstudio.watchrss.phone.data.repo

import com.lightningstudio.watchrss.phone.cloud.compactCloudExcerpt
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
import com.lightningstudio.watchrss.phone.data.model.canRecoverSavedRssBodyFrom
import com.lightningstudio.watchrss.phone.data.model.isMissingSavedRssBody
import com.lightningstudio.watchrss.phone.data.model.hasSameSavedRssIdentityAndClock
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
import org.json.JSONObject
import java.lang.Long.max
import java.net.URI
import java.text.Normalizer
import kotlin.math.roundToLong

data class PhoneRssSourceImportResult(
    val source: PhoneRssSourceEntity,
    val articleCount: Int,
    val skippedArticleCount: Int = 0
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
    val fallbackReason: String,
    val projectionDefinition:String? = null
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
    override suspend fun lastConflictResolution(articleId: String): Long? = null
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
    private val appMetaDao: AppMetaDao = NoopAppMetaDao,
    private val cloudBackendBaseUrl: String = "",
    val books:com.lightningstudio.watchrss.phone.data.book.ImportedBookStore?=null,
    internal val bleDomains:com.lightningstudio.watchrss.phone.data.ble.BleDomainStore?=null,
    internal var articleProvenance:ArticleProvenanceStore?=null
) {
    suspend fun articleRegisterReadState(id:String):ArticleProvenanceReadState?=articleProvenance?.state(id)
    fun observeArticleProvenancePendingCount():Flow<Int> = articleProvenance?.observePendingCount()?:flowOf(0)
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

    private fun publicRssSourceMappingKey(url: String) = "public_rss_source:" +
        com.lightningstudio.watchrss.phone.cloud.CloudSnapshotCodec.sha256(cloudBackendBaseUrl.trimEnd('/').toByteArray()) + ":" + url

    suspend fun publicRssSourceId(url: String): String? = appMetaDao.getString(publicRssSourceMappingKey(url))
    suspend fun rememberPublicRssSource(url: String, id: String) {
        require(id.isNotBlank())
        appMetaDao.set(AppMetaEntity(publicRssSourceMappingKey(url), id))
    }

    private fun rssIdentityAliasPrefix() = "rss_identity_alias:" +
        com.lightningstudio.watchrss.phone.cloud.CloudSnapshotCodec.sha256(cloudBackendBaseUrl.trimEnd('/').toByteArray()) + ":"

    internal suspend fun canonicalRssArticleId(id: String): String = appMetaDao.getString(rssIdentityAliasPrefix()+id) ?: id
    internal suspend fun legacyRssArticleIds(): Map<String,List<String>> = appMetaDao.getAll()
        .filter { it.key.startsWith(rssIdentityAliasPrefix()) }
        .groupBy({ it.value },{ it.key.removePrefix(rssIdentityAliasPrefix()) })

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

    suspend fun compactCloudExcerpts(checkOwner: () -> Unit): Int = withContext(Dispatchers.IO) {
        var changed = 0
        for (row in articleDao.longCloudExcerpts()) {
            if(articleProvenance?.protected(row.articleId)==true)continue
            val compact = row.compactCloudExcerpt(System.currentTimeMillis(), deviceId).externalizeLargeLocalContent()
            checkOwner()
            changed += articleDao.compactCloudExcerpt(row.articleId, row.contentHash, row.excerpt, row.contentText,
                row.contentHtml, compact.excerpt, compact.contentText, compact.contentHtml, compact.updatedAt, deviceId)
        }
        changed
    }

    internal suspend fun privateCloudVersions(layoutV3: Boolean = false): Map<String,String> = withContext(Dispatchers.IO) {
        val excluded=books?.chapterIds().orEmpty()
        val versions = mutableMapOf<String,String>()
        var after = ""
        while (true) {
            val page = articleDao.privateCloudBodyPage(after,25)
            if (page.isEmpty()) break
            for (raw in page) {
                if(raw.articleId in excluded)continue
                val row = raw.hydrateExternalTextForBackup()
                versions[row.articleId] = com.lightningstudio.watchrss.phone.cloud.CloudArchiveReuse.version(row.contentHash,row.deleted,row.contentText,row.contentHtml) + if (layoutV3) ":" + row.importedAt + ":" + com.lightningstudio.watchrss.phone.cloud.CloudSnapshotCodec.sha256((row.url + "\n" + row.title + "\n" + row.rssSourceUrl + "\n" + listOf(row.independentSaved,row.favoriteSaved,row.watchLaterSaved).joinToString(":" )).toByteArray()) else ""
            }
            after = page.last().articleId
        }
        versions
    }

    suspend fun missingCloudBodyIds(): List<String> = articleDao.missingPrivateBodyIds()

    suspend fun fillMissingCloudBody(article: PhoneArticleEntity, text: String, checkOwner: () -> Unit) =
        withContext(Dispatchers.IO) {
            checkOwner()
            val prepared = article.copy(contentText = text, contentHtml = null).externalizeLargeLocalContent()
            checkOwner()
            articleDao.fillMissingCloudBody(article.articleId, article.contentHash, prepared.contentText, prepared.contentHtml, article.contentText)
        }

    suspend fun getRssSource(sourceUrl: String): PhoneRssSourceEntity? =
        withContext(Dispatchers.IO) {
            rssSourceDao.getByUrl(sourceUrl)
        }

    internal suspend fun getPrivateArticlesForCloudArchive(): List<PhoneArticleEntity> = withContext(Dispatchers.IO) {
        val excluded=books?.chapterIds().orEmpty()
        val articles = mutableListOf<PhoneArticleEntity>()
        var after = ""
        while (true) {
            val page = articleDao.privateCloudBodyPage(after,25)
            if (page.isEmpty()) break
            articles += page.filterNot {it.articleId in excluded}.map { it.hydrateExternalTextForBackup() }
            after = page.last().articleId
        }
        articles
    }

    suspend fun getArticlesForBackup(): List<PhoneArticleEntity> =
        withContext(Dispatchers.IO) {
            articleProvenance?.requireExportable()
            articleDao.getAllForSync().map { it.hydrateExternalTextForBackup() }
        }

    suspend fun replaceArticlesFromBackup(incoming: List<PhoneArticleEntity>): Int =
        withContext(Dispatchers.IO) {
            articleProvenance?.requireExportable()
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
                if(articleProvenance?.protected(rawBackup.articleId)==true) {
                    if(articleProvenance?.materializeOriginal(rawBackup)==true)merged++
                    return@forEach
                }
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
                        .withCurrentSyncMetadata(local?.syncBodyHash.orEmpty())
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
            articleProvenance?.originalReferences().orEmpty().filter(store::isMarker).forEach(::add)
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

    suspend fun markArticleRead(articleId: String, read: Boolean) = withContext(Dispatchers.IO) {
        articleProvenance?.guardEdit(articleId,JSONObject().put("operation","reading").put("isRead",read))
        val article = articleDao.getById(articleId) ?: return@withContext
        val at = maxOf(System.currentTimeMillis(), article.readingPositionChangedAt + 1)
        articleDao.upsert(article.copy(sourceDeviceId = deviceId, isRead = read, readingPositionChangedAt = at))
        recordArticleChange(articleId, "readingState", at)
    }

    suspend fun updateArticleReadingProgress(articleId: String, progress: Float) = withContext(Dispatchers.IO) {
        if(articleProvenance?.stageReadingProgress(articleId,progress.coerceIn(0f,1f).toDouble())==true)return@withContext
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
            books?.ensureRegistered()
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
        books?.ensureRegistered()
        val replacingBook=replaceArticleId?.let { books?.bookForArticle(it) }
        if(replacingBook!=null)deleteArticle(requireNotNull(replaceArticleId))
        if (replaceArticleId == null || replacingBook!=null || inspection.imported.kind != LocalContentImportKind.TXT) {
            val result = saveImportedSource(
                imported = inspection.imported.source,
                replaceExistingArticles = inspection.imported.kind != LocalContentImportKind.TXT
            )
            books?.ensureRegistered()
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
            articleProvenance?.guardEdit(article.articleId,JSONObject().put("operation","toggleSaved").put("type",type.name))
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
            articleProvenance?.guardEdit(article.articleId,JSONObject().put("operation","reorderIndependent").put("sortOrder",rowSortOrder-index))
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
        articles.forEach {articleProvenance?.guardEdit(it.articleId,JSONObject().put("operation","clearSourceContent").put("url",sourceUrl))}
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
        articleDao.getByRssSourceUrl(sourceUrl).forEach { articleProvenance?.guardEdit(it.articleId,JSONObject().put("operation","deleteSource").put("url",sourceUrl)) }
        books?.ensureRegistered()
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
        articleProvenance?.guardEdit(articleId,JSONObject().put("operation","delete"))
        books?.ensureRegistered()
        val current = articleDao.getById(articleId) ?: return@withContext
        val now = System.currentTimeMillis()
        val mapping=books?.bookForArticle(articleId)
        val deleted = current.markDeletedForStorage(now)
        articleDao.upsert(deleted)
        recordArticleChange(deleted.articleId, "delete", now)
        if(mapping!=null && ImportedContentIds.isImportedTextArticleUrl(current.url))books?.markDeleted(mapping.bookId,now)
    }

    suspend fun clearImportedContent(): Int = withContext(Dispatchers.IO) {
        articleDao.getByRssSourceUrl(ImportedContentIds.ROOT_SOURCE_URL).forEach { articleProvenance?.guardEdit(it.articleId,JSONObject().put("operation","clearImportedContent")) }
        books?.ensureRegistered()
        val importedArticles = articleDao.getByRssSourceUrl(ImportedContentIds.ROOT_SOURCE_URL)
            .filterNot { it.deleted }
        val now = System.currentTimeMillis()
        importedArticles.forEachIndexed { index, article ->
            val deleted = article.markDeletedForStorage(now + index)
            articleDao.upsert(deleted)
            recordArticleChange(deleted.articleId, "delete", now + index)
            books?.bookForArticle(article.articleId)?.let{books?.markDeleted(it.bookId,now+index)}
        }
        importedArticles.size
    }

    internal suspend fun bluetoothIdentityModeChanged(peer:String,canonical:Boolean):Boolean =
        appMetaDao.getString("ble_identity_mode:"+WebArticleImporter.sha256(peer)) != canonical.toString()

    private val bleIdentityMigrationKey="ble_rss_identity_dedup_v2"
    internal suspend fun bluetoothIdentityProjection(canonical:Boolean):com.lightningstudio.watchrss.phone.connection.bluetooth.BluetoothArticleIdentityProjection {
        val aliases=legacyRssArticleIds().flatMap { (id,old)->old.map { it to id } }.toMap()
        val indexed=if(canonical)bleDomains?.identities() else null
        return com.lightningstudio.watchrss.phone.connection.bluetooth.BluetoothArticleIdentityProjection(indexed?.rows?:articleDao.getAllMetadataForSync(),aliases,canonical,indexed?.legacy.orEmpty())
    }

    /** Retire only a provable URL-hash alias. Distinct real guid IDs sharing a URL stay distinct. */
    internal suspend fun repairBluetoothRssDuplicates():Int=withContext(Dispatchers.IO) {
        if(appMetaDao.getString(bleIdentityMigrationKey)=="done")return@withContext 0
        var changed=0
        val groups=articleDao.getAllMetadataForSync().filter { !it.rssSourceUrl.isNullOrBlank() &&
            !ImportedContentIds.isImportedContentUrl(it.rssSourceUrl) && !ImportedContentIds.isImportedContentUrl(it.url) }
            .groupBy { it.rssSourceUrl to it.url }
        for((_,rows) in groups) {
            if(rows.size<2)continue
            val legacy=WebArticleImporter.stableArticleId(rows.first().url)
            val candidates=rows.filter { it.articleId!=legacy }
            if(candidates.size!=1)continue
            val old=rows.firstOrNull { it.articleId==legacy }?:continue
            migrateBluetoothAlias(old.articleId,candidates.single().articleId);changed++
        }
        appMetaDao.set(AppMetaEntity(bleIdentityMigrationKey,"done"));changed
    }

    internal suspend fun acceptBluetoothAliases(manifest:List<ArticleSyncManifestEntry>):Map<String,String> {
        val migrated=linkedMapOf<String,String>()
        val owners=manifest.flatMap { entry -> entry.legacyArticleIds.distinct().map { it to entry.articleId } }.groupBy({it.first},{it.second})
        require(owners.values.none { it.distinct().size>1 }) { "RSS身份别名指向多个条目" }
        for(entry in manifest)for(old in entry.legacyArticleIds.distinct()) {
            require(entry.articleId.matches(Regex("[0-9a-f]{64}")) && old.matches(Regex("[0-9a-f]{64}"))) { "RSS身份别名无效" }
            if(old==entry.articleId)continue
            val local=articleDao.getById(old)?:continue
            if(local.rssSourceUrl.isNullOrBlank() || ImportedContentIds.isImportedContentUrl(local.rssSourceUrl) ||
                WebArticleImporter.stableArticleId(local.url)!=old)continue
            migrateBluetoothAlias(old,entry.articleId)
            migrated[old]=entry.articleId
        }
        return migrated
    }

    private suspend fun migrateBluetoothAlias(oldId:String,id:String) {
        articleProvenance?.requireExportable(oldId)
        articleProvenance?.requireExportable(id)
        val old=articleDao.getById(oldId)?.hydrateExternalText()?:return
        val current=articleDao.getById(id)?.hydrateExternalText()
        require(current==null || current.url==old.url && current.rssSourceUrl==old.rssSourceUrl) { "RSS别名指向不同条目" }
        val rekeyed=old.copy(articleId=id,syncMetadataHash="")
        val merged=(if(current==null)rekeyed else mergeArticleByLatest(rekeyed,current)).withCurrentSyncMetadata()
        articleDao.upsertRssIdentityRows(listOf(merged.externalizeLargeLocalContent()),listOf(oldId))
        appMetaDao.set(AppMetaEntity(rssIdentityAliasPrefix()+oldId,id))
        appMetaDao.getString(conflictDecisionKey(oldId))?.let { receipt ->
            val oldClock = runCatching { JSONObject(receipt).optLong("clock") }.getOrDefault(0L)
            val currentClock = conflictDecisionClock(id) ?: 0L
            if (oldClock > currentClock) appMetaDao.set(AppMetaEntity(conflictDecisionKey(id), receipt))
        }
        recordArticleChange(id,"bluetoothIdentityMigration",merged.latestOperationAt())
        if (merged.sourceDeviceId == deviceId && syncChangeLogDao.lastConflictResolution(oldId) == merged.latestOperationAt()) {
            recordArticleChange(id, "conflictResolution", merged.latestOperationAt())
        }
    }

    private fun echoPrefix(peer:String)="ble_echo_v2:"+WebArticleImporter.sha256(peer)+":"
    private fun articleSignature(article:PhoneArticleEntity)=WebArticleImporter.sha256(listOf(ArticleSyncBody.metadataHashFor(article),article.syncBodyHash,
        article.syncBodyByteCount,article.syncChunkSize,article.syncChunkHashesJson,article.readingPositionChangedAt,article.readingProgress,
        article.isRead,article.readingPositionBytes,article.readingPositionContentHash).joinToString("\n"))
    internal suspend fun rememberBluetoothReceipts(peer:String,manifest:List<ArticleSyncManifestEntry>) {
        val rows=articleMetadataForSync(manifest.mapTo(hashSetOf()){it.articleId}).associateBy{it.articleId}
        for(entry in manifest) {
            val local=rows[entry.articleId]?:continue
            if(local.sourceDeviceId!=peer)continue
            // A conflicting merge cannot be acknowledged as an exact replica of the sender.
            if(entry.metadataHash.isBlank() || local.syncMetadataHash!=entry.metadataHash || local.readingProgress!=entry.readingProgress ||
                local.readingPositionBytes!=entry.readingPositionBytes || local.readingPositionContentHash!=entry.readingPositionContentHash ||
                local.contentHash!=entry.contentHash || local.updatedAt!=entry.updatedAt || local.favoriteChangedAt!=entry.favoriteChangedAt ||
                local.watchLaterChangedAt!=entry.watchLaterChangedAt || local.independentChangedAt!=entry.independentChangedAt || local.deleted!=entry.deleted ||
                local.readingPositionChangedAt!=entry.readingPositionChangedAt || local.isRead!=entry.isRead || local.syncBodyHash!=entry.bodyHash ||
                local.syncBodyByteCount!=entry.bodyByteCount || local.syncChunkSize!=entry.chunkSize || local.syncChunkHashesJson.toStringList()!=entry.chunkHashes)continue
            val key=echoPrefix(peer)+local.articleId;val signature=articleSignature(local)
            if(appMetaDao.getString(key)!=signature)appMetaDao.set(AppMetaEntity(key,signature))
        }
    }

    private suspend fun suppressPeerEcho(peer:String,entries:List<ArticleSyncManifestEntry>):List<ArticleSyncManifestEntry> {
        val retained=mutableListOf<ArticleSyncManifestEntry>()
        val rows=articleMetadataForSync(entries.mapTo(hashSetOf()){it.articleId}).associateBy{it.articleId}
        for(entry in entries) {
            val local=rows[entry.articleId]
            if(local==null || local.sourceDeviceId!=peer || appMetaDao.getString(echoPrefix(peer)+entry.articleId)!=articleSignature(local))retained+=entry
        }
        return retained
    }

    suspend fun getArticlesForSync(): List<PhoneArticleEntity> = withContext(Dispatchers.IO) {
        articleDao.getAllMetadataForSync()
            .filter { it.shouldSyncThroughLibrary() }
            .mapNotNull { it.articleForBodyExport() }
    }

    /** Only dirty entities are hydrated/hashed. Cache publication cannot overtake a business write. */
    private suspend fun cachedBluetoothManifests(ids:Collection<String>?=null):List<ArticleSyncManifestEntry> {
        val cache=requireNotNull(bleDomains)
        val definition=cache.libraryDefinition()
        val selected=ids?.toSet()
        for((id,seq,bodyDirty) in cache.pending().filter{selected==null||it.first in selected}) {
            val row=if(cache.mapped(id))null else articleMetadataForSync(setOf(id)).firstOrNull()
            val previous=if(!bodyDirty)cache.manifests(listOf(id)).firstOrNull() else null
            val entry=row?.takeIf {it.shouldSyncThroughLibrary()}?.let {
                if(previous!=null && previous.bodyAvailable && previous.contentHash==it.contentHash) {
                    val hash=ArticleSyncBody.metadataHashFor(it)
                    articleDao.updateSyncMetadata(it.copy(syncBodyHash=previous.bodyHash,syncBodyByteCount=previous.bodyByteCount,syncChunkSize=previous.chunkSize,syncChunkHashesJson=previous.chunkHashes.toJsonString(),syncMetadataHash=hash))
                    previous.copy(sourceDeviceId=it.sourceDeviceId,contentHash=it.contentHash,updatedAt=it.updatedAt,
                        independentChangedAt=it.independentChangedAt,favoriteChangedAt=it.favoriteChangedAt,watchLaterChangedAt=it.watchLaterChangedAt,
                        deletedAt=it.deletedAt,deleted=it.deleted,metadataHash=hash,bodySyncMode=it.bodySyncModeForSync(),
                        readingProgress=it.readingProgress,readingPositionBytes=it.readingPositionBytes,readingPositionContentHash=it.readingPositionContentHash,readingPositionChangedAt=it.readingPositionChangedAt,isRead=it.isRead)
                } else it.syncManifestEntryForExport()
            }
            if(cache.publishManifest(id,seq,entry,row,definition) && entry!=null && seq>0L) {
                // SQL-observed changes can have equal clocks (explicit unread/cancel), or bypass the repository.
                recordArticleChange(id,"bleProjection",entry.latestOperationAt())
            }
        }
        return cache.manifests(ids)
    }

    internal suspend fun bluetoothManifestsForRequests(ids:Collection<String>,witness:com.lightningstudio.watchrss.phone.data.ble.BleDomainStore.RootWindow?=null):List<ArticleSyncManifestEntry> = withContext(Dispatchers.IO) {
        if(ids.isEmpty())return@withContext emptyList()
        val cache=bleDomains?:return@withContext getArticleManifestsForSync(ids)
        // Recheck existence witnesses after the remote round trip; never reuse a body removed meanwhile.
        cache.refreshBodyInventory(ids,witness)
        cache.frozenWindow {cachedBluetoothManifests(ids)}
    }

    suspend fun getArticleManifestsForSync(): List<ArticleSyncManifestEntry> = withContext(Dispatchers.IO) {
        articleDao.getAllMetadataForSync()
            .filter { it.shouldSyncThroughLibrary() }
            .map { it.syncManifestEntryForExport() }
    }

    suspend fun getArticleManifestsForSync(articleIds: Collection<String>): List<ArticleSyncManifestEntry> {
        val idSet = articleIds.toSet()
        if (idSet.isEmpty()) return emptyList()
        return articleMetadataForSync(idSet)
            .filter { it.shouldSyncThroughLibrary() }
            .map { it.syncManifestEntryForExport() }
    }

    suspend fun getArticlesForSync(articleIds: Collection<String>): List<PhoneArticleEntity> =
        withContext(Dispatchers.IO) {
            val idSet = articleIds.toSet()
            if (idSet.isEmpty()) return@withContext emptyList()
            articleMetadataForSync(idSet)
                .mapNotNull { it.articleForBodyExport() }
        }

    private suspend fun articleMetadataForSync(articleIds: Set<String>): List<PhoneArticleEntity> {
        return articleIds
            .chunked(SYNC_ID_QUERY_BATCH_SIZE)
            .flatMap { articleDao.getMetadataForSync(it) }
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

    /** The fast path must never bypass old cursor/protocol/periodic-full admission. */
    internal suspend fun libraryFastPathAllowed(peer:String,time:Long=System.currentTimeMillis()):Boolean {
        if(articleProvenance?.anyProtected()==true)return false
        val state=syncPeerStateDao.get(peer) ?: return false
        return state.lastProtocolVersion>=CHANGE_SEQUENCE_PROTOCOL_VERSION && state.lastFullSyncAt>0 &&
            time>=state.lastFullSyncAt && time-state.lastFullSyncAt<FULL_SNAPSHOT_INTERVAL_MS &&
            bleDomains?.libraryBaselineMatches(peer)!=false && !bluetoothIdentityModeChanged(peer,true)
    }

    suspend fun prepareLibrarySyncWindow(
        peerDeviceId:String,peerAppliedLocalSeq:Long?=null,excludeRegisteredBooks:Boolean=false
    ):PhoneLibrarySyncWindow=withContext(Dispatchers.IO) {
        articleProvenance?.requireExportable()
        if(excludeRegisteredBooks && bleDomains!=null)bleDomains.frozenWindow {
            prepareLibrarySyncWindowFrozen(peerDeviceId,peerAppliedLocalSeq,true)
        } else prepareLibrarySyncWindowFrozen(peerDeviceId,peerAppliedLocalSeq,false)
    }

    private suspend fun prepareLibrarySyncWindowFrozen(
        peerDeviceId:String,peerAppliedLocalSeq:Long?,excludeRegisteredBooks:Boolean
    ):PhoneLibrarySyncWindow {
            val normalizedPeerId = peerDeviceId.ifBlank { DEFAULT_LIBRARY_PEER_ID }
            val peerState = syncPeerStateDao.get(normalizedPeerId)
            val now = System.currentTimeMillis()
            val projectionDefinition=bleDomains?.libraryDefinition()
            val fullArticleManifest = if(excludeRegisteredBooks && bleDomains!=null)cachedBluetoothManifests() else getArticleManifestsForSync()
            val repairKey="ble_repair_watermark_v2"
            val watermark=appMetaDao.getString(repairKey)?.toLongOrNull()?:0L
            val scanFull=peerState==null || peerState.lastFullSyncAt<=0 || now-peerState.lastFullSyncAt>=FULL_SNAPSHOT_INTERVAL_MS || peerAppliedLocalSeq==0L
            val repairCandidates=if(scanFull)fullArticleManifest else fullArticleManifest.filter { it.updatedAt>watermark }
            repairMissingArticleChangeLogEntries(repairCandidates)
            val nextWatermark=maxOf(watermark,fullArticleManifest.maxOfOrNull { it.updatedAt }?:0L)
            if(nextWatermark!=watermark)appMetaDao.set(AppMetaEntity(repairKey,nextWatermark.toString()))
            val maxSeq = syncChangeLogDao.maxSeq()
            val cachedPeerAckedSeq = peerState?.lastLocalSeqAckedByPeer ?: 0L
            val peerAckedSeq = peerAppliedLocalSeq?.coerceAtLeast(0L) ?: cachedPeerAckedSeq
            val fullSnapshotReason = when {
                peerState == null -> "newPeer"
                peerState.lastProtocolVersion < CHANGE_SEQUENCE_PROTOCOL_VERSION -> "peerProtocol"
                peerAppliedLocalSeq != null && peerAckedSeq < cachedPeerAckedSeq -> "peerCursorBehind"
                peerAppliedLocalSeq != null && peerAckedSeq > maxSeq -> "peerCursorAhead"
                peerState.lastFullSyncAt <= 0L -> "noFullSnapshot"
                now < peerState.lastFullSyncAt || now - peerState.lastFullSyncAt >= FULL_SNAPSHOT_INTERVAL_MS -> "periodicFull"
                projectionDefinition!=null && !bleDomains!!.libraryBaselineMatches(normalizedPeerId) -> "projectionChanged"
                else -> ""
            }
            if (fullSnapshotReason.isNotBlank()) {
                return PhoneLibrarySyncWindow(
                    articleManifest = fullArticleManifest,
                    fullArticleManifest = fullArticleManifest,
                    rssSources = getRssSourcesForSync(),
                    fullSnapshot = true,
                    fromSeqExclusive = 0L,
                    toSeqInclusive = maxSeq,
                    peerAckedSeq = peerAckedSeq,
                    fallbackReason = fullSnapshotReason,
                    projectionDefinition = projectionDefinition
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
            return PhoneLibrarySyncWindow(
                articleManifest = suppressPeerEcho(normalizedPeerId,if(excludeRegisteredBooks && bleDomains!=null)bleDomains.manifests(changedArticleIds) else getArticleManifestsForSync(changedArticleIds)),
                fullArticleManifest = fullArticleManifest,
                rssSources = getRssSourcesForSync(changedSourceUrls),
                fullSnapshot = false,
                fromSeqExclusive = peerAckedSeq,
                toSeqInclusive = maxSeq,
                peerAckedSeq = peerAckedSeq,
                fallbackReason = "",
                projectionDefinition = projectionDefinition
            )
        }

    suspend fun markLibrarySyncSuccess(
        peerDeviceId: String,
        localSeqToInclusive: Long,
        remoteSeqToInclusive: Long,
        remoteProtocolVersion: Int,
        fullSnapshot: Boolean,
        canonicalIds: Boolean? = null,
        projectionDefinition:String?=null
    ) = withContext(Dispatchers.IO) {
        canonicalIds?.let {
            val key="ble_identity_mode:"+WebArticleImporter.sha256(peerDeviceId)
            if(appMetaDao.getString(key)!=it.toString())appMetaDao.set(AppMetaEntity(key,it.toString()))
        }
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
        if(fullSnapshot && projectionDefinition!=null)bleDomains?.acknowledgeLibraryDefinition(normalizedPeerId,projectionDefinition)
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

    private var repairedBluetoothLibraryVersion:String?=null
    suspend fun repairImportedContentSourceStates(): Int = withContext(Dispatchers.IO) {
        val revision=bleDomains?.libraryVersion()
        if(revision!=null && revision==repairedBluetoothLibraryVersion)return@withContext 0
        var repaired = 0
        val sources = rssSourceDao.getAllForSync()
        if(bleDomains!=null && sources.any{it.url==ImportedContentIds.ROOT_SOURCE_URL} && sources.none{it.deleted&&ImportedContentIds.isImportedContentUrl(it.url)}) {
            repairedBluetoothLibraryVersion=bleDomains.libraryVersion()
            return@withContext 0
        }
        val articleMetadata = if(bleDomains!=null)articleDao.unregisteredBookMetadata() else articleDao.getAllMetadataForSync()
        repaired += if (repairMissingImportedTextSourceIfNeeded(sources, articleMetadata)) 1 else 0
        val importedSources = sources
            .filter { ImportedContentIds.isImportedContentUrl(it.url) }
        importedSources.forEach { source ->
            val liveArticles = if (ImportedContentIds.isImportedTextSourceUrl(source.url)) {
                liveImportedTextArticles(articleMetadata)
            } else {
                articleMetadata.filter { it.rssSourceUrl == source.url && !it.deleted }
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
        repairedBluetoothLibraryVersion=bleDomains?.libraryVersion()
        repaired
    }

    private suspend fun repairMissingImportedTextSourceIfNeeded(
        sources: List<PhoneRssSourceEntity>,
        articleMetadata: List<PhoneArticleEntity>? = null
    ): Boolean {
        if (sources.any { it.url == ImportedContentIds.ROOT_SOURCE_URL }) return false
        val liveArticles = liveImportedTextArticles(articleMetadata)
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

    private suspend fun liveImportedTextArticles(
        articleMetadata: List<PhoneArticleEntity>? = null
    ): List<PhoneArticleEntity> {
        return (articleMetadata ?: articleDao.getAllMetadataForSync()).filter { article ->
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
                buildDeleteConflict(local, remote)?.takeUnless { rememberedConflictResolution(local, remote) != null }
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
            articleProvenance?.guardEdit(local.articleId,JSONObject().put("operation","resolveConflict").put("resolution",resolutions[remote.articleId]?.name?:"pending"))
            val remembered = rememberedConflictResolution(local, remote)
            // No UI answer is not consent. Only an explicit answer or a durable decision
            // for exactly these input states may resolve a deletion conflict.
            val resolution = checkNotNull(resolutions[remote.articleId] ?: remembered) {
                "删除冲突尚未确认，请重新同步并选择处理方式"
            }
            if (remembered != null && conflictDecision(local.articleId)?.optString("after") == conflictState(local)) {
                outgoingArticleIds += local.articleId
                suppressedRemoteArticleIds += local.articleId
                return@forEachIndexed
            }
            val timestamp = maxOf(now + index, local.latestOperationAt(), remote.latestOperationAt()) + 1L
            val previous = conflictDecision(local.articleId)
            val consumed = previous?.optJSONArray("consumed")?.let { a -> (0 until a.length()).map(a::getString) }.orEmpty()
            val receipt = JSONObject().put("resolution", resolution.name).put("clock", timestamp)
                .put("before", conflictState(local)).put("remote", conflictState(remote))
                .put("consumed", JSONArray((consumed + listOfNotNull(previous?.optString("remote")) + conflictState(remote)).distinct()))
            appMetaDao.set(AppMetaEntity(conflictDecisionKey(local.articleId), receipt.toString()))
            when (deleteConflictAction(local, remote, resolution)) {
                ConflictAction.PHONE -> {
                    val updated = local.stampKeptByConflict(timestamp)
                    articleDao.upsert(updated.withCurrentSyncMetadata().externalizeLargeLocalContent())
                    recordArticleChange(updated.articleId, "conflictResolution", timestamp)
                    rememberAppliedConflict(updated)
                    outgoingArticleIds += updated.articleId
                    suppressedRemoteArticleIds += updated.articleId
                }
                ConflictAction.WATCH -> {
                    suppressedRemoteArticleIds += remote.articleId
                    if (remote.deleted) {
                        val updated = local.applyRemoteDelete(remote).markDeletedByConflict(timestamp)
                        articleDao.upsert(updated.withCurrentSyncMetadata().externalizeLargeLocalContent())
                        recordArticleChange(updated.articleId, "conflictResolution", timestamp)
                        rememberAppliedConflict(updated)
                        outgoingArticleIds += updated.articleId
                    } else {
                        require(remote.bodyAvailable) { "手表保留的文章正文不可用，请在手表补回正文后重试同步" }
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
                    val updated = local.markDeletedByConflict(timestamp)
                    articleDao.upsert(updated.withCurrentSyncMetadata().externalizeLargeLocalContent())
                    recordArticleChange(updated.articleId, "conflictResolution", timestamp)
                    rememberAppliedConflict(updated)
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
        conflictResolutions: Map<String, PhoneSyncConflictResolution> = emptyMap(),
        authoritative: Boolean = false
    ): Int = withContext(Dispatchers.IO) {
        var merged = 0
        incoming.forEach { candidate ->
            articleProvenance?.requireExportable(candidate.articleId)
            val remote=books?.canonicalArticle(candidate)?:candidate
            val local = articleDao.getById(remote.articleId)
            val localHydrated = local?.hydrateExternalText()
            val recoversLocalOriginal = localHydrated?.let(remote::canRecoverSavedRssBodyFrom) == true
            val retainsLocalBody =
                recoversLocalOriginal || local != null && remote.contentHash == local.contentHash &&
                (remote.contentHash.isNotBlank() || remote.updatedAt == local.updatedAt) &&
                remote.contentHtml.isNullOrBlank() &&
                remote.contentText.isBlank()
            val remoteWithBody = if (retainsLocalBody) {
                val localBody = requireNotNull(localHydrated)
                remote.copy(
                    contentHash = if (recoversLocalOriginal) localBody.contentHash else remote.contentHash,
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
                remoteWithBody.withCurrentSyncMetadata(localHydrated?.syncBodyHash.orEmpty())
            }
            val next = if (authoritative || local == null) {
                preparedRemote
            } else {
                mergeArticle(
                    local = requireNotNull(localHydrated),
                    remote = preparedRemote,
                    conflictResolution = conflictResolutions[remote.articleId],
                    decisionClock = conflictDecisionClock(remote.articleId)
                )
            }
            val verifiedNext = (books?.constrainDeletion(next) ?: next).withCurrentSyncMetadata(localHydrated?.syncBodyHash.orEmpty())
            if(localHydrated==verifiedNext) {
                if (remote.articleId in conflictResolutions) rememberAppliedConflict(verifiedNext)
                return@forEach
            }
            val storedNext=verifiedNext.reuseStoredBody(local,localHydrated).externalizeLargeLocalContent()
            if (local != storedNext) {
                articleDao.upsert(storedNext)
                recordArticleChange(storedNext.articleId,"merge",storedNext.latestOperationAt(),storedNext.sourceDeviceId)
                merged += 1
            }
            if (remote.articleId in conflictResolutions) rememberAppliedConflict(verifiedNext)
        }
        merged
    }

    suspend fun mergeChunkedArticlesFromSync(
        incoming: List<ChunkedArticlePayload>,
        conflictResolutions: Map<String, PhoneSyncConflictResolution> = emptyMap(),
        authoritative: Boolean = false
    ): Int =
        withContext(Dispatchers.IO) {
            val projected=incoming.map{payload->payload.copy(article=books?.canonicalArticle(payload.article)?:payload.article)}
            projected.forEach{articleProvenance?.requireExportable(it.article.articleId)}
            val localByArticleId = mutableMapOf<String, PhoneArticleEntity?>()
            projected.forEach { payload ->
                if (!localByArticleId.containsKey(payload.article.articleId)) {
                    localByArticleId[payload.article.articleId] = articleDao.getById(payload.article.articleId)
                }
            }
            projected.filter { it.metadataOnly }.forEach { payload ->
                val local = localByArticleId[payload.article.articleId]
                    ?: error("同步正文仅元数据响应缺少本地正文：${payload.article.articleId}")
                val hydrated = local.hydrateExternalTextForSync()
                check(hydrated.bodyAvailable) {
                    "同步正文仅元数据响应的本地正文文件缺失：${payload.article.articleId}"
                }
                requireMetadataOnlyLocalBody(payload, hydrated.article)
            }
            var merged = 0
            projected.forEach { payload ->
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
                val decodedRemote = payload.article.copy(
                    contentHtml = contentHtml,
                    contentText = contentText,
                    // The raw frame has already passed rebuildBody's complete/chunk verification.
                    // Retain its digest only as a witness; metadataFor revalidates the actual body.
                    syncBodyHash = payload.bodyHash,
                    syncBodyByteCount = payload.bodyByteCount,
                    syncChunkSize = payload.chunkSize,
                    syncChunkHashesJson = payload.chunkHashes.toJsonString(),
                    syncMetadataHash = ""
                )
                // An authoritative state transfer still cannot turn a missing RSS original into an erasure.
                val preparedRemote = if (localHydrated != null && decodedRemote.canRecoverSavedRssBodyFrom(localHydrated)) {
                    decodedRemote.copy(contentHash=localHydrated.contentHash,
                        contentHtml=localHydrated.contentHtml, contentText=localHydrated.contentText)
                } else decodedRemote
                val conflictResolution = conflictResolutions[payload.article.articleId]
                val localForMerge = localHydrated ?: local?.takeIf { conflictResolution != null }
                val mergedArticle = if (authoritative || localForMerge == null) {
                    preparedRemote
                } else {
                    mergeArticle(
                        local = localForMerge,
                        remote = preparedRemote,
                        conflictResolution = conflictResolution,
                        decisionClock = conflictDecisionClock(payload.article.articleId)
                    )
                }
                val constrained=books?.constrainDeletion(mergedArticle) ?: mergedArticle
                val next = if (
                    explicitUnavailableTombstone &&
                    !retainsLocalDeletedBody &&
                    constrained.deleted
                ) {
                    constrained.withUnavailableTombstoneBodyMetadata(clearBody = true)
                } else {
                    constrained.withCurrentSyncMetadata(localHydrated?.syncBodyHash.orEmpty())
                }
                if(localHydrated==next) {
                    if (conflictResolution != null) rememberAppliedConflict(next)
                    return@forEach
                }
                val storedNext=next.reuseStoredBody(local,localHydrated).externalizeLargeLocalContent()
                if (local != storedNext) {
                    articleDao.upsert(storedNext)
                    recordArticleChange(storedNext.articleId,"merge",storedNext.latestOperationAt(),storedNext.sourceDeviceId)
                    merged += 1
                }
                if (conflictResolution != null) rememberAppliedConflict(next)
            }
            merged
        }

    suspend fun mergeRssSourcesFromSync(incoming: List<PhoneRssSourceEntity>,
        onMerged:(PhoneRssSourceEntity?,PhoneRssSourceEntity)->Unit = {_,_->}): Int =
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
                                if(articleProvenance?.protected(article.articleId)==true)return@forEachIndexed
                                articleDao.upsert(article.applyRemoteSourceDelete(next, index))
                            }
                    }
                    rssSourceDao.upsert(next)
                    runCatching { onMerged(local,next) }
                    merged += 1
                }
            }
            merged
        }

    suspend fun mergeAuthoritativeRssSources(incoming: List<PhoneRssSourceEntity>): Int =
        withContext(Dispatchers.IO) {
            var changed=0
            incoming.forEach { if(rssSourceDao.getByUrl(it.url)!=it) {rssSourceDao.upsert(it);changed++} }
            changed
        }

    /**
     * Finalizes a validated authoritative snapshot without holding article bodies twice.
     * Matching rows are retained while target-only rows are pruned.
     */
    suspend fun finalizeAuthoritativeLibrarySnapshot(
        retainedArticleIds: Collection<String>,
        retainedSourceUrls: Collection<String>
    ) = withContext(Dispatchers.IO) {
        articleProvenance?.requireExportable()
        val articleIds = retainedArticleIds.toHashSet()
        val sourceUrls = retainedSourceUrls.toHashSet()
        val removedAt = System.currentTimeMillis()
        var removedArticles=0;var removedSources=0
        articleDao.getAllIds().filterNot(articleIds::contains).forEach { id ->
            val article = articleDao.getById(id) ?: return@forEach
            if (!article.deleted) { articleDao.upsert(article.markDeletedForStorage(removedAt)); recordArticleChange(id, "delete", removedAt);removedArticles++ }
        }
        rssSourceDao.getAllUrls().filterNot(sourceUrls::contains).forEach { url ->
            val source = rssSourceDao.getByUrl(url) ?: return@forEach
            if (!source.deleted) {
                rssSourceDao.upsert(source.copy(deleted = true, deletedAt = removedAt, updatedAt = removedAt))
                recordRssSourceChange(url, "delete", removedAt)
                removedSources++
            }
        }
        savedItemDao.deleteAll()
        syncPeerStateDao.deleteAll()
        pruneUnreferencedArticleContent()
        removedArticles to removedSources
    }

    suspend fun clearLibrary() = withContext(Dispatchers.IO) {
        books?.clear()
        articleDao.deleteAll()
        articleProvenance?.clearLibrary()
        rssSourceDao.deleteAll()
        savedItemDao.deleteAll()
        syncChangeLogDao.deleteAll()
        syncPeerStateDao.deleteAll()
        articleContentStore?.prune(emptySet())
    }

    suspend fun resetLibrarySyncPeerState() = withContext(Dispatchers.IO) {
        syncPeerStateDao.deleteAll()
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
        if(articleProvenance?.protected(imported.articleId)==true)articleDao.getById(imported.articleId)?.let {
            articleProvenance?.materializeOriginal(it.copy(contentHash=imported.contentHash,contentText=imported.contentText,contentHtml=imported.contentHtml))
        }
        articleProvenance?.guardEdit(imported.articleId,JSONObject().put("operation","import").put("url",imported.url)
            .put("title",imported.title).put("contentHash",imported.contentHash).put("type",type?.name?:JSONObject.NULL).put("independent",independent))
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
        imported.sourceId?.let { rememberPublicRssSource(imported.url, it) }
        val now = System.currentTimeMillis()
        val existing = rssSourceDao.getByUrl(imported.url)
        // A delayed public-pool response is cache refresh, never explicit re-subscription.
        if(!recordSyncChanges && existing?.deleted==true)return PhoneRssSourceImportResult(existing,0)
        val existingArticles = articleDao.getByRssSourceUrl(imported.url)
        val existingByContentHash = existingArticles
            .filter { it.contentHash.isNotBlank() }
            .associateBy { it.contentHash }
        val existingByUrl = existingArticles.associateBy { it.url }
        val candidateSource = PhoneRssSourceEntity(
            url = imported.url,
            sourceDeviceId = deviceId,
            title = existing?.title ?: imported.title.ifBlank { hostLabel(imported.url) },
            description = imported.description,
            siteUrl = imported.siteUrl,
            imageUrl = imported.imageUrl,
            createdAt = existing?.createdAt ?: now,
            updatedAt = existing?.takeUnless { it.deleted }?.updatedAt ?: now,
            // Signed order is shared with the watch; refreshing a feed is not a reorder.
            sortOrder = existing?.sortOrder ?: now,
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
            // A metadata edit with the old clock loses again to a lexically larger peer.
            // Also outrank a future tombstone when explicitly restoring a subscription.
            candidateSource.copy(updatedAt = if(existing==null) now else
                maxOf(now,existing.updatedAt+1,existing.deletedAt+1))
        }
        rssSourceDao.upsert(source)
        // Only public article cache rows are local-only. A changed subscription must
        // reach the next Bluetooth delta as well as records/full snapshots.
        if (source != existing) {
            recordRssSourceChange(source.url, "upsert", source.updatedAt)
        }
        val activeSourceUrls = rssSourceDao.getAllForSync().filterNot { it.deleted }.map { it.url }.toSet()
        val rekeyed=mutableMapOf<String,String>()
        var skippedArticleCount=0
        val articles = imported.items.mapIndexedNotNull { index, item ->
            val timestamp = now - index
            val canonicalId = try {
                if (ImportedContentIds.isImportedContentUrl(imported.url)) WebArticleImporter.stableArticleId(item.url)
                else com.lightningstudio.watchrss.phone.cloud.CloudRssEntryIdentity.id(item.guid,item.identityLink,
                    item.contentHash ?: (item.contentHtml?.takeIf { it.isNotEmpty() } ?: item.contentText.takeIf { it.isNotEmpty() })?.let(WebArticleImporter::sha256),
                    item.title,item.dedupKey)
            } catch (_: IllegalArgumentException) { skippedArticleCount++;return@mapIndexedNotNull null }
            val legacy=existingByUrl[item.url]?.takeIf { old ->
                old.rssSourceUrl==imported.url && old.articleId !in rekeyed &&
                    runCatching { WebArticleImporter.stableArticleId(item.url)==old.articleId }.getOrDefault(false)
            }
            val existingArticle = articleDao.getById(canonicalId)
                ?: legacy
                ?: if (ImportedContentIds.isImportedContentUrl(imported.url)) existingByContentHash[item.contentHash ?: WebArticleImporter.sha256(
                    item.contentHtml ?: item.contentText.ifBlank { item.url }
                )] else null
            if(existingArticle!=null && existingArticle.articleId!=canonicalId && articleProvenance?.protected(existingArticle.articleId)==true)
                return@mapIndexedNotNull existingArticle
            // One canonical URL has one local row. Another subscribed feed must not
            // steal it, reset its clocks, or erase its local read/saved state.
            if (existingArticle?.keepsOtherActiveRssOwner(source.url, activeSourceUrls) == true) {
                return@mapIndexedNotNull existingArticle
            }
            if (existingArticle!=null && existingArticle.articleId!=canonicalId) rekeyed[existingArticle.articleId]=canonicalId
            val candidate = PhoneArticleEntity(
                articleId = canonicalId,
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
                // Normalize the feed preview before comparing against the stored row.
                // Otherwise every refresh restores the long summary and stamps a new change.
                .compactCloudExcerpt(timestamp, deviceId)
                .withRssLocalStateFrom(existingArticle?.hydrateExternalText()?.copy(articleId=canonicalId))
                .withCurrentSyncMetadata()
                .externalizeLargeLocalContent()
            if(existingArticle!=null && articleProvenance?.protected(existingArticle.articleId)==true) {
                articleProvenance?.materializeOriginal(candidate)
                return@mapIndexedNotNull articleDao.getById(existingArticle.articleId)?:existingArticle
            }
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
                ).withCurrentSyncMetadata()
            } else {
                candidate
            }
        }
        if (replaceExistingArticles) {
            val incomingIds = articles.map { it.articleId }.toSet()
            articleDao.getByRssSourceUrl(imported.url).filterNot { it.articleId in incomingIds || it.deleted }.forEach { old ->
                val deleted = old.markDeletedForStorage(System.currentTimeMillis())
                articleDao.upsert(deleted)
                if (recordSyncChanges) recordArticleChange(deleted.articleId, "delete", deleted.deletedAt)
            }
        }
        if (articles.isNotEmpty()) {
            // Persist identity aliases first; if interrupted, old rows still protect their saved original.
            rekeyed.forEach { (oldId,newId) -> appMetaDao.set(AppMetaEntity(rssIdentityAliasPrefix()+oldId,newId)) }
            // Store replacements and retire their legacy rows in one transaction.
            articleDao.upsertRssIdentityRows(articles,rekeyed.keys.filter { old -> articles.none { it.articleId==old } })
            if (recordSyncChanges) {
                articles.forEach { article ->
                    val previous = existingByUrl[article.url]
                    if (previous != article) {
                        recordArticleChange(article.articleId, "upsert", article.updatedAt)
                    }
                }
            }
        }
        return PhoneRssSourceImportResult(source, articles.size, skippedArticleCount)
    }

    private suspend fun recordArticleChange(
        articleId: String,
        reason: String,
        changedAt: Long = System.currentTimeMillis(),
        origin: String = deviceId
    ) {
        if (articleId.isBlank()) return
        syncChangeLogDao.insert(
            SyncChangeLogEntity(
                kind = SYNC_KIND_ARTICLE,
                entityId = articleId,
                changedAt = changedAt,
                originDeviceId = origin.ifBlank { deviceId },
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
        conflictResolution: PhoneSyncConflictResolution? = null,
        decisionClock: Long? = null
    ): PhoneArticleEntity {
        val clock = maxOf(decisionClock ?: 0L, maxOf(local.latestOperationAt(), remote.latestOperationAt()) + 1L)
        return when (conflictResolution) {
            null,
            PhoneSyncConflictResolution.KEEP_LATEST -> mergeArticleByLatest(local, remote)
            PhoneSyncConflictResolution.KEEP_PHONE -> local.stampKeptByConflict(
                timestamp = clock
            )
            PhoneSyncConflictResolution.KEEP_WATCH -> remote.stampKeptByConflict(clock)
            PhoneSyncConflictResolution.DELETE_CONTENT -> mergeArticleByLatest(local, remote).markDeletedByConflict(
                timestamp = clock
            )
            PhoneSyncConflictResolution.MERGE_CONTENT -> mergeArticleContent(local, remote).stampKeptByConflict(clock)
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
        // A metadata-only tombstone can arrive before the legacy article/body.
        // Old feed snapshots must not turn it live just because it is RSS or saved.
        val newestDeletionAt = maxOf(
            if (local.deleted) local.deletedAt else 0L,
            if (remote.deleted) remote.deletedAt else 0L
        )
        val newestLiveOperationAt = maxOf(
            if (!local.deleted) local.latestOperationAt() else 0L,
            if (!remote.deleted) remote.latestOperationAt() else 0L
        )
        val deleted = when {
            newestDeletionAt > 0L && newestDeletionAt >= newestLiveOperationAt -> true
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
        // State-only records can win the timestamp while carrying no body.
        // Same-version hydration remains strict, except for the equal-clock saved RSS placeholder below.
        val recoveredRssBody = listOf(local, remote).firstOrNull(metadata::canRecoverSavedRssBodyFrom)
        val body = if (recoveredRssBody != null) {
            recoveredRssBody
        } else if (metadata.contentText.isBlank() && metadata.contentHtml.isNullOrBlank()) {
            listOf(local, remote).firstOrNull { it.contentHash == metadata.contentHash &&
                (metadata.contentHash.isNotBlank() || it.updatedAt == metadata.updatedAt) &&
                (it.contentText.isNotBlank() || !it.contentHtml.isNullOrBlank()) } ?: metadata
        } else if (metadata.contentHtml.isNullOrBlank()) {
            listOf(local, remote).firstOrNull { it.contentHash == metadata.contentHash &&
                (metadata.contentHash.isNotBlank() || it.updatedAt == metadata.updatedAt) && !it.contentHtml.isNullOrBlank() } ?: metadata
        } else metadata
        val sameClockSavedRss = local.hasSameSavedRssIdentityAndClock(remote) || remote.hasSameSavedRssIdentityAndClock(local)
        val readFlag = when {
            // A body-only/old metadata projection's default unread is not a reading operation.
            sameClockSavedRss && local.readingPositionChangedAt > 0 && remote.readingPositionChangedAt <= 0 -> local.isRead
            sameClockSavedRss && remote.readingPositionChangedAt > 0 && local.readingPositionChangedAt <= 0 -> remote.isRead
            else -> com.lightningstudio.watchrss.phone.data.backup.mergeReadFlag(local.isRead, local.readingPositionChangedAt, local.updatedAt, local.sourceDeviceId,
                remote.isRead, remote.readingPositionChangedAt, remote.updatedAt, remote.sourceDeviceId)
        }
        return metadata.copy(
            // Keep the independent metadata/reading decisions above; only repair the missing version.
            contentHash = recoveredRssBody?.contentHash ?: metadata.contentHash,
            contentText = body.contentText,
            contentHtml = body.contentHtml,
            syncBodyHash = body.syncBodyHash,
            syncBodyByteCount = body.syncBodyByteCount,
            syncChunkSize = body.syncChunkSize,
            syncChunkHashesJson = body.syncChunkHashesJson,
            syncMetadataHash = body.syncMetadataHash,
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
            isRead = readFlag
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
            merged.deleted -> true
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
            isRead = com.lightningstudio.watchrss.phone.data.backup.mergeReadFlag(local.isRead, local.readingPositionChangedAt, local.updatedAt, local.sourceDeviceId,
                remote.isRead, remote.readingPositionChangedAt, remote.updatedAt, remote.sourceDeviceId)
        )
    }

    private fun conflictDecisionKey(id: String) = "article_conflict_decision_v1:$id"
    private suspend fun conflictDecision(id: String): JSONObject? =
        appMetaDao.getString(conflictDecisionKey(id))?.let { runCatching { JSONObject(it) }.getOrNull() }
    private suspend fun conflictDecisionClock(id: String): Long? = conflictDecision(id)?.optLong("clock")

    // A content refresh/read position does not undo a save/delete decision. Receipts
    // compare the independently clocked save/delete state, not encoding/body metadata.
    private fun conflictState(article: PhoneArticleEntity): String = listOf(article.url, article.rssSourceUrl.orEmpty(),
        article.independentSaved, article.independentChangedAt,
        article.favoriteSaved, article.favoriteChangedAt, article.watchLaterSaved, article.watchLaterChangedAt,
        article.deleted, article.deletedAt).joinToString("\n").let(WebArticleImporter::sha256)
    private fun conflictState(article: ArticleSyncManifestEntry): String = listOf(article.independentChangedAt,
        article.favoriteChangedAt, article.watchLaterChangedAt, article.deleted, article.deletedAt).joinToString("\n").let(WebArticleImporter::sha256)

    private suspend fun rememberedConflictResolution(local: PhoneArticleEntity, remote: ArticleSyncManifestEntry): PhoneSyncConflictResolution? {
        val receipt = conflictDecision(local.articleId)
        if (receipt == null) {
            // Upgrade existing installs: the business journal already proves an explicit
            // decision. Only the unchanged winning row can consume an older alias replay.
            if (local.sourceDeviceId == deviceId && local.latestOperationAt() > remote.latestOperationAt() &&
                syncChangeLogDao.lastConflictResolution(local.articleId) == local.latestOperationAt()) {
                return if (local.deleted) PhoneSyncConflictResolution.DELETE_CONTENT else PhoneSyncConflictResolution.KEEP_PHONE
            }
            return null
        }
        val state = conflictState(local)
        if (state != receipt.optString("before") && state != receipt.optString("after")) return null
        val remoteState = conflictState(remote)
        val consumed = receipt.optJSONArray("consumed")
        if (remoteState != receipt.optString("remote") && !(state == receipt.optString("after") && consumed != null &&
                (0 until consumed.length()).any { consumed.optString(it) == remoteState })) return null
        return runCatching { PhoneSyncConflictResolution.valueOf(receipt.getString("resolution")) }.getOrNull()
    }

    private suspend fun rememberAppliedConflict(article: PhoneArticleEntity) {
        val receipt = conflictDecision(article.articleId) ?: return
        receipt.put("after", conflictState(article)).put("clock", article.latestOperationAt())
        appMetaDao.set(AppMetaEntity(conflictDecisionKey(article.articleId), receipt.toString()))
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
            independentChangedAt = timestamp,
            independentSortOrder = if (independentSaved) timestamp else independentSortOrder,
            favoriteChangedAt = timestamp,
            favoriteSortOrder = if (favoriteSaved) timestamp else favoriteSortOrder,
            watchLaterChangedAt = timestamp,
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

    private fun PhoneArticleEntity.markDeletedByConflict(timestamp: Long): PhoneArticleEntity =
        markDeletedByUser(timestamp).copy(independentChangedAt = timestamp,
            favoriteChangedAt = timestamp, watchLaterChangedAt = timestamp)

    private fun PhoneArticleEntity.markDeletedByUser(timestamp: Long): PhoneArticleEntity {
        return copy(
            sourceDeviceId = deviceId,
            updatedAt = timestamp,
            independentSaved = false,
            independentChangedAt = if (independentSaved || independentChangedAt > 0L) timestamp else independentChangedAt,
            independentSortOrder = 0L,
            favoriteSaved = false,
            favoriteChangedAt = if (favoriteSaved || favoriteChangedAt > 0L) timestamp else favoriteChangedAt,
            favoriteSortOrder = 0L,
            watchLaterSaved = false,
            watchLaterChangedAt = if (watchLaterSaved || watchLaterChangedAt > 0L) timestamp else watchLaterChangedAt,
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
            articleDao.updateSyncMetadata(updated)
            return updated
        }
        val metadata = ArticleSyncBody.metadataFor(hydrated)
        if (hasSyncMetadata(metadata)) {
            return this
        }
        val updated = withSyncMetadata(metadata)
        articleDao.updateSyncMetadata(updated)
        return updated
    }

    private suspend fun PhoneArticleEntity.syncManifestEntryForExport(): ArticleSyncManifestEntry {
        articleProvenance?.requireExportable(articleId)
        val hydrated = if (ArticleSyncBody.cachedMetadataFor(this) != null) {
            hydrateExternalTextForSync()
        } else {
            loadStoredBodyForSync()
        }
        if (!hydrated.bodyAvailable || hydrated.article.isMissingSavedRssBody()) {
            val tombstone = if (deleted) withUnavailableTombstoneBodyMetadata() else this
            if (tombstone != this) articleDao.upsert(tombstone)
            return tombstone.toSyncManifestEntry(bodyAvailable = false)
        }
        return ensureSyncMetadata(hydrated.article).toSyncManifestEntry(bodyAvailable = true)
    }

    private suspend fun PhoneArticleEntity.articleForBodyExport(): PhoneArticleEntity? {
        articleProvenance?.requireExportable(articleId)
        val hydrated = loadStoredBodyForSync()
        if (!hydrated.bodyAvailable || hydrated.article.isMissingSavedRssBody()) return null
        return hydrated.article.withCurrentSyncMetadata()
    }

    private suspend fun PhoneArticleDao.updateSyncMetadata(article: PhoneArticleEntity) {
        updateSyncMetadata(
            articleId = article.articleId,
            bodyHash = article.syncBodyHash,
            bodyByteCount = article.syncBodyByteCount,
            chunkSize = article.syncChunkSize,
            chunkHashesJson = article.syncChunkHashesJson,
            metadataHash = article.syncMetadataHash
        )
    }

    private fun PhoneArticleEntity.withCurrentSyncMetadata(expectedBodyHash: String = syncBodyHash): PhoneArticleEntity {
        return withSyncMetadata(ArticleSyncBody.currentMetadataFor(this, expectedBodyHash.ifBlank { syncBodyHash }))
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
            deleted = deleted,
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

    private fun PhoneArticleEntity.reuseStoredBody(stored:PhoneArticleEntity?,hydrated:PhoneArticleEntity?):PhoneArticleEntity {
        if(stored==null || hydrated==null)return this
        return copy(contentHtml=if(contentHtml==hydrated.contentHtml)stored.contentHtml else contentHtml,
            contentText=if(contentText==hydrated.contentText)stored.contentText else contentText)
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
        if(articleProvenance?.protected(articleId)==true)return this
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
            // Body hash is a hint, never proof: withCurrentSyncMetadata verifies the complete digest.
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

    private suspend fun PhoneArticleEntity.loadStoredBodyForSync(): SyncHydratedArticle {
        val stored = copy(
            contentHtml = articleDao.readContentHtmlChunked(articleId),
            contentText = articleDao.readContentTextChunked(articleId)
        )
        return stored.hydrateExternalTextForSync()
    }

    private suspend fun PhoneArticleDao.readContentHtmlChunked(articleId: String): String? {
        val first = getContentHtmlChunk(
            articleId = articleId,
            startCharacter = 1,
            characterCount = SYNC_DB_TEXT_CHUNK_CHARACTERS
        ) ?: return null
        return readTextChunks(articleId, first) { id, start, count ->
            getContentHtmlChunk(id, start, count)
        }
    }

    private suspend fun PhoneArticleDao.readContentTextChunked(articleId: String): String {
        val first = getContentTextChunk(
            articleId = articleId,
            startCharacter = 1,
            characterCount = SYNC_DB_TEXT_CHUNK_CHARACTERS
        ).orEmpty()
        return readTextChunks(articleId, first) { id, start, count ->
            getContentTextChunk(id, start, count)
        }
    }

    private suspend fun readTextChunks(
        articleId: String,
        firstChunk: String,
        readChunk: suspend (String, Int, Int) -> String?
    ): String {
        if (firstChunk.length < SYNC_DB_TEXT_CHUNK_CHARACTERS) return firstChunk
        val text = StringBuilder(firstChunk)
        var startCharacter = firstChunk.length + 1
        while (true) {
            val chunk = readChunk(
                articleId,
                startCharacter,
                SYNC_DB_TEXT_CHUNK_CHARACTERS
            ).orEmpty()
            if (chunk.isEmpty()) break
            text.append(chunk)
            startCharacter += chunk.length
            if (chunk.length < SYNC_DB_TEXT_CHUNK_CHARACTERS) break
        }
        return text.toString()
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
        private const val FULL_SNAPSHOT_INTERVAL_MS = com.lightningstudio.watchrss.phone.data.ble.BleDomainStore.RECEIPT_MAX_AGE_MS
        private const val SYNC_KIND_ARTICLE = "article"
        private const val SYNC_KIND_RSS_SOURCE = "rssSource"
        private const val SYNC_DB_TEXT_CHUNK_CHARACTERS = 64 * 1024
        private const val SYNC_ID_QUERY_BATCH_SIZE = 800
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
