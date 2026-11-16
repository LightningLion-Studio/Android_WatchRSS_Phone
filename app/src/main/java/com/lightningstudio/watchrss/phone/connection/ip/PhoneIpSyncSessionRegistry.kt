package com.lightningstudio.watchrss.phone.connection.ip

import android.util.Log
import java.io.Closeable
import java.io.InputStream
import java.io.FilterInputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Per socket, before WebSocket message buffering. It is activity, not an applied receipt. */
internal class IpReadActivity {
    companion object { private val sequence = java.util.concurrent.atomic.AtomicLong() }
    val connectionId = sequence.incrementAndGet()
    @Volatile var trackWrites = false
    @Volatile var lastWriteNanos: Long = 0L; private set
    private fun readAt(bytes: Int) {
        if (bytes <= 0) return
        val at = System.nanoTime()
        lastNanos = at
        if (trackWrites) Log.i("WatchRSS_IpSyncIO", "event=raw.read connection=$connectionId rawReadNanos=$at bytes=$bytes")
    }
    fun output(target: OutputStream): OutputStream = object : OutputStream() {
        override fun write(value: Int) { target.write(value); lastWriteNanos = System.nanoTime() }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (!trackWrites) { target.write(bytes, offset, length); return }
            // Raw SocketOutputStream has no partial-write count. Each returned byte
            // is an observable transport write, including inside a WebSocket send.
            for (index in offset until offset + length) {
                target.write(bytes, index, 1)
                lastWriteNanos = System.nanoTime()
            }
        }
        override fun flush() = target.flush()
        override fun close() = target.close()
    }
    @Volatile var lastNanos: Long = 0L; private set
    fun input(source: InputStream): InputStream = object : FilterInputStream(source) {
        override fun read(): Int = source.read().also { if (it >= 0) readAt(1) }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int = source.read(bytes, offset, length)
            .also { readAt(it) }
    }
}

internal class PhoneIpSyncSession(
    val watchDeviceId: String,
    val sessionId: String,
    val routeKind: IpTransportKind,
    val remoteAddress: String,
    private val sendBinary: (ByteArray) -> Unit,
    private val closeSocket: () -> Unit
) : Closeable {
    var wireConnectionId: Long = 0L
    var enableWireWriteTracking: () -> Unit = {}
    var wireWriteActivityNanos: () -> Long = { 0L }
    @Volatile var wireReadActivityNanos: () -> Long = { 0L }
    private val closed = AtomicBoolean(false)
    private val incoming = PipedInputStream(PIPE_BUFFER_BYTES)
    private val incomingWriter = PipedOutputStream(incoming)

    val inputStream: InputStream = incoming
    val outputStream: OutputStream = object : OutputStream() {
        override fun write(value: Int) {
            write(byteArrayOf(value.toByte()))
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            check(!closed.get()) { "IP 同步连接已关闭" }
            sendBinary(bytes.copyOfRange(offset, offset + length))
        }
    }

    val isClosed: Boolean
        get() = closed.get()

    fun acceptBinary(bytes: ByteArray) {
        if (!closed.get()) {
            incomingWriter.write(bytes)
            // PipedInputStream otherwise polls an empty pipe at one-second intervals.
            incomingWriter.flush()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        PhoneIpSyncSessionRegistry.unregister(this)
        // Abort the captured socket first. Never wait for a WebSocket Close frame
        // behind an in-flight send; that send may be what this close must interrupt.
        runCatching { closeSocket() }
        runCatching { incoming.close() }
        runCatching { incomingWriter.close() }
    }

    companion object {
        private const val PIPE_BUFFER_BYTES = 4 * 1024 * 1024
    }
}

internal object PhoneIpSyncSessionRegistry {
    private val sessionsByDeviceId = ConcurrentHashMap<String, PhoneIpSyncSession>()

    fun register(session: PhoneIpSyncSession) {
        val previous = sessionsByDeviceId.put(session.watchDeviceId, session)
        if (previous !== session) previous?.close()
    }

    fun unregister(session: PhoneIpSyncSession) {
        sessionsByDeviceId.remove(session.watchDeviceId, session)
    }

    fun session(deviceId: String? = null): PhoneIpSyncSession? = if (deviceId.isNullOrBlank()) {
        sessionsByDeviceId.values.filterNot { it.isClosed }.maxByOrNull { it.routeKind.priority }
    } else {
        sessionsByDeviceId[deviceId.removePrefix(IP_DEVICE_PREFIX)]?.takeUnless { it.isClosed }
    }

    fun activeSessions(): List<PhoneIpSyncSession> =
        sessionsByDeviceId.values.filterNot { it.isClosed }
            .sortedByDescending { it.routeKind.priority }

    fun closeAll() {
        activeSessions().forEach(PhoneIpSyncSession::close)
    }

    const val IP_DEVICE_PREFIX = "ip:"
}
