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

internal fun isRetryableBluetoothDisconnect(failure: Throwable): Boolean {
    if (failure is CancellationException && failure !is TimeoutCancellationException) return false
    // Protocol rejection/validation is deliberately not matched by its human-readable message.
    if (failure is IllegalArgumentException || failure is WatchSyncBlocked) return false
    var current: Throwable? = failure
    val seen = mutableSetOf<Throwable>()
    while (current != null && seen.add(current)) {
        if (current is IOException || current is TimeoutCancellationException) return true
        if (current is IllegalStateException && current.message in setOf("持久同步连接已关闭", "IP 同步连接已关闭", "IP 同步通道已关闭")) return true
        current = current.cause
    }
    return false
}
