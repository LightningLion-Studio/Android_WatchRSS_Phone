package com.lightningstudio.watchrss.phone.data.importer

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class ImportedWebArticle(
    val articleId: String,
    val url: String,
    val title: String,
    val siteName: String,
    val excerpt: String,
    val contentHtml: String?,
    val contentText: String,
    val imageUrl: String?,
    val contentHash: String
)

class WebArticleImporter(
    private val client: OkHttpClient = buildClient()
) {
    fun importUrl(input: String): ImportedWebArticle {
        val url = normalizeUrl(input)
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "网页抓取失败：HTTP ${response.code}" }
            val body = response.body?.string()?.takeIf { it.isNotBlank() }
                ?: error("网页内容为空")
            return parse(url, body)
        }
    }

    fun parse(baseUrl: String, html: String): ImportedWebArticle {
        val doc = Jsoup.parse(html, baseUrl)
        doc.outputSettings().prettyPrint(false)
        val embeddedArticle = extractEmbeddedArticle(doc, baseUrl)
        doc.select("script,style,noscript,template,svg,form,button,nav,footer,header,aside").remove()
        val root = selectReadableRoot(doc)
        normalizeMediaUrls(root)
        val title = listOf(
            embeddedArticle?.title,
            pickTitle(doc, root),
            hostLabel(baseUrl),
            baseUrl
        ).firstNotBlank().take(MAX_TITLE_CHARS)
        val rootText = root.text().trim()
        val fallbackText = doc.body()?.text()?.trim().orEmpty()
        val description = pickDescription(doc)
        val contentText = listOf(
            embeddedArticle?.contentText,
            rootText,
            fallbackText,
            description,
            title
        ).firstNotBlank()
        require(contentText.isNotBlank()) { "未提取到有效正文" }
        val rootHtml = root.outerHtml().trim()
            .takeIf { rootText.isNotBlank() || root.select("img[src], video[src], iframe[src]").isNotEmpty() }
        val contentHtml = embeddedArticle?.contentHtml ?: rootHtml
        val excerpt = listOf(
            embeddedArticle?.excerpt,
            description,
            contentText.take(MAX_EXCERPT_CHARS)
        ).firstNotBlank().take(MAX_EXCERPT_CHARS)
        val imageUrl = embeddedArticle?.imageUrl ?: pickImage(doc, root)
        val articleId = stableArticleId(baseUrl)
        val contentHash = sha256(contentHtml ?: contentText)
        return ImportedWebArticle(
            articleId = articleId,
            url = baseUrl,
            title = title,
            siteName = listOf(embeddedArticle?.siteName, pickSiteName(doc, baseUrl)).firstNotBlank(),
            excerpt = excerpt,
            contentHtml = contentHtml,
            contentText = contentText,
            imageUrl = imageUrl,
            contentHash = contentHash
        )
    }

    companion object {
        private const val MAX_TITLE_CHARS = 160
        private const val MAX_EXCERPT_CHARS = 280
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 WatchRSS-Phone/1.0"

        fun normalizeUrl(input: String): String {
            val trimmed = input.trim()
                .removeSurrounding("<", ">")
                .trim()
            require(trimmed.isNotBlank()) { "请输入网页地址" }
            val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                trimmed
            } else {
                "https://$trimmed"
            }
            val uri = URI(withScheme)
            require(uri.scheme == "http" || uri.scheme == "https") { "只支持 HTTP/HTTPS 网页" }
            require(!uri.host.isNullOrBlank()) { "网页地址不合法" }
            return uri.normalize().toString()
        }

        fun stableArticleId(url: String): String = sha256(normalizeForIdentity(url))

        fun normalizeForIdentity(url: String): String {
            val uri = URI(normalizeUrl(url))
            val scheme = uri.scheme.lowercase()
            val host = uri.host.orEmpty().lowercase().removePrefix("www.")
            val path = uri.rawPath.orEmpty().ifBlank { "/" }
            val query = uri.rawQuery?.takeIf { it.isNotBlank() }?.let { "?$it" }.orEmpty()
            return "$scheme://$host$path$query"
        }

        fun sha256(value: String): String {
            val bytes = MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }

        fun hostLabel(url: String): String {
            return runCatching { URI(url).host.orEmpty().removePrefix("www.") }
                .getOrDefault("")
                .trim()
        }

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

