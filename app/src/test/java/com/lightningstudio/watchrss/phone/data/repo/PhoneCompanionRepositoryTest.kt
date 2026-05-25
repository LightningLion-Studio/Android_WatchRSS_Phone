package com.lightningstudio.watchrss.phone.data.repo

import com.lightningstudio.watchrss.phone.data.db.PhoneArticleDao
import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
import com.lightningstudio.watchrss.phone.data.db.PhoneSavedItemDao
import com.lightningstudio.watchrss.phone.data.db.PhoneSavedItemEntity
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
}
