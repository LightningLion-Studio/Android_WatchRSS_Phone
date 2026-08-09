package com.lightningstudio.watchrss.phone.data.importer

import com.lightningstudio.watchrss.phone.data.model.ImportedContentIds
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

enum class LocalContentImportKind {
    TXT,
    TXT_CHAPTERS,
    EPUB
}

data class ImportedLocalContent(
    val kind: LocalContentImportKind,
    val source: ImportedRssSource,
    /** A lightweight chapter plan. Chapter bodies and hashes are built only after confirmation. */
    val txtChapterPlan: TxtChapterImportPlan? = null
)

data class TxtChapterImportPlan(
    val bookTitle: String,
    val fileName: String,
    val contentKey: String,
    val text: String,
    val headings: List<TxtChapterHeading>
)

data class TxtChapterHeading(
    val start: Int,
    val title: String
)

class LocalContentImporter {
    fun importFile(fileName: String, mimeType: String?, bytes: ByteArray): ImportedLocalContent {
        require(bytes.isNotEmpty()) { "文件内容为空" }
        val safeName = fileName.ifBlank { "未命名文件" }
        val lowerName = safeName.lowercase()
        val lowerMime = mimeType.orEmpty().lowercase()
        return when {
            isMarkdownFileName(lowerName) ||
                lowerMime == "text/markdown" ||
                lowerMime == "text/x-markdown" -> {
                error("Markdown 文件应导入备忘录")
            }
            lowerName.endsWith(".epub") || lowerMime == "application/epub+zip" -> {
                importEpub(safeName, bytes)
            }
            lowerName.endsWith(".txt") || lowerMime.startsWith("text/") -> {
                importTxt(safeName, bytes)
            }
            else -> error("只支持 TXT 和 EPUB 文件")
        }
    }

    private fun importTxt(fileName: String, bytes: ByteArray): ImportedLocalContent {
        val text = decodeText(bytes)
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
        require(text.isNotBlank()) { "TXT 内容为空" }
        val title = fileNameWithoutExtension(fileName).ifBlank { firstTextLine(text) }
        val contentKey = WebArticleImporter.sha256("$fileName\n$text").take(32)
        val item = ImportedRssItem(
            url = ImportedContentIds.txtArticleUrl(contentKey),
            title = title,
            excerpt = excerpt(text),
            contentHtml = null,
            contentText = text,
            imageUrl = null,
            guid = contentKey
        )
        val continuousSource = ImportedRssSource(
            url = ImportedContentIds.ROOT_SOURCE_URL,
            title = ImportedContentIds.ROOT_SOURCE_TITLE,
            description = "从手机导入的 TXT 内容",
            siteUrl = null,
            imageUrl = null,
            items = listOf(item)
        )
        return ImportedLocalContent(
            kind = LocalContentImportKind.TXT,
            source = continuousSource,
            txtChapterPlan = buildTxtChapterPlan(
                bookTitle = title,
                fileName = fileName,
                contentKey = contentKey,
                text = text
            )
        )
    }

    /**
     * Splits only heading-shaped standalone lines.  Requiring at least two headings prevents
     * prose such as “第一节课” from unexpectedly becoming a one-chapter book.
     */
    private fun buildTxtChapterPlan(
        bookTitle: String,
        fileName: String,
        contentKey: String,
        text: String
    ): TxtChapterImportPlan? {
        val headings = TXT_CHAPTER_HEADING.findAll(text)
            .map { match ->
                TxtChapterHeading(
                    start = match.range.first,
                    title = match.value.trim().replace(Regex("""\s+"""), " ")
                )
            }
            .toList()
        if (headings.size < MIN_TXT_CHAPTERS) return null

        return TxtChapterImportPlan(
            bookTitle = bookTitle,
            fileName = fileName,
            contentKey = contentKey,
            text = text,
            headings = headings
        )
    }

