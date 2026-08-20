package com.lightningstudio.watchrss.phone.data.importer

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.URI
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

data class OpmlSubscription(
    val title: String?,
    val feedUrl: String,
    val siteUrl: String?
)

class OpmlImporter {
    fun parse(bytes: ByteArray): List<OpmlSubscription> {
        require(bytes.isNotEmpty()) { "OPML 文件内容为空" }
        require(bytes.size <= MAX_FILE_BYTES) { "OPML 文件过大" }
        require(!bytes.toString(Charsets.UTF_8).contains("<!DOCTYPE", ignoreCase = true)) {
            "OPML 文件不能包含 DOCTYPE"
        }

        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
            setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
            isXIncludeAware = false
            setExpandEntityReferences(false)
        }
        val document = factory.newDocumentBuilder()
            .parse(ByteArrayInputStream(bytes))
        require(document.documentElement?.localName.equals("opml", ignoreCase = true)) {
            "所选文件不是有效的 OPML"
        }

        val subscriptions = LinkedHashMap<String, OpmlSubscription>()
        val outlines = document.getElementsByTagName("outline")
        for (index in 0 until outlines.length) {
            val outline = outlines.item(index) as? Element ?: continue
            val feedUrl = outline.attributeIgnoreCase("xmlUrl")?.trim().orEmpty()
            if (!isHttpUrl(feedUrl) || feedUrl in subscriptions) continue
            subscriptions[feedUrl] = OpmlSubscription(
                title = outline.attributeIgnoreCase("title")
                    ?.takeIf { it.isNotBlank() }
                    ?: outline.attributeIgnoreCase("text")?.takeIf { it.isNotBlank() },
                feedUrl = feedUrl,
                siteUrl = outline.attributeIgnoreCase("htmlUrl")
                    ?.trim()
                    ?.takeIf(::isHttpUrl)
            )
            require(subscriptions.size <= MAX_SUBSCRIPTIONS) {
                "OPML 最多支持 $MAX_SUBSCRIPTIONS 个订阅源"
            }
        }
        require(subscriptions.isNotEmpty()) { "OPML 中没有有效的 RSS 订阅地址" }
        return subscriptions.values.toList()
    }

    private fun Element.attributeIgnoreCase(name: String): String? {
        for (index in 0 until attributes.length) {
            val attribute = attributes.item(index)
            if (attribute.nodeName.equals(name, ignoreCase = true)) return attribute.nodeValue
        }
        return null
    }

    private fun isHttpUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        (uri.scheme.equals("http", ignoreCase = true) ||
            uri.scheme.equals("https", ignoreCase = true)) && !uri.host.isNullOrBlank()
    }.getOrDefault(false)

    private companion object {
        const val MAX_FILE_BYTES = 2 * 1024 * 1024
        const val MAX_SUBSCRIPTIONS = 500
    }
}
