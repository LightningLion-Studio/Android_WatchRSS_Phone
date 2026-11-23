package com.lightningstudio.watchrss.phone.connection.watchs

import java.nio.ByteBuffer
import java.util.zip.CRC32

internal object WatchSBleFrames {
    const val MIN_MTU = 23
    const val MAX_MTU = 247
    const val HEADER = 14
    const val LIMIT = 262144
    const val DATA = 1
    const val FINAL = 2
    const val ACK = 3
    const val ACK_TIMEOUT_MS = 3000L

    data class Frame(val type: Int, val id: Long, val offset: Int, val payload: ByteArray = byteArrayOf()) {
        val end get() = offset + payload.size
    }

    fun encode(frame: Frame): ByteArray {
        validate(frame)
        return ByteBuffer.allocate(HEADER + frame.payload.size).apply {
            put(0x57.toByte())
            put(frame.type.toByte())
            putInt(frame.id.toInt())
            putInt(frame.offset)
            putInt(0)
            put(frame.payload)
            putInt(10, crc(array()))
        }.array()
    }

    fun decode(bytes: ByteArray, mtu: Int): Frame {
        require(mtu in MIN_MTU..MAX_MTU && bytes.size in HEADER..(mtu - 3))
        val input = ByteBuffer.wrap(bytes)
        require(input.get().toInt() == 0x57)
        val type = input.get().toInt()
        val id = input.int.toLong() and 0xffffffffL
        val offset = input.int
        require(input.int == crc(bytes))
        return Frame(type, id, offset, bytes.copyOfRange(HEADER, bytes.size)).also(::validate)
    }

    private fun validate(frame: Frame) {
        require(frame.id in 1..0xffffffffL && frame.offset in 0..LIMIT)
        require(frame.payload.size <= MAX_MTU - 3 - HEADER && frame.end <= LIMIT)
        require(when (frame.type) {
            DATA -> frame.payload.isNotEmpty() && frame.end < LIMIT
            FINAL -> frame.payload.isNotEmpty()
            ACK -> frame.payload.isEmpty()
            else -> false
        })
    }

    private fun crc(bytes: ByteArray): Int = CRC32().apply {
        update(bytes, 0, 10)
        update(bytes, HEADER, bytes.size - HEADER)
    }.value.toInt()

    fun fragments(id: Long, bytes: ByteArray, mtu: Int): Sequence<Frame> {
        require(bytes.size in 1..LIMIT && mtu in MIN_MTU..MAX_MTU)
        val step = mtu - 3 - HEADER
        return (bytes.indices step step).asSequence().map { offset ->
            val end = minOf(offset + step, bytes.size)
            Frame(if (end == bytes.size) FINAL else DATA, id, offset, bytes.copyOfRange(offset, end))
        }
    }

    class Receiver {
        data class Accepted(val ack: Frame, val message: ByteArray?)
        private var id = 1L
        private val prefix = java.io.ByteArrayOutputStream()
        private var last: Frame? = null

        fun accept(frame: Frame): Accepted? {
            if (frame.type == ACK) return null
            val previous = last
            val ack = Frame(ACK, frame.id, frame.end)
            if (previous != null && previous.id == frame.id && previous.offset == frame.offset &&
                previous.type == frame.type && previous.payload.contentEquals(frame.payload)
            ) return Accepted(ack, null)
            if (frame.id != id || frame.offset != prefix.size()) return null
            prefix.write(frame.payload)
            last = frame
            if (frame.type != FINAL) return Accepted(ack, null)
            id++
            return Accepted(ack, prefix.toByteArray().also { prefix.reset() })
        }
    }

    suspend fun send(
        id: Long,
        bytes: ByteArray,
        mtu: Int,
        transmit: suspend (ByteArray) -> Unit,
        awaitAck: suspend (id: Long, offset: Int, timeoutMillis: Long) -> Boolean
    ) {
        for (frame in fragments(id, bytes, mtu)) {
            val packet = encode(frame)
            var acknowledged = false
            for (attempt in 1..3) {
                transmit(packet)
                if (awaitAck(id, frame.end, ACK_TIMEOUT_MS)) {
                    acknowledged = true
                    break
                }
            }
            check(acknowledged) { "ack_timeout" }
        }
    }
}
