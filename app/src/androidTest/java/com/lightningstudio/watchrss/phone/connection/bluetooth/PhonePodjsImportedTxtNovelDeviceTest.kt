package com.lightningstudio.watchrss.phone.connection.bluetooth

import android.os.Bundle
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.lightningstudio.watchrss.phone.data.db.PhoneCompanionDatabase
import com.lightningstudio.watchrss.phone.data.db.PhoneLlmTokenUsageRepository
import com.lightningstudio.watchrss.phone.data.importer.LocalContentImportKind
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The production phone TXT chapter choice and sync manager transfer ordered chapters to an owned watch fixture. */
class PhonePodjsImportedTxtNovelDeviceTest {
    @Test fun importsAndTransfersTxtChapters() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val address = requireNotNull(args.getString("watchAddress"))
        val watchId = requireNotNull(args.getString("watchDeviceId"))
        val targetContext = instrumentation.targetContext
        val database = Room.inMemoryDatabaseBuilder(targetContext, PhoneCompanionDatabase::class.java).build()
        // The runner does not expose its APK context. Keep files in an owned cache subdirectory.
        val contentDirectory = File(targetContext.cacheDir, "podjs-txt-novel-fixture-${System.nanoTime()}")
        val contentStore = FileArticleContentStore(contentDirectory)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var session: PhoneSyncSession? = null
        try {
            val repository = PhoneCompanionRepository(
                savedItemDao = database.phoneSavedItemDao(),
                articleDao = database.phoneArticleDao(),
                rssSourceDao = database.phoneRssSourceDao(),
                deviceId = "phone-txt-novel-fixture",
                syncChangeLogDao = database.syncChangeLogDao(),
                syncPeerStateDao = database.syncPeerStateDao(),
                articleContentStore = contentStore,
                appMetaDao = database.appMetaDao()
            )
            val inspection = repository.inspectLocalContentImport("TXT章节夹具.txt", "text/plain", sampleTxt())
            assertNotNull("TXT chapter choice was not offered", inspection.imported.txtChapterPlan)
            val selected = repository.useTxtChapterImport(inspection)
            assertEquals(LocalContentImportKind.TXT_CHAPTERS, selected.imported.kind)
            val imported = repository.confirmLocalContentImport(selected, replaceArticleId = null)
            assertEquals(2, imported.articleCount)
            assertEquals("TXT章节夹具", imported.source.title)
            assertEquals(listOf("第一章", "第二章"), repository.getArticlesForSync().map { it.title })
            val largeChapter = database.phoneArticleDao().getAllForSync().first { it.title == "第一章" }
            assertTrue("Large TXT chapter should use the production file store", contentStore.isMarker(largeChapter.contentText))
            assertTrue(contentStore.loadText(largeChapter.contentText).orEmpty().contains("字".repeat(130_000)))
            val manager = PhoneBluetoothSyncManager(
                context = targetContext,
                repository = repository,
                noteRepository = NoteRepository(database.noteDao(), "phone-txt-novel-fixture"),
                readerPresetRepository = ReaderPresetRepository(targetContext, database, database.readerPresetDao(), "phone-txt-novel-fixture", scope),
                llmTokenUsageRepository = PhoneLlmTokenUsageRepository(database.llmTokenUsageDao()),
                deviceId = "phone-txt-novel-fixture",
                debugLog = BluetoothDebugLog(targetContext)
            )
            val targets = manager.probeLibrarySyncTargets()
            session = targets.sessionLease
            val device = targets.devices.singleOrNull { it.address.equals(address, ignoreCase = true) }
            assertNotNull("Owned watch was not found", device)
            assertEquals("Refuse transfer against the main watch identity", watchId, device!!.remoteDeviceId)
            assertNotNull("Transfer must reuse the identity-checked socket", session)
            assertTrue(session!!.isPersistent)
            val result = manager.syncLibrary(
                deviceAddress = address,
                syncSession = session,
                forceFull = true,
                mode = LibrarySyncMode.PHONE_TO_WATCH_REPLACE
            )
            session!!.complete("phone-txt-novel-fixture-complete")
            assertEquals(2, result.libraryStats?.sent)
            assertEquals(listOf("第一章", "第二章"), repository.getArticlesForSync().map { it.title })
            instrumentation.sendStatus(0, Bundle().apply {
                putString("txtNovelWatchId", watchId)
                putInt("txtNovelSent", result.libraryStats?.sent ?: -1)
            })
        } finally {
            session?.close()
            scope.cancel()
            database.close()
            contentDirectory.deleteRecursively()
        }
    }

    private fun sampleTxt(): ByteArray = buildString {
        append("第一章\n第一章正文")
        append("字".repeat(130_000))
        append("\n第二章\n这里是第二章正文，用于验证章节次序与完整传输。")
    }.toByteArray(Charsets.UTF_8)
}
