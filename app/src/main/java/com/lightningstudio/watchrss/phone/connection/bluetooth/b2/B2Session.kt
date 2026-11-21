package com.lightningstudio.watchrss.phone.connection.bluetooth.b2

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import org.json.JSONObject
import java.io.*
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
    private sealed interface Outgoing {
        data class Frame(val frame: B2Frame, val heartbeatJob: String? = null) : Outgoing
        data class Data(val request: JSONObject, val slot: Int, val bytes: ByteArray, val answer: CompletableDeferred<Int>) : Outgoing
        data class Durable(val replies: List<B2ReceivePipeline.Reply>) : Outgoing
    }
    private val high = Channel<Outgoing>(8)
    private val durable = Channel<Outgoing>(8)
    private val normal = Channel<Outgoing>(8)
    private val dataQueue = Channel<Outgoing.Data>(Channel.RENDEZVOUS) { it.answer.cancel() }
    private val stopped = AtomicBoolean(false)
    private val outstandingWrites = java.util.concurrent.atomic.AtomicInteger()
    @Volatile private var cause: Throwable? = null
    private data class Wait(val request: JSONObject, val answer: CompletableDeferred<JSONObject>, @Volatile var start: Long? = null)
    private data class Grant(val stream: Int, val job: String, val plan: String, val selection: String, val epoch: Long, val heartbeat: B2Heartbeat)
    private val pending = ConcurrentHashMap<String, Wait>()
    private data class DataWait(val request: JSONObject, val answer: CompletableDeferred<Int>)
    private val dataWait = mutableMapOf<String, DataWait>() // guarded by lifecycle
    private val busy = ConcurrentHashMap.newKeySet<String>()
    private val inbound = ConcurrentHashMap<String, Any>()
    private val inboundDirections = ConcurrentHashMap<String, Int>()
    private val inboundHeartbeats = ConcurrentHashMap.newKeySet<String>()
    private val sendFlows = mapOf(1 to B2Flow(1), 2 to B2Flow(2))
    private val grants = ConcurrentHashMap<String, Grant>()
    private val sourceBindings = mutableMapOf<String, Grant>() // guarded by lifecycle
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
    @Volatile private var pipeline: B2ReceivePipeline? = null
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
    fun install(receiver: B2Receiver, handler: suspend (JSONObject) -> JSONObject) {
        pipeline?.close()
        this.receiver = receiver
        this.pipeline = B2ReceivePipeline(scope, receiver, clock, { replies ->
            enqueue(Outgoing.Durable(replies), true)
        }, ::abort)
        this.handler = handler
    }
    fun bindSource(stream: Int, job: String, plan: String, selection: String, epoch: Long,
        chunks: Int = 1, bytes: Int = chunks * B2Wire.CHUNK) = synchronized(lifecycle) {
        checkOpen()
        val flow = sendFlows.getValue(stream)
        if (flow.epoch != epoch) {
            replay.reset()
            dataWait.values.filter { it.request.s("jobId") == job }.forEach {
                dataWait.remove(it.request.s("requestId"))
                it.answer.completeExceptionally(B2Exception("LEASE_EXPIRED"))
            }
        }
        flow.bind(job, plan, selection, epoch, chunks, bytes)
        if (grants[job]?.epoch != epoch) {
            val grant = Grant(stream, job, plan, selection, epoch, B2Heartbeat(clock))
            grants[job] = grant
            sourceBindings[job] = grant
        }
    }
    fun finishSource(job: String, completed: Boolean = true) {
        if (completed) receiver?.sourceCompleted(job)
        grants.remove(job)
    }
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
        val timer = scope.launch { delay(30000); val e = B2NetworkTimeout("B2 switch deadline"); done.completeExceptionally(e); abort(e) }
        scope.launch { try { B2Switch.perform(input, output, capability, enabled); done.complete(Unit) } catch (e: Throwable) { done.completeExceptionally(e); abort(e) } }
        try { done.await() } finally { timer.cancel() }
    }
    fun start() {
        check(!started); checkOpen(); started = true
        workers.forEach { queue -> scope.launch { try { for (work in queue) work() } catch (e: Throwable) { abort(e) } } }
        scope.launch {
            try {
                while (isActive) {
                    val outgoing = high.tryReceive().getOrNull() ?: durable.tryReceive().getOrNull() ?: select {
                        high.onReceive { it }
                        durable.onReceive { it }
                        normal.onReceive { it }
                        dataQueue.onReceive { it }
                    }
                    when (outgoing) {
                        is Outgoing.Frame -> {
                            writeFrame(outgoing.frame, outgoing.heartbeatJob)
                        }
                        is Outgoing.Durable -> writeDurable(outgoing.replies)
                        is Outgoing.Data -> {
                            try {
                                val frame = synchronized(lifecycle) {
                                    checkOpen()
                                    val r = outgoing.request
                                    val g = grants[r.s("jobId")] ?: fail("AUTH_REVOKED")
                                    if (r.n("epoch") != g.epoch) fail("LEASE_EXPIRED")
                                    val p = r.o("payload")
                                    val h = sendFlows.getValue(g.stream).reserve(outgoing.slot, p.n("chunkIndex").toInt(),
                                        outgoing.bytes.size, r.s("requestId"), r.s("requestDigest"), p.s("objectId"))
                                    dataWait[h.requestId] = DataWait(outgoing.request, outgoing.answer)
                                    writingSince = clock()
                                    lastWrite = writingSince
                                    B2Frame.Data(h, outgoing.bytes)
                                }
                                writeFrame(frame)
                            } catch (e: B2Exception) {
                                if (e.code != "LEASE_EXPIRED") throw e
                                outgoing.answer.completeExceptionally(e)
                            }
                        }
                    }
                    outstandingWrites.decrementAndGet()
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
                    var binding: Pair<JSONObject, String>? = null
                    var buffer: ByteArray? = null
                    val frame = try {
                        B2Frames.readInto(tracked) { h ->
                            val target = receiver ?: fail("AUTH_REVOKED")
                            val pipe = pipeline ?: fail("AUTH_REVOKED")
                            val inspected = try {
                                runBlocking { target.inspect(h) {
                                    pipe.acquire(h)
                                    if (inbound.containsKey(target.jobFor(h.stream))) fail("BUSY")
                                } }
                            } catch (rejected: B2RejectedData) {
                                if (rejected.rejection == "FLOW_CONTROL_VIOLATION") {
                                    val failure = B2Exception(rejected.rejection)
                                    abort(failure, listOf(B2Results.answer(rejected.request, null, h.epoch, failure.code)))
                                    throw failure
                                }
                                // A recoverable rejection holds credit until its reply starts.
                                replay.take()
                                B2Frames.drain(tracked, h.length, rejected.hash)
                                enqueue(Outgoing.Durable(listOf(B2ReceivePipeline.Reply(h, rejected.request, 0, rejected.rejection))), true)
                                throw DiscardedOldData()
                            }
                            if (target.isOld(h)) {
                                pipe.release(h)
                                replay.take()
                                B2Frames.drain(tracked, h.length, inspected.second)
                                throw DiscardedOldData()
                            }
                            binding = inspected
                            pipe.buffer(h.stream).also { buffer = it }
                        }
                    } catch (_: DiscardedOldData) { readingSince = 0; continue }
                    catch (e: Throwable) { buffer?.let { pipeline?.recycle(it) }; throw e }
                    readingSince = 0
                    checkOpen()
                    when (frame) {
                        is B2Frame.Ack -> synchronized(lifecycle) {
                            acks.take()
                            val settled = sendFlows.getValue(frame.stream).settle(frame)
                            if (settled.isEmpty()) staleAcks.take()
                            else for ((entry, row) in frame.entries.zip(settled)) {
                                dataWait.remove(row.header.requestId)?.answer?.complete(entry.disposition) ?: fail()
                            }
                        }
                        is B2Frame.Data -> {
                            val (request, hash) = binding ?: fail()
                            (pipeline ?: fail("AUTH_REVOKED")).submit(frame, request, hash)
                        }
                        is B2Frame.Control -> {
                            controls.take()
                            val r = frame.value
                            if (r.s("type") == "response") acceptResponse(r)
                            else if (r.s("phase") == "missing" && r.o("payload").n("limit") == 0L &&
                                receiver?.canFastHeartbeat(r.s("jobId")) != false) {
                                if (receiveEpochs[r.s("jobId")] != r.n("epoch")) fail("STALE_EPOCH")
                                if (!inboundHeartbeats.add(r.s("jobId"))) fail("INVALID_FRAME")
                                // Separate lightweight IO work; neither DB work nor its mutex
                                // can hold this job's reserved heartbeat response slot.
                                scope.launch {
                                    val key = "heartbeat:" + r.s("jobId"); val watch = B2LocalWatchdog(clock)
                                    localWork[key] = watch
                                    try {
                                        val answer = runInterruptible(Dispatchers.IO) { (receiver ?: fail("AUTH_REVOKED")).heartbeat(r) }
                                        enqueue(Outgoing.Frame(B2Frame.Control(answer), r.s("jobId")), true)
                                    } catch (e: B2Exception) {
                                        val answer = B2Results.answer(r, null, r.n("epoch"), e.code)
                                        if (e.code == "LEASE_EXPIRED") {
                                            try { enqueue(Outgoing.Frame(B2Frame.Control(answer), r.s("jobId")), true) }
                                            catch (failure: Throwable) { abort(failure) }
                                        } else abort(e, listOf(answer))
                                    }
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
                                    if (receiveEpochs.put(r.s("jobId"), epoch) != epoch) {
                                        replay.reset()
                                        pipeline?.awaitIdle()
                                    }
                                }
                                synchronized(lifecycle) {
                                    enqueue(B2Frame.Control(answer), false)
                                    release()
                                }
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
                    authorize()
                    val now = clock()
                    if (readingSince != 0L && (now - readingSince >= 60000 || now - lastRead >= 30000)) throw B2NetworkTimeout("B2 partial frame deadline")
                    if (writingSince != 0L && (now - writingSince >= 60000 || now - lastWrite >= 30000)) throw B2NetworkTimeout("B2 write deadline")
                    pending.values.forEach { w ->
                        val r = w.request; val phase = r.s("phase")
                        val heartbeat = phase == "missing" && r.o("payload").n("limit") == 0L
                        if ((heartbeat || phase in listOf("prepare", "begin", "missing", "complete", "cancel")) && w.start?.let { now - it >= 30000 } == true) throw B2NetworkTimeout("B2 response deadline")
                    }
                    localWork.values.forEach { it.check() }
                    pipeline?.checkWatchdog()
                    for ((id, g) in grants) {
                        g.heartbeat.checkDeadline()
                        if (!g.heartbeat.due() || pending.values.any { it.request.optString("jobId") == id && it.request.optString("phase") == "missing" && it.request.o("payload").n("limit") == 0L }) continue
                        g.heartbeat.queue()
                        scope.launch {
                            try { exchange(B2Wire.request("missing", id, g.plan, g.epoch, obj("afterObjectId" to null, "afterChunkIndex" to null, "limit" to 0))); g.heartbeat.reply() }
                            catch (e: B2Exception) {
                                if (e.code == "LEASE_EXPIRED") g.heartbeat.reply()
                                else if (grants[id] === g) abort(e)
                            }
                            catch (e: Throwable) { if (grants[id] === g) abort(e) }
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
    private fun writeDurable(replies: List<B2ReceivePipeline.Reply>) {
        val h = replies.first().header
        val target = receiver ?: fail("AUTH_REVOKED")
        if (!target.current(h.stream, h.epoch)) return
        val entries = replies.filter { it.error == null }.map { B2AckEntry(it.header.seq, it.disposition) }
        if (entries.isNotEmpty()) {
            val ack = target.ack(h.stream, h.epoch, entries) ?: return
            writeFrame(ack) { replies.filter { it.error == null }.forEach { pipeline?.release(it.header) } }
        }
        for (reply in replies) if (reply.error != null) {
            writeFrame(B2Frame.Control(B2Results.answer(reply.request, null, h.epoch, reply.error))) {
                pipeline?.release(reply.header)
            }
        }
    }

    private fun writeFrame(frame: B2Frame, heartbeatJob: String? = null, starting: () -> Unit = {}) {
        synchronized(lifecycle) {
            checkOpen()
            authorize()
            if (frame is B2Frame.Ack && receiver?.current(frame.stream, frame.epoch) != true) return
            if (frame is B2Frame.Control) {
                val r = frame.value
                if (heartbeatJob != null) {
                    inboundHeartbeats.remove(heartbeatJob)
                    if (receiveEpochs[heartbeatJob] != r.n("epoch")) return
                }
                if (r.s("type") == "response" && r.s("phase") == "putChunk" &&
                    receiver?.let { it.current(it.streamFor(r.s("jobId")), r.n("epoch")) } != true) return
                if (r.s("type") == "request") {
                    val waiter = pending[r.s("requestId")]
                    if (r.s("phase") == "missing" && r.o("payload").n("limit") == 0L) {
                        val grant = sourceBindings[r.s("jobId")]
                        if (grant != null && grant.epoch != r.n("epoch")) {
                            waiter?.answer?.completeExceptionally(B2Exception("STALE_EPOCH"))
                            return
                        }
                        grant?.heartbeat?.beginWrite()
                    }
                    waiter?.start = clock()
                }
            }
            val admit = {
                starting()
                writingSince = clock()
                lastWrite = writingSince
            }
            if (isReceiverReply(frame)) receiver?.admitResponse(admit) ?: admit()
            else admit()
        }
        B2Frames.write(object : OutputStream() {
            override fun write(b: Int) { checkOpen(); output.write(b); lastWrite = clock() }
            override fun write(b: ByteArray, off: Int, len: Int) { checkOpen(); output.write(b, off, len); lastWrite = clock() }
            override fun flush() { checkOpen(); output.flush() }
        }, frame)
        writingSince = 0
    }

    internal fun admitSourceCommit(authorize: () -> Unit, account: B2CommitFence?) = synchronized(lifecycle) {
        checkOpen()
        if (account == null) authorize() else account.admitCommit(authorize)
    }

    private fun isReceiverReply(frame: B2Frame): Boolean =
        frame is B2Frame.Ack || frame is B2Frame.Control && frame.value.s("type") == "response" &&
            frame.value.s("phase") !in setOf("prepare", "cancel") && frame.value.getBoolean("success")

    private fun enqueue(frame: B2Frame, priority: Boolean) = enqueue(Outgoing.Frame(frame), priority)
    private fun enqueue(frame: Outgoing, priority: Boolean) = synchronized(lifecycle) {
        checkOpen()
        val submit = {
            outstandingWrites.incrementAndGet()
            val queue = when {
                frame is Outgoing.Durable -> durable
                priority -> high
                else -> normal
            }
            if (!queue.trySend(frame).isSuccess) {
                outstandingWrites.decrementAndGet()
                fail("LIMIT_EXCEEDED")
            }
        }
        if (frame is Outgoing.Durable || frame is Outgoing.Frame && isReceiverReply(frame.frame)) {
            receiver?.admitResponse(submit) ?: submit()
        } else submit()
    }
    private fun acceptResponse(r: JSONObject) {
        val id = r.s("requestId")
        if (r.s("phase") == "putChunk") {
            synchronized(lifecycle) {
                val grant = sourceBindings[r.s("jobId")] ?: fail("REQUEST_CONFLICT")
                val flow = sendFlows.getValue(grant.stream)
                if (r.s("planDigest") != grant.plan || r.getBoolean("success")) fail()
                if (flow.isOld(r.n("epoch"))) { staleAcks.take(); return }
                val data = dataWait[id] ?: fail("REQUEST_CONFLICT")
                validateCorrelation(data.request, r)
                flow.negative(r.s("jobId"), r.s("planDigest"), r.n("epoch"), id, r.s("requestDigest"))
                dataWait.remove(id)
                val error = B2Exception(r.o("error").s("code"), r.o("error").n("retryAfterMillis"))
                if (r.o("error").s("recovery") in listOf("STOP", "REPLAN")) abort(error)
                data.answer.completeExceptionally(error)
            }
            return
        }
        val w = pending[id] ?: fail("REQUEST_CONFLICT")
        validateCorrelation(w.request, r)
        if (!r.getBoolean("success")) {
            val error = r.o("error")
            val recovery = error.s("recovery")
            if (recovery in listOf("STOP", "REPLAN")) {
                val code = error.s("code")
                val failure = if (r.s("phase") == "apply" && recovery == "STOP" && error.s("class") == "BUSINESS")
                    B2ApplyStop(code, w.request.s("jobId"), w.request.o("payload").s("objectId"))
                else B2Exception(code)
                abort(failure)
                return
            }
        }
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
            val answer = w.answer.await()
            // A peer may close immediately after acknowledging terminal cancellation.
            if (r.s("phase") != "cancel" || !answer.getBoolean("success") || cause !is EOFException) checkOpen()
            if (!answer.getBoolean("success")) throw B2Exception(answer.o("error").s("code"), answer.o("error").n("retryAfterMillis"))
            return answer
        } finally { pending.remove(r.s("requestId")); busy.remove(key) }
    }
    /** The file/hash lane produces one bounded block ahead of the socket writer. */
    internal suspend fun queueFile(request: JSONObject, slot: Int, ref: B2FileRef, hash: String): Deferred<Int> {
        val id = request.s("jobId")
        val bytes = localWork(id) { ref.readBlock(ByteArray(ref.size.toInt()), hash) { workProgress(id) } }
        return queueData(request, slot, bytes)
    }

    internal suspend fun queueData(request: JSONObject, slot: Int, bytes: ByteArray): Deferred<Int> {
        checkOpen()
        B2Wire.envelope(request)
        val answer = CompletableDeferred<Int>()
        outstandingWrites.incrementAndGet()
        try { dataQueue.send(Outgoing.Data(request, slot, bytes, answer)) }
        catch (e: Throwable) { outstandingWrites.decrementAndGet(); checkOpen(); throw e }
        return answer
    }
    suspend fun sendData(request: JSONObject, slot: Int, bytes: ByteArray): Int =
        queueData(request, slot, bytes).await().also { checkOpen() }

    suspend fun flush() {
        while (outstandingWrites.get() > 0 || inbound.isNotEmpty()) { checkOpen(); delay(1) }
    }
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
            cause = error
            receiver?.fenceNow()
            pipeline?.close()
            sendFlows.values.forEach { it.clear() }
            while (normal.tryReceive().isSuccess) { }
            while (high.tryReceive().isSuccess) { }
            while (durable.tryReceive().isSuccess) { }
            dataQueue.cancel()
            pending.values.forEach { it.answer.completeExceptionally(error) }
            dataWait.values.forEach { it.answer.completeExceptionally(error) }
            dataWait.clear()
            ended.complete(error); cancellationHandle?.dispose(); job.cancel()
        }
        // A user cancellation never waits for output or reconnects. If no frame is in
        // progress, the stopped writer hands its stream to one bounded best-effort cancel.
        if (idle && cancelRequests.isNotEmpty()) {
            cleanup.launch { delay(250); runCatching(closeTransport) }
            cleanup.launch { try { cancelRequests.forEach { B2Frames.write(output, B2Frame.Control(it)) } } catch (_: Exception) { }
                finally { runCatching(closeTransport) } }
        } else cleanup.launch { runCatching(closeTransport) }
        cleanup.launch { receiver?.closeIndex() }
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
