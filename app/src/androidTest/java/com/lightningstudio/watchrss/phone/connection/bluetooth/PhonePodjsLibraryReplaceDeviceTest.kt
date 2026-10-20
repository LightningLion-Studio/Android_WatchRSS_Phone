package com.lightningstudio.watchrss.phone.connection.bluetooth

import android.os.Bundle
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
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

/** Production phone manager with an in-memory library and an owned watch fixture. */
class PhonePodjsLibraryReplaceDeviceTest {
    @Test fun replacesEmptyPhoneLibraryFromOwnedWatch() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val address = requireNotNull(args.getString("watchAddress"))
        val watchId = requireNotNull(args.getString("watchDeviceId"))
        val articleId = requireNotNull(args.getString("articleId"))
        val context = instrumentation.targetContext
        val database = Room.inMemoryDatabaseBuilder(context, PhoneCompanionDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var session: PhoneSyncSession? = null
        try {
            val repository = PhoneCompanionRepository(
                savedItemDao = database.phoneSavedItemDao(),
                articleDao = database.phoneArticleDao(),
                rssSourceDao = database.phoneRssSourceDao(),
                deviceId = "normal-phone-fixture",
                syncChangeLogDao = database.syncChangeLogDao(),
                syncPeerStateDao = database.syncPeerStateDao(),
                appMetaDao = database.appMetaDao()
            )
            val manager = PhoneBluetoothSyncManager(
                context = context,
                repository = repository,
                noteRepository = NoteRepository(database.noteDao(), "normal-phone-fixture"),
                readerPresetRepository = ReaderPresetRepository(context, database, database.readerPresetDao(), "normal-phone-fixture", scope),
                llmTokenUsageRepository = PhoneLlmTokenUsageRepository(database.llmTokenUsageDao()),
                deviceId = "normal-phone-fixture",
                debugLog = BluetoothDebugLog(context)
            )
            assertTrue(repository.getArticlesForSync().isEmpty())
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
                mode = LibrarySyncMode.WATCH_TO_PHONE_REPLACE
            )
            session!!.complete("normal-phone-fixture-complete")
            val articles = repository.getArticlesForSync()
            assertEquals(1, articles.size)
            val article = articles.single()
            assertEquals(articleId, article.articleId)
            assertEquals("Watch source body 😀", article.contentText)
            assertTrue(article.favoriteSaved)
            assertTrue(article.watchLaterSaved)
            assertEquals(1, result.libraryStats?.received)
            instrumentation.sendStatus(0, Bundle().apply {
                putString("normalPhoneWatchId", watchId)
                putString("normalPhoneArticleId", articleId)
                putString("normalPhoneBody", article.contentText)
                putInt("normalPhoneArticles", articles.size)
                putBoolean("normalPhoneFavorite", article.favoriteSaved)
                putBoolean("normalPhoneWatchLater", article.watchLaterSaved)
            })
        } finally {
            session?.close()
            scope.cancel()
            database.close()
        }
    }
}
