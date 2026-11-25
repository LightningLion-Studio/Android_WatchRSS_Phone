package com.lightningstudio.watchrss.phone.cloud

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import java.io.IOException
import kotlin.random.Random
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.asStateFlow

internal sealed interface CloudHeadReply {
    data class Head(val etag: String, val pollAfterMs: Long?) : CloudHeadReply
    data object Unchanged : CloudHeadReply
    data object Unsupported : CloudHeadReply
    data class RateLimited(val retryAfterMs: Long?) : CloudHeadReply
}

internal class PhoneCloudHeadPoller(
    private val elapsed: () -> Long,
    private val jitter: () -> Long = { Random.nextLong(-2_000L, 2_001L) }
) {
    private enum class Support { UNKNOWN, AVAILABLE, UNSUPPORTED }

    @Volatile private var support = Support.UNKNOWN
    private val _available = MutableStateFlow(false)
    val available = _available.asStateFlow()

    private var acknowledged: Pair<List<String>, String>? = null
    private var intervalMs = DEFAULT_INTERVAL_MS
    private var retryMs = 0L
    private var nextAttemptAt = 0L

    suspend fun run(
        lifecycle: Lifecycle,
        enabled: Flow<Boolean>,
        attempt: suspend () -> Boolean
    ) = lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        enabled.distinctUntilChanged().collectLatest { allowed ->
            if (!allowed) return@collectLatest
            while (support != Support.UNSUPPORTED) {
                delay((nextAttemptAt - elapsed()).coerceAtLeast(0))
                if (!attempt()) delay(1_000)
            }
        }
    }

    /** Call under the same admission lock as a full sync. False means no head request was sent. */
    suspend fun check(
        owner: List<String>,
        probe: suspend (String?) -> CloudHeadReply?,
        sync: suspend () -> Boolean,
        pendingLocal: suspend () -> Boolean
    ): Boolean {
        if (support == Support.UNSUPPORTED) return false
        val etag = acknowledged?.takeIf { it.first == owner }?.second
        val reply = try {
            probe(etag)
        } catch (_: IOException) {
            currentCoroutineContext().ensureActive()
            backOff(null)
            return true
        } ?: return false

        when (reply) {
            CloudHeadReply.Unsupported -> {
                support = Support.UNSUPPORTED
                _available.value = false
            }
            is CloudHeadReply.RateLimited -> backOff(reply.retryAfterMs)
            CloudHeadReply.Unchanged -> {
                accept(intervalMs)
                if (pendingLocal()) sync()
            }
            is CloudHeadReply.Head -> {
                accept(reply.pollAfterMs ?: DEFAULT_INTERVAL_MS)
                if (reply.etag != etag || pendingLocal()) {
                    if (sync()) acknowledged = owner to reply.etag
                }
            }
        }
        return true
    }

    private fun accept(interval: Long) {
        support = Support.AVAILABLE
        _available.value = true
        retryMs = 0
        intervalMs = interval.coerceIn(2_001L, Long.MAX_VALUE / 2)
        nextAttemptAt = elapsed() + intervalMs + jitter()
    }

    private fun backOff(hint: Long?) {
        retryMs = (retryMs * 2).coerceIn(DEFAULT_INTERVAL_MS, MAX_RETRY_MS)
        nextAttemptAt = elapsed() + (hint ?: retryMs + jitter()).coerceIn(1_000L, MAX_RETRY_MS)
    }

    private companion object {
        const val DEFAULT_INTERVAL_MS = 20_000L
        const val MAX_RETRY_MS = 300_000L
    }
}
