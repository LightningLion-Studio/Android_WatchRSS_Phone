package com.lightningstudio.watchrss.phone.libraryresume

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import java.util.concurrent.ConcurrentLinkedQueue

/** RVPHONEC2-01: retain the reviewer's blocked-output interleaving with fixed assertions. */
class ResumeSessionR3Test {
    @Test fun blockedErrorReplyImmediatelyPublishesStopAndRejectsOppositeSuccess() = runBlocking {
        for (code in listOf("HASH_MISMATCH", "BUSINESS_REJECTED", "AUTH_REVOKED")) withTimeout(6000) {
            val incoming = PipedInputStream(1048576)
            val peerWrite = PipedOutputStream(incoming)
            val peerRead = PipedInputStream(1048576)
            val outgoing = PipedOutputStream(peerRead)
            val arm = AtomicBoolean(false)
            val blocked = CountDownLatch(1)
            val release = CountDownLatch(1)
            val stalledOutput = object : OutputStream() {
                private fun barrier() { if (arm.compareAndSet(true, false)) { blocked.countDown(); if (!release.await(4, TimeUnit.SECONDS)) throw IOException("probe barrier timeout") } }
                override fun write(b: Int) { barrier(); outgoing.write(b) }
                override fun write(b: ByteArray, off: Int, len: Int) { barrier(); outgoing.write(b, off, len) }
                override fun flush() = outgoing.flush()
                override fun close() = outgoing.close()
            }
            val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val session = ResumeSession(incoming, stalledOutput, bg)
            val failure = ResumeException(code)
            session.installReceiver { throw failure }
            session.start()
            val termination = async(start = CoroutineStart.UNDISPATCHED) { session.awaitTermination() }
            val root = ResumeDigests.receipts(emptyList())
            val sourceRequest = ResumeWire.request("complete", "c".repeat(32), "a".repeat(64), 1, obj("objectCount" to 0, "receiptsRoot" to root, "ackToInclusive" to 0))
            val pendingSource = async { runCatching { session.exchange(sourceRequest) } }
            try {
                val onWire = withContext(Dispatchers.IO) { ResumeWire.read(peerRead) }
                arm.set(true)
                withContext(Dispatchers.IO) { ResumeWire.write(peerWrite, ResumeWire.request("cancel", "d".repeat(32), "b".repeat(64), 1, obj("reason" to "USER_CANCEL"))) }
                assertTrue("local STOP is reserved and error output is now blocked", withContext(Dispatchers.IO) { blocked.await(2, TimeUnit.SECONDS) })
                // Finish well before the 1000-ms best-effort output deadline.
                withTimeout(500) {
                    assertSame("controller must receive STOP before output is released", failure, termination.await())
                    assertSame("opposite direction must fail before output is released", failure, pendingSource.await().exceptionOrNull())
                }
                // This is a valid response to the opposite direction. The error frame
                // has not published even its prefix, so the peer cannot know local STOP.
                withContext(Dispatchers.IO) { ResumeWire.write(peerWrite, ResumeResults.answer(onWire, ResumeDigests.completion(onWire.s("jobId"), onWire.s("planDigest"), 0, root, 0), 1)) }
                val result = pendingSource.await()
                assertSame("late opposite COMPLETE cannot replace STOP", failure, result.exceptionOrNull())
                assertSame(failure, runCatching { session.exchange(sourceRequest) }.exceptionOrNull())
                release.countDown()
                assertSame("releasing output cannot replace the original cause", failure, termination.await())
            } finally {
                release.countDown(); session.close(); bg.cancel(); peerWrite.close(); peerRead.close(); termination.cancel()
            }
        }
    }
    /** Cancelling a coroutine cannot bound a native write/close that ignores interrupts.
     * The controller and waiter must still finish, and the independent deadline must
     * start both stream closes exactly once before either blocking operation is released. */
    @Test fun stopReplyDeadlineStartsCleanupEvenWhenWriteAndCloseIgnoreCancellation() = runBlocking {
        val incoming = PipedInputStream(1048576); val peerWrite = PipedOutputStream(incoming)
        val peerRead = PipedInputStream(1048576); val outgoing = PipedOutputStream(peerRead)
        val arm = AtomicBoolean(false)
        val blocked = CountDownLatch(1); val releaseWrite = CountDownLatch(1)
        val closeEntered = CountDownLatch(1); val releaseClose = CountDownLatch(1)
        val sourceClosed = CountDownLatch(1); val sourceCloses = AtomicInteger(); val sinkCloses = AtomicInteger()
        fun awaitUninterruptibly(latch: CountDownLatch) { while (true) try { latch.await(); return } catch (_: InterruptedException) {} }
        val source = object : FilterInputStream(incoming) {
            override fun close() { sourceCloses.incrementAndGet(); sourceClosed.countDown(); super.close() }
        }
        val sink = object : OutputStream() {
            private fun barrier() { if (arm.compareAndSet(true, false)) { blocked.countDown(); awaitUninterruptibly(releaseWrite) } }
            override fun write(b: Int) { barrier(); outgoing.write(b) }
            override fun write(b: ByteArray, off: Int, len: Int) { barrier(); outgoing.write(b, off, len) }
            override fun flush() = outgoing.flush()
            override fun close() { sinkCloses.incrementAndGet(); closeEntered.countDown(); awaitUninterruptibly(releaseClose); outgoing.close() }
        }
        val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val session = ResumeSession(source, sink, bg, 200)
        val failure = ResumeException("HASH_MISMATCH"); val calls = AtomicInteger()
        session.installReceiver { calls.incrementAndGet(); throw failure }; session.start()
        val request = completeRequest()
        val pending = async { runCatching { session.exchange(request) } }
        try {
            withTimeout(6000) {
                withContext(Dispatchers.IO) { ResumeWire.read(peerRead) }
                arm.set(true)
                withContext(Dispatchers.IO) { ResumeWire.write(peerWrite, cancelRequest()) }
                assertTrue(withContext(Dispatchers.IO) { blocked.await(2, TimeUnit.SECONDS) })
                assertSame(failure, session.awaitTermination())
                assertSame(failure, pending.await().exceptionOrNull())
                assertTrue("deadline must start output cleanup without joining its blocked writer", withContext(Dispatchers.IO) { closeEntered.await(2, TimeUnit.SECONDS) })
                assertTrue("blocked output close must not prevent input cleanup", withContext(Dispatchers.IO) { sourceClosed.await(2, TimeUnit.SECONDS) })
                assertEquals(1L, releaseWrite.count); assertEquals(1L, releaseClose.count)
                session.close(); session.close()
                assertEquals(1, sourceCloses.get()); assertEquals(1, sinkCloses.get()); assertEquals(1, calls.get())
                assertSame(failure, runCatching { session.exchange(request) }.exceptionOrNull())
            }
        } finally { releaseWrite.countDown(); releaseClose.countDown(); session.close(); bg.cancel(); peerWrite.close(); peerRead.close() }
    }

