package com.lightningstudio.watchrss.phone.connection.bluetooth.b2

import androidx.room.Room
import com.lightningstudio.watchrss.phone.BuildConfig
import com.lightningstudio.watchrss.phone.connection.bluetooth.B2BookBusiness
import com.lightningstudio.watchrss.phone.connection.bluetooth.B2KindRoute
import com.lightningstudio.watchrss.phone.connection.bluetooth.B2RoomStore
import com.lightningstudio.watchrss.phone.connection.bluetooth.binaryDescriptor
import com.lightningstudio.watchrss.phone.connection.bluetooth.binaryFileHashes
import com.lightningstudio.watchrss.phone.data.book.BookChapterEntity
import com.lightningstudio.watchrss.phone.data.book.BookEntity
import com.lightningstudio.watchrss.phone.data.book.BookPackageFiles
import com.lightningstudio.watchrss.phone.data.book.BookSync
import com.lightningstudio.watchrss.phone.data.book.ImportedBookStore
import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
import com.lightningstudio.watchrss.phone.data.db.PhoneCompanionDatabase
import com.lightningstudio.watchrss.phone.data.db.PhoneRssSourceEntity
import com.lightningstudio.watchrss.phone.data.local.FileArticleContentStore
import com.lightningstudio.watchrss.phone.data.model.ImportedContentIds
import com.lightningstudio.watchrss.phone.data.repo.PhoneCompanionRepository
import com.lightningstudio.watchrss.phone.syncv2.SyncV2BodyRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = android.app.Application::class)
class B2BooksRoomTest {
    @Test fun v1CaptureAcceptsLegacyLifecycleFieldsWithB2Disabled() = runBlocking<Unit> {
        assumeFalse(BuildConfig.B2_ENABLED)
        val root = java.nio.file.Files.createTempDirectory("v1-book-capture").toFile()
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), PhoneCompanionDatabase::class.java).build()
        try {
            val content = FileArticleContentStore(File(root, "content"))
            val books = ImportedBookStore(db, File(root, "books"), content, "phone")
            for (complete in listOf(false, true)) {
                val fingerprint = (if (complete) "d" else "e").repeat(64)
                val id = BookSync.id(fingerprint)
                val articleId = BookSync.articleId(id, 1)
                val source = "https://watchrss.local/import-txt-novel/$id"
                val lifecycle = obj("generation" to 1)
                if (complete) lifecycle.put("operationId", "b".repeat(32)).put("startedAt", 1)
                val state = obj("bookId" to id, "fingerprint" to fingerprint, "title" to "Review fixture",
                    "sourceUrl" to source, "sourceDeviceId" to "fixture-watch", "chapterCount" to 2,
                    "deletedChapters" to JSONArray(), "deleted" to false, "deletionClock" to 0, "deletedAt" to 0,
                    "updatedAt" to 1, "lifecycle" to lifecycle)
                db.bookDao().put(BookEntity(id, BookSync.canonical(state)))
                db.bookDao().putChapters(listOf(BookChapterEntity(id, 1, articleId)))
                val text = "甲".repeat(50_001)
                db.phoneArticleDao().upsert(PhoneArticleEntity(
                    articleId = articleId, sourceDeviceId = "fixture-watch", url = ImportedContentIds.txtArticleUrl(id),
                    title = "chapter", siteName = "", excerpt = "", contentHtml = null, contentText = text,
                    imageUrl = null, contentHash = "", importedAt = 1, updatedAt = 1,
                    independentSaved = true, independentChangedAt = 1, independentSortOrder = 1,
                    rssSourceUrl = source, rssSourceTitle = null, favoriteSaved = false, favoriteChangedAt = 0,
                    favoriteSortOrder = 0, watchLaterSaved = false, watchLaterChangedAt = 0, watchLaterSortOrder = 0,
                    deleted = false, deletedAt = 0
                ))
                db.phoneRssSourceDao().upsert(PhoneRssSourceEntity(
                    source, "fixture-watch", "Review fixture", "", null, null, 1, 2, 0, false, true, 2
                ))

                books.ensureRegistered()

                val captured = books.binaryState(id)!!
                assertTrue(captured.getBoolean("deleted"))
                assertEquals(2, captured.getInt("deletionClock"))
                assertEquals(lifecycle.toString(), captured.getJSONObject("lifecycle").toString())
                val chapter = db.phoneArticleDao().getById(articleId)!!
                assertTrue(chapter.deleted)
                assertFalse(chapter.independentSaved)
                // The legacy projection externalizes inline text when it updates the row.
                assertTrue(content.isMarker(chapter.contentText))
                assertEquals(text, content.loadText(chapter.contentText))
            }
            assertFalse(db.openHelper.readableDatabase.query(
                "SELECT 1 FROM sqlite_master WHERE name='library_binary_records'"
            ).use { it.moveToFirst() })
        } finally {
            db.close()
            root.deleteRecursively()
        }
    }

    @Test fun validLegacyOriginalRemainsAvailableWithTruncatedOrIdenticalSidecar() = runBlocking<Unit> {
        assumeFalse(BuildConfig.B2_ENABLED)
        val root = java.nio.file.Files.createTempDirectory("v1-book-package").toFile()
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), PhoneCompanionDatabase::class.java).build()
        try {
            val books = ImportedBookStore(db, root, null, "phone")
            val fingerprint = "d".repeat(64)
            val id = BookSync.id(fingerprint)
            val state = obj("bookId" to id, "fingerprint" to fingerprint, "title" to "fixture",
                "sourceDeviceId" to "watch", "chapterCount" to 1, "deletedChapters" to JSONArray(),
                "deleted" to false, "deletionClock" to 0, "deletedAt" to 0, "updatedAt" to 1)
            val legacy = File(root, "$id.jsonl").apply { writeText("legacy package fixture") }
            val hash = binaryFileHashes(legacy).sha256
            db.bookDao().put(BookEntity(id, BookSync.canonical(state), hash, legacy.length()))
            val sidecar = books.binaryPackage(id, hash).apply { writeText("truncated") }
            assertEquals(legacy, books.packageFile(id))
            sidecar.writeBytes(legacy.readBytes())
            assertEquals(legacy, books.packageFile(id))
            assertTrue(books.manifests().single().getBoolean("contentAvailable"))
            assertFalse(id in books.missingBookIds())
            assertEquals(legacy, books.availableBooks().single().second)
        } finally {
            db.close()
            root.deleteRecursively()
        }
    }

    @Test fun firstLegacyRegistrationCannotCommitDigestOfFileRemovedBeforeRoomWriter() = runBlocking<Unit> {
        assumeFalse(BuildConfig.B2_ENABLED)
        val fixture = JSONObject(requireNotNull(javaClass.getResource("/book/cli-single-txt-book.json")).readText())
        val id = fixture.getString("bookId")
        val chapter = fixture.getJSONObject("chapter")
        val url = ImportedContentIds.txtArticleUrl("review-unregistered")
        val root = java.nio.file.Files.createTempDirectory("v1-registration-cleanup").toFile()
        val directory = File(root, "books")
        val packageFile = File(directory, "$id.jsonl")
        val removed = java.util.concurrent.atomic.AtomicBoolean()
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), PhoneCompanionDatabase::class.java)
            .setTransactionExecutor { command ->
                executor.execute {
                    if (!removed.get() && packageFile.isFile) removed.set(packageFile.delete())
                    command.run()
                }
            }.build()
        try {
            val text = chapter.getString("contentText")
            db.phoneArticleDao().upsert(PhoneArticleEntity(
                articleId = "unregistered", sourceDeviceId = "phone", url = url,
                title = chapter.getString("title"), siteName = "", excerpt = "",
                contentHtml = chapter.optString("contentHtml").takeIf { !chapter.isNull("contentHtml") },
                contentText = text, imageUrl = null, contentHash = "", importedAt = 1, updatedAt = 1,
                independentSaved = true, independentChangedAt = 1, independentSortOrder = 1,
                rssSourceUrl = ImportedContentIds.ROOT_SOURCE_URL, rssSourceTitle = null,
                favoriteSaved = false, favoriteChangedAt = 0, favoriteSortOrder = 0,
                watchLaterSaved = false, watchLaterChangedAt = 0, watchLaterSortOrder = 0,
                deleted = false, deletedAt = 0
            ))
            val store = ImportedBookStore(db, directory, FileArticleContentStore(File(root, "text")), "phone")
            store.ensureRegistered()
            assertTrue("fixture removed the published original before the Room writer entered", removed.get())
            assertNull(db.bookDao().get(id))
            assertTrue(db.bookDao().chapters(id).isEmpty())
            assertFalse(packageFile.exists())
        } finally {
            db.close()
            executor.shutdownNow()
            root.deleteRecursively()
        }
    }

    @Test fun bookCommitKeepsRegisteredOriginalRestoredAfterPreparation() = runBlocking<Unit> {
        val fixture = JSONObject(requireNotNull(javaClass.getResource("/book/cli-single-txt-book.json")).readText())
        val raw = fixture.getString("packageUtf8")
        val id = fixture.getString("bookId")
        val fingerprint = fixture.getString("fingerprint")
        for (guarded in listOf(false, true)) {
            val root = java.nio.file.Files.createTempDirectory("b2-restored-original").toFile()
            val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), PhoneCompanionDatabase::class.java).build()
            try {
                val directory = File(root, "books")
                val books = ImportedBookStore(db, directory, FileArticleContentStore(File(root, "text")), "phone")
                val legacyInput = File(root, "original").apply { writeText(raw) }
                assertTrue(books.receivePackage(id, legacyInput) > 0)
                val before = requireNotNull(db.bookDao().get(id))
                val local = requireNotNull(books.packageFile(id))
                assertTrue(local.delete())
                val first = JSONObject(raw.lineSequence().first()).put("title", "Alternate title")
                val incoming = File(root, "incoming").apply { writeText(first.toString() + "\n" + raw.substringAfter('\n')) }
                val revision = com.lightningstudio.watchrss.phone.data.book.BookPackageFiles.Revision(
                    binaryFileHashes(incoming).sha256, incoming.length())
                assertNotEquals(before.packageSha256, revision.sha256)
                val original = requireNotNull(com.lightningstudio.watchrss.phone.data.book.BookPackageFiles.verify(incoming, revision) {})
                val state = JSONObject(before.stateJson)
                val scratch = books.binaryImportDirectory()
                val prepared = if (guarded) books.prepareBinaryPackage(id, original, {}, scratch, state)
                    else books.prepareBinaryPackage(id, original, {})
                assertNull(prepared.currentOriginal)

                val ledger = B2RoomStore(db)
                val outcome = ledger.exclusive {
                    ledger.transaction {
                        // Restore the bytes of the already registered revision in an earlier writer.
                        local.writeText(raw)
                        assertEquals(before, db.bookDao().get(id))
                    }
                    ledger.transaction { books.installBinaryPackage(id, fingerprint, state, prepared, scratch) {} }
                }
                assertEquals("retainedLocal", outcome)
                assertEquals(before, db.bookDao().get(id))
                assertEquals(raw, requireNotNull(books.packageFile(id)).readText())
                assertFalse(books.binaryPackage(id, revision.sha256).exists())
            } finally {
                db.close()
                root.deleteRecursively()
            }
        }
    }

    @Test
    @Config(sdk = [35])
    @SQLiteMode(SQLiteMode.Mode.NATIVE)
    fun independentCloudReaderWaitsForSameRevisionBookCommit() = runBlocking<Unit> {
        val fixture = JSONObject(requireNotNull(javaClass.getResource("/book/cli-single-txt-book.json")).readText())
        val raw = fixture.getString("packageUtf8")
        val id = fixture.getString("bookId")
        val root = java.nio.file.Files.createTempDirectory("b2-book-publication").toFile()
        fun database() = Room.databaseBuilder(RuntimeEnvironment.getApplication(), PhoneCompanionDatabase::class.java,
            File(root, "publication.db").absolutePath)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .allowMainThreadQueries().build()
        val writer = database()
        val reader = database()
        try {
            val directory = File(root, "imported_books")
            val books = ImportedBookStore(writer, directory, FileArticleContentStore(File(root, "text")), "phone")
            val legacy = File(root, "legacy").apply { writeText(raw) }
            assertTrue(books.receivePackage(id, legacy) > 0)
            val row = requireNotNull(writer.bookDao().get(id))
            val revision = BookPackageFiles.Revision(row.packageSha256, row.packageBytes)
            val state = JSONObject(row.stateJson)
            val ledger = B2RoomStore(writer)
            val receipts = B2RoomStore(reader)
            val originals = SyncV2BodyRepository(reader.openHelper.readableDatabase, root)
            assertTrue(requireNotNull(books.packageFile(id)).delete())
            assertNull(originals.original("book", id, state))
            val scratch = books.binaryImportDirectory()

            suspend fun attempt(key: String, rollback: Boolean) = coroutineScope {
                val staged = CompletableDeferred<Unit>()
                val release = java.util.concurrent.CountDownLatch(1)
                val incoming = File(root, "incoming-$key").apply { writeText(raw) }
                val assembly = requireNotNull(BookPackageFiles.verify(incoming, revision) {})
                val prepared = books.prepareBinaryPackage(id, assembly, {})
                assertNull(prepared.currentOriginal)
                val result = async(Dispatchers.IO) {
                    runCatching {
                        ledger.exclusive { ledger.transaction {
                            assertEquals("merged", books.installBinaryPackage(id, fixture.getString("fingerprint"),
                                state, prepared, scratch) {})
                            ledger.put("book-test", "receipt", key, obj("state" to "APPLIED"))
                            staged.complete(Unit)
                            check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                            if (rollback) throw B2Exception("LOCAL_IO")
                        } }
                    }
                }
                try {
                    withTimeout(5_000) { staged.await() }
                    assertNull(receipts.get("book-test", "receipt", key))
                    assertNull("uncommitted same-revision book was visible to the cloud reader",
                        originals.original("book", id, state))
                    assertNull("uncommitted same-revision book was visible to recovery",
                        originals.committedBook(id, state))
                } finally {
                    release.countDown()
                }
                if (rollback) {
                    assertEquals("LOCAL_IO", (result.await().exceptionOrNull() as B2Exception).code)
                    assertNull(receipts.get("book-test", "receipt", key))
                    assertNull(originals.original("book", id, state))
                    assertNull(originals.committedBook(id, state))
                } else {
                    result.await().getOrThrow()
                    assertEquals("APPLIED", receipts.get("book-test", "receipt", key)?.s("state"))
                    assertArrayEquals(raw.toByteArray(), requireNotNull(originals.original("book", id, state)).raw)
                    assertEquals(raw, requireNotNull(originals.committedBook(id, state)).file.readText())
                }
            }
            attempt("legacy-rollback", rollback = true)
            attempt("first-commit", rollback = false)
            assertTrue(requireNotNull(books.packageFile(id)).delete())
            attempt("same-b2-rollback", rollback = true)
            attempt("same-b2-commit", rollback = false)
        } finally {
            reader.close()
            writer.close()
            root.deleteRecursively()
        }
    }

    @Test fun freezeRejectsChangeToAnEarlierBookDuringTheLaterVisit() = runBlocking<Unit> {
        val root = java.nio.file.Files.createTempDirectory("b2-book-scan-generation").toFile()
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), PhoneCompanionDatabase::class.java).build()
        try {
            val books = ImportedBookStore(db, root, FileArticleContentStore(File(root, "content")), "phone")
            val fingerprints = listOf("1".repeat(64), "2".repeat(64))
            val ids = fingerprints.map(BookSync::id).sorted()
            for (fingerprint in fingerprints) {
                val id = BookSync.id(fingerprint)
                val state = obj("bookId" to id, "fingerprint" to fingerprint, "title" to id, "chapterCount" to 1,
                    "deletedChapters" to JSONArray(), "deletionClock" to 0, "deleted" to false, "deletedAt" to 0,
                    "updatedAt" to 1, "sourceDeviceId" to "phone")
                db.bookDao().put(BookEntity(id, BookSync.canonical(state)))
            }
            val seen = mutableListOf<String>()
            val failure = runCatching {
                books.freezeBinaryBooks { row, _ ->
                    seen += row.bookId
                    if (row.bookId == ids.last()) {
                        val earlier = requireNotNull(db.bookDao().get(ids.first()))
                        db.bookDao().put(earlier.copy(stateJson = JSONObject(earlier.stateJson).put("title", "changed").toString()))
                    }
                }
            }.exceptionOrNull()
            assertEquals(ids, seen)
            assertEquals("SOURCE_CHANGED", (failure as? B2Exception)?.code)
        } finally {
            db.close()
            root.deleteRecursively()
        }
    }

    @Test fun resumedPackageCommitsFromVerifiedLegacyBytesAndKeepsTheV1Original() = runBlocking<Unit> {
        val root = java.nio.file.Files.createTempDirectory("b2-book-legacy-resume").toFile()
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), PhoneCompanionDatabase::class.java).build()
        try {
            val content = FileArticleContentStore(File(root, "content"))
            val books = ImportedBookStore(db, root, content, "phone")
            val ledger = B2RoomStore(db)
            val scope = "a".repeat(64)
            val job = "b".repeat(32)
            val business = B2BookBusiness(ledger, scope, books) {}
            val title = "章节"
            val text = "旧版原件正文"
            val fingerprint = BookSync.fingerprint(listOf(BookSync.chapterHash(1, title, text)))
            val id = BookSync.id(fingerprint)
            val source = "https://watchrss.local/import-txt-novel/legacy"
            val state = obj("bookId" to id, "fingerprint" to fingerprint, "title" to title, "chapterCount" to 1,
                "deletedChapters" to JSONArray(), "deletionClock" to 0, "deleted" to false, "deletedAt" to 0,
                "updatedAt" to 1, "sourceDeviceId" to "watch", "sourceUrl" to source)
            val legacy = File(root, "$id.jsonl").apply {
                writeText(obj("format" to "watchrss-book", "version" to 1, "title" to title, "sourceUrl" to source,
                    "kind" to "txt", "chapterCount" to 1).toString() + "\n")
                appendText(obj("chapterIndex" to 1, "title" to title, "contentText" to text,
                    "contentHtml" to null, "url" to "$source/chapter/1").toString() + "\n")
            }
            val original = legacy.readBytes()
            val hashes = binaryFileHashes(legacy)
            // The transfer plan binds the original independently of an unpublished Room package revision.
            db.bookDao().put(BookEntity(id, BookSync.canonical(state)))
            assertNull(books.packageFile(id))
            ledger.exclusive {
                ledger.put(scope, "bookStateWitness", "$job:$id", obj("jobId" to job, "fingerprint" to fingerprint, "chapterCount" to 1))
            }
            val empty = File(root, "commit").apply { createNewFile() }
            val meta = obj("bookId" to id, "fingerprint" to fingerprint, "packageSha256" to hashes.sha256,
                "packageBytes" to hashes.size, "segmentObjectIds" to JSONArray(listOf("c".repeat(64))))
            val descriptor = binaryDescriptor(B2Wire.BOOK_COMMIT, id, meta, binaryFileHashes(empty),
                listOf("c".repeat(64), "d".repeat(64)))
            val context = B2ApplyContext(job, { error("segments already reclaimed") }, {},
                assembly = File(root, "missing-assembly"), assemblyReady = { true })
            val invalidPlan = copy(meta).put("packageSha256", "0".repeat(64))
            assertEquals("JOURNAL_CORRUPT", (runCatching { business.prepareCommit(context, invalidPlan) {} }
                .exceptionOrNull() as B2Exception).code)
            business.prepareCommit(context, meta) {}
            val staged = B2FileRef(empty, 0, 0)
            suspend fun apply() = ledger.exclusive { ledger.transaction {
                business.apply(context, descriptor, meta, staged) {}
            } }
            val replacement = File(root, "replacement").apply {
                writeBytes(original.clone().also { it[it.lastIndex] = 32 })
                java.nio.file.Files.setLastModifiedTime(toPath(), java.nio.file.Files.getLastModifiedTime(legacy.toPath()))
            }
            java.nio.file.Files.move(replacement.toPath(), legacy.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            assertEquals("SOURCE_CHANGED", (runCatching { apply() }.exceptionOrNull() as B2Exception).code)
            legacy.writeBytes(original)
            business.prepareCommit(context, meta) {}
            assertEquals("merged", apply())
            assertEquals(legacy, books.packageFile(id))
            assertArrayEquals(original, legacy.readBytes())
            assertFalse(books.binaryPackage(id, hashes.sha256).exists())
            val chapter = db.phoneArticleDao().getById(BookSync.articleId(id, 1))!!
            assertEquals(text, content.loadText(chapter.contentText))
        } finally {
            db.close()
            root.deleteRecursively()
        }
    }

    @Test fun guardedBookResumesBothDirectionsWithOneRoomInstallation() = runBlocking<Unit> {
        val total = 128
        val prefix = 27
        val bytes = total * B2Wire.CHUNK
        val source = "https://watchrss.local/import-txt-novel/synthetic"
        val title = "Synthetic book"
        val instance = "d".repeat(32)
        val header = obj("format" to "watchrss-book", "version" to 1, "title" to title,
            "sourceUrl" to source, "kind" to "txt", "chapterCount" to 32, "instanceId" to instance)
        fun chapter(index: Int, text: String) = obj("chapterIndex" to index, "title" to "Chapter $index",
            "contentText" to text, "contentHtml" to null, "url" to "$source/chapter/$index")
        val headerLine = header.toString() + "\n"
        val overhead = headerLine.length + (1..32).sumOf { chapter(it, "").toString().length + 1 }
        val text = "a".repeat((bytes - overhead) / 32)
        val remainder = (bytes - overhead) % 32
        val texts = (1..32).map { if (it == 32) text + "a".repeat(remainder) else text }
        val fingerprint = BookSync.fingerprint(texts.mapIndexed { i, body -> BookSync.chapterHash(i + 1, "Chapter ${i + 1}", body) })
        val id = BookSync.binaryId(fingerprint, instance)
        val state = obj("bookId" to id, "fingerprint" to fingerprint, "title" to title, "chapterCount" to 32,
            "deletedChapters" to JSONArray(), "deletionClock" to 0, "deleted" to false, "deletedAt" to 0,
            "updatedAt" to 1, "sourceDeviceId" to "synthetic", "instanceId" to instance, "sourceUrl" to source,
            "kind" to "txt", "lifecycle" to obj("generation" to 1, "operationId" to instance, "startedAt" to 1))

        for (direction in listOf("phoneToWatch", "watchToPhone")) {
            val root = java.nio.file.Files.createTempDirectory("guarded-book-resume").toFile()
            val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), PhoneCompanionDatabase::class.java)
                .build()
            try {
                val account = "a".repeat(64)
                val job = "b".repeat(32)
                val stream = if (direction == "phoneToWatch") 1 else 2
                val store = B2RoomStore(db)
                val files = B2Files(File(root, "transfer"), File(root, "books"))
                val articleFiles = FileArticleContentStore(File(root, "content"))
                val books = ImportedBookStore(db, File(root, "books"), articleFiles, "phone")
                val adapter = B2BookBusiness(store, account, books) {}
                val stateFile = File(root, "state").apply { writeText(state.toString()) }
                val original = File(root, "package").apply {
                    bufferedWriter().use { out ->
                        out.write(headerLine)
                        for ((i, body) in texts.withIndex()) {
                            out.write(chapter(i + 1, body).toString())
                            out.newLine()
                        }
                    }
                }
                assertEquals(bytes.toLong(), original.length())
                val hashes = binaryFileHashes(original)
                val stateObject = binaryDescriptor(B2Wire.BOOK_STATE, id, obj("bookId" to id), binaryFileHashes(stateFile))
                val metadata = obj("bookId" to id, "fingerprint" to fingerprint, "packageSha256" to hashes.sha256,
                    "packageBytes" to hashes.size)
                val segment = binaryDescriptor(B2Wire.BOOK_SEGMENT, "$id:${hashes.sha256}:0",
                    copy(metadata).put("segmentIndex", 0).put("segmentCount", 1).put("segmentOffset", 0),
                    hashes, listOf(stateObject.s("objectId")))
                val empty = File(root, "empty").apply { createNewFile() }
                val commit = binaryDescriptor(B2Wire.BOOK_COMMIT, id,
                    copy(metadata).put("segmentObjectIds", JSONArray(listOf(segment.s("objectId")))),
                    binaryFileHashes(empty), listOf(stateObject.s("objectId"), segment.s("objectId")).sorted())
                val ordered = listOf(stateObject, segment, commit)
                val orderedForManifest = ordered.sortedBy { it.s("objectId") }
                val caps = B2Negotiation.capability(B2Wire.bookKinds, B2Wire.bookKinds, B2Wire.s4Limits())
                val selection = B2Negotiation.select(caps, caps, direction)!!
                val snapshot = obj("snapshotId" to "book", "manifestDigest" to hashes.sha256,
                    "fromExclusive" to 0, "toInclusive" to 0)
                val start = obj("intent" to "create", "accountScope" to account, "phoneDeviceId" to "phone",
                    "watchDeviceId" to "watch", "direction" to direction, "phoneSnapshot" to snapshot,
                    "watchSnapshot" to snapshot, "selection" to selection, "selectionDigest" to B2Digests.selection(selection),
                    "objectCount" to ordered.size, "objectsRoot" to B2Digests.objects(orderedForManifest.map { it.s("objectId") }),
                    "pageObjectCounts" to JSONArray(listOf(ordered.size)))
                val plan = B2Digests.plan(start)
                var installs = 0
                val business = object : B2BusinessPort {
                    override val kinds = B2Wire.bookKinds
                    override suspend fun apply(descriptor: JSONObject, metadata: JSONObject, staged: File) =
                        error("Requires contextual application")
                    override suspend fun prepareFile(context: B2ApplyContext, descriptor: JSONObject, metadata: JSONObject) {
                        if (descriptor.s("kind") == B2Wire.BOOK_COMMIT) adapter.prepareCommit(context, metadata) {}
                    }
                    override suspend fun applyFile(context: B2ApplyContext, descriptor: JSONObject, metadata: JSONObject,
                        staged: B2FileRef): String {
                        val outcome = adapter.apply(context, descriptor, metadata, staged) {}
                        if (descriptor.s("kind") == B2Wire.BOOK_COMMIT && outcome == "merged") installs++
                        return outcome
                    }
                    override suspend fun receiptWitness(context: B2ApplyContext?, descriptor: JSONObject,
                        metadata: JSONObject, prior: JSONObject?) = adapter.receiptWitness(context, descriptor, metadata, prior)
                }
                fun receiver(intent: String): B2Receiver {
                    val binding = obj("direction" to direction, "jobId" to job, "intent" to intent, "selection" to selection)
                    val auth = B2Authorization(account, "phone", "watch", snapshot, snapshot, listOf(binding)) { true }
                    auth.commitWindow = B2CommitWindow {
                        B2CommitWindow.Version(B2CommitWindow.Head("scope", "head", "1", "0"), "1", "business")
                    }
                    return B2Receiver(store, files, auth, business, receiveDirection = direction)
                }
                val frames = mutableListOf<Int>()
                suspend fun send(target: B2Receiver, epoch: Long, seq: Long, descriptor: JSONObject,
                    index: Int, data: ByteArray) {
                    val request = B2Wire.request("putChunk", job, plan, epoch,
                        obj("objectId" to descriptor.s("objectId"), "chunkIndex" to index))
                    val frame = B2Frame.Data(B2DataHeader(stream, orderedForManifest.indexOf(descriptor), epoch, seq,
                        index, data.size, request.s("requestId"), request.s("requestDigest")), data)
                    val verified = target.inspect(frame.header).first
                    if (descriptor == segment) frames += index
                    assertEquals(1, target.data(frame, verified).entries.single().disposition)
                }
                suspend fun apply(target: B2Receiver, epoch: Long, descriptor: JSONObject): JSONObject =
                    target.handle(B2Wire.request("apply", job, plan, epoch,
                        obj("objectId" to descriptor.s("objectId")))).o("result").o("receipt")
                val first = receiver("create")
                val epoch = first.handle(B2Wire.request("begin", job, plan, null, start)).n("epoch")
                val page = obj("pageIndex" to 0, "startIndex" to 0, "previousPageDigest" to "0".repeat(64),
                    "pageDigest" to "0".repeat(64), "entries" to JSONArray(orderedForManifest))
                page.put("pageDigest", B2Digests.page(plan, page))
                first.handle(B2Wire.request("manifestPage", job, plan, epoch, page))
                send(first, epoch, 1, stateObject, 0, stateFile.readBytes())
                val stateReceipt = apply(first, epoch, stateObject)
                java.io.RandomAccessFile(original, "r").use { file ->
                    for (i in 0 until prefix) {
                        send(first, epoch, i + 2L, segment, i, ByteArray(B2Wire.CHUNK).also(file::readFully))
                    }
                    first.fenceNow()
                    first.closeIndex()
                    val resumed = receiver("resume")
                    val continuation = copy(start).put("intent", "resume")
                    val next = resumed.handle(B2Wire.request("begin", job, plan, null, continuation)).n("epoch")
                    assertEquals(epoch + 1, next)
                    val missing = resumed.handle(B2Wire.request("missing", job, plan, next,
                        obj("afterObjectId" to null, "afterChunkIndex" to null, "limit" to 4))).o("result")
                    val part = missing.a("items").values().map { it as JSONObject }
                        .single { it.s("objectId") == segment.s("objectId") }
                    assertEquals("[[$prefix,$total]]", part.a("missingRanges").toString())
                    val range = part.a("missingRanges").getJSONArray(0)
                    file.seek(range.getLong(0) * B2Wire.CHUNK)
                    for (i in range.getInt(0) until range.getInt(1)) {
                        send(resumed, next, i - prefix + 1L, segment, i, ByteArray(B2Wire.CHUNK).also(file::readFully))
                    }
                    val partReceipt = apply(resumed, next, segment)
                    assertNull(books.packageFile(id))
                    val commitReceipt = apply(resumed, next, commit)
                    val receipts = listOf(stateReceipt, partReceipt, commitReceipt).associateBy { it.s("objectId") }
                    val roots = B2Digests.receipts(orderedForManifest.map { receipts.getValue(it.s("objectId")) })
                    val complete = resumed.handle(B2Wire.request("complete", job, plan, next,
                        obj("objectCount" to 3, "receiptsRoot" to roots, "ackToInclusive" to 0))).o("result")
                    assertEquals("COMPLETE", complete.s("state"))
                    assertEquals((0 until total).toList(), frames)
                    assertEquals(1, installs)
                    assertEquals(32, db.bookDao().binaryPresentChapters(id))
                    assertEquals(hashes.sha256, binaryFileHashes(requireNotNull(books.packageFile(id))).sha256)
                    resumed.closeIndex()
                }
            } finally {
                db.close()
                root.deleteRecursively()
            }
        }
    }

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
            val binary = requireNotNull(books.packageFile(id))
            val published = requireNotNull(BookPackageFiles.publication(db.openHelper.readableDatabase, id))
            assertEquals("b2-$id-${hashes.sha256}-${published.slot}.jsonl", binary.name)
            assertEquals(hashes.sha256, binaryFileHashes(binary).sha256)
            assertEquals(binary, books.packageFile(id))
            assertEquals("APPLIED", dbStore.get(scope, "testReceipt", job)!!.s("status"))

            val reader = PhoneCompanionRepository(db.phoneSavedItemDao(), db.phoneArticleDao(), db.phoneRssSourceDao(),
                "phone", articleContentStore = content, books = books)
            db.phoneArticleDao().upsert(article.copy(contentText = "", favoriteSaved = true, readingPositionBytes = 1))
            assertTrue(content.openTextFile(article.contentText)!!.delete())
            val originalBytes = binary.readBytes()
            binary.writeBytes(originalBytes.copyOf(originalBytes.size - 1))
            assertEquals("", reader.getArticleForReading(article.articleId)!!.contentText)
            binary.writeBytes(originalBytes)
            val restored = reader.getArticleForReading(article.articleId)!!
            assertEquals(BookSync.normalized(text), restored.contentText)
            assertTrue(restored.favoriteSaved)
            assertEquals(1L, restored.readingPositionBytes)
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
