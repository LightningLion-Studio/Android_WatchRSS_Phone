package com.lightningstudio.watchrss.phone.connection.bluetooth.b2

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assert.fail as assertionFailed
import org.junit.Test
import java.io.File
import java.nio.file.Files

internal class MemoryB2Store : B2Persistence {
    private val lane = Mutex()
    val rows = java.util.concurrent.ConcurrentHashMap<Triple<String, String, String>, String>()
    override suspend fun <T> exclusive(block: suspend () -> T): T = lane.withLock { block() }
    override suspend fun <T> transaction(block: suspend () -> T): T {
        val before = rows.toMap()
        try { return block() } catch (e: Throwable) { rows.clear(); rows.putAll(before); throw e }
    }
    override suspend fun get(scope: String, ns: String, id: String) = rows[Triple(scope, ns, id)]?.let { B2Json.parse(it, false) }
    override suspend fun list(scope: String, ns: String) = rows.filter { it.key.first == scope && it.key.second == ns }.map { it.key.third to B2Json.parse(it.value, false) }.sortedBy { it.first }
    override suspend fun all() = rows.map { B2Persistence.Record(it.key.first, it.key.second, it.key.third, it.value) }
    override suspend fun put(scope: String, ns: String, id: String, value: JSONObject) { rows[Triple(scope, ns, id)] = value.toString() }
    override suspend fun delete(scope: String, ns: String, id: String) { rows.remove(Triple(scope, ns, id)) }
}
internal fun assetDescriptor(bytes: ByteArray, name: String = "picture.png"): JSONObject {
    val hash = B2Digests.hash(bytes)
    val chunkHashes = bytes.indices.step(B2Wire.CHUNK).map { offset ->
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update(bytes, offset, minOf(B2Wire.CHUNK, bytes.size - offset))
        B2Frames.hex(digest.digest())
    }
    val metadata = obj("storageKey" to name, "sha256" to hash, "size" to bytes.size).toString()
    val d = obj("objectId" to "0".repeat(64), "kind" to "noteAsset", "schemaVersion" to 1,
        "businessId" to name, "contentVersion" to hash, "encoding" to "raw", "size" to bytes.size, "chunkBytes" to 65536,
        "sha256" to hash, "chunkHashes" to JSONArray(chunkHashes),
        "metadataUtf8" to metadata, "metadataSha256" to B2Digests.hash(B2Json.utf8(metadata)), "dependsOn" to JSONArray())
    return d.put("objectId", B2Digests.objectId(d)).also { B2Wire.descriptor(it) }
}
internal class ReceiverRig(val bytes: ByteArray = "abc".toByteArray(), val store: MemoryB2Store = MemoryB2Store(), val root: File = Files.createTempDirectory("b2-test").toFile()) {
    val account = B2Digests.account("https://example.invalid", "test-user")
    val descriptor = assetDescriptor(bytes)
    val files = B2Files(root)
    val cap = B2Negotiation.capability(setOf("noteAsset"), setOf("noteAsset"))
    val selection = B2Negotiation.select(cap, cap, "watchToPhone")!!
    val snapshot = obj("snapshotId" to "frozen", "manifestDigest" to "d".repeat(64), "fromExclusive" to 0, "toInclusive" to 0)
    val job = "1".repeat(32)
    var now = 0L
    var authorized = true
    var commits = 0
    var fault: String? = null
    val binding get() = obj("direction" to "watchToPhone", "jobId" to job, "intent" to "create", "selection" to selection)
    val description get() = obj("intent" to "create", "accountScope" to account, "phoneDeviceId" to "phone", "watchDeviceId" to "watch", "direction" to "watchToPhone", "phoneSnapshot" to snapshot, "watchSnapshot" to snapshot,
        "selection" to selection, "selectionDigest" to B2Digests.selection(selection), "objectCount" to 1, "objectsRoot" to B2Digests.objects(listOf(descriptor.s("objectId"))), "pageObjectCounts" to JSONArray(listOf(1)))
    val plan get() = B2Digests.plan(description)
    fun receiver(intent: String = "create") = B2Receiver(store, files,
        B2Authorization(account, "phone", "watch", snapshot, snapshot, listOf(binding.put("intent", intent))) { authorized },
        object : B2BusinessPort {
            override val kinds = setOf("noteAsset")
            override suspend fun apply(descriptor: JSONObject, metadata: JSONObject, staged: File): String {
                assertArrayEquals(bytes, staged.readBytes())
                store.put(account, "business", "picture.png", obj("fileHash" to B2Digests.hash(staged.readBytes())))
                commits++
                return "merged"
            }
        }, { now }, { if (it == fault) throw B2Exception("LOCAL_IO") })
    suspend fun begin(r: B2Receiver, intent: String = "create") = r.handle(B2Wire.request("begin", job, plan, null, description.put("intent", intent))).n("epoch")
    suspend fun manifest(r: B2Receiver, epoch: Long) {
        val p = obj("pageIndex" to 0, "startIndex" to 0, "previousPageDigest" to "0".repeat(64), "pageDigest" to "0".repeat(64), "entries" to JSONArray(listOf(descriptor)))
        p.put("pageDigest", B2Digests.page(plan, p)); r.handle(B2Wire.request("manifestPage", job, plan, epoch, p))
    }
    fun data(epoch: Long, seq: Long = 1, body: ByteArray = bytes): B2Frame.Data {
        val r = B2Wire.request("putChunk", job, plan, epoch, obj("objectId" to descriptor.s("objectId"), "chunkIndex" to 0), "2".repeat(32))
        return B2Frame.Data(B2DataHeader(2, 0, epoch, seq, 0, body.size, r.s("requestId"), r.s("requestDigest")), body)
    }
    suspend fun put(r: B2Receiver, epoch: Long, seq: Long = 1, body: ByteArray = bytes): B2Frame.Ack {
        val d = data(epoch, seq, body); val request = r.inspect(d.header).first; return r.data(d, request)
    }
    suspend fun apply(r: B2Receiver, epoch: Long) = r.handle(B2Wire.request("apply", job, plan, epoch, obj("objectId" to descriptor.s("objectId"))))
    suspend fun missing(r: B2Receiver, epoch: Long) = r.handle(B2Wire.request("missing", job, plan, epoch, obj("afterObjectId" to null, "afterChunkIndex" to null, "limit" to 1))).o("result")
}
class B2DurabilityTest {
    @Test fun guardedApplyRefusesHeadChangeAfterDurableObjectBeforeBusinessCommit() = runBlocking<Unit> {
        val rig = ReceiverRig()
        var version = B2CommitWindow.Version(
            B2CommitWindow.Head("scope", "original", "1", "0"), "1", "original-business"
        )
        val auth = B2Authorization(rig.account, "phone", "watch", rig.snapshot, rig.snapshot, listOf(rig.binding)) {
            true
        }.apply { commitWindow = B2CommitWindow { version } }
        val receiver = B2Receiver(rig.store, rig.files, auth, object : B2BusinessPort {
            override val kinds = setOf("noteAsset")
            override suspend fun apply(descriptor: JSONObject, metadata: JSONObject, staged: File): String {
                rig.commits++
                return "merged"
            }
        }, fault = {
            if (it == "afterObjectDurable") {
                version = version.copy(head = version.head.copy(oid = "replacement", revision = "2"))
            }
        })
        try {
            val epoch = rig.begin(receiver)
            rig.manifest(receiver, epoch)
            rig.put(receiver, epoch)
            val error = runCatching { rig.apply(receiver, epoch) }.exceptionOrNull() as B2Exception
            assertEquals("BUSINESS_REJECTED", error.code)
            assertEquals(0, rig.commits)
            assertTrue(rig.store.list(rig.account, "object").single().second.nil("receipt"))
            assertTrue(rig.files.composed(rig.account, rig.job, rig.descriptor.s("objectId")).isFile)
        } finally {
            rig.root.deleteRecursively()
        }
    }

