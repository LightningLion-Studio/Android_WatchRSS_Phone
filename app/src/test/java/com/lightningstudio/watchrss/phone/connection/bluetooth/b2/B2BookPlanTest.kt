package com.lightningstudio.watchrss.phone.connection.bluetooth.b2

import com.lightningstudio.watchrss.phone.connection.bluetooth.B2FileHashes
import com.lightningstudio.watchrss.phone.connection.bluetooth.binaryDescriptor
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class B2BookPlanTest {
    private val id = "a".repeat(64)
    private val hash = "c".repeat(64)
    private val state = binaryDescriptor(B2Wire.BOOK_STATE, id, obj("bookId" to id), B2FileHashes(1, hash, listOf(hash), emptyList()))
    private val empty = B2FileHashes(0, B2Digests.hash(byteArrayOf()), emptyList(), emptyList())

    private fun plan(bytes: Long, reverse: Boolean = false): List<JSONObject> {
        val unit = B2Wire.BOOK_SEGMENT_BYTES
        val count = ((bytes + unit - 1) / unit).toInt()
        val common = obj("bookId" to id, "fingerprint" to hash, "packageSha256" to hash, "packageBytes" to bytes)
        val parts = (0 until count).map { i ->
            val size = minOf(unit, bytes - i * unit)
            val meta = copy(common).put("segmentCount", count).put("segmentIndex", i).put("segmentOffset", i * unit)
            binaryDescriptor(B2Wire.BOOK_SEGMENT, "$id:$hash:$i", meta,
                B2FileHashes(size, hash, List(((size + B2Wire.CHUNK - 1) / B2Wire.CHUNK).toInt()) { hash }, emptyList()),
                listOf(state.s("objectId")))
        }
        val ids = parts.map { it.s("objectId") }
        val commit = binaryDescriptor(B2Wire.BOOK_COMMIT, id,
            copy(common).put("segmentObjectIds", JSONArray(if (reverse) ids.reversed() else ids)), empty,
            (ids + state.s("objectId")).sorted())
        return parts + state + commit
    }

    @Test fun maximumIsThirtyTwoSegmentsAndThirtyThreeCommitEdges() {
        val largest = plan(B2Wire.BOOK_MAX_BYTES)
        assertEquals(34, largest.size)
        assertEquals(33, largest.last().a("dependsOn").length())
        B2BookManifest.validatePlan(largest, B2Wire.BOOK_MAX_BYTES)
        val lower = runCatching { B2BookManifest.validatePlan(largest, B2Wire.BOOK_MAX_BYTES - 1) }.exceptionOrNull()
        assertEquals("LIMIT_EXCEEDED", (lower as B2Exception).code)
        assertTrue(runCatching { plan(B2Wire.BOOK_MAX_BYTES + 1) }.isFailure)
        val wrongOrder = runCatching { B2BookManifest.validatePlan(plan(B2Wire.BOOK_SEGMENT_BYTES + 1, true), B2Wire.BOOK_MAX_BYTES) }.exceptionOrNull()
        assertEquals("PLAN_CONFLICT", (wrongOrder as B2Exception).code)
    }

    @Test fun peakAdmissionRejectsBeforeReadyAndBeforeData() = runBlocking<Unit> {
        val root = java.nio.file.Files.createTempDirectory("b2-book-admission").toFile()
        try {
            val job = "d".repeat(32)
            val account = "e".repeat(64)
            val objects = plan(B2Wire.BOOK_SEGMENT_BYTES + 1).sortedBy { it.s("objectId") }
            val snap = obj("snapshotId" to "books", "manifestDigest" to hash, "fromExclusive" to 0, "toInclusive" to 0)
            val limits = B2Wire.s4Limits().put("maxStagingBytes", 65 * 1024 * 1024)
            val capability = B2Negotiation.capability(B2Wire.bookKinds, B2Wire.bookKinds, limits)
            val selection = B2Negotiation.select(capability, capability, "watchToPhone")!!
            val binding = obj("jobId" to job, "direction" to "watchToPhone", "intent" to "create", "selection" to selection)
            val store = MemoryB2Store()
            val receiver = B2Receiver(store, B2Files(root), B2Authorization(account, "phone", "watch", snap, snap, listOf(binding)) { true },
                object : B2BusinessPort {
                    override val kinds = B2Wire.bookKinds
                    override fun applicationScratchBytes(objects: List<JSONObject>) = 40L * 1024 * 1024
                    override suspend fun apply(descriptor: JSONObject, metadata: JSONObject, staged: File): String = error("apply before admission")
                })
            val begin = obj("intent" to "create", "accountScope" to account, "phoneDeviceId" to "phone", "watchDeviceId" to "watch",
                "direction" to "watchToPhone", "phoneSnapshot" to snap, "watchSnapshot" to snap, "selection" to selection,
                "selectionDigest" to B2Digests.selection(selection), "objectCount" to objects.size,
                "objectsRoot" to B2Digests.objects(objects.map { it.s("objectId") }), "pageObjectCounts" to JSONArray(listOf(objects.size)))
            val plan = B2Digests.plan(begin)
            val epoch = receiver.handle(B2Wire.request("begin", job, plan, null, begin)).n("epoch")
            val page = obj("pageIndex" to 0, "startIndex" to 0, "previousPageDigest" to "0".repeat(64),
                "pageDigest" to "0".repeat(64), "entries" to JSONArray(objects))
            page.put("pageDigest", B2Digests.page(plan, page))
            val denied = runCatching { receiver.handle(B2Wire.request("manifestPage", job, plan, epoch, page)) }.exceptionOrNull()
            assertEquals("STORAGE_PRESSURE", (denied as B2Exception).code)
            assertEquals("PLANNING", store.get(account, "job", job)!!.s("state"))
            assertTrue(store.list(account, "object").isEmpty())
            val cancellation = receiver.handle(B2Wire.request("cancel", job, plan, epoch, obj("reason" to "USER_CANCEL")))
            assertEquals("CANCELLED", cancellation.o("result").s("state"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun reconnectReportsOnlyMissingBookBlocksAndCancelKeepsAppliedState() = runBlocking<Unit> {
        val root = java.nio.file.Files.createTempDirectory("b2-book-resume").toFile()
        try {
            val stateBytes = "{}".toByteArray()
            val stateHash = B2Digests.hash(stateBytes)
            val state = binaryDescriptor(B2Wire.BOOK_STATE, id, obj("bookId" to id),
                B2FileHashes(stateBytes.size.toLong(), stateHash, listOf(stateHash), emptyList()))
            val block = ByteArray(B2Wire.CHUNK) { 107 }
            val bytes = block.size * 2L
            val hash = B2Digests.hash(block + block)
            val metadata = obj("bookId" to id, "fingerprint" to this@B2BookPlanTest.hash,
                "packageSha256" to hash, "packageBytes" to bytes)
            val part = binaryDescriptor(B2Wire.BOOK_SEGMENT, "$id:$hash:0",
                copy(metadata).put("segmentIndex", 0).put("segmentCount", 1).put("segmentOffset", 0),
                B2FileHashes(bytes, hash, List(2) { B2Digests.hash(block) }, emptyList()), listOf(state.s("objectId")))
            val commit = binaryDescriptor(B2Wire.BOOK_COMMIT, id,
                copy(metadata).put("segmentObjectIds", JSONArray(listOf(part.s("objectId")))), empty,
                listOf(state.s("objectId"), part.s("objectId")).sorted())
            val objects = listOf(state, part, commit).sortedBy { it.s("objectId") }
            val job = "1".repeat(32)
            val account = "2".repeat(64)
            val snapshot = obj("snapshotId" to "book-resume", "manifestDigest" to hash, "fromExclusive" to 0, "toInclusive" to 0)
            val cap = B2Negotiation.capability(B2Wire.bookKinds, B2Wire.bookKinds, B2Wire.s4Limits())
            val selection = B2Negotiation.select(cap, cap, "watchToPhone")!!
            val store = MemoryB2Store()
            val files = B2Files(root)
            var stateApplies = 0
            fun receiver(intent: String): B2Receiver {
                val binding = obj("direction" to "watchToPhone", "jobId" to job, "intent" to intent, "selection" to selection)
                return B2Receiver(store, files, B2Authorization(account, "phone", "watch", snapshot, snapshot, listOf(binding)) { true },
                    object : B2BusinessPort {
                        override val kinds = B2Wire.bookKinds
                        override suspend fun apply(descriptor: JSONObject, metadata: JSONObject, staged: File): String {
                            assertEquals(B2Wire.BOOK_STATE, descriptor.s("kind"))
                            stateApplies++
                            return "merged"
                        }
                    })
            }
            val begin = obj("intent" to "create", "accountScope" to account, "phoneDeviceId" to "phone", "watchDeviceId" to "watch",
                "direction" to "watchToPhone", "phoneSnapshot" to snapshot, "watchSnapshot" to snapshot, "selection" to selection,
                "selectionDigest" to B2Digests.selection(selection), "objectCount" to objects.size,
                "objectsRoot" to B2Digests.objects(objects.map { it.s("objectId") }), "pageObjectCounts" to JSONArray(listOf(objects.size)))
            val plan = B2Digests.plan(begin)
            val first = receiver("create")
            val epoch = first.handle(B2Wire.request("begin", job, plan, null, begin)).n("epoch")
            val page = obj("pageIndex" to 0, "startIndex" to 0, "previousPageDigest" to "0".repeat(64),
                "pageDigest" to "0".repeat(64), "entries" to JSONArray(objects))
            page.put("pageDigest", B2Digests.page(plan, page))
            assertTrue(first.handle(B2Wire.request("manifestPage", job, plan, epoch, page)).o("result").getBoolean("manifestVerified"))
            suspend fun durable(d: JSONObject, seq: Long, payload: ByteArray) {
                val request = B2Wire.request("putChunk", job, plan, epoch, obj("objectId" to d.s("objectId"), "chunkIndex" to 0))
                val head = B2DataHeader(2, objects.indexOf(d), epoch, seq, 0, payload.size, request.s("requestId"), request.s("requestDigest"))
                first.inspect(head)
                val chunk = first.writeChunk(B2Frame.Data(head, payload), request)!!
                assertEquals(1, first.commitChunks(listOf(chunk)).single().disposition)
            }
            durable(state, 1, stateBytes)
            first.handle(B2Wire.request("apply", job, plan, epoch, obj("objectId" to state.s("objectId"))))
            durable(part, 2, block)
            first.fenceNow()
            first.closeIndex()
            val next = receiver("resume")
            val resumed = next.handle(B2Wire.request("begin", job, plan, null, copy(begin).put("intent", "resume")))
            val later = resumed.n("epoch")
            assertEquals(epoch + 1, later)
            val result = next.handle(B2Wire.request("missing", job, plan, later,
                obj("afterObjectId" to null, "afterChunkIndex" to null, "limit" to 16))).o("result")
            val partStatus = result.a("items").values().map { it as JSONObject }.single { it.s("objectId") == part.s("objectId") }
            assertEquals("[[1,2]]", partStatus.a("missingRanges").toString())
            next.handle(B2Wire.request("cancel", job, plan, later, obj("reason" to "USER_CANCEL")))
            assertEquals(1, stateApplies)
            assertEquals("CANCELLED", store.get(account, "job", job)!!.s("state"))
            assertEquals(1, store.listForJob(account, "object", job).count { !it.nil("receipt") })
            next.closeIndex()
        } finally {
            root.deleteRecursively()
        }
    }

}
