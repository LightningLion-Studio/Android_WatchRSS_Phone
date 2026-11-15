package com.lightningstudio.watchrss.phone.libraryresume

import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*
import java.io.PipedInputStream
import java.io.PipedOutputStream

class ResumeSessionTest {
    @Test fun simultaneousRequestsDoNotBlockResponseDispatchOrInterleaveBytes() = runBlocking {
        val aIn = PipedInputStream(1048576); val bOut = PipedOutputStream(aIn)
        val bIn = PipedInputStream(1048576); val aOut = PipedOutputStream(bIn)
        val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val a = ResumeSession(aIn, aOut, background); val b = ResumeSession(bIn, bOut, background)
        val received = java.util.concurrent.atomic.AtomicInteger()
        val handle: suspend (org.json.JSONObject) -> org.json.JSONObject = { r -> received.incrementAndGet(); ResumeResults.answer(r, obj("state" to "CANCELLED", "alreadyComplete" to false), r.n("epoch")) }
        a.installReceiver(handle); b.installReceiver(handle); a.start(); b.start()
        try {
            withTimeout(10000) {
                val ra = ResumeWire.request("cancel", "a".repeat(32), "a".repeat(64), 1L, obj("reason" to "USER_CANCEL"))
                val rb = ResumeWire.request("cancel", "b".repeat(32), "b".repeat(64), 2L, obj("reason" to "LOCAL_ABORT"))
                val first = async { a.exchange(ra) }; val second = async { b.exchange(rb) }
                assertEquals(ra.s("requestId"), first.await().s("requestId")); assertEquals(rb.s("requestId"), second.await().s("requestId"))
                assertEquals(2, received.get())
            }
        } finally { a.close(); b.close(); background.cancel() }
    }
}
