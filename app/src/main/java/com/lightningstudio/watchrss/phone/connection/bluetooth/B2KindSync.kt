package com.lightningstudio.watchrss.phone.connection.bluetooth

import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.*
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** Connects selected business ports to the persistent B2 session. */
internal class B2KindSync(private val store: B2Persistence, private val directory: File,
    private val assetDirectory: File, private val account: String, private val phone: String,
    private val watch: String, private val authorize: () -> Unit,
    private val progress: (String) -> Unit,
    private val route: B2KindRoute,
    private val business: B2InheritedKinds, private val retentionGeneration: String? = null,
    private val onBookIssue: (BookSyncIssue) -> Unit = {}) {
    private val files = B2Files(directory, business.bookImportRoot())
    private val monotonic = android.os.SystemClock::elapsedRealtime
    private val retention = B2Retention(store, files, account, monotonic, retentionGeneration, authorize)
    private val snapshots = B2Snapshots(store, files)
    private val key = if (route.isAssetOnly) {
        B2Digests.d("wrss.library-binary.v2/asset-attempt", listOf(account, phone, watch))
    } else B2Digests.d("wrss.library-binary.v2/kind-attempt", listOf(account, phone, watch,
        route.send.sorted().joinToString(","), route.receive.sorted().joinToString(",")))
    private val capability = B2Negotiation.capability(route.send, route.receive, (if (route.hasBinaryBooks) B2Wire.s4Limits() else B2Wire.s3Limits()).apply {
        if (route.hasBinaryBooks) {
            put("maxManifestBytes", 1536 * 1024)
            put("maxPlanObjects", 2048)
        } else if (route.hasBinaryLibrary) {
            put("maxManifestBytes", 1024 * 1024)
            put("maxPlanObjects", 1024)
        }
    })
    private val rejectionNamespace = "applyRejection:" + B2Digests.hash(B2Json.utf8(watch))
    private val warnings = linkedSetOf<BookSyncIssue>()
    val bookIssues: List<BookSyncIssue> get() = warnings.toList()

    private fun warn(issue: BookSyncIssue) {
        if (warnings.add(issue)) onBookIssue(issue)
    }

    private fun rejectionKey(d: JSONObject): String {
        // Reading pages are contiguous. An unchanged page can move when an earlier
        // page is omitted, so use the book and payload digest for that kind.
        val kind = d.s("kind")
        val identity = if (kind == B2Wire.BOOK_READING) d.s("businessId").substringBefore(':')
            else d.s("businessId") + ":" + d.s("metadataSha256")
        return B2Digests.d("wrss.library-binary.v2/apply-rejection", listOf(kind, identity, d.s("sha256")))
    }

    private fun warnRejected(d: JSONObject) {
        if (d.s("kind") in B2Wire.bookKinds)
            warn(BookSyncIssue(B2Metadata.validate(d).s("bookId").take(8), "book_apply_rejected"))
    }

    /** The current connection is fenced before this runs. Keep receipts and retire
     * the peer's old grants on authenticated connections before starting a new plan. */
    private suspend fun stopAttempt(a: JSONObject, rejected: JSONObject? = null, error: String? = null) {
        val bindings = a.a("bindings").values().map { it as JSONObject }
        store.exclusive {
            store.transaction {
                authorize()
                if (rejected != null) {
                    store.put(account, rejectionNamespace, rejectionKey(rejected),
                        obj("objectId" to rejected.s("objectId"), "sha256" to rejected.s("sha256"), "code" to error))
                }
                for (binding in bindings) {
                    val job = binding.s("jobId")
                    val ns = if (binding.s("direction") == "watchToPhone") "job" else "sourceJob"
                    val row = store.get(account, ns, job)
                    // A source saved before the begin response has no state yet.
                    if (row != null && row.optString("state") != "COMPLETE") {
                        row.put("state", "CANCELLED").put("stagingReservation", 0)
                        store.put(account, ns, job, row)
                    }
                    if (binding.s("direction") == "watchToPhone" && row == null)
                        store.put(account, "cancelIntent", job, obj("reason" to "LOCAL_ABORT", "businessStop" to true))
                }
                a.put("state", "RETIRING").put("retireStopped", true)
                store.put(account, "attempt", key, a)
            }
            for (binding in bindings.filter { it.s("direction") == "watchToPhone" }) {
                val job = binding.s("jobId")
                if (store.get(account, "job", job)?.s("state") != "CANCELLED") continue
                for (row in store.listForJob(account, "object", job)) {
                    val d = row.o("descriptor")
                    files.release(account, job, d)
                    if (d.s("kind") == B2Wire.BOOK_COMMIT) files.assembly(account, job, d.s("objectId")).delete()
                }
            }
        }
        discardBookSource(a)
        if (rejected != null) warnRejected(rejected)
    }

    private fun omitRejected(descriptors: MutableList<JSONObject>, rejected: Set<String>) {
        val blocked = descriptors.filter { rejectionKey(it) in rejected }
        blocked.forEach(::warnRejected)
        val ids = blocked.mapTo(mutableSetOf()) { it.s("objectId") }
        val bookPackages = setOf(B2Wire.BOOK_SEGMENT, B2Wire.BOOK_COMMIT)
        val blockedPackages = blocked.filter { it.s("kind") in bookPackages }
            .mapTo(mutableSetOf()) { B2Metadata.validate(it).s("bookId") }
        if (blockedPackages.isNotEmpty()) descriptors.filter {
            it.s("kind") in bookPackages && B2Metadata.validate(it).s("bookId") in blockedPackages
        }.forEach { ids.add(it.s("objectId")) }
        if (ids.isEmpty()) return
        do {
            var changed = false
            for (d in descriptors) {
                if (d.a("dependsOn").strings().any { it in ids }) changed = ids.add(d.s("objectId")) || changed
            }
        } while (changed)
        descriptors.removeAll { it.s("objectId") in ids }
    }

    private var preparing: () -> Unit = {}
    private fun id() = java.util.UUID.randomUUID().toString().replace("-", "")
    private suspend fun discardBookSource(attempt: JSONObject) {
        if (!route.hasBinaryBooks && !attempt.optBoolean("retireStopped")) return
        val snapshot = attempt.getJSONObject("phoneSnapshot")
        for (d in snapshots.descriptors(account, snapshot)) {
            files.frozen(account, snapshot.getString("snapshotId"), d.getString("objectId")).delete()
        }
    }

    suspend fun resetPlan() = store.exclusive { store.transaction {
        val previous = store.get(account, "attempt", key) ?: return@transaction
        previous.put("state", "RETIRING"); store.put(account, "attempt", key, previous)
        val bindings = previous.getJSONArray("bindings")
        for (i in 0 until bindings.length()) {
            val b = bindings.getJSONObject(i)
            if (b.getString("direction") == "watchToPhone") {
                val local = store.get(account, "job", b.getString("jobId")) ?: continue
                if (local.getString("state") != "COMPLETE") { local.put("state", "CANCELLED"); store.put(account, "job", b.getString("jobId"), local) }
            }
        }
    } }
    suspend fun prepare(probe: JSONObject, assets: List<NoteAssetInventoryEntry>) {
        createOrResume(probe, assets)
    }

    private suspend fun createOrResume(probe: JSONObject, assets: List<NoteAssetInventoryEntry>): JSONObject = try {
        B2LocalWork.run { unit ->
            preparing = unit
            files.onProgress = { unit() }
            try {
                store.exclusive { retention.collect(retention.clock()) }
                freeze(probe, assets)
            } finally { preparing = {} }
        }
    } catch (_: java.io.IOException) {
        throw B2Exception("LOCAL_IO")
    } catch (_: android.database.SQLException) {
        throw B2Exception("LOCAL_IO")
    }

    private suspend fun freeze(probe: JSONObject, assets: List<NoteAssetInventoryEntry>): JSONObject {
        val selection = B2Negotiation.select(capability, probe.getJSONObject("libraryBinary"), "phoneToWatch")
        val budget = selection?.getJSONObject("limits")?.let(::B2WatchReceiveBudget)
        val attempt = store.exclusive { store.get(account, "attempt", key) }
        if (attempt != null && attempt.optString("state") in setOf("PREPARED", "RETIRING", "CANCELLED")) {
            if (attempt.getString("state") != "PREPARED") return attempt
            val frozen = snapshots.descriptors(account, attempt.getJSONObject("phoneSnapshot"))
            val bindings = attempt.getJSONArray("bindings")
            val oversized = route.hasBinaryBooks && attempt.optInt("bookMetadataVersion") != B2BookMetadataLimits.VERSION &&
                frozen.any { d ->
                    val path = files.frozen(account, attempt.o("phoneSnapshot").s("snapshotId"), d.s("objectId"))
                    !B2BookMetadataLimits.acceptsFrozen(d, path, preparing)
                }
            if (oversized && bindings.length() > 0) {
                stopAttempt(attempt)
                return attempt
            }
            if (oversized) discardBookSource(attempt)
            val needsRefreeze = oversized || try {
                validateBinaryRssDependencies(frozen, preparing)
                false
            } catch (failure: B2Exception) {
                if (failure.code != "PLAN_CONFLICT") throw failure
                true
            }
            if (!needsRefreeze) {
                checkFrozenPlan(selection, frozen)
                for (i in 0 until bindings.length()) {
                    val binding = bindings.getJSONObject(i)
                    if (binding.getString("direction") == "phoneToWatch") {
                        // Resumes send the original selection, even if the new probe raised a limit.
                        val original = binding.getJSONObject("selection")
                        if (selection == null || B2Digests.selection(original) != B2Digests.selection(selection)) {
                            checkFrozenPlan(original, frozen)
                        }
                        break
                    }
                }
                return attempt
            }
            // Adding an edge changes object and plan identities. Use the existing retirement
            // path for a bound plan. An unused snapshot can be superseded locally.
            if (bindings.length() > 0) {
                resetPlan()
                return attempt.put("state", "RETIRING")
            }
        }
        val rejected = store.exclusive { store.list(account, rejectionNamespace).mapTo(mutableSetOf()) { it.first } }
        val descriptors = mutableListOf<JSONObject>()
        val sources = mutableMapOf<String, File>()
        fun add(descriptor: JSONObject, source: File) {
            if (rejected.isEmpty()) (budget ?: fail("CAPABILITY_MISMATCH")).add(descriptor)
            descriptors += descriptor
            sources[descriptor.getString("objectId")] = source
        }
        val witness = B2Scratch.open(directory, B2Scratch.FREEZE).use { lease ->
            val scratch = lease.file
            if (B2_NOTE_ASSET in route.send) for (asset in assets.sortedBy { it.storageKey }) {
                authorize()
                val source = File(assetDirectory, asset.storageKey)
                val hashes = binaryFileHashes(source, preparing)
                if (hashes.size != asset.size || hashes.sha256 != asset.sha256) throw B2Exception("SOURCE_CHANGED")
                val metadata = JSONObject().put("storageKey", asset.storageKey)
                    .put("sha256", asset.sha256).put("size", asset.size)
                add(binaryDescriptor(B2_NOTE_ASSET, asset.storageKey, metadata, hashes), source)
            }
            business.freeze(route.send, scratch, preparing, { descriptor ->
                (rejectionKey(descriptor) !in rejected).also { if (!it) warnRejected(descriptor) }
            }, ::warn, ::add)
            omitRejected(descriptors, rejected)
            if (rejected.isNotEmpty()) {
                for (descriptor in descriptors) (budget ?: fail("CAPABILITY_MISMATCH")).add(descriptor)
            }
            B2BookManifest.validatePlan(descriptors, selection?.getJSONObject("limits")?.getLong("maxPackageBytes") ?: B2Wire.BOOK_MAX_BYTES)
            descriptors.sortBy { it.getString("objectId") }
            validateBinaryRssDependencies(descriptors, preparing)
            checkPlan(selection, descriptors)
            snapshots.freeze(account, "asset-" + id(), descriptors, sourceBudget = if (route.hasBinaryBooks)
                B2Wire.s4Limits().getLong("maxStagingBytes") - B2Wire.s4Limits().getLong("maxJournalBytes") else 67108864,
                ownedSources = scratch) { sources.getValue(it.getString("objectId")) }
        }
        return JSONObject().put("state", "PREPARED").put("phoneSnapshot", witness)
            .put("bookMetadataVersion", B2BookMetadataLimits.VERSION)
            .put("watchSnapshot", JSONObject.NULL).put("bindings", JSONArray()).also { attempt ->
                store.exclusive { authorize(); store.budget(B2Json.utf8(attempt.toString()).size.toLong() + 1024)
                    store.put(account, "attempt", key, attempt) }
            }
    }

    private fun checkFrozenPlan(selection: JSONObject?, descriptors: List<JSONObject>) {
        if (descriptors.isNotEmpty()) {
            val limits = selection?.getJSONObject("limits") ?: throw B2Exception("CAPABILITY_MISMATCH")
            val budget = B2WatchReceiveBudget(limits)
            for (descriptor in descriptors) budget.add(descriptor)
        }
        checkPlan(selection, descriptors)
    }

    private fun checkPlan(selection: JSONObject?, descriptors: List<JSONObject>) {
        if (selection != null && B2Planner.preflight("merge", selection, descriptors) is B2Planner.Admission.UseLegacy) {
            throw B2Exception("LIMIT_EXCEEDED")
        }
    }

    suspend fun run(input: InputStream, output: OutputStream, probe: JSONObject,
        assets: List<NoteAssetInventoryEntry>, close: () -> Unit) = supervisorScope {
        authorize()
        val a = createOrResume(probe, assets)
        val retiring = a.getString("state") in setOf("RETIRING", "CANCELLED")
        val stopped = a.optBoolean("retireStopped")
        if (retiring && !stopped) {
            val oldBindings = a.getJSONArray("bindings")
            val forward = (0 until oldBindings.length()).map { oldBindings.getJSONObject(it) }.find { it.getString("direction") == "phoneToWatch" }
            val saved = forward?.let { store.get(account, "sourceJob", it.getString("jobId")) }
            if (saved == null || saved.optString("state") == "COMPLETE") {
                discardBookSource(a)
                store.exclusive { store.delete(account, "attempt", key) }
                throw B2Exception("PLAN_RETIRED")
            }
        }
        val pendingRetirements = if (stopped) a.a("bindings").values().map { it as JSONObject }.filter { b ->
            b.s("direction") != "phoneToWatch" || store.get(account, "sourceJob", b.s("jobId")) != null
        } else emptyList()
        if (stopped && pendingRetirements.isEmpty()) {
            discardBookSource(a)
            store.exclusive { store.delete(account, "attempt", key) }
            throw B2Exception("PLAN_RETIRED")
        }
        val retiringDirection = pendingRetirements.firstOrNull()?.s("direction")
        val session = B2Session(input, output, this, close, authorize = authorize)
        val client = B2Client(store, snapshots, session::exchange, authorize, session, progress)
        if (!client.acceptProbe(probe)) throw B2Exception("CAPABILITY_MISMATCH")
        session.switch(probe.getJSONObject("libraryBinary"), true)
        files.onProgress = session::workProgress
        session.start()
        val reverseDone = CompletableDeferred<Unit>()
        try {
            val offer = client.offer(account, phone, watch, capability, a.getJSONObject("phoneSnapshot"), if (a.isNull("watchSnapshot")) null else a.getJSONObject("watchSnapshot"), verifySource = !retiring)
            val oldBindings = a.getJSONArray("bindings")
            val bindings = if (oldBindings.length() > 0) (0 until oldBindings.length()).map { i ->
                JSONObject(oldBindings.getJSONObject(i).toString()).put("intent", "resume")
            }.filter { !retiring || it.s("direction") == (retiringDirection ?: "phoneToWatch") } else listOf("phoneToWatch", "watchToPhone").mapNotNull { direction ->
                B2Negotiation.select(capability, offer.getJSONObject("watchCapabilities"), direction)?.let { s ->
                    JSONObject().put("direction", direction).put("jobId", id()).put("intent", "create").put("selection", s)
                }
            }
            if (bindings.isEmpty()) throw B2Exception("CAPABILITY_MISMATCH")
            a.put("watchSnapshot", offer.getJSONObject("watchSnapshot"))
            if (!retiring) a.put("bindings", JSONArray(bindings))
            store.exclusive { authorize(); store.put(account, "attempt", key, a) }
            try {
                client.confirm(bindings) { auth ->
                    val receiver = B2Receiver(store, files, auth, business,
                        monotonic = monotonic, progress = progress,
                        retentionGeneration = retentionGeneration)
                    session.install(receiver) { request ->
                        val apply = request.getString("phase") == "apply"
                        if (apply) business.applyWork = { session.workProgress(request.getString("jobId")) }
                        val answer = try {
                            client.handleRequest(request, receiver)
                        } finally {
                            if (apply) business.applyWork = {}
                        }
                        answer.also {
                            if (request.getString("phase") == "complete" && answer.getBoolean("success")) { progress("complete:watchToPhone"); reverseDone.complete(Unit) }
                            if (request.s("phase") == "begin") {
                                val state = answer.o("result").s("state")
                                if (state == "COMPLETE" || retiringDirection == "watchToPhone" && state == "CANCELLED")
                                    reverseDone.complete(Unit)
                            }
                        }
                    }
                }
                if (retiringDirection == "watchToPhone") {
                    val termination = async { session.awaitTermination() }
                    select<Unit> { reverseDone.onAwait {}; termination.onAwait { throw it } }
                    val ended = termination.await()
                    if (ended !is java.io.EOFException && !(ended is B2Exception && ended.code == "JOB_CANCELLED")) throw ended
                } else if (retiring) {
                    val forward = bindings.single().getString("jobId")
                    try {
                        val reason = store.get(account, "cancelIntent", forward)?.optString("reason") ?: "LOCAL_ABORT"
                        client.retire(forward, reason)
                        if (!stopped) session.flush()
                    }
                    catch (e: B2Exception) { if (e.code !in setOf("NOT_FOUND", "EXPIRED")) throw e }
                    if (!stopped) {
                        discardBookSource(a)
                        store.exclusive { store.delete(account, "attempt", key) }
                        throw B2Exception("PLAN_RETIRED")
                    }
                }
            } catch (e: B2Exception) {
                if (!stopped || e.code !in setOf("NOT_FOUND", "EXPIRED")) throw e
            }
            if (stopped) {
                val leftover = pendingRetirements.drop(1)
                if (leftover.isEmpty()) discardBookSource(a)
                store.exclusive { store.transaction {
                    store.delete(account, "cancelIntent", pendingRetirements.first().s("jobId"))
                    if (leftover.isEmpty()) store.delete(account, "attempt", key)
                    else store.put(account, "attempt", key, a.put("bindings", JSONArray(leftover)))
                } }
                throw B2Exception("PLAN_RETIRED")
            }
            val work = async {
                val source = bindings.find { it.getString("direction") == "phoneToWatch" }
                if (source != null) client.send(source.getString("jobId"))
                if (bindings.any { it.getString("direction") == "watchToPhone" }) reverseDone.await()
            }
            val terminated = async { session.awaitTermination() }
            select<Unit> { work.onAwait { }; terminated.onAwait { throw it } }
            terminated.cancel()
            session.flush()
            val snapshot = a.getJSONObject("phoneSnapshot")
            val sent = snapshots.descriptors(account, snapshot)
            val stats = store.exclusive {
                authorize()
                val reverse = bindings.find { it.getString("direction") == "watchToPhone" }?.getString("jobId")
                val received = if (reverse == null || !route.hasBinaryLibrary) emptyList() else
                    store.listForJob(account, "object", reverse)
                a.put("state", "COMPLETE")
                store.put(account, "attempt", key, a)
                fun article(kind: String) = kind == B2_ARTICLE_BODY || kind == B2_ARTICLE_METADATA
                fun incoming(test: (String) -> Boolean) = received.filter { test(it.getJSONObject("descriptor").getString("kind")) }
                val articles = incoming(::article)
                val sources = incoming { it == B2_RSS_SOURCE }
                fun merged(rows: List<JSONObject>) = rows.count { it.getJSONObject("receipt").getString("outcome") == "merged" }
                LibrarySyncStats(sent.count { article(it.getString("kind")) }, articles.size, merged(articles),
                    sent.count { it.getString("kind") == B2_RSS_SOURCE }, sources.size, merged(sources))
            }
            if (route.hasBinaryLibrary || route.hasBinaryBooks) for (descriptor in sent) {
                files.frozen(account, snapshot.getString("snapshotId"), descriptor.getString("objectId")).delete()
            }
            stats
        } catch (e: Throwable) {
            val authorized = runCatching(authorize).isSuccess
            if (e is CancellationException && authorized) session.cancelUser() else session.close()
            if (e is B2ApplyStop && authorized) {
                withContext(NonCancellable + Dispatchers.IO) {
                    if (a.a("bindings").values().none {
                            val binding = it as JSONObject
                            binding.s("direction") == "phoneToWatch" && binding.s("jobId") == e.jobId
                        }) fail("JOURNAL_CORRUPT")
                    val rejected = snapshots.descriptors(account, a.o("phoneSnapshot"))
                        .singleOrNull { it.s("objectId") == e.objectId } ?: fail("JOURNAL_CORRUPT")
                    stopAttempt(a, rejected, e.code)
                }
            }
            if (e is CancellationException || e is B2Exception && e.code in setOf("JOB_CANCELLED", "USER_CANCELLED", "AUTH_REVOKED")) {
                // Cancellation intent survives a lost cancel frame and cannot revive on resume.
                withContext(NonCancellable + Dispatchers.IO) { store.exclusive { store.transaction {
                    val bindings = a.getJSONArray("bindings")
                    for (i in 0 until bindings.length()) {
                        val jobId = bindings.getJSONObject(i).getString("jobId")
                        for (ns in listOf("job", "sourceJob")) {
                            val row = store.get(account, ns, jobId) ?: continue
                            if (row.optString("state") != "COMPLETE") { row.put("state", "CANCELLED"); store.put(account, ns, jobId, row) }
                        }
                        store.put(account, "cancelIntent", jobId, JSONObject().put("reason", if (!authorized || e is B2Exception && e.code == "AUTH_REVOKED") "ACCOUNT_CHANGED" else if (e is CancellationException) "USER_CANCEL" else "LOCAL_ABORT"))
                    }
                    a.put("state", "CANCELLED"); store.put(account, "attempt", key, a)
                } } }
            }
            if (e is android.database.SQLException) throw B2Exception("LOCAL_IO")
            throw e
        } finally { session.close() }
    }
}
