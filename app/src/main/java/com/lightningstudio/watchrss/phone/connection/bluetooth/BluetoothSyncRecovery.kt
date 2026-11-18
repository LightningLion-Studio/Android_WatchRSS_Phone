package com.lightningstudio.watchrss.phone.connection.bluetooth

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One retry budget for the entire user operation, including failed reconnects. */
internal class BluetoothSyncRecovery(
    private val pause: suspend (Long) -> Unit,
    private val reconnect: suspend () -> Unit,
    private val onRetry: (Int, Long, Throwable) -> Unit
) {
    private var attempts = 0
    suspend fun <T> run(operation: suspend () -> T): T {
        var needsReconnect = false
        while (true) {
            try {
                if (needsReconnect) reconnect()
                return operation()
            } catch (failure: Throwable) {
                // A child operation timeout can be retried; cancelling the user operation cannot.
                currentCoroutineContext().ensureActive()
                if (!isRetryableBluetoothDisconnect(failure)) throw failure
                if (attempts == BACKOFF.size) throw IOException(
                    "连接中断，重连未成功。可直接点“同步”重试。", failure
                )
                val wait = BACKOFF[attempts++]
                onRetry(attempts, wait, failure)
                pause(wait)
                needsReconnect = true
            }
        }
    }
    companion object { private val BACKOFF = longArrayOf(2_000, 5_000, 10_000) }
}

internal class LibraryBluetoothUnavailable : IOException("蓝牙未开启")
internal class LibraryReconnectLimit(cause: Throwable) : IOException("连续 3 次搜索或重新连接手表失败", cause)
internal class LibraryDisconnectLimit(cause: Throwable) : IOException("10 秒内同步连接断开 3 次", cause)

/** Keeps one rolling disconnect window across all stages of the same sync. */
internal class LibrarySyncRecovery(
    private val now: () -> Long,
    private val pause: suspend (Long) -> Unit,
    private val reconnect: suspend () -> Unit,
    private val onInterrupted: (Throwable) -> Unit,
    private val onSearch: (Int) -> Unit,
    private val onReady: () -> Unit
) {
    private val disconnectTimes = ArrayDeque<Long>()

    suspend fun <T> run(operation: suspend () -> T): T {
        while (true) {
            try {
                return operation()
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                if (!isRetryableBluetoothDisconnect(failure)) throw failure
                onInterrupted(failure)
                val at = now()
                while (disconnectTimes.isNotEmpty() && at - disconnectTimes.first() > 10_000L) {
                    disconnectTimes.removeFirst()
                }
                disconnectTimes.addLast(at)
                if (disconnectTimes.size >= 3) throw LibraryDisconnectLimit(failure)
            }
            search()
        }
    }

    suspend fun search() {
        for (attempt in 1..3) {
            currentCoroutineContext().ensureActive()
            onSearch(attempt)
            try {
                reconnect()
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                if (!isRetryableBluetoothDisconnect(failure)) throw failure
                if (attempt == 3) throw LibraryReconnectLimit(failure)
                if (failure is LibraryBluetoothUnavailable) pause(6_000L)
                continue
            }
            onReady()
            return
        }
    }
}

internal fun isRetryableBluetoothDisconnect(failure: Throwable): Boolean {
    if (failure is CancellationException && failure !is TimeoutCancellationException) return false
    // Protocol rejection/validation is deliberately not matched by its human-readable message.
    if (failure is IllegalArgumentException || isTerminalAccountSyncFailure(failure)) return false
    // DNS/routing/connect failures establish an IP failure. Socket read timeouts
    // remain retryable here: RFCOMM deadlines use the same timeout classes. HTTP
    // timeouts are classified at the account boundary before reaching recovery.
    val causes = mutableSetOf<Throwable>()
    var cause: Throwable? = failure
    while (cause != null && causes.add(cause)) {
        if (isTerminalAccountSyncFailure(cause) || cause is java.net.UnknownHostException ||
            cause is java.net.NoRouteToHostException || cause is java.net.ConnectException) return false
        cause = cause.cause
    }
    var current: Throwable? = failure
    val seen = mutableSetOf<Throwable>()
    while (current != null && seen.add(current)) {
        if (current is IOException || current is TimeoutCancellationException) return true
        if (current is IllegalStateException && current.message in setOf("持久同步连接已关闭", "IP 同步连接已关闭", "IP 同步通道已关闭")) return true
        current = current.cause
    }
    return false
}
