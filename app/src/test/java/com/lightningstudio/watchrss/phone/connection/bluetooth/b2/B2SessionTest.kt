package com.lightningstudio.watchrss.phone.connection.bluetooth.b2

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assert.fail as assertionFailed
import org.junit.Test
import java.io.*
import java.util.concurrent.atomic.AtomicInteger

class B2SessionTest {
    /** Phone B2Client and a separate watch B2Receiver execute real raw frames over two
     * host pipes. No Android classes or in-memory frame shortcut crosses this boundary. */
    @Test fun s2ClientAndReceiverCompleteRawMultiBlockTransfer() = runBlocking {
        withTimeout(15000) {
            val phoneIn = PipedInputStream(262144); val watchOut = PipedOutputStream(phoneIn)
            val watchIn = PipedInputStream(262144); val phoneOut = PipedOutputStream(watchIn)
            val close = { runCatching { phoneIn.close() }; runCatching { watchIn.close() }; runCatching { phoneOut.close() }; runCatching { watchOut.close() }; Unit }
            val x = ReceiverRig(ByteArray(65536 + 19) { (it * 7).toByte() })
            val sourceStore = MemoryB2Store(); val sourceRoot = java.nio.file.Files.createTempDirectory("b2-source").toFile()
            val sourceFile = File(sourceRoot, "original").apply { writeBytes(x.bytes) }
            val sourceSnapshots = B2Snapshots(sourceStore, B2Files(File(sourceRoot, "frozen")))
            val witness = sourceSnapshots.freeze(x.account, "source", listOf(x.descriptor)) { sourceFile }
            val cap = B2Negotiation.capability(setOf("noteAsset"), setOf("noteAsset"), B2Wire.s2Limits())
            val selection = B2Negotiation.select(x.cap, cap, "phoneToWatch")!!
            val binding = obj("direction" to "phoneToWatch", "jobId" to x.job, "intent" to "create", "selection" to selection)
            val phone = B2Session(phoneIn, phoneOut, this, close)
            val watch = B2Session(watchIn, watchOut, this, close)
            var receiver: B2Receiver? = null
            val commits = AtomicInteger()
            // An installed dummy receiver permits prepare routing before confirm.
            val dummy = x.receiver()
            watch.install(dummy) { r ->
                if (r.s("phase") == "prepare") {
                    val p = r.o("payload")
                    val result = if (p.s("step") == "offer") obj("step" to "offer", "watchCapabilities" to cap, "watchSnapshot" to x.snapshot, "offerDigest" to B2Digests.offer(p, cap, x.snapshot))
                    else {
                        val auth = B2Authorization(x.account, "phone", "watch", witness, x.snapshot, listOf(binding)) { true }
                        receiver = B2Receiver(x.store, x.files, auth, object : B2BusinessPort {
                            override val kinds = setOf("noteAsset")
                            override suspend fun apply(descriptor: JSONObject, metadata: JSONObject, staged: File): String {
                                assertArrayEquals(x.bytes, staged.readBytes()); commits.incrementAndGet(); return "merged"
                            }
                        }, receiveDirection = "phoneToWatch")
                        // Swap handler before writing confirm response, so the next DATA is routable.
                        watch.install(receiver!!) { receiver!!.handle(it) }
                        obj("step" to "confirm", "preparationDigest" to p.s("preparationDigest"))
                    }
                    B2Results.answer(r, result, null)
                } else receiver!!.handle(r)
            }
            watch.start(); phone.start()
            val client = B2Client(sourceStore, sourceSnapshots, phone::exchange, {}, phone)
            client.acceptProbe(obj("libraryBinary" to cap))
            try {
                client.offer(x.account, "phone", "watch", x.cap, witness)
                client.confirm(listOf(binding)) { }
                val complete = client.send(x.job)
                assertEquals("COMPLETE", complete.s("state")); assertEquals(1, commits.get())
                assertNotNull(sourceStore.get(x.account, "peerAck", x.job))
                sourceRoot.deleteRecursively()
                client.retire(x.job)
                assertEquals("COMPLETE", x.store.get(x.account, "job", x.job)!!.s("state"))
                assertEquals(1, commits.get())
            } finally { phone.close(); watch.close(); close(); sourceRoot.deleteRecursively(); x.root.deleteRecursively() }
        }
        Unit
    }
    @Test fun readWindowAndHeartbeatWhileDurableGroupIsBlocked() = exerciseBlockedWindow(overflow = false)