    /** Called only after the user chooses chapter import. */
    suspend fun buildTxtChapterSource(plan: TxtChapterImportPlan): ImportedRssSource? = coroutineScope {
        val bookKey = WebArticleImporter.sha256("${plan.fileName}\n${plan.contentKey}").take(32)
        val preface = plan.text.substring(0, plan.headings.first().start).trim()
        val hasPreface = preface.length >= MIN_PREFACE_CHARS
        val ranges = plan.headings.mapIndexed { headingIndex, heading ->
            TxtChapterRange(
                index = headingIndex + 1 + if (hasPreface) 1 else 0,
                title = heading.title,
                start = heading.start,
                end = plan.headings.getOrNull(headingIndex + 1)?.start ?: plan.text.length
            )
        }
        // Hashing and copying chapter text dominates large novels. Bound concurrency so a
        // multi-core phone benefits without spawning thousands of tiny jobs or raising peak RAM.
        val workerCount = minOf(MAX_CHAPTER_BUILD_WORKERS, Runtime.getRuntime().availableProcessors())
            .coerceAtLeast(1)
        val batchSize = (ranges.size + workerCount - 1) / workerCount
        val chapterItems = ranges.chunked(batchSize)
            .map { batch ->
                async(Dispatchers.Default) {
                    batch.mapNotNull { range ->
                        val chapterText = plan.text.substring(range.start, range.end).trim()
                        chapterText.takeIf { it.length >= MIN_CHAPTER_CHARS }?.let { text ->
                            txtChapterItem(bookKey, range.index, range.title, text)
                        }
                    }
                }
            }
            .awaitAll()
            .flatten()
        val chapters = buildList {
            if (hasPreface) add(txtChapterItem(bookKey, 1, "前言", preface))
            addAll(chapterItems)
        }
        if (chapters.size < MIN_TXT_CHAPTERS) return@coroutineScope null
        ImportedRssSource(
            url = ImportedContentIds.txtNovelSourceUrl(bookKey),
            title = plan.bookTitle,
            description = "从 TXT 分章节导入：${plan.fileName}",
            siteUrl = null,
            imageUrl = null,
            items = chapters
        )
    }

    private fun txtChapterItem(
        bookKey: String,
        chapterIndex: Int,
        title: String,
        text: String
    ): ImportedRssItem {
        val chapterKey = WebArticleImporter.sha256("$title\n$text").take(16)
        return ImportedRssItem(
            url = ImportedContentIds.txtNovelChapterUrl(bookKey, chapterIndex, chapterKey),
            title = title,
            excerpt = excerpt(text),
            contentHtml = null,
            contentText = text,
            imageUrl = null,
            guid = "${chapterIndex}-${chapterKey}"
        )
    }

