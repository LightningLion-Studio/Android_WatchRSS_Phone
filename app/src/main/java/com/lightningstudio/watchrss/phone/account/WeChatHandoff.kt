package com.lightningstudio.watchrss.phone.account

import org.json.JSONObject
import java.util.Base64

internal const val WECHAT_HANDOFF_TIMEOUT_MILLIS = 3_000L

private val tokenFormat = Regex("[A-Za-z0-9_-]{43}")

internal class WeChatHandoff(val token: String, val expiresAtMillis: Long) {
    override fun toString() = "WeChatHandoff([redacted])"
}

internal class WeChatHandoffUnavailable : IllegalStateException("暂时无法取得微信登录信息")

internal fun parseWeChatHandoff(body: String): WeChatHandoff {
    try {
        val json = JSONObject(body)
        val token = json.get("token") as? String ?: throw WeChatHandoffUnavailable()
        val expiry = when (val value = json.get("expiresAt")) {
            is Long -> value
            is Int -> value.toLong()
            else -> throw WeChatHandoffUnavailable()
        }
        require(tokenFormat.matches(token) && expiry > 0)
        val decoded = Base64.getUrlDecoder().decode(token)
        require(decoded.size == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) == token)
        return WeChatHandoff(token, expiry)
    } catch (_: Exception) {
        // Parser errors can contain part of the response.
        throw WeChatHandoffUnavailable()
    }
}
