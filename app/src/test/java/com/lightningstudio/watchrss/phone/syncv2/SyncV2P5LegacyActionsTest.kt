package com.lightningstudio.watchrss.phone.syncv2

import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
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
