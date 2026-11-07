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
class SyncV2P5AccountTest {
    private fun code(expected:String,block:()->Unit){val e=runCatching(block).exceptionOrNull();assertNotNull("Expected $expected",e);assertEquals(expected,if(e is SyncV2CloudError)e.code else e!!.message)}
    @Test fun cleanDeviceFollowsRollbackAndKeepsIdentityAndOldLegacyCursors()=SyncV2P5Fixture().use{w->
        w.start();assertEquals("base\nline\n",w.text());val map=w.sql.query("SELECT COUNT(*) FROM syncv2_identity_map").use{it.moveToFirst();it.getInt(0)}
        w.rollbackRemote();w.actions().refresh()
        assertEquals("1",w.projection.state().body.getString("activeGeneration"));assertTrue(w.projection.state().body.isNull("pendingChoice"))
        assertEquals(map,w.sql.query("SELECT COUNT(*) FROM syncv2_identity_map").use{it.moveToFirst();it.getInt(0)})
        assertEquals(0,w.sql.query("SELECT COUNT(*) FROM sync_peer_state").use{it.moveToFirst();it.getInt(0)})
    }
    @Test fun missedEventsRebuildProjectionPreservesEditsAndPendingAcrossRestart()=SyncV2P5Fixture().use{w->
        w.pending();w.f.now+=604800001;w.historyExpired=true;w.actions().poll();assertEquals("offline\nline\n",w.text())
        assertEquals("awaiting-user",w.projection.status());val st=w.projection.state();w.reopen()
        assertEquals("awaiting-user",w.projection.status());assertEquals(st.revision,w.projection.state().revision)
    }
    @Test fun cloudChoiceMakesNoContentCommitAndAtomicallyPinsMinimalRecovery()=SyncV2P5Fixture().use{w->
        w.pending();val a=w.actions();val before=w.current.toString();a.choose(a.preview(),"cloud")
        assertEquals(before,w.current.toString());assertEquals(0,w.publishCalls);assertEquals("base\nline\n",w.text());assertTrue(w.projection.state().body.isNull("pendingChoice"))
        val list=a.recoveryCopies();assertEquals(1,list.length());val copyId=list.getJSONObject(0).getString("copyId");val delta=a.recoveryDelta(copyId)
        assertEquals(1,delta.getJSONArray("changes").length());assertEquals("offline\nline\n",delta.getJSONArray("changes").getJSONObject(0).getJSONObject("value").getJSONObject("state").getJSONObject("payload").getString("markdown"));assertEquals(0,delta.getJSONArray("bodyRefs").length())
        assertTrue(w.ackCalls>=2);w.reopen();assertEquals(copyId,w.actions().recoveryCopies().getJSONObject(0).getString("copyId"))
    }
    @Test fun confirmedAccountAloneControlsStagingAndLocalRevisionIsAudited()=SyncV2P5Fixture().use{w->
        w.pending();val a=w.actions();val p=a.preview();val captured=p.capture
        w.onConfirm={assertEquals(0,w.sql.query("SELECT COUNT(*) FROM syncv2_p5_rows").use{it.moveToFirst();it.getInt(0)})}
        w.afterCloudConfirmation={r->r.put("account",JSONObject().put("commitOid",w.initial).put("revision","999").put("rollbackGeneration","999"))}
        a.choose(p,"cloud");assertEquals(captured.localChangeRevision,w.selectedLocalRevision);assertEquals(captured.accountOid,w.projection.state().body.getJSONObject("appliedAccount").getString("oid"));assertEquals(captured.generation,w.projection.state().body.getString("activeGeneration"))
    }
    @Test fun localChoicePublishesResolveLocalAndKeepsWholeLocalSnapshot()=SyncV2P5Fixture().use{w->
        w.pending();val a=w.actions();a.choose(a.preview(),"local")
        val body=w.headers.getValue(w.current.getString("commitOid")).getJSONObject("body")
        assertEquals("resolve-local",body.getJSONObject("operation").getString("kind"));assertEquals("1",body.getString("rollbackGeneration"));assertEquals("1",body.getJSONObject("operation").getString("resolvesGeneration"))
        assertEquals("offline\nline\n",w.text());assertTrue(w.projection.state().body.isNull("pendingChoice"));assertEquals(0,a.recoveryCopies().length())
    }
    @Test fun mergeChoiceUsesUniquePlannerAndPublishesItsWholeCandidate()=SyncV2P5Fixture().use{w->
        w.pending();val a=w.actions();val preview=a.preview();assertTrue("merge reason=${preview.reason}",preview.canMerge);a.choose(preview,"merge")
        assertEquals("resolve-merge",w.headers.getValue(w.current.getString("commitOid")).getJSONObject("body").getJSONObject("operation").getString("kind"))
        assertEquals("offline\nline\n",w.text());assertEquals("old title",w.sql.query("SELECT title FROM notes").use{it.moveToFirst();it.getString(0)})
    }
    @Test fun fullNoteThreeWayMergeRecomputesDerivedFieldsBeforePublication()=SyncV2P5Fixture().use{w->
        w.pending();w.edit("base\nlocal\n")
        val oid=w.commit(listOf(w.note("old title","remote\nline\n")),w.current.getString("commitOid"),"1","normal",null);w.setCurrent(oid,SyncV2Codec.next(w.current.getString("revision")))
        val a=w.actions();a.refresh();w.drain();val p=a.preview();assertTrue(p.reason,p.canMerge);a.choose(p,"merge")
        assertEquals("remote\nlocal\n",w.text())
        val expected=com.lightningstudio.watchrss.phone.data.note.MarkdownNoteCodec.sha256(w.text())
        assertEquals(expected,w.sql.query("SELECT contentHash FROM notes").use{it.moveToFirst();it.getString(0)})
        assertEquals(com.lightningstudio.watchrss.phone.data.note.MarkdownNoteCodec.toPlainText(w.text()),w.sql.query("SELECT plainText FROM notes").use{it.moveToFirst();it.getString(0)})
    }
    @Test fun missingTargetEntityMakesMergeUnavailableWithoutPartialApply()=SyncV2P5Fixture().use{w->
        w.pending();val oid=w.commit(emptyList(),w.current.getString("commitOid"),"1","normal",null);w.setCurrent(oid,SyncV2Codec.next(w.current.getString("revision")))
        val a=w.actions();a.refresh();w.drain();val p=a.preview();assertFalse(p.canMerge);assertEquals("delete_edit_conflict",p.reason)
        code("merge_unavailable"){a.choose(p,"merge")};assertEquals("offline\nline\n",w.text());assertEquals(0,w.publishCalls)
    }
    @Test fun changedStateRejectsOldHandleBeforeAnyServerChoiceAndPreservesAllEdits()=SyncV2P5Fixture().use{w->
        w.pending();val a=w.actions();val preview=a.preview();w.edit("later\nline\n");val calls=w.choiceCalls
        code("choice_stale"){a.choose(preview,"cloud")};assertEquals(calls,w.choiceCalls);assertEquals("later\nline\n",w.text());assertEquals(0,a.recoveryCopies().length())
    }
    @Test fun serverChoiceStaleAndPublicationCasFailureKeepPendingEdits()=SyncV2P5Fixture().use{w->
        w.pending();val a=w.actions();val p=a.preview();w.current.put("revision",SyncV2Codec.next(w.current.getString("revision")))
        code("choice_stale"){a.choose(p,"cloud")};assertEquals("offline\nline\n",w.text());assertEquals("awaiting-user",w.projection.status())
    }
    @Test fun editAfterServerConfirmationRejectsLocalCommitAndRecoveryCanQueryExactRequest()=SyncV2P5Fixture().use{w->
        w.pending();val a=w.actions();val p=a.preview();w.onConfirm={w.sql.execSQL("UPDATE notes SET markdown='newer' WHERE noteId='note'")}
        code("choice_stale"){a.choose(p,"cloud")};assertEquals("newer",w.text());assertEquals(0,a.recoveryCopies().length());assertEquals("awaiting-user",w.projection.status())
        w.onConfirm=null;assertNotNull(a.reconcileStaleAction());assertTrue(w.projection.state().body.isNull("pendingAccountAction"));assertEquals("newer",w.text())
    }
    @Test fun crashBeforeCommitKeepsOldBusinessAndRestartReplaysSameAudit()=SyncV2P5Fixture().use{w->
        w.pending();val crashing=w.actions{if(it=="business-applied")throw java.io.IOException("injected crash")};val p=crashing.preview()
        assertNotNull(runCatching{crashing.choose(p,"cloud")}.exceptionOrNull());assertEquals("offline\nline\n",w.text());assertEquals(0,crashing.recoveryCopies().length())
        val requestId=w.projection.state().body.getJSONObject("pendingAccountAction").getString("id");w.reopen();w.actions().resume()
        assertEquals(1,w.results.keys.count{it==requestId});assertEquals("base\nline\n",w.text());assertEquals(1,w.actions().recoveryCopies().length())
    }
    @Test fun lostCloudAndPublishResponsesUseIdempotencyResults() {
        for(choice in listOf("cloud","local"))SyncV2P5Fixture().use{w->w.pending();val a=w.actions();w.drop=true;a.choose(a.preview(),choice)
            assertTrue(w.projection.state().body.isNull("pendingAccountAction"));assertEquals(1,if(choice=="cloud")w.choiceCalls else w.publishCalls)
        }
    }
    @Test fun applySuccessRemainsSuccessWhenAppliedAckFailsAndReopensForRetry()=SyncV2P5Fixture().use{w->
        w.pending();w.ackFailure=true;val a=w.actions();val response=a.choose(a.preview(),"cloud");assertTrue(response.getBoolean("localCommitted"));assertTrue(response.getBoolean("appliedAckPending"));assertEquals("base\nline\n",w.text())
        w.reopen();w.ackFailure=false;w.actions().flushAppliedAck();assertTrue(w.projection.state().body.isNull("pendingAppliedAck"))
    }
    @Test fun disabledGateKeepsPreparedActionQueueAndLocalEdits()=SyncV2P5Fixture().use{w->
        w.pending();val a=w.actions{if(it=="action-staged")throw java.io.IOException("pause")};runCatching{a.choose(a.preview(),"cloud")}
        val before=w.projection.state().body.toString();code("feature_disabled"){w.actions(enabled=false).resume()};assertEquals(before,w.projection.state().body.toString())
        w.flags=false;code("feature_disabled"){w.actions().resume()};assertEquals(before,w.projection.state().body.toString());assertEquals("offline\nline\n",w.text())
    }
    @Test fun recoveryQuotaFailureKeepsActivePendingAndEdits()=SyncV2P5Fixture().use{w->
        w.pending();val a=w.actions(quota=1);code("quota_exceeded"){a.choose(a.preview(),"cloud")};assertEquals("offline\nline\n",w.text());assertEquals("awaiting-user",w.projection.status());assertEquals(0,a.recoveryCopies().length())
    }
    @Test fun userDisabledRecoveryAffectsFutureChoiceAndDoesNotRemoveExistingCopies()=SyncV2P5Fixture().use{w->
        w.pending();val a=w.actions();a.setKeepDiscardedLocalChanges7Days(false);a.choose(a.preview(),"cloud");assertEquals(0,a.recoveryCopies().length());assertEquals("base\nline\n",w.text())
    }
    @Test fun rollbackCreatesNewSignedCommitWithTargetTreeAndGenerationAndHistoryIsOnDemand()=SyncV2P5Fixture().use{w->
        w.start();val a=w.actions();assertEquals(0,w.historyCalls);assertEquals(1,a.history().getJSONArray("items").length());val old=w.current.getString("commitOid")
        a.rollback(a.captureRollback(),w.target);val oid=w.current.getString("commitOid");assertNotEquals(w.target,oid);assertNotEquals(old,oid)
        val b=w.headers.getValue(oid).getJSONObject("body");assertEquals(old,b.getString("accountParent"));assertEquals(w.headers.getValue(w.target).getJSONObject("body").getString("treeRoot"),b.getString("treeRoot"));assertEquals("1",b.getString("rollbackGeneration"))
    }
    @Test fun expiredRollbackAndMissingClosureNeverChangeCurrentOrBusiness()=SyncV2P5Fixture().use{w->
        w.start();val a=w.actions();val current=w.current.toString();w.snapshotExpired=true
        code("snapshot_expired"){a.rollback(a.captureRollback(),w.target)};assertEquals(current,w.current.toString());assertEquals("base\nline\n",w.text())
        w.snapshotExpired=false;val tree=w.headers.getValue(w.target).getJSONObject("body").getString("treeRoot");w.f.objects.remove(tree)
        code("object_missing"){a.rollback(a.captureRollback(),w.target)};assertEquals(current,w.current.toString())
    }
    @Test fun multipleRollbacksAndReplayKeepDurableHighestGeneration()=SyncV2P5Fixture().use{w->
        w.start();repeat(3){w.rollbackRemote();w.actions().refresh();w.drain()};assertEquals("3",w.projection.state().body.getString("lastSeen"))
        w.setCurrent(w.initial,"999");code("replay_detected"){w.actions().refresh()};assertEquals("3",w.projection.state().body.getString("lastSeen"))
    }
    private fun stagedLargeControl(w:SyncV2P5Fixture):Pair<String,Int> {
        w.pending();val bytes=ByteArray(180000);java.util.Random(5943).nextBytes(bytes)
        val name=java.util.Base64.getEncoder().encodeToString(bytes)
        w.sql.execSQL("INSERT INTO note_folders VALUES('large-folder',?,0,0,'synthetic',0,0)",arrayOf(name));w.drain()
        var stopped=false;val a=w.actions{point->if(!stopped && point.startsWith("control-frame:") && point.substringAfter(':').toInt()>=131072){stopped=true;throw java.io.IOException("frame crash")}}
        assertNotNull(runCatching{a.choose(a.preview(),"local")}.exceptionOrNull());assertTrue(stopped)
        val action=w.projection.state().body.getJSONObject("pendingAccountAction").getString("id")
        return w.sql.query("SELECT body FROM syncv2_p5_objects WHERE action_id=? AND oid LIKE 'upload/%'",arrayOf(action)).use{c->buildList{while(c.moveToNext())add(w.projection.accountOpen("transfer",c.getBlob(0)))}.maxBy{it.getInt("durableOffset")}.let{it.getString("oid") to it.getInt("durableOffset")}}
    }
    @Test fun controlFramesResumeFromAuthenticatedOffsetAfterRestart()=SyncV2P5Fixture().use{w->
        val (oid,offset)=stagedLargeControl(w);assertEquals(131072,offset);val before=w.f.putOids.size;w.reopen();w.actions().resume()
        val resumed=w.f.putOids.indices.drop(before).filter{w.f.putOids[it]==oid}.map{w.f.putOffsets[it]}
        assertEquals(offset,resumed.first());assertTrue(w.projection.state().body.isNull("pendingAccountAction"))
    }
    @Test fun controlFramesRefuseAConfirmedPrefixRewindLargerThanOneFrame()=SyncV2P5Fixture().use{w->
        val (oid,_)=stagedLargeControl(w);w.f.prefix.remove(oid);val before=w.f.putOids.count{it==oid};w.reopen()
        code("offset_conflict"){w.actions().resume()};assertEquals(before,w.f.putOids.count{it==oid});assertFalse(w.projection.state().body.isNull("pendingAccountAction"));assertEquals("awaiting-user",w.projection.status())
    }
    @Test fun randomizedObservationEditCrashAndReopenKeepWholeStateCommitPoints():Unit {
        val random=java.util.Random(57043);val stages=listOf("before-action-stage","action-staged","server-confirmed","business-applied","before-account-commit","after-account-commit");val counts=linkedMapOf<String,Int>()
        repeat(24){round->SyncV2P5Fixture().use{w->
            w.pending();val stage=stages[random.nextInt(stages.size)];counts[stage]=(counts[stage]?:0)+1
            var injected=false
            val a=w.actions{point->if(!injected && point==stage){injected=true
                when(stage){"before-action-stage"->w.edit("new-tail-$round");"action-staged"->w.actions().refresh();"server-confirmed"->w.sql.execSQL("UPDATE notes SET markdown=? WHERE noteId='note'",arrayOf("new-tail-$round"));else->throw java.io.IOException("injected crash")}
            }}
            val p=a.preview();assertNotNull(runCatching{a.choose(p,"cloud")}.exceptionOrNull());assertTrue(injected)
            com.lightningstudio.watchrss.phone.data.local.CloudExportFileWitness.forgetProcessForTest(w.root);w.reopen()
            if(stage=="after-account-commit") {
                assertEquals("base\nline\n",w.text());assertEquals(1,w.actions().recoveryCopies().length());assertTrue(w.projection.state().body.isNull("pendingChoice"));w.actions().resume()
            }else {
                assertEquals(if(stage in setOf("before-action-stage","server-confirmed"))"new-tail-$round"else"offline\nline\n",w.text())
                assertEquals(0,w.actions().recoveryCopies().length());assertEquals("awaiting-user",w.projection.status())
                if(stage in setOf("business-applied","before-account-commit")){w.actions().resume();assertEquals("base\nline\n",w.text())}
            }
        }}
        System.getenv("WRSS_P5_EVIDENCE")?.let{dir->java.io.File(dir).mkdirs();java.io.File(dir,"atomic-random.json").writeText(JSONObject().put("rounds",24).put("seed",57043).put("stages",JSONObject(counts)).put("wholeStateAssertions",true).put("physicalPowerLoss",false).toString(2)+"\n")}
    }
    @Test fun exhaustedStateVersionStopsBeforeChoiceNetworkAndFaultHooks()=SyncV2P5Fixture().use{w->
        w.pending();w.sql.execSQL("UPDATE syncv2_state SET state_version=?",arrayOf(SyncV2Codec.maxD64.toString()))
        var hooks=0;val a=w.actions{hooks++};val p=a.preview();val calls=w.f.requests
        code("counter_exhausted"){a.choose(p,"cloud")};assertEquals(calls,w.f.requests);assertEquals(0,hooks);assertEquals("offline\nline\n",w.text());assertEquals(SyncV2Codec.maxD64.toString(),w.projection.state().version)
    }
    @Test fun thirtyConcurrentEditsInvalidateThirtyPreviewsWithoutLosingLatestBusiness():Unit=SyncV2P5Fixture().use{w->
        w.pending();val samples=mutableListOf<Long>()
        repeat(30){n->val a=w.actions();val p=a.preview();val start=System.nanoTime();val writer=Thread{w.sql.execSQL("UPDATE notes SET markdown=? WHERE noteId='note'",arrayOf("edit-$n"))};writer.start();writer.join()
            code("choice_stale"){a.choose(p,"cloud")};samples+=(System.nanoTime()-start)/1000000;assertEquals("edit-$n",w.text());w.drain()}
        assertEquals(0,w.choiceCalls)
        System.getenv("WRSS_P5_EVIDENCE")?.let{dir->java.io.File(dir).mkdirs();java.io.File(dir,"concurrency.json").writeText(JSONObject().put("rounds",30).put("retainedEdits",30).put("samples",samples.size).put("maxMillis",samples.max()).put("p99Reported",false).put("platform","JVM/native SQLite").toString(2)+"\n")}
    }
}
