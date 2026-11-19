package com.lightningstudio.watchrss.phone.connection.bluetooth.b2

import org.junit.Assert.*
import org.junit.Assert.fail as assertionFailed
import org.junit.Test

class B2HeartbeatBenchmarkTest {
    @Test fun frozenPlanningContractsDriveTheProductionTimers() {
        val cases = fixture("planning-heartbeat-cases.json").getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i); var now = 0L
            val heart = B2Heartbeat { now }; val local = B2LocalWatchdog { now }
            var replies = 0; var recovery = 0; var localStop = 0L; var timedOut = 0L
            while (now <= c.getLong("durationMs")) {
                val work = c.getLong("workEveryMs")
                if (work > 0 && now > 0 && now % work == 0L) local.completedWorkUnit()
                if (!c.getBoolean("receiverPaused")) {
                    try { local.check() } catch (e: B2Exception) { assertEquals("LOCAL_IO", e.code); localStop = now; break }
                }
                try { heart.checkDeadline() } catch (_: java.net.SocketTimeoutException) { recovery++; timedOut = now; break }
                if (heart.due()) { heart.beginWrite(); if (!c.getBoolean("networkBlackhole")) { heart.reply(); replies++ } }
                now += 1000
            }
            val expected = c.getJSONObject("expected")
            assertEquals(c.getString("id"), expected.getInt("recoveryEvents"), recovery)
            if (expected.has("completedReplies")) assertEquals(expected.getInt("completedReplies"), replies)
            if (expected.has("heartbeatTimeoutAtMs")) assertEquals(expected.getLong("heartbeatTimeoutAtMs"), timedOut)
            if (expected.has("localStopAtMs")) assertEquals(expected.getLong("localStopAtMs"), localStop)
        }
    }
    @Test fun ordinaryNoiseCannotExtendOutstandingHeartbeatAndNoStacking() {
        var now = 0L; val h = B2Heartbeat { now }
        now = 10000; assertTrue(h.due()); h.beginWrite()
        for (n in 11000L..39000L step 1000) { now = n; assertFalse(h.due()); h.checkDeadline() }
        now = 40000
        try { h.checkDeadline(); assertionFailed() } catch (_: java.net.SocketTimeoutException) {}
    }
    @Test fun queuedHeartbeatDeadlineStartsAtTheFirstWrite() {
        var now = 0L; val h = B2Heartbeat { now }
        now = 10000; h.queue(); assertFalse(h.due())
        now = 50000; h.checkDeadline() // a bounded current DATA can occupy the writer
        h.beginWrite(); now = 79999; h.checkDeadline()
        now = 80000
        try { h.checkDeadline(); assertionFailed() } catch (_: java.net.SocketTimeoutException) {}
    }
    @Test fun host64KiBCodecBenchmarkReportsTimeAndThreadAllocations() {
        val frame = B2Frame.Data(B2DataHeader(1, 0, 1, 1, 0, 65536, "1".repeat(32), "2".repeat(64)), ByteArray(65536) { (it * 31).toByte() })
        val raw = B2Frames.encode(frame)
        val iterations = 2000
        repeat(200) { B2Frames.encode(frame); B2Frames.decode(raw) }
        val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val mx = Class.forName("com.sun.management.ThreadMXBean")
        mx.getMethod("setThreadAllocatedMemoryEnabled", Boolean::class.javaPrimitiveType).invoke(bean, true)
        val allocated = mx.getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        fun allocatedBytes() = allocated.invoke(bean, Thread.currentThread().id) as Long
        fun measure(name: String, action: () -> Unit) {
            val beforeBytes = allocatedBytes(); val start = System.nanoTime()
            repeat(iterations) { action() }
            val nanos = System.nanoTime() - start; val bytes = allocatedBytes() - beforeBytes
            println("B2_HOST_BENCH $name iterations=$iterations ns_per_block=${nanos / iterations} allocated_bytes_per_block=${bytes / iterations} payload=65536 JVM=${System.getProperty("java.version")}")
        }
        measure("encode") { assertEquals(65632, B2Frames.encode(frame).size) }
        measure("decode") { assertEquals(65536, (B2Frames.decode(raw) as B2Frame.Data).bytes.size) }
    }
}
