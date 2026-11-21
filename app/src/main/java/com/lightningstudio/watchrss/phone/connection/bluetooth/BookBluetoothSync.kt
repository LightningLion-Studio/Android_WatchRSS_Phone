package com.lightningstudio.watchrss.phone.connection.bluetooth

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.lightningstudio.watchrss.phone.data.book.*
import com.lightningstudio.watchrss.phone.cloud.CloudSnapshotCodec
import org.json.*
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Base64

/** Bounded display title is authorized for local diagnostics; never body, URL or exception text. */
data class BookSyncIssue(val bookIdPrefix:String,val code:String,val title:String?=null,val reason:String=code) {
    internal companion object {
        fun displayTitle(raw:Any?):String?=(raw as? String)?.replace(Regex("[\\p{Cc}\\p{Cf}]")," ")?.trim()?.take(120)?.takeIf{it.isNotBlank()}
    }
}

/** The same immutable package used in WRSS. Exchange is the existing authenticated/paired session. */
internal class BookBluetoothSync(private val store:ImportedBookStore,private val deviceId:String,
    private val staging:File,private val exchange:suspend(JSONObject)->JSONObject) {
    private var deltaPeer:String?=null
    private var appliedExchange:(suspend(JSONObject,(JSONObject)->Unit)->BluetoothSyncExchange)?=null
    private val titles=mutableMapOf<String,String>()
    private val rejected=linkedSetOf<String>()
    private val itemIssues=mutableListOf<BookSyncIssue>()
    val issues:List<BookSyncIssue> get()=itemIssues.toList()
    val booksComplete:Boolean get()=itemIssues.isEmpty()
    var diagnosticItem:(String,String,String)->Unit={_,_,_->}
    var onIssue:(BookSyncIssue)->Unit={}
    private fun reject(id:String,code:String,reason:String=code) {
        if(!rejected.add(id))return
        val issue=BookSyncIssue(id.takeIf{it.matches(Regex("[0-9a-f]{64}"))}?.take(8)?:"invalid",code,titles[id],reason)
        itemIssues+=issue;trace("book-item-isolated:id=${issue.bookIdPrefix}:code=$code:reason=$reason");onIssue(issue)
    }
    var checkOwner: () -> Unit = {}
    var admitOwnerCommit: () -> Unit = {}
    var rollbackWindow: () -> String? = { null }
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
        rejected.clear();itemIssues.clear();titles.clear()
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
        local.forEach{state->BookSyncIssue.displayTitle(state.opt("title"))?.let{titles[state.getString("bookId")]=it}}
        trace("manifest-prepared:books=${local.size}:ms=${(System.nanoTime()-started)/1_000_000}")
        val response=send(request("manifest").put("books",JSONArray(local)).put("supportsBookSyncV1",true).put("supportsBookReadingDeltaV1",true))
        peerReadingDelta=response.opt("supportsBookReadingDeltaV1")!=false && response.opt("supportsBookSyncV1")!=false
        started=System.nanoTime()
        require(response.getString("phase")=="manifest")
        val candidates=response.getJSONArray("books").let {a->(0 until a.length()).mapNotNull{index->
            val raw=a.optJSONObject(index)
            val id=raw?.optString("bookId").orEmpty()
            BookSyncIssue.displayTitle(raw?.opt("title"))?.let{titles.putIfAbsent(id,it)}
            try {validateBookPackage{if(raw==null)throw InvalidBookPackageException("manifest_not_object");raw.also(BookSync::validate)}}
            catch(e:InvalidBookPackageException){reject(id,"book_manifest_invalid",e.reason);null}
        }}
        candidates.groupBy{it.getString("bookId")}.filterValues{it.size>1}.keys.forEach{reject(it,"book_manifest_invalid","manifest_duplicate_book_id")}
        val remote=candidates.filter{it.getString("bookId") !in rejected}
        var metadataApplied=0
        remote.forEach { state ->
            try {val count=store.merge(state);metadataApplied+=count;applied+=count;recordApplied(count)}
            catch(e:InvalidBookPackageException){reject(state.getString("bookId"),"book_manifest_invalid",e.reason)}
        }
        trace("manifest-merged:books=${remote.size}:applied=$metadataApplied:ms=${(System.nanoTime()-started)/1_000_000}")
        started=System.nanoTime()
        val byId=local.associateBy{it.getString("bookId")}
        val missing=response.getJSONArray("missingBookIds")
        for(i in 0 until missing.length()) {
            val id=missing.optString(i);if(id in rejected)continue
            val state=byId[id]
            if(state==null || state.getBoolean("deleted") || !state.getBoolean("contentAvailable")){
                reject(id,"book_manifest_invalid",when {state==null->"manifest_requested_unknown_book";state.getBoolean("deleted")->"manifest_requested_deleted_book";else->"manifest_requested_body_unavailable"});continue
            }
            val (file, packageHash) = requireNotNull(store.packageForSync(id))
            val count = ((file.length() + CHUNK - 1) / CHUNK).toInt()
            RandomAccessFile(file,"r").use {input->repeat(count){index->val bytes=ByteArray(minOf(CHUNK.toLong(),file.length()-input.filePointer).toInt());input.readFully(bytes)
                val ack=send(request("body").put("bookId",id).put("fingerprint",state.getString("fingerprint")).put("packageSha256",packageHash)
                    .put("byteCount",file.length()).put("chunkIndex",index).put("chunkCount",count).put("dataBase64",Base64.getEncoder().encodeToString(bytes)))
                require(ack.getString("phase")=="body"&&ack.getBoolean("received"))
                if(index==count-1)require(ack.getBoolean("complete"))
                currentCoroutineContext()[LibrarySyncRecovery]?.confirmedProgress("sendBookChunk", "$id|$packageHash|$index")
            }}
        }
        staging.mkdirs()
        for(state in remote.filter{it.getString("bookId") !in rejected && !it.getBoolean("deleted") && it.opt("contentAvailable")==true}) {
            val id=state.getString("bookId");if(store.packageFile(id)!=null)continue
            val tmp=File.createTempFile("book-pull-",".part",staging)
            try {
                var expectedSha="";var expectedSize=0L;var count=1;var index=0
                val digest=MessageDigest.getInstance("SHA-256")
                tmp.outputStream().buffered().use {out->do {
                    val chunk=send(request("pull").put("bookId",id).put("chunkIndex",index))
                    validateBookPackage {
                    require(chunk.getString("phase")=="body"&&chunk.getString("bookId")==id&&chunk.getString("fingerprint")==state.getString("fingerprint")&&chunk.getInt("chunkIndex")==index)
                    if(index==0){expectedSha=chunk.getString("packageSha256");expectedSize=BookSync.integer(chunk,"byteCount");count=chunk.getInt("chunkCount");require(expectedSha.matches(Regex("[0-9a-f]{64}"))&&expectedSize in 1..536870912L&&count==((expectedSize+CHUNK-1)/CHUNK).toInt())}
                    require(chunk.getString("packageSha256")==expectedSha&&chunk.getLong("byteCount")==expectedSize&&chunk.getInt("chunkCount")==count)
                    val data=Base64.getDecoder().decode(chunk.getString("dataBase64"));require(data.size==minOf(CHUNK.toLong(),expectedSize-index.toLong()*CHUNK).toInt())
                    data
                    }.also {data->digest.update(data);out.write(data);index++}
                }while(index<count)}
                validateBookPackage {require(tmp.length()==expectedSize&&digest.digest().joinToString(""){"%02x".format(it)}==expectedSha)}
                val owner = currentCoroutineContext()
                val revision = BookPackageFiles.Revision(expectedSha, expectedSize)
                store.receiveVerifiedPackage(id, tmp, revision, admitOwnerCommit) {
                    owner.ensureActive()
                    checkOwner()
                }.let { applied += it; recordApplied(it) }
            } catch(e:InvalidBookPackageException) {reject(id,"book_package_invalid",e.reason)}
            finally {tmp.delete()}
        }
        for(id in (remote.asSequence()+local.asSequence()).map{it.getString("bookId")}.filter{it !in rejected}.distinct())diagnosticItem(id,"applied","none")
        trace("packages-ready:ms=${(System.nanoTime()-started)/1_000_000}")
        if(domainFastPath && booksComplete && missing.length()>0) {
            val confirmed=send(request("manifest").put("books",JSONArray(store.manifests())).put("supportsBookSyncV1",true).put("supportsBookReadingDeltaV1",true))
            require(confirmed.optString("phase")=="manifest"&&confirmed.getJSONArray("missingBookIds").length()==0){"手表未确认书籍落盘"}
        }
        }
        if(!syncReading)return applied
        if(deltaPeer!=null && peerReadingDelta) {
            return applied+BookReadingDeltaSync(store,deviceId,requireNotNull(deltaPeer),::send,requireNotNull(appliedExchange),onApplied,trace,
                rollbackWindow, checkOwner, admitOwnerCommit).sync()
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
