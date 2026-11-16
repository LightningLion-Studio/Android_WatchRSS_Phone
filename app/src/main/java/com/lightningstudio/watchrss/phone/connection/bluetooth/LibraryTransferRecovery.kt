package com.lightningstudio.watchrss.phone.connection.bluetooth

import android.util.Log
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Created only by a live sync transport, never by HTTP, storage or payload parsing. */
internal class LibraryTransportFailure(val generation: Long, cause: IOException) :
    IOException(cause.message ?: "同步连接已关闭", cause)

internal class LibrarySearchFailure(cause: Throwable? = null) : IOException("未找到可重新连接的原手表", cause)
internal class LibraryReconnectLimit : IOException("连续 3 次搜索或重新连接手表失败")
internal class LibraryDisconnectLimit : IOException("10 秒内同步连接断开 3 次")
internal class LibraryNoProgressLimit : IOException("同步多次中断且没有进展，请稍后重试")

internal class LibraryTransferRecovery(private val now: () -> Long) {
    var consecutiveFailures = 0; private set
    var generation: Long = -1; private set
    private val disconnected = mutableSetOf<Long>()
    private val times = ArrayDeque<Long>()
    var consecutiveNoProgress = 0; private set
    private var monitoringReady = false
    private var progressSinceReady = false

    fun clockMillis(): Long = now()
    fun observe(value: Long) { generation = value }
    fun ready(reconnected: Boolean = false) {
        consecutiveFailures = 0
        if (reconnected) { monitoringReady = true; progressSinceReady = false }
    }
    fun confirmedProgress() {
        progressSinceReady = true
        consecutiveNoProgress = 0
    }
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
        if (monitoringReady) {
            monitoringReady = false
            consecutiveNoProgress = if (progressSinceReady) 0 else consecutiveNoProgress + 1
            if (consecutiveNoProgress >= 3) throw LibraryNoProgressLimit()
        }
    }
}

private class LibraryIoValidationFailure(val original: Exception) : RuntimeException(original)