    @Test fun guardedAttachmentResumesBothDirectionsWithoutResendingDurableBlocks() = runBlocking<Unit> {
        val total = 5 * 1024 * 1024 / B2Wire.CHUNK
        val beforeDrop = 27
        for (direction in listOf("phoneToWatch", "watchToPhone")) {
            val rig = ReceiverRig(ByteArray(total * B2Wire.CHUNK) { (it * 7 + it / B2Wire.CHUNK).toByte() })
            try {
                val stream = if (direction == "phoneToWatch") 1 else 2
                val cap = B2Negotiation.capability(setOf("noteAsset"), setOf("noteAsset"), B2Wire.s4Limits())
                val selection = B2Negotiation.select(cap, cap, direction)!!
                val description = rig.description.put("direction", direction).put("selection", selection)
                    .put("selectionDigest", B2Digests.selection(selection))
                val plan = B2Digests.plan(description)
                val oid = rig.descriptor.s("objectId")
                val frames = mutableListOf<Int>()
                val version = B2CommitWindow.Version(B2CommitWindow.Head("scope", "head", "1", "0"), "1", "business")
                val business = object : B2BusinessPort {
                    override val kinds = setOf("noteAsset")
                    override suspend fun apply(descriptor: JSONObject, metadata: JSONObject, staged: File): String {
                        assertArrayEquals(rig.bytes, staged.readBytes())
                        rig.commits++
                        return "merged"
                    }

                    override suspend fun receiptWitness(context: B2ApplyContext?, descriptor: JSONObject,
                        metadata: JSONObject, prior: JSONObject?): JSONObject? =
                        if (rig.commits == 1) obj("sha256" to descriptor.s("sha256")) else null
                }
                fun receiver(intent: String): B2Receiver {
                    val binding = rig.binding.put("direction", direction).put("selection", selection).put("intent", intent)
                    val auth = B2Authorization(rig.account, "phone", "watch", rig.snapshot, rig.snapshot, listOf(binding)) { true }
                    auth.commitWindow = B2CommitWindow { version }
                    return B2Receiver(rig.store, rig.files, auth, business, receiveDirection = direction)
                }
                suspend fun send(target: B2Receiver, epoch: Long, seq: Long, index: Int) {
                    val request = B2Wire.request("putChunk", rig.job, plan, epoch,
                        obj("objectId" to oid, "chunkIndex" to index))
                    val bytes = rig.bytes.copyOfRange(index * B2Wire.CHUNK, (index + 1) * B2Wire.CHUNK)
                    val frame = B2Frame.Data(B2DataHeader(stream, 0, epoch, seq, index, bytes.size,
                        request.s("requestId"), request.s("requestDigest")), bytes)
                    val checked = target.inspect(frame.header).first
                    frames += index
                    assertEquals(1, target.data(frame, checked).entries.single().disposition)
                }
                val first = receiver("create")
                val epoch = first.handle(B2Wire.request("begin", rig.job, plan, null, description)).n("epoch")
                val page = obj("pageIndex" to 0, "startIndex" to 0, "previousPageDigest" to "0".repeat(64),
                    "pageDigest" to "0".repeat(64), "entries" to JSONArray(listOf(rig.descriptor)))
                page.put("pageDigest", B2Digests.page(plan, page))
                first.handle(B2Wire.request("manifestPage", rig.job, plan, epoch, page))
                for (index in 0 until beforeDrop) send(first, epoch, index + 1L, index)
                first.fenceNow()
                first.closeIndex()
                val resumed = receiver("resume")
                val reply = resumed.handle(B2Wire.request("begin", rig.job, plan, null, copy(description).put("intent", "resume")))
                val newEpoch = reply.n("epoch")
                assertEquals(epoch + 1, newEpoch)
                val missing = resumed.handle(B2Wire.request("missing", rig.job, plan, newEpoch,
                    obj("afterObjectId" to null, "afterChunkIndex" to null, "limit" to 1))).o("result")
                val ranges = missing.a("items").getJSONObject(0).a("missingRanges")
                assertEquals("[[$beforeDrop,$total]]", ranges.toString())
                var seq = 0L
                for (range in ranges.values().map { it as JSONArray }) {
                    for (index in range.getInt(0) until range.getInt(1)) send(resumed, newEpoch, ++seq, index)
                }
                val receipt = resumed.handle(B2Wire.request("apply", rig.job, plan, newEpoch,
                    obj("objectId" to oid))).o("result").o("receipt")
                val result = resumed.handle(B2Wire.request("complete", rig.job, plan, newEpoch,
                    obj("objectCount" to 1, "receiptsRoot" to B2Digests.receipts(listOf(receipt)), "ackToInclusive" to 0))).o("result")
                assertEquals("COMPLETE", result.s("state"))
                assertEquals((0 until total).toList(), frames)
                assertEquals(1, rig.commits)
                resumed.closeIndex()
            } finally {
                rig.root.deleteRecursively()
            }
        }
    }

