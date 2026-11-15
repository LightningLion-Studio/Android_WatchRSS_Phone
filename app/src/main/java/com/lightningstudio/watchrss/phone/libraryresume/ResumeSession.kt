package com.lightningstudio.watchrss.phone.libraryresume

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream

/** This dispatcher is the SOLE reader once all v14 ACKs finish. Its read loop routes
 * responses without waiting for inbound request execution. Both peers can begin at once.
 * It never creates v14 ACKs, reconnects, changes routes, or classifies transport failures. */
class ResumeSession(private val input: InputStream, private val output: OutputStream, private val scope: CoroutineScope) : AutoCloseable {
    private val writes = Mutex()
    private val controls = Mutex()
    private val cancelledJobs = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val pending = java.util.concurrent.ConcurrentHashMap<String, Pair<JSONObject, CompletableDeferred<JSONObject>>>()
    private val requests = Channel<JSONObject>(1)
    private val inboundActive = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var receiver: (suspend (JSONObject) -> JSONObject)? = null
    private var reader: Job? = null
    private var worker: Job? = null
    fun installReceiver(handler: suspend (JSONObject) -> JSONObject) { receiver = handler }
    fun start() {
        check(reader == null)
        worker = scope.launch {
            try {
                for (r in requests) {
                    val answer = try { (receiver ?: fail("AUTH_REVOKED"))(r) } catch (e: ResumeException) { ResumeResults.answer(r, null, if (r.nil("epoch")) null else r.n("epoch"), e.code) }
                    write(answer)
                    inboundActive.set(false)
                }
            } catch (e: Throwable) { abort(e) }
        }
        reader = scope.launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    val frame = ResumeWire.read(input)
                    if (frame.s("type") == "response") {
                        val pair = pending[frame.s("requestId")]
                        if (pair == null) { if (frame.optString("jobId") in cancelledJobs) continue else fail() }
                        validateCorrelation(pair.first, frame)
                        if (!pair.second.complete(frame)) fail()
                    } else {
                        if (!inboundActive.compareAndSet(false, true) || !requests.trySend(frame).isSuccess) fail("INVALID_FRAME")
                    }
                }
            } catch (e: Throwable) { abort(e) }
        }
    }
    private fun abort(error: Throwable) {
        pending.values.forEach { it.second.completeExceptionally(error) }
        requests.close(error)
        try { input.close() } catch (_: Exception) {}
        try { output.close() } catch (_: Exception) {}
        reader?.cancel(); worker?.cancel()
    }
    private suspend fun write(frame: JSONObject) = writes.withLock { withContext(Dispatchers.IO) { ResumeWire.write(output, frame) } }
    suspend fun exchange(frame: JSONObject): JSONObject = controls.withLock {
        ResumeWire.envelope(frame)
        val waiter = CompletableDeferred<JSONObject>()
        if (pending.putIfAbsent(frame.s("requestId"), frame to waiter) != null) fail("REQUEST_CONFLICT")
        try { write(frame); val answer = waiter.await(); if (!answer.getBoolean("success")) throw ResumeException(answer.o("error").s("code")); answer }
        finally { pending.remove(frame.s("requestId")) }
    }
    fun cancelJob(job: String) {
        cancelledJobs.add(job)
        pending.values.filter { it.first.optString("jobId") == job }.forEach { it.second.completeExceptionally(ResumeException("JOB_CANCELLED")) }
    }
    override fun close() { abort(CancellationException("C session closed")); try { output.close() } catch (_: Exception) {} }
    companion object {
        fun validateCorrelation(request: JSONObject, answer: JSONObject) {
            if (answer.s("type") != "response" || listOf("action", "version", "phase", "jobId", "requestId", "requestDigest", "planDigest").any { request.get(it) != answer.get(it) }) fail()
            if (request.s("phase") !in listOf("prepare", "begin") && (request.nil("epoch") != answer.nil("epoch") || !request.nil("epoch") && request.n("epoch") != answer.n("epoch"))) fail("STALE_EPOCH")
        }
    }
}
