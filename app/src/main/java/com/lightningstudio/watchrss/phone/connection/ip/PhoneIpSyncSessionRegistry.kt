package com.lightningstudio.watchrss.phone.connection.ip

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
    @Volatile var lastNanos: Long = 0L; private set
    fun input(source: InputStream): InputStream = object : FilterInputStream(source) {
        override fun read(): Int = source.read().also { if (it >= 0) lastNanos = System.nanoTime() }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int = source.read(bytes, offset, length)
            .also { if (it > 0) lastNanos = System.nanoTime() }
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
        runCatching { incomingWriter.close() }
        runCatching { incoming.close() }
        runCatching { closeSocket() }
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
