package com.lightningstudio.watchrss.phone.data.reader

import androidx.room.Room
import com.lightningstudio.watchrss.phone.connection.bluetooth.ReaderPresetSyncPayload
import com.lightningstudio.watchrss.phone.data.ble.BleDomainStore
import com.lightningstudio.watchrss.phone.data.db.PhoneCompanionDatabase
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class ReaderBackgroundDeletionTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db = Room.inMemoryDatabaseBuilder(context, PhoneCompanionDatabase::class.java).allowMainThreadQueries().build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val dao = db.readerPresetDao()
    private val repo = ReaderPresetRepository(context, db, dao, "phone", scope)
    private fun asset() = ReaderBackgroundAssetEntity("missing", "a".repeat(64), "background", "IMAGE", "image/jpeg", "missing.jpg", 2000, 0, 466, 466, null, "{}", 10, "watch", false)
    private fun preset(id: String = "theme") = ReaderPreset(id = id, name = id,
        body = ReaderTextStyle(fontSizeSp = 27f),
        background = ReaderBackground(type = ReaderBackgroundType.IMAGE, colorArgb = 0xFFF8F3EC, assetId = "missing", posterAssetId = "missing"),
        updatedAt = System.currentTimeMillis() + 100_000, modifiedBy = "watch")
    @After fun close() { scope.cancel(); db.close() }

    @Test fun deleteMissingOriginalIsAtomicAndIdempotentAndReaderRootBecomesEligible() = runBlocking<Unit> {
        val original = preset(); dao.upsertBackground(asset()); dao.upsertPreset(original.toEntity())
        val roots = BleDomainStore(db, context.filesDir)
        assertNull(roots.roots("account")["reader"])
        repo.deleteBackground("missing")
        val snapshot = repo.exportSnapshot()
        assertTrue(snapshot.backgrounds.single().deleted)
        val deletion = snapshot.deletions.single()
        assertEquals("background", deletion.kind); assertEquals("missing", deletion.entityId)
        assertTrue(deletion.deletedAt > original.updatedAt)
        val theme = repo.preset("theme")!!
        assertEquals(ReaderBackground(colorArgb = original.background.colorArgb), theme.background)
        assertEquals(original.body, theme.body)
        assertTrue(theme.updatedAt > original.updatedAt)
        assertTrue(ReaderPresetSyncPayload.locallyMissing(repo).isEmpty())
        val after = roots.roots("account").getValue("reader")
        repo.deleteBackground("missing")
        assertEquals(snapshot, repo.exportSnapshot()); assertEquals(after, roots.roots("account")["reader"])
    }

    @Test fun wireDeletionCleansPeerOnlyThemeAndDoesNotResurrectOnStaleReplay() = runBlocking<Unit> {
        dao.upsertBackground(asset()); dao.upsertPreset(preset().toEntity()); repo.deleteBackground("missing")
        val wire = ReaderPresetSyncPayload.buildManifest(repo.exportSnapshot(), "phone")
        assertEquals(0, ReaderPresetSyncPayload.backgroundInventory(wire)["watchDeletedBackgroundReferences"])
        assertEquals(1, ReaderPresetSyncPayload.backgroundInventory(wire)["watchBackgroundsDeleted"])
        val peerDb = Room.inMemoryDatabaseBuilder(context, PhoneCompanionDatabase::class.java).allowMainThreadQueries().build()
        try {
            val peer = ReaderPresetRepository(context, peerDb, peerDb.readerPresetDao(), "peer", scope)
            val peerOnly = preset("peer-only")
            peerDb.readerPresetDao().upsertBackground(asset()); peerDb.readerPresetDao().upsertPreset(peerOnly.toEntity())
            ReaderPresetSyncPayload.mergeManifest(wire, peer)
            assertTrue(peer.exportSnapshot().backgrounds.single().deleted)
            assertEquals(ReaderBackgroundType.SOLID, peer.preset("peer-only")!!.background.type)
            assertNull(peer.preset("peer-only")!!.background.assetId)
            assertNull(peer.preset("peer-only")!!.background.posterAssetId)
            val stable = peer.exportSnapshot()
            ReaderPresetSyncPayload.mergeManifest(wire, peer)
            peer.mergeRemote(listOf(peerOnly.toEntity()), emptyList(), listOf(asset()), emptyList())
            assertEquals(stable, peer.exportSnapshot())
            assertTrue(ReaderPresetSyncPayload.locallyMissing(peer).isEmpty())
        } finally { peerDb.close() }
    }

    @Test fun tombstoneOnlyIsAppliedButOlderDeletionCannotRemoveExplicitNewerReimport() = runBlocking<Unit> {
        dao.upsertBackground(asset()); dao.upsertPreset(preset().toEntity())
        val deletion = ReaderDeletionEntity("background", "missing", 20, "peer")
        repo.mergeRemote(emptyList(), emptyList(), emptyList(), listOf(deletion))
        assertTrue(dao.backgroundById("missing")!!.deleted)
        assertNull(repo.preset("theme")!!.background.assetId)
        val restored = asset().copy(updatedAt = 30)
        repo.mergeRemote(emptyList(), emptyList(), listOf(restored), emptyList())
        repo.mergeRemote(emptyList(), emptyList(), emptyList(), listOf(deletion.copy(deletedAt = 15)))
        assertFalse(dao.backgroundById("missing")!!.deleted)
        assertEquals(20L, repo.exportSnapshot().deletions.single().deletedAt)
    }

    @Test fun deletedPosterDoesNotRemoveDifferentVideoOrChangeUnrelatedTheme() = runBlocking<Unit> {
        dao.upsertBackground(asset())
        val video = preset().copy(background = ReaderBackground(type = ReaderBackgroundType.VIDEO, assetId = "other-video", posterAssetId = "missing"))
        val unrelated = preset("unrelated").copy(background = ReaderBackground())
        dao.upsertPresets(listOf(video.toEntity(), unrelated.toEntity()))
        repo.deleteBackground("missing")
        assertEquals(video.background.copy(posterAssetId = null), repo.preset("theme")!!.background)
        assertEquals(unrelated.toEntity(), dao.presetById("unrelated"))
    }

    @Test fun inventoryReportsPeerDanglingReferencesWithoutLoggingPayload() {
        val snapshot = ReaderPresetSnapshot(listOf(preset().toEntity()), emptyList(), listOf(asset().copy(deleted = true)), emptyList())
        val inventory = ReaderPresetSyncPayload.backgroundInventory(ReaderPresetSyncPayload.buildManifest(snapshot, "peer"))
        assertEquals(1, inventory["watchDeletedBackgroundReferences"])
        assertEquals(0, inventory["watchBackgroundsActive"])
        assertEquals(0, inventory["watchUnreadablePresets"])
        assertFalse(inventory.toString().contains("missing"))
    }
}
