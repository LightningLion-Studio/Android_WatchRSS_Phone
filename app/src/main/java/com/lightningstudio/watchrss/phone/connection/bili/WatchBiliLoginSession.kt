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
    private var generation = 0L
    private var deadlineNanos = 0L

    @Synchronized
    fun clear() {
        generation++
        deadlineNanos = 0L
        state = State(status = "idle")
    }

    @Synchronized
    fun isCurrent(token: Long): Boolean = token == generation && deadlineNanos > System.nanoTime()


    @Synchronized
    fun begin(): Long {
        generation++
        deadlineNanos = System.nanoTime() + 300_000_000_000L
        state = State(status = "waiting")
        return generation
    }

    @Synchronized
    fun complete(cookie: String, refreshToken: String, token: Long = generation) {
        if (!isCurrent(token)) return
        require(Regex("(?:^|;\\s*)SESSDATA=[^;\\s]+").containsMatchIn(cookie)) { "B站未返回有效登录 Cookie" }
        state = State(status = "success", cookie = cookie, refreshToken = refreshToken)
    }

    @Synchronized
    fun fail(message: String, token: Long = generation) {
        if (!isCurrent(token)) return
        state = State(status = "error", message = message)
    }

    @Synchronized
    fun watchResponse(): JSONObject {
        if (deadlineNanos != 0L && System.nanoTime() >= deadlineNanos) {
            clear()
            state = State(status = "error", message = "登录已超时，请在手表重新连接")
        }
        return JSONObject()
        .put("status", state.status)
        .put("message", state.message)
        .apply {
            if (state.status == "success") {
                put("cookie", state.cookie)
                put("refreshToken", state.refreshToken)
            }
        }
    }
}
