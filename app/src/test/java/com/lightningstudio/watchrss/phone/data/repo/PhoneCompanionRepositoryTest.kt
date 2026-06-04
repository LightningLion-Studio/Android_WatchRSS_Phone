package com.lightningstudio.watchrss.phone.data.repo

import com.lightningstudio.watchrss.phone.connection.bluetooth.ArticleSyncManifestEntry
import com.lightningstudio.watchrss.phone.connection.bluetooth.PhoneSyncConflictResolution
import com.lightningstudio.watchrss.phone.data.db.PhoneArticleDao
import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
import com.lightningstudio.watchrss.phone.data.db.PhoneRssSourceDao
import com.lightningstudio.watchrss.phone.data.db.PhoneRssSourceEntity
import com.lightningstudio.watchrss.phone.data.db.PhoneSavedItemDao
import com.lightningstudio.watchrss.phone.data.db.PhoneSavedItemEntity
import com.lightningstudio.watchrss.phone.data.importer.ImportedLocalContent
import com.lightningstudio.watchrss.phone.data.importer.ImportedRssItem
import com.lightningstudio.watchrss.phone.data.importer.ImportedRssSource
import com.lightningstudio.watchrss.phone.data.importer.ImportedWebArticle
import com.lightningstudio.watchrss.phone.data.importer.LocalContentImportKind
import com.lightningstudio.watchrss.phone.data.local.ArticleContentStore
import com.lightningstudio.watchrss.phone.data.local.StoredTextChunkHandle
import com.lightningstudio.watchrss.phone.data.model.ImportedContentIds
import com.lightningstudio.watchrss.phone.data.model.PhoneSavedItemType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneCompanionRepositoryTest {
    @Test
    fun replaceSavedItems_usesPayloadContentWithoutFetchingMetadata() = runBlocking {
        val dao = FakePhoneSavedItemDao()
        val articleDao = FakePhoneArticleDao()
        val repository = PhoneCompanionRepository(
            savedItemDao = dao,
            articleDao = articleDao,
            rssSourceDao = FakePhoneRssSourceDao(),
            deviceId = "test-phone"
        )

        val count = repository.replaceSavedItems(
            PhoneSavedItemType.FAVORITE,
            JSONArray(
                """
                [
                  {
                    "id": 7,
                    "title": "示例标题",
                    "link": "https://example.com/post",
                    "summary": "示例摘要",
                    "channelTitle": "示例频道",
                    "pubDate": "2026-03-27"
                  }
                ]
                """.trimIndent()
            )
        )

        assertEquals(1, count)
        assertEquals(1, dao.items.size)
        val savedItem = dao.items.single()
        assertEquals("示例标题", savedItem.title)
        assertEquals("示例摘要", savedItem.summary)
        assertEquals("示例频道", savedItem.channelTitle)
        assertEquals("2026-03-27", savedItem.pubDate)
        assertEquals(1, articleDao.items.size)
        assertEquals(true, articleDao.items.single().favoriteSaved)
    }

    @Test
    fun replaceSavedItems_fallsBackToLinkAndHostForLinksOnlyPayload() = runBlocking {
        val dao = FakePhoneSavedItemDao()
        val articleDao = FakePhoneArticleDao()
        val repository = PhoneCompanionRepository(
            savedItemDao = dao,
            articleDao = articleDao,
            rssSourceDao = FakePhoneRssSourceDao(),
            deviceId = "test-phone"
        )

        val count = repository.replaceSavedItems(
            PhoneSavedItemType.WATCH_LATER,
            JSONArray(
                """
                [
                  {
                    "link": "https://www.example.com/path/to/article"
                  }
                ]
                """.trimIndent()
            )
        )

        assertEquals(1, count)
        val savedItem = dao.items.single()
        assertEquals("https://www.example.com/path/to/article", savedItem.title)
        assertEquals("", savedItem.summary)
        assertEquals("example.com", savedItem.channelTitle)
        assertTrue(savedItem.syncedAt > 0L)
    }

    @Test
    fun importWebArticle_savesAsIndependentArticle() = runBlocking {
        val articleDao = FakePhoneArticleDao()
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = articleDao,
            rssSourceDao = FakePhoneRssSourceDao(),
            deviceId = "test-phone",
            webArticleImporter = {
                ImportedWebArticle(
                    articleId = "article-1",
                    url = "https://example.com/post",
                    title = "独立标题",
                    siteName = "example.com",
                    excerpt = "摘要",
                    contentHtml = null,
                    contentText = "正文",
                    imageUrl = null,
                    contentHash = "hash"
                )
            }
        )

        val article = repository.importWebArticle("https://example.com/post")

        assertEquals("独立标题", article.title)
        assertTrue(article.independentSaved)
        assertEquals(false, article.favoriteSaved)
        assertEquals(false, article.watchLaterSaved)
    }

    @Test
    fun addRssSource_savesSourceAndChannelArticles() = runBlocking {
        val sourceDao = FakePhoneRssSourceDao()
        val articleDao = FakePhoneArticleDao()
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = articleDao,
            rssSourceDao = sourceDao,
            deviceId = "test-phone",
            rssSourceImporter = {
                ImportedRssSource(
                    url = "https://example.com/feed.xml",
                    title = "示例源",
                    description = "源描述",
                    siteUrl = "https://example.com",
                    imageUrl = null,
                    items = listOf(
                        ImportedRssItem(
                            url = "https://example.com/a",
                            title = "频道文章",
                            excerpt = "摘要",
                            contentHtml = null,
                            contentText = "正文",
                            imageUrl = null,
                            guid = "a"
                        )
                    )
                )
            }
        )

        val result = repository.addRssSource("https://example.com/feed.xml")

        assertEquals(1, result.articleCount)
        assertEquals("示例源", sourceDao.sources.single().title)
        val article = articleDao.items.single()
        assertEquals("https://example.com/feed.xml", article.rssSourceUrl)
        assertEquals("示例源", article.rssSourceTitle)
        assertEquals(false, article.independentSaved)
    }

    @Test
    fun refreshRssSource_reimportsFeedAndPreservesSavedState() = runBlocking {
        val sourceDao = FakePhoneRssSourceDao()
        val articleDao = FakePhoneArticleDao()
        var importCount = 0
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = articleDao,
            rssSourceDao = sourceDao,
            deviceId = "test-phone",
            rssSourceImporter = { input ->
                importCount += 1
                ImportedRssSource(
                    url = input,
                    title = if (importCount == 1) "示例源" else "示例源更新",
                    description = "源描述",
                    siteUrl = "https://example.com",
                    imageUrl = null,
                    items = buildList {
                        add(
                            ImportedRssItem(
                                url = "https://example.com/a",
                                title = if (importCount == 1) "频道文章" else "频道文章更新",
                                excerpt = "摘要",
                                contentHtml = null,
                                contentText = "正文",
                                imageUrl = null,
                                guid = "a"
                            )
                        )
                        if (importCount > 1) {
                            add(
                                ImportedRssItem(
                                    url = "https://example.com/b",
                                    title = "新增文章",
                                    excerpt = "摘要",
                                    contentHtml = null,
                                    contentText = "正文",
                                    imageUrl = null,
                                    guid = "b"
                                )
                            )
                        }
                    }
                )
            }
        )
        repository.addRssSource("https://example.com/feed.xml")
        val existingArticle = articleDao.items.single()
        articleDao.upsert(
            existingArticle.copy(
                favoriteSaved = true,
                favoriteChangedAt = 123L,
                favoriteSortOrder = 123L
            )
        )

        val result = repository.refreshRssSource("https://example.com/feed.xml")

        assertEquals(2, result.articleCount)
        assertEquals("示例源更新", sourceDao.sources.single().title)
        val refreshedArticle = articleDao.items.first { it.url == "https://example.com/a" }
        assertEquals("频道文章更新", refreshedArticle.title)
        assertTrue(refreshedArticle.favoriteSaved)
        assertEquals(123L, refreshedArticle.favoriteChangedAt)
        assertTrue(articleDao.items.any { it.url == "https://example.com/b" })
    }

    @Test
    fun toggleSaved_preservesRssArticleUpdatedAt() = runBlocking {
        val articleDao = FakePhoneArticleDao()
        val article = article(
            id = "rss-article",
            rssSourceUrl = "https://example.com/feed.xml"
        ).copy(updatedAt = 42L)
        articleDao.items = listOf(article)
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = articleDao,
            rssSourceDao = FakePhoneRssSourceDao(),
            deviceId = "test-phone"
        )

        val updated = repository.toggleSaved(article, PhoneSavedItemType.FAVORITE)

        assertTrue(updated.favoriteSaved)
        assertTrue(updated.favoriteChangedAt > 0L)
        assertEquals(42L, updated.updatedAt)
        assertEquals(42L, articleDao.items.single().updatedAt)
    }

    @Test
    fun refreshRssSource_rejectsImportedContentChannels() = runBlocking {
        val sourceUrl = ImportedContentIds.epubSourceUrl("book")
        val sourceDao = FakePhoneRssSourceDao().apply {
            sources = listOf(source(sourceUrl, title = "本地书籍"))
        }
        var importCount = 0
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = FakePhoneArticleDao(),
            rssSourceDao = sourceDao,
            deviceId = "test-phone",
            rssSourceImporter = {
                importCount += 1
                ImportedRssSource(
                    url = it,
                    title = "不应抓取",
                    description = "",
                    siteUrl = null,
                    imageUrl = null,
                    items = emptyList()
                )
            }
        )

        val result = runCatching { repository.refreshRssSource(sourceUrl) }

        assertTrue(result.isFailure)
        assertEquals("本地导入频道无需从 RSS 源刷新", result.exceptionOrNull()?.message)
        assertEquals(0, importCount)
    }

    @Test
    fun importLocalContent_savesImportedChannelArticle() = runBlocking {
        val sourceDao = FakePhoneRssSourceDao()
        val articleDao = FakePhoneArticleDao()
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = articleDao,
            rssSourceDao = sourceDao,
            deviceId = "test-phone",
            localContentImporter = { _, _, _ ->
                ImportedLocalContent(
                    kind = LocalContentImportKind.TXT,
                    source = ImportedRssSource(
                        url = ImportedContentIds.ROOT_SOURCE_URL,
                        title = ImportedContentIds.ROOT_SOURCE_TITLE,
                        description = "导入",
                        siteUrl = null,
                        imageUrl = null,
                        items = listOf(
                            ImportedRssItem(
                                url = ImportedContentIds.txtArticleUrl("txt-1"),
                                title = "本地小说",
                                excerpt = "摘要",
                                contentHtml = "<article><p>正文</p></article>",
                                contentText = "正文",
                                imageUrl = null,
                                guid = "txt-1"
                            )
                        )
                    )
                )
            }
        )

        val result = repository.importLocalContent("novel.txt", "text/plain", byteArrayOf(1))

        assertEquals(LocalContentImportKind.TXT, result.kind)
        assertEquals(ImportedContentIds.ROOT_SOURCE_TITLE, sourceDao.sources.single().title)
        val article = articleDao.items.single()
        assertEquals(ImportedContentIds.ROOT_SOURCE_URL, article.rssSourceUrl)
        assertEquals(false, article.independentSaved)
        assertEquals(false, article.favoriteSaved)
        assertEquals(false, article.watchLaterSaved)
    }

    @Test
    fun importLocalContent_externalizesLargeTxtWithoutSplitting() = runBlocking {
        val sourceDao = FakePhoneRssSourceDao()
        val articleDao = FakePhoneArticleDao()
        val contentStore = FakeArticleContentStore()
        val longText = buildString {
            repeat(130_000) { append('字') }
        }
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = articleDao,
            rssSourceDao = sourceDao,
            deviceId = "test-phone",
            localContentImporter = { _, _, _ ->
                ImportedLocalContent(
                    kind = LocalContentImportKind.TXT,
                    source = ImportedRssSource(
                        url = ImportedContentIds.ROOT_SOURCE_URL,
                        title = ImportedContentIds.ROOT_SOURCE_TITLE,
                        description = "导入",
                        siteUrl = null,
                        imageUrl = null,
                        items = listOf(
                            ImportedRssItem(
                                url = ImportedContentIds.txtArticleUrl("txt-large"),
                                title = "长篇小说",
                                excerpt = "摘要",
                                contentHtml = null,
                                contentText = longText,
                                imageUrl = null,
                                guid = "txt-large"
                            )
                        )
                    )
                )
            },
            articleContentStore = contentStore
        )

        val result = repository.importLocalContent("novel.txt", "text/plain", byteArrayOf(1))

        assertEquals(1, result.articleCount)
        val storedArticle = articleDao.items.single()
        assertTrue(contentStore.isMarker(storedArticle.contentText))
        assertEquals(longText, contentStore.loadText(storedArticle.contentText))
        assertEquals(longText, repository.getArticlesForSync().single().contentText)

        val reader = repository.getImportedTextReader(storedArticle.articleId)
        assertEquals(storedArticle.contentText, reader?.marker)
        assertTrue((reader?.chunkCount ?: 0) > 1)
        val firstChunk = repository.loadImportedTextChunk(storedArticle.contentText, 0).orEmpty()
        assertTrue(firstChunk.isNotBlank())
        assertTrue(firstChunk.length < longText.length)
    }

    @Test
    fun importLocalContent_externalizesLargeEpubChapterWithoutSplitting() = runBlocking {
        val sourceDao = FakePhoneRssSourceDao()
        val articleDao = FakePhoneArticleDao()
        val contentStore = FakeArticleContentStore()
        val longHtml = "<article><p>${"字".repeat(130_000)}</p></article>"
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = articleDao,
            rssSourceDao = sourceDao,
            deviceId = "test-phone",
            localContentImporter = { _, _, _ ->
                ImportedLocalContent(
                    kind = LocalContentImportKind.EPUB,
                    source = ImportedRssSource(
                        url = ImportedContentIds.epubSourceUrl("book-large"),
                        title = "长篇 EPUB",
                        description = "导入",
                        siteUrl = null,
                        imageUrl = null,
                        items = listOf(
                            ImportedRssItem(
                                url = ImportedContentIds.epubChapterUrl("book-large", 1, "chapter-large"),
                                title = "第一章",
                                excerpt = "摘要",
                                contentHtml = longHtml,
                                contentText = "正文",
                                imageUrl = null,
                                guid = "chapter-large"
                            )
                        )
                    )
                )
            },
            articleContentStore = contentStore
        )

        val result = repository.importLocalContent("book.epub", "application/epub+zip", byteArrayOf(1))

        assertEquals(1, result.articleCount)
        assertTrue(result.source.url.startsWith(ImportedContentIds.EPUB_SOURCE_ROOT_URL))
        val storedArticle = articleDao.items.single()
        assertTrue(contentStore.isMarker(storedArticle.contentHtml.orEmpty()))
        assertEquals("正文", storedArticle.contentText)
        assertEquals(longHtml, contentStore.loadText(storedArticle.contentHtml.orEmpty()))
        assertEquals(longHtml, repository.getArticlesForSync().single().contentHtml)
    }

    @Test
    fun repairImportedContentTitles_usesExistingHtmlTocAndTextFallback() = runBlocking {
        val sourceUrl = ImportedContentIds.epubSourceUrl("three-body")
        val sourceDao = FakePhoneRssSourceDao().apply {
            sources = listOf(
                PhoneRssSourceEntity(
                    url = sourceUrl,
                    sourceDeviceId = "test-phone",
                    title = "三体全集",
                    description = "导入",
                    siteUrl = null,
                    imageUrl = null,
                    createdAt = 1L,
                    updatedAt = 1L,
                    sortOrder = 1L,
                    isPinned = false,
                    deleted = false,
                    deletedAt = 0L
                )
            )
        }
        val articleDao = FakePhoneArticleDao().apply {
            items = listOf(
                article(
                    id = "toc",
                    url = "$sourceUrl/chapter/0001",
                    rssSourceUrl = sourceUrl,
                    title = "Contents",
                    contentHtml = """
                        <article>
                          <a href="part0001.html">§§第一章 科学边界</a>
                          <a href="part0002.html">§§第二章 台 球</a>
                          <a href="part0003.html">§§第三章 射手和农场主</a>
                        </article>
                    """.trimIndent(),
                    contentText = "目录",
                    importedAt = 4L
                ),
                article(id = "c1", url = "$sourceUrl/chapter/0002", rssSourceUrl = sourceUrl, title = "未知", importedAt = 3L),
                article(id = "c2", url = "$sourceUrl/chapter/0003", rssSourceUrl = sourceUrl, title = "未知", importedAt = 2L),
                article(id = "c3", url = "$sourceUrl/chapter/0004", rssSourceUrl = sourceUrl, title = "未知", contentText = "第三章 射手和农场主\n正文", importedAt = 1L)
            )
        }
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = articleDao,
            rssSourceDao = sourceDao,
            deviceId = "test-phone"
        )

        val repaired = repository.repairImportedContentTitles()

        assertEquals(4, repaired)
        assertEquals(listOf("目录", "第一章 科学边界", "第二章 台 球", "第三章 射手和农场主"), articleDao.items.sortedByDescending { it.importedAt }.map { it.title })
    }

    @Test
    fun getArticlesForSync_excludesPlainRssSourceArticles() = runBlocking {
        val articleDao = FakePhoneArticleDao()
        articleDao.items = listOf(
            article(
                id = "plain-rss",
                rssSourceUrl = "https://example.com/feed.xml"
            ),
            article(
                id = "saved-rss",
                rssSourceUrl = "https://example.com/feed.xml",
                favoriteSaved = true,
                favoriteChangedAt = 10L
            ),
            article(
                id = "removed-save-state",
                rssSourceUrl = "https://example.com/feed.xml",
                favoriteChangedAt = 20L
            ),
            article(
                id = "independent",
                independentSaved = true,
                independentChangedAt = 30L
            ),
            article(
                id = "imported-content",
                url = ImportedContentIds.txtArticleUrl("txt-1"),
                rssSourceUrl = ImportedContentIds.ROOT_SOURCE_URL
            )
        )
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = articleDao,
            rssSourceDao = FakePhoneRssSourceDao(),
            deviceId = "test-phone"
        )

        val ids = repository.getArticlesForSync().map { it.articleId }.toSet()

        assertEquals(setOf("saved-rss", "removed-save-state", "independent", "imported-content"), ids)
    }

    @Test
    fun deleteArticle_marksImportedContentAsDeletedTombstone() = runBlocking {
        val articleDao = FakePhoneArticleDao()
        val imported = article(
            id = "imported-content",
            url = ImportedContentIds.txtArticleUrl("txt-1"),
            rssSourceUrl = ImportedContentIds.ROOT_SOURCE_URL,
            favoriteSaved = true,
            favoriteChangedAt = 10L
        )
        articleDao.items = listOf(imported)
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = articleDao,
            rssSourceDao = FakePhoneRssSourceDao(),
            deviceId = "test-phone"
        )

        repository.deleteArticle(imported.articleId)

        val deleted = articleDao.items.single()
        assertTrue(deleted.deleted)
        assertEquals(false, deleted.favoriteSaved)
        assertEquals(ImportedContentIds.ROOT_SOURCE_URL, deleted.rssSourceUrl)
        assertEquals(setOf(imported.articleId), repository.getArticlesForSync().map { it.articleId }.toSet())
    }

    @Test
    fun prepareDeleteConflictResolutions_keepPhoneSendsFreshLocalVersion() = runBlocking {
        val articleDao = FakePhoneArticleDao()
        val local = article(
            id = "article-1",
            favoriteSaved = true,
            favoriteChangedAt = 10L
        )
        val remoteDeleted = local.copy(
            sourceDeviceId = "watch",
            favoriteSaved = false,
            favoriteChangedAt = 100L,
            deleted = true,
            deletedAt = 100L
        ).toManifestEntry()
        articleDao.items = listOf(local)
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = articleDao,
            rssSourceDao = FakePhoneRssSourceDao(),
            deviceId = "test-phone"
        )

        val plan = repository.prepareDeleteConflictResolutions(
            remoteManifest = listOf(remoteDeleted),
            resolutions = mapOf(local.articleId to PhoneSyncConflictResolution.KEEP_PHONE)
        )

        val updated = articleDao.items.single()
        assertEquals(setOf(local.articleId), plan.outgoingArticleIds)
        assertEquals(false, updated.deleted)
        assertTrue(updated.updatedAt > remoteDeleted.deletedAt)
        assertEquals("test-phone", updated.sourceDeviceId)
    }

    @Test
    fun prepareDeleteConflictResolutions_deleteContentMarksLocalTombstoneForSync() = runBlocking {
        val articleDao = FakePhoneArticleDao()
        val local = article(id = "article-1", favoriteSaved = true, favoriteChangedAt = 10L)
        val remoteDeleted = local.copy(
            sourceDeviceId = "watch",
            favoriteSaved = false,
            favoriteChangedAt = 100L,
            deleted = true,
            deletedAt = 100L
        ).toManifestEntry()
        articleDao.items = listOf(local)
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = articleDao,
            rssSourceDao = FakePhoneRssSourceDao(),
            deviceId = "test-phone"
        )

        val plan = repository.prepareDeleteConflictResolutions(
            remoteManifest = listOf(remoteDeleted),
            resolutions = mapOf(local.articleId to PhoneSyncConflictResolution.DELETE_CONTENT)
        )

        val updated = articleDao.items.single()
        assertEquals(setOf(local.articleId), plan.outgoingArticleIds)
        assertTrue(updated.deleted)
        assertEquals(false, updated.favoriteSaved)
        assertTrue(updated.deletedAt > remoteDeleted.deletedAt)
    }

    @Test
    fun prepareDeleteConflictResolutions_mergeContentRequestsWatchWhenPhoneDeleted() = runBlocking {
        val articleDao = FakePhoneArticleDao()
        val localDeleted = article(
            id = "article-1",
            deleted = true,
            deletedAt = 100L
        )
        val remoteKept = localDeleted.copy(
            sourceDeviceId = "watch",
            favoriteSaved = true,
            favoriteChangedAt = 50L,
            deleted = false,
            deletedAt = 0L
        ).toManifestEntry()
        articleDao.items = listOf(localDeleted)
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = articleDao,
            rssSourceDao = FakePhoneRssSourceDao(),
            deviceId = "test-phone"
        )

        val plan = repository.prepareDeleteConflictResolutions(
            remoteManifest = listOf(remoteKept),
            resolutions = mapOf(localDeleted.articleId to PhoneSyncConflictResolution.MERGE_CONTENT)
        )

        assertEquals(emptySet<String>(), plan.outgoingArticleIds)
        assertEquals(listOf(localDeleted.articleId), plan.forcedRemoteRequests.map { it.articleId })
        assertEquals(
            PhoneSyncConflictResolution.MERGE_CONTENT,
            plan.mergeResolutions[localDeleted.articleId]
        )
    }

    @Test
    fun mergeArticlesFromSync_keepWatchRestoresRemoteAfterLocalDelete() = runBlocking {
        val articleDao = FakePhoneArticleDao()
        val localDeleted = article(
            id = "article-1",
            title = "本地已删",
            deleted = true,
            deletedAt = 100L
        )
        val remoteKept = article(
            id = "article-1",
            title = "手表保留",
            favoriteSaved = true,
            favoriteChangedAt = 50L
        ).copy(sourceDeviceId = "watch")
        articleDao.items = listOf(localDeleted)
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = articleDao,
            rssSourceDao = FakePhoneRssSourceDao(),
            deviceId = "test-phone"
        )

        repository.mergeArticlesFromSync(
            incoming = listOf(remoteKept),
            conflictResolutions = mapOf(localDeleted.articleId to PhoneSyncConflictResolution.KEEP_WATCH)
        )

        val updated = articleDao.items.single()
        assertEquals(false, updated.deleted)
        assertEquals("手表保留", updated.title)
        assertTrue(updated.favoriteSaved)
    }

    @Test
    fun clearImportedContent_marksOnlyTxtRootArticlesAsDeletedTombstones() = runBlocking {
        val articleDao = FakePhoneArticleDao()
        val txt = article(
            id = "txt",
            url = ImportedContentIds.txtArticleUrl("txt-1"),
            rssSourceUrl = ImportedContentIds.ROOT_SOURCE_URL
        )
        val epub = article(
            id = "epub",
            url = ImportedContentIds.epubChapterUrl("book", 1, "chapter-1"),
            rssSourceUrl = ImportedContentIds.epubSourceUrl("book")
        )
        articleDao.items = listOf(txt, epub)
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = articleDao,
            rssSourceDao = FakePhoneRssSourceDao(),
            deviceId = "test-phone"
        )

        val cleared = repository.clearImportedContent()

        assertEquals(1, cleared)
        val deletedTxt = articleDao.items.first { it.articleId == txt.articleId }
        val keptEpub = articleDao.items.first { it.articleId == epub.articleId }
        assertTrue(deletedTxt.deleted)
        assertEquals(false, keptEpub.deleted)
        assertEquals(
            setOf(txt.articleId, epub.articleId),
            repository.getArticlesForSync().map { it.articleId }.toSet()
        )
    }

    @Test
    fun importedTextRootSource_isNotSyncedAsRssSource() = runBlocking {
        val importedText = source(
            url = ImportedContentIds.ROOT_SOURCE_URL,
            title = ImportedContentIds.ROOT_SOURCE_TITLE,
            deleted = true,
            updatedAt = 100L
        )
        val regular = source(url = "https://example.com/feed.xml")
        val sourceDao = FakePhoneRssSourceDao().apply {
            sources = listOf(importedText, regular)
        }
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = FakePhoneArticleDao(),
            rssSourceDao = sourceDao,
            deviceId = "test-phone"
        )

        val exported = repository.getRssSourcesForSync()
        val merged = repository.mergeRssSourcesFromSync(
            listOf(importedText.copy(sourceDeviceId = "watch", updatedAt = 200L))
        )

        assertEquals(listOf(regular.url), exported.map { it.url })
        assertEquals(0, merged)
        assertEquals(true, sourceDao.sources.first { it.url == importedText.url }.deleted)
    }

    @Test
    fun importedEpubSources_areSyncedAsRssSources() = runBlocking {
        val importedEpub = source(
            url = ImportedContentIds.epubSourceUrl("book"),
            title = "三体全集",
            deleted = true,
            updatedAt = 100L
        )
        val legacyImportedEpub = source(
            url = "${ImportedContentIds.ROOT_SOURCE_URL}/epub/legacy-book",
            title = "旧版导入书籍",
            deleted = true,
            updatedAt = 100L
        )
        val regular = source(url = "https://example.com/feed.xml")
        val sourceDao = FakePhoneRssSourceDao().apply {
            sources = listOf(importedEpub, legacyImportedEpub, regular)
        }
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = FakePhoneArticleDao(),
            rssSourceDao = sourceDao,
            deviceId = "test-phone"
        )

        val exported = repository.getRssSourcesForSync()
        val merged = repository.mergeRssSourcesFromSync(
            listOf(importedEpub.copy(sourceDeviceId = "watch", deleted = false, updatedAt = 200L))
        )

        assertEquals(
            listOf(importedEpub.url, legacyImportedEpub.url, regular.url),
            exported.map { it.url }
        )
        assertEquals(1, merged)
        assertEquals(false, sourceDao.sources.first { it.url == importedEpub.url }.deleted)
    }

    @Test
    fun repairImportedContentSourceStates_restoresDeletedSourceWithLiveArticles() = runBlocking {
        val sourceUrl = ImportedContentIds.epubSourceUrl("three-body")
        val sourceDao = FakePhoneRssSourceDao().apply {
            sources = listOf(
                source(
                    url = sourceUrl,
                    title = "三体全集",
                    deleted = true,
                    deletedAt = 50L,
                    updatedAt = 50L
                )
            )
        }
        val articleDao = FakePhoneArticleDao().apply {
            items = listOf(
                article(
                    id = "chapter",
                    url = ImportedContentIds.epubChapterUrl("three-body", 1, "chapter"),
                    rssSourceUrl = sourceUrl,
                    importedAt = 120L
                )
            )
        }
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = articleDao,
            rssSourceDao = sourceDao,
            deviceId = "test-phone"
        )

        val repaired = repository.repairImportedContentSourceStates()

        val repairedSource = sourceDao.sources.single()
        assertEquals(1, repaired)
        assertEquals(false, repairedSource.deleted)
        assertEquals(0L, repairedSource.deletedAt)
        assertEquals("test-phone", repairedSource.sourceDeviceId)
        assertEquals(120L, repairedSource.updatedAt)
    }

    @Test
    fun deleteRssSource_marksImportedContentArticlesDeleted() = runBlocking {
        val sourceUrl = ImportedContentIds.epubSourceUrl("three-body")
        val sourceDao = FakePhoneRssSourceDao().apply {
            sources = listOf(source(url = sourceUrl, title = "三体全集"))
        }
        val articleDao = FakePhoneArticleDao().apply {
            items = listOf(
                article(
                    id = "chapter",
                    url = ImportedContentIds.epubChapterUrl("three-body", 1, "chapter"),
                    rssSourceUrl = sourceUrl
                )
            )
        }
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = articleDao,
            rssSourceDao = sourceDao,
            deviceId = "test-phone"
        )

        repository.deleteRssSource(sourceUrl)

        assertEquals(true, articleDao.items.single().deleted)
        assertEquals(true, sourceDao.sources.single().deleted)
    }

    @Test
    fun getArticleManifestsForSync_recomputesStaleBodyMetadata() = runBlocking {
        val sourceUrl = ImportedContentIds.ROOT_SOURCE_URL
        val articleDao = FakePhoneArticleDao().apply {
            items = listOf(
                article(
                    id = "stale",
                    url = ImportedContentIds.txtArticleUrl("stale"),
                    rssSourceUrl = sourceUrl,
                    contentText = "新的正文"
                ).copy(
                    syncBodyHash = "stale-hash",
                    syncBodyByteCount = 18L,
                    syncChunkSize = 131_072,
                    syncChunkHashesJson = """["stale-hash"]""",
                    syncMetadataHash = "stale-metadata"
                )
            )
        }
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = articleDao,
            rssSourceDao = FakePhoneRssSourceDao(),
            deviceId = "test-phone"
        )

        val manifest = repository.getArticleManifestsForSync().single()

        assertEquals(false, manifest.chunkHashes.contains("stale-hash"))
        assertEquals(false, articleDao.items.single().syncChunkHashesJson.contains("stale-hash"))
    }

    @Test
    fun getArticleManifestsForSync_marksMissingExternalBodyUnavailable() = runBlocking {
        val sourceUrl = ImportedContentIds.ROOT_SOURCE_URL
        val articleDao = FakePhoneArticleDao().apply {
            items = listOf(
                article(
                    id = "missing-body",
                    url = ImportedContentIds.txtArticleUrl("missing-body"),
                    rssSourceUrl = sourceUrl,
                    contentText = "fake-local-text:missing-body-text"
                ).copy(
                    syncBodyHash = "cached-body",
                    syncBodyByteCount = 1024L,
                    syncChunkSize = 131_072,
                    syncChunkHashesJson = """["cached-chunk"]""",
                    syncMetadataHash = "cached-metadata"
                )
            )
        }
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = articleDao,
            rssSourceDao = FakePhoneRssSourceDao(),
            deviceId = "test-phone",
            articleContentStore = FakeArticleContentStore()
        )

        val manifest = repository.getArticleManifestsForSync().single()
        val outgoing = repository.getArticlesForSync(listOf("missing-body"))

        assertEquals(false, manifest.bodyAvailable)
        assertEquals("cached-body", manifest.bodyHash)
        assertEquals(emptyList<PhoneArticleEntity>(), outgoing)
        assertEquals("cached-body", articleDao.items.single().syncBodyHash)
    }

    private fun source(
        url: String,
        title: String = "示例源",
        deleted: Boolean = false,
        deletedAt: Long = 0L,
        updatedAt: Long = 1L
    ): PhoneRssSourceEntity {
        return PhoneRssSourceEntity(
            url = url,
            sourceDeviceId = "test-phone",
            title = title,
            description = "",
            siteUrl = null,
            imageUrl = null,
            createdAt = 1L,
            updatedAt = updatedAt,
            sortOrder = updatedAt,
            isPinned = false,
            deleted = deleted,
            deletedAt = deletedAt
        )
    }

    private fun article(
        id: String,
        url: String = "https://example.com/$id",
        title: String = id,
        contentHtml: String? = null,
        contentText: String = "正文",
        importedAt: Long = 1L,
        rssSourceUrl: String? = null,
        independentSaved: Boolean = false,
        independentChangedAt: Long = 0L,
        favoriteSaved: Boolean = false,
        favoriteChangedAt: Long = 0L,
        watchLaterSaved: Boolean = false,
        watchLaterChangedAt: Long = 0L,
        deleted: Boolean = false,
        deletedAt: Long = 0L
    ): PhoneArticleEntity {
        return PhoneArticleEntity(
            articleId = id,
            sourceDeviceId = "test-phone",
            url = url,
            title = title,
            siteName = "example.com",
            excerpt = "",
            contentHtml = contentHtml,
            contentText = contentText,
            imageUrl = null,
            contentHash = "hash-$id",
            importedAt = importedAt,
            updatedAt = maxOf(independentChangedAt, favoriteChangedAt, watchLaterChangedAt, 1L),
            independentSaved = independentSaved,
            independentChangedAt = independentChangedAt,
            independentSortOrder = independentChangedAt,
            rssSourceUrl = rssSourceUrl,
            rssSourceTitle = rssSourceUrl?.let { "示例源" },
            favoriteSaved = favoriteSaved,
            favoriteChangedAt = favoriteChangedAt,
            favoriteSortOrder = favoriteChangedAt,
            watchLaterSaved = watchLaterSaved,
            watchLaterChangedAt = watchLaterChangedAt,
            watchLaterSortOrder = watchLaterChangedAt,
            deleted = deleted,
            deletedAt = deletedAt
        )
    }

    private fun PhoneArticleEntity.toManifestEntry(): ArticleSyncManifestEntry {
        return ArticleSyncManifestEntry(
            articleId = articleId,
            sourceDeviceId = sourceDeviceId,
            contentHash = contentHash,
            updatedAt = updatedAt,
            independentChangedAt = independentChangedAt,
            favoriteChangedAt = favoriteChangedAt,
            watchLaterChangedAt = watchLaterChangedAt,
            deletedAt = deletedAt,
            deleted = deleted
        )
    }

    private class FakePhoneSavedItemDao : PhoneSavedItemDao {
        var items: List<PhoneSavedItemEntity> = emptyList()

        override fun observeByType(type: String): Flow<List<PhoneSavedItemEntity>> = emptyFlow()

        override suspend fun deleteByType(type: String) {
            items = items.filterNot { it.type == type }
        }

        override suspend fun upsertAll(items: List<PhoneSavedItemEntity>) {
            this.items = this.items + items
        }
    }

    private class FakePhoneArticleDao : PhoneArticleDao {
        var items: List<PhoneArticleEntity> = emptyList()

        override fun observeRecent(limit: Int): Flow<List<PhoneArticleEntity>> = emptyFlow()

        override fun observeIndependent(): Flow<List<PhoneArticleEntity>> = emptyFlow()

        override fun observeRssArticles(
            importedTextSourceUrl: String,
            importedTextArticlePrefix: String
        ): Flow<List<PhoneArticleEntity>> = emptyFlow()

        override fun observeImportedContentArticles(
            importedTextSourceUrl: String,
            importedTextArticlePrefix: String
        ): Flow<List<PhoneArticleEntity>> = emptyFlow()

        override fun observeFavorites(): Flow<List<PhoneArticleEntity>> = emptyFlow()

        override fun observeWatchLater(): Flow<List<PhoneArticleEntity>> = emptyFlow()

        override suspend fun getById(articleId: String): PhoneArticleEntity? {
            return items.firstOrNull { it.articleId == articleId }
        }

        override suspend fun getByRssSourceUrl(rssSourceUrl: String): List<PhoneArticleEntity> {
            return items.filter { it.rssSourceUrl == rssSourceUrl }
        }

        override fun observeById(articleId: String): Flow<PhoneArticleEntity?> = emptyFlow()

        override suspend fun getAllForSync(): List<PhoneArticleEntity> = items

        override suspend fun updateTitle(articleId: String, title: String, updatedAt: Long) {
            items = items.map { article ->
                if (article.articleId == articleId) {
                    article.copy(title = title, updatedAt = updatedAt)
                } else {
                    article
                }
            }
        }

        override suspend fun updateReadingProgress(articleId: String, progress: Float) {
            items = items.map { article ->
                if (article.articleId == articleId) {
                    article.copy(readingProgress = progress)
                } else {
                    article
                }
            }
        }

        override suspend fun upsert(article: PhoneArticleEntity) {
            items = items.filterNot { it.articleId == article.articleId } + article
        }

        override suspend fun upsertAll(articles: List<PhoneArticleEntity>) {
            articles.forEach { upsert(it) }
        }

        override suspend fun deleteByRssSourceUrl(rssSourceUrl: String) {
            items = items.filterNot { it.rssSourceUrl == rssSourceUrl }
        }
    }

    private class FakePhoneRssSourceDao : PhoneRssSourceDao {
        var sources: List<PhoneRssSourceEntity> = emptyList()

        override fun observeActive(
            importedTextSourceUrl: String
        ): Flow<List<PhoneRssSourceEntity>> = emptyFlow()

        override suspend fun getByUrl(url: String): PhoneRssSourceEntity? {
            return sources.firstOrNull { it.url == url }
        }

        override suspend fun getAllForSync(): List<PhoneRssSourceEntity> = sources

        override suspend fun upsert(source: PhoneRssSourceEntity) {
            sources = sources.filterNot { it.url == source.url } + source
        }

        override suspend fun upsertAll(sources: List<PhoneRssSourceEntity>) {
            sources.forEach { upsert(it) }
        }
    }

    private class FakeArticleContentStore : ArticleContentStore {
        private val texts = mutableMapOf<String, String>()

        override fun markerFor(articleId: String): String = "fake-local-text:$articleId"

        override fun isMarker(value: String): Boolean = value.startsWith("fake-local-text:")

        override fun storeText(articleId: String, text: String): String {
            val marker = markerFor(articleId)
            texts[marker] = text
            return marker
        }

        override fun loadText(marker: String): String? = texts[marker]

        override fun textChunkHandle(marker: String, chunkBytes: Int): StoredTextChunkHandle? {
            val text = texts[marker] ?: return null
            val byteLength = text.toByteArray(Charsets.UTF_8).size.toLong()
            val chunkCount = ((byteLength + chunkBytes - 1L) / chunkBytes)
                .toInt()
                .coerceAtLeast(1)
            return StoredTextChunkHandle(
                marker = marker,
                byteLength = byteLength,
                chunkBytes = chunkBytes,
                chunkCount = chunkCount
            )
        }

        override fun loadTextChunk(marker: String, chunkIndex: Int, chunkBytes: Int): String? {
            if (chunkIndex < 0 || chunkBytes <= 0) return null
            val bytes = texts[marker]?.toByteArray(Charsets.UTF_8) ?: return null
            val nominalStart = chunkIndex * chunkBytes
            if (nominalStart >= bytes.size) return null
            val nominalEnd = (nominalStart + chunkBytes).coerceAtMost(bytes.size)
            val start = bytes.adjustUtf8Boundary(nominalStart)
            val end = bytes.adjustUtf8Boundary(nominalEnd)
            if (end <= start) return ""
            return String(bytes.copyOfRange(start, end), Charsets.UTF_8)
        }

        private fun ByteArray.adjustUtf8Boundary(requested: Int): Int {
            var position = requested.coerceIn(0, size)
            if (position <= 0 || position >= size) return position
            while (position > 0 && (this[position].toInt() and UTF8_CONTINUATION_MASK) == UTF8_CONTINUATION_PREFIX) {
                position -= 1
            }
            return position
        }

        companion object {
            private const val UTF8_CONTINUATION_MASK = 0xC0
            private const val UTF8_CONTINUATION_PREFIX = 0x80
        }
    }
}
