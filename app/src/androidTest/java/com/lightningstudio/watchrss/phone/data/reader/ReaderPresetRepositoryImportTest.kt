package com.lightningstudio.watchrss.phone.data.reader

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReaderPresetRepositoryImportTest {
    private lateinit var targetContext: Context
    private lateinit var context: Context
    private lateinit var testRoot: File
    private lateinit var database: PhoneCompanionDatabase
    private lateinit var repository: ReaderPresetRepository
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() = runBlocking {
        targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        testRoot = File(targetContext.cacheDir, "reader-repository-import-tests")
        testRoot.deleteRecursively()
        check(testRoot.mkdirs())
        context = object : ContextWrapper(targetContext) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(testRoot, "files")
                .also { check(it.isDirectory || it.mkdirs()) }
            override fun getCacheDir(): File = File(testRoot, "cache")
                .also { check(it.isDirectory || it.mkdirs()) }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences("reader-repository-import-test-$name", mode)
        }
        database = Room.inMemoryDatabaseBuilder(context, PhoneCompanionDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        repository = ReaderPresetRepository(
            context = context,
            database = database,
            dao = database.readerPresetDao(),
            deviceId = "test-device",
            scope = scope
        )
        repository.ensureSeeded()
    }

    @After
    fun tearDown() {
        scope.cancel()
        database.close()
        testRoot.deleteRecursively()
        targetContext.getSharedPreferences(
            "reader-repository-import-test-reader_preset_state",
            Context.MODE_PRIVATE
        ).edit().clear().commit()
        targetContext.deleteSharedPreferences(
            "reader-repository-import-test-reader_preset_state"
        )
    }

    @Test
    fun singleCopy_keepsOriginalAndCreatesUniqueLocalIdentity() = runBlocking {
        val original = repository.exportSnapshot().presets.first { !it.deleted }
        val imported = ReaderPresetCodec.decode(original.payloadJson)

        val result = repository.applyImportedSnapshot(
            ReaderPresetSnapshot(
                presets = listOf(imported.toEntity()),
                fonts = emptyList(),
                backgrounds = emptyList(),
                deletions = emptyList()
            ),
            ReaderPresetRepositoryImportMode.SINGLE_COPY
        )

        val live = repository.exportSnapshot().presets.filterNot(ReaderPresetEntity::deleted)
        assertEquals(3, live.size)
        assertNotEquals(original.id, result.importedPresetIds.single())
        assertTrue(result.importedPresetNames.single().endsWith(" 2"))
    }

    @Test
    fun singleOverwrite_replacesMatchingIdInPlace() = runBlocking {
        val original = repository.exportSnapshot().presets.first { !it.deleted }
        val imported = ReaderPresetCodec.decode(original.payloadJson).copy(
            name = "覆盖后的预设",
            body = ReaderTextStyle(fontSizeSp = 29f)
        )

        val result = repository.applyImportedSnapshot(
            ReaderPresetSnapshot(
                presets = listOf(imported.toEntity()),
                fonts = emptyList(),
                backgrounds = emptyList(),
                deletions = emptyList()
            ),
            ReaderPresetRepositoryImportMode.SINGLE_OVERWRITE
        )

        val live = repository.exportSnapshot().presets.filterNot(ReaderPresetEntity::deleted)
        assertEquals(2, live.size)
        assertEquals(original.id, result.importedPresetIds.single())
        assertEquals(
            29f,
            ReaderPresetCodec.decode(live.first { it.id == original.id }.payloadJson).body.fontSizeSp
        )
    }

    @Test
    fun differentIdWithSameName_getsNumberedAutomatically() = runBlocking {
        val original = repository.exportSnapshot().presets.first { !it.deleted }
        val imported = ReaderPreset.lightDefault(id = "different-id", name = original.name)

        val result = repository.applyImportedSnapshot(
            ReaderPresetSnapshot(
                presets = listOf(imported.toEntity()),
                fonts = emptyList(),
                backgrounds = emptyList(),
                deletions = emptyList()
            ),
            ReaderPresetRepositoryImportMode.LIBRARY_MERGE
        )

        assertEquals("${original.name} 2", result.importedPresetNames.single())
    }

    @Test
    fun libraryMerge_updatesMatchingIdAndKeepsLocalOnlyPreset() = runBlocking {
        val before = repository.exportSnapshot()
        val target = ReaderPresetCodec.decode(before.presets.first().payloadJson)
            .copy(name = "包内更新", body = ReaderTextStyle(fontSizeSp = 31f))

        repository.applyImportedSnapshot(
            ReaderPresetSnapshot(
                presets = listOf(target.toEntity()),
                fonts = emptyList(),
                backgrounds = emptyList(),
                deletions = emptyList()
            ),
            ReaderPresetRepositoryImportMode.LIBRARY_MERGE
        )

        val after = repository.exportSnapshot().presets.filterNot(ReaderPresetEntity::deleted)
        assertEquals(2, after.size)
        assertEquals(
            31f,
            ReaderPresetCodec.decode(after.first { it.id == target.id }.payloadJson).body.fontSizeSp
        )
        assertTrue(after.any { it.id != target.id })
    }

    @Test
    fun libraryReplace_repairsSelectionAndRestoreCreatesCompensatingState() = runBlocking {
        val before = repository.exportSnapshot()
        val beforeSelection = repository.currentSelection()
        val imported = ReaderPreset.lightDefault(id = "imported-only", name = "导入预设")

        repository.applyImportedSnapshot(
            ReaderPresetSnapshot(
                presets = listOf(imported.toEntity()),
                fonts = emptyList(),
                backgrounds = emptyList(),
                deletions = emptyList()
            ),
            ReaderPresetRepositoryImportMode.LIBRARY_REPLACE
        )

        val replaced = repository.exportSnapshot()
        assertEquals(
            listOf("imported-only"),
            replaced.presets.filterNot(ReaderPresetEntity::deleted).map(ReaderPresetEntity::id)
        )
        assertEquals("imported-only", repository.currentSelection().lightPresetId)
        assertTrue(repository.currentSelection().darkFollowsLight)
        assertTrue(
            replaced.presets.filter(ReaderPresetEntity::deleted)
                .map(ReaderPresetEntity::id)
                .containsAll(before.presets.map(ReaderPresetEntity::id))
        )

        repository.restoreImportSnapshot(before, beforeSelection)

        val restored = repository.exportSnapshot()
        val restoredLiveIds = restored.presets.filterNot(ReaderPresetEntity::deleted)
            .mapTo(hashSetOf(), ReaderPresetEntity::id)
        assertEquals(
            before.presets.filterNot(ReaderPresetEntity::deleted)
                .mapTo(hashSetOf(), ReaderPresetEntity::id),
            restoredLiveIds
        )
        assertTrue(restored.presets.first { it.id == "imported-only" }.deleted)
        assertTrue(restored.deletions.any { it.entityId == "imported-only" })
        assertEquals(beforeSelection, repository.currentSelection())
        assertFalse(restoredLiveIds.contains("imported-only"))
    }
}
