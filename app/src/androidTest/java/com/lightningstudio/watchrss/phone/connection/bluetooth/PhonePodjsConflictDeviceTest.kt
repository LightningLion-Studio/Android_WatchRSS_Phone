package com.lightningstudio.watchrss.phone.connection.bluetooth

import android.os.Bundle
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
import com.lightningstudio.watchrss.phone.data.db.PhoneCompanionDatabase
import com.lightningstudio.watchrss.phone.data.db.PhoneLlmTokenUsageRepository
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

/** Newer phone content and newer watch saved state must merge independently for one URL. */
class PhonePodjsConflictDeviceTest {
    @Test fun mergesArticleBodyAndSavedStateClocks() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val address = requireNotNull(args.getString("watchAddress"))
        val watchId = requireNotNull(args.getString("watchDeviceId"))
        val context = instrumentation.targetContext
        val database = Room.inMemoryDatabaseBuilder(context, PhoneCompanionDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var session: PhoneSyncSession? = null
        try {
            val repository = PhoneCompanionRepository(
                savedItemDao = database.phoneSavedItemDao(),
                articleDao = database.phoneArticleDao(),
                rssSourceDao = database.phoneRssSourceDao(),
                deviceId = "conflict-phone-fixture",
                syncChangeLogDao = database.syncChangeLogDao(),
                syncPeerStateDao = database.syncPeerStateDao(),
                appMetaDao = database.appMetaDao()
            )
            repository.replaceArticlesFromBackup(listOf(PhoneArticleEntity(
                articleId = "9cfe65c6cc9c21ecc63e0b09f39c3965c851f9eea5ae22adcf14f7bab94ee377",
                sourceDeviceId = "conflict-phone-fixture",
                url = "https://example.com/merge-conflict",
                title = "Phone newer title",
                siteName = "",
                excerpt = "Fixture excerpt",
                contentHtml = null,
                contentText = "Phone newer body 😀",
                imageUrl = null,
                contentHash = "",
                importedAt = 100L,
                updatedAt = 400L,
                independentSaved = true,
                independentChangedAt = 100L,
                independentSortOrder = 100L,
                rssSourceUrl = null,
                rssSourceTitle = null,
                favoriteSaved = false,
                favoriteChangedAt = 200L,
                favoriteSortOrder = 200L,
                watchLaterSaved = true,
                watchLaterChangedAt = 100L,
                watchLaterSortOrder = 100L,
                deleted = false,
                deletedAt = 0L
            )))
            val noteRepository = NoteRepository(database.noteDao(), "conflict-phone-fixture")
            val manager = PhoneBluetoothSyncManager(
                context = context,
                repository = repository,
                noteRepository = noteRepository,
                readerPresetRepository = ReaderPresetRepository(context, database, database.readerPresetDao(), "conflict-phone-fixture", scope),
                llmTokenUsageRepository = PhoneLlmTokenUsageRepository(database.llmTokenUsageDao()),
                deviceId = "conflict-phone-fixture",
                debugLog = BluetoothDebugLog(context)
            )
            assertEquals(1, repository.getArticlesForSync().size)
            val targets = manager.probeLibrarySyncTargets()
            session = targets.sessionLease
            val device = targets.devices.singleOrNull { it.address.equals(address, ignoreCase = true) }
            assertNotNull("Owned watch was not found", device)
            assertEquals("Refuse replacement against the main watch identity", watchId, device!!.remoteDeviceId)
            assertNotNull("Replacement must reuse the identity-checked socket", session)
            assertTrue(session!!.isPersistent)
            val result = manager.syncLibrary(
                deviceAddress = address,
                syncSession = session,
                forceFull = true,
                mode = LibrarySyncMode.MERGE
            )
            session!!.complete("conflict-phone-fixture-complete")
            assertEquals(null, result.readerSyncWarning)
            assertNotNull(result.noteStats)
            val articles = repository.getArticlesForSync()
            assertEquals(1, articles.size)
            val phoneArticle = articles.single()
            assertEquals("Phone newer body 😀", phoneArticle.contentText)
            assertTrue(phoneArticle.favoriteSaved)
            assertTrue(phoneArticle.watchLaterSaved)
            val notes = noteRepository.allNotes()
            assertTrue(notes.isEmpty())
            instrumentation.sendStatus(0, Bundle().apply {
                putString("conflictWatchId", watchId)
                putInt("conflictPhoneArticles", articles.size)
                putInt("conflictSent", result.libraryStats?.sent ?: -1)
            })
        } finally {
            session?.close()
            scope.cancel()
            database.close()
        }
    }
}
