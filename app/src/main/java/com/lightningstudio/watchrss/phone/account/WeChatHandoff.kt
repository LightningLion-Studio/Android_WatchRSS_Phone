package com.lightningstudio.watchrss.phone.account

import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.time.Instant

internal class WeChatHandoff(val miniPath: String, val expiresAtMillis: Long) {
    override fun toString() = "WeChatHandoff([redacted])"
}

internal class WeChatHandoffUnavailable : IllegalStateException("暂时无法打开微信小程序")

internal fun parseWeChatHandoff(body: String): WeChatHandoff {
    try {
        val json = JSONObject(body)
        val ticket = json.get("ticket") as? String ?: throw WeChatHandoffUnavailable()
        val path = json.get("miniPath") as? String ?: throw WeChatHandoffUnavailable()
        val page = path.substringBefore('?').removePrefix("/")
        val expiry = when (val value = json.get("expiresAt")) {
            is Number -> json.getLong("expiresAt")
            is String -> value.toLongOrNull() ?: Instant.parse(value).toEpochMilli()
            else -> throw WeChatHandoffUnavailable()
        }
        require(ticket.isNotBlank() && ticket.length <= 512)
        require(path.length <= 2048 && path.none { it <= ' ' || it == '#' })
        require(page.split('/').all { it.matches(Regex("[A-Za-z0-9_-][A-Za-z0-9_.-]*")) })
        require("https://mini.invalid/${path.removePrefix("/")}".toHttpUrl().queryParameterValues("ticket") == listOf(ticket))
        require(expiry > 0)
        return WeChatHandoff(path, expiry)
    } catch (_: Exception) {
        // Parse exceptions may quote the response, including the one-use ticket.
        throw WeChatHandoffUnavailable()
    }
}

internal enum class WeChatPurchaseFallback {
    WECHAT_NOT_INSTALLED, SDK_UNAVAILABLE, APP_ID_MISSING, HANDOFF_UNAVAILABLE
}

internal interface WeChatMiniProgram : AutoCloseable {
    fun send(userName: String, path: String): Boolean
}

internal class WeChatPurchase(
    private val installed: () -> Boolean,
    private val appId: String,
    private val originalId: String,
    private val connect: (String) -> WeChatMiniProgram?,
    private val now: () -> Long = System::currentTimeMillis
) {
    /** Null means WeChat accepted the request. Other results use the manual mini-program instructions. */
    suspend fun launch(request: suspend () -> WeChatHandoff): WeChatPurchaseFallback? {
        when (sdkCall(installed)) {
            false -> return WeChatPurchaseFallback.WECHAT_NOT_INSTALLED
            null -> return WeChatPurchaseFallback.SDK_UNAVAILABLE
            true -> Unit
        }
        if (appId.isBlank()) return WeChatPurchaseFallback.APP_ID_MISSING
        if (originalId.isBlank()) return WeChatPurchaseFallback.SDK_UNAVAILABLE
        val sdk = sdkCall { connect(appId) } ?: return WeChatPurchaseFallback.SDK_UNAVAILABLE
        try {
            val handoff = try {
                request()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return WeChatPurchaseFallback.HANDOFF_UNAVAILABLE
            }
            if (handoff.expiresAtMillis <= now()) return WeChatPurchaseFallback.HANDOFF_UNAVAILABLE
            return if (sdkCall { sdk.send(originalId, handoff.miniPath) } == true) null
            else WeChatPurchaseFallback.SDK_UNAVAILABLE
        } finally {
            sdkCall { sdk.close() }
        }
    }

    private inline fun <T> sdkCall(block: () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: RuntimeException) {
        null
    } catch (_: LinkageError) {
        null
    }
}
