package com.lightningstudio.watchrss.phone.connection.bluetooth

import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
import com.lightningstudio.watchrss.phone.connection.ip.IpReadActivity
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.*
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class HotfixR2IndependentTest {
    private fun article(id:String="r2"):PhoneArticleEntity {
        val r=java.util.Random(13132)
        return PhoneArticleEntity(id,"watch","https://example.invalid/$id","independent","site","excerpt",null,
            CharArray(700000){(33+r.nextInt(80)).toChar()}.concatToString(),null,"hash",1,7,false,0,0,null,null,true,2,2,false,0,0,false,0)
    }
    private fun entry(a:PhoneArticleEntity):ArticleSyncManifestEntry {
        val m=ArticleSyncBody.metadataFor(a)
        return ArticleSyncManifestEntry(a.articleId,a.sourceDeviceId,a.contentHash,a.updatedAt,0,a.favoriteChangedAt,0,0,
            bodyHash=m.bodyHash,bodyByteCount=m.bodyByteCount,chunkSize=m.chunkSize,chunkHashes=m.chunkHashes,metadataHash=m.metadataHash,
            readingProgress=a.readingProgress,readingPositionBytes=a.readingPositionBytes,readingPositionContentHash=a.readingPositionContentHash,
            readingPositionChangedAt=a.readingPositionChangedAt,isRead=a.isRead)
    }
    private fun whole(a:PhoneArticleEntity)=ArticleSyncBody.payloadForRequest(a,ArticleBodyRequest(a.articleId,entry(a).bodyHash,entry(a).chunkHashes.indices.toList()))
    private fun context(p:LibraryTransferRecovery,events:MutableList<String> = mutableListOf())=LibraryRecoveryContext(p,LibrarySyncMode.MERGE,{true},{},{},{},{n,_->events+=n},{})

    @Test fun independentSparseBodyUsesActualLocalBytesAndRejectsForgedCachedMetadata()=runBlocking<Unit> {
        val old=article().copy(independentSaved=true)
        val next=old.copy(contentText=old.contentText.dropLast(173)+"different ending".repeat(31),contentHash="next",updatedAt=8)
        val request=LibrarySyncPayload.buildBodyRequestsForRemoteArticles(listOf(entry(old)),listOf(entry(next))).single()
        assertTrue(request.chunkIndexes.size in 1 until entry(next).chunkHashes.size)
        val payload=ArticleSyncBody.payloadForRequest(next,request)
        val root=Files.createTempDirectory("r2-sparse-independent").toFile()
        try {
            var calls=0
            val cp=PhoneArticleReceiveCheckpoint(File(root,"good"),"owner","peer",{old});cp.bind(listOf(entry(next)))
            assertTrue(cp.apply(payload){calls++;assertEquals(next.contentText,ArticleSyncBody.rebuildBody(old,it).second);1})
            assertTrue(cp.apply(payload){fail("duplicate application");0});assertEquals(1,calls)
            val m=ArticleSyncBody.metadataFor(old)
            val corrupt=old.copy(contentText="corrupted",syncBodyHash=m.bodyHash,syncBodyByteCount=m.bodyByteCount,
                syncChunkSize=m.chunkSize,syncChunkHashesJson=org.json.JSONArray(m.chunkHashes).toString(),syncMetadataHash=m.metadataHash)
            val bad=PhoneArticleReceiveCheckpoint(File(root,"bad"),"owner","peer",{corrupt});bad.bind(listOf(entry(next)))
            assertFalse(bad.apply(payload){fail("cached metadata substituted for bytes");0})
        }finally{root.deleteRecursively()}
    }
    @Test fun independentReadingOnlyChangesReachMergeOncePerCompleteState()=runBlocking<Unit> {
        var local=article();val root=Files.createTempDirectory("r2-reading-independent").toFile();var calls=0
        try {
            val cp=PhoneArticleReceiveCheckpoint(root,"owner","peer",{local})
            cp.bind(listOf(entry(local)));assertTrue(cp.apply(whole(local)){calls++;local=it.article;1})
            val states=listOf(local.copy(readingProgress=.3f,readingPositionBytes=321,readingPositionChangedAt=50,readingPositionContentHash="hash"),
                local.copy(readingProgress=.1f,readingPositionBytes=111,readingPositionChangedAt=60,readingPositionContentHash="hash"),
                local.copy(isRead=true,readingPositionChangedAt=70))
            for(next in states) {
                val before=entry(local).copy(bodySyncMode="saved");val after=entry(next).copy(bodySyncMode="saved");assertEquals(before.metadataHash,after.metadataHash);assertEquals(before.updatedAt,after.updatedAt)
                val request=LibrarySyncPayload.buildBodyRequestsForRemoteArticles(listOf(before),listOf(after),supportsMetadataOnlyArticles=true).single()
                assertTrue(request.metadataOnly)
                cp.bind(listOf(after));assertFalse(cp.isApplied(next.articleId))
                val payload=ArticleSyncBody.payloadForRequest(next,request)
                assertTrue(cp.apply(payload){calls++;local=it.article;1});assertEquals(next.readingPositionChangedAt,local.readingPositionChangedAt)
                assertTrue(cp.apply(payload){fail("duplicate reading merge");0})
            }
            assertEquals(4,calls);assertTrue(local.isRead)
        }finally{root.deleteRecursively()}
    }
    @Test fun independentlyConfirmedMetadataOnlyUploadMustResetNoProgress() {
        var now=0L;val p=LibraryTransferRecovery{now};val events=mutableListOf<String>();val c=context(p,events)
        p.observe(1);p.ready(true);now+=6000;p.disconnected(1)
        p.observe(2);p.ready(true);now+=6000;p.disconnected(2);assertEquals(2,p.consecutiveNoProgress)
        val a=article().copy(readingPositionBytes=900,readingProgress=.5f,readingPositionChangedAt=999)
        val remote=entry(a)
        val frames=LibrarySyncPayload.buildChunkedArticleRequestFrames("phone",listOf(a),
            listOf(ArticleBodyRequest(a.articleId,remote.bodyHash,emptyList(),metadataOnly=true)),emptyList(),true)
        val actual=frames.flatMap{LibrarySyncPayload.parseChunkedArticles(it)}.single()
        assertTrue(actual.metadataOnly);assertTrue(actual.chunks.isEmpty())
        frames.forEach(c::sentArticleFrame);assertEquals(2,p.consecutiveNoProgress)
        p.observe(3);p.ready(true)
        // Authenticated next manifest exactly matches the newly sent reading state.
        c.confirmPeerChunks(listOf(remote),emptyList())
        assertEquals("peer confirms new metadata/reading state, yet streak remains",0,p.consecutiveNoProgress)
        now+=6000;p.disconnected(3)
    }
    @Test fun duplicateReadyReceiptsAndWritesDoNotKeepTaskAliveButNewChunkDoes() {
        var now=0L;val p=LibraryTransferRecovery{now};val c=context(p)
        fun drop(g:Long){p.observe(g);p.ready(true);now+=6000;p.disconnected(g)}
        c.confirmedProgress("receiveChunk","a|v|0|hash");drop(1);drop(2)
        p.observe(3);p.ready(true);c.confirmedProgress("receiveChunk","a|v|1|hash2");assertEquals(0,p.consecutiveNoProgress)
        now+=6000;p.disconnected(3)
        drop(4);drop(5);p.observe(6);p.ready(true);c.confirmedProgress("receiveChunk","a|v|1|hash2")
        val a=article();LibrarySyncPayload.buildChunkedArticleRequestFrames("phone",listOf(a),listOf(ArticleBodyRequest(a.articleId,entry(a).bodyHash,emptyList())),emptyList(),true).forEach(c::sentArticleFrame)
        now+=6000;assertThrows(LibraryNoProgressLimit::class.java){p.disconnected(6)}
    }
    @Test fun sustainedOneKiBWritesRefreshIdleDeadlineAfterEachCompletedChunk() {
        val count=AtomicInteger();val closed=AtomicBoolean();val calls=AtomicInteger()
        val sink=object:OutputStream(){override fun write(n:Int){error("bulk expected")}
            override fun write(b:ByteArray,o:Int,n:Int){assertTrue(n<=8192);Thread.sleep(4);count.addAndGet(n);calls.incrementAndGet()}}
        val guard=LibraryIoGuard(1,{true},{closed.set(true)},100)
        val failure=runCatching{guard.output(sink).write(ByteArray(64*8192))}.exceptionOrNull()
        assertNull("completed 1 KiB chunks must extend the idle deadline",failure)
        assertFalse(closed.get());assertEquals(64*8192,count.get());assertEquals(512,calls.get())
    }
    @Test fun independentReadActivityExtendsOnlyItsOwnDeadlineThenSilenceExpires() {
        val activity=IpReadActivity();val end=CountDownLatch(1);val stopped=AtomicBoolean();val closed=AtomicBoolean()
        val source=object:InputStream(){override fun read():Int {check(end.await(3,TimeUnit.SECONDS));throw IOException("closed")}}
        val writer=thread {while(!stopped.get()){activity.input(ByteArrayInputStream(byteArrayOf(1))).read();Thread.sleep(10)}}
        val reader=thread {runCatching{LibraryIoGuard(4,{true},{closed.set(true);end.countDown()},120,
            wireReadActivityNanos={activity.lastNanos}).input(source).read()}}
        try{Thread.sleep(400);assertFalse(closed.get());stopped.set(true);writer.join(500);assertTrue(end.await(1,TimeUnit.SECONDS));reader.join(1000);assertTrue(closed.get())}
        finally{stopped.set(true);end.countDown();writer.join(500);reader.join(1000)}
    }
    @Test fun metadataReceiptRequiresEveryBusinessFieldAndIsNotNewTwice() {
        var now=0L;val p=LibraryTransferRecovery{now};val c=context(p)
        fun drop(g:Long){p.observe(g);p.ready(true);now+=6000;p.disconnected(g)}
        drop(1);drop(2)
        val a=article().copy(readingPositionBytes=900,readingProgress=.5f,readingPositionChangedAt=999,isRead=true)
        val remote=entry(a)
        LibrarySyncPayload.buildChunkedArticleRequestFrames("phone",listOf(a),
            listOf(ArticleBodyRequest(a.articleId,remote.bodyHash,emptyList(),metadataOnly=true)),emptyList(),true).forEach(c::sentArticleFrame)
        p.observe(3);p.ready(true)
        for(bad in listOf(remote.copy(metadataHash="old"),remote.copy(readingPositionBytes=899),
            remote.copy(readingPositionChangedAt=998),remote.copy(readingProgress=.4f),remote.copy(isRead=false),
            remote.copy(readingPositionContentHash="other"),remote.copy(updatedAt=6),remote.copy(bodyHash="other"),
            remote.copy(favoriteChangedAt=3),remote.copy(sourceDeviceId="other"))) {
            c.confirmPeerChunks(listOf(bad),emptyList());assertEquals(2,p.consecutiveNoProgress)
        }
        c.confirmPeerChunks(listOf(remote.copy(bodySyncMode="saved")),emptyList());assertEquals(0,p.consecutiveNoProgress)
        now+=6000;p.disconnected(3);drop(4);drop(5);p.observe(6);p.ready(true)
        c.confirmPeerChunks(listOf(remote),emptyList());c.confirmArticleBatch(setOf(a.articleId))
        assertEquals(2,p.consecutiveNoProgress)
        now+=6000;assertThrows(LibraryNoProgressLimit::class.java){p.disconnected(6)}
    }

    @Test fun rawWriteActivityExtendsBulkAndFlushButRealSilenceStillExpires() {
        val activity=java.util.concurrent.atomic.AtomicLong();val release=CountDownLatch(1);val closed=AtomicBoolean()
        val sink=object:OutputStream(){override fun write(n:Int)=Unit
            override fun write(b:ByteArray,o:Int,n:Int){repeat(n){activity.set(System.nanoTime());Thread.sleep(2)}}
            override fun flush(){repeat(120){activity.set(System.nanoTime());Thread.sleep(2)}}}
        val output=LibraryIoGuard(1,{true},{closed.set(true);release.countDown()},100,
            wireWriteActivityNanos={activity.get()}).output(sink)
        output.write(ByteArray(256));output.flush();assertFalse(closed.get())
        val silent=object:OutputStream(){override fun write(n:Int){release.await(2,TimeUnit.SECONDS);throw IOException("closed")}}
        val failure=runCatching{LibraryIoGuard(2,{true},{closed.set(true);release.countDown()},50,
            wireWriteActivityNanos={activity.get()}).output(silent).write(1)}.exceptionOrNull()
        assertTrue(failure is LibraryTransportFailure);assertTrue(failure!!.cause is java.net.SocketTimeoutException);assertTrue(closed.get())
    }

    @Test fun validationDuringBulkWriteKeepsAccountOwnerAndCancellationTypesAfterPeerClose() {
        for(failure in listOf(IllegalStateException("登录已失效，请重新登录"),IllegalStateException("同步账号已变化，请重新开始同步"),CancellationException("cancel"))) {
            var written=0;var closed=false
            val sink=object:OutputStream(){override fun write(n:Int){written++}
                override fun write(b:ByteArray,o:Int,n:Int){written+=n}}
            val output=LibraryIoGuard(1,{true},{closed=true},1000,
                remotelyClosed={written>=8192},beforeIo={if(written>=8192)throw failure}).output(sink)
            assertSame(failure,runCatching{output.write(ByteArray(9000))}.exceptionOrNull())
            assertEquals(8192,written);assertFalse(closed)
        }
    }

    @Test fun mainLegacyAliasesDoNotHideOtherwiseExactMetadataConfirmation() {
        var now = 0L
        val p = LibraryTransferRecovery { now }
        val c = context(p)
        val a = article().copy(readingPositionBytes = 900, readingPositionChangedAt = 999)
        val remote = entry(a)
        LibrarySyncPayload.buildChunkedArticleRequestFrames("phone", listOf(a),
            listOf(ArticleBodyRequest(a.articleId, remote.bodyHash, emptyList(), metadataOnly = true)), emptyList(), true)
            .forEach(c::sentArticleFrame)
        p.observe(1); p.ready(true); now += 6000; p.disconnected(1)
        assertEquals(1, p.consecutiveNoProgress)
        p.observe(2); p.ready(true)
        c.confirmPeerChunks(listOf(remote.copy(legacyArticleIds = listOf("legacy-id"))), emptyList())
        assertEquals(0, p.consecutiveNoProgress)
    }

}
