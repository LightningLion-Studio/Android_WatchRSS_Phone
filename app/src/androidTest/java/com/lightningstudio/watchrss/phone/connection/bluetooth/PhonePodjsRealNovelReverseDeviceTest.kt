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
import java.io.File
import java.security.MessageDigest

/** A real 5,378-chapter phone copy replaces an isolated empty watch over public RFCOMM. */
class PhonePodjsRealNovelReverseDeviceTest {
    @Test fun replacesEmptyWatchFromRealPhoneCopy() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val address = requireNotNull(args.getString("watchAddress"))
        val watchId = requireNotNull(args.getString("watchDeviceId"))
        val expectedDigest = requireNotNull(args.getString("expectedDigest"))
        val context = instrumentation.targetContext
        val fixtureFile = File(context.filesDir, "real-novel-reverse-fixture.sqlite")
        assertTrue("Isolated phone fixture file is missing", fixtureFile.isFile)
        val testDbName = "watchrss-real-novel-reverse-test.db"
        context.deleteDatabase(testDbName)
        val database = Room.databaseBuilder(context, PhoneCompanionDatabase::class.java, testDbName)
            .createFromFile(fixtureFile)
            .build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var session: PhoneSyncSession? = null
        try {
            val repository = PhoneCompanionRepository(
                savedItemDao = database.phoneSavedItemDao(),
                articleDao = database.phoneArticleDao(),
                rssSourceDao = database.phoneRssSourceDao(),
                deviceId = "real-novel-reverse-phone-fixture",
                syncChangeLogDao = database.syncChangeLogDao(),
                syncPeerStateDao = database.syncPeerStateDao(),
                appMetaDao = database.appMetaDao()
            )
            assertEquals(5378, repository.getArticlesForSync().size)
            assertEquals(4, repository.getRssSourcesForSync().size)
            assertEquals(expectedDigest, articleDigest(database))
            val manager = PhoneBluetoothSyncManager(
                context = context,
                repository = repository,
                noteRepository = NoteRepository(database.noteDao(), "real-novel-reverse-phone-fixture"),
                readerPresetRepository = ReaderPresetRepository(context, database, database.readerPresetDao(), "real-novel-reverse-phone-fixture", scope),
                llmTokenUsageRepository = PhoneLlmTokenUsageRepository(database.llmTokenUsageDao()),
                deviceId = "real-novel-reverse-phone-fixture",
                debugLog = BluetoothDebugLog(context)
            )
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
                mode = LibrarySyncMode.PHONE_TO_WATCH_REPLACE
            )
            session!!.complete("real-novel-reverse-fixture-complete")
            assertEquals(5378, result.libraryStats?.sent)
            assertEquals(expectedDigest, articleDigest(database))
            instrumentation.sendStatus(0, Bundle().apply {
                putString("realNovelReverseWatchId", watchId)
                putInt("realNovelReverseSent", result.libraryStats?.sent ?: -1)
                putString("realNovelReverseSourceDigest", expectedDigest)
            })
        } finally {
            session?.close()
            scope.cancel()
            database.close()
            context.deleteDatabase(testDbName)
        }
    }

    private fun articleDigest(database: PhoneCompanionDatabase): String {
        val aggregate = MessageDigest.getInstance("SHA-256")
        database.openHelper.readableDatabase.query(
            "SELECT articleId, contentHash, contentText FROM phone_articles ORDER BY articleId"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val bodyHash = hex(MessageDigest.getInstance("SHA-256")
                    .digest(cursor.getString(2).toByteArray(Charsets.UTF_8)))
                aggregate.update("${cursor.getString(0)}\t${cursor.getString(1)}\t$bodyHash\n".toByteArray(Charsets.UTF_8))
            }
        }
        return hex(aggregate.digest())
    }

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
