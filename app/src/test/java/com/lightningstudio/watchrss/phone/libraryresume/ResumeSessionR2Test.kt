package com.lightningstudio.watchrss.phone.libraryresume

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** RVPHONEC-01/02: run the actual dispatcher over byte streams, not a direct handler. */
class ResumeSessionR2Test {
    private fun request(reason: String = "LOCAL_ABORT") = ResumeWire.request(
        "cancel", "d".repeat(32), "a".repeat(64), 1, obj("reason" to reason)
    )

    @Test fun nextRequestCanQueueAfterReplyBytesButBeforeFlushReturns() = runBlocking {
        val first = request()
        val second = request("USER_CANCEL")
        val aIn = PipedInputStream(1048576)
        val bRawOut = PipedOutputStream(aIn)
        val bRawIn = PipedInputStream(1048576)
        val aOut = PipedOutputStream(bRawIn)
        val secondQueued = CountDownLatch(1)
        val releaseFlush = CountDownLatch(1)
        val twoRequestBytes = 8 + ResumeWire.frameBytes(first) + ResumeWire.frameBytes(second)
        val bIn = object : InputStream() {
            private var consumed = 0
            // A third header read proves the second request passed dispatch while
            // the worker is still inside its first response's blocked flush.
            private fun beforeRead() { if (consumed >= twoRequestBytes) secondQueued.countDown() }
            override fun read(): Int { beforeRead(); return bRawIn.read().also { if (it >= 0) consumed++ } }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                beforeRead()
                return bRawIn.read(bytes, offset, length).also { if (it > 0) consumed += it }
            }
            override fun close() = bRawIn.close()
        }
        val flushes = AtomicInteger()
        val bOut = object : FilterOutputStream(bRawOut) {
            override fun flush() {
                super.flush()
                if (flushes.incrementAndGet() == 1 && !releaseFlush.await(5, TimeUnit.SECONDS)) throw IOException("test flush barrier timed out")
            }
        }
        val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val a = ResumeSession(aIn, aOut, background)
        val b = ResumeSession(bIn, bOut, background)
        val calls = AtomicInteger()
        b.installReceiver { r -> calls.incrementAndGet(); ResumeResults.answer(r, obj("state" to "CANCELLED", "alreadyComplete" to false), 1) }
        a.start(); b.start()
        try {
            withTimeout(10000) {
                assertTrue(a.exchange(first).getBoolean("success"))
                val reply = async(Dispatchers.Default) { a.exchange(second) }
                assertTrue("second request must pass dispatch while first flush is blocked", withContext(Dispatchers.IO) { secondQueued.await(2, TimeUnit.SECONDS) })
                assertEquals("handlers must remain serial", 1, calls.get())
                releaseFlush.countDown()
                assertTrue(reply.await().getBoolean("success"))
                assertEquals(2, calls.get())
            }
        } finally { releaseFlush.countDown(); a.close(); b.close(); background.cancel() }
    }

    private suspend fun assertLocalStop(code: String) = coroutineScope {
        val aIn = PipedInputStream(1048576); val bOut = PipedOutputStream(aIn)
        val bIn = PipedInputStream(1048576); val aOut = PipedOutputStream(bIn)
        val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val a = ResumeSession(aIn, aOut, background); val b = ResumeSession(bIn, bOut, background)
        val oppositeArrived = CompletableDeferred<Unit>()
        val holdOpposite = CompletableDeferred<Unit>()
        val failure = ResumeException(code)
        val calls = AtomicInteger()
        a.installReceiver { r ->
            oppositeArrived.complete(Unit); holdOpposite.await()
            ResumeResults.answer(r, obj("state" to "CANCELLED", "alreadyComplete" to false), 1)
        }
        b.installReceiver { calls.incrementAndGet(); throw failure }
        a.start(); b.start()
        try {
            withTimeout(10000) {
                val pendingOpposite = async { runCatching { b.exchange(request()) } }
                oppositeArrived.await()
                val peerFailure = runCatching { a.exchange(request("USER_CANCEL")) }.exceptionOrNull()
                assertEquals(code, (peerFailure as ResumeException).code)
                assertSame("controller receives original local cause", failure, b.awaitTermination())
                assertSame("opposite direction must stop with the local cause, not EOF", failure, pendingOpposite.await().exceptionOrNull())
                assertSame(failure, runCatching { b.exchange(request()) }.exceptionOrNull())
                assertEquals(1, calls.get())
                b.close()
                assertSame("normal close must not replace STOP", failure, b.awaitTermination())
            }
        } finally { holdOpposite.complete(Unit); a.close(); b.close(); background.cancel() }
    }
    @Test fun inboundHashMismatchStopsBothDirectionsAndNotifiesController() = runBlocking { assertLocalStop("HASH_MISMATCH") }
    @Test fun inboundBusinessRejectionStopsBothDirectionsAndNotifiesController() = runBlocking { assertLocalStop("BUSINESS_REJECTED") }
    @Test fun inboundAuthorizationRevocationStopsBothDirectionsAndNotifiesController() = runBlocking { assertLocalStop("AUTH_REVOKED") }

    @Test fun recoverableStateErrorsKeepConnectionOpenForNextRequest() = runBlocking {
        val aIn = PipedInputStream(1048576); val bOut = PipedOutputStream(aIn)
        val bIn = PipedInputStream(1048576); val aOut = PipedOutputStream(bIn)
        val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val a = ResumeSession(aIn, aOut, background); val b = ResumeSession(bIn, bOut, background)
        val errors = listOf("CHUNKS_MISSING", "WINDOW_FULL", "LEASE_EXPIRED", "NOT_FOUND")
        val calls = AtomicInteger()
        b.installReceiver { r ->
            val index = calls.getAndIncrement()
            if (index < errors.size) throw ResumeException(errors[index])
            ResumeResults.answer(r, obj("state" to "CANCELLED", "alreadyComplete" to false), 1)
        }
        a.start(); b.start()
        val stop = async(start = CoroutineStart.UNDISPATCHED) { b.awaitTermination() }
        try {
            withTimeout(10000) {
                for (code in errors) assertEquals(code, (runCatching { a.exchange(request()) }.exceptionOrNull() as ResumeException).code)
                assertTrue(a.exchange(request("USER_CANCEL")).getBoolean("success"))
                assertEquals(5, calls.get()); assertFalse(stop.isCompleted)
            }
        } finally { a.close(); b.close(); stop.cancel(); background.cancel() }
    }
}
