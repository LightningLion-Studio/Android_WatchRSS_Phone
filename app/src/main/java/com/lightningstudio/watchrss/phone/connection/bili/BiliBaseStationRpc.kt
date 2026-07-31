package com.lightningstudio.watchrss.phone.connection.bili

import org.json.JSONObject

internal data class BiliBaseStationRequest(
    val id: Int,
    val method: String,
    val cookieHeader: String,
    val params: JSONObject
) {
    companion object {
        fun decode(bytes: ByteArray): BiliBaseStationRequest {
            val root = JSONObject(bytes.toString(Charsets.UTF_8))
            return BiliBaseStationRequest(
                id = root.getInt("id"),
                method = root.getString("method"),
                cookieHeader = root.optString("cookie"),
                params = root.optJSONObject("params") ?: JSONObject()
            )
        }
    }
}

internal fun successResponse(id: Int, data: JSONObject): ByteArray = JSONObject()
    .put("id", id)
    .put("ok", true)
    .put("data", data)
    .toString()
    .toByteArray(Charsets.UTF_8)

internal fun failureResponse(id: Int, error: Throwable): ByteArray = JSONObject()
    .put("id", id)
    .put("ok", false)
    .put("error", error.message ?: error.javaClass.simpleName)
    .toString()
    .toByteArray(Charsets.UTF_8)
