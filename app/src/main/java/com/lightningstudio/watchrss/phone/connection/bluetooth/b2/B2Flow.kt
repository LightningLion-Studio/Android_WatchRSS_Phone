package com.lightningstudio.watchrss.phone.connection.bluetooth.b2

/** Temporary connection credit only. Persistent receipts belong to B2Persistence. */
class B2Flow(val stream: Int) {
    enum class State { IN_FLIGHT, DURABLE, SETTLED_OK, SETTLED_ERROR, ABORTED }
    data class Pending(val header: B2DataHeader, val objectId: String, var state: State = State.IN_FLIGHT)
    private var binding: Triple<String, String, String>? = null
    @Volatile var epoch = 0L; private set
    private var firstEpoch = 0L
    private var window = 1
    private var byteWindow = B2Wire.CHUNK
    private var nextTx = 1L
    private var nextRx = 1L
    private var lastAck = 0L
    private var nextAck = 1L
    private val pending = linkedMapOf<Long, Pending>()
    val pendingCount: Int @Synchronized get() = pending.size
    val pendingBytes: Int @Synchronized get() = pending.values.sumOf { it.header.length }
    @Synchronized fun bind(job: String, plan: String, selection: String, epoch: Long, chunks: Int = 1, bytes: Int = B2Wire.CHUNK) {
        val b = Triple(job, plan, selection)
        if (binding != null && (binding != b || window != chunks || byteWindow != bytes)) fail("PLAN_CONFLICT")
        if (chunks !in listOf(1, 8) || bytes != chunks * B2Wire.CHUNK) fail("CAPABILITY_MISMATCH")
        if (epoch !in 1..B2Json.MAX_SAFE || this.epoch != 0L && epoch !in this.epoch..(this.epoch + 1))
            fail("STALE_EPOCH")
        binding = b
        window = chunks
        byteWindow = bytes
        if (firstEpoch == 0L) firstEpoch = epoch
        if (epoch != this.epoch) { clear(); this.epoch = epoch }
    }
    @Synchronized fun reserve(slot: Int, index: Int, bytes: Int, id: String, digest: String, oid: String): B2DataHeader {
        if (epoch == 0L || pending.size >= window || pendingBytes + bytes > byteWindow) fail("WINDOW_FULL")
        if (pending.values.any { it.header.requestId == id || it.header.slot == slot && it.header.index == index }) fail("REQUEST_CONFLICT")
        if (nextTx > B2Json.MAX_SAFE) fail("SEQUENCE_EXHAUSTED")
        val h = B2DataHeader(stream, slot, epoch, nextTx, index, bytes, id, digest); B2Frames.validate(h)
        pending[nextTx++] = Pending(h, oid); return h
    }
    @Synchronized fun receive(h: B2DataHeader) {
        if (h.stream != stream || h.epoch != epoch || h.seq != nextRx) fail("STALE_EPOCH")
        if (nextRx > B2Json.MAX_SAFE) fail()
        nextRx++ // consumed before admission; a WAIT retry uses a new txSeq
    }
    @Synchronized fun durable(h: B2DataHeader, applied: Boolean): B2Frame.Ack {
        if (h.stream != stream) fail("STALE_EPOCH")
        return durable(h.epoch, listOf(B2AckEntry(h.seq, if (applied) 2 else 1)))
    }
    @Synchronized fun durable(epoch: Long, entries: List<B2AckEntry>): B2Frame.Ack {
        if (epoch != this.epoch || nextAck > B2Json.MAX_SAFE) fail("STALE_EPOCH")
        if (entries.size !in 1..window) fail()
        return B2Frame.Ack(stream, epoch, nextAck++, entries)
    }
    @Synchronized fun isOld(epoch: Long): Boolean {
        if (epoch < firstEpoch || epoch > this.epoch) fail("STALE_EPOCH")
        return epoch < this.epoch
    }
    /** Validate every entry before releasing any credit. Old syntactically valid ACKs
     * are discarded by the caller's independent cheap rate bucket. */
    @Synchronized fun settle(a: B2Frame.Ack): List<Pending> {
        if (a.stream != stream) fail("STALE_EPOCH")
        if (isOld(a.epoch) || a.seq <= lastAck) return emptyList()
        if (a.seq != lastAck + 1 || a.entries.size !in 1..window ||
            a.entries.zipWithNext().any { (a, b) -> a.txSeq >= b.txSeq } ||
            a.entries.any { it.disposition !in 1..2 }) fail()
        val rows = a.entries.map { pending[it.txSeq] ?: fail() }
        if (rows.any { it.header.epoch != epoch }) fail()
        rows.forEach { it.state = State.DURABLE }
        a.entries.forEach { pending.remove(it.txSeq)?.state = State.SETTLED_OK }; lastAck = a.seq
        return rows
    }
    @Synchronized fun negative(job: String, plan: String, epoch: Long, id: String, digest: String): Pending {
        if (binding?.first != job || binding?.second != plan || epoch != this.epoch) fail("STALE_EPOCH")
        val row = pending.values.singleOrNull { it.header.requestId == id && it.header.digest == digest } ?: fail("REQUEST_CONFLICT")
        row.state = State.SETTLED_ERROR; pending.remove(row.header.seq); return row
    }
    @Synchronized fun clear() { pending.values.forEach { it.state = State.ABORTED }; pending.clear(); nextTx = 1; nextRx = 1; lastAck = 0; nextAck = 1 }
}

class B2Rate(private val burst: Double, private val perSecond: Double, private val clock: () -> Long) {
    private var tokens = burst; private var last = clock()
    @Synchronized fun reset() { tokens = burst; last = clock() }
    @Synchronized fun take() {
        val now = clock(); tokens = minOf(burst, tokens + maxOf(0, now - last) * perSecond / 1000); last = now
        if (tokens < 1) fail("RATE_LIMITED"); tokens--
    }
}
class B2Backoff {
    private var count = 0
    fun next(retryAfter: Long = 0): Long = maxOf(retryAfter, minOf(1000L, 100L shl minOf(count++, 4)))
    fun reset() { count = 0 }
}
