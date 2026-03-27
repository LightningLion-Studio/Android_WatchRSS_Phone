package com.lightningstudio.watchrss.phone.connection

import org.junit.Assert.assertEquals
import org.junit.Test

class AcousticConnectionProtocolTest {
    @Test
    fun parsePureSound_acceptsLegacyAbilityAlias() {
        val payload = """
            {"kind":"pure_sound","ability":"watchrss-remote-input","url":"https://example.com/feed.xml"}
        """.trimIndent().toByteArray()

        val envelope = AcousticConnectionProtocol.parsePureSound(payload)

        assertEquals(PhoneConnectionAbility.REMOTE_INPUT, envelope.ability)
        assertEquals("https://example.com/feed.xml", envelope.url)
    }
}
