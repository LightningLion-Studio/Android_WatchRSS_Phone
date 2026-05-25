package com.lightningstudio.watchrss.phone.connection.bluetooth

import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
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
            favoriteSaved = true,
            favoriteChangedAt = 21L,
            favoriteSortOrder = 21L,
            watchLaterSaved = false,
            watchLaterChangedAt = 0L,
            watchLaterSortOrder = 0L,
            deleted = false,
            deletedAt = 0L
        )

        val request = LibrarySyncPayload.buildRequest("phone", listOf(article))
        val parsed = LibrarySyncPayload.parseArticles(request).single()

        assertEquals(article.contentHtml, parsed.contentHtml)
        assertEquals(article.contentText, parsed.contentText)
        assertTrue(parsed.favoriteSaved)
        assertEquals("syncLibrary", request.getString("action"))
    }
}
