package com.lightningstudio.watchrss.phone.data.importer

import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.net.URL
import java.util.concurrent.TimeUnit

data class ImportedRssSource(
    val url: String,
    val title: String,
    val description: String,
    val siteUrl: String?,
    val imageUrl: String?,
    val items: List<ImportedRssItem>
)

data class ImportedRssItem(
    val url: String,
    val title: String,
    val excerpt: String,
    val contentHtml: String?,
    val contentText: String,
    val imageUrl: String?,
    val guid: String?,
    val contentHash: String? = null
)

class RssSourceImporter(
    private val client: OkHttpClient = buildClient()
) {
    fun importUrl(input: String): ImportedRssSource {
        val url = WebArticleImporter.normalizeUrl(input)
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "RSS 抓取失败：HTTP ${response.code}" }
            val body = response.body?.string()?.takeIf { it.isNotBlank() }
                ?: error("RSS 内容为空")
            return parse(response.request.url.toString(), body)
        }
    }

    fun parse(url: String, xml: String): ImportedRssSource {
        val doc = Jsoup.parse(xml, url, Parser.xmlParser())
        return parseRss(url, doc) ?: parseAtom(url, doc) ?: error("不是有效的 RSS/Atom 源")
    }

    private fun parseRss(url: String, doc: Document): ImportedRssSource? {
        val channel = doc.selectFirst("rss > channel") ?: doc.selectFirst("channel") ?: return null
        val title = channel.childTextAny("title").ifBlank { WebArticleImporter.hostLabel(url) }
        val siteUrl = channel.childTextAny("link").takeIf { it.isNotBlank() }?.let { resolveUrl(it, url) }
        val imageUrl = channel.selectFirst("image > url")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?.let { resolveUrl(it, url) }
        val items = channel.children()
            .filter { it.normalName() == "item" }
            .mapIndexedNotNull { index, item -> item.toImportedRssItem(url, index) }
        return ImportedRssSource(
            url = url,
            title = title,
            description = channel.childTextAny("description"),
            siteUrl = siteUrl,
            imageUrl = imageUrl,
            items = items
        )
    }

    private fun parseAtom(url: String, doc: Document): ImportedRssSource? {
        val feed = doc.selectFirst("feed") ?: return null
        val title = feed.childTextAny("title").ifBlank { WebArticleImporter.hostLabel(url) }
        val siteUrl = feed.select("link[href]").firstOrNull { link ->
            link.attr("rel").isBlank() || link.attr("rel").equals("alternate", ignoreCase = true)
        }?.absUrl("href")?.takeIf { it.isNotBlank() }
        val items = feed.children()
            .filter { it.normalName() == "entry" }
            .mapIndexedNotNull { index, entry -> entry.toImportedAtomItem(url, index) }
        return ImportedRssSource(
            url = url,
            title = title,
            description = feed.childTextAny("subtitle"),
            siteUrl = siteUrl,
            imageUrl = feed.childTextAny("icon", "logo").takeIf { it.isNotBlank() }?.let { resolveUrl(it, url) },
            items = items
        )
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 WatchRSS-Phone/1.0"

        private fun buildClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .callTimeout(30, TimeUnit.SECONDS)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }
    }
}

private fun Element.toImportedRssItem(sourceUrl: String, index: Int): ImportedRssItem? {
    val guid = childTextAny("guid").ifBlank { null }
    val rawLink = childTextAny("link")
    val url = rawLink.takeIf { it.isNotBlank() }?.let { resolveUrl(it, sourceUrl) }
        ?: syntheticItemUrl(sourceUrl, guid ?: childTextAny("title").ifBlank { index.toString() })
    val media = enclosureMedia(sourceUrl)
    val contentHtml = appendEnclosureMedia(
        childTextAny("content:encoded", "encoded", "description").takeIf { it.isNotBlank() },
        media
    )
    val contentText = contentHtml?.let { Jsoup.parse(it, url).text().trim() }.orEmpty()
    val excerpt = Jsoup.parse(childTextAny("description").ifBlank { contentHtml.orEmpty() }, url)
        .text()
        .trim()
        .take(MAX_EXCERPT_CHARS)
    return ImportedRssItem(
        url = url,
        title = childTextAny("title").ifBlank { url },
        excerpt = excerpt,
        contentHtml = contentHtml,
        contentText = contentText.ifBlank { excerpt },
        imageUrl = media.imageUrl,
        guid = guid
    )
}

