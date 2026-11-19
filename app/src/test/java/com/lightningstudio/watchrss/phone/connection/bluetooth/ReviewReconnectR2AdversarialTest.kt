package com.lightningstudio.watchrss.phone.connection.bluetooth

import androidx.room.Room
import com.lightningstudio.watchrss.phone.cloud.CloudSnapshotCodec
import com.lightningstudio.watchrss.phone.data.book.ImportedBookStore
import com.lightningstudio.watchrss.phone.data.db.*
import com.lightningstudio.watchrss.phone.data.log.BluetoothDebugLog
import com.lightningstudio.watchrss.phone.data.note.*
import com.lightningstudio.watchrss.phone.data.reader.ReaderPresetRepository
import com.lightningstudio.watchrss.phone.data.repo.PhoneCompanionRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.*

/** External read-only-review probes. Production manager, codec, generated DAOs and SQLite. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class ReviewReconnectR2AdversarialTest {
    private val app = RuntimeEnvironment.getApplication()
    private val db = Room.inMemoryDatabaseBuilder(app, PhoneCompanionDatabase::class.java).allowMainThreadQueries().build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val notes = NoteRepository(db.noteDao(), "phone", now = { 100 })
    private val tokens = PhoneLlmTokenUsageRepository(db.llmTokenUsageDao())
    private val repo = PhoneCompanionRepository(db.phoneSavedItemDao(), db.phoneArticleDao(), db.phoneRssSourceDao(),
        "phone", db.syncChangeLogDao(), db.syncPeerStateDao(), appMetaDao = db.appMetaDao())
    private val log = BluetoothDebugLog(app)
    private val device = PhoneBluetoothWatchDevice("watch", "11:22:33:44:55:66", 1, "watch", supportsPersistentSession = true)
    private val manager = PhoneBluetoothSyncManager(app, repo, notes, ReaderPresetRepository(app, db, db.readerPresetDao(), "phone", scope),
        tokens, "phone", log, buildAccountSyncRequest = { _, _, _ -> JSONObject().put("action", "syncAccount") },
        canSyncAccount = { true }, currentPhoneUserId = { "owner" })
    private val sessions = mutableListOf<PhoneSyncSession>()
    private val disks = mutableListOf<PhoneCompanionDatabase>()
    private val diskNames = mutableListOf<String>()
    private val picture = File(app.filesDir, "notes/assets/rvrecmain2.png")
    private var time = 0L
    private var searches = 0
    private var ackLosses = 0
    private var events = 0
    private var usageWarning: String? = null
    private val usageRequests = mutableListOf<String>()
    private val policy = LibraryTransferRecovery { time }

    @After fun close() {
        sessions.forEach { it.close() }; scope.cancel(); db.close(); picture.delete()
        disks.forEach { if (it.isOpen) it.close() }; diskNames.forEach { app.deleteDatabase(it) }
    }
    private fun ok(action: String, phase: String? = null) = JSONObject().put("success", true).put("version", 14)
        .put("deviceId", "watch").put("action", action).apply { phase?.let { put("phase", it) } }
    private fun binding(delta: Boolean = false) = ok("syncLibrary", "probe")
        .put("supportsReaderPresets", false).put("supportsNoteManifestV2", delta).put("supportsNoteAssetInventory", delta)
        .put("supportsTokenUsageCursor", true).put("account", JSONObject().put("bound", true)
            .put("userIdHash", CloudSnapshotCodec.sha256("owner".toByteArray())))
    private fun start(delta: Boolean) = listOf(binding(delta), ok("syncAccount"),
        LibrarySyncPayload.buildCursorResponse("watch", LibrarySyncCursor(0, 0, 0)),
        LibrarySyncPayload.buildManifestResponse("watch", emptyList()).put("supportsReaderPresets", false)
            .put("supportsNoteManifestV2", delta).put("supportsNoteAssetInventory", delta),
        ok("syncLibrary", "complete").put("articles", JSONArray()))
    private fun noteReply(values: List<NoteEntity>, delta: Boolean = false) = NoteSyncPayload.manifest("watch", values)
        .put("success", true).apply { if (delta) put("phase", "delta") }
    private fun noteWindow(note: NoteEntity) = listOf(ok("syncNotes", "manifest").put("noteManifest", JSONArray())
        .put("assetManifest", JSONArray()), noteReply(listOf(note), true))
    private fun usage(next: Long, ids: List<Long>, more: Boolean) = ok("syncLlmTokenUsage")
        .put("usageEpoch", "r2-epoch").put("nextAfterId", next).put("hasMore", more).put("count", ids.size)
        .put("records", JSONArray().apply { ids.forEach { id -> put(JSONObject().put("id", id).put("createdAt", id)
            .put("model", "synthetic").put("requestId", "r2-$id").put("totalTokens", 3)) } })
    private fun finish() = BluetoothSyncProtocol.buildSessionControlResponse(14, "complete")
    private fun session(frames: List<JSONObject>, cutLastFrame: Boolean = false, loseAckFor: String? = null): PhoneSyncSession {
        val wire = ByteArrayOutputStream()
        frames.forEachIndexed { index, frame ->
            val encoded = ByteArrayOutputStream().also { BluetoothSyncProtocol.writeFrame(it, frame) }.toByteArray()
            wire.write(encoded, 0, if (cutLastFrame && index == frames.lastIndex) encoded.size / 2 else encoded.size)
        }
        var action = ""; var phase = ""; var failed = false
        val output = object : ByteArrayOutputStream() {
            override fun flush() {
                if (size() == 0) return
                val frame = BluetoothSyncProtocol.readFrame(toByteArray().inputStream()); reset()
                if (frame.optString("action") != "ack") { action = frame.optString("action"); phase = frame.optString("phase") }
                else if (action == loseAckFor && !failed && (action != "syncNotes" || phase == "delta")) {
                    failed = true; ackLosses++; throw IOException("review injected ACK loss")
                }
            }
        }
        return PhoneSyncSession(PhoneBluetoothSyncClient(app, log), device, "phone",
            PhoneSyncSession.Transport(wire.toByteArray().inputStream(), output, "review-host", false, {}),
            true, false, false).also { sessions += it }
    }
    private suspend fun seedImageNote(conflict: Boolean): NoteEntity {
        val base = notes.save("local", "note", "![image](assets/rvrecmain2.png)\nbase")
        picture.parentFile!!.mkdirs(); picture.writeBytes(byteArrayOf(7, 8, 9))
        if (conflict) notes.save("local", "note", "![image](assets/rvrecmain2.png)\nlocal")
        return base
    }
    private suspend fun runNotes(kind: String): Throwable? {
        val conflict = kind == "freshConflict" || kind == "resolvedConflict"
        val base = seedImageNote(conflict)
        fun remote(n: Int): NoteEntity {
            if (!conflict) return base.copy(noteId = "incoming-$n", modifiedBy = "watch")
            val text = "![image](assets/rvrecmain2.png)\nremote-${if (kind == "resolvedConflict") 1 else n}"
            return base.copy(markdown = text, plainText = text, contentHash = MarkdownNoteCodec.sha256(text), updatedAt = 200, modifiedBy = "watch")
        }
        if (kind == "resolvedConflict") {
            assertEquals(1, notes.applyRemoteWithChanges(remote(1), "watch").addedConflicts)
            db.noteDao().resolveConflict(notes.observeConflicts().first().single().conflictId, 999)
        }
        fun link(n: Int): PhoneSyncSession {
            val frames = (if (n == 1) start(true) else listOf(binding(true), ok("syncAccount"))) + noteWindow(remote(n))
            val completed = if (n == 5 && kind != "cutBeforeCommit") frames + listOf(ok("syncNoteAsset").put("alreadyPresent", true),
                usage(0, emptyList(), false), finish()) else frames
            return session(completed, cutLastFrame = kind == "cutBeforeCommit", loseAckFor = if (kind == "lostNoteAck" && n < 5) "syncNotes" else null)
        }
        manager.reconnectSession = { _, _ ->
            searches++; time += 6000
            if (kind == "cutBeforeCommit" && searches > 4) throw LibrarySearchFailure()
            link(searches + 1)
        }
        val failure = runCatching { manager.syncAll(device, reusableSession = link(1), recoveryPolicy = policy) }.exceptionOrNull()
        println("R2_NOTE kind=$kind searches=$searches rows=${notes.allNotes().size} conflicts=${notes.observeConflicts().first().size} ackLosses=$ackLosses noProgress=${policy.consecutiveNoProgress} failure=$failure")
        return failure
    }
    @Test fun truncationBeforeNoteCommitNeverRenewsRecovery() = runBlocking<Unit> {
        assertTrue(runNotes("cutBeforeCommit") is LibraryNoProgressLimit)
        assertEquals(3, searches); assertEquals(1, notes.allNotes().size); assertEquals(3, policy.consecutiveNoProgress)
    }
    @Test fun lostNoteAckStillCountsCommittedNotesAndReplaysNewPhysicalSession() = runBlocking<Unit> {
        assertNull(runNotes("lostNoteAck")); assertEquals(4, searches)
        assertEquals(4, ackLosses); assertEquals(6, notes.allNotes().size)
    }
    @Test fun freshConflictCommitsInRealManagerRenewFourReadyIntervals() = runBlocking<Unit> {
        assertNull(runNotes("freshConflict")); assertEquals(4, searches)
        assertEquals(5, notes.observeConflicts().first().size); assertEquals(1, notes.allNotes().size)
        assertTrue(notes.note("local")!!.markdown.endsWith("local"))
    }
    @Test fun replayOfResolvedConflictCannotRenewRealManagerBudget() = runBlocking<Unit> {
        assertTrue(runNotes("resolvedConflict") is LibraryNoProgressLimit)
        assertEquals(3, searches); assertTrue(notes.observeConflicts().first().isEmpty())
        assertEquals(1, notes.allNotes().size)
    }
    private suspend fun runUsage(duplicate: Boolean): Throwable? {
        fun link(n: Int): PhoneSyncSession {
            val incoming = ArrayDeque<Byte>()
            var lastAction = ""; var page = 0L; var failed = false
            val output = object : ByteArrayOutputStream() {
                override fun flush() {
                    if (size() == 0) return
                    val q = BluetoothSyncProtocol.readFrame(toByteArray().inputStream()); reset()
                    val action = q.optString("action"); val phase = q.optString("phase")
                    if (action == "ack") {
                        if (lastAction == "syncLlmTokenUsage" && page < 5 && !failed) {
                            failed = true; ackLosses++; throw IOException("review injected usage ACK loss")
                        }
                        return
                    }
                    usageRequests += "$n:$action:$phase"
                    val response = when (action) {
                        "syncLibrary" -> when (phase) {
                            "probe" -> binding()
                            "cursor" -> LibrarySyncPayload.buildCursorResponse("watch", LibrarySyncCursor(0, 0, 0))
                            "manifest" -> start(false)[3]
                            "articles" -> ok("syncLibrary", "complete").put("articles", JSONArray())
                            else -> error("unexpected library phase $phase")
                        }
                        "syncAccount" -> ok("syncAccount")
                        "syncNotes" -> noteReply(emptyList())
                        "syncLlmTokenUsage" -> {
                            page = q.optLong("afterId") + 1
                            usage(page, listOf(if (duplicate) 1 else page), page < 5)
                        }
                        "syncSession" -> BluetoothSyncProtocol.buildSessionControlResponse(14, phase)
                        else -> error("unexpected request $action")
                    }
                    lastAction = action
                    ByteArrayOutputStream().also { BluetoothSyncProtocol.writeFrame(it, response) }.toByteArray()
                        .forEach { incoming.addLast(it) }
                }
            }
            val input = object : InputStream() { override fun read(): Int = if (incoming.isEmpty()) -1 else incoming.removeFirst().toInt() and 255 }
            return PhoneSyncSession(PhoneBluetoothSyncClient(app, log), device, "phone",
                PhoneSyncSession.Transport(input, output, "review-live-peer", false, {}), true, false, false).also { sessions += it }
        }
        manager.reconnectSession = { _, _ ->
            searches++; time += 6000
            assertEquals(searches.toLong(), tokens.cursor("owner:watch")!!.afterId)
            link(searches + 1)
        }
        val result = runCatching { manager.syncAll(device, reusableSession = link(1), recoveryPolicy = policy) }
        val failure = result.exceptionOrNull(); usageWarning = result.getOrNull()?.tokenUsageSyncWarning
        println("R2_USAGE duplicate=$duplicate searches=$searches rows=${tokens.observeRecent().first().size} ackLosses=$ackLosses warning=$usageWarning failure=$failure requests=$usageRequests")
        return failure
    }
    @Test fun lostUsageAckCountsNewCommittedRows() = runBlocking<Unit> {
        assertNull(runUsage(false)); assertNull("usage ACK loss must resume paging rather than become a warning", usageWarning)
        assertEquals(4, searches); assertEquals(4, ackLosses)
        assertEquals(5, tokens.observeRecent().first().size)
    }
    @Test fun lostUsageAckDoesNotPromoteDuplicateRowsToProgress() = runBlocking<Unit> {
        assertTrue(runUsage(true) is LibraryNoProgressLimit); assertEquals(3, searches)
        assertEquals(4, ackLosses); assertEquals(1, tokens.observeRecent().first().size)
    }
    private fun recovery(s: PhoneSyncSession): LibraryRecoveryContext {
        for (g in listOf(Long.MAX_VALUE - 2, Long.MAX_VALUE - 1)) {
            policy.observe(g); policy.ready(true); time += 6000; policy.disconnected(g)
        }
        time += 6000; policy.observe(s.transport!!.generation); policy.ready(true)
        s.transport!!.recoveryEnabled = true
        return LibraryRecoveryContext(policy, LibrarySyncMode.MERGE, { true }, { searches++; error("local SQL failure must not reconnect") },
            {}, {}, { name, _ -> if (name == "library.progress.confirmed") events++ }, {})
    }
    private fun note(id: String) = NoteEntity(id, null, "note", "remote", "remote", MarkdownNoteCodec.sha256("remote"),
        MarkdownNoteCodec.sha256("remote"), "remote", false, 1, 200, "watch")
    @Test fun abortingFirstNoteInsertCannotReportCommittedProgress() = runBlocking<Unit> {
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER r2_abort_note BEFORE INSERT ON notes BEGIN SELECT RAISE(ABORT,'r2_note_abort'); END")
        val s = session(listOf(noteReply(listOf(note("incoming")))))
        val failure = runCatching { withContext(recovery(s)) { manager.syncNotes(device.address, "r2-abort", s) } }.exceptionOrNull()
        assertNotNull(failure); assertFalse(failure is LibraryTransportFailure)
        assertNull(notes.note("incoming")); assertEquals(0, events); assertEquals(2, policy.consecutiveNoProgress); assertEquals(0, searches)
    }
    @Test fun secondNoteAbortPreservesAndCountsOnlyFirstCommittedNote() = runBlocking<Unit> {
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER r2_abort_note BEFORE INSERT ON notes WHEN NEW.noteId='second' BEGIN SELECT RAISE(ABORT,'r2_second_abort'); END")
        val s = session(listOf(noteReply(listOf(note("first"), note("second")))))
        val failure = runCatching { withContext(recovery(s)) { manager.syncNotes(device.address, "r2-abort-second", s) } }.exceptionOrNull()
        assertNotNull(failure); assertNotNull(notes.note("first")); assertNull(notes.note("second"))
        assertEquals(1, events); assertEquals(0, policy.consecutiveNoProgress); assertEquals(0, searches)
    }
    @Test fun conflictInsertAbortCannotRenewBudget() = runBlocking<Unit> {
        val base = notes.save("conflict", "title", "base"); notes.save("conflict", "title", "local")
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER r2_abort_conflict BEFORE INSERT ON note_conflicts BEGIN SELECT RAISE(ABORT,'r2_conflict_abort'); END")
        val remote = base.copy(markdown = "remote", contentHash = MarkdownNoteCodec.sha256("remote"), updatedAt = 200, modifiedBy = "watch")
        val s = session(listOf(noteReply(listOf(remote))))
        val failure = runCatching { withContext(recovery(s)) { manager.syncNotes(device.address, "r2-abort-conflict", s) } }.exceptionOrNull()
        assertNotNull(failure); assertTrue(notes.observeConflicts().first().isEmpty()); assertEquals("local", notes.note("conflict")!!.markdown)
        assertEquals(0, events); assertEquals(2, policy.consecutiveNoProgress); assertEquals(0, searches)
    }
    @Test fun usageCursorInsertAbortRollsBackRowsAndCannotReportProgress() = runBlocking<Unit> {
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER r2_abort_cursor BEFORE INSERT ON llm_token_usage_sync_state BEGIN SELECT RAISE(ABORT,'r2_cursor_abort'); END")
        val s = session(listOf(usage(2, listOf(1, 2), false)))
        val failure = runCatching { withContext(recovery(s)) { manager.syncLlmTokenUsage(device, s,
            BleSyncBindingSnapshot(device.address, "owner", s, binding())) } }.exceptionOrNull()
        assertNotNull(failure); assertTrue(tokens.observeRecent().first().isEmpty()); assertNull(tokens.cursor("owner:watch"))
        assertEquals(0, events); assertEquals(2, policy.consecutiveNoProgress); assertEquals(0, searches)
    }

    private fun disk(name: String): PhoneCompanionDatabase = Room.databaseBuilder(app, PhoneCompanionDatabase::class.java, name)
        .addMigrations(PhoneCompanionDatabase.MIGRATION_15_16, PhoneCompanionDatabase.MIGRATION_16_17,
            PhoneCompanionDatabase.MIGRATION_17_18, PhoneCompanionDatabase.MIGRATION_18_19)
        .addCallback(PhoneCompanionDatabase.ARTICLE_PROVENANCE_CALLBACK).allowMainThreadQueries().build().also { disks += it }
    private suspend fun upgrade(from: Int) {
        val name = "r2-migration-$from-${System.nanoTime()}"; diskNames += name
        var d = disk(name)
        var n = NoteRepository(d.noteDao(), "phone", now = { 100 })
        val base = n.save("preserved", "title", "base"); n.save("preserved", "title", "local")
        val remote = base.copy(markdown = "remote", contentHash = MarkdownNoteCodec.sha256("remote"), updatedAt = 200, modifiedBy = "watch")
        assertEquals(1, n.applyRemoteWithChanges(remote, "watch").addedConflicts)
        val conflict = n.observeConflicts().first().single(); d.noteDao().resolveConflict(conflict.conflictId, 123)
        d.llmTokenUsageDao().insert(PhoneLlmTokenUsageEntity(requestId = "legacy", createdAt = 1, totalTokens = 7))
        val article = PhoneArticleEntity("legacy", "source", "https://synthetic.invalid/article", "title", "site", "", null,
            "body", null, "body-hash", 1, 123, false, 0, 0, null, null, false, 0, 0, false, 0, 0, false, 0)
        d.phoneArticleDao().upsert(article)
        if (from >= 18) {
            PhoneLlmTokenUsageRepository(d.llmTokenUsageDao()).applyIncremental("owner:watch", usage(1, listOf(1), false))
            ImportedBookStore(d, File(app.cacheDir, name), null, "phone").mergeReading(JSONObject().put("bookId", "a".repeat(64))
                .put("chapterIndex", 1).put("isRead", true).put("readingProgress", .5).put("readingPositionBytes", 42)
                .put("readingPositionContentHash", "hash").put("readingPositionChangedAt", 123).put("sourceDeviceId", "watch"))
        }
        val sql = d.openHelper.writableDatabase
        val identity = sql.query("SELECT identity_hash FROM room_master_table WHERE id=42").use { it.moveToFirst(); it.getString(0) }
        if (from < 19) {
            listOf("article_provenance_freeze_insert", "article_provenance_freeze_update").forEach { sql.execSQL("DROP TRIGGER IF EXISTS $it") }
            listOf("article_register_provenance", "article_register_originals", "article_register_versions").forEach { sql.execSQL("DROP TABLE $it") }
        }
        if (from == 15) {
            val tables = sql.query("SELECT name FROM sqlite_master WHERE type='table'").use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
            tables.filter { it.startsWith("book_") || it.startsWith("imported_book") }.forEach { sql.execSQL("DROP TABLE $it") }
            sql.execSQL("DROP INDEX index_llm_token_usage_remoteKey")
            sql.execSQL("ALTER TABLE llm_token_usage DROP COLUMN remoteKey")
            sql.execSQL("DROP TABLE llm_token_usage_sync_state")
        }
        sql.version = from; d.close(); d = disk(name); n = NoteRepository(d.noteDao(), "phone", now = { 200 })
        assertEquals(19, d.openHelper.readableDatabase.version)
        assertEquals(identity, d.openHelper.readableDatabase.query("SELECT identity_hash FROM room_master_table WHERE id=42").use { it.moveToFirst(); it.getString(0) })
        assertEquals("local", n.note("preserved")!!.markdown)
        assertEquals(0, n.applyRemoteWithChanges(remote, "watch").addedConflicts)
        assertTrue(n.observeConflicts().first().isEmpty())
        val stored = d.openHelper.readableDatabase.query("SELECT resolvedAt FROM note_conflicts WHERE conflictId='${conflict.conflictId}'").use { it.moveToFirst(); it.getLong(0) }
        assertEquals(123L, stored)
        assertEquals(article, d.phoneArticleDao().getById("legacy"))
        assertEquals(if (from >= 18) 2 else 1, PhoneLlmTokenUsageRepository(d.llmTokenUsageDao()).observeRecent().first().size)
        if (from >= 18) {
            assertEquals(1L, d.llmTokenUsageDao().syncCursor("owner:watch")!!.afterId)
            assertEquals(1, ImportedBookStore(d, File(app.cacheDir, name), null, "phone").readingStates().size)
        }
        assertEquals(1, n.applyRemoteWithChanges(note("post-migration"), "watch").changedNotes)
        d.close(); d = disk(name)
        assertEquals("remote", NoteRepository(d.noteDao(), "phone").note("post-migration")!!.markdown)
        println("R2_MIGRATION from=$from to=${d.openHelper.readableDatabase.version} identity=$identity note=preserved resolvedConflict=preserved")
    }
    @Test fun populated15To19RunsRealMigrationsAndKeepsNotesConflictsAndLegacyUsage() = runBlocking<Unit> { upgrade(15) }
    @Test fun populated18To19KeepsBookReadingUsageCursorsAndResolvedConflicts() = runBlocking<Unit> { upgrade(18) }
    @Test fun existingSchema19ReopensWithoutMigrationOrDataReset() = runBlocking<Unit> { upgrade(19) }
}
