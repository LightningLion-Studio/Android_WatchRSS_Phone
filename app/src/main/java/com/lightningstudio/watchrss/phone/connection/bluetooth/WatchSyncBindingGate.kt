package com.lightningstudio.watchrss.phone.connection.bluetooth

import org.json.JSONObject
import java.security.MessageDigest

sealed interface WatchSyncBindingEvent {
    data object RequireWatchBinding : WatchSyncBindingEvent
    data object WatchAccountMismatch : WatchSyncBindingEvent
    data object OpenWatchPairing : WatchSyncBindingEvent
}

class WatchSyncBlocked(val event: WatchSyncBindingEvent) : IllegalStateException(
    when (event) {
        WatchSyncBindingEvent.RequireWatchBinding -> "请先扫码绑定手表，再进行同步"
        WatchSyncBindingEvent.WatchAccountMismatch -> "这块手表已登录其他账号，请先在手表上退出"
        WatchSyncBindingEvent.OpenWatchPairing -> error("Invalid sync rejection")
    }
)

/** Absent fields belong to older watches. Malformed new fields are protocol errors, never 'unbound'. */
internal fun requireWatchSyncBinding(response: JSONObject, phoneUserId: String) {
    if (!response.has("account") || response.isNull("account")) return
    val account = response.getJSONObject("account")
    if (!account.has("bound") || account.isNull("bound")) return
    require(account.get("bound") is Boolean) { "手表账号状态无效，请重试或升级手表端" }
    if (!account.getBoolean("bound")) throw WatchSyncBlocked(WatchSyncBindingEvent.RequireWatchBinding)
    val hash = account.getString("userIdHash")
    require(hash.matches(Regex("[0-9a-f]{64}"))) { "手表账号摘要无效，请重试或升级手表端" }
    val expected = MessageDigest.getInstance("SHA-256").digest(phoneUserId.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    if (hash != expected) throw WatchSyncBlocked(WatchSyncBindingEvent.WatchAccountMismatch)
}

/** No login leaves the old behavior intact. A changed phone owner cannot consume a late probe. */
internal suspend fun checkWatchSyncBinding(currentPhoneUserId: () -> String?,
    probe: suspend () -> BluetoothSyncExchange): String? {
    val owner = currentPhoneUserId()?.takeIf { it.isNotBlank() } ?: return null
    val result = probe()
    check(currentPhoneUserId() == owner) { "手机账号已变化，请重新同步" }
    requireWatchSyncBinding(result.response, owner)
    return result.deviceAddress
}