private fun Element.toImportedAtomItem(sourceUrl: String, index: Int): ImportedRssItem? {
    val guid = childTextAny("id").ifBlank { null }
    val link = select("link[href]").firstOrNull { item ->
        item.attr("rel").isBlank() || item.attr("rel").equals("alternate", ignoreCase = true)
    }?.absUrl("href")?.takeIf { it.isNotBlank() }
    val url = link ?: syntheticItemUrl(sourceUrl, guid ?: childTextAny("title").ifBlank { index.toString() })
    val media = enclosureMedia(sourceUrl)
    val contentHtml = appendEnclosureMedia(
        childTextAny("content", "summary").takeIf { it.isNotBlank() },
        media
    )
    val contentText = contentHtml?.let { Jsoup.parse(it, url).text().trim() }.orEmpty()
    val excerpt = Jsoup.parse(childTextAny("summary").ifBlank { contentHtml.orEmpty() }, url)
        .text()
        .trim()
        .take(MAX_EXCERPT_CHARS)
    return ImportedRssItem(
        url = url,
        title = childTextAny("title").ifBlank { url },
        excerpt = excerpt,
        contentHtml = contentHtml,
        contentText = contentText.ifBlank { excerpt },
        imageUrl = media.imageUrl ?: firstMediaUrl(sourceUrl),
        guid = guid
    )
}

private fun Element.childTextAny(vararg names: String): String {
    return names.firstNotNullOfOrNull { name ->
        children().firstOrNull { child ->
            child.tagName().equals(name, ignoreCase = true) ||
                child.normalName().equals(name, ignoreCase = true)
        }?.text()?.trim()?.takeIf { it.isNotBlank() }
    }.orEmpty()
}

private fun Element.firstMediaUrl(sourceUrl: String): String? {
    val direct = enclosureMedia(sourceUrl).imageUrl
        ?: getAllElements().firstOrNull { element ->
            val tag = element.tagName().lowercase()
            element.hasAttr("url") &&
                tag in setOf("media:thumbnail", "thumbnail")
        }?.attr("url")?.takeIf { it.isNotBlank() }?.let { resolveUrl(it, sourceUrl) }
    return direct?.let { resolveUrl(it, sourceUrl) }
}

private data class EnclosureMedia(
    val imageUrl: String?,
    val audioUrl: String?,
    val videoUrl: String?
)

private fun Element.enclosureMedia(sourceUrl: String): EnclosureMedia {
    var imageUrl: String? = null
    var audioUrl: String? = null
    var videoUrl: String? = null

    getAllElements().forEach { element ->
        val tag = element.tagName().lowercase()
        val type = element.attr("type").trim().lowercase()
        val rawUrl = when {
            element.hasAttr("url") -> element.attr("url")
            element.hasAttr("href") -> element.attr("href")
            else -> ""
        }.trim()
        if (rawUrl.isEmpty()) return@forEach
        val resolved = resolveUrl(rawUrl, sourceUrl)

        when {
            tag in setOf("media:thumbnail", "thumbnail", "itunes:image") -> {
                if (imageUrl == null) imageUrl = resolved
            }
            tag == "media:content" && type.startsWith("image/") -> {
                if (imageUrl == null) imageUrl = resolved
            }
            tag == "media:content" && type.startsWith("audio/") -> {
                if (audioUrl == null) audioUrl = resolved
            }
            tag == "media:content" && type.startsWith("video/") -> {
                if (videoUrl == null) videoUrl = resolved
            }
            tag == "enclosure" && type.startsWith("image/") -> {
                if (imageUrl == null) imageUrl = resolved
            }
            tag == "enclosure" && type.startsWith("audio/") -> {
                if (audioUrl == null) audioUrl = resolved
            }
            tag == "enclosure" && type.startsWith("video/") -> {
                if (videoUrl == null) videoUrl = resolved
            }
            tag == "link" && element.attr("rel").equals("enclosure", ignoreCase = true) &&
                type.startsWith("audio/") -> {
                if (audioUrl == null) audioUrl = resolved
            }
            tag == "link" && element.attr("rel").equals("enclosure", ignoreCase = true) &&
                type.startsWith("video/") -> {
                if (videoUrl == null) videoUrl = resolved
            }
        }
    }
    return EnclosureMedia(imageUrl, audioUrl, videoUrl)
}

private fun appendEnclosureMedia(contentHtml: String?, media: EnclosureMedia): String? {
    if (media.audioUrl == null && media.videoUrl == null) return contentHtml
    val document = Jsoup.parseBodyFragment(contentHtml.orEmpty())
    document.outputSettings().prettyPrint(false)
    val body = document.body()
    media.videoUrl?.let { url ->
        if (body.select("video[src], video source[src]").none { it.attr("src") == url }) {
            body.appendElement("video")
                .attr("controls", "")
                .attr("src", url)
        }
    }
    media.audioUrl?.let { url ->
        if (body.select("audio[src], audio source[src]").none { it.attr("src") == url }) {
            body.appendElement("audio")
                .attr("controls", "")
                .attr("src", url)
        }
    }
    return body.html().trim().takeIf { it.isNotBlank() }
}

private fun syntheticItemUrl(sourceUrl: String, key: String): String {
    val separator = if (sourceUrl.contains("?")) "&" else "?"
    return "$sourceUrl${separator}watchrss_item=${WebArticleImporter.sha256(key).take(16)}"
}

private fun resolveUrl(value: String, baseUrl: String): String {
    return runCatching {
        if (value.startsWith("http://") || value.startsWith("https://")) {
            value
        } else {
            URL(URL(baseUrl), value).toString()
        }
    }.getOrDefault(value)
}

private const val MAX_EXCERPT_CHARS = 280
