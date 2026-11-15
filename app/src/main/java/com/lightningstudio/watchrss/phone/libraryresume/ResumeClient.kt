package com.lightningstudio.watchrss.phone.libraryresume

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/** Protocol client only. Transport/account/Ready/A-B controller are intentionally outside.
 * The exchange port must route reverse requests concurrently (ResumeSession does this). */
class ResumeClient(
    private val store: ResumePersistence, private val snapshots: ResumeSnapshots,
    private val exchange: suspend (JSONObject) -> JSONObject,
    private val authorize: () -> Unit,
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
    private var account = ""
    private var phone = ""
    private var watch = ""
    /** Must be called separately for EACH newly authenticated connection. */
    fun acceptProbe(probe: JSONObject): Boolean {
        generation++
        echoed = ResumeNegotiation.echoed(probe); offer = null; offerReply = null; offerId = null; bindings = emptyList()
        return echoed != null
    }
    private suspend fun call(phase: String, job: String?, plan: String?, epoch: Long?, p: JSONObject, stable: Boolean = true): JSONObject {
        val connectionGeneration = generation
        authorize()
        if (job != null && job in cancelledSources && phase != "cancel") fail("JOB_CANCELLED")
        if (echoed == null) fail("CAPABILITY_MISMATCH")
        val digest = ResumeDigests.request(job, plan, phase, p)
        val id = if (stable) ResumeDigests.d("wrss.library-resume.v1/source-request", listOf(job ?: "", digest)).take(32) else java.util.UUID.randomUUID().toString().replace("-", "")
        val request = ResumeWire.request(phase, job, plan, epoch, p, id)
        val answer = ResumeWire.envelope(exchange(request))
        val maxFrame = bindings.find { it.s("jobId") == job }?.o("selection")?.o("limits")?.n("maxFrameBytes") ?: 262144L
        if (ResumeWire.frameBytes(answer) > maxFrame) fail("LIMIT_EXCEEDED")
        if (generation != connectionGeneration) fail("STALE_EPOCH")
        authorize(); if (job != null && job in cancelledSources && phase != "cancel") fail("JOB_CANCELLED")
        ResumeSession.validateCorrelation(request, answer)
        if (!answer.getBoolean("success")) fail(answer.o("error").s("code"))
        val result = answer.o("result")
        val completion = if (phase == "complete") result else if (result.has("completion") && !result.nil("completion")) result.o("completion") else null
        if (completion != null && job != null && plan != null) {
            val expected = ResumeDigests.completion(job, plan, completion.n("objectCount"), completion.s("receiptsRoot"), completion.n("ackToInclusive"))
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
    suspend fun offer(accountScope: String, phoneDeviceId: String, watchDeviceId: String, capabilities: JSONObject, phoneSnapshot: JSONObject, originalWatchSnapshot: JSONObject? = null): JSONObject {
        if (echoed == null) fail("CAPABILITY_MISMATCH")
        // Confirm that this is a durable local witness, never just a live DB count.
        snapshots.descriptors(accountScope, phoneSnapshot)
        account = accountScope; phone = phoneDeviceId; watch = watchDeviceId
        val p = obj("step" to "offer", "accountScope" to account, "phoneDeviceId" to phone, "watchDeviceId" to watch, "phoneCapabilities" to copy(capabilities), "phoneSnapshot" to copy(phoneSnapshot), "watchSnapshot" to originalWatchSnapshot)
        val reply = call("prepare", null, null, null, p, false); val result = reply.o("result")
        if (ResumeDigests.capability(result.o("watchCapabilities")) != ResumeDigests.capability(echoed!!) || ResumeDigests.offer(p, result.o("watchCapabilities"), result.o("watchSnapshot")) != result.s("offerDigest")) fail("CAPABILITY_MISMATCH")
        if (originalWatchSnapshot != null && ResumeDigests.snapshot(originalWatchSnapshot) != ResumeDigests.snapshot(result.o("watchSnapshot"))) fail("PLAN_CONFLICT")
        offer = copy(p); offerReply = copy(result); offerId = reply.s("requestId")
        return copy(result)
    }
    /** Persist both witnesses and job bindings AND install receiver before sending confirm.
     * install must not issue network requests. The caller supplies its LOCAL authorization. */
    suspend fun confirm(selected: List<JSONObject>, install: (ResumeAuthorization) -> Unit) {
        authorize(); val p = offer ?: fail("CAPABILITY_MISMATCH"); val result = offerReply ?: fail()
        ResumeWire.bindings(JSONArray(selected))
        selected.forEach { b ->
            val chosen = ResumeNegotiation.select(p.o("phoneCapabilities"), result.o("watchCapabilities"), b.s("direction"), if (b.s("intent") == "resume") b.o("selection") else null) ?: fail("CAPABILITY_MISMATCH")
            if (ResumeDigests.selection(chosen) != ResumeDigests.selection(b.o("selection"))) fail("CAPABILITY_MISMATCH")
        }
        val digest = ResumeDigests.preparation(result.s("offerDigest"), selected)
        store.exclusive {
            authorize(); store.budget(ResumeJson.utf8(p.toString()).size.toLong() + ResumeJson.utf8(result.toString()).size + ResumeJson.utf8(JSONArray(selected).toString()).size + 4096)
            store.put(account, "preparation", digest, obj("offer" to copy(p), "result" to copy(result), "bindings" to JSONArray(selected.map(::copy))))
        }
        bindings = selected.map(::copy)
        install(ResumeAuthorization(account, phone, watch, p.o("phoneSnapshot"), result.o("watchSnapshot"), bindings) { try { authorize(); true } catch (_: Exception) { false } })
        call("prepare", null, null, null, obj("step" to "confirm", "offerRequestId" to offerId, "bindings" to JSONArray(bindings), "preparationDigest" to digest), false)
    }
    private fun validateReceipt(r: JSONObject, job: String, plan: String, oid: String) {
        ResumeResults.receipt(r)
        if (r.s("jobId") != job || r.s("planDigest") != plan || r.s("objectId") != oid || r.s("receiverDeviceId") != watch) fail("RECEIPT_CONFLICT")
    }
    private suspend fun acceptCompletion(job: String, plan: String, p: JSONObject, c: JSONObject) {
        ResumeResults.completion(c)
        val expected = ResumeDigests.completion(job, plan, p.n("objectCount"), c.s("receiptsRoot"), p.o("phoneSnapshot").n("toInclusive"))
        if (c.toString() != expected.toString()) { // Object key order is not a semantic identity.
            if (listOf("state", "objectCount", "receiptsRoot", "ackToInclusive", "completionDigest").any { c.get(it).toString() != expected.get(it).toString() }) fail("RECEIPT_CONFLICT")
        }
        store.exclusive { authorize(); store.transaction { store.put(account, "peerAck", job, copy(c))
            val source = store.get(account, "sourceJob", job) ?: fail("JOURNAL_CORRUPT")
            if (source.s("connection") != connectionId) fail("STALE_EPOCH")
            source.put("state", "COMPLETE"); store.put(account, "sourceJob", job, source); authorize() } }
    }
    /** Resume uses the exact persisted plan and files. Errors are returned to A/B/C
     * orchestration as typed errors; this method never reconnects or downgrades. */
    suspend fun send(job: String): JSONObject {
        while (true) {
            try { return transfer(job) }
            catch (e: ResumeException) {
                // In-connection state recovery is NOT a disconnect/reconnect attempt.
                when (e.code) {
                    "WINDOW_FULL", "BUSY" -> delay(100)
                    "LEASE_EXPIRED", "MANIFEST_INCOMPLETE", "CHUNKS_MISSING", "DEPENDENCIES_PENDING" -> Unit
                    else -> throw e
                }
                authorize()
            }
        }
    }
    private suspend fun transfer(job: String): JSONObject {
        val b = bindings.find { it.s("jobId") == job && it.s("direction") == "phoneToWatch" } ?: fail("AUTH_REVOKED")
        val o = offer!!; val offered = offerReply!!
        val saved = store.get(account, "sourceJob", job)
        var ds: List<JSONObject>? = null
        val p = if (b.s("intent") == "resume") {
            val old = saved ?: fail("NOT_FOUND"); copy(old.o("description")).put("intent", "resume")
        } else {
            ds = snapshots.descriptors(account, o.o("phoneSnapshot")).filter { d -> b.o("selection").a("kinds").values().any { (it as JSONObject).s("kind") == d.s("kind") } }
            val admission = ResumePlanner.preflight("merge", b.o("selection"), ds!!)
            val counts = when (admission) {
                is ResumePlanner.Admission.Ready -> admission.pageObjectCounts
                is ResumePlanner.Admission.UseLegacy -> fail("LIMIT_EXCEEDED") // Caller preflights BEFORE route commitment.
            }
            obj("intent" to "create", "accountScope" to account, "phoneDeviceId" to phone, "watchDeviceId" to watch, "direction" to "phoneToWatch", "phoneSnapshot" to o.o("phoneSnapshot"), "watchSnapshot" to offered.o("watchSnapshot"), "selection" to b.o("selection"), "selectionDigest" to ResumeDigests.selection(b.o("selection")), "objectCount" to ds!!.size, "objectsRoot" to ResumeDigests.objects(ds!!.map { it.s("objectId") }), "pageObjectCounts" to JSONArray(counts))
        }
        ResumeWire.payload("begin", p)
        val plan = ResumeDigests.plan(p)
        if (saved?.optString("state") == "CANCELLED") fail("JOB_CANCELLED")
        if (saved != null && (saved.s("planDigest") != plan || saved.o("description").a("pageObjectCounts").toString() != p.a("pageObjectCounts").toString())) fail("JOB_CONFLICT")
        // Save before begin so ACK loss can always be recovered with intent=resume.
        store.exclusive { authorize(); if (saved == null) { store.budget(ResumeJson.utf8(p.toString()).size.toLong() + 2048); store.put(account, "sourceJob", job, obj("planDigest" to plan, "description" to copy(p))) } }
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
        if (br.s("state") == "CANCELLED") fail("JOB_CANCELLED")
        if (br.s("state") == "COMPLETE") { val c = br.o("completion"); acceptCompletion(job, plan, p, c); return c }
        try { ds = ds ?: snapshots.descriptors(account, p.o("phoneSnapshot")).filter { d -> b.o("selection").a("kinds").values().any { (it as JSONObject).s("kind") == d.s("kind") } } }
        catch (e: ResumeException) { if (e.code == "SOURCE_CHANGED") cancel(job, plan, epoch, "SOURCE_CHANGED"); throw e }
        val objects = ds!!; ResumeReceiver.validateGraph(objects)
        if (ResumeDigests.objects(objects.map { it.s("objectId") }) != p.s("objectsRoot")) fail("SOURCE_CHANGED")
        var start = 0; var previous = "0".repeat(64)
        p.a("pageObjectCounts").values().forEachIndexed { index, n ->
            val count = ResumeJson.integer(n).toInt(); val entries = objects.subList(start, start + count)
            val page = obj("pageIndex" to index, "startIndex" to start, "previousPageDigest" to previous, "pageDigest" to "0".repeat(64), "entries" to JSONArray(entries))
            page.put("pageDigest", ResumeDigests.page(plan, page))
            if (index.toLong() >= br.n("nextManifestPage")) call("manifestPage", job, plan, epoch, page)
            previous = page.s("pageDigest"); start += count
        }
        val observed = mutableMapOf<String, MutableList<Pair<Int, Int>>>(); val applied = mutableMapOf<String, JSONObject>()
        var cursor = obj("afterObjectId" to null, "afterChunkIndex" to null, "limit" to b.o("selection").o("limits").n("maxResultsPerPage"))
        val seenCursors = HashSet<String>()
        while (true) {
            if (!seenCursors.add(cursor.toString())) fail("PLAN_CONFLICT")
            val result = missing(job, plan, epoch, cursor)
            if (result.a("items").length().toLong() > cursor.n("limit")) fail()
            for (raw in result.a("items").values()) {
                val item = raw as JSONObject; val d = objects.find { it.s("objectId") == item.s("objectId") } ?: fail("RECEIPT_CONFLICT")
                val ranges = observed.getOrPut(d.s("objectId")) { mutableListOf() }
                item.a("missingRanges").values().forEach { v ->
                    val a = v as JSONArray; val from = a.getInt(0); val to = a.getInt(1)
                    if (to > d.a("chunkHashes").length() || ranges.lastOrNull()?.second?.let { from < it } == true) fail()
                    ranges.add(from to to)
                }
                if (!item.nil("receipt")) { val rc = item.o("receipt"); validateReceipt(rc, job, plan, d.s("objectId")); applied[d.s("objectId")] = rc }
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
        while (applied.size < objects.size) {
            val ready = objects.filter { it.s("objectId") !in applied && it.a("dependsOn").strings().all { id -> id in applied } }.sortedBy { it.s("objectId") }
            if (ready.isEmpty()) fail("PLAN_CONFLICT")
            for (d in ready) {
                val ranges = observed[d.s("objectId")] ?: fail("RECEIPT_CONFLICT")
                for ((from, to) in ranges) for (index in from until to) {
                    val bytes = try { withHeartbeat(job, plan, epoch) { snapshots.read(account, p.o("phoneSnapshot"), d, index) } } catch (e: ResumeException) { if (e.code == "SOURCE_CHANGED") cancel(job, plan, epoch, "SOURCE_CHANGED"); throw e }
                    put(job, plan, epoch, d.s("objectId"), index, bytes)
                }
                val rc = apply(job, plan, epoch, d.s("objectId")); validateReceipt(rc, job, plan, d.s("objectId")); applied[d.s("objectId")] = rc
            }
        }
        val c = call("complete", job, plan, epoch, obj("objectCount" to objects.size, "receiptsRoot" to ResumeDigests.receipts(objects.map { applied.getValue(it.s("objectId")) }), "ackToInclusive" to p.o("phoneSnapshot").n("toInclusive"))).o("result")
        acceptCompletion(job, plan, p, c); return c
    }
    /** Session routing seam: watch may cancel a job for which the phone is source. */
    suspend fun handleRequest(frame: JSONObject, receiver: ResumeReceiver): JSONObject {
        if (bindings.any { it.s("jobId") == frame.optString("jobId") && it.s("direction") == "phoneToWatch" }) return handleSourceCancel(frame)
        return receiver.handle(frame)
    }
    suspend fun handleSourceCancel(frame: JSONObject): JSONObject {
        // A cancel can follow the begin reply before its waiter resumes. Wait OUTSIDE
        // the DB lane so the source can persist the receiver-granted epoch first.
        sourceBegins[frame.optString("jobId")]?.await()
        return store.exclusive {
        val r = ResumeWire.envelope(ResumeJson.parse(ResumeJson.utf8(frame.toString()))).also { ResumeWire.retainSize(it, ResumeWire.frameBytes(frame)) }
        authorize()
        if (r.s("type") != "request" || r.s("phase") != "cancel" || bindings.none { it.s("jobId") == r.s("jobId") && it.s("direction") == "phoneToWatch" }) fail()
        val binding = bindings.first { it.s("jobId") == r.s("jobId") }
        if (ResumeWire.frameBytes(r) > binding.o("selection").o("limits").n("maxFrameBytes")) fail("LIMIT_EXCEEDED")
        if (r.o("payload").s("reason") == "SOURCE_CHANGED") fail()
        val job = r.s("jobId"); val source = store.get(account, "sourceJob", job) ?: fail("NOT_FOUND")
        if (source.s("connection") != connectionId || source.n("epoch") != r.n("epoch")) fail("STALE_EPOCH")
        if (source.s("planDigest") != r.s("planDigest")) fail("JOB_CONFLICT")
        val key = ResumeDigests.d("wrss.library-resume.v1/source-cancel", listOf(job, watch, r.s("requestId")))
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
        ResumeResults.answer(r, result, r.n("epoch"))
        }
    }
    private suspend fun <T> withHeartbeat(job: String, plan: String, epoch: Long, block: () -> T): T = coroutineScope {
        val work = async(Dispatchers.IO) { block() }
        while (!work.isCompleted) {
            val done = withTimeoutOrNull(8000) { work.await(); true }
            if (done == null) missing(job, plan, epoch)
        }
        work.await()
    }
    suspend fun missing(job: String, plan: String, epoch: Long, cursor: JSONObject = obj("afterObjectId" to null, "afterChunkIndex" to null, "limit" to 0)): JSONObject {
        val result = call("missing", job, plan, epoch, cursor, false).o("result")
        if (result.a("items").length().toLong() > cursor.n("limit") || cursor.n("limit") == 0L && (!result.nil("next") || !result.getBoolean("done"))) fail()
        return result
    }
    suspend fun put(job: String, plan: String, epoch: Long, oid: String, index: Int, bytes: ByteArray) = call("putChunk", job, plan, epoch, obj("objectId" to oid, "chunkIndex" to index, "dataBase64" to Base64.getEncoder().encodeToString(bytes))).o("result")
    suspend fun apply(job: String, plan: String, epoch: Long, oid: String) = call("apply", job, plan, epoch, obj("objectId" to oid)).o("result").o("receipt")
    suspend fun cancel(job: String, plan: String, epoch: Long, reason: String) = call("cancel", job, plan, epoch, obj("reason" to reason)).o("result")
}
