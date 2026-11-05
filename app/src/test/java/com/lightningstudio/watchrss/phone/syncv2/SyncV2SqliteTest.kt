package com.lightningstudio.watchrss.phone.syncv2

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lightningstudio.watchrss.phone.BuildConfig
import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
import com.lightningstudio.watchrss.phone.data.db.PhoneCompanionDatabase
import com.lightningstudio.watchrss.phone.data.db.SyncPeerStateEntity
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SyncV2SqliteTest {
    private val context:Context=RuntimeEnvironment.getApplication()
    private val roots=mutableListOf<File>();private val opened=mutableListOf<PhoneCompanionDatabase>()
    private fun path()=File(context.cacheDir,"syncv2-${System.nanoTime()}").also{it.mkdirs();roots+=it}.resolve("phone.db")
    private fun room(path:File=path(),callback:Boolean=false,trace:MutableList<String>?=null):PhoneCompanionDatabase=Room.databaseBuilder(context,PhoneCompanionDatabase::class.java,path.absolutePath)
        .allowMainThreadQueries().apply{if(trace!=null)setQueryCallback({query,_->trace.add(query)},java.util.concurrent.Executor{it.run()});if(callback)addCallback(PhoneCompanionDatabase.ARTICLE_PROVENANCE_CALLBACK)}.build().also{opened+=it}
    private fun adapter(db:PhoneCompanionDatabase,fault:(String)->Unit={}):SyncV2Projection=SyncV2Projection(db.openHelper.writableDatabase,SyncV2Fixtures.identity(),SyncV2Fixtures.localKey,true,fault)
    private fun scalar(db:SupportSQLiteDatabase,sql:String)=db.query(sql).use{assertTrue(it.moveToFirst());it.getLong(0)}
    private fun article(id:String,title:String="title",body:String="body")=PhoneArticleEntity(id,"old-device","https://example.invalid/$id",title,"site","excerpt",null,body,null,"old-content-hash",13,15,true,16,-1,null,null,true,17,18,true,19,20,false,0,readingProgress=0.25f,isRead=true,readingPositionBytes=123,readingPositionContentHash="ancestor",readingPositionChangedAt=12)
    private fun put(db:PhoneCompanionDatabase,value:PhoneArticleEntity)=runBlocking{db.phoneArticleDao().upsert(value)}
    private fun same(a:Any?,b:Any?)=assertEquals(SyncV2Codec.canonical(a),SyncV2Codec.canonical(b))
    private fun error(action:()->Unit):String?=try{action();null}catch(e:IllegalStateException){e.message}
    private fun rawState(db:SupportSQLiteDatabase,a:SyncV2Projection,fn:(JSONObject)->Unit) {
        val st=a.state();fn(st.body)
        db.execSQL("UPDATE syncv2_state SET body=? WHERE scope=?",arrayOf(SyncV2Codec.sealLocal(SyncV2Fixtures.localKey,a.scope,"state",st.body),a.scope))
    }
    private fun pending(a:SyncV2Projection) {
        a.observeVerified(SyncV2Fixtures.window(),SyncV2Fixtures.observationHeaders())
        a.receiveLegacy(SyncV2Projection.LegacyInput("peer",14,"z",JSONObject("{changed:{title:'offline'}}")),null,true)
    }
    @After fun close(){opened.forEach{if(it.isOpen)it.close()};roots.forEach{it.deleteRecursively()}}

    @Test fun defaultGateDoesNotCreateProjectionTablesOrChangeLegacyData() {
        assertFalse(BuildConfig.SYNC_OBJECTS_V2_LOCAL_PROJECTION)
        val db=room(callback=true);put(db,article("one"));val sql=db.openHelper.writableDatabase
        assertEquals(0,scalar(sql,"SELECT COUNT(*) FROM sqlite_master WHERE name LIKE 'syncv2_%'"))
        val closed=SyncV2Projection(sql,SyncV2Fixtures.identity(),SyncV2Fixtures.localKey,false)
        assertEquals("feature_disabled",error{closed.install()})
        assertEquals(1,scalar(sql,"SELECT COUNT(*) FROM phone_articles"))
        assertEquals("feature_disabled",error{SyncV2ProjectionFactory(context,db).recoverLocalProjection(JSONObject(),JSONObject(),ByteArray(32),"",JSONObject(),"")})
    }
    @Test fun migrationRetainsIdsCountsReadingListsNoteAncestorsCopiesTombstonesAndOldCursor()=runBlocking<Unit> {
        val db=room();val sql=db.openHelper.writableDatabase
        put(db,article("original"));db.syncPeerStateDao().upsert(SyncPeerStateEntity("paired-peer",91,87,100,14,101))
        sql.execSQL("INSERT INTO note_folders VALUES('folder','folder',-1,0,'old',0,0)")
        sql.execSQL("INSERT INTO notes VALUES('note',NULL,'note','local','local','hash','ancestor','base',1,0,10,'old',0,0)")
        sql.execSQL("INSERT INTO note_conflicts VALUES('conflict','note','base','local','remote','peer',1,0)")
        val book=SyncV2Fixtures.vector("book-merge").getJSONArray("cases").getJSONObject(0).getJSONObject("left")
        sql.execSQL("INSERT INTO imported_books VALUES(?,?,'hash',5)",arrayOf(book.getString("bookId"),book.toString()))
        val copy=JSONObject(SyncV2Fixtures.vector("book-merge").getJSONArray("cases").getJSONObject(0).getJSONObject("right").toString()).put("instanceId","ab".repeat(16))
        sql.execSQL("INSERT INTO imported_books VALUES('copy',?,'hash',5)",arrayOf(copy.toString()))
        val countsBefore=businessCounts(sql)
        val before=businessDigest(sql);val a=adapter(db);a.install()
        for(d in SyncV2Schema.domains)while(!a.scanPage(d.table).complete){}
        assertEquals(before,businessDigest(sql));assertEquals(2,scalar(sql,"SELECT COUNT(*) FROM imported_books"))
        val pair=a.candidate("article","original")!!
        val restored=SyncV2Business.join("article",pair.first,pair.second)
        assertTrue(restored.getBoolean("isRead"));assertTrue(restored.getBoolean("favoriteSaved"));assertTrue(restored.getBoolean("watchLaterSaved"))
        assertEquals(123,restored.getLong("readingPositionBytes"));assertEquals("original",restored.getString("articleId"))
        assertEquals("base",a.candidate("note","note")!!.second.getJSONObject("payload").getString("baseMarkdown"))
        assertEquals("remote",a.sourceProvenance("note","note")!!.getJSONArray("conflicts").getJSONObject(0).getString("remoteMarkdown"))
        assertEquals("unknown",pair.first.getString("addedAtSource"))
        assertTrue(a.candidate("book",book.getString("bookId"))!!.second.getJSONObject("payload").getBoolean("deleted"))
        assertEquals("ab".repeat(16),a.candidate("book","copy")!!.first.getJSONObject("payload").getString("instanceId"))
        assertEquals(87,db.syncPeerStateDao().get("paired-peer")!!.lastRemoteSeqApplied)
        SyncV2Fixtures.evidence("domain-preservation.json",JSONObject().put("countsBefore",countsBefore).put("countsAfter",businessCounts(sql)).put("beforeSha256",before).put("afterSha256",businessDigest(sql)).put("oldRemoteCursor",87).put("candidateCount",scalar(sql,"SELECT COUNT(*) FROM syncv2_candidates")))
    }
    private fun businessCounts(db:SupportSQLiteDatabase)=JSONObject().also{out->
        for(table in listOf("phone_articles","phone_rss_sources","phone_saved_items","notes","note_folders","note_assets","note_conflicts","imported_books","imported_book_chapters","imported_book_reading","sync_peer_state","sync_change_log"))out.put(table,scalar(db,"SELECT COUNT(*) FROM `$table`"))
    }
    private fun businessDigest(db:SupportSQLiteDatabase):String {
        val all=JSONArray()
        for(table in listOf("phone_articles","phone_rss_sources","phone_saved_items","notes","note_folders","note_assets","note_conflicts","imported_books","imported_book_chapters","imported_book_reading","sync_peer_state","sync_change_log")) {
            val rows=JSONArray()
            db.query("SELECT * FROM `$table` ORDER BY rowid").use{c->while(c.moveToNext())rows.put(JSONArray().also{r->for(i in 0 until c.columnCount)r.put(if(c.isNull(i))JSONObject.NULL else c.getString(i))})}
            all.put(JSONArray().put(table).put(rows))
        }
        return SyncV2Codec.hash(all)
    }
    @Test fun nativeReopenThreeTimesAndGuestDoesNotRecreateTriggersOrResetScan() {
        val path=path();var db=room(path);put(db,article("old"));var a=adapter(db);a.install();a.scanPage("phone_articles")
        val sql=db.openHelper.writableDatabase;val cookie=scalar(sql,"PRAGMA schema_version");val original=a.state();val checkpoints=scalar(sql,"SELECT COUNT(*) FROM syncv2_scan WHERE complete=1")
        val trace=JSONArray()
        repeat(3) {
            val queries=mutableListOf<String>()
            db.close();db=room(path,trace=queries);a=adapter(db);a.install()
            val reopened=db.openHelper.writableDatabase
            assertEquals(cookie,scalar(reopened,"PRAGMA schema_version"));assertEquals(original.version,a.state().version);assertEquals(original.revision,a.state().revision)
            assertEquals(checkpoints,scalar(reopened,"SELECT COUNT(*) FROM syncv2_scan WHERE complete=1"))
            val ddl=queries.count{q->q.contains("TRIGGER",true) && (q.startsWith("DROP",true) || q.startsWith("CREATE",true))}
            assertEquals(0,ddl)
            trace.put(JSONObject().put("triggerDDL",ddl).put("open",it+1).put("schemaCookie",cookie).put("stateVersion",a.state().version).put("localChangeRevision",a.state().revision).put("completedCheckpoints",checkpoints))
        }
        a.pause();val old=a.stateOrNullForTest();assertNull(old)
        SyncV2Schema.onOpen(db.openHelper.writableDatabase)
        a.install();assertEquals(original.version,a.state().version)
        SyncV2Fixtures.evidence("reopen.json",JSONObject().put("observations",trace).put("ddlOnStableReopen",0).put("guestGateReopenPreserved",true))
    }
    private fun SyncV2Projection.stateOrNullForTest():SyncV2Projection.State?=try{state()}catch(_:IllegalStateException){null}
    @Test fun writesCaptureOutboxAndCountersAtomicallyWithoutReadingArticleBody() {
        val db=room();val a=adapter(db);a.install();val sql=db.openHelper.writableDatabase
        put(db,article("big",body="x".repeat(3*1024*1024)))
        assertEquals("1",a.state().revision);assertEquals(1,scalar(sql,"SELECT COUNT(*) FROM syncv2_outbox"))
        val page=a.scanPage("phone_articles");assertEquals(1,page.rows);assertTrue(page.bytes<4096)
        val v=a.state();val before=scalar(sql,"SELECT length(contentText) FROM phone_articles")
        sql.beginTransaction()
        try{sql.execSQL("UPDATE phone_articles SET title='uncommitted' WHERE articleId='big'")}finally{sql.endTransaction()}
        assertEquals(v.revision,a.state().revision);assertEquals(before,scalar(sql,"SELECT length(contentText) FROM phone_articles"))
        assertEquals("title",sql.query("SELECT title FROM phone_articles").use{it.moveToFirst();it.getString(0)})
        val old=scalar(sql,"SELECT COUNT(*) FROM syncv2_outbox")
        sql.execSQL("UPDATE phone_articles SET title=title WHERE articleId='big'")
        assertEquals(old,scalar(sql,"SELECT COUNT(*) FROM syncv2_outbox"))
    }
    @Test fun scanMappingLogAndCheckpointRollbackAtEveryPrecommitBoundary() {
        val stages=listOf("begin","before-map","after-map","before-log","after-log","before-checkpoint","after-checkpoint","before-state","before-commit","after-transaction")
        val results=JSONArray()
        for(stage in stages) {
            val path=path();var db=room(path);put(db,article("one"));adapter(db).install()
            var armed=true
            val a=adapter(db){if(armed && it==stage){armed=false;error("injected:$stage")}}
            assertEquals("injected:$stage",error{a.scanPage("phone_articles")});db.close();db=room(path)
            val reopened=adapter(db);reopened.install();val sql=db.openHelper.writableDatabase
            val committed=stage=="after-transaction"
            assertEquals(if(committed)1L else 0L,scalar(sql,"SELECT COUNT(*) FROM syncv2_identity_map"))
            assertEquals(if(committed)1L else 0L,scalar(sql,"SELECT COUNT(*) FROM syncv2_change_log"))
            assertEquals(if(committed)1L else 0L,scalar(sql,"SELECT COUNT(*) FROM syncv2_scan WHERE complete=1"))
            reopened.scanPage("phone_articles");assertEquals(1,scalar(sql,"SELECT COUNT(*) FROM syncv2_identity_map"));assertEquals(1,scalar(sql,"SELECT COUNT(*) FROM syncv2_candidates"))
            results.put(JSONObject().put("boundary",stage).put("committedOnReopen",committed).put("identityCountAfterRetry",1))
        }
        SyncV2Fixtures.evidence("fault-reopen.json",JSONObject().put("port","Robolectric native SQLite; exception injection and real database reopen").put("cases",results))
    }
    @Test fun tailSurvivesCrashAndFixedR1RetainsNewWriterChanges() {
        val path=path();var db=room(path);val a=adapter(db);a.install()
        repeat(3){put(db,article("item$it"))};a.scanPage("phone_articles",limit=1)
        var armed=true
        val crash=adapter(db){if(it=="after-tail-entry" && armed){armed=false;error("tail-crash")}}
        assertEquals("tail-crash",error{crash.catchUp(1)});db.close();db=room(path)
        val resumed=adapter(db);resumed.install();assertEquals(1,resumed.catchUp(1))
        put(db,article("late"));assertEquals(2,resumed.catchUp(64));assertFalse(resumed.state().body.has("tailR1Seq"))
        assertEquals(1,resumed.catchUp(64));assertEquals(0,resumed.catchUp(64))
        val sql=db.openHelper.writableDatabase
        assertEquals(4,scalar(sql,"SELECT COUNT(*) FROM syncv2_candidates"));assertEquals(4,scalar(sql,"SELECT COUNT(*) FROM syncv2_identity_map"))
        assertEquals(4,scalar(sql,"SELECT COUNT(*) FROM syncv2_outbox"))
    }
    @Test fun preciseSchemaCapabilityAndScopeBindingRetainsUnrelatedCandidates() {
        val db=room();val a=adapter(db);a.install();put(db,article("one"));a.scanPage("phone_articles");a.scanPage("notes")
        val sql=db.openHelper.writableDatabase
        val original=sql.query("SELECT state FROM syncv2_candidates").use{it.moveToFirst();it.getBlob(0)}
        assertEquals(0,a.scanPage("phone_articles").rows)
        sql.execSQL("CREATE INDEX semantic_test ON notes(title)")
        assertEquals(0,a.scanPage("phone_articles").rows)
        a.scanPage("notes")
        assertEquals(1,a.scanPage("phone_articles",capability="require-auth-v2").rows)
        assertArrayEquals(original,sql.query("SELECT state FROM syncv2_candidates").use{it.moveToFirst();it.getBlob(0)})
        val st=a.state();assertEquals("1",st.revision)
    }
    @Test fun singleEditOnlyRewritesMerklePathAndKeepsOtherEncryptedCandidateBytes() {
        val db=room();val a=adapter(db);a.install();repeat(600){put(db,article("row$it"))}
        while(!a.scanPage("phone_articles").complete){}
        val sql=db.openHelper.writableDatabase
        fun nodes()=sql.query("SELECT prefix,hash FROM syncv2_tree_nodes").use{c->buildMap{while(c.moveToNext())put(c.getString(0),c.getString(1))}}
        val before=nodes();val other=sql.query("SELECT state FROM syncv2_candidates WHERE logical_id='row599'").use{it.moveToFirst();it.getBlob(0)}
        // Catch up already scanned source witnesses first, then change exactly one entity.
        while(a.catchUp()>0){}
        put(db,article("row1",title="one edit"));assertEquals(1,a.catchUp())
        val after=nodes();val changed=after.keys.filter{before[it]!=after[it]}
        val entity=SyncV2Fixtures.identity().entity("article","row1")
        assertTrue(changed.isNotEmpty());assertTrue(changed.all{entity.startsWith(it)});assertTrue(changed.size<=65)
        assertArrayEquals(other,sql.query("SELECT state FROM syncv2_candidates WHERE logical_id='row599'").use{it.moveToFirst();it.getBlob(0)})
        SyncV2Fixtures.evidence("single-edit-tree.json",JSONObject().put("nodesBefore",before.size).put("nodesAfter",after.size).put("rewrittenPrefixes",JSONArray(changed)).put("unrelatedCiphertextPreserved",true))
    }
    @Test fun currentAccountGuardAndCaptureMatchSharedRc6InvalidCurrent() {
        val cases=SyncV2Fixtures.vector("rc6-contracts").getJSONArray("invalidCurrent")
        for(i in 0 until cases.length()) {
            val db=room();val a=adapter(db);a.install();pending(a)
            val c=cases.getJSONObject(i);val path=c.getJSONArray("path")
            if(path.getString(0)=="localChangeRevision") {
                val sql=db.openHelper.writableDatabase
                if(c.optBoolean("remove")) {
                    try{sql.execSQL("UPDATE syncv2_state SET local_revision=NULL");fail("missing revision persisted")}catch(_:android.database.sqlite.SQLiteConstraintException){}
                    // The NOT NULL port rejects absence before the strict capture entry.
                    continue
                }
                sql.execSQL("UPDATE syncv2_state SET local_revision=?",arrayOf(c.get("value")))
            } else rawState(db.openHelper.writableDatabase,a){body->
                var at=body;for(j in 0 until path.length()-1)at=at.getJSONObject(path.getString(j))
                if(c.optBoolean("remove"))at.remove(path.getString(path.length()-1))else at.put(path.getString(path.length()-1),c.get("value"))
            }
            val before=a.state();assertEquals(c.getString("name"),"choice_stale",error{a.captureLegacyChoice(SyncV2Fixtures.window())})
            same(before.body,a.state().body);assertEquals(before.version,a.state().version)
        }
    }
    @Test fun eachCaptureFieldIsCheckedBeforePreparationAndAfterPreparation() {
        for(field in listOf("stateVersion","accountOid","accountRevision","generation","localChangeRevision"))for(final in listOf(false,true)) {
            val db=room();val a=adapter(db);a.install();pending(a);val cap=a.captureLegacyChoice(SyncV2Fixtures.window());val sql=db.openHelper.writableDatabase
            fun change() {
                when(field) {
                    "stateVersion"->sql.execSQL("UPDATE syncv2_state SET state_version='99'")
                    "localChangeRevision"->sql.execSQL("UPDATE syncv2_state SET local_revision='99'")
                    else->rawState(sql,a){b->b.getJSONObject("account").put(when(field){"accountOid"->"oid";"accountRevision"->"revision";else->"generation"},if(field=="accountOid")"commit:sha256:"+"f".repeat(64)else"99")}
                }
            }
            if(!final)change()
            var prepareRan=false
            assertEquals("choice_stale",error{a.validateChoice(cap){prepareRan=true;if(final)change()}})
            assertEquals(final,prepareRan);assertEquals(cap,ChoiceCapture.restore(cap.json()))
            assertEquals(1,scalar(sql,"SELECT COUNT(*) FROM syncv2_legacy_inbox WHERE applied=0"))
        }
    }
    @Test fun originalCaptureSurvivesNewInputReopenAndFreshCaptureNeedsExplicitRequest() {
        val path=path();var db=room(path);var a=adapter(db);a.install();pending(a)
        val cap=a.captureLegacyChoice(SyncV2Fixtures.window());val original=cap.json().toString()
        a.receiveLegacy(SyncV2Projection.LegacyInput("peer",14,"a",JSONObject("{second:{title:'offline'}}")),null,true)
        db.close();db=room(path);a=adapter(db);a.install()
        assertEquals("choice_stale",error{a.validateChoice(cap)});assertEquals(original,cap.json().toString())
        val av=SyncV2Fixtures.vector("adversarial-review").getJSONArray("cases")
        val expected=(0 until av.length()).map(av::getJSONObject).single{it.getString("id")=="A16"}.getJSONObject("expected").getString("returned")
        assertEquals(expected,a.status())
        val fresh=a.captureLegacyChoice(SyncV2Fixtures.window());assertNotEquals(fresh,cap);a.validateChoice(fresh)
        assertEquals("feature_disabled",error{a.resolveLegacyCloud(fresh)})
    }
    @Test fun legacyInboxIsEncryptedIdempotentAndNeverFakesOldAppliedCursor() {
        val db=room();val a=adapter(db);a.install();val sql=db.openHelper.writableDatabase
        val input=SyncV2Projection.LegacyInput("peer",13,"opaque-z",JSONObject("{one:{title:'private offline body'}}"))
        assertEquals("pendingChoice",a.receiveLegacy(input,null,true));val version=a.state().version
        assertEquals("pendingChoice",a.receiveLegacy(input,null,true));assertEquals(version,a.state().version)
        assertEquals("idempotency_conflict",error{a.receiveLegacy(input.copy(body=JSONObject("{one:{title:'other'}}")),null,true)})
        assertEquals("normal",a.receiveLegacy(input.copy(version=14),emptySet(),true))
        val installed=SyncV2Fixtures.identity();val encrypted=installed.encryptedRecord(SyncV2Fixtures.localKey)
        assertEquals(installed.entity("article","one"),SyncV2Identity.restoreInstalled(encrypted,SyncV2Fixtures.localKey,installed.scopeJson,installed.accountId,installed.descriptorOid).entity("article","one"))
        assertEquals(2,scalar(sql,"SELECT COUNT(*) FROM syncv2_legacy_inbox"))
        assertEquals(0,a.state().body.getJSONObject("applied").length())
        sql.query("SELECT body FROM syncv2_legacy_inbox").use{while(it.moveToNext())assertFalse(it.getBlob(0).toString(Charsets.UTF_8).contains("private offline body"))}
    }
    @Test fun d64ExhaustionRejectsBeforeCallbacksAndBusinessTriggerRollsBack() {
        val db=room();val a=adapter(db);a.install();val sql=db.openHelper.writableDatabase
        sql.execSQL("UPDATE syncv2_state SET state_version='18446744073709551614',local_revision='18446744073709551614'")
        put(db,article("last"));assertEquals("18446744073709551615",a.state().version)
        assertEquals("18446744073709551615",a.state().revision)
        var callback=false;val exhausted=adapter(db){callback=true}
        assertEquals("counter_exhausted",error{exhausted.scanPage("phone_articles")});assertFalse(callback)
        try{put(db,article("never"));fail("overflow accepted")}catch(e:android.database.sqlite.SQLiteException){assertTrue(e.message!!.contains("counter_exhausted"))}
        assertEquals(1,scalar(sql,"SELECT COUNT(*) FROM phone_articles"))
    }
    @Test fun sharedB05B09ObservationsAndEditsPreserveLatestState() {
        val fixed=SyncV2Fixtures.vector("rc3-review").getJSONArray("cases")
        fun expected(id:String)=(0 until fixed.length()).map(fixed::getJSONObject).single{it.getString("id")==id}.getJSONObject("expected")
        val db=room();val a=adapter(db);a.install();pending(a);val cap=a.captureLegacyChoice(SyncV2Fixtures.window())
        val body=JSONObject(SyncV2Fixtures.observationHeaders()[0].getJSONObject("body").toString()).put("accountParent",cap.accountOid).put("previousOwnCommit",cap.accountOid).put("rollbackGeneration","2").put("deviceSequence","9007199254740996")
        val header=SyncV2Fixtures.signSyntheticHeader(body)
        val window=JSONObject().put("oid",SyncV2Codec.oid("commit",body)).put("revision","4").put("generation","2")
        val stale=error{a.validateChoice(cap){a.observeVerified(window,listOf(header))}}
        same(expected("B05"),JSONObject().put("lastSeen",a.state().body.getString("lastSeen")).put("oldGenerationRejected",stale=="choice_stale"))
        val sql=db.openHelper.writableDatabase;sql.execSQL("UPDATE syncv2_state SET local_revision='7'")
        val next=a.captureLegacyChoice(window)
        val error=error{a.validateChoice(next){put(db,article("new-edit"))}}
        same(expected("B09"),JSONObject().put("error",error).put("oldEditRetained",scalar(sql,"SELECT COUNT(*) FROM syncv2_legacy_inbox WHERE applied=0")==1L).put("newEditRetained",scalar(sql,"SELECT COUNT(*) FROM syncv2_outbox")==1L).put("revision",a.state().revision))
    }
    @Test fun samePinnedHeadCannotClaimAnotherSignedGeneration() {
        val db=room();val a=adapter(db);a.install();pending(a);val before=a.state()
        val forged=JSONObject(SyncV2Fixtures.window().toString()).put("generation","2").put("revision","4")
        assertEquals("replay_detected",error{a.observeVerified(forged,emptyList())})
        assertEquals(before.version,a.state().version);same(before.body,a.state().body)
    }
    @Test fun deletionBeforeScanIsDurableAndUpdatesOnlyCandidateTree() {
        val db=room();val a=adapter(db);a.install();put(db,article("removed-before-scan"));val sql=db.openHelper.writableDatabase
        sql.execSQL("DELETE FROM phone_articles WHERE articleId='removed-before-scan'")
        while(a.catchUp()>0){}
        assertTrue(a.candidate("article","removed-before-scan")!!.second.getBoolean("projectionRemoved"))
        assertEquals(1,scalar(sql,"SELECT COUNT(*) FROM syncv2_identity_map"));assertEquals(0,scalar(sql,"SELECT COUNT(*) FROM phone_articles"))
        assertEquals(1,scalar(sql,"SELECT COUNT(*) FROM syncv2_tree_nodes WHERE prefix=''"))
    }
    @Test fun windowBudgetPauseReusesActualPersistedPrefix() {
        val path=path();var db=room(path);var a=adapter(db);a.install();repeat(200){put(db,article("id%03d".format(it)))}
        val first=a.runWindow(maxPages=1);assertEquals(64,first.rows);assertTrue(first.paused)
        db.close();db=room(path);a=adapter(db);a.install()
        val second=a.runWindow(maxPages=1);assertEquals(64,second.rows);assertTrue(second.paused)
        val sql=db.openHelper.writableDatabase;assertEquals(128,scalar(sql,"SELECT COUNT(*) FROM syncv2_candidates"))
        var ticks=0L;val exhausted=a.runWindow(budgetMillis=1,now={ticks++})
        assertTrue(exhausted.paused);assertEquals(0,exhausted.rows)
    }
    @Test fun paged12500MetadataCandidatesResumeAcrossWindows() {
        val path=path();var db=room(path);var a=adapter(db);a.install();val sql=db.openHelper.writableDatabase
        sql.beginTransaction();try{repeat(12500){put(db,article("id-%05d".format(it)))};sql.setTransactionSuccessful()}finally{sql.endTransaction()}
        val before=businessDigest(sql);var count=0;var pages=0;var maxPageBytes=0;var bytes=0L;val windows=JSONArray();val started=System.nanoTime()
        while(true) {
            val p=a.scanPage("phone_articles");count+=p.rows;pages++;maxPageBytes=maxOf(maxPageBytes,p.bytes);bytes+=p.bytes
            if(pages%40==0){val checkpoint=count;db.close();db=room(path);a=adapter(db);a.install();windows.put(checkpoint)}
            if(p.complete)break
        }
        assertEquals(12500,count);assertTrue(pages>=196);assertTrue(maxPageBytes<=1048576)
        assertEquals(before,businessDigest(db.openHelper.writableDatabase))
        var tail=0;do{val n=a.catchUp();tail+=n}while(n>0)
        assertEquals(12500,tail);assertEquals(12500,scalar(db.openHelper.writableDatabase,"SELECT COUNT(*) FROM syncv2_identity_map"))
        val evidence=JSONObject().put("platform","JVM Robolectric native SQLite, sdk35; not a physical phone").put("entities",count).put("pages",pages).put("maxRowsPerPage",64).put("metadataInputBytes",bytes).put("maxPageBytes",maxPageBytes).put("durableWindowPrefixes",windows).put("tailEntries",tail).put("scanElapsedMillis",(System.nanoTime()-started)/1000000)
        db.openHelper.writableDatabase.query("SELECT SUM(length(meta)),SUM(length(state)),COUNT(*) FROM syncv2_candidates").use{it.moveToFirst();evidence.put("encryptedMetaBytes",it.getLong(0)).put("encryptedStateBytes",it.getLong(1)).put("candidateObjects",it.getLong(2)*2)}
        val status=File("/proc/self/status").readLines().firstOrNull{it.startsWith("VmHWM:")};db.openHelper.writableDatabase.query("SELECT COUNT(*),SUM(length(body)) FROM syncv2_tree_nodes").use{it.moveToFirst();evidence.put("localCandidateTreeNodes",it.getLong(0)).put("localCandidateTreeBytes",it.getLong(1))}
        evidence.put("processPeakRss",status?:"unavailable").put("bridgeCalls","not applicable: JVM native SQLite host").put("databaseBytes",path.length())
        SyncV2Fixtures.evidence("12500.json",evidence)
    }
}
