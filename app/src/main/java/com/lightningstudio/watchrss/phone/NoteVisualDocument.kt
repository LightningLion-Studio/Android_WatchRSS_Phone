@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi::class)

package com.lightningstudio.watchrss.phone

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import com.mohamedrejeb.richeditor.model.RichSpan
import com.mohamedrejeb.richeditor.model.RichSpanStyle
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.paragraph.RichParagraph
import com.mohamedrejeb.richeditor.paragraph.type.DefaultParagraph
import com.mohamedrejeb.richeditor.paragraph.type.OrderedList
import com.mohamedrejeb.richeditor.paragraph.type.UnorderedList
import com.lightningstudio.watchrss.phone.data.note.NoteEditorStorage
import org.json.JSONArray
import org.json.JSONObject

/**
 * Standard HTML blocks with paragraph-local editor metadata. The note wire/schema
 * is unchanged and other HTML readers can use the visible fallback. Untouched legacy bodies
 * are never converted. This adapter deliberately targets the pinned rich-editor rc10 model:
 * its Markdown/HTML exporters discard whitespace, em units, and empty paragraphs. Pixel
 * round-trip tests must accompany any rich-editor dependency upgrade.
 */
internal object NoteVisualDocument {

    /** rc10 masks selected span backgrounds in its annotation cache. Refresh that cache when
     * focus changes, retaining the selection (needed by formatting popups) and IME composition.
     * Refreshing a collapsed cursor does not clear its pending typing styles.
     */
    fun focusProjection(editor: RichTextState, focused: Boolean) {
        if (editor.composition != null) return
        if (focused || editor.selection.collapsed) {
            editor.updateAnnotatedString()
        } else {
            val value = editor.textFieldValue
            restoreParagraphs(editor, editor.richParagraphList.toList())
            editor.onTextFieldValueChange(value)
        }
    }

    fun decode(markup: String, editor: RichTextState): String? {
        NoteEditorStorage.sourceText(markup)?.let { return it }
        NoteEditorStorage.richLines(markup)?.let { lines ->
            val blocks = lines.filter(NoteEditorStorage::isRichBlock).map(NoteEditorStorage::readRichBlock)
            val config = blocks.first().getJSONObject("config")
            blocks.forEach { require(sameConfig(config, it.getJSONObject("config"))) }
            applyConfig(editor, config)
            restoreParagraphs(editor, readRichLines(lines, config))
            return null
        }
        val json = NoteEditorStorage.readPayload(markup)
        return when (json.getString("kind")) {
            "source" -> json.getString("text")
            "rich" -> {
                val config = json.getJSONObject("config")
                editor.config.orderedListIndent = config.getInt("orderedIndent")
                editor.config.unorderedListIndent = config.getInt("unorderedIndent")
                editor.config.preserveStyleOnEmptyLine = config.getBoolean("preserveEmptyStyle")
                val rows = json.getJSONArray("paragraphs")
                restoreParagraphs(editor, List(rows.length()) { readRichParagraph(rows.getJSONObject(it)) })
                // null means rich mode; an empty source string is still source mode.
                null
            }
            else -> error("Unsupported phone note editor representation")
        }
    }

    fun isEncoded(markup: String): Boolean = NoteEditorStorage.isEncoded(markup)

    /** The old exporter chose Markdown for ordinary lists and HTML for richer styles.
     * Project native blocks through that same choice before deriving an automatic title.
     */
    fun legacyTitleMarkup(markup: String): String? {
        if (!isEncoded(markup)) return null
        return runCatching {
            val editor = RichTextState()
            if (decode(markup, editor) == null) editor.toNoteStorageMarkup() else null
        }.getOrNull()
    }

    @JvmOverloads
    fun rich(editor: RichTextState, previous: String? = null): String {
        val paragraphs = JSONArray()
        editor.richParagraphList.forEach { paragraphs.put(writeRichParagraph(it)) }
        val config = JSONObject().put("orderedIndent", editor.config.orderedListIndent)
            .put("unorderedIndent", editor.config.unorderedListIndent)
            .put("preserveEmptyStyle", editor.config.preserveStyleOnEmptyLine)
        val fallback = RichTextState()
        fallback.config.orderedListIndent = editor.config.orderedListIndent
        fallback.config.unorderedListIndent = editor.config.unorderedListIndent
        fallback.config.preserveStyleOnEmptyLine = editor.config.preserveStyleOnEmptyLine
        val retained = retainedLines(previous, editor, paragraphs, config)
        return (0 until paragraphs.length()).joinToString("\n") { index ->
            retained[index] ?: run {
                val row = paragraphs.getJSONObject(index)
                restoreParagraphs(fallback, listOf(readRichParagraph(row)))
                NoteEditorStorage.richBlock(row, config,
                    fallback.toHtml().restoreNoteImagePlaceholders().normalizeNoteImageDimensionAttributes())
            }
        }
    }

