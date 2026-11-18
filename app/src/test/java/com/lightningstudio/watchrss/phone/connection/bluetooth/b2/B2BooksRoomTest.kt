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
