package com.lightningstudio.watchrss.phone.connection.bluetooth

import org.json.JSONObject

data class AccountSyncResult(
    val boundUserId: String,
    val watchDeviceId: String,
    val telemetryBacklog: Int,
    val accountStateRoot: String? = null
)

object AccountSyncPayload {
    const val PROTOCOL_VERSION = 1

    fun parseResponse(payload: JSONObject): AccountSyncResult {
        require(payload.optBoolean("success")) {
            payload.optString("message").ifBlank { "手表返回账号同步失败" }
        }
        val root = if (payload.has("accountStateRoot")) payload.getString("accountStateRoot").also {
            require(it.matches(Regex("[0-9a-f]{64}"))) { "手表账号交付后状态根无效" }
        } else null
        return AccountSyncResult(
            boundUserId = payload.optString("boundUserId").trim(),
            watchDeviceId = payload.optString("deviceId").trim(),
            telemetryBacklog = payload.optInt("telemetryBacklog", 0),
            accountStateRoot = root
        )
    }
}

