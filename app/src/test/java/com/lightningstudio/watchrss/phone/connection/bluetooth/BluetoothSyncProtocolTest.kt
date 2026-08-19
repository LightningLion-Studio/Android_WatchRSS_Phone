package com.lightningstudio.watchrss.phone.connection.bluetooth

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class BluetoothSyncProtocolTest {
    @Test
    fun frameReadWrite_reportTransferredBytesInChunks() {
        val payload = JSONObject().apply {
            put("action", BluetoothSyncProtocol.ACTION_SYNC_LIBRARY)
            put("data", "x".repeat(80_000))
        }
        val output = ByteArrayOutputStream()
        val writeDeltas = mutableListOf<Long>()

        BluetoothSyncProtocol.writeFrame(output, payload) { bytes ->
            writeDeltas += bytes
        }

        val readDeltas = mutableListOf<Long>()
        val decoded = BluetoothSyncProtocol.readFrame(ByteArrayInputStream(output.toByteArray())) { bytes ->
            readDeltas += bytes
        }

        assertEquals(payload.toString(), decoded.toString())
        assertEquals(BluetoothSyncProtocol.wireSize(payload), writeDeltas.sum())
        assertEquals(BluetoothSyncProtocol.wireSize(payload), readDeltas.sum())
        assertEquals(BluetoothSyncProtocol.LENGTH_PREFIX_BYTES.toLong(), writeDeltas.first())
        assertEquals(BluetoothSyncProtocol.LENGTH_PREFIX_BYTES.toLong(), readDeltas.first())
        assertTrue(writeDeltas.size > 2)
        assertTrue(readDeltas.size > 2)
    }

    @Test
    fun persistentSession_negotiatesV14AndFallsBackForV13() {
        val request = BluetoothSyncProtocol.withPersistentSessionRequest(
            JSONObject().put("version", 1).put("action", BluetoothSyncProtocol.ACTION_SYNC_ACCOUNT)
        )
        val accepted = BluetoothSyncProtocol.buildSessionControlResponse(
            LibrarySyncPayload.PROTOCOL_VERSION,
            BluetoothSyncProtocol.SESSION_PHASE_COMPLETE
        )
        val legacy = JSONObject().put("success", true).put("version", 13)

        assertEquals(14, LibrarySyncPayload.PROTOCOL_VERSION)
        assertEquals(13, LibrarySyncPayload.MIN_SUPPORTED_WATCH_PROTOCOL_VERSION)
        assertTrue(BluetoothSyncProtocol.requestsPersistentSession(request))
        assertTrue(BluetoothSyncProtocol.acceptsPersistentSession(accepted))
        assertFalse(BluetoothSyncProtocol.acceptsPersistentSession(legacy))
        assertEquals(15 * 60 * 1_000L, BluetoothSyncProtocol.PERSISTENT_SESSION_IDLE_TIMEOUT_MS)
    }

    @Test
    fun sessionControl_acceptsCompleteAndAbortButRejectsUnknownPhaseAndAction() {
        listOf(
            BluetoothSyncProtocol.SESSION_PHASE_COMPLETE,
            BluetoothSyncProtocol.SESSION_PHASE_ABORT
        ).forEach { phase ->
            val request = BluetoothSyncProtocol.buildSessionControlRequest(14, phase)
            assertEquals(phase, BluetoothSyncProtocol.sessionControlPhase(request))
        }
        assertEquals(
            null,
            BluetoothSyncProtocol.sessionControlPhase(JSONObject().put("action", "unknown"))
        )
        try {
            BluetoothSyncProtocol.buildSessionControlRequest(14, "pause")
            fail("unknown session phase should fail")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun frameRead_reportsEofForClosedSession() {
        try {
            BluetoothSyncProtocol.readFrame(ByteArrayInputStream(ByteArray(0)))
            fail("closed session should report EOF")
        } catch (_: EOFException) {
        }
    }

    @Test
    fun persistentSession_duplexSequenceUsesOneTransportAndClosesOnce() {
        val clientInput = PipedInputStream()
        val serverOutput = PipedOutputStream(clientInput)
        val serverInput = PipedInputStream()
        val clientOutput = PipedOutputStream(serverInput)
        val openedTransports = AtomicInteger(1)
        val closedTransports = AtomicInteger()
        val serverFailure = AtomicReference<Throwable?>()
        val server = thread(name = "persistent-session-server") {
            try {
                listOf(
                    BluetoothSyncProtocol.ACTION_SYNC_ACCOUNT,
                    BluetoothSyncProtocol.ACTION_SYNC_LIBRARY,
                    BluetoothSyncProtocol.ACTION_SYNC_NOTE_ASSET
                ).forEachIndexed { index, expectedAction ->
                    val request = BluetoothSyncProtocol.readFrame(serverInput)
                    assertEquals(expectedAction, request.getString("action"))
                    if (index == 0) assertTrue(BluetoothSyncProtocol.requestsPersistentSession(request))
                    val response = JSONObject()
                        .put("success", true)
                        .put("version", if (expectedAction == BluetoothSyncProtocol.ACTION_SYNC_LIBRARY) 14 else 1)
                        .put("action", expectedAction)
                    if (index == 0) {
                        response.put(BluetoothSyncProtocol.FIELD_SUPPORTS_PERSISTENT_SESSION, true)
                        response.put(BluetoothSyncProtocol.FIELD_PERSISTENT_SESSION_ACCEPTED, true)
                    }
                    BluetoothSyncProtocol.writeFrame(serverOutput, response)
                    assertEquals(
                        BluetoothSyncProtocol.ACTION_ACK,
                        BluetoothSyncProtocol.readFrame(serverInput).getString("action")
                    )
                }
                val finish = BluetoothSyncProtocol.readFrame(serverInput)
                val phase = checkNotNull(BluetoothSyncProtocol.sessionControlPhase(finish))
                BluetoothSyncProtocol.writeFrame(
                    serverOutput,
                    BluetoothSyncProtocol.buildSessionControlResponse(14, phase)
                )
                assertEquals(
                    BluetoothSyncProtocol.ACTION_ACK,
                    BluetoothSyncProtocol.readFrame(serverInput).getString("action")
                )
            } catch (throwable: Throwable) {
                serverFailure.set(throwable)
            } finally {
                closedTransports.incrementAndGet()
                serverInput.close()
                serverOutput.close()
            }
        }

        listOf(
            BluetoothSyncProtocol.ACTION_SYNC_ACCOUNT,
            BluetoothSyncProtocol.ACTION_SYNC_LIBRARY,
            BluetoothSyncProtocol.ACTION_SYNC_NOTE_ASSET
        ).forEachIndexed { index, action ->
            val request = JSONObject().put("version", if (action == BluetoothSyncProtocol.ACTION_SYNC_LIBRARY) 14 else 1)
                .put("action", action)
                .let { if (index == 0) BluetoothSyncProtocol.withPersistentSessionRequest(it) else it }
            BluetoothSyncProtocol.writeFrame(clientOutput, request)
            val response = BluetoothSyncProtocol.readFrame(clientInput)
            if (index == 0) assertTrue(BluetoothSyncProtocol.acceptsPersistentSession(response))
            BluetoothSyncProtocol.writeFrame(
                clientOutput,
                JSONObject().put("action", BluetoothSyncProtocol.ACTION_ACK).put("success", true)
            )
        }
        BluetoothSyncProtocol.writeFrame(
            clientOutput,
            BluetoothSyncProtocol.buildSessionControlRequest(
                14,
                BluetoothSyncProtocol.SESSION_PHASE_COMPLETE
            )
        )
        assertEquals(
            BluetoothSyncProtocol.SESSION_PHASE_COMPLETE,
            BluetoothSyncProtocol.readFrame(clientInput).getString("phase")
        )
        BluetoothSyncProtocol.writeFrame(
            clientOutput,
            JSONObject().put("action", BluetoothSyncProtocol.ACTION_ACK).put("success", true)
        )
        server.join(3_000L)
        assertFalse("server thread did not finish", server.isAlive)
        serverFailure.get()?.let { throw AssertionError("server failed", it) }
        assertEquals(1, openedTransports.get())
        assertEquals(1, closedTransports.get())
        clientInput.close()
        clientOutput.close()
    }

    @Test
    fun sessionTransport_closeIsIdempotentDuringIpTakeover() {
        val closeCount = AtomicInteger()
        val transport = PhoneSyncSession.Transport(
            inputStream = ByteArrayInputStream(ByteArray(0)),
            outputStream = ByteArrayOutputStream(),
            owner = "rfcomm",
            closeOnLegacyFallback = true,
            closeTransport = { closeCount.incrementAndGet() }
        )

        transport.close()
        transport.close()

        assertEquals(1, closeCount.get())
    }
}
