package com.lightningstudio.watchrss.phone.syncv2

import android.content.Context
import androidx.room.withTransaction
import com.lightningstudio.watchrss.phone.cloud.BluetoothAutomaticCloudFence
import com.lightningstudio.watchrss.phone.cloud.CloudKeyManager
import com.lightningstudio.watchrss.phone.connection.bluetooth.B2BodyHandoff
import com.lightningstudio.watchrss.phone.connection.bluetooth.B2NoteAssetBusiness
import com.lightningstudio.watchrss.phone.connection.bluetooth.B2RoomStore
import com.lightningstudio.watchrss.phone.connection.bluetooth.BookBluetoothSync
import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.B2Metadata
import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.assetDescriptor
import com.lightningstudio.watchrss.phone.data.book.BookPackageFiles
import com.lightningstudio.watchrss.phone.data.book.BookSync
import com.lightningstudio.watchrss.phone.data.book.ImportedBookStore
import com.lightningstudio.watchrss.phone.data.local.FileArticleContentStore
import com.lightningstudio.watchrss.phone.data.note.NoteAssetEntity
import com.lightningstudio.watchrss.phone.data.note.NoteAssetFiles
import com.lightningstudio.watchrss.phone.data.note.MarkdownArchiveEntry
import com.lightningstudio.watchrss.phone.data.note.MarkdownNoteArchive
import com.lightningstudio.watchrss.phone.data.note.NoteImportExportService
import com.lightningstudio.watchrss.phone.data.note.NoteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SyncV2BodyTransportTest {
    internal class Backend(val f:SyncV2P5Fixture) {
        val descriptors=linkedMapOf<String,JSONObject>();val objects=mutableMapOf<String,ByteArray>();val ready=mutableSetOf<String>()
        val puts=mutableListOf<Pair<String,Int>>();val reads=mutableListOf<Int>();var writeError:String?=null;var status=503;var delay:Long?=null;var retryable:Boolean?=null
        var losePart=false;var loseRead=false;var bodyRequests=0;var reserveCalls=0;var beforeRead:(()->Unit)?=null
        var beforeList: (() -> Unit)? = null
        var beforePut: ((String, Int, ByteArray) -> MockResponse?)? = null
        var customReadResponse: ((String, Int, String?, ByteArray) -> MockResponse?)? = null
        var reservePartHeader: String? = null
        var missingPartHeader: String? = null
        var maxAcceptedPart = 65_536
        var maxServedPart = 65_536
        var missingCalls = 0
        val putLengths = mutableListOf<Int>()
        val readPartSizes = mutableListOf<String?>()
        var listCalls = 0
        var completeCalls = 0
        var downloadBytes = 0L
        var uploadBytes = 0L
        init {val previous=f.f.route;f.f.route={r->val url=r.requestUrl!!;val p=url.encodedPath.removePrefix("/functions/v1/cloud/bodies/v1/")
            if(!url.encodedPath.startsWith("/functions/v1/cloud/bodies/v1/"))previous!!.invoke(r)else {
                synchronized(this) { bodyRequests++ }
                fun json(v:JSONObject):MockResponse=f.f.response(JSONObject().put("version",1).put("purpose",SyncV2BodyCodec.PURPOSE).put("scope",f.identity.scope).also{o->for(k in v.keys())o.put(k,v.get(k))})
                fun uploadStatus(id: String, header: String?): MockResponse {
                    val descriptors = SyncV2BodyCodec.objects(descriptors.getValue(id).getJSONObject("body"))
                    val offsets = descriptors.map { o ->
                        JSONObject(o.toString()).put("offset", objects[o.getString("sha256")]?.size ?: 0)
                    }
                    return json(JSONObject().put("carrierId", id).put("state", if (id in ready) "ready" else "reserved")
                        .put("expiresAtMillis", 600000).put("objects", JSONArray(offsets))).also {
                        if (header != null) it.setHeader("X-WatchRSS-Max-Body-Part-Bytes", header)
                    }
                }
                if(r.method!="GET" && writeError!=null) {f.f.response(JSONObject().put("error",JSONObject().put("code",writeError).put("retryable",retryable?:(status==429||status==507)).put("retryAfterMillis",delay?:JSONObject.NULL).put("details",JSONObject()))).setResponseCode(status)}
                else if(p=="reserve") {
                    reserveCalls++;assertEquals(null,url.queryParameter("repoId"));assertEquals("old-epoch",r.getHeader("x-watchrss-library-epoch"));val d=JSONObject(r.body.readUtf8());val b=SyncV2BodyCodec.validate(d,f.identity.scope);val id=b.getString("carrierId");descriptors[id]=d
                    uploadStatus(id, reservePartHeader)
                } else if (p.endsWith("/missing")) {
                    missingCalls++
                    assertEquals(setOf("scope"), JSONObject(r.body.readUtf8()).keys().asSequence().toSet())
                    uploadStatus(p.split('/')[1], missingPartHeader)
                }else if(r.method=="PUT") {
                    val parts=p.split('/');val hash=parts[3];val at=parts[5].toInt();val raw=r.body.readByteArray()
                    assertEquals(f.identity.repoId,r.getHeader("x-watchrss-sync-repo-id"))
                    assertEquals("application/octet-stream", r.getHeader("Content-Type"))
                    assertEquals(SyncV2BodyCodec.hash(raw),r.getHeader("x-watchrss-sync-part-sha256"))
                    beforePut?.invoke(hash, at, raw) ?: synchronized(this) {
                        assertTrue(raw.size in 1..maxAcceptedPart)
                        assertEquals(0, at % 65_536)
                        assertEquals(objects[hash]?.size ?: 0, at)
                        val id = parts[1]
                        val size = SyncV2BodyCodec.objects(descriptors.getValue(id).getJSONObject("body"))
                            .first { it.getString("sha256") == hash }.getInt("sizeBytes")
                        assertTrue(at + raw.size == size || raw.size % 65_536 == 0)
                        objects[hash] = (objects[hash] ?: byteArrayOf()) + raw
                        puts.add(hash to at)
                        putLengths.add(raw.size)
                        uploadBytes += raw.size
                        if (losePart) {
                            losePart = false
                            MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                        } else json(JSONObject())
                    }
                }else if(p.endsWith("/complete")) {
                    completeCalls++
                    val id=p.split('/')[1];for(o in SyncV2BodyCodec.objects(descriptors.getValue(id).getJSONObject("body"))){assertEquals(o.getInt("sizeBytes"),objects[o.getString("sha256")]!!.size);assertEquals(o.getString("sha256"),SyncV2BodyCodec.hash(objects[o.getString("sha256")]!!))};ready.add(id)
                    json(JSONObject().put("carrierId",id).put("state","ready"))
                }else if(p=="carriers") {
                    listCalls++
                    beforeList?.invoke()
                    assertEquals(f.identity.repoId,url.queryParameter("repoId"));val a=JSONArray()
                    for(id in ready.sorted()) {val d=descriptors.getValue(id);if(d.getJSONObject("body").getString("entityKey")==url.queryParameter("entityKey")&&d.getJSONObject("body").getString("bodyRevision")==url.queryParameter("bodyRevision"))a.put(JSONObject().put("carrierId",id).put("descriptor",d))}
                    json(JSONObject().put("carriers",a).put("nextAfter",JSONObject.NULL))
                }else if("/objects/" in p) {
                    val hash=p.split('/')[3];val raw=objects.getValue(hash);val at=url.queryParameter("offset")!!.toInt()
                    val requestedPartSize = url.queryParameter("partSize")
                    reads.add(at)
                    readPartSizes.add(requestedPartSize)
                    beforeRead?.invoke()
                    customReadResponse?.invoke(hash, at, requestedPartSize, raw) ?: run {
                        val partSize = if (maxServedPart > 65_536) {
                            requestedPartSize?.toIntOrNull()?.coerceIn(65_536, maxServedPart) ?: 65_536
                        } else maxServedPart
                        val slice = raw.copyOfRange(at, minOf(raw.size, at + partSize))
                        downloadBytes += slice.size
                        if (loseRead && at > 0) {
                            loseRead = false
                            MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                        } else {
                            MockResponse().setBody(Buffer().write(slice))
                                .setHeader("x-watchrss-body-total-bytes", raw.size)
                                .setHeader("x-watchrss-body-offset", at)
                        }
                    }
                }else error("unexpected body route $p")
            }
        }}
    }
    private class UploadBook(val f: SyncV2P5Fixture, size: Int) : AutoCloseable {
        val backend = Backend(f)
        val client = f.f.client().also { it.capabilities() }
        private val raw = ByteArray(size) { (it % 251).toByte() }
        private val id = "ab".repeat(32)
        private val entity = f.identity.entity("book", id)
        private val binding = SyncV2BodyCodec.Binding("book", id, entity,
            SyncV2BodyCodec.revision("book", entity, SyncV2BodyCodec.hash(raw)))
        private val anchor = JSONObject().put("commitOid", "commit:sha256:${"ab".repeat(32)}")
            .put("revision", "1").put("rollbackGeneration", "0")
        private val prepared = SyncV2BodyCodec.prepare(f.identity.scope, anchor, binding, f.key, 1, f.f.signer, raw)
        private val directory = File(f.root, "part-negotiation")
        private val body = prepared.descriptor.getJSONObject("body")
        val manifest = body.getJSONObject("manifest").getString("sha256")
        val chunks = prepared.objects.filterKeys { it != manifest }
        init {
            directory.mkdirs()
            for ((hash, bytes) in prepared.objects) File(directory, hash).writeBytes(bytes)
        }

        fun skipManifest() {
            backend.objects[manifest] = prepared.objects.getValue(manifest)
            assertTrue(File(directory, manifest).delete())
        }

        fun upload(beforeTransfer: () -> Unit = {}) {
            SyncV2BodyHttp(client) {}.upload(prepared.descriptor, directory, beforeTransfer)
            assertEquals(1, backend.completeCalls)
        }

        fun assertRoundTrip() {
            assertArrayEquals(raw, SyncV2BodyCodec.open(prepared.descriptor, f.identity.scope, binding,
                f.f.signer.publicHex, { f.key }) { hash, _ -> backend.objects.getValue(hash) })
        }

        override fun close() {
            client.close()
            f.close()
        }
    }

    @Test fun advertised512KUsesOnePutPerFullBookObject() {
        UploadBook(SyncV2P5Fixture(), 1_100_000).use { book ->
            book.backend.reservePartHeader = "524288"
            book.backend.maxAcceptedPart = 524_288
            book.upload()

            assertEquals(3, book.chunks.size)
            for ((hash, bytes) in book.chunks) {
                assertEquals(listOf(hash to 0), book.backend.puts.filter { it.first == hash })
                assertEquals(bytes.size, book.backend.putLengths[book.backend.puts.indexOf(hash to 0)])
            }
            assertEquals(2, book.backend.putLengths.count { it == 524_288 })
            assertEquals(0, book.backend.missingCalls)
            book.assertRoundTrip()
        }
    }

    @Test fun missingAndInvalidPartHeadersUseLegacy64K() {
        for (header in listOf(null, "invalid", "131073", "32768", "589824")) {
            UploadBook(SyncV2P5Fixture(), 200_000).use { book ->
                book.backend.reservePartHeader = header
                book.skipManifest()
                book.upload()
                assertEquals(listOf(65_536, 65_536, 65_536, 3_443), book.backend.putLengths)
                assertEquals(0, book.backend.missingCalls)
            }
        }
    }

    @Test fun payloadTooLargeFromProxyDowngradesAndRefreshesOffset() =
        rejectedLargePartResumes(MockResponse().setResponseCode(413).setBody("Payload Too Large"))

    @Test fun objectTooLargeFromBackendDowngradesAndRefreshesOffset() =
        rejectedLargePartResumes(partError(400, "object_too_large"))

    private fun rejectedLargePartResumes(rejection: MockResponse) {
        UploadBook(SyncV2P5Fixture(), 450_000).use { book ->
            val backend = book.backend
            backend.reservePartHeader = "524288"
            backend.missingPartHeader = "524288"
            book.skipManifest()
            val (hash, bytes) = book.chunks.entries.single()
            var rejected = false
            backend.beforePut = { _, offset, part ->
                if (!rejected) {
                    assertEquals(0, offset)
                    assertArrayEquals(bytes, part)
                    // A different upload already advanced the prefix after reserve.
                    backend.objects[hash] = bytes.copyOf(65_536)
                    rejected = true
                    rejection
                } else null
            }
            book.upload()

            assertTrue(rejected)
            assertEquals(1, backend.missingCalls)
            assertEquals(65_536, backend.puts.first().second)
            assertTrue(backend.putLengths.all { it <= 65_536 })
            book.assertRoundTrip()
        }
    }

    @Test fun offsetAndPartConflictsUseOffsetAndLimitFromMissing() {
        for (code in listOf("offset_conflict", "part_conflict")) {
            UploadBook(SyncV2P5Fixture(), 460_000).use { book ->
                val backend = book.backend
                backend.missingPartHeader = "262144"
                backend.maxAcceptedPart = 262_144
                book.skipManifest()
                val (hash, bytes) = book.chunks.entries.single()
                backend.beforePut = { _, at, part ->
                    if (at == 0) {
                        assertEquals(65_536, part.size)
                        backend.objects[hash] = bytes.copyOf(65_536)
                        partError(409, code)
                    } else null
                }
                book.upload()

                assertEquals(1, backend.missingCalls)
                assertEquals(listOf(hash to 65_536, hash to 327_680), backend.puts)
                assertEquals(listOf(262_144, bytes.size - 327_680), backend.putLengths)
                book.assertRoundTrip()
            }
        }
    }

    @Test fun mixedDurablePrefixesResumeWithAtMostThreeConcurrentObjects() {
        UploadBook(SyncV2P5Fixture(), 2_100_000).use { book ->
            val backend = book.backend
            backend.reservePartHeader = "524288"
            backend.maxAcceptedPart = 524_288
            book.skipManifest()
            val full = book.chunks.filterValues { it.size == 524_288 }.keys.toList()
            backend.objects[full[0]] = book.chunks.getValue(full[0])
            backend.objects[full[1]] = book.chunks.getValue(full[1]).copyOf(65_536)
            val reached = CountDownLatch(3)
            val inFlight = AtomicInteger()
            val peak = AtomicInteger()
            backend.beforePut = { _, _, _ ->
                val count = inFlight.incrementAndGet()
                peak.accumulateAndGet(count, ::maxOf)
                try {
                    reached.countDown()
                    assertTrue("Three objects must reach PUT before any response", reached.await(5, TimeUnit.SECONDS))
                    null
                } finally {
                    inFlight.decrementAndGet()
                }
            }

            book.upload()

            assertEquals(3, peak.get())
            assertEquals(4, backend.puts.size)
            assertTrue(backend.puts.none { it.first == full[0] || it.first == book.manifest })
            assertTrue(full[1] to 65_536 in backend.puts)
            assertEquals(0, backend.missingCalls)
            book.assertRoundTrip()
        }
    }

    @Test fun cancellingRoundCancelsAllInFlightParts() = runBlocking<Unit> {
        UploadBook(SyncV2P5Fixture(), 1_100_000).use { book ->
            val backend = book.backend
            backend.reservePartHeader = "524288"
            book.skipManifest()
            val entered = CountDownLatch(3)
            backend.beforePut = { _, _, _ ->
                entered.countDown()
                MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
            }
            val upload = launch(Dispatchers.IO) { SyncV2CloudClient.runCancellable { book.upload() } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))

            upload.cancel()
            withTimeout(5_000) { upload.join() }

            assertEquals(0, backend.completeCalls)
        }
    }

    @Test fun downloadRequests512KAndAcceptsLegacy64KParts() {
        SyncV2P5Fixture().use { f ->
            val backend = Backend(f)
            val client = f.f.client().also { it.capabilities() }
            val raw = ByteArray(600_000) { (it % 251).toByte() }
            val hash = SyncV2BodyCodec.hash(raw)
            backend.objects[hash] = raw
            backend.maxServedPart = 65_536

            val dir = File(f.root, "download-legacy-64k")
            val got = SyncV2BodyHttp(client) {}.download("aa".repeat(16), hash, raw.size, dir)

            assertArrayEquals(raw, got)
            assertEquals((0 until raw.size step 65_536).toList(), backend.reads)
            assertTrue(backend.readPartSizes.isNotEmpty() && backend.readPartSizes.all { it == "524288" })
            assertFalse(File(dir, "$hash.part").exists())
            client.close()
        }
    }

    @Test fun downloadDowngradesWhenLegacyBackendRejectsPartSizeWith400InvalidSchema() {
        SyncV2P5Fixture().use { f ->
            val backend = Backend(f)
            val client = f.f.client().also { it.capabilities() }
            val firstRaw = ByteArray(200_000) { (it % 251).toByte() }
            val secondRaw = ByteArray(150_000) { ((it + 17) % 251).toByte() }
            val firstHash = SyncV2BodyCodec.hash(firstRaw)
            val secondHash = SyncV2BodyCodec.hash(secondRaw)
            backend.objects[firstHash] = firstRaw
            backend.objects[secondHash] = secondRaw
            backend.maxServedPart = 65_536
            var partSizeRejections = 0
            backend.customReadResponse = { _, _, partSizeParam, _ ->
                if (partSizeParam != null) {
                    partSizeRejections++
                    partError(400, "invalid_schema")
                } else null
            }

            val http = SyncV2BodyHttp(client) {}
            val dir = File(f.root, "download-legacy-400-fallback")
            val firstGot = http.download("aa".repeat(16), firstHash, firstRaw.size, dir)
            val secondGot = http.download("aa".repeat(16), secondHash, secondRaw.size, dir)

            assertArrayEquals(firstRaw, firstGot)
            assertArrayEquals(secondRaw, secondGot)
            assertEquals(1, partSizeRejections)
            assertEquals(listOf("524288") + List(7) { null }, backend.readPartSizes)
            assertEquals(listOf(0, 0, 65_536, 131_072, 196_608, 0, 65_536, 131_072), backend.reads)
            assertFalse(File(dir, "$firstHash.part").exists())
            assertFalse(File(dir, "$secondHash.part").exists())
            client.close()
        }
    }

    @Test fun downloadUses512KPartsAndShortTailFromNewBackend() {
        for (size in listOf(1_100_000, 180_000, 524_288)) {
            SyncV2P5Fixture().use { f ->
                val backend = Backend(f)
                backend.maxServedPart = 524_288
                val client = f.f.client().also { it.capabilities() }
                val raw = ByteArray(size) { (it % 251).toByte() }
                val hash = SyncV2BodyCodec.hash(raw)
                backend.objects[hash] = raw

                val dir = File(f.root, "download-512k-$size")
                val got = SyncV2BodyHttp(client) {}.download("bb".repeat(16), hash, raw.size, dir)

                assertArrayEquals(raw, got)
                assertEquals((0 until raw.size step 524_288).toList(), backend.reads)
                assertTrue(backend.readPartSizes.isNotEmpty() && backend.readPartSizes.all { it == "524288" })
                assertFalse(File(dir, "$hash.part").exists())
                client.close()
            }
        }
    }

    @Test fun downloadRejectsEmptyOversizedAndUnalignedIntermediatePartsAsInvalidRange() {
        data class Case(val label: String, val totalSize: Int, val partSize: Int,
            val totalHeader: Int = totalSize, val offsetHeader: Int = 0)
        val cases = listOf(
            Case("empty chunk", 100_000, 0),
            Case("exceeds remaining", 100_000, 100_001),
            Case("exceeds 512 KiB by 1", 700_000, 524_289),
            Case("exceeds 512 KiB aligned", 700_000, 589_824),
            Case("intermediate under 64 KiB", 700_000, 32_768),
            Case("intermediate unaligned", 700_000, 100_000),
            Case("intermediate 512 KiB minus 1", 700_000, 524_287),
            Case("mismatched total header", 100_000, 65_536, totalHeader = 100_001),
            Case("mismatched offset header", 100_000, 65_536, offsetHeader = 65_536)
        )
        for (case in cases) {
            SyncV2P5Fixture().use { f ->
                val backend = Backend(f)
                val client = f.f.client().also { it.capabilities() }
                val raw = ByteArray(case.totalSize) { (it % 251).toByte() }
                val hash = SyncV2BodyCodec.hash(raw)
                backend.objects[hash] = raw
                backend.customReadResponse = { _, _, partSizeParam, _ ->
                    assertEquals("524288", partSizeParam)
                    MockResponse()
                        .setBody(Buffer().write(ByteArray(case.partSize) { (it % 251).toByte() }))
                        .setHeader("x-watchrss-body-total-bytes", case.totalHeader)
                        .setHeader("x-watchrss-body-offset", case.offsetHeader)
                }

                val dir = File(f.root, "download-invalid")
                val error = runCatching {
                    SyncV2BodyHttp(client) {}.download("cc".repeat(16), hash, raw.size, dir)
                }.exceptionOrNull()
                assertEquals(case.label, "invalid_range", error?.message)
                assertFalse(case.label, File(dir, hash).exists())
                client.close()
            }
        }
    }

    private fun partError(status: Int, code: String): MockResponse = MockResponse().setResponseCode(status)
        .setBody(JSONObject().put("error", JSONObject().put("code", code).put("retryable", false)
            .put("retryAfterMillis", JSONObject.NULL).put("details", JSONObject())).toString())

    private fun keys(f:SyncV2P5Fixture):SyncV2ContentKeys {
        val context=RuntimeEnvironment.getApplication() as Context
        val manager=CloudKeyManager(null,preferencesOverride=context.getSharedPreferences("body-${System.nanoTime()}",Context.MODE_PRIVATE)).also{it.bindBackend("http://synthetic");it.saveAccountKey("u",1,f.key,true)}
        return SyncV2ContentKeys.authenticated(manager,"u"){}
    }
    @Test fun coldCacheAfterV1BookAndLinkedAssetUsesOnlyCarrierControl() = coldCacheAfterBluetooth(binary = false)

    @Test fun coldCacheAfterB2BookAndLinkedAssetUsesOnlyCarrierControl() = coldCacheAfterBluetooth(binary = true)

    private fun coldCacheAfterBluetooth(binary: Boolean) = runBlocking<Unit> {
        SyncV2P5Fixture().use { f ->
            f.start()
            val backend = Backend(f)
            val client = f.f.client()
            val files = f.root
            val directory = File(files, "imported_books")
            val store = ImportedBookStore(f.room, directory, FileArticleContentStore(File(files, "imported_text")), "synthetic-phone")
            val size = 8 * 1024 * 1024
            val source = "https://watchrss.local/import-txt-novel/d1"
            val header = JSONObject().put("format", "watchrss-book").put("version", 1).put("title", "D1")
                .put("chapterCount", 1).put("sourceUrl", source).put("kind", "txt")
            fun chapter(text: String) = JSONObject().put("chapterIndex", 1).put("title", "Ch")
                .put("contentText", text).put("contentHtml", JSONObject.NULL).put("url", "$source/chapter/1")
            val base = "$header\n${chapter("")}\n".toByteArray()
            val bodyText = "x".repeat(size - base.size)
            val raw = "$header\n${chapter(bodyText)}\n".toByteArray()
            val fingerprint = BookSync.fingerprint(listOf(BookSync.chapterHash(1, "Ch", bodyText)))
            val bookId = BookSync.id(fingerprint)
            val hash = SyncV2BodyCodec.hash(raw)
            val state = JSONObject().put("bookId", bookId).put("fingerprint", fingerprint).put("chapterCount", 1)
                .put("title", "D1").put("kind", "txt").put("sourceUrl", source).put("deletedChapters", JSONArray())
                .put("deletionClock", 0).put("deleted", false).put("deletedAt", 0).put("updatedAt", 1)
                .put("sourceDeviceId", "synthetic-watch").put("contentAvailable", true)
            val chunkSize = BookBluetoothSync.CHUNK
            var chunksPulled = 0
            val bluetooth = BookBluetoothSync(store, "synthetic-phone", File(files, "bluetooth")) { request ->
                val reply = JSONObject().put("action", "syncBooks").put("version", 1).put("success", true)
                when (request.getString("phase")) {
                    "manifest" -> reply.put("phase", "manifest").put("books", JSONArray().put(state))
                        .put("missingBookIds", JSONArray())
                    "pull" -> {
                        val index = request.getInt("chunkIndex")
                        chunksPulled++
                        reply.put("phase", "body").put("bookId", bookId).put("fingerprint", fingerprint)
                            .put("packageSha256", hash).put("byteCount", raw.size).put("chunkIndex", index)
                            .put("chunkCount", size / chunkSize)
                            .put("dataBase64", Base64.getEncoder().encodeToString(raw.copyOfRange(index * chunkSize, (index + 1) * chunkSize)))
                    }
                    else -> error("unexpected v1 phase")
                }
            }
            if (binary) {
                store.merge(state, {}, binary = true)
                java.nio.file.Files.createDirectories(directory.toPath())
                val staged = File(files, "b2-staged-book").apply { writeBytes(raw) }
                val source = BookPackageFiles.verify(staged, BookPackageFiles.Revision(hash, raw.size.toLong())) {}
                val prepared = store.prepareBinaryPackage(bookId, requireNotNull(source), {})
                assertNull(SyncV2BodyRepository(f.sql, files).original("book", bookId, state))
                f.room.withTransaction {
                    assertEquals("merged", store.installBinaryPackage(bookId, fingerprint, state, prepared,
                        File(files, "b2-chapter-scratch").apply { mkdirs() }) {})
                }
                val published = requireNotNull(BookPackageFiles.publication(f.sql, bookId))
                assertEquals(BookPackageFiles.Revision(hash, raw.size.toLong()), published.revision)
                assertEquals("b2-$bookId-$hash-${published.slot}.jsonl", store.packageFile(bookId)!!.name)
                File(directory, "$bookId.jsonl").writeBytes(raw.copyOf().apply { this[lastIndex - 1] = 0 })
            } else {
                bluetooth.sync(syncReading = false)
                assertEquals(128, chunksPulled)
            }
            assertEquals(size, raw.size)
            assertEquals(hash, store.packageHash(bookId))

            val asset = "fixture.png"
            val image = ByteArray(33009) { (it % 251).toByte() }
            f.edit("![D1](assets/$asset)\n")
            val repository = SyncV2BodyRepository(f.sql, files)
            val assetObject = assetDescriptor(image, asset)
            if (binary) {
                val room = B2RoomStore(f.room)
                val account = "a".repeat(64)
                val business = B2NoteAssetBusiness(room, account, File(files, "notes/assets")) {}
                val staged = File(files, "b2-staged-asset").apply { writeBytes(image) }
                room.exclusive { room.transaction {
                    assertEquals("merged", business.apply(assetObject, B2Metadata.validate(assetObject), staged))
                } }
                assertEquals(emptyList<String>(), repository.refreshReferences(f.f.now) {})
                assertArrayEquals(image, repository.original("asset", asset,
                    JSONObject().put("assetId", asset).put("deleted", false))!!.raw)
            } else {
                repository.file("asset", asset).apply { parentFile!!.mkdirs(); writeBytes(image) }
                NoteRepository(f.room.noteDao(), "synthetic-phone").registerAsset(
                    NoteAssetEntity("private-d1-uuid", "note", SyncV2BodyCodec.hash(image), asset, "image/png",
                        image.size.toLong(), asset, false, f.f.now)
                )
            }
            // The real account worker observes file writes before choosing its publication revision.
            f.actions().state()
            f.drain()
            val bodies = listOf(Triple("book", bookId, raw), Triple("asset", asset, image))
            f.projection.prepareWireIndex(f.f.signer, f.key, 1).close()
            for ((kind, id, original) in bodies) {
                val entity = f.identity.entity(kind, id)
                f.projection.bindArticleRevision(f.projection.wireContentRow(entity)!!,
                    SyncV2BodyCodec.revision(kind, entity, SyncV2BodyCodec.hash(original)), true)
            }
            SyncV2CloudSync(client, f.identity, f.projection, { true }, { f.key }, { f.f.now }, files)
                .publishIndex(f.f.signer, f.key, 1)
            f.actions().refresh()
            val accepted = f.projection.state()
            assertEquals(accepted.body.getString("acknowledgedLocalRevision"), accepted.revision)
            val account = accepted.body.getJSONObject("account")
            val anchor = JSONObject().put("commitOid", account.get("oid")).put("revision", account.get("revision"))
                .put("rollbackGeneration", account.get("generation"))
            for ((kind, id, original) in bodies) {
                val entity = f.identity.entity(kind, id)
                val binding = SyncV2BodyCodec.Binding(kind, id, entity,
                    SyncV2BodyCodec.revision(kind, entity, SyncV2BodyCodec.hash(original)))
                val prepared = SyncV2BodyCodec.prepare(f.identity.scope, anchor, binding, f.key, 1, f.f.signer, original)
                val body = prepared.descriptor.getJSONObject("body")
                val carrier = body.getString("carrierId")
                backend.ready.add(carrier)
                backend.descriptors[carrier] = prepared.descriptor
                backend.objects.putAll(prepared.objects)
            }

            var installs = 0
            val readsBefore = backend.reads.size
            val putsBefore = backend.puts.size
            val controlsBefore = backend.listCalls
            keys(f).use { ring ->
                repeat(2) { round ->
                    f.reopen()
                    if (round == 1) {
                        val reading = JSONObject().put("bookId", bookId).put("chapterIndex", 1)
                            .put("sourceDeviceId", "synthetic-phone").put("isRead", true)
                            .put("readingProgress", 0.25).put("readingPositionBytes", 1)
                            .put("readingPositionContentHash", hash).put("readingPositionChangedAt", 1000)
                        val oldHead = f.current.getString("commitOid")
                        val oldTree = f.headers.getValue(oldHead).getJSONObject("body").getString("treeRoot")
                        val oldRows = SyncV2Index.read(f.identity.scope, oldTree, f.f.objects::getValue)
                        val staged = f.commit(listOf(reading), oldHead, f.current.getString("rollbackGeneration"), "normal", null)
                        val header = f.headers.remove(staged)!!.getJSONObject("body")
                        val readingRows = SyncV2Index.read(f.identity.scope, header.getString("treeRoot"), f.f.objects::getValue)
                        val tree = SyncV2Index.build(f.identity.scope, oldRows + readingRows)
                        f.f.objects.putAll(tree.objects)
                        header.put("treeRoot", tree.root)
                        val head = SyncV2Codec.oid("commit", header)
                        val signed = f.f.signer.commit(header)
                        f.headers[head] = signed
                        f.f.objects[head] = SyncV2Codec.utf8(SyncV2Codec.canonical(signed))
                        f.deviceOid = head
                        f.setCurrent(head, SyncV2Codec.next(f.current.getString("revision")))
                        f.actions().refresh()
                        val localReading = f.room.bookDao().reading(bookId, 1)!!
                        assertEquals(1000L, JSONObject(localReading.stateJson).getLong("readingPositionChangedAt"))
                    }
                    val cold = File(files, "ciphertext-$round")
                    val reader = SyncV2BodyRepository(f.sql, files) { installs++ }
                    val channel = SyncV2BodyChannel(f.projection, f.identity, ring, f.f.signer, client, reader,
                        cold, SyncV2Lifetime { true }, { true }, { SyncV2TransportSignals(wifi = true, internet = true) }, { f.f.now })
                    val ackBefore = f.ackCalls
                    assertEquals(emptyList<String>(), channel.sync().errors)
                    assertEquals(ackBefore, f.ackCalls)
                }
            }
            println("D1 cold cache size=$size bookPulls=$chunksPulled controlLists=${backend.listCalls - controlsBefore} " +
                "reads=${backend.reads.size - readsBefore} downloadBytes=${backend.downloadBytes} " +
                "puts=${backend.puts.size - putsBefore} uploadBytes=${backend.uploadBytes} installs=$installs")
            assertEquals(readsBefore, backend.reads.size)
            assertEquals(putsBefore, backend.puts.size)
            assertEquals(0L, backend.downloadBytes)
            assertEquals(0L, backend.uploadBytes)
            assertEquals(4, backend.listCalls - controlsBefore)
            assertEquals(0, installs)
            assertEquals(2, backend.ready.size)
            assertEquals(1, f.room.bookDao().all().size)
            assertEquals(1, f.sql.query("SELECT count(*) FROM note_assets WHERE storageKey=?", arrayOf(asset)).use {
                it.moveToFirst()
                it.getInt(0)
            })

            // Fresh cloud storage, with the same authenticated rows.
            backend.ready.clear()
            backend.objects.clear()
            backend.descriptors.clear()
            keys(f).use { ring ->
                fun syncAt(originals: File, cache: String) =
                    SyncV2BodyChannel(f.projection, f.identity, ring, f.f.signer, client,
                        SyncV2BodyRepository(f.sql, originals), File(files, cache), SyncV2Lifetime { true },
                        { true }, { SyncV2TransportSignals(wifi = true, internet = true) }, { f.f.now }).sync()
                assertEquals(emptyList<String>(), syncAt(files, "first-publication").errors)
                assertEquals(2, backend.completeCalls)
                assertEquals(2, backend.ready.size)
                assertTrue(backend.uploadBytes > size)
                val putBytes = backend.uploadBytes
                assertEquals(emptyList<String>(), syncAt(files, "source-restarted").errors)
                assertEquals(2, backend.completeCalls)
                assertEquals(putBytes, backend.uploadBytes)
                assertEquals(0L, backend.downloadBytes)
                val third = File(files, "third-client")
                assertEquals(emptyList<String>(), syncAt(third, "third-ciphertext").errors)
                for ((kind, id, expected) in bodies) {
                    assertArrayEquals(expected, SyncV2BodyRepository(f.sql, third).read(kind, id))
                }
                assertTrue(backend.downloadBytes > size)
                println("D1 first backup completions=" + backend.completeCalls + " uploadBytes=" + putBytes +
                    " thirdClientDownloadBytes=" + backend.downloadBytes)
            }
        }
    }

    @Test fun d1RechecksScopeWindowReferenceAndBytesBeforeEndingLocalDemand() {
        SyncV2P5Fixture().use { f ->
            f.start()
            val backend = Backend(f)
            val client = f.f.client()
            val id = "boundary.png"
            val raw = ByteArray(80000) { (it % 251).toByte() }
            val changed = raw.clone().also { it[0] = 42 }
            val hash = SyncV2BodyCodec.hash(raw)
            f.edit("![boundary](assets/$id)\n")
            val repo = SyncV2BodyRepository(f.sql, f.root)
            val file = repo.file("asset", id).apply { parentFile!!.mkdirs(); writeBytes(raw) }
            SyncV2PhoneApply(f.sql).upsert("note_assets", listOf("assetId"), JSONObject()
                .put("assetId", "private-uuid").put("storageKey", id).put("noteId", "note")
                .put("mimeType", "image/png").put("displayName", id).put("createdAt", 1)
                .put("sha256", hash).put("byteCount", raw.size).put("deleted", false).put("deletedAt", 0))
            f.drain()
            val entity = f.identity.entity("asset", id)
            val rev = SyncV2BodyCodec.revision("asset", entity, hash)
            f.projection.prepareWireIndex(f.f.signer, f.key, 1).close()
            val wire = f.projection.bindArticleRevision(f.projection.wireContentRow(entity)!!, rev, true)
            assertEquals(rev, wire.getString("promiseId"))
            SyncV2CloudSync(client, f.identity, f.projection, { true }, { f.key }, { f.f.now })
                .publishIndex(f.f.signer, f.key, 1)
            f.actions().refresh()
            val account = f.projection.state().body.getJSONObject("account")
            val anchor = JSONObject().put("commitOid", account.get("oid")).put("revision", account.get("revision"))
                .put("rollbackGeneration", account.get("generation"))
            val descriptor = SyncV2BodyCodec.prepare(f.identity.scope, anchor, SyncV2BodyCodec.Binding("asset", id, entity, rev),
                f.key, 1, f.f.signer, raw)
            val body = descriptor.descriptor.getJSONObject("body")
            val carrier = body.getString("carrierId")
            backend.ready.add(carrier)
            backend.descriptors[carrier] = descriptor.descriptor
            backend.objects.putAll(descriptor.objects)
            var clock = f.f.now
            keys(f).use { ring ->
                var attempt = 0
                var installs = 0
                fun sync() = SyncV2BodyChannel(f.projection, f.identity, ring, f.f.signer, client,
                    SyncV2BodyRepository(f.sql, f.root) { installs++ }, File(f.root, "negative-${attempt++}"),
                    SyncV2Lifetime { true }, { true }, { SyncV2TransportSignals(wifi = true, internet = true) }, { clock }).sync()

                backend.descriptors[carrier] = JSONObject(descriptor.descriptor.toString())
                    .put("signature", SyncV2Codec.b64(ByteArray(64) { 3 }))
                assertEquals(listOf("signature_invalid"), sync().errors)

                val wrongScope = JSONObject(f.identity.scope.toString()).put("libraryEpoch", "99")
                backend.descriptors[carrier] = f.f.signer.signed(SyncV2BodyCodec.DOMAIN,
                    JSONObject(body.toString()).put("scope", wrongScope))
                assertEquals(listOf("scope_mismatch"), sync().errors)
                backend.descriptors[carrier] = f.f.signer.signed(SyncV2BodyCodec.DOMAIN,
                    JSONObject(body.toString()).put("keyVersion", 2))
                assertEquals(listOf("key_unavailable"), sync().errors)
                backend.descriptors[carrier] = descriptor.descriptor

                backend.beforeList = { clock = f.f.now + 300000 }
                assertEquals(listOf("window_expired"), sync().errors)
                clock = f.f.now

                backend.beforeList = {
                    val replacement = File(f.root, "asset-replacement").apply { writeBytes(changed) }
                    java.nio.file.Files.move(replacement.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
                assertEquals(listOf("body_revision_changed"), sync().errors)
                assertArrayEquals(changed, file.readBytes())
                assertEquals(0, backend.reads.size)

                backend.beforeList = null
                f.sql.execSQL("UPDATE note_assets SET sha256=? WHERE storageKey=?", arrayOf(SyncV2BodyCodec.hash(changed), id))
                assertEquals(listOf("body_revision_changed"), sync().errors)
                assertEquals(0, backend.reads.size)
                f.sql.execSQL("UPDATE note_assets SET sha256=? WHERE storageKey=?", arrayOf(hash, id))
                // An unchanged corrupted file can be repaired by the authenticated current carrier.
                assertEquals(emptyList<String>(), sync().errors)
                assertTrue(backend.downloadBytes > 0)
                assertArrayEquals(raw, file.readBytes())
                val downloaded = backend.downloadBytes
                assertTrue(file.delete())
                assertEquals(emptyList<String>(), sync().errors)
                assertTrue(backend.downloadBytes > downloaded)
                assertArrayEquals(raw, file.readBytes())

                val reads = backend.reads.size
                backend.beforeList = { f.rollbackRemote(); f.actions().refresh() }
                assertEquals(listOf("choice_stale"), sync().errors)
                assertEquals("1", f.projection.state().body.getString("rollbackGeneration"))
                assertEquals(reads, backend.reads.size)
                assertEquals(0, backend.puts.size)
                assertEquals(2, installs) // Repair and subsequent authenticated hydration.
            }
        }
    }

    @Test fun lostUploadAndDownloadResponsesResumeFromDurableOffsetsAcrossReopen() {
        SyncV2P5Fixture().use{f->val backend=Backend(f);val client=f.f.client();client.capabilities();val http=SyncV2BodyHttp(client){}
            val raw=ByteArray(400000){(it%251).toByte()};val entity=f.identity.entity("asset","resume.png");val binding=SyncV2BodyCodec.Binding("asset","resume.png",entity,SyncV2BodyCodec.revision("asset",entity,SyncV2BodyCodec.hash(raw)))
            val anchor=JSONObject().put("commitOid","commit:sha256:"+"ab".repeat(32)).put("revision","1").put("rollbackGeneration","0")
            val p=SyncV2BodyCodec.prepare(f.identity.scope,anchor,binding,f.key,1,f.f.signer,raw);val cache=File(f.root,"upload")
            for((h,b)in p.objects)SyncV2BodyHttp.atomic(File(cache,h),b)
            backend.losePart=true
            try{http.upload(p.descriptor,cache);fail("lost response succeeded")}catch(_:java.io.IOException){}
            val first=backend.puts.first();val next=f.f.client();next.capabilities();val reopened=SyncV2BodyHttp(next){};reopened.upload(p.descriptor,cache)
            assertEquals(1,backend.puts.count{it==first});assertEquals(1,backend.ready.size)
            val count=backend.puts.size;reopened.upload(p.descriptor,cache);assertEquals(count,backend.puts.size)
            val body=p.descriptor.getJSONObject("body");val objectRef=body.getJSONArray("chunks").getJSONObject(0);val dir=File(f.root,"download")
            backend.loseRead=true;try{reopened.download(body.getString("carrierId"),objectRef.getString("sha256"),objectRef.getInt("sizeBytes"),dir);fail("download succeeded")}catch(_:java.io.IOException){}
            assertEquals(65536,File(dir,objectRef.getString("sha256")+".part").length().toInt());val before=backend.reads.size
            val got=SyncV2BodyHttp(client){}.download(body.getString("carrierId"),objectRef.getString("sha256"),objectRef.getInt("sizeBytes"),dir)
            assertEquals(65536,backend.reads[before]);assertEquals(objectRef.getString("sha256"),SyncV2BodyCodec.hash(got))
        }
    }
    private fun renamedBody(kind:String,choice:Boolean) {
        SyncV2P5Fixture().use{f->f.start();val backend=Backend(f);val client=f.f.client()
            val text="body original";val fingerprint=com.lightningstudio.watchrss.phone.data.book.BookSync.fingerprint(listOf(com.lightningstudio.watchrss.phone.data.book.BookSync.chapterHash(1,"chapter",text)))
            val id=if(kind=="book")com.lightningstudio.watchrss.phone.data.book.BookSync.id(fingerprint)else"rename.png"
            val raw=if(kind=="asset")text.toByteArray()else (JSONObject().put("format","watchrss-book").put("version",1).put("title","original title").put("chapterCount",1).put("sourceUrl","https://watchrss.local/import-txt-novel/review").put("kind","txt").toString()+"\n"+JSONObject().put("chapterIndex",1).put("title","chapter").put("contentText",text).put("contentHtml",JSONObject.NULL).put("url","https://watchrss.local/import-txt-novel/review/chapter/1").toString()+"\n").toByteArray()
            val value=JSONObject().put("bookId",id).put("fingerprint",fingerprint).put("title","original title").put("chapterCount",1).put("deletedChapters",JSONArray()).put("deletionClock",0).put("deleted",false).put("deletedAt",0).put("updatedAt",1).put("sourceDeviceId","synthetic-phone").put("sourceUrl","https://watchrss.local/import-txt-novel/review").put("kind","txt")
            val apply=SyncV2PhoneApply(f.sql)
            if(kind=="book")apply.upsert("imported_books",listOf("bookId"),JSONObject().put("bookId",id).put("stateJson",value.toString()))
            else apply.upsert("note_assets",listOf("assetId"),JSONObject().put("assetId","private-rename").put("storageKey",id).put("mimeType","image/png").put("displayName","original title").put("createdAt",1).put("deleted",false).put("deletedAt",0))
            val seed=if(kind=="book")f.seedBookOriginal(id,raw)else null
            f.drain();f.projection.prepareWireIndex(f.f.signer,f.key,1).close()
            val entity=f.identity.entity(kind,id);val rev=SyncV2BodyCodec.revision(kind,entity,SyncV2BodyCodec.hash(raw))
            f.projection.bindArticleRevision(f.projection.wireContentRow(entity)!!,rev,independent=true)
            val cloud=SyncV2CloudSync(client,f.identity,f.projection,{true},{f.key},{f.f.now},f.root);cloud.publishIndex(f.f.signer,f.key,1);f.actions().refresh()
            seed?.let{check(it.delete())}
            val old=f.projection.cloudRows(setOf(entity)).single().getJSONObject("row")
            val account=f.projection.state().body.getJSONObject("account");val anchor=JSONObject().put("commitOid",account.get("oid")).put("revision",account.get("revision")).put("rollbackGeneration",account.get("generation"))
            val prepared=SyncV2BodyCodec.prepare(f.identity.scope,anchor,SyncV2BodyCodec.Binding(kind,id,entity,rev),f.key,1,f.f.signer,raw)
            val upload=File(f.root,"fixture-upload");for((h,b)in prepared.objects)SyncV2BodyHttp.atomic(File(upload,h),b)
            SyncV2BodyHttp(client){}.upload(prepared.descriptor,upload)
            val repository=SyncV2BodyRepository(f.sql,f.root);assertFalse(repository.file(kind,id).exists())
            if(kind=="book")f.sql.execSQL("UPDATE imported_books SET stateJson=? WHERE bookId=?",arrayOf(value.put("title","offline renamed").put("updatedAt",2).toString(),id))
            else f.sql.execSQL("UPDATE note_assets SET displayName='offline renamed',mimeType='application/octet-stream',createdAt=2 WHERE storageKey=?",arrayOf(id))
            f.drain()
            if(choice){f.projection.requestConcurrentChoice();val actions=f.actions();actions.choose(actions.preview(),"local")}
            else cloud.publishIndex(f.f.signer,f.key,1)
            f.actions().refresh()
            val current=f.projection.cloudRows(setOf(entity)).single().getJSONObject("row")
            for(field in SyncV2BodyIdentity.fields)assertEquals("$kind/$choice/$field",old.get(field),current.get(field))
            assertFalse(repository.file(kind,id).exists());val ack=f.ackCalls
            keys(f).use{keys->val channel=SyncV2BodyChannel(f.projection,f.identity,keys,f.f.signer,client,repository,File(f.root,"receiver-cache"),SyncV2Lifetime{true},{true},{SyncV2TransportSignals(wifi=true,internet=true)},{f.f.now})
                assertEquals(emptyList<String>(),channel.sync().errors)}
            assertArrayEquals(raw,repository.file(kind,id).readBytes());assertEquals(ack,f.ackCalls)
            if (kind == "book" && !choice) {
                val path = repository.file(kind, id)
                val damaged = raw.clone().also { it[it.lastIndex] = 32 }
                path.writeBytes(damaged)
                val readBytes = backend.downloadBytes
                keys(f).use { ring ->
                    val channel = SyncV2BodyChannel(f.projection, f.identity, ring, f.f.signer, client, repository,
                        File(f.root, "repair-cold-cache"), SyncV2Lifetime { true }, { true },
                        { SyncV2TransportSignals(wifi = true, internet = true) }, { f.f.now })
                    assertEquals(emptyList<String>(), channel.sync().errors)
                }
                assertTrue(backend.downloadBytes > readBytes)
                assertArrayEquals(raw, path.readBytes())
                assertEquals(ack, f.ackCalls)
            }
            val title=if(kind=="book")f.sql.query("SELECT stateJson FROM imported_books WHERE bookId=?",arrayOf(id)).use{it.moveToFirst();JSONObject(it.getString(0)).getString("title")}else f.sql.query("SELECT displayName FROM note_assets WHERE storageKey=?",arrayOf(id)).use{it.moveToFirst();it.getString(0)}
            assertEquals("offline renamed",title);assertEquals(1,backend.ready.size)
        }
    }
    @Test fun metadataOnlyBookRenameRetainsDescriptorUntilLaterDownload()=renamedBody("book",false)
    @Test fun metadataOnlyAssetRenameRetainsDescriptorUntilLaterDownload()=renamedBody("asset",false)
    @Test fun localChoiceBookRenameRetainsDescriptorUntilLaterDownload()=renamedBody("book",true)
    @Test fun localChoiceAssetRenameRetainsDescriptorUntilLaterDownload()=renamedBody("asset",true)
    @Test fun finalInstallRacePreservesConcurrentLocalBytesAndRecordsConflict() {
        for(present in listOf(false,true))SyncV2P5Fixture().use{f->
            val original="local original".toByteArray();val incoming="remote original".toByteArray()
            val target=File(f.root,"notes/assets/race.png");if(present){target.parentFile!!.mkdirs();target.writeBytes(incoming)}
            val repository=SyncV2BodyRepository(f.sql,f.root){file->com.lightningstudio.watchrss.phone.data.local.CloudExportFileWitness.writeBytes(file,original)}
            f.sql.beginTransaction();try {
                try{repository.install("asset","race.png",incoming);fail("replaced concurrent original")}catch(e:IllegalStateException){assertEquals("body_revision_changed",e.message)}
            }finally{f.sql.endTransaction()}
            assertArrayEquals(original,target.readBytes())
            val conflict=JSONObject(com.lightningstudio.watchrss.phone.data.local.ConditionalBodyFile.conflictRecord(target).readText())
            assertEquals(SyncV2BodyCodec.hash(original),conflict.getString("actualSha256"));assertEquals(1,conflict.getInt("attempts"))
        }
    }
    @Test fun actualSignedAccountBodyGateRetryAndAtomicReceiveNeverAcknowledgeBody() {
        SyncV2P5Fixture().use{f->f.start();val backend=Backend(f);val client=f.f.client();val raw="actual original é😀".repeat(18000).toByteArray();val id="phone.png"
            SyncV2PhoneApply(f.sql).upsert("note_assets",listOf("assetId"),JSONObject().put("assetId","private-uuid").put("storageKey",id).put("mimeType","image/png").put("displayName",id).put("createdAt",1).put("deleted",false).put("deletedAt",0))
            f.edit("![Asset](assets/$id)\n")
            val repository=SyncV2BodyRepository(f.sql,f.root);val original=repository.file("asset",id);original.parentFile!!.mkdirs();original.writeBytes(raw);f.drain()
            keys(f).use{keys->val life=SyncV2Lifetime{true};var online=false;var clock=f.f.now
                fun channel()=SyncV2BodyChannel(f.projection,f.identity,keys,f.f.signer,client,repository,File(f.root,"bodies"),life,{true},{SyncV2TransportSignals(wifi=online,internet=online)},{clock})
                var channel=channel();f.projection.prepareWireIndex(f.f.signer,f.key,1).close();channel.bindPrepared()
                SyncV2CloudSync(client,f.identity,f.projection,{true},{f.key},{f.f.now}).publishIndex(f.f.signer,f.key,1);f.actions().refresh()
                val ack=f.ackCalls;assertEquals(listOf("network_unavailable"),channel.sync().errors);assertEquals(0,backend.bodyRequests)
                online=true;backend.writeError="body_channel_write_disabled";assertEquals(listOf("body_channel_write_disabled"),channel.sync().errors);assertEquals(0,backend.objects.size);assertArrayEquals(raw,original.readBytes());assertEquals(ack,f.ackCalls)
                // Metadata remains independently publishable while the body gate is closed.
                f.edit("metadata continues\n![Asset](assets/$id)\n");SyncV2CloudSync(client,f.identity,f.projection,{true},{f.key},{f.f.now}).publishIndex(f.f.signer,f.key,1);f.actions().refresh();assertTrue(f.text().startsWith("metadata continues\n"))
                backend.writeError="rate_limited";backend.status=429;backend.delay=1000
                assertEquals(listOf("rate_limited"),channel.sync().errors);val requests=backend.bodyRequests;channel=channel();assertEquals(listOf("rate_limited"),channel.sync().errors);assertEquals(requests,backend.bodyRequests)
                clock+=1001;f.f.now=clock;backend.writeError="quota_exceeded";backend.status=507;backend.delay=2000
                assertEquals(listOf("quota_exceeded"),channel.sync().errors);val quotaRequests=backend.bodyRequests;channel=channel();assertEquals(listOf("quota_exceeded"),channel.sync().errors);assertEquals(quotaRequests,backend.bodyRequests)
                // Frozen backend 507 shape: no Retry-After and retryable=false.
                clock+=2001;f.f.now=clock;backend.delay=null;backend.retryable=false
                assertEquals(listOf("quota_exceeded"),channel.sync().errors)
                val noDelayRequests=backend.bodyRequests
                channel=channel();assertEquals(listOf("quota_exceeded"),channel.sync().errors);assertEquals(noDelayRequests,backend.bodyRequests)
                val persisted=JSONObject(File(f.root,"bodies/retry.json").readText());assertEquals(clock+60000,persisted.getLong("until"))
                clock+=60001;f.f.now=clock;backend.writeError=null;assertEquals(emptyList<String>(),channel.sync().errors);assertEquals(1,backend.ready.size)
                // A separate local body directory simulates the receiving phone, with the same authenticated Room metadata.
                val receiverRoot=File(f.root,"receiver");val receiver=SyncV2BodyRepository(f.sql,receiverRoot)
                val remote=SyncV2BodyChannel(f.projection,f.identity,keys,f.f.signer,client,receiver,File(f.root,"receiver-cache"),life,{true},{SyncV2TransportSignals(wifi=true,internet=true)},{clock})
                val beforeAck=f.ackCalls;val beforeRevision=f.projection.state().revision;val originalClock=f.sql.query("SELECT createdAt FROM note_assets WHERE storageKey=?",arrayOf(id)).use{it.moveToFirst();it.getLong(0)}
                val carrierId=backend.ready.single();val good=backend.descriptors.getValue(carrierId)
                backend.descriptors[carrierId]=JSONObject(good.toString()).put("signature",SyncV2Codec.b64(ByteArray(64){1}))
                assertEquals(listOf("signature_invalid"),remote.sync().errors);assertFalse(receiver.file("asset",id).exists());assertArrayEquals(raw,original.readBytes());assertEquals(beforeAck,f.ackCalls)
                backend.descriptors[carrierId]=f.f.signer.signed(SyncV2BodyCodec.DOMAIN,JSONObject(good.getJSONObject("body").toString()).put("chunks",JSONArray()))
                assertEquals(listOf("closure_incomplete"),remote.sync().errors);assertFalse(receiver.file("asset",id).exists());assertArrayEquals(raw,original.readBytes())
                backend.descriptors[carrierId]=good
                val hash=good.getJSONObject("body").getJSONArray("chunks").getJSONObject(0).getString("sha256");val saved=backend.objects.getValue(hash)
                backend.objects[hash]=saved.copyOf().also{it[it.lastIndex]=(it.last().toInt() xor 1).toByte()}
                assertEquals(listOf("hash_mismatch"),remote.sync().errors);assertFalse(receiver.file("asset",id).exists());assertArrayEquals(raw,original.readBytes());assertEquals(beforeAck,f.ackCalls)
                backend.objects[hash]=saved
                assertEquals(emptyList<String>(),remote.sync().errors);assertArrayEquals(raw,receiver.file("asset",id).readBytes());assertEquals(beforeAck,f.ackCalls);assertEquals(beforeRevision,f.projection.state().revision)
                assertEquals(originalClock,f.sql.query("SELECT createdAt FROM note_assets WHERE storageKey=?",arrayOf(id)).use{it.moveToFirst();it.getLong(0)})
                assertEquals("private-uuid",f.sql.query("SELECT assetId FROM note_assets WHERE storageKey=?",arrayOf(id)).use{it.moveToFirst();it.getString(0)})
            }
        }
    }
 @Test fun allFiveMiBEncryptedBytesUploadAndDownloadThroughActualHttp() {
  SyncV2P5Fixture().use{f->
   // Reuse the original synthetic server dispatcher, without replacing product code.
   val backend=Backend(f)
   val client=f.f.client();client.capabilities();val http=SyncV2BodyHttp(client){}
   val raw=ByteArray(5*1024*1024){(it%251).toByte()};val entity=f.identity.entity("asset","five-mib.bin")
   val revision=SyncV2BodyCodec.revision("asset",entity,SyncV2BodyCodec.hash(raw));val binding=SyncV2BodyCodec.Binding("asset","five-mib.bin",entity,revision)
   val anchor=JSONObject().put("commitOid","commit:sha256:"+"ab".repeat(32)).put("revision","1").put("rollbackGeneration","0")
   val p=SyncV2BodyCodec.prepare(f.identity.scope,anchor,binding,f.key,1,f.f.signer,raw);val upload=File(f.root,"upload");for((h,b)in p.objects)SyncV2BodyHttp.atomic(File(upload,h),b)
   http.upload(p.descriptor,upload)
   val receive=File(f.root,"receive");val decoded=SyncV2BodyCodec.open(p.descriptor,f.identity.scope,binding,f.f.signer.publicHex,{f.key}){h,n->http.download(p.descriptor.getJSONObject("body").getString("carrierId"),h,n,receive)}
   assertArrayEquals(raw,decoded)
   assertTrue(backend.puts.size>80);assertTrue(backend.reads.size>80)
   assertFalse(receive.listFiles()!!.any{it.name.endsWith(".part")})
  }
 }

    private inner class ReviewAsset(val f: SyncV2P5Fixture, ready: Boolean) : AutoCloseable {
        val backend: Backend
        val client: SyncV2CloudClient
        val id = "review.png"
        val raw = ByteArray(180000) { (it % 251).toByte() }
        val hash = SyncV2BodyCodec.hash(raw)
        val changed = raw.clone().also { it[0] = 42 }
        val entity = f.identity.entity("asset", id)
        val rev = SyncV2BodyCodec.revision("asset", entity, hash)
        val file = SyncV2BodyRepository(f.sql, f.root).file("asset", id)
        val ring: SyncV2ContentKeys
        val descriptor: SyncV2BodyCodec.Prepared
        val life = SyncV2Lifetime { true }
        val clock = java.util.concurrent.atomic.AtomicLong()
        var attempt = 0
        var installs = 0
        init {
            f.start()
            backend = Backend(f)
            client = f.f.client()
            f.edit("![review](assets/$id)\n")
            file.parentFile!!.mkdirs()
            file.writeBytes(raw)
            SyncV2PhoneApply(f.sql).upsert("note_assets", listOf("assetId"), JSONObject()
                .put("assetId", "private-fixture").put("storageKey", id).put("noteId", "note")
                .put("mimeType", "image/png").put("displayName", id).put("createdAt", 1)
                .put("sha256", hash).put("byteCount", raw.size).put("deleted", false).put("deletedAt", 0))
            f.drain()
            f.projection.prepareWireIndex(f.f.signer, f.key, 1).close()
            f.projection.bindArticleRevision(f.projection.wireContentRow(entity)!!, rev, true)
            SyncV2CloudSync(client, f.identity, f.projection, { true }, { f.key }, { f.f.now })
                .publishIndex(f.f.signer, f.key, 1)
            f.actions().refresh()
            val a = f.projection.state().body.getJSONObject("account")
            val anchor = JSONObject().put("commitOid", a.get("oid")).put("revision", a.get("revision"))
                .put("rollbackGeneration", a.get("generation"))
            descriptor = SyncV2BodyCodec.prepare(f.identity.scope, anchor, SyncV2BodyCodec.Binding("asset", id, entity, rev),
                f.key, 1, f.f.signer, raw)
            if (ready) {
                val carrier = descriptor.descriptor.getJSONObject("body").getString("carrierId")
                backend.ready.add(carrier)
                backend.descriptors[carrier] = descriptor.descriptor
                backend.objects.putAll(descriptor.objects)
            }
            ring = keys(f)
            clock.set(f.f.now)
        }
        fun channel(approved: (String) -> Boolean = { true }, now: () -> Long = clock::get,
            beforeInstall: () -> Unit = {}, originals: File = f.root) =
            SyncV2BodyChannel(f.projection, f.identity, ring, f.f.signer, client,
                SyncV2BodyRepository(f.sql, originals) { installs++; beforeInstall() },
                File(f.root, "review-${attempt++}"), life, approved,
                { SyncV2TransportSignals(wifi = true, internet = true) }, now)
        override fun close() { life.close(); ring.close(); client.close(); f.close() }
    }

    private fun ReviewAsset.bluetoothWindowKey(): String {
        val account = f.projection.state().body.getJSONObject("account")
        return SyncV2Codec.hash(JSONArray().put(f.projection.scope).put(account.getString("oid"))
            .put(account.getString("revision")).put(account.getString("generation")))
    }

    @Test fun matchingPlanInTheProductionHeadWindowDefersCloudBodyAndRequeuesOnClose() {
        ReviewAsset(SyncV2P5Fixture(), true).use { a ->
            assertTrue(a.file.delete())
            val writerKey = a.bluetoothWindowKey()
            var retries = 0
            B2BodyHandoff.open(a.f.root, writerKey) { retries++ }.use { connection ->
                connection.verified("c".repeat(32), listOf(assetDescriptor(a.raw, a.id)))
                assertTrue(B2BodyHandoff.hasPending(a.f.root, writerKey, "asset", a.id))
                assertEquals(SyncV2BodyChannel.Result(1, listOf("body_channel_pending")), a.channel().sync())
                assertEquals(1, a.backend.listCalls)
                assertEquals(0, a.backend.reads.size)
                assertEquals(0, a.backend.puts.size)
                assertEquals(0, a.installs)
                assertFalse(a.file.exists())
            }
            assertEquals(1, retries)
        }
    }

    @Test fun b2CommitBeforeFirstBodyRequestAvoidsDuplicateGet() {
        ReviewAsset(SyncV2P5Fixture(), true).use { a ->
            assertTrue(a.file.delete())
            val inbound = assetDescriptor(a.raw, a.id)
            val job = "b".repeat(32)
            var committed = false
            val result = a.channel(now = {
                val stack = Thread.currentThread().stackTrace
                if (!committed && stack.any {
                        it.className == SyncV2BodyChannel::class.java.name && it.methodName.startsWith("receive")
                    }) {
                    assertEquals(0, a.backend.reads.size)
                    committed = true
                    B2BodyHandoff.open(a.f.root, a.bluetoothWindowKey()).use { connection ->
                        connection.verified(job, listOf(inbound))
                        runBlocking { assertEquals("merged", publishFixtureAttachment(a)) }
                        connection.applied(job, inbound.getString("objectId"))
                    }
                    assertArrayEquals(a.raw, NoteAssetFiles.current(a.f.sql, a.f.root, a.id).readBytes())
                }
                a.clock.get()
            }).sync()
            println("commit before first cloud request: committed=$committed result=$result GET=${a.backend.reads.size} install=${a.installs}")
            assertTrue(committed)
            assertEquals(SyncV2BodyChannel.Result(0, emptyList()), result)
            assertEquals(0, a.installs)
            assertEquals("same-version original had already committed before the first cloud request", 0, a.backend.reads.size)
        }
    }

    private suspend fun publishFixtureAttachment(a: ReviewAsset): String {
        val room = B2RoomStore(a.f.room)
        val receiver = B2NoteAssetBusiness(room, "a".repeat(64), a.file.parentFile!!) {}
        val descriptor = assetDescriptor(a.raw, a.id)
        val staged = File(a.f.root, "b2-fixture-repair-input").apply { writeBytes(a.raw) }
        return room.exclusive { room.transaction {
            receiver.apply(descriptor, B2Metadata.validate(descriptor), staged)
        } }
    }

    @Test fun sameRevisionB2TransferRestoresMissingCommittedAttachment() = runBlocking<Unit> {
        ReviewAsset(SyncV2P5Fixture(), false).use { a ->
            assertTrue(a.file.delete())
            assertEquals("merged", publishFixtureAttachment(a))
            assertTrue(NoteAssetFiles.current(a.f.sql, a.f.root, a.id).delete())
            assertEquals("merged", publishFixtureAttachment(a))
            val current = requireNotNull(NoteAssetFiles.readable(a.f.sql, a.f.root, a.id))
            assertArrayEquals(a.raw, current.readBytes())
            assertFalse(a.file.exists())
        }
    }

    @Test fun zipRestoresTheCurrentB2ReferenceRatherThanTheLegacyPath() = runBlocking<Unit> {
        ReviewAsset(SyncV2P5Fixture(), false).use { a ->
            assertTrue(a.file.delete())
            assertEquals("merged", publishFixtureAttachment(a))
            val context = object : android.content.ContextWrapper(RuntimeEnvironment.getApplication()) {
                override fun getFilesDir(): File = a.f.root
            }
            val service = NoteImportExportService(context, NoteRepository(a.f.room.noteDao(), "phone"),
                assetDatabase = { a.f.sql }) { key -> NoteAssetFiles.readable(a.f.sql, a.f.root, key) }
            val zip = service.exportZip()
            assertArrayEquals(a.raw, MarkdownNoteArchive.read(zip).single { it.path == "notes/assets/${a.id}" }.bytes)
            assertTrue(NoteAssetFiles.current(a.f.sql, a.f.root, a.id).delete())
            val corrupt = a.raw.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() }
            service.restoreAssetsZip(MarkdownNoteArchive.write(listOf(MarkdownArchiveEntry("notes/assets/${a.id}", corrupt))))
            assertNull(NoteAssetFiles.readable(a.f.sql, a.f.root, a.id))
            service.restoreAssetsZip(zip)
            val current = requireNotNull(NoteAssetFiles.readable(a.f.sql, a.f.root, a.id))
            assertArrayEquals(a.raw, current.readBytes())
            assertFalse(a.file.exists())
            assertArrayEquals(a.raw, MarkdownNoteArchive.read(service.exportZip()).single { it.path == "notes/assets/${a.id}" }.bytes)
        }
    }

    @Test fun verifiedB2PlanDefersOnlyTheMatchingAuthenticatedBodyAndReusesTheCommit() = runBlocking<Unit> {
        ReviewAsset(SyncV2P5Fixture(), true).use { a ->
            assertTrue(a.file.delete())
            val scope = a.bluetoothWindowKey()
            val descriptor = assetDescriptor(a.raw, a.id)
            val otherRevision = assetDescriptor(a.changed, a.id)
            val room = B2RoomStore(a.f.room)
            val receiver = B2NoteAssetBusiness(room, "a".repeat(64), a.file.parentFile!!) {}
            val staged = File(a.f.root, "fixture-b2-staged").apply { writeBytes(a.raw) }
            val repo = SyncV2BodyRepository(a.f.sql, a.f.root)
            val binding = SyncV2BodyCodec.Binding("asset", a.id, a.entity, a.rev)
            val job = "a".repeat(32)
            B2BodyHandoff.open(a.f.root, scope).use { pending ->
                pending.verified(job, listOf(otherRevision))
                assertFalse(repo.pendingBluetooth(binding, scope))
                pending.verified(job, listOf(descriptor))
                assertFalse(repo.pendingBluetooth(binding, "$scope-other"))
                assertTrue(repo.pendingBluetooth(binding, scope))
                assertEquals(SyncV2BodyChannel.Result(1, listOf("body_channel_pending")), a.channel().sync())
                assertEquals(1, a.backend.listCalls)
                assertFalse(a.file.exists())
                assertEquals(0, a.backend.reads.size)
                room.exclusive { room.transaction {
                    assertEquals("merged", receiver.apply(descriptor, B2Metadata.validate(descriptor), staged))
                } }
                pending.applied(job, descriptor.getString("objectId"))
                assertEquals(SyncV2BodyChannel.Result(0, emptyList()), a.channel().sync())
            }
            assertArrayEquals(a.raw, NoteAssetFiles.current(a.f.sql, a.f.root, a.id).readBytes())
            assertEquals(0, a.installs)
            assertEquals(0, a.backend.reads.size)
            assertEquals(0, a.backend.puts.size)
        }
    }

    @Test fun interruptedB2PlanRequeuesDeferredCloudReadAfterBluetoothCloses() {
        ReviewAsset(SyncV2P5Fixture(), true).use { a ->
            assertTrue(a.file.delete())
            val scope = a.bluetoothWindowKey()
            val fence = BluetoothAutomaticCloudFence()
            fence.setActive(true)
            B2BodyHandoff.open(a.f.root, scope, fence::deferBodyRetry).use { connection ->
                connection.verified("c".repeat(32), listOf(assetDescriptor(a.raw, a.id)))
                assertEquals(SyncV2BodyChannel.Result(1, listOf("body_channel_pending")), a.channel().sync())
                assertEquals(0, a.backend.reads.size)
            }
            assertFalse(fence.needsResume)
            fence.setActive(false)
            assertTrue(fence.needsResume)
            fence.started(manual = false, job = null)
            assertEquals(SyncV2BodyChannel.Result(0, emptyList()), a.channel().sync())
            assertTrue(a.backend.reads.isNotEmpty())
            assertEquals(1, a.installs)
            assertEquals(0, a.backend.puts.size)
            assertArrayEquals(a.raw, a.file.readBytes())
            assertFalse(fence.needsResume)
        }
    }

    @Test fun finalOriginalCheckRejectsReplacementDuringLastWindowCheck() {
        ReviewAsset(SyncV2P5Fixture(), true).use { a ->
            val ack = a.f.ackCalls
            var replaced = false
            val result = a.channel(now = {
                val onFinalCurrentCheck = a.backend.listCalls == 1 && Thread.currentThread().stackTrace.any {
                    it.className == SyncV2BodyChannel::class.java.name && it.methodName == "current"
                }
                if (onFinalCurrentCheck && !replaced) {
                    replaced = true
                    val other = File(a.f.root, "replacement").apply { writeBytes(a.changed) }
                    java.nio.file.Files.move(other.toPath(), a.file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
                a.clock.get()
            }).sync()
            assertTrue(replaced)
            assertArrayEquals(a.changed, a.file.readBytes())
            assertEquals(0, a.backend.reads.size)
            assertEquals(0, a.installs)
            assertEquals(0, a.backend.puts.size)
            assertEquals(ack, a.f.ackCalls)
            assertEquals(SyncV2BodyChannel.Result(1, listOf("body_revision_changed")), result)
            println("final-proof race: $result, replaced=true, GET=0, PUT=0, install=0, ACK=0")
        }
    }

    @Test fun installRefusesPublicationAfterWindowExpires() {
        ReviewAsset(SyncV2P5Fixture(), true).use { a ->
            val ack = a.f.ackCalls
            assertTrue(a.file.delete())
            val result = a.channel(beforeInstall = {
                assertFalse(a.file.exists())
                a.clock.set(a.f.f.now + 300001)
            }).sync()
            assertEquals(1, a.installs)
            assertFalse(a.file.exists())
            assertTrue(a.backend.downloadBytes > 0)
            assertEquals(SyncV2BodyChannel.Result(1, listOf("window_expired")), result)
            assertEquals(ack, a.f.ackCalls)
            println("expired install: $result, visible=${a.file.length()}, GET=${a.backend.downloadBytes}, ACK=0")
        }
    }

    @Test fun legacyUnknownDigestIsRepairedByAuthenticatedCarrier() {
        ReviewAsset(SyncV2P5Fixture(), true).use { a ->
            a.f.sql.execSQL("UPDATE note_assets SET sha256='' WHERE storageKey=?", arrayOf(a.id))
            a.file.writeBytes(a.changed)
            val result = a.channel().sync()
            assertEquals(SyncV2BodyChannel.Result(0, emptyList()), result)
            assertEquals(1, a.backend.listCalls)
            assertTrue(a.backend.reads.isNotEmpty())
            assertArrayEquals(a.raw, a.file.readBytes())
            assertEquals(1, a.installs)
            println("legacy damaged original: $result, list=${a.backend.listCalls}, GET=${a.backend.downloadBytes}, installs=${a.installs}, restored=true")
        }
    }

    @Test fun registeredCorruptionIsRepairedByAuthenticatedCarrier() {
        ReviewAsset(SyncV2P5Fixture(), true).use { a ->
            a.file.writeBytes(a.changed)
            val result = a.channel().sync()
            assertEquals(SyncV2BodyChannel.Result(0, emptyList()), result)
            assertEquals(1, a.backend.listCalls)
            assertTrue(a.backend.downloadBytes > 0)
            assertEquals(1, a.installs)
            assertArrayEquals(a.raw, a.file.readBytes())
            println("registered damaged original: $result, GET=${a.backend.downloadBytes}, installs=${a.installs}, restored=true")
        }
    }

    @Test fun repairPreservesReplacementMadeDuringAuthenticatedDownload() {
        ReviewAsset(SyncV2P5Fixture(), true).use { a ->
            a.file.writeBytes(a.changed)
            val unrelated = a.raw.clone().also { it[0] = 99 }
            a.backend.beforeRead = {
                val other = File(a.f.root, "repaired-concurrent-replacement").apply { writeBytes(unrelated) }
                java.nio.file.Files.move(other.toPath(), a.file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                a.backend.beforeRead = null
            }
            assertEquals(SyncV2BodyChannel.Result(1, listOf("body_revision_changed")), a.channel().sync())
            assertArrayEquals(unrelated, a.file.readBytes())
        }
    }

}
