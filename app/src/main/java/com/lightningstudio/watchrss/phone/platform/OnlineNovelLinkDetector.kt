package com.lightningstudio.watchrss.phone.platform

import java.net.URI

object OnlineNovelLinkDetector {
    private val httpUrlPattern = Regex("""https?://[^\s<>\"']+""", RegexOption.IGNORE_CASE)

    private val onlineNovelDomains = setOf(
        // 番茄小说 / 番茄畅听
        "fanqienovel.com",
        "changdunovel.com",
        // 七猫小说
        "qimao.com",
        "wtzw.com",
        // 晋江文学城
        "jjwxc.net",
        "jjwxc.com",
        "jjwxc.cn",
        // 其他常见在线小说库
        "qidian.com",
        "qdmm.com",
        "zongheng.com",
        "17k.com",
        "readnovel.com",
        "hongxiu.com",
        "xxsy.net",
        "ciweimao.com",
        "sfacg.com",
        "faloo.com",
        "shuqi.com",
        "tadu.com",
        "heiyan.com",
        "ruochu.com",
        "ihuaben.com",
        "book.qq.com",
        "yunqi.qq.com",
        "weread.qq.com"
    )

    fun isOnlineNovelLink(text: String?): Boolean = findOnlineNovelUrl(text) != null

    fun findOnlineNovelUrl(text: String?): String? {
        val candidate = text?.trim().orEmpty()
        if (candidate.isBlank()) return null
        return httpUrlPattern.findAll(candidate)
            .map { match -> match.value.trimUrlTail() }
            .firstOrNull(::hasOnlineNovelHost)
    }

    private fun hasOnlineNovelHost(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase().orEmpty()
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.lowercase()?.trimEnd('.').orEmpty()
        if (host.isBlank()) return false
        return onlineNovelDomains.any { domain ->
            host == domain || host.endsWith(".$domain")
        }
    }

    private fun String.trimUrlTail(): String = trimEnd(
        '.', ',', '，', '。', ';', '；', '!', '！', '?', '？',
        ')', '）', ']', '】', '}', '》'
    )
}
