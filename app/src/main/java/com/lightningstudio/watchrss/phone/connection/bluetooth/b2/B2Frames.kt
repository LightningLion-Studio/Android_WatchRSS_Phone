package com.lightningstudio.watchrss.phone.connection.bluetooth.b2

import org.json.JSONObject
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

sealed interface B2Frame {
    data class Control(val value: JSONObject) : B2Frame
    data class Data(val header: B2DataHeader, val bytes: ByteArray) : B2Frame
    data class Ack(val stream: Int, val epoch: Long, val seq: Long, val entries: List<B2AckEntry>) : B2Frame
}
data class B2DataHeader(val stream: Int, val slot: Int, val epoch: Long, val seq: Long,
    val index: Int, val length: Int, val requestId: String, val digest: String)
data class B2AckEntry(val txSeq: Long, val disposition: Int)

/** Fixed LBV2 codec. A single owner reads exactly one frame; never reads ahead across
 * a JSON switch boundary and never searches for magic after an invalid/partial frame. */
object B2Frames {
    const val MAX_FRAME = 131084
    private const val MAGIC = 0x4c425632
    fun unhex(s: String, count: Int): ByteArray {
        B2Json.hex(s, count * 2)
        return ByteArray(count) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
    fun hex(b: ByteArray): String {
        val chars = "0123456789abcdef"; val result = CharArray(b.size * 2)
        b.forEachIndexed { i, value -> val n = value.toInt() and 255; result[2 * i] = chars[n ushr 4]; result[2 * i + 1] = chars[n and 15] }
        return String(result)
    }
    private fun safe(n: Long): Long = n.also { if (it !in 1..B2Json.MAX_SAFE) fail() }
    private fun stream(n: Int): Int = n.also { if (it !in 1..2) fail() }
    fun validate(h: B2DataHeader) {
        stream(h.stream); safe(h.epoch); safe(h.seq)
        if (h.slot !in 0..4095 || h.index !in 0..255 || h.length !in 1..65536) fail()
        B2Json.hex(h.requestId, 32); B2Json.hex(h.digest)
    }
    private fun validate(a: B2Frame.Ack) {
        stream(a.stream); safe(a.epoch); safe(a.seq)
        if (a.entries.size !in 1..32) fail()
        var last = 0L
        a.entries.forEach { if (safe(it.txSeq) <= last || it.disposition !in 1..2) fail(); last = it.txSeq }
    }
    /** Header and payload are written separately. No DATA payload enters JSON. */
    fun write(output: OutputStream, frame: B2Frame) {
        val kind: Int; val header: ByteArray; val bytes: ByteArray
        when (frame) {
            is B2Frame.Control -> {
                kind = 1; B2Wire.envelope(frame.value)
                if (frame.value.s("phase") == "putChunk" && (frame.value.s("type") != "response" || frame.value.getBoolean("success"))) fail()
                header = B2Json.utf8(frame.value.toString()); bytes = ByteArray(0)
                if (header.size > 131072) fail("LIMIT_EXCEEDED")
            }
            is B2Frame.Data -> {
                kind = 2; val h = frame.header; validate(h)
                if (h.length != frame.bytes.size) fail()
                header = ByteBuffer.allocate(80).order(ByteOrder.BIG_ENDIAN).apply {
                    putInt(h.stream); putInt(h.slot); putLong(h.epoch); putLong(h.seq)
                    putInt(h.index); putInt(h.length); put(unhex(h.requestId, 16)); put(unhex(h.digest, 32))
                }.array(); bytes = frame.bytes
            }
            is B2Frame.Ack -> {
                kind = 3; validate(frame)
                header = ByteBuffer.allocate(32 + 16 * frame.entries.size).order(ByteOrder.BIG_ENDIAN).apply {
                    putInt(frame.stream); putInt(0); putLong(frame.epoch); putLong(frame.seq); putInt(frame.entries.size); putInt(0)
                    frame.entries.forEach { putLong(it.txSeq); put(it.disposition.toByte()); put(ByteArray(7)) }
                }.array(); bytes = ByteArray(0)
            }
        }
        val out = DataOutputStream(output)
        out.writeInt(12 + header.size + bytes.size); out.writeInt(MAGIC); out.writeByte(kind)
        out.writeByte(0); out.writeShort(0); out.writeInt(header.size)
        out.write(header)
        var offset = 0
        while (offset < bytes.size) { val n = minOf(16384, bytes.size - offset); out.write(bytes, offset, n); offset += n }
        out.flush()
    }
    fun encode(frame: B2Frame): ByteArray = ByteArrayOutputStream(when (frame) { is B2Frame.Data -> 96 + frame.bytes.size; is B2Frame.Ack -> 48 + 16 * frame.entries.size; is B2Frame.Control -> 1024 }).also { write(it, frame) }.toByteArray()
    fun decode(bytes: ByteArray): B2Frame = ByteArrayInputStream(bytes).use { input ->
        read(input).also { if (input.available() != 0) fail() }
    }
    /** beforePayload checks the authenticated registration, slot and expected length before
     * allocating a payload buffer. Recoverable rejection must use drain() with frozen hash. */
    fun read(input: InputStream, beforePayload: (B2DataHeader) -> Unit = {}): B2Frame =
        readInto(input) { header -> beforePayload(header); ByteArray(header.length) }

    internal fun readInto(input: InputStream, payloadBuffer: (B2DataHeader) -> ByteArray): B2Frame {
        val d = DataInputStream(input)
        val length = d.readInt()
        if (length !in 12..MAX_FRAME) fail()
        if (d.readInt() != MAGIC) fail()
        val kind = d.readUnsignedByte()
        if (kind !in 1..3 || d.readUnsignedByte() != 0 || d.readUnsignedShort() != 0) fail()
        val hl = d.readInt()
        if (hl < 0 || hl > length - 12 || (kind == 1 && hl > 131072) || (kind == 2 && hl != 80) || (kind == 3 && (hl !in 48..544 || (hl - 32) % 16 != 0))) fail()
        val payload = length - 12 - hl
        if (kind != 2 && payload != 0 || kind == 2 && payload !in 1..65536) fail()
        val header = ByteArray(hl); d.readFully(header)
        if (kind == 1) {
            val value = B2Wire.envelope(B2Json.parse(header)); B2Wire.retainSize(value, length)
            if (value.s("phase") == "putChunk" && (value.s("type") != "response" || value.getBoolean("success"))) fail()
            return B2Frame.Control(value)
        }
        val h = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        if (kind == 2) {
            val stream = h.int; val slot = h.int; val epoch = h.long; val seq = h.long; val index = h.int; val n = h.int
            val id = ByteArray(16).also { h.get(it) }; val digest = ByteArray(32).also { h.get(it) }
            val data = B2DataHeader(stream, slot, epoch, seq, index, n, hex(id), hex(digest)); validate(data)
            if (n != payload) fail()
            val bytes = payloadBuffer(data)
            d.readFully(bytes, 0, payload)
            return B2Frame.Data(data, bytes)
        }
        val stream = h.int
        if (h.int != 0) fail()
        val epoch = h.long; val seq = h.long; val count = h.int
        if (count !in 1..32 || hl != 32 + count * 16 || h.int != 0) fail()
        val entries = (0 until count).map {
            val tx = h.long; val disposition = h.get().toInt() and 255
            repeat(7) { if (h.get().toInt() != 0) fail() }
            B2AckEntry(tx, disposition)
        }
        return B2Frame.Ack(stream, epoch, seq, entries).also(::validate)
    }
    /** Error paths use a fixed scratch area, consume the entire frame, then check its hash. */
    fun drain(input: InputStream, length: Int, hash: String) {
        if (length !in 1..65536) fail()
        val digest = java.security.MessageDigest.getInstance("SHA-256"); val scratch = ByteArray(4096)
        var left = length
        while (left > 0) { val n = input.read(scratch, 0, minOf(left, scratch.size)); if (n < 0) throw EOFException(); if (n == 0) continue; digest.update(scratch, 0, n); left -= n }
        if (hex(digest.digest()) != hash) fail("HASH_MISMATCH")
    }
}
