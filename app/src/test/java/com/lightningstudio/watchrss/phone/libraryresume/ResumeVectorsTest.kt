package com.lightningstudio.watchrss.phone.libraryresume

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.io.*

internal fun vectors() = ResumeJson.parse(ResumeVectorsTest::class.java.getResourceAsStream("/library-resume/v1.json")!!.readBytes(), false)
internal fun assertError(code: String, f: () -> Unit) { try { f(); throw AssertionError("expected $code") } catch (e: ResumeException) { assertEquals(code, e.code) } }
class ResumeVectorsTest {
    @Test fun everyCrossLanguageDigestAndRawFrame() {
        val v = vectors()
        assertEquals(v.o("fixed").n("chunkBytes"), ResumeWire.CHUNK.toLong())
        assertEquals(v.o("fixed").n("retentionMillis"), ResumeWire.RETENTION)
        assertEquals(v.o("fixed").n("leaseMillis"), ResumeWire.LEASE)
        assertEquals(v.s("accountScope"), ResumeDigests.account(v.o("account").s("backendBaseUrl"), v.o("account").s("userId")))
        assertEquals(v.s("capabilityDigest"), ResumeDigests.capability(v.o("capability")))
        assertEquals(v.s("selectionDigest"), ResumeDigests.selection(v.o("selection")))
        for (raw in v.a("lp").values()) { val r = raw as JSONObject; assertEquals(r.s("hex"), ResumeDigests.lp(r.s("value")).joinToString("") { "%02x".format(it) }) }
        val requests = v.a("requests").values().map { it as JSONObject }
        for (raw in requests) {
            val bytes = Base64.getDecoder().decode(raw.s("utf8Base64")); val frame = ResumeWire.envelope(ResumeJson.parse(bytes))
            assertEquals(raw.a("phaseArgs").strings(), ResumeDigests.args(frame.s("phase"), frame.o("payload")))
            assertEquals(frame.s("requestDigest"), ResumeDigests.request(if (frame.nil("jobId")) null else frame.s("jobId"), if (frame.nil("planDigest")) null else frame.s("planDigest"), frame.s("phase"), frame.o("payload")))
            val packet = ByteArrayOutputStream(); DataOutputStream(packet).apply { writeInt(bytes.size); write(bytes) }
            assertEquals(frame.s("requestDigest"), ResumeWire.read(ByteArrayInputStream(packet.toByteArray())).s("requestDigest"))
            val out = ByteArrayOutputStream(); ResumeWire.write(out, frame); assertEquals(frame.s("requestDigest"), ResumeWire.read(ByteArrayInputStream(out.toByteArray())).s("requestDigest"))
        }
        assertEquals(8, requests.map { it.o("frame").s("phase") }.toSet().size)
        for (group in listOf("objects", "readerObjects", "assetObjects", "metadataObjects")) for (raw in v.a(group).values()) {
            val d = ResumeWire.descriptor(raw as JSONObject); assertEquals(d.s("objectId"), ResumeDigests.objectId(d)); ResumeMetadata.validate(d)
        }
        val offer = requests.first { it.s("name") == "offer" }.o("frame").o("payload")
        assertEquals(v.s("offerDigest"), ResumeDigests.offer(offer, v.o("capability"), v.o("watchSnapshot")))
        assertEquals(v.s("preparationDigest"), ResumeDigests.preparation(v.s("offerDigest"), v.a("bindings").values().map { it as JSONObject }))
        val begin = requests.first { it.s("name") == "begin" }.o("frame").o("payload")
        assertEquals(v.s("planDigest"), ResumeDigests.plan(begin))
        assertEquals(v.s("objectsRoot"), ResumeDigests.objects(v.a("objects").values().map { (it as JSONObject).s("objectId") }))
        assertEquals(v.s("pageDigest"), ResumeDigests.page(v.s("planDigest"), requests.first { it.s("name") == "manifest" }.o("frame").o("payload")))
        val rs = v.a("receipts").values().map { it as JSONObject }
        for (r in rs) {
            ResumeResults.receipt(r); val frame = requests.first { it.o("frame").s("requestId") == r.s("applyRequestId") }.o("frame")
            assertEquals(r.s("resultDigest"), ResumeDigests.receipt(frame, r.s("objectId"), v.s("watchDeviceId"), "merged").s("resultDigest"))
        }
        val root = ResumeDigests.receipts(rs); assertEquals(v.o("completion").s("receiptsRoot"), root)
        assertEquals(v.o("completion").s("completionDigest"), ResumeDigests.completion(v.a("bindings").getJSONObject(0).s("jobId"), v.s("planDigest"), 2, root, 0).s("completionDigest"))
    }
    @Test fun allBoundaryRecipesAndOversize() {
        for (raw in vectors().a("boundaries").values()) {
            val r = raw as JSONObject; val bytes = ByteArray(r.o("recipe").n("length").toInt()) { r.o("recipe").n("repeatByte").toByte() }
            assertEquals(r.n("size"), bytes.size.toLong()); assertEquals(r.s("sha256"), ResumeDigests.hash(bytes))
            val count = (bytes.size + ResumeWire.CHUNK - 1) / ResumeWire.CHUNK
            assertEquals(r.n("chunkCount"), count.toLong()); assertEquals(r.n("lastChunkBytes"), if (count == 0) 0L else (bytes.size - (count - 1) * ResumeWire.CHUNK).toLong())
        }
        val d = copy(vectors().a("objects").getJSONObject(1)).put("size", 16777217)
        assertError("INVALID_FRAME") { ResumeWire.descriptor(d) }
    }
    @Test fun lexicalAndEnvelopeCounterexamples() {
        for (source in listOf("{\"a\":1,\"a\":2}", "{\"a\":{\"x\":1,\"\\u0078\":2}}", "{\"n\":-0}", "{\"n\":1.0}", "{\"n\":1e0}", "{\"n\":9007199254740992}", "{\"x\":\"\\ud800\"}", "{\"x\":\"\\udc00\"}", "{\"x\":true} trailing", "{\"x\":NaN}", "{\"x\":01}", "{\"x\":1,}", "{\"x\":\"a\n\"}")) assertError("INVALID_FRAME") { ResumeJson.parse(source) }
        assertError("INVALID_FRAME") { ResumeJson.parse(byteArrayOf(123, 34, 120, 34, 58, 34, 0xc0.toByte(), 0x80.toByte(), 34, 125)) }
        assertError("INVALID_FRAME") { ResumeJson.b64("YQ", 3) }; assertError("INVALID_FRAME") { ResumeJson.b64("YR==", 3) }
        val r = vectors().a("requests").getJSONObject(4).o("frame")
        assertError("INVALID_FRAME") { ResumeWire.envelope(copy(r).put("unknown", true)) }
        assertError("INVALID_FRAME") { ResumeWire.envelope(copy(r).put("epoch", "1")) }
        val source = ResumeJson.parse("{\"x\":\"😀\",\"array\":[0,true,null]}"); assertEquals("😀", source.s("x"))
        val packet = ByteArrayOutputStream(); ResumeWire.write(packet, r)
        try { ResumeWire.read(ByteArrayInputStream(packet.toByteArray().dropLast(1).toByteArray())); fail("INVALID_FRAME") } catch (_: EOFException) {}
        assertError("INVALID_FRAME") { ResumeWire.read(ByteArrayInputStream(byteArrayOf(0, 4, 0, 1))) }
    }
    @Test fun metadataRejectsAndKeyOrderUseOriginalBytes() {
        val v = vectors()
        for (raw in v.a("metadataRejects").values()) {
            val r = raw as JSONObject; val original = if (r.s("kind") == "noteAsset") v.a("assetObjects").getJSONObject(0) else v.a("readerObjects").getJSONObject(0)
            val d = copy(original); val bytes = ResumeJson.utf8(r.o("metadata").toString()); d.put("metadataBase64", Base64.getEncoder().encodeToString(bytes)).put("metadataSha256", ResumeDigests.hash(bytes)); d.put("objectId", ResumeDigests.objectId(d))
            assertError(r.s("code")) { ResumeMetadata.validate(d) }
        }
        val a = ResumeJson.utf8("{\"a\":1,\"b\":2}"); val b = ResumeJson.utf8("{\"b\":2,\"a\":1}")
        assertNotEquals(ResumeDigests.hash(a), ResumeDigests.hash(b))
    }
    @Test fun capabilityEchoIsMandatoryAndSelectionsAreDirectional() {
        val probe = obj("action" to "syncLibrary", "phase" to "probe", "version" to 14)
        assertNull(ResumeNegotiation.echoed(probe)); assertFalse(ResumeNegotiation.propose(probe, false).has("supportedLibraryResumeVersions")); assertEquals(1, ResumeNegotiation.propose(probe, true).a("supportedLibraryResumeVersions").getInt(0))
        val phone = ResumeNegotiation.capability(setOf("rssSource"), setOf("articleMetadata")); val watch = ResumeNegotiation.capability(setOf("articleMetadata"), setOf("rssSource"))
        assertEquals("rssSource", ResumeNegotiation.select(phone, watch, "phoneToWatch")!!.a("kinds").getJSONObject(0).s("kind"))
        assertEquals("articleMetadata", ResumeNegotiation.select(phone, watch, "watchToPhone")!!.a("kinds").getJSONObject(0).s("kind"))
        // reconnectCases belong to the separate A/B controller card; not product code here.
    }
    @Test fun preBeginRoutingKeepsUnsupportedPlansOnLegacyAndPages1864ObjectsWithinLimits() {
        val v = vectors(); val selection = v.o("selection")
        val source = v.a("objects").values().map { it as JSONObject }.first { it.s("kind") == "rssSource" }
        fun changed(index: Int): JSONObject {
            val d = copy(source); val m = ResumeMetadata.validate(d).put("url", "https://fixture.invalid/$index")
            val bytes = ResumeJson.utf8(m.toString()); d.put("businessId", m.s("url")).put("metadataBase64", Base64.getEncoder().encodeToString(bytes)).put("metadataSha256", ResumeDigests.hash(bytes))
            return d.put("objectId", ResumeDigests.objectId(d))
        }
        val objects = (0 until 1864).map(::changed).sortedBy { it.s("objectId") }
        val admission = ResumePlanner.preflight("merge", selection, objects) as ResumePlanner.Admission.Ready
        assertEquals(1864, admission.pageObjectCounts.sum()); assertTrue(admission.pageObjectCounts.all { it in 1..16 }); assertTrue(admission.journalReservation <= 67108864)
        assertTrue(ResumePlanner.preflight("phoneToWatchReplace", selection, objects) is ResumePlanner.Admission.UseLegacy)
        assertTrue(ResumePlanner.preflight("merge", selection, objects, 1) is ResumePlanner.Admission.UseLegacy)
        val large = copy(source).put("size", 16777217)
        assertEquals("object_limit", (ResumePlanner.preflight("merge", selection, listOf(large)) as ResumePlanner.Admission.UseLegacy).reason)
        val metadata = copy(source).put("metadataBase64", Base64.getEncoder().encodeToString(ByteArray(16385)))
        assertEquals("metadata_limit", (ResumePlanner.preflight("merge", selection, listOf(metadata)) as ResumePlanner.Admission.UseLegacy).reason)
    }

}
