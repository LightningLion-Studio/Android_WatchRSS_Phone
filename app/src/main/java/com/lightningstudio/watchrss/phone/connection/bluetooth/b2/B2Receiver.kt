package com.lightningstudio.watchrss.phone.connection.bluetooth.b2

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
    fun canCommitRange(description: JSONObject): Boolean = false
    suspend fun complete(description: JSONObject, completion: JSONObject) {}
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
    private val progress: (String) -> Unit = {}
) {
    private val connection = java.util.UUID.randomUUID().toString()
    @Volatile private var revoked = false
    private val light = java.util.concurrent.ConcurrentHashMap<String, JSONObject>()
    private val firstEpoch = java.util.concurrent.ConcurrentHashMap<Int, Long>()
    private val streamJobs = java.util.concurrent.ConcurrentHashMap<Int, String>()
    @Volatile private var receiveIndex: B2ReceiveIndex? = null
    private val applied = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val finishedSources = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val flows = mapOf(1 to B2Flow(1), 2 to B2Flow(2))
    private val epochs = Any()
    private val active = mutableSetOf<String>()
    fun fenceNow() { revoked = true }
    internal fun closeIndex() = synchronized(epochs) {
        receiveIndex?.close()
        receiveIndex = null
    }
    internal fun sourceCompleted(job: String) { finishedSources.add(job) }
    private val leases = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val begun = mutableSetOf<String>()
    private val scope get() = auth.accountScope
    private fun check() { if (revoked) fail("AUTH_REVOKED"); auth.check() }
    suspend fun revoke(cancelJobs: Boolean = false) = store.exclusive {
        revoked = true; leases.clear()
        if (cancelJobs) for (b in auth.bindings) {
            val j = store.get(scope, "job", b.s("jobId")) ?: continue
            // A late old-controller cancellation cannot cancel a newer connection.
            if (j.s("connection") != connection || terminal(j)) continue
            store.transaction { j.put("state", "CANCELLED"); store.put(scope, "job", j.s("jobId"), j) }
            objects(j.s("jobId")).forEach { files.release(scope, j.s("jobId"), it.o("descriptor")) }
        }
    }
    private val sourcePeer get() = if (receiveDirection == "watchToPhone") auth.watchDeviceId else auth.phoneDeviceId
    private fun key(vararg p: String) = B2Digests.d("wrss.library-binary.v2/record-key", p.toList())
    private fun objectKey(job: String, oid: String) = key(job, oid)
    private suspend fun objects(job: String) = store.list(scope, "object").map { it.second }.filter { it.s("jobId") == job }.sortedBy { it.o("descriptor").s("objectId") }
    private suspend fun objectRow(job: String, oid: String) = store.get(scope, "object", objectKey(job, oid)) ?: fail()
    private suspend fun job(id: String) = store.get(scope, "job", id) ?: fail("NOT_FOUND")
    private fun terminal(j: JSONObject) = j.s("state") in listOf("COMPLETE", "CANCELLED")
    private fun description(j: JSONObject) = j.o("description")
    private fun lim(j: JSONObject) = description(j).o("selection").o("limits")
    private suspend fun clock(): Long {
        // Logical expiry only advances by proven monotonic elapsed time. A new process
        // conservatively starts at the last persisted logical instant, never wall time.
        val old = store.get(scope, "clock", "clock")
        val now = monotonic()
        val logical = if (old == null) 0L else old.n("logical") + if (old.s("process") == processGeneration) maxOf(0L, now - old.n("elapsed")) else 0L
        store.put(scope, "clock", "clock", obj("logical" to logical, "elapsed" to now, "process" to processGeneration))
        return logical
    }
    private suspend fun touch(j: JSONObject, now: Long) {
        j.put("expiresAtMillis", maxOf(j.n("expiresAtMillis"), now + B2Wire.RETENTION))
        store.put(scope, "job", j.s("jobId"), j)
        leases[j.s("jobId")] = monotonic() + B2Wire.LEASE
    }
    private fun fence(j: JSONObject, r: JSONObject) {
        check()
        if (j.s("connection") != connection || !leases.containsKey(j.s("jobId")) || j.n("epoch") != r.n("epoch")) fail("STALE_EPOCH")
        if (monotonic() >= leases.getValue(j.s("jobId"))) fail("LEASE_EXPIRED")
        if (j.s("planDigest") != r.s("planDigest")) fail("PLAN_CONFLICT")
    }
    private suspend fun bind(j: JSONObject, r: JSONObject, result: JSONObject? = null): JSONObject? {
        val id = key(j.s("jobId"), sourcePeer, r.s("requestId"))
        files.bindRequest(scope, j.s("jobId"), sourcePeer, r.s("requestId"), r.s("requestDigest"), lim(j).n("maxJournalBytes"))
        val old = store.get(scope, "request", id)
        if (old != null && old.s("digest") != r.s("requestDigest")) fail("REQUEST_CONFLICT")
        if (old == null) { store.budget(1024, lim(j).n("maxJournalBytes")); store.put(scope, "request", id, obj("digest" to r.s("requestDigest"), "result" to result)) }
        else if (result != null) store.put(scope, "request", id, obj("digest" to r.s("requestDigest"), "result" to result))
        return old?.takeUnless { it.nil("result") }?.o("result")
    }
    /** Throws typed errors for the dispatcher to encode. Transport I/O remains distinct. */
    suspend fun handle(frame: JSONObject, data: ByteArray? = null): JSONObject = try { store.exclusive {
        val r = B2Wire.envelope(B2Json.parse(B2Json.utf8(frame.toString()))).also { B2Wire.retainSize(it, B2Wire.frameBytes(frame)) }
        if (r.s("type") != "request" || r.s("phase") == "prepare") fail()
        check()
        val now = clock()
        if (r.s("phase") == "begin") return@exclusive begin(r, now)
        val j = job(r.s("jobId")); fence(j, r)
        if (B2Wire.frameBytes(r) > lim(j).n("maxFrameBytes") || B2Wire.frameBytes(r) - 12 > lim(j).n("maxControlHeaderBytes")) fail("LIMIT_EXCEEDED")
        // Phase-specific limits apply before request/page receipt adoption too.
        if (r.s("phase") == "manifestPage" && B2Wire.frameBytes(r) > lim(j).n("maxManifestPageBytes")) fail("LIMIT_EXCEEDED")
        if (j.s("state") == "CANCELLED" && r.s("phase") !in listOf("missing", "cancel")) fail("JOB_CANCELLED")
        if (j.s("state") == "PLANNING" && r.s("phase") in listOf("putChunk", "apply")) fail("MANIFEST_INCOMPLETE")
        // Wire payload validation always precedes deduplication. put additionally checks bytes.
        if (r.s("phase") == "putChunk") validateChunk(j, r.o("payload"), data ?: fail())
        val previous = bind(j, r)
        touch(j, now)
        val result = when (r.s("phase")) {
            "missing" -> missing(j, r.o("payload")) // Queries return current verified observations.
            "putChunk" -> put(j, r, data ?: fail())
            "apply" -> apply(j, r)
            "manifestPage" -> previous ?: manifest(j, r)
            "complete" -> complete(j, r)
            "cancel" -> cancel(j, r)
            else -> fail()
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
    /** PLANNING exception: committed snapshot only; no DB lane, data scan or admission. */
    fun heartbeat(r: JSONObject): JSONObject {
        B2Wire.envelope(r)
        if (r.s("phase") != "missing" || r.o("payload").n("limit") != 0L) fail()
        val j = light[r.s("jobId")] ?: fail("AUTH_REVOKED")
        fence(j, r)
        files.bindRequest(scope, j.s("jobId"), sourcePeer, r.s("requestId"), r.s("requestDigest"), lim(j).n("maxJournalBytes"))
        leases[j.s("jobId")] = monotonic() + B2Wire.LEASE
        return B2Results.answer(r, obj("state" to j.s("state"), "nextManifestPage" to j.n("nextManifestPage"),
            "expiresAtMillis" to j.n("expiresAtMillis"), "items" to JSONArray(), "next" to null,
            "done" to true, "completion" to j.get("completion")), j.n("epoch"))
    }
    /** Read only committed registration here. The socket reader never acquires
     * the writer lane or waits for a chunk to be written or synced. */
    suspend fun inspect(h: B2DataHeader, busy: Boolean = false): Pair<JSONObject, String> =
        inspect(h) { _, _ -> if (busy) fail("BUSY") }

    internal suspend fun inspect(h: B2DataHeader, credit: (JSONObject, String) -> Unit): Pair<JSONObject, String> {
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
                fence(j, r)
                credit(r, hash)
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
                if (B2Results.errorValue(e.code).s("recovery") in listOf("WAIT", "QUERY", "BEGIN"))
                    throw B2RejectedData(r, hash, e.code)
                throw e
            }
        }
        return r to hash
    }

    internal fun current(stream: Int, epoch: Long): Boolean =
        !revoked && streamJobs[stream]?.let { light[it]?.n("epoch") } == epoch

    internal fun streamFor(job: String): Int = streamJobs.entries.firstOrNull { it.value == job }?.key ?: fail("AUTH_REVOKED")

    internal fun window(stream: Int): Int = lim(light[jobFor(stream)] ?: fail("AUTH_REVOKED")).n("maxInFlightChunks").toInt()

    internal data class WrittenChunk(val header: B2DataHeader, val request: JSONObject, val temp: File)

    internal fun writeChunk(frame: B2Frame.Data, request: JSONObject): WrittenChunk? {
        if (!current(frame.header.stream, frame.header.epoch)) return null
        val temp = files.writeChunk(frame.bytes, frame.header.length) {
            check()
            if (!current(frame.header.stream, frame.header.epoch)) fail("STALE_EPOCH")
        }
        return WrittenChunk(frame.header, request, temp)
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
        val outcomes = group.map { chunk ->
            val oid = chunk.request.o("payload").s("objectId")
            val row = objectRow(j.s("jobId"), oid)
            if (receipt(j, row) != null) return@map CommittedChunk(chunk, 2)
            if (terminal(j)) fail("JOB_CANCELLED")
            val descriptor = row.o("descriptor")
            runnable(j, descriptor)
            staged.add(chunk.temp to files.chunk(scope, j.s("jobId"), oid, chunk.header.index))
            CommittedChunk(chunk, 1, receiveDirection + ":" + B2Progress.chunk(descriptor, chunk.header.index))
        }
        val budget = stagingLimit(j)
        if (files.usedBytes() > budget) fail("STORAGE_PRESSURE")
        files.publishChunks(staged) { fence(j, group.first().request) }
        if (staged.isNotEmpty()) fault("afterChunkDurable")
        store.transaction {
            for ((chunk, disposition) in outcomes) {
                fence(j, chunk.request)
                val p = chunk.request.o("payload")
                val oid = p.s("objectId")
                val row = objectRow(j.s("jobId"), oid)
                val receipt = if (disposition == 2) receipt(j, row) else null
                bind(j, chunk.request, obj("objectId" to oid, "chunkIndex" to p.n("chunkIndex"),
                    "chunkState" to if (disposition == 1) "STAGED" else null,
                    "objectState" to if (disposition == 1) "PARTIAL" else "APPLIED", "receipt" to receipt))
                if (disposition == 1) {
                    val hash = row.o("descriptor").a("chunkHashes").getString(chunk.header.index)
                    store.put(scope, "chunk", key(j.s("jobId"), oid, chunk.header.index.toString()), obj("sha256" to hash))
                    if (!row.optBoolean("active", false)) {
                        row.put("active", true)
                        store.put(scope, "object", objectKey(j.s("jobId"), oid), row)
                    }
                }
            }
            if (staged.isNotEmpty()) j.put("state", "TRANSFERRING")
            touch(j, clock())
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
        if (old == null && p.s("intent") == "resume") fail("NOT_FOUND")
        if (old != null) {
            if (old.s("planDigest") != r.s("planDigest") || description(old).a("pageObjectCounts").toString() != p.a("pageObjectCounts").toString()) fail("JOB_CONFLICT")
            // No eager GC: retained records remain resumable beyond their minimum 24h.
            // This also preserves heartbeats/local work across conservative process clocks.
            if (p.s("intent") == "create" && (terminal(old) || old.s("connection") != connection)) fail("JOB_CONFLICT")
        }
        if (p.o(if (receiveDirection == "watchToPhone") "watchSnapshot" else "phoneSnapshot").n("toInclusive") != 0L && !business.canCommitRange(p)) fail("BUSINESS_REJECTED")
        val limits = p.o("selection").o("limits")
        if (B2Wire.frameBytes(r) > limits.n("maxFrameBytes")) fail("LIMIT_EXCEEDED")
        if (old == null) {
            val active = store.all().filter { it.namespace == "job" }.map { B2Json.parse(it.body, false) }.count { !terminal(it) && it.n("expiresAtMillis") >= now }
            if (active >= limits.n("maxActiveJobs")) fail("WINDOW_FULL")
        }
        val reservation = B2Json.utf8(p.toString()).size + 4096L + p.n("objectCount") * 4096L + minOf(limits.n("maxManifestBytes"), p.n("objectCount") * 48000L)
        store.budget(if (old == null) reservation else 0, limits.n("maxJournalBytes"))
        if (p.n("objectCount") == 0L && p.s("objectsRoot") != B2Digests.objects(emptyList())) fail("PLAN_CONFLICT")
        if (old != null && old.s("state") !in listOf("PLANNING", "COMPLETE", "CANCELLED")) {
            val reserve = old.optLong("stagingReservation", 0)
            if (p.n("objectCount") > 0 && reserve <= 0) fail("JOURNAL_CORRUPT")
            if (reserve + 1048576 > files.availableBytes()) fail("STORAGE_PRESSURE")
        }
        val sameLease = old != null && old.s("connection") == connection && (leases[id] ?: -1) > monotonic()
        val epoch = if (sameLease) old!!.n("epoch") else (old?.n("epoch") ?: 0) + 1
        if (epoch > B2Json.MAX_SAFE) fail("JOURNAL_CORRUPT")
        val j = old ?: obj("jobId" to id, "planDigest" to r.s("planDigest"), "description" to copy(p), "state" to if (p.n("objectCount") == 0L) "READY" else "PLANNING", "nextManifestPage" to 0, "completion" to null, "reservation" to reservation, "expiresAtMillis" to now + B2Wire.RETENTION)
        store.transaction {
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
            if (revoked) {
                resumedIndex?.close()
                fail("AUTH_REVOKED")
            }
            if (resumedIndex != null) receiveIndex = resumedIndex
            light[id] = copy(j)
        }
        begun.add(id)
        cleanupApplied(j)
        return B2Results.answer(r, obj("disposition" to if (old == null) "CREATED" else "RESUMED", "selectionDigest" to p.s("selectionDigest"), "state" to j.s("state"), "expiresAtMillis" to j.n("expiresAtMillis"), "nextManifestPage" to j.n("nextManifestPage"), "completion" to j.get("completion")), epoch)
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
            // Whole-plan physical peak admission precedes READY and all DATA/apply.
            val incoming = all.sumOf { it.n("size") * 3 + B2Json.utf8(it.toString()).size + B2ReceiveIndex.cost(it) + 1024 }
            val other = store.list(scope, "job").filter { it.first != j.s("jobId") && !terminal(it.second) }.sumOf { it.second.optLong("stagingReservation", 0) }
            if (incoming + other + files.dataBytes() + lim(j).n("maxJournalBytes") > lim(j).n("maxStagingBytes") || incoming + other + lim(j).n("maxJournalBytes") + 1048576 > files.availableBytes()) fail("STORAGE_PRESSURE")
            j.put("stagingReservation", incoming)
        }
        val result = obj("pageIndex" to index, "pageDigest" to p.s("pageDigest"), "nextManifestPage" to index + 1, "manifestVerified" to full, "state" to if (full) "READY" else "PLANNING")
        store.transaction {
            fence(j, r)
            entries.forEach { d -> store.put(scope, "object", objectKey(j.s("jobId"), d.s("objectId")), obj("jobId" to j.s("jobId"), "descriptor" to d, "receipt" to null)) }
            store.put(scope, "page", key(j.s("jobId"), index.toString()), obj("digest" to p.s("pageDigest"), "result" to result))
            j.put("state", result.s("state")).put("nextManifestPage", index + 1); store.put(scope, "job", j.s("jobId"), j); bind(j, r, result)
        }
        if (full) {
            val index = files.receiveIndex(j.s("jobId"), all)
            synchronized(epochs) {
                if (revoked) { index.close(); fail("AUTH_REVOKED") }
                receiveIndex = index
            }
        }
        light[j.s("jobId")] = copy(j)
        return result
    }
    private fun receipt(j: JSONObject, row: JSONObject): JSONObject? {
        if (row.nil("receipt")) return null
        val r = try { row.o("receipt").also { B2Results.receipt(it) } } catch (_: B2Exception) { fail("RECEIPT_CONFLICT") }
        if (r.s("jobId") != j.s("jobId") || r.s("planDigest") != j.s("planDigest") || r.s("receiverDeviceId") != (if (receiveDirection == "watchToPhone") auth.phoneDeviceId else auth.watchDeviceId) || r.s("objectId") != row.o("descriptor").s("objectId")) fail("RECEIPT_CONFLICT")
        return r
    }
    /** The durable receipt plus immutable descriptor is the cleanup retry record.
     * Only our disposable chunk/compose paths are removed. Failure leaves that record
     * intact, never rolls back APPLIED, and is retried on observation or admission. */
    private suspend fun cleanupApplied(j: JSONObject) {
        for (row in objects(j.s("jobId"))) if (receipt(j, row) != null) {
            files.release(scope, j.s("jobId"), row.o("descriptor"))
        }
    }
    private suspend fun status(j: JSONObject, row: JSONObject): Pair<String, List<Int>> {
        if (receipt(j, row) != null) {
            files.release(scope, j.s("jobId"), row.o("descriptor"))
            return "APPLIED" to emptyList()
        }
        val d = row.o("descriptor"); val missing = files.working(j.s("jobId")) { files.missing(scope, j.s("jobId"), d) }
        // Adopt durable orphan files after a crash; discard stale block availability.
        d.a("chunkHashes").strings().indices.forEach { i ->
            val k = key(j.s("jobId"), d.s("objectId"), i.toString())
            if (i in missing) store.delete(scope, "chunk", k)
            else if (store.get(scope, "chunk", k) == null) { store.budget(1024, lim(j).n("maxJournalBytes")); store.put(scope, "chunk", k, obj("sha256" to d.a("chunkHashes").getString(i))) }
        }
        if (missing.size == d.a("chunkHashes").length() && row.optBoolean("active", false)) {
            // Lost/corrupt cache bytes hold no active object buffer after revalidation.
            row.put("active", false); store.put(scope, "object", objectKey(j.s("jobId"), d.s("objectId")), row)
            synchronized(active) { active.remove(d.s("objectId")) }
        }
        val staged = missing.isEmpty() && files.verified(files.composed(scope, j.s("jobId"), d.s("objectId")), d.n("size"), d.s("sha256"))
        return (if (staged) "STAGED" else if (missing.size < d.a("chunkHashes").length()) "PARTIAL" else "PENDING") to missing
    }
    private suspend fun missing(j: JSONObject, p: JSONObject): JSONObject {
        val base = obj("state" to j.s("state"), "nextManifestPage" to j.n("nextManifestPage"), "expiresAtMillis" to j.n("expiresAtMillis"), "completion" to j.get("completion"))
        val items = JSONArray(); var next: JSONObject? = null
        if (p.n("limit") == 0L || j.s("state") == "CANCELLED") return base.put("items", items).put("next", JSONObject.NULL).put("done", true)
        if (j.s("state") == "PLANNING") fail("MANIFEST_INCOMPLETE")
        if (p.n("limit") > lim(j).n("maxResultsPerPage")) fail()
        cleanupApplied(j)
        val all = objects(j.s("jobId")); var start = 0; var from = 0
        if (!p.nil("afterObjectId")) {
            val index = all.indexOfFirst { it.o("descriptor").s("objectId") == p.s("afterObjectId") }; if (index < 0) fail()
            if (p.nil("afterChunkIndex")) start = index + 1 else { start = index; from = p.n("afterChunkIndex").toInt(); if (from >= all[index].o("descriptor").a("chunkHashes").length()) fail() }
        }
        var remaining = lim(j).n("maxRangesPerPage").toInt()
        for (i in start until all.size) {
            if (items.length().toLong() >= p.n("limit")) break
            val row = all[i]; val d = row.o("descriptor"); val (state, indices) = status(j, row)
            val ranges = mutableListOf<MutableList<Int>>()
            indices.filter { it >= from }.forEach { n -> if (ranges.lastOrNull()?.get(1) == n) ranges.last()[1] = n + 1 else ranges.add(mutableListOf(n, n + 1)) }; from = 0
            val take = ranges.take(remaining); remaining -= take.size
            items.put(obj("objectId" to d.s("objectId"), "state" to state, "missingRanges" to JSONArray(take), "receipt" to receipt(j, row)))
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
            row.put("active", true); store.put(scope, "object", objectKey(j.s("jobId"), d.s("objectId")), row)
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
        val p = r.o("payload"); val row = objectRow(j.s("jobId"), p.s("objectId")); val d = row.o("descriptor"); val rc = receipt(j, row)
        if (rc != null) {
            files.release(scope, j.s("jobId"), d)
            return obj("objectId" to d.s("objectId"), "chunkIndex" to p.n("chunkIndex"), "chunkState" to null, "objectState" to "APPLIED", "receipt" to rc)
        }
        if (terminal(j)) fail("JOB_CANCELLED")
        runnable(j, d); admit(j, d)
        val budget = stagingLimit(j)
        files.working(j.s("jobId")) { files.stage(scope, j.s("jobId"), d, p.n("chunkIndex").toInt(), bytes, budget) }; fault("afterChunkDurable")
        leases[j.s("jobId")] = monotonic() + B2Wire.LEASE
        val result = obj("objectId" to d.s("objectId"), "chunkIndex" to p.n("chunkIndex"), "chunkState" to "STAGED", "objectState" to "PARTIAL", "receipt" to null)
        store.transaction { fence(j, r); bind(j, r, result)
            store.put(scope, "chunk", key(j.s("jobId"), d.s("objectId"), p.n("chunkIndex").toString()), obj("sha256" to d.a("chunkHashes").getString(p.n("chunkIndex").toInt())))
            j.put("state", "TRANSFERRING"); store.put(scope, "job", j.s("jobId"), j) }
        progress(receiveDirection + ":" + B2Progress.chunk(d, p.n("chunkIndex").toInt()))
        return result
    }
    private suspend fun apply(j: JSONObject, r: JSONObject): JSONObject {
        if (j.s("state") == "PLANNING") fail("MANIFEST_INCOMPLETE")
        val row = objectRow(j.s("jobId"), r.o("payload").s("objectId")); val d = row.o("descriptor")
        receipt(j, row)?.let {
            files.release(scope, j.s("jobId"), d)
            return obj("state" to "APPLIED", "receipt" to it)
        }
        if (terminal(j)) fail("JOB_CANCELLED")
        runnable(j, d); admit(j, d)
        val metadata = B2Metadata.validate(d)
        val budget = stagingLimit(j)
        val staged = files.working(j.s("jobId")) { files.finalize(scope, j.s("jobId"), d, budget) }; fault("afterObjectDurable")
        // The writer lane is held during local work. Extend the in-use lease before
        // entering the commit; GC/revoke/new begin cannot cross this boundary.
        leases[j.s("jobId")] = monotonic() + B2Wire.LEASE
        val result = store.transaction {
            fence(j, r)
            val outcome = business.apply(copy(d), metadata, staged)
            check(); fault("beforeApplyCommit")
            touch(j, clock())
            val rc = B2Digests.receipt(r, d.s("objectId"), if (receiveDirection == "watchToPhone") auth.phoneDeviceId else auth.watchDeviceId, outcome)
            row.put("receipt", rc); store.put(scope, "object", objectKey(j.s("jobId"), d.s("objectId")), row)
            if (objects(j.s("jobId")).all { !it.nil("receipt") }) j.put("state", "APPLIED") else j.put("state", "TRANSFERRING")
            store.put(scope, "job", j.s("jobId"), j)
            obj("state" to "APPLIED", "receipt" to rc).also { bind(j, r, it) }
        }
        applied.add(d.s("objectId"))
        synchronized(active) { active.remove(d.s("objectId")) }
        progress(receiveDirection + ":" + B2Progress.applied(d))
        fault("afterApplyCommit")
        files.release(scope, j.s("jobId"), d)
        return result
    }
    private suspend fun complete(j: JSONObject, r: JSONObject): JSONObject {
        if (j.s("state") == "CANCELLED") fail("JOB_CANCELLED")
        val p = r.o("payload"); val ds = description(j)
        if (p.n("objectCount") != ds.n("objectCount") || p.n("ackToInclusive") != ds.o(if (receiveDirection == "watchToPhone") "watchSnapshot" else "phoneSnapshot").n("toInclusive")) fail("PLAN_CONFLICT")
        if (j.s("state") == "COMPLETE") { val c = j.o("completion"); if (p.s("receiptsRoot") != c.s("receiptsRoot")) fail("RECEIPT_CONFLICT"); return c }
        if (j.s("state") == "PLANNING") fail("MANIFEST_INCOMPLETE")
        val rs = objects(j.s("jobId")).map { receipt(j, it) ?: fail("CHUNKS_MISSING") }
        val root = B2Digests.receipts(rs); if (root != p.s("receiptsRoot")) fail("RECEIPT_CONFLICT")
        val c = B2Digests.completion(j.s("jobId"), j.s("planDigest"), ds.n("objectCount"), root, p.n("ackToInclusive"))
        store.transaction {
            fence(j, r); business.complete(copy(ds), copy(c)); check(); touch(j, clock())
            j.put("state", "COMPLETE").put("completion", c); store.put(scope, "job", j.s("jobId"), j); bind(j, r, c)
        }; fault("afterCompleteCommit"); return c
    }
    private suspend fun cancel(j: JSONObject, r: JSONObject): JSONObject {
        val result = obj("state" to if (j.s("state") == "COMPLETE") "COMPLETE" else "CANCELLED", "alreadyComplete" to (j.s("state") == "COMPLETE"))
        store.transaction { fence(j, r); j.put("state", result.s("state")); store.put(scope, "job", j.s("jobId"), j); bind(j, r, result) }
        if (j.s("state") == "CANCELLED") objects(j.s("jobId")).forEach { files.release(scope, j.s("jobId"), it.o("descriptor")) }
        return result
    }
    companion object {
        private val processGeneration = java.util.UUID.randomUUID().toString()
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
