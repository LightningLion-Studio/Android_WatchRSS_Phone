package com.lightningstudio.watchrss.phone.data.backup

import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
import com.lightningstudio.watchrss.phone.data.db.PhoneRssSourceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudRssStateCodecTest {
    @Test
    fun roundTripsReadStateAndEncryptedSubscriptionPayload() {
        val article = article(isRead = true)
        val source = source()

        val encoded = CloudRssStateCodec.encode(
            articles = listOf(article),
            exportedAt = 123L,
            sources = listOf(source)
        )

        val decoded = CloudRssStateCodec.decode(encoded).single()
        assertTrue(decoded.isRead)
        assertEquals(article.readingPositionBytes, decoded.readingPositionBytes)
        assertEquals(article.readingPositionContentHash, decoded.readingPositionContentHash)
        assertEquals(article.readingPositionChangedAt, decoded.readingPositionChangedAt)
        assertEquals(source, CloudRssStateCodec.decodeSources(encoded).single())
    }

    @Test
    fun oldStateWithoutReadOrSourcesRemainsCompatible() {
        val encoded = """
            {"format":"watchrss-rss-state","version":1,"exportedAt":1,"articles":[{
              "articleId":"a","url":"https://example.com/a","readingProgress":0.5
            }]}
        """.trimIndent().toByteArray()

        val decoded = CloudRssStateCodec.decode(encoded).single()
        assertFalse(decoded.isRead)
        assertEquals(0L, decoded.readingPositionBytes)
        assertEquals("", decoded.readingPositionContentHash)
        assertEquals(0L, decoded.readingPositionChangedAt)
        assertTrue(CloudRssStateCodec.decodeSources(encoded).isEmpty())
    }

    private fun source() = PhoneRssSourceEntity(
        url = "https://example.com/feed",
        sourceDeviceId = "phone-1",
        title = "Example",
        description = "Feed",
        siteUrl = "https://example.com",
        imageUrl = null,
        createdAt = 1L,
        updatedAt = 2L,
        sortOrder = 3L,
        isPinned = true,
        deleted = false,
        deletedAt = 0L
    )

    private fun article(isRead: Boolean) = PhoneArticleEntity(
        articleId = "article-1",
        sourceDeviceId = "phone-1",
        url = "https://example.com/a",
        title = "A",
        siteName = "Example",
        excerpt = "",
        contentHtml = null,
        contentText = "",
        imageUrl = null,
        contentHash = "hash",
        importedAt = 1L,
        updatedAt = 2L,
        independentSaved = false,
        independentChangedAt = 0L,
        independentSortOrder = 0L,
        rssSourceUrl = "https://example.com/feed",
        rssSourceTitle = "Example",
        favoriteSaved = false,
        favoriteChangedAt = 0L,
        favoriteSortOrder = 0L,
        watchLaterSaved = false,
        watchLaterChangedAt = 0L,
        watchLaterSortOrder = 0L,
        deleted = false,
        deletedAt = 0L,
        readingProgress = 0.25f,
        readingPositionBytes = 250L,
        readingPositionContentHash = "hash",
        readingPositionChangedAt = 7L,
        isRead = isRead
    )
}
