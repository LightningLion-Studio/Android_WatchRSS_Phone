package com.lightningstudio.watchrss.phone.connection.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleBandwidthProtocolTest {
    @Test
    fun advertisementMarkerIsStable() {
        assertEquals(0xffff, BleBandwidthProtocol.ADVERTISEMENT_MANUFACTURER_ID)
        assertArrayEquals(
            byteArrayOf(0x57, 0x52, 0x53, 0x33),
            BleBandwidthProtocol.ADVERTISEMENT_MARKER
        )
        assertEquals(
            BleBandwidthProtocol.DATA_UUID,
            BleBandwidthProtocol.CONTROL_UUID
        )
        assertEquals(3, BleBandwidthProtocol.DATA_UUIDS.size)
        assertEquals(4, BleBandwidthProtocol.CONTROL_UUIDS.size)
        assertTrue(BleBandwidthProtocol.DATA_UUIDS.contains(BleBandwidthProtocol.V1_DATA_UUID))
        assertTrue(BleBandwidthProtocol.CONTROL_UUIDS.contains(BleBandwidthProtocol.V1_CONTROL_UUID))
    }

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
    fun `marks watch audio and video envelopes independently`() {
        val audio = BleBandwidthProtocol.encodeBegin(
            trialId = 9,
            sizeBytes = 1024,
            repetition = 0,
            payloadKind = BleBandwidthProtocol.PAYLOAD_KIND_AUDIO
        )
        val video = BleBandwidthProtocol.encodeBegin(
            trialId = 10,
            sizeBytes = 2048,
            repetition = 3,
            payloadKind = BleBandwidthProtocol.PAYLOAD_KIND_VIDEO
        )
        assertEquals(BleBandwidthProtocol.PAYLOAD_KIND_AUDIO, audio[10].toInt())
        assertEquals(BleBandwidthProtocol.PAYLOAD_KIND_VIDEO, video[10].toInt())
        assertEquals(4, BleBandwidthProtocol.PAYLOAD_KIND_AUDIO_CHUNK)
        assertEquals(5, BleBandwidthProtocol.PAYLOAD_KIND_AUDIO_FINAL)
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
        assertEquals(
            ControlMessage.VideoReady,
            BleBandwidthProtocol.decodeControl(
                byteArrayOf(
                    BleBandwidthProtocol.VERSION.toByte(),
                    BleBandwidthProtocol.TYPE_VIDEO_READY.toByte()
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
    fun `decodes final watch playback summary`() {
        val packet = byteArrayOf(
            1, 11,
            0x34, 0x12,
            0x18, 0x01,
            19, 0,
            2, 0,
            0x2d, 0x01,
            0x7b, 0x01,
            0xff.toByte(), 0xff.toByte(),
            0x60, 0xea.toByte(), 0, 0,
            0x39, 0x30, 0, 0,
            0x41, 0x01,
            0xd7.toByte(), 0x11, 0, 0,
            0x31, 0xd4.toByte(), 0, 0,
            0xdb.toByte(), 0x03
        )
        assertEquals(
            ControlMessage.VideoStats(
                reportId = 0x1234,
                displayedFrames = 280,
                droppedFrames = 19,
                rejectedFrames = 2,
                receivedFrames = 301,
                lastReceivedFrameIndex = 379,
                lastDisplayedFrameIndex = -1,
                elapsedMs = 60_000,
                decodeMsTotal = 12_345,
                decodeMsMax = 321,
                maxLateMs = 4_567,
                uiCommitMsTotal = 54_321,
                uiCommitMsMax = 987
            ),
            BleBandwidthProtocol.decodeControl(packet)
        )
    }

    @Test
    fun `calculates watch playback timing`() {
        val stats = BleVideoPlaybackStats(
            sourceFrames = 600,
            sentFrames = 500,
            phoneSkippedFrames = 100,
            lastSentFrameIndex = 599,
            phoneElapsedMs = 100_000,
            displayedFrames = 280,
            receivedFrames = 500,
            droppedFrames = 220,
            rejectedFrames = 0,
            lastReceivedFrameIndex = 599,
            lastDisplayedFrameIndex = 599,
            watchElapsedMs = 60_000,
            decodeMsTotal = 14_000,
            decodeMsMax = 80,
            maxLateMs = 400,
            uiCommitMsTotal = 28_000,
            uiCommitMsMax = 140
        )
        assertEquals(4.65, stats.actualFps, 0.001)
        assertEquals(50.0, stats.averageDecodeMs, 0.001)
        assertEquals(100.0, stats.averageUiCommitMs, 0.001)
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
