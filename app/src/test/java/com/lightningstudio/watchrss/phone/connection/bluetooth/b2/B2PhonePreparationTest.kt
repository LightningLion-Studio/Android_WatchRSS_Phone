package com.lightningstudio.watchrss.phone.connection.bluetooth.b2

import android.content.Context
import androidx.room.Room
import com.lightningstudio.watchrss.phone.connection.bluetooth.*
import com.lightningstudio.watchrss.phone.data.ble.BleDomainStore
import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
import com.lightningstudio.watchrss.phone.data.db.PhoneCompanionDatabase
import com.lightningstudio.watchrss.phone.data.db.PhoneLlmTokenUsageRepository
import com.lightningstudio.watchrss.phone.data.importer.WebArticleImporter
import com.lightningstudio.watchrss.phone.data.local.FileArticleContentStore
import com.lightningstudio.watchrss.phone.data.log.BluetoothDebugLog
import com.lightningstudio.watchrss.phone.data.note.NoteRepository
import com.lightningstudio.watchrss.phone.data.reader.ReaderPresetRepository
import com.lightningstudio.watchrss.phone.data.repo.PhoneCompanionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = android.app.Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class B2PhonePreparationTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val root = Files.createTempDirectory("b2-phone-prepare").toFile()
    private fun openDatabase() = Room.databaseBuilder(context, PhoneCompanionDatabase::class.java,
        File(root, "phone.db").absolutePath).allowMainThreadQueries().build()
    private var db = openDatabase()
    private val content = FileArticleContentStore(File(root, "content"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val device = PhoneBluetoothWatchDevice("watch", "11:22:33:44:55:66", 1, "watch")
    private val kinds = setOf(B2_ARTICLE_BODY, B2_ARTICLE_METADATA, B2_RSS_SOURCE)
    private var account = B2Digests.account("https://example.invalid", "owner")
    private val manager by lazy {
        val repo = PhoneCompanionRepository(db.phoneSavedItemDao(), db.phoneArticleDao(),
            db.phoneRssSourceDao(), "phone", syncChangeLogDao = db.syncChangeLogDao(),
            appMetaDao = db.appMetaDao(), articleContentStore = content,
            bleDomains = BleDomainStore(db, File(root, "ble")))
        PhoneBluetoothSyncManager(context, repo, NoteRepository(db.noteDao(), "phone"),
            ReaderPresetRepository(context, db, db.readerPresetDao(), "phone", scope),
            PhoneLlmTokenUsageRepository(db.llmTokenUsageDao()), "phone", BluetoothDebugLog(context),
            canSyncAccount = { true }, currentPhoneUserId = { "owner" }, b2Database = { db },
            b2AccountScope = { account })
    }

    @After fun close() {
        scope.cancel()
        db.close()
        root.deleteRecursively()
    }

    @Test fun guardedOfferUsesTheIdentifiedRfcommConnectionAndChecksReplyBeforeAck() = runBlocking<Unit> {
        val discovery = fixture("peer-fixtures.json").getJSONArray("cases").getJSONObject(0).getJSONObject("probe")
            .put("deviceId", "watch")
        val podjs = JSONObject(discovery.toString()).apply { remove("supportsAccountDataSpaces") }
        val reply = probe().put("deviceId", "watch").put("guardedLibraryVersion", 1)
            .put("supportsPersistentSession", true)
        val client = PhoneBluetoothSyncClient(context, BluetoothDebugLog(context))
        fun bind(initial: JSONObject, response: JSONObject, output: ByteArrayOutputStream,
            owner: String = "rfcomm"): BleSyncBindingSnapshot {
            val bytes = ByteArrayOutputStream().also { BluetoothSyncProtocol.writeFrame(it, response) }
            val session = PhoneSyncSession(client, device, "phone",
                PhoneSyncSession.Transport(bytes.toByteArray().inputStream(), output, owner, false) {},
                true, false, false)
            return BleSyncBindingSnapshot(device.address, "owner", session, initial)
        }
        val output = ByteArrayOutputStream()
        bind(discovery, reply, output).session!!.use {
            assertNull(manager.guardedLibraryProbe(BleSyncBindingSnapshot(device.address, "owner", it, discovery), "compose"))
        }
        val ip = bind(podjs, reply, output, owner = "ip:wifiLan")
        ip.session!!.use { assertNull(manager.guardedLibraryProbe(ip, "ip")) }
        assertEquals(0, output.size())

        val valid = bind(podjs, reply, output)
        valid.session!!.use { assertEquals(1, manager.guardedLibraryProbe(valid, "podjs")!!.getInt("guardedLibraryVersion")) }
        val wire = output.toByteArray().inputStream()
        assertEquals("[1]", BluetoothSyncProtocol.readFrame(wire).getJSONArray("supportedGuardedLibraryVersions").toString())
        assertEquals("ack", BluetoothSyncProtocol.readFrame(wire).getString("action"))
        assertEquals(0, wire.available())

        output.reset()
        val wrongPeer = bind(podjs, reply.put("deviceId", "different-watch"), output)
        wrongPeer.session!!.use {
            assertEquals("CAPABILITY_MISMATCH",
                (runCatching { manager.guardedLibraryProbe(wrongPeer, "wrong-peer") }.exceptionOrNull() as B2Exception).code)
            assertFalse(it.isPersistent)
        }
        val rejectedWire = output.toByteArray().inputStream()
        BluetoothSyncProtocol.readFrame(rejectedWire)
        assertEquals(0, rejectedWire.available())
    }

    @Test fun watchProbeLimitsCoverSingleAndAggregateBodyPeaks() {
        val limits = B2Wire.s2Limits().put("maxJournalBytes", 32L * MIB)
        for (delta in -1..0) {
            B2WatchReceiveBudget(limits).add(descriptor(16 * MIB + delta))
            B2WatchReceiveBudget(limits).apply {
                add(descriptor(8 * MIB))
                add(descriptor(8 * MIB + delta))
            }
        }
        assertEquals("LIMIT_EXCEEDED", assertThrows(B2Exception::class.java) {
            B2WatchReceiveBudget(limits).add(descriptor(16 * MIB + 1))
        }.code)
        assertEquals("LIMIT_EXCEEDED", assertThrows(B2Exception::class.java) {
            B2WatchReceiveBudget(limits).apply {
                add(descriptor(8 * MIB))
                add(descriptor(8 * MIB + 1))
            }
        }.code)

        val small = descriptor(3 * MIB)
        val peak = 64L * MIB + 4L * (3 * MIB)
        B2WatchReceiveBudget(B2Wire.s2Limits().put("maxStagingBytes", peak).put("maxObjectBytes", 3 * MIB))
            .add(small)
        assertEquals("LIMIT_EXCEEDED", assertThrows(B2Exception::class.java) {
            B2WatchReceiveBudget(B2Wire.s2Limits().put("maxStagingBytes", peak - 1)).add(small)
        }.code)
        assertEquals("LIMIT_EXCEEDED", assertThrows(B2Exception::class.java) {
            B2WatchReceiveBudget(B2Wire.s2Limits().put("maxObjectBytes", 3 * MIB - 1)).add(small)
        }.code)
    }

    @Test fun assetOnlyStagingDoesNotChargeTheJournalBudget() {
        val limits = B2Wire.s2Limits().put("maxStagingBytes", 8 * MIB).put("maxJournalBytes", 32 * MIB)
        B2WatchReceiveBudget(limits).add(descriptor(2 * MIB).put("kind", B2_NOTE_ASSET))
        assertEquals("LIMIT_EXCEEDED", assertThrows(B2Exception::class.java) {
            B2WatchReceiveBudget(limits).add(descriptor(2 * MIB + 1).put("kind", B2_NOTE_ASSET))
        }.code)
    }

    @Test fun preparationUsesLegacyBeforeSwitchForOneLargeOrMultipleFittingBodies() = runBlocking<Unit> {
        val text = java.util.Base64.getEncoder().encodeToString(Random(4).nextBytes(64 * 1024))
        db.phoneArticleDao().upsert(article("1".repeat(64)).copy(contentText = text))
        val smallObject = probe(objectBytes = 32768)
        val (limited, limitedRuntime) = prepare(smallObject)
        assertTrue(limited.sendLegacyArticles)
        assertTrue(limited.sendLegacySources)
        assertTrue(limited.isAssetOnly)
        assertNotNull(limitedRuntime)

        val staging = probe(stagingBytes = 64 * MIB + 330_000)
        val (fitting, runtime) = prepare(staging)
        assertFalse(fitting.sendLegacyArticles)
        val written = ByteArrayOutputStream()
        try {
            requireNotNull(runtime).run(ByteArrayInputStream(ByteArray(0)), written, smallObject, emptyList()) {}
            fail("An already-frozen body must be checked against the new probe before switching")
        } catch (e: B2Exception) {
            assertEquals("LIMIT_EXCEEDED", e.code)
        }
        assertEquals(0, written.size())
        assertTrue(prepare(smallObject).first.sendLegacyArticles)

        db.phoneArticleDao().upsert(article("2".repeat(64)).copy(contentText = text))
        account = B2Digests.account("https://example.invalid", "second-owner")
        val (aggregate, _) = prepare(staging)
        assertTrue(aggregate.sendLegacyArticles)
        assertTrue(aggregate.sendLegacySources)
        assertTrue(aggregate.isAssetOnly)
        assertEquals(2, db.phoneArticleDao().getAllMetadataForSync().size)
    }

    @Test fun oversizedOldSnapshotRetiresWhenBeginAckWasLost() = runBlocking<Unit> {
        val store = B2RoomStore(db)
        val directory = File(root, "b2")
        val files = B2Files(directory)
        val bookId = "d".repeat(64)
        val source = File(root, "oversized-state.json").apply {
            writeText(obj("title" to "x".repeat(1024 * 1024)).toString())
        }
        val state = binaryDescriptor(B2Wire.BOOK_STATE, bookId, obj("bookId" to bookId), binaryFileHashes(source))
        val snapshot = B2Snapshots(store, files).freeze(account, "old", listOf(state)) { source }
        val jobId = "e".repeat(32)
        val bookKinds = B2Wire.bookKinds
        val capability = B2Negotiation.capability(bookKinds, bookKinds, B2Wire.s4Limits())
        val selection = requireNotNull(B2Negotiation.select(capability, capability, "phoneToWatch"))
        val watcher = obj("snapshotId" to "watch-old", "manifestDigest" to "f".repeat(64), "fromExclusive" to 0, "toInclusive" to 0)
        val description = obj("intent" to "create", "accountScope" to account, "phoneDeviceId" to "phone",
            "watchDeviceId" to "watch", "direction" to "phoneToWatch", "phoneSnapshot" to snapshot,
            "watchSnapshot" to watcher, "selection" to selection, "selectionDigest" to B2Digests.selection(selection),
            "objectCount" to 1, "objectsRoot" to B2Digests.objects(listOf(state.s("objectId"))),
            "pageObjectCounts" to JSONArray(listOf(1)))
        val binding = obj("direction" to "phoneToWatch", "jobId" to jobId, "intent" to "create", "selection" to selection)
        val kindList = bookKinds.sorted().joinToString(",")
        val attemptKey = B2Digests.d("wrss.library-binary.v2/kind-attempt", listOf(account, "phone", "watch", kindList, kindList))
        store.exclusive {
            store.put(account, "attempt", attemptKey, obj("state" to "PREPARED", "phoneSnapshot" to snapshot,
                "watchSnapshot" to watcher, "bindings" to JSONArray(listOf(binding))))
            // B2Client persists the plan before sending begin. The state and epoch come from its ACK.
            store.put(account, "sourceJob", jobId, obj("planDigest" to B2Digests.plan(description), "description" to description))
        }
        val repo = PhoneCompanionRepository(db.phoneSavedItemDao(), db.phoneArticleDao(), db.phoneRssSourceDao(), "phone")
        val assets = File(root, "assets")
        val business = B2InheritedKinds(db, repo, BluetoothArticleIdentityProjection(emptyList(), emptyMap(), false),
            B2NoteAssetBusiness(store, account, assets) {}, bookKinds, false, {})
        val runtime = B2KindSync(store, directory, assets, account, "phone", "watch", {}, {},
            B2KindRoute(bookKinds, bookKinds), business)

        runtime.prepare(obj("libraryBinary" to capability), emptyList())

        assertEquals("RETIRING", store.get(account, "attempt", attemptKey)?.getString("state"))
        assertEquals("CANCELLED", store.get(account, "sourceJob", jobId)?.getString("state"))
        assertFalse(files.frozen(account, snapshot.s("snapshotId"), state.s("objectId")).exists())
    }

    @Test fun preparedArticleUsesEightInFlightAndAcceptsBatchedAcks() = runBlocking<Unit> {
        val text = java.util.Base64.getEncoder().encodeToString(Random(12).nextBytes(500_000))
        db.phoneArticleDao().upsert(article("3".repeat(64)).copy(contentText = text))
        val probe = probe(includeAssets = false, limits = B2Wire.s3Limits())
        val (route, runtime) = prepare(probe)
        assertFalse(route.sendLegacyArticles)
        assertTrue(route.receive.isEmpty())
        withTimeout(30_000) {
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
                Socket(listener.inetAddress, listener.localPort).use { phone ->
                    listener.accept().use { watch ->
                        watch.soTimeout = 10_000
                        val peer = async(Dispatchers.IO) { receiveArticleWindow(watch, probe, text) }
                        val stats = requireNotNull(runtime).run(phone.getInputStream(), phone.getOutputStream(),
                            probe, emptyList(), close = phone::close)
                        peer.await()
                        assertEquals(1, stats.sent)
                    }
                }
            }
        }
    }

    private suspend fun receiveArticleWindow(watch: Socket, probe: JSONObject, text: String) {
        val input = watch.getInputStream()
        val output = watch.getOutputStream()
        val cap = probe.o("libraryBinary")
        val switch = BluetoothSyncProtocol.readFrame(input)
        assertEquals("switch", switch.s("phase"))
        assertEquals(B2Digests.capability(cap), switch.o("payload").s("watchCapabilityDigest"))
        BluetoothSyncProtocol.writeFrame(output, obj("action" to B2Wire.ACTION, "version" to 2,
            "phase" to "switch", "type" to "response", "requestId" to switch.s("requestId"),
            "success" to true, "result" to switch.o("payload")))

        fun request(phase: String) = (B2Frames.read(input) as B2Frame.Control).value.also {
            assertEquals(phase, it.s("phase"))
        }
        fun answer(value: JSONObject) = B2Frames.write(output, B2Frame.Control(value))

        val offer = request("prepare")
        val payload = offer.o("payload")
        val snapshot = obj("snapshotId" to "watch", "manifestDigest" to "d".repeat(64),
            "fromExclusive" to 0, "toInclusive" to 0)
        answer(B2Results.answer(offer, obj("step" to "offer", "watchCapabilities" to cap,
            "watchSnapshot" to snapshot, "offerDigest" to B2Digests.offer(payload, cap, snapshot)), null))
        val confirm = request("prepare")
        val binding = confirm.o("payload").a("bindings").getJSONObject(0)
        assertEquals(1, confirm.o("payload").a("bindings").length())
        val selection = binding.o("selection").o("limits")
        assertEquals(8, selection.getInt("maxInFlightChunks"))
        assertEquals(524288, selection.getInt("maxInFlightBytes"))
        val authorization = B2Authorization(account, "phone", "watch", payload.o("phoneSnapshot"),
            snapshot, listOf(binding)) { true }
        val importer = object : B2BusinessPort {
            override val kinds = this@B2PhonePreparationTest.kinds

            override suspend fun apply(descriptor: JSONObject, metadata: JSONObject, staged: File): String {
                assertEquals(B2_ARTICLE_BODY, descriptor.s("kind"))
                val decoded = File(root, "watch-text")
                ArticleSyncBody.decodeFileBody(staged, File(root, "watch-html"), decoded)
                assertEquals(text, decoded.readText())
                return "merged"
            }
        }
        val receiver = B2Receiver(MemoryB2Store(), B2Files(File(root, "watch-spool")),
            authorization, importer, receiveDirection = "phoneToWatch")
        try {
            answer(B2Results.answer(confirm, obj("step" to "confirm",
                "preparationDigest" to confirm.o("payload").s("preparationDigest")), null))
            answer(receiver.handle(request("begin")))
            val page = request("manifestPage")
            assertEquals(8, page.o("payload").a("entries").getJSONObject(0).a("chunkHashes").length())
            answer(receiver.handle(page))
            answer(receiver.handle(request("missing")))

            // The receiver holds all ACKs until the negotiated window is full.
            val chunks = List(8) { index ->
                val frame = B2Frames.read(input) as B2Frame.Data
                assertEquals(index, frame.header.index)
                val (put, hash) = receiver.inspect(frame.header)
                assertEquals(hash, B2Digests.hash(frame.bytes))
                requireNotNull(receiver.writeChunk(frame, put))
            }
            for (batch in chunks.chunked(4)) {
                val commits = receiver.commitChunks(batch)
                val entries = commits.map { B2AckEntry(it.chunk.header.seq, it.disposition) }
                assertEquals(4, entries.size)
                B2Frames.write(output, requireNotNull(receiver.ack(1, batch.first().header.epoch, entries)))
            }
            answer(receiver.handle(request("apply")))
            val complete = receiver.handle(request("complete"))
            assertEquals("COMPLETE", complete.o("result").s("state"))
            answer(complete)
        } finally {
            receiver.closeIndex()
        }
    }

    @Test fun dualAliasLegacyDatabaseDoesNotMaterializeBodiesOrWriteBeforeRouting() = runBlocking<Unit> {
        val url = "https://example.invalid/feed/article"
        val legacy = WebArticleImporter.stableArticleId(url)
        val canonical = "a".repeat(64)
        seedLegacyAliases(legacy, canonical, url)
        db.close()
        db = openDatabase()
        val entry = manager
        val probe = probe(includeAssets = false)
        val projection = B2KindRoute.select(probe, includeNoteAssets = false, libraryReady = true)
        assertFalse(projection.sendLegacyArticles)
        val increments = List(5) {
            measureJvmHeapPeak {
                val (route, runtime) = entry.prepareBinaryRoute(projection, probe, device)
                assertEquals(B2KindRoute.legacy, route)
                assertNull(runtime)
            }
        }
        println("dualAliasPreparationJVMHeapPeakBytes=$increments")
        assertTrue("Peak JVM heap above the 20 MiB host limit: $increments", increments.max() <= 20L * MIB)

        val dbRows = db.phoneArticleDao().getAllMetadataForSync().associateBy { it.articleId }
        assertEquals(setOf(legacy, canonical), dbRows.keys)
        assertEquals(10L, dbRows.getValue(legacy).updatedAt)
        assertEquals(20L, dbRows.getValue(canonical).updatedAt)
        assertEquals(24L * MIB, content.openTextFile(dbRows.getValue(canonical).contentText)?.length())
        db.openHelper.readableDatabase.query("SELECT length(contentText) FROM phone_articles WHERE articleId=?",
            arrayOf(legacy)).use {
            assertTrue(it.moveToFirst())
            assertEquals(24L * MIB, it.getLong(0))
        }
        assertNull(db.appMetaDao().getString("ble_rss_identity_dedup_v2"))
        assertTrue(db.appMetaDao().getAll().isEmpty())
        assertTrue(db.phoneRssSourceDao().getAllForSync().isEmpty())
    }

    private suspend fun measureJvmHeapPeak(prepare: suspend () -> Unit): Long {
        // Android's compile stubs omit java.lang.management. These beans are provided by the host JVM.
        val factory = Class.forName("java.lang.management.ManagementFactory")
        val poolClass = Class.forName("java.lang.management.MemoryPoolMXBean")
        val memoryClass = Class.forName("java.lang.management.MemoryUsage")
        val getType = poolClass.getMethod("getType")
        val pools = (factory.getMethod("getMemoryPoolMXBeans").invoke(null) as List<*>)
            .filter { (getType.invoke(it) as Enum<*>).name == "HEAP" }
        assertTrue(pools.isNotEmpty())
        val resetPeak = poolClass.getMethod("resetPeakUsage")
        val usage = poolClass.getMethod("getUsage")
        val peakUsage = poolClass.getMethod("getPeakUsage")
        val usedBytes = memoryClass.getMethod("getUsed")
        System.gc()
        pools.forEach { resetPeak.invoke(it) }
        val initial = pools.sumOf { usedBytes.invoke(usage.invoke(it)) as Long }
        prepare()
        return pools.sumOf { usedBytes.invoke(peakUsage.invoke(it)) as Long } - initial
    }

    private suspend fun seedLegacyAliases(legacy: String, canonical: String, url: String) {
        db.phoneArticleDao().upsert(article(legacy, url).copy(contentText = "x".repeat(24 * MIB)))
        val file = File(root, "legacy-file")
        file.outputStream().use { output ->
            val chunk = ByteArray(64 * 1024) { 'y'.code.toByte() }
            repeat(24 * 16) { output.write(chunk) }
        }
        db.phoneArticleDao().upsert(article(canonical, url).copy(updatedAt = 20,
            contentText = content.publishTextFile(canonical, file)))
    }

    private suspend fun prepare(probe: JSONObject): Pair<B2KindRoute, B2KindSync?> =
        manager.prepareBinaryRoute(B2KindRoute.select(probe, includeNoteAssets = true, libraryReady = true), probe, device)

    private fun probe(objectBytes: Int = 16 * MIB, stagingBytes: Int = 128 * MIB,
        includeAssets: Boolean = true, limits: JSONObject = B2Wire.s2Limits()): JSONObject {
        val assets = if (includeAssets) setOf(B2_NOTE_ASSET) else emptySet()
        val cap = B2Negotiation.capability(assets + B2_RSS_SOURCE, kinds + assets, limits
            .put("maxObjectBytes", objectBytes).put("maxStagingBytes", stagingBytes).put("maxJournalBytes", 32 * MIB)
            .put("maxPlanObjects", 512).put("maxManifestBytes", 262144))
        return obj("deviceId" to "watch", "success" to true, "version" to 14, "action" to "syncLibrary", "phase" to "probe",
            "libraryPeer" to obj("implementation" to "podjs", "appVersion" to "2.0.3"),
            "supportsRssCanonicalIds" to true, "libraryBinary" to cap)
    }

    private fun descriptor(bytes: Int) = obj("kind" to B2_ARTICLE_BODY, "size" to bytes,
        "metadataUtf8" to "{}", "chunkHashes" to JSONArray(List((bytes + 65535) / 65536) { "a".repeat(64) }))

    private fun article(id: String, url: String = "https://example.invalid/$id") =
        PhoneArticleEntity(id, "phone", url, "Article", "", "", null, "", null, "content-hash",
            10, 10, true, 10, 10, "https://example.invalid/feed", "Feed",
            false, 0, 0, false, 0, 0, false, 0)

    private companion object {
        const val MIB = 1024 * 1024
    }
}
