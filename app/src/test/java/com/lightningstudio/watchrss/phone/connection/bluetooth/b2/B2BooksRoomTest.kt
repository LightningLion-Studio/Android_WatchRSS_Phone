package com.lightningstudio.watchrss.phone.connection.bluetooth.b2

import androidx.room.Room
import com.lightningstudio.watchrss.phone.connection.bluetooth.B2BookBusiness
import com.lightningstudio.watchrss.phone.connection.bluetooth.B2KindRoute
import com.lightningstudio.watchrss.phone.connection.bluetooth.B2RoomStore
import com.lightningstudio.watchrss.phone.connection.bluetooth.binaryDescriptor
import com.lightningstudio.watchrss.phone.connection.bluetooth.binaryFileHashes
import com.lightningstudio.watchrss.phone.data.book.BookSync
import com.lightningstudio.watchrss.phone.data.book.ImportedBookStore
import com.lightningstudio.watchrss.phone.data.db.PhoneCompanionDatabase
import com.lightningstudio.watchrss.phone.data.local.FileArticleContentStore
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = android.app.Application::class)
class B2BooksRoomTest {
    @Test fun bookPublicationAndReceiptRollbackTogether() = runBlocking<Unit> {
        val root = java.nio.file.Files.createTempDirectory("book-b2-room").toFile()
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), PhoneCompanionDatabase::class.java).build()
        try {
            val scope = "a".repeat(64)
            val job = "b".repeat(32)
            val content = FileArticleContentStore(File(root, "content"))
            val books = ImportedBookStore(db, File(root, "books"), content, "phone")
            val dbStore = B2RoomStore(db)
            val business = B2BookBusiness(dbStore, scope, books) {}
            val title = "书籍"
            val text = "甲\r\n乙🙂"
            val fingerprint = BookSync.fingerprint(listOf(BookSync.chapterHash(1, title, text)))
            val instance = "c".repeat(32)
            val id = BookSync.binaryId(fingerprint, instance)
            val source = "https://watchrss.local/import-txt-novel/sample"
            val state = obj("bookId" to id, "fingerprint" to fingerprint, "title" to title, "chapterCount" to 1,
                "deletedChapters" to JSONArray(), "deletionClock" to 0, "deleted" to false, "deletedAt" to 0,
                "updatedAt" to 1, "sourceDeviceId" to "watch", "instanceId" to instance, "sourceUrl" to source,
                "lifecycle" to obj("generation" to 1, "operationId" to instance, "startedAt" to 1))
            val stateFile = File(root, "state").apply {
                outputStream().use { out ->
                    val whitespace = ByteArray(65536) { ' '.code.toByte() }
                    repeat(33) { out.write(whitespace) }
                    out.write(state.toString().toByteArray())
                }
            }
            val stateObject = binaryDescriptor("bookState", id, obj("bookId" to id), binaryFileHashes(stateFile))
            val part = File(root, "part").apply {
                writeText(obj("format" to "watchrss-book", "version" to 1, "title" to title, "sourceUrl" to source,
                    "kind" to "txt", "chapterCount" to 1, "instanceId" to instance).toString() + "\n")
                appendText(obj("chapterIndex" to 1, "title" to title, "contentText" to text,
                    "contentHtml" to null, "url" to "$source/chapter/1").toString() + "\n")
            }
            val hashes = binaryFileHashes(part)
            val meta = obj("bookId" to id, "fingerprint" to fingerprint, "packageSha256" to hashes.sha256,
                "packageBytes" to hashes.size)
            val segment = binaryDescriptor("bookPackageSegment", "$id:${hashes.sha256}:0",
                copy(meta).put("segmentIndex", 0).put("segmentCount", 1).put("segmentOffset", 0), hashes,
                listOf(stateObject.s("objectId")))
            val empty = File(root, "commit").apply { createNewFile() }
            val commit = binaryDescriptor("bookPackageCommit", id,
                copy(meta).put("segmentObjectIds", JSONArray(listOf(segment.s("objectId")))), binaryFileHashes(empty),
                listOf(stateObject.s("objectId"), segment.s("objectId")).sorted())
            B2BookManifest.validatePlan(listOf(stateObject, segment, commit), B2Wire.BOOK_MAX_BYTES)
            val assembly = File(root, "assembly")
            var assembled = false
            val apply = B2ApplyContext(job, { oid ->
                assertEquals(segment.s("objectId"), oid)
                B2FileRef(part, 0, part.length())
            }, {}, assembly, { file, _ ->
                if (file != assembly) java.nio.file.Files.move(file.toPath(), assembly.toPath())
                assembled = true
            }, { assembled })
            suspend fun applyObject(d: JSONObject, file: File) =
                business.apply(apply, d, B2Metadata.validate(d), B2FileRef(file, 0, file.length())) {}
            dbStore.exclusive { dbStore.transaction {
                assertEquals("merged", applyObject(stateObject, stateFile))
                assertEquals("merged", applyObject(segment, part))
            } }
            assertNull(books.packageFile(id))
            assertEquals(0, db.bookDao().binaryPresentChapters(id))
            business.prepareCommit(apply, B2Metadata.validate(commit)) {}
            val interrupted = runCatching { dbStore.exclusive { dbStore.transaction {
                assertEquals("merged", applyObject(commit, empty))
                dbStore.put(scope, "testReceipt", job, obj("status" to "APPLIED"))
                throw B2Exception("LOCAL_IO")
            } } }.exceptionOrNull()
            assertEquals("LOCAL_IO", (interrupted as B2Exception).code)
            assertNull(books.packageFile(id))
            assertEquals(0, db.bookDao().binaryPresentChapters(id))
            assertNull(dbStore.get(scope, "testReceipt", job))
            business.prepareCommit(apply, B2Metadata.validate(commit)) {}
            dbStore.exclusive { dbStore.transaction {
                assertEquals("merged", applyObject(commit, empty))
                dbStore.put(scope, "testReceipt", job, obj("status" to "APPLIED"))
            } }
            val article = db.phoneArticleDao().getById(BookSync.articleId(id, 1))!!
            assertEquals(BookSync.normalized(text), content.loadText(article.contentText))
            assertEquals(hashes.sha256, binaryFileHashes(books.packageFile(id)!!).sha256)
            assertEquals("APPLIED", dbStore.get(scope, "testReceipt", job)!!.s("status"))
            val exported = mutableListOf<JSONObject>()
            business.freeze(File(root, "source").apply { mkdirs() }, {}) { descriptor, _ -> exported += descriptor }
            assertEquals(setOf("bookState", "bookPackageSegment", "bookPackageCommit"), exported.map { it.s("kind") }.toSet())
            B2BookManifest.validatePlan(exported, B2Wire.BOOK_MAX_BYTES)
        } finally {
            db.close()
            root.deleteRecursively()
        }
    }

    @Test fun longBookMetadataSurvivesRoomAndRefreeze() = runBlocking<Unit> {
        val root = java.nio.file.Files.createTempDirectory("b2-long-metadata").toFile()
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), PhoneCompanionDatabase::class.java).build()
        try {
            val scope = "a".repeat(64)
            val job = "b".repeat(32)
            val books = ImportedBookStore(db, File(root, "books"), FileArticleContentStore(File(root, "content")), "phone")
            val persistence = B2RoomStore(db)
            val business = B2BookBusiness(persistence, scope, books) {}
            val fingerprint = "d".repeat(64)
            val id = BookSync.id(fingerprint)
            val title = "𠀀字".repeat(730_000)
            val state = obj("bookId" to id, "fingerprint" to fingerprint, "title" to title, "chapterCount" to 1,
                "deletedChapters" to JSONArray(), "deletionClock" to 0, "deleted" to false, "deletedAt" to 0,
                "updatedAt" to 1, "sourceDeviceId" to "watch")
            val reading = obj("bookId" to id, "chapterIndex" to 1, "isRead" to true, "sourceDeviceId" to "watch",
                "readingProgress" to 0.5, "readingPositionBytes" to 1, "readingPositionChangedAt" to 2,
                "readingPositionContentHash" to title)
            val ctx = B2ApplyContext(job, { error("No package in this test") }, {})
            suspend fun apply(kind: String, key: String, metadata: JSONObject, raw: String, exactLimit: Boolean,
                dependsOn: List<String> = emptyList()): String {
                val file = File(root, kind).apply { writeText(raw) }
                if (exactLimit) {
                    val remaining = B2Wire.hardLimits.getValue("maxObjectBytes") - file.length()
                    file.appendBytes(ByteArray(remaining.toInt()) { ' '.code.toByte() })
                }
                val descriptor = binaryDescriptor(kind, key, metadata, binaryFileHashes(file), dependsOn)
                persistence.exclusive { persistence.transaction {
                    assertEquals("merged", business.apply(ctx, descriptor, metadata, B2FileRef(file, 0, file.length())) {})
                } }
                return descriptor.s("objectId")
            }
            val stateObject = apply(B2Wire.BOOK_STATE, id, obj("bookId" to id), state.toString(), exactLimit = true)
            apply(B2Wire.BOOK_READING, "$id:0", obj("bookId" to id, "pageIndex" to 0),
                JSONArray().put(reading).toString(), exactLimit = true, dependsOn = listOf(stateObject))
            assertEquals("", db.bookDao().bookHeader(id)!!.stateJson)
            assertEquals("", db.bookDao().readingHeader(id, 1)!!.stateJson)
            assertEquals(title, books.binaryState(id)!!.s("title"))
            assertEquals(title, JSONObject(db.bookDao().reading(id, 1)!!.stateJson).s("readingPositionContentHash"))
            val exported = mutableSetOf<String>()
            business.freeze(File(root, "source").apply { mkdirs() }, {}) { descriptor, _ ->
                exported += descriptor.s("kind")
            }
            assertTrue("watch metadata business limits must be checked on freeze", exported.isEmpty())
        } finally {
            db.close()
            root.deleteRecursively()
        }
    }

    @Test fun outgoingMetadataUsesUtf8LimitsAndSplitsReadingPages() = runBlocking<Unit> {
        val root = java.nio.file.Files.createTempDirectory("b2-metadata-preflight").toFile()
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), PhoneCompanionDatabase::class.java).build()
        try {
            val books = ImportedBookStore(db, File(root, "books"), FileArticleContentStore(File(root, "content")), "phone")
            val store = B2RoomStore(db)
            store.exclusive { Unit }
            val business = B2BookBusiness(store, "a".repeat(64), books) {}
            suspend fun seed(fingerprint: String, title: String, fill: Boolean = false): String {
                val id = BookSync.id(fingerprint)
                val state = obj("bookId" to id, "fingerprint" to fingerprint, "title" to title, "chapterCount" to 7,
                    "deletedChapters" to JSONArray(), "deletionClock" to 0, "deleted" to false, "deletedAt" to 0,
                    "updatedAt" to 1, "sourceDeviceId" to "phone")
                if (fill) repeat(5) { state.put("note$it", "x".repeat(220_000)) }
                db.bookDao().put(com.lightningstudio.watchrss.phone.data.book.BookEntity(id, state.toString()))
                return id
            }
            val exact = "界".repeat(87_381) + "x"
            val good = seed("a".repeat(64), exact)
            val stringTooLarge = seed("b".repeat(64), exact + "x")
            val bodyTooLarge = seed("c".repeat(64), "Valid fields", fill = true)
            for (chapter in 1..7) {
                val row = obj("bookId" to good, "chapterIndex" to chapter, "isRead" to false,
                    "sourceDeviceId" to "phone", "readingProgress" to 0.5, "readingPositionBytes" to 0,
                    "readingPositionChangedAt" to 1, "readingPositionContentHash" to "")
                row.put("padding", if (chapter == 3) exact + "x" else exact)
                db.bookDao().putReading(com.lightningstudio.watchrss.phone.data.book.BookReadingEntity(good, chapter, row.toString()))
            }
            val warnings = mutableSetOf<String>()
            val descriptors = mutableListOf<JSONObject>()
            val chapters = mutableListOf<List<Int>>()
            business.freeze(File(root, "source").apply { mkdirs() }, {}, warn = { warnings += it.bookIdPrefix }) { d, file ->
                descriptors += d
                assertTrue(file.length() <= 1_048_576)
                if (d.s("kind") == B2Wire.BOOK_READING)
                    chapters += JSONArray(file.readText()).values().map { (it as JSONObject).getInt("chapterIndex") }
            }
            assertEquals(setOf(good.take(8), stringTooLarge.take(8), bodyTooLarge.take(8)), warnings)
            assertEquals(listOf(good), descriptors.filter { it.s("kind") == B2Wire.BOOK_STATE }.map { it.s("businessId") })
            assertEquals(listOf(listOf(1, 2, 4), listOf(5, 6, 7)), chapters)
            B2BookManifest.validatePlan(descriptors, B2Wire.BOOK_MAX_BYTES)
        } finally {
            db.close()
            root.deleteRecursively()
        }
    }

    @Test fun expiryReleasesNativeAndRoomRequestCharges() = runBlocking<Unit> {
        val root = java.nio.file.Files.createTempDirectory("b2-room-expiry").toFile()
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), PhoneCompanionDatabase::class.java).build()
        try {
            val scope = "a".repeat(64)
            val old = "1".repeat(32)
            val fresh = "2".repeat(32)
            val digest = "a".repeat(64)
            val legacyId = "3".repeat(32)
            val files = B2Files(root)
            val native = File(root, "request-bindings").apply { mkdirs() }
            val legacyKey = B2Digests.d("wrss.library-binary.v2/request-binding-file", listOf(scope, old, "watch", legacyId))
            File(native, legacyKey).writeText(digest)
            val store = B2RoomStore(db)
            val cap = B2Negotiation.capability(B2Wire.bookKinds, B2Wire.bookKinds, B2Wire.s4Limits())
            val selection = B2Negotiation.select(cap, cap, "watchToPhone")!!
            val snap = obj("snapshotId" to "empty-books", "manifestDigest" to digest, "fromExclusive" to 0, "toInclusive" to 0)
            val begin = obj("intent" to "create", "accountScope" to scope, "phoneDeviceId" to "phone", "watchDeviceId" to "watch",
                "direction" to "watchToPhone", "phoneSnapshot" to snap, "watchSnapshot" to snap, "selection" to selection,
                "selectionDigest" to B2Digests.selection(selection), "objectCount" to 0,
                "objectsRoot" to B2Digests.objects(emptyList()), "pageObjectCounts" to JSONArray())
            val plan = B2Digests.plan(begin)
            val business = object : B2BusinessPort {
                override val kinds = B2Wire.bookKinds
                override suspend fun apply(descriptor: JSONObject, metadata: JSONObject, staged: File) = error("empty plan")
            }
            var elapsed = 0L
            fun receiver(id: String) = B2Receiver(store, B2Files(root),
                B2Authorization(scope, "phone", "watch", snap, snap,
                    listOf(obj("jobId" to id, "direction" to "watchToPhone", "intent" to "create", "selection" to selection))) { true },
                business, monotonic = { elapsed }, retentionGeneration = "test-boot")

            val first = receiver(old)
            val epoch = first.handle(B2Wire.request("begin", old, plan, null, begin)).n("epoch")
            val completion = obj("objectCount" to 0, "receiptsRoot" to B2Digests.receipts(emptyList()), "ackToInclusive" to 0)
            assertEquals("COMPLETE", first.handle(B2Wire.request("complete", old, plan, epoch, completion)).o("result").s("state"))
            first.fenceNow()
            assertEquals("REQUEST_CONFLICT", assertThrows(B2Exception::class.java) {
                files.checkRequest(scope, old, "watch", legacyId, "b".repeat(64))
            }.code)
            val before = files.usedBytes()
            elapsed = B2Wire.RETENTION + 1
            val startup = B2Retention(store, files, scope, { elapsed }, "test-boot") {}
            store.exclusive {
                startup.collect(startup.clock())
                assertNull(store.get(scope, "job", old))
            }
            receiver(fresh).handle(B2Wire.request("begin", fresh, plan, null, begin))
            store.exclusive {
                assertNull(store.get(scope, "job", old))
                assertNull(store.get(scope, "nativeOwner", old))
                assertTrue(store.listForJob(scope, "request", old).isEmpty())
                assertNotNull(store.get(scope, "job", fresh))
                val requestRows = store.list(scope, "request")
                assertEquals(listOf(fresh), requestRows.map { it.second.s("jobId") })
            }
            assertTrue(files.usedBytes() < before)
            files.checkRequest(scope, old, "watch", legacyId, "b".repeat(64))
        } finally {
            db.close()
            root.deleteRecursively()
        }
    }

    @Test fun missingBookKindKeepsWholeBooksDomainOnLegacy() {
        val partial = B2Wire.bookKinds - B2Wire.BOOK_READING
        fun probe(kinds: Set<String>) = obj("action" to "syncLibrary", "success" to true, "phase" to "probe", "version" to 14,
            "deviceId" to "watch", "libraryPeer" to obj("implementation" to "podjs", "appVersion" to "2.1.0-1"),
            "libraryBinary" to B2Negotiation.capability(kinds, kinds, B2Wire.s4Limits()))
        assertFalse(B2KindRoute.select(probe(partial), false, false, true).hasBinaryBooks)
        assertFalse(B2KindRoute.select(probe(B2Wire.bookKinds), false, false, false).hasBinaryBooks)
        assertTrue(B2KindRoute.select(probe(B2Wire.bookKinds), false, false, true).hasBinaryBooks)
    }
}
