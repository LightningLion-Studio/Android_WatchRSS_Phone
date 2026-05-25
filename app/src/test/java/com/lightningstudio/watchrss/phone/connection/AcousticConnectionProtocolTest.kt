package com.lightningstudio.watchrss.phone.connection

import org.junit.Assert.assertEquals
import org.junit.Test

class AcousticConnectionProtocolTest {
    @Test
    fun guidedWifi_roundTripsPayload() {
        val payload = AcousticConnectionProtocol.buildGuidedWifi(
            ability = PhoneConnectionAbility.REMOTE_INPUT,
            ssid = "WatchRSS",
            passphrase = "12345678",
            host = "192.168.43.1",
            port = 8080,
            token = "token"
        )

        val envelope = AcousticConnectionProtocol.parseGuidedWifi(payload)

        assertEquals(PhoneConnectionAbility.REMOTE_INPUT, envelope.ability)
        assertEquals("WatchRSS", envelope.ssid)
        assertEquals("12345678", envelope.passphrase)
        assertEquals("192.168.43.1", envelope.host)
        assertEquals(8080, envelope.port)
        assertEquals("token", envelope.token)
    }

    @Test
    fun parseGuidedWifi_acceptsLegacyPayload() {
        val payload = """
            {"kind":"guided_wifi","ability":"REMOTE_INPUT","ssid":"WatchRSS","passphrase":"12345678","host":"192.168.43.1","port":8080,"token":"token"}
        """.trimIndent().toByteArray()

        val envelope = AcousticConnectionProtocol.parseGuidedWifi(payload)

        assertEquals(PhoneConnectionAbility.REMOTE_INPUT, envelope.ability)
        assertEquals("WatchRSS", envelope.ssid)
        assertEquals("12345678", envelope.passphrase)
        assertEquals("192.168.43.1", envelope.host)
        assertEquals(8080, envelope.port)
        assertEquals("token", envelope.token)
    }

    @Test
    fun guidedWifi_acceptsExistingWifiPayloadWithoutPassword() {
        val payload = AcousticConnectionProtocol.buildGuidedWifi(
            ability = PhoneConnectionAbility.REMOTE_INPUT,
            ssid = "mi108507",
            passphrase = "",
            host = "192.168.31.234",
            port = 8080,
            token = "TOKEN123"
        )

        val envelope = AcousticConnectionProtocol.parseGuidedWifi(payload)

        assertEquals(PhoneConnectionAbility.REMOTE_INPUT, envelope.ability)
        assertEquals("mi108507", envelope.ssid)
        assertEquals("", envelope.passphrase)
        assertEquals("192.168.31.234", envelope.host)
        assertEquals(8080, envelope.port)
        assertEquals("TOKEN123", envelope.token)
    }
}
