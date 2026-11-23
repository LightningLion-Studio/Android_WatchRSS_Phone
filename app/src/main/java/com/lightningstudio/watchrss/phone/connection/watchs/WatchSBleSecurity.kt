package com.lightningstudio.watchrss.phone.connection.watchs

import com.lightningstudio.watchrss.phone.syncv2.SyncV2Codec
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object WatchSBleSecurity {
    const val LABEL = "wrss.watchs.ble.v2/"
    const val TAG_BYTES = 32
    const val MAX_PLAIN = WatchSBleFrames.LIMIT - TAG_BYTES

    fun derive(key: ByteArray, purpose: String, vararg fields: ByteArray) =
        SyncV2Codec.hmac(key, SyncV2Codec.lp((LABEL + purpose).toByteArray(), *fields))

    fun pairKey(code: ByteArray): ByteArray {
        require(code.size == 16)
        return derive(code, "pair-key")
    }

    fun pairId(key: ByteArray) = SyncV2Codec.hex(derive(key, "pair-id").copyOf(16))
    fun resetKey(key: ByteArray) = derive(key, "reset-key")

    fun transcript(pairId: String, counter: String, nonce: ByteArray, clientMtu: Int, mtu: Int, mode: String): ByteArray {
        val sequence = SyncV2Codec.unhex(counter, 8)
        require(sequence.any { it != 0.toByte() })
        return SyncV2Codec.sha(SyncV2Codec.lp((LABEL + "hello").toByteArray(),
            SyncV2Codec.unhex(pairId, 16), sequence, nonce, u32(clientMtu.toLong()), u32(mtu.toLong()), mode.toByteArray()))
    }

    fun proof(key: ByteArray, transcriptHash: ByteArray, direction: WatchSBleDirection) =
        derive(key, "proof", transcriptHash, direction.bytes)

    fun matches(expected: ByteArray, supplied: String) =
        MessageDigest.isEqual(expected, SyncV2Codec.unhex(supplied, expected.size))

    fun u32(value: Long) = ByteBuffer.allocate(4).putInt(value.toInt()).array()
}

internal enum class WatchSBleDirection(val label: String, val firstId: Long) {
    WATCH("watch", 3), PHONE("phone", 2);

    val bytes get() = label.toByteArray()
    val opposite get() = if (this == WATCH) PHONE else WATCH
}

internal class WatchSBleCipher(
    key: ByteArray,
    private val transcriptHash: ByteArray,
    outbound: WatchSBleDirection
) : AutoCloseable {
    private class Lane(key: ByteArray, hash: ByteArray, val direction: WatchSBleDirection) {
        val encryption = WatchSBleSecurity.derive(key, "enc", hash, direction.bytes)
        val authentication = WatchSBleSecurity.derive(key, "mac", hash, direction.bytes)
        var nextId = direction.firstId

        fun checkId(id: Long) = check(id == nextId && id <= 0xffffffffL)

        fun clear() {
            encryption.fill(0)
            authentication.fill(0)
        }
    }

    private val sender = Lane(key, transcriptHash, outbound)
    private val receiver = Lane(key, transcriptHash, outbound.opposite)
    private var closed = false

    fun seal(id: Long, plaintext: ByteArray): ByteArray {
        check(!closed)
        sender.checkId(id)
        require(plaintext.size in 1..WatchSBleSecurity.MAX_PLAIN)
        // A failure still consumes the outbound ID. The endpoint must close on error.
        sender.nextId++
        val encrypted = ctr(sender, id, plaintext)
        return encrypted + tag(sender, id, encrypted)
    }

    fun open(id: Long, message: ByteArray): ByteArray {
        check(!closed)
        receiver.checkId(id)
        require(message.size in (WatchSBleSecurity.TAG_BYTES + 1)..WatchSBleFrames.LIMIT)
        val end = message.size - WatchSBleSecurity.TAG_BYTES
        val encrypted = message.copyOfRange(0, end)
        check(MessageDigest.isEqual(tag(receiver, id, encrypted), message.copyOfRange(end, message.size)))
        receiver.nextId++
        return ctr(receiver, id, encrypted)
    }

    private fun ctr(lane: Lane, id: Long, bytes: ByteArray): ByteArray {
        val iv = WatchSBleSecurity.u32(id) + ByteArray(12)
        return Cipher.getInstance("AES/CTR/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(lane.encryption, "AES"), IvParameterSpec(iv))
            doFinal(bytes)
        }
    }

    private fun tag(lane: Lane, id: Long, encrypted: ByteArray) = WatchSBleSecurity.derive(
        lane.authentication, "message", transcriptHash, lane.direction.bytes, WatchSBleSecurity.u32(id), encrypted
    )

    override fun close() {
        closed = true
        sender.clear()
        receiver.clear()
    }
}
