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

/** An owned watch fixture receives an authoritative snapshot from an in-memory phone library. */
class PhonePodjsReverseReplaceDeviceTest {
    @Test fun replacesOwnedWatchLibraryFromPhone() = runBlocking {
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
                deviceId = "reverse-phone-fixture",
                syncChangeLogDao = database.syncChangeLogDao(),
                syncPeerStateDao = database.syncPeerStateDao(),
                appMetaDao = database.appMetaDao()
            )
            repository.replaceArticlesFromBackup(listOf(PhoneArticleEntity(
                articleId = "phone-source-article",
                sourceDeviceId = "reverse-phone-fixture",
                url = "https://example.com/phone-source-article",
                title = "Phone source article",
                siteName = "",
                excerpt = "Fixture excerpt",
                contentHtml = null,
                contentText = "Phone source body 😀",
                imageUrl = null,
                contentHash = "",
                importedAt = 100L,
                updatedAt = 100L,
                independentSaved = true,
                independentChangedAt = 100L,
                independentSortOrder = 100L,
                rssSourceUrl = null,
                rssSourceTitle = null,
                favoriteSaved = true,
                favoriteChangedAt = 100L,
                favoriteSortOrder = 100L,
                watchLaterSaved = true,
                watchLaterChangedAt = 100L,
                watchLaterSortOrder = 100L,
                deleted = false,
                deletedAt = 0L
            )))
            val manager = PhoneBluetoothSyncManager(
                context = context,
                repository = repository,
                noteRepository = NoteRepository(database.noteDao(), "reverse-phone-fixture"),
                readerPresetRepository = ReaderPresetRepository(context, database, database.readerPresetDao(), "reverse-phone-fixture", scope),
                llmTokenUsageRepository = PhoneLlmTokenUsageRepository(database.llmTokenUsageDao()),
                deviceId = "reverse-phone-fixture",
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
                mode = LibrarySyncMode.PHONE_TO_WATCH_REPLACE
            )
            session!!.complete("reverse-phone-fixture-complete")
            val articles = repository.getArticlesForSync()
            assertEquals(1, articles.size)
            assertEquals("phone-source-article", articles.single().articleId)
            assertEquals("Phone source body 😀", articles.single().contentText)
            assertTrue(articles.single().favoriteSaved)
            assertTrue(articles.single().watchLaterSaved)
            instrumentation.sendStatus(0, Bundle().apply {
                putString("reverseWatchId", watchId)
                putInt("reversePhoneArticles", articles.size)
                putInt("reverseSent", result.libraryStats?.sent ?: -1)
            })
        } finally {
            session?.close()
            scope.cancel()
            database.close()
        }
    }
}