    private fun importEpub(fileName: String, bytes: ByteArray): ImportedLocalContent {
        val entries = readZipEntries(bytes)
        val containerXml = entries["META-INF/container.xml"]
            ?: error("EPUB 缺少 container.xml")
        val container = Jsoup.parse(decodeText(containerXml), "", Parser.xmlParser())
        val packagePath = container.getElementsByTag("rootfile")
            .firstOrNull()
            ?.attr("full-path")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: error("EPUB 缺少 OPF 路径")
        val packageBytes = entries[normalizeZipPath(packagePath)]
            ?: error("EPUB 缺少 OPF 文件")
        val packageDoc = Jsoup.parse(decodeText(packageBytes), "", Parser.xmlParser())
        val packageBasePath = packagePath.substringBeforeLast('/', missingDelimiterValue = "")
        val bookTitle = metadataText(packageDoc, "title")
            .ifBlank { fileNameWithoutExtension(fileName) }
            .ifBlank { "未命名 EPUB" }
        val manifest = packageDoc.getElementsByTag("item")
            .mapNotNull { item ->
                val id = item.attr("id").trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val href = item.attr("href").trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val mediaType = item.attr("media-type").trim()
                id to EpubManifestItem(
                    path = resolveZipPath(packageBasePath, href),
                    mediaType = mediaType,
                    properties = item.attr("properties")
                        .trim()
                        .split(Regex("""\s+"""))
                        .filter { it.isNotBlank() }
                        .toSet()
                )
            }
            .toMap()
        val spineIds = packageDoc.getElementsByTag("itemref")
            .mapNotNull { it.attr("idref").trim().takeIf { id -> id.isNotBlank() } }
        val chapterItems = spineIds
            .mapNotNull { manifest[it] }
            .ifEmpty {
                manifest.values.filter { item ->
                    item.mediaType.contains("html", ignoreCase = true) ||
                        item.path.endsWith(".xhtml", ignoreCase = true) ||
                        item.path.endsWith(".html", ignoreCase = true) ||
                        item.path.endsWith(".htm", ignoreCase = true)
                }
            }
        require(chapterItems.isNotEmpty()) { "EPUB 没有可导入章节" }
        val bookKey = WebArticleImporter.sha256(bytes.toString(Charsets.ISO_8859_1)).take(32)
        val tocEntries = parseEpubToc(packageDoc, manifest, entries)
            .ifEmpty { parseSpineHtmlToc(chapterItems, entries) }
        val tocTitleByPath = tocEntries
            .filter { it.title.isMeaningfulTitle() }
            .groupBy { it.path }
            .mapValues { (_, entries) -> entries.first().title }
        val parsedChapters = chapterItems.mapIndexedNotNull { index, item ->
            val chapterBytes = entries[item.path] ?: return@mapIndexedNotNull null
            parseEpubChapter(
                chapterIndex = index + 1,
                chapterPath = item.path,
                tocTitle = tocTitleByPath[item.path],
                bytes = chapterBytes
            )
        }
        val chapterUrlByPath = parsedChapters.associate { chapter ->
            chapter.path to ImportedContentIds.epubChapterUrl(bookKey, chapter.index, chapter.chapterKey)
        }
        val chapters = parsedChapters.map { chapter ->
            val url = chapterUrlByPath.getValue(chapter.path)
            ImportedRssItem(
                url = url,
                title = chapter.title,
                excerpt = excerpt(chapter.text),
                contentHtml = chapter.bodyHtml
                    ?.let { rewriteEpubInternalLinks(it, chapter.path, chapterUrlByPath) }
                    ?.let { "<article>$it</article>" },
                contentText = chapter.text,
                imageUrl = null,
                guid = "${chapter.index}-${chapter.chapterKey}"
            )
        }
        require(chapters.isNotEmpty()) { "EPUB 没有可导入章节正文" }
        return ImportedLocalContent(
            kind = LocalContentImportKind.EPUB,
            source = ImportedRssSource(
                url = ImportedContentIds.epubSourceUrl(bookKey),
                title = bookTitle,
                description = "从 EPUB 导入：$fileName",
                siteUrl = null,
                imageUrl = null,
                items = chapters
            )
        )
    }

    private fun parseEpubChapter(
        chapterIndex: Int,
        chapterPath: String,
        tocTitle: String?,
        bytes: ByteArray
    ): ParsedEpubChapter? {
        val doc = Jsoup.parse(decodeText(bytes), "", Parser.xmlParser())
        doc.outputSettings().prettyPrint(false)
        doc.select("script,style,noscript,template,svg,form,button").remove()
        val body = doc.selectFirst("body") ?: doc.body() ?: return null
        val text = body.text().trim()
        if (text.isBlank()) return null
        val title = firstMeaningfulText(
            tocTitle,
            body.selectFirst(".sgc-toc-title")?.text(),
            body.selectFirst("h1,h2,h3")?.text(),
            doc.selectFirst("title")?.text(),
            fileNameWithoutExtension(chapterPath),
            firstShortTextLine(text),
            "第 $chapterIndex 章"
        )
        val bodyHtml = body.html()
            .trim()
            .takeIf { it.isNotBlank() }
        val chapterKey = WebArticleImporter.sha256("$chapterPath\n$text").take(16)
        return ParsedEpubChapter(
            index = chapterIndex,
            path = chapterPath,
            title = title,
            text = text,
            bodyHtml = bodyHtml,
            chapterKey = chapterKey
        )
    }

