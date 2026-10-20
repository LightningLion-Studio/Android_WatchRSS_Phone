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
import java.security.MessageDigest

/** Full imported TXT library over the production RFCOMM manager, with an in-memory phone DB. */
class PhonePodjsRealNovelReplaceDeviceTest {
    @Test fun replacesEmptyPhoneLibraryFromRealWatchCopy() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val address = requireNotNull(args.getString("watchAddress"))
        val watchId = requireNotNull(args.getString("watchDeviceId"))
        val expectedDigest = requireNotNull(args.getString("expectedDigest"))
        val context = instrumentation.targetContext
        val database = Room.inMemoryDatabaseBuilder(context, PhoneCompanionDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var session: PhoneSyncSession? = null
        try {
            val repository = PhoneCompanionRepository(
                savedItemDao = database.phoneSavedItemDao(),
                articleDao = database.phoneArticleDao(),
                rssSourceDao = database.phoneRssSourceDao(),
                deviceId = "real-novel-phone-fixture",
                syncChangeLogDao = database.syncChangeLogDao(),
                syncPeerStateDao = database.syncPeerStateDao(),
                appMetaDao = database.appMetaDao()
            )
            val manager = PhoneBluetoothSyncManager(
                context = context,
                repository = repository,
                noteRepository = NoteRepository(database.noteDao(), "real-novel-phone-fixture"),
                readerPresetRepository = ReaderPresetRepository(context, database, database.readerPresetDao(), "real-novel-phone-fixture", scope),
                llmTokenUsageRepository = PhoneLlmTokenUsageRepository(database.llmTokenUsageDao()),
                deviceId = "real-novel-phone-fixture",
                debugLog = BluetoothDebugLog(context)
            )
            assertTrue(repository.getArticlesForSync().isEmpty())
            val targets = manager.probeLibrarySyncTargets()
            session = targets.sessionLease
            val device = targets.devices.singleOrNull { it.address.equals(address, ignoreCase = true) }
            assertNotNull("Owned isolated watch was not found", device)
            assertEquals("Refuse a different watch identity", watchId, device!!.remoteDeviceId)
            assertNotNull("Identity-checked session must be reused", session)
            assertTrue(session!!.isPersistent)
            val result = manager.syncLibrary(
                deviceAddress = address,
                syncSession = session,
                forceFull = true,
                mode = LibrarySyncMode.WATCH_TO_PHONE_REPLACE
            )
            session!!.complete("real-novel-fixture-complete")
            val articles = repository.getArticlesForSync()
            assertEquals(5378, articles.size)
            assertEquals(5378, result.libraryStats?.received)
            assertEquals(4, repository.getRssSourcesForSync().size)
            val aggregate = MessageDigest.getInstance("SHA-256")
            for (article in articles.sortedBy { it.articleId }) {
                assertTrue("Blank body: ${article.articleId}", article.contentText.isNotBlank())
                val bodyHash = sha256Hex(article.contentText.toByteArray(Charsets.UTF_8))
                aggregate.update("${article.articleId}\t${article.contentHash}\t$bodyHash\n".toByteArray(Charsets.UTF_8))
            }
            val actualDigest = hex(aggregate.digest())
            assertEquals("Full article IDs, content hashes and body text must match the watch copy", expectedDigest, actualDigest)
            instrumentation.sendStatus(0, Bundle().apply {
                putString("realNovelWatchId", watchId)
                putInt("realNovelArticles", articles.size)
                putString("realNovelDigest", actualDigest)
                putInt("realNovelSources", 4)
            })
        } finally {
            session?.close()
            scope.cancel()
            database.close()
        }
    }

    private fun sha256Hex(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun hex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        val result = CharArray(bytes.size * 2)
        for (index in bytes.indices) {
            val value = bytes[index].toInt() and 0xff
            result[index * 2] = digits[value ushr 4]
            result[index * 2 + 1] = digits[value and 15]
        }
        return String(result)
    }
}
