package com.lightningstudio.watchrss.phone.connection.bili

import org.json.JSONObject

/**
 * Ephemeral hand-off between the phone login sheet and the connected watch.
 * The phone never persists the Bilibili account; the watch receives the cookie
 * only through its already-established Bluetooth connection.
 */
internal object WatchBiliLoginSession {
    private data class State(
        val status: String,
        val cookie: String = "",
        val refreshToken: String = "",
        val message: String = ""
    )

    private var state = State(status = "idle")

    @Synchronized
    fun begin() {
        state = State(status = "waiting")
    }

    @Synchronized
    fun complete(cookie: String, refreshToken: String) {
        require(cookie.contains("SESSDATA=")) { "B站未返回有效登录 Cookie" }
        state = State(status = "success", cookie = cookie, refreshToken = refreshToken)
    }

    @Synchronized
    fun fail(message: String) {
        state = State(status = "error", message = message)
    }

    @Synchronized
    fun watchResponse(): JSONObject = JSONObject()
        .put("status", state.status)
        .put("message", state.message)
        .apply {
            if (state.status == "success") {
                put("cookie", state.cookie)
                put("refreshToken", state.refreshToken)
            }
        }
}
