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
            val cap = x.cap
            val selection = B2Negotiation.select(cap, cap, "phoneToWatch")!!
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
                client.offer(x.account, "phone", "watch", cap, witness)
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
