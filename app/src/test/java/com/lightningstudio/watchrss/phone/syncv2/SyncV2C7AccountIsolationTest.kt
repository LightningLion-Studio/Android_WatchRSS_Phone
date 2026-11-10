package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/**
 * C7 review issues 1-4 and 16: an isolated note (F1/R3) is a verbatim wire row on every account
 * path. Normal refresh applies the other entities and advances the ACK; choose(cloud/local/merge)
 * and rollback carry it back under its original stateOid and never crash on its missing value.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SyncV2C7AccountIsolationTest {
    private val isolate:(String,JSONObject)->Unit={id,state->if(id=="note")state.put("requiredFeatures",JSONArray().put(SyncV2NoteSegments.feature))}
    private fun SyncV2P5Fixture.other(markdown:String="other\n")=note("other",markdown).put("noteId","note2")
    private fun SyncV2P5Fixture.treeRows(commit:String)=SyncV2Index.read(identity.scope,headers.getValue(commit).getJSONObject("body").getString("treeRoot")){f.objects.getValue(it)}
    private fun SyncV2P5Fixture.stateOid(commit:String,noteId:String)=treeRows(commit).first{it.getString("entityKey")==identity.entity("note",noteId)}.getString("stateOid")
    private fun SyncV2P5Fixture.textOf(noteId:String)=sql.query("SELECT markdown FROM notes WHERE noteId=?",arrayOf(noteId)).use{if(it.moveToFirst())it.getString(0) else null}
    private fun SyncV2P5Fixture.publishIsolated(markdown:String="cloud\nline\n"):String {
        stateHook=isolate
        val oid=commit(listOf(note("cloud title",markdown),other()),current.getString("commitOid"),current.getString("rollbackGeneration"),"normal",null)
        stateHook=null;setCurrent(oid,SyncV2Codec.next(current.getString("revision")));return oid
    }

    @Test fun refreshAppliesOtherEntitiesAndAdvancesAckWhenAnIsolatedNoteExists()=SyncV2P5Fixture().use{w->
        w.start();assertEquals("base\nline\n",w.text())
        val oid=w.publishIsolated();val acks=w.ackCalls
        w.actions().refresh();w.drain()
        assertEquals("the isolated note keeps its local business body","base\nline\n",w.text())
        assertEquals("other entities still apply","other\n",w.textOf("note2"))
        assertEquals("unsupported_feature",w.projection.noteIsolation().getJSONObject(w.identity.entity("note","note")).getString("reason"))
        assertEquals(oid,w.projection.state().body.getJSONObject("appliedAccount").getString("oid"))
        assertTrue("the applied ACK advances",w.ackCalls>acks)
        // A second poll is stable (no crash, no re-isolation churn).
        w.actions().poll();w.drain();assertEquals("other\n",w.textOf("note2"))
    }

    private fun pendingWithIsolatedTarget(w:SyncV2P5Fixture):String {
        w.start()
        val c1=w.commit(listOf(w.note("new title","base\nline\n"),w.other()),w.current.getString("commitOid"),"0","normal",null);w.setCurrent(c1,"3")
        w.actions().refresh();w.drain();assertEquals("other\n",w.textOf("note2"))
        val cache=w.projection.prepareWireIndex(w.f.signer,w.key,1)
        val indexed=SyncV2Index.read(w.identity.scope,cache.tree.root,cache.objects::getValue).associateBy{it.getString("entityKey")}
        w.projection.accountStoreOwnWireRows(w.projection.accountLocalRows().map{it.put("row",indexed.getValue(it.getJSONObject("meta").getString("entityKey")))},cache.objects)
        w.sql.execSQL("UPDATE notes SET markdown='mine\n',plainText='mine\n',updatedAt=updatedAt+1 WHERE noteId='note2'");w.drain()
        w.stateHook=isolate
        val t=w.commit(listOf(w.note("cloud title","cloud\nline\n"),w.other()),c1,"0","normal",null)
        w.stateHook=null;w.target=t
        w.setCurrent(c1,"3");w.rollbackRemote(listOf(w.note("cloud title","cloud\nline\n"),w.other()))
        w.actions().refresh();w.drain()
        return w.stateOid(t,"note")
    }

    @Test fun everyChoiceCarriesTheIsolatedRowBackVerbatim() {
        for(choice in listOf("cloud","local","merge"))SyncV2P5Fixture().use{w->
            val isolatedState=pendingWithIsolatedTarget(w)
            assertEquals("awaiting-user",w.projection.status())
            val entity=w.identity.entity("note","note")
            val cached=w.sql.query("SELECT body FROM syncv2_wire_candidates WHERE scope=? AND entity_key=?",arrayOf(w.projection.scope,entity)).use{it.moveToFirst();it.getBlob(0)}
            val a=w.actions();val preview=a.preview()
            if(choice=="merge")assertTrue(preview.reason,preview.canMerge)
            a.choose(preview,choice)
            assertTrue(w.projection.state().body.isNull("pendingChoice"))
            val after=w.sql.query("SELECT body FROM syncv2_wire_candidates WHERE scope=? AND entity_key=?",arrayOf(w.projection.scope,entity)).use{it.moveToFirst();it.getBlob(0)}
            assertArrayEquals("$choice preserves the local sealed cache",cached,after)
            assertEquals("$choice keeps the isolated row under its original stateOid",isolatedState,w.stateOid(w.current.getString("commitOid"),"note"))
            assertEquals("the local Room note is never overwritten by an isolated row","base\nline\n",w.textOf("note"))
        }
    }

    @Test fun rollbackToATargetWithAnIsolatedNoteCarriesItVerbatim()=SyncV2P5Fixture().use{w->
        w.start()
        w.stateHook=isolate
        val t=w.commit(listOf(w.note("cloud title","cloud\nline\n"),w.other()),w.current.getString("commitOid"),"0","normal",null)
        w.stateHook=null;w.target=t
        val c2=w.commit(listOf(w.note("new title","base\nline\n")),t,"0","normal",null);w.setCurrent(c2,"3")
        w.actions().refresh();w.drain()
        val a=w.actions();a.rollback(a.captureRollback(),t)
        val head=w.current.getString("commitOid")
        assertEquals(w.headers.getValue(t).getJSONObject("body").getString("treeRoot"),w.headers.getValue(head).getJSONObject("body").getString("treeRoot"))
        assertEquals(w.stateOid(t,"note"),w.stateOid(head,"note"))
        assertEquals("other\n",w.textOf("note2"))
        assertEquals("base\nline\n",w.text())
    }
    @Test fun forkIsVisibleInRoomAndStaysPendingUntilPublishedOrDeleted()=SyncV2P5Fixture().use{w->
        w.start();w.publishIsolated();w.actions().refresh();w.drain()
        val fork=w.actions().forkNote("note").getString("forkLogicalId")
        assertEquals("base\nline\n",w.textOf(fork))
        assertEquals("base\nline\n",w.text())
        val title=w.sql.query("SELECT title FROM notes WHERE noteId=?",arrayOf(fork)).use{it.moveToFirst();it.getString(0)}
        assertTrue(title.contains("副本"))
        assertEquals(fork,w.projection.noteFork("note"))
        assertTrue(w.sql.query("SELECT COUNT(*) FROM syncv2_outbox WHERE logical_id=?",arrayOf(fork)).use{it.moveToFirst();it.getInt(0)}>0)
        w.drain()
        assertEquals(fork,w.projection.noteFork("note"))
        w.sql.execSQL("UPDATE notes SET deleted=1,deletedAt=10 WHERE noteId=?",arrayOf(fork));w.drain()
        assertNull(w.projection.noteFork("note"))
    }
    @Test fun automaticForkWritesTheEditedRoomBodyToTheVisibleCopy()=SyncV2P5Fixture().use{w->
        w.start();w.publishIsolated();w.actions().refresh();w.drain()
        w.edit("edited\n")
        val fork=w.projection.noteFork("note")!!
        assertEquals("edited\n",w.textOf(fork))
        assertTrue(w.sql.query("SELECT COUNT(*) FROM syncv2_outbox WHERE logical_id=?",arrayOf(fork)).use{it.moveToFirst();it.getInt(0)}>0)
    }
    @Test fun absentEntityClearsItsIsolationWithoutInvalidatingItsLifetimeBinding()=SyncV2P5Fixture().use{w->
        w.start();w.publishIsolated();w.actions().refresh();w.drain()
        val entity=w.identity.entity("note","note")
        SyncV2NoteWiring.putBinding(w.sql,w.projection.scope,entity,"ab".repeat(16),false,false)
        val empty=w.commit(emptyList(),w.current.getString("commitOid"),"0","normal",null);w.setCurrent(empty,"4")
        w.actions().refresh();w.drain()
        assertFalse(w.projection.noteIsolation().has(entity))
        assertEquals("ab".repeat(16),SyncV2NoteWiring.binding(w.sql,w.projection.scope,entity)!!.getString("noteRevision"))
        assertFalse(SyncV2NoteWiring.binding(w.sql,w.projection.scope,entity)!!.getBoolean("invalidated"))
    }
    @Test fun missingIsolatedRefsDoNotPreventInstallingAndApplyingTheSibling()=SyncV2P5Fixture().use{w->
        w.start();val oid=w.publishIsolated();val noteOid=w.stateOid(oid,"note")
        // The product gate is closed. The reference lookup fails for only the isolated row.
        val ref=w.refs.getValue(noteOid);w.f.objects.remove(ref.getString("refsOid"))
        w.actions().refresh();w.drain()
        assertEquals("other\n",w.textOf("note2"));assertEquals("base\nline\n",w.text())
        assertEquals(oid,w.projection.state().body.getJSONObject("appliedAccount").getString("oid"))
    }

    @Test fun rollbackRequiresBothTheLocalGateAndTheServerAnnouncement() {
        for(local in listOf(false,true))for(server in listOf(false,true))SyncV2P5Fixture().use{w->
            w.noteSegmentsAnnounced=server;w.start()
            val client=w.f.client().also{it.noteSegmentsLocal=true}
            val a=SyncV2AccountActions(client,w.identity,w.projection,w.f.signer,{w.key},{it=="synthetic-phone"},w.root,true,{w.f.now},noteSegmentsRead=local)
            // Ordinary note wire plus a row feature exercises the gate before any join.
            w.stateHook=isolate
            val t=w.commit(listOf(w.note("segmented marker")),w.current.getString("commitOid"),"0","normal",null)
            w.stateHook=null;w.target=t
            val head=w.commit(listOf(w.note("current")),t,"0","normal",null);w.setCurrent(head,"3")
            a.refresh();w.drain()
            val failure=runCatching{a.rollback(a.captureRollback(),t)}.exceptionOrNull()
            // With both gates open, the deliberately malformed segmented parent reaches schema
            // validation. Otherwise it is carried as an opaque isolated wire row.
            if(local&&server)assertEquals("invalid_schema",failure?.message)else {
                assertNull(failure)
                assertEquals(w.stateOid(t,"note"),w.stateOid(w.current.getString("commitOid"),"note"))
            }
        }
    }

    @Test fun confirmedForkPublicationClearsOnlyTheForkTrackingEntry()=SyncV2P5Fixture().use{w->
        w.start();val isolated=w.publishIsolated();w.actions().refresh();w.drain()
        val fork=w.actions().forkNote("note").getString("forkLogicalId");w.drain()
        w.target=isolated;w.rollbackRemote();w.actions().refresh();w.drain()
        val a=w.actions();a.choose(a.preview(),"local")
        assertEquals("base\nline\n",w.textOf(fork));assertEquals("base\nline\n",w.text())
        assertNull(w.projection.noteFork("note"))
        assertEquals(0L,w.sql.query("SELECT COUNT(*) FROM syncv2_outbox WHERE logical_id=?",arrayOf(fork)).use{it.moveToFirst();it.getLong(0)})
        assertTrue(w.treeRows(w.current.getString("commitOid")).any{it.getString("entityKey")==w.identity.entity("note",fork)})
    }

    @Test fun physicalJournalQuotaFailurePreservesTheLocalNoteAndDoesNotPublish()=SyncV2P5Fixture().use{w->
        w.pending();w.edit("a".repeat(50000))
        val a=w.actions();val preview=a.preview();val text=w.text();val calls=w.publishCalls
        val pages=w.sql.query("PRAGMA page_count").use{it.moveToFirst();it.getLong(0)}
        // PRAGMA is connection-local. Bind it to Android's primary writer, rather
        // than the pool's read-only query connection.
        w.sql.beginTransaction()
        try{w.sql.query("PRAGMA max_page_count=$pages").use{it.moveToFirst();assertEquals(pages,it.getLong(0))};w.sql.setTransactionSuccessful()}finally{w.sql.endTransaction()}
        val failure=runCatching{a.choose(preview,"local")}.exceptionOrNull()
        assertEquals("storage_pressure",failure?.message)
        assertEquals(text,w.text());assertEquals(calls,w.publishCalls)
        assertFalse(w.projection.state().body.isNull("pendingChoice"))
        assertTrue(w.projection.state().body.isNull("pendingAccountAction"))
    }

    @Test fun unchangedSourceDoesNotResurrectForkTrackingAfterPublication()=SyncV2P5Fixture().use{w->
        w.start();val isolated=w.publishIsolated();w.actions().refresh();w.drain()
        w.edit("edited\n");val fork=w.projection.noteFork("note")!!
        w.target=isolated;w.rollbackRemote();w.actions().refresh();w.drain()
        val a=w.actions();a.choose(a.preview(),"local");assertNull(w.projection.noteFork("note"))
        w.sql.execSQL("UPDATE notes SET updatedAt=updatedAt+1 WHERE noteId='note2'");w.drain()
        assertNull(w.projection.noteFork("note"))
        assertEquals("edited\n",w.textOf(fork))
        assertEquals(1L,w.sql.query("SELECT COUNT(*) FROM notes WHERE noteId LIKE 'syncv2-fork-%'").use{it.moveToFirst();it.getLong(0)})
    }

    @Test fun rollbackAwayFromIsolationRestoresTheOrdinaryNoteWithoutForking()=SyncV2P5Fixture().use{w->
        w.start();w.publishIsolated();w.actions().refresh();w.drain()
        val a=w.actions();a.rollback(a.captureRollback(),w.initial);w.drain()
        assertEquals("base\nline\n",w.text());assertEquals(0,w.projection.noteIsolation().length())
        assertNull(w.projection.noteFork("note"))
        assertEquals(0L,w.sql.query("SELECT COUNT(*) FROM notes WHERE noteId LIKE 'syncv2-fork-%'").use{it.moveToFirst();it.getLong(0)})
    }

    @Test fun overBudgetLocalChoiceNeverDropsTheVisibleNoteOrClearsItsQueue()=SyncV2P5Fixture().use{w->
        w.noteSegmentsAnnounced=true;w.pending();w.edit("a".repeat(1_048_560))
        val client=w.f.client().also{it.noteSegmentsLocal=true}
        val a=SyncV2AccountActions(client,w.identity,w.projection,w.f.signer,{w.key},{it=="synthetic-phone"},w.root,true,{w.f.now},noteSegmentsRead=true)
        val text=w.text();val publishes=w.publishCalls;val acks=w.ackCalls
        val queued=w.sql.query("SELECT COUNT(*) FROM syncv2_outbox").use{it.moveToFirst();it.getLong(0)}
        val preview=a.preview();assertFalse(preview.canMerge);assertEquals("storage_pressure",preview.reason)
        assertEquals("storage_pressure",runCatching{a.choose(preview,"local")}.exceptionOrNull()?.message)
        assertEquals(text,w.text());assertEquals(publishes,w.publishCalls);assertEquals(acks,w.ackCalls)
        assertEquals(queued,w.sql.query("SELECT COUNT(*) FROM syncv2_outbox").use{it.moveToFirst();it.getLong(0)})
        assertFalse(w.projection.state().body.isNull("pendingChoice"))
    }

    @Test fun reopeningAnUnchangedWireSourceRestoresRoomAndKeepsThePublishedFork()=SyncV2P5Fixture().use{w->
        w.start();val isolated=w.publishIsolated();w.actions().refresh();w.drain();w.edit("edited\n")
        val fork=w.projection.noteFork("note")!!;w.target=isolated;w.rollbackRemote();w.actions().refresh();w.drain()
        val a=w.actions();a.choose(a.preview(),"local")
        val values=w.projection.accountLocalRows().map{JSONObject(it.getJSONObject("value").toString())}
        val normal=w.commit(values,w.current.getString("commitOid"),w.current.getString("rollbackGeneration"),"normal",null)
        w.setCurrent(normal,SyncV2Codec.next(w.current.getString("revision")));a.refresh();w.drain()
        assertEquals("base\nline\n",w.text());assertEquals("edited\n",w.textOf(fork))
        assertEquals(0,w.projection.noteIsolation().length())
    }
    @Test fun confirmedLocalPrefixWithAnIsolatedRowRebasesOnlyTheOrdinaryTail()=SyncV2P5Fixture().use{w->
        val isolated=pendingWithIsolatedTarget(w)
        val a=w.actions{if(it=="server-confirmed")throw java.io.IOException("prefix lost")}
        assertEquals("prefix lost",runCatching{a.choose(a.preview(),"local")}.exceptionOrNull()?.message)
        w.sql.execSQL("UPDATE notes SET markdown='tail\n',plainText='tail\n',updatedAt=updatedAt+1 WHERE noteId='note2'");w.drain()
        val result=w.actions().reconcileStaleAction()!!
        assertTrue(result.getBoolean("localCommitted"));assertTrue(result.getBoolean("tailPreserved"))
        assertEquals("base\nline\n",w.text());assertEquals("tail\n",w.textOf("note2"))
        assertEquals(isolated,w.stateOid(w.current.getString("commitOid"),"note"))
        assertFalse(w.projection.state().body.isNull("pendingChoice"))
    }

}
