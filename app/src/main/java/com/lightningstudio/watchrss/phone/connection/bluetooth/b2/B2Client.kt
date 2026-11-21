package com.lightningstudio.watchrss.phone.connection.bluetooth.b2

import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import org.json.JSONArray
import org.json.JSONObject

/** Protocol client only. Transport/account/Ready/A-B controller are intentionally outside.
 * The exchange port must route reverse requests concurrently (B2Session does this). */
class B2Client(
    private val store: B2Persistence, private val snapshots: B2Snapshots,
    private val exchange: suspend (JSONObject) -> JSONObject,
    private val authorize: () -> Unit,
    private val session: B2Session,
    private val progress: (String) -> Unit = {},
    private val onSourceCancelled: (String) -> Unit = {}
) {
    private val connectionId = java.util.UUID.randomUUID().toString()
    private val sourceBegins = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val cancelledSources = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile private var generation = 0L
    private var echoed: JSONObject? = null
    private var offer: JSONObject? = null
    private var offerReply: JSONObject? = null
    private var offerId: String? = null
    private var bindings = emptyList<JSONObject>()
    private val waitBackoff = B2Backoff()
    private var lastGrantedEpoch = 0L
    private var rejectedChunks = 0
    private var sourceObjects = emptyList<JSONObject>()
    private var account = ""
    private var phone = ""
    private var watch = ""
    /** Must be called separately for EACH newly authenticated connection. */
    fun acceptProbe(probe: JSONObject): Boolean {
        generation++
        echoed = B2Negotiation.echoed(probe); offer = null; offerReply = null; offerId = null; bindings = emptyList()
        return echoed != null
    }
    private suspend fun call(phase: String, job: String?, plan: String?, epoch: Long?, p: JSONObject, stable: Boolean = true): JSONObject {
        val connectionGeneration = generation
        authorize()
        if (job != null && job in cancelledSources && phase != "cancel") fail("JOB_CANCELLED")
        if (echoed == null) fail("CAPABILITY_MISMATCH")
        val digest = B2Digests.request(job, plan, phase, p)
        val id = if (stable) B2Digests.d("wrss.library-binary.v2/source-request", listOf(job ?: "", digest)).take(32) else java.util.UUID.randomUUID().toString().replace("-", "")
        val request = B2Wire.request(phase, job, plan, epoch, p, id)
        val limits = bindings.find { it.s("jobId") == job }?.o("selection")?.o("limits") ?: echoed!!.o("limits")
        val headerBytes = B2Json.utf8(request.toString()).size
        if (headerBytes > limits.n("maxControlHeaderBytes") || headerBytes + 12 > limits.n("maxFrameBytes")) fail("LIMIT_EXCEEDED")
        val answer = B2Wire.envelope(exchange(request))
        val maxFrame = bindings.find { it.s("jobId") == job }?.o("selection")?.o("limits")?.n("maxFrameBytes") ?: 262144L
        if (B2Wire.frameBytes(answer) > maxFrame || B2Wire.frameBytes(answer) - 12 > limits.n("maxControlHeaderBytes")) fail("LIMIT_EXCEEDED")
        if (generation != connectionGeneration) fail("STALE_EPOCH")
        authorize(); if (job != null && job in cancelledSources && phase != "cancel") fail("JOB_CANCELLED")
        B2Session.validateCorrelation(request, answer)
        if (!answer.getBoolean("success")) throw B2Exception(answer.o("error").s("code"), answer.o("error").n("retryAfterMillis"))
        val result = answer.o("result")
        if (phase == "begin" && answer.n("epoch") != lastGrantedEpoch) { waitBackoff.reset(); lastGrantedEpoch = answer.n("epoch") }
        if (phase in listOf("manifestPage", "apply")) waitBackoff.reset()
        val completion = if (phase == "complete") result else if (result.has("completion") && !result.nil("completion")) result.o("completion") else null
        if (completion != null && job != null && plan != null) {
            val expected = B2Digests.completion(job, plan, completion.n("objectCount"), completion.s("receiptsRoot"), completion.n("ackToInclusive"))
            if (expected.s("completionDigest") != completion.s("completionDigest")) fail("RECEIPT_CONFLICT")
        }
        when (phase) {
            "prepare" -> if (result.s("step") != p.s("step") || p.s("step") == "confirm" && result.s("preparationDigest") != p.s("preparationDigest")) fail()
            "begin" -> if (result.s("selectionDigest") != p.s("selectionDigest") || result.n("nextManifestPage") > p.a("pageObjectCounts").length() || p.s("intent") == "resume" && result.s("disposition") == "CREATED") fail("PLAN_CONFLICT")
            "manifestPage" -> if (result.n("pageIndex") != p.n("pageIndex") || result.s("pageDigest") != p.s("pageDigest") || result.n("nextManifestPage") != p.n("pageIndex") + 1) fail()
            "putChunk" -> if (result.s("objectId") != p.s("objectId") || result.n("chunkIndex") != p.n("chunkIndex")) fail()
            "complete" -> if (listOf("objectCount", "receiptsRoot", "ackToInclusive").any { result.get(it) != p.get(it) && result.get(it).toString() != p.get(it).toString() }) fail("RECEIPT_CONFLICT")
        }
        return answer
    }
    suspend fun offer(accountScope: String, phoneDeviceId: String, watchDeviceId: String, capabilities: JSONObject, phoneSnapshot: JSONObject, originalWatchSnapshot: JSONObject? = null, verifySource: Boolean = true): JSONObject {
        if (echoed == null) fail("CAPABILITY_MISMATCH")
        // Confirm that this is a durable local witness, never just a live DB count.
        if (verifySource) snapshots.descriptors(accountScope, phoneSnapshot)
        account = accountScope; phone = phoneDeviceId; watch = watchDeviceId
        val p = obj("step" to "offer", "accountScope" to account, "phoneDeviceId" to phone, "watchDeviceId" to watch, "phoneCapabilities" to copy(capabilities), "phoneSnapshot" to copy(phoneSnapshot), "watchSnapshot" to originalWatchSnapshot)
        val reply = call("prepare", null, null, null, p, false); val result = reply.o("result")
        if (B2Digests.capability(result.o("watchCapabilities")) != B2Digests.capability(echoed!!) || B2Digests.offer(p, result.o("watchCapabilities"), result.o("watchSnapshot")) != result.s("offerDigest")) fail("CAPABILITY_MISMATCH")
        if (originalWatchSnapshot != null && B2Digests.snapshot(originalWatchSnapshot) != B2Digests.snapshot(result.o("watchSnapshot"))) fail("PLAN_CONFLICT")
        offer = copy(p); offerReply = copy(result); offerId = reply.s("requestId")
        return copy(result)
    }
    /** Persist both witnesses and job bindings AND install receiver before sending confirm.
     * install must not issue network requests. The caller supplies its LOCAL authorization. */
    suspend fun confirm(selected: List<JSONObject>, install: (B2Authorization) -> Unit) {
        authorize(); val p = offer ?: fail("CAPABILITY_MISMATCH"); val result = offerReply ?: fail()
        B2Wire.bindings(JSONArray(selected))
        selected.forEach { b ->
            val chosen = B2Negotiation.select(p.o("phoneCapabilities"), result.o("watchCapabilities"), b.s("direction"), if (b.s("intent") == "resume") b.o("selection") else null) ?: fail("CAPABILITY_MISMATCH")
            if (B2Digests.selection(chosen) != B2Digests.selection(b.o("selection"))) fail("CAPABILITY_MISMATCH")
        }
        val digest = B2Digests.preparation(result.s("offerDigest"), selected)
        store.exclusive {
            authorize(); store.budget(B2Json.utf8(p.toString()).size.toLong() + B2Json.utf8(result.toString()).size + B2Json.utf8(JSONArray(selected).toString()).size + 4096)
            store.put(account, "preparation", digest, obj("offer" to copy(p), "result" to copy(result), "bindings" to JSONArray(selected.map(::copy))))
        }
        session.confirmBinding(digest, selected.filter { it.s("direction") == "phoneToWatch" }.map { it.s("jobId") })
        bindings = selected.map(::copy)
        install(B2Authorization(account, phone, watch, p.o("phoneSnapshot"), result.o("watchSnapshot"), bindings) { try { authorize(); true } catch (_: Exception) { false } })
        call("prepare", null, null, null, obj("step" to "confirm", "offerRequestId" to offerId, "bindings" to JSONArray(bindings), "preparationDigest" to digest), false)
    }
    private fun validateReceipt(r: JSONObject, job: String, plan: String, oid: String) {
        B2Results.receipt(r)
        if (r.s("jobId") != job || r.s("planDigest") != plan || r.s("objectId") != oid || r.s("receiverDeviceId") != watch) fail("RECEIPT_CONFLICT")
    }
    private suspend fun acceptCompletion(job: String, plan: String, p: JSONObject, c: JSONObject) {
        B2Results.completion(c)
        val expected = B2Digests.completion(job, plan, p.n("objectCount"), c.s("receiptsRoot"), p.o("phoneSnapshot").n("toInclusive"))
        if (c.toString() != expected.toString()) { // Object key order is not a semantic identity.
            if (listOf("state", "objectCount", "receiptsRoot", "ackToInclusive", "completionDigest").any { c.get(it).toString() != expected.get(it).toString() }) fail("RECEIPT_CONFLICT")
        }
        store.exclusive { authorize(); store.transaction { store.put(account, "peerAck", job, copy(c))
            val source = store.get(account, "sourceJob", job) ?: fail("JOURNAL_CORRUPT")
            if (source.s("connection") != connectionId) fail("STALE_EPOCH")
            source.put("state", "COMPLETE"); store.put(account, "sourceJob", job, source); authorize() } }
    }
    /** B2 uses the exact persisted plan and files. Errors are returned to A/B/C
     * orchestration as typed errors; this method never reconnects or downgrades. */
    suspend fun send(job: String): JSONObject {
        var resume = false
        while (true) {
            rejectedChunks = 0
            try { return transfer(job, resume) }
            catch (e: B2Exception) {
                // A multiplexed window can consume several entries of the peer's
                // recovery bucket at once. Give those entries time to refill.
                if (e.code !in setOf("WINDOW_FULL", "BUSY", "LEASE_EXPIRED", "MANIFEST_INCOMPLETE", "CHUNKS_MISSING", "DEPENDENCIES_PENDING")) throw e
                delay(maxOf(waitBackoff.next(e.retryAfterMillis), rejectedChunks * 250L))
                resume = true
                authorize()
            }
        }
    }
    private suspend fun transfer(job: String, resume: Boolean): JSONObject {
        val b = bindings.find { it.s("jobId") == job && it.s("direction") == "phoneToWatch" } ?: fail("AUTH_REVOKED")
        val o = offer!!; val offered = offerReply!!
        val saved = store.get(account, "sourceJob", job)
        var ds: List<JSONObject>? = null
        val p = if (b.s("intent") == "resume" || resume && saved != null && saved.has("epoch")) {
            val old = saved ?: fail("NOT_FOUND"); copy(old.o("description")).put("intent", "resume")
        } else {
            ds = snapshots.descriptors(account, o.o("phoneSnapshot")).filter { d -> b.o("selection").a("kinds").values().any { (it as JSONObject).s("kind") == d.s("kind") } }
            val admission = B2Planner.preflight("merge", b.o("selection"), ds!!)
            val counts = when (admission) {
                is B2Planner.Admission.Ready -> admission.pageObjectCounts
                is B2Planner.Admission.UseLegacy -> fail("LIMIT_EXCEEDED") // Caller preflights BEFORE route commitment.
            }
            obj("intent" to "create", "accountScope" to account, "phoneDeviceId" to phone, "watchDeviceId" to watch, "direction" to "phoneToWatch", "phoneSnapshot" to o.o("phoneSnapshot"), "watchSnapshot" to offered.o("watchSnapshot"), "selection" to b.o("selection"), "selectionDigest" to B2Digests.selection(b.o("selection")), "objectCount" to ds!!.size, "objectsRoot" to B2Digests.objects(ds!!.map { it.s("objectId") }), "pageObjectCounts" to JSONArray(counts))
        }
        B2Wire.payload("begin", p)
        val plan = B2Digests.plan(p)
        if (saved?.optString("state") == "CANCELLED") fail("JOB_CANCELLED")
        if (saved != null && (saved.s("planDigest") != plan || saved.o("description").a("pageObjectCounts").toString() != p.a("pageObjectCounts").toString())) fail("JOB_CONFLICT")
        // Save before begin so ACK loss can always be recovered with intent=resume.
        store.exclusive { authorize(); if (saved == null) { store.budget(B2Json.utf8(p.toString()).size.toLong() + 2048); store.put(account, "sourceJob", job, obj("planDigest" to plan, "description" to copy(p))) } }
        val ready = CompletableDeferred<Unit>(); sourceBegins[job] = ready
        val began = try {
            val response = call("begin", job, plan, null, p)
            store.exclusive {
                authorize(); val local = store.get(account, "sourceJob", job) ?: fail("JOURNAL_CORRUPT")
                local.put("epoch", response.n("epoch")).put("connection", connectionId).put("state", response.o("result").s("state"))
                store.put(account, "sourceJob", job, local)
            }
            ready.complete(Unit); response
        } catch (e: Throwable) { ready.completeExceptionally(e); throw e }
        finally { sourceBegins.remove(job, ready) }
        val epoch = began.n("epoch"); val br = began.o("result")
        val limits = p.o("selection").o("limits")
        val window = limits.n("maxInFlightChunks").toInt()
        session.bindSource(1, job, plan, p.s("selectionDigest"), epoch, window, limits.n("maxInFlightBytes").toInt())
        if (br.s("state") == "CANCELLED") fail("JOB_CANCELLED")
        if (br.s("state") == "COMPLETE") { val c = br.o("completion"); acceptCompletion(job, plan, p, c); session.finishSource(job); return c }
        try { ds = ds ?: snapshots.descriptors(account, p.o("phoneSnapshot")).filter { d -> b.o("selection").a("kinds").values().any { (it as JSONObject).s("kind") == d.s("kind") } } }
        catch (e: B2Exception) { if (e.code == "SOURCE_CHANGED") cancel(job, plan, epoch, "SOURCE_CHANGED"); throw e }
        val objects = ds!!; sourceObjects = objects; B2Receiver.validateGraph(objects)
        if (B2Digests.objects(objects.map { it.s("objectId") }) != p.s("objectsRoot")) fail("SOURCE_CHANGED")
        session.transferObserver?.let { observer ->
            objects.forEach { observer.binaryDescriptor(it.s("objectId"), it.s("kind"), incoming = false) }
        }
        var start = 0; var previous = "0".repeat(64)
        p.a("pageObjectCounts").values().forEachIndexed { index, n ->
            val count = B2Json.integer(n).toInt(); val entries = objects.subList(start, start + count)
            val page = obj("pageIndex" to index, "startIndex" to start, "previousPageDigest" to previous, "pageDigest" to "0".repeat(64), "entries" to JSONArray(entries))
            page.put("pageDigest", B2Digests.page(plan, page))
            if (index.toLong() >= br.n("nextManifestPage")) call("manifestPage", job, plan, epoch, page)
            previous = page.s("pageDigest"); start += count
        }
        val observed = mutableMapOf<String, MutableList<Pair<Int, Int>>>(); val observedStates = mutableMapOf<String, String>(); val applied = mutableMapOf<String, JSONObject>()
        var cursor = obj("afterObjectId" to null, "afterChunkIndex" to null, "limit" to b.o("selection").o("limits").n("maxResultsPerPage"))
        val seenCursors = HashSet<String>()
        while (true) {
            if (!seenCursors.add(cursor.toString())) fail("PLAN_CONFLICT")
            val result = missing(job, plan, epoch, cursor)
            if (result.a("items").length().toLong() > cursor.n("limit")) fail()
            for (raw in result.a("items").values()) {
                val item = raw as JSONObject; val d = objects.find { it.s("objectId") == item.s("objectId") } ?: fail("RECEIPT_CONFLICT")
                observedStates[d.s("objectId")] = item.s("state")
                val ranges = observed.getOrPut(d.s("objectId")) { mutableListOf() }
                item.a("missingRanges").values().forEach { v ->
                    val a = v as JSONArray; val from = a.getInt(0); val to = a.getInt(1)
                    if (to > d.a("chunkHashes").length() || ranges.lastOrNull()?.second?.let { from < it } == true) fail()
                    ranges.add(from to to)
                }
                if (!item.nil("receipt")) { val rc = item.o("receipt"); validateReceipt(rc, job, plan, d.s("objectId")); applied[d.s("objectId")] = rc; progress("phoneToWatch:" + B2Progress.applied(d)) }
            }
            if (result.getBoolean("done")) break
            val next = result.o("next")
            val descriptor = objects.find { it.s("objectId") == next.s("afterObjectId") } ?: fail()
            if (!next.nil("afterChunkIndex") && next.n("afterChunkIndex") >= descriptor.a("chunkHashes").length()) fail()
            if (!cursor.nil("afterObjectId")) {
                val previousId = cursor.s("afterObjectId"); val nextId = next.s("afterObjectId")
                if (nextId < previousId || nextId == previousId && (cursor.nil("afterChunkIndex") || !next.nil("afterChunkIndex") && next.n("afterChunkIndex") <= cursor.n("afterChunkIndex"))) fail()
            }
            cursor = copy(next).put("limit", cursor.n("limit"))
        }
        // The complete missing report confirms durable chunks even if the old ACK was lost.
        // The observer attributes recovered outbound chunks to the session that sent them.
        val resumeObserver = session.transferObserver.takeIf { p.s("intent") == "resume" }
        for (d in objects) {
            val id = d.s("objectId")
            val alreadyApplied = id in applied
            if (alreadyApplied && resumeObserver == null) continue
            val ranges = observed[id] ?: fail("RECEIPT_CONFLICT")
            for (index in 0 until d.a("chunkHashes").length()) {
                if (ranges.any { (from, to) -> index in from until to }) continue
                if (resumeObserver != null) {
                    val length = minOf(B2Wire.CHUNK.toLong(), d.n("size") - index * B2Wire.CHUNK.toLong())
                    resumeObserver.confirmResumedOutboundChunk(id, index, length.toInt())
                }
                if (!alreadyApplied) progress("phoneToWatch:" + B2Progress.chunk(d, index))
            }
        }
        while (applied.size < objects.size) {
            val m = b.o("selection").o("limits").n("maxInFlightObjects").toInt()
            val slots = if (bindings.size == 2) (m + 1) / 2 else m
            val ready = B2Scheduling.runnable(objects, applied.keys, observedStates, slots)
            for (d in ready) {
                val ranges = observed[d.s("objectId")] ?: fail("RECEIPT_CONFLICT")
                try {
                    transferChunks(job, plan, epoch, p.o("phoneSnapshot"), d, ranges, window)
                } catch (e: B2Exception) {
                    if (e.code == "SOURCE_CHANGED") cancel(job, plan, epoch, "SOURCE_CHANGED")
                    throw e
                }
                val rc = apply(job, plan, epoch, d.s("objectId")); validateReceipt(rc, job, plan, d.s("objectId")); applied[d.s("objectId")] = rc
            }
        }
        val c = call("complete", job, plan, epoch, obj("objectCount" to objects.size, "receiptsRoot" to B2Digests.receipts(objects.map { applied.getValue(it.s("objectId")) }), "ackToInclusive" to p.o("phoneSnapshot").n("toInclusive"))).o("result")
        acceptCompletion(job, plan, p, c); session.finishSource(job); progress("complete:phoneToWatch"); return c
    }
    /** Session routing seam: watch may cancel a job for which the phone is source. */
    suspend fun handleRequest(frame: JSONObject, receiver: B2Receiver): JSONObject {
        if (bindings.any { it.s("jobId") == frame.optString("jobId") && it.s("direction") == "phoneToWatch" }) return handleSourceCancel(frame)
        return receiver.handle(frame)
    }
    suspend fun handleSourceCancel(frame: JSONObject): JSONObject {
        // A cancel can follow the begin reply before its waiter resumes. Wait OUTSIDE
        // the DB lane so the source can persist the receiver-granted epoch first.
        sourceBegins[frame.optString("jobId")]?.await()
        return store.exclusive {
        val r = B2Wire.envelope(B2Json.parse(B2Json.utf8(frame.toString()))).also { B2Wire.retainSize(it, B2Wire.frameBytes(frame)) }
        authorize()
        if (r.s("type") != "request" || r.s("phase") != "cancel" || bindings.none { it.s("jobId") == r.s("jobId") && it.s("direction") == "phoneToWatch" }) fail()
        val binding = bindings.first { it.s("jobId") == r.s("jobId") }
        if (B2Wire.frameBytes(r) > binding.o("selection").o("limits").n("maxFrameBytes")) fail("LIMIT_EXCEEDED")
        if (r.o("payload").s("reason") == "SOURCE_CHANGED") fail()
        val job = r.s("jobId"); val source = store.get(account, "sourceJob", job) ?: fail("NOT_FOUND")
        if (source.s("connection") != connectionId || source.n("epoch") != r.n("epoch")) fail("STALE_EPOCH")
        if (source.s("planDigest") != r.s("planDigest")) fail("JOB_CONFLICT")
        val key = B2Digests.d("wrss.library-binary.v2/source-cancel", listOf(job, watch, r.s("requestId")))
        val old = store.get(account, "request", key)
        if (old != null && old.s("digest") != r.s("requestDigest")) fail("REQUEST_CONFLICT")
        val complete = store.get(account, "peerAck", job) != null
        val result = obj("state" to if (complete) "COMPLETE" else "CANCELLED", "alreadyComplete" to complete)
        store.transaction {
            authorize()
            if (old == null) store.budget(1024)
            store.put(account, "request", key, obj("digest" to r.s("requestDigest"), "result" to result))
            source.put("state", result.s("state")); store.put(account, "sourceJob", job, source)
        }
        if (!complete) { cancelledSources.add(job); onSourceCancelled(job) }
        B2Results.answer(r, result, r.n("epoch"))
        }
    }
    suspend fun missing(job: String, plan: String, epoch: Long, cursor: JSONObject = obj("afterObjectId" to null, "afterChunkIndex" to null, "limit" to 0)): JSONObject {
        val result = call("missing", job, plan, epoch, cursor, false).o("result")
        if (result.a("items").length().toLong() > cursor.n("limit") || cursor.n("limit") == 0L && (!result.nil("next") || !result.getBoolean("done"))) fail()
        return result
    }
    /** Retire a frozen source plan on a NEW authenticated connection. No source bytes
     * are read; terminal/absent plans can be retired after their spool is lost. */
    suspend fun retire(job: String, reason: String = "LOCAL_ABORT") {
        val source = store.get(account, "sourceJob", job) ?: return
        val p = copy(source.o("description")).put("intent", "resume")
        val plan = source.s("planDigest")
        val began = call("begin", job, plan, null, p)
        if (began.o("result").s("state") !in listOf("COMPLETE", "CANCELLED")) {
            val epoch = began.n("epoch")
            val limits = p.o("selection").o("limits")
            session.bindSource(1, job, plan, p.s("selectionDigest"), epoch,
                limits.n("maxInFlightChunks").toInt(), limits.n("maxInFlightBytes").toInt())
            cancel(job, plan, epoch, reason)
            session.finishSource(job, completed = false)
        }
        store.exclusive { store.transaction {
            source.put("state", if (began.o("result").s("state") == "COMPLETE") "COMPLETE" else "CANCELLED")
            store.put(account, "sourceJob", job, source)
        } }
    }
    /** Payload ownership ends at the writer. The source only retains settlement
     * handles and waits for any completion, so a selective ACK returns credit. */
    private suspend fun transferChunks(job: String, plan: String, epoch: Long, snapshot: JSONObject,
        descriptor: JSONObject, ranges: List<Pair<Int, Int>>, window: Int) {
        val oid = descriptor.s("objectId")
        val slot = sourceObjects.indexOfFirst { it.s("objectId") == oid }
        if (slot < 0) fail()
        val indices = ranges.asSequence().flatMap { (from, to) -> (from until to).asSequence() }.iterator()
        val queued = linkedMapOf<Deferred<Int>, Int>()
        var error: B2Exception? = null
        while (queued.isNotEmpty() || error == null && indices.hasNext()) {
            if (queued.isNotEmpty()) {
                val settled = queued.keys.firstOrNull { it.isCompleted }
                    ?: if (queued.size == window || error != null || !indices.hasNext()) {
                        select<Deferred<Int>> { for (pending in queued.keys) pending.onJoin { pending } }
                    } else null
                if (settled != null) {
                    val index = queued.remove(settled)!!
                    try {
                        if (settled.await() == 1) progress("phoneToWatch:" + B2Progress.chunk(descriptor, index))
                    } catch (e: B2Exception) {
                        val recovery = B2Results.errorValue(e.code).s("recovery")
                        if (recovery !in listOf("QUERY", "WAIT", "BEGIN")) throw e
                        rejectedChunks++
                        val previous = error
                        if (previous == null || e.code == "LEASE_EXPIRED" ||
                            previous.code != "LEASE_EXPIRED" && e.retryAfterMillis > previous.retryAfterMillis) error = e
                    }
                    continue
                }
            }
            val index = indices.next()
            val request = chunkRequest(job, plan, epoch, oid, index)
            val ref = snapshots.range(account, snapshot, descriptor, index)
            queued[session.queueFile(request, slot, ref, descriptor.a("chunkHashes").getString(index))] = index
        }
        error?.let { throw it }
        waitBackoff.reset()
    }

    suspend fun put(job: String, plan: String, epoch: Long, oid: String, index: Int, bytes: ByteArray) {
        val slot = sourceObjects.indexOfFirst { it.s("objectId") == oid }
        if (slot < 0) fail()
        val disposition = session.sendData(chunkRequest(job, plan, epoch, oid, index), slot, bytes)
        if (disposition == 1) progress("phoneToWatch:" + B2Progress.chunk(sourceObjects[slot], index))
        waitBackoff.reset()
    }

    private suspend fun chunkRequest(job: String, plan: String, epoch: Long, oid: String, index: Int): JSONObject {
        val payload = obj("objectId" to oid, "chunkIndex" to index)
        val digest = B2Digests.request(job, plan, "putChunk", payload)
        val id = B2Digests.d("wrss.library-binary.v2/source-request", listOf(job, digest)).take(32)
        // Persist operation binding before emitting DATA. Retries and reconnects retain it.
        store.exclusive {
            authorize()
            val key = "$job:$oid:$index"
            val old = store.get(account, "sourceChunk", key)
            if (old != null && (old.s("requestDigest") != digest || old.s("requestId") != id)) fail("JOURNAL_CORRUPT")
            if (old == null) {
                store.budget(1024)
                store.put(account, "sourceChunk", key, obj("requestId" to id, "requestDigest" to digest))
            }
        }
        return B2Wire.request("putChunk", job, plan, epoch, payload, id)
    }

    suspend fun apply(job: String, plan: String, epoch: Long, oid: String): JSONObject {
        val r = call("apply", job, plan, epoch, obj("objectId" to oid)).o("result").o("receipt")
        validateReceipt(r, job, plan, oid)
        sourceObjects.find { it.s("objectId") == oid }?.let { progress("phoneToWatch:" + B2Progress.applied(it)) }; return r
    }
    suspend fun cancel(job: String, plan: String, epoch: Long, reason: String) = call("cancel", job, plan, epoch, obj("reason" to reason)).o("result")
}
