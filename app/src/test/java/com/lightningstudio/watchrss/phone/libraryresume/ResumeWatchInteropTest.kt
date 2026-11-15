package com.lightningstudio.watchrss.phone.libraryresume

import android.content.Context
import androidx.room.Room
import com.lightningstudio.watchrss.phone.data.db.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.*
import java.util.Base64

/** Explicit opt-in; ordinary tests never start an external repository peer. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, manifest = Config.NONE, sdk = [35])
class ResumeWatchInteropTest {
    @Test fun realPodjsApplicationExchangesBothDirectionsWithoutLegacyAcks() = exercise(false)
    @Test fun realPhoneSessionExchangesBothDirectionsWithPinnedPodjsByteFrames() = exercise(true)

    private fun exercise(useSession: Boolean) = runBlocking {
        val watchRepo = System.getenv("WATCHRSS_RESUME_WATCH")
        Assume.assumeTrue("set WATCHRSS_RESUME_WATCH for pinned host interop", !watchRepo.isNullOrEmpty())
        val context: Context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, PhoneCompanionDatabase::class.java).allowMainThreadQueries().build()
        val root = java.nio.file.Files.createTempDirectory("RESUMEC-interop").toFile()
        val command = listOf("bun", "src/test/interop/library-resume-watch.ts", watchRepo!!) + if (useSession) listOf("--binary") else emptyList()
        val process = ProcessBuilder(command).redirectError(File(root, "peer.stderr")).start()
        val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val session = if (useSession) ResumeSession(process.inputStream, process.outputStream, background).also { it.start() } else null
        val reverseDone = CompletableDeferred<Unit>()
        val input = process.inputStream.bufferedReader(); val output = process.outputStream.bufferedWriter()
        val store = ResumePersistence(db); val files = ResumeFiles(File(root, "files")); val snapshots = ResumeSnapshots(store, files); val v = vectors()
        val body = Base64.getDecoder().decode(v.s("bodyBase64")); val objects = v.a("objects").values().map { it as JSONObject }
        val scope = v.s("accountScope"); val witness = snapshots.freeze(scope, "interop-phone", objects, { d -> if (d.s("kind") == "articleBody") body else byteArrayOf() })
        var receiver: ResumeReceiver? = null
        val reverse = java.util.concurrent.CopyOnWriteArrayList<JSONObject>(); val trace = java.util.concurrent.CopyOnWriteArrayList<JSONObject>()
        val port = object : ResumeBusinessPort {
            override val kinds = setOf("articleBody", "articleMetadata", "rssSource")
            override suspend fun apply(db: PhoneCompanionDatabase, descriptor: JSONObject, metadata: JSONObject, staged: File): String {
                val id = "interop-" + descriptor.s("objectId"); assertNull(db.appMetaDao().getString(id))
                db.appMetaDao().set(AppMetaEntity(id, "applied")); reverse.add(copy(descriptor)); return "merged"
            }
        }
        suspend fun send(frame: JSONObject) = withContext(Dispatchers.IO) { output.write(frame.toString()); output.newLine(); output.flush(); trace.add(copy(frame)) }
        val exchange: suspend (JSONObject) -> JSONObject = { frame ->
            if (session != null) {
                trace.add(copy(frame)); session.exchange(frame).also { trace.add(copy(it)) }
            } else {
            send(frame)
            var answer: JSONObject? = null
            while (answer == null) {
                val line = withContext(Dispatchers.IO) { input.readLine() } ?: throw EOFException(File(root, "peer.stderr").readText())
                val incoming = ResumeWire.envelope(ResumeJson.parse(line)); trace.add(copy(incoming))
                if (incoming.s("type") == "request") send(receiver!!.handle(incoming)) else answer = incoming
            }
            answer
            }
        }
        try {
            withTimeout(60000) {
                val client = ResumeClient(store, snapshots, exchange, {}, { job -> session?.cancelJob(job) })
                // Isolated helper's real capability is article/rss only.
                val capability = ResumeNegotiation.capability(port.kinds, port.kinds)
                assertTrue(client.acceptProbe(obj("libraryResume" to capability)))
                val offer = client.offer(scope, v.s("phoneDeviceId"), v.s("watchDeviceId"), capability, witness)
                val bs = listOf("phoneToWatch", "watchToPhone").mapIndexed { i, direction -> obj("direction" to direction, "jobId" to (if (i == 0) "a" else "b").repeat(32), "intent" to "create", "selection" to ResumeNegotiation.select(capability, offer.o("watchCapabilities"), direction)!!) }
                client.confirm(bs) {
                    receiver = ResumeReceiver(store, files, it, port)
                    session?.installReceiver { incoming ->
                        trace.add(copy(incoming))
                        client.handleRequest(incoming, receiver!!).also { answer ->
                            trace.add(copy(answer))
                            if (incoming.s("phase") == "complete") reverseDone.complete(Unit)
                        }
                    }
                }
                val completion = client.send(bs[0].s("jobId")); assertEquals("COMPLETE", completion.s("state"))
                // The reverse source can still be finishing after the forward complete response.
                if (session != null) {
                    reverseDone.await()
                    assertTrue("watch must consume both COMPLETE replies", withContext(Dispatchers.IO) { process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) })
                    assertEquals(File(root, "peer.stderr").readText(), 0, process.exitValue())
                } else while (store.get(scope, "job", bs[1].s("jobId"))?.s("state") != "COMPLETE") {
                    val incoming = ResumeWire.envelope(ResumeJson.parse(withContext(Dispatchers.IO) { input.readLine() } ?: throw EOFException()))
                    trace.add(copy(incoming)); assertEquals("request", incoming.s("type")); send(receiver!!.handle(incoming))
                }
                assertEquals(2, reverse.size); assertEquals(2, reverse.map { it.s("objectId") }.distinct().size)
                assertNotNull(store.get(scope, "peerAck", bs[0].s("jobId")))
                assertTrue(trace.all { it.s("action") == ResumeWire.ACTION })
                assertTrue(trace.none { it.s("phase") in listOf("getChunk", "status", "ack") })
                assertTrue(trace.any { it.s("type") == "request" && it.s("phase") == "begin" && it.o("payload").s("direction") == "watchToPhone" })
            }
        } finally {
            session?.close(); background.cancel()
            runCatching { output.close() }
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly()
            db.close(); root.deleteRecursively()
        }
    }
}