    @Test fun rawDataDurableBeforeAckAndAppliedSharesBusinessTransaction() = runBlocking {
        val x = ReceiverRig(); val r = x.receiver(); val e = x.begin(r); x.manifest(r, e)
        val ack = x.put(r, e)
        assertEquals(1, ack.entries.single().disposition)
        assertTrue(x.store.list(x.account, "chunk").isNotEmpty())
        assertEquals(0, x.commits)
        val applied = x.apply(r, e); assertEquals("APPLIED", applied.o("result").s("state"))
        assertNotNull(x.store.get(x.account, "business", "picture.png"))
        assertEquals(1, x.commits)
        val duplicate = x.apply(r, e); assertEquals(applied.o("result").o("receipt").s("resultDigest"), duplicate.o("result").o("receipt").s("resultDigest"))
        assertEquals(1, x.commits)
        assertEquals(2, x.put(r, e, 2).entries.single().disposition)
        try { x.put(r, e, 3, "abd".toByteArray()); assertionFailed() } catch (v: B2Exception) { assertEquals("HASH_MISMATCH", v.code) }
        x.root.deleteRecursively(); Unit
    }
    @Test fun disconnectAfterDurableRechecksMissingAndFencesOldGeneration() = runBlocking {
        val x = ReceiverRig(); val old = x.receiver(); val e = x.begin(old); x.manifest(old, e); x.put(old, e)
        old.fenceNow()
        val current = x.receiver("resume"); val next = x.begin(current, "resume"); assertEquals(e + 1, next)
        assertEquals(0, x.missing(current, next).a("items").getJSONObject(0).a("missingRanges").length())
        try { x.apply(old, e); assertionFailed() } catch (v: B2Exception) { assertEquals("AUTH_REVOKED", v.code) }
        // A corrupt local cache becomes missing, rather than accepting its historic receipt.
        x.files.chunk(x.account, x.job, x.descriptor.s("objectId"), 0).writeText("bad")
        assertEquals("[[0,1]]", x.missing(current, next).a("items").getJSONObject(0).a("missingRanges").toString())
        x.put(current, next); x.apply(current, next); assertEquals(1, x.commits)
        x.root.deleteRecursively(); Unit
    }
    @Test fun applyCommitFailureRollsBackBusinessAndReceiptTogether() = runBlocking {
        val x = ReceiverRig(); val r = x.receiver(); val e = x.begin(r); x.manifest(r, e); x.put(r, e)
        x.fault = "beforeApplyCommit"
        try { x.apply(r, e); assertionFailed() } catch (v: B2Exception) { assertEquals("LOCAL_IO", v.code) }
        assertNull(x.store.get(x.account, "business", "picture.png"))
        assertTrue(x.store.list(x.account, "object").single().second.nil("receipt"))
        x.fault = null; x.apply(r, e); assertNotNull(x.store.get(x.account, "business", "picture.png"))
        x.root.deleteRecursively(); Unit
    }
    @Test fun completeResumeReturnsOriginalReceiptWithoutRequiringReleasedBytes() = runBlocking {
        val x = ReceiverRig(); val r = x.receiver(); val e = x.begin(r); x.manifest(r, e); x.put(r, e)
        val receipt = x.apply(r, e).o("result").o("receipt")
        val p = obj("objectCount" to 1, "receiptsRoot" to B2Digests.receipts(listOf(receipt)), "ackToInclusive" to 0)
        val complete = r.handle(B2Wire.request("complete", x.job, x.plan, e, p)).o("result")
        val resumed = x.receiver("resume"); val newer = x.begin(resumed, "resume")
        assertEquals(complete.s("completionDigest"), x.missing(resumed, newer).o("completion").s("completionDigest"))
        assertEquals(1, x.commits); x.root.deleteRecursively(); Unit
    }
    @Test fun planningHeartbeatBeforeAdmissionReturnsNoRangesAndDataIsBlocked() = runBlocking {
        val x = ReceiverRig(); val r = x.receiver(); val e = x.begin(r)
        repeat(5) {
            x.now = it * 10000L
            val reply = r.heartbeat(B2Wire.request("missing", x.job, x.plan, e, obj("afterObjectId" to null, "afterChunkIndex" to null, "limit" to 0))).o("result")
            assertEquals("PLANNING", reply.s("state")); assertEquals(0, reply.a("items").length()); assertTrue(reply.nil("next")); assertTrue(reply.getBoolean("done"))
        }
        assertEquals(0, x.files.dataBytes())
        try { x.apply(r, e); assertionFailed() } catch (v: B2Exception) { assertEquals("MANIFEST_INCOMPLETE", v.code) }
        x.authorized = false
        rejected("AUTH_REVOKED") { r.heartbeat(B2Wire.request("missing", x.job, x.plan, e, obj("afterObjectId" to null, "afterChunkIndex" to null, "limit" to 0))) }
        x.root.deleteRecursively(); Unit
    }
    @Test fun cancelIsDurableAndCannotReviveJob() = runBlocking {
        val x = ReceiverRig(); val r = x.receiver(); val e = x.begin(r); x.manifest(r, e)
        r.handle(B2Wire.request("cancel", x.job, x.plan, e, obj("reason" to "USER_CANCEL")))
        val resumed = x.receiver("resume"); val n = x.begin(resumed, "resume")
        assertEquals("CANCELLED", x.missing(resumed, n).s("state"))
        try { x.apply(resumed, n); assertionFailed() } catch (v: B2Exception) { assertEquals("JOB_CANCELLED", v.code) }
        assertEquals(0, x.commits); x.root.deleteRecursively(); Unit
    }
    @Test fun sameConnectionEpochBarrierDrainsOldDataWithoutConsumingNewSequence() = runBlocking {
        val x = ReceiverRig(); val r = x.receiver(); val oldEpoch = x.begin(r); x.manifest(r, oldEpoch)
        x.now = 30001
        val newEpoch = x.begin(r, "resume"); assertEquals(oldEpoch + 1, newEpoch)
        val old = x.data(oldEpoch)
        val inspected = r.inspect(old.header); assertTrue(r.isOld(old.header))
        B2Frames.drain(java.io.ByteArrayInputStream(old.bytes), old.bytes.size, inspected.second)
        assertTrue(x.store.list(x.account, "chunk").isEmpty())
        assertEquals(1, x.put(r, newEpoch).entries.single().txSeq)
        x.root.deleteRecursively(); Unit
    }
    @Test fun oneObjectBidirectionalWindowWaitsForForwardCompleteAndDrainsBeforeNegative() = runBlocking {
        val x = ReceiverRig(); x.selection.o("limits").put("maxInFlightObjects", 1)
        val forwardJob = "9".repeat(32)
        val forward = obj("direction" to "phoneToWatch", "jobId" to forwardJob, "intent" to "create", "selection" to x.selection)
        val auth = B2Authorization(x.account, "phone", "watch", x.snapshot, x.snapshot, listOf(forward, x.binding)) { true }
        val r = B2Receiver(x.store, x.files, auth, object : B2BusinessPort {
            override val kinds = setOf("noteAsset")
            override suspend fun apply(descriptor: JSONObject, metadata: JSONObject, staged: File) = "merged"
        })
        val epoch = x.begin(r); x.manifest(r, epoch)
        val h = x.data(epoch).header
        val negative = try { r.inspect(h); assertionFailed(); null } catch (e: B2RejectedData) { e }!!
        assertEquals("WINDOW_FULL", negative.rejection)
        B2Frames.drain(java.io.ByteArrayInputStream(x.bytes), h.length, negative.hash)
        rejected("HASH_MISMATCH") { B2Frames.drain(java.io.ByteArrayInputStream("abd".toByteArray()), h.length, negative.hash) }
        assertTrue(x.store.list(x.account, "chunk").isEmpty())
        x.store.put(x.account, "peerAck", forwardJob, obj("state" to "COMPLETE"))
        assertEquals(2, x.put(r, epoch, 2).entries.single().txSeq)
        x.root.deleteRecursively(); Unit
    }