    private fun applyConfig(editor: RichTextState, config: JSONObject) {
        editor.config.orderedListIndent = config.getInt("orderedIndent")
        editor.config.unorderedListIndent = config.getInt("unorderedIndent")
        editor.config.preserveStyleOnEmptyLine = config.getBoolean("preserveEmptyStyle")
    }

    private fun sameConfig(a: JSONObject, b: JSONObject): Boolean =
        a.getInt("orderedIndent") == b.getInt("orderedIndent") &&
            a.getInt("unorderedIndent") == b.getInt("unorderedIndent") &&
            a.getBoolean("preserveEmptyStyle") == b.getBoolean("preserveEmptyStyle")

    private fun readLegacyLine(line: String, config: JSONObject): RichParagraph {
        val parsed = RichTextState()
        applyConfig(parsed, config)
        if (line.isNoteRichHtmlMarkup()) parsed.setHtml(line.toNoteEditorMarkup())
        else parsed.setMarkdown(line.toNoteEditorMarkup())
        require(parsed.richParagraphList.size == 1)
        return parsed.richParagraphList.single().also { paragraph ->
            // rc10 normalizes an isolated list item to 1; its original literal marker is
            // authoritative when assembling unchanged legacy lines beside native blocks.
            (paragraph.type as? OrderedList)?.let { type ->
                Regex("^(\\d+)\\. ").find(line)?.groupValues?.get(1)?.toIntOrNull()?.let { type.number = it }
            }
        }
    }

    private fun readRichLines(lines: List<String>, config: JSONObject): List<RichParagraph> {
        var precedingMarkerStyle: SpanStyle? = null
        return lines.map { line ->
            val native = NoteEditorStorage.isRichBlock(line)
            val paragraph: RichParagraph = if (native) readRichParagraph(NoteEditorStorage.readRichBlock(line).getJSONObject("paragraph"))
                else readLegacyLine(line, config)
            val type = paragraph.type as? OrderedList
            if (type == null) precedingMarkerStyle = null else {
                // Legacy rc10 lists inherit marker style from the start of the run. Keep
                // that context when a neighbouring paragraph uses native metadata.
                if (!native) precedingMarkerStyle?.let { type.startTextSpanStyle = it }
                precedingMarkerStyle = type.startTextSpanStyle
            }
            paragraph
        }
    }

    private fun sameParagraph(a: JSONObject, b: JSONObject): Boolean {
        // Marker width is measured by layout, not a user edit. Reopening unchanged legacy
        // lines recomputes it. Native marker number/style and all body styles still compare.
        fun key(row: JSONObject) = JSONObject(row.toString()).apply { remove("markerWidth") }.toString()
        return key(a) == key(b)
    }

    /** Preserve legacy lines around an edited hunk. Converting the whole ancestor to v2,
     * even with per-paragraph attributes, would itself destroy the merge's shared lines.
     * Only reuse mappings verified against the original complete parsed document.
     */
    private fun retainedLines(previous: String?, editor: RichTextState, rows: JSONArray,
        config: JSONObject): Map<Int, String> = runCatching {
        if (previous == null) return@runCatching emptyMap()
        val before = RichTextState()
        if (isEncoded(previous)) require(decode(previous, before) == null)
        else if (previous.isNoteRichHtmlMarkup()) before.setHtml(previous.toNoteEditorMarkup())
        else before.setMarkdown(previous.toNoteEditorMarkup())
        require(before.config.orderedListIndent == editor.config.orderedListIndent &&
            before.config.unorderedListIndent == editor.config.unorderedListIndent &&
            before.config.preserveStyleOnEmptyLine == editor.config.preserveStyleOnEmptyLine)
        val lines = previous.split('\n')
        val old = before.richParagraphList.map(::writeRichParagraph)
        require(lines.size == old.size)
        readRichLines(lines, config).forEachIndexed { index, paragraph ->
            require(sameParagraph(old[index], writeRichParagraph(paragraph)))
        }
        var prefix = 0
        while (prefix < minOf(old.size, rows.length()) && sameParagraph(old[prefix], rows.getJSONObject(prefix))) prefix++
        var suffix = 0
        while (suffix < minOf(old.size, rows.length()) - prefix &&
            sameParagraph(old[old.lastIndex - suffix], rows.getJSONObject(rows.length() - 1 - suffix))) suffix++
        buildMap {
            repeat(prefix) { put(it, lines[it]) }
            repeat(suffix) { put(rows.length() - 1 - it, lines[lines.lastIndex - it]) }
        }
    }.getOrDefault(emptyMap())

