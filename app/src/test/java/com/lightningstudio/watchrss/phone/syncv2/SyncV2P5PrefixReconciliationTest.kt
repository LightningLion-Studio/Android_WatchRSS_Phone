package com.lightningstudio.watchrss.phone.syncv2

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
class SyncV2P5PrefixReconciliationTest {
    private fun failure(expected:String,block:()->Unit){val e=runCatching(block).exceptionOrNull();assertNotNull(e);assertEquals(expected,if(e is SyncV2CloudError)e.code else e!!.message)}
    private fun reconcile(a:SyncV2AccountActions):JSONObject {
        var result=a.reconcileStaleAction()!!
        repeat(16){if(result.optBoolean("projectionPaused"))result=a.reconcileStaleAction()!!}
        assertFalse(result.toString(),result.optBoolean("projectionPaused"));return result
    }
    @Test fun successfulLocalAndMergeOnlyConfirmCapturedRevisionAndKeepTailPending() {
        for(choice in listOf("local","merge"))SyncV2P5Fixture().use{w->
            w.pending();val a=w.actions();val p=a.preview();val revision=p.capture.localChangeRevision
            w.onConfirm={w.sql.execSQL("UPDATE notes SET pinned=1 WHERE noteId='note'")}
            failure("choice_stale"){a.choose(p,choice)};w.onConfirm=null
            val id=w.projection.state().body.getJSONObject("pendingAccountAction").getString("id");val published=w.current.getString("commitOid")
            val result=reconcile(a);assertTrue(result.getBoolean("localCommitted"));assertFalse(result.optBoolean("tailReconciliationPending"))
            val st=w.projection.state();assertEquals(revision,st.body.getString("acknowledgedLocalRevision"));assertTrue(SyncV2Codec.d64(st.revision)>SyncV2Codec.d64(revision))
            assertEquals(published,st.body.getJSONObject("appliedAccount").getString("oid"));assertEquals(published,st.body.getJSONObject("editBase").getString("accountOid"))
            assertEquals("awaiting-user",w.projection.status());assertTrue(st.body.isNull("pendingAccountAction"));assertEquals(id,st.body.getJSONObject("accountActionReceipt").getString("id"))
            w.sql.query("SELECT title,pinned FROM notes").use{assertTrue(it.moveToFirst());assertEquals(if(choice=="merge")"old title"else"new title",it.getString(0));assertEquals(1,it.getInt(1))}
            w.reopen();assertEquals("awaiting-user",w.projection.status());assertEquals(revision,w.projection.state().body.getString("acknowledgedLocalRevision"))
        }
    }
    @Test fun lostPublishResponseAndRestartStillUsePublishedCandidateAndTail():Unit=SyncV2P5Fixture().use{w->
        w.pending();val a=w.actions();val p=a.preview();w.drop=true
        w.onConfirm={w.sql.execSQL("UPDATE notes SET pinned=1 WHERE noteId='note'")}
        failure("choice_stale"){a.choose(p,"merge")};w.onConfirm=null;val id=w.projection.state().body.getJSONObject("pendingAccountAction").getString("id")
        w.reopen();assertTrue(reconcile(w.actions()).getBoolean("localCommitted"));assertEquals(1,w.results.keys.count{it==id})
        w.sql.query("SELECT title,pinned FROM notes").use{assertTrue(it.moveToFirst());assertEquals("old title",it.getString(0));assertEquals(1,it.getInt(1))}
    }
    @Test fun laterAuthenticatedObservationIsNotReplacedByOlderPublishedPrefix():Unit=SyncV2P5Fixture().use{w->
        w.pending();val a=w.actions();val p=a.preview();w.onConfirm={w.sql.execSQL("UPDATE notes SET pinned=1 WHERE noteId='note'")}
        failure("choice_stale"){a.choose(p,"merge")};w.onConfirm=null;val published=w.current.getString("commitOid")
        val newest=w.commit(listOf(w.note("later cloud","offline\nline\n")),published,"1","normal",null);w.setCurrent(newest,SyncV2Codec.next(w.current.getString("revision")))
        a.refresh();w.drain();assertTrue(reconcile(a).getBoolean("localCommitted"))
        val st=w.projection.state();assertEquals(newest,st.body.getJSONObject("account").getString("oid"));assertEquals(published,st.body.getJSONObject("appliedAccount").getString("oid"))
        assertEquals("later cloud",w.projection.cloudRows().single().getJSONObject("meta").getJSONObject("payload").getString("title"))
        assertEquals("old title",w.sql.query("SELECT title FROM notes").use{it.moveToFirst();it.getString(0)})
        assertEquals("awaiting-user",w.projection.status())
    }
    @Test fun conflictingTailKeepsSuccessfulCandidateUntilExplicitTailResolution():Unit=SyncV2P5Fixture().use{w->
        w.pending();val a=w.actions();val p=a.preview();w.onConfirm={w.sql.execSQL("UPDATE notes SET title='tail title' WHERE noteId='note'")}
        failure("choice_stale"){a.choose(p,"merge")};w.onConfirm=null;val id=w.projection.state().body.getJSONObject("pendingAccountAction").getString("id")
        val result=reconcile(a);assertTrue(result.getBoolean("tailReconciliationPending"));assertEquals("identity_conflict",result.getString("reason"))
        assertEquals("tail title",w.sql.query("SELECT title FROM notes").use{it.moveToFirst();it.getString(0)});assertTrue(w.sql.query("SELECT COUNT(*) FROM syncv2_p5_rows").use{it.moveToFirst();it.getInt(0)}>0)
        w.reopen();assertEquals(id,w.projection.state().body.getJSONObject("pendingAccountAction").getString("id"))
        w.sql.execSQL("UPDATE notes SET title='old title' WHERE noteId='note'")
        assertTrue(reconcile(w.actions()).getBoolean("localCommitted"));assertEquals("old title",w.sql.query("SELECT title FROM notes").use{it.moveToFirst();it.getString(0)})
    }
    @Test fun reconciliationCrashRollsBackPrefixAndTailTogetherAndCanRetry():Unit=SyncV2P5Fixture().use{w->
        w.pending();val a=w.actions();val p=a.preview();w.onConfirm={w.sql.execSQL("UPDATE notes SET pinned=1 WHERE noteId='note'")}
        failure("choice_stale"){a.choose(p,"merge")};w.onConfirm=null;val oldAck=w.projection.state().body.getString("acknowledgedLocalRevision")
        val crashing=w.actions{if(it=="before-account-commit")throw java.io.IOException("reconcile crash")}
        assertNotNull(runCatching{reconcile(crashing)}.exceptionOrNull())
        w.reopen();assertEquals(oldAck,w.projection.state().body.getString("acknowledgedLocalRevision"));assertFalse(w.projection.state().body.isNull("pendingAccountAction"))
        w.sql.query("SELECT title,pinned FROM notes").use{it.moveToFirst();assertEquals("new title",it.getString(0));assertEquals(1,it.getInt(1))}
        assertTrue(reconcile(w.actions()).getBoolean("localCommitted"));assertEquals("old title",w.sql.query("SELECT title FROM notes").use{it.moveToFirst();it.getString(0)})
    }
    @Test fun unknownPublishOutcomeCannotDropStagedCandidateOrTail():Unit=SyncV2P5Fixture().use{w->
        w.pending();val a=w.actions{if(it=="action-staged")throw java.io.IOException("staged only")};val p=a.preview()
        assertNotNull(runCatching{a.choose(p,"local")}.exceptionOrNull());w.sql.execSQL("UPDATE notes SET pinned=1 WHERE noteId='note'")
        val before=w.current.toString();val result=reconcile(w.actions());assertEquals("unknown",result.getString("status"));assertEquals("request_outcome_unknown",result.getString("reason"))
        assertFalse(w.projection.state().body.isNull("pendingAccountAction"));assertEquals(before,w.current.toString());assertEquals(0,w.publishCalls)
        assertEquals(1,w.sql.query("SELECT pinned FROM notes").use{it.moveToFirst();it.getInt(0)})
    }
    @Test fun newlyImportedTailBodySurvivesPublishedPrefixAndItsNextExplicitChoice():Unit=SyncV2P5Fixture().use{w->
        w.pending();val a=w.actions();val p=a.preview()
        val article=com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity("tail-body","synthetic","https://example.invalid/tail","title","site","summary",null,"only original tail body",null,"tail-body-hash",0,0,true,0,0,null,null,true,0,0,false,0,0,false,0)
        w.onConfirm={kotlinx.coroutines.runBlocking{w.room.phoneArticleDao().upsert(article)}}
        failure("choice_stale"){a.choose(p,"merge")};w.onConfirm=null
        assertTrue(reconcile(a).getBoolean("localCommitted"));assertEquals("awaiting-user",w.projection.status())
        assertEquals("old title",w.sql.query("SELECT title FROM notes").use{it.moveToFirst();it.getString(0)})
        assertEquals("only original tail body",w.sql.query("SELECT contentText FROM phone_articles WHERE articleId='tail-body'").use{it.moveToFirst();it.getString(0)})
        w.reopen();val reopened=w.actions();reopened.choose(reopened.preview(),"local")
        assertEquals("only original tail body",w.sql.query("SELECT contentText FROM phone_articles WHERE articleId='tail-body'").use{it.moveToFirst();it.getString(0)})
        assertEquals("old title",w.sql.query("SELECT title FROM notes").use{it.moveToFirst();it.getString(0)})
    }

}
