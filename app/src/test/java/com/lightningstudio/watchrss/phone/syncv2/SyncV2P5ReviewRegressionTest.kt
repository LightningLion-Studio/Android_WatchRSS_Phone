package com.lightningstudio.watchrss.phone.syncv2

import androidx.sqlite.db.SupportSQLiteDatabase
import android.database.Cursor
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
class SyncV2P5ReviewRegressionTest {
    private fun value(kind:String):JSONObject {
        val a=javaClass.classLoader!!.getResourceAsStream("syncv2-business-schema.json")!!.bufferedReader().use{JSONObject(it.readText())}.getJSONArray("records")
        return (0 until a.length()).map{a.getJSONObject(it)}.single{it.getString("kind")==kind}.getJSONObject("value")
    }
    private fun record(w:SyncV2P5Fixture,kind:String,v:JSONObject):JSONObject {
        val id=SyncV2Business.logicalId(kind,v);val p=SyncV2Business.split(kind,id,w.identity.entity(kind,id),v,null)
        return JSONObject().put("meta",p.meta).put("state",p.state).put("value",v)
    }
    private fun code(f:()->Unit):String?=runCatching(f).exceptionOrNull()?.let{if(it is SyncV2CloudError)it.code else it.message}
    @Test fun unchangedRowsReuseAuthenticatedCiphertextOnSingleNoteEdit():Unit=SyncV2P5Fixture().use{w->
        val folders=(0 until 32).map{i->value("folder").put("folderId","folder-$i")}
        val old=w.commit(listOf(w.note("old title"))+folders,w.initial,"0","normal",null)
        val latest=w.commit(listOf(w.note("new title"))+folders,old,"0","normal",null);w.setCurrent(latest,"4")
        w.sql.execSQL("CREATE TABLE review_folder_updates(id TEXT)")
        w.sql.execSQL("CREATE TRIGGER review_folder_update AFTER UPDATE ON note_folders BEGIN INSERT INTO review_folder_updates VALUES(new.folderId); END")
        val a=w.actions();w.start();w.edit()
        val rolled=w.commit(emptyList(),latest,"1","rollback",old);w.setCurrent(rolled,"5");a.refresh();w.drain()
        val original=w.projection.cloudRows().filter{it.getJSONObject("meta").getString("kind")=="folder"}.associateBy{it.getJSONObject("meta").getString("entityKey")}
        val putBefore=w.f.putOids.size;w.sql.execSQL("DELETE FROM review_folder_updates")
        a.choose(a.preview(),"local")
        val after=w.projection.cloudRows().filter{it.getJSONObject("meta").getString("kind")=="folder"}
        val meta=after.count{it.getJSONObject("row").getString("metaOid")!=original.getValue(it.getJSONObject("meta").getString("entityKey")).getJSONObject("row").getString("metaOid")}
        val state=after.count{it.getJSONObject("row").getString("stateOid")!=original.getValue(it.getJSONObject("meta").getString("entityKey")).getJSONObject("row").getString("stateOid")}
        val puts=w.f.putOids.drop(putBefore);val bytes=puts.distinct().sumOf{w.f.objects.getValue(it).size.toLong()}
        val updates=w.sql.query("SELECT COUNT(*) FROM review_folder_updates").use{it.moveToFirst();it.getInt(0)}
        println("P5R_RESEAL folders=32 changedMeta=$meta changedState=$state uploadedObjects=${puts.distinct().size} uploadedBytes=$bytes unchangedFolderUpdates=$updates")
        assertEquals(0,meta);assertEquals(0,state);assertEquals(0,updates);assertTrue(puts.distinct().size<=6);assertTrue(bytes<40000)
    }
    @Test fun normalizedLegacyCloudChoicePersistsRecoveryAndKeepsOldAppliedCursor():Unit=SyncV2P5Fixture().use{w->
        w.pending();val local=w.projection.accountLocalRows().single();val entity=local.getJSONObject("meta").getString("entityKey")
        w.projection.receiveLegacy(SyncV2Projection.LegacyInput("synthetic-peer",14,"cursor-1",JSONObject().put(entity,local.getJSONObject("value"))),null,true)
        val a=w.actions();val pending=a.pendingChoice()!!;assertNotNull(pending.preview)
        a.choose(pending.preview,"cloud")
        assertEquals(1,w.choiceCalls);assertTrue(w.projection.state().body.getJSONObject("applied").length()==0)
        assertEquals("discarded-cloud",w.sql.query("SELECT route FROM syncv2_legacy_inbox").use{it.moveToFirst();it.getString(0)})
        assertEquals(0,w.sql.query("SELECT applied FROM syncv2_legacy_inbox").use{it.moveToFirst();it.getInt(0)})
        val copy=a.recoveryCopies().getJSONObject(0).getString("copyId");w.reopen()
        assertEquals(1,w.actions().recoveryDelta(copy).getJSONArray("changes").length())
        assertTrue(w.projection.state().body.getJSONObject("applied").length()==0)
    }
    @Test fun missingRssStateResetsPublicInteractionAndKeepsArticleIdentity():Unit=SyncV2P5Fixture().use{w->
        val article=value("article").put("rssSourceUrl","https://example.invalid/public").put("favoriteSaved",false)
        val state=value("rss-state").put("sourceId",com.lightningstudio.watchrss.phone.cloud.CloudRssSourceIdentity.sourceKey(article.getString("rssSourceUrl"))).put("articleIdHash",SyncV2Codec.hex(SyncV2Codec.sha(SyncV2Codec.utf8(article.getString("articleId"))))).put("favoriteSaved",true)
        val ar=record(w,"article",article);val rss=record(w,"rss-state",state);val apply=SyncV2PhoneApply(w.sql)
        w.sql.beginTransaction();try{apply.apply(emptyList(),listOf(ar,rss));w.sql.setTransactionSuccessful()}finally{w.sql.endTransaction()}
        w.sql.beginTransaction();try{apply.apply(listOf(rss),emptyList());w.sql.setTransactionSuccessful()}finally{w.sql.endTransaction()}
        val favorite=w.sql.query("SELECT favoriteSaved FROM phone_articles WHERE articleId=?",arrayOf(article.getString("articleId"))).use{it.moveToFirst();it.getInt(0)}
        println("P5R_RSS targetStates=0 favoriteAfterApply=$favorite");assertEquals(0,favorite);assertEquals(1,w.sql.query("SELECT COUNT(*) FROM phone_articles").use{it.moveToFirst();it.getInt(0)})
    }
    @Test fun recoverySlicesUseOneAuthenticatedSessionAndBoundedBlobReads():Unit=SyncV2P5Fixture().use{w->
        w.pending();val text="z".repeat(1024*1024)
        val article=PhoneArticleEntity("large","synthetic","https://example.invalid/large","title","site","summary",null,text,null,"body-hash",0,0,true,0,0,null,null,true,0,0,false,0,0,false,0)
        runBlocking{w.room.phoneArticleDao().upsert(article)};w.drain();val a=w.actions();a.choose(a.preview(),"cloud")
        val copy=a.recoveryCopies().getJSONObject(0).getString("copyId");val recipe=SyncV2Wire.strings(a.recoveryDelta(copy).getJSONArray("bodyRefs"),100000,false).single{it.startsWith("recipe:")}
        var reads=0;val original=w.sql
        val counted=object:SupportSQLiteDatabase by original {
            override fun query(query:String,bindArgs:Array<out Any?>):Cursor {if(query.startsWith("SELECT body FROM syncv2_recovery_objects"))reads++;return original.query(query,bindArgs)}
        }
        val p=SyncV2Projection(counted,w.identity,SyncV2Fixtures.localKey,true);val storage=SyncV2RecoveryStorage(p,w.identity,w.root)
        val calls=17;var returned=0
        repeat(calls){i->returned+=storage.readPart(copy,recipe,i*65536L,65536).size}
        println("P5R_RECOVERY calls=$calls objectReads=$reads returnedBytes=$returned")
        assertEquals(1024*1024+37,returned);assertTrue("bounded recovery reads: $reads",reads<=calls+7)
    }
    @Test fun injectedCrashAfterBusinessApplyKeepsOldRowsAndRecoveryUncommitted():Unit=SyncV2P5Fixture().use{w->
        w.pending();val old=w.text();val a=w.actions(fault={if(it=="business-applied")throw java.io.IOException("independent crash")})
        assertEquals("independent crash",code{a.choose(a.preview(),"cloud")});w.reopen()
        assertEquals(old,w.text());assertFalse(w.projection.state().body.isNull("pendingChoice"));assertEquals(0,w.actions().recoveryCopies().length())
        w.actions().resume();assertEquals(1,w.actions().recoveryCopies().length());println("P5R_CRASH oldRowsAndPendingPreserved=true resumed=true")
    }
    @Test fun cleanSameGenerationFollowPreservesResolvedConflictMarker():Unit=SyncV2P5Fixture().use{w->
        val n=w.note();val conflict=JSONObject().put("conflictId","frozen-id").put("noteId","note").put("baseMarkdown","base").put("localMarkdown","local").put("remoteMarkdown","remote").put("remoteDeviceId","synthetic-peer").put("createdAt",0).put("resolvedAt",10)
        n.getJSONObject("noteConflicts").put("records",JSONArray().put(conflict))
        val withMarker=w.commit(listOf(n),w.initial,"0","normal",null);w.setCurrent(withMarker,"3");w.start()
        assertEquals(1,w.sql.query("SELECT COUNT(*) FROM note_conflicts").use{it.moveToFirst();it.getInt(0)})
        val a=w.actions();assertTrue(w.projection.state().body.isNull("pendingChoice"))
        val without=w.commit(listOf(w.note("next title")),withMarker,"0","normal",null);w.setCurrent(without,"4");a.refresh();w.drain()
        val count=w.sql.query("SELECT COUNT(*) FROM note_conflicts").use{it.moveToFirst();it.getInt(0)}
        println("P5R_CONFLICT sameGeneration=true explicitChoiceCalls=${w.choiceCalls} markersAfterFollow=$count")
        assertEquals(0,w.choiceCalls);assertEquals(1,count);assertEquals(10,w.sql.query("SELECT resolvedAt FROM note_conflicts").use{it.moveToFirst();it.getLong(0)})
    }
    @Test fun ordinaryConflictReplayKeepsResolutionAndRejectsImmutableTupleChanges():Unit=SyncV2P5Fixture().use{w->
        val conflict=JSONObject().put("conflictId","stable").put("noteId","note").put("baseMarkdown","base").put("localMarkdown","local").put("remoteMarkdown","remote").put("remoteDeviceId","peer").put("createdAt",1).put("resolvedAt",10)
        fun note(c:JSONObject)=w.note().also{it.getJSONObject("noteConflicts").put("records",JSONArray().put(c))}
        val first=w.commit(listOf(note(conflict)),w.initial,"0","normal",null);w.setCurrent(first,"3");w.start()
        val replay=w.commit(listOf(note(JSONObject(conflict.toString()).put("resolvedAt",0))),first,"0","normal",null);w.setCurrent(replay,"4");w.actions().refresh();w.drain();w.reopen()
        val known=w.projection.accountLocalRows().single().getJSONObject("value").getJSONObject("noteConflicts").getJSONArray("records").getJSONObject(0)
        assertEquals(10,known.getLong("resolvedAt"))
        val bad=w.commit(listOf(note(JSONObject(conflict.toString()).put("remoteMarkdown","forged"))),replay,"0","normal",null);w.setCurrent(bad,"5")
        assertEquals("conflict_identity_mismatch",code{w.actions().refresh()})
        assertEquals("remote",w.sql.query("SELECT remoteMarkdown FROM note_conflicts").use{it.moveToFirst();it.getString(0)})
        assertEquals(replay,w.projection.state().body.getJSONObject("appliedAccount").getString("oid"))
    }
    @Test fun explicitRollbackStillReplacesConflictFactsFromTheOldGeneration():Unit=SyncV2P5Fixture().use{w->
        val n=w.note();n.getJSONObject("noteConflicts").put("records",JSONArray().put(JSONObject().put("conflictId","stable").put("noteId","note").put("baseMarkdown","base").put("localMarkdown","local").put("remoteMarkdown","remote").put("remoteDeviceId","peer").put("createdAt",1).put("resolvedAt",10)))
        val first=w.commit(listOf(n),w.initial,"0","normal",null);w.setCurrent(first,"3");w.start();w.rollbackRemote();w.actions().refresh();w.drain()
        assertEquals(0,w.sql.query("SELECT COUNT(*) FROM note_conflicts").use{it.moveToFirst();it.getInt(0)})
        assertEquals(0,w.projection.accountLocalRows().single().getJSONObject("value").getJSONObject("noteConflicts").getJSONArray("records").length())
    }
    @Test fun removedPublicStateClearsReadSavedAndDeletionRegistersWithoutDroppingBody():Unit=SyncV2P5Fixture().use{w->
        val article=value("article").put("rssSourceUrl","https://example.invalid/public")
        val state=value("rss-state").put("sourceId",com.lightningstudio.watchrss.phone.cloud.CloudRssSourceIdentity.sourceKey(article.getString("rssSourceUrl"))).put("articleIdHash",SyncV2Codec.hex(SyncV2Codec.sha(SyncV2Codec.utf8(article.getString("articleId")))))
            .put("isRead",true).put("readingProgress",0.75).put("readingPositionBytes",25).put("readingPositionContentHash","old").put("readingPositionChangedAt",15)
            .put("favoriteSaved",true).put("favoriteChangedAt",15).put("favoriteSortOrder",3).put("watchLaterSaved",true).put("watchLaterChangedAt",15).put("watchLaterSortOrder",4).put("deleted",true).put("deletedAt",20)
        val ar=record(w,"article",article);val rss=record(w,"rss-state",state);val apply=SyncV2PhoneApply(w.sql)
        w.sql.beginTransaction();try{apply.apply(emptyList(),listOf(ar,rss));w.sql.execSQL("UPDATE phone_articles SET contentText='kept body'");apply.apply(listOf(rss),emptyList());w.sql.setTransactionSuccessful()}finally{w.sql.endTransaction()}
        w.sql.query("SELECT articleId,contentText,isRead,readingProgress,readingPositionBytes,readingPositionContentHash,favoriteSaved,watchLaterSaved,deleted,deletedAt FROM phone_articles").use{c->assertTrue(c.moveToFirst());assertEquals(article.getString("articleId"),c.getString(0));assertEquals("kept body",c.getString(1));for(i in listOf(2,4,6,7,8,9))assertEquals(0,c.getLong(i));assertEquals(0.0,c.getDouble(3),0.0);assertEquals("",c.getString(5))}
    }
    @Test fun ordinaryConflictUnionCanBePublishedNormallyWithoutReplacingRegisteredRefs():Unit=SyncV2P5Fixture().use{w->
        val n=w.note();n.getJSONObject("noteConflicts").put("records",JSONArray().put(JSONObject().put("conflictId","stable").put("noteId","note").put("baseMarkdown","base").put("localMarkdown","local").put("remoteMarkdown","remote").put("remoteDeviceId","peer").put("createdAt",1).put("resolvedAt",10)))
        val first=w.commit(listOf(n),w.initial,"0","normal",null);w.setCurrent(first,"3");w.start()
        val next=w.commit(listOf(w.note("next title")),first,"0","normal",null);w.setCurrent(next,"4");val a=w.actions();a.refresh();w.drain()
        val sync=SyncV2CloudSync(w.f.client(),w.identity,w.projection,{it=="synthetic-phone"},{w.key},now={w.f.now})
        sync.publishIndex(w.f.signer,w.key,1);a.refresh();w.drain()
        assertEquals("normal",w.headers.getValue(w.current.getString("commitOid")).getJSONObject("body").getJSONObject("operation").getString("kind"))
        val known=w.projection.cloudRows().single().getJSONObject("state").getJSONObject("payload").getJSONObject("noteConflicts").getJSONArray("records")
        assertEquals(1,known.length());assertEquals(10,known.getJSONObject(0).getLong("resolvedAt"));assertEquals(0,w.choiceCalls)
    }
    @Test fun removedRssStateRefreshesSharedArticleFootprintForTheNextLocalEdit():Unit=SyncV2P5Fixture().use{w->
        val article=value("article").put("rssSourceUrl","https://example.invalid/public").put("favoriteSaved",false)
        val state=value("rss-state").put("sourceId",com.lightningstudio.watchrss.phone.cloud.CloudRssSourceIdentity.sourceKey(article.getString("rssSourceUrl"))).put("articleIdHash",SyncV2Codec.hex(SyncV2Codec.sha(SyncV2Codec.utf8(article.getString("articleId"))))).put("favoriteSaved",true)
        val ar=record(w,"article",article);val rss=record(w,"rss-state",state);val apply=SyncV2PhoneApply(w.sql)
        w.projection.observeVerified(SyncV2Fixtures.window(),SyncV2Fixtures.observationHeaders())
        var st=w.projection.state();w.projection.accountWriter(st.version){current->val lookup=apply.apply(emptyList(),listOf(ar,rss));w.projection.accountApplied(current,current.body.getJSONObject("account"),listOf(ar,rss),"first",emptyList(),lookup)}
        st=w.projection.state();w.projection.accountWriter(st.version){current->val lookup=apply.apply(listOf(ar,rss),listOf(ar));w.projection.accountApplied(current,current.body.getJSONObject("account"),listOf(ar),"removed",listOf(ar,rss),lookup)}
        assertEquals(0,w.sql.query("SELECT favoriteSaved FROM phone_articles").use{it.moveToFirst();it.getInt(0)})
        w.sql.execSQL("UPDATE phone_articles SET favoriteSaved=1");w.drain()
        val candidate=w.projection.candidate("article",article.getString("articleId"))!!
        assertTrue(SyncV2Business.join("article",candidate.first,candidate.second).getBoolean("favoriteSaved"))
    }
    @Test fun accountApplySharesOnePublicArticleLookupAcrossAllRssEntities():Unit=SyncV2P5Fixture().use{w->
        w.start();val a=value("article").put("rssSourceUrl","https://example.invalid/public")
        w.sql.beginTransaction();try{SyncV2PhoneApply(w.sql).apply(emptyList(),listOf(record(w,"article",a)));w.sql.setTransactionSuccessful()}finally{w.sql.endTransaction()};w.drain()
        val records=(0 until 32).map{i->record(w,"rss-state",value("rss-state").put("articleIdHash",i.toString(16).padStart(64,'0')))}
        var scans=0;val sql=w.sql
        val counted=object:SupportSQLiteDatabase by sql {
            override fun query(query:String):Cursor {if(query.contains("FROM phone_articles WHERE rssSourceUrl IS NOT NULL"))scans++;return sql.query(query)}
        }
        val p=SyncV2Projection(counted,w.identity,SyncV2Fixtures.localKey,true);val st=p.state()
        p.accountWriter(st.version){current->p.accountApplied(current,current.body.getJSONObject("account"),records,"scan-counter")}
        println("P5R_RSS_SCANS rssEntities=32 fullPublicArticleScans=$scans");assertEquals(1,scans)
    }

