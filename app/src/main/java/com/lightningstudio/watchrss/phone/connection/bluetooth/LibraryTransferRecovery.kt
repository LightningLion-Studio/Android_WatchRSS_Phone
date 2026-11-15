package com.lightningstudio.watchrss.phone.connection.bluetooth

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Created only by a live sync transport, never by HTTP, storage or payload parsing. */
internal class LibraryTransportFailure(val generation: Long, cause: IOException) :
    IOException(cause.message ?: "同步连接已关闭", cause)

internal class LibrarySearchFailure(cause: Throwable? = null) : IOException("未找到可重新连接的原手表", cause)
internal class LibraryReconnectLimit : IOException("连续 3 次搜索或重新连接手表失败")
internal class LibraryDisconnectLimit : IOException("10 秒内同步连接断开 3 次")

internal class LibraryTransferRecovery(private val now: () -> Long) {
    var consecutiveFailures = 0; private set
    var generation: Long = -1; private set
    private val disconnected = mutableSetOf<Long>()
    private val times = ArrayDeque<Long>()

    fun clockMillis(): Long = now()
    fun observe(value: Long) { generation = value }
    fun ready() { consecutiveFailures = 0 }
    fun searchFailed() {
        consecutiveFailures += 1
        if (consecutiveFailures >= 3) throw LibraryReconnectLimit()
    }
    fun disconnected(value: Long) {
        if (value != generation || !disconnected.add(value)) return
        val at = now()
        while (times.isNotEmpty() && at - times.first() > 10_000L) times.removeFirst()
        times.addLast(at)
        if (times.size >= 3) throw LibraryDisconnectLimit()
    }
}

/** Timeouts belong to an individual network read/write, not to the whole resumed task. */
internal class LibraryIoGuard(
    private val generation: Long,
    private val enabled: () -> Boolean,
    private val close: () -> Unit,
    private val timeoutMs: Long = 120_000L,
    private val remotelyClosed: () -> Boolean = { false },
    private val beforeIo: () -> Unit = {}
) {
    companion object {
        private val timer = Executors.newSingleThreadScheduledExecutor { work ->
            Thread(work, "library-io-deadline").apply { isDaemon = true }
        }
    }
    private fun <T> io(block: () -> T): T {
        if (!enabled()) return block()
        beforeIo()
        val timedOut = AtomicBoolean(false)
        val alarm = timer.schedule({ timedOut.set(true); runCatching(close) }, timeoutMs, TimeUnit.MILLISECONDS)
        try {
            val result = block()
            if (timedOut.get()) throw SocketTimeoutException("同步连接读写超时")
            return result
        } catch (failure: IllegalStateException) {
            // The existing IP pipe uses check(!closed) rather than IOException. Only its
            // actual closed state may turn this exception into a transport disconnect.
            if (remotelyClosed()) throw LibraryTransportFailure(generation, IOException("同步连接已关闭", failure))
            throw failure
        } catch (failure: IOException) {
            if (failure is ProtocolException || failure is LibraryTransportFailure) throw failure
            throw LibraryTransportFailure(generation,
                if (timedOut.get()) SocketTimeoutException("同步连接读写超时").apply { initCause(failure) } else failure)
        } finally { alarm.cancel(false) }
    }
    fun input(source: InputStream): InputStream = object : InputStream() {
        override fun read(): Int = io {
            source.read().also { if (enabled() && it < 0) throw EOFException("同步连接读取结束") }
        }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int = io {
            source.read(bytes, offset, length).also { if (enabled() && length > 0 && it < 0) throw EOFException("同步连接读取结束") }
        }
        override fun close() = source.close()
    }
    fun output(target: OutputStream): OutputStream = object : OutputStream() {
        override fun write(value: Int) = io { target.write(value) }
        override fun write(bytes: ByteArray, offset: Int, length: Int) = io { target.write(bytes, offset, length) }
        override fun flush() = io { target.flush() }
        override fun close() = target.close()
    }
}

