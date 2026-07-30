package com.lightningstudio.watchrss.phone.connection.ble

import java.util.UUID

internal object BleBandwidthProtocol {
    const val VERSION = 1
    const val TYPE_BEGIN = 1
    const val TYPE_DATA = 2
    const val TYPE_END = 3
    const val TYPE_ACK = 4
    const val TYPE_READY = 5

    const val ACK_OK = 0
    const val ACK_SEQUENCE_ERROR = 1
    const val ACK_SIZE_ERROR = 2
    const val ACK_CHECKSUM_ERROR = 3

    const val DATA_HEADER_BYTES = 8
    const val ATT_HEADER_BYTES = 3
    const val DEFAULT_MTU = 23
    const val REQUESTED_MTU = 247
    const val MAX_ATTRIBUTE_BYTES = 512

    val SERVICE_UUID: UUID = UUID.fromString("7e57c001-1f7d-4f0b-9f3d-2d7d3a65b001")
    val DATA_UUID: UUID = UUID.fromString("7e57c001-1f7d-4f0b-9f3d-2d7d3a65b002")
    val CONTROL_UUID: UUID = UUID.fromString("7e57c001-1f7d-4f0b-9f3d-2d7d3a65b003")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    val testSizesBytes = listOf(32 * 1024, 64 * 1024, 128 * 1024, 256 * 1024)

    fun encodeBegin(trialId: Int, sizeBytes: Int, repetition: Int): ByteArray =
        ByteArray(12).also { packet ->
            packet[0] = VERSION.toByte()
            packet[1] = TYPE_BEGIN.toByte()
            putUInt16(packet, 2, trialId)
            putUInt32(packet, 4, sizeBytes.toLong())
            putUInt16(packet, 8, repetition)
        }

    fun encodeData(
        trialId: Int,
        sequence: Int,
        absoluteOffset: Int,
        payloadLength: Int
    ): ByteArray {
        require(payloadLength in 1..0xffff)
        val packet = ByteArray(DATA_HEADER_BYTES + payloadLength)
        packet[0] = VERSION.toByte()
        packet[1] = TYPE_DATA.toByte()
        putUInt16(packet, 2, trialId)
        putUInt16(packet, 4, sequence)
        putUInt16(packet, 6, payloadLength)
        for (index in 0 until payloadLength) {
            val position = absoluteOffset + index
            packet[DATA_HEADER_BYTES + index] =
                (((position * 31) xor (position ushr 3) xor (trialId * 17)) and 0xff).toByte()
        }
        return packet
    }

    fun encodeEnd(trialId: Int, sizeBytes: Int, checksum: Long): ByteArray =
        ByteArray(12).also { packet ->
            packet[0] = VERSION.toByte()
            packet[1] = TYPE_END.toByte()
            putUInt16(packet, 2, trialId)
            putUInt32(packet, 4, sizeBytes.toLong())
            putUInt32(packet, 8, checksum)
        }

    fun updateChecksum(checksum: Long, packet: ByteArray): Long {
        var value = checksum
        for (index in DATA_HEADER_BYTES until packet.size) {
            value = (value + (packet[index].toInt() and 0xff)) and 0xffff_ffffL
        }
        return value
    }

    fun maxPayloadBytes(mtu: Int): Int =
        (minOf(mtu - ATT_HEADER_BYTES, MAX_ATTRIBUTE_BYTES) - DATA_HEADER_BYTES)
            .coerceAtLeast(1)

    fun decodeControl(value: ByteArray): ControlMessage? {
        if (value.size < 2 || unsigned(value[0]) != VERSION) return null
        return when (unsigned(value[1])) {
            TYPE_READY -> ControlMessage.Ready
            TYPE_ACK -> {
                if (value.size < 16) return null
                ControlMessage.Ack(
                    trialId = readUInt16(value, 2),
                    status = unsigned(value[4]),
                    receivedBytes = readUInt32(value, 6),
                    checksum = readUInt32(value, 10),
                    expectedSequence = readUInt16(value, 14)
                )
            }
            else -> null
        }
    }

    fun kibPerSecond(bytes: Int, elapsedMs: Long): Double =
        if (elapsedMs <= 0) 0.0 else bytes * 1000.0 / elapsedMs / 1024.0

    fun kilobitsPerSecond(bytes: Int, elapsedMs: Long): Double =
        if (elapsedMs <= 0) 0.0 else bytes * 8.0 / elapsedMs

    private fun unsigned(value: Byte): Int = value.toInt() and 0xff

    private fun putUInt16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xff).toByte()
        target[offset + 1] = ((value ushr 8) and 0xff).toByte()
    }

    private fun putUInt32(target: ByteArray, offset: Int, value: Long) {
        for (index in 0 until 4) {
            target[offset + index] = ((value ushr (index * 8)) and 0xff).toByte()
        }
    }

    private fun readUInt16(source: ByteArray, offset: Int): Int =
        unsigned(source[offset]) or (unsigned(source[offset + 1]) shl 8)

    private fun readUInt32(source: ByteArray, offset: Int): Long {
        var value = 0L
        for (index in 0 until 4) {
            value = value or (unsigned(source[offset + index]).toLong() shl (index * 8))
        }
        return value
    }
}

internal sealed interface ControlMessage {
    data object Ready : ControlMessage

    data class Ack(
        val trialId: Int,
        val status: Int,
        val receivedBytes: Long,
        val checksum: Long,
        val expectedSequence: Int
    ) : ControlMessage
}

internal data class BleBandwidthTrialResult(
    val sizeBytes: Int,
    val repetition: Int,
    val elapsedMs: Long,
    val mtu: Int,
    val packetCount: Int
) {
    val kibPerSecond: Double
        get() = BleBandwidthProtocol.kibPerSecond(sizeBytes, elapsedMs)

    val kilobitsPerSecond: Double
        get() = BleBandwidthProtocol.kilobitsPerSecond(sizeBytes, elapsedMs)
}
