package com.lightningstudio.watchrss.phone.connection.ip

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneIpSyncSessionRegistryTest {
    @After
    fun tearDown() {
        PhoneIpSyncSessionRegistry.closeAll()
    }

    @Test
    fun closeAll_invalidatesSessionsThatStillLookLocallyOpen() {
        var socketClosed = false
        val session = PhoneIpSyncSession(
            watchDeviceId = "watch-1",
            sessionId = "session-1",
            routeKind = IpTransportKind.WIFI_LAN,
            remoteAddress = "192.168.77.81",
            sendBinary = {},
            closeSocket = { socketClosed = true }
        )
        PhoneIpSyncSessionRegistry.register(session)

        PhoneIpSyncSessionRegistry.closeAll()

        assertTrue(socketClosed)
        assertTrue(session.isClosed)
        assertEquals(emptyList<PhoneIpSyncSession>(), PhoneIpSyncSessionRegistry.activeSessions())
    }
}