    private fun parseEpubToc(
        packageDoc: Document,
        manifest: Map<String, EpubManifestItem>,
        entries: Map<String, ByteArray>
    ): List<EpubTocEntry> {
        val navItem = manifest.values.firstOrNull { "nav" in it.properties }
        if (navItem != null) {
            val navEntries = parseEpub3Nav(entries[navItem.path], navItem.path)
            if (navEntries.isNotEmpty()) return navEntries
        }

        val spineTocId = packageDoc.getElementsByTag("spine")
            .firstOrNull()
            ?.attr("toc")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val ncxItem = spineTocId?.let { manifest[it] }
            ?: manifest.values.firstOrNull { item ->
                item.mediaType.equals("application/x-dtbncx+xml", ignoreCase = true) ||
                    item.path.endsWith(".ncx", ignoreCase = true)
            }
        return parseEpub2Ncx(ncxItem?.let { entries[it.path] }, ncxItem?.path)
    }

    private fun parseEpub3Nav(bytes: ByteArray?, navPath: String): List<EpubTocEntry> {
        if (bytes == null) return emptyList()
        val doc = Jsoup.parse(decodeText(bytes), "", Parser.xmlParser())
        val tocNav = doc.getElementsByTag("nav")
            .firstOrNull { element -> element.hasTokenAttribute("toc") }
            ?: return emptyList()
        return tocNav.select("a[href]").mapNotNull { anchor ->
            val title = anchor.text().trim().takeIf { it.isMeaningfulTitle() }
                ?: return@mapNotNull null
            val target = resolveEpubHref(navPath, anchor.attr("href")) ?: return@mapNotNull null
            EpubTocEntry(path = target.path, title = title)
        }
    }

    private fun parseEpub2Ncx(bytes: ByteArray?, ncxPath: String?): List<EpubTocEntry> {
        if (bytes == null || ncxPath == null) return emptyList()
        val doc = Jsoup.parse(decodeText(bytes), "", Parser.xmlParser())
        return doc.getAllElements()
            .filter { it.normalName().equals("navpoint", ignoreCase = true) }
            .mapNotNull { navPoint ->
                val title = navPoint.firstDescendantText("text")
                    .takeIf { it.isMeaningfulTitle() }
                    ?: return@mapNotNull null
                val href = navPoint.firstDescendantAttr("content", "src")
                    .takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val target = resolveEpubHref(ncxPath, href) ?: return@mapNotNull null
                EpubTocEntry(path = target.path, title = title)
            }
    }

    private fun parseSpineHtmlToc(
        chapterItems: List<EpubManifestItem>,
        entries: Map<String, ByteArray>
    ): List<EpubTocEntry> {
        val chapterPaths = chapterItems.mapTo(mutableSetOf()) { it.path }
        return chapterItems.firstNotNullOfOrNull { item ->
            val bytes = entries[item.path] ?: return@firstNotNullOfOrNull null
            val doc = Jsoup.parse(decodeText(bytes), "", Parser.xmlParser())
            val links = doc.select("a[href]").mapNotNull { anchor ->
                val target = resolveEpubHref(item.path, anchor.attr("href")) ?: return@mapNotNull null
                if (target.path !in chapterPaths) return@mapNotNull null
                val title = cleanupTocTitle(anchor.text()).takeIf { it.isMeaningfulTitle() }
                    ?: return@mapNotNull null
                EpubTocEntry(path = target.path, title = title)
            }
            if (isLikelyHtmlToc(doc, links)) links else null
        }.orEmpty()
    }

    private fun isLikelyHtmlToc(doc: Document, links: List<EpubTocEntry>): Boolean {
        if (links.size < MIN_HTML_TOC_LINKS) return false
        val title = listOf(
            doc.selectFirst("title")?.text(),
            doc.selectFirst("h1,h2,h3,.sgc-toc-title")?.text()
        ).joinToString(" ").lowercase()
        val normalized = title.replace(Regex("""\s+"""), "")
        return normalized.contains("目录") ||
            normalized.contains("contents") ||
            normalized.contains("tableofcontents") ||
            links.size >= MIN_HTML_TOC_FALLBACK_LINKS
    }

