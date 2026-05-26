package com.lightningstudio.watchrss.phone.data.repo

import com.lightningstudio.watchrss.phone.data.db.PhoneArticleDao
import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
import com.lightningstudio.watchrss.phone.data.db.PhoneRssSourceDao
import com.lightningstudio.watchrss.phone.data.db.PhoneRssSourceEntity
import com.lightningstudio.watchrss.phone.data.db.PhoneSavedItemDao
import com.lightningstudio.watchrss.phone.data.db.PhoneSavedItemEntity
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

class PhoneCompanionRepository(
    private val savedItemDao: PhoneSavedItemDao,
    private val articleDao: PhoneArticleDao,
    private val rssSourceDao: PhoneRssSourceDao,
    private val deviceId: String,
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

    fun observeRssSources(): Flow<List<PhoneRssSourceEntity>> {
        return rssSourceDao.observeActive()
    }

    fun observeRssArticles(): Flow<List<PhoneArticleEntity>> {
        return articleDao.observeRssArticles()
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
            updated
        }

    suspend fun getArticlesForSync(): List<PhoneArticleEntity> = withContext(Dispatchers.IO) {
        articleDao.getAllForSync()
            .filter { it.shouldSyncThroughLibrary() }
            .map { it.hydrateExternalText() }
    }

    suspend fun getRssSourcesForSync(): List<PhoneRssSourceEntity> = withContext(Dispatchers.IO) {
        rssSourceDao.getAllForSync()
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
                articleDao.updateTitle(
                    articleId = articleId,
                    title = title,
                    updatedAt = System.currentTimeMillis() + repaired
                )
                repaired += 1
            }
        }
        repaired
    }

    suspend fun mergeArticlesFromSync(incoming: List<PhoneArticleEntity>): Int = withContext(Dispatchers.IO) {
        var merged = 0
        incoming.forEach { remote ->
            val local = articleDao.getById(remote.articleId)
            val next = if (local == null) {
                remote
            } else {
                mergeArticle(local, remote)
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
                val next = if (local == null || remote.isNewerThan(local)) {
                    remote
                } else {
                    local
                }
                if (local != next) {
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
        val stored = saved.externalizeLargeLocalContent()
        articleDao.upsert(stored)
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
            deleted = false,
            deletedAt = 0L
        )
        rssSourceDao.upsert(source)
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
                .externalizeLargeLocalContent()
        }
        if (replaceExistingArticles) {
            articleDao.deleteByRssSourceUrl(imported.url)
        }
        if (articles.isNotEmpty()) {
            articleDao.upsertAll(articles)
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
        return trim().lowercase().startsWith("${ImportedContentIds.ROOT_SOURCE_URL}/epub/")
    }

    private fun mergeArticle(local: PhoneArticleEntity, remote: PhoneArticleEntity): PhoneArticleEntity {
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
        val deleted = when {
            favoriteSaved || watchLaterSaved || independentSaved || !rssSourceUrl.isNullOrBlank() -> false
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

    private fun hostLabel(link: String): String {
        return runCatching { URI(link).host.orEmpty().removePrefix("www.") }
            .getOrDefault("")
            .trim()
    }

    companion object {
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
