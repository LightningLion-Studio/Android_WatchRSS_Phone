package com.lightningstudio.watchrss.phone.connection.bluetooth

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class RfcommSlowWriteTest {
    @Test fun continuous256BytesPerSecondOutlivesDefaultThirtySecondDeadline() {
        val closed = AtomicBoolean()
        val interrupted = CountDownLatch(1)
        val expected = ByteArray(8192) { (it % 251).toByte() }
        val received = ByteArrayOutputStream()
        var calls = 0
        val sink = object : OutputStream() {
            override fun write(value: Int) = error("bulk writes expected")
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                assertTrue("RFCOMM chunk exceeds 1 KiB", length <= 1024)
                calls++
                // The sink advances 64 bytes every 250 ms: 256 B/s. No raw-write
                // activity callback is available, exactly as with BluetoothSocket.
                var at = 0
                while (at < length) {
                    if (interrupted.await(250, TimeUnit.MILLISECONDS)) throw IOException("closed")
                    val count = minOf(64, length - at)
                    received.write(bytes, offset + at, count)
                    at += count
                }
            }
        }
        val started = System.nanoTime()
        LibraryIoGuard(1, { true }, { closed.set(true); interrupted.countDown() })
            .output(sink).write(expected)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) >= 30_000)
        assertFalse(closed.get())
        assertEquals(8, calls)
        assertArrayEquals(expected, received.toByteArray())
    }

    @Test fun rawWriteActivityKeepsIpEightKiBBatchingAndDisabledGuardKeepsOriginalCall() {
        for (enabled in listOf(false, true)) {
            val lengths = mutableListOf<Int>()
            val sink = object : OutputStream() {
                override fun write(value: Int) = error("bulk writes expected")
                override fun write(bytes: ByteArray, offset: Int, length: Int) { lengths += length }
            }
            LibraryIoGuard(1, { enabled }, {}, wireWriteActivityNanos = { System.nanoTime() })
                .output(sink).write(ByteArray(16 * 1024))
            assertEquals(if (enabled) listOf(8192, 8192) else listOf(16384), lengths)
        }
    }
}
