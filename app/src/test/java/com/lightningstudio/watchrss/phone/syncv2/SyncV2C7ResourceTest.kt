package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Spec 25.4/25.9 resource bounds: 2047/2048/2049 segment sweeps stream; over-budget fails bounded. */
class SyncV2C7ResourceTest {
    private val entityKey="ee".repeat(32)
    private val scope=SyncV2Fixtures.identity().scope
    private fun source(totalBytes:Long)=SyncV2NoteSegments.NoteTextSource.replayed(totalBytes){
        sequence{var left=totalBytes
            while(left>0L){val n=minOf(left,8192L);yield("a".repeat(n.toInt()));left-=n}}
    }
    private fun encode(totalBytes:Long,noteRevision:String?,maxStreamBytes:Long)=SyncV2NoteSegments.encode(
        entityKey,JSONObject().put("contentHash","ab".repeat(32)),
        LinkedHashMap<String,SyncV2NoteSegments.NoteTextSource>().apply{put("markdown",source(totalBytes))},
        JSONArray(),noteRevision,scope,maxStreamBytes,null)

    @Test fun budgetClassificationOverTwoMiBStopsProportionalToTheBudgetNotTheBody() {
        // A remote 3 MiB note is rejected with storage_pressure after scanning only budget-sized bytes.
        val started=System.nanoTime();val usedBefore=usedHeap()
        val error=runCatching{encode(3L*1024*1024,"ab".repeat(16),SyncV2NoteSegments.maxNoteBytes)}.exceptionOrNull()
        val took=(System.nanoTime()-started)/1_000_000;val growth=usedHeap()-usedBefore
        assertEquals("storage_pressure",(error as? IllegalStateException)?.message)
        assertTrue("budget rejection must stop near the budget (${took}ms)",took<2500)
        assertTrue("budget rejection must not buffer the body (${growth/1024/1024}MiB)",growth<64L*1024*1024)
        SyncV2Fixtures.evidence("c7-resource-budget.json",JSONObject().put("millis",took).put("heapDeltaMiB",growth/1024/1024))
    }

    @Test fun segmentSweepAtTheMaximumStaysStreamingAndBounded() {
        val per=SyncV2NoteSegments.TEXT.toLong()
        for(count in listOf(2047L,2048L)) {
            // The token stream carries the J(field) punctuation, so N full TEXT chunks yield N+1 segments.
            val raw=per*(count-1)
            val started=System.nanoTime();val usedBefore=usedHeap()
            val encoded=encode(raw,"ab".repeat(16),Long.MAX_VALUE)
            val took=(System.nanoTime()-started)/1_000_000;val growth=usedHeap()-usedBefore
            assertEquals("segmented",encoded.form)
            assertEquals(count,encoded.segmentCount.toLong())
            assertTrue("${count} segments must encode well under the round budget (${took}ms)",took<60_000)
            assertTrue("segmentation must not materialize the body (heap grew ${growth/1024/1024}MiB)",growth<160L*1024*1024)
            SyncV2Fixtures.evidence("c7-resource-$count.json",JSONObject().put("millis",took).put("heapDeltaMiB",growth/1024/1024)
                .put("streamBytes",encoded.streamBytes).put("closureUpperBound",encoded.closureStoredBytesUpperBound))
        }
        // One segment beyond the ceiling is rejected while still streaming.
        val started=System.nanoTime();val usedBefore=usedHeap()
        val error=runCatching{encode(per*2048L,"ab".repeat(16),Long.MAX_VALUE)}.exceptionOrNull()
        val took=(System.nanoTime()-started)/1_000_000;val growth=usedHeap()-usedBefore
        assertEquals("object_too_large",(error as? IllegalStateException)?.message)
        assertTrue("over-maximum rejection stays bounded (${took}ms/${growth/1024/1024}MiB)",took<60_000&&growth<160L*1024*1024)
        SyncV2Fixtures.evidence("c7-resource-2049.json",JSONObject().put("millis",took).put("heapDeltaMiB",growth/1024/1024))
    }

    private fun usedHeap():Long {
        val r=Runtime.getRuntime()
        repeat(3){System.gc();Thread.sleep(25)}
        return r.totalMemory()-r.freeMemory()
    }
}
