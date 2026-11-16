package com.lightningstudio.watchrss.phone.account

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException

/** Shares rotations across cloud clients without serializing concurrent cloud requests. */
internal class DeviceSessionRefreshCoordinator(
    private val load: () -> AppAuthorization?,
    private val save: (AppAuthorization) -> Unit,
    private val owner: () -> String?,
    private val refresh: suspend (AppAuthorization) -> AppAuthorization,
    private val elapsedMillis: () -> Long,
    private val wallMillis: () -> Long
) {
    private val requests = DeviceSessionRequestGate()
    private var lastOwner: String? = null
    private var lastToken: String? = null
    private var freshUntil = 0L
    private var retryOwner: String? = null
    private var retryAt = 0L
    private var retryRequiresNetwork = false

    suspend fun <T> withSession(expectedOwner: String, operation: suspend (String?) -> T): T =
        requests.read {
            requireOwner(expectedOwner)
            operation(load()?.deviceAccessToken)
        }

    suspend fun refresh(expectedOwner: String, rejectedToken: String? = null): AppAuthorization =
        requests.write {
            requireOwner(expectedOwner)
            val current = load() ?: throw IOException("设备身份需要重新验证")
            // A waiting caller must reuse the published rotation, not rotate it again.
            if (rejectedToken != null && current.deviceAccessToken != rejectedToken) return@write current
            val now = elapsedMillis()
            if (retryOwner == expectedOwner && now < retryAt) {
                throw DeviceSessionRefreshBackoffException(retryAt - now, retryRequiresNetwork)
            }
            if (rejectedToken == null && lastOwner == expectedOwner && lastToken == current.deviceAccessToken &&
                now < freshUntil && current.deviceAccessTokenExpiresAt > wallMillis()) {
                return@write current
            }
            try {
                val next = refresh(current)
                requireOwner(expectedOwner)
                check(load()?.deviceAccessToken == current.deviceAccessToken) { "设备身份已变更，请重试" }
                require(next.deviceAccessToken.isNotBlank() && next.deviceAccessToken != current.deviceAccessToken) {
                    "设备会话未更新，请重试"
                }
                save(next)
                lastOwner = expectedOwner
                lastToken = next.deviceAccessToken
                freshUntil = elapsedMillis() + RECONCILE_INTERVAL_MILLIS
                retryOwner = null
                retryAt = 0L
                retryRequiresNetwork = false
                next
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                val http = error.findAccountHttpException()
                retryOwner = expectedOwner
                retryRequiresNetwork = isAccountNetworkFailure(error)
                retryAt = elapsedMillis() + if (http?.statusCode == 429) {
                    maxOf(RATE_LIMIT_BACKOFF_MILLIS, http.retryAfterMillis ?: 0L)
                } else FAILED_REFRESH_BACKOFF_MILLIS
                throw error
            }
        }

    /** Reconcile reports offline access; reactive callers still need the shared retry deadline. */
    suspend fun checkBackoff(expectedOwner: String) = requests.read {
        requireOwner(expectedOwner)
        if (retryOwner == expectedOwner && elapsedMillis() < retryAt) {
            throw DeviceSessionRefreshBackoffException(retryAt - elapsedMillis(), retryRequiresNetwork)
        }
    }

    private fun requireOwner(expectedOwner: String) {
        check(owner() == expectedOwner) { "账号已切换，请重试" }
    }

    private companion object {
        const val RECONCILE_INTERVAL_MILLIS = 60_000L
        const val RATE_LIMIT_BACKOFF_MILLIS = 60_000L
        const val FAILED_REFRESH_BACKOFF_MILLIS = 1_000L
    }
}

internal class DeviceSessionRefreshBackoffException(val retryAfterMillis: Long, val networkRequired: Boolean = false) : IOException(
    "设备验证请求过于频繁，请在${(retryAfterMillis + 999) / 1000}秒后重试；本机云密钥已保留"
)

/** Writer priority prevents a rotation from revoking a token while a request is being sent. */
internal class DeviceSessionRequestGate {
    private val admission = Mutex()
    private val readerCountMutex = Mutex()
    private val drained = Semaphore(1)
    private var readers = 0

    suspend fun <T> read(operation: suspend () -> T): T {
        admission.withLock {
            readerCountMutex.withLock {
                if (readers == 0) drained.acquire()
                readers += 1
            }
        }
        try {
            return operation()
        } finally {
            withContext(NonCancellable) {
                readerCountMutex.withLock {
                    readers -= 1
                    if (readers == 0) drained.release()
                }
            }
        }
    }

    suspend fun <T> write(operation: suspend () -> T): T =
        admission.withLock { drained.withPermit { operation() } }
}
