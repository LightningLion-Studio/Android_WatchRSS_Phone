package com.lightningstudio.watchrss.phone.connection.bluetooth.b2

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import org.json.JSONObject
import java.io.*
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Sole RFCOMM reader/writer after switch. Business workers never own the read loop.
 * All close paths revoke write authority before interrupting the underlying socket. */
@OptIn(InternalCoroutinesApi::class)
class B2Session(private val input: InputStream, private val output: OutputStream,
    parent: CoroutineScope, private val closeTransport: () -> Unit,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val authorize: () -> Unit = {}) : AutoCloseable {
    private class DiscardedOldData : Exception()
    private val lifecycle = Any()
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job + Dispatchers.IO)
    private val ended = CompletableDeferred<Throwable>()
    private val high = Channel<B2Frame>(8)
    private val normal = Channel<B2Frame>(8)
    private val stopped = AtomicBoolean(false)
    private val outstandingWrites = java.util.concurrent.atomic.AtomicInteger()
    @Volatile private var cause: Throwable? = null
    private data class Wait(val request: JSONObject, val answer: CompletableDeferred<JSONObject>, @Volatile var start: Long? = null)
    private data class Grant(val stream: Int, val job: String, val plan: String, val selection: String, val epoch: Long, val heartbeat: B2Heartbeat)
    private val pending = ConcurrentHashMap<String, Wait>()
    private val dataWait = ConcurrentHashMap<String, CompletableDeferred<Int>>()
    private val dataRequests = ConcurrentHashMap<String, JSONObject>()
    private val busy = ConcurrentHashMap.newKeySet<String>()
    private val inbound = ConcurrentHashMap<String, Any>()
    private val inboundDirections = ConcurrentHashMap<String, Int>()
    private val inboundHeartbeats = ConcurrentHashMap.newKeySet<String>()
    private val sendFlows = mapOf(1 to B2Flow(1), 2 to B2Flow(2))
    private val grants = ConcurrentHashMap<String, Grant>()
    private val receiveEpochs = ConcurrentHashMap<String, Long>()
    private val expectedSources = ConcurrentHashMap.newKeySet<String>()
    private var confirmedBinding: String? = null
    fun confirmBinding(digest: String, sourceJobs: List<String>) = synchronized(lifecycle) {
        if (confirmedBinding != null && confirmedBinding != digest) fail("PLAN_CONFLICT")
        confirmedBinding = digest; expectedSources.addAll(sourceJobs)
    }
    private val workers = listOf(Channel<suspend () -> Unit>(1), Channel<suspend () -> Unit>(1))
    @Volatile private var handler: (suspend (JSONObject) -> JSONObject)? = null
    @Volatile private var receiver: B2Receiver? = null
    private val controls = B2Rate(128.0, 64.0, clock)
    private val acks = B2Rate(256.0, 128.0, clock)
    private val staleAcks = B2Rate(32.0, 16.0, clock)
    private val replay = B2Rate(8.0, 4.0, clock)
    @Volatile private var started = false
    @Volatile private var readingSince = 0L
    @Volatile private var lastRead = 0L
    @Volatile private var writingSince = 0L
    @Volatile private var lastWrite = 0L
    private val localWork = ConcurrentHashMap<String, B2LocalWatchdog>()
    private var cancellationHandle: DisposableHandle? = null
    init {
        cancellationHandle = parent.coroutineContext[Job]?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { failure ->
            if (failure != null) {
                if (failure is CancellationException && runCatching(authorize).isSuccess) cancelUser() else abort(failure)
            }
        }
    }
    fun install(receiver: B2Receiver, handler: suspend (JSONObject) -> JSONObject) { this.receiver = receiver; this.handler = handler }
    fun bindSource(stream: Int, job: String, plan: String, selection: String, epoch: Long) {
        if (sendFlows.getValue(stream).epoch != epoch) replay.reset()
        sendFlows.getValue(stream).bind(job, plan, selection, epoch)
        grants[job] = Grant(stream, job, plan, selection, epoch, B2Heartbeat(clock))
    }
    fun finishSource(job: String) { grants.remove(job) }
    fun workProgress(job: String) { localWork[job]?.completedWorkUnit() }
    suspend fun <T> localWork(job: String, block: () -> T): T {
        val watch = B2LocalWatchdog(clock)
        if (localWork.putIfAbsent(job, watch) != null) fail("BUSY")
        try { return runInterruptible(Dispatchers.IO) { block() } } finally { localWork.remove(job, watch) }
    }
    private fun checkOpen() { cause?.let { throw it }; if (stopped.get()) fail("AUTH_REVOKED") }
    /** The parser/dispatcher exists before the last legacy exchange. The reader starts
     * only after the full switch response; its first read retains any glued B2 tail. */
    suspend fun switch(capability: JSONObject, enabled: Boolean) {
        check(!started); checkOpen()
        val done = CompletableDeferred<Unit>()
        val timer = scope.launch { delay(30000); val e = SocketTimeoutException("B2 switch deadline"); done.completeExceptionally(e); abort(e) }
        scope.launch { try { B2Switch.perform(input, output, capability, enabled); done.complete(Unit) } catch (e: Throwable) { done.completeExceptionally(e); abort(e) } }
        try { done.await() } finally { timer.cancel() }
    }
    fun start() {
        check(!started); checkOpen(); started = true
        workers.forEach { queue -> scope.launch { try { for (work in queue) work() } catch (e: Throwable) { abort(e) } } }
        scope.launch {
            try {
                while (isActive) {
                    val frame = high.tryReceive().getOrNull() ?: select { high.onReceive { it }; normal.onReceive { it } }
                    synchronized(lifecycle) { checkOpen(); writingSince = clock(); lastWrite = writingSince }
                    if (frame is B2Frame.Control && frame.value.s("type") == "request") {
                        val r = frame.value
                        pending[r.s("requestId")]?.start = clock()
                        if (r.s("phase") == "missing" && r.o("payload").n("limit") == 0L) grants[r.s("jobId")]?.heartbeat?.beginWrite()
                    }
                    B2Frames.write(object : OutputStream() {
                        override fun write(b: Int) { checkOpen(); output.write(b); lastWrite = clock() }
                        override fun write(b: ByteArray, off: Int, len: Int) { checkOpen(); output.write(b, off, len); lastWrite = clock() }
                        override fun flush() { checkOpen(); output.flush() }
                    }, frame)
                    if (frame is B2Frame.Control && frame.value.s("type") == "response" && frame.value.s("phase") == "missing") inboundHeartbeats.remove(frame.value.s("jobId"))
                    writingSince = 0; outstandingWrites.decrementAndGet()
                }
            } catch (e: Throwable) { abort(e) }
        }
        scope.launch {
            val tracked = object : InputStream() {
                override fun read(): Int { val b = input.read(); if (b >= 0) { if (readingSince == 0L) readingSince = clock(); lastRead = clock() }; return b }
                override fun read(b: ByteArray, off: Int, len: Int): Int { val n = input.read(b, off, len); if (n > 0) { if (readingSince == 0L) readingSince = clock(); lastRead = clock() }; return n }
            }
            try {
                while (isActive) {
                    var dataRequest: JSONObject? = null
                    val frame = try { B2Frames.read(tracked) { h ->
                        // Only a bounded, committed descriptor lookup; no business worker/DB lane.
                        val target = receiver ?: fail("AUTH_REVOKED")
                        val inspected = try { runBlocking { target.inspect(h, inbound.containsKey(target.jobFor(h.stream))) } }
                        catch (rejected: B2RejectedData) {
                            replay.take(); B2Frames.drain(tracked, h.length, rejected.hash)
                            enqueue(B2Frame.Control(B2Results.answer(rejected.request, null, h.epoch, rejected.rejection)), true)
                            throw DiscardedOldData()
                        }
                        dataRequest = inspected.first
                        if (target.isOld(h)) { replay.take(); B2Frames.drain(tracked, h.length, inspected.second); throw DiscardedOldData() }
                    } } catch (_: DiscardedOldData) { readingSince = 0; continue }
                    readingSince = 0
                    checkOpen()
                    when (frame) {
                        is B2Frame.Ack -> {
                            acks.take()
                            val settled = sendFlows.getValue(frame.stream).settle(frame)
                            if (settled.isEmpty()) { staleAcks.take(); continue }
                            settled.forEach { row -> dataWait[row.header.requestId]?.complete(frame.entries.single().disposition) ?: fail() }
                        }
                        is B2Frame.Data -> {
                            val request = dataRequest ?: fail()
                            dispatch(request) { release ->
                                val ack = (receiver ?: fail("AUTH_REVOKED")).data(frame, request)
                                release()
                                enqueue(ack, true) // durable callback; no timer or batching wait
                            }
                        }
                        is B2Frame.Control -> {
                            controls.take()
                            val r = frame.value
                            if (r.s("type") == "response") acceptResponse(r)
                            else if (r.s("phase") == "missing" && r.o("payload").n("limit") == 0L) {
                                if (!inboundHeartbeats.add(r.s("jobId"))) fail("INVALID_FRAME")
                                // Separate lightweight IO work; neither DB work nor its mutex
                                // can hold this job's reserved heartbeat response slot.
                                scope.launch {
                                    val key = "heartbeat:" + r.s("jobId"); val watch = B2LocalWatchdog(clock)
                                    localWork[key] = watch
                                    try {
                                        val answer = runInterruptible(Dispatchers.IO) { (receiver ?: fail("AUTH_REVOKED")).heartbeat(r) }
                                        enqueue(B2Frame.Control(answer), true)
                                    } catch (e: B2Exception) { abort(e, listOf(B2Results.answer(r, null, r.n("epoch"), e.code))) }
                                    catch (e: Throwable) { abort(e) }
                                    finally { localWork.remove(key, watch) }
                                }
                            } else if (r.s("phase") == "cancel") {
                                // Revoke immediately, before a potentially blocked business worker.
                                val source = expectedSources.contains(r.s("jobId")) || grants.containsKey(r.s("jobId"))
                                val receiving = receiver ?: fail("AUTH_REVOKED")
                                if (!source) receiving.validateCancel(r)
                                receiving.fenceNow()
                                val handle = handler
                                cleanup.launch { runCatching { if (source) handle?.invoke(r) else receiving.persistCancel(r) } }
                                fail(if (r.o("payload").s("reason") == "SOURCE_CHANGED") "SOURCE_CHANGED" else "JOB_CANCELLED")
                            } else dispatch(r) { release ->
                                val answer = (handler ?: fail("AUTH_REVOKED"))(r)
                                if (r.s("phase") == "begin" && answer.getBoolean("success")) {
                                    val epoch = answer.n("epoch")
                                    if (receiveEpochs.put(r.s("jobId"), epoch) != epoch) replay.reset()
                                }
                                release()
                                enqueue(B2Frame.Control(answer), false)
                            }
                        }
                    }
                }
            } catch (e: Throwable) { abort(e) }
        }
        scope.launch {
            try {
                while (isActive) {
                    delay(100)
                    val now = clock()
                    if (readingSince != 0L && (now - readingSince >= 60000 || now - lastRead >= 30000)) throw SocketTimeoutException("B2 partial frame deadline")
                    if (writingSince != 0L && (now - writingSince >= 60000 || now - lastWrite >= 30000)) throw SocketTimeoutException("B2 write deadline")
                    pending.values.forEach { w ->
                        val r = w.request; val phase = r.s("phase")
                        val heartbeat = phase == "missing" && r.o("payload").n("limit") == 0L
                        if ((heartbeat || phase in listOf("prepare", "begin", "missing", "complete", "cancel")) && w.start?.let { now - it >= 30000 } == true) throw SocketTimeoutException("B2 response deadline")
                    }
                    localWork.values.forEach { it.check() }
                    for ((id, g) in grants) {
                        g.heartbeat.checkDeadline()
                        if (!g.heartbeat.due() || pending.values.any { it.request.optString("jobId") == id && it.request.optString("phase") == "missing" && it.request.o("payload").n("limit") == 0L }) continue
                        g.heartbeat.queue()
                        scope.launch {
                            try { exchange(B2Wire.request("missing", id, g.plan, g.epoch, obj("afterObjectId" to null, "afterChunkIndex" to null, "limit" to 0))); g.heartbeat.reply() }
                            catch (e: Throwable) { abort(e) }
                        }
                    }
                }
            } catch (e: Throwable) { abort(e) }
        }
    }
    private fun dispatch(r: JSONObject, work: suspend (() -> Unit) -> Unit) {
        val key = if (r.nil("jobId")) "prepare" else r.s("jobId")
        val token = Any()
        if (inbound.putIfAbsent(key, token) != null) fail("INVALID_FRAME")
        val worker = if (receiverJobDirection(r) == 1) 0 else 1
        if (!workers[worker].trySend {
            val watch = B2LocalWatchdog(clock)
            localWork[key] = watch
            try { work { inbound.remove(key, token); Unit } }
            catch (e: B2Exception) {
                val action = B2Results.errorValue(e.code).s("recovery")
                val response = B2Results.answer(r, null, if (r.nil("epoch")) null else r.n("epoch"), e.code)
                if (action in listOf("STOP", "REPLAN")) abort(e, listOf(response))
                else { replay.take(); inbound.remove(key, token); enqueue(B2Frame.Control(response), true) }
            } finally { localWork.remove(key, watch); inbound.remove(key, token) }
        }.isSuccess) fail("INVALID_FRAME")
    }
    private fun receiverJobDirection(r: JSONObject): Int {
        if (r.s("phase") == "begin") {
            val direction = if (r.o("payload").s("direction") == "phoneToWatch") 1 else 2
            val prior = inboundDirections.putIfAbsent(r.s("jobId"), direction)
            if (prior != null && prior != direction) fail("PLAN_CONFLICT")
            return direction
        }
        return inboundDirections[r.optString("jobId")] ?: 2
    }
    private fun enqueue(frame: B2Frame, priority: Boolean) {
        checkOpen()
        outstandingWrites.incrementAndGet()
        if (!(if (priority) high else normal).trySend(frame).isSuccess) { outstandingWrites.decrementAndGet(); fail("LIMIT_EXCEEDED") }
    }
    private fun acceptResponse(r: JSONObject) {
        val id = r.s("requestId")
        val grant = grants[r.optString("jobId")]
        if (r.s("phase") == "putChunk" && grant != null && !r.nil("epoch") && r.n("epoch") < grant.epoch) {
            if (r.s("planDigest") != grant.plan || r.getBoolean("success")) fail()
            staleAcks.take(); return
        }
        val data = dataRequests[id]
        if (data != null) {
            validateCorrelation(data, r)
            if (r.s("phase") != "putChunk" || r.getBoolean("success")) fail()
            val flow = sendFlows.values.singleOrNull { it.pendingCount > 0 } ?: fail()
            flow.negative(r.s("jobId"), r.s("planDigest"), r.n("epoch"), id, r.s("requestDigest"))
            val error = B2Exception(r.o("error").s("code"))
            if (r.o("error").s("recovery") in listOf("STOP", "REPLAN")) { abort(error); return }
            dataWait[id]?.completeExceptionally(error) ?: fail(); return
        }
        val w = pending[id] ?: fail("REQUEST_CONFLICT")
        validateCorrelation(w.request, r)
        if (!r.getBoolean("success") && r.o("error").s("recovery") in listOf("STOP", "REPLAN")) { abort(B2Exception(r.o("error").s("code"))); return }
        if (!w.answer.complete(r)) fail("REQUEST_CONFLICT")
    }
    suspend fun exchange(r: JSONObject): JSONObject {
        checkOpen(); B2Wire.envelope(r)
        val heartbeat = r.s("phase") == "missing" && r.o("payload").n("limit") == 0L
        val key = (if (r.nil("jobId")) "prepare" else r.s("jobId")) + if (heartbeat) ":heartbeat" else ":business"
        if (!busy.add(key)) fail("BUSY")
        val w = Wait(r, CompletableDeferred())
        if (pending.putIfAbsent(r.s("requestId"), w) != null) { busy.remove(key); fail("REQUEST_CONFLICT") }
        try {
            enqueue(B2Frame.Control(r), heartbeat)
            val answer = w.answer.await(); checkOpen()
            if (!answer.getBoolean("success")) fail(answer.o("error").s("code"))
            return answer
        } finally { pending.remove(r.s("requestId")); busy.remove(key) }
    }
    suspend fun sendData(request: JSONObject, slot: Int, bytes: ByteArray): Int {
        checkOpen(); B2Wire.envelope(request)
        val g = grants[request.s("jobId")] ?: fail("AUTH_REVOKED")
        val stream = sendFlows.getValue(g.stream)
        val h = stream.reserve(slot, request.o("payload").n("chunkIndex").toInt(), bytes.size, request.s("requestId"), request.s("requestDigest"), request.o("payload").s("objectId"))
        if (h.epoch != g.epoch) fail("STALE_EPOCH")
        val waiter = CompletableDeferred<Int>()
        dataRequests[h.requestId] = request; dataWait[h.requestId] = waiter
        try { enqueue(B2Frame.Data(h, bytes), false); return waiter.await().also { checkOpen() } }
        finally { dataRequests.remove(h.requestId); dataWait.remove(h.requestId) }
    }
    suspend fun flush() { while (outstandingWrites.get() > 0) { checkOpen(); delay(1) } }
    suspend fun awaitTermination(): Throwable = ended.await()
    fun cancelUser() {
        val requests = grants.values.map { B2Wire.request("cancel", it.job, it.plan, it.epoch, obj("reason" to "USER_CANCEL")) } +
            runCatching { receiver?.cancellationRequests("USER_CANCEL").orEmpty() }.getOrDefault(emptyList())
        abort(B2Exception("USER_CANCELLED"), requests.distinctBy { it.s("jobId") })
    }
    private fun abort(error: Throwable, cancelRequests: List<JSONObject> = emptyList()) {
        var idle = false
        synchronized(lifecycle) {
            if (!stopped.compareAndSet(false, true)) return
            idle = writingSince == 0L
            cause = error; receiver?.fenceNow(); sendFlows.values.forEach { it.clear() }
            while (normal.tryReceive().isSuccess) { }
            while (high.tryReceive().isSuccess) { }
            pending.values.forEach { it.answer.completeExceptionally(error) }; dataWait.values.forEach { it.completeExceptionally(error) }
            ended.complete(error); cancellationHandle?.dispose(); job.cancel()
        }
        // A user cancellation never waits for output or reconnects. If no frame is in
        // progress, the stopped writer hands its stream to one bounded best-effort cancel.
        if (idle && cancelRequests.isNotEmpty()) {
            cleanup.launch { delay(250); runCatching(closeTransport) }
            cleanup.launch { try { cancelRequests.forEach { B2Frames.write(output, B2Frame.Control(it)) } } catch (_: Exception) { }
                finally { runCatching(closeTransport) } }
        } else cleanup.launch { runCatching(closeTransport) }
    }
    override fun close() { abort(CancellationException("B2 connection closed")) }
    companion object {
        private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        fun validateCorrelation(request: JSONObject, answer: JSONObject) {
            if (answer.s("type") != "response" || listOf("action", "version", "phase", "jobId", "requestId", "requestDigest", "planDigest").any { request.get(it) != answer.get(it) }) fail()
            if (request.s("phase") !in listOf("prepare", "begin") && request.n("epoch") != answer.n("epoch")) fail("STALE_EPOCH")
        }
    }
}
