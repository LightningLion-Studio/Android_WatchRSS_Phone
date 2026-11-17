package com.lightningstudio.watchrss.phone.data.note

import com.lightningstudio.watchrss.phone.NoteVisualDocument
import org.jsoup.parser.Parser
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

data class ImportedMarkdownNote(
    val noteId: String?,
    val folderId: String?,
    val title: String?,
    val markdown: String
)

/** Portable Markdown envelope. Unknown front matter is deliberately retained as document text. */
object MarkdownNoteCodec {
    private const val FRONT_MATTER_END = "---"

    fun export(note: NoteEntity): String = buildString {
        appendLine("---")
        appendLine("watchrss-note: 1")
        appendLine("id: ${note.noteId}")
        appendLine("title: ${JSONObject.quote(note.title)}")
        note.folderId?.let { appendLine("folder: $it") }
        appendLine("created-at: ${note.createdAt}")
        appendLine("updated-at: ${note.updatedAt}")
        appendLine("---")
        append(note.markdown)
    }

    fun parse(input: String): ImportedMarkdownNote {
        // Scan only the envelope. Normalizing the entire file changes CRLF/CR in the body.
        var cursor = when {
            input.startsWith("---\r\n") -> 5
            input.startsWith("---\n") || input.startsWith("---\r") -> 4
            else -> return ImportedMarkdownNote(null, null, null, input)
        }
        val fields = mutableMapOf<String, String>()
        var bodyStart = -1
        while (cursor < input.length) {
            val end = input.indexOfAny(charArrayOf('\r', '\n'), cursor).let { if (it < 0) input.length else it }
            val line = input.substring(cursor, end)
            val next = when {
                end == input.length -> end
                input[end] == '\r' && input.getOrNull(end + 1) == '\n' -> end + 2
                else -> end + 1
            }
            if (line == FRONT_MATTER_END) {
                bodyStart = next
                break
            }
            val key = line.substringBefore(':', "").trim()
            if (key.isNotEmpty()) fields[key] = line.substringAfter(':').trim()
            if (next == input.length) break
            cursor = next
        }
        if (bodyStart < 0 || fields["watchrss-note"] != "1") return ImportedMarkdownNote(null, null, null, input)
        return ImportedMarkdownNote(
            noteId = fields["id"]?.takeIf { it.isNotBlank() },
            folderId = fields["folder"]?.takeIf { it.isNotBlank() },
            title = fields["title"]?.takeIf { it.isNotBlank() }?.let(::decodeTitle),
            markdown = input.substring(bodyStart)
        )
    }

    private fun decodeTitle(value: String): String {
        if (!value.startsWith('"')) return value // Legacy unquoted front matter.
        return runCatching {
            val encoded = JSONArray("[$value]")
            require(encoded.length() == 1)
            encoded.getString(0)
        }.getOrDefault(value)
    }

    fun toPlainText(markdown: String): String = projectText(markdown, preserveListNumbers = false)

    // Literal ordered-list numbers count as words only in the title projection.
    internal fun titlePlainText(markdown: String): String = projectText(markdown, preserveListNumbers = true)

    private fun projectText(markdown: String, preserveListNumbers: Boolean): String {
        val content = NoteEditorStorage.sourceText(markdown)
            ?: (if (preserveListNumbers) NoteVisualDocument.legacyTitleMarkup(markdown) else null)
            ?: markdown
        val hasHtml = HTML_TAG.containsMatchIn(content)
        val withoutHtml = if (hasHtml) {
            content
                .replace(Regex("(?is)<img\\b[^>]*alt=[\"']([^\"']*)[\"'][^>]*>"), "[图片：$1]")
                .replace(Regex("(?is)<img\\b[^>]*>"), "[图片]")
                .replace(Regex("(?i)<br\\s*/?>"), "\n")
                .replace(Regex("(?i)<li(?:\\s[^>]*)?>"), "- ")
                .replace(Regex("(?i)</(?:p|div|h[1-6]|li|ul|ol|blockquote)>"), "\n")
                .replace(HTML_TAG, "")
                .let { Parser.unescapeEntities(it, false) }
        } else {
            content
        }
        val plain = withoutHtml
            .replace(Regex("!\\[([^]]*)]\\([^)]*\\)"), "[图片：$1]")
            .replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
            .replace(Regex("(?m)^#{1,6}\\s+"), "")
            .replace(Regex("(?m)^>\\s?"), "")
            .replace(Regex(if (preserveListNumbers) "(?m)^\\s*[-*+] " else "(?m)^\\s*([-*+] |\\d+\\. )"), "")
            .replace("**", "").replace("__", "").replace("~~", "").replace("`", "")
        return if (hasHtml) {
            plain.lineSequence()
                .map(String::trimEnd)
                .filterNot { it.isBlank() }
                .joinToString("\n")
                .trim()
        } else {
            plain.trim()
        }
    }

    fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private val HTML_TAG = Regex(
        "(?is)</?(?:p|div|span|b|strong|i|em|u|s|strike|del|ol|ul|li|br|h[1-6]|" +
            "blockquote|code|a|img|mark)(?:\\s[^>]*)?>"
    )
}