/** Timeouts belong to an individual network read/write, not to the whole resumed task. */
internal class LibraryIoGuard(
    private val generation: Long,
    private val enabled: () -> Boolean,
    private val close: () -> Unit,
    // 40 frozen-APK cases: longest successful complete read <= 6001 ms.
    // This conservatively includes snapshot generation and wire time:
    // min(60s, max(30s, 3 * 6001ms)) = 30s. Reset at each stream I/O, not per frame.
    private val timeoutMs: Long = 30_000L,
    private val remotelyClosed: () -> Boolean = { false },
    private val beforeIo: () -> Unit = {},
    private val wireReadActivityNanos: () -> Long = { 0L },
    private val wireWriteActivityNanos: (() -> Long)? = null,
    private val wireConnectionId: Long = 0L
) {
    private val completedWriteNanos = java.util.concurrent.atomic.AtomicLong()
    companion object {
        private val closer = Executors.newCachedThreadPool { work ->
            Thread(work, "library-io-close").apply { isDaemon = true }
        }
        private val timer = Executors.newSingleThreadScheduledExecutor { work ->
            Thread(work, "library-io-deadline").apply { isDaemon = true }
        }
    }
    private fun <T> io(reading: Boolean = false, block: () -> T): T {
        if (!enabled()) return block()
        beforeIo()
        val timedOut = AtomicBoolean(false)
        val finished = AtomicBoolean(false)
        val alarm = AtomicReference<ScheduledFuture<*>?>()
        val started = System.nanoTime()
        val timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val deadline = object : Runnable {
            override fun run() {
                if (finished.get()) return
                // A WebSocket may buffer an entire large message before feeding the
                // application pipe. Observe this connection's raw reads, not just onMessage.
                val rawRead = wireReadActivityNanos()
                val rawWrite = wireWriteActivityNanos?.invoke() ?: completedWriteNanos.get()
                val activity = maxOf(started, if (reading) rawRead else rawWrite)
                val checked = System.nanoTime()
                val remaining = timeoutNanos - (checked - activity)
                if (wireConnectionId != 0L) Log.i("WatchRSS_IpSyncIO",
                    "event=deadline.check connection=$wireConnectionId generation=$generation reading=$reading " +
                        "checkNanos=$checked startedNanos=$started rawReadNanos=$rawRead " +
                        "rawWriteNanos=$rawWrite remainingNanos=$remaining")
                if (remaining > 0) {
                    val next = timer.schedule(this, remaining, TimeUnit.NANOSECONDS)
                    alarm.set(next)
                    if (finished.get()) next.cancel(false)
                } else if (!finished.get()) {
                    timedOut.set(true)
                    closer.execute { runCatching(close) }
                }
            }
        }
        alarm.set(timer.schedule(deadline, timeoutNanos, TimeUnit.NANOSECONDS))
        try {
            val result = block()
            if (timedOut.get()) throw SocketTimeoutException("同步连接读写超时")
            return result
        } catch (failure: LibraryIoValidationFailure) {
            throw failure.original
        } catch (failure: IllegalStateException) {
            // The existing IP pipe uses check(!closed) rather than IOException. Only its
            // actual closed state may turn this exception into a transport disconnect.
            if (remotelyClosed()) throw LibraryTransportFailure(generation, IOException("同步连接已关闭", failure))
            throw failure
        } catch (failure: IOException) {
            if (failure is ProtocolException || failure is LibraryTransportFailure) throw failure
            throw LibraryTransportFailure(generation,
                if (timedOut.get()) SocketTimeoutException("同步连接读写超时").apply { initCause(failure) } else failure)
        } finally { finished.set(true); alarm.get()?.cancel(false) }
    }
    fun input(source: InputStream): InputStream = object : InputStream() {
        override fun read(): Int = io(reading = true) {
            source.read().also { if (enabled() && it < 0) throw EOFException("同步连接读取结束") }
        }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int = io(reading = true) {
            source.read(bytes, offset, if (enabled()) minOf(length, 8192) else length)
                .also { if (enabled() && length > 0 && it < 0) throw EOFException("同步连接读取结束") }
        }
        override fun close() = source.close()
    }
    fun output(target: OutputStream): OutputStream = object : OutputStream() {
        override fun write(value: Int) = io {
            target.write(value)
            completedWriteNanos.set(System.nanoTime())
        }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (!enabled()) { target.write(bytes, offset, length); return }
            // Keep RFCOMM writes bounded without per-byte socket calls. Each
            // completed 1 KiB chunk refreshes activity (4 seconds at 256 B/s).
            // IP retains 8 KiB chunks because it also observes raw writes.
            io {
                var written = 0
                while (written < length) {
                    if (written > 0) {
                        // Validation is not a transport failure, even if the peer
                        // concurrently closes. Preserve the original error type.
                        try { beforeIo() } catch (failure: Exception) { throw LibraryIoValidationFailure(failure) }
                    }
                    val count = minOf(length - written, if (wireWriteActivityNanos == null) 1024 else 8192)
                    target.write(bytes, offset + written, count)
                    completedWriteNanos.set(System.nanoTime())
                    written += count
                }
            }
        }
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
    var localArticle: suspend (String) -> com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity? = { null }
    var checkpoints = false
    var receiveFrame: suspend (JSONObject) -> Unit = {}
    private var insideUnit = false
    private val confirmed = mutableSetOf<String>()
    private var appliedReceiptSequence = 0L
    private data class SentArticle(val expected: ArticleSyncManifestEntry,
        val chunks: MutableSet<Int>, val receipt: String) {
        val bodyHash get() = expected.bodyHash
        val chunkSize get() = expected.chunkSize
        val chunkHashes get() = expected.chunkHashes
    }
    private val sentArticles = mutableMapOf<String, SentArticle>()
    fun validateOwner() = checkOwner()

    fun confirmedProgress(kind: String, identity: String) {
        if (confirmed.add("$kind|$identity")) {
            policy.confirmedProgress()
            event("library.progress.confirmed", mapOf("kind" to kind, "confirmedUnits" to confirmed.size))
        }
    }
    /** Only repositories that report actual new committed business rows may call this.
     * Idempotent replays report zero, so a Ready or a repeated peer receipt cannot renew it. */
    fun appliedProgress(kind: String, changed: Int) {
        if (changed > 0) confirmedProgress("apply:$kind", (++appliedReceiptSequence).toString())
    }
    fun stageComplete(name: String) = confirmedProgress("stage", name)

    /** A successful write is only a candidate. It is never itself confirmed progress. */
    fun sentArticleFrame(frame: JSONObject) {
        for (payload in LibrarySyncPayload.parseChunkedArticles(frame)) {
            val a = payload.article
            val expected = ArticleSyncManifestEntry(a.articleId, a.sourceDeviceId, a.contentHash, a.updatedAt,
                a.independentChangedAt, a.favoriteChangedAt, a.watchLaterChangedAt, a.deletedAt, a.deleted,
                payload.bodyHash, payload.bodyByteCount, payload.chunkSize, payload.chunkHashes,
                ArticleSyncBody.metadataHashFor(a), readingProgress = a.readingProgress,
                readingPositionBytes = a.readingPositionBytes, readingPositionContentHash = a.readingPositionContentHash,
                readingPositionChangedAt = a.readingPositionChangedAt, isRead = a.isRead)
            val receipt = MessageDigest.getInstance("SHA-256").digest(expected.toString().toByteArray())
                .joinToString("") { "%02x".format(it) }
            val previous = sentArticles[a.articleId]
            val candidate = if (previous?.receipt == receipt) previous else SentArticle(expected, mutableSetOf(), receipt)
            payload.chunks.forEach { candidate.chunks += it.index }
            sentArticles[a.articleId] = candidate
        }
    }
    fun confirmArticleBatch(articleIds: Set<String>) {
        articleIds.forEach { id -> sentArticles[id]?.let { confirmedProgress("sendArticle", it.receipt) } }
    }
    /** Old peers' current missing-chunk list (or complete body hash) is their receipt. */
    fun confirmPeerChunks(manifest: List<ArticleSyncManifestEntry>, requests: List<ArticleBodyRequest>) {
        val remote = manifest.associateBy { it.articleId }
        val missing = requests.associateBy { it.articleId }
        sentArticles.forEach { (id, sent) ->
            val existing = remote[id]
            val request = missing[id]
            // Transfer policy and legacy aliases describe routing, not business state.
            // A matching body alone never proves that new metadata/reading state applied.
            if (existing?.copy(bodySyncMode = sent.expected.bodySyncMode, legacyArticleIds = sent.expected.legacyArticleIds) == sent.expected) {
                confirmedProgress("sendArticle", sent.receipt)
            }
            val complete = existing?.bodyAvailable == true && existing.bodyHash == sent.bodyHash &&
                existing.chunkSize == sent.chunkSize && existing.chunkHashes == sent.chunkHashes
            sent.chunks.forEach { index ->
                val omitted = request != null && request.bodyHash == sent.bodyHash && !request.metadataOnly &&
                    request.chunkIndexes.isNotEmpty() && index !in request.chunkIndexes
                if (complete || omitted) confirmedProgress("sendChunk", "$id|${sent.bodyHash}|$index|${sent.chunkHashes[index]}")
            }
        }
    }

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
                policy.ready(reconnected = true)
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
