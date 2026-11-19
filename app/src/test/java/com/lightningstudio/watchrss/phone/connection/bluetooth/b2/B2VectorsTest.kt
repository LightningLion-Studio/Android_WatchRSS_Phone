package com.lightningstudio.watchrss.phone.connection.bluetooth.b2

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assert.fail as assertionFailed
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

internal fun fixture(name: String) = JSONObject(B2VectorsTest::class.java.getResource("/b2/$name")!!.readText())
internal fun rejected(code: String? = null, work: () -> Unit) {
    try { work(); assertionFailed("expected STOP") } catch (e: B2Exception) { if (code != null) assertEquals(code, e.code) }
}
class B2VectorsTest {
    @Test fun goldenDataAndAckAreByteExactInBothFrozenOracles() {
        for (name in listOf("spec-r3-vectors.json", "review-r3-vectors.json")) {
            val v = fixture(name)
            val data = B2Frames.unhex(v.getString("dataHex"), 99)
            val ack = B2Frames.unhex(v.getString("ackHex"), 64)
            val d = B2Frames.decode(data) as B2Frame.Data
            assertEquals("abc", d.bytes.toString(Charsets.UTF_8)); assertEquals(1, d.header.stream)
            assertEquals(v.getString("requestDigest"), d.header.digest)
            assertArrayEquals(data, B2Frames.encode(d)); assertArrayEquals(ack, B2Frames.encode(B2Frames.decode(ack)))
            assertEquals(v.getString("capabilityDigest"), B2Digests.capability(v.getJSONObject("capability")))
            val selection = B2Negotiation.select(v.getJSONObject("capability"), v.getJSONObject("capability"), "phoneToWatch")!!
            assertEquals(v.getString("selectionDigest"), B2Digests.selection(selection))
            val cap = v.getJSONObject("s2Capability")
            assertEquals("0c1eb2358c10202c551616763a75842daee9dc534d2f9577231145a4e04856b5", B2Digests.capability(cap))
            assertEquals(v.getString("s2CapabilityDigest"), B2Digests.capability(cap))
            assertEquals(v.getString("s2SelectionDigest"), B2Digests.selection(B2Negotiation.select(cap, cap, "phoneToWatch")!!))
            assertEquals(v.getJSONArray("limitsOrder").toString(), JSONArray(B2Wire.limitKeys).toString())
        }
        assertEquals("eb5136ead643582076f1010ed1e24830cb0408ea8ecf9489aff14967fa5a58da", B2Digests.request("00112233445566778899aabbccddeeff", "b".repeat(64), "putChunk", obj("objectId" to "c".repeat(64), "chunkIndex" to 0)))
    }
    @Test fun fixedFrameMutationsStopBeforeUnboundedAllocation() {
        val v = fixture("spec-r3-vectors.json")
        val data = B2Frames.unhex(v.getString("dataHex"), 99)
        for ((offset, value) in listOf(0 to 0x7f, 4 to 0, 8 to 4, 9 to 1, 10 to 1, 12 to 1, 44 to 1)) {
            rejected { B2Frames.decode(data.copyOf().also { it[offset] = value.toByte() }) }
        }
        rejected { B2Frames.decode(data + byteArrayOf(0)) }
        for (size in listOf(0, 1, 4, 15, 95, 98)) {
            try { B2Frames.decode(data.copyOf(size)); assertionFailed() } catch (_: java.io.EOFException) {} catch (_: B2Exception) {}
        }
        val ack = B2Frames.unhex(v.getString("ackHex"), 64)
        for (offset in listOf(20, 40, 44, 57, 63)) rejected { B2Frames.decode(ack.copyOf().also { it[offset] = 1 }) }
    }
    @Test fun strictControlRejectsDuplicateUnicodeUnsafeNumbersAndDepth() {
        for (raw in listOf("{\"a\":1,\"a\":2}", "{\"a\":-0}", "{\"a\":9007199254740992}", "{\"a\":1.0}", "{\"a\":NaN}", "{\"a\":\"\\ud800\"}", "{}{}", "{\"a\":".repeat(18) + "0" + "}".repeat(18))) rejected { B2Json.parse(raw) }
        rejected { B2Json.parse(byteArrayOf(0xc0.toByte(), 0x80.toByte())) }
        rejected { B2Json.parse("{\"a\":[" + List(32768) { "0" }.joinToString(",") + "]}") }
    }
    @Test fun arbitraryReadFragmentationAndGluedFramesRetainAllBytes() {
        val v = fixture("spec-r3-vectors.json")
        val raw = B2Frames.unhex(v.getString("dataHex"), 99) + B2Frames.unhex(v.getString("ackHex"), 64)
        val fragmented = object : ByteArrayInputStream(raw) { override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, minOf(3, len)) }
        assertTrue(B2Frames.read(fragmented) is B2Frame.Data); assertTrue(B2Frames.read(fragmented) is B2Frame.Ack); assertEquals(0, fragmented.available())
    }
    @Test fun raw64KiBHasExactly96BytesOverheadAndBlockWrites() {
        val data = ByteArray(65536) { (it * 31).toByte() }
        val calls = mutableListOf<Int>(); val out = object : ByteArrayOutputStream() { override fun write(b: ByteArray, off: Int, len: Int) { calls += len; super.write(b, off, len) } }
        B2Frames.write(out, B2Frame.Data(B2DataHeader(1, 0, 1, 1, 0, data.size, "0".repeat(32), "1".repeat(64)), data))
        assertEquals(65632, out.size()); assertEquals(4, calls.count { it == 16384 })
        assertArrayEquals(data, (B2Frames.decode(out.toByteArray()) as B2Frame.Data).bytes)
    }
}
