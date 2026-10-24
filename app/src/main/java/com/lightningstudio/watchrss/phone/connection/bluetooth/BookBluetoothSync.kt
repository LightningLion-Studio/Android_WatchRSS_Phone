package com.lightningstudio.watchrss.phone.connection.bluetooth

import com.lightningstudio.watchrss.phone.data.book.*
import com.lightningstudio.watchrss.phone.cloud.CloudSnapshotCodec
import org.json.*
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Base64

/** The same immutable package used in WRSS. Exchange is the existing authenticated/paired session. */
internal class BookBluetoothSync(private val store:ImportedBookStore,private val deviceId:String,
    private val staging:File,private val exchange:suspend(JSONObject)->JSONObject) {
    private var deltaPeer:String?=null
    private var appliedExchange:(suspend(JSONObject,(JSONObject)->Unit)->BluetoothSyncExchange)?=null
    var domainFastPath=false
    var onApplied:(Int)->Unit={}
    var trace:(String)->Unit={}
    fun enableReadingDelta(peer:String,exchange:suspend(JSONObject,(JSONObject)->Unit)->BluetoothSyncExchange):BookBluetoothSync {
        require(peer.isNotBlank());deltaPeer=peer;appliedExchange=exchange;return this
    }
    private fun recordApplied(count:Int){if(count>0)onApplied(count)}
    private fun request(phase:String)=JSONObject().put("action","syncBooks").put("version",1).put("deviceId",deviceId).put("phase",phase)
    private suspend fun send(request:JSONObject):JSONObject=exchange(request).also {
        require(it.optBoolean("success")&&it.optString("action")=="syncBooks"&&it.optInt("version")==1) {"手表书籍同步失败"}
    }
    suspend fun sync(syncBooks:Boolean=true,syncReading:Boolean=true):Int {
        val previous=store.onDiagnostic
        store.onDiagnostic={trace(it)}
        return try { syncWindow(syncBooks,syncReading) } finally {store.onDiagnostic=previous}
    }
    private suspend fun syncWindow(syncBooks:Boolean,syncReading:Boolean):Int {
        var applied=0
        var peerReadingDelta=true
        if(syncBooks) {
        var started=System.nanoTime()
        val local=store.manifests()
        trace("manifest-prepared:books=${local.size}:ms=${(System.nanoTime()-started)/1_000_000}")
        val response=send(request("manifest").put("books",JSONArray(local)).put("supportsBookSyncV1",true).put("supportsBookReadingDeltaV1",true))
        peerReadingDelta=response.opt("supportsBookReadingDeltaV1")!=false && response.opt("supportsBookSyncV1")!=false
        started=System.nanoTime()
        require(response.getString("phase")=="manifest")
        val remote=response.getJSONArray("books").let {a->(0 until a.length()).map{a.getJSONObject(it).also(BookSync::validate)}}
        require(remote.map{it.getString("bookId")}.distinct().size==remote.size)
        remote.forEach{val count=store.merge(it);applied+=count;recordApplied(count)}
        trace("manifest-merged:books=${remote.size}:ms=${(System.nanoTime()-started)/1_000_000}")
        started=System.nanoTime()
        val byId=local.associateBy{it.getString("bookId")}
        val missing=response.getJSONArray("missingBookIds")
        for(i in 0 until missing.length()) {
            val id=missing.getString(i);val state=requireNotNull(byId[id]);require(!state.getBoolean("deleted") && state.getBoolean("contentAvailable"))
            val file=requireNotNull(store.packageFile(id));val count=((file.length()+CHUNK-1)/CHUNK).toInt()
            RandomAccessFile(file,"r").use {input->repeat(count){index->val bytes=ByteArray(minOf(CHUNK.toLong(),file.length()-input.filePointer).toInt());input.readFully(bytes)
                val ack=send(request("body").put("bookId",id).put("fingerprint",state.getString("fingerprint")).put("packageSha256",store.packageHash(id))
                    .put("byteCount",file.length()).put("chunkIndex",index).put("chunkCount",count).put("dataBase64",Base64.getEncoder().encodeToString(bytes)))
                require(ack.getString("phase")=="body"&&ack.getBoolean("received"));if(index==count-1)require(ack.getBoolean("complete"))
            }}
        }
        staging.mkdirs()
        for(state in remote.filter{!it.getBoolean("deleted") && it.opt("contentAvailable")==true}) {
            val id=state.getString("bookId");if(store.packageFile(id)!=null)continue
            val tmp=File.createTempFile("book-pull-",".part",staging)
            try {
                var expectedSha="";var expectedSize=0L;var count=1;var index=0
                val digest=MessageDigest.getInstance("SHA-256")
                tmp.outputStream().buffered().use {out->do {
                    val chunk=send(request("pull").put("bookId",id).put("chunkIndex",index))
                    require(chunk.getString("phase")=="body"&&chunk.getString("bookId")==id&&chunk.getString("fingerprint")==state.getString("fingerprint")&&chunk.getInt("chunkIndex")==index)
                    if(index==0){expectedSha=chunk.getString("packageSha256");expectedSize=BookSync.integer(chunk,"byteCount");count=chunk.getInt("chunkCount");require(expectedSha.matches(Regex("[0-9a-f]{64}"))&&expectedSize in 1..536870912L&&count==((expectedSize+CHUNK-1)/CHUNK).toInt())}
                    require(chunk.getString("packageSha256")==expectedSha&&chunk.getLong("byteCount")==expectedSize&&chunk.getInt("chunkCount")==count)
                    val data=Base64.getDecoder().decode(chunk.getString("dataBase64"));require(data.size==minOf(CHUNK.toLong(),expectedSize-index.toLong()*CHUNK).toInt())
                    digest.update(data);out.write(data);index++
                }while(index<count)}
                require(tmp.length()==expectedSize&&digest.digest().joinToString(""){"%02x".format(it)}==expectedSha)
                store.receivePackage(id,tmp).let{applied+=it;recordApplied(it)}
            } finally {tmp.delete()}
        }
        trace("packages-ready:ms=${(System.nanoTime()-started)/1_000_000}")
        if(domainFastPath && missing.length()>0) {
            val confirmed=send(request("manifest").put("books",JSONArray(store.manifests())).put("supportsBookSyncV1",true).put("supportsBookReadingDeltaV1",true))
            require(confirmed.optString("phase")=="manifest"&&confirmed.getJSONArray("missingBookIds").length()==0){"手表未确认书籍落盘"}
        }
        }
        if(!syncReading)return applied
        if(deltaPeer!=null && peerReadingDelta) {
            return applied+BookReadingDeltaSync(store,deviceId,requireNotNull(deltaPeer),::send,requireNotNull(appliedExchange),onApplied,trace).sync()
        }
        val localReading=store.readingStates();var localOffset=0;var remoteOffset=0L;var more=true
        do {
            val batch=localReading.drop(localOffset).take(100);localOffset+=batch.size
            val page=send(request("reading").put("states",JSONArray(batch)).put("after",remoteOffset))
            require(page.getString("phase")=="reading")
            val rows=page.getJSONArray("states");require(rows.length()<=100)
            val incoming=(0 until rows.length()).map{rows.getJSONObject(it).also(BookSync::validateReading)}
            more=page.getBoolean("hasMore");val next=BookSync.integer(page,"nextAfter");require(next>=remoteOffset&&(!more||next>remoteOffset))
            store.mergeReadingBatch(incoming).let{applied+=it;recordApplied(it)};remoteOffset=next
        }while(localOffset<localReading.size||more)
        return applied
    }
    companion object {const val CHUNK=65536}
}
