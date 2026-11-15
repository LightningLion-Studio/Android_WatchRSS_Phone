package com.lightningstudio.watchrss.phone.connection.bluetooth

import org.json.JSONObject

internal class PhoneLoginRequired(cause: Throwable? = null) :
    IllegalStateException("登录已失效，请重新登录", cause)

internal class PhoneAccountSyncFailed(cause: Throwable) :
    IllegalStateException("账号同步失败：${cause.message ?: "请检查手机网络后重试"}", cause)

internal class WatchAccountSyncRequired(val response: JSONObject) :
    IllegalArgumentException(response.optString("message").ifBlank { "手表账号尚未同步" })

/** Compose 1.3.3-5 sends only these messages, without an error/errorCode field. */
internal fun requireAccountAccepted(response: JSONObject) {
    if (response.optBoolean("success", true)) return
    val code = response.optString("error").ifBlank { response.optString("errorCode") }
    val legacyMessage = response.optString("message") in setOf(
        "请重新连接并同步账号后再同步资料",
        "手机与当前同步会话的资料账号不一致，请先同步账号或重新连接"
    )
    if (code in setOf("account_sync_required", "account_not_synced") || (code.isBlank() && legacyMessage)) {
        throw WatchAccountSyncRequired(response)
    }
}

/** One account repair per operation. A new transport always needs its own confirmation. */
internal class AccountSyncGate(
    private val syncAccount: suspend () -> Unit,
    private val reconnect: suspend () -> Unit
) {
    private var confirmed = false
    private var repaired = false

    fun invalidate() { confirmed = false }

    suspend fun ensure() {
        if (!confirmed) {
            syncAccount()
            confirmed = true
        }
    }

    suspend fun <T> run(block: suspend () -> T): T {
        ensure()
        return try {
            block()
        } catch (failure: WatchAccountSyncRequired) {
            if (repaired) throw failure
            repaired = true
            invalidate()
            // Compose closes the rejected connection. Do not replay on that socket.
            reconnect()
            ensure()
            block()
        }
    }
}
