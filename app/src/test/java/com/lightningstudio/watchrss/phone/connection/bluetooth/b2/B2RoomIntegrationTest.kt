package com.lightningstudio.watchrss.phone.connection.bluetooth.b2

import android.content.Context
import androidx.room.Room
import com.lightningstudio.watchrss.phone.account.B2AccountFence
import com.lightningstudio.watchrss.phone.connection.bluetooth.B2RoomStore
import com.lightningstudio.watchrss.phone.connection.bluetooth.B2NoteAssetBusiness
import com.lightningstudio.watchrss.phone.connection.bluetooth.LibraryNoProgressLimit
import com.lightningstudio.watchrss.phone.connection.bluetooth.LibraryTransferRecovery
import com.lightningstudio.watchrss.phone.connection.bluetooth.LibraryTransportFailure
import com.lightningstudio.watchrss.phone.data.db.PhoneCompanionDatabase
import com.lightningstudio.watchrss.phone.data.note.MarkdownNoteArchive
import com.lightningstudio.watchrss.phone.data.note.NoteAssetFiles
import com.lightningstudio.watchrss.phone.data.note.NoteImportExportService
import com.lightningstudio.watchrss.phone.data.note.NoteRepository
import com.lightningstudio.watchrss.phone.syncv2.SyncV2BodyRepository
import com.lightningstudio.watchrss.phone.data.note.NoteEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assert.fail as assertionFailed
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.EOFException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = android.app.Application::class)
class B2RoomIntegrationTest {
    @Test fun accountRevocationBeforeAdmissionRollsBackBusinessAndReceipt() =
        exerciseRevocationOrder(commitFirst = false)

    @Test fun admittedTransactionCanFinishAfterRevocationWithoutAcknowledgingIt() =
        exerciseRevocationOrder(commitFirst = true)

