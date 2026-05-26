package com.lightningstudio.watchrss.phone.data.repo

import com.lightningstudio.watchrss.phone.data.db.PhoneArticleDao
import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
import com.lightningstudio.watchrss.phone.data.db.PhoneRssSourceDao
import com.lightningstudio.watchrss.phone.data.db.PhoneRssSourceEntity
import com.lightningstudio.watchrss.phone.data.db.PhoneSavedItemDao
import com.lightningstudio.watchrss.phone.data.db.PhoneSavedItemEntity
import com.lightningstudio.watchrss.phone.data.importer.ImportedRssItem
import com.lightningstudio.watchrss.phone.data.importer.ImportedRssSource
import com.lightningstudio.watchrss.phone.data.importer.ImportedWebArticle
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
            )
        )
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = articleDao,
            rssSourceDao = FakePhoneRssSourceDao(),
            deviceId = "test-phone"
        )

        val ids = repository.getArticlesForSync().map { it.articleId }.toSet()

        assertEquals(setOf("saved-rss", "removed-save-state", "independent"), ids)
    }

    private fun article(
        id: String,
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
            url = "https://example.com/$id",
            title = id,
            siteName = "example.com",
            excerpt = "",
            contentHtml = null,
            contentText = "正文",
            imageUrl = null,
            contentHash = "hash-$id",
            importedAt = 1L,
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

        override fun observeRssArticles(): Flow<List<PhoneArticleEntity>> = emptyFlow()

        override fun observeFavorites(): Flow<List<PhoneArticleEntity>> = emptyFlow()

        override fun observeWatchLater(): Flow<List<PhoneArticleEntity>> = emptyFlow()

        override suspend fun getById(articleId: String): PhoneArticleEntity? {
            return items.firstOrNull { it.articleId == articleId }
        }

        override fun observeById(articleId: String): Flow<PhoneArticleEntity?> = emptyFlow()

        override suspend fun getAllForSync(): List<PhoneArticleEntity> = items

        override suspend fun upsert(article: PhoneArticleEntity) {
            items = items.filterNot { it.articleId == article.articleId } + article
        }

        override suspend fun upsertAll(articles: List<PhoneArticleEntity>) {
            articles.forEach { upsert(it) }
        }
    }

    private class FakePhoneRssSourceDao : PhoneRssSourceDao {
        var sources: List<PhoneRssSourceEntity> = emptyList()

        override fun observeActive(): Flow<List<PhoneRssSourceEntity>> = emptyFlow()

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
}
