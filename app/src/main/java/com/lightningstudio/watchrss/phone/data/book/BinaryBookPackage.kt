package com.lightningstudio.watchrss.phone.data.book

import com.lightningstudio.watchrss.phone.cloud.CloudProtocolV2
import com.lightningstudio.watchrss.phone.connection.bluetooth.ArticleSyncBody.BodyJsonCursor
import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.B2Frames
import com.lightningstudio.watchrss.phone.data.model.ImportedContentIds
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/** A package stays in the filesystem even if a single JSONL string spans many segments. */
internal object BinaryBookPackage {
    data class Header(val title: String, val source: String, val kind: String, val count: Int, val instance: String?)
    data class Chapter(val title: String, val url: String, val text: File, val html: File?, val contentHash: String)

    private fun BodyJsonCursor.fields(visit: (String) -> Unit) {
        expect('{')
        val seen = mutableSetOf<String>()
        do {
            val key = boundedString(32)
            require(seen.add(key))
            expect(':')
            visit(key)
            val next = nextNonWhitespace()
            if (next == '}'.code) {
                finishLine()
                return
            }
            require(next == ','.code)
        } while (true)
    }

    private fun BodyJsonCursor.header(): Header {
        var title: String? = null
        var source: String? = null
        var kind: String? = null
        var count = 0
        var instance: String? = null
        var format = false
        var version = false
        fields { key ->
            when (key) {
                "title" -> title = boundedString(16384)
                "sourceUrl" -> source = boundedString(16384)
                "kind" -> kind = boundedString(8)
                "chapterCount" -> count = positiveInteger()
                "instanceId" -> instance = boundedString(32).also { require(it.matches(Regex("[0-9a-f]{32}"))) }
                "format" -> format = boundedString(32) == "watchrss-book"
                "version" -> version = positiveInteger() == 1
                else -> skipValue()
            }
        }
        val url = requireNotNull(source)
        require(format && version && count in 1..100_000 && kind in setOf("txt", "epub"))
        require(isImportUrl(url))
        if (ImportedContentIds.isCliSingleTextUrl(url.trim())) require(count == 1 && kind == "txt")
        return Header(requireNotNull(title), url, requireNotNull(kind), count, instance)
    }

    private val importedUrl = Regex("^https://watchrss\\.local/(?:import-txt-novel(?:/|$)|import-epub(?:/|$)|import-content/txt/)",
        RegexOption.IGNORE_CASE)
    private val instanceUrl = Regex("^watchrss://import/instance-v1/[a-f0-9]{32}(?:/|$)")
    private fun isImportUrl(url: String) = importedUrl.containsMatchIn(url) ||
        ImportedContentIds.isCliSingleTextUrl(url) || instanceUrl.containsMatchIn(url)

    private fun BodyJsonCursor.body(file: File, work: () -> Unit): Boolean {
        return OutputStreamWriter(file.outputStream(), Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)).buffered().use { out ->
            var chars = 0
            var pendingCr = false
            val present = copyNullableString(object : java.io.Writer() {
                override fun write(c: Int) {
                    if (++chars % 32768 == 0) work()
                    if (pendingCr) {
                        out.write('\n'.code)
                        pendingCr = false
                        if (c == '\n'.code) return
                    }
                    if (c == '\r'.code) pendingCr = true else out.write(c)
                }
                override fun write(cbuf: CharArray, off: Int, len: Int) {
                    for (i in off until off + len) write(cbuf[i].code)
                }
                override fun flush() = out.flush()
                override fun close() = Unit
            })
            if (pendingCr) out.write('\n'.code)
            present
        }
    }

    suspend fun read(file: File, scratch: File, work: () -> Unit,
        start: (Header) -> Unit,
        chapter: suspend (Header, Int, Chapter) -> Unit): String {
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        val buffer = ByteArray(65536)
        val text = File(scratch, "text")
        val html = File(scratch, "html")
        return InputStreamReader(file.inputStream(), decoder).buffered().use { reader ->
            val cursor = BodyJsonCursor(reader, bounded = true, jsonLine = true)
            val header = cursor.header()
            start(header)
            val fingerprint = MessageDigest.getInstance("SHA-256")
            fingerprint.update(CloudProtocolV2.lp("watchrss/book/fingerprint/v1", header.count.toString()))
            for (index in 1..header.count) {
                work()
                var title: String? = null
                var url: String? = null
                var actual = 0
                var hasText = false
                var hasHtml = false
                cursor.fields { key ->
                    when (key) {
                        "chapterIndex" -> actual = cursor.positiveInteger()
                        "title" -> title = cursor.boundedString(16384)
                        "url" -> url = cursor.boundedString(16384)
                        "contentText" -> hasText = cursor.body(text, work)
                        "contentHtml" -> hasHtml = cursor.body(html, work)
                        else -> cursor.skipValue()
                    }
                }
                val link = requireNotNull(url)
                require(actual == index && hasText && isImportUrl(link))
                val cli = ImportedContentIds.isCliSingleTextUrl(header.source.trim())
                if (cli || ImportedContentIds.isCliSingleTextUrl(link.trim())) require(cli && header.source.equals(link, ignoreCase = true))
                val heading = requireNotNull(title)
                val digest = MessageDigest.getInstance("SHA-256")
                val bodyHash = MessageDigest.getInstance("SHA-256")
                digest.update(CloudProtocolV2.lp("watchrss/book/chapter/v1", index.toString(), BookSync.normalized(heading)))
                digest.update(ByteBuffer.allocate(4).putInt(Math.toIntExact(text.length())).array())
                text.inputStream().use { body ->
                    while (true) {
                        val n = body.read(buffer)
                        if (n < 0) break
                        digest.update(buffer, 0, n)
                        bodyHash.update(buffer, 0, n)
                        work()
                    }
                }
                fingerprint.update(CloudProtocolV2.lp(B2Frames.hex(digest.digest())))
                chapter(header, index, Chapter(heading, link, text, html.takeIf { hasHtml }, B2Frames.hex(bodyHash.digest())))
                text.delete()
                html.delete()
            }
            require(cursor.nextNonWhitespace() == -1)
            B2Frames.hex(fingerprint.digest())
        }
    }
}
