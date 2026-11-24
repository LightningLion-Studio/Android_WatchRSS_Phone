package com.lightningstudio.watchrss.phone.data.ai

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class AiDisclosureRepositoryTest {
    @get:Rule val server = MockWebServer()

    private var cache: String? = null
    private var bundled: String? = snapshot(1, "随包模型")
    private val support = AiDisclosureFeature.SUPPORT

    private fun repository() = AiDisclosureRepository(
        server.url("/").toString(), OkHttpClient(), { cache }, { cache = it }, { bundled }
    )

    @Test
    fun cachesFullRemoteSnapshotAndReusesItAcrossRepositoryInstances() = runBlocking {
        val remote = snapshot(3, "所用模型：客服测试模型\n上线编号：测试01")
        server.enqueue(MockResponse().setBody(remote))
        assertEquals("所用模型：客服测试模型\n上线编号：测试01", repository().displayText(support))
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/functions/v1/ai-disclosure?platform=phone", request.path)
        assertNull(request.getHeader("Authorization"))
        assertEquals(remote, cache)

        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals("总结测试模型", repository().displayText(AiDisclosureFeature.ARTICLE_SUMMARY))
    }

    @Test
    fun fallsBackFromInvalidCacheToBundledThenToUnavailable() = runBlocking {
        cache = "{"
        server.enqueue(MockResponse().setBody("[]"))
        assertEquals("随包模型", repository().displayText(support))

        bundled = snapshot(0, "__FILL_BEFORE_RELEASE__")
        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(AiDisclosureRepository.UNAVAILABLE, repository().displayText(support))
    }

    @Test
    fun validEmptyRemoteSnapshotClearsOldDisclosure() = runBlocking {
        cache = snapshot(2, "旧模型")
        val empty = """{"revision":3,"updatedAt":1795000000000,"features":{"ai_support":[]}}"""
        server.enqueue(MockResponse().setBody(empty))
        assertEquals(AiDisclosureRepository.UNAVAILABLE, repository().displayText(support))
        assertEquals(empty, cache)

        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(AiDisclosureRepository.UNAVAILABLE, repository().displayText(support))
    }

    @Test
    fun lowerRevisionCannotOverwriteNewerCache() = runBlocking {
        val newer = snapshot(5, "新模型")
        cache = newer
        server.enqueue(MockResponse().setBody(snapshot(4, "旧模型")))
        assertEquals("新模型", repository().displayText(support))
        assertEquals(newer, cache)
    }

    @Test
    fun keepsMultipleDisplayTextsInServerOrder() = runBlocking {
        val remote = """{"revision":3,"updatedAt":1795000000000,"features":{"ai_support":[
            {"displayText":"  所用模型：甲\n备案号：01"},{"displayText":"所用模型：乙"}
        ]}}"""
        server.enqueue(MockResponse().setBody(remote))
        assertEquals("  所用模型：甲\n备案号：01\n所用模型：乙", repository().displayText(support))
    }

    private fun snapshot(revision: Int, text: String): String {
        val quotedText = JSONObject.quote(text)
        return """{"revision":$revision,"updatedAt":1795000000000,"features":{
            "ai_support":[{"displayText":$quotedText}],
            "article_summary":[{"displayText":"总结测试模型"}]
        }}"""
    }
}