private data class EmbeddedArticle(
    val title: String,
    val siteName: String,
    val excerpt: String,
    val contentHtml: String?,
    val contentText: String,
    val imageUrl: String?
)

private const val REMOVABLE_CONTENT_SELECTOR =
    "script,style,noscript,template,svg,form,button,nav,footer,header,aside"

private fun extractEmbeddedArticle(doc: Document, baseUrl: String): EmbeddedArticle? {
    return doc.select("script").asSequence().firstNotNullOfOrNull { script ->
        val scriptText = script.data().ifBlank { script.html() }
        extractAssignedJson(scriptText, "window.initData")?.let { parseTencentInitData(it, baseUrl) }
    }
}

private fun parseTencentInitData(json: String, baseUrl: String): EmbeddedArticle? {
    return runCatching {
        val root = JSONObject(json)
        val article = root.optJSONObject("content") ?: root
        val nestedContent = article.optJSONObject("content")
        val rawContentHtml = nestedContent?.cleanString("text").orEmpty()
        val contentHtmlWithImages = replaceTencentImagePlaceholders(
            html = rawContentHtml,
            attributes = article.optJSONObject("attribute")
        )
        val fragment = Jsoup.parseBodyFragment(contentHtmlWithImages, baseUrl)
        fragment.outputSettings().prettyPrint(false)
        fragment.select(REMOVABLE_CONTENT_SELECTOR).remove()
        val body = fragment.body()
        normalizeMediaUrls(body)
        val contentText = body.text().trim()
        if (contentText.isBlank()) return@runCatching null
        EmbeddedArticle(
            title = listOf(
                article.cleanString("title"),
                article.cleanString("shareTitle")
            ).firstNotBlank(),
            siteName = listOf(
                article.cleanString("source"),
                article.optJSONObject("card")?.cleanString("chlname"),
                WebArticleImporter.hostLabel(baseUrl)
            ).firstNotBlank(),
            excerpt = listOf(
                article.cleanString("abstract"),
                article.cleanString("shareDesc")
            ).firstNotBlank(),
            contentHtml = body.html().trim().takeIf { it.isNotBlank() }?.let { "<article>$it</article>" },
            contentText = contentText,
            imageUrl = listOf(
                article.cleanString("shareImg"),
                pickFirstTencentImageUrl(article.optJSONObject("attribute"), baseUrl)
            ).firstNotBlank().ifBlank { null }
        )
    }.getOrNull()
}

private fun replaceTencentImagePlaceholders(html: String, attributes: JSONObject?): String {
    if (html.isBlank() || attributes == null) return html
    var result = html
    attributes.keys().asSequence()
        .filter { it.startsWith("IMG_") }
        .forEach { key ->
            val image = attributes.optJSONObject(key) ?: return@forEach
            val url = listOf(
                image.cleanString("url"),
                image.cleanString("origUrl"),
                image.cleanString("compressUrl"),
                image.cleanString("bigOrigUrl")
            ).firstNotBlank()
            val replacement = if (url.isBlank()) {
                ""
            } else {
                val desc = image.cleanString("desc")
                val alt = if (desc.isNotBlank()) " alt=\"${escapeHtmlAttribute(desc)}\"" else ""
                "<img src=\"${escapeHtmlAttribute(url)}\"$alt>"
            }
            result = result.replace("<!--$key-->", replacement)
        }
    return result
}

private fun pickFirstTencentImageUrl(attributes: JSONObject?, baseUrl: String): String {
    if (attributes == null) return ""
    return attributes.keys().asSequence()
        .filter { it.startsWith("IMG_") }
        .mapNotNull { key ->
            val image = attributes.optJSONObject(key) ?: return@mapNotNull null
            listOf(
                image.cleanString("url"),
                image.cleanString("origUrl"),
                image.cleanString("compressUrl"),
                image.cleanString("bigOrigUrl")
            ).firstNotBlank().takeIf { it.isNotBlank() }?.let { resolveUrl(it, baseUrl) }
        }
        .firstOrNull()
        .orEmpty()
}

private fun extractAssignedJson(script: String, variableName: String): String? {
    val variableIndex = script.indexOf(variableName)
    if (variableIndex < 0) return null
    val assignIndex = script.indexOf('=', startIndex = variableIndex + variableName.length)
    if (assignIndex < 0) return null
    val jsonStart = script.indexOfFirstJsonChar(assignIndex + 1)
    if (jsonStart < 0) return null
    return extractBalancedJson(script, jsonStart)
}

