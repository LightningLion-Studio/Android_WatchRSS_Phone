package com.lightningstudio.watchrss.phone.connection.bluetooth

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.*
import java.net.ProtocolException
import java.net.SocketTimeoutException

class LibraryTransferRecoveryTest {
    @Test fun ownerFailureBeforeIoCannotBeReclassifiedByConcurrentIpClose() {
        val owner=IllegalStateException("account changed");var writes=0
        val output=object:OutputStream(){override fun write(value:Int){writes++}}
        val guard=LibraryIoGuard(1,{true},{},remotelyClosed={true},beforeIo={throw owner})
        assertSame(owner,runCatching{guard.output(output).write(1)}.exceptionOrNull())
        assertEquals(0,writes)
    }
    @Test fun retryLimitsShowTheirChineseReasonWithoutImplementationClassNames() {
        for (failure in listOf(LibraryReconnectLimit(), LibraryDisconnectLimit(), LibraryNoProgressLimit())) {
            assertEquals(failure.message, com.lightningstudio.watchrss.phone.viewmodel.syncFailureMessage(failure, "信息传输中"))
        }
    }
    @Test fun sixSecondEofLoopStopsAfterThirdReadyWithoutProgress()=runBlocking {
        var now=0L;var generation=1L;var searches=0;var attempts=0
        val p=LibraryTransferRecovery{now};p.observe(generation)
        val c=LibraryRecoveryContext(p,LibrarySyncMode.MERGE,{true},
            {searches++;generation++;p.observe(generation)},{},{},{_,_->},{})
        val failure=runCatching { c.unit("articles") {
            attempts++;now+=6000;throw LibraryTransportFailure(generation,EOFException())
        }}.exceptionOrNull()
        assertTrue(failure is LibraryNoProgressLimit)
        assertEquals("同步多次中断且没有进展，请稍后重试",failure!!.message)
        assertEquals(3,searches);assertEquals(4,attempts);assertEquals(3,p.consecutiveNoProgress)
        assertEquals(0,p.consecutiveFailures)
    }
    @Test fun newConfirmedProgressResetsStreakButDuplicateReceiptAndReadyDoNot() {
        var now=0L;val p=LibraryTransferRecovery{now}
        val c=LibraryRecoveryContext(p,LibrarySyncMode.MERGE,{true},{},{},{},{_,_->},{})
        fun drop(generation:Long){p.observe(generation);p.ready(true);now+=6000;p.disconnected(generation)}
        drop(1);drop(2);assertEquals(2,p.consecutiveNoProgress)
        p.observe(3);p.ready(true);c.confirmedProgress("receiveChunk","a|body|0|hash")
        assertEquals(0,p.consecutiveNoProgress);now+=6000;p.disconnected(3)
        drop(4);assertEquals(1,p.consecutiveNoProgress)
        p.observe(5);p.ready(true);c.confirmedProgress("receiveChunk","a|body|0|hash")
        now+=6000;p.disconnected(5);assertEquals(2,p.consecutiveNoProgress)
        p.observe(6);p.ready(true);c.stageComplete("reader");assertEquals(0,p.consecutiveNoProgress)
        now+=6000;p.disconnected(6);drop(7)
        p.observe(8);p.ready(true);c.stageComplete("reader");now+=6000;p.disconnected(8)
        assertEquals(2,p.consecutiveNoProgress)
    }
    @Test fun senderWritesDoNotCountUntilPeerConfirmsAndReceiptsAreDeduplicated() {
        val article=PhoneArticleReceiveCheckpointTest().article();val m=ArticleSyncBody.metadataFor(article)
        val frames=LibrarySyncPayload.buildChunkedArticleRequestFrames("phone",listOf(article),
            listOf(ArticleBodyRequest(article.articleId,m.bodyHash,m.chunkHashes.indices.toList())),emptyList(),true)
        var now=0L;val p=LibraryTransferRecovery{now}
        val c=LibraryRecoveryContext(p,LibrarySyncMode.MERGE,{true},{},{},{},{_,_->},{})
        fun drop(n:Long){p.observe(n);p.ready(true);now+=6000;p.disconnected(n)}
        drop(1);frames.forEach(c::sentArticleFrame);assertEquals(1,p.consecutiveNoProgress)
        p.observe(2);p.ready(true);c.confirmPeerChunks(emptyList(),listOf(ArticleBodyRequest(article.articleId,m.bodyHash,emptyList())))
        now+=6000;p.disconnected(2);assertEquals(2,p.consecutiveNoProgress) // empty list means request full body
        p.observe(3);p.ready(true);c.confirmPeerChunks(emptyList(),listOf(ArticleBodyRequest(article.articleId,m.bodyHash,listOf(1))))
        assertEquals(0,p.consecutiveNoProgress);now+=6000;p.disconnected(3)
        drop(4);p.observe(5);p.ready(true)
        c.confirmPeerChunks(emptyList(),listOf(ArticleBodyRequest(article.articleId,m.bodyHash,listOf(1))))
        now+=6000;p.disconnected(5);assertEquals(2,p.consecutiveNoProgress)
        p.observe(6);p.ready(true);c.confirmArticleBatch(setOf(article.articleId));assertEquals(0,p.consecutiveNoProgress)
    }
    @Test fun activeSlowFrameAndWritesCanExceedIdleDeadlineWithoutTimingOut() {
        val frame=JSONObject().put("data","x".repeat(4096));val encoded=ByteArrayOutputStream().also{BluetoothSyncProtocol.writeFrame(it,frame)}.toByteArray()
        var offset=0;var closed=false
        val source=object:InputStream(){override fun read():Int {Thread.sleep(3);return if(offset<encoded.size)encoded[offset++].toInt()and 255 else -1}
            override fun read(b:ByteArray,o:Int,n:Int):Int {Thread.sleep(3);val count=minOf(n,16,encoded.size-offset);if(count==0)return -1;encoded.copyInto(b,o,offset,offset+count);offset+=count;return count}}
        val guard=LibraryIoGuard(1,{true},{closed=true},500)
        assertEquals(frame.toString(),BluetoothSyncProtocol.readFrame(guard.input(source)).toString());assertFalse(closed)
        var calls=0
        val target=object:OutputStream(){override fun write(value:Int)=error("bulk writes expected")
            override fun write(b:ByteArray,o:Int,n:Int){assertTrue(n<=8192);calls++;Thread.sleep(3)}}
        guard.output(target).write(ByteArray(2*1024*1024));assertTrue(calls>1);assertFalse(closed)
    }
    @Test fun rfcommEightySevenKilobyteFrameUsesEightySixBoundedWrites() {
        val frame=ByteArray(87759){(it%251).toByte()};val received=ByteArrayOutputStream();var calls=0
        val sink=object:OutputStream(){override fun write(value:Int)=error("bulk writes expected")
            override fun write(b:ByteArray,o:Int,n:Int){assertTrue(n<=1024);calls++;received.write(b,o,n)}}
        LibraryIoGuard(1,{true},{fail("active RFCOMM write must not expire")}).output(sink).write(frame)
        assertTrue("87 KB RFCOMM frame used $calls underlying writes",calls==86)
        assertArrayEquals(frame,received.toByteArray())
    }
    @Test fun thirdSearchFailureStopsAndSuccessResetsOnlySearchCounter() {
        var now = 0L
        val p = LibraryTransferRecovery { now }
        p.searchFailed(); p.searchFailed(); p.ready()
        assertEquals(0, p.consecutiveFailures)
        p.observe(1); p.disconnected(1)
        now = 5000; p.observe(2); p.disconnected(2); p.ready()
        now = 10000; p.observe(3)
        assertThrows(LibraryDisconnectLimit::class.java) { p.disconnected(3) }
        p.searchFailed(); p.searchFailed()
        assertThrows(LibraryReconnectLimit::class.java) { p.searchFailed() }
    }
    @Test fun tenSecondBoundaryIsInclusiveAndOldOrDuplicateCallbacksDoNotCount() {
        var now = 0L; val p = LibraryTransferRecovery { now }
        p.observe(1); p.disconnected(1); p.disconnected(1)
        p.observe(2); p.disconnected(1); now = 5000; p.disconnected(2)
        p.observe(3); now = 10001; p.disconnected(3)
        p.ready(); p.observe(4); now = 10002
        assertThrows(LibraryDisconnectLimit::class.java) { p.disconnected(4) }
    }
    @Test fun onlyNetworkGuardCreatesRetryableFailures() {
        for (error in listOf(IOException("reset by peer"), EOFException(), SocketTimeoutException())) {
            val input = object : InputStream() { override fun read(): Int = throw error }
            val failure = runCatching { LibraryIoGuard(42, { true }, {}).input(input).read() }.exceptionOrNull()
            assertTrue(failure is LibraryTransportFailure)
            assertSame(error, failure!!.cause)
        }
        val protocol = ProtocolException("bad frame")
        assertSame(protocol, runCatching { LibraryIoGuard(42, { true }, {}).input(object : InputStream() {
            override fun read(): Int = throw protocol
        }).read() }.exceptionOrNull())
        val local = IOException("disk full")
        assertFalse(local is LibraryTransportFailure)
        assertTrue(runCatching { LibraryIoGuard(1, { true }, {}).input(ByteArrayInputStream(byteArrayOf())).read() }.exceptionOrNull() is LibraryTransportFailure)
    }
    @Test fun deadlineClosesBlockedIoAndRetainsTimeoutCause() {
        val closed = java.util.concurrent.CountDownLatch(1)
        val source = object : InputStream() { override fun read(): Int {
            check(closed.await(2, java.util.concurrent.TimeUnit.SECONDS)); throw IOException("closed")
        } }
        val failure = runCatching { LibraryIoGuard(1, { true }, { closed.countDown() }, 10).input(source).read() }.exceptionOrNull()
        assertTrue(failure is LibraryTransportFailure)
        assertTrue(failure!!.cause is SocketTimeoutException)
    }
    @Test fun controllerConfirmsReplacementBeforeReplayingCurrentUnit() = runBlocking {
        var active = true; var generation = 1L; var confirms = 0; var searches = 0
        val order = mutableListOf<String>(); val policy = LibraryTransferRecovery { 0 }
        policy.observe(1)
        val context = LibraryRecoveryContext(policy, LibrarySyncMode.MERGE, { active },
            { searches++; generation++; policy.observe(generation); active = true; order += "connect" },
            { confirms++; order += "account" }, {}, { _, _ -> }, { order += "progress" })
        var calls = 0
        withContext(context) { context.unit("body") {
            calls++; order += "data"
            if (calls == 1) { active = false; throw LibraryTransportFailure(1, EOFException()) }
        } }
        assertEquals(listOf("data", "progress", "connect", "account", "data"), order)
        assertEquals(1, searches); assertEquals(1, confirms)
    }
    @Test fun searchFailureStopsAfterThreeAndProtocolOrAccountErrorsNeverSearch() = runBlocking {
        var searches = 0; val p = LibraryTransferRecovery { 0 }
        val c = LibraryRecoveryContext(p, LibrarySyncMode.MERGE, { false },
            { searches++; throw LibrarySearchFailure() }, {}, {}, { _, _ -> }, {})
        assertTrue(runCatching { c.unit("start") {} }.exceptionOrNull() is LibraryReconnectLimit)
        assertEquals(3, searches)
        for (error in listOf(IllegalStateException("登录已失效，请重新登录"), ProtocolException("invalid"), IOException("local file"))) {
            val context = LibraryRecoveryContext(LibraryTransferRecovery { 0 }, LibrarySyncMode.MERGE, { true },
                { fail("must not reconnect") }, {}, {}, { _, _ -> }, {})
            assertSame(error, runCatching { context.unit("data") { throw error } }.exceptionOrNull())
        }
    }
    @Test fun cancellationNeverReconnectsEvenWhenSocketReportsEof() = runBlocking {
        var searches = 0
        val c = LibraryRecoveryContext(LibraryTransferRecovery { 0 }, LibrarySyncMode.MERGE, { true },
            { searches++ }, {}, {}, { _, _ -> }, {})
        val job = launch { c.unit("data") { currentCoroutineContext().cancel(); throw LibraryTransportFailure(1, EOFException()) } }
        job.join(); assertTrue(job.isCancelled); assertEquals(0, searches)
    }
    @Test fun halfFramesAreTransportFailuresButMalformedFramesAreNot() {
        val encoded=ByteArrayOutputStream().also { out ->
            val data=DataOutputStream(out);data.writeInt(12);data.write(byteArrayOf(123,34))
        }.toByteArray()
        assertTrue(runCatching { BluetoothSyncProtocol.readFrame(LibraryIoGuard(7,{true},{}).input(ByteArrayInputStream(encoded))) }.exceptionOrNull() is LibraryTransportFailure)
        val bad=ByteArrayOutputStream().also { DataOutputStream(it).writeInt(-1) }.toByteArray()
        assertTrue(runCatching { BluetoothSyncProtocol.readFrame(LibraryIoGuard(7,{true},{}).input(ByteArrayInputStream(bad))) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(isLibraryProbeFatal(ProtocolException()))
        assertFalse(isLibraryProbeFatal(IOException("connect failed")))
    }
    @Test fun oldConnectionFailureCannotCloseOrResearchNewConnection()=runBlocking {
        val p=LibraryTransferRecovery{0};p.observe(2)
        var calls=0
        val c=LibraryRecoveryContext(p,LibrarySyncMode.MERGE,{true},{fail("old generation researched")},{},{},{_,_->},{})
        c.unit("data"){calls++;if(calls==1)throw LibraryTransportFailure(1,EOFException())}
        assertEquals(2,calls)
    }
    @Test fun cancellationDuringSearchOrConfirmationStopsFurtherAttempts()=runBlocking {
        for(confirming in listOf(false,true)) {
            val entered=CompletableDeferred<Unit>();var searches=0
            val c=LibraryRecoveryContext(LibraryTransferRecovery{0},LibrarySyncMode.MERGE,{false},
                {searches++;if(!confirming){entered.complete(Unit);awaitCancellation()}},
                {entered.complete(Unit);awaitCancellation()},{},{_,_->},{})
            val job=launch{c.unit("data"){}}
            entered.await();job.cancelAndJoin();assertEquals(1,searches)
        }
    }
    @Test fun reconnectProgressRetainsVisiblePercentAndExactStageLabel() {
        val hold = LibraryProgressHold()
        assertEquals(37, hold.percent(true, 90, 37))
        assertEquals(37, hold.percent(false, 0, 37))
        assertEquals(68, hold.percent(false, 68, 37))
        assertEquals(68, hold.percent(true, 1, 68))
        assertEquals("正在重新连接手表…", PhoneBluetoothSyncStage.RECONNECTING.displayName)
        hold.reset(); assertEquals(0, hold.percent(false, 0, 68))
    }
}
