package com.lightningstudio.watchrss.phone.data.telemetry

import com.lightningstudio.watchrss.phone.connection.bluetooth.PhoneLoginRequired
import com.lightningstudio.watchrss.phone.connection.bluetooth.ProbeTransportException
import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.B2Exception
import com.lightningstudio.watchrss.phone.rollout.B2Cohort
import com.lightningstudio.watchrss.phone.rollout.B2RolloutDecision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID

class BtSyncSessionTest {
    private val events = mutableListOf<BtSyncEvent>()

    @Test
    fun oneTerminalEventPerSessionWithSanitizedFailureAtEveryPhase() = runBlocking {
        for (phase in BtSyncStage.entries) {
            events.clear()
            val session = session().apply { stage = phase }
            session.finishFailure(IllegalStateException("https://example.test/private /home/a secret title",
                B2Exception("BAD:https://example.test/password")))
            session.finishCancelledIfOpen()
            val event = events.single().data
            assertEquals(phase.wire, event.getString("failureStage"))
            assertEquals("failure", event.getString("result"))
            assertEquals("unknown", event.getString("errorCode"))
            assertFalse(event.toString().contains("example.test"))
            assertFalse(event.toString().contains("secret"))
            assertFalse(event.toString().contains("/home"))
        }
    }

    @Test
    fun successStoresWireFieldsAndOnlyKnownPeerAndTransferAttributes() = runBlocking {
        val session = session().apply {
            decision = B2RolloutDecision(true, B2Cohort.ON, 3)
            observePeer(JSONObject("""{"success":true,"watchCapabilities":{"model":"Watch 4","appVersionCode":120},
                "deviceId":"must-not-leak","name":"must-not-leak","url":"https://example.test"}"""))
            binaryStarted()
            bytes(900)
            binaryRequest(JSONObject().put("phase", "manifestPage").put("payload", JSONObject()
                .put("entries", JSONArray().put(JSONObject().put("objectId", "object-1").put("kind", "noteAsset")))),
                incoming = false)
            val chunkRequest = JSONObject().put("payload", JSONObject().put("objectId", "object-1").put("chunkIndex", 0))
            chunk(chunkRequest, incoming = false, length = 600)
            chunk(chunkRequest, incoming = false, length = 600)
            chunk(chunkRequest, incoming = false, length = 600, accepted = true)
            chunk(chunkRequest, incoming = false, length = 600, accepted = true)
            binaryApplied(chunkRequest, incoming = false)
            disconnected()
            binaryRequest(JSONObject("""{"phase":"begin","payload":{"intent":"resume"}}"""), incoming = false)
        }
        session.finishSuccess()
        session.finishFailure(CancellationException())
        val json = events.single().data
        UUID.fromString(json.getString("eventId"))
        assertEquals(10L, json.getLong("durationMs"))
        assertEquals("b2", json.getString("protocol"))
        assertEquals("b2_on", json.getString("cohort"))
        assertEquals("on", json.getJSONObject("features").getString("b2"))
        assertEquals(3, json.getInt("configVersion"))
        assertEquals("phone", json.getString("platform"))
        assertEquals("watch", json.getJSONObject("peer").getString("platform"))
        assertEquals("Watch 4", json.getJSONObject("peer").getString("model"))
        assertEquals(120, json.getJSONObject("peer").getInt("appVersionCode"))
        assertEquals("manual", json.getString("trigger"))
        assertEquals("success", json.getString("result"))
        assertTrue(json.isNull("failureStage"))
        assertTrue(json.isNull("errorCode"))
        assertEquals(1, json.getJSONObject("kinds").getInt("noteAsset"))
        assertEquals(1, json.getJSONObject("transfer").getInt("objects"))
        assertEquals(600, json.getJSONObject("transfer").getInt("bytesEffective"))
        assertEquals(900, json.getJSONObject("transfer").getInt("bytesWire"))
        assertEquals(1, json.getJSONObject("transfer").getInt("disconnects"))
        assertEquals(1, json.getJSONObject("transfer").getInt("resentChunks"))
        assertTrue(json.getJSONObject("transfer").getBoolean("resumed"))
        assertFalse(json.toString().contains("must-not-leak"))
        assertFalse(json.toString().contains("example.test"))
    }

    @Test
    fun earlyFailuresAndCancellationRetainAccurateStageAndProtocol() = runBlocking {
        session().finishDiscoveryFailure("clear_probe_connect")
        session().finishFailure(ProbeTransportException("read", SocketTimeoutException("private")))
        session().finishFailure(PhoneLoginRequired())
        session().finishDiscoveryFailure(null)
        session().finishFailure(CancellationException("private"))
        val expected = listOf("connect" to "connection_failed", "ready" to "timeout", "account" to "account_required",
            "discover" to "not_found", "discover" to "cancelled")
        assertEquals(expected, events.map { it.data.getString("failureStage") to it.data.getString("errorCode") })
        assertTrue(events.all { it.data.getString("protocol") == "none" })
        assertTrue(events.all { it.data.isNull("peer") })
        assertEquals("cancelled", events.last().data.getString("result"))
    }

    @Test
    fun operationWrapperEmitsForThrowAndReturnAndPreservesOriginalError() = runBlocking {
        val error = IOException("private")
        val result = runCatching {
            session().runBtSync { throw error }
        }
        assertTrue(result.exceptionOrNull() === error)
        assertEquals(5, session().runBtSync { 5 })
        assertEquals(listOf("failure", "success"), events.map { it.data.getString("result") })
    }

    private fun session(): BtSyncSession {
        var elapsed = 90L
        return BtSyncSession(BtSyncDevice("install-0", "1.4.0-13", 121, "Vendor", "Model", "14", 34),
            null, BtSyncTrigger.MANUAL, record = { events += it },
            now = { 1_000_000L }, monotonic = { elapsed += 10; elapsed })
    }
}
