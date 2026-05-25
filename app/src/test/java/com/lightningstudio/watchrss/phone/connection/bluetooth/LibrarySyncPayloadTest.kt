package com.lightningstudio.watchrss.phone.connection.bluetooth

import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
import com.lightningstudio.watchrss.phone.data.db.PhoneRssSourceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySyncPayloadTest {
    @Test
    fun buildRequest_roundTripsCompressedArticleContent() {
        val article = PhoneArticleEntity(
            articleId = "article-1",
            sourceDeviceId = "phone",
            url = "https://example.com/a",
            title = "标题",
            siteName = "example.com",
            excerpt = "摘要",
            contentHtml = "<article><p>正文</p></article>",
            contentText = "正文",
            imageUrl = "https://example.com/a.jpg",
            contentHash = "hash",
            importedAt = 10L,
            updatedAt = 20L,
            independentSaved = true,
            independentChangedAt = 22L,
            independentSortOrder = 22L,
            rssSourceUrl = "https://example.com/feed.xml",
            rssSourceTitle = "示例源",
            favoriteSaved = true,
            favoriteChangedAt = 21L,
            favoriteSortOrder = 21L,
            watchLaterSaved = false,
            watchLaterChangedAt = 0L,
            watchLaterSortOrder = 0L,
            deleted = false,
            deletedAt = 0L
        )

        val source = PhoneRssSourceEntity(
            url = "https://example.com/feed.xml",
            sourceDeviceId = "phone",
            title = "示例源",
            description = "源描述",
            siteUrl = "https://example.com",
            imageUrl = null,
            createdAt = 1L,
            updatedAt = 2L,
            sortOrder = 2L,
            deleted = false,
            deletedAt = 0L
        )

        val request = LibrarySyncPayload.buildRequest("phone", listOf(article), listOf(source))
        val parsed = LibrarySyncPayload.parseArticles(request).single()
        val parsedSource = LibrarySyncPayload.parseRssSources(request).single()

        assertEquals(article.contentHtml, parsed.contentHtml)
        assertEquals(article.contentText, parsed.contentText)
        assertTrue(parsed.independentSaved)
        assertEquals(source.title, parsedSource.title)
        assertTrue(parsed.favoriteSaved)
        assertEquals("syncLibrary", request.getString("action"))
    }
}
