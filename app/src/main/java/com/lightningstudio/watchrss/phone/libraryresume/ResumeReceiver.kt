package com.lightningstudio.watchrss.phone.libraryresume

import com.lightningstudio.watchrss.phone.data.db.PhoneCompanionDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Adapter methods MUST use only the supplied business DB transaction. No detached work,
 * nested database, KV writes, or network calls. Files are published immutably before apply.
 * P6 protected inputs must reject here until their real business transaction can commit. */
interface ResumeBusinessPort {
    val kinds: Set<String>
    suspend fun apply(db: PhoneCompanionDatabase, descriptor: JSONObject, metadata: JSONObject, staged: File): String
    fun canCommitRange(description: JSONObject): Boolean = false
    suspend fun complete(db: PhoneCompanionDatabase, description: JSONObject, completion: JSONObject) {}
}

/** Construct only after LOCAL consent + current account/peer verification + successful
 * prepare confirmation. The controller owns consent; begin cannot manufacture it. */
class ResumeAuthorization(
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
class ResumeReceiver(
    private val store: ResumePersistence, private val files: ResumeFiles,
    private val auth: ResumeAuthorization, private val business: ResumeBusinessPort,
    private val monotonic: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val fault: (String) -> Unit = {}
) {
    private val connection = java.util.UUID.randomUUID().toString()
    private var revoked = false
    private val leases = mutableMapOf<String, Long>()
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
    private fun key(vararg p: String) = ResumeDigests.d("wrss.library-resume.v1/record-key", p.toList())
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
        j.put("expiresAtMillis", maxOf(j.n("expiresAtMillis"), now + ResumeWire.RETENTION))
        store.put(scope, "job", j.s("jobId"), j)
        leases[j.s("jobId")] = monotonic() + ResumeWire.LEASE
    }
    private fun fence(j: JSONObject, r: JSONObject) {
        check()
        if (j.s("connection") != connection || !leases.containsKey(j.s("jobId")) || j.n("epoch") != r.n("epoch")) fail("STALE_EPOCH")
        if (monotonic() >= leases.getValue(j.s("jobId"))) fail("LEASE_EXPIRED")
        if (j.s("planDigest") != r.s("planDigest")) fail("PLAN_CONFLICT")
    }
    private suspend fun bind(j: JSONObject, r: JSONObject, result: JSONObject? = null): JSONObject? {
        val id = key(j.s("jobId"), auth.watchDeviceId, r.s("requestId"))
        val old = store.get(scope, "request", id)
        if (old != null && old.s("digest") != r.s("requestDigest")) fail("REQUEST_CONFLICT")
        if (old == null) { store.budget(1024, lim(j).n("maxJournalBytes")); store.put(scope, "request", id, obj("digest" to r.s("requestDigest"), "result" to result)) }
        else if (result != null) store.put(scope, "request", id, obj("digest" to r.s("requestDigest"), "result" to result))
        return old?.takeUnless { it.nil("result") }?.o("result")
    }
    /** Throws typed errors for the dispatcher to encode. Transport I/O remains distinct. */
    suspend fun handle(frame: JSONObject): JSONObject = try { store.exclusive {
        val r = ResumeWire.envelope(ResumeJson.parse(ResumeJson.utf8(frame.toString()))).also { ResumeWire.retainSize(it, ResumeWire.frameBytes(frame)) }
        if (r.s("type") != "request" || r.s("phase") == "prepare") fail()
        check()
        val now = clock()
        if (r.s("phase") == "begin") return@exclusive begin(r, now)
        val j = job(r.s("jobId")); fence(j, r)
        if (ResumeWire.frameBytes(r) > lim(j).n("maxFrameBytes")) fail("LIMIT_EXCEEDED")
        // Phase-specific limits apply before request/page receipt adoption too.
        if (r.s("phase") == "manifestPage" && ResumeWire.frameBytes(r) > lim(j).n("maxManifestPageBytes")) fail("LIMIT_EXCEEDED")
        if (j.s("state") == "CANCELLED" && r.s("phase") !in listOf("missing", "cancel")) fail("JOB_CANCELLED")
        if (j.s("state") == "PLANNING" && r.s("phase") in listOf("putChunk", "apply")) fail("MANIFEST_INCOMPLETE")
        // Wire payload validation always precedes deduplication. put additionally checks bytes.
        if (r.s("phase") == "putChunk") validateChunk(j, r.o("payload"))
        val previous = bind(j, r)
        touch(j, now)
        val result = when (r.s("phase")) {
            "missing" -> missing(j, r.o("payload")) // Queries return current verified observations.
            "putChunk" -> put(j, r)
            "apply" -> apply(j, r)
            "manifestPage" -> previous ?: manifest(j, r)
            "complete" -> complete(j, r)
            "cancel" -> cancel(j, r)
            else -> fail()
        }
        check()
        ResumeResults.answer(r, result, j.n("epoch"))
    } } catch (_: java.io.IOException) { fail("LOCAL_IO") }
      catch (_: android.database.SQLException) { fail("LOCAL_IO") }
    private suspend fun begin(r: JSONObject, now: Long): JSONObject {
        val p = r.o("payload"); val id = r.s("jobId")
        val b = auth.bindings.find { it.s("jobId") == id } ?: fail("AUTH_REVOKED")
        if (p.s("direction") != "watchToPhone" || b.s("direction") != p.s("direction") || p.s("accountScope") != scope || p.s("phoneDeviceId") != auth.phoneDeviceId || p.s("watchDeviceId") != auth.watchDeviceId) fail("PEER_MISMATCH")
        if (ResumeDigests.snapshot(p.o("phoneSnapshot")) != ResumeDigests.snapshot(auth.phoneSnapshot) || ResumeDigests.snapshot(p.o("watchSnapshot")) != ResumeDigests.snapshot(auth.watchSnapshot) || ResumeDigests.selection(b.o("selection")) != p.s("selectionDigest") || ResumeDigests.plan(p) != r.s("planDigest")) fail("PLAN_CONFLICT")
        if (p.o("selection").a("kinds").values().any { (it as JSONObject).s("kind") !in business.kinds }) fail("UNSUPPORTED_KIND")
        if (id !in begun && b.s("intent") != p.s("intent")) fail("JOB_CONFLICT")
        val old = store.get(scope, "job", id)
        if (old == null && p.s("intent") == "resume") fail("NOT_FOUND")
        if (old != null) {
            if (old.s("planDigest") != r.s("planDigest") || description(old).a("pageObjectCounts").toString() != p.a("pageObjectCounts").toString()) fail("JOB_CONFLICT")
            if (old.n("expiresAtMillis") < now) fail("EXPIRED")
            if (p.s("intent") == "create" && (terminal(old) || old.s("connection") != connection)) fail("JOB_CONFLICT")
        }
        if (p.o("watchSnapshot").n("toInclusive") != 0L && !business.canCommitRange(p)) fail("BUSINESS_REJECTED")
        val limits = p.o("selection").o("limits")
        if (ResumeWire.frameBytes(r) > limits.n("maxFrameBytes")) fail("LIMIT_EXCEEDED")
        if (old == null) {
            val active = store.db.resumeDao().all().filter { it.namespace == "job" }.map { ResumeJson.parse(it.body, false) }.count { !terminal(it) && it.n("expiresAtMillis") >= now }
            if (active >= limits.n("maxActiveJobs")) fail("WINDOW_FULL")
        }
        val reservation = ResumeJson.utf8(p.toString()).size + 4096L + p.n("objectCount") * 4096L + minOf(limits.n("maxManifestBytes"), p.n("objectCount") * 48000L)
        store.budget(if (old == null) reservation else 0, limits.n("maxJournalBytes"))
        if (p.n("objectCount") == 0L && p.s("objectsRoot") != ResumeDigests.objects(emptyList())) fail("PLAN_CONFLICT")
        val sameLease = old != null && old.s("connection") == connection && (leases[id] ?: -1) > monotonic()
        val epoch = if (sameLease) old!!.n("epoch") else (old?.n("epoch") ?: 0) + 1
        if (epoch > ResumeJson.MAX_SAFE) fail("JOURNAL_CORRUPT")
        val j = old ?: obj("jobId" to id, "planDigest" to r.s("planDigest"), "description" to copy(p), "state" to if (p.n("objectCount") == 0L) "READY" else "PLANNING", "nextManifestPage" to 0, "completion" to null, "reservation" to reservation, "expiresAtMillis" to now + ResumeWire.RETENTION)
        store.transaction {
            check(); j.put("connection", connection).put("epoch", epoch)
            bind(j, r); touch(j, now)
        }
        begun.add(id)
        cleanupApplied(j)
        return ResumeResults.answer(r, obj("disposition" to if (old == null) "CREATED" else "RESUMED", "selectionDigest" to p.s("selectionDigest"), "state" to j.s("state"), "expiresAtMillis" to j.n("expiresAtMillis"), "nextManifestPage" to j.n("nextManifestPage"), "completion" to j.get("completion")), epoch)
    }
    private suspend fun manifest(j: JSONObject, r: JSONObject): JSONObject {
        val p = r.o("payload"); val ds = description(j); val index = p.n("pageIndex").toInt(); val counts = ds.a("pageObjectCounts").values().map { ResumeJson.integer(it) }
        if (index !in counts.indices || p.n("startIndex") != counts.take(index).sum() || p.a("entries").length().toLong() != counts[index]) fail("PLAN_CONFLICT")
        if (ResumeWire.frameBytes(r) > lim(j).n("maxManifestPageBytes")) fail("LIMIT_EXCEEDED")
        val prior = if (index == 0) "0".repeat(64) else store.get(scope, "page", key(j.s("jobId"), (index - 1).toString()))?.s("digest") ?: fail("PLAN_CONFLICT")
        if (p.s("previousPageDigest") != prior || ResumeDigests.page(j.s("planDigest"), p) != p.s("pageDigest")) fail("PLAN_CONFLICT")
        val persistedPage = store.get(scope, "page", key(j.s("jobId"), index.toString()))
        if (persistedPage != null) {
            if (persistedPage.s("digest") != p.s("pageDigest")) fail("PLAN_CONFLICT")
            return persistedPage.o("result")
        }
        if (j.s("state") != "PLANNING" || index.toLong() != j.n("nextManifestPage")) fail("PLAN_CONFLICT")
        val entries = p.a("entries").values().map { ResumeWire.descriptor(it as JSONObject, lim(j)) }
        val selected = ds.o("selection").a("kinds").values().map { (it as JSONObject).s("kind") }
        if (entries.any { it.s("kind") !in selected }) fail("UNSUPPORTED_KIND")
        val all = objects(j.s("jobId")).map { it.o("descriptor") } + entries
        ResumeWire.sorted(all.map { it.s("objectId") })
        if (all.sumOf { ResumeJson.utf8(it.toString()).size.toLong() } > lim(j).n("maxManifestBytes")) fail("LIMIT_EXCEEDED")
        val full = index + 1 == counts.size
        if (full) { if (all.size.toLong() != ds.n("objectCount") || ResumeDigests.objects(all.map { it.s("objectId") }) != ds.s("objectsRoot")) fail("PLAN_CONFLICT"); validateGraph(all) }
        val result = obj("pageIndex" to index, "pageDigest" to p.s("pageDigest"), "nextManifestPage" to index + 1, "manifestVerified" to full, "state" to if (full) "READY" else "PLANNING")
        store.transaction {
            fence(j, r)
            entries.forEach { d -> store.put(scope, "object", objectKey(j.s("jobId"), d.s("objectId")), obj("jobId" to j.s("jobId"), "descriptor" to d, "receipt" to null)) }
            store.put(scope, "page", key(j.s("jobId"), index.toString()), obj("digest" to p.s("pageDigest"), "result" to result))
            j.put("state", result.s("state")).put("nextManifestPage", index + 1); store.put(scope, "job", j.s("jobId"), j); bind(j, r, result)
        }; return result
    }
    private suspend fun receipt(j: JSONObject, row: JSONObject): JSONObject? {
        if (row.nil("receipt")) return null
        val r = try { row.o("receipt").also { ResumeResults.receipt(it) } } catch (_: ResumeException) { fail("RECEIPT_CONFLICT") }
        if (r.s("jobId") != j.s("jobId") || r.s("planDigest") != j.s("planDigest") || r.s("receiverDeviceId") != auth.phoneDeviceId || r.s("objectId") != row.o("descriptor").s("objectId")) fail("RECEIPT_CONFLICT")
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
        val d = row.o("descriptor"); val missing = files.missing(scope, j.s("jobId"), d)
        // Adopt durable orphan files after a crash; discard stale block availability.
        d.a("chunkHashes").strings().indices.forEach { i ->
            val k = key(j.s("jobId"), d.s("objectId"), i.toString())
            if (i in missing) store.delete(scope, "chunk", k)
            else if (store.get(scope, "chunk", k) == null) { store.budget(1024, lim(j).n("maxJournalBytes")); store.put(scope, "chunk", k, obj("sha256" to d.a("chunkHashes").getString(i))) }
        }
        val staged = missing.isEmpty() && files.verified(files.composed(scope, j.s("jobId"), d.s("objectId")), d.n("size"), d.s("sha256"))
        return (if (staged) "STAGED" else if (missing.size < d.a("chunkHashes").length()) "PARTIAL" else "PENDING") to missing
    }
    private suspend fun missing(j: JSONObject, p: JSONObject): JSONObject {
        cleanupApplied(j)
        val base = obj("state" to j.s("state"), "nextManifestPage" to j.n("nextManifestPage"), "expiresAtMillis" to j.n("expiresAtMillis"), "completion" to j.get("completion"))
        val items = JSONArray(); var next: JSONObject? = null
        if (p.n("limit") == 0L || j.s("state") == "CANCELLED") return base.put("items", items).put("next", JSONObject.NULL).put("done", true)
        if (j.s("state") == "PLANNING") fail("MANIFEST_INCOMPLETE")
        if (p.n("limit") > lim(j).n("maxResultsPerPage")) fail()
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
        if (ResumeJson.utf8(result.toString()).size + 1024 > lim(j).n("maxFrameBytes")) fail("LIMIT_EXCEEDED")
        return result
    }
    private suspend fun runnable(j: JSONObject, d: JSONObject) {
        for (id in d.a("dependsOn").strings()) if (receipt(j, objectRow(j.s("jobId"), id)) == null) fail("DEPENDENCIES_PENDING")
    }
    private suspend fun admit(j: JSONObject, d: JSONObject) {
        var active = 0L; var ceiling = lim(j).n("maxInFlightObjects")
        for ((id, other) in store.list(scope, "job")) {
            cleanupApplied(other)
            if (terminal(other)) continue
            ceiling = minOf(ceiling, lim(other).n("maxInFlightObjects"))
            for (row in objects(id)) {
                if (row.o("descriptor").s("objectId") == d.s("objectId") && id == j.s("jobId")) continue
                if (receipt(other, row) == null && status(other, row).first != "PENDING") active++
            }
        }
        if (status(j, objectRow(j.s("jobId"), d.s("objectId"))).first == "PENDING" && active >= ceiling) fail("WINDOW_FULL")
    }
    private suspend fun stagingLimit(j: JSONObject): Long {
        var limit = lim(j).n("maxStagingBytes")
        for ((_, other) in store.list(scope, "job")) if (!terminal(other)) limit = minOf(limit, lim(other).n("maxStagingBytes"))
        return limit
    }
    private suspend fun validateChunk(j: JSONObject, p: JSONObject): Pair<JSONObject, ByteArray> {
        val row = objectRow(j.s("jobId"), p.s("objectId")); val d = row.o("descriptor"); val index = p.n("chunkIndex").toInt()
        if (index !in 0 until d.a("chunkHashes").length()) fail()
        val b = ResumeJson.b64(p.get("dataBase64"), ResumeWire.CHUNK)
        if (b.size.toLong() != minOf(ResumeWire.CHUNK.toLong(), d.n("size") - index * ResumeWire.CHUNK.toLong()) || ResumeDigests.hash(b) != d.a("chunkHashes").getString(index)) fail("HASH_MISMATCH")
        return row to b
    }
    private suspend fun put(j: JSONObject, r: JSONObject): JSONObject {
        if (j.s("state") == "PLANNING") fail("MANIFEST_INCOMPLETE")
        val p = r.o("payload"); val (row, bytes) = validateChunk(j, p); val d = row.o("descriptor"); val rc = receipt(j, row)
        if (rc != null) {
            files.release(scope, j.s("jobId"), d)
            return obj("objectId" to d.s("objectId"), "chunkIndex" to p.n("chunkIndex"), "chunkState" to null, "objectState" to "APPLIED", "receipt" to rc)
        }
        if (terminal(j)) fail("JOB_CANCELLED")
        runnable(j, d); admit(j, d)
        files.stage(scope, j.s("jobId"), d, p.n("chunkIndex").toInt(), bytes, stagingLimit(j)); fault("afterChunkDurable")
        leases[j.s("jobId")] = monotonic() + ResumeWire.LEASE
        val state = status(j, row).first
        val result = obj("objectId" to d.s("objectId"), "chunkIndex" to p.n("chunkIndex"), "chunkState" to "STAGED", "objectState" to if (state == "STAGED") state else "PARTIAL", "receipt" to null)
        store.transaction { fence(j, r); bind(j, r, result); j.put("state", "TRANSFERRING"); store.put(scope, "job", j.s("jobId"), j) }
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
        val metadata = ResumeMetadata.validate(d)
        val staged = files.finalize(scope, j.s("jobId"), d, stagingLimit(j)); fault("afterObjectDurable")
        // The writer lane is held during local work. Extend the in-use lease before
        // entering the commit; GC/revoke/new begin cannot cross this boundary.
        leases[j.s("jobId")] = monotonic() + ResumeWire.LEASE
        val result = store.transaction {
            fence(j, r)
            val outcome = business.apply(store.db, copy(d), metadata, staged)
            check(); fault("beforeApplyCommit")
            touch(j, clock())
            val rc = ResumeDigests.receipt(r, d.s("objectId"), auth.phoneDeviceId, outcome)
            row.put("receipt", rc); store.put(scope, "object", objectKey(j.s("jobId"), d.s("objectId")), row)
            if (objects(j.s("jobId")).all { !it.nil("receipt") }) j.put("state", "APPLIED") else j.put("state", "TRANSFERRING")
            store.put(scope, "job", j.s("jobId"), j)
            obj("state" to "APPLIED", "receipt" to rc).also { bind(j, r, it) }
        }
        fault("afterApplyCommit")
        files.release(scope, j.s("jobId"), d)
        return result
    }
    private suspend fun complete(j: JSONObject, r: JSONObject): JSONObject {
        if (j.s("state") == "CANCELLED") fail("JOB_CANCELLED")
        val p = r.o("payload"); val ds = description(j)
        if (p.n("objectCount") != ds.n("objectCount") || p.n("ackToInclusive") != ds.o("watchSnapshot").n("toInclusive")) fail("PLAN_CONFLICT")
        if (j.s("state") == "COMPLETE") { val c = j.o("completion"); if (p.s("receiptsRoot") != c.s("receiptsRoot")) fail("RECEIPT_CONFLICT"); return c }
        if (j.s("state") == "PLANNING") fail("MANIFEST_INCOMPLETE")
        val rs = objects(j.s("jobId")).map { receipt(j, it) ?: fail("CHUNKS_MISSING") }
        val root = ResumeDigests.receipts(rs); if (root != p.s("receiptsRoot")) fail("RECEIPT_CONFLICT")
        val c = ResumeDigests.completion(j.s("jobId"), j.s("planDigest"), ds.n("objectCount"), root, p.n("ackToInclusive"))
        store.transaction {
            fence(j, r); business.complete(store.db, copy(ds), copy(c)); check(); touch(j, clock())
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
