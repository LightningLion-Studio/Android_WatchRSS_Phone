package com.lightningstudio.watchrss.phone.connection.bluetooth

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException

/** Raised only by socket I/O and the B2 wire deadline. */
internal class LibraryTransportFailure(val generation: Long, cause: IOException) :
    IOException(cause.message ?: "同步连接已关闭", cause)

internal class LibraryBinaryOpenFailure(cause: Throwable? = null) :
    IOException("未找到可重新连接的原手表", cause)

/** Recovery for the dedicated RFCOMM route. Legacy operations own their existing retry policy. */
internal class LibraryTransferRecovery(
    private val now: () -> Long,
    private val pause: suspend (Long) -> Unit,
    private val onSearch: (Int) -> Unit
) {
    private val disconnectTimes = ArrayDeque<Long>()
    private val progress = mutableSetOf<String>()
    private var generation = -1L
    private var lastDisconnect = -1L
    private var readyAfterDisconnect = false
    private var progressSinceReady = false
    private var noProgress = 0

    @Synchronized
    fun confirmedProgress(identity: String) {
        if (progress.add(identity)) {
            progressSinceReady = true
            noProgress = 0
        }
    }

    @Synchronized
    fun observe(value: Long) {
        generation = value
    }

    @Synchronized
    private fun disconnected(failure: LibraryTransportFailure): Boolean {
        if (failure.generation != generation || generation == lastDisconnect) return false
        lastDisconnect = generation
        val at = now()
        while (disconnectTimes.isNotEmpty() && at - disconnectTimes.first() > 10_000L) {
            disconnectTimes.removeFirst()
        }
        disconnectTimes.addLast(at)
        if (disconnectTimes.size >= 3) throw LibraryDisconnectLimit(failure)
        if (readyAfterDisconnect && !progressSinceReady && ++noProgress >= 3) {
            throw IOException("连续 3 次重连后同步未推进", failure)
        }
        readyAfterDisconnect = false
        return true
    }

    private suspend fun open(openReady: suspend () -> Unit, reconnecting: Boolean) {
        for (attempt in 1..3) {
            if (reconnecting || attempt > 1) onSearch(attempt)
            try {
                openReady()
                currentCoroutineContext().ensureActive()
                if (reconnecting) synchronized(this) {
                    readyAfterDisconnect = true
                    progressSinceReady = false
                }
                return
            } catch (failure: IOException) {
                currentCoroutineContext().ensureActive()
                when (failure) {
                    is LibraryTransportFailure -> disconnected(failure)
                    is LibraryBinaryOpenFailure -> Unit
                    else -> throw failure
                }
                if (attempt == 3) throw LibraryReconnectLimit(failure)
                pause(8_000L)
            }
        }
    }

    suspend fun <T> handoff(openReady: suspend () -> Unit, block: suspend () -> T): T {
        open(openReady, reconnecting = false)
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                return block()
            } catch (failure: LibraryTransportFailure) {
                currentCoroutineContext().ensureActive()
                if (disconnected(failure)) open(openReady, reconnecting = true)
            }
        }
    }
}
