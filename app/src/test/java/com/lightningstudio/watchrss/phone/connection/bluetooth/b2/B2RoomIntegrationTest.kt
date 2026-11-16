package com.lightningstudio.watchrss.phone.connection.bluetooth.b2

import android.content.Context
import androidx.room.Room
import com.lightningstudio.watchrss.phone.connection.bluetooth.B2RoomStore
import com.lightningstudio.watchrss.phone.connection.bluetooth.B2NoteAssetBusiness
import com.lightningstudio.watchrss.phone.connection.bluetooth.BluetoothSyncRecovery
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
import java.io.IOException

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
    @Test fun commonRecoverySurvivesMoreThanThreeProgressingReconnects() = runBlocking {
        val policy = B2Recovery(); var now = 0L; var calls = 0; var connects = 0
        policy.connected("initial", false)
        val recovery = BluetoothSyncRecovery({}, { now += 20000; policy.connected("r${++connects}", true) }, { _, _, _ -> })
        recovery.b2Policy = policy; recovery.monotonic = { now }
        val answer = recovery.run {
            calls++; policy.confirmed("v1-or-b2-semantic-content:$calls")
            if (calls <= 5) throw IOException("EOF")
            "COMPLETE"
        }
        assertEquals("COMPLETE", answer); assertEquals(5, connects)
    }
    @Test fun plannedHandoffKeepsTheNoProgressIntervals() {
        val policy = B2Recovery()
        repeat(2) { policy.connected("r$it", true); policy.transportFailure(it * 20000L) }
        policy.connected("r3", true)
        policy.handoffReady("planned-B2-connection")
        rejected { policy.transportFailure(60000) }
    }
}
