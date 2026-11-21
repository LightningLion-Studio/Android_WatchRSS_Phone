package com.lightningstudio.watchrss.phone.data.telemetry

import com.lightningstudio.watchrss.phone.connection.bluetooth.PhoneLoginRequired
import com.lightningstudio.watchrss.phone.connection.bluetooth.ProbeTransportException
import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.B2Exception
import com.lightningstudio.watchrss.phone.features.FeatureDecision
import com.lightningstudio.watchrss.phone.features.RolloutSource
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
        assertFalse(json.has("configStale"))
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
        val propagated = result.exceptionOrNull()
        assertTrue(propagated is IOException)
        assertEquals(error.message, propagated?.message)
        assertTrue(generateSequence(propagated) { it.cause }.any { it === error })
        assertEquals(5, session().runBtSync { 5 })
        assertEquals(listOf("failure", "success"), events.map { it.data.getString("result") })
    }

    @Test
    fun sanitizesSystemAndPeerLabelsAndRecordsStaleOff() = runBlocking {
        val session = BtSyncSession(
            BtSyncDevice("install-0", "fixture", 121, "手表\t", "OPPO-[" + "a".repeat(80) + "]", "14\n/.02", 34),
            BtSyncTrigger.MANUAL, record = { events += it }, now = { 42L }, monotonic = { 42L }
        )
        session.decision = B2RolloutDecision(false, B2Cohort.OFF, 42, configStale = true)
        session.observePeer(JSONObject("""{"success":true,"watchCapabilities":{"model":"AT&T [Watch]+手表"}}"""))
        session.finishSuccess()
        val event = events.single().data
        val labels = event.getJSONObject("device")
        assertTrue(labels.isNull("manufacturer"))
        assertEquals("OPPO-[" + "a".repeat(58), labels.getString("model"))
        assertEquals("14.02", labels.getString("osVersion"))
        assertEquals("AT&T [Watch]+", event.getJSONObject("peer").getString("model"))
        assertTrue(event.getBoolean("configStale"))
        assertEquals(42L, event.getLong("configVersion"))
        assertEquals("b2_off", event.getString("cohort"))
        assertEquals("off", event.getJSONObject("features").getString("b2"))
    }

    @Test
    fun reportsConsistentB2FeatureStateForEachRolloutCohort() = runBlocking {
        val cases = listOf(
            Triple(FeatureDecision(true, RolloutSource.BUCKET), "b2_on", "on"),
            Triple(FeatureDecision(false, RolloutSource.BUCKET), "b2_off", "off"),
            Triple(FeatureDecision(false, RolloutSource.INELIGIBLE), "ineligible", "off"),
            Triple(FeatureDecision(true, RolloutSource.FORCED_ON), "forced_on", "on"),
            Triple(FeatureDecision(false, RolloutSource.FORCED_OFF), "forced_off", "off"),
            Triple(FeatureDecision(true, RolloutSource.DEBUG), "debug_override", "on"),
            Triple(FeatureDecision(false, RolloutSource.DEBUG), "debug_override", "off"),
            Triple(FeatureDecision(false, RolloutSource.STALE), "b2_off", "off")
        )
        for ((result, expectedCohort, expectedFeature) in cases) {
            events.clear()
            val session = session()
            session.decision = B2RolloutDecision(
                result,
                configVersion = 3,
                configStale = result.source == RolloutSource.STALE
            )
            session.finishSuccess()
            val event = events.single().data
            assertEquals(expectedCohort, event.getString("cohort"))
            assertEquals(expectedFeature, event.getJSONObject("features").getString("b2"))
        }
    }

    private fun session(): BtSyncSession {
        var elapsed = 90L
        return BtSyncSession(BtSyncDevice("install-0", "1.4.0-13", 121, "Vendor", "Model", "14", 34),
            BtSyncTrigger.MANUAL, record = { events += it },
            now = { 1_000_000L }, monotonic = { elapsed += 10; elapsed })
    }
}
