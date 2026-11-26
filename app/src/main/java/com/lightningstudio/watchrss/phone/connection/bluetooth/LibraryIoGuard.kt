package com.lightningstudio.watchrss.phone.connection.bluetooth

import android.util.Log
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private class LibraryIoValidationFailure(val original: Exception) : RuntimeException(original)

/** Timeouts belong to an individual network read/write, not to the whole resumed task. */
internal class LibraryIoGuard(
    private val generation: Long,
    private val enabled: () -> Boolean,
    private val close: () -> Unit,
    // 40 frozen-APK cases: longest successful complete read <= 6001 ms.
    // Reset at each stream I/O. Manifest preparation has a separate first-byte budget.
    private val timeoutMs: Long = 30_000L,
    private val remotelyClosed: () -> Boolean = { false },
    private val beforeIo: () -> Unit = {},
    private val wireReadActivityNanos: () -> Long = { 0L },
    private val wireWriteActivityNanos: (() -> Long)? = null,
    private val wireConnectionId: Long = 0L
) {
    private val completedWriteNanos = java.util.concurrent.atomic.AtomicLong()
    companion object {
        private val closer = Executors.newCachedThreadPool { work ->
            Thread(work, "library-io-close").apply { isDaemon = true }
        }
        private val timer = Executors.newSingleThreadScheduledExecutor { work ->
            Thread(work, "library-io-deadline").apply { isDaemon = true }
        }
    }
    private fun <T> io(
        reading: Boolean = false,
        firstByteTimeoutMs: Long? = null,
        block: () -> T
    ): T {
        if (!enabled()) return block()
        beforeIo()
        val timedOut = AtomicBoolean(false)
        val finished = AtomicBoolean(false)
        val alarm = AtomicReference<ScheduledFuture<*>?>()
        val started = System.nanoTime()
        val timeoutNanos = TimeUnit.MILLISECONDS.toNanos(firstByteTimeoutMs ?: timeoutMs)
        val deadline = object : Runnable {
            override fun run() {
                if (finished.get()) return
                // A WebSocket may buffer an entire large message before feeding the
                // application pipe. Observe this connection's raw reads, not just onMessage.
                val rawRead = wireReadActivityNanos()
                val rawWrite = wireWriteActivityNanos?.invoke() ?: completedWriteNanos.get()
                val activity = if (firstByteTimeoutMs != null) started
                    else maxOf(started, if (reading) rawRead else rawWrite)
                val checked = System.nanoTime()
                val remaining = timeoutNanos - (checked - activity)
                if (wireConnectionId != 0L) Log.i("WatchRSS_IpSyncIO",
                    "event=deadline.check connection=$wireConnectionId generation=$generation reading=$reading " +
                        "checkNanos=$checked startedNanos=$started rawReadNanos=$rawRead " +
                        "rawWriteNanos=$rawWrite remainingNanos=$remaining")
                if (remaining > 0) {
                    val next = timer.schedule(this, remaining, TimeUnit.NANOSECONDS)
                    alarm.set(next)
                    if (finished.get()) next.cancel(false)
                } else if (!finished.get()) {
                    timedOut.set(true)
                    closer.execute { runCatching(close) }
                }
            }
        }
        alarm.set(timer.schedule(deadline, timeoutNanos, TimeUnit.NANOSECONDS))
        try {
            val result = block()
            if (timedOut.get()) throw SocketTimeoutException("同步连接读写超时")
            return result
        } catch (failure: LibraryIoValidationFailure) {
            throw failure.original
        } catch (failure: IllegalStateException) {
            // The existing IP pipe uses check(!closed) rather than IOException. Only its
            // actual closed state may turn this exception into a transport disconnect.
            if (remotelyClosed()) throw LibraryTransportFailure(generation, IOException("同步连接已关闭", failure))
            throw failure
        } catch (failure: IOException) {
            if (failure is ProtocolException || failure is LibraryTransportFailure) throw failure
            throw LibraryTransportFailure(generation,
                if (timedOut.get()) SocketTimeoutException("同步连接读写超时").apply { initCause(failure) } else failure)
        } finally { finished.set(true); alarm.get()?.cancel(false) }
    }
    fun input(source: InputStream, firstByteTimeoutMs: Long? = null): InputStream = object : InputStream() {
        private var initialTimeout = firstByteTimeoutMs

        private fun receive(read: () -> Int): Int =
            io(reading = true, firstByteTimeoutMs = initialTimeout, block = read)

        override fun read(): Int = receive {
            source.read().also {
                if (enabled() && it < 0) throw EOFException("同步连接读取结束")
                if (it >= 0) initialTimeout = null
            }
        }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int = if (length == 0) 0 else receive {
            source.read(bytes, offset, if (enabled()) minOf(length, 8192) else length).also {
                if (enabled() && it < 0) throw EOFException("同步连接读取结束")
                if (it > 0) initialTimeout = null
            }
        }
        override fun close() = source.close()
    }
    fun output(target: OutputStream): OutputStream = object : OutputStream() {
        override fun write(value: Int) = io {
            target.write(value)
            completedWriteNanos.set(System.nanoTime())
        }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (!enabled()) { target.write(bytes, offset, length); return }
            // Keep RFCOMM writes bounded without per-byte socket calls. Each
            // completed 1 KiB chunk refreshes activity (4 seconds at 256 B/s).
            // IP retains 8 KiB chunks because it also observes raw writes.
            io {
                var written = 0
                while (written < length) {
                    if (written > 0) {
                        // Validation is not a transport failure, even if the peer
                        // concurrently closes. Preserve the original error type.
                        try { beforeIo() } catch (failure: Exception) { throw LibraryIoValidationFailure(failure) }
                    }
                    val count = minOf(length - written, if (wireWriteActivityNanos == null) 1024 else 8192)
                    target.write(bytes, offset + written, count)
                    completedWriteNanos.set(System.nanoTime())
                    written += count
                }
            }
        }
        override fun flush() = io { target.flush() }
        override fun close() = target.close()
    }
}
