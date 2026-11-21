package com.lightningstudio.watchrss.phone.syncv2

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lightningstudio.watchrss.phone.cloud.CloudKeyManager
import com.lightningstudio.watchrss.phone.data.db.PhoneCompanionDatabase
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE, sdk=[35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ClosureR3IndependentProbeTest {
    private val bytes = "verified fixture attachment".toByteArray()

    private fun putAsset(w: SyncV2P5Fixture, name: String) {
        File(w.root, "notes/assets/$name").apply {
            parentFile!!.mkdirs()
            writeBytes(bytes)
        }
    }

    @Test
    fun releaseAfterLastCheckWaitsForSqliteCommit() {
        val generation = SyncV2Lifetime.Generation()
        val current = generation.current()
        val releaseStarted = CountDownLatch(1)
        val invalidated = CountDownLatch(1)
        val release = Executors.newSingleThreadExecutor()
        try {
            SyncV2Lifetime(generation) { true }.use { lifetime ->
                SyncV2P5Fixture(lifetime).use { w ->
                    w.pending()
                    putAsset(w, "legacy.png")
                    w.edit("![legacy](assets/legacy.png)")
                    val sql = w.sql
                    var prepared = false
                    var armed = false
                    var intercepted = false
                    var committed = false
                    val adapter = Proxy.newProxyInstance(
                        SupportSQLiteDatabase::class.java.classLoader,
                        arrayOf(SupportSQLiteDatabase::class.java)
                    ) { _, method, args ->
                        if (armed && method.name == "setTransactionSuccessful") {
                            armed = false
                            intercepted = true
                            assertTrue(sql.inTransaction())
                            release.submit {
                                releaseStarted.countDown()
                                generation.invalidate()
                                invalidated.countDown()
                                lifetime.close()
                            }
                            assertTrue(releaseStarted.await(5, TimeUnit.SECONDS))
                            assertFalse("release advanced during commit", invalidated.await(200, TimeUnit.MILLISECONDS))
                        }
                        try {
                            method.invoke(sql, *(args ?: emptyArray())).also {
                                if (intercepted && !committed && method.name == "endTransaction") {
                                    assertEquals(current, generation.current())
                                    committed = true
                                }
                            }
                        } catch (error: InvocationTargetException) {
                            throw error.targetException
                        }
                    } as SupportSQLiteDatabase
                    val projection = SyncV2Projection(adapter, w.identity, SyncV2Fixtures.localKey, true,
                        fault = { point ->
                            if (point == "before-commit" && prepared && !intercepted) armed = true
                            if (point == "after-transaction" && committed) {
                                assertTrue("release must proceed after commit", invalidated.await(5, TimeUnit.SECONDS))
                            }
                        }, lifetime = lifetime)
                    projection.install()
                    SyncV2AccountActions(w.f.client(), w.identity, projection, w.f.signer, { w.key },
                        { it == "synthetic-phone" }, w.root, true, { w.f.now },
                        fault = { if (it == "before-action-stage") prepared = true }).use { actions ->
                        val result = runCatching { actions.choose(actions.preview(), "local") }
                        assertTrue("commit interception not reached: ${result.exceptionOrNull()}", intercepted)
                        assertTrue(committed)
                        assertEquals("authentication_required", result.exceptionOrNull()?.message)
                        assertEquals(0, w.publishCalls)
                        val counts = listOf("note_assets", "syncv2_p5_objects", "syncv2_p5_rows").map { table ->
                            sql.query("SELECT COUNT(*) FROM $table").use { assertTrue(it.moveToFirst()); it.getInt(0) }
                        }
                        assertEquals(1, counts[0])
                        assertTrue(counts[1] > 0 && counts[2] > 0)
                    }
                }
            }
        } finally {
            release.shutdown()
            assertTrue(release.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun receiverCanRestoreEveryOriginalNameFromPublishedClosure() = SyncV2P5Fixture().use { w ->
        val backend = SyncV2BodyTransportTest.Backend(w)
        w.pending()
        putAsset(w, "first.png")
        putAsset(w, "second.png")
        val markdown = "![first](assets/first.png)\n![second](assets/second.png)"
        w.edit(markdown)
        w.actions().use { assertTrue(it.choose(it.preview(), "local").getBoolean("localCommitted")) }
        assertEquals(1, w.publishCalls)

        val authors = w.f.authors()
        val head = w.headers.getValue(w.current.getString("commitOid"))
        val closure = SyncV2ControlClosure(w.identity, authors, { w.key })
        val names = setOf("first.png", "second.png")
        closure.verify(head, { w.f.objects.getValue(it) }, { w.refs.getValue(it) }).use { published ->
            val assets = published.rows.filter { it.getJSONObject("meta").getString("kind") == "asset" }
            assertEquals(names, assets.map { it.getJSONObject("meta").getString("logicalId") }.toSet())
            for (asset in assets) {
                val row = asset.getJSONObject("row")
                val revision = SyncV2BodyCodec.revision("asset", row.getString("entityKey"), SyncV2BodyCodec.hash(bytes))
                assertEquals(revision, row.getString("bodyRevision"))
            }
        }

        val context = RuntimeEnvironment.getApplication() as Context
        val manager = CloudKeyManager(null, preferencesOverride=context.getSharedPreferences("closure-r3-${System.nanoTime()}",Context.MODE_PRIVATE))
        manager.bindBackend("http://synthetic")
        manager.saveAccountKey("u",1,w.key,true)
        SyncV2ContentKeys.authenticated(manager,"u") {}.use { keys ->
            fun sync(projection: SyncV2Projection, files: File, downloadOnly: Boolean): SyncV2BodyChannel.Result =
                SyncV2Lifetime { true }.use { lifetime ->
                    val repository = SyncV2BodyRepository(projection.accountDatabase, files)
                    w.f.client().use { client ->
                        client.capabilities()
                        SyncV2BodyChannel(projection,w.identity,keys,w.f.signer,client,repository,File(files,"body-cache"),
                            lifetime,{true},{SyncV2TransportSignals(wifi=true,internet=true)},{w.f.now}).sync(downloadOnly=downloadOnly)
                    }
                }
            assertEquals(SyncV2BodyChannel.Result(0, emptyList()), sync(w.projection, w.root, false))
            assertEquals(2, backend.ready.size)
            val restoredFiles = File(w.root, "receiver")
            val room = Room.inMemoryDatabaseBuilder(context, PhoneCompanionDatabase::class.java)
                .allowMainThreadQueries().build()
            try {
                val sql = room.openHelper.writableDatabase
                val projection = SyncV2Projection(sql,w.identity,SyncV2Fixtures.localKey,true).also { it.install() }
                SyncV2AccountActions(w.f.client(),w.identity,projection,w.f.signer,{w.key},{it=="synthetic-phone"},
                    restoredFiles,true,{w.f.now}).use { it.refresh() }
                val repository = SyncV2BodyRepository(sql, restoredFiles)
                for (name in names) assertFalse(repository.file("asset", name).exists())
                assertEquals(SyncV2BodyChannel.Result(0, emptyList()), sync(projection, restoredFiles, true))
                val restoredMarkdown = sql.query("SELECT markdown FROM notes WHERE noteId='note'").use {
                    assertTrue(it.moveToFirst())
                    it.getString(0)
                }
                assertEquals(markdown, restoredMarkdown)
                for (name in SyncV2BodyRepository.assetKeys(restoredMarkdown)) {
                    assertArrayEquals(bytes, repository.read("asset", name))
                }
                assertTrue(repository.refreshReferences(w.f.now) {}.isEmpty())
                assertAssetSizes(sql)
            } finally {
                room.close()
            }
        }
    }

    @Test
    fun followUpEditOfSegmentedNoteCanUseTheSameVerifiedAlias() = SyncV2P5Fixture().use { w ->
        w.pending()
        putAsset(w, "first.png")
        putAsset(w, "second.png")
        val markdown = "![first](assets/first.png)\n![second](assets/second.png)\n" + "paragraph\n".repeat(29_000)
        w.edit(markdown)
        w.noteSegmentsAnnounced = true
        val client = w.f.client().also { it.noteSegmentsLocal = true }
        SyncV2AccountActions(client, w.identity, w.projection, w.f.signer, { w.key },
            { it == "synthetic-phone" }, w.root, true, { w.f.now }, noteSegmentsRead = true).use {
            assertTrue(it.choose(it.pendingChoice()!!.preview, "local").getBoolean("localCommitted"))
        }
        w.reopen()
        val updated = "$markdown\nnew local edit"
        w.edit(updated)
        assertEquals(updated, w.text())
        assertAssetSizes(w.sql)
        assertTrue(SyncV2BodyRepository(w.sql, w.root).refreshReferences(w.f.now) {}.isEmpty())
        val projection = SyncV2Projection(w.sql, w.identity, SyncV2Fixtures.localKey, true, noteSegments = true)
        projection.install()
        projection.noteSegmentsServerAnnounced = true
        projection.prepareWireIndex(w.f.signer, w.key, 1).use { index ->
            for (name in listOf("first.png", "second.png")) {
                val entity = w.identity.entity("asset", name)
                assertNotNull(SyncV2Index.find(w.identity.scope,index.tree.root,entity) { index.objects[it] ?: projection.accountObject(it) })
            }
        }
    }

    @Test
    fun ordinaryDiscoveryBackfillsALegacyAliasAfterTheNoteWasScanned() = SyncV2P5Fixture().use { w ->
        w.start()
        putAsset(w, "first.png")
        putAsset(w, "second.png")
        w.sql.execSQL(
            "INSERT INTO note_assets VALUES('asset','note',?,'first.png','image/png',?,'first.png',0,0,0,0)",
            arrayOf<Any>(SyncV2BodyCodec.hash(bytes), bytes.size)
        )
        w.edit("![first](assets/first.png)\n![second](assets/second.png)")
        assertNull(w.projection.candidate("asset", "second.png"))
        val repository = SyncV2BodyRepository(w.sql, w.root)
        assertTrue(repository.refreshReferences(w.f.now) {}.isEmpty())
        val witness = SyncV2BusinessWitness.read(w.sql)
        assertTrue(repository.refreshReferences(w.f.now) {}.isEmpty())
        assertEquals(witness, SyncV2BusinessWitness.read(w.sql))
        w.drain()
        assertNotNull(w.projection.candidate("asset", "second.png"))
        assertAssetSizes(w.sql)
    }

    private fun assertAssetSizes(sql: SupportSQLiteDatabase) {
        sql.query("SELECT COUNT(*) FROM note_assets").use {
            assertTrue(it.moveToFirst())
            assertEquals(1, it.getInt(0))
        }
        val sizes = sql.query("SELECT storageKey,byteCount FROM ${SyncV2NoteAssetNames.source}").use { rows ->
            buildMap { while (rows.moveToNext()) put(rows.getString(0),rows.getInt(1)) }
        }
        assertEquals(mapOf("first.png" to bytes.size, "second.png" to bytes.size), sizes)
    }
}