    private fun rewriteEpubInternalLinks(
        bodyHtml: String,
        currentPath: String,
        chapterUrlByPath: Map<String, String>
    ): String {
        val doc = Jsoup.parseBodyFragment(bodyHtml)
        doc.outputSettings().prettyPrint(false)
        doc.select("a[href]").forEach { anchor ->
            val target = resolveEpubHref(currentPath, anchor.attr("href")) ?: return@forEach
            val chapterUrl = chapterUrlByPath[target.path] ?: return@forEach
            val href = target.fragment?.takeIf { it.isNotBlank() }?.let { "$chapterUrl#$it" } ?: chapterUrl
            anchor.attr("href", href)
        }
        return doc.body().html().trim()
    }

    private fun resolveEpubHref(baseFilePath: String, href: String): EpubHref? {
        val trimmed = href.trim()
        if (trimmed.isBlank() || trimmed.startsWith("#")) {
            val fragment = trimmed.removePrefix("#").takeIf { it.isNotBlank() }
            return EpubHref(path = baseFilePath, fragment = fragment)
        }
        if (trimmed.contains(':')) {
            val scheme = trimmed.substringBefore(':')
            if (scheme.matches(Regex("""[A-Za-z][A-Za-z0-9+.-]*"""))) return null
        }
        val hrefWithoutFragment = trimmed.substringBefore('#')
        val fragment = trimmed.substringAfter('#', missingDelimiterValue = "")
            .takeIf { it.isNotBlank() }
        val basePath = baseFilePath.substringBeforeLast('/', missingDelimiterValue = "")
        val resolved = resolveZipPath(basePath, hrefWithoutFragment)
        return EpubHref(path = resolved, fragment = fragment)
    }

