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

/** The production phone importer and sync manager transfer a file-backed TXT into an owned watch fixture. */
class PhonePodjsImportedTxtDeviceTest {
    @Test fun importsAndTransfersLargeTxt() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val address = requireNotNull(args.getString("watchAddress"))
        val watchId = requireNotNull(args.getString("watchDeviceId"))
        val targetContext = instrumentation.targetContext
        val database = Room.inMemoryDatabaseBuilder(targetContext, PhoneCompanionDatabase::class.java).build()
        // The runner does not expose its APK context. Keep files in an owned cache subdirectory.
        val contentDirectory = File(targetContext.cacheDir, "podjs-txt-fixture-${System.nanoTime()}")
        val contentStore = FileArticleContentStore(contentDirectory)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var session: PhoneSyncSession? = null
        try {
            val repository = PhoneCompanionRepository(
                savedItemDao = database.phoneSavedItemDao(),
                articleDao = database.phoneArticleDao(),
                rssSourceDao = database.phoneRssSourceDao(),
                deviceId = "phone-txt-fixture",
                syncChangeLogDao = database.syncChangeLogDao(),
                syncPeerStateDao = database.syncPeerStateDao(),
                articleContentStore = contentStore,
                appMetaDao = database.appMetaDao()
            )
            val body = (0 until 6000).joinToString("\n") { index ->
                "行$index 汉字😀 alpha-${index % 97}"
            }
            val imported = repository.importLocalContent("FixtureLong.txt", "text/plain", body.toByteArray(Charsets.UTF_8))
            assertEquals(LocalContentImportKind.TXT, imported.kind)
            assertEquals(1, imported.articleCount)
            val stored = database.phoneArticleDao().getAllForSync().single()
            assertTrue("TXT should use the production file-backed store", contentStore.isMarker(stored.contentText))
            assertEquals(body, contentStore.loadText(stored.contentText))
            val manager = PhoneBluetoothSyncManager(
                context = targetContext,
                repository = repository,
                noteRepository = NoteRepository(database.noteDao(), "phone-txt-fixture"),
                readerPresetRepository = ReaderPresetRepository(targetContext, database, database.readerPresetDao(), "phone-txt-fixture", scope),
                llmTokenUsageRepository = PhoneLlmTokenUsageRepository(database.llmTokenUsageDao()),
                deviceId = "phone-txt-fixture",
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
            session!!.complete("phone-txt-fixture-complete")
            assertEquals(1, result.libraryStats?.sent)
            assertEquals(body, repository.getArticlesForSync().single().contentText)
            instrumentation.sendStatus(0, Bundle().apply {
                putString("txtWatchId", watchId)
                putInt("txtBytes", body.toByteArray(Charsets.UTF_8).size)
                putInt("txtSent", result.libraryStats?.sent ?: -1)
            })
        } finally {
            session?.close()
            scope.cancel()
            database.close()
            contentDirectory.deleteRecursively()
        }
    }
}