    private fun writeRichParagraph(paragraph: RichParagraph): JSONObject {
        val row = JSONObject().put("style", writeParagraph(paragraph.paragraphStyle))
            .put("children", writeChildren(paragraph.children))
        when (val type = paragraph.type) {
            is OrderedList -> row.put("type", "ordered").put("number", type.number)
                .put("markerStyle", writeSpan(type.startTextSpanStyle)).put("markerWidth", writeUnit(type.startTextWidth))
            is UnorderedList -> row.put("type", "unordered").put("markerWidth", writeUnit(type.startTextWidth))
            else -> row.put("type", "paragraph")
        }
        return row
    }

    /** rc10 builds ranges/projection first, then silently renumbers lists from 1.
     * Restore the saved marker values before any subsequent cache/input refresh. The built
     * text and ranges already refer to those original numbers, including their UTF-16 width.
     */
    private fun restoreParagraphs(editor: RichTextState, paragraphs: List<RichParagraph>) {
        val markers = paragraphs.map { paragraph ->
            (paragraph.type as? OrderedList)?.let { it.number to it.startTextSpanStyle }
        }
        editor.updateRichParagraphList(paragraphs)
        paragraphs.forEachIndexed { index, paragraph ->
            markers[index]?.let { (number, style) ->
                (paragraph.type as OrderedList).also { it.number = number; it.startTextSpanStyle = style }
            }
        }
    }

    private fun readRichParagraph(row: JSONObject): RichParagraph {
        val type = when (row.getString("type")) {
            "ordered" -> OrderedList(row.getInt("number"),
                startTextSpanStyle = readSpan(row.getJSONObject("markerStyle")),
                startTextWidth = readUnit(row.getJSONObject("markerWidth")))
            "unordered" -> UnorderedList(startTextWidth = readUnit(row.getJSONObject("markerWidth")))
            else -> DefaultParagraph()
        }
        return RichParagraph(paragraphStyle = readParagraph(row.getJSONObject("style")), type = type).also { paragraph ->
            readChildren(row.getJSONArray("children"), paragraph, null, paragraph.children)
        }
    }

    private fun writeChildren(spans: List<RichSpan>): JSONArray = JSONArray().apply {
        spans.forEach { span ->
            put(JSONObject().put("text", span.text).put("style", writeSpan(span.spanStyle))
                .put("rich", when (val style = span.richSpanStyle) {
                    is RichSpanStyle.Link -> JSONObject().put("type", "link").put("url", style.url)
                    is RichSpanStyle.Code -> JSONObject().put("type", "code")
                    RichSpanStyle.Default -> JSONObject().put("type", "default")
                    else -> error("Unsupported note rich span")
                }).put("children", writeChildren(span.children)))
        }
    }

    private fun readChildren(rows: JSONArray, paragraph: RichParagraph, parent: RichSpan?, children: MutableList<RichSpan>) {
        repeat(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            val rich = row.getJSONObject("rich")
            val style = when (rich.getString("type")) {
                "link" -> RichSpanStyle.Link(rich.getString("url"))
                "code" -> RichSpanStyle.Code()
                else -> RichSpanStyle.Default
            }
            val span = RichSpan(paragraph = paragraph, parent = parent, text = row.getString("text"),
                spanStyle = readSpan(row.getJSONObject("style")), richSpanStyle = style)
            readChildren(row.getJSONArray("children"), paragraph, span, span.children)
            children.add(span)
        }
    }

    private fun writeUnit(unit: TextUnit): JSONObject = JSONObject().apply {
        if (unit.isSpecified) { put("type", if (unit.isSp) "sp" else "em"); put("value", unit.value.toDouble()) }
    }
    private fun readUnit(json: JSONObject): TextUnit = when (json.optString("type")) {
        "sp" -> json.getDouble("value").toFloat().sp
        "em" -> json.getDouble("value").toFloat().em
        else -> TextUnit.Unspecified
    }
    private fun color(color: Color): String = color.value.toString()
    private fun readColor(value: String): Color = Color(value.toULong())