    @Test fun bidirectionalWindowEightDoesNotBorrowOddObjectSlots() = runBlocking {
        for ((direction, stream, cap) in listOf(Triple("phoneToWatch", 1, 2), Triple("watchToPhone", 2, 1))) {
            val x = ReceiverRig()
            try {
                val ds = (0..2).map { assetDescriptor(x.bytes, "asset-$it.png") }.sortedBy { it.s("objectId") }
                x.selection.o("limits").put("maxInFlightObjects", 3)
                val opposite = if (stream == 1) "watchToPhone" else "phoneToWatch"
                val bindings = listOf(x.binding.put("direction", direction),
                    x.binding.put("direction", opposite).put("jobId", "9".repeat(32)))
                val receiver = B2Receiver(x.store, x.files,
                    B2Authorization(x.account, "phone", "watch", x.snapshot, x.snapshot, bindings) { true },
                    object : B2BusinessPort {
                        override val kinds = setOf("noteAsset")
                        override suspend fun apply(descriptor: JSONObject, metadata: JSONObject, staged: File) = "merged"
                    }, receiveDirection = direction)
                val description = x.description.put("direction", direction).put("objectCount", ds.size)
                    .put("objectsRoot", B2Digests.objects(ds.map { it.s("objectId") })).put("pageObjectCounts", JSONArray(listOf(ds.size)))
                val plan = B2Digests.plan(description)
                val epoch = receiver.handle(B2Wire.request("begin", x.job, plan, null, description)).n("epoch")
                val page = obj("pageIndex" to 0, "startIndex" to 0, "previousPageDigest" to "0".repeat(64),
                    "pageDigest" to "0".repeat(64), "entries" to JSONArray(ds))
                page.put("pageDigest", B2Digests.page(plan, page))
                receiver.handle(B2Wire.request("manifestPage", x.job, plan, epoch, page))
                fun header(slot: Int, seq: Long): B2DataHeader {
                    val request = B2Wire.request("putChunk", x.job, plan, epoch,
                        obj("objectId" to ds[slot].s("objectId"), "chunkIndex" to 0), slot.toString().repeat(32))
                    return B2DataHeader(stream, slot, epoch, seq, 0, x.bytes.size, request.s("requestId"), request.s("requestDigest"))
                }
                repeat(cap) { i ->
                    val h = header(i, i + 1L)
                    receiver.data(B2Frame.Data(h, x.bytes), receiver.inspect(h).first)
                }
                val first = header(cap, cap + 1L)
                val rejectedData = try {
                    receiver.inspect(first)
                    error("the other direction's slots cannot be borrowed")
                } catch (e: B2RejectedData) { e }
                assertEquals("WINDOW_FULL", rejectedData.rejection)
                B2Frames.drain(java.io.ByteArrayInputStream(x.bytes), x.bytes.size, rejectedData.hash)
                receiver.handle(B2Wire.request("apply", x.job, plan, epoch, obj("objectId" to ds[0].s("objectId"))))
                val retry = first.copy(seq = first.seq + 1)
                assertEquals(retry.seq, receiver.data(B2Frame.Data(retry, x.bytes), receiver.inspect(retry).first).entries.single().txSeq)
            } finally { x.root.deleteRecursively() }
        }
        Unit
    }

    @Test fun heartbeatRequestIdCannotBeReboundToBusinessAfterReconnect() = runBlocking {
        val x = ReceiverRig(); val first = x.receiver(); val e = x.begin(first)
        val id = "7".repeat(32)
        first.heartbeat(B2Wire.request("missing", x.job, x.plan, e, obj("afterObjectId" to null, "afterChunkIndex" to null, "limit" to 0), id))
        first.fenceNow()
        val resumed = x.receiver("resume"); val epoch = x.begin(resumed, "resume"); x.manifest(resumed, epoch)
        try {
            resumed.handle(B2Wire.request("apply", x.job, x.plan, epoch, obj("objectId" to x.descriptor.s("objectId")), id))
            assertionFailed()
        } catch (v: B2Exception) { assertEquals("REQUEST_CONFLICT", v.code) }
        x.root.deleteRecursively(); Unit
    }

}
