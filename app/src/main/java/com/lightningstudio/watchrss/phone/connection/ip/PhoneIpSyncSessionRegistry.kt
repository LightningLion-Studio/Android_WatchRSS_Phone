package com.lightningstudio.watchrss.phone.connection.ip

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.security.MessageDigest
import java.util.UUID

internal class PhoneIpSyncSession(
    val watchDeviceId: String,
    val sessionId: String,
    val routeKind: IpTransportKind,
    val remoteAddress: String,
    private val sendBinary: (ByteArray) -> Unit,
    private val closeSocket: () -> Unit,
    private val trace:(String,Map<String,Any>)->Unit = {_,_->},
    readIdleTimeoutMs:Long=30_000
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val incoming = PipedInputStream(PIPE_BUFFER_BYTES)
    private val incomingWriter = PipedOutputStream(incoming)

    private val received=AtomicLong();private val consumed=AtomicLong();private val sent=AtomicLong()
    private val connectionTag=UUID.randomUUID().toString().take(8)
    private val sessionTag=MessageDigest.getInstance("SHA-256").digest(sessionId.toByteArray()).take(6).joinToString(""){"%02x".format(it)}
    fun diagnostics():Map<String,Any> = mapOf("connection" to connectionTag,"protocolSession" to sessionTag,"receivedBytes" to received.get(),"consumedBytes" to consumed.get(),"sentBytes" to sent.get(),"closed" to closed.get(),"route" to routeKind.wireName)
    val inputStream: InputStream = IpReadDeadline(incoming,readIdleTimeoutMs,{consumed.addAndGet(it.toLong())}) {limit->
        close();trace("read-idle",diagnostics()+mapOf("timeoutMs" to limit))
    }
    val outputStream: OutputStream = object : OutputStream() {
        override fun write(value: Int) {
            write(byteArrayOf(value.toByte()))
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            check(!closed.get()) { "IP 同步连接已关闭" }
            sendBinary(bytes.copyOfRange(offset, offset + length))
            sent.addAndGet(length.toLong());trace("send",diagnostics()+mapOf("frameBytes" to length))
        }
    }

    val isClosed: Boolean
        get() = closed.get()

    fun acceptBinary(bytes: ByteArray) {
        if (!closed.get()) {
            received.addAndGet(bytes.size.toLong());trace("receive",diagnostics()+mapOf("frameBytes" to bytes.size))
            incomingWriter.write(bytes)
            // PipedOutputStream.write does not wake a reader waiting on an empty pipe.
            incomingWriter.flush()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        PhoneIpSyncSessionRegistry.unregister(this)
        runCatching { incomingWriter.close() }
        runCatching { incoming.close() }
        runCatching { closeSocket() }
        trace("closed",diagnostics())
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
