package com.lightningstudio.watchrss.phone.data.backup

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream

class CloudRelayStreamDeviceTest {
    @Test fun plainAndCompressedBodiesPreserveParagraphs() = runBlocking {
        val text = "第一段\r\n\r\n第二段"
        val out = ByteArrayOutputStream(); GZIPOutputStream(out).use { it.write(text.toByteArray()) }
        val rows = JSONArray().put(JSONObject().put("articleId", "plain").put("url", "https://watchrss.local/import-content/a")
            .put("contentText", text).put("contentHtml", "<p>第一段</p><p>第二段</p>"))
            .put(JSONObject().put("articleId", "gzip").put("url", "https://watchrss.local/import-content/b")
                .put("contentTextGzip", Base64.getEncoder().encodeToString(out.toByteArray())))
        val source = JSONObject().put("url", "https://example.test/rss").put("title", "fixture")
        val input = JSONObject().put("articles", rows).put("rssSources", JSONArray().put(source)).toString().byteInputStream()
        val values = mutableListOf<String>(); var sources = 0
        CloudRelayStream.read(input, { values.add(it.contentText) }, { sources++ })
        assertEquals(listOf(text, text), values)
        assertEquals(1, sources)
    }

    @Test fun deliversFirstRowBeforeReadingWholeLibraryAndRejectsTruncatedInput() = runBlocking {
        val rows = JSONArray()
        repeat(200) { i -> rows.put(JSONObject().put("articleId", "row$i").put("url", "https://example.test/$i").put("contentText", "x".repeat(1024))) }
        val bytes = JSONObject().put("articles", rows).toString().toByteArray()
        val input = object : ByteArrayInputStream(bytes) { fun consumed() = pos }
        var count = 0
        CloudRelayStream.read(input, { if (count++ == 0) assertTrue(input.consumed() < bytes.size / 2) }, {})
        assertEquals(200, count)
        var failed = false
        try { CloudRelayStream.read(bytes.copyOf(bytes.size - 1).inputStream(), {}, {}) }
        catch (_: Exception) { failed = true }
        assertTrue(failed)
    }
}
