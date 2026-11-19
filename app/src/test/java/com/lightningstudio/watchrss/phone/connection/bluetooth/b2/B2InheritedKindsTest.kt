package com.lightningstudio.watchrss.phone.connection.bluetooth.b2

import android.content.Context
import androidx.room.Room
import com.lightningstudio.watchrss.phone.connection.bluetooth.*
import com.lightningstudio.watchrss.phone.data.db.PhoneCompanionDatabase
import com.lightningstudio.watchrss.phone.data.db.PhoneRssSourceEntity
import com.lightningstudio.watchrss.phone.data.local.FileArticleContentStore
import com.lightningstudio.watchrss.phone.data.repo.PhoneCompanionRepository
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
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = android.app.Application::class)
class B2InheritedKindsTest {
    @Test fun bodyMetadataAndSourceShareTheReceiverTransaction() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication() as Context,
            PhoneCompanionDatabase::class.java).allowMainThreadQueries().build()
        val root = Files.createTempDirectory("b2-kind-test").toFile()
        try {
            val articles = db.phoneArticleDao()
            val sources = db.phoneRssSourceDao()
            val content = FileArticleContentStore(File(root, "content"))
            val repo = PhoneCompanionRepository(db.phoneSavedItemDao(), articles, sources, "phone",
                syncChangeLogDao = db.syncChangeLogDao(), articleContentStore = content)
            val ledger = B2RoomStore(db)
            val files = B2Files(File(root, "spool"))
            val account = B2Digests.account("https://example.invalid", "test-user")
            val kinds = setOf(B2_ARTICLE_BODY, B2_ARTICLE_METADATA, B2_RSS_SOURCE)
            val business = B2InheritedKinds(db, repo, BluetoothArticleIdentityProjection(emptyList(), emptyMap(), false),
                B2NoteAssetBusiness(ledger, account, File(root, "notes")) {}, kinds, false, {})
            val cap = B2Negotiation.capability(kinds, kinds)
            val selection = requireNotNull(B2Negotiation.select(cap, cap, "watchToPhone"))
            val snapshot = obj("snapshotId" to "snap", "manifestDigest" to "d".repeat(64),
                "fromExclusive" to 0, "toInclusive" to 0)
            val text = "line \\ slash / 😀 汉字\n".repeat(400)
            val raw = File(root, "text").apply { writeText(text) }
            val encoded = File(root, "gzip").apply { ArticleSyncBody.writeFileBody(null, raw, outputStream()) }
            val empty = File(root, "empty").apply { createNewFile() }
            val bodyHashes = binaryFileHashes(encoded)
            val emptyHashes = binaryFileHashes(empty)
            val id = "fixture-body"
            val bodyMeta = article(id, 30, bodyHashes)
            val payload = LibrarySyncPayload.parseChunkedArticles(JSONArray().put(bodyMeta)).single()
            assertEquals(bodyHashes.sha256, ArticleSyncBody.metadataFor(payload.article.copy(contentText = text)).bodyHash)
            var jobNumber = 0
            suspend fun apply(descriptor: JSONObject, file: File, rollback: suspend () -> Unit = {}): JSONObject {
                val job = (++jobNumber).toString().padStart(32, '0')
                val binding = obj("direction" to "watchToPhone", "jobId" to job,
                    "intent" to "create", "selection" to selection)
                val auth = B2Authorization(account, "phone", "watch", snapshot, snapshot, listOf(binding)) { true }
                var fault = true
                val receiver = B2Receiver(ledger, files, auth, business,
                    fault = { if (fault && it == "beforeApplyCommit") throw B2Exception("LOCAL_IO") })
                val objectId = descriptor.s("objectId")
                val begin = obj("intent" to "create", "accountScope" to account, "phoneDeviceId" to "phone",
                    "watchDeviceId" to "watch", "direction" to "watchToPhone", "phoneSnapshot" to snapshot,
                    "watchSnapshot" to snapshot, "selection" to selection, "selectionDigest" to B2Digests.selection(selection),
                    "objectCount" to 1, "objectsRoot" to B2Digests.objects(listOf(objectId)), "pageObjectCounts" to JSONArray(listOf(1)))
                val plan = B2Digests.plan(begin)
                val epoch = receiver.handle(B2Wire.request("begin", job, plan, null, begin)).n("epoch")
                val page = obj("pageIndex" to 0, "startIndex" to 0, "previousPageDigest" to "0".repeat(64),
                    "pageDigest" to "0".repeat(64), "entries" to JSONArray(listOf(descriptor)))
                page.put("pageDigest", B2Digests.page(plan, page))
                receiver.handle(B2Wire.request("manifestPage", job, plan, epoch, page))
                if (file.length() > 0) {
                    val bytes = file.readBytes()
                    val put = B2Wire.request("putChunk", job, plan, epoch, obj("objectId" to objectId, "chunkIndex" to 0))
                    val frame = B2Frame.Data(B2DataHeader(2, 0, epoch, 1, 0, bytes.size, put.s("requestId"), put.s("requestDigest")), bytes)
                    val ack = receiver.data(frame, receiver.inspect(frame.header).first)
                    assertEquals(1, ack.entries.single().disposition)
                }
                val request = B2Wire.request("apply", job, plan, epoch, obj("objectId" to objectId))
                try {
                    receiver.handle(request)
                    fail("Expected the injected rollback")
                } catch (e: B2Exception) {
                    assertEquals("LOCAL_IO", e.code)
                }
                assertTrue(ledger.list(account, "object").single { it.second.s("jobId") == job }.second.nil("receipt"))
                rollback()
                fault = false
                val receipt = receiver.handle(request).o("result").o("receipt")
                assertEquals(receipt.toString(), receiver.handle(request).o("result").o("receipt").toString())
                val complete = obj("objectCount" to 1, "receiptsRoot" to B2Digests.receipts(listOf(receipt)),
                    "ackToInclusive" to 0)
                assertEquals("COMPLETE", receiver.handle(B2Wire.request(
                    "complete", job, plan, epoch, complete)).o("result").s("state"))
                return receipt
            }
            val first = binaryDescriptor(B2_ARTICLE_BODY, id, bodyMeta, bodyHashes)
            assertEquals("merged", apply(first, encoded) { assertNull(articles.getById(id)) }.s("outcome"))
            val reference = requireNotNull(articles.getById(id)).contentText
            assertTrue(content.isMarker(reference))
            assertEquals(text, repo.getArticle(id)?.contentText)

            val metadata = binaryDescriptor(B2_ARTICLE_METADATA, id, article(id, 60), emptyHashes)
            assertEquals("merged", apply(metadata, empty) { assertEquals(30L, articles.getById(id)?.updatedAt) }.s("outcome"))
            assertEquals(reference, articles.getById(id)?.contentText)
            val stale = binaryDescriptor(B2_ARTICLE_METADATA, id, article(id, 40), emptyHashes)
            assertEquals("retainedLocal", apply(stale, empty).s("outcome"))

            val tombstone = article(id, 90).put("deleted", true).put("deletedAt", 90).put("independentSaved", false)
            apply(binaryDescriptor(B2_ARTICLE_METADATA, id, tombstone, emptyHashes), empty)
            assertTrue(requireNotNull(articles.getById(id)).deleted)
            assertEquals(reference, articles.getById(id)?.contentText)
            val missing = binaryDescriptor(B2_ARTICLE_METADATA, "missing", article("missing", 120), emptyHashes)
            apply(missing, empty)
            assertEquals("", articles.getById("missing")?.contentText)

            val url = "https://example.invalid/feed"
            val source = obj("url" to url, "sourceDeviceId" to "watch", "title" to "News",
                "description" to "", "siteUrl" to null, "imageUrl" to null, "createdAt" to 10,
                "updatedAt" to 40, "sortOrder" to 80, "isPinned" to true, "deleted" to false, "deletedAt" to 0)
            assertEquals("merged", apply(binaryDescriptor(B2_RSS_SOURCE, url, source, emptyHashes), empty) {
                assertNull(sources.getByUrl(url))
            }.s("outcome"))
            assertTrue(requireNotNull(sources.getByUrl(url)).isPinned)
            source.put("deleted", true).put("deletedAt", 45).put("updatedAt", 45)
            apply(binaryDescriptor(B2_RSS_SOURCE, url, source, emptyHashes), empty)
            assertTrue(requireNotNull(sources.getByUrl(url)).deleted)
        } finally {
            db.close()
            root.deleteRecursively()
        }
        Unit
    }

    @Test fun frozenAndReceivedLinkedArticlesRequireTheirRssReceipt() = runBlocking<Unit> {
        val context = RuntimeEnvironment.getApplication() as Context
        val senderDb = Room.inMemoryDatabaseBuilder(context, PhoneCompanionDatabase::class.java).build()
        val receiverDb = Room.inMemoryDatabaseBuilder(context, PhoneCompanionDatabase::class.java).build()
        val root = Files.createTempDirectory("b2-rss-links").toFile()
        val account = B2Digests.account("https://example.invalid", "test-user")
        val kinds = setOf(B2_ARTICLE_BODY, B2_ARTICLE_METADATA, B2_RSS_SOURCE)
        fun business(db: PhoneCompanionDatabase, side: String): B2InheritedKinds {
            val repo = PhoneCompanionRepository(db.phoneSavedItemDao(), db.phoneArticleDao(),
                db.phoneRssSourceDao(), side, syncChangeLogDao = db.syncChangeLogDao(),
                articleContentStore = FileArticleContentStore(File(root, "$side-content")))
            return B2InheritedKinds(db, repo, BluetoothArticleIdentityProjection(emptyList(), emptyMap(), false),
                B2NoteAssetBusiness(B2RoomStore(db), account, File(root, "notes")) {}, kinds, false, {})
        }
        val receiverStore = B2RoomStore(receiverDb)
        val receiverBusiness = business(receiverDb, "phone")
        val cap = B2Negotiation.capability(kinds, kinds)
        val selection = requireNotNull(B2Negotiation.select(cap, cap, "watchToPhone"))
        val snapshot = obj("snapshotId" to "snap", "manifestDigest" to "d".repeat(64),
            "fromExclusive" to 0, "toInclusive" to 0)
        var jobNumber = 0
        try {
            val sourceUrl = "http://fixture.invalid:80/feed?a=1"
            val articleUrl = "  fixture.invalid:80/feed?a=1  "
            val source = PhoneRssSourceEntity(sourceUrl, "watch", "Linked", "",
                null, null, 10, 40, 40, true, false, 0)
            val unrelated = source.copy(url = "http://elsewhere.invalid/feed")
            senderDb.phoneRssSourceDao().upsert(unrelated)
            val text = "linked article content\n"
            val body = LibrarySyncPayload.parseChunkedArticles(JSONArray().put(article("linked-body", 20))).single()
                .article.copy(rssSourceUrl = articleUrl, contentText = text, favoriteSaved = true,
                    favoriteChangedAt = 20, favoriteSortOrder = 20)
            val tombstone = body.copy(articleId = "linked-meta", contentText = "", deleted = true, deletedAt = 30)
            senderDb.phoneArticleDao().upsert(body)
            senderDb.phoneArticleDao().upsert(tombstone)
            for (rss in listOf(source, source.copy(deleted = true, deletedAt = 50, updatedAt = 50, isPinned = false))) {
                senderDb.phoneRssSourceDao().upsert(rss)
                val spool = Files.createTempDirectory(root.toPath(), "frozen").toFile()
                val frozen = mutableListOf<Pair<JSONObject, File>>()
                business(senderDb, "watch").freeze(kinds, spool, {}) { descriptor, file -> frozen += descriptor to file }
                val descriptors = frozen.map { it.first }
                val rssId = descriptors.single { it.s("businessId") == sourceUrl }.s("objectId")
                val otherId = descriptors.single { it.s("businessId") == unrelated.url }.s("objectId")
                val articles = descriptors.filter { it.s("kind") in setOf(B2_ARTICLE_BODY, B2_ARTICLE_METADATA) }
                assertEquals(setOf(B2_ARTICLE_BODY, B2_ARTICLE_METADATA), articles.map { it.s("kind") }.toSet())
                for (descriptor in articles) {
                    assertEquals(listOf(rssId), descriptor.a("dependsOn").strings())
                    assertEquals(B2Digests.objectId(descriptor), descriptor.s("objectId"))
                }
                suspend fun expectCode(code: String, block: suspend () -> Unit) {
                    try {
                        block()
                        fail("Expected $code")
                    } catch (failure: B2Exception) {
                        assertEquals(code, failure.code)
                    }
                }
                suspend fun tryPlan(objects: List<JSONObject>, expectRejected: Boolean) {
                    val sorted = objects.sortedBy { it.s("objectId") }
                    val job = (++jobNumber).toString().padStart(32, '0')
                    val binding = obj("direction" to "watchToPhone", "jobId" to job,
                        "intent" to "create", "selection" to selection)
                    val receiver = B2Receiver(receiverStore, B2Files(File(root, "receive")),
                        B2Authorization(account, "phone", "watch", snapshot, snapshot, listOf(binding)) { true },
                        receiverBusiness)
                    try {
                        val begin = obj("intent" to "create", "accountScope" to account, "phoneDeviceId" to "phone",
                            "watchDeviceId" to "watch", "direction" to "watchToPhone", "phoneSnapshot" to snapshot,
                            "watchSnapshot" to snapshot, "selection" to selection, "selectionDigest" to B2Digests.selection(selection),
                            "objectCount" to sorted.size, "objectsRoot" to B2Digests.objects(sorted.map { it.s("objectId") }),
                            "pageObjectCounts" to JSONArray(listOf(1, sorted.size - 1)))
                        val plan = B2Digests.plan(begin)
                        val epoch = receiver.handle(B2Wire.request("begin", job, plan, null, begin)).n("epoch")
                        suspend fun call(phase: String, payload: JSONObject) =
                            receiver.handle(B2Wire.request(phase, job, plan, epoch, payload)).o("result")
                        var previous = "0".repeat(64)
                        suspend fun page(index: Int, entries: List<JSONObject>): String {
                            val payload = obj("pageIndex" to index, "startIndex" to index, "previousPageDigest" to previous,
                                "entries" to JSONArray(entries), "pageDigest" to "0".repeat(64))
                            previous = B2Digests.page(plan, payload)
                            payload.put("pageDigest", previous)
                            return call("manifestPage", payload).s("state")
                        }
                        assertEquals("PLANNING", page(0, sorted.take(1)))
                        if (expectRejected) {
                            expectCode("PLAN_CONFLICT") { page(1, sorted.drop(1)) }
                            assertEquals("PLANNING", receiverStore.get(account, "job", job)?.s("state"))
                            return
                        }
                        assertEquals("READY", page(1, sorted.drop(1)))
                        suspend fun apply(d: JSONObject) = call("apply", obj("objectId" to d.s("objectId"))).o("receipt")
                        for (d in articles) {
                            expectCode("DEPENDENCIES_PENDING") { apply(d) }
                        }
                        val receipts = mutableMapOf<String, JSONObject>()
                        for ((descriptor, file) in frozen.sortedBy { it.first.s("kind") != B2_RSS_SOURCE }) {
                            val id = descriptor.s("objectId")
                            if (descriptor.s("kind") == B2_ARTICLE_BODY) {
                                val put = B2Wire.request("putChunk", job, plan, epoch, obj("objectId" to id, "chunkIndex" to 0))
                                receiver.handle(put, file.readBytes())
                            }
                            receipts[id] = apply(descriptor)
                        }
                        val complete = call("complete", obj("objectCount" to sorted.size, "ackToInclusive" to 0,
                            "receiptsRoot" to B2Digests.receipts(sorted.map { receipts.getValue(it.s("objectId")) })))
                        assertEquals("COMPLETE", complete.s("state"))
                        assertEquals(rss.deleted, receiverDb.phoneRssSourceDao().getByUrl(rss.url)?.deleted)
                        val row = requireNotNull(receiverDb.phoneArticleDao().getById(body.articleId))
                        assertEquals(text, FileArticleContentStore(File(root, "phone-content")).loadText(row.contentText))
                        assertTrue(row.favoriteSaved)
                    } finally {
                        receiver.revoke(cancelJobs = true)
                        receiver.closeIndex()
                    }
                }
                for (d in articles) {
                    val deps = if (d.s("kind") == B2_ARTICLE_BODY) emptyList() else listOf(otherId)
                    val invalid = JSONObject(d.toString()).put("dependsOn", JSONArray(deps))
                    invalid.put("objectId", B2Digests.objectId(invalid))
                    tryPlan(descriptors.map { if (it === d) invalid else it }, expectRejected = true)
                }
                tryPlan(descriptors, expectRejected = false)
            }
        } finally {
            senderDb.close()
            receiverDb.close()
            root.deleteRecursively()
        }
    }

    private fun article(id: String, clock: Long, hashes: B2FileHashes? = null): JSONObject =
        obj("articleId" to id, "sourceDeviceId" to "watch", "url" to "https://example.invalid/$id",
            "title" to "Title $clock", "siteName" to "", "excerpt" to "", "contentHash" to "content-hash",
            "importedAt" to 10, "updatedAt" to clock, "independentSaved" to true, "independentChangedAt" to clock,
            "independentSortOrder" to clock, "favoriteSaved" to false, "favoriteChangedAt" to 0,
            "favoriteSortOrder" to 0, "watchLaterSaved" to false, "watchLaterChangedAt" to 0, "watchLaterSortOrder" to 0,
            "deleted" to false, "deletedAt" to 0, "readingProgress" to 0.5, "readingPositionBytes" to 10,
            "readingPositionContentHash" to "content-hash", "readingPositionChangedAt" to clock, "isRead" to true,
            "body" to obj("metadataOnly" to (hashes == null), "bodyHash" to hashes?.sha256.orEmpty(),
                "bodyByteCount" to (hashes?.size ?: 0L), "chunkSize" to (if (hashes == null) 0 else 131072),
                "chunkHashes" to JSONArray(hashes?.legacyChunks.orEmpty())))
}