    @Test fun ninthFullChunkStopsTheConnectionAndRevokesAllUnsettledWork() = exerciseBlockedWindow(overflow = true)

    private fun exerciseBlockedWindow(overflow: Boolean) = runBlocking {
        withTimeout(20000) {
            val fullChunks = if (overflow) 9 else 8
            val x = ReceiverRig(ByteArray(B2Wire.CHUNK * fullChunks + 13) { (it * 7).toByte() })
            val blocked = java.util.concurrent.CountDownLatch(1)
            val resume = java.util.concurrent.CountDownLatch(1)
            val link = java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
            val peer = java.net.Socket(link.inetAddress, link.localPort)
            val transport = link.use { it.accept() }
            peer.soTimeout = 5000
            val receiver = B2Receiver(x.store, x.files,
                B2Authorization(x.account, "phone", "watch", x.snapshot, x.snapshot, listOf(x.binding)) { true },
                object : B2BusinessPort {
                    override val kinds = setOf("noteAsset")
                    override suspend fun apply(descriptor: JSONObject, metadata: JSONObject, staged: File) = "merged"
                }, fault = { phase ->
                    if (phase == "afterChunkDurable") {
                        blocked.countDown()
                        check(resume.await(8, java.util.concurrent.TimeUnit.SECONDS))
                    }
                })
            val session = B2Session(transport.getInputStream(), transport.getOutputStream(), this, transport::close)
            session.install(receiver) { receiver.handle(it) }
            session.start()
            try {
                val fromPeer = peer.getInputStream()
                val toPhone = peer.getOutputStream()
                suspend fun request(frame: JSONObject): JSONObject = withContext(Dispatchers.IO) {
                    B2Frames.write(toPhone, B2Frame.Control(frame))
                    (B2Frames.read(fromPeer) as B2Frame.Control).value
                }
                fun heartbeat(epoch: Long) = B2Wire.request("missing", x.job, x.plan, epoch,
                    obj("limit" to 0, "afterObjectId" to null, "afterChunkIndex" to null))
                val e = request(B2Wire.request("begin", x.job, x.plan, null, x.description)).n("epoch")
                assertEquals("PLANNING", request(heartbeat(e)).o("result").s("state"))
                val page = obj("pageIndex" to 0, "startIndex" to 0, "previousPageDigest" to "0".repeat(64),
                    "pageDigest" to "0".repeat(64), "entries" to JSONArray(listOf(x.descriptor)))
                page.put("pageDigest", B2Digests.page(x.plan, page))
                assertTrue(request(B2Wire.request("manifestPage", x.job, x.plan, e, page)).getBoolean("success"))
                fun data(index: Int): B2Frame.Data {
                    val put = B2Wire.request("putChunk", x.job, x.plan, e,
                        obj("objectId" to x.descriptor.s("objectId"), "chunkIndex" to index))
                    val bytes = x.bytes.copyOfRange(index * B2Wire.CHUNK, minOf((index + 1) * B2Wire.CHUNK, x.bytes.size))
                    return B2Frame.Data(B2DataHeader(2, 0, e, index + 1L, index, bytes.size,
                        put.s("requestId"), put.s("requestDigest")), bytes)
                }
                withContext(Dispatchers.IO) {
                    repeat(8) { B2Frames.write(toPhone, data(it)) }
                    check(blocked.await(5, java.util.concurrent.TimeUnit.SECONDS))
                }
                val whileBlocked = request(heartbeat(e))
                assertTrue(whileBlocked.o("result").a("items").length() == 0)
                assertTrue(x.store.list(x.account, "chunk").isEmpty())
                if (overflow) {
                    val ninth = data(8)
                    assertEquals(B2Wire.CHUNK, ninth.header.length)
                    withContext(Dispatchers.IO) {
                        val wire = B2Frames.encode(ninth)
                        // A credit violation aborts once the header is known, with no payload drain.
                        toPhone.write(wire, 0, wire.size - ninth.bytes.size)
                        toPhone.flush()
                        val error = (B2Frames.read(fromPeer) as B2Frame.Control).value
                        assertEquals(ninth.header.requestId, error.s("requestId"))
                        assertEquals(ninth.header.digest, error.s("requestDigest"))
                        assertEquals("FLOW_CONTROL_VIOLATION", error.o("error").s("code"))
                        assertEquals("PROTOCOL", error.o("error").s("class"))
                        assertEquals("STOP", error.o("error").s("recovery"))
                        assertFalse(error.o("error").getBoolean("retryable"))
                        resume.countDown()
                        assertEquals(-1, fromPeer.read()) // No ACK before or after releasing the blocked commit.
                    }
                    assertEquals("FLOW_CONTROL_VIOLATION", (session.awaitTermination() as B2Exception).code)
                    // Join the writer lane to establish that the blocked group could not commit after STOP.
                    assertTrue(x.store.exclusive { x.store.list(x.account, "chunk").isEmpty() })
                    return@withTimeout
                }
                resume.countDown()
                withContext(Dispatchers.IO) {
                    val settled = mutableListOf<Long>()
                    val sizes = mutableListOf<Int>()
                    while (settled.size < 8) {
                        val ack = B2Frames.read(fromPeer) as B2Frame.Ack
                        sizes.add(ack.entries.size)
                        settled.addAll(ack.entries.map { it.txSeq })
                    }
                    assertEquals((1L..8L).toList(), settled)
                    assertTrue(sizes.any { it > 1 })
                    assertTrue(sizes.all { it <= 4 })
                    B2Frames.write(toPhone, data(8))
                    val tail = B2Frames.read(fromPeer) as B2Frame.Ack
                    assertEquals(listOf(B2AckEntry(9, 1)), tail.entries)
                }
                assertEquals(9, x.store.list(x.account, "chunk").size)
            } finally {
                resume.countDown()
                session.close()
                peer.close()
                transport.close()
                x.root.deleteRecursively()
            }
        }
        Unit
    }