    private fun exerciseRevocationOrder(commitFirst: Boolean) = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), PhoneCompanionDatabase::class.java)
            .allowMainThreadQueries().build()
        val rig = ReceiverRig()
        val account = B2AccountFence()
        val admitted = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        try {
            val store = B2RoomStore(db)
            var pauseCommit = false
            val pausedStore = object : B2Persistence by store {
                override suspend fun <T> transaction(block: suspend () -> T): T = store.transaction {
                    val result = block()
                    if (pauseCommit) {
                        pauseCommit = false
                        admitted.complete(Unit)
                        finish.await()
                    }
                    result
                }
            }
            val auth = B2Authorization(rig.account, "phone", "watch", rig.snapshot, rig.snapshot, listOf(rig.binding)) {
                true
            }.apply { accountCommitFence = account.commitFence }
            val business = B2NoteAssetBusiness(store, rig.account, File(rig.root, "notes/assets"), account::check)
            val receiver = B2Receiver(pausedStore, rig.files, auth, business, fault = {
                if (it == "beforeApplyCommit") {
                    if (commitFirst) pauseCommit = true else account.revoke()
                }
            })
            val epoch = rig.begin(receiver)
            rig.manifest(receiver, epoch)
            rig.put(receiver, epoch)
            val applied = async(Dispatchers.IO) { runCatching { rig.apply(receiver, epoch) } }
            try {
                if (commitFirst) {
                    withTimeout(5_000) { admitted.await() }
                    account.revoke()
                }
            } finally {
                finish.complete(Unit)
            }
            val failure = withTimeout(5_000) { applied.await() }.exceptionOrNull() as B2Exception
            assertEquals("AUTH_REVOKED", failure.code)
            assertEquals(commitFirst, store.get(rig.account, "assetReference", "picture.png") != null)
            assertEquals(commitFirst, !store.list(rig.account, "object").single().second.nil("receipt"))
            val attachment = NoteAssetFiles.current(db.openHelper.readableDatabase, rig.root, "picture.png")
            assertEquals(commitFirst, attachment.isFile)
            if (commitFirst) assertArrayEquals(rig.bytes, attachment.readBytes())
        } finally {
            db.close()
            rig.root.deleteRecursively()
        }
    }

    @Test fun rejectionRetentionReclaimsExpiredRowsAndKeepsLegacyRowsForOneWindow() = runBlocking<Unit> {
        val root = java.nio.file.Files.createTempDirectory("b2-apply-rejection-gc").toFile()
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), PhoneCompanionDatabase::class.java).build()
        try {
            val store = B2RoomStore(db)
            val peer = "applyRejection:peer"
            var elapsed = 1L
            val retention = B2Retention(store, B2Files(root), "owner", { elapsed }, "boot") {}
            store.exclusive {
                retention.clock()
                retention.clock("other-owner")
                store.put("owner", peer, "expired", obj("expiresAtMillis" to 1L))
                store.put("owner", peer, "live", obj("expiresAtMillis" to B2Wire.RETENTION + 2L))
                store.put("other-owner", peer, "own-clock", obj("expiresAtMillis" to 2L))
                store.put("other-owner", peer, "legacy", obj("code" to "APPLY_REJECTED"))
                store.put("owner", "otherNamespace", "expired", obj("expiresAtMillis" to 1L))

                elapsed += 2
                val now = retention.clock()
                retention.collect(now)
                assertNull(store.get("owner", peer, "expired"))
                assertNotNull(store.get("owner", peer, "live"))
                assertNotNull(store.get("other-owner", peer, "own-clock"))
                assertNotNull(store.get("other-owner", peer, "legacy"))
                assertNotNull(store.get("owner", "otherNamespace", "expired"))

                elapsed += B2Wire.RETENTION
                retention.collect(retention.clock())
                assertNotNull(store.get("other-owner", peer, "legacy"))
                elapsed++
                retention.collect(retention.clock())
                assertTrue(store.applyRejections().isEmpty())
                assertNotNull(store.get("owner", "otherNamespace", "expired"))
            }
        } finally {
            db.close()
            root.deleteRecursively()
        }
    }

    @Test fun dormantStoreCreatesNoTablesAndEnabledLedgerRollsBackAtomically() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        val db = Room.inMemoryDatabaseBuilder(context, PhoneCompanionDatabase::class.java).allowMainThreadQueries().build()
        try {
            val store = B2RoomStore(db)
            fun count() = db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM sqlite_master WHERE name LIKE 'library_binary_%'").use { it.moveToFirst(); it.getInt(0) }
            assertEquals(0, count())
            store.exclusive {
                store.put("scope", "test", "one", obj("value" to 1))
                val size = store.all().size
                try { store.transaction { store.put("scope", "test", "two", obj("value" to 2)); throw B2Exception("LOCAL_IO") } } catch (_: B2Exception) {}
                assertEquals(size, store.all().size)
                assertNull(store.get("scope", "test", "two"))
                val charge = B2Json.utf8(obj("value" to 1).toString()).size + 512L
                store.budget(0, charge)
                try { store.budget(1, charge); assertionFailed() } catch (e: B2Exception) { assertEquals("STORAGE_PRESSURE", e.code) }
                store.delete("scope", "test", "one"); store.budget(charge, charge)
            }
            assertEquals(2, count())
        } finally { db.close() }
        Unit
    }
    @Test fun restoredWriterConfigurationStillRequiresFullDurabilityForEveryWrite() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        val db = Room.inMemoryDatabaseBuilder(context, PhoneCompanionDatabase::class.java).allowMainThreadQueries().build()
        try {
            val store = B2RoomStore(db)
            val sql = db.openHelper.writableDatabase
            fun mode() = sql.query("PRAGMA synchronous").use { it.moveToFirst(); it.getInt(0) }
            store.exclusive {
                // A replacement Android pooled connection uses the DB's default mode,
                // not the raw PRAGMA previously executed on another connection.
                sql.execSQL("PRAGMA synchronous=NORMAL"); assertEquals(1, mode())
                store.transaction {
                    assertEquals(2, mode())
                    store.put("scope", "test", "one", obj("value" to 1))
                }
                sql.execSQL("PRAGMA synchronous=NORMAL")
                store.put("scope", "test", "two", obj("value" to 2)); assertEquals(2, mode())
                sql.execSQL("PRAGMA synchronous=NORMAL")
                store.delete("scope", "test", "two"); assertEquals(2, mode())
            }
        } finally { db.close() }
        Unit
    }
    @Test fun assetReferenceAndAppliedReceiptShareTheRealRoomTransaction() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        val db = Room.inMemoryDatabaseBuilder(context, PhoneCompanionDatabase::class.java).allowMainThreadQueries().build()
        val x = ReceiverRig(); val assets = File(x.root, "notes/assets"); var fault = true
        try {
            val store = B2RoomStore(db)
            val business = B2NoteAssetBusiness(store, x.account, assets) { }
            val auth = B2Authorization(x.account, "phone", "watch", x.snapshot, x.snapshot, listOf(x.binding)) { true }
            val receiver = B2Receiver(store, x.files, auth, business, fault = { if (fault && it == "beforeApplyCommit") throw B2Exception("LOCAL_IO") })
            val epoch = x.begin(receiver); x.manifest(receiver, epoch); x.put(receiver, epoch)
            try { x.apply(receiver, epoch); assertionFailed() } catch (e: B2Exception) { assertEquals("LOCAL_IO", e.code) }
            assertNull(store.get(x.account, "assetReference", "picture.png"))
            assertTrue(store.list(x.account, "object").single().second.nil("receipt"))
            assertFalse(File(assets, "picture.png").exists())
            fault = false
            x.apply(receiver, epoch)
            assertEquals(x.descriptor.s("sha256"), store.get(x.account, "assetReference", "picture.png")!!.s("sha256"))
            assertFalse(store.list(x.account, "object").single().second.nil("receipt"))
            assertArrayEquals(x.bytes, NoteAssetFiles.current(db.openHelper.readableDatabase, x.root, "picture.png").readBytes())
            // Concurrent local content must survive a different incoming version.
            val local = NoteAssetFiles.legacy(x.root, "independent.png").apply {
                parentFile!!.mkdirs()
                writeText("new local content")
            }
            val incoming = assetDescriptor(x.bytes, local.name)
            val staged = File(x.root, "incoming").apply { writeBytes(x.bytes) }
            store.exclusive { store.transaction {
                assertEquals("retainedLocal", business.apply(incoming, B2Metadata.validate(incoming), staged))
            } }
            assertEquals("new local content", local.readText())
        } finally { db.close(); x.root.deleteRecursively() }
        Unit
    }
    @Test fun independentCloudAndArchiveReadersCannotSeeUncommittedB2Asset() = runBlocking<Unit> {
        val context = RuntimeEnvironment.getApplication() as Context
        val rig = ReceiverRig()
        fun database() = Room.databaseBuilder(context, PhoneCompanionDatabase::class.java,
            File(rig.root, "publication.db").absolutePath)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .allowMainThreadQueries().build()
        val db = database()
        val reader = database()
        val staged = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        try {
            val key = "picture.png"
            val digest = rig.descriptor.s("sha256")
            val markdown = "![picture](assets/$key)"
            db.noteDao().upsertNotes(listOf(NoteEntity("note", null, "pictures", markdown, "", "one",
                "one", markdown, false, 1, 1, "phone")))
            db.openHelper.writableDatabase.execSQL("INSERT INTO note_assets VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any>("asset", "note", digest, key, "image/png", rig.bytes.size, key, true, 1L, false, 0L))
            val repository = SyncV2BodyRepository(reader.openHelper.readableDatabase, rig.root)
            val selected = org.json.JSONObject().put("assetId", key).put("deleted", false)
            val export = NoteImportExportService(context, NoteRepository(reader.noteDao(), "phone")) { storageKey ->
                NoteAssetFiles.readable(reader.openHelper.readableDatabase, rig.root, storageKey)
            }
            suspend fun archivedAsset() = MarkdownNoteArchive.read(export.exportZip())
                .singleOrNull { it.path == "notes/assets/$key" }
            val store = B2RoomStore(db)
            val business = B2NoteAssetBusiness(store, rig.account, File(rig.root, "notes/assets")) {}
            val auth = B2Authorization(rig.account, "phone", "watch", rig.snapshot, rig.snapshot, listOf(rig.binding)) { true }
            var fail = true
            val receiver = B2Receiver(store, rig.files, auth, business, fault = {
                if (it == "beforeApplyCommit" && fail) {
                    staged.complete(Unit)
                    check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    throw B2Exception("LOCAL_IO")
                }
            })
            val epoch = rig.begin(receiver)
            rig.manifest(receiver, epoch)
            rig.put(receiver, epoch)
            val apply = async(Dispatchers.IO) { runCatching { rig.apply(receiver, epoch) } }
            try {
                withTimeout(5_000) { staged.await() }
                val cloudVisible = withTimeout(5_000) { repository.original("asset", key, selected) != null }
                val archiveVisible = withTimeout(5_000) { archivedAsset() != null }
                assertEquals("cloud/archive visibility before Room commit", listOf(false, false), listOf(cloudVisible, archiveVisible))
            } finally {
                release.countDown()
            }
            assertEquals("LOCAL_IO", (apply.await().exceptionOrNull() as B2Exception).code)
            assertNull(repository.original("asset", key, selected))
            assertNull(archivedAsset())
            assertTrue(store.list(rig.account, "object").single().second.nil("receipt"))
            fail = false
            assertEquals("APPLIED", rig.apply(receiver, epoch).o("result").s("state"))
            assertArrayEquals(rig.bytes, requireNotNull(repository.original("asset", key, selected)).raw)
            assertArrayEquals(rig.bytes, requireNotNull(archivedAsset()).bytes)
            NoteAssetFiles.current(reader.openHelper.readableDatabase, rig.root, key)
                .writeBytes(rig.bytes.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() })
            assertNull(repository.original("asset", key, selected))
            assertNull(archivedAsset())
        } finally {
            release.countDown()
            reader.close()
            db.close()
            rig.root.deleteRecursively()
        }
    }

    @Test fun b2DoesNotRefillMissingBytesOfAnotherCommittedAssetKeyBeforeCommit() = runBlocking<Unit> {
        val rig = ReceiverRig()
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), PhoneCompanionDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val store = B2RoomStore(db)
            val sql = db.openHelper.writableDatabase
            val other = "previously-committed.png"
            val digest = rig.descriptor.s("sha256")
            val revision = com.lightningstudio.watchrss.phone.data.book.BookPackageFiles.Revision(digest, rig.bytes.size.toLong())
            store.exclusive { store.transaction { NoteAssetFiles.publish(sql, other, revision) } }
            val previous = NoteAssetFiles.current(sql, rig.root, other)
            assertFalse(previous.exists())
            val auth = B2Authorization(rig.account, "phone", "watch", rig.snapshot, rig.snapshot, listOf(rig.binding)) { true }
            val receiver = B2Receiver(store, rig.files, auth,
                B2NoteAssetBusiness(store, rig.account, File(rig.root, "notes/assets")) {})
            val epoch = rig.begin(receiver)
            rig.manifest(receiver, epoch)
            rig.put(receiver, epoch)
            assertEquals("RECEIPT_CONFLICT", (runCatching { rig.apply(receiver, epoch) }.exceptionOrNull() as B2Exception).code)
            assertFalse(previous.exists())
            assertNull(NoteAssetFiles.reference(sql, "picture.png"))
            assertTrue(store.list(rig.account, "object").single().second.nil("receipt"))
        } finally {
            db.close()
            rig.root.deleteRecursively()
        }
    }

    @Test fun guardedAssetIsVisibleOnlyThroughCommitAndReplayChecksTheCurrentBytes() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), PhoneCompanionDatabase::class.java)
            .allowMainThreadQueries().build()
        val rig = ReceiverRig()
        try {
            val sql = db.openHelper.writableDatabase
            val markdown = "![picture](assets/picture.png)"
            db.noteDao().upsertNotes(listOf(NoteEntity("note", null, "pictures", markdown, "", "one",
                "one", markdown, false, 1, 1, "phone")))
            val store = B2RoomStore(db)
            val business = B2NoteAssetBusiness(store, rig.account, File(rig.root, "notes/assets")) {}
            val version = B2CommitWindow.Version(B2CommitWindow.Head("scope", "head", "1", "0"), "1", "business")
            fun receiver(intent: String, beforeCommit: () -> Unit = {}): B2Receiver {
                val auth = B2Authorization(rig.account, "phone", "watch", rig.snapshot, rig.snapshot,
                    listOf(rig.binding.put("intent", intent))) { true }
                auth.commitWindow = B2CommitWindow { version }
                return B2Receiver(store, rig.files, auth, business, fault = { if (it == "beforeApplyCommit") beforeCommit() })
            }
            val key = "picture.png"
            val candidate = NoteAssetFiles.candidate(rig.root, rig.descriptor.s("sha256"))
            var deny = true
            val first = receiver("create") {
                assertTrue(candidate.isFile)
                assertFalse(NoteAssetFiles.legacy(rig.root, key).exists())
                if (deny) throw B2Exception("LOCAL_IO")
            }
            val epoch = rig.begin(first)
            rig.manifest(first, epoch)
            rig.put(first, epoch)
            assertEquals("LOCAL_IO", (runCatching { rig.apply(first, epoch) }.exceptionOrNull() as B2Exception).code)
            assertNull(NoteAssetFiles.reference(sql, key))
            assertFalse(NoteAssetFiles.current(sql, rig.root, key).exists())
            deny = false
            val original = rig.apply(first, epoch).o("result").o("receipt")
            assertArrayEquals(rig.bytes, NoteAssetFiles.current(sql, rig.root, key).readBytes())
            first.fenceNow()
            val second = receiver("resume")
            val resumed = rig.begin(second, "resume")
            assertTrue(resumed > epoch)
            val alternate = B2Wire.request("apply", rig.job, rig.plan, resumed,
                obj("objectId" to rig.descriptor.s("objectId")), "a".repeat(32))
            assertEquals(original.toString(), second.handle(alternate).o("result").o("receipt").toString())
            NoteAssetFiles.legacy(rig.root, key).apply { parentFile!!.mkdirs(); writeText("stale legacy replacement") }
            assertArrayEquals(rig.bytes, NoteAssetFiles.current(sql, rig.root, key).readBytes())
            candidate.writeBytes(ByteArray(rig.bytes.size))
            val stale = runCatching { rig.missing(second, resumed) }.exceptionOrNull() as B2Exception
            assertEquals("RECEIPT_CONFLICT", stale.code)
        } finally {
            db.close()
            rig.root.deleteRecursively()
        }
    }

    @Test fun rollbackPreservesAttachmentPublishedByAnotherWriter() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), PhoneCompanionDatabase::class.java).build()
        val rig = ReceiverRig()
        try {
            val assets = File(rig.root, "notes/assets").apply { mkdirs() }
            val target = File(assets, "picture.png")
            val store = B2RoomStore(db)
            val auth = B2Authorization(rig.account, "phone", "watch", rig.snapshot, rig.snapshot, listOf(rig.binding)) { true }
            val business = B2NoteAssetBusiness(store, rig.account, assets) {}
            val receiver = B2Receiver(store, rig.files, auth, business, fault = { phase ->
                if (phase == "beforeApplyCommit") {
                    val replacement = File(rig.root, "local").apply { writeText("locally restored") }
                    java.nio.file.Files.move(replacement.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    throw B2Exception("LOCAL_IO")
                }
            })
            val epoch = rig.begin(receiver)
            rig.manifest(receiver, epoch)
            rig.put(receiver, epoch)
            val failure = runCatching { rig.apply(receiver, epoch) }.exceptionOrNull() as B2Exception
            assertEquals("LOCAL_IO", failure.code)
            assertEquals("locally restored", target.readText())
            assertNull(store.get(rig.account, "assetReference", target.name))
            assertTrue(store.list(rig.account, "object").single().second.nil("receipt"))
        } finally {
            db.close()
            rig.root.deleteRecursively()
        }
    }

    @Test fun stagedChunkGroupRollsBackTogetherWhenSecondReceiptFails() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        val db = Room.inMemoryDatabaseBuilder(context, PhoneCompanionDatabase::class.java).allowMainThreadQueries().build()
        val x = ReceiverRig(ByteArray(B2Wire.CHUNK + 19) { (it * 11).toByte() })
        try {
            val store = B2RoomStore(db)
            val auth = B2Authorization(x.account, "phone", "watch", x.snapshot, x.snapshot, listOf(x.binding)) { true }
            val receiver = B2Receiver(store, x.files, auth, object : B2BusinessPort {
                override val kinds = setOf("noteAsset")
                override suspend fun apply(descriptor: org.json.JSONObject, metadata: org.json.JSONObject, staged: File) = "merged"
            })
            val epoch = x.begin(receiver)
            x.manifest(receiver, epoch)
            val oid = x.descriptor.s("objectId")
            val group = (0..1).map { i ->
                val payload = x.bytes.copyOfRange(i * B2Wire.CHUNK, minOf((i + 1) * B2Wire.CHUNK, x.bytes.size))
                val req = B2Wire.request("putChunk", x.job, x.plan, epoch, obj("objectId" to oid, "chunkIndex" to i))
                val header = B2DataHeader(2, 0, epoch, i + 1L, i, payload.size, req.s("requestId"), req.s("requestDigest"))
                receiver.inspect(header)
                receiver.writeChunk(B2Frame.Data(header, payload), req)!!
            }
            val sql = db.openHelper.writableDatabase
            sql.execSQL("""CREATE TRIGGER reject_second_chunk
                BEFORE INSERT ON library_binary_records
                WHEN NEW.namespace = 'chunk' AND (SELECT count(*) FROM library_binary_records WHERE namespace = 'chunk') = 1
                BEGIN SELECT RAISE(ABORT, 'reject second chunk'); END""")
            try {
                receiver.commitChunks(group)
                assertionFailed("expected the second receipt to abort the group")
            } catch (_: android.database.SQLException) {}
            assertTrue(store.list(x.account, "chunk").isEmpty())
            assertTrue(store.list(x.account, "request").none { it.second.optJSONObject("result")?.has("chunkIndex") == true })
            sql.execSQL("DROP TRIGGER reject_second_chunk")
            val recovered = x.missing(receiver, epoch).a("items").getJSONObject(0)
            assertEquals(0, recovered.a("missingRanges").length())
            assertEquals(2, store.list(x.account, "chunk").size)
        } finally { db.close(); x.root.deleteRecursively() }
        Unit
    }

    @Test fun progressingBinaryReconnectsCanExceedThree() = runBlocking {
        var now = 0L
        var generation = 0L
        var calls = 0
        val recovery = LibraryTransferRecovery({ now }, {}, {})
        val answer = recovery.handoff(openReady = { recovery.observe(++generation) }) {
            calls++
            recovery.confirmedProgress("image|version|$calls")
            if (calls <= 5) {
                now += 20_000L
                throw LibraryTransportFailure(generation, EOFException())
            }
            "COMPLETE"
        }
        assertEquals("COMPLETE", answer)
        assertEquals(6L, generation)
    }

    @Test fun plannedHandoffPreservesRepeatedFailureBudgetAndIgnoresDuplicateReceipts() = runBlocking {
        var now = 0L
        var generation = 0L
        val recovery = LibraryTransferRecovery({ now }, {}, {})
        val open: suspend () -> Unit = { recovery.observe(++generation) }
        recovery.confirmedProgress("committed")
        recovery.handoff(open) {
            if (generation == 1L) throw LibraryTransportFailure(generation, EOFException())
        }
        val stopped = runCatching {
            recovery.handoff(open) {
                recovery.confirmedProgress("committed")
                now += 20_000L
                throw LibraryTransportFailure(generation, EOFException())
            }
        }.exceptionOrNull()
        assertTrue(stopped is LibraryNoProgressLimit)
        assertEquals("连续 3 次重连后同步均无进展，请稍后重试。", stopped?.message)
        assertEquals(5L, generation)
    }

    @Test fun binaryLocalFailureDoesNotOpenAnotherConnection() = runBlocking {
        var opened = 0
        val failure = java.nio.file.FileSystemException("stage")
        val recovery = LibraryTransferRecovery({ 0 }, {}, {})
        val result = runCatching {
            recovery.handoff(openReady = { recovery.observe((++opened).toLong()) }) { throw failure }
        }
        assertSame(failure, result.exceptionOrNull())
        assertEquals(1, opened)
    }
}
