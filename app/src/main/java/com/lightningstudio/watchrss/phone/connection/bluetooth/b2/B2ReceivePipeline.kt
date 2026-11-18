package com.lightningstudio.watchrss.phone.connection.bluetooth.b2

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger
import java.security.MessageDigest

/** Only the reader leases buffers, and only the file writer returns them. The
 * same eight credits bound all stages together, including durable replies. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class B2ReceivePipeline(
    parent: CoroutineScope,
    private val receiver: B2Receiver,
    private val clock: () -> Long,
    private val reply: (List<Reply>) -> Unit,
    private val abort: (Throwable, List<JSONObject>) -> Unit
) {
    data class Reply(val header: B2DataHeader, val request: JSONObject, val disposition: Int, val error: String? = null)
    private data class Block(val frame: B2Frame.Data, val request: JSONObject, val hash: String, val verifiedAt: Long = 0)
    private data class Written(val chunk: B2Receiver.WrittenChunk, val verifiedAt: Long)

    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)
    private val buffers = Channel<ByteArray>(8)
    private var reserved = false
    private fun stop(cause: Throwable, replies: List<JSONObject>) { if (job.isActive) abort(cause, replies) }
    fun close() {
        synchronized(credits) { credits.clear() }
        job.cancel()
    }
    private val hashing = Channel<Block>(8) { recycle(it.frame.bytes) }
    private val writing = Channel<Block>(8) { recycle(it.frame.bytes) }
    private val committing = Channel<Written>(8) { it.chunk.temp.file.delete() }
    private val credits = linkedSetOf<B2DataHeader>()
    private val working = AtomicInteger()
    private val idle = Channel<Unit>(Channel.CONFLATED)
    private val watchdog = B2LocalWatchdog(clock)

    fun acquire(h: B2DataHeader) = synchronized(credits) {
        credits.removeAll { !receiver.current(it.stream, it.epoch) }
        if (credits.size >= receiver.window(h.stream) ||
            credits.sumOf { it.length } + h.length > receiver.byteWindow(h.stream)) fail("FLOW_CONTROL_VIOLATION")
        if (credits.any { it.requestId == h.requestId || it.slot == h.slot && it.index == h.index }) fail("REQUEST_CONFLICT")
        credits.add(h)
    }

    fun release(h: B2DataHeader) {
        synchronized(credits) { credits.remove(h) }
    }

    fun buffer(stream: Int): ByteArray {
        if (!reserved) {
            repeat(receiver.window(stream)) { recycle(ByteArray(B2Wire.CHUNK)) }
            reserved = true
        }
        return buffers.tryReceive().getOrNull() ?: fail("LOCAL_IO")
    }
    fun recycle(bytes: ByteArray) { check(buffers.trySend(bytes).isSuccess) }

    fun submit(frame: B2Frame.Data, request: JSONObject, hash: String) {
        if (working.get() == 0) watchdog.completedWorkUnit()
        working.incrementAndGet()
        if (!hashing.trySend(Block(frame, request, hash)).isSuccess) {
            recycle(frame.bytes)
            fail("LOCAL_IO")
        }
    }

    fun checkWatchdog() { if (working.get() != 0) watchdog.check() }

    // A new grant can answer begin as soon as the previous generation has
    // released its buffers and temporary files. Old wire replies are fenced.
    suspend fun awaitIdle() { while (working.get() != 0) idle.receive() }

    private fun done(count: Int = 1) {
        watchdog.completedWorkUnit()
        if (working.addAndGet(-count) == 0) idle.trySend(Unit)
    }

    private fun rejected(block: Block, error: B2Exception) =
        B2Results.answer(block.request, null, block.frame.header.epoch, error.code)

    init {
        scope.launch {
            try {
                val sha = MessageDigest.getInstance("SHA-256")
                for (block in hashing) {
                    var forwarded = false
                    try {
                        val h = block.frame.header
                        if (!receiver.current(h.stream, h.epoch)) continue
                        sha.update(block.frame.bytes, 0, h.length)
                        if (B2Frames.hex(sha.digest()) != block.hash) fail("HASH_MISMATCH")
                        watchdog.completedWorkUnit()
                        forwarded = writing.trySend(block.copy(verifiedAt = clock())).isSuccess
                        if (!forwarded) fail("LOCAL_IO")
                    } catch (e: B2Exception) {
                        stop(e, listOf(rejected(block, e)))
                        return@launch
                    } finally {
                        if (!forwarded) {
                            recycle(block.frame.bytes)
                            done()
                        }
                    }
                }
            } catch (e: Throwable) { stop(e, emptyList()) }
        }
        scope.launch {
            try {
                for (block in writing) {
                    var written: B2Receiver.WrittenChunk? = null
                    var forwarded = false
                    var recycled = false
                    try {
                        written = receiver.writeChunk(block.frame, block.request)
                        if (written == null) continue
                        watchdog.completedWorkUnit()
                        recycle(block.frame.bytes)
                        recycled = true
                        forwarded = committing.trySend(Written(written, block.verifiedAt)).isSuccess
                        if (!forwarded) fail("LOCAL_IO")
                        written = null
                    } catch (e: B2Exception) {
                        if (e.code != "STALE_EPOCH" || receiver.current(block.frame.header.stream, block.frame.header.epoch)) {
                            stop(e, listOf(rejected(block, e)))
                            return@launch
                        }
                    } finally {
                        written?.temp?.file?.delete()
                        if (!recycled) recycle(block.frame.bytes)
                        if (!forwarded) done()
                    }
                }
            } catch (e: Throwable) { stop(e, emptyList()) }
        }
        scope.launch {
            var carry: Written? = null
            val group = ArrayList<Written>(4)
            try {
                while (true) {
                    val first = carry ?: committing.receive()
                    carry = null
                    group.add(first)
                    val header = first.chunk.header
                    val deadline = first.verifiedAt + 20
                    while (receiver.current(header.stream, header.epoch) &&
                        group.size < minOf(4, receiver.window(header.stream))) {
                        val next = committing.tryReceive().getOrNull() ?: run {
                            val left = deadline - clock()
                            if (left <= 0) null else select<Written?> {
                                committing.onReceive { it }
                                onTimeout(left) { null }
                            }
                        } ?: break
                        if (next.chunk.header.epoch != header.epoch) { carry = next; break }
                        group.add(next)
                    }
                    if (receiver.current(header.stream, header.epoch)) {
                        val chunks = group.map { it.chunk }
                        val committed = try {
                            receiver.commitChunks(chunks)
                        } catch (e: B2Exception) {
                            if (B2Results.errorValue(e.code).s("recovery") !in listOf("WAIT", "QUERY", "BEGIN")) {
                                stop(e, listOf(B2Results.answer(first.chunk.request, null, header.epoch, e.code)))
                                return@launch
                            }
                            reply(chunks.map { Reply(it.header, it.request, 0, e.code) })
                            emptyList()
                        }
                        if (committed.isNotEmpty()) {
                            reply(committed.map { (chunk, disposition) -> Reply(chunk.header, chunk.request, disposition) })
                            committed.forEach { it.progressKey?.let(receiver::reportChunkProgress) }
                        }
                    }
                    group.forEach { it.chunk.temp.file.delete() }
                    done(group.size)
                    group.clear()
                }
            } catch (e: Throwable) { stop(e, emptyList()) }
            finally {
                carry?.chunk?.temp?.file?.delete()
                group.forEach { it.chunk.temp.file.delete() }
                committing.cancel()
            }
        }
        job.invokeOnCompletion {
            hashing.cancel()
            writing.cancel()
            committing.cancel()
        }
    }
}
