package com.lightningstudio.watchrss.phone.connection.bluetooth.b2

import com.lightningstudio.watchrss.phone.data.book.BookPackageFiles
import com.lightningstudio.watchrss.phone.data.book.ImportedBookStore
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Adapter methods MUST use only the supplied business DB transaction. No detached work,
 * nested database, KV writes, or network calls. Files are published immutably before apply.
 * P6 protected inputs must reject here until their real business transaction can commit. */
class B2RejectedData(val request: JSONObject, val hash: String, val rejection: String) : Exception(rejection)

interface B2BusinessPort {
    val kinds: Set<String>
    suspend fun apply(descriptor: JSONObject, metadata: JSONObject, staged: File): String
    suspend fun prepareFile(context: B2ApplyContext, descriptor: JSONObject, metadata: JSONObject) {}
    suspend fun applyFile(context: B2ApplyContext, descriptor: JSONObject, metadata: JSONObject, staged: B2FileRef): String =
        apply(descriptor, metadata, staged.file)
    suspend fun receiptWitness(
        context: B2ApplyContext?, descriptor: JSONObject, metadata: JSONObject, prior: JSONObject?
    ): JSONObject? = null
    fun validatePlan(objects: List<JSONObject>) {}
    fun applicationScratchBytes(objects: List<JSONObject>): Long = 0L
    fun canCommitRange(description: JSONObject): Boolean = false
    suspend fun complete(description: JSONObject, completion: JSONObject) {}
}

class B2ApplyContext internal constructor(
    val jobId: String,
    internal val segment: suspend (String) -> B2FileRef,
    internal val check: () -> Unit,
    internal val assembly: File? = null,
    internal val publishAssembly: suspend (File, List<String>) -> Unit = { _, _ -> },
    internal val assemblyReady: suspend () -> Boolean = { false },
    internal val guarded: Boolean = false,
    internal val stagedFile: File? = null
) {
    internal var preparedBook: ImportedBookStore.PreparedBinaryPackage? = null
    internal var finishAsset: ((committed: Boolean) -> Unit)? = null
    internal var readingIndices: List<Int> = emptyList()
    internal var preparedAsset: com.lightningstudio.watchrss.phone.connection.bluetooth.B2NoteAssetBusiness.Prepared? = null
}

/** Construct only after LOCAL consent + current account/peer verification + successful
 * prepare confirmation. The controller owns consent; begin cannot manufacture it. */
class B2Authorization(
    val accountScope: String, val phoneDeviceId: String, val watchDeviceId: String,
    phoneSnapshot: JSONObject, watchSnapshot: JSONObject, bindings: List<JSONObject>,
    private val current: () -> Boolean
) {
    internal val phoneSnapshot = copy(phoneSnapshot)
    internal val watchSnapshot = copy(watchSnapshot)
    internal val bindings = bindings.map(::copy)
    internal var accountCommitFence: B2CommitFence? = null
    internal var commitWindow: B2CommitWindow? = null
    internal fun check() { if (!current()) fail("AUTH_REVOKED") }
}

/** One connection instance, opaque LOCAL handle. New processes cannot reuse this handle.
 * Every engine sharing this Room DB uses the same commit lane. */
