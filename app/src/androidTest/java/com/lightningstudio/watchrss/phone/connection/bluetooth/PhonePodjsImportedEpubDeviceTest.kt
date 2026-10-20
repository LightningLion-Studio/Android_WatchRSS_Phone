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
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The production phone importer and sync manager transfer an EPUB's ordered chapters to an owned watch fixture. */
class PhonePodjsImportedEpubDeviceTest {
    @Test fun importsAndTransfersEpubChapters() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val address = requireNotNull(args.getString("watchAddress"))
        val watchId = requireNotNull(args.getString("watchDeviceId"))
        val targetContext = instrumentation.targetContext
        val database = Room.inMemoryDatabaseBuilder(targetContext, PhoneCompanionDatabase::class.java).build()
        // The runner does not expose its APK context. Keep files in an owned cache subdirectory.
        val contentDirectory = File(targetContext.cacheDir, "podjs-epub-fixture-${System.nanoTime()}")
        val contentStore = FileArticleContentStore(contentDirectory)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var session: PhoneSyncSession? = null
        try {
            val repository = PhoneCompanionRepository(
                savedItemDao = database.phoneSavedItemDao(),
                articleDao = database.phoneArticleDao(),
                rssSourceDao = database.phoneRssSourceDao(),
                deviceId = "phone-epub-fixture",
                syncChangeLogDao = database.syncChangeLogDao(),
                syncPeerStateDao = database.syncPeerStateDao(),
                articleContentStore = contentStore,
                appMetaDao = database.appMetaDao()
            )
            val imported = repository.importLocalContent("Fixture.epub", "application/epub+zip", sampleEpub())
            assertEquals(LocalContentImportKind.EPUB, imported.kind)
            assertEquals(2, imported.articleCount)
            assertEquals("示例书名", imported.source.title)
            assertEquals(listOf("第一章", "第二章"), repository.getArticlesForSync().map { it.title })
            val largeChapter = database.phoneArticleDao().getAllForSync().first { it.title == "第一章" }
            assertTrue("Large EPUB chapter should use the production file store", contentStore.isMarker(largeChapter.contentHtml.orEmpty()))
            assertTrue(contentStore.loadText(largeChapter.contentHtml.orEmpty()).orEmpty().contains("字".repeat(130_000)))
            val manager = PhoneBluetoothSyncManager(
                context = targetContext,
                repository = repository,
                noteRepository = NoteRepository(database.noteDao(), "phone-epub-fixture"),
                readerPresetRepository = ReaderPresetRepository(targetContext, database, database.readerPresetDao(), "phone-epub-fixture", scope),
                llmTokenUsageRepository = PhoneLlmTokenUsageRepository(database.llmTokenUsageDao()),
                deviceId = "phone-epub-fixture",
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
            session!!.complete("phone-epub-fixture-complete")
            assertEquals(2, result.libraryStats?.sent)
            assertEquals(listOf("第一章", "第二章"), repository.getArticlesForSync().map { it.title })
            instrumentation.sendStatus(0, Bundle().apply {
                putString("epubWatchId", watchId)
                putInt("epubSent", result.libraryStats?.sent ?: -1)
            })
        } finally {
            session?.close()
            scope.cancel()
            database.close()
            contentDirectory.deleteRecursively()
        }
    }

    private fun sampleEpub(): ByteArray {
        val longText = "字".repeat(130_000)
        val entries = listOf(
            "META-INF/container.xml" to """<?xml version="1.0" encoding="UTF-8"?><container><rootfiles><rootfile full-path="OPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
            "OPS/content.opf" to """<?xml version="1.0" encoding="UTF-8"?><package xmlns:dc="http://purl.org/dc/elements/1.1/"><metadata><dc:title>示例书名</dc:title></metadata><manifest><item id="c1" href="chapter1.xhtml" media-type="application/xhtml+xml"/><item id="c2" href="chapter2.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c1"/><itemref idref="c2"/></spine></package>""",
            "OPS/chapter1.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>第一章</title></head><body><h1>第一章</h1><p>第一章正文$longText</p></body></html>""",
            "OPS/chapter2.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>第二章</title></head><body><h1>第二章</h1><p>第二章正文</p></body></html>"""
        )
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }
}
