package com.lightningstudio.watchrss.phone.data.note

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Reads legacy snapshots and stores rich-text formatting per paragraph. */
internal object NoteEditorStorage {
    private const val LEGACY = "<div data-watchrss-editor-v1=\""
    private const val RICH = "<div data-watchrss-editor-block-v2=\""

    fun isEncoded(markup: String): Boolean = markup.startsWith(LEGACY) || markup.startsWith(RICH) ||
        lines(markup).any(::isRichBlock)

    fun richLines(markup: String): List<String>? = markup.split('\n').takeIf { rows -> rows.any(::isRichBlock) }
    fun isRichBlock(line: String): Boolean = line.startsWith(RICH)
    fun readRichBlock(line: String): JSONObject = readBlock(line, RICH, 2, compressed = true)

    fun readPayload(markup: String): JSONObject {
        if (markup.startsWith(LEGACY)) return readBlock(markup, LEGACY, 1, compressed = true)
        val rows = JSONArray()
        var config: JSONObject? = null
        lines(markup).forEach { line ->
            val block = readBlock(line, RICH, 2, compressed = true)
            require(block.getString("kind") == "rich")
            val settings = block.getJSONObject("config")
            if (config == null) config = settings else require(
                config!!.getInt("orderedIndent") == settings.getInt("orderedIndent") &&
                    config!!.getInt("unorderedIndent") == settings.getInt("unorderedIndent") &&
                    config!!.getBoolean("preserveEmptyStyle") == settings.getBoolean("preserveEmptyStyle"))
            rows.put(block.getJSONObject("paragraph"))
        }
        require(rows.length() > 0)
        return JSONObject().put("kind", "rich").put("config", config).put("paragraphs", rows)
    }

    private fun readBlock(markup: String, prefix: String, version: Int, compressed: Boolean): JSONObject {
        require(markup.startsWith(prefix) && markup.endsWith("</div>"))
        val end = markup.indexOf('"', prefix.length)
        require(end >= 0)
        val bytes = Base64.getUrlDecoder().decode(markup.substring(prefix.length, end))
        val payload = if (!compressed) bytes else GZIPInputStream(ByteArrayInputStream(bytes)).use { stream ->
            val result = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                require(result.size().toLong() + count <= 64L * 1024 * 1024)
                result.write(buffer, 0, count)
            }
            result.toByteArray()
        }
        val json = JSONObject(payload.toString(Charsets.UTF_8))
        require(json.getInt("version") == version)
        val bodyStart = markup.indexOf('>', end) + 1
        require(bodyStart > 0)
        val fallback = markup.substring(bodyStart, markup.length - "</div>".length)
        require(json.getString("htmlHash") == MarkdownNoteCodec.sha256(fallback))
        return json
    }

    fun sourceText(markup: String): String? {
        if (!markup.startsWith(LEGACY)) return null
        return runCatching {
            val json = readBlock(markup, LEGACY, 1, compressed = true)
            if (json.getString("kind") == "source") json.getString("text") else null
        }.getOrNull()
    }

    fun richBlock(paragraph: JSONObject, config: JSONObject, html: String): String {
        val fallback = html.replace("\r", "&#13;").replace("\n", "&#10;")
        val json = JSONObject().put("version", 2).put("kind", "rich").put("config", config)
            .put("paragraph", paragraph).put("htmlHash", MarkdownNoteCodec.sha256(fallback))
        val bytes = ByteArrayOutputStream().apply {
            GZIPOutputStream(this).use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
        }.toByteArray()
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        return "$RICH$payload\" style=\"white-space:pre-wrap\">$fallback</div>"
    }

    private fun lines(text: String): List<String> = buildList {
        var start = 0
        var cursor = 0
        while (cursor < text.length) {
            val c = text[cursor]
            if (c != '\r' && c != '\n') { cursor++; continue }
            val end = cursor
            cursor += if (c == '\r' && text.getOrNull(cursor + 1) == '\n') 2 else 1
            add(text.substring(start, end))
            start = cursor
        }
        if (start < text.length || isEmpty()) add(text.substring(start))
    }
}