    private fun writeSpan(style: SpanStyle): JSONObject = JSONObject().apply {
        put("color", color(style.color)); put("background", color(style.background))
        put("size", writeUnit(style.fontSize)); put("spacing", writeUnit(style.letterSpacing))
        style.fontWeight?.let { put("weight", it.weight) }
        style.fontStyle?.let { put("italic", it == FontStyle.Italic) }
        style.fontSynthesis?.let { put("synthesis", it.toString()) }
        style.fontFamily?.let { family -> put("family", when (family) {
            FontFamily.Default -> "default"
            FontFamily.SansSerif -> "sans"
            FontFamily.Serif -> "serif"
            FontFamily.Monospace -> "mono"
            FontFamily.Cursive -> "cursive"
            else -> error("Unsupported note span font family")
        }) }
        style.fontFeatureSettings?.let { put("features", it) }
        style.localeList?.let { put("locales", it.joinToString(",") { locale -> locale.toLanguageTag() }) }
        style.baselineShift?.let { put("baseline", it.multiplier.toDouble()) }
        style.textGeometricTransform?.let { put("scale", it.scaleX.toDouble()); put("skew", it.skewX.toDouble()) }
        style.textDecoration?.let { put("underline", it.contains(TextDecoration.Underline)); put("strike", it.contains(TextDecoration.LineThrough)) }
        style.shadow?.let { put("shadow", JSONObject().put("color", color(it.color)).put("x", it.offset.x.toDouble())
            .put("y", it.offset.y.toDouble()).put("blur", it.blurRadius.toDouble())) }
        // No editor action or supported HTML/Markdown import creates platform/brush/stroke styles.
        require(style.platformStyle == null && style.brush == null && style.drawStyle == null)
    }

    private fun readSpan(json: JSONObject): SpanStyle {
        val decoration = if (!json.has("underline")) null else TextDecoration.combine(buildList {
            if (json.getBoolean("underline")) add(TextDecoration.Underline)
            if (json.getBoolean("strike")) add(TextDecoration.LineThrough)
        })
        val shadow = json.optJSONObject("shadow")?.let { Shadow(readColor(it.getString("color")),
            Offset(it.getDouble("x").toFloat(), it.getDouble("y").toFloat()), it.getDouble("blur").toFloat()) }
        return SpanStyle(color = readColor(json.getString("color")), background = readColor(json.getString("background")),
            fontSize = readUnit(json.getJSONObject("size")), letterSpacing = readUnit(json.getJSONObject("spacing")),
            fontWeight = if (json.has("weight")) FontWeight(json.getInt("weight")) else null,
            fontStyle = if (!json.has("italic")) null else if (json.getBoolean("italic")) FontStyle.Italic else FontStyle.Normal,
            fontSynthesis = when (json.optString("synthesis")) {
                FontSynthesis.All.toString() -> FontSynthesis.All
                FontSynthesis.None.toString() -> FontSynthesis.None
                FontSynthesis.Style.toString() -> FontSynthesis.Style
                FontSynthesis.Weight.toString() -> FontSynthesis.Weight
                else -> null
            },
            fontFamily = when (json.optString("family")) {
                "default" -> FontFamily.Default
                "sans" -> FontFamily.SansSerif
                "serif" -> FontFamily.Serif
                "mono" -> FontFamily.Monospace
                "cursive" -> FontFamily.Cursive
                else -> null
            },
            fontFeatureSettings = json.optString("features").takeIf { json.has("features") },
            localeList = json.optString("locales").takeIf { json.has("locales") }?.let(::LocaleList),
            baselineShift = if (json.has("baseline")) BaselineShift(json.getDouble("baseline").toFloat()) else null,
            textGeometricTransform = if (json.has("scale")) TextGeometricTransform(json.getDouble("scale").toFloat(), json.getDouble("skew").toFloat()) else null,
            textDecoration = decoration, shadow = shadow)
    }

    private val aligns = listOf(TextAlign.Unspecified, TextAlign.Left, TextAlign.Right, TextAlign.Center, TextAlign.Justify, TextAlign.Start, TextAlign.End)
    private val directions = listOf(TextDirection.Unspecified, TextDirection.Ltr, TextDirection.Rtl, TextDirection.Content,
        TextDirection.ContentOrLtr, TextDirection.ContentOrRtl)

    private fun writeParagraph(style: ParagraphStyle): JSONObject = JSONObject().apply {
        put("align", aligns.indexOf(style.textAlign)); put("direction", directions.indexOf(style.textDirection))
        put("height", writeUnit(style.lineHeight))
        style.textIndent?.let { put("first", writeUnit(it.firstLine)); put("rest", writeUnit(it.restLine)) }
        require(style.platformStyle == null && style.lineHeightStyle == null)
    }
    private fun readParagraph(json: JSONObject): ParagraphStyle = ParagraphStyle(
        textAlign = aligns[json.getInt("align")], textDirection = directions[json.getInt("direction")],
        lineHeight = readUnit(json.getJSONObject("height")),
        textIndent = if (!json.has("first")) null else TextIndent(readUnit(json.getJSONObject("first")), readUnit(json.getJSONObject("rest"))))
}
