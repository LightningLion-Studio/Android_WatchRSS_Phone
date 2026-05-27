package com.lightningstudio.watchrss.phone.platform

import java.net.URI

enum class PlatformLinkKind(val displayName: String) {
    BILI("B站"),
    DOUYIN("抖音")
}

object PlatformLinkRouter {
    fun detect(url: String?): PlatformLinkKind? {
        val candidate = url?.trim().orEmpty()
        if (candidate.isBlank()) return null
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase().orEmpty()
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase()?.trimEnd('.').orEmpty()
        if (host.isBlank()) return null
        return when {
            host == "b23.tv" || hostMatches(host, "bilibili.com") || hostMatches(host, "bili2233.cn") ->
                PlatformLinkKind.BILI

            hostMatches(host, "douyin.com") || hostMatches(host, "iesdouyin.com") ->
                PlatformLinkKind.DOUYIN

            else -> null
        }
    }

    private fun hostMatches(host: String, domain: String): Boolean {
        return host == domain || host.endsWith(".$domain")
    }
}