    private fun readZipEntries(bytes: ByteArray): Map<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    result[normalizeZipPath(entry.name)] = zip.readBytes()
                }
                zip.closeEntry()
            }
        }
        require(result.isNotEmpty()) { "EPUB 文件为空" }
        return result
    }

    private fun metadataText(doc: Document, name: String): String {
        val suffix = ":$name"
        return doc.getAllElements()
            .firstOrNull { element ->
                element.normalName().equals(name, ignoreCase = true) ||
                    element.tagName().equals(name, ignoreCase = true) ||
                    element.tagName().endsWith(suffix, ignoreCase = true)
            }
            ?.text()
            ?.trim()
            .orEmpty()
    }

    private fun decodeText(bytes: ByteArray): String {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            return bytes.copyOfRange(3, bytes.size).toString(Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16BE)
        }
        return decodeStrict(bytes, Charsets.UTF_8)
            ?: decodeStrict(bytes, Charset.forName("GB18030"))
            ?: bytes.toString(Charsets.UTF_8)
    }

    private fun decodeStrict(bytes: ByteArray, charset: Charset): String? {
        return try {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    private fun excerpt(text: String): String {
        return text.replace(Regex("""\s+"""), " ")
            .trim()
            .take(MAX_EXCERPT_CHARS)
    }

    private fun fileNameWithoutExtension(fileName: String): String {
        val baseName = fileName.substringAfterLast('/')
        return baseName
            .substringBeforeLast('.', missingDelimiterValue = baseName)
            .trim()
    }

    private fun firstTextLine(text: String): String {
        return text.lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            .orEmpty()
    }

    private fun firstShortTextLine(text: String): String {
        return firstTextLine(text)
            .takeIf { it.length <= MAX_TITLE_CHARS }
            .orEmpty()
    }

    private fun firstMeaningfulText(vararg values: String?): String {
        return values.firstNotNullOfOrNull { value ->
            cleanupTocTitle(value).takeIf { it.isMeaningfulTitle() }
        }.orEmpty()
    }

    private fun cleanupTocTitle(value: String?): String {
        return value.orEmpty()
            .replace('\u00A0', ' ')
            .replace(Regex("""^\s*[§•·・\-–—>»]+"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun String?.isMeaningfulTitle(): Boolean {
        val value = this?.trim().orEmpty()
        if (value.isBlank()) return false
        val normalized = value
            .lowercase()
            .replace(Regex("""\s+"""), "")
            .removeSuffix(".xhtml")
            .removeSuffix(".html")
            .removeSuffix(".htm")
        return normalized !in GENERIC_EPUB_TITLES
    }

    private fun Element.hasTokenAttribute(token: String): Boolean {
        return attributes().asList().any { attribute ->
            val key = attribute.key.lowercase()
            if (key != "type" && !key.endsWith(":type")) return@any false
            attribute.value.split(Regex("""\s+""")).any { it.equals(token, ignoreCase = true) }
        }
    }

    private fun Element.firstDescendantText(tagName: String): String {
        return getAllElements()
            .firstOrNull { it !== this && it.normalName().equals(tagName, ignoreCase = true) }
            ?.text()
            ?.trim()
            .orEmpty()
    }

    private fun Element.firstDescendantAttr(tagName: String, attrName: String): String {
        return getAllElements()
            .firstOrNull { it !== this && it.normalName().equals(tagName, ignoreCase = true) }
            ?.attr(attrName)
            ?.trim()
            .orEmpty()
    }

    private fun resolveZipPath(basePath: String, href: String): String {
        val cleanHref = href.substringBefore('#')
        val joined = if (basePath.isBlank()) cleanHref else "$basePath/$cleanHref"
        return normalizeZipPath(joined)
    }

    private fun normalizeZipPath(path: String): String {
        val parts = mutableListOf<String>()
        path.replace('\\', '/')
            .trim()
            .removePrefix("/")
            .split('/')
            .forEach { part ->
                when {
                    part.isBlank() || part == "." -> Unit
                    part == ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                    else -> parts += part
                }
            }
        return parts.joinToString("/")
    }

    private data class EpubManifestItem(
        val path: String,
        val mediaType: String,
        val properties: Set<String>
    )

    private data class EpubTocEntry(
        val path: String,
        val title: String
    )

    private data class EpubHref(
        val path: String,
        val fragment: String?
    )

    private data class ParsedEpubChapter(
        val index: Int,
        val path: String,
        val title: String,
        val text: String,
        val bodyHtml: String?,
        val chapterKey: String
    )

    private data class TxtChapterRange(
        val index: Int,
        val title: String,
        val start: Int,
        val end: Int
    )

    companion object {
        private const val MAX_EXCERPT_CHARS = 280
        private const val MAX_TITLE_CHARS = 80
        private const val MIN_HTML_TOC_LINKS = 3
        private const val MIN_HTML_TOC_FALLBACK_LINKS = 8
        private const val MIN_TXT_CHAPTERS = 2
        private const val MIN_CHAPTER_CHARS = 8
        private const val MIN_PREFACE_CHARS = 24
        private const val MAX_CHAPTER_BUILD_WORKERS = 4
        /**
         * Chinese web novels commonly use 第N章/回/卷/篇/部/集, with Arabic or Chinese
         * numerals. English e-books often use “Chapter N”.  The whole-line anchor is
         * intentional: it avoids splitting ordinary prose that merely mentions a chapter.
         */
        private val TXT_CHAPTER_HEADING = Regex(
            """(?im)^[\t 　]*(?:(?:第\s*[0-9０-９一二三四五六七八九十百千万亿零〇两壹贰叁肆伍陆柒捌玖拾佰仟萬]+\s*[章回卷篇部集])|(?:chapter\s+(?:[0-9]+|[ivxlcdm]+)))[\t 　:：、.．—-]*[^\r\n]{0,100}$"""
        )
        private val GENERIC_EPUB_TITLES = setOf(
            "unknown",
            "untitled",
            "untitleddocument",
            "unknown.xhtml",
            "unknown.html",
            "未知",
            "无标题",
            "未命名",
            "正文"
        )
    }
}
