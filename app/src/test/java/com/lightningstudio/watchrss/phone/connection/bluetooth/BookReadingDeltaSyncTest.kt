package com.lightningstudio.watchrss.phone.connection.bluetooth

import android.content.Context
import androidx.room.Room
import com.lightningstudio.watchrss.phone.data.book.*
import com.lightningstudio.watchrss.phone.data.db.PhoneCompanionDatabase
import kotlinx.coroutines.*
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.*

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class BookReadingDeltaSyncTest {
    private val context:Context=RuntimeEnvironment.getApplication()
    private val dbs=mutableListOf<PhoneCompanionDatabase>()
    private fun store():ImportedBookStore {
        val db=Room.inMemoryDatabaseBuilder(context,PhoneCompanionDatabase::class.java).allowMainThreadQueries().build().also{dbs+=it}
        return ImportedBookStore(db,File(context.cacheDir,"delta-${System.nanoTime()}"),null,"phone")
    }
    private val id="a".repeat(64)
    private fun state(index:Int=1,clock:Long=100,source:String="phone")=JSONObject().put("bookId",id).put("chapterIndex",index).put("isRead",true)
        .put("readingProgress",0.5).put("readingPositionBytes",42).put("readingPositionContentHash","text").put("readingPositionChangedAt",clock).put("sourceDeviceId",source)
    private fun copy(v:JSONObject)=JSONObject(v.toString())
    @After fun close(){dbs.forEach{it.close()}}
    private inner class Peer(initial:List<JSONObject> = emptyList()) {
        val requests=mutableListOf<JSONObject>();val values=linkedMapOf<Pair<String,Int>,BookReadingEntry>()
        var seq=0L;var serial=0;val epoch="f".repeat(32)
        var failCommits=0;var loseAck=false;var omitAckEvidence=false;var ackWrites=0;var strictFailures=0
        var fromOverride:Long?=null;var remoteFromOverride:Long?=null
        var onSummary:suspend()->Unit={};var afterAck:suspend()->Unit={};var mutate:(JSONObject)->JSONObject={it}
        private var frozen:BookReadingSummary?=null;private var outgoing=emptyList<BookReadingEntry>()
        init {initial.forEach(::merge)}
        private fun key(v:JSONObject)=v.getString("bookId") to v.getInt("chapterIndex")
        private fun merge(v:JSONObject) {
            val k=key(v);val old=values[k];val next=BookSync.mergeReading(old?.state,v)
            if(old==null||BookReadingHash.leaf(old.state)!=BookReadingHash.leaf(next))values[k]=BookReadingEntry(++seq,next)
        }
        private fun summary():BookReadingSummary {
            val books=values.values.groupBy{it.state.getString("bookId")}.map{(book,states)->BookReadingRootEntity(book,BookReadingHash.book(book,states.map{it.state.getInt("chapterIndex") to BookReadingHash.leaf(it.state)}),states.size.toLong())}
            return BookReadingSummary(epoch,seq,BookReadingHash.root(books),values.size.toLong())
        }
        private fun base(phase:String)=JSONObject().put("success",true).put("action","syncBooks").put("version",1).put("phase",phase)
        suspend fun exchange(q:JSONObject):JSONObject {
            requests+=copy(q)
            val phase=q.getString("phase")
            val response=when(phase) {
                "readingSummary" -> {
                    val local=BookReadingSummary.read(q.getJSONObject("summary"));val remote=summary();frozen=remote;serial++
                    val from=fromOverride?:0L;val remoteFrom=remoteFromOverride?:0L
                    outgoing=values.values.filter{it.seq>from && it.seq<=remote.seq}.sortedBy{it.seq}.map{BookReadingEntry(it.seq,copy(it.state))}
                    onSummary()
                    base(phase).put("windowId","window-$serial").put("summary",remote.json()).put("fromSeq",from).put("remoteFromSeq",remoteFrom)
                        .put("equal",local.root==remote.root && local.count==remote.count)
                }
                "readingDelta" -> {
                    assertEquals("window-$serial",q.getString("windowId"))
                    val entries=q.getJSONArray("entries");assertTrue(entries.length()<=100)
                    for(i in 0 until entries.length())merge(entries.getJSONObject(i).getJSONObject("state"))
                    val after=q.getInt("after");val batch=outgoing.drop(after).take(100)
                    base(phase).put("windowId","window-$serial").put("entries",JSONArray(batch.map{it.json()})).put("nextAfter",after+batch.size)
                        .put("hasMore",after+batch.size<outgoing.size).put("applied",0)
                }
                "readingCommit" -> {
                    assertEquals(frozen!!.seq,q.getLong("receivedSeq"))
                    val current=summary();val match=current.root==q.getString("mergedRoot") && failCommits--<=0
                    base(phase).put("windowId","window-$serial").put("committed",match).put("applied",0).apply {
                        if(match)put("root",current.root)else put("retry",true).put("summary",current.json())
                    }
                }
                else -> error(phase)
            }
            return mutate(response)
        }
        suspend fun applied(q:JSONObject,validate:(JSONObject)->Unit):BluetoothSyncExchange {
            val v=exchange(q)
            val acked=try{validateAndWriteAppliedAck(v,validate){ackWrites++;if(loseAck)IOException("socket closed")else null}}
                catch(e:Throwable){strictFailures++;throw e}
            afterAck()
            return BluetoothSyncExchange("watch","address",q,v,acked&&!omitAckEvidence)
        }
        fun client(s:ImportedBookStore,onApplied:(Int)->Unit={})=BookReadingDeltaSync(s,"phone","watch",::exchange,::applied,onApplied)
    }
    @Test fun equalRootsSendOnlySummaryCommitAndPersistBothWatermarksAfterAck()=runBlocking<Unit> {
        val s=store();s.mergeReading(state());val initial=s.readingSummary();val peer=Peer(listOf(state()))
        assertEquals(0,peer.client(s).sync())
        assertEquals(listOf("readingSummary","readingCommit"),peer.requests.map{it.getString("phase")})
        assertEquals(1,peer.ackWrites);val saved=s.readingPeer("watch")!!
        assertEquals(initial.seq,saved.localSeq);assertEquals(1L,saved.remoteSeq);assertEquals(initial.root,saved.root)
        assertEquals(initial,s.readingSummary())
    }
    @Test fun oneChangedKeyAfterAckSendsOnlyThatKeyIncludingUnreadAndListCancellation()=runBlocking<Unit> {
        for(change in listOf<(JSONObject)->JSONObject>(
            {it.put("isRead",false).put("readingPositionChangedAt",200)},
            {it.put("favoriteSaved",false).put("favoriteChangedAt",200).put("favoriteSortOrder",0).put("favoriteSourceDeviceId","favorite")},
            {it.put("watchLaterSaved",false).put("watchLaterChangedAt",300).put("watchLaterSortOrder",0).put("watchLaterSourceDeviceId","later")})) {
            val s=store();s.mergeReading(state());val old=s.readingSummary()
            val remote=BookReadingSummary("f".repeat(32),1,old.root,old.count);s.acknowledgeReading("watch",old,remote,old.root)
            s.mergeReading(change(state()));val peer=Peer(listOf(state()));peer.fromOverride=1;peer.remoteFromOverride=old.seq
            peer.client(s).sync();val deltas=peer.requests.filter{it.getString("phase")=="readingDelta"}
            assertEquals(1,deltas.size);assertEquals(1,deltas.single().getJSONArray("entries").length())
            val summary=peer.requests.first();assertEquals(1,summary.getInt("ackedLocalSeq"));assertEquals(remote.epoch,summary.getJSONObject("knownRemote").getString("epoch"))
        }
    }
    @Test fun bidirectional101PagesAreAtomicAndAckOnlyFrozenPrefixNotMergedNewSeq()=runBlocking<Unit> {
        val s=store();(102..202).chunked(100).forEach{batch->s.mergeReadingBatch(batch.map{state(it,source="phone")})}
        val frozen=s.readingSummary();val peer=Peer((1..101).map{state(it,source="watch")})
        assertTrue(peer.client(s).sync()>0)
        val pages=peer.requests.filter{it.getString("phase")=="readingDelta"}
        assertEquals(listOf(100,1),pages.map{it.getJSONArray("entries").length()});assertEquals(listOf(0,100),pages.map{it.getInt("after")})
        assertEquals(listOf(0,1),pages.map{it.getInt("batchIndex")});assertFalse(pages[0].getBoolean("done"));assertTrue(pages[1].getBoolean("done"))
        assertEquals(202L,s.readingSummary().count);assertEquals(frozen.seq,s.readingPeer("watch")!!.localSeq);assertTrue(s.readingSummary().seq>frozen.seq)
    }
    @Test fun localDoneStillSendsEmptyPagesToFetchRemainingRemoteStatesAndMissingStatesDoNotDelete()=runBlocking<Unit> {
        val s=store();s.mergeReading(state(500));val peer=Peer((1..201).map{state(it,source="watch")})
        peer.client(s).sync();val pages=peer.requests.filter{it.getString("phase")=="readingDelta"}
        assertEquals(listOf(1,0,0),pages.map{it.getJSONArray("entries").length()});assertTrue(pages.all{it.getBoolean("done")})
        assertEquals(202L,s.readingSummary().count)
    }
    @Test fun lostAckDoesNotSaveCursorAndReconnectUsesNewWindowWithIdempotentStates()=runBlocking<Unit> {
        val s=store();val peer=Peer(listOf(state(source="watch")));peer.loseAck=true
        assertTrue(runCatching{peer.client(s).sync()}.exceptionOrNull() is IOException);assertNull(s.readingPeer("watch"))
        val first=s.readingSummary();peer.loseAck=false;assertEquals(0,peer.client(s).sync());assertEquals(2,peer.serial)
        assertEquals(first,s.readingSummary());assertNotNull(s.readingPeer("watch"))
    }
    @Test fun missingAckSuccessEvidenceCannotAdvanceEvenForValidCommittedResponse()=runBlocking<Unit> {
        val s=store();val peer=Peer();peer.omitAckEvidence=true
        assertTrue(runCatching{peer.client(s).sync()}.isFailure);assertNull(s.readingPeer("watch"))
    }
    @Test fun rootMismatchRetriesAtMostTwoNewSummariesAndKeepsActualChangesVisibleOnFailure()=runBlocking<Unit> {
        val s=store();val peer=Peer(listOf(state(source="watch")));peer.failCommits=100;var actual=0
        assertTrue(runCatching{peer.client(s){actual+=it}.sync()}.isFailure)
        assertEquals(3,peer.serial);assertEquals(3,peer.ackWrites);assertNull(s.readingPeer("watch"));assertTrue(actual>0)
        peer.failCommits=0;peer.client(s).sync();assertNotNull(s.readingPeer("watch"))
    }
    @Test fun postWindowLocalEditIsNotSentWithOldSeqAndConvergesNextSummary()=runBlocking<Unit> {
        val s=store();s.mergeReading(state());val peer=Peer();var once=true
        peer.onSummary={if(once){once=false;s.mergeReading(state(clock=200).put("isRead",false))}}
        peer.client(s).sync()
        val first=peer.requests.first{it.getString("phase")=="readingDelta"}.getJSONArray("entries").getJSONObject(0)
        assertEquals(100,first.getJSONObject("state").getInt("readingPositionChangedAt"));assertTrue(first.getJSONObject("state").getBoolean("isRead"))
        assertEquals(2,peer.serial);assertFalse(s.readingEntries(0,s.readingSummary().seq).single().state.getBoolean("isRead"))
    }
    @Test fun corruptCommitPhaseVersionWindowRootOrFlagIsRejectedBeforeAppliedAck()=runBlocking<Unit> {
        for(mutation in listOf<(JSONObject)->JSONObject>(
            {it.put("phase","readingDelta")},{it.put("version",2)},{it.put("windowId","old")},{it.put("root","0".repeat(64))},
            {it.put("success",false)},{it.put("committed","true")},{it.put("applied",1)})) {
            val s=store();val peer=Peer();peer.mutate={v->if(v.getString("phase")=="readingCommit")mutation(v)else v}
            assertTrue(runCatching{peer.client(s).sync()}.isFailure);assertEquals(0,peer.ackWrites);assertNull(s.readingPeer("watch"))
        }
    }
    @Test fun invalidDeltaSequenceOffsetOrFalseProgressFailsWithoutCursorOrDeletion()=runBlocking<Unit> {
        for(mutation in listOf<(JSONObject)->JSONObject>(
            {it.put("nextAfter",5)},{it.put("hasMore",true).put("entries",JSONArray()).put("nextAfter",0)},
            {it.apply{getJSONArray("entries").getJSONObject(0).put("seq",2)}},
            {it.apply{getJSONArray("entries").getJSONObject(0).put("seq",0)}})) {
            val s=store();s.mergeReading(state(2));val peer=Peer(listOf(state(source="watch")))
            peer.mutate={v->if(v.getString("phase")=="readingDelta")mutation(v)else v}
            assertTrue(runCatching{peer.client(s).sync()}.isFailure);assertNull(s.readingPeer("watch"));assertEquals(1L,s.readingSummary().count)
        }
    }
    @Test fun changedLocalEpochSuppressesPersistedKnownRemoteAndAckedLocalSeq()=runBlocking<Unit> {
        val s=store();val first=s.readingSummary();s.acknowledgeReading("watch",first,first,first.root);s.clear()
        val peer=Peer();peer.client(s).sync();assertTrue(peer.requests.first().isNull("knownRemote"));assertEquals(0,peer.requests.first().getInt("ackedLocalSeq"))
    }
    @Test fun newRemoteEpochRepairsFromZeroAndReplacesOnlyItsAckedNamespace()=runBlocking<Unit> {
        val s=store();s.mergeReading(state());val local=s.readingSummary()
        s.acknowledgeReading("watch",local,BookReadingSummary("e".repeat(32),99,local.root,1),local.root)
        val peer=Peer(listOf(state(clock=200,source="watch")));peer.client(s).sync()
        assertEquals("e".repeat(32),peer.requests.first().getJSONObject("knownRemote").getString("epoch"))
        assertEquals("f".repeat(32),s.readingPeer("watch")!!.remoteEpoch);assertEquals(1L,s.readingPeer("watch")!!.remoteSeq)
    }
    @Test fun outOfBoundsSummaryWatermarksOrLocalEpochChangeCannotCommitOldPrefix()=runBlocking<Unit> {
        for(localSide in listOf(false,true)) {
            val s=store();val peer=Peer()
            if(localSide)peer.remoteFromOverride=1 else peer.fromOverride=1
            assertTrue(runCatching{peer.client(s).sync()}.isFailure);assertNull(s.readingPeer("watch"));assertEquals(0,peer.ackWrites)
        }
        val s=store();val peer=Peer();peer.onSummary={s.clear()}
        assertTrue(runCatching{peer.client(s).sync()}.isFailure);assertNull(s.readingPeer("watch"));assertEquals(0,peer.ackWrites)
    }
    @Test fun postAckNewLocalStateKeepsItsSeqAboveCommittedPrefix()=runBlocking<Unit> {
        val s=store();s.mergeReading(state());val first=s.readingSummary();val peer=Peer(listOf(state()))
        peer.afterAck={s.mergeReading(state(clock=200).put("isRead",false))}
        peer.client(s).sync();assertEquals(first.seq,s.readingPeer("watch")!!.localSeq)
        val current=s.readingSummary();assertTrue(current.seq>first.seq);assertFalse(s.readingEntries(first.seq,current.seq).single().state.getBoolean("isRead"))
    }
    @Test fun peerCannotResumeEntriesAfterDeclaringItsWindowFinished()=runBlocking<Unit> {
        val s=store();(102..202).chunked(100).forEach{batch->s.mergeReadingBatch(batch.map{state(it)})}
        val peer=Peer((1..101).map{state(it,source="watch")});var page=0
        peer.mutate={v->if(v.getString("phase")=="readingDelta" && page++==0)v.put("hasMore",false)else v}
        assertTrue(runCatching{peer.client(s).sync()}.isFailure);assertNull(s.readingPeer("watch"));assertEquals(0,peer.ackWrites)
    }
    @Test fun oldPeerOrExplicitManifestDeclineKeepsOriginalReadingPagination()=runBlocking<Unit> {
        for(decline in listOf(false,true)) {
            val s=store();s.mergeReading(state());val phases=mutableListOf<String>();val peer=Peer()
            val sync=BookBluetoothSync(s,"phone",context.cacheDir) { q ->
                val phase=q.getString("phase");phases+=phase
                when(phase) {
                    "manifest" -> JSONObject().put("success",true).put("action","syncBooks").put("version",1).put("phase",phase)
                        .put("books",JSONArray()).put("missingBookIds",JSONArray()).apply{if(decline)put("supportsBookReadingDeltaV1",false)}
                    "reading" -> JSONObject().put("success",true).put("action","syncBooks").put("version",1).put("phase",phase)
                        .put("states",JSONArray()).put("hasMore",false).put("nextAfter",0)
                    else -> error("unexpected new phase $phase")
                }
            }
            if(decline)sync.enableReadingDelta("watch",peer::applied)
            sync.sync();assertEquals(listOf("manifest","reading"),phases);assertTrue(peer.requests.isEmpty())
        }
    }
    @Test fun negotiationIsExplicitAndOldFlagsCannotLeakAcrossConnections() {
        assertFalse(BleSyncCapabilities.read(JSONObject().put("version",14)).bookReadingDelta)
        assertTrue(BleSyncCapabilities.read(JSONObject().advertiseBleSyncV2()).bookReadingDelta)
        assertFalse(BleSyncCapabilities.read(JSONObject().put("supportsBookReadingDeltaV1","true")).bookReadingDelta)
        assertFalse(BleSyncCapabilities.read(JSONObject().advertiseBleSyncV2()).withDeclarations(JSONObject().put("supportsBookReadingDeltaV1",false)).bookReadingDelta)
    }
}
