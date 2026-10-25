package com.lightningstudio.watchrss.phone.connection.ip

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertNull(PhoneIpSyncSessionRegistry.session("watch-1"))
        assertEquals(emptyList<PhoneIpSyncSession>(), PhoneIpSyncSessionRegistry.activeSessions())
    }

    @Test
    fun close_unregistersSessionWithoutWaitingForSocketCallback() {
        val session = PhoneIpSyncSession(
            watchDeviceId = "watch-2",
            sessionId = "session-2",
            routeKind = IpTransportKind.WIFI_LAN,
            remoteAddress = "192.168.77.82",
            sendBinary = {},
            closeSocket = {}
        )
        PhoneIpSyncSessionRegistry.register(session)

        session.close()

        assertNull(PhoneIpSyncSessionRegistry.session("watch-2"))
        assertEquals(emptyList<PhoneIpSyncSession>(), PhoneIpSyncSessionRegistry.activeSessions())
    }

    @Test
    fun registeredSession_isResolvedByRemoteDeviceIdInsteadOfBluetoothMac() {
        val session = PhoneIpSyncSession(
            watchDeviceId = "watch-device-id",
            sessionId = "session-3",
            routeKind = IpTransportKind.WIFI_LAN,
            remoteAddress = "192.168.77.83",
            sendBinary = {},
            closeSocket = {}
        )
        PhoneIpSyncSessionRegistry.register(session)

        assertEquals(session, PhoneIpSyncSessionRegistry.session("watch-device-id"))
        assertEquals(session, PhoneIpSyncSessionRegistry.session("ip:watch-device-id"))
        assertNull(PhoneIpSyncSessionRegistry.session("AA:BB:CC:DD:EE:FF"))
    }
    @Test
    fun resumedReplacementWakesTheOldReaderAndKeepsTheNewPhysicalConnectionRegistered() {
        fun session()=PhoneIpSyncSession("watch","resumed-session",IpTransportKind.WIFI_LAN,"192.0.2.1",{}, {})
        val old=session();PhoneIpSyncSessionRegistry.register(old)
        val pending=java.util.concurrent.FutureTask {try{old.inputStream.read();true}catch(_:java.io.IOException){true}}
        val reader=Thread(pending).apply{start()}
        val replacement=session()
        try {
            PhoneIpSyncSessionRegistry.register(replacement)
            assertTrue(pending.get(1,java.util.concurrent.TimeUnit.SECONDS));assertTrue(old.isClosed)
            assertEquals(replacement,PhoneIpSyncSessionRegistry.session("watch"))
            org.junit.Assert.assertNotEquals(old.diagnostics()["connection"],replacement.diagnostics()["connection"])
            assertEquals(old.diagnostics()["protocolSession"],replacement.diagnostics()["protocolSession"])
            replacement.acceptBinary(byteArrayOf(7));assertEquals(7,replacement.inputStream.read())
            old.close();assertEquals(replacement,PhoneIpSyncSessionRegistry.session("watch"))
        }finally{old.close();replacement.close();reader.join(1000)}
    }

}
