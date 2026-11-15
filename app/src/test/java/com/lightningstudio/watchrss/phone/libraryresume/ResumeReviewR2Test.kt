package com.lightningstudio.watchrss.phone.libraryresume

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.lightningstudio.watchrss.phone.data.db.*
import kotlinx.coroutines.*
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.*

/** Regression cases adapted from the independent RVPHONEC probes. Assertions verify
 * protocol success/rejection rather than the former erroneous behavior. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, manifest = Config.NONE, sdk = [35])
class ResumeReviewR2Test {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val db = Room.inMemoryDatabaseBuilder(context, PhoneCompanionDatabase::class.java).allowMainThreadQueries().build()
    private val store = ResumePersistence(db)
    private val root = java.nio.file.Files.createTempDirectory("rvphonec-probe-").toFile()
    private var files = ResumeFiles(root)
    private val businessRoot = java.nio.file.Files.createTempDirectory("rvphonec-business-").toFile()
    private val v = vectors()
    private val job = "d".repeat(32)
    private val scope = v.s("accountScope")
    private val base = copy(v.a("requests").values().map { it as JSONObject }.first { it.s("name") == "begin" }.o("frame").o("payload")).put("direction", "watchToPhone")
    private val port = object : ResumeBusinessPort {
        override val kinds = ResumeWire.kinds.toSet()
        override suspend fun apply(db: PhoneCompanionDatabase, descriptor: JSONObject, metadata: JSONObject, staged: File): String {
            val key = "business-count-" + descriptor.s("objectId")
            val count = db.appMetaDao().getString(key)?.toInt() ?: 0
            db.appMetaDao().set(AppMetaEntity(key, (count + 1).toString()))
            staged.copyTo(File(businessRoot, descriptor.s("objectId")), overwrite = false)
            return "merged"
        }
    }
    @After fun cleanup() { db.close(); root.deleteRecursively(); businessRoot.deleteRecursively() }
    private fun receiver(p: JSONObject = base, intent: String = "create", fault: (String) -> Unit = {}): ResumeReceiver {
        val a = ResumeAuthorization(scope, v.s("phoneDeviceId"), v.s("watchDeviceId"), v.o("phoneSnapshot"), v.o("watchSnapshot"), listOf(obj("direction" to "watchToPhone", "jobId" to job, "intent" to intent, "selection" to p.o("selection")))) { true }
        return ResumeReceiver(store, files, a, port, { 1000L }, fault)
    }
    private fun req(phase: String, p: JSONObject, epoch: Long?, desc: JSONObject = base, id: String = java.util.UUID.randomUUID().toString().replace("-", "")) = ResumeWire.request(phase, job, ResumeDigests.plan(desc), epoch, p, id)
    private fun page(p: JSONObject = base, entries: JSONArray = v.a("objects")): JSONObject {
        val page = obj("pageIndex" to 0, "startIndex" to 0, "previousPageDigest" to "0".repeat(64), "pageDigest" to "0".repeat(64), "entries" to entries)
        return page.put("pageDigest", ResumeDigests.page(ResumeDigests.plan(p), page))
    }
    private fun padded(frame: JSONObject, count: Int): JSONObject {
        val bytes = ResumeJson.utf8(" ".repeat(count) + frame.toString())
        val out = ByteArrayOutputStream(); DataOutputStream(out).apply { writeInt(bytes.size); write(bytes) }
        return ResumeWire.read(ByteArrayInputStream(out.toByteArray()))
    }
    @Test fun manifestReplayRejectsOriginalFrameAbovePageLimitBeforeReceiptAdoption() = runBlocking {
        val r = receiver(); val e = r.handle(req("begin", base, null)).n("epoch")
        val request = req("manifestPage", page(), e)
        r.handle(request)
        val replay = padded(request, 131072)
        assertTrue(ResumeWire.frameBytes(replay) > base.o("selection").o("limits").n("maxManifestPageBytes"))
        val before = store.list(scope, "request").size
        assertError("LIMIT_EXCEEDED") { runBlocking { r.handle(replay) } }
        assertEquals(before, store.list(scope, "request").size)
        assertEquals("READY", r.handle(request).o("result").s("state"))
    }
    private suspend fun crashCleanup(trigger: String) {
        var allowDeletion = trigger !in listOf("missing", "admission")
        var deletionAttempts = 0
        files = ResumeFiles(root) { file -> deletionAttempts++; if (allowDeletion) file.delete() else false }
        fun descriptor(id: String): JSONObject {
            val b = ByteArray(65536) { 120 }; val hash = ResumeDigests.hash(b)
            val meta = ResumeJson.utf8(obj("kind" to "font", "fileName" to "$id.ttf", "sha256" to hash, "byteCount" to 65536).toString())
            val d = obj("kind" to "readerResource", "schemaVersion" to 1, "businessId" to id, "contentVersion" to "v1", "encoding" to "raw", "size" to 65536, "sha256" to hash, "chunkHashes" to JSONArray(listOf(hash)), "metadataBase64" to java.util.Base64.getEncoder().encodeToString(meta), "metadataSha256" to ResumeDigests.hash(meta), "dependsOn" to JSONArray())
            return d.put("objectId", ResumeDigests.objectId(d))
        }
        val ds = listOf(descriptor("one"), descriptor("two")).sortedBy { it.s("objectId") }
        val p = copy(base); val selection = copy(p.o("selection")); selection.o("limits").put("maxStagingBytes", 131072); selection.put("kinds", JSONArray(listOf("readerMetadata", "readerResource").map { obj("kind" to it, "schemaVersion" to 1) }))
        p.put("selection", selection).put("selectionDigest", ResumeDigests.selection(selection)).put("objectsRoot", ResumeDigests.objects(ds.map { it.s("objectId") }))
        var crash = true; var r = receiver(p, fault = { if (it == "afterApplyCommit" && crash) throw IllegalStateException("crash") })
        var e = r.handle(req("begin", p, null, p)).n("epoch"); r.handle(req("manifestPage", page(p, JSONArray(ds)), e, p))
        fun chunk(d: JSONObject) = obj("objectId" to d.s("objectId"), "chunkIndex" to 0, "dataBase64" to java.util.Base64.getEncoder().encodeToString(ByteArray(65536) { 120 }))
        r.handle(req("putChunk", chunk(ds[0]), e, p))
        try { r.handle(req("apply", obj("objectId" to ds[0].s("objectId")), e, p)); throw AssertionError() } catch (_: IllegalStateException) {}
        assertEquals(131072L, files.usedBytes()); crash = false
        val originalReceipt = store.list(scope, "object").map { it.second }.first { !it.nil("receipt") }.o("receipt")
        if (trigger != "apply") {
            r = receiver(p, "resume"); e = r.handle(req("begin", copy(p).put("intent", "resume"), null, p)).n("epoch")
        }
        if (trigger == "begin") assertEquals("resume must reclaim cache before an apply replay", 0L, files.usedBytes())
        val replay = r.handle(req("apply", obj("objectId" to ds[0].s("objectId")), e, p)).o("result")
        assertEquals("APPLIED", replay.s("state"))
        assertEquals(originalReceipt.s("resultDigest"), replay.o("receipt").s("resultDigest"))
        if (!allowDeletion) {
            assertTrue(deletionAttempts > 0)
            assertEquals("failed cleanup must preserve the durable receipt", "1", db.appMetaDao().getString("business-count-" + ds[0].s("objectId")))
            assertEquals(131072L, files.usedBytes())
            allowDeletion = true
            if (trigger == "missing") r.handle(req("missing", obj("afterObjectId" to null, "afterChunkIndex" to null, "limit" to 0), e, p))
        }
        if (trigger != "admission") assertEquals(0L, files.usedBytes())
        assertEquals("1", db.appMetaDao().getString("business-count-" + ds[0].s("objectId")))
        val business = File(businessRoot, ds[0].s("objectId"))
        assertEquals(ds[0].s("sha256"), ResumeDigests.hash(business.readBytes()))
        r.handle(req("putChunk", chunk(ds[1]), e, p))
        r.handle(req("apply", obj("objectId" to ds[1].s("objectId")), e, p))
        assertEquals(0L, files.usedBytes())
        assertEquals("1", db.appMetaDao().getString("business-count-" + ds[0].s("objectId")))
        assertEquals("1", db.appMetaDao().getString("business-count-" + ds[1].s("objectId")))
        assertEquals(ds[0].s("sha256"), ResumeDigests.hash(business.readBytes()))
    }
    @Test fun appliedCrashReleasesOnlyDisposableCacheOnResumeAndAllowsNextObject() = runBlocking { crashCleanup("begin") }
    @Test fun appliedReplayReleasesCommittedCacheWithoutRunningBusinessAgain() = runBlocking { crashCleanup("apply") }
    @Test fun missingRetriesFailedCleanupWithoutRevokingReceiptOrDeletingBusinessFile() = runBlocking { crashCleanup("missing") }
    @Test fun nextObjectAdmissionRetriesFailedCleanupBeforeChargingQuota() = runBlocking { crashCleanup("admission") }

    @Test fun roomMigrationChainFromEveryHistoricalVersionPreservesRows() = runBlocking {
        val migrations = (1..19).map { n -> PhoneCompanionDatabase.Companion::class.java.getMethod("getMIGRATION_${n}_${n+1}").invoke(PhoneCompanionDatabase.Companion) as androidx.room.migration.Migration }
        for (version in 1..19) {
            val name = "rvphonec-migration-$version-${System.nanoTime()}"
            val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(object : SupportSQLiteOpenHelper.Callback(version) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    val sql = db
                    sql.execSQL("CREATE TABLE phone_saved_items (type TEXT NOT NULL,stableKey TEXT NOT NULL,remoteId INTEGER NOT NULL,title TEXT NOT NULL,link TEXT NOT NULL,summary TEXT NOT NULL,channelTitle TEXT NOT NULL,pubDate TEXT NOT NULL,syncedAt INTEGER NOT NULL,PRIMARY KEY(type,stableKey))")
                    for (n in 1 until version) migrations[n-1].migrate(sql)
                    sql.execSQL("INSERT INTO phone_saved_items VALUES('favorite','old',7,'keep title','https://fixture.invalid','keep body','source','date',9)")
                    if (version >= 9) sql.execSQL("INSERT INTO app_meta VALUES('review-preserve','keep')")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) { throw AssertionError() }
            }).build())
            helper.writableDatabase; helper.close()
            val upgraded = Room.databaseBuilder(context, PhoneCompanionDatabase::class.java, name).addMigrations(*migrations.toTypedArray()).allowMainThreadQueries().build()
            try {
                upgraded.openHelper.writableDatabase.query("SELECT title,summary FROM phone_saved_items WHERE stableKey='old'").use { assertTrue(it.moveToFirst()); assertEquals("keep title", it.getString(0)); assertEquals("keep body", it.getString(1)) }
                if (version >= 9) assertEquals("keep", upgraded.appMetaDao().getString("review-preserve"))
                assertEquals(0, upgraded.resumeDao().all().size)
            } finally { upgraded.close(); context.deleteDatabase(name) }
        }
    }
    @Test fun reverseSourceCancelRejectsOriginalFrameAboveNegotiatedLimitWithoutTombstone() = runBlocking {
        val rss = v.a("objects").values().map { it as JSONObject }.first { it.s("kind") == "rssSource" }
        val snapshots = ResumeSnapshots(store, files); val witness = snapshots.freeze(scope, "source-bound-probe", listOf(rss), { byteArrayOf() })
        val limits = JSONObject(ResumeWire.hardLimits as Map<*, *>).put("maxFrameBytes", 90000)
        val capability = ResumeNegotiation.capability(setOf("rssSource"), setOf("rssSource"), limits)
        val queried = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val client = ResumeClient(store, snapshots, { request ->
            val p = request.o("payload")
            val result = when (request.s("phase")) {
                "prepare" -> if (p.s("step") == "offer") obj("step" to "offer", "watchCapabilities" to capability, "watchSnapshot" to v.o("watchSnapshot"), "offerDigest" to ResumeDigests.offer(p, capability, v.o("watchSnapshot"))) else obj("step" to "confirm", "preparationDigest" to p.s("preparationDigest"))
                "begin" -> obj("disposition" to "CREATED", "selectionDigest" to p.s("selectionDigest"), "state" to "PLANNING", "expiresAtMillis" to 86400000, "nextManifestPage" to 0, "completion" to null)
                "manifestPage" -> obj("pageIndex" to 0, "pageDigest" to p.s("pageDigest"), "nextManifestPage" to 1, "manifestVerified" to true, "state" to "READY")
                "missing" -> { queried.complete(Unit); finish.await(); obj("state" to "CANCELLED", "nextManifestPage" to 1, "expiresAtMillis" to 86400000, "items" to JSONArray(), "next" to null, "done" to true, "completion" to null) }
                else -> throw AssertionError("unexpected request")
            }
            ResumeResults.answer(request, result, if (request.s("phase") == "prepare") null else 1L)
        }, {})
        client.acceptProbe(obj("libraryResume" to capability))
        val offered = client.offer(scope, v.s("phoneDeviceId"), v.s("watchDeviceId"), capability, witness)
        val selection = ResumeNegotiation.select(capability, offered.o("watchCapabilities"), "phoneToWatch")!!
        client.confirm(listOf(obj("direction" to "phoneToWatch", "jobId" to job, "intent" to "create", "selection" to selection))) {}
        val sending = async { runCatching { client.send(job) } }
        try {
            queried.await(); val local = store.get(scope, "sourceJob", job)!!
            val request = padded(ResumeWire.request("cancel", job, local.s("planDigest"), 1, obj("reason" to "USER_CANCEL")), 100000)
            assertTrue(ResumeWire.frameBytes(request) > 90000)
            val before = store.list(scope, "request").size
            assertError("LIMIT_EXCEEDED") { runBlocking { client.handleSourceCancel(request) } }
            assertEquals(before, store.list(scope, "request").size)
            assertEquals("PLANNING", store.get(scope, "sourceJob", job)!!.s("state"))
            val replay = padded(ResumeWire.request("cancel", job, local.s("planDigest"), 1, obj("reason" to "USER_CANCEL")), 1000)
            assertEquals("CANCELLED", client.handleSourceCancel(replay).o("result").s("state"))
        } finally { finish.complete(Unit); sending.await() }
    }

}