    @Test fun switchLeavesGluedBinaryBytesAndNeverWritesLegacyAck() {
        val cap = fixture("spec-r3-vectors.json").getJSONObject("s2Capability")
        val requestOutput = ByteArrayOutputStream()
        var reply: ByteArrayInputStream? = null
        val output = object : OutputStream() {
            override fun write(b: Int) { requestOutput.write(b) }
            override fun write(b: ByteArray, off: Int, len: Int) { requestOutput.write(b, off, len) }
            override fun flush() {
                val request = BluetoothLegacyRead(requestOutput.toByteArray())
                val response = obj("action" to B2Wire.ACTION, "version" to 2, "phase" to "switch", "type" to "response", "requestId" to request.s("requestId"), "success" to true, "result" to request.o("payload"))
                val bytes = B2Json.utf8(response.toString())
                val old = ByteArrayOutputStream().also { DataOutputStream(it).apply { writeInt(bytes.size); write(bytes) } }.toByteArray()
                reply = ByteArrayInputStream(old + B2Frames.unhex(fixture("spec-r3-vectors.json").s("ackHex"), 64))
            }
        }
        val input = object : InputStream() { override fun read() = reply!!.read(); override fun read(b: ByteArray, off: Int, len: Int) = reply!!.read(b, off, len) }
        B2Switch.perform(input, output, cap, true)
        assertTrue(B2Frames.read(input) is B2Frame.Ack)
        assertEquals(0, reply!!.available())
        assertEquals("switch", BluetoothLegacyRead(requestOutput.toByteArray()).s("phase"))
    }
    private fun BluetoothLegacyRead(bytes: ByteArray): JSONObject = DataInputStream(ByteArrayInputStream(bytes)).let { input -> val n = input.readInt(); B2Json.parse(ByteArray(n).also { input.readFully(it) }).also { assertEquals(0, input.available()) } }
}