    @Test fun successfulMergeReconciliationKeepsTailAndAppliesUntouchedMergedField():Unit=SyncV2P5Fixture().use{w->
        w.pending();val a=w.actions();val p=a.preview();assertTrue(p.canMerge)
        w.onConfirm={w.sql.execSQL("UPDATE notes SET pinned=1 WHERE noteId='note'")}
        assertEquals("choice_stale",code{a.choose(p,"merge")});w.onConfirm=null
        var result=a.reconcileStaleAction()!!
        repeat(4){if(result.optBoolean("projectionPaused"))result=a.reconcileStaleAction()!!}
        assertFalse(result.toString(),result.optBoolean("projectionPaused"));assertFalse(result.toString(),result.optBoolean("tailReconciliationPending"))
        assertEquals("succeeded",result.getString("status"));a.refresh();w.drain()
        val cloudTitle=w.projection.cloudRows().single().getJSONObject("meta").getJSONObject("payload").getString("title")
        val localTitle=w.sql.query("SELECT title FROM notes WHERE noteId='note'").use{it.moveToFirst();it.getString(0)}
        a.choose(a.preview(),"local")
        val republishedTitle=w.projection.cloudRows().single().getJSONObject("meta").getJSONObject("payload").getString("title")
        println("P5R_MERGE_TAIL successfulMergeTitle=$cloudTitle reconciledLocalTitle=$localTitle tailField=pinned republishedTitle=$republishedTitle")
        assertEquals("old title",cloudTitle);assertEquals("old title",localTitle);assertEquals("old title",republishedTitle);assertEquals(1,w.sql.query("SELECT pinned FROM notes").use{it.moveToFirst();it.getInt(0)})
    }