class B2Receiver(
    private val store: B2Persistence, private val files: B2Files,
    private val auth: B2Authorization, private val business: B2BusinessPort,
    private val monotonic: () -> Long = { System.nanoTime() / 1_000_000 },
    private val fault: (String) -> Unit = {},
    private val receiveDirection: String = "watchToPhone",
    private val progress: (String) -> Unit = {},
    retentionGeneration: String? = null
) {
    private val retention = B2Retention(store, files, auth.accountScope, monotonic, retentionGeneration, ::check)
    private val connection = java.util.UUID.randomUUID().toString()
    private val commitFence = B2CommitFence(auth.accountCommitFence)
    private val light = java.util.concurrent.ConcurrentHashMap<String, JSONObject>()
    private val firstEpoch = java.util.concurrent.ConcurrentHashMap<Int, Long>()
    private val streamJobs = java.util.concurrent.ConcurrentHashMap<Int, String>()
    @Volatile private var receiveIndex: B2ReceiveIndex? = null
    private val applied = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val finishedSources = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val flows = mapOf(1 to B2Flow(1), 2 to B2Flow(2))
    private val epochs = Any()
    private val active = mutableSetOf<String>()
    fun fenceNow() = commitFence.revoke()
    internal fun closeIndex() = synchronized(epochs) {
        receiveIndex?.close()
        receiveIndex = null
    }
    internal fun sourceCompleted(job: String) { finishedSources.add(job) }
    private val retentionDeadlines = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val leases = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val begun = mutableSetOf<String>()
    private val scope get() = auth.accountScope
    private fun check() { commitFence.check(); auth.check() }

    private suspend fun <T> receiveTransaction(
        businessWrite: Boolean = false, checkWindow: Boolean = businessWrite, block: suspend () -> T
    ): T {
        val window = auth.commitWindow
        var committedVersion: B2CommitWindow.Version? = null
        val result = store.transaction {
            check()
            if (checkWindow) window?.check()
            val value = block()
            if (businessWrite) committedVersion = window?.afterBusinessWrite()
            commitFence.admitCommit {
                auth.check()
                if (checkWindow) window?.checkHead()
            }
            value
        }
        committedVersion?.let { window?.committed(it) }
        return result
    }

    internal fun admitResponse(send: () -> Unit) = commitFence.admitCommit {
        auth.check()
        auth.commitWindow?.checkHead()
        send()
    }

    private suspend fun applyTransaction(kind: String, context: B2ApplyContext, block: suspend () -> JSONObject): JSONObject {
        if (kind != "noteAsset") return receiveTransaction(businessWrite = true, block = block)
        val caller = currentCoroutineContext()
        // Preserve the transaction outcome across cancellation so rollback never unlinks an
        // admitted commit. The caller and account are still checked before commit admission.
        return withContext(NonCancellable) {
            val result = try {
                receiveTransaction(businessWrite = true) {
                    caller.ensureActive()
                    block().also { caller.ensureActive() }
                }
            } catch (failure: Throwable) {
                try {
                    context.finishAsset?.invoke(false)
                } catch (cleanup: Exception) {
                    failure.addSuppressed(cleanup)
                }
                throw failure
            }
            context.finishAsset?.invoke(true)
            result
        }
    }

    suspend fun revoke(cancelJobs: Boolean = false) {
        fenceNow()
        store.exclusive {
            leases.clear()
            if (cancelJobs) for (b in auth.bindings) {
                val j = store.get(scope, "job", b.s("jobId")) ?: continue
                // A late old-controller cancellation cannot cancel a newer connection.
                if (j.s("connection") != connection || terminal(j)) continue
                store.transaction { j.put("state", "CANCELLED"); store.put(scope, "job", j.s("jobId"), j) }
                objects(j.s("jobId")).forEach { files.release(scope, j.s("jobId"), it.o("descriptor"), keepObject = it.o("descriptor").s("kind") == B2Wire.BOOK_SEGMENT) }
            }
        }
    }
    private val sourcePeer get() = if (receiveDirection == "watchToPhone") auth.watchDeviceId else auth.phoneDeviceId
    private fun key(vararg p: String) = B2Digests.d("wrss.library-binary.v2/record-key", p.toList())
    private fun objectKey(job: String, oid: String) = key(job, oid)
    private suspend fun objects(job: String) = store.listForJob(scope, "object", job).sortedBy { it.o("descriptor").s("objectId") }
    private suspend fun objectRow(job: String, oid: String) = store.get(scope, "object", objectKey(job, oid)) ?: fail()
    private suspend fun job(id: String) = store.get(scope, "job", id) ?: fail("NOT_FOUND")
    private fun terminal(j: JSONObject) = j.s("state") in listOf("COMPLETE", "CANCELLED")
    private fun description(j: JSONObject) = j.o("description")
    private fun lim(j: JSONObject) = description(j).o("selection").o("limits")
    private suspend fun touch(j: JSONObject, now: Long) {
        j.put("expiresAtMillis", maxOf(j.n("expiresAtMillis"), now + B2Wire.RETENTION))
        store.put(scope, "job", j.s("jobId"), j)
        val elapsed = monotonic()
        leases[j.s("jobId")] = elapsed + B2Wire.LEASE
        retentionDeadlines[j.s("jobId")] = elapsed + j.n("expiresAtMillis") - now
    }
    private fun fence(j: JSONObject, r: JSONObject) {
        check()
        if (j.s("connection") != connection || !leases.containsKey(j.s("jobId")) || j.n("epoch") != r.n("epoch")) fail("STALE_EPOCH")
        val elapsed = monotonic()
        if (elapsed > (retentionDeadlines[j.s("jobId")] ?: fail("STALE_EPOCH"))) fail("EXPIRED")
        if (elapsed >= leases.getValue(j.s("jobId"))) fail("LEASE_EXPIRED")
        if (j.s("planDigest") != r.s("planDigest")) fail("PLAN_CONFLICT")
    }
    private fun bindNative(j: JSONObject, r: JSONObject) =
        files.bindRequest(scope, j.s("jobId"), sourcePeer, r.s("requestId"), r.s("requestDigest"), lim(j).n("maxJournalBytes"))

    private suspend fun bind(j: JSONObject, r: JSONObject, result: JSONObject? = null,
        nativeAlreadyBound: Boolean = false): JSONObject? {
        val id = key(j.s("jobId"), sourcePeer, r.s("requestId"))
        if (!nativeAlreadyBound) bindNative(j, r)
        val old = store.get(scope, "request", id)
        if (old != null && old.s("digest") != r.s("requestDigest")) fail("REQUEST_CONFLICT")
        if (old == null) { store.budget(1024, lim(j).n("maxJournalBytes")); store.put(scope, "request", id, obj("jobId" to j.s("jobId"), "digest" to r.s("requestDigest"), "result" to result)) }
        else if (result != null) store.put(scope, "request", id, obj("jobId" to j.s("jobId"), "digest" to r.s("requestDigest"), "result" to result))
        return old?.takeUnless { it.nil("result") }?.o("result")
    }
    /** Throws typed errors for the dispatcher to encode. Transport I/O remains distinct. */
    suspend fun handle(frame: JSONObject, data: ByteArray? = null): JSONObject = try { store.exclusive {
        val r = B2Wire.envelope(B2Json.parse(B2Json.utf8(frame.toString()))).also { B2Wire.retainSize(it, B2Wire.frameBytes(frame)) }
        if (r.s("type") != "request" || r.s("phase") == "prepare") fail()
        val replyIncludesBusiness = r.s("phase") in setOf("begin", "apply", "missing", "complete", "putChunk")
        val now = receiveTransaction(checkWindow = replyIncludesBusiness) { retention.clock() }
        if (r.s("phase") == "begin") return@exclusive begin(r, now)
        val j = job(r.s("jobId")); fence(j, r)
        if (B2Wire.frameBytes(r) > lim(j).n("maxFrameBytes") || B2Wire.frameBytes(r) - 12 > lim(j).n("maxControlHeaderBytes")) fail("LIMIT_EXCEEDED")
        // Phase-specific limits apply before request/page receipt adoption too.
        if (r.s("phase") == "manifestPage" && B2Wire.frameBytes(r) > lim(j).n("maxManifestPageBytes")) fail("LIMIT_EXCEEDED")
        if (j.s("state") == "CANCELLED" && r.s("phase") !in listOf("missing", "cancel")) fail("JOB_CANCELLED")
        if (j.s("state") == "PLANNING" && r.s("phase") in listOf("putChunk", "apply")) fail("MANIFEST_INCOMPLETE")
        // Wire payload validation always precedes deduplication. put additionally checks bytes.
        if (r.s("phase") == "putChunk") validateChunk(j, r.o("payload"), data ?: fail())
        val previous = receiveTransaction {
            val bound = bind(j, r)
            touch(j, now)
            bound
        }
        val result = when (r.s("phase")) {
            "missing" -> missing(j, r.o("payload")) // Queries return current verified observations.
            "putChunk" -> put(j, r, data ?: fail())
            "apply" -> apply(j, r)
            "manifestPage" -> previous ?: manifest(j, r)
            "complete" -> complete(j, r)
            "cancel" -> cancel(j, r)
            else -> fail()
        }
        if (replyIncludesBusiness && auth.commitWindow != null) {
            receiveTransaction(checkWindow = true) { fence(j, r) }
        }
        check()
        light[j.s("jobId")] = copy(j)
        B2Results.answer(r, result, j.n("epoch"))
    } } catch (_: java.io.IOException) { fail("LOCAL_IO") }

    /** Validate on the reader before fencing, then serialize the cancellation tombstone
     * with the same business lane as apply/complete. No new connection is opened. */
    fun validateCancel(r: JSONObject) {
        B2Wire.envelope(r)
        if (r.s("phase") != "cancel") fail()
        fence(light[r.s("jobId")] ?: fail("AUTH_REVOKED"), r)
    }
    suspend fun persistCancel(r: JSONObject): JSONObject = store.exclusive {
        val j = job(r.s("jobId"))
        if (j.s("planDigest") != r.s("planDigest")) fail("PLAN_CONFLICT")
        val result = obj("state" to if (j.s("state") == "COMPLETE") "COMPLETE" else "CANCELLED", "alreadyComplete" to (j.s("state") == "COMPLETE"))
        store.transaction { j.put("state", result.s("state")); store.put(scope, "job", j.s("jobId"), j); bind(j, r, result) }
        B2Results.answer(r, result, r.n("epoch"))
    }
    internal fun canFastHeartbeat(job: String): Boolean =
        auth.commitWindow == null || light[job]?.optString("state") != "COMPLETE"

    /** PLANNING exception: committed snapshot only; no DB lane, data scan or admission. */
    fun heartbeat(r: JSONObject): JSONObject {
        B2Wire.envelope(r)
        if (r.s("phase") != "missing" || r.o("payload").n("limit") != 0L) fail()
        val j = light[r.s("jobId")] ?: fail("AUTH_REVOKED")
        fence(j, r)
        files.bindRequest(scope, j.s("jobId"), sourcePeer, r.s("requestId"), r.s("requestDigest"), lim(j).n("maxJournalBytes")) {
            fence(j, r)
        }
        leases[j.s("jobId")] = monotonic() + B2Wire.LEASE
        return B2Results.answer(r, obj("state" to j.s("state"), "nextManifestPage" to j.n("nextManifestPage"),
            "expiresAtMillis" to j.n("expiresAtMillis"), "items" to JSONArray(), "next" to null,
            "done" to true, "completion" to j.get("completion")), j.n("epoch"))
    }
    /** Read only committed registration here. The socket reader never acquires
     * the writer lane or waits for a chunk to be written or synced. */
    suspend fun inspect(h: B2DataHeader, busy: Boolean = false): Pair<JSONObject, String> =
        inspect(h) { if (busy) fail("BUSY") }

    internal suspend fun inspect(h: B2DataHeader, credit: () -> Unit): Pair<JSONObject, String> {
        check()
        val job = jobFor(h.stream)
        val j = light[job] ?: fail("AUTH_REVOKED")
        if (j.s("state") == "PLANNING") fail("MANIFEST_INCOMPLETE")
        val registration = (receiveIndex ?: fail()).chunk(h.slot, h.index, h.length)
        val oid = registration.objectId
        val r = B2Wire.request("putChunk", job, j.s("planDigest"), h.epoch,
            obj("objectId" to oid, "chunkIndex" to h.index), h.requestId)
        if (h.digest != r.s("requestDigest")) fail("REQUEST_CONFLICT")
        files.checkRequest(scope, job, sourcePeer, h.requestId, h.digest)
        val hash = registration.hash
        val old = synchronized(epochs) {
            isOld(h).also { if (!it) flows.getValue(h.stream).receive(h) }
        }
        if (!old) {
            try {
                credit()
                fence(j, r)
                if (oid !in applied) {
                    if (terminal(j)) fail("JOB_CANCELLED")
                    if (!applied.containsAll(registration.dependencies)) fail("DEPENDENCIES_PENDING")
                    val cap = objectLimit(j)
                    synchronized(epochs) {
                        if (isOld(h)) return r to hash
                        synchronized(active) {
                            if (cap == 0L || oid !in active && active.size >= cap) fail("WINDOW_FULL")
                            active.add(oid)
                        }
                    }
                }
                // Hold the in-use lease through this bounded DATA frame.
                leases[job] = monotonic() + 60000
            } catch (e: B2Exception) {
                if (e.code == "FLOW_CONTROL_VIOLATION" || B2Results.errorValue(e.code).s("recovery") in listOf("WAIT", "QUERY", "BEGIN"))
                    throw B2RejectedData(r, hash, e.code)
                throw e
            }
        }
        return r to hash
    }

    internal fun current(stream: Int, epoch: Long): Boolean =
        !commitFence.isRevoked && streamJobs[stream]?.let { light[it]?.n("epoch") } == epoch

    internal fun streamFor(job: String): Int = streamJobs.entries.firstOrNull { it.value == job }?.key ?: fail("AUTH_REVOKED")

    private fun receiveLimits(stream: Int) = lim(light[jobFor(stream)] ?: fail("AUTH_REVOKED"))

    internal fun window(stream: Int): Int = receiveLimits(stream).n("maxInFlightChunks").toInt()
    internal fun byteWindow(stream: Int): Int = receiveLimits(stream).n("maxInFlightBytes").toInt()

    internal data class WrittenChunk(val header: B2DataHeader, val request: JSONObject, val temp: B2FileRef)

    internal fun writeChunk(frame: B2Frame.Data, request: JSONObject): WrittenChunk? {
        if (!current(frame.header.stream, frame.header.epoch)) return null
        val temp = files.writeChunk(frame.bytes, frame.header.length) {
            check()
            if (!current(frame.header.stream, frame.header.epoch)) fail("STALE_EPOCH")
        }
        return WrittenChunk(frame.header, request, B2FileRef(temp, 0, frame.header.length.toLong()))
    }

    internal fun ack(stream: Int, epoch: Long, entries: List<B2AckEntry>): B2Frame.Ack? = synchronized(epochs) {
        if (!current(stream, epoch)) null else flows.getValue(stream).durable(epoch, entries)
    }

    /** The staged temp files can be written ahead while this lane syncs a group.
     * Canonical files and the single FULL journal transaction belong to this grant. */
    internal data class CommittedChunk(val chunk: WrittenChunk, val disposition: Int, val progressKey: String? = null)

    internal fun reportChunkProgress(key: String) { progress(key) }

    internal suspend fun commitChunks(group: List<WrittenChunk>): List<CommittedChunk> = store.exclusive {
        val first = group.first().header
        if (!current(first.stream, first.epoch)) return@exclusive emptyList()
        val j = job(group.first().request.s("jobId"))
        group.forEach { fence(j, it.request) }
        val staged = mutableListOf<Pair<File, File>>()
        val rows = mutableMapOf<String, JSONObject>()
        val outcomes = group.map { chunk ->
            val oid = chunk.request.o("payload").s("objectId")
            val row = rows.getOrPut(oid) { objectRow(j.s("jobId"), oid) }
            if (verifiedReceipt(j, row) != null) return@map CommittedChunk(chunk, 2)
            if (terminal(j)) fail("JOB_CANCELLED")
            val descriptor = row.o("descriptor")
            runnable(j, descriptor)
            staged.add(chunk.temp.file to files.chunk(scope, j.s("jobId"), oid, chunk.header.index))
            CommittedChunk(chunk, 1, receiveDirection + ":" + B2Progress.chunk(descriptor, chunk.header.index))
        }
        val budget = stagingLimit(j)
        if (files.usedBytes() > budget) fail("STORAGE_PRESSURE")
        // A verified block can be adopted after a crash as soon as its canonical
        // path is durable. Preserve the request identity before that point.
        for ((chunk, _) in outcomes) {
            fence(j, chunk.request)
            bindNative(j, chunk.request)
        }
        files.publishChunks(staged) { fence(j, group.first().request) }
        if (staged.isNotEmpty()) fault("afterChunkDurable")
        receiveTransaction(checkWindow = outcomes.any { it.disposition == 2 }) {
            for ((chunk, disposition) in outcomes) {
                fence(j, chunk.request)
                val p = chunk.request.o("payload")
                val oid = p.s("objectId")
                val row = rows.getValue(oid)
                val receipt = if (disposition == 2) receipt(j, row) else null
                bind(j, chunk.request, obj("objectId" to oid, "chunkIndex" to p.n("chunkIndex"),
                    "chunkState" to if (disposition == 1) "STAGED" else null,
                    "objectState" to if (disposition == 1) "PARTIAL" else "APPLIED", "receipt" to receipt),
                    nativeAlreadyBound = true)
                if (disposition == 1) {
                    val hash = row.o("descriptor").a("chunkHashes").getString(chunk.header.index)
                    store.put(scope, "chunk", key(j.s("jobId"), oid, chunk.header.index.toString()), obj("jobId" to j.s("jobId"), "sha256" to hash))
                    if (!row.optBoolean("active", false)) {
                        row.put("active", true)
                        store.put(scope, "object", objectKey(j.s("jobId"), oid), row)
                    }
                }
            }
            if (staged.isNotEmpty()) j.put("state", "TRANSFERRING")
            touch(j, retention.clock())
            fence(j, group.first().request)
        }
        light[j.s("jobId")] = copy(j)
        outcomes
    }
    fun cancellationRequests(reason: String): List<JSONObject> {
        check()
        return light.values.filter { it.s("state") !in listOf("COMPLETE", "CANCELLED") }.map {
            B2Wire.request("cancel", it.s("jobId"), it.s("planDigest"), it.n("epoch"), obj("reason" to reason))
        }
    }
    fun jobFor(stream: Int): String = streamJobs[stream] ?: fail("AUTH_REVOKED")
    fun isOld(h: B2DataHeader): Boolean {
        val job = streamJobs[h.stream] ?: fail("AUTH_REVOKED")
        val current = light[job]?.n("epoch") ?: fail("AUTH_REVOKED")
        if (h.epoch < (firstEpoch[h.stream] ?: current) || h.epoch > current) fail("STALE_EPOCH")
        return h.epoch < current
    }
    suspend fun data(frame: B2Frame.Data, request: JSONObject): B2Frame.Ack {
        val result = handle(request, frame.bytes).o("result")
        return flows.getValue(frame.header.stream).durable(frame.header, result.s("objectState") == "APPLIED")
    }
    private suspend fun begin(r: JSONObject, now: Long): JSONObject {
        val p = r.o("payload"); val id = r.s("jobId")
        val b = auth.bindings.find { it.s("jobId") == id } ?: fail("AUTH_REVOKED")
        if (p.s("direction") != receiveDirection || b.s("direction") != p.s("direction") || p.s("accountScope") != scope || p.s("phoneDeviceId") != auth.phoneDeviceId || p.s("watchDeviceId") != auth.watchDeviceId) fail("PEER_MISMATCH")
        if (B2Digests.snapshot(p.o("phoneSnapshot")) != B2Digests.snapshot(auth.phoneSnapshot) || B2Digests.snapshot(p.o("watchSnapshot")) != B2Digests.snapshot(auth.watchSnapshot) || B2Digests.selection(b.o("selection")) != p.s("selectionDigest") || B2Digests.plan(p) != r.s("planDigest")) fail("PLAN_CONFLICT")
        if (p.o("selection").a("kinds").values().any { (it as JSONObject).s("kind") !in business.kinds }) fail("UNSUPPORTED_KIND")
        if (id !in begun && b.s("intent") != p.s("intent")) fail("JOB_CONFLICT")
        val old = store.get(scope, "job", id)
        val cancelBeforeBegin = old == null && p.s("intent") == "resume" &&
            store.get(scope, "cancelIntent", id)?.optBoolean("businessStop") == true
        if (old == null && p.s("intent") == "resume" && !cancelBeforeBegin) fail("NOT_FOUND")
        if (old != null) {
            if (old.n("expiresAtMillis") < now) fail("EXPIRED")
            if (old.s("planDigest") != r.s("planDigest") || description(old).a("pageObjectCounts").toString() != p.a("pageObjectCounts").toString()) fail("JOB_CONFLICT")
            auth.commitWindow?.let { if (old.optString("guardedHead") != it.bindingId) fail("BUSINESS_REJECTED") }
            if (old.s("state") == "COMPLETE") verifyCompletion(old)
            // No eager GC: retained records remain resumable beyond their minimum 24h.
            // This also preserves heartbeats/local work across conservative process clocks.
            if (p.s("intent") == "create" && (terminal(old) || old.s("connection") != connection)) fail("JOB_CONFLICT")
        }
        if (p.o(if (receiveDirection == "watchToPhone") "watchSnapshot" else "phoneSnapshot").n("toInclusive") != 0L && !business.canCommitRange(p)) fail("BUSINESS_REJECTED")
        val limits = p.o("selection").o("limits")
        if (B2Wire.frameBytes(r) > limits.n("maxFrameBytes")) fail("LIMIT_EXCEEDED")
        if (old == null && !cancelBeforeBegin) {
            retention.collect(now, id)
            val active = store.jobs().map { B2Json.parse(it.body, false) }.count { !terminal(it) }
            if (active >= limits.n("maxActiveJobs")) fail("WINDOW_FULL")
        }
        val reservation = B2Json.utf8(p.toString()).size + 4096L + if (cancelBeforeBegin) 0L else
            p.n("objectCount") * 4096L + minOf(limits.n("maxManifestBytes"), p.n("objectCount") * 48000L)
        store.budget(if (old == null) reservation else 0, limits.n("maxJournalBytes"))
        if (p.n("objectCount") == 0L && p.s("objectsRoot") != B2Digests.objects(emptyList())) fail("PLAN_CONFLICT")
        if (old != null && old.s("state") !in listOf("PLANNING", "COMPLETE", "CANCELLED")) {
            val reserve = old.optLong("stagingReservation", 0)
            if (p.n("objectCount") > 0 && reserve <= 0) fail("JOURNAL_CORRUPT")
            val materialized = files.jobDataBytes(scope, id, objects(id).map { it.o("descriptor") })
            val remaining = (reserve - materialized).coerceAtLeast(0)
            if (remaining + otherReservations(id) + limits.n("maxJournalBytes") + 1048576 > files.availableBytes() ||
                remaining + otherReservations(id) + files.dataBytes() + limits.n("maxJournalBytes") > limits.n("maxStagingBytes")) fail("STORAGE_PRESSURE")
        }
        val sameLease = old != null && old.s("connection") == connection && (leases[id] ?: -1) > monotonic()
        val epoch = if (sameLease) old!!.n("epoch") else (old?.n("epoch") ?: 0) + 1
        if (epoch > B2Json.MAX_SAFE) fail("JOURNAL_CORRUPT")
        val j = old ?: obj("jobId" to id, "bindingVersion" to 2, "planDigest" to r.s("planDigest"), "description" to copy(p), "state" to if (p.n("objectCount") == 0L) "READY" else "PLANNING", "nextManifestPage" to 0, "completion" to null, "reservation" to reservation, "expiresAtMillis" to now + B2Wire.RETENTION)
        auth.commitWindow?.let { j.put("guardedHead", it.bindingId) }
        if (cancelBeforeBegin) j.put("state", "CANCELLED")
        if (old == null) {
            // Record ownership separately so a rolled-back begin can retain
            // its native binding and still be collected after the recovery window.
            receiveTransaction {
                store.put(scope, "nativeOwner", id, obj("expiresAtMillis" to now + B2Wire.RETENTION))
            }
        }
        receiveTransaction(checkWindow = true) {
            check(); j.put("connection", connection).put("epoch", epoch)
            bind(j, r); touch(j, now)
        }
        val stream = if (p.s("direction") == "phoneToWatch") 1 else 2
        val rows = if (old != null && !sameLease) objects(id) else null
        val resumedIndex = if (receiveIndex == null && rows != null && rows.isNotEmpty() && j.s("state") != "PLANNING")
            files.receiveIndex(id, rows.map { it.o("descriptor") }) else null
        synchronized(epochs) {
            val prior = streamJobs.putIfAbsent(stream, id)
            if (prior != null && prior != id) fail("PLAN_CONFLICT")
            firstEpoch.putIfAbsent(stream, epoch)
            flows.getValue(stream).bind(id, r.s("planDigest"), p.s("selectionDigest"), epoch,
                limits.n("maxInFlightChunks").toInt(), limits.n("maxInFlightBytes").toInt())
            if (rows != null) {
                applied.clear()
                rows.filter { receipt(j, it) != null }.forEach { applied.add(it.o("descriptor").s("objectId")) }
                synchronized(active) {
                    active.clear()
                    rows.filter { it.optBoolean("active", false) && it.nil("receipt") }
                        .forEach { active.add(it.o("descriptor").s("objectId")) }
                }
            }
            if (commitFence.isRevoked) {
                resumedIndex?.close()
                fail("AUTH_REVOKED")
            }
            if (resumedIndex != null) receiveIndex = resumedIndex
            light[id] = copy(j)
        }
        begun.add(id)
        if (auth.commitWindow == null) cleanupApplied(j)
        return B2Results.answer(r, obj("disposition" to if (old == null && !cancelBeforeBegin) "CREATED" else "RESUMED", "selectionDigest" to p.s("selectionDigest"), "state" to j.s("state"), "expiresAtMillis" to j.n("expiresAtMillis"), "nextManifestPage" to j.n("nextManifestPage"), "completion" to j.get("completion")), epoch)
    }
    private suspend fun manifest(j: JSONObject, r: JSONObject): JSONObject {
        val p = r.o("payload"); val ds = description(j); val index = p.n("pageIndex").toInt(); val counts = ds.a("pageObjectCounts").values().map { B2Json.integer(it) }
        if (index !in counts.indices || p.n("startIndex") != counts.take(index).sum() || p.a("entries").length().toLong() != counts[index]) fail("PLAN_CONFLICT")
        if (B2Wire.frameBytes(r) > lim(j).n("maxManifestPageBytes")) fail("LIMIT_EXCEEDED")
        val prior = if (index == 0) "0".repeat(64) else store.get(scope, "page", key(j.s("jobId"), (index - 1).toString()))?.s("digest") ?: fail("PLAN_CONFLICT")
        if (p.s("previousPageDigest") != prior || B2Digests.page(j.s("planDigest"), p) != p.s("pageDigest")) fail("PLAN_CONFLICT")
        val persistedPage = store.get(scope, "page", key(j.s("jobId"), index.toString()))
        if (persistedPage != null) {
            if (persistedPage.s("digest") != p.s("pageDigest")) fail("PLAN_CONFLICT")
            return persistedPage.o("result")
        }
        if (j.s("state") != "PLANNING" || index.toLong() != j.n("nextManifestPage")) fail("PLAN_CONFLICT")
        val entries = p.a("entries").values().map { B2Wire.descriptor(it as JSONObject, lim(j)) }
        val selected = ds.o("selection").a("kinds").values().map { (it as JSONObject).s("kind") }
        if (entries.any { it.s("kind") !in selected }) fail("UNSUPPORTED_KIND")
        val all = objects(j.s("jobId")).map { it.o("descriptor") } + entries
        B2Wire.sorted(all.map { it.s("objectId") })
        if (all.sumOf { B2Json.utf8(it.toString()).size.toLong() } > lim(j).n("maxManifestBytes")) fail("LIMIT_EXCEEDED")
        val full = index + 1 == counts.size
        if (full) { if (all.size.toLong() != ds.n("objectCount") || B2Digests.objects(all.map { it.s("objectId") }) != ds.s("objectsRoot")) fail("PLAN_CONFLICT"); validateGraph(all)
            B2BookManifest.validatePlan(all, lim(j).n("maxPackageBytes"))
            business.validatePlan(all)
            // Whole-plan physical peak admission precedes READY and all DATA/apply.
            val segmentScratch = all.filter { it.s("kind") == B2Wire.BOOK_SEGMENT }.maxOfOrNull { it.n("size") } ?: 0L
            val incoming = segmentScratch + all.sumOf {
                it.n("size") * (if (it.s("kind") == B2Wire.BOOK_SEGMENT) 1 else 3) + B2Json.utf8(it.toString()).size + B2ReceiveIndex.cost(it) + 1024 } +
                business.applicationScratchBytes(all)
            val other = otherReservations(j.s("jobId"))
            if (incoming + other + files.dataBytes() + lim(j).n("maxJournalBytes") > lim(j).n("maxStagingBytes") || incoming + other + lim(j).n("maxJournalBytes") + 1048576 > files.availableBytes()) fail("STORAGE_PRESSURE")
            j.put("stagingReservation", incoming)
        }
        val result = obj("pageIndex" to index, "pageDigest" to p.s("pageDigest"), "nextManifestPage" to index + 1, "manifestVerified" to full, "state" to if (full) "READY" else "PLANNING")
        receiveTransaction {
            fence(j, r)
            entries.forEach { d -> store.put(scope, "object", objectKey(j.s("jobId"), d.s("objectId")), obj("jobId" to j.s("jobId"), "descriptor" to d, "receipt" to null)) }
            store.put(scope, "page", key(j.s("jobId"), index.toString()), obj("jobId" to j.s("jobId"), "digest" to p.s("pageDigest"), "result" to result))
            j.put("state", result.s("state")).put("nextManifestPage", index + 1); store.put(scope, "job", j.s("jobId"), j); bind(j, r, result)
        }
        if (full) {
            val index = files.receiveIndex(j.s("jobId"), all)
            synchronized(epochs) {
                if (commitFence.isRevoked) { index.close(); fail("AUTH_REVOKED") }
                receiveIndex = index
            }
        }
        light[j.s("jobId")] = copy(j)
        return result
    }
    private suspend fun otherReservations(jobId: String): Long =
        store.list(scope, "job").filter { it.first != jobId && !terminal(it.second) }.sumOf { it.second.optLong("stagingReservation", 0) }

    private suspend fun releaseApplied(j: JSONObject, d: JSONObject) {
        val id = j.s("jobId")
        val keep = d.s("kind") == B2Wire.BOOK_SEGMENT && j.s("state") != "COMPLETE" &&
            store.get(scope, "bookAssembly", key(id, B2Metadata.validate(d).s("bookId"))) == null
        files.release(scope, id, d, keepObject = keep)
        if (d.s("kind") == B2Wire.BOOK_COMMIT) {
            val retained = auth.commitWindow != null &&
                objectRow(id, d.s("objectId")).o("receipt").s("outcome") == "retainedLocal"
            if (!retained) files.assembly(scope, id, d.s("objectId")).delete()
        }
    }

    private fun receipt(j: JSONObject, row: JSONObject): JSONObject? {
        if (row.nil("receipt")) return null
        val r = try { row.o("receipt").also { B2Results.receipt(it) } } catch (_: B2Exception) { fail("RECEIPT_CONFLICT") }
        if (r.s("jobId") != j.s("jobId") || r.s("planDigest") != j.s("planDigest") || r.s("receiverDeviceId") != (if (receiveDirection == "watchToPhone") auth.phoneDeviceId else auth.watchDeviceId) || r.s("objectId") != row.o("descriptor").s("objectId")) fail("RECEIPT_CONFLICT")
        return r
    }
    private suspend fun verifiedReceipt(j: JSONObject, row: JSONObject): JSONObject? {
        val value = receipt(j, row) ?: return null
        val window = auth.commitWindow ?: return value
        if (j.optString("guardedHead") != window.bindingId) fail("BUSINESS_REJECTED")
        val job = j.s("jobId")
        val descriptor = row.o("descriptor")
        val oid = descriptor.s("objectId")
        val source = store.get(scope, "request", key(job, sourcePeer, value.s("applyRequestId")))
            ?: fail("RECEIPT_CONFLICT")
        val digest = B2Digests.request(job, j.s("planDigest"), "apply", obj("objectId" to oid))
        if (source.s("digest") != digest || source.optJSONObject("result")?.optJSONObject("receipt")?.toString() != value.toString()) {
            fail("RECEIPT_CONFLICT")
        }
        val witness = row.optJSONObject("witness") ?: fail("RECEIPT_CONFLICT")
        val metadata = B2Metadata.validate(descriptor)
        if (descriptor.s("kind") == B2Wire.BOOK_SEGMENT) {
            if (!verifyHiddenSegment(job, descriptor, metadata)) fail("RECEIPT_CONFLICT")
        } else if (business.receiptWitness(null, descriptor, metadata, witness)?.toString() != witness.toString()) {
            fail("RECEIPT_CONFLICT")
        }
        check()
        return value
    }

    private suspend fun verifyHiddenSegment(job: String, descriptor: JSONObject, metadata: JSONObject): Boolean {
        val staged = files.composed(scope, job, descriptor.s("objectId"))
        val revision = BookPackageFiles.Revision(descriptor.s("sha256"), descriptor.n("size"))
        if (BookPackageFiles.verify(staged, revision) { check() } != null) return true
        val objectId = store.get(scope, "bookAssembly", key(job, metadata.s("bookId")))?.s("objectId")
            ?: return false
        val commit = objectRow(job, objectId)
        val commitDescriptor = commit.o("descriptor")
        if (descriptor.s("objectId") !in B2Metadata.validate(commitDescriptor).a("segmentObjectIds").strings()) return false
        val assembled = files.assembly(scope, job, objectId)
        val packageRevision = BookPackageFiles.Revision(metadata.s("packageSha256"), metadata.n("packageBytes"))
        if (BookPackageFiles.verify(assembled, packageRevision) { check() } != null) return true
        if (commit.nil("receipt")) return false
        val witness = commit.optJSONObject("witness") ?: return false
        return witness.optString("packageSha256") == packageRevision.sha256 &&
            business.receiptWitness(null, commitDescriptor, B2Metadata.validate(commitDescriptor), witness)?.toString() ==
            witness.toString()
    }

    private suspend fun verifyCompletion(j: JSONObject) {
        if (auth.commitWindow == null) return
        val receipts = objects(j.s("jobId")).map { verifiedReceipt(j, it) ?: fail("RECEIPT_CONFLICT") }
        val completion = j.o("completion")
        if (receipts.size.toLong() != completion.n("objectCount") ||
            B2Digests.receipts(receipts) != completion.s("receiptsRoot")) fail("RECEIPT_CONFLICT")
    }

    /** The durable receipt plus immutable descriptor is the cleanup retry record.
     * Only our disposable chunk/compose paths are removed. Failure leaves that record
     * intact, never rolls back APPLIED, and is retried on observation or admission. */
    private suspend fun cleanupApplied(j: JSONObject) {
        for (row in objects(j.s("jobId"))) if (receipt(j, row) != null) {
            releaseApplied(j, row.o("descriptor"))
        }
    }
    private suspend fun status(j: JSONObject, row: JSONObject, appliedReceipt: JSONObject?): Pair<String, List<Int>> {
        if (appliedReceipt != null) {
            releaseApplied(j, row.o("descriptor"))
            return "APPLIED" to emptyList()
        }
        val d = row.o("descriptor"); val missing = files.working(j.s("jobId")) { files.missing(scope, j.s("jobId"), d) }
        // Adopt durable orphan files after a crash; discard stale block availability.
        val inactive = missing.size == d.a("chunkHashes").length() && row.optBoolean("active", false)
        receiveTransaction {
            d.a("chunkHashes").strings().indices.forEach { i ->
                val k = key(j.s("jobId"), d.s("objectId"), i.toString())
                if (i in missing) store.delete(scope, "chunk", k)
                else if (store.get(scope, "chunk", k) == null) {
                    store.budget(1024, lim(j).n("maxJournalBytes"))
                    store.put(scope, "chunk", k, obj("jobId" to j.s("jobId"), "sha256" to d.a("chunkHashes").getString(i)))
                }
            }
            if (inactive) {
                // Lost/corrupt cache bytes hold no active object buffer after revalidation.
                row.put("active", false)
                store.put(scope, "object", objectKey(j.s("jobId"), d.s("objectId")), row)
            }
        }
        if (inactive) synchronized(active) { active.remove(d.s("objectId")) }
        val staged = missing.isEmpty() && files.verified(files.composed(scope, j.s("jobId"), d.s("objectId")), d.n("size"), d.s("sha256"))
        return (if (staged) "STAGED" else if (missing.size < d.a("chunkHashes").length()) "PARTIAL" else "PENDING") to missing
    }
    private suspend fun missing(j: JSONObject, p: JSONObject): JSONObject {
        if (j.s("state") == "COMPLETE") verifyCompletion(j)
        val base = obj("state" to j.s("state"), "nextManifestPage" to j.n("nextManifestPage"), "expiresAtMillis" to j.n("expiresAtMillis"), "completion" to j.get("completion"))
        val items = JSONArray(); var next: JSONObject? = null
        if (p.n("limit") == 0L || j.s("state") == "CANCELLED") return base.put("items", items).put("next", JSONObject.NULL).put("done", true)
        if (j.s("state") == "PLANNING") fail("MANIFEST_INCOMPLETE")
        if (p.n("limit") > lim(j).n("maxResultsPerPage")) fail()
        if (auth.commitWindow == null) cleanupApplied(j)
        val all = objects(j.s("jobId")); var start = 0; var from = 0
        if (!p.nil("afterObjectId")) {
            val index = all.indexOfFirst { it.o("descriptor").s("objectId") == p.s("afterObjectId") }; if (index < 0) fail()
            if (p.nil("afterChunkIndex")) start = index + 1 else { start = index; from = p.n("afterChunkIndex").toInt(); if (from >= all[index].o("descriptor").a("chunkHashes").length()) fail() }
        }
        var remaining = lim(j).n("maxRangesPerPage").toInt()
        for (i in start until all.size) {
            if (items.length().toLong() >= p.n("limit")) break
            val row = all[i]
            val d = row.o("descriptor")
            val rc = verifiedReceipt(j, row)
            val (state, indices) = status(j, row, rc)
            val ranges = mutableListOf<MutableList<Int>>()
            indices.filter { it >= from }.forEach { n -> if (ranges.lastOrNull()?.get(1) == n) ranges.last()[1] = n + 1 else ranges.add(mutableListOf(n, n + 1)) }; from = 0
            val take = ranges.take(remaining); remaining -= take.size
            items.put(obj("objectId" to d.s("objectId"), "state" to state, "missingRanges" to JSONArray(take), "receipt" to rc))
            if (take.size < ranges.size) { next = obj("afterObjectId" to d.s("objectId"), "afterChunkIndex" to take.lastOrNull()?.get(1)); if (take.isEmpty()) { items.remove(items.length() - 1); next.put("afterChunkIndex", ranges[0][0]) }; break }
            next = if (i + 1 < all.size) obj("afterObjectId" to d.s("objectId"), "afterChunkIndex" to null) else null
            if (remaining == 0) break
        }
        val result = base.put("items", items).put("next", next ?: JSONObject.NULL).put("done", next == null)
        if (B2Json.utf8(result.toString()).size + 1024 > lim(j).n("maxFrameBytes")) fail("LIMIT_EXCEEDED")
        return result
    }
    private suspend fun runnable(j: JSONObject, d: JSONObject) {
        for (id in d.a("dependsOn").strings()) if (receipt(j, objectRow(j.s("jobId"), id)) == null) fail("DEPENDENCIES_PENDING")
    }
    private suspend fun objectLimit(j: JSONObject): Long {
        val max = lim(j).n("maxInFlightObjects")
        if (auth.bindings.size == 1) return max
        if (receiveDirection == "phoneToWatch") return (max + 1) / 2
        if (max > 1) return max / 2
        val forward = auth.bindings.first { it.s("direction") == "phoneToWatch" }.s("jobId")
        if (forward in finishedSources) return 1
        if (store.get(scope, "peerAck", forward) == null) return 0
        sourceCompleted(forward)
        return 1
    }
    private suspend fun admit(j: JSONObject, d: JSONObject) {
        val row = objectRow(j.s("jobId"), d.s("objectId"))
        if (!row.optBoolean("active", false)) {
            val cap = objectLimit(j)
            synchronized(active) {
                val oid = d.s("objectId")
                if (oid !in active && active.size >= cap) fail("WINDOW_FULL")
                active.add(oid)
            }
            receiveTransaction {
                row.put("active", true)
                store.put(scope, "object", objectKey(j.s("jobId"), d.s("objectId")), row)
            }
        }
    }
    private suspend fun stagingLimit(j: JSONObject): Long {
        var limit = lim(j).n("maxStagingBytes")
        for ((_, other) in store.list(scope, "job")) if (!terminal(other)) limit = minOf(limit, lim(other).n("maxStagingBytes"))
        return limit
    }
    private suspend fun validateChunk(j: JSONObject, p: JSONObject, b: ByteArray): Pair<JSONObject, ByteArray> {
        val row = objectRow(j.s("jobId"), p.s("objectId")); val d = row.o("descriptor"); val index = p.n("chunkIndex").toInt()
        if (index !in 0 until d.a("chunkHashes").length()) fail()
        if (b.size.toLong() != minOf(B2Wire.CHUNK.toLong(), d.n("size") - index * B2Wire.CHUNK.toLong()) || B2Digests.hash(b) != d.a("chunkHashes").getString(index)) fail("HASH_MISMATCH")
        return row to b
    }
    private suspend fun put(j: JSONObject, r: JSONObject, bytes: ByteArray): JSONObject {
        if (j.s("state") == "PLANNING") fail("MANIFEST_INCOMPLETE")
        val p = r.o("payload"); val row = objectRow(j.s("jobId"), p.s("objectId")); val d = row.o("descriptor"); val rc = verifiedReceipt(j, row)
        if (rc != null) {
            releaseApplied(j, d)
            return obj("objectId" to d.s("objectId"), "chunkIndex" to p.n("chunkIndex"), "chunkState" to null, "objectState" to "APPLIED", "receipt" to rc)
        }
        if (terminal(j)) fail("JOB_CANCELLED")
        runnable(j, d); admit(j, d)
        val budget = stagingLimit(j)
        files.working(j.s("jobId")) { files.stage(scope, j.s("jobId"), d, p.n("chunkIndex").toInt(), bytes, budget) }; fault("afterChunkDurable")
        leases[j.s("jobId")] = monotonic() + B2Wire.LEASE
        val result = obj("objectId" to d.s("objectId"), "chunkIndex" to p.n("chunkIndex"), "chunkState" to "STAGED", "objectState" to "PARTIAL", "receipt" to null)
        receiveTransaction { fence(j, r); bind(j, r, result)
            store.put(scope, "chunk", key(j.s("jobId"), d.s("objectId"), p.n("chunkIndex").toString()), obj("jobId" to j.s("jobId"), "sha256" to d.a("chunkHashes").getString(p.n("chunkIndex").toInt())))
            j.put("state", "TRANSFERRING"); store.put(scope, "job", j.s("jobId"), j) }
        progress(receiveDirection + ":" + B2Progress.chunk(d, p.n("chunkIndex").toInt()))
        return result
    }
    private suspend fun apply(j: JSONObject, r: JSONObject): JSONObject {
        if (j.s("state") == "PLANNING") fail("MANIFEST_INCOMPLETE")
        val row = objectRow(j.s("jobId"), r.o("payload").s("objectId")); val d = row.o("descriptor")
        verifiedReceipt(j, row)?.let {
            releaseApplied(j, d)
            return obj("state" to "APPLIED", "receipt" to it)
        }
        if (terminal(j)) fail("JOB_CANCELLED")
        runnable(j, d); admit(j, d)
        val metadata = B2Metadata.validate(d)
        val budget = stagingLimit(j)
        val staged = files.working(j.s("jobId")) { files.finalize(scope, j.s("jobId"), d, budget) }; fault("afterObjectDurable")
        leases[j.s("jobId")] = monotonic() + B2Wire.LEASE
        val inbox = auth.commitWindow?.prepareInbox(
            B2CommitWindow.InboxInput(sourcePeer, receiveDirection, j.s("jobId"), j.s("planDigest"), d, staged)
        ) {
            fence(j, r)
        }
        if (inbox != null) rejectToInbox(j, r, inbox)
        // The writer lane keeps GC and new epochs out during local work.
        // Extend the in-use lease. Revocation can still fence commit admission.
        leases[j.s("jobId")] = monotonic() + B2Wire.LEASE
        val jobId = j.s("jobId")
        val isBookCommit = d.s("kind") == B2Wire.BOOK_COMMIT
        val assembly = if (isBookCommit) files.assembly(scope, jobId, d.s("objectId")) else null
        val assemblyKey = if (isBookCommit) key(jobId, metadata.s("bookId")) else ""
        val context = B2ApplyContext(jobId, segment = { oid ->
            val dep = objectRow(jobId, oid)
            val segment = dep.o("descriptor")
            if (receipt(j, dep) == null || segment.s("kind") != B2Wire.BOOK_SEGMENT) fail("DEPENDENCIES_PENDING")
            B2FileRef(files.composed(scope, jobId, oid), 0, segment.n("size"))
        }, check = ::check, guarded = auth.commitWindow != null, stagedFile = staged,
            assembly = assembly, publishAssembly = { temp, segments ->
            if (assembly == null || segments != metadata.a("segmentObjectIds").strings()) fail("PLAN_CONFLICT")
            check()
            files.publishAssembly(temp, assembly)
            receiveTransaction {
                check()
                store.put(scope, "bookAssembly", assemblyKey, obj("jobId" to jobId, "objectId" to d.s("objectId")))
            }
            // The durable assembly registration proves these bytes remain recoverable.
            for (id in segments) releaseApplied(j, objectRow(jobId, id).o("descriptor"))
        }, assemblyReady = {
            isBookCommit && store.get(scope, "bookAssembly", assemblyKey)?.s("objectId") == d.s("objectId")
        })
        try {
            business.prepareFile(context, copy(d), metadata)
        } catch (failure: Throwable) {
            runCatching { context.finishAsset?.invoke(false) }.onFailure(failure::addSuppressed)
            throw failure
        }
        leases[jobId] = monotonic() + B2Wire.LEASE
        val result = applyTransaction(d.s("kind"), context) {
            fence(j, r)
            val outcome = business.applyFile(context, copy(d), metadata, B2FileRef(staged, 0, d.n("size")))
            fault("beforeApplyCommit")
            check()
            touch(j, retention.clock())
            val rc = B2Digests.receipt(r, d.s("objectId"), if (receiveDirection == "watchToPhone") auth.phoneDeviceId else auth.watchDeviceId, outcome)
            if (auth.commitWindow != null) {
                val witness = if (d.s("kind") == B2Wire.BOOK_SEGMENT) obj("sha256" to d.s("sha256"))
                    else business.receiptWitness(context, d, metadata, null) ?: fail("BUSINESS_REJECTED")
                row.put("witness", witness)
            }
            row.put("receipt", rc); store.put(scope, "object", objectKey(j.s("jobId"), d.s("objectId")), row)
            if (objects(j.s("jobId")).all { !it.nil("receipt") }) j.put("state", "APPLIED") else j.put("state", "TRANSFERRING")
            store.put(scope, "job", j.s("jobId"), j)
            obj("state" to "APPLIED", "receipt" to rc).also { bind(j, r, it) }
        }
        applied.add(d.s("objectId"))
        synchronized(active) { active.remove(d.s("objectId")) }
        progress(receiveDirection + ":" + B2Progress.applied(d))
        fault("afterApplyCommit")
        releaseApplied(j, d)
        if (d.s("kind") == B2Wire.BOOK_COMMIT) {
            for (id in metadata.a("segmentObjectIds").strings()) {
                releaseApplied(j, objectRow(j.s("jobId"), id).o("descriptor"))
            }
        }
        return result
    }
    private suspend fun rejectToInbox(j: JSONObject, r: JSONObject, inbox: B2CommitWindow.PendingInbox): Nothing {
        val caller = currentCoroutineContext()
        withContext(NonCancellable) {
            var committed = false
            try {
                receiveTransaction(checkWindow = true) {
                    caller.ensureActive()
                    fence(j, r)
                    inbox.commit()
                    caller.ensureActive()
                }
                committed = true
            } finally {
                inbox.finish(committed)
            }
        }
        fail("BUSINESS_REJECTED")
    }

    private suspend fun complete(j: JSONObject, r: JSONObject): JSONObject {
        if (j.s("state") == "CANCELLED") fail("JOB_CANCELLED")
        val p = r.o("payload"); val ds = description(j)
        if (p.n("objectCount") != ds.n("objectCount") || p.n("ackToInclusive") != ds.o(if (receiveDirection == "watchToPhone") "watchSnapshot" else "phoneSnapshot").n("toInclusive")) fail("PLAN_CONFLICT")
        if (j.s("state") == "COMPLETE") {
            verifyCompletion(j)
            val c = j.o("completion")
            if (p.s("receiptsRoot") != c.s("receiptsRoot")) fail("RECEIPT_CONFLICT")
            return c
        }
        if (j.s("state") == "PLANNING") fail("MANIFEST_INCOMPLETE")
        val rs = objects(j.s("jobId")).map { verifiedReceipt(j, it) ?: fail("CHUNKS_MISSING") }
        val root = B2Digests.receipts(rs); if (root != p.s("receiptsRoot")) fail("RECEIPT_CONFLICT")
        val c = B2Digests.completion(j.s("jobId"), j.s("planDigest"), ds.n("objectCount"), root, p.n("ackToInclusive"))
        receiveTransaction(businessWrite = true) {
            fence(j, r); business.complete(copy(ds), copy(c)); check(); touch(j, retention.clock())
            j.put("state", "COMPLETE").put("completion", c); store.put(scope, "job", j.s("jobId"), j); bind(j, r, c)
        }; fault("afterCompleteCommit"); return c
    }
    private suspend fun cancel(j: JSONObject, r: JSONObject): JSONObject {
        val result = obj("state" to if (j.s("state") == "COMPLETE") "COMPLETE" else "CANCELLED", "alreadyComplete" to (j.s("state") == "COMPLETE"))
        receiveTransaction { fence(j, r); j.put("state", result.s("state")); store.put(scope, "job", j.s("jobId"), j); bind(j, r, result) }
        if (j.s("state") == "CANCELLED") objects(j.s("jobId")).forEach {
            val d = it.o("descriptor")
            files.release(scope, j.s("jobId"), d, keepObject = d.s("kind") == B2Wire.BOOK_SEGMENT)
        }
        return result
    }
    companion object {
        fun validateGraph(all: List<JSONObject>) {
            val byId = all.associateBy { it.s("objectId") }; if (byId.size != all.size) fail("PLAN_CONFLICT")
            val identities = HashSet<String>()
            for (d in all) { val k = if (d.s("kind") in listOf("articleBody", "articleMetadata")) "article" else d.s("kind"); if (!identities.add(k + "\u0000" + d.s("businessId"))) fail("PLAN_CONFLICT") }
            val remaining = byId.keys.toMutableSet(); val done = HashSet<String>()
            while (remaining.isNotEmpty()) {
                val ready = remaining.filter { id -> byId.getValue(id).a("dependsOn").strings().all { it in done } }
                if (ready.isEmpty()) fail("PLAN_CONFLICT"); remaining.removeAll(ready.toSet()); done.addAll(ready)
            }
        }
    }
}