    @Test fun stopFailsExchangeWhileItsOwnRequestWriteIsStillBlocked() = runBlocking {
        val incoming = PipedInputStream(1048576); val peerWrite = PipedOutputStream(incoming)
        val blocked = CountDownLatch(1); val release = CountDownLatch(1)
        val sink = object : OutputStream() {
            override fun write(b: Int) { blocked.countDown(); release.await(4, TimeUnit.SECONDS) }
            override fun write(b: ByteArray, off: Int, len: Int) { write(0) }
        }
        val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val session = ResumeSession(incoming, sink, bg)
        val failure = ResumeException("AUTH_REVOKED")
        session.installReceiver { throw failure }; session.start()
        val pending = async { runCatching { session.exchange(completeRequest()) } }
        try {
            withTimeout(6000) {
                assertTrue(withContext(Dispatchers.IO) { blocked.await(2, TimeUnit.SECONDS) })
                withContext(Dispatchers.IO) { ResumeWire.write(peerWrite, cancelRequest()) }
                withTimeout(500) {
                    assertSame(failure, session.awaitTermination())
                    assertSame("no join of the blocked outgoing write", failure, pending.await().exceptionOrNull())
                }
                assertEquals(1L, release.count)
            }
        } finally { release.countDown(); session.close(); bg.cancel(); peerWrite.close() }
    }

