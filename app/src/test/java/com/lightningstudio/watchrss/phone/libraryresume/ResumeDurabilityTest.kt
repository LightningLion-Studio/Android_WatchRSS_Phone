package com.lightningstudio.watchrss.phone.libraryresume

import android.content.Context
import androidx.room.Room
import com.lightningstudio.watchrss.phone.data.db.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, manifest = Config.NONE, sdk = [35])
class ResumeDurabilityTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val name = "RESUMEC-${System.nanoTime()}"
    private var db = open()
    private fun open() = Room.databaseBuilder(context, PhoneCompanionDatabase::class.java, name).addMigrations(PhoneCompanionDatabase.MIGRATION_19_20).allowMainThreadQueries().build()
    private val root = java.nio.file.Files.createTempDirectory("RESUMEC-").toFile()
    private val files = ResumeFiles(root)
    private var store = ResumePersistence(db)
    private val v = vectors()
    private val job = v.a("bindings").getJSONObject(0).s("jobId")
    private val scope = v.s("accountScope")
    private val body = v.a("objects").values().map { it as JSONObject }.first { it.s("kind") == "articleBody" }
    private val rss = v.a("objects").values().map { it as JSONObject }.first { it.s("kind") == "rssSource" }
    private var now = 1000L
    private var authorized = true
    private val port = object : ResumeBusinessPort {
        override val kinds = ResumeWire.kinds.toSet()
        override suspend fun apply(db: PhoneCompanionDatabase, descriptor: JSONObject, metadata: JSONObject, staged: File): String {
            val key = "applied-" + descriptor.s("objectId")
            val n = db.appMetaDao().getString(key)?.toInt() ?: 0
            db.appMetaDao().set(AppMetaEntity(key, (n + 1).toString()))
            if (descriptor.s("kind") == "rssSource") db.phoneRssSourceDao().upsert(PhoneRssSourceEntity(metadata.s("url"), metadata.s("sourceDeviceId"), metadata.s("title"), metadata.s("description"), if (metadata.nil("siteUrl")) null else metadata.s("siteUrl"), if (metadata.nil("imageUrl")) null else metadata.s("imageUrl"), metadata.n("createdAt"), metadata.n("updatedAt"), metadata.n("sortOrder"), metadata.getBoolean("isPinned"), metadata.getBoolean("deleted"), metadata.n("deletedAt")))
            if (descriptor.s("kind") == "articleBody") assertEquals(descriptor.s("sha256"), ResumeDigests.hash(staged.readBytes()))
            return "merged"
        }
        override suspend fun complete(db: PhoneCompanionDatabase, description: JSONObject, completion: JSONObject) {
            val n = db.appMetaDao().getString("completion-count")?.toInt() ?: 0
            db.appMetaDao().set(AppMetaEntity("completion-count", (n + 1).toString()))
        }
    }
    private val p = copy(v.a("requests").values().map { it as JSONObject }.first { it.s("name") == "begin" }.o("frame").o("payload")).put("direction", "watchToPhone")
    private val plan get() = ResumeDigests.plan(p)
    private fun receiver(intent: String = "create", fault: (String) -> Unit = {}): ResumeReceiver {
        val binding = obj("direction" to "watchToPhone", "jobId" to job, "intent" to intent, "selection" to p.o("selection"))
        val auth = ResumeAuthorization(scope, v.s("phoneDeviceId"), v.s("watchDeviceId"), v.o("phoneSnapshot"), v.o("watchSnapshot"), listOf(binding)) { authorized }
        return ResumeReceiver(store, files, auth, port, { now }, fault)
    }
    private fun frame(phase: String, payload: JSONObject, epoch: Long?, id: String? = null): JSONObject {
        val digest = ResumeDigests.request(job, plan, phase, payload)
        return ResumeWire.request(phase, job, plan, epoch, payload, id ?: ResumeDigests.d("wrss.library-resume.v1/source-request", listOf(job, digest)).take(32))
    }
    private suspend fun begin(r: ResumeReceiver, intent: String = "create") = r.handle(frame("begin", copy(p).put("intent", intent), null)).n("epoch")
    private suspend fun manifest(r: ResumeReceiver, epoch: Long) {
        val page = obj("pageIndex" to 0, "startIndex" to 0, "previousPageDigest" to "0".repeat(64), "pageDigest" to "0".repeat(64), "entries" to v.a("objects"))
        page.put("pageDigest", ResumeDigests.page(plan, page)); assertEquals("READY", r.handle(frame("manifestPage", page, epoch)).o("result").s("state"))
    }
    private fun chunk(index: Int, data: ByteArray? = null): JSONObject {
        val all = Base64.getDecoder().decode(v.s("bodyBase64")); val bytes = data ?: all.copyOfRange(index * ResumeWire.CHUNK, minOf(all.size, (index + 1) * ResumeWire.CHUNK))
        return obj("objectId" to body.s("objectId"), "chunkIndex" to index, "dataBase64" to Base64.getEncoder().encodeToString(bytes))
    }
    private suspend fun stage(r: ResumeReceiver, epoch: Long) { for (i in 0 until body.a("chunkHashes").length()) r.handle(frame("putChunk", chunk(i), epoch)) }
    private suspend fun apply(r: ResumeReceiver, epoch: Long, d: JSONObject = body, id: String? = null) = r.handle(frame("apply", obj("objectId" to d.s("objectId")), epoch, id)).o("result").o("receipt")
    private suspend fun missing(r: ResumeReceiver, epoch: Long, limit: Int = 16) = r.handle(frame("missing", obj("afterObjectId" to null, "afterChunkIndex" to null, "limit" to limit), epoch)).o("result")
    private suspend fun writes(d: JSONObject = body) = db.appMetaDao().getString("applied-" + d.s("objectId"))?.toInt() ?: 0
    private suspend fun expect(code: String, action: suspend () -> Unit) { try { action(); throw AssertionError("expected $code") } catch (e: ResumeException) { assertEquals(code, e.code) } }
    private fun reopen() { db.close(); db = open(); store = ResumePersistence(db) }
    @After fun cleanup() { db.close(); context.deleteDatabase(name); root.deleteRecursively() }

    @Test fun lostChunkAckAndRestartUsesOnlyDurableMissingBlocks() = runBlocking {
        var r = receiver(); var epoch = begin(r); manifest(r, epoch)
        val reply = r.handle(frame("putChunk", chunk(0), epoch)) // Reply intentionally discarded.
        assertEquals("STAGED", reply.o("result").s("chunkState")); val size = files.usedBytes()
        reopen(); r = receiver("resume"); epoch = begin(r, "resume"); assertEquals(2L, epoch)
        val row = missing(r, epoch).a("items").values().map { it as JSONObject }.first { it.s("objectId") == body.s("objectId") }
        assertEquals("[[1,2]]", row.a("missingRanges").toString())
        r.handle(frame("putChunk", chunk(0), epoch)); assertEquals(size, files.usedBytes())
        r.handle(frame("putChunk", chunk(1), epoch)); assertEquals(0, writes()); apply(r, epoch); assertEquals(1, writes())
    }
    @Test fun orphanDurableFileBeforeReceiptIsAdoptedAndNotApplied() = runBlocking {
        var crash = true
        var r = receiver { if (it == "afterChunkDurable" && crash) throw IllegalStateException("crash") }; var epoch = begin(r); manifest(r, epoch)
        try { r.handle(frame("putChunk", chunk(0), epoch)); throw AssertionError() } catch (_: IllegalStateException) {}
        assertEquals(0, store.list(scope, "chunk").size); assertEquals(0, writes())
        val size = files.usedBytes(); crash = false; reopen(); r = receiver("resume"); epoch = begin(r, "resume")
        missing(r, epoch); assertEquals(1, store.list(scope, "chunk").size); assertEquals(size, files.usedBytes()); assertEquals(0, writes())
        stage(r, epoch); apply(r, epoch); assertEquals(1, writes())
    }
    @Test fun businessAndAppliedReceiptRollbackTogetherThenCommitExactlyOnce() = runBlocking {
        var crash = true
        var r = receiver { if (it == "beforeApplyCommit" && crash) throw IllegalStateException("rollback") }; var epoch = begin(r); manifest(r, epoch)
        try { apply(r, epoch, rss); throw AssertionError() } catch (_: IllegalStateException) {}
        assertEquals(0, writes(rss)); assertNull(db.phoneRssSourceDao().getByUrl(rss.s("businessId")))
        assertTrue(store.list(scope, "object").all { it.second.nil("receipt") })
        crash = false; reopen(); r = receiver("resume"); epoch = begin(r, "resume")
        val a = apply(r, epoch, rss); val b = apply(r, epoch, rss, "c".repeat(32))
        assertEquals(a.s("resultDigest"), b.s("resultDigest")); assertEquals(1, writes(rss)); assertNotNull(db.phoneRssSourceDao().getByUrl(rss.s("businessId")))
    }
    @Test fun lostAppliedReplyRecoversWithoutFilesOrSecondBusinessWrite() = runBlocking {
        var crash = true
        var r = receiver { if (it == "afterApplyCommit" && crash) throw IllegalStateException("reply lost") }; var epoch = begin(r); manifest(r, epoch); stage(r, epoch)
        try { apply(r, epoch); throw AssertionError() } catch (_: IllegalStateException) {}
        assertEquals(1, writes()); crash = false; root.listFiles()!!.forEach { it.delete() }; reopen(); r = receiver("resume"); epoch = begin(r, "resume")
        val result = apply(r, epoch); assertEquals(1, writes()); assertEquals("merged", result.s("outcome"))
        val row = missing(r, epoch).a("items").values().map { it as JSONObject }.first { it.s("objectId") == body.s("objectId") }
        assertEquals("APPLIED", row.s("state")); assertEquals(0, row.a("missingRanges").length())
        val bad = Base64.getDecoder().decode(chunk(0).s("dataBase64")); bad[0] = (bad[0].toInt() xor 1).toByte()
        expect("HASH_MISMATCH") { r.handle(frame("putChunk", chunk(0, bad), epoch)) }; assertEquals(1, writes())
    }
    @Test fun newConnectionFencesLateWritesEvenWhenOldConnectionGuessesEpoch() = runBlocking {
        val old = receiver(); val e = begin(old); manifest(old, e); old.handle(frame("putChunk", chunk(0), e))
        val fresh = receiver("resume"); val current = begin(fresh, "resume"); assertEquals(e + 1, current)
        expect("STALE_EPOCH") { old.handle(frame("putChunk", chunk(1), current)) }
        expect("STALE_EPOCH") { apply(old, current) }; assertEquals(0, writes())
        fresh.handle(frame("putChunk", chunk(1), current)); apply(fresh, current); assertEquals(1, writes())
        val req = frame("apply", obj("objectId" to body.s("objectId")), current)
        val stale = ResumeResults.answer(req, obj("state" to "APPLIED", "receipt" to apply(fresh, current)), e)
        assertError("STALE_EPOCH") { ResumeSession.validateCorrelation(req, stale) }
    }
    @Test fun requestIdReuseAndNetworkBadHashNeverChangeStoredBytes() = runBlocking {
        val r = receiver(); val e = begin(r); manifest(r, e)
        val id = "d".repeat(32); r.handle(frame("putChunk", chunk(0), e, id)); val size = files.usedBytes()
        expect("REQUEST_CONFLICT") { r.handle(frame("putChunk", chunk(1), e, id)) }
        val bad = Base64.getDecoder().decode(chunk(0).s("dataBase64")); bad[10] = (bad[10].toInt() xor 1).toByte()
        expect("HASH_MISMATCH") { r.handle(frame("putChunk", chunk(0, bad), e, id)) }; assertEquals(size, files.usedBytes()); assertEquals(0, writes())
        val altered = copy(body).put("contentVersion", "other")
        assertError("OBJECT_CONFLICT") { ResumeWire.descriptor(altered) }
    }
    @Test fun localCorruptChunkIsRequestedAgainAndFixedWithoutDoubleEffects() = runBlocking {
        val r = receiver(); val e = begin(r); manifest(r, e); stage(r, e)
        files.chunk(scope, job, body.s("objectId"), 0).writeBytes(byteArrayOf(1))
        val row = missing(r, e).a("items").values().map { it as JSONObject }.first { it.s("objectId") == body.s("objectId") }
        assertEquals("[[0,1]]", row.a("missingRanges").toString()); r.handle(frame("putChunk", chunk(0), e)); apply(r, e); assertEquals(1, writes())
    }
    @Test fun leaseExpiryRebeginAndAccountRevocation() = runBlocking {
        val r = receiver(); var e = begin(r); manifest(r, e); now += ResumeWire.LEASE
        expect("LEASE_EXPIRED") { missing(r, e) }
        e = begin(r, "resume"); assertEquals(2L, e); assertEquals("READY", missing(r, e, 0).s("state"))
        authorized = false; expect("AUTH_REVOKED") { missing(r, e) }; authorized = true; r.revoke(); expect("AUTH_REVOKED") { apply(r, e, rss) }; assertEquals(0, writes(rss))
    }
    @Test fun firstTerminalResumeReturnsOriginalCompletionAndNeverReadsReleasedFiles() = runBlocking {
        var r = receiver { if (it == "afterCompleteCommit") throw IllegalStateException("reply lost") }; var e = begin(r); manifest(r, e); stage(r, e)
        val rs = listOf(apply(r, e, rss), apply(r, e)).sortedBy { it.s("objectId") }; val payload = obj("objectCount" to 2, "receiptsRoot" to ResumeDigests.receipts(rs), "ackToInclusive" to 0)
        try { r.handle(frame("complete", payload, e)); throw AssertionError() } catch (_: IllegalStateException) {}
        assertEquals("1", db.appMetaDao().getString("completion-count")); root.listFiles()!!.forEach { it.delete() }; reopen(); r = receiver("resume")
        val began = r.handle(frame("begin", copy(p).put("intent", "resume"), null, "e".repeat(32))); e = began.n("epoch")
        assertEquals("COMPLETE", began.o("result").s("state")); assertEquals(2L, e)
        val c = began.o("result").o("completion")
        assertEquals(c.s("completionDigest"), missing(r, e, 0).o("completion").s("completionDigest"))
        assertEquals(c.s("completionDigest"), r.handle(frame("complete", payload, e)).o("result").s("completionDigest"))
        assertEquals("1", db.appMetaDao().getString("completion-count")); assertEquals(1, writes()); assertEquals(1, writes(rss))
    }
    @Test fun cancelledFirstResumeStaysTerminal() = runBlocking {
        var r = receiver(); var e = begin(r); manifest(r, e); r.handle(frame("putChunk", chunk(0), e))
        r.handle(frame("cancel", obj("reason" to "USER_CANCEL"), e)); reopen(); r = receiver("resume"); e = begin(r, "resume")
        assertEquals("CANCELLED", missing(r, e).s("state")); assertEquals(0L, files.usedBytes()); expect("JOB_CANCELLED") { apply(r, e) }; assertEquals(0, writes())
    }
    @Test fun missingDoesNotCacheAnEarlierObservationAndRootMustVerifyBeforeTransfer() = runBlocking {
        val r = receiver(); val e = begin(r)
        expect("MANIFEST_INCOMPLETE") { r.handle(frame("putChunk", chunk(0), e)) }
        manifest(r, e); val req = frame("missing", obj("afterObjectId" to null, "afterChunkIndex" to null, "limit" to 16), e)
        val before = r.handle(req).o("result").toString(); stage(r, e); val after = r.handle(req).o("result").toString(); assertNotEquals(before, after)
    }
    @Test fun migration19To20PreservesExistingBusinessRowsAndIsIdempotent() = runBlocking {
        db.appMetaDao().set(AppMetaEntity("legacy", "preserve")); db.close()
        val raw = context.openOrCreateDatabase(name, 0, null)
        raw.execSQL("DROP TABLE library_resume_records"); raw.execSQL("PRAGMA user_version=19"); raw.close()
        db = open(); store = ResumePersistence(db)
        assertEquals("preserve", db.appMetaDao().getString("legacy")); assertEquals(0, store.list(scope, "job").size)
        PhoneCompanionDatabase.MIGRATION_19_20.migrate(db.openHelper.writableDatabase)
        assertEquals("preserve", db.appMetaDao().getString("legacy"))
    }
    @Test fun clientWithoutCapabilityEchoEmitsNoNewFrame() = runBlocking {
        var writes = 0
        val client = ResumeClient(store, ResumeSnapshots(store, files), { writes++; fail() }, {})
        assertFalse(client.acceptProbe(obj("version" to 14, "phase" to "probe")))
        expect("CAPABILITY_MISMATCH") { client.offer(scope, v.s("phoneDeviceId"), v.s("watchDeviceId"), v.o("capability"), v.o("phoneSnapshot")) }
        assertEquals(0, writes)
    }
    @Test fun senderSnapshotDoesNotReadLaterLiveEditsAndDetectsMissingFrozenBytes() = runBlocking {
        val ss = ResumeSnapshots(store, files); var live = Base64.getDecoder().decode(v.s("bodyBase64"))
        val witness = ss.freeze(scope, "frozen", listOf(body), { live })
        live = byteArrayOf(9); assertEquals(body.s("objectId"), ss.descriptors(scope, witness).single().s("objectId"))
        assertEquals(body.a("chunkHashes").getString(0), ResumeDigests.hash(ss.read(scope, witness, body, 0)))
        files.frozen(scope, "frozen", body.s("objectId")).delete()
        assertError("SOURCE_CHANGED") { ss.read(scope, witness, body, 0) }
    }
    @Test fun dependencyAdmissionWorksWhenFourResourcesSortBeforeMetadataAtWindowsOneAndFour() = runBlocking {
        fun make(kind: String, business: String, metadata: JSONObject, bytes: ByteArray, deps: List<String> = emptyList()): JSONObject {
            val encoded = ResumeJson.utf8(metadata.toString())
            val d = obj("kind" to kind, "schemaVersion" to 1, "businessId" to business, "contentVersion" to "v1", "encoding" to if (kind == "readerResource") "raw" else "none", "size" to bytes.size, "sha256" to ResumeDigests.hash(bytes), "chunkHashes" to JSONArray(if (bytes.isEmpty()) emptyList<String>() else listOf(ResumeDigests.hash(bytes))), "metadataBase64" to Base64.getEncoder().encodeToString(encoded), "metadataSha256" to ResumeDigests.hash(encoded), "dependsOn" to JSONArray(deps.sorted()))
            return d.put("objectId", ResumeDigests.objectId(d))
        }
        for (window in listOf(1, 4)) {
            val metadata = (0 until 4).map { index ->
                val original = ResumeJson.parse(ResumeJson.b64(v.a("readerObjects").getJSONObject(0).get("metadataBase64"), 16384), false)
                original.a("fonts").getJSONObject(0).put("id", "font-$window-$index")
                original.a("deletions").values().forEach { (it as JSONObject).put("entityId", "font-$window-$index") }
                make("readerMetadata", "font:font-$window-$index", original, byteArrayOf())
            }
            val minimum = metadata.minOf { it.s("objectId") }
            val resources = metadata.mapIndexed { index, m ->
                val bytes = byteArrayOf(120)
                val d = make("readerResource", "resource-$window-$index", obj("kind" to "font", "fileName" to "font-$index.ttf", "sha256" to ResumeDigests.hash(bytes), "byteCount" to 1), bytes, listOf(m.s("objectId")))
                var nonce = 0
                do { d.put("contentVersion", "v" + nonce++); d.put("objectId", ResumeDigests.objectId(d)) } while (d.s("objectId") >= minimum)
                d
            }
            val all = (metadata + resources).sortedBy { it.s("objectId") }
            assertTrue(all.take(4).all { it.s("kind") == "readerResource" })
            val limits = JSONObject(ResumeWire.hardLimits as Map<*, *>).put("maxInFlightObjects", window)
            val selection = obj("kinds" to JSONArray(listOf("readerMetadata", "readerResource").map { obj("kind" to it, "schemaVersion" to 1) }), "limits" to limits)
            val localJob = window.toString().repeat(32)
            val beginPayload = copy(p).put("selection", selection).put("selectionDigest", ResumeDigests.selection(selection)).put("objectCount", 8).put("objectsRoot", ResumeDigests.objects(all.map { it.s("objectId") })).put("pageObjectCounts", JSONArray(listOf(8)))
            val localPlan = ResumeDigests.plan(beginPayload)
            val auth = ResumeAuthorization(scope, v.s("phoneDeviceId"), v.s("watchDeviceId"), v.o("phoneSnapshot"), v.o("watchSnapshot"), listOf(obj("direction" to "watchToPhone", "jobId" to localJob, "intent" to "create", "selection" to selection))) { true }
            val r = ResumeReceiver(store, files, auth, port, { now })
            fun request(phase: String, payload: JSONObject, epoch: Long?) = ResumeWire.request(phase, localJob, localPlan, epoch, payload)
            val e = r.handle(request("begin", beginPayload, null)).n("epoch")
            val page = obj("pageIndex" to 0, "startIndex" to 0, "previousPageDigest" to "0".repeat(64), "pageDigest" to "0".repeat(64), "entries" to JSONArray(all)); page.put("pageDigest", ResumeDigests.page(localPlan, page))
            r.handle(request("manifestPage", page, e))
            for (d in resources) expect("DEPENDENCIES_PENDING") { r.handle(request("putChunk", obj("objectId" to d.s("objectId"), "chunkIndex" to 0, "dataBase64" to "eA=="), e)) }
            assertEquals(0L, files.usedBytes())
            for (i in 0 until 4) {
                r.handle(request("apply", obj("objectId" to metadata[i].s("objectId")), e))
                r.handle(request("putChunk", obj("objectId" to resources[i].s("objectId"), "chunkIndex" to 0, "dataBase64" to "eA=="), e))
                r.handle(request("apply", obj("objectId" to resources[i].s("objectId")), e))
                assertEquals(0L, files.usedBytes())
            }
            val observed = r.handle(request("missing", obj("afterObjectId" to null, "afterChunkIndex" to null, "limit" to 16), e)).o("result")
            assertEquals("APPLIED", observed.s("state")); assertTrue(observed.a("items").values().all { (it as JSONObject).s("state") == "APPLIED" })
            r.handle(request("cancel", obj("reason" to "LOCAL_ABORT"), e))
        }
    }

    @Test fun manifestReplayWithNewOperationIdReturnsOriginalDurablePageReceipt() = runBlocking {
        val r = receiver(); val e = begin(r); manifest(r, e)
        val page = obj("pageIndex" to 0, "startIndex" to 0, "previousPageDigest" to "0".repeat(64), "pageDigest" to "0".repeat(64), "entries" to v.a("objects")); page.put("pageDigest", ResumeDigests.page(plan, page))
        val replay = r.handle(frame("manifestPage", page, e, "f".repeat(32))).o("result")
        assertEquals("READY", replay.s("state")); assertEquals(1L, replay.n("nextManifestPage"))
        assertEquals(2, store.list(scope, "object").size)
    }
    @Test fun localDatabaseIoFailureRollsBusinessBackAndNeverBecomesTransportIo() = runBlocking {
        val r = receiver { if (it == "beforeApplyCommit") throw java.io.IOException("injected local write failure") }; val e = begin(r); manifest(r, e)
        expect("LOCAL_IO") { apply(r, e, rss) }; assertEquals(0, writes(rss)); assertNull(db.phoneRssSourceDao().getByUrl(rss.s("businessId")))
        val mode = db.openHelper.writableDatabase.query("PRAGMA synchronous").use { it.moveToFirst(); it.getInt(0) }
        assertEquals(2, mode)
    }
    @Test fun localCancellationFencesAndPersistsTombstoneWithoutWaitingForNetwork() = runBlocking {
        var r = receiver(); val e = begin(r); manifest(r, e); r.handle(frame("putChunk", chunk(0), e)); r.revoke(cancelJobs=true)
        expect("AUTH_REVOKED") { apply(r, e, rss) }; assertEquals(0L, files.usedBytes())
        reopen(); r = receiver("resume"); val next = begin(r, "resume"); assertEquals("CANCELLED", missing(r, next, 0).s("state")); assertEquals(0, writes(rss))
    }

    @Test fun lateOldConnectionReplyCannotInstallOfferAfterNewProbe() = runBlocking {
        val snapshots = ResumeSnapshots(store, files); val witness = snapshots.freeze(scope, "late-offer", listOf(rss), { byteArrayOf() })
        val entered = CompletableDeferred<JSONObject>(); val release = CompletableDeferred<Unit>()
        val client = ResumeClient(store, snapshots, { request ->
            entered.complete(copy(request)); release.await()
            val payload = request.o("payload")
            ResumeResults.answer(request, obj("step" to "offer", "watchCapabilities" to v.o("capability"), "watchSnapshot" to v.o("watchSnapshot"), "offerDigest" to ResumeDigests.offer(payload, v.o("capability"), v.o("watchSnapshot"))), null)
        }, {})
        client.acceptProbe(obj("libraryResume" to v.o("capability")))
        val old = async { runCatching { client.offer(scope, v.s("phoneDeviceId"), v.s("watchDeviceId"), v.o("capability"), witness) } }
        entered.await(); client.acceptProbe(obj("version" to 14)); release.complete(Unit)
        assertEquals("STALE_EPOCH", (old.await().exceptionOrNull() as ResumeException).code)
    }

    @Test fun receiverCanCancelPhoneSourceWithoutAnotherApplyOrNetworkReconnect() = runBlocking {
        val snapshots = ResumeSnapshots(store, files); val witness = snapshots.freeze(scope, "source-cancel", listOf(rss), { byteArrayOf() })
        val capability = ResumeNegotiation.capability(setOf("rssSource"), setOf("rssSource"))
        val queried = CompletableDeferred<Unit>(); val cancelled = CompletableDeferred<Unit>()
        val client = ResumeClient(store, snapshots, { r ->
            val payload = r.o("payload")
            val result = when (r.s("phase")) {
                "prepare" -> if (payload.s("step") == "offer") obj("step" to "offer", "watchCapabilities" to capability, "watchSnapshot" to v.o("watchSnapshot"), "offerDigest" to ResumeDigests.offer(payload, capability, v.o("watchSnapshot"))) else obj("step" to "confirm", "preparationDigest" to payload.s("preparationDigest"))
                "begin" -> obj("disposition" to "CREATED", "selectionDigest" to payload.s("selectionDigest"), "state" to "PLANNING", "expiresAtMillis" to 86400000, "nextManifestPage" to 0, "completion" to null)
                "manifestPage" -> obj("pageIndex" to 0, "pageDigest" to payload.s("pageDigest"), "nextManifestPage" to 1, "manifestVerified" to true, "state" to "READY")
                "missing" -> { queried.complete(Unit); cancelled.await(); obj("state" to "CANCELLED", "nextManifestPage" to 1, "expiresAtMillis" to 86400000, "items" to JSONArray(), "next" to null, "done" to true, "completion" to null) }
                else -> throw AssertionError("unexpected outgoing phase")
            }
            ResumeResults.answer(r, result, if (r.s("phase") == "prepare") null else 1L)
        }, {}, { cancelled.complete(Unit) })
        client.acceptProbe(obj("libraryResume" to capability)); val offered = client.offer(scope, v.s("phoneDeviceId"), v.s("watchDeviceId"), capability, witness)
        val binding = obj("direction" to "phoneToWatch", "jobId" to job, "intent" to "create", "selection" to ResumeNegotiation.select(capability, offered.o("watchCapabilities"), "phoneToWatch")!!)
        client.confirm(listOf(binding)) {}
        val sending = async { runCatching { client.send(job) } }; queried.await()
        val local = store.get(scope, "sourceJob", job)!!
        val response = client.handleSourceCancel(ResumeWire.request("cancel", job, local.s("planDigest"), 1, obj("reason" to "USER_CANCEL")))
        assertEquals("CANCELLED", response.o("result").s("state")); assertEquals("JOB_CANCELLED", (sending.await().exceptionOrNull() as ResumeException).code)
        assertEquals("CANCELLED", store.get(scope, "sourceJob", job)!!.s("state")); assertEquals(0, writes(rss))
    }

    @Test fun originalManifestFrameWhitespaceCountsTowardNegotiatedPageLimit() = runBlocking {
        val r = receiver(); val e = begin(r)
        val page = obj("pageIndex" to 0, "startIndex" to 0, "previousPageDigest" to "0".repeat(64), "pageDigest" to "0".repeat(64), "entries" to v.a("objects")); page.put("pageDigest", ResumeDigests.page(plan, page))
        val request = frame("manifestPage", page, e)
        val padded = ResumeJson.utf8(" ".repeat(131072) + request.toString())
        val packet = java.io.ByteArrayOutputStream(); java.io.DataOutputStream(packet).apply { writeInt(padded.size); write(padded) }
        val decoded = ResumeWire.read(java.io.ByteArrayInputStream(packet.toByteArray()))
        expect("LIMIT_EXCEEDED") { r.handle(decoded) }; assertEquals(0, store.list(scope, "object").size)
    }

}
