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
class PhonePodjsMergeDeviceTest {
    @Test fun mergesOwnedWatchAndPhoneLibraries() = runBlocking {
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
                deviceId = "merge-phone-fixture",
                syncChangeLogDao = database.syncChangeLogDao(),
                syncPeerStateDao = database.syncPeerStateDao(),
                appMetaDao = database.appMetaDao()
            )
            repository.replaceArticlesFromBackup(listOf(PhoneArticleEntity(
                articleId = "merge-phone-article",
                sourceDeviceId = "merge-phone-fixture",
                url = "https://example.com/merge-phone-article",
                title = "Phone merge article",
                siteName = "",
                excerpt = "Fixture excerpt",
                contentHtml = null,
                contentText = "Phone merge body 😀",
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
            val noteRepository = NoteRepository(database.noteDao(), "merge-phone-fixture")
            noteRepository.save(noteId = "phone-fixture-note", title = "Phone fixture note", markdown = "# Phone note\nPhone body 😀")
            val manager = PhoneBluetoothSyncManager(
                context = context,
                repository = repository,
                noteRepository = noteRepository,
                readerPresetRepository = ReaderPresetRepository(context, database, database.readerPresetDao(), "merge-phone-fixture", scope),
                llmTokenUsageRepository = PhoneLlmTokenUsageRepository(database.llmTokenUsageDao()),
                deviceId = "merge-phone-fixture",
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
            session!!.complete("merge-phone-fixture-complete")
            assertEquals(null, result.readerSyncWarning)
            assertNotNull(result.noteStats)
            val articles = repository.getArticlesForSync()
            assertEquals(2, articles.size)
            val phoneArticle = articles.single { it.articleId == "merge-phone-article" }
            val watchArticle = articles.single { it.articleId != "merge-phone-article" }
            assertEquals("Phone merge body 😀", phoneArticle.contentText)
            assertTrue(phoneArticle.favoriteSaved)
            assertTrue(phoneArticle.watchLaterSaved)
            assertEquals("Watch merge body 😀", watchArticle.contentText)
            val notes = noteRepository.allNotes()
            assertEquals(2, notes.size)
            assertTrue(notes.any { it.title == "Phone fixture note" && it.markdown == "# Phone note\nPhone body 😀" })
            assertTrue(notes.any { it.title == "Watch fixture note" && it.markdown == "# Watch note\nWatch body 😀" })
            instrumentation.sendStatus(0, Bundle().apply {
                putString("mergeWatchId", watchId)
                putInt("mergePhoneArticles", articles.size)
                putInt("mergePhoneNotes", notes.size)
                putInt("mergeSent", result.libraryStats?.sent ?: -1)
            })
        } finally {
            session?.close()
            scope.cancel()
            database.close()
        }
    }
}
