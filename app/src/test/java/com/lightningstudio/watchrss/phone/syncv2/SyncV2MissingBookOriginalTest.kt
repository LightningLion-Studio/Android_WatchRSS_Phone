package com.lightningstudio.watchrss.phone.syncv2

import com.lightningstudio.watchrss.phone.data.book.BookSync
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SyncV2MissingBookOriginalTest {
    @Test fun keepLocalPublishesRssAndArticleWhileRetainingBothOrphans() = SyncV2P5Fixture().use { fixture ->
        val oldBook = book("ab", 1)
        val differentBook = book("ab", 2)
        val localOnly = book("cd", 1)
        val article = example("article").put("rssSourceUrl", JSONObject.NULL).put("rssSourceTitle", JSONObject.NULL)
        val articleId = article.getString("articleId")
        val rss = example("source")
        fixture.advance(listOf(fixture.note(), oldBook, article, rss))
        fixture.start()
        val oldWire = fixture.wire("book", oldBook.getString("bookId"))

        fixture.insert(localOnly)
        val differentId = differentBook.getString("bookId")
        fixture.sql.execSQL("UPDATE imported_books SET stateJson=? WHERE bookId=?", arrayOf(differentBook.toString(), differentId))
        fixture.sql.execSQL("INSERT INTO imported_book_chapters VALUES(?,2,?)", arrayOf(differentId, BookSync.articleId(differentId, 2)))
        fixture.sql.execSQL("UPDATE phone_articles SET title='local article',contentText='local original' WHERE articleId=?", arrayOf(articleId))
        fixture.sql.execSQL("UPDATE phone_rss_sources SET title='local source' WHERE url=?", arrayOf(rss.getString("url")))
        fixture.drain()
        fixture.advance(listOf(fixture.note("cloud edit"), oldBook, article, rss),
            mapOf(fixture.identity.entity("book", differentId) to checkNotNull(oldWire)))

        val actions = fixture.actions()
        actions.refresh()
        fixture.projection.requestConcurrentChoice()
        assertTrue(actions.choose(actions.preview(), "local").getBoolean("localCommitted"))
        val localId = localOnly.getString("bookId")
        assertNull(fixture.wire("book", localId))
        assertEquals(oldWire.toString(), fixture.wire("book", differentId).toString())
        assertNotNull(fixture.wire("article", articleId))
        assertEquals("local article", fixture.cloudValue("article", articleId).getString("title"))
        assertEquals("local source", fixture.cloudValue("source", SyncV2Business.logicalId("source", rss)).getString("title"))
        assertEquals("local original", fixture.scalar("SELECT contentText FROM phone_articles WHERE articleId=?", articleId))
        assertEquals("2", fixture.scalar("SELECT json_extract(stateJson,'$.chapterCount') FROM imported_books WHERE bookId=?", differentId))
        assertEquals(BookSync.articleId(differentId, 2), fixture.scalar("SELECT articleId FROM imported_book_chapters WHERE bookId=?", differentId))
        assertEquals(emptySet<String>(), fixture.projection.publicationIds())
        assertTrue(fixture.projection.state().body.isNull("pendingChoice"))

        fixture.reopen()
        fixture.actions().refresh()
        assertTrue(fixture.projection.isUnchangedAccount(fixture.projection.state()))
        assertEquals(1, fixture.publishCalls)
        fixture.edit("later unrelated edit")
        fixture.publishNormally()
        assertNull(fixture.wire("book", localId))
        assertEquals(oldWire.toString(), fixture.wire("book", differentId).toString())
        assertEquals("later unrelated edit", fixture.cloudValue("note", "note").getString("markdown"))
        assertEquals(2, fixture.sql.query("SELECT bookId FROM imported_books").use { it.count })
        assertEquals(emptySet<String>(), fixture.projection.publicationIds())

        val restored = fixture.seedBookOriginal(differentId, "restored different original".toByteArray())
        fixture.edit("edit after original returned")
        fixture.publishNormally()
        assertEquals(oldWire.toString(), fixture.wire("book", differentId).toString())
        assertEquals("edit after original returned", fixture.cloudValue("note", "note").getString("markdown"))
        assertTrue(restored.isFile)
    }

    @Test fun sameIndependentBodyCarriesTheAuthenticatedReferenceWithoutAnOriginal() = SyncV2P5Fixture().use { fixture ->
        val book = book("ef", 1)
        val id = book.getString("bookId")
        fixture.advance(listOf(fixture.note(), book))
        val prior = checkNotNull(fixture.wire("book", id)).put("bodyRevision", "11".repeat(16)).put("promiseId", "22".repeat(16))
        fixture.advance(listOf(fixture.note(), book), mapOf(fixture.identity.entity("book", id) to prior))
        fixture.start()
        fixture.sql.execSQL("UPDATE imported_books SET stateJson=? WHERE bookId=?", arrayOf(book.put("title", "local title").toString(), id))
        fixture.drain()
        fixture.projection.requestConcurrentChoice()
        val actions = fixture.actions()
        val preview = actions.preview()
        assertTrue(preview.reason, preview.canMerge)
        assertTrue(actions.choose(preview, "local").getBoolean("localCommitted"))
        val published = checkNotNull(fixture.wire("book", id))
        for (field in SyncV2BodyIdentity.fields) assertEquals(prior.get(field), published.get(field))
        assertEquals("local title", fixture.cloudValue("book", id).getString("title"))
        assertTrue(fixture.projection.heldBooks().isEmpty())
    }

    @Test fun normalPublicationHoldsAnOrphanAndCanPublishItOnceTheOriginalReturns() = SyncV2P5Fixture().use { fixture ->
        fixture.start()
        val book = book("23", 1)
        val id = book.getString("bookId")
        val original = "synthetic book original".toByteArray()
        fixture.insert(book, original)
        fixture.edit("ordinary edit")
        fixture.publishNormally()
        assertNull(fixture.wire("book", id))
        assertEquals("ordinary edit", fixture.cloudValue("note", "note").getString("markdown"))
        assertEquals(setOf(fixture.identity.entity("book", id)), fixture.projection.heldBooks())
        assertFalse(fixture.projection.state().body.optBoolean("unknownEditBase"))
        assertEquals(emptySet<String>(), fixture.projection.publicationIds())

        fixture.reopen()
        File(fixture.root, "imported_books/$id.jsonl").also { it.parentFile!!.mkdirs(); it.writeBytes(original) }
        fixture.publishNormally(setOf(fixture.identity.entity("book", id)))
        assertNotNull(fixture.wire("book", id))
        assertTrue(fixture.projection.heldBooks().isEmpty())
    }

    private fun example(kind: String): JSONObject {
        val input = checkNotNull(javaClass.classLoader!!.getResourceAsStream("syncv2-business-schema.json"))
        val records = input.bufferedReader().use { JSONObject(it.readText()).getJSONArray("records") }
        return (0 until records.length()).map(records::getJSONObject)
            .single { it.getString("kind") == kind }.getJSONObject("value")
    }

    private fun book(pattern: String, chapters: Int): JSONObject {
        val fingerprint = pattern.repeat(32)
        return JSONObject().put("bookId", BookSync.id(fingerprint)).put("fingerprint", fingerprint)
            .put("chapterCount", chapters).put("title", "Book").put("deletedChapters", JSONArray())
            .put("deletionClock", 0).put("deleted", false).put("deletedAt", 0)
            .put("updatedAt", 1).put("sourceDeviceId", "synthetic-phone")
    }

    private fun SyncV2P5Fixture.insert(book: JSONObject, original: ByteArray? = null) {
        sql.execSQL("INSERT INTO imported_books VALUES(?,?,?,?)", arrayOf(book.getString("bookId"), book.toString(),
            original?.let { SyncV2Codec.hex(SyncV2Codec.sha(it)) }.orEmpty(), original?.size ?: 0))
    }

    private fun SyncV2P5Fixture.scalar(query: String, id: String): String =
        sql.query(query, arrayOf(id)).use { check(it.moveToFirst()); it.getString(0) }

    private fun SyncV2P5Fixture.advance(values: List<JSONObject>, reuse: Map<String, JSONObject> = emptyMap()) {
        val oid = commit(values, current.getString("commitOid"), current.getString("rollbackGeneration"), "normal", null, reuse)
        setCurrent(oid, SyncV2Codec.next(current.getString("revision")))
    }

    private fun SyncV2P5Fixture.wire(kind: String, id: String): JSONObject? {
        val root = headers.getValue(current.getString("commitOid")).getJSONObject("body").getString("treeRoot")
        return SyncV2Index.find(identity.scope, root, identity.entity(kind, id)) { f.objects.getValue(it) }
    }

    private fun SyncV2P5Fixture.cloudValue(kind: String, id: String): JSONObject =
        projection.cloudRows(setOf(identity.entity(kind, id))).single().let {
            SyncV2Business.join(kind, it.getJSONObject("meta"), it.getJSONObject("state"))
        }

    private fun SyncV2P5Fixture.publishNormally(extraIds: Set<String> = emptySet()) {
        val cloud = SyncV2CloudSync(f.client(), identity, projection, { it == "synthetic-phone" }, { key }, { f.now }, root)
        cloud.publishIndex(f.signer, key, 1, projection.state().revision, extraIds = extraIds,
            observePublished = { delta -> actions().refresh(published = delta) })
    }
}
