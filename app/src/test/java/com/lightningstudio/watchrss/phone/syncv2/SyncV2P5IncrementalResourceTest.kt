package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SyncV2P5IncrementalResourceTest {
    private fun folders(n:Int)=(0 until n).map{i->JSONObject().put("folderId","folder-${i.toString().padStart(5,'0')}").put("name","folder").put("sortOrder",i).put("updatedAt",0).put("modifiedBy","synthetic").put("deleted",false).put("deletedAt",0)}
    private fun emit(name:String,value:JSONObject){System.getenv("WRSS_P5_REVIEW_EVIDENCE")?.takeIf{it.isNotBlank()}?.let{dir->File(dir).mkdirs();File(dir,name).writeText(value.toString(2)+"\n")}}
    /** Concurrent samples report simultaneous heap use; MXBean pool peaks can occur
     * at different instants and must not be added to claim a heap high-water mark. */
    private class HeapSampler:AutoCloseable {
        private val live=java.util.concurrent.atomic.AtomicBoolean(true)
        val peak=java.util.concurrent.atomic.AtomicLong()
        private val thread=Thread({
            runCatching {
                val factory=Class.forName("java.lang.management.ManagementFactory")
                val bean=factory.getMethod("getMemoryMXBean").invoke(null)
                val usage=Class.forName("java.lang.management.MemoryMXBean").getMethod("getHeapMemoryUsage")
                val used=Class.forName("java.lang.management.MemoryUsage").getMethod("getUsed")
                while(live.get()) {
                    val bytes=used.invoke(usage.invoke(bean))as Long
                    peak.accumulateAndGet(bytes,::maxOf);Thread.sleep(100)
                }
            }
        },"p5-test-heap-sampler").apply{isDaemon=true;start()}
        override fun close(){live.set(false);thread.join(1000)}
    }
    /** Large setup authenticates real encrypted control objects in memory. Only the
     * measured incremental publication uses HTTP; this avoids a synthetic 25k-GET setup. */
    private fun install(w:SyncV2P5Fixture) {
        val authors=w.f.authors();val oid=w.current.getString("commitOid");val head=w.headers.getValue(oid)
        val verified=SyncV2ControlClosure(w.identity,authors,{w.key}).verify(head,{w.f.objects.getValue(it)},{w.refs.getValue(it)})
        w.projection.saveSigningBindings(JSONArray().put(w.binding()),"0")
        w.projection.installCloudIndex(JSONObject().put("oid",oid).put("revision",w.current.getString("revision")).put("generation",w.current.getString("rollbackGeneration")),w.headers.values.toList(),verified,authors::header,w.current.getString("revision"),{w.headers.getValue(it)})
    }
    @Test fun twelveThousandUnchangedFoldersKeepCiphertextCandidatesAndBoundedUpload():Unit=SyncV2P5Fixture().use{w->HeapSampler().use{memory->
        val started=System.nanoTime();val count=12500;val fs=folders(count)
        val old=w.commit(listOf(w.note("old title"))+fs,w.initial,"0","normal",null)
        val latest=w.commit(listOf(w.note("new title"))+fs,old,"0","normal",null);w.setCurrent(latest,"4")
        w.sql.execSQL("CREATE TABLE resource_updates(kind TEXT)")
        w.sql.execSQL("CREATE TRIGGER resource_folder_update AFTER UPDATE ON note_folders BEGIN INSERT INTO resource_updates VALUES('business'); END")
        w.sql.execSQL("CREATE TRIGGER resource_binding_insert AFTER INSERT ON syncv2_p5_bindings BEGIN INSERT INTO resource_updates VALUES('binding'); END")
        w.sql.execSQL("CREATE TRIGGER resource_candidate_insert AFTER INSERT ON syncv2_candidates BEGIN INSERT INTO resource_updates VALUES('candidate'); END")
        install(w);w.drain();val a=w.actions();assertTrue(a.applyObserved());w.drain();val initial=System.nanoTime()
        w.edit();val edited=System.nanoTime()
        val rolled=w.commit(emptyList(),latest,"1","rollback",old);w.setCurrent(rolled,"5");install(w);w.drain();val observed=System.nanoTime()
        val original=w.projection.cloudRows().filter{it.getJSONObject("meta").getString("kind")=="folder"}.associate{it.getJSONObject("meta").getString("entityKey") to it.getJSONObject("row")}
        val candidateBefore=w.sql.query("SELECT entity_key,source_hash,meta,state FROM syncv2_candidates WHERE kind='folder' ORDER BY entity_key").use{c->buildMap{while(c.moveToNext())put(c.getString(0),listOf(c.getString(1),SyncV2Codec.hex(SyncV2Codec.sha(c.getBlob(2))),SyncV2Codec.hex(SyncV2Codec.sha(c.getBlob(3)))) )}}
        w.sql.execSQL("DELETE FROM resource_updates");val putBefore=w.f.putOids.size;var stagedRows=0L
        val action=w.actions{if(it=="action-staged")stagedRows=w.sql.query("SELECT COUNT(*) FROM syncv2_p5_rows").use{it.moveToFirst();it.getLong(0)}}
        val preview=action.preview();val previewed=System.nanoTime();action.choose(preview,"local");val committed=System.nanoTime()
        val progress=w.projection.runWindow();assertFalse(progress.paused);assertEquals(0,progress.rows);assertEquals(0,progress.tailEntries)
        val current=w.projection.cloudRows().filter{it.getJSONObject("meta").getString("kind")=="folder"}
        assertEquals(count,current.size)
        for(r in current)assertEquals(SyncV2Codec.canonical(original.getValue(r.getJSONObject("meta").getString("entityKey"))),SyncV2Codec.canonical(r.getJSONObject("row")))
        val candidateAfter=w.sql.query("SELECT entity_key,source_hash,meta,state FROM syncv2_candidates WHERE kind='folder' ORDER BY entity_key").use{c->buildMap{while(c.moveToNext())put(c.getString(0),listOf(c.getString(1),SyncV2Codec.hex(SyncV2Codec.sha(c.getBlob(2))),SyncV2Codec.hex(SyncV2Codec.sha(c.getBlob(3)))) )}}
        assertEquals(candidateBefore,candidateAfter)
        val updates=w.sql.query("SELECT kind,COUNT(*) FROM resource_updates GROUP BY kind").use{c->buildMap{while(c.moveToNext())put(c.getString(0),c.getInt(1))}}
        assertEquals(0,updates["business"]?:0);assertEquals(0,updates["candidate"]?:0);assertEquals(1,updates["binding"])
        val puts=w.f.putOids.drop(putBefore).distinct();val bytes=puts.sumOf{w.f.objects.getValue(it).size.toLong()}
        assertTrue("incremental objects ${puts.size}",puts.size<=8);assertTrue("incremental bytes $bytes",bytes<262144);assertEquals(4,stagedRows)
        assertTrue("heap samples available",memory.peak.get()>0);assertTrue(memory.peak.get()<=Runtime.getRuntime().maxMemory())
        val evidence=JSONObject().put("platform","JVM/native Room, sdk35; not a physical phone").put("unchangedFolders",count).put("editedNotes",1)
            .put("changedFolderWireRows",0).put("changedFolderCandidates",0).put("businessFolderUpdates",updates["business"]?:0).put("candidateWrites",updates["candidate"]?:0).put("bindingWrites",updates["binding"]?:0)
            .put("stagedRows",stagedRows).put("uploadedControlObjects",puts.size).put("uploadedControlBytes",bytes).put("postApplyScanRows",progress.rows)
            .put("setupMillis",(initial-started)/1000000).put("localEditProjectionMillis",(edited-initial)/1000000).put("authenticatedRollbackSetupMillis",(observed-edited)/1000000).put("previewMillis",(previewed-observed)/1000000).put("chooseMillis",(committed-previewed)/1000000)
            .put("sampledHeapPeakBytes",memory.peak.get()).put("heapSamplingIntervalMillis",100).put("heapMaxBytes",Runtime.getRuntime().maxMemory()).put("processPeakRss",System.getenv("WRSS_P5_PROC_STATUS")?.takeIf{it.isNotBlank()}?.let{File(it).readLines().firstOrNull{line->line.startsWith("VmHWM:")}}?:JSONObject.NULL).put("physicalBudgetVerified",false)
        println("P5R_LARGE entities=$count objects=${puts.size} bytes=$bytes stagedRows=$stagedRows businessUpdates=${updates["business"]?:0}")
        emit("incremental-12500.json",evidence)
    }}
    @Test fun singlePageWindowsResumePastCompletedDomainsAndSurviveRestart():Unit=SyncV2P5Fixture().use{w->
        val all=folders(257);val oid=w.commit(listOf(w.note())+all,w.initial,"0","normal",null);w.setCurrent(oid,"3")
        install(w);w.drain();assertTrue(w.actions().applyObserved());w.drain()
        w.sql.execSQL("UPDATE note_folders SET name='changed' WHERE folderId='folder-00256'")
        var complete=false;var windows=0;var restarts=0;var rows=0;val checkpoints=JSONArray()
        while(!complete && windows<32) {
            val result=w.projection.runWindow(maxPages=1);windows++;rows+=result.rows;assertTrue(result.pages<=1)
            checkpoints.put(w.sql.query("SELECT page_key FROM syncv2_scan WHERE domain='note_folders'").use{if(it.moveToFirst())it.getString(0)else""})
            complete=!result.paused
            if(!complete && windows%3==0){w.reopen();restarts++}
        }
        assertTrue("bounded windows must advance",complete);assertTrue(restarts>0)
        val entity=w.projection.candidate("folder","folder-00256")!!;assertEquals("changed",SyncV2Business.join("folder",entity.first,entity.second).getString("name"))
        emit("incremental-window-resume.json",JSONObject().put("folders",257).put("maxPagesPerWindow",1).put("windows",windows).put("restarts",restarts).put("scannedRows",rows).put("checkpoints",checkpoints).put("complete",complete))
    }
}
