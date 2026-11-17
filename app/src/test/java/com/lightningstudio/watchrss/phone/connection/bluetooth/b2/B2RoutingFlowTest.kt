package com.lightningstudio.watchrss.phone.connection.bluetooth.b2

import com.lightningstudio.watchrss.phone.connection.bluetooth.B2KindRoute
import com.lightningstudio.watchrss.phone.connection.bluetooth.BluetoothSyncProtocol
import com.lightningstudio.watchrss.phone.connection.bluetooth.LibrarySyncPayload
import com.lightningstudio.watchrss.phone.connection.bluetooth.NoteAssetSyncPayload
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assert.fail as assertionFailed
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class B2RoutingFlowTest {
    @Test fun allFrozenPeerAndReadyFixtures() {
        val cases = fixture("peer-fixtures.json").getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i); val r = c.getJSONObject("ready")
            val got = try { B2Routing.classify(c.getJSONObject("probe"), B2Routing.Ready(r.getBoolean("identityVerified"), r.getBoolean("accountConfirmed"), r.getBoolean("legacyAcksDrained"))).name }
            catch (e: B2Exception) { e.code }
            assertEquals(c.getString("name"), c.getString("expected"), got)
        }
    }
    @Test fun s2S2S2V1V1S2AndMissingKindRouting() {
        val p = fixture("peer-fixtures.json").getJSONArray("cases").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.first { it.getString("expected") == "B2" }.getJSONObject("probe") }
        val cap = fixture("spec-r3-vectors.json").getJSONObject("s2Capability")
        val ready = B2Routing.Ready(true, true, true)
        assertTrue(B2Routing.selected(p, ready, true, cap, "phoneToWatch", "rssSource"))
        assertFalse(B2Routing.selected(p, ready, false, cap, "phoneToWatch", "rssSource"))
        assertFalse(B2Routing.selected(p, ready, true, cap, "phoneToWatch", "noteAsset"))
        val v1 = JSONObject(p.toString()).apply { remove("libraryBinary") }
        assertFalse(B2Routing.selected(v1, ready, true, cap, "phoneToWatch", "rssSource"))
        assertEquals(B2Routing.Peer.WAIT_READY, B2Routing.classify(p, B2Routing.Ready(true, false, true)))
        for ((n, m) in listOf(1 to 1, 1 to 8, 8 to 1)) {
            val a = JSONObject(cap.toString()); val b = JSONObject(cap.toString())
            a.getJSONObject("limits").put("maxInFlightChunks", n).put("maxInFlightBytes", n * 65536)
            b.getJSONObject("limits").put("maxInFlightChunks", m).put("maxInFlightBytes", m * 65536)
            assertEquals(1, B2Negotiation.select(a, b, "phoneToWatch")!!.getJSONObject("limits").getInt("maxInFlightChunks"))
        }
        val bad = JSONObject(p.toString()); bad.getJSONObject("libraryBinary").put("chunkSizes", JSONArray(listOf(131072)))
        rejected("CAPABILITY_MISMATCH") { B2Routing.classify(bad, ready) }
    }
    @Test fun asymmetricKindProbeKeepsTheUnselectedDirectionOnLegacy() {
        val kindSet = setOf("articleBody", "articleMetadata", "noteAsset", "rssSource")
        val probe = obj("success" to true, "version" to 14, "action" to "syncLibrary", "phase" to "probe",
            "libraryPeer" to obj("implementation" to "podjs", "appVersion" to "2.0.3"),
            "libraryBinary" to B2Negotiation.capability(setOf("noteAsset", "rssSource"), kindSet))
        val route = B2KindRoute.select(probe, true, true)
        assertEquals(kindSet, route.send)
        assertEquals(setOf("noteAsset"), route.receive)
        assertTrue(route.needsLegacyLibrary)
        assertTrue(route.receiveLegacyArticles)
        assertFalse(route.sendLegacyArticles)
        assertFalse(route.sendLegacySources)
        probe.put("libraryBinary", B2Negotiation.capability(kindSet, kindSet))
        assertFalse(B2KindRoute.select(probe, true, true).needsLegacyLibrary)
        probe.put("libraryBinary", B2Negotiation.capability(setOf("noteAsset"), setOf("noteAsset")))
        assertTrue(B2KindRoute.select(probe, true, true).isAssetOnly)
        probe.remove("libraryBinary")
        assertTrue(B2KindRoute.select(probe, true, true).isEmpty)
    }
    @Test fun disabledPathMatchesPinnedLegacyWireForEveryFixtureAndRequest() {
        val probes = fixture("peer-fixtures.json").getJSONArray("cases")
        val requests = listOf(LibrarySyncPayload.buildProbeRequest("phone-fixed"),
            NoteAssetSyncPayload.chunk("picture.png", B2Digests.hash("abc".toByteArray()), 0, 1, "abc".toByteArray()),
            JSONObject().put("action", "syncLibrary").put("body", JSONObject().put("chunks", JSONArray(listOf("YWJj"))))) +
            (0 until probes.length()).map { probes.getJSONObject(it).getJSONObject("probe") }
        for (p in requests) {
            // ae22f23 BluetoothSyncProtocol: 4-byte BE length + JSONObject UTF-8.
            val bytes = p.toString().toByteArray(Charsets.UTF_8)
            val baseline = ByteArrayOutputStream().also { DataOutputStream(it).apply { writeInt(bytes.size); write(bytes); flush() } }.toByteArray()
            val actual = ByteArrayOutputStream()
            assertSame(p, B2Routing.probe(p, false))
            BluetoothSyncProtocol.writeFrame(actual, B2Routing.probe(p, false))
            assertArrayEquals(baseline, actual.toByteArray())
        }
        // Malformed/unknown capabilities are not even inspected with the local flag off.
        assertFalse(B2Routing.selected(JSONObject().put("libraryBinary", "bad"), B2Routing.Ready(false, false, false), false, JSONObject(), "phoneToWatch", "noteAsset"))
    }
    @Test fun singleWindowNegativeAckAndSequenceAccounting() {
        val flow = B2Flow(1); flow.bind("a", "b", "c", 1)
        val id = "1".repeat(32); val digest = "2".repeat(64)
        val first = flow.reserve(0, 0, 65536, id, digest, "o")
        assertEquals(65536, flow.pendingBytes)
        rejected("WINDOW_FULL") { flow.reserve(0, 1, 1, id, digest, "o") }
        flow.negative("a", "b", 1, id, digest); assertEquals(0, flow.pendingBytes)
        rejected { flow.negative("a", "b", 1, id, digest) }
        val retry = flow.reserve(0, 0, 65536, id, digest, "o")
        assertEquals(first.seq + 1, retry.seq); assertEquals(first.requestId, retry.requestId)
        rejected { flow.settle(B2Frame.Ack(1, 1, 2, listOf(B2AckEntry(2, 1)))) }
        assertEquals(1, flow.pendingCount)
        flow.settle(B2Frame.Ack(1, 1, 1, listOf(B2AckEntry(2, 1)))); assertEquals(0, flow.pendingCount)
        flow.reserve(0, 1, 1, id, digest, "o")
        assertTrue(flow.settle(B2Frame.Ack(1, 1, 1, listOf(B2AckEntry(999, 2)))).isEmpty())
        assertEquals(1, flow.pendingBytes)
        flow.bind("a", "b", "c", 2); assertEquals(0, flow.pendingCount)
        assertEquals(1, flow.reserve(0, 1, 1, id, digest, "o").seq)
        rejected("PLAN_CONFLICT") { flow.bind("different", "b", "c", 2) }
    }
    @Test fun receiveSequencesConsumeWaitAttemptAndDoNotAcceptGaps() {
        val flow = B2Flow(2); flow.bind("a", "b", "c", 1)
        val h = B2DataHeader(2, 0, 1, 1, 0, 3, "1".repeat(32), "2".repeat(64))
        flow.receive(h); rejected { flow.receive(h) }; rejected { flow.receive(h.copy(seq = 3)) }
        flow.receive(h.copy(seq = 2))
        assertEquals(64, B2Frames.encode(flow.durable(h.copy(seq = 2), false)).size)
    }
    @Test fun threeRecoveryRulesIncludingBoundaryAndMetadataProgress() {
        val failures = B2Recovery(); failures.recoveryFailed(); failures.recoveryFailed(); rejected { failures.recoveryFailed() }
        val boundary = B2Recovery(); boundary.disconnected("1", 0); boundary.disconnected("2", 5000); rejected { boundary.disconnected("3", 10000) }
        val outside = B2Recovery(); outside.disconnected("1", 0); outside.disconnected("2", 5000); outside.disconnected("3", 10001)
        val empty = B2Recovery(); empty.ready(false); empty.disconnected("initial", 0)
        for (i in 1..2) { empty.ready(true); empty.disconnected("c$i", i * 20000L) }
        empty.ready(true); rejected { empty.disconnected("c3", 60000) }
        val metadata = B2Recovery()
        repeat(5) { metadata.ready(true); metadata.confirmed("applied:metadata-version-$it"); metadata.disconnected("p$it", it * 20000L) }
        // New job IDs cannot disguise repeated semantic content.
        repeat(2) { metadata.ready(true); metadata.confirmed("applied:metadata-version-4"); metadata.disconnected("repeat$it", 200000 + it * 20000L) }
        metadata.ready(true); rejected { metadata.disconnected("repeat3", 260000) }
    }
    @Test fun independentAckAndReplayRateBucketsAndBoundedBackoff() {
        var now = 0L; val bucket = B2Rate(32.0, 16.0) { now }
        repeat(32) { bucket.take() }; rejected("RATE_LIMITED") { bucket.take() }
        now = 1000; repeat(16) { bucket.take() }
        val b = B2Backoff(); assertEquals(listOf(100L, 200L, 400L, 800L, 1000L, 1000L), List(6) { b.next() })
    }
    @Test fun progressKeyIgnoresNewJobAndMetadataFormatting() {
        val a = assetDescriptor("abc".toByteArray())
        val b = JSONObject(a.toString()).put("metadataUtf8", "  " + a.s("metadataUtf8") + "  ")
        b.put("metadataSha256", B2Digests.hash(B2Json.utf8(b.s("metadataUtf8"))))
        b.put("objectId", B2Digests.objectId(b))
        assertNotEquals(a.s("objectId"), b.s("objectId"))
        assertEquals(B2Progress.applied(a), B2Progress.applied(b))
        assertEquals(B2Progress.chunk(a, 0), B2Progress.chunk(b, 0))
    }

    @Test fun sequenceExhaustionRequiresNewConnectionWithoutWrapping() {
        val flow = B2Flow(1); flow.bind("job", "plan", "selection", 1)
        B2Flow::class.java.getDeclaredField("nextTx").apply { isAccessible = true; setLong(flow, B2Json.MAX_SAFE + 1) }
        rejected("SEQUENCE_EXHAUSTED") { flow.reserve(0, 0, 1, "1".repeat(32), "2".repeat(64), "o") }
        assertEquals(0, flow.pendingCount)
    }

    @Test fun dependenciesAndExistingActiveObjectsPrecedeNewAdmission() {
        fun d(id: String, vararg deps: String) = obj("objectId" to id, "dependsOn" to JSONArray(deps.toList()))
        val objects = listOf(d("00", "ff"), d("01", "ff"), d("02", "ff"), d("03", "ff"), d("ff"))
        for (slots in listOf(1, 4)) assertEquals(listOf("ff"), B2Scheduling.runnable(objects, emptySet(), emptyMap(), slots).map { it.s("objectId") })
        assertEquals(listOf("03"), B2Scheduling.runnable(objects, setOf("ff"), mapOf("03" to "PARTIAL"), 1).map { it.s("objectId") })
        assertEquals(listOf("00", "01", "02", "03"), B2Scheduling.runnable(objects, setOf("ff"), mapOf("03" to "PARTIAL"), 4).map { it.s("objectId") })
    }

}
