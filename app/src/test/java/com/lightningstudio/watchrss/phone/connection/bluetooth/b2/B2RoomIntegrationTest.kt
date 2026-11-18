package com.lightningstudio.watchrss.phone.connection.bluetooth.b2

import android.content.Context
import androidx.room.Room
import com.lightningstudio.watchrss.phone.connection.bluetooth.B2RoomStore
import com.lightningstudio.watchrss.phone.connection.bluetooth.B2NoteAssetBusiness
import com.lightningstudio.watchrss.phone.connection.bluetooth.LibraryRecoveryContext
import com.lightningstudio.watchrss.phone.connection.bluetooth.LibrarySyncMode
import com.lightningstudio.watchrss.phone.connection.bluetooth.LibraryTransferRecovery
import com.lightningstudio.watchrss.phone.connection.bluetooth.LibraryTransportFailure
import com.lightningstudio.watchrss.phone.data.db.PhoneCompanionDatabase
import kotlinx.coroutines.runBlocking
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
        val x = ReceiverRig(); val assets = File(x.root, "business-assets"); var fault = true
        try {
            val store = B2RoomStore(db)
            val business = B2NoteAssetBusiness(store, x.account, assets) { }
            val auth = B2Authorization(x.account, "phone", "watch", x.snapshot, x.snapshot, listOf(x.binding)) { true }
            val receiver = B2Receiver(store, x.files, auth, business, fault = { if (fault && it == "beforeApplyCommit") throw B2Exception("LOCAL_IO") })
            val epoch = x.begin(receiver); x.manifest(receiver, epoch); x.put(receiver, epoch)
            try { x.apply(receiver, epoch); assertionFailed() } catch (e: B2Exception) { assertEquals("LOCAL_IO", e.code) }
            assertNull(store.get(x.account, "assetReference", "picture.png"))
            assertTrue(store.list(x.account, "object").single().second.nil("receipt"))
            fault = false
            x.apply(receiver, epoch)
            assertEquals(x.descriptor.s("sha256"), store.get(x.account, "assetReference", "picture.png")!!.s("sha256"))
            assertFalse(store.list(x.account, "object").single().second.nil("receipt"))
            assertArrayEquals(x.bytes, File(assets, "picture.png").readBytes())
            // Concurrent local content must survive a different incoming version.
            val local = File(assets, "picture.png"); local.writeText("new local content")
            val staged = File(x.root, "incoming").apply { writeBytes(x.bytes) }
            store.exclusive { store.transaction {
                assertEquals("retainedLocal", business.apply(x.descriptor, B2Metadata.validate(x.descriptor), staged))
            } }
            assertEquals("new local content", local.readText())
        } finally { db.close(); x.root.deleteRecursively() }
        Unit
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

    @Test fun commonRecoverySurvivesMoreThanThreeProgressingReconnects() = runBlocking {
        var now = 0L
        var generation = 1L
        var binaryOpens = 0
        var calls = 0
        val policy = LibraryTransferRecovery { now }
        policy.observe(generation)
        val recovery = LibraryRecoveryContext(policy, LibrarySyncMode.MERGE, { true },
            { error("must reopen the binary route") }, {}, {}, { _, _ -> }, {})
        recovery.stageComplete("notes")
        val answer = recovery.handoff("noteAssetsB2", openReady = {
            binaryOpens++
            policy.observe(++generation)
        }, connected = { true }) {
            calls++
            recovery.confirmedProgress("b2:noteAsset", "image|version|$calls")
            if (calls <= 5) {
                now += 20_000L
                throw LibraryTransportFailure(generation, EOFException())
            }
            "COMPLETE"
        }
        assertEquals("COMPLETE", answer)
        assertEquals(6, binaryOpens)
    }

    @Test fun plannedHandoffKeepsTheNoProgressIntervals() = runBlocking {
        var now = 0L
        val policy = LibraryTransferRecovery { now }
        for (generation in 1L..2L) {
            policy.observe(generation)
            policy.ready(reconnected = true)
            now += 20_000L
            policy.disconnected(generation)
        }
        policy.observe(3)
        policy.ready(reconnected = true)
        val recovery = LibraryRecoveryContext(policy, LibrarySyncMode.MERGE, { true },
            { error("must reopen the binary route") }, {}, {}, { _, _ -> }, {})
        var binaryOpens = 0
        val result = recovery.handoff("noteAssetsB2", openReady = {
            policy.observe(4L + binaryOpens++)
        }, connected = { true }) {
            if (binaryOpens == 1) {
                now += 20_000L
                throw LibraryTransportFailure(4, EOFException())
            }
            "COMPLETE"
        }
        assertEquals("COMPLETE", result)
        assertEquals(2, binaryOpens)
        assertEquals(3, policy.consecutiveNoProgress)
    }
}
