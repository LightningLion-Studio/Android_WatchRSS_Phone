package com.lightningstudio.watchrss.phone

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import com.lightningstudio.watchrss.phone.data.note.MarkdownMergeResult
import com.lightningstudio.watchrss.phone.data.note.MarkdownNoteCodec
import com.lightningstudio.watchrss.phone.data.note.MarkdownThreeWayMerge
import com.lightningstudio.watchrss.phone.data.note.deriveNoteTitle
import com.mohamedrejeb.richeditor.model.RichTextState
import java.util.Base64

private fun utf8(text: String): String = Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

fun main() {
    val atoms = listOf("中文家庭作业", "👩🏽‍💻", "e\u0301clair", "مرحبا", "क्\u200dष", "𠮷野家", "🇨🇳",
        "A\u00a0B", "字".repeat(85), "a".repeat(79) + "e\u0301", "👨‍👩‍👧‍👦".repeat(15), "한글")
    val forms = listOf("%s next end", "1. %s 中文 gamma", "6. %s\n10. next", "**%s** and next",
        "<p>%s</p><p>终</p>", "&gt; %s tail", "\u3000%s\r\nnext", "![%s](assets/a.jpg)",
        "[%s](https://example.invalid)", "\n# %s and next", "\t%s  ", "%s\r\n\r尾部")

    fun emit(id: String, reference: String, stored: String, wantedMode: String = "-",
             preferredSourceMode: Boolean? = null) {
        val projection = MarkdownNoteCodec.titlePlainText(stored)
        val draft = NoteEditorDraft(stored, preferredSourceMode)
        println(listOf(id, utf8(reference), utf8(projection), utf8(deriveNoteTitle(projection)),
            if (draft.sourceMode) "source" else "rich", wantedMode).joinToString("\t"))
    }

    for ((i, atom) in atoms.withIndex()) for ((j, form) in forms.withIndex()) {
        val text = form.replace("%s", atom)
        emit("legacy/$i/$j", text, text)
        emit("source/$i/$j", text, text, "source", preferredSourceMode = true)
    }

    for ((i, atom) in atoms.withIndex()) {
        val item = "$atom 中文 next"
        for ((name, markdown) in listOf(
            "plain" to "$item\ntail",
            "ordered" to "1. $item\n2. tail",
            "unordered" to "- $item\n- tail",
            "heading" to "# $item\ntail",
            "bold" to "1. **$item**\n2. tail",
            "mixed" to "# Start\n\n1. $item\n2. tail",
            "html" to "1. $item\n2. tail")) {
            val editor = RichTextState().setMarkdown(markdown)
            if (name == "html") {
                editor.addSpanStyle(SpanStyle(color = Color.Red, fontWeight = FontWeight.Bold), TextRange(3, 4))
            }
            val legacy = editor.toNoteStorageMarkup()
            emit("native/$name/$i", legacy, NoteVisualDocument.rich(editor), "rich")
        }
    }

    // Diff3 control against canonical plaintext, plus mode and title of genuine save/reopens.
    val tokens = listOf("alpha", "中文", "e\u0301", "👩🏽‍💻", "مرحبا", "09", "a & b", "< literal", "two  spaces", "tab\tword")
    for (i in 0..39) {
        val text = "a${tokens[i % 10]}\nbeta${i / 10}\nc${tokens[(i * 7 + 3) % 10]}"
        val editor = RichTextState().setText(text)
        val legacy = editor.toNoteStorageMarkup()
        val native = NoteVisualDocument.rich(editor)
        for ((format, base) in listOf("legacy" to legacy, "native" to native)) {
            fun edit(first: Boolean): Triple<String, String, String> {
                val draft = NoteEditorDraft(base)
                check(!draft.sourceMode)
                val start = if (first) 0 else draft.editor.annotatedString.text.lastIndexOf(" c") + 1
                check(first || start > 0)
                draft.editor.replaceTextRange(TextRange(start, start + 1), if (first) "A" else "C")
                val stored = draft.storageMarkup()
                val wanted = draft.editor.toText()
                val reopened = NoteEditorDraft(stored)
                check(!reopened.sourceMode && wanted == reopened.editor.toText())
                val reference = draft.editor.toNoteStorageMarkup()
                emit("edit/$format/$i/$first", reference, stored, "rich")
                return Triple(stored, wanted, reference)
            }
            val (left, leftText) = edit(true)
            val (right, rightText) = edit(false)
            val ancestor = NoteEditorDraft(base).editor.toText()
            val control = MarkdownThreeWayMerge.merge(ancestor, leftText, rightText) as MarkdownMergeResult.Merged
            val merged = MarkdownThreeWayMerge.merge(base, left, right) as MarkdownMergeResult.Merged
            val result = NoteEditorDraft(merged.markdown)
            check(!result.sourceMode && result.editor.toText() == control.markdown)
            emit("merge/$format/$i", result.editor.toNoteStorageMarkup(), merged.markdown, "rich")
        }
    }

    for ((i, survivor) in listOf("plain", "中文", "e\u0301clair", "👩🏽‍💻", "# heading", "**bold**",
        "1. item", "plain\r\nend", "plain\n", "").withIndex()) {
        for ((where, input) in listOf("start" to "x\n$survivor", "end" to "$survivor\nx")) {
            val draft = NoteEditorDraft(input, preferredSourceMode = true)
            check(draft.sourceMode)
            emit("pre-delete/$where/$i", input, input, "source", preferredSourceMode = true)
            draft.editSource(survivor)
            val saved = draft.storageMarkup()
            check(saved == survivor)
            val reopened = NoteEditorDraft(saved, preferredSourceMode = draft.sourceMode)
            check(reopened.sourceMode && reopened.sourceText == survivor)
            emit("delete/$where/$i", survivor, saved, "source", preferredSourceMode = draft.sourceMode)
        }
    }
}
