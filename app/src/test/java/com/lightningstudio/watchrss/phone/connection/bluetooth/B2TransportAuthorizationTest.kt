package com.lightningstudio.watchrss.phone.connection.bluetooth

import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.B2DataHeader
import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.B2Exception
import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.B2Frame
import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.B2Frames
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class B2TransportAuthorizationTest {
    private val payload = ByteArray(65_536)
    private val frame = B2Frame.Data(B2DataHeader(1, 0, 1, 1, 0, payload.size, "a".repeat(32), "b".repeat(64)), payload)
    private val encoded = B2Frames.encode(frame)
    private val headerSize = encoded.size - payload.size

    @Test fun revocationAfterHeaderOrFirstBlockStopsTheNextUnderlyingWrite() {
        for (stopAt in listOf(headerSize, headerSize + 16_384)) {
            var authorized = true
            var flushes = 0
            val socket = object : ByteArrayOutputStream() {
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    super.write(bytes, offset, length)
                    if (size() == stopAt) authorized = false
                }

                override fun flush() { flushes++ }
            }
            val transport = PhoneSyncSession.Transport(InputStream.nullInputStream(), socket, "test-rfcomm", false) {}
            transport.binaryAuthorization = { if (!authorized) throw B2Exception("AUTH_REVOKED") }
            val output = transport.outputStream

            val revoked = assertThrows(B2Exception::class.java) { B2Frames.write(output, frame) }
            assertEquals("AUTH_REVOKED", revoked.code)
            assertEquals(stopAt, socket.size())
            assertThrows(B2Exception::class.java) { output.write(0) }
            assertThrows(B2Exception::class.java) { output.flush() }
            assertEquals(0, flushes)
            assertEquals(stopAt, socket.size())
        }
    }

    @Test fun revocationAfterReadingHeaderLeavesTheBodyUnread() {
        var authorized = true
        val socket = object : ByteArrayInputStream(encoded) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                super.read(bytes, offset, length).also { if (available() == payload.size) authorized = false }
        }
        val transport = PhoneSyncSession.Transport(socket, OutputStream.nullOutputStream(), "test-rfcomm", false) {}
        transport.binaryAuthorization = { if (!authorized) throw B2Exception("AUTH_REVOKED") }
        val input = transport.inputStream

        val revoked = assertThrows(B2Exception::class.java) { B2Frames.read(input) }
        assertEquals("AUTH_REVOKED", revoked.code)
        assertThrows(B2Exception::class.java) { input.read() }
        assertEquals(payload.size, socket.available())
    }
}
