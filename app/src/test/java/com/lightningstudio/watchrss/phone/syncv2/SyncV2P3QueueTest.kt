package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncV2P3QueueTest {
    @get:Rule val temp=TemporaryFolder()
    private val identity=SyncV2Fixtures.identity()
    private fun row(i:Int)=JSONObject().put("entityKey",i.toString(16).padStart(64,'0')).put("bodyRevision","22".repeat(16)).put("rowSequence","1").put("deleted",false)
    private fun job(i:Int)=SyncV2ContentQueue.Job(row(i),"book",true,i.toLong())
    private fun store(dir:java.io.File,fault:(String)->Unit={})=SyncV2ContentStore(dir,identity.scopeJson,SyncV2Fixtures.localKey,fault=fault)
    @Test fun queueReopensPagedMetadataAndVisitsAllSeventyJobsWithoutScanningBody() {
        val dir=temp.newFolder();val q=SyncV2ContentQueue(store(dir));repeat(70){q.enqueue(job(it+1))}
        val reopened=store(dir);val queue=SyncV2ContentQueue(reopened)
        val first=queue.page();val second=queue.page();assertEquals(64,first.size);assertEquals(6,second.size)
        assertEquals(70,(first+second).map{it.getJSONObject("row").getString("entityKey")}.distinct().size)
        queue.complete(row(1).getString("entityKey"),"22".repeat(16));assertTrue(queue.page().first().getBoolean("complete"))
        assertEquals(0,reopened.counts.sourceBytes)
    }
    @Test fun durableInsertionIntentRecoversAtEveryCheckpointWithoutLosingOrDuplicatingJob() {
        for(boundary in 1..5) {
            val dir=temp.newFolder();var ticks=0
            val queue=SyncV2ContentQueue(store(dir){if(it=="checkpoint" && ++ticks==boundary)throw java.io.IOException("synthetic crash")})
            try{queue.enqueue(job(1))}catch(_:java.io.IOException){}
            val recovered=SyncV2ContentQueue(store(dir));recovered.enqueue(job(1));assertEquals(1,recovered.page().size)
        }
    }
    @Test fun receiptAndFairnessJournalRecoverEveryCheckpointAndCountCompletionOnce() {
        for(boundary in 1..5) {
            val dir=temp.newFolder();var ticks=0;val oid="blob:sha256:"+"ab".repeat(32)
            val faulty=store(dir){if(it=="checkpoint" && ++ticks==boundary)throw java.io.IOException("synthetic crash")}
            try{faulty.completeUpload(oid,JSONObject().put("synthetic",true),0)}catch(_:java.io.IOException){}
            val recovered=store(dir);recovered.recoverUploadCompletion();recovered.completeUpload(oid,JSONObject().put("synthetic",true),0)
            assertEquals(1,recovered.load("fairness")!!.getInt("demanded"));assertNotNull(recovered.load("receipt/$oid"))
            assertFalse(recovered.completeUpload(oid,JSONObject().put("synthetic",true),0))
        }
    }
    @Test fun demandMergesLatestAndExpiresAndNeverPromotesThirdCompletedImportSlot() {
        val q=SyncV2ContentQueue(store(temp.newFolder()));(1..3).forEach{q.enqueue(job(it))};q.complete(row(3).getString("entityKey"),"22".repeat(16))
        val jobs=q.page();q.demand(row(1).getString("entityKey"),"22".repeat(16),100,500,100)
        q.demand(row(1).getString("entityKey"),"22".repeat(16),90,400,100)
        val ranked=q.localPriorities(jobs,emptyList(),100).associate{it.first.getJSONObject("row").getString("entityKey") to it.second}
        assertEquals(0,ranked[row(1).getString("entityKey")]);assertEquals(2,ranked[row(2).getString("entityKey")]);assertFalse(ranked.containsKey(row(3).getString("entityKey")))
        val expired=q.localPriorities(jobs,emptyList(),600).associate{it.first.getJSONObject("row").getString("entityKey") to it.second}
        assertEquals(4,expired[row(1).getString("entityKey")])
    }
}