/** Complete, decoded body chunks are exact; partial JSON frames are reported separately as wire bytes. */
internal class LibraryBodyTransferStats {
    private val seen = mutableSetOf<String>()
    private val sent = mutableSetOf<String>()
    private var bodyBytes = 0L
    private var duplicateBytes = 0L
    private var partialWireBytes = 0L
    private var attemptBody = 0L
    private var attemptRepeated = 0L
    private var attemptPartial = 0L
    fun beginAttempt() { attemptBody = bodyBytes; attemptRepeated = duplicateBytes; attemptPartial = partialWireBytes }
    fun partialWire(bytes: Long) { partialWireBytes += bytes }
    val sentArticles: Int get() = sent.size
    fun frame(direction: String, frame: JSONObject) {
        val articles = frame.optJSONArray("articles") ?: return
        for (i in 0 until articles.length()) {
            val article = articles.getJSONObject(i)
            val id = article.optString("articleId")
            if (direction == "send") sent += id
            val body = article.optJSONObject("body") ?: continue
            val chunks = body.optJSONArray("chunks") ?: continue
            for (n in 0 until chunks.length()) {
                val chunk = chunks.getJSONObject(n)
                val data = chunk.getString("data")
                val bytes = data.length.toLong() * 3L / 4L - data.takeLast(2).count { it == '=' }
                val key = "$direction|$id|${body.optString("bodyHash")}|${chunk.getInt("index")}|${chunk.getString("hash")}"
                bodyBytes += bytes
                if (!seen.add(key)) duplicateBytes += bytes
            }
        }
    }
    fun snapshot(): Map<String, Any> = mapOf("bodyBytes" to bodyBytes, "repeatedBodyBytes" to duplicateBytes,
        "partialFrameWireBytes" to partialWireBytes, "uniqueBodyChunks" to seen.size,
        "attemptBodyBytes" to bodyBytes - attemptBody, "attemptRepeatedBodyBytes" to duplicateBytes - attemptRepeated,
        "attemptPartialFrameWireBytes" to partialWireBytes - attemptPartial)
}

/** The only retry owner for one user library task, including its auxiliary network steps. */
internal class LibraryRecoveryContext(
    val policy: LibraryTransferRecovery,
    val mode: LibrarySyncMode,
    private val connected: () -> Boolean,
    private val reconnect: suspend () -> Unit,
    private val confirm: suspend () -> Unit,
    private val checkOwner: () -> Unit,
    private val event: (String, Map<String, Any>) -> Unit,
    private val reconnecting: () -> Unit
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<LibraryRecoveryContext>
    val bodies = LibraryBodyTransferStats()
    var receiver: PhoneArticleReceiveCheckpoint? = null
    var checkpoints = false
    var receiveFrame: suspend (JSONObject) -> Unit = {}
    private var insideUnit = false
    fun validateOwner() = checkOwner()

    private suspend fun repair() {
        while (true) {
            currentCoroutineContext().ensureActive()
            checkOwner()
            reconnecting()
            event("library.reconnect.search", mapOf("consecutiveFailures" to policy.consecutiveFailures))
            try {
                reconnect()
                bodies.beginAttempt()
                confirm()
                currentCoroutineContext().ensureActive()
                checkOwner()
                policy.ready()
                event("library.reconnect.ready", mapOf("generation" to policy.generation) + bodies.snapshot())
                return
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                when (failure) {
                    is LibraryTransportFailure -> {
                        event("library.connection.interrupted", mapOf("unit" to "account", "generation" to failure.generation,
                            "monotonicMillis" to policy.clockMillis()) + bodies.snapshot())
                        policy.disconnected(failure.generation)
                    }
                    is LibrarySearchFailure -> Unit
                    else -> throw failure
                }
                event("library.reconnect.search.failed", mapOf("consecutiveFailures" to policy.consecutiveFailures + 1))
                policy.searchFailed()
            }
        }
    }

    suspend fun <T> unit(name: String, block: suspend () -> T): T {
        if (insideUnit) return block()
        insideUnit = true
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                checkOwner()
                try {
                    if (!connected()) {
                        if (policy.generation >= 0) throw LibraryTransportFailure(policy.generation, EOFException("同步连接已关闭"))
                        repair()
                    }
                    return block()
                } catch (failure: LibraryTransportFailure) {
                    currentCoroutineContext().ensureActive()
                    if (policy.generation >= 0 && failure.generation != policy.generation) continue
                    event("library.connection.interrupted", mapOf("unit" to name, "generation" to failure.generation, "monotonicMillis" to policy.clockMillis()) + bodies.snapshot())
                    policy.disconnected(failure.generation)
                    repair()
                }
            }
        } finally { insideUnit = false }
    }
}

internal fun libraryRecoveryMatches(expected: PhoneBluetoothWatchDevice, actual: PhoneBluetoothWatchDevice): Boolean =
    if (expected.remoteDeviceId.isNotBlank()) expected.remoteDeviceId == actual.remoteDeviceId
    else expected.bluetoothAddress.ifBlank { expected.address }.equals(actual.bluetoothAddress.ifBlank { actual.address }, ignoreCase = true)

internal class LibraryProgressHold {
    private var floor: Int? = null
    fun reset() { floor = null }
    fun percent(reconnecting: Boolean, next: Int, visible: Int?): Int {
        if (reconnecting) floor = maxOf(floor ?: 0, visible ?: next)
        return if (reconnecting) requireNotNull(floor) else maxOf(next, floor ?: 0)
    }
}

internal fun isLibraryProbeFatal(failure: Throwable): Boolean = failure is ProtocolException ||
    (failure !is IOException && failure !is kotlinx.coroutines.CancellationException)
