package com.lightningstudio.watchrss.phone.data.about

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class AboutSectionRepositoryTest {
    @get:Rule val server = MockWebServer()

    private var cache: String? = null
    private val filled = """{"revision":1,"items":[{"title":"产品说明","body":"第一行\n第二行"},{"title":null,"body":"无标题说明"}]}"""
    private val items = listOf(AboutSectionItem("产品说明", "第一行\n第二行"), AboutSectionItem(null, "无标题说明"))

    private fun repository() = AboutSectionRepository(
        server.url("/").toString(), OkHttpClient(), { cache }, { cache = it }
    )

    @Test
    fun loadsAndCachesPublicPlainTextInOrder() = runBlocking {
        server.enqueue(MockResponse().setBody(filled))
        assertEquals(items, repository().load())
        assertEquals(filled, cache)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/functions/v1/about-section?platform=phone", request.path)
        assertNull(request.getHeader("Authorization"))
    }

    @Test
    fun successfulEmptySnapshotReplacesPreviouslyCachedItems() = runBlocking {
        cache = filled
        val empty = """{"revision":2,"items":[]}"""
        server.enqueue(MockResponse().setBody(empty))
        assertEquals(emptyList<AboutSectionItem>(), repository().load())
        assertEquals(empty, cache)

        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(emptyList<AboutSectionItem>(), repository().load())
    }

    @Test
    fun fallsBackToCachedItemsThenToEmpty() = runBlocking {
        cache = filled
        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(items, repository().load())

        cache = null
        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(emptyList<AboutSectionItem>(), repository().load())
        assertNull(cache)
    }

    @Test
    fun invalidResponseCannotReplaceTheCache() = runBlocking {
        cache = filled
        server.enqueue(MockResponse().setBody("""{"revision":2,"items":[{"title":false,"body":"文本"}]}"""))
        assertEquals(items, repository().load())
        assertEquals(filled, cache)
    }
}
