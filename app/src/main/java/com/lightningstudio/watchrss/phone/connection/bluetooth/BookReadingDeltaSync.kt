package com.lightningstudio.watchrss.phone.connection.bluetooth

import com.lightningstudio.watchrss.phone.data.book.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** The strict transport path calls this before reporting a successful applied ACK. */
internal suspend fun validateAndWriteAppliedAck(response:JSONObject,validate:(JSONObject)->Unit,write:()->Throwable?):Boolean {
    currentCoroutineContext().ensureActive()
    validate(response)
    currentCoroutineContext().ensureActive()
    write()?.let { throw IOException("阅读确认回执发送失败，可直接重试同步",it) }
    currentCoroutineContext().ensureActive()
    return true
}

internal class BookReadingDeltaSync(
    private val store:ImportedBookStore,private val deviceId:String,private val peer:String,
    private val exchange:suspend(JSONObject)->JSONObject,
    private val appliedExchange:suspend(JSONObject,(JSONObject)->Unit)->BluetoothSyncExchange,
    private val onApplied:(Int)->Unit={},private val trace:(String)->Unit={}
) {
    private fun request(phase:String)=JSONObject().put("action","syncBooks").put("version",1).put("phase",phase).put("deviceId",deviceId)
    private fun validate(v:JSONObject,phase:String,window:String?=null) {
        require(v.opt("success")==true && v.optString("action")=="syncBooks" && v.opt("version")==1 && v.optString("phase")==phase){"手表阅读增量响应不匹配"}
        if(window!=null)require(v.getString("windowId")==window){"手表阅读窗口已改变"}
    }
    suspend fun sync():Int {
        require(peer.isNotBlank())
        var applied=0
        repeat(3) { round ->
            val start=System.nanoTime();val hashes=store.readingHashCounts
            val local=store.readingSummary()
            trace("reading-summary:round=$round:ms=${(System.nanoTime()-start)/1_000_000}:hashes=${store.readingHashCounts.zip(hashes).sumOf{it.first-it.second}}")
            val p=store.readingPeer(peer)?.takeIf{it.localEpoch==local.epoch}
            val summary=exchange(request("readingSummary").put("summary",local.json())
                .put("knownRemote",p?.let{JSONObject().put("epoch",it.remoteEpoch).put("seq",it.remoteSeq)}?:JSONObject.NULL)
                .put("ackedLocalSeq",p?.localSeq?.takeIf{it<=local.seq}?:0L))
            validate(summary,"readingSummary")
            val remote=BookReadingSummary.read(summary.getJSONObject("summary"))
            val window=summary.getString("windowId").also{require(it.isNotBlank()&&it.length<=256)}
            val from=BookSync.integer(summary,"fromSeq").also{require(it<=remote.seq)}
            val remoteFrom=BookSync.integer(summary,"remoteFromSeq").also{require(it<=local.seq)}
            val equal=summary.get("equal").also{require(it is Boolean)} as Boolean
            require(equal==(local.root==remote.root && local.count==remote.count))
            var sentCount=0;var receivedCount=0
            if(!equal) {
                val outgoing=store.readingEntries(remoteFrom,local.seq)
                var at=0;var after=0L;var batch=0;var more=true;var last=from;var remoteFinished=false
                val keys=hashSetOf<Pair<String,Int>>()
                do {
                    val entries=outgoing.drop(at).take(100);at+=entries.size
                    val page=exchange(request("readingDelta").put("windowId",window).put("batchIndex",batch++)
                        .put("after",after).put("entries",JSONArray(entries.map{it.json()})).put("done",at==outgoing.size))
                    validate(page,"readingDelta",window)
                    BookSync.integer(page,"applied")
                    val rows=page.getJSONArray("entries");require(rows.length()<=100)
                    val incoming=(0 until rows.length()).map { i ->
                        val entry=rows.getJSONObject(i);val seq=BookSync.integer(entry,"seq")
                        require(seq>last && seq<=remote.seq);last=seq
                        entry.getJSONObject("state").also { state ->
                            BookSync.validateReading(state);require(keys.add(state.getString("bookId") to state.getInt("chapterIndex")))
                        }
                    }
                    val next=BookSync.integer(page,"nextAfter");require(next==after+rows.length())
                    more=page.get("hasMore").also{require(it is Boolean)} as Boolean
                    require(!more || incoming.isNotEmpty())
                    require(!remoteFinished || (!more && incoming.isEmpty()))
                    remoteFinished=!more
                    receivedCount+=incoming.size;require(receivedCount<=remote.count)
                    val changed=store.mergeReadingBatch(incoming);applied+=changed;onApplied(changed)
                    after=next;sentCount+=entries.size
                } while(at<outgoing.size || more)
            }
            trace("reading-window:round=$round:equal=$equal:sent=$sentCount:received=$receivedCount")
            val merged=store.readingSummary()
            check(merged.epoch==local.epoch){"本机阅读日志已重建，请重试同步"}
            val commit=request("readingCommit").put("windowId",window).put("receivedSeq",remote.seq).put("sentSeq",local.seq).put("mergedRoot",merged.root)
            val result=appliedExchange(commit) { response ->
                validate(response,"readingCommit",window)
                require(response.get("committed") is Boolean)
                require(BookSync.integer(response,"applied")==0L)
                if(response.getBoolean("committed"))require(response.getString("root")==merged.root)
                else {require(response.opt("retry")==true);BookReadingSummary.read(response.getJSONObject("summary"))}
            }
            check(result.appliedAckWritten){"阅读确认回执未写入，请重试同步"}
            if(result.response.getBoolean("committed")) {
                currentCoroutineContext().ensureActive()
                store.acknowledgeReading(peer,local,remote,merged.root)
                trace("reading-committed:round=$round")
                return applied
            }
            trace("reading-root-retry:round=$round")
        }
        error("阅读状态仍有并发变化，可直接点同步重试")
    }
}
