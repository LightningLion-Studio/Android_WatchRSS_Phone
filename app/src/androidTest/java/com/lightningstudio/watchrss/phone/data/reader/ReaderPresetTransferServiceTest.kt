package com.lightningstudio.watchrss.phone.data.reader

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lightningstudio.watchrss.phone.data.db.PhoneCompanionDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReaderPresetTransferServiceTest {
    private lateinit var targetContext: Context
    private lateinit var context: Context
    private lateinit var database: PhoneCompanionDatabase
    private lateinit var repository: ReaderPresetRepository
    private lateinit var scope: CoroutineScope
    private lateinit var packageFile: File
    private lateinit var testRoot: File
    private var now = 1_000_000L

    @Before
    fun setUp() = runBlocking {
        targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        testRoot = File(targetContext.cacheDir, "reader-transfer-service-tests")
        testRoot.deleteRecursively()
        check(testRoot.mkdirs())
        context = object : ContextWrapper(targetContext) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(testRoot, "files")
                .also { check(it.isDirectory || it.mkdirs()) }
            override fun getCacheDir(): File = File(testRoot, "cache")
                .also { check(it.isDirectory || it.mkdirs()) }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences("reader-transfer-service-test-$name", mode)
        }
        check(context.filesDir.isDirectory || context.filesDir.mkdirs())
        check(context.cacheDir.isDirectory || context.cacheDir.mkdirs())
        File(context.filesDir, "reader-preset-import-undo.json").delete()
        File(context.cacheDir, "reader-preset-imports").deleteRecursively()
        packageFile = File(context.cacheDir, "service-test.wrsspreset")
        packageFile.delete()
        check(packageFile.createNewFile())
        database = Room.inMemoryDatabaseBuilder(context, PhoneCompanionDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        repository = ReaderPresetRepository(
            context = context,
            database = database,
            dao = database.readerPresetDao(),
            deviceId = "transfer-test-device",
            scope = scope
        )
        repository.ensureSeeded()
    }

    @After
    fun tearDown() {
        if (::scope.isInitialized) scope.cancel()
        if (::database.isInitialized) database.close()
        if (!::context.isInitialized) return
        File(context.filesDir, "reader-preset-import-undo.json").delete()
        File(context.cacheDir, "reader-preset-imports").deleteRecursively()
        if (::packageFile.isInitialized) packageFile.delete()
        if (::testRoot.isInitialized) testRoot.deleteRecursively()
        if (::targetContext.isInitialized) {
            targetContext.deleteSharedPreferences(
                "reader-transfer-service-test-reader_preset_state"
            )
        }
    }

    @Test
    fun multipleImports_surviveServiceRestartAndUndoInReverseOrder() = runBlocking {
        val service = newService()
        val original = repository.exportSnapshot().presets.first { !it.deleted }
        service.exportSingle(original.id, Uri.fromFile(packageFile))

        repeat(2) {
            val prepared = service.inspect(Uri.fromFile(packageFile))
            service.importSingle(prepared, ReaderPresetSingleImportChoice.COPY)
            now += 1_000L
        }

        assertEquals(4, livePresetIds().size)
        assertEquals(2, service.undoEntries.value.size)

        val restarted = newService()
        assertEquals(2, restarted.undoEntries.value.size)

        assertEquals(1, restarted.undoLatest().remainingCount)
        assertEquals(3, livePresetIds().size)
        assertEquals(0, restarted.undoLatest().remainingCount)
        assertEquals(2, livePresetIds().size)
        assertTrue(
            repository.exportSnapshot().presets
                .filter(ReaderPresetEntity::deleted)
                .size >= 2
        )
    }

    @Test
    fun expiredImport_isRemovedFromPersistentUndoHistory() = runBlocking {
        val service = newService(retentionMillis = 5L * 60L * 1_000L)
        val original = repository.exportSnapshot().presets.first { !it.deleted }
        service.exportSingle(original.id, Uri.fromFile(packageFile))
        service.importSingle(
            service.inspect(Uri.fromFile(packageFile)),
            ReaderPresetSingleImportChoice.COPY
        )
        assertEquals(1, service.undoEntries.value.size)

        now += 5L * 60L * 1_000L + 1L
        service.refreshUndoHistory()

        assertTrue(service.undoEntries.value.isEmpty())
        assertTrue(
            ReaderPresetTransferService(
                context = context,
                repository = repository,
                nowMillis = { now }
            ).undoEntries.value.isEmpty()
        )
    }

    @Test
    fun manualEditAfterImport_requiresConfirmationBeforeUndo() = runBlocking {
        val service = newService()
        val original = repository.exportSnapshot().presets.first { !it.deleted }
        service.exportSingle(original.id, Uri.fromFile(packageFile))
        val imported = service.importSingle(
            service.inspect(Uri.fromFile(packageFile)),
            ReaderPresetSingleImportChoice.COPY
        )
        repository.setLightPreset(imported.importedPresetIds.single())

        val guarded = service.undoLatest()
        assertTrue(guarded.requiresConfirmation)
        assertEquals(1, guarded.remainingCount)

        val forced = service.undoLatest(force = true)
        assertEquals(0, forced.remainingCount)
        assertEquals(2, livePresetIds().size)
    }

    @Test
    fun legacyJson_missingDependenciesFallBackWithWarnings() = runBlocking {
        val legacy = ReaderPreset.darkDefault(id = "legacy-import", name = "旧版导入").copy(
            body = ReaderTextStyle(fontAssetId = "missing-font"),
            background = ReaderBackground(
                type = ReaderBackgroundType.IMAGE,
                assetId = "missing-background"
            )
        )
        packageFile.writeText(ReaderPresetCodec.encode(legacy), Charsets.UTF_8)
        val service = newService()

        val prepared = service.inspect(Uri.fromFile(packageFile))
        assertTrue(prepared.preview.legacyJson)
        assertTrue(prepared.preview.warnings.any { it.contains("系统字体") })
        assertTrue(prepared.preview.warnings.any { it.contains("缺失背景") })
        service.importSingle(prepared, ReaderPresetSingleImportChoice.OVERWRITE)

        val imported = requireNotNull(repository.preset("legacy-import"))
        assertEquals(null, imported.body.fontAssetId)
        assertEquals(ReaderBackgroundType.SOLID, imported.background.type)
        assertEquals(null, imported.background.assetId)
    }

    private fun newService(retentionMillis: Long = 5L * 60L * 1_000L) =
        ReaderPresetTransferService(
            context = context,
            repository = repository,
            nowMillis = { now },
            undoRetentionMillis = retentionMillis
        )

    private suspend fun livePresetIds(): Set<String> = repository.exportSnapshot().presets
        .filterNot(ReaderPresetEntity::deleted)
        .mapTo(hashSetOf(), ReaderPresetEntity::id)
}
