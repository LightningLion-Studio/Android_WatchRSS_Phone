package com.lightningstudio.watchrss.phone.libraryresume

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** This dispatcher is the SOLE reader once all v14 ACKs finish. Its read loop routes
 * responses without waiting for inbound request execution. Both peers can begin at once.
 * It never creates v14 ACKs, reconnects, changes routes, or classifies transport failures. */
class ResumeSession internal constructor(
    private val input: InputStream,
    private val output: OutputStream,
    private val scope: CoroutineScope,
    private val stopReplyTimeoutMillis: Long
) : AutoCloseable {
    constructor(input: InputStream, output: OutputStream, scope: CoroutineScope) : this(input, output, scope, 1000)
    init { require(stopReplyTimeoutMillis > 0) }
    private val writes = Mutex()
    private val controls = Mutex()
    private val lifecycle = Any()
    private val cancelledJobs = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val pending = java.util.concurrent.ConcurrentHashMap<String, Pair<JSONObject, CompletableDeferred<JSONObject>>>()
    private val requests = Channel<JSONObject>(1)
    private val inboundActive = AtomicBoolean(false)
    private val stopped = CompletableDeferred<Throwable>()
    private val firstStop = AtomicReference<Throwable?>()
    private var rejectSuccessfulReplies = false // guarded by lifecycle, unlike ordinary EOF
    private val streamsClosing = AtomicBoolean(false)
    // No exchange or controller notification joins a possibly blocking stream operation.
    // The STOP deadline cancels this scope and starts independent stream cleanup.
    private val streamWork = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var receiver: (suspend (JSONObject) -> JSONObject)? = null
    private var reader: Job? = null
    private var worker: Job? = null
    fun installReceiver(handler: suspend (JSONObject) -> JSONObject) { receiver = handler }

    /** Logical termination is published immediately, independently of error output/close.
     * Pending exchanges fail with this same cause, including the opposite directed job. */
    suspend fun awaitTermination(): Throwable = stopped.await()

    fun start() {
        check(reader == null)
        worker = scope.launch {
            try {
                for (r in requests) {
                    firstStop.get()?.let { throw it }
                    val answer = try {
                        (receiver ?: fail("AUTH_REVOKED"))(r)
                    } catch (e: ResumeException) {
                        if (ResumeResults.errorValue(e.code).s("recovery") == "STOP") {
                            stopLogically(e, rejectSuccess = true)
                            // The error frame is optional after logical termination. Neither
                            // a blocked write nor a blocked close delays controller revocation.
                            bestEffortStopReply(ResumeResults.answer(r, null, if (r.nil("epoch")) null else r.n("epoch"), e.code))
                            return@launch
                        }
                        ResumeResults.answer(r, null, if (r.nil("epoch")) null else r.n("epoch"), e.code)
                    }
                    // Release before bytes become visible; the sole worker still serializes handlers.
                    inboundActive.set(false)
                    write(answer)
                }
            } catch (e: Throwable) { abort(e) }
        }
        reader = scope.launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    val frame = ResumeWire.read(input)
                    if (firstStop.get() != null) return@launch
                    if (frame.s("type") == "response") {
                        val pair = pending[frame.s("requestId")]
                        if (pair == null) { if (frame.optString("jobId") in cancelledJobs) continue else fail() }
                        validateCorrelation(pair.first, frame)
                        if (!frame.getBoolean("success") && frame.o("error").s("recovery") == "STOP") {
                            abort(ResumeException(frame.o("error").s("code")))
                            return@launch
                        }
                        synchronized(lifecycle) {
                            val cause = firstStop.get()
                            if (cause != null) pair.second.completeExceptionally(cause)
                            else if (!pair.second.complete(frame)) fail()
                        }
                    } else {
                        if (!inboundActive.compareAndSet(false, true) || !requests.trySend(frame).isSuccess) fail("INVALID_FRAME")
                    }
                }
            } catch (e: Throwable) { abort(e) }
        }
    }
    /** Registration, response adoption, STOP publication and success return share this
     * boundary. A reply already delivered before ordinary EOF can still be adopted;
     * a reserved protocol/business/account STOP always defeats an unreturned success. */
    private fun stopLogically(error: Throwable, rejectSuccess: Boolean): Throwable = synchronized(lifecycle) {
        firstStop.compareAndSet(null, error)
        val cause = firstStop.get()!!
        rejectSuccessfulReplies = rejectSuccessfulReplies || rejectSuccess
        pending.values.forEach { it.second.completeExceptionally(cause) }
        requests.cancel(CancellationException("C session stopped", cause))
        stopped.complete(cause)
        cause
    }
    private fun bestEffortStopReply(answer: JSONObject) {
        // Start the timer first, separately from the write coroutine. A blocking native
        // OutputStream cannot make a structured withTimeout wait for its own IO child.
        streamWork.launch(start = CoroutineStart.UNDISPATCHED) {
            delay(stopReplyTimeoutMillis)
            closeStreamsOnce()
        }
        streamWork.launch {
            try { write(answer) }
            catch (_: Throwable) { /* Preserve the already published typed STOP. */ }
            finally { closeStreamsOnce() }
        }
    }
    private fun abort(error: Throwable) {
        stopLogically(error, rejectSuccess = error is ResumeException)
        closeStreamsOnce()
    }
    private fun closeStreamsOnce() {
        if (!streamsClosing.compareAndSet(false, true)) return
        reader?.cancel(); worker?.cancel(); streamWork.cancel()
        // Close methods may themselves block/flush. They must never run on a controller,
        // waiter, or timeout coroutine, nor prevent the other stream from closing.
        val source = input; val sink = output
        streamCleanup.launch { try { source.close() } catch (_: Exception) {} }
        streamCleanup.launch { try { sink.close() } catch (_: Exception) {} }
    }
    private suspend fun write(frame: JSONObject) = writes.withLock {
        val stopReply = frame.s("type") == "response" && !frame.getBoolean("success") && frame.o("error").s("recovery") == "STOP"
        withContext(Dispatchers.IO) {
            val guarded = object : OutputStream() {
                private fun checkWrite() {
                    if (!stopReply) firstStop.get()?.let { throw it }
                    if (streamsClosing.get()) throw java.io.IOException("C streams closing")
                }
                override fun write(b: Int) { checkWrite(); output.write(b) }
                override fun write(bytes: ByteArray, offset: Int, length: Int) { checkWrite(); output.write(bytes, offset, length) }
                override fun flush() { checkWrite(); output.flush() }
            }
            ResumeWire.write(guarded, frame)
        }
    }
    suspend fun exchange(frame: JSONObject): JSONObject = controls.withLock {
        firstStop.get()?.let { throw it }
        ResumeWire.envelope(frame)
        val waiter = CompletableDeferred<JSONObject>()
        synchronized(lifecycle) {
            firstStop.get()?.let { throw it }
            if (pending.putIfAbsent(frame.s("requestId"), frame to waiter) != null) fail("REQUEST_CONFLICT")
        }
        // Await the reply independently of request output too: STOP must release this
        // exchange even if its own write/flush, rather than the error reply, is blocked.
        streamWork.launch { try { write(frame) } catch (e: Throwable) { abort(e) } }
        try {
            val answer = waiter.await()
            synchronized(lifecycle) {
                if (rejectSuccessfulReplies) firstStop.get()?.let { throw it }
                if (!answer.getBoolean("success")) throw ResumeException(answer.o("error").s("code"))
                answer
            }
        } catch (e: Throwable) { throw (firstStop.get() ?: e) }
        finally { pending.remove(frame.s("requestId")) }
    }
    fun cancelJob(job: String) {
        cancelledJobs.add(job)
        pending.values.filter { it.first.optString("jobId") == job }.forEach { it.second.completeExceptionally(ResumeException("JOB_CANCELLED")) }
    }
    override fun close() { abort(CancellationException("C session closed")) }
    companion object {
        private val streamCleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        fun validateCorrelation(request: JSONObject, answer: JSONObject) {
            if (answer.s("type") != "response" || listOf("action", "version", "phase", "jobId", "requestId", "requestDigest", "planDigest").any { request.get(it) != answer.get(it) }) fail()
            if (request.s("phase") !in listOf("prepare", "begin") && (request.nil("epoch") != answer.nil("epoch") || !request.nil("epoch") && request.n("epoch") != answer.n("epoch"))) fail("STALE_EPOCH")
        }
    }
}
