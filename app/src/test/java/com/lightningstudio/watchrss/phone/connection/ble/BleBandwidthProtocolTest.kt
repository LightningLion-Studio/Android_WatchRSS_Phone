package com.lightningstudio.watchrss.phone.connection.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleBandwidthProtocolTest {
    @Test
    fun `encodes deterministic data packets and checksum`() {
        val packet = BleBandwidthProtocol.encodeData(
            trialId = 7,
            sequence = 3,
            absoluteOffset = 100,
            payloadLength = 20
        )

        assertEquals(BleBandwidthProtocol.DATA_HEADER_BYTES + 20, packet.size)
        assertEquals(BleBandwidthProtocol.VERSION, packet[0].toInt())
        assertEquals(BleBandwidthProtocol.TYPE_DATA, packet[1].toInt())
        assertEquals(7, packet[2].toInt())
        assertEquals(3, packet[4].toInt())
        assertEquals(20, packet[6].toInt())
        assertTrue(BleBandwidthProtocol.updateChecksum(0, packet) > 0)
        assertTrue(packet.contentEquals(
            BleBandwidthProtocol.encodeData(7, 3, 100, 20)
        ))
    }

    @Test
    fun `decodes ready and integrity ack`() {
        assertEquals(
            ControlMessage.Ready,
            BleBandwidthProtocol.decodeControl(
                byteArrayOf(
                    BleBandwidthProtocol.VERSION.toByte(),
                    BleBandwidthProtocol.TYPE_READY.toByte()
                )
            )
        )

        val ack = byteArrayOf(
            1, 4,
            0x34, 0x12,
            0, 0,
            0x00, 0x00, 0x04, 0x00,
            0x78, 0x56, 0x34, 0x12,
            0x56, 0x00
        )
        assertEquals(
            ControlMessage.Ack(
                trialId = 0x1234,
                status = 0,
                receivedBytes = 256 * 1024L,
                checksum = 0x12345678,
                expectedSequence = 0x56
            ),
            BleBandwidthProtocol.decodeControl(ack)
        )
    }

    @Test
    fun `rejects malformed controls and sizes payload from mtu`() {
        assertNull(BleBandwidthProtocol.decodeControl(byteArrayOf()))
        assertNull(BleBandwidthProtocol.decodeControl(byteArrayOf(2, 5)))
        assertEquals(12, BleBandwidthProtocol.maxPayloadBytes(23))
        assertEquals(236, BleBandwidthProtocol.maxPayloadBytes(247))
        assertEquals(504, BleBandwidthProtocol.maxPayloadBytes(517))
    }

    @Test
    fun `reports application goodput`() {
        assertEquals(
            100.0,
            BleBandwidthProtocol.kibPerSecond(256 * 1024, 2560),
            0.001
        )
        assertEquals(
            819.2,
            BleBandwidthProtocol.kilobitsPerSecond(256 * 1024, 2560),
            0.001
        )
    }
}
