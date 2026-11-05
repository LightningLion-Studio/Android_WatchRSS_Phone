package com.lightningstudio.watchrss.phone.syncv2

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
import com.lightningstudio.watchrss.phone.data.db.PhoneCompanionDatabase
import com.lightningstudio.watchrss.phone.data.db.SyncPeerStateEntity
import kotlinx.coroutines.runBlocking
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
class SyncV2WindowTest {
    private val roots=mutableListOf<File>()
    private val opened=mutableListOf<PhoneCompanionDatabase>()
    private fun file()=File(RuntimeEnvironment.getApplication().cacheDir,"r10-${System.nanoTime()}").also{it.mkdirs();roots.add(it)}.resolve("phone.db")
    private fun room(file:File)=Room.databaseBuilder(RuntimeEnvironment.getApplication(),PhoneCompanionDatabase::class.java,file.absolutePath).allowMainThreadQueries().build().also{opened.add(it)}
    private fun adapter(db:PhoneCompanionDatabase)=SyncV2Projection(db.openHelper.writableDatabase,SyncV2Fixtures.identity(),SyncV2Fixtures.localKey,true)
    private fun article(id:String)=PhoneArticleEntity(id,"legacy","https://example.invalid/$id","initial","site","excerpt",null,"body",null,"hash",1,1,true,1,1,null,null,true,1,1,false,0,0,false,0)
    private fun scalar(db:SupportSQLiteDatabase,sql:String)=db.query(sql).use{assertTrue(it.moveToFirst());it.getLong(0)}
    private fun finish(a:SyncV2Projection) {
        for(d in SyncV2Schema.domains){var rounds=0;while(!a.scanPage(d.table).complete)assertTrue(++rounds<100)}
        var rounds=0
        while(true) {
            val n=a.catchUp();val body=a.state().body
            if(n==0 && !body.has("reconcileStamp") && body.optString("businessStamp")==body.optString("reconciledStamp"))return
            assertTrue(++rounds<100)
        }
    }
    /** Identical racing insertion and fourth clock callback as the independent review. */
    private fun raceClock(sql:SupportSQLiteDatabase):()->Long {
        var ticks=0
        return {
            ticks++
            if(ticks==4) {
                val columns=sql.query("PRAGMA table_info(phone_articles)").use{c->buildList{while(c.moveToNext())add(c.getString(1))}}
                val selection=columns.joinToString(","){if(it=="articleId")"'zz-new'"else if(it=="title")"'racing new'"else"`$it`"}
                sql.execSQL("INSERT INTO phone_articles SELECT $selection FROM phone_articles WHERE articleId='a000'")
            }
            0L
        }
    }
    private fun prefix(sql:SupportSQLiteDatabase)=sql.query("SELECT meta,state FROM syncv2_candidates WHERE logical_id='a000'").use{assertTrue(it.moveToFirst());it.getBlob(0)+it.getBlob(1)}
    @After fun close(){opened.forEach{if(it.isOpen)it.close()};roots.forEach{it.deleteRecursively()}}