    @Test fun warmedRecoverySessionStillAuthenticatesReturnedBlocksAndExpiresAfterRestart():Unit=SyncV2P5Fixture().use{w->
        w.pending();val article=PhoneArticleEntity("session-body","synthetic","https://example.invalid/session","title","site","summary",null,"necessary body",null,"body-hash",0,0,true,0,0,null,null,true,0,0,false,0,0,false,0)
        runBlocking{w.room.phoneArticleDao().upsert(article)};w.drain();var a=w.actions();a.choose(a.preview(),"cloud")
        val header=a.recoveryCopies().getJSONObject(0);val id=header.getString("copyId");val refs=SyncV2Wire.strings(a.recoveryDelta(id).getJSONArray("bodyRefs"),100000,false)
        val recipe=refs.single{it.startsWith("recipe:")};val blob=refs.single{it.startsWith("blob:")};val expected=a.recoveryBodyPart(id,recipe,0,65536)
        val original=w.sql.query("SELECT body FROM syncv2_recovery_objects WHERE oid=?",arrayOf(blob)).use{it.moveToFirst();it.getBlob(0)}
        w.sql.execSQL("UPDATE syncv2_recovery_objects SET body=? WHERE oid=?",arrayOf(byteArrayOf(1,2,3),blob))
        assertNotNull(code{a.recoveryBodyPart(id,recipe,0,65536)})
        w.sql.execSQL("UPDATE syncv2_recovery_objects SET body=? WHERE oid=?",arrayOf(original,blob))
        w.reopen();a=w.actions();assertArrayEquals(expected,a.recoveryBodyPart(id,recipe,0,65536))
        w.f.now=header.getLong("expiresAtMillis");assertEquals("snapshot_expired",code{a.recoveryBodyPart(id,recipe,0,65536)})
    }

