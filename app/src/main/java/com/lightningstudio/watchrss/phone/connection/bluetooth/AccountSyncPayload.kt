package com.lightningstudio.watchrss.phone.connection.bluetooth

import org.json.JSONObject

data class AccountSyncResult(
    val boundUserId: String,
    val watchDeviceId: String,
    val telemetryBacklog: Int
)

object AccountSyncPayload {
    const val PROTOCOL_VERSION = 1

    fun parseResponse(payload: JSONObject): AccountSyncResult {
        require(payload.optBoolean("success")) {
            payload.optString("message").ifBlank { "手表返回账号同步失败" }
        }
        return AccountSyncResult(
            boundUserId = payload.optString("boundUserId").trim(),
            watchDeviceId = payload.optString("deviceId").trim(),
            telemetryBacklog = payload.optInt("telemetryBacklog", 0)
        )
    }
}