    @Test fun racingInsertPausesAt64AndResumesPersistedPageAfterReopen()=runBlocking<Unit> {
        val file=file();var db=room(file)
        repeat(100){db.phoneArticleDao().upsert(article("a"+it.toString().padStart(3,'0')))}
        db.syncPeerStateDao().upsert(SyncPeerStateEntity("peer",8,7,0,14,0))
        var a=adapter(db);a.install();finish(a)
        val sql=db.openHelper.writableDatabase;val prefix=prefix(sql)
        val first=a.runWindow(maxPages=4096,now=raceClock(sql))
        assertTrue(first.paused);assertEquals(64,first.tailEntries);assertNull(a.candidate("article","zz-new"))
        val body=a.state().body
        assertTrue(body.has("reconcileStamp"));assertEquals(0,body.getInt("reconcileDomain"));assertFalse(body.has("tailR1Seq"))
        sql.query("SELECT page_key,complete FROM syncv2_scan WHERE domain='phone_articles'").use{assertTrue(it.moveToFirst());assertEquals("a063",it.getString(0));assertEquals(0,it.getInt(1))}
        val version=a.state().version
        db.close();db=room(file);a=adapter(db);a.install();assertEquals(version,a.state().version)
        val second=a.runWindow(maxPages=4096,now={0L})
        assertEquals(37,second.rows) // Resume after a063, including the one racing insert.
        assertEquals("racing new",a.candidate("article","zz-new")!!.first.getJSONObject("payload").getString("title"))
        var last=second;var windows=2
        while(last.paused){assertTrue(++windows<10);last=a.runWindow(maxPages=4096,now={0L})}
        val completed=a.state().body
        assertFalse(completed.has("reconcileStamp"));assertFalse(completed.has("tailR1Seq"))
        assertEquals(completed.getString("businessStamp"),completed.getString("reconciledStamp"))
        assertEquals(101,scalar(db.openHelper.writableDatabase,"SELECT COUNT(*) FROM syncv2_identity_map"))
        assertEquals(7,db.syncPeerStateDao().get("peer")!!.lastRemoteSeqApplied)
        assertArrayEquals(prefix,prefix(db.openHelper.writableDatabase))
        SyncV2Fixtures.evidence("r10-resume.json",JSONObject().put("firstPaused",first.paused).put("firstTailEntries",first.tailEntries).put("persistedPage","a063")
            .put("secondRows",second.rows).put("secondPaused",second.paused).put("windows",windows).put("finalPaused",last.paused).put("identityCount",101).put("oldCursor",7).put("prefixCiphertextPreserved",true))
    }
    @Test fun completedFixedPassStillPausesWhenCurrentBusinessRevisionIsNewer()=runBlocking<Unit> {
        val db=room(file());val sql=db.openHelper.writableDatabase
        repeat(100){db.phoneArticleDao().upsert(article("a"+it.toString().padStart(3,'0')))}
        sql.execSQL("INSERT INTO note_assets VALUES('asset','note',?,'old','text/plain',0,'asset',1,0,0,0)",arrayOf("ab".repeat(32)))
        val a=adapter(db);a.install();finish(a)
        sql.execSQL("UPDATE note_assets SET displayName='changed' WHERE assetId='asset'")
        var rounds=0
        while(a.state().body.optInt("reconcileDomain",-1)<SyncV2Schema.domains.size){a.catchUp();assertTrue(++rounds<20)}
        // The last domain's page is durable; only the fixed-pass finalization is pending.
        while(scalar(sql,"SELECT COALESCE(MAX(seq),0) FROM syncv2_outbox")>a.state().body.optLong("tailSeq",0))a.catchUp()
        assertTrue(a.state().body.has("reconcileStamp"))
        val progress=a.runWindow(maxPages=4096,now=raceClock(sql))
        val body=a.state().body
        assertTrue(progress.paused);assertFalse(body.has("reconcileStamp"));assertFalse(body.has("tailR1Seq"))
        assertNotEquals(body.getString("businessStamp"),body.getString("reconciledStamp"))
        assertNull(a.candidate("article","zz-new"))
        var last=progress;var windows=0
        while(last.paused){assertTrue(++windows<10);last=a.runWindow(maxPages=4096,now={0L})}
        assertNotNull(a.candidate("article","zz-new"))
        SyncV2Fixtures.evidence("r10-newer-revision.json",JSONObject().put("pausedWithNoTailOrReconcileFlag",progress.paused).put("revisionsDiffered",true).put("finalPaused",last.paused))
    }
    @Test fun unchangedCompleteWindowReturnsFalseWithoutMutatingCheckpoints()=runBlocking<Unit> {
        val db=room(file());repeat(3){db.phoneArticleDao().upsert(article("a00$it"))}
        val a=adapter(db);a.install();finish(a);val version=a.state().version
        val complete=a.runWindow(maxPages=4096,now={0L})
        assertFalse(complete.paused);assertEquals(0,complete.rows);assertEquals(0,complete.tailEntries)
        assertEquals(version,a.state().version)
    }
}
