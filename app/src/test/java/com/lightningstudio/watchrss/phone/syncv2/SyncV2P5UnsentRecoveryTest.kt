package com.lightningstudio.watchrss.phone.syncv2

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SyncV2P5UnsentRecoveryTest {
    private fun code(block:()->Unit)=runCatching(block).exceptionOrNull()?.let{if(it is SyncV2CloudError)it.code else it.message}
    @Test fun staleUnsentAttemptReturnsChoiceStaleThenAllowsANewChoice():Unit=SyncV2P5Fixture().use{w->
        w.pending();val a=w.actions{if(it=="action-staged")throw java.io.IOException("unsent")}
        assertEquals("unsent",code{a.choose(a.preview(),"local")});assertEquals(0,w.publishCalls)
        w.edit("new tail");w.reopen();val resumed=w.actions();assertEquals("choice_stale",code{resumed.resume()})
        assertTrue(w.projection.state().body.isNull("pendingAccountAction"));assertEquals("new tail",w.text());assertEquals("awaiting-user",w.projection.status())
        resumed.choose(resumed.preview(),"local");assertEquals(1,w.publishCalls);assertEquals("new tail",w.text())
    }
    @Test fun explicitCancelAndReconcileCanEndUnsentLocalAndMergeAttempts() {
        for(choice in listOf("local","merge"))for(cancel in listOf(true,false))SyncV2P5Fixture().use{w->
            w.pending();val a=w.actions{if(it=="action-staged")throw java.io.IOException("unsent")};assertEquals("unsent",code{a.choose(a.preview(),choice)})
            w.reopen();val resumed=w.actions();val result=if(cancel)resumed.cancelUnsentAction()else resumed.reconcileStaleAction()!!
            assertTrue(result.getBoolean("cancelled"));assertEquals(0,w.publishCalls);assertEquals("offline\nline\n",w.text());assertEquals("awaiting-user",w.projection.status())
            resumed.choose(resumed.preview(),"cloud");assertEquals(1,w.choiceCalls)
        }
    }
    @Test fun editAfterUploadBeforePublicationMarkerRetiresOnlyTheUnsentAttempt():Unit=SyncV2P5Fixture().use{w->
        w.pending();var edited=false;val a=w.actions{if(it=="before-publication-mark"&&!edited){edited=true;w.edit("newest")}}
        assertEquals("choice_stale",code{a.choose(a.preview(),"local")});assertEquals(0,w.publishCalls);assertEquals("newest",w.text());assertTrue(w.projection.state().body.isNull("pendingAccountAction"))
    }
    @Test fun uncertainAttemptDetachesWithoutDroppingItsCandidateOrBlockingTheNewChoice():Unit=SyncV2P5Fixture().use{w->
        w.pending();val a=w.actions{if(it=="publication-attempted")throw java.io.IOException("fenced")};assertEquals("fenced",code{a.choose(a.preview(),"local")})
        val id=w.projection.state().body.getJSONObject("pendingAccountAction").getString("id");w.edit("tail");w.reopen()
        assertFalse(w.actions().cancelUnsentAction().getBoolean("cancelled"));val result=w.actions().reconcileStaleAction()!!;assertTrue(result.getBoolean("tailReconciliationPending"));assertTrue(result.getBoolean("detached"));assertTrue(w.projection.state().body.isNull("pendingAccountAction"));assertEquals(id,w.sql.query("SELECT action_id FROM syncv2_p5_detached").use{it.moveToFirst();it.getString(0)});assertEquals("tail",w.text())
        assertEquals("unknown",w.actions().reconcileDetachedAction(id).getString("status"));assertEquals(0,w.publishCalls)
        val fresh=w.actions();fresh.choose(fresh.preview(),"local");assertEquals(1,w.publishCalls);assertEquals("tail",w.text())
    }
    @Test fun oldJournalWithoutASendMarkerIsRetainedConservatively():Unit=SyncV2P5Fixture().use{w->
        w.pending();val a=w.actions{if(it=="action-staged")throw java.io.IOException("old-journal")};assertEquals("old-journal",code{a.choose(a.preview(),"local")})
        val state=w.projection.state();val id=state.body.getJSONObject("pendingAccountAction").getString("id")
        w.projection.accountWriter(state.version){it.body.getJSONObject("pendingAccountAction").remove("publicationAttempted")};w.reopen()
        assertFalse(w.actions().cancelUnsentAction(id).getBoolean("cancelled"));assertTrue(w.actions().cancelAction(id).getBoolean("detached"))
        assertEquals(1,w.sql.query("SELECT COUNT(*) FROM syncv2_p5_detached").use{it.moveToFirst();it.getInt(0)});assertEquals(0,w.publishCalls)
        assertTrue(w.sql.query("SELECT COUNT(*) FROM syncv2_p5_rows").use{it.moveToFirst();it.getInt(0)}>0)
    }
    @Test fun requestingANewPreviewAlsoEndsAStaleUnsentAttempt():Unit=SyncV2P5Fixture().use{w->
        w.pending();val a=w.actions{if(it=="action-staged")throw java.io.IOException("unsent")};assertEquals("unsent",code{a.choose(a.preview(),"local")})
        w.edit("latest edit");w.reopen();val fresh=w.actions();assertEquals("choice_stale",code{fresh.preview()})
        assertTrue(w.projection.state().body.isNull("pendingAccountAction"));fresh.choose(fresh.preview(),"cloud");assertEquals(1,w.choiceCalls)
    }
    @Test fun lateSuccessOfACancelledAttemptCannotOverwriteANewerChoice():Unit=SyncV2P5Fixture().use{w->
        w.pending();val a=w.actions{if(it=="server-confirmed")throw java.io.IOException("lost-result")};assertEquals("lost-result",code{a.choose(a.preview(),"local")})
        val id=w.projection.state().body.getJSONObject("pendingAccountAction").getString("id");w.edit("newest tail")
        assertTrue(w.actions().cancelAction(id).getBoolean("detached"));val fresh=w.actions();fresh.refresh();w.drain();fresh.choose(fresh.preview(),"local")
        val before=w.projection.state();val published=w.publishCalls;val result=fresh.reconcileDetachedAction(id)
        assertEquals("succeeded",result.getString("status"));assertFalse(result.getBoolean("localCommitted"));assertEquals(published,w.publishCalls)
        assertEquals("newest tail",w.text());assertEquals(before.body.getJSONObject("account").toString(),w.projection.state().body.getJSONObject("account").toString());assertEquals(before.revision,w.projection.state().revision)
    }
}
