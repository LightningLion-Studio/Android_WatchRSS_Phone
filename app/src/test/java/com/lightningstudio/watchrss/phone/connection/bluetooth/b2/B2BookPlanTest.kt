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

    @Test fun reconnectAcrossBookSegmentsResendsOnlyMissingDataAndRetainsRequestBinding() = runBlocking<Unit> {
        val root = java.nio.file.Files.createTempDirectory("b2-book-resume").toFile()
        try {
            val stateBytes = "{}".toByteArray()
            val stateHash = B2Digests.hash(stateBytes)
            val state = binaryDescriptor(B2Wire.BOOK_STATE, id, obj("bookId" to id),
                B2FileHashes(stateBytes.size.toLong(), stateHash, listOf(stateHash), emptyList()))
            val block = ByteArray(B2Wire.CHUNK) { 107 }
            val blockHash = B2Digests.hash(block)
            val segmentChunks = (B2Wire.BOOK_SEGMENT_BYTES / block.size).toInt()
            fun hashBlocks(count: Int): String = B2Frames.hex(java.security.MessageDigest.getInstance("SHA-256").run {
                repeat(count) { update(block) }
                digest()
            })
            val bytes = (segmentChunks + 2) * block.size.toLong()
            val hash = hashBlocks(segmentChunks + 2)
            val metadata = obj("bookId" to id, "fingerprint" to this@B2BookPlanTest.hash,
                "packageSha256" to hash, "packageBytes" to bytes)
            val parts = listOf(segmentChunks, 2).mapIndexed { index, chunks ->
                binaryDescriptor(B2Wire.BOOK_SEGMENT, "$id:$hash:$index",
                    copy(metadata).put("segmentIndex", index).put("segmentCount", 2)
                        .put("segmentOffset", index * B2Wire.BOOK_SEGMENT_BYTES),
                    B2FileHashes(chunks * block.size.toLong(), hashBlocks(chunks), List(chunks) { blockHash }, emptyList()),
                    listOf(state.s("objectId")))
            }
            val partIds = parts.map { it.s("objectId") }
            val commit = binaryDescriptor(B2Wire.BOOK_COMMIT, id,
                copy(metadata).put("segmentObjectIds", JSONArray(partIds)), empty,
                (listOf(state.s("objectId")) + partIds).sorted())
            val objects = (listOf(state, commit) + parts).sortedBy { it.s("objectId") }
            val job = "1".repeat(32)
            val account = "2".repeat(64)
            val snapshot = obj("snapshotId" to "book-resume", "manifestDigest" to hash, "fromExclusive" to 0, "toInclusive" to 0)
            val cap = B2Negotiation.capability(B2Wire.bookKinds, B2Wire.bookKinds, B2Wire.s4Limits())
            val selection = B2Negotiation.select(cap, cap, "watchToPhone")!!
            val store = MemoryB2Store()
            val applied = mutableListOf<String>()
            var elapsed = 0L
            var rejectTransaction = false
            val journal = object : B2Persistence by store {
                override suspend fun <T> transaction(block: suspend () -> T): T = store.transaction {
                    block().also { if (rejectTransaction) fail("LOCAL_IO") }
                }
            }
            val imports = File(root, "book-work").apply { mkdirs() }
            fun receiver(intent: String, jobId: String = job): B2Receiver {
                val binding = obj("direction" to "watchToPhone", "jobId" to jobId, "intent" to intent, "selection" to selection)
                return B2Receiver(journal, B2Files(root, imports), B2Authorization(account, "phone", "watch", snapshot, snapshot, listOf(binding)) { true },
                    object : B2BusinessPort {
                        override val kinds = B2Wire.bookKinds
                        override suspend fun apply(descriptor: JSONObject, metadata: JSONObject, staged: File): String {
                            assertEquals(descriptor.n("size"), staged.length())
                            applied += descriptor.s("objectId")
                            return "merged"
                        }
                    }, monotonic = { elapsed })
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
            fun chunkRequest(generation: Long, d: JSONObject, index: Int) =
                B2Wire.request("putChunk", job, plan, generation, obj("objectId" to d.s("objectId"), "chunkIndex" to index))
            suspend fun durable(
                target: B2Receiver, generation: Long, d: JSONObject, seq: Long, index: Int, payload: ByteArray,
                request: JSONObject = chunkRequest(generation, d, index)
            ) {
                val head = B2DataHeader(2, objects.indexOf(d), generation, seq, index, payload.size,
                    request.s("requestId"), request.s("requestDigest"))
                target.inspect(head)
                val chunk = target.writeChunk(B2Frame.Data(head, payload), request)!!
                assertEquals(1, target.commitChunks(listOf(chunk)).single().disposition)
                assertNotNull(target.ack(2, generation, listOf(B2AckEntry(seq, 1))))
            }
            suspend fun apply(target: B2Receiver, generation: Long, d: JSONObject) =
                target.handle(B2Wire.request("apply", job, plan, generation, obj("objectId" to d.s("objectId")))).o("result")
            durable(first, epoch, state, 1, 0, stateBytes)
            apply(first, epoch, state)
            for (index in 0 until segmentChunks) {
                durable(first, epoch, parts[0], index + 2L, index, block)
            }
            val firstSegment = apply(first, epoch, parts[0])
            // The bytes and native binding survive the aborted business transaction.
            val unacked = chunkRequest(epoch, parts[1], 0)
            rejectTransaction = true
            val rollback = runCatching { durable(first, epoch, parts[1], segmentChunks + 2L, 0, block, unacked) }
            assertEquals("LOCAL_IO", (rollback.exceptionOrNull() as B2Exception).code)
            rejectTransaction = false
            val requestKey = B2Digests.d("wrss.library-binary.v2/record-key", listOf(job, "watch", unacked.s("requestId")))
            assertNull(store.get(account, "request", requestKey))
            first.fenceNow()
            first.closeIndex()
            val next = receiver("resume")
            suspend fun resume(target: B2Receiver): Long =
                target.handle(B2Wire.request("begin", job, plan, null, copy(begin).put("intent", "resume"))).n("epoch")
            val later = resume(next)
            assertEquals(epoch + 1, later)
            val result = next.handle(B2Wire.request("missing", job, plan, later,
                obj("afterObjectId" to null, "afterChunkIndex" to null, "limit" to 16))).o("result")
            val statuses = result.a("items").values().map { it as JSONObject }.associateBy { it.s("objectId") }
            val partStatus = statuses.getValue(parts[1].s("objectId"))
            assertEquals("[[1,2]]", partStatus.a("missingRanges").toString())
            val appliedPart = statuses.getValue(parts[0].s("objectId"))
            assertEquals(firstSegment.o("receipt").s("resultDigest"), appliedPart.o("receipt").s("resultDigest"))

            val conflict = B2Wire.request("missing", job, plan, later,
                obj("afterObjectId" to null, "afterChunkIndex" to null, "limit" to 16), unacked.s("requestId"))
            val error = runCatching { next.handle(conflict) }.exceptionOrNull() as B2Exception
            assertEquals("REQUEST_CONFLICT", error.code)
            assertEquals("STOP", B2Results.errorValue(error.code).s("recovery"))

            next.fenceNow()
            next.closeIndex()
            val resumed = receiver("resume")
            val finalEpoch = resume(resumed)
            assertEquals(later + 1, finalEpoch)
            durable(resumed, finalEpoch, parts[1], 1, 1, block)
            apply(resumed, finalEpoch, parts[1])
            resumed.handle(B2Wire.request("cancel", job, plan, finalEpoch, obj("reason" to "USER_CANCEL")))
            assertEquals(listOf(state.s("objectId")) + partIds, applied)
            assertEquals("CANCELLED", store.get(account, "job", job)!!.s("state"))
            assertEquals(3, store.listForJob(account, "object", job).count { !it.nil("receipt") })
            resumed.closeIndex()

            val staging = B2Files(root, imports)
            val seg = parts[0].s("objectId")
            assertTrue(staging.composed(account, job, seg).isFile)
            val frozenOrphan = File(root, ".freeze-interrupted/nested/chapter").apply {
                parentFile!!.mkdirs()
                writeBytes(ByteArray(32))
            }
            val importOrphan = File(imports, ".b2-import-interrupted/text").apply {
                parentFile!!.mkdirs()
                writeBytes(ByteArray(64))
            }
            elapsed = B2Wire.RETENTION
            val currentJob = "3".repeat(32)
            val current = receiver("create", currentJob)
            val keepBegin = B2Wire.request("begin", currentJob, plan, null, begin)
            val keepEpoch = current.handle(keepBegin).n("epoch")
            assertTrue(staging.composed(account, job, seg).isFile)
            assertNotNull(store.get(account, "job", job))
            assertFalse(frozenOrphan.exists())
            assertFalse(importOrphan.exists())

            elapsed += 1
            val laterJob = "4".repeat(32)
            receiver("create", laterJob).handle(B2Wire.request("begin", laterJob, plan, null, begin))
            assertNull(store.get(account, "job", job))
            assertNull(store.get(account, "nativeOwner", job))
            assertTrue(store.listForJob(account, "object", job).isEmpty())
            assertTrue(store.listForJob(account, "request", job).isEmpty())
            assertFalse(staging.composed(account, job, seg).exists())
            staging.checkRequest(account, job, "watch", unacked.s("requestId"), "a".repeat(64))
            val stillBound = runCatching {
                staging.checkRequest(account, currentJob, "watch", keepBegin.s("requestId"), "a".repeat(64))
            }.exceptionOrNull() as B2Exception
            assertEquals("REQUEST_CONFLICT", stillBound.code)
            assertEquals(1L, keepEpoch)
        } finally {
            root.deleteRecursively()
        }
    }

}
