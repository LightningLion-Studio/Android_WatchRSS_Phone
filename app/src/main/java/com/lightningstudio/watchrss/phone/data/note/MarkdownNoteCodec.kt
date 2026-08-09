package com.lightningstudio.watchrss.phone.data.note

import org.jsoup.parser.Parser
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
        note.folderId?.let { appendLine("folder: $it") }
        appendLine("created-at: ${note.createdAt}")
        appendLine("updated-at: ${note.updatedAt}")
        appendLine("---")
        append(note.markdown)
        if (!note.markdown.endsWith('\n')) appendLine()
    }

    fun parse(input: String): ImportedMarkdownNote {
        val normalized = input.replace("\r\n", "\n").replace('\r', '\n')
        if (!normalized.startsWith("---\n")) return ImportedMarkdownNote(null, null, null, normalized)
        val end = normalized.indexOf("\n$FRONT_MATTER_END\n", startIndex = 4)
        if (end < 0) return ImportedMarkdownNote(null, null, null, normalized)
        val fields = normalized.substring(4, end).lineSequence()
            .mapNotNull { line -> line.substringBefore(':', "").trim().takeIf { it.isNotBlank() }?.let { it to line.substringAfter(':').trim() } }
            .toMap()
        if (fields["watchrss-note"] != "1") return ImportedMarkdownNote(null, null, null, normalized)
        return ImportedMarkdownNote(
            noteId = fields["id"]?.takeIf { it.isNotBlank() },
            folderId = fields["folder"]?.takeIf { it.isNotBlank() },
            title = fields["title"]?.takeIf { it.isNotBlank() },
            markdown = normalized.substring(end + FRONT_MATTER_END.length + 2)
        )
    }

    fun toPlainText(markdown: String): String {
        val hasHtml = HTML_TAG.containsMatchIn(markdown)
        val withoutHtml = if (hasHtml) {
            markdown
                .replace(Regex("(?is)<img\\b[^>]*alt=[\"']([^\"']*)[\"'][^>]*>"), "[图片：$1]")
                .replace(Regex("(?is)<img\\b[^>]*>"), "[图片]")
                .replace(Regex("(?i)<br\\s*/?>"), "\n")
                .replace(Regex("(?i)<li(?:\\s[^>]*)?>"), "- ")
                .replace(Regex("(?i)</(?:p|div|h[1-6]|li|ul|ol|blockquote)>"), "\n")
                .replace(HTML_TAG, "")
                .let { Parser.unescapeEntities(it, false) }
        } else {
            markdown
        }
        val plain = withoutHtml
            .replace(Regex("!\\[([^]]*)]\\([^)]*\\)"), "[图片：$1]")
            .replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
            .replace(Regex("(?m)^#{1,6}\\s+"), "")
            .replace(Regex("(?m)^>\\s?"), "")
            .replace(Regex("(?m)^\\s*([-*+] |\\d+\\. )"), "")
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
