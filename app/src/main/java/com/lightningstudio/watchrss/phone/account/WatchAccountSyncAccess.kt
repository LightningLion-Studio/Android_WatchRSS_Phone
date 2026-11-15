package com.lightningstudio.watchrss.phone.account

import com.lightningstudio.watchrss.phone.connection.bluetooth.PhoneLoginRequired
import org.json.JSONObject
import kotlinx.coroutines.CancellationException

/** Retry issuance once with the coordinator's existing device-session refresh entry point. */
internal suspend fun <T> withWatchAccountDeviceRefresh(
    deviceToken: () -> String?,
    refresh: suspend (String) -> Boolean,
    build: suspend () -> T
): T {
    val rejected = deviceToken()
    try {
        return build()
    } catch (failure: Throwable) {
        val http = failure.findAccountHttpException() ?: throw failure
        val code = runCatching { JSONObject(http.responseBody).optString("error") }.getOrDefault("")
        if (http.statusCode == 401) throw PhoneLoginRequired(failure)
        if (http.statusCode == 403 && code == "device_session_invalid") throw PhoneLoginRequired(failure)
        if (http.statusCode != 403 || code !in setOf("device_session_expired", "device_session_revoked")) throw failure
        if (rejected.isNullOrBlank()) throw PhoneLoginRequired(failure)
        val refreshed = try { refresh(rejected) } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (refreshFailure: Throwable) {
            throw PhoneLoginRequired(refreshFailure)
        }
        if (!refreshed) throw PhoneLoginRequired(failure)
    }
    return try {
        build()
    } catch (failure: Throwable) {
        val http = failure.findAccountHttpException() ?: throw failure
        if (http.statusCode == 401 || (http.statusCode == 403 &&
                runCatching { JSONObject(http.responseBody).optString("error") }.getOrDefault("") in setOf("device_session_expired", "device_session_revoked", "device_session_invalid"))) {
            throw PhoneLoginRequired(failure)
        }
        throw failure
    }
}
