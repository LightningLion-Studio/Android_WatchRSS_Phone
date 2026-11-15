package com.lightningstudio.watchrss.phone.account

import com.lightningstudio.watchrss.phone.connection.bluetooth.PhoneLoginRequired
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

internal sealed interface WatchAccountRefreshResult {
    data object Ready : WatchAccountRefreshResult
    data class AccountUnrecoverable(val cause: Throwable? = null) : WatchAccountRefreshResult
    data class DeviceVerificationRequired(val cause: Throwable? = null) : WatchAccountRefreshResult
    data class TemporaryFailure(val cause: Throwable, val retryAfterMillis: Long? = null) : WatchAccountRefreshResult
}

internal class PhoneDeviceVerificationRequired(cause: Throwable? = null) :
    IllegalStateException("手机设备身份需要验证，请前往账号页面完成验证", cause)
internal class PhoneAccountTemporarilyUnavailable(cause: Throwable, val retryAfterMillis: Long? = null) :
    IllegalStateException("账号服务暂时不可用，请稍后重试", cause)

internal fun accountSyncFailureResult(error: Throwable): WatchAccountRefreshResult {
    if (error is CancellationException) throw error
    val http = error.findAccountHttpException()
    // Device/session rejection does not establish failure of the account authentication.
    if (http?.statusCode == 401) return WatchAccountRefreshResult.AccountUnrecoverable(error)
    if (http?.statusCode == 403) return WatchAccountRefreshResult.DeviceVerificationRequired(error)
    val retry = http?.let { runCatching { JSONObject(it.responseBody).optLong("retryAfterMillis").takeIf { n -> n > 0 } }.getOrNull() }
    return WatchAccountRefreshResult.TemporaryFailure(error, retry)
}

internal fun WatchAccountRefreshResult.requireReady() {
    when (this) {
        WatchAccountRefreshResult.Ready -> Unit
        is WatchAccountRefreshResult.AccountUnrecoverable -> throw PhoneLoginRequired(cause)
        is WatchAccountRefreshResult.DeviceVerificationRequired -> throw PhoneDeviceVerificationRequired(cause)
        is WatchAccountRefreshResult.TemporaryFailure -> throw PhoneAccountTemporarilyUnavailable(cause, retryAfterMillis)
    }
}

/** The coordinator owns refresh and shared backoff. This adapter only bounds issuance replay. */
internal suspend fun <T> withWatchAccountDeviceRefresh(
    deviceToken: () -> String?,
    refresh: suspend (String) -> WatchAccountRefreshResult,
    build: suspend () -> T
): T {
    val rejected = deviceToken()
    try {
        return build()
    } catch (failure: Throwable) {
        val http = failure.findAccountHttpException() ?: run {
            if (failure is java.io.IOException) accountSyncFailureResult(failure).requireReady()
            throw failure
        }
        val code = runCatching { JSONObject(http.responseBody).optString("error") }.getOrDefault("")
        if (http.statusCode == 401) throw PhoneLoginRequired(failure)
        if (http.statusCode == 403 && code == "device_session_invalid") throw PhoneDeviceVerificationRequired(failure)
        if (http.statusCode == 429 || http.statusCode >= 500) accountSyncFailureResult(failure).requireReady()
        if (http.statusCode != 403 || code !in setOf("device_session_expired", "device_session_revoked")) throw failure
        if (rejected.isNullOrBlank()) throw PhoneDeviceVerificationRequired(failure)
        val result = try { refresh(rejected) } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (refreshFailure: Throwable) { accountSyncFailureResult(refreshFailure) }
        result.requireReady()
    }
    return try { build() } catch (failure: Throwable) {
        val http = failure.findAccountHttpException() ?: run {
            if (failure is java.io.IOException) accountSyncFailureResult(failure).requireReady()
            throw failure
        }
        if (http.statusCode == 401) throw PhoneLoginRequired(failure)
        if (http.statusCode == 403) throw PhoneDeviceVerificationRequired(failure)
        if (http.statusCode == 429 || http.statusCode >= 500) accountSyncFailureResult(failure).requireReady()
        throw failure
    }
}
