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
class SyncV2P5SparseReadTest {
    @Test fun bodySourceCountersObserveActualDatabaseSlicesAndFileHashReads():Unit=SyncV2P5Fixture().use{w->
        val schema=javaClass.classLoader!!.getResourceAsStream("syncv2-business-schema.json")!!.bufferedReader().use{JSONObject(it.readText())}.getJSONArray("records")
        val article=JSONObject((0 until schema.length()).map{schema.getJSONObject(it)}.single{it.getString("kind")=="article"}.getJSONObject("value").toString())
        val id=article.getString("articleId");val pair=SyncV2Business.split("article",id,w.identity.entity("article",id),article,null)
        val record=JSONObject().put("meta",pair.meta).put("state",pair.state).put("value",article)
        w.sql.beginTransaction();try{SyncV2PhoneApply(w.sql).apply(emptyList(),listOf(record));w.sql.setTransactionSuccessful()}finally{w.sql.endTransaction()}
        val bytes="source body ".repeat(10000).toByteArray();w.sql.execSQL("UPDATE phone_articles SET contentText=?,contentHtml=NULL WHERE articleId=?",arrayOf(bytes.toString(Charsets.UTF_8),id))
        val reads=SyncV2RecoveryStorage.SourceReads();val storage=SyncV2RecoveryStorage(w.projection,w.identity,w.root,sourceReads=reads)
        assertTrue(storage.contentReady(record));assertEquals(bytes.size.toLong(),reads.databaseBytes);assertTrue(reads.operations>=2)
        val asset=JSONObject().put("assetId","asset").put("mime","application/octet-stream").put("originalFilename","asset").put("updatedAt",0).put("sourceDeviceId","synthetic").put("deleted",false).put("deletedAt",0)
        val p=SyncV2Business.split("asset","asset",w.identity.entity("asset","asset"),asset,null)
        w.sql.execSQL("INSERT INTO note_assets VALUES('asset','note',?,'asset.bin','application/octet-stream',?,'asset.bin',0,0,0,0)",arrayOf<Any>(SyncV2Codec.hex(SyncV2Codec.sha(bytes)),bytes.size))
        File(w.root,"notes/assets/asset.bin").also{it.parentFile!!.mkdirs();it.writeBytes(bytes)}
        assertTrue(storage.contentReady(JSONObject().put("meta",p.meta).put("state",p.state).put("value",asset)));assertEquals(bytes.size.toLong(),reads.fileBytes)
    }
    private class Reads {
        val calls=linkedMapOf<String,Long>();val rows=linkedMapOf<String,Long>();val blobBytes=linkedMapOf<String,Long>();val stringBytes=linkedMapOf<String,Long>()
        fun wrap(db:androidx.sqlite.db.SupportSQLiteDatabase):androidx.sqlite.db.SupportSQLiteDatabase {
            fun cursor(sql:String,c:android.database.Cursor):android.database.Cursor {
                val table=listOf("syncv2_candidates","syncv2_cloud_rows","syncv2_cloud_objects","syncv2_p5_bindings","syncv2_wire_candidates","syncv2_state").firstOrNull{sql.contains("FROM $it ") || sql.contains("FROM $it\n")}?:"other"
                calls[table]=(calls[table]?:0)+1
                return object:android.database.CursorWrapper(c) {
                    override fun moveToNext():Boolean=super.moveToNext().also{if(it)rows[table]=(rows[table]?:0)+1}
                    override fun moveToFirst():Boolean=super.moveToFirst().also{if(it)rows[table]=(rows[table]?:0)+1}
                    override fun getBlob(index:Int):ByteArray=super.getBlob(index).also{blobBytes[table]=(blobBytes[table]?:0)+it.size}
                    override fun getString(index:Int):String?=super.getString(index).also{if(it!=null)stringBytes[table]=(stringBytes[table]?:0)+it.toByteArray(Charsets.UTF_8).size}
                }
            }
            return object:androidx.sqlite.db.SupportSQLiteDatabase by db {
                override fun query(query:String)=cursor(query,db.query(query))
                override fun query(query:String,bindArgs:Array<out Any?>)=cursor(query,db.query(query,bindArgs))
                override fun query(query:androidx.sqlite.db.SupportSQLiteQuery)=cursor(query.sql,db.query(query))
            }
        }
        fun json()=JSONObject().put("queryCalls",JSONObject(calls)).put("rowsVisited",JSONObject(rows)).put("blobBytesMaterialized",JSONObject(blobBytes)).put("stringUtf8BytesMaterialized",JSONObject(stringBytes))
    }
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
    private fun large(choice:String,mixed:Boolean=false,count:Int=12500,noteCount:Int=if(mixed)8 else 0):Unit=SyncV2P5Fixture().use{w->HeapSampler().use{memory->
        val started=System.nanoTime();val fs=folders(count)
        val schema=javaClass.classLoader!!.getResourceAsStream("syncv2-business-schema.json")!!.bufferedReader().use{JSONObject(it.readText())}.getJSONArray("records")
        fun value(kind:String)=JSONObject((0 until schema.length()).map{schema.getJSONObject(it)}.single{it.getString("kind")==kind}.getJSONObject("value").toString())
        val articles=if(mixed)(0 until 4).map{value("article").put("articleId","mixed-article-$it").put("rssSourceUrl",JSONObject.NULL)}else emptyList()
        val books=if(mixed)(0 until 2).map{index->val book=value("book");val instance=(index+1).toString(16).padStart(32,'0');book.put("instanceId",instance).put("bookId",SyncV2Codec.hex(SyncV2Codec.sha(SyncV2Codec.lp(*listOf("watchrss/book/instance-id/v1",book.getString("fingerprint"),instance).map(SyncV2Codec::utf8).toTypedArray()))))}else emptyList()
        val notes=(0 until noteCount).map{w.note("untouched","unchanged inline body\n".repeat(100)).put("noteId","mixed-note-$it")}
        val rss=if(mixed)(0 until 128).map{value("rss-state").put("articleIdHash",SyncV2Codec.hex(SyncV2Codec.sha(SyncV2Codec.utf8("unrelated-rss-$it"))))}else emptyList()
        val extras=articles+books+notes+rss
        val originalBytes="original book\n".repeat(10000).toByteArray();for(book in books)File(w.root,"imported_books/${book.getString("bookId")}.jsonl").also{it.parentFile!!.mkdirs();it.writeBytes(originalBytes)}
        val old=w.commit(listOf(w.note("old title"))+fs+extras,w.initial,"0","normal",null)
        val latest=w.commit(listOf(w.note("new title"))+fs+extras,old,"0","normal",null);w.setCurrent(latest,"4")
        w.sql.execSQL("CREATE TABLE resource_updates(kind TEXT)")
        w.sql.execSQL("CREATE TRIGGER resource_folder_update AFTER UPDATE ON note_folders BEGIN INSERT INTO resource_updates VALUES('business'); END")
        w.sql.execSQL("CREATE TRIGGER resource_binding_insert AFTER INSERT ON syncv2_p5_bindings BEGIN INSERT INTO resource_updates VALUES('binding'); END")
        w.sql.execSQL("CREATE TRIGGER resource_candidate_insert AFTER INSERT ON syncv2_candidates BEGIN INSERT INTO resource_updates VALUES('candidate'); END")
        install(w);w.drain();val a=w.actions();assertTrue(a.applyObserved());w.drain()
        for(article in articles)w.sql.execSQL("UPDATE phone_articles SET contentText=? WHERE articleId=?",arrayOf("original text ".repeat(5000),article.getString("articleId")))
        for(book in books)w.sql.execSQL("UPDATE imported_books SET packageSha256=?,packageBytes=? WHERE bookId=?",arrayOf(SyncV2Codec.hex(SyncV2Codec.sha(originalBytes)),originalBytes.size,book.getString("bookId")))
        if(mixed)w.drain();val initial=System.nanoTime()
        w.edit();val edited=System.nanoTime()
        val rolled=w.commit(emptyList(),latest,"1","rollback",old);w.setCurrent(rolled,"5");install(w);w.drain();val observed=System.nanoTime()
        fun unchanged(r:JSONObject)=r.getJSONObject("meta").getString("kind")=="folder" || r.getJSONObject("meta").let{it.getString("kind")=="note"&&it.getString("logicalId")!="note"}
        val original=w.projection.cloudRows().filter(::unchanged).associate{it.getJSONObject("meta").getString("entityKey") to it.getJSONObject("row")}
        val candidateBefore=w.sql.query("SELECT entity_key,source_hash,meta,state FROM syncv2_candidates WHERE kind='folder' OR (kind='note' AND logical_id!='note') ORDER BY entity_key").use{c->buildMap{while(c.moveToNext())put(c.getString(0),listOf(c.getString(1),SyncV2Codec.hex(SyncV2Codec.sha(c.getBlob(2))),SyncV2Codec.hex(SyncV2Codec.sha(c.getBlob(3)))) )}}
        w.sql.execSQL("DELETE FROM resource_updates");val putBefore=w.f.putOids.size;var stagedRows=0L
        val reads=Reads();val sourceReads=SyncV2RecoveryStorage.SourceReads();val counted=SyncV2Projection(reads.wrap(w.sql),w.identity,SyncV2Fixtures.localKey,true)
        val action=SyncV2AccountActions(w.f.client(),w.identity,counted,w.f.signer,{w.key},{it=="synthetic-phone"},w.root,true,{w.f.now},recoveryReads=sourceReads,fault={if(it=="action-staged")stagedRows=w.sql.query("SELECT COUNT(*) FROM syncv2_p5_rows").use{it.moveToFirst();it.getLong(0)}})
        val beforePreview=System.nanoTime();val preview=action.preview();val previewed=System.nanoTime();assertTrue(preview.reason,preview.canMerge);action.choose(preview,choice);val committed=System.nanoTime()
        val blobTotal=reads.blobBytes.values.sum();val queryTotal=reads.calls.values.sum()
        assertTrue("bounded BLOB returns $blobTotal",blobTotal<4L*1024*1024);assertTrue("bounded SQL queries $queryTotal",queryTotal<1500)
        assertEquals(0,sourceReads.databaseBytes);assertEquals(0,sourceReads.fileBytes)
        val progress=w.projection.runWindow();assertFalse(progress.paused);assertEquals(0,progress.rows);assertEquals(0,progress.tailEntries)
        val current=w.projection.cloudRows().filter(::unchanged)
        assertEquals(count+noteCount,current.size)
        for(r in current)assertEquals(SyncV2Codec.canonical(original.getValue(r.getJSONObject("meta").getString("entityKey"))),SyncV2Codec.canonical(r.getJSONObject("row")))
        val candidateAfter=w.sql.query("SELECT entity_key,source_hash,meta,state FROM syncv2_candidates WHERE kind='folder' OR (kind='note' AND logical_id!='note') ORDER BY entity_key").use{c->buildMap{while(c.moveToNext())put(c.getString(0),listOf(c.getString(1),SyncV2Codec.hex(SyncV2Codec.sha(c.getBlob(2))),SyncV2Codec.hex(SyncV2Codec.sha(c.getBlob(3)))) )}}
        assertEquals(candidateBefore,candidateAfter)
        val updates=w.sql.query("SELECT kind,COUNT(*) FROM resource_updates GROUP BY kind").use{c->buildMap{while(c.moveToNext())put(c.getString(0),c.getInt(1))}}
        assertEquals(0,updates["business"]?:0);assertEquals(if(choice=="merge")1 else 0,updates["candidate"]?:0);assertTrue((updates["binding"]?:0)<=1)
        val puts=w.f.putOids.drop(putBefore).distinct();val bytes=puts.sumOf{w.f.objects.getValue(it).size.toLong()}
        assertTrue("incremental objects ${puts.size}",puts.size<=8);assertTrue("incremental bytes $bytes",bytes<262144);assertTrue(stagedRows<=4)
        assertTrue("heap samples available",memory.peak.get()>0);assertTrue(memory.peak.get()<=Runtime.getRuntime().maxMemory())
        val evidence=JSONObject().put("platform","JVM/native Room, sdk35; not a physical phone").put("unchangedFolders",count).put("editedNotes",1)
            .put("changedFolderWireRows",0).put("changedFolderCandidates",0).put("businessFolderUpdates",updates["business"]?:0).put("candidateWrites",updates["candidate"]?:0).put("bindingWrites",updates["binding"]?:0)
            .put("stagedRows",stagedRows).put("uploadedControlObjects",puts.size).put("uploadedControlBytes",bytes).put("postApplyScanRows",progress.rows)
            .put("setupMillis",(initial-started)/1000000).put("localEditProjectionMillis",(edited-initial)/1000000).put("authenticatedRollbackSetupMillis",(observed-edited)/1000000).put("comparisonInstrumentationMillis",(beforePreview-observed)/1000000).put("previewMillis",(previewed-beforePreview)/1000000).put("chooseMillis",(committed-previewed)/1000000)
            .put("sampledHeapPeakBytes",memory.peak.get()).put("heapSamplingIntervalMillis",100).put("heapMaxBytes",Runtime.getRuntime().maxMemory()).put("processPeakRss",System.getenv("WRSS_P5_PROC_STATUS")?.takeIf{it.isNotBlank()}?.let{File(it).readLines().firstOrNull{line->line.startsWith("VmHWM:")}}?:JSONObject.NULL).put("physicalBudgetVerified",false)
        println("P5RR_LARGE choice=$choice folders=$count untouchedNotes=$noteCount objects=${puts.size} bytes=$bytes stagedRows=$stagedRows businessUpdates=${updates["business"]?:0}")
        emit("sparse-$count-$choice${if(mixed)"-mixed"else""}.json",evidence.put("choice",choice).put("reads",reads.json()).put("sourceReads",sourceReads.json()).put("untouchedArticles",articles.size).put("untouchedBooks",books.size).put("untouchedNotes",notes.size).put("untouchedRssStates",rss.size))
    }}
    @Test fun sparse12500Local()=large("local")
    @Test fun sparse12500Merge()=large("merge")
    @Test fun mixedOriginalsAndUntouchedNotesAreNotReadForUnrelatedMerge()=large("merge",true)
    @Test fun thousandsOfUnchangedEmptyConflictChannelsAreNotReadOrResealed()=large("merge",count=0,noteCount=2048)

}
