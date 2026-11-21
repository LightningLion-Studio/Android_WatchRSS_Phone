package com.lightningstudio.watchrss.phone.syncv2

import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
import com.lightningstudio.watchrss.phone.connection.bluetooth.binaryDescriptor
import com.lightningstudio.watchrss.phone.connection.bluetooth.binaryFileHashes
import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.B2CommitWindow
import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.B2Wire
import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.assetDescriptor
import com.lightningstudio.watchrss.phone.data.note.NoteAssetFiles
import com.lightningstudio.watchrss.phone.data.book.BookPackageFiles
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SyncV2P5LegacyActionsTest {
    private fun key(cursor:String)=SyncV2Codec.canonical(JSONArray().put("peer").put(14).put(cursor))
    private fun stream()=SyncV2Codec.canonical(JSONArray().put("peer").put(14))
    private fun input(w:SyncV2P5Fixture,cursor:String,value:JSONObject=w.projection.accountLocalRows().single().getJSONObject("value")):SyncV2Projection.LegacyInput {
        val kind=if(value.has("articleId"))"article"else"note";val id=SyncV2Business.logicalId(kind,value)
        return SyncV2Projection.LegacyInput("peer",14,cursor,JSONObject().put(w.identity.entity(kind,id),value))
    }
    private fun receive(w:SyncV2P5Fixture,input:SyncV2Projection.LegacyInput){assertEquals("pendingChoice",w.projection.receiveLegacy(input,null,true))}
    private fun code(expected:String,block:()->Unit){val e=runCatching(block).exceptionOrNull();assertNotNull(e);assertEquals(expected,if(e is SyncV2CloudError)e.code else e!!.message)}
    @Test fun originalLegacyCloudEntryUsesN07AndNeverAdvancesOldAppliedCursor():Unit=SyncV2P5Fixture().use{w->
        w.pending();val incoming=input(w,"first");receive(w,incoming)
        val st=w.projection.state();w.projection.accountWriter(st.version){it.body.getJSONObject("applied").put(stream(),"older-applied")}
        val a=w.actions();val capture=w.projection.captureLegacyChoice(w.projection.state().body.getJSONObject("account"))
        w.projection.resolveLegacyCloud(capture)
        assertEquals("older-applied",w.projection.state().body.getJSONObject("applied").getString(stream()))
        assertEquals("first",w.projection.state().body.getJSONObject("received").getString(stream()))
        val id=a.recoveryCopies().getJSONObject(0).getString("copyId");assertEquals(1,a.recoveryDelta(id).getJSONArray("changes").length())
        val version=w.projection.state().version;assertEquals("discarded-cloud",w.projection.receiveLegacy(incoming,null,true));assertEquals(version,w.projection.state().version)
        w.reopen();assertEquals("older-applied",w.projection.state().body.getJSONObject("applied").getString(stream()));assertEquals(1,w.actions().recoveryDelta(id).getJSONArray("changes").length())
    }
    @Test fun normalizedLocalAndMergeApplyBusinessAndAcknowledgeOnlyContiguousActualInputs() {
        for(choice in listOf("local","merge"))SyncV2P5Fixture().use{w->
            w.pending();receive(w,input(w,"first"));val a=w.actions();val p=a.preview();assertTrue(p.reason,p.canMerge)
            a.choose(p,choice);assertEquals("offline\nline\n",w.text());assertEquals("first",w.projection.state().body.getJSONObject("applied").getString(stream()))
            assertEquals(1,w.sql.query("SELECT applied FROM syncv2_legacy_inbox").use{it.moveToFirst();it.getInt(0)})
            w.reopen();assertEquals("first",w.projection.state().body.getJSONObject("applied").getString(stream()))
        }
    }
    @Test fun selectingOneNormalizedInputKeepsOtherInputsAndTheirBodiesPending():Unit=SyncV2P5Fixture().use{w->
        w.pending();receive(w,input(w,"first"));receive(w,input(w,"second",w.note("other title","another pending body")))
        val a=w.actions();a.choose(a.preview(setOf(key("first"))),"cloud")
        val remaining=w.projection.accountLegacyEntries();assertEquals(1,remaining.size);assertEquals(key("second"),remaining.single().getString("key"))
        assertTrue(remaining.single().getJSONObject("intent").getJSONObject("body").toString().contains("another pending body"))
        assertEquals(1,w.projection.state().body.getJSONObject("pendingChoice").getJSONArray("inboxKeys").length())
        assertTrue(w.projection.state().body.getJSONObject("applied").length()==0)
    }
    @Test fun rollbackClosureKeepsIndependentFolderAndRefusesAMissingAssetWitness(): Unit = SyncV2P5Fixture().use { w ->
        fun row(kind: String, key: String, value: JSONObject, revision: String): JSONObject {
            val entity = w.identity.entity(kind, key)
            val parts = SyncV2Business.split(kind, key, entity, value, null)
            return JSONObject().put("meta", parts.meta).put("state", parts.state)
                .put("row", JSONObject().put("revision", revision))
        }
        val folder = JSONObject().put("folderId", "independent").put("name", "independent").put("sortOrder", 0)
            .put("updatedAt", 1).put("modifiedBy", "phone").put("deleted", false).put("deletedAt", 0)
        val assetId = "attached.png"
        val asset = JSONObject().put("assetId", assetId).put("mime", "image/png").put("originalFilename", assetId)
            .put("updatedAt", 1).put("sourceDeviceId", "phone").put("deleted", false).put("deletedAt", 0)
        val body = "![attached](assets/$assetId)"
        val untouched = row("folder", "independent", folder, "1")
        val attachment = row("asset", assetId, asset, "1")
        val first = row("note", "note", w.note("before", body), "1")
        val second = row("note", "note", w.note("after", body), "2")
        val stable = SyncV2BluetoothImpact.stableEntities(w.identity,
            listOf(untouched, attachment, first), listOf(untouched, attachment, second))
        assertEquals(setOf(w.identity.entity("folder", "independent")), stable)
        assertNull(SyncV2BluetoothImpact.stableEntities(w.identity,
            listOf(untouched, first), listOf(untouched, second)))
    }

    @Test fun protectedBookStateRetainsItsFrozenPackageConstraintWithoutInventingBodyBytes(): Unit =
        SyncV2P5Fixture().use { w ->
            w.pending()
            val fixture = JSONObject(requireNotNull(javaClass.getResource("/book/cli-single-txt-book.json")).readText())
            val id = fixture.getString("bookId")
            val raw = fixture.getString("packageUtf8").toByteArray()
            val hash = SyncV2Codec.hex(SyncV2Codec.sha(raw))
            val state = JSONObject().put("bookId", id).put("fingerprint", fixture.getString("fingerprint"))
                .put("title", "Book waiting for its original").put("chapterCount", 1)
                .put("deleted", false).put("deletedAt", 0).put("deletionClock", 0).put("deletedChapters", JSONArray())
                .put("updatedAt", 1).put("sourceDeviceId", "watch").put("sourceUrl", fixture.getString("sourceUrl"))
                .put("kind", "txt")
            val staged = File(w.root, "book-state").apply { writeText(state.toString()) }
            val desc = binaryDescriptor(B2Wire.BOOK_STATE, id, JSONObject().put("bookId", id), binaryFileHashes(staged))
            val segment = "b".repeat(64)
            val closure = JSONObject().put("requiredObjectIds", JSONArray().put(segment))
                .put("package", JSONObject().put("bookId", id).put("fingerprint", fixture.getString("fingerprint"))
                    .put("packageSha256", hash).put("packageBytes", raw.size).put("segmentObjectIds", JSONArray().put(segment)))
            val input = B2CommitWindow.InboxInput("watch", "watchToPhone", "a".repeat(32), "c".repeat(64), desc, staged) { closure }
            val archive = SyncV2BluetoothInbox(w.projection, w.root)
            val pending = runBlocking { requireNotNull(archive.prepare(input) {}) }
            w.sql.beginTransaction()
            try {
                pending.commit()
                w.sql.setTransactionSuccessful()
            } finally {
                w.sql.endTransaction()
            }
            pending.finish(true)
            val actions = w.actions()
            code("closure_incomplete") { actions.choose(actions.preview(firstLegacyOnly = true), "cloud") }
            assertEquals(0, actions.recoveryCopies().length())
            val link = w.projection.accountLegacyEntries().single().getJSONObject("intent")
                .getJSONObject("body").getJSONObject("guarded-transfer")
            assertEquals(closure.toString(), link.getJSONObject("bookClosure").toString())
            val stored = archive.read(link.getString("archiveId"), staged.length(), 0, staged.length().toInt())
            assertArrayEquals(staged.readBytes(), stored)
            val recovery = SyncV2RecoveryStorage(w.projection, w.identity, w.root)
            assertFalse(recovery.guardedAlreadyPresent(link))
            w.sql.execSQL("INSERT INTO imported_books VALUES(?,?,?,?)", arrayOf<Any>(id, state.toString(), hash, raw.size))
            val original = BookPackageFiles.legacy(File(w.root, "imported_books"), id)
            original.parentFile!!.mkdirs()
            original.writeBytes(raw)
            assertTrue(recovery.guardedAlreadyPresent(link))
            original.writeBytes(raw.copyOf().apply { this[lastIndex] = 0 })
            assertFalse(recovery.guardedAlreadyPresent(link))
        }

    @Test fun cloudChoicePinsTheReceivedAttachmentAndLeavesItsOldReceiptRejected(): Unit = SyncV2P5Fixture().use { w ->
        w.pending()
        val key = "archive.png"
        val raw = "synthetic incoming image".toByteArray()
        val legacy = NoteAssetFiles.legacy(w.root, key).apply {
            parentFile!!.mkdirs()
            writeText("independent local image")
        }
        val oldSha = SyncV2Codec.hex(SyncV2Codec.sha(legacy.readBytes()))
        w.edit("![picture](assets/$key)")
        w.sql.execSQL("INSERT INTO note_assets VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf("local-asset", "note", oldSha, key, "image/png", legacy.length(), key, true, w.f.now, false, 0L))
        w.drain()
        val original = File(w.root, "staged").apply { writeBytes(raw) }
        val descriptor = assetDescriptor(raw, key)
        val input = B2CommitWindow.InboxInput("watch", "watchToPhone", "a".repeat(32), "b".repeat(64), descriptor, original)
        val inbox = SyncV2BluetoothInbox(w.projection, w.root)
        val pending = runBlocking { requireNotNull(inbox.prepare(input) {}) }
        w.sql.beginTransaction()
        try {
            pending.commit()
            w.sql.setTransactionSuccessful()
        } finally {
            w.sql.endTransaction()
        }
        pending.finish(true)
        val record = w.projection.accountLegacyEntries().single()
        assertTrue(w.projection.state().body.getJSONObject("applied").length() == 0)
        val archived = record.getJSONObject("intent").getJSONObject("body").getJSONObject("guarded-transfer")
        val file = File(w.root, "syncv2-guarded-inbox/${archived.getString("archiveId")}.body")
        assertFalse(file.readBytes().contentEquals(raw))
        assertArrayEquals(raw, inbox.read(archived.getString("archiveId"), raw.size.toLong(), 0, raw.size))
        val actions = w.actions()
        val preview = actions.preview(firstLegacyOnly = true)
        assertEquals(listOf(record.getString("key")), preview.selectedLegacyKeys)
        actions.choose(preview, "cloud")
        assertFalse(file.exists())
        assertTrue(w.projection.state().body.getJSONObject("applied").length() == 0)
        val restored = actions.recoveryCopies().let { copies ->
            (0 until copies.length()).map { copies.getJSONObject(it).getString("copyId") }
        }.first { id ->
            val changes = actions.recoveryDelta(id).getJSONArray("changes")
            changes.length() == 1 && changes.getJSONObject(0).optJSONObject("value")?.has("guardedAsset") == true
        }
        val header = actions.recoveryCopies().let { copies ->
            (0 until copies.length()).map(copies::getJSONObject).single { it.getString("copyId") == restored }
        }
        assertEquals(604800000L, header.getLong("expiresAtMillis") - header.getLong("createdAtMillis"))
        val bodyRefs = actions.recoveryDelta(restored).getJSONArray("bodyRefs")
        val recipe = (0 until bodyRefs.length()).map(bodyRefs::getString).single { it.startsWith("recipe:") }
        assertArrayEquals(raw, actions.recoveryBodyPart(restored, recipe, 0, raw.size))
        code("choice_stale") { actions.restoreRecovery(restored) }
    }

    @Test fun capturedFirstChoiceCannotApplyTheNewFirstEntry(): Unit = SyncV2P5Fixture().use { w ->
        w.pending()
        receive(w, input(w, "first"))
        receive(w, input(w, "second", w.note("other title", "other pending body")))
        val actions = w.actions()
        val old = actions.preview(firstLegacyOnly = true)
        assertEquals(listOf(key("first")), old.selectedLegacyKeys)
        actions.choose(actions.preview(setOf(key("first"))), "cloud")
        val calls = w.choiceCalls
        code("choice_stale") { actions.choose(old, "cloud") }
        assertEquals(calls, w.choiceCalls)
        assertEquals(listOf(key("second")), actions.preview(firstLegacyOnly = true).selectedLegacyKeys)
    }

    @Test fun rawFrameDoesNotBlockAnotherExplicitNormalizedSelection():Unit=SyncV2P5Fixture().use{w->
        w.pending();receive(w,SyncV2Projection.LegacyInput("peer",14,"raw",JSONObject().put("legacy-frame",JSONObject().put("opaque","kept"))))
        receive(w,input(w,"normalized"));val a=w.actions();a.choose(a.preview(setOf(key("normalized"))),"cloud")
        assertEquals(key("raw"),w.projection.accountLegacyEntries().single().getString("key"))
        assertTrue(w.projection.accountLegacyEntries().single().getJSONObject("intent").getJSONObject("body").has("legacy-frame"))
        code("unsupported_feature"){a.preview(setOf(key("raw")))}
    }
    @Test fun localSelectionAfterAnUnappliedGapNeverFakesTheCumulativeLegacyAck():Unit=SyncV2P5Fixture().use{w->
        w.pending();receive(w,SyncV2Projection.LegacyInput("peer",14,"raw",JSONObject().put("legacy-frame",JSONObject().put("opaque","kept"))))
        receive(w,input(w,"normalized"));val a=w.actions();val p=a.preview(setOf(key("normalized")));assertEquals(listOf(key("normalized")),p.selectedLegacyKeys)
        a.choose(p,"local");assertTrue(w.projection.state().body.getJSONObject("applied").length()==0)
        assertEquals(1,w.sql.query("SELECT applied FROM syncv2_legacy_inbox WHERE cursor='normalized'").use{it.moveToFirst();it.getInt(0)})
        assertEquals(key("raw"),w.projection.accountLegacyEntries().single().getString("key"))
    }
    @Test fun normalizedCompleteNewFolderCanBeAppliedThroughTheLocalChoice():Unit=SyncV2P5Fixture().use{w->
        w.pending();val value=JSONObject().put("folderId","incoming-folder").put("name","incoming").put("sortOrder",0).put("updatedAt",1).put("modifiedBy","peer").put("deleted",false).put("deletedAt",0)
        receive(w,SyncV2Projection.LegacyInput("peer",14,"folder",JSONObject().put(w.identity.entity("folder","incoming-folder"),value)))
        val a=w.actions();a.choose(a.preview(),"local")
        assertEquals("incoming",w.sql.query("SELECT name FROM note_folders WHERE folderId='incoming-folder'").use{it.moveToFirst();it.getString(0)})
        assertEquals("folder",w.projection.state().body.getJSONObject("applied").getString(stream()))
    }
    @Test fun cloudChoiceSavesDistinctLocalAndPendingVersionsWithoutDuplicatingEquivalentValues():Unit=SyncV2P5Fixture().use{w->
        w.pending();receive(w,input(w,"first",w.note("incoming title","incoming body")))
        receive(w,input(w,"second",w.note("incoming title","incoming body")))
        val a=w.actions();val p=a.preview();assertFalse(p.canMerge);a.choose(p,"cloud");assertEquals(2,a.recoveryCopies().length())
        val texts=(0 until a.recoveryCopies().length()).map{i->a.recoveryDelta(a.recoveryCopies().getJSONObject(i).getString("copyId")).getJSONArray("changes").getJSONObject(0).getJSONObject("value").getJSONObject("state").getJSONObject("payload").getString("markdown")}.toSet()
        assertEquals(setOf("offline\nline\n","incoming body"),texts);w.reopen();assertEquals(2,w.actions().recoveryCopies().length())
    }
    @Test fun combinedRecoveryQuotaStopsBeforeCloudAuditAndKeepsEveryPendingVersion():Unit=SyncV2P5Fixture().use{w->
        w.pending();receive(w,input(w,"first",w.note("incoming title","incoming body")));val a=w.actions(quota=9000)
        code("quota_exceeded"){a.choose(a.preview(),"cloud")};assertEquals(0,w.choiceCalls);assertEquals("offline\nline\n",w.text());assertEquals(1,w.projection.accountLegacyEntries().size)
    }
    @Test fun failedLegacyDispositionRollsBackRecoveryBusinessAndMarkersAndResumesSameAudit():Unit=SyncV2P5Fixture().use{w->
        w.pending();receive(w,input(w,"first"));w.sql.execSQL("CREATE TRIGGER reject_legacy_disposition BEFORE UPDATE OF route ON syncv2_legacy_inbox BEGIN SELECT RAISE(ABORT,'injected disposition failure'); END")
        val a=w.actions();assertNotNull(runCatching{a.choose(a.preview(),"cloud")}.exceptionOrNull())
        assertEquals("offline\nline\n",w.text());assertEquals(0,a.recoveryCopies().length());assertEquals(1,w.projection.accountLegacyEntries().size);assertEquals(1,w.choiceCalls)
        w.sql.execSQL("DROP TRIGGER reject_legacy_disposition");w.reopen();w.actions().resume()
        assertEquals(2,w.choiceCalls);assertEquals(1,w.results.size);assertEquals(1,w.actions().recoveryCopies().length());assertTrue(w.projection.accountLegacyEntries().isEmpty());assertTrue(w.projection.state().body.getJSONObject("applied").length()==0)
    }
    @Test fun normalizedFractionalProgressSurvivesEncryptedInboxRestartAndRecovery():Unit=SyncV2P5Fixture().use{w->
        w.pending();val article=PhoneArticleEntity("legacy-progress","synthetic","https://example.invalid/legacy-progress","title","site","summary",null,"original body",null,"body-hash",0,0,true,0,0,null,null,true,0,0,false,0,0,false,0).copy(readingProgress=0.75f)
        runBlocking{w.room.phoneArticleDao().upsert(article)};w.drain()
        val value=w.projection.accountLocalRows().single{it.getJSONObject("meta").getString("kind")=="article"}.getJSONObject("value")
        val incoming=input(w,"fraction",value);receive(w,incoming);w.reopen()
        assertEquals(0.75,w.projection.accountLegacyEntries().single().getJSONObject("intent").getJSONObject("body").getJSONObject(w.identity.entity("article","legacy-progress")).getDouble("readingProgress"),0.0)
        val a=w.actions();a.choose(a.preview(),"cloud")
        val changes=a.recoveryDelta(a.recoveryCopies().getJSONObject(0).getString("copyId")).getJSONArray("changes")
        val saved=(0 until changes.length()).map{changes.getJSONObject(it)}.single{it.getString("id")==w.identity.entity("article","legacy-progress")}
        assertEquals(750000,saved.getJSONObject("value").getJSONObject("state").getJSONObject("payload").getInt("readingProgressMicros"))
    }
    @Test fun normalizedDifferentBodyCannotRecoverUnrelatedOriginalOrDiscardTheInbox():Unit=SyncV2P5Fixture().use{w->
        w.pending();val article=PhoneArticleEntity("legacy-body","synthetic","https://example.invalid/legacy-body","title","site","summary",null,"original body",null,"original-hash",0,0,true,0,0,null,null,true,0,0,false,0,0,false,0)
        runBlocking{w.room.phoneArticleDao().upsert(article)};w.drain()
        val value=JSONObject(w.projection.accountLocalRows().single{it.getJSONObject("meta").getString("kind")=="article"}.getJSONObject("value").toString()).put("contentHash","different-incoming-body")
        receive(w,input(w,"missing-body",value));val a=w.actions()
        code("closure_incomplete"){a.choose(a.preview(),"cloud")}
        assertEquals(0,w.choiceCalls);assertEquals(0,a.recoveryCopies().length());assertEquals(1,w.projection.accountLegacyEntries().size)
        assertEquals("original body",w.sql.query("SELECT contentText FROM phone_articles WHERE articleId='legacy-body'").use{it.moveToFirst();it.getString(0)})
    }

}
