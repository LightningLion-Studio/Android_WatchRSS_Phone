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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
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

    private fun probe(objectBytes: Int = 16 * MIB, stagingBytes: Int = 128 * MIB, includeAssets: Boolean = true): JSONObject {
        val assets = if (includeAssets) setOf(B2_NOTE_ASSET) else emptySet()
        val cap = B2Negotiation.capability(assets + B2_RSS_SOURCE, kinds + assets, B2Wire.s2Limits()
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
