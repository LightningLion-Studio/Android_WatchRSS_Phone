package com.lightningstudio.watchrss.phone.acoustic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AcousticCodecTest {
    @Test
    fun encodeDecode_roundTripsPayload() {
        val payload = """
            {"kind":"pure_sound","ability":"REMOTE_INPUT","url":"https://example.com/feed.xml?x=1&y=2"}
        """.trimIndent().toByteArray()

        val packet = AcousticCodec.encode(payload)
        val decoded = AcousticCodec.decode(packet.waveform)

        assertArrayEquals(payload, decoded)
        assertTrue(packet.durationMs > 0)
    }
}