    private class HeldDispatcher : CoroutineDispatcher() {
        val dispatches = AtomicInteger()
        private val queued = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queued.add(block); dispatches.incrementAndGet() }
        fun drain() { while (true) (queued.poll() ?: return).run() }
    }
    private fun completeRequest() = ResumeWire.request("complete", "c".repeat(32), "a".repeat(64), 1,
        obj("objectCount" to 0, "receiptsRoot" to ResumeDigests.receipts(emptyList()), "ackToInclusive" to 0))
    private fun cancelRequest() = ResumeWire.request("cancel", "d".repeat(32), "b".repeat(64), 1, obj("reason" to "USER_CANCEL"))
    private fun completeReply(r: org.json.JSONObject) = ResumeResults.answer(r,
        ResumeDigests.completion(r.s("jobId"), r.s("planDigest"), 0, ResumeDigests.receipts(emptyList()), 0), 1)

    @Test fun successAlreadyDeliveredToWaiterCannotReturnAfterLocalStopReservation() = runBlocking {
        val incoming = PipedInputStream(1048576); val peerWrite = PipedOutputStream(incoming)
        val peerRead = PipedInputStream(1048576); val outgoing = PipedOutputStream(peerRead)
        val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val session = ResumeSession(incoming, outgoing, bg)
        val failure = ResumeException("BUSINESS_REJECTED")
        session.installReceiver { throw failure }; session.start()
        val held = HeldDispatcher()
        val pending = async(held, start = CoroutineStart.LAZY) { runCatching { session.exchange(completeRequest()) } }
        try {
            withTimeout(6000) {
                pending.start(); held.drain()
                val onWire = withContext(Dispatchers.IO) { ResumeWire.read(peerRead) }
                val initialDispatches = held.dispatches.get()
                withContext(Dispatchers.IO) { ResumeWire.write(peerWrite, completeReply(onWire)) }
                // A new queued continuation proves success was delivered to the waiter;
                // the caller cannot adopt that success until we drain this dispatcher.
                while (held.dispatches.get() == initialDispatches) yield()
                withContext(Dispatchers.IO) { ResumeWire.write(peerWrite, cancelRequest()) }
                assertSame(failure, session.awaitTermination())
                held.drain()
                assertSame("success return must recheck reserved STOP", failure, pending.await().exceptionOrNull())
            }
        } finally { held.drain(); session.close(); bg.cancel(); peerWrite.close(); peerRead.close() }
    }

    @Test fun completeDeliveredBeforeOrdinaryEofRemainsSuccessful() = runBlocking {
        val incoming = PipedInputStream(1048576); val peerWrite = PipedOutputStream(incoming)
        val peerRead = PipedInputStream(1048576); val outgoing = PipedOutputStream(peerRead)
        val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val session = ResumeSession(incoming, outgoing, bg); session.start()
        val held = HeldDispatcher()
        val pending = async(held, start = CoroutineStart.LAZY) { runCatching { session.exchange(completeRequest()) } }
        try {
            withTimeout(6000) {
                pending.start(); held.drain()
                val onWire = withContext(Dispatchers.IO) { ResumeWire.read(peerRead) }
                withContext(Dispatchers.IO) { ResumeWire.write(peerWrite, completeReply(onWire)); peerWrite.close() }
                assertTrue(session.awaitTermination() is EOFException)
                held.drain()
                assertEquals("COMPLETE", pending.await().getOrThrow().o("result").s("state"))
            }
        } finally { held.drain(); session.close(); bg.cancel(); peerWrite.close(); peerRead.close() }
    }

}
