package com.lightningstudio.watchrss.phone.syncv2

import android.content.Context
import androidx.room.Room
import com.lightningstudio.watchrss.phone.data.db.PhoneCompanionDatabase
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

/** verifyCloud transport: batch reads, resumable fetch cache, installed-object reuse. A2 outbox base. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SyncV2ControlFetchTest {
    private val context:Context=RuntimeEnvironment.getApplication()
    private val root=File(context.cacheDir,"syncv2-fetch-${System.nanoTime()}").also{it.mkdirs()}
    private val room=Room.databaseBuilder(context,PhoneCompanionDatabase::class.java,File(root,"phone.db").absolutePath).allowMainThreadQueries().build()
    private val sql get()=room.openHelper.writableDatabase
    private val identity=SyncV2Fixtures.identity();private val contentKey=ByteArray(32){0x22}
    private fun signer()=SyncV2Signing.fromPrivate(ByteArray(31)+byteArrayOf(7),"aa".repeat(16),"ab".repeat(16))
    @After fun close(){if(room.isOpen)room.close();root.deleteRecursively()}

    private inner class Cloud(count:Int) {
        val p=SyncV2Projection(sql,identity,SyncV2Fixtures.localKey,true,{},false)
        var gets=0;var references=0;var batches=0;var batched=0;var mappingCalls=0;var mapped=0
        var failMappingsAfter=Int.MAX_VALUE
        /** Small response limit: the server returns the overflow as nextOids. */
        var batchLimit=40
        val index:SyncV2Projection.WireIndex;val signed:JSONObject;val authors=SyncV2Authors(identity){true}
        private val refs:Map<String,JSONObject>
        init {
            for(i in 0 until count)sql.execSQL("INSERT INTO notes(noteId,folderId,title,markdown,plainText,contentHash,baseContentHash,baseMarkdown,pinned,createdAt,updatedAt,modifiedBy,deleted,deletedAt) VALUES(?,NULL,'title',?,?,?,?,'',0,0,90000,'old',0,0)",
                arrayOf<Any>("note$i","body $i","body $i","ab".repeat(32),"cd".repeat(32)))
            p.install();while(!p.scanPage("notes").complete){}
            index=p.prepareWireIndex(signer(),contentKey,1)
            refs=index.refs.associateBy{it.getJSONObject("body").getString("stateOid")}
            signed=signer().commit(JSONObject(SyncV2Fixtures.envelope().getJSONObject("body").toString()).put("treeRoot",index.tree.root))
            authors.accept(identity.signingBinding(signer(),100,"0"),"0")
        }
        fun mapping(parent:String):JSONObject {
            val ref=refs.getValue(parent)
            return JSONObject().put("scope",identity.scope).put("parentOid",parent).put("refsOid",SyncV2Codec.oid("state-refs",ref))
                .put("authorDeviceId",signer().deviceId).put("keyId",signer().keyId).put("totalBytes",SyncV2Codec.utf8(SyncV2Codec.canonical(ref)).size)
        }
        val remote=SyncV2ControlFetch.Remote({oids->
            batches++
            val now=oids.take(batchLimit);batched+=now.size
            JSONObject().put("objects",JSONArray(now.map{JSONObject().put("oid",it).put("bytes",SyncV2Codec.b64(index.objects.getValue(it)))}))
                .put("nextOids",JSONArray(oids.drop(batchLimit)))
        }) {parents->
            mappingCalls++
            if(mapped>=failMappingsAfter)SyncV2Codec.fail("budget_paused")
            mapped+=parents.size;parents.associateWith{mapping(it)}
        }
        fun verify(remote:SyncV2ControlFetch.Remote?=this.remote)=p.verifyCloud(SyncV2ControlClosure(identity,authors,{contentKey},true),signed,
            {oid->gets++;index.objects.getValue(oid)},{parent->references++;mapping(parent)},remote)
        fun install(verified:SyncV2ControlClosure.Verified)=verified.use{
            p.installCloudIndex(JSONObject().put("oid",it.commitOid).put("revision","1").put("generation","0"),listOf(signed),it,authors::header)
        }
        fun cached(where:String)=sql.query("SELECT COUNT(*) FROM syncv2_verify_cache WHERE scope=? AND $where",arrayOf(p.scope)).use{it.moveToFirst();it.getInt(0)}
        fun reset(){gets=0;references=0;batches=0;batched=0;mappingCalls=0;mapped=0}
    }

    @Test fun batchReadsReplaceTheSerialControlReadsAndReturnTheSameClosure() {
        val rows=150;val c=Cloud(rows)
        val serial=c.verify(null).use{v->v.commitOid to v.rows.map{SyncV2Codec.canonical(it)}}
        // One tree read, then meta, state, mapping and refs for each row.
        assertTrue(c.gets>=3*rows);assertEquals(rows,c.references)
        c.reset()
        c.verify().use{v->
            assertEquals(serial,v.commitOid to v.rows.map{SyncV2Codec.canonical(it)})
            assertEquals(0,c.gets);assertEquals(0,c.references)
            assertEquals(rows,c.mapped);assertEquals((rows+63)/64,c.mappingCalls)
            assertTrue(c.batched>=3*rows)
            // The response limit is the only reason for more than one call per level.
            assertTrue("batches=${c.batches}",c.batches<=c.batched/c.batchLimit+8)
        }
    }

    @Test fun pausedRoundKeepsItsProgressAndInstalledObjectsNeedNoNetwork() {
        val rows=150;val c=Cloud(rows)
        c.failMappingsAfter=64
        try{c.verify();fail("budget")}catch(e:IllegalStateException){assertEquals("budget_paused",e.message)}
        val objects=c.batched;assertTrue(objects>=2*rows)
        assertEquals(64,c.cached("key LIKE 'map:%'"));assertEquals(objects,c.cached("key NOT LIKE 'map:%'"))
        // Next round: no object is read again; only the missing mappings and their refs are read.
        c.reset();c.failMappingsAfter=Int.MAX_VALUE
        val verified=c.verify()
        assertEquals(rows-64,c.mapped);assertEquals(rows,c.batched);assertEquals(0,c.gets);assertEquals(0,c.references)
        c.install(verified)
        // Installed objects are served from syncv2_cloud_objects. The fetch copy is released.
        assertEquals(0,c.cached("key NOT LIKE 'map:%'"));assertEquals(rows,c.cached("key LIKE 'map:%'"))
        c.reset()
        c.verify().close()
        assertEquals(0,c.batches);assertEquals(0,c.mappingCalls);assertEquals(0,c.gets);assertEquals(0,c.references)
    }

    @Test fun staleCachedMappingAndCorruptCachedObjectFallBackToTheServer() {
        val rows=6;val c=Cloud(rows)
        c.verify().close()
        val keys=sql.query("SELECT key FROM syncv2_verify_cache WHERE scope=? ORDER BY key",arrayOf(c.p.scope)).use{k->buildList{while(k.moveToNext())add(k.getString(0))}}
        val state=keys.first{it.startsWith("map:")}.removePrefix("map:")
        // A locator that no longer matches fails verification; the round then asks the server.
        sql.execSQL("UPDATE syncv2_verify_cache SET body=(SELECT body FROM syncv2_verify_cache WHERE key=?) WHERE key=?",arrayOf(keys.last{it.startsWith("map:")},"map:$state"))
        // A cached object with wrong bytes is never returned.
        val meta=keys.first{it.startsWith("meta:")}
        sql.execSQL("UPDATE syncv2_verify_cache SET body=x'00' WHERE key=?",arrayOf(meta))
        c.reset()
        c.verify().use{assertEquals(rows,it.rows.size)}
        assertEquals(rows,c.references);assertTrue(c.batched>=1)
        // Without a batch transport the verifier reads each object alone, as before.
        c.reset();c.verify(null).close();assertTrue(c.gets>=3*rows)
    }

    @Test fun scanSealsOnlyTheAccountBaseInTheOutboxAndOldRecordsStillRead() {
        val c=Cloud(3)
        val bodies=sql.query("SELECT base_state FROM syncv2_outbox WHERE scope=?",arrayOf(c.p.scope)).use{k->buildList{while(k.moveToNext())add(k.getBlob(0))}}
        assertEquals(3,bodies.size)
        val state=sql.query("SELECT body FROM syncv2_state WHERE scope=?",arrayOf(c.p.scope)).use{it.moveToFirst();it.getBlob(0)}
        for(body in bodies) {
            val base=JSONObject(SyncV2Codec.openLocal(SyncV2Fixtures.localKey,c.p.scope,"state",body))
            assertTrue(base.keys().asSequence().all{it=="account"})
            assertTrue(body.size<state.size)
        }
        // A record from an earlier version holds the complete state. The tail reads it the same way.
        sql.execSQL("UPDATE syncv2_outbox SET base_state=? WHERE scope=?",arrayOf(state,c.p.scope))
        assertEquals(3,c.p.catchUp())
    }
}