    @Test fun ordinaryMissingNoteCannotDeleteItsConflictTombstone():Unit=SyncV2P5Fixture().use{w->
        val n=w.note();n.getJSONObject("noteConflicts").put("records",JSONArray().put(JSONObject().put("conflictId","retained").put("noteId","note").put("baseMarkdown","base").put("localMarkdown","local").put("remoteMarkdown","remote").put("remoteDeviceId","peer").put("createdAt",1).put("resolvedAt",10)))
        val first=w.commit(listOf(n),w.initial,"0","normal",null);w.setCurrent(first,"3");w.start()
        val missing=w.commit(emptyList(),first,"0","normal",null);w.setCurrent(missing,"4")
        assertEquals("closure_incomplete",code{w.actions().refresh()})
        assertEquals(1,w.sql.query("SELECT COUNT(*) FROM note_conflicts").use{it.moveToFirst();it.getInt(0)})
        assertEquals(first,w.projection.state().body.getJSONObject("appliedAccount").getString("oid"))
    }

    @Test fun incrementalPreviewStillChecksUntouchedNotesOutsideItsEditOperations():Unit=SyncV2P5Fixture().use{w->
        val untouched=w.note().put("noteId","untouched")
        untouched.getJSONObject("noteConflicts").put("records",JSONArray().put(JSONObject().put("conflictId","open").put("noteId","untouched").put("baseMarkdown","base").put("localMarkdown","local").put("remoteMarkdown","remote").put("remoteDeviceId","peer").put("createdAt",1).put("resolvedAt",0)))
        val old=w.commit(listOf(w.note("old title"),untouched),w.initial,"0","normal",null)
        val latest=w.commit(listOf(w.note("new title"),untouched),old,"0","normal",null);w.setCurrent(latest,"4");w.start();w.edit()
        val rolled=w.commit(emptyList(),latest,"1","rollback",old);w.setCurrent(rolled,"5");w.actions().refresh();w.drain()
        val p=w.actions().preview();assertFalse(p.canMerge);assertEquals("note_conflict",p.reason)
        assertEquals(1,w.sql.query("SELECT COUNT(*) FROM note_conflicts WHERE noteId='untouched'").use{it.moveToFirst();it.getInt(0)})
    }

}
