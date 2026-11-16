package com.lightningstudio.watchrss.phone.connection.bluetooth.b2

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.nio.file.Files

class B2SnapshotsTest {
    private class ManifestSent : Exception()

    @Test fun threeThousandAssetsReopenOfferAndSendBoundedManifestPages() = runBlocking {
        val root = Files.createTempDirectory("b2-3072").toFile()
        val store = MemoryB2Store(); val account = "3".repeat(64)
        val source = File(root, "one-byte").apply { writeBytes(byteArrayOf(97)) }
        val objects = (0 until 3072).map { assetDescriptor(byteArrayOf(97), "asset-$it.png") }.sortedBy { it.s("objectId") }
        val files = B2Files(File(root, "frozen"))
        val witness = B2Snapshots(store, files).freeze(account, "many-assets", objects) { source }
        val reopened = MemoryB2Store().also { it.rows.putAll(store.rows) }
        val snapshots = B2Snapshots(reopened, B2Files(File(root, "frozen")))
        val saved = reopened.get(account, "snapshot", "many-assets")!!
        assertFalse(saved.has("inventoryUtf8")); assertTrue(saved.n("pageCount") > 1)
        assertEquals(objects.map { it.s("objectId") }, snapshots.descriptors(account, witness).map { it.s("objectId") })
        assertEquals(B2Digests.hash(B2Json.utf8(JSONArray(objects).toString())), witness.s("manifestDigest"))
        val cap = B2Negotiation.capability(setOf("noteAsset"), setOf("noteAsset"))
        val selection = B2Negotiation.select(cap, cap, "phoneToWatch")!!
        val ready = B2Planner.preflight("merge", selection, snapshots.descriptors(account, witness)) as B2Planner.Admission.Ready
        val peer = obj("snapshotId" to "peer", "manifestDigest" to B2Digests.hash("[]".toByteArray()), "fromExclusive" to 0, "toInclusive" to 0)
        val job = "1".repeat(32); val binding = obj("direction" to "phoneToWatch", "jobId" to job, "intent" to "create", "selection" to selection)
        val session = B2Session(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream(), this, {})
        val received = mutableListOf<String>(); var pages = 0; var offers = 0
        val client = B2Client(reopened, snapshots, { request ->
            val raw = B2Frames.encode(B2Frame.Control(request))
            assertTrue(raw.size <= 131088)
            val decoded = (B2Frames.decode(raw) as B2Frame.Control).value
            val p = decoded.o("payload")
            val result = when (decoded.s("phase")) {
                "prepare" -> if (p.s("step") == "offer") {
                    offers++
                    obj("step" to "offer", "watchCapabilities" to cap, "watchSnapshot" to peer, "offerDigest" to B2Digests.offer(p, cap, peer))
                } else obj("step" to "confirm", "preparationDigest" to p.s("preparationDigest"))
                "begin" -> obj("disposition" to "CREATED", "selectionDigest" to p.s("selectionDigest"), "state" to "PLANNING", "expiresAtMillis" to 86400000, "nextManifestPage" to 0, "completion" to null)
                "manifestPage" -> {
                    assertTrue(p.a("entries").length() <= 16)
                    received += p.a("entries").values().map { (it as org.json.JSONObject).s("objectId") }; pages++
                    val done = pages == ready.pageObjectCounts.size
                    obj("pageIndex" to p.n("pageIndex"), "pageDigest" to p.s("pageDigest"), "nextManifestPage" to pages, "manifestVerified" to done, "state" to if (done) "READY" else "PLANNING")
                }
                "missing" -> throw ManifestSent()
                else -> error("Unexpected phase")
            }
            B2Results.answer(decoded, result, if (decoded.s("phase") == "prepare") null else 1)
        }, {}, session)
        try {
            client.acceptProbe(obj("libraryBinary" to cap)); client.offer(account, "phone", "watch", cap, witness)
            client.confirm(listOf(binding)) {}
            try { client.send(job); error("Expected manifest boundary") } catch (_: ManifestSent) {}
            assertEquals(1, offers); assertEquals(ready.pageObjectCounts.size, pages)
            assertEquals(objects.map { it.s("objectId") }, received)
        } finally { session.close(); root.deleteRecursively() }
        Unit
    }

    @Test fun oldFrozenInventoryMigratesWithoutChangingItsWitness() = runBlocking {
        val store = MemoryB2Store(); val ds = (0 until 40).map { assetDescriptor(byteArrayOf(97), "old-$it.png") }.sortedBy { it.s("objectId") }
        val raw = JSONArray(ds).toString()
        val witness = obj("snapshotId" to "old", "manifestDigest" to B2Digests.hash(B2Json.utf8(raw)), "fromExclusive" to 0, "toInclusive" to 0)
        store.put("s", "snapshot", "old", obj("witness" to witness, "inventoryUtf8" to raw))
        val root = Files.createTempDirectory("b2-old-snapshot").toFile()
        try {
            val snapshots = B2Snapshots(store, B2Files(root))
            assertEquals(40, snapshots.descriptors("s", witness).size)
            assertFalse(store.get("s", "snapshot", "old")!!.has("inventoryUtf8"))
            assertEquals(40, B2Snapshots(store, B2Files(root)).descriptors("s", witness).size)
        } finally { root.deleteRecursively() }
        Unit
    }

    @Test fun aSingleControlStillStopsAtTheUnchangedNodeLimit() {
        val body = B2Json.utf8("{\"nodes\":[" + List(32768) { "0" }.joinToString(",") + "]}")
        assertTrue(body.size < 131072)
        rejected("INVALID_FRAME") { B2Json.parse(body) }
        val frame = ByteArrayOutputStream().also { stream -> DataOutputStream(stream).apply {
            writeInt(12 + body.size); writeInt(0x4c425632); writeByte(1); writeByte(0); writeShort(0); writeInt(body.size); write(body)
        } }.toByteArray()
        rejected("INVALID_FRAME") { B2Frames.decode(frame) }
    }
}