private fun String.indexOfFirstJsonChar(startIndex: Int): Int {
    for (index in startIndex until length) {
        val char = this[index]
        if (char == '{' || char == '[') return index
        if (!char.isWhitespace()) return -1
    }
    return -1
}

private fun extractBalancedJson(value: String, startIndex: Int): String? {
    val open = value[startIndex]
    val close = when (open) {
        '{' -> '}'
        '[' -> ']'
        else -> return null
    }
    var depth = 0
    var inString = false
    var escaping = false
    for (index in startIndex until value.length) {
        val char = value[index]
        if (inString) {
            when {
                escaping -> escaping = false
                char == '\\' -> escaping = true
                char == '"' -> inString = false
            }
            continue
        }
        when (char) {
            '"' -> inString = true
            open -> depth += 1
            close -> {
                depth -= 1
                if (depth == 0) return value.substring(startIndex, index + 1)
            }
        }
    }
    return null
}

private fun selectReadableRoot(doc: Document): Element {
    val candidates = doc.select(
        "article, main, [role=main], div#content, div#article, div#article_content, div.content, div.post, div.entry-content"
    )
    return candidates.maxByOrNull { scoreReadableCandidate(it) } ?: doc.body()
}

private fun scoreReadableCandidate(element: Element): Int {
    val textScore = element.text().length
    val paragraphScore = element.select("p").sumOf { it.text().length / 2 }
    return textScore + paragraphScore
}

private fun pickTitle(doc: Document, root: Element): String {
    return listOf(
        doc.selectFirst("meta[property=og:title]")?.attr("content"),
        doc.selectFirst("meta[name=twitter:title]")?.attr("content"),
        root.selectFirst("h1")?.text(),
        doc.title()
    ).firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()
}

private fun pickDescription(doc: Document): String {
    return listOf(
        doc.selectFirst("meta[property=og:description]")?.attr("content"),
        doc.selectFirst("meta[name=description]")?.attr("content"),
        doc.selectFirst("meta[name=twitter:description]")?.attr("content")
    ).firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()
}

private fun pickSiteName(doc: Document, url: String): String {
    return listOf(
        doc.selectFirst("meta[property=og:site_name]")?.attr("content"),
        WebArticleImporter.hostLabel(url)
    ).firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()
}

private fun pickImage(doc: Document, root: Element): String? {
    return listOfNotNull(
        doc.selectFirst("meta[property=og:image]")?.attr("content"),
        doc.selectFirst("meta[name=twitter:image]")?.attr("content"),
        root.selectFirst("img[src]")?.attr("src")
    ).firstNotNullOfOrNull { candidate ->
        resolveUrl(candidate, doc.baseUri())
    }
}

private fun normalizeMediaUrls(root: Element) {
    val imageFallbacks = listOf("data-src", "data-original", "data-lazy-src", "data-actualsrc", "data-url")
    root.select("img").forEach { img ->
        val src = pickFirstAttr(img, "src", imageFallbacks)
        updateAbsUrl(img, "src", src)
        img.removeAttr("srcset")
    }
    root.select("video[src], source[src], iframe[src]").forEach { element ->
        updateAbsUrl(element, "src", element.attr("src").trim())
    }
}

private fun pickFirstAttr(element: Element, primary: String, fallbacks: List<String>): String? {
    val direct = element.attr(primary).trim()
    if (direct.isNotBlank()) return direct
    return fallbacks.firstNotNullOfOrNull { attr ->
        element.attr(attr).trim().takeIf { it.isNotBlank() }
    }
}

private fun updateAbsUrl(element: Element, attr: String, value: String?) {
    val trimmed = value?.trim()?.ifEmpty { null } ?: return
    element.attr(attr, trimmed)
    val abs = element.absUrl(attr)
    if (abs.isNotBlank()) {
        element.attr(attr, abs)
    }
}

private fun resolveUrl(value: String, baseUrl: String): String? {
    val trimmed = value.trim().ifEmpty { return null }
    return runCatching {
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            URL(URL(baseUrl), trimmed).toString()
        }
    }.getOrNull()
}

private fun JSONObject.cleanString(name: String): String {
    val value = optString(name, "").trim()
    return value.takeUnless { it.equals("null", ignoreCase = true) }.orEmpty()
}

private fun List<String?>.firstNotBlank(): String {
    return firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()
}

private fun escapeHtmlAttribute(value: String): String {
    return value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
