package com.lightningstudio.watchrss.phone.syncv2

import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Local-only chapter mappings. A source revision and its pages commit in the projection writer. */
internal class SyncV2BookProvenance(
    private val db: SupportSQLiteDatabase,
    private val scope: String,
    private val seal: (String, JSONObject) -> ByteArray,
    private val open: (String, ByteArray) -> JSONObject
) {
    private companion object {
        const val PAGE_ROWS = 256
        const val PAGE_BYTES = 262_144
        const val EMPTY_PAGE_BYTES = 15 // {"chapters":[]} in canonical UTF-8
    }

    private fun forEachChapter(id: String, visit: (JSONObject) -> Unit) {
        var after = Long.MIN_VALUE
        do {
            val count = db.controlQuery(
                """SELECT chapterIndex, articleId FROM imported_book_chapters
                   WHERE bookId=? AND chapterIndex>? ORDER BY chapterIndex LIMIT ?""",
                arrayOf<Any>(id, after, PAGE_ROWS)
            ).use { rows ->
                while (rows.moveToNext()) {
                    after = rows.getLong(0)
                    visit(JSONObject().put("bookId", id).put("chapterIndex", after).put("articleId", rows.getString(1)))
                }
                rows.count
            }
        } while (count == PAGE_ROWS)
    }

    fun sourceHash(id: String, objects: SyncV2Business.PairObjects): String {
        // Stream the original canonical source object so paging does not create an edit.
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(value: String) = digest.update(SyncV2Codec.utf8(value))
        add("{\"meta\":")
        add(SyncV2Codec.canonical(objects.meta))
        add(",\"provenance\":{\"chapters\":[")
        var first = true
        forEachChapter(id) { chapter ->
            if (!first) digest.update(','.code.toByte())
            add(SyncV2Codec.canonical(chapter))
            first = false
        }
        add("]},\"state\":")
        add(SyncV2Codec.canonical(objects.state))
        add("}")
        return SyncV2Codec.hex(digest.digest())
    }

    fun write(entity: String, id: String, hash: String, retainForReset: Boolean): JSONObject {
        check(db.inTransaction())
        remove(entity)
        var page = JSONArray()
        var bytes = EMPTY_PAGE_BYTES
        var pages = 0
        fun flush() {
            val body = seal(purpose(entity, hash, pages), JSONObject().put("chapters", page))
            if (body.size > PAGE_BYTES) SyncV2Codec.fail("object_too_large")
            db.execSQL("INSERT OR IGNORE INTO syncv2_book_provenance VALUES(?,?,?,?,?,?)",
                arrayOf(scope, entity, hash, pages, body, if (retainForReset) 1 else 0))
            pages++
            page = JSONArray()
            bytes = EMPTY_PAGE_BYTES
        }
        forEachChapter(id) { chapter ->
            val size = SyncV2Codec.utf8(SyncV2Codec.canonical(chapter)).size
            // Allow 28 bytes for the local AEAD nonce and tag.
            if (page.length() == PAGE_ROWS || (page.length() > 0 && bytes + size + 1 > PAGE_BYTES - 28)) flush()
            bytes += size + if (page.length() == 0) 0 else 1
            page.put(chapter)
        }
        if (page.length() > 0) flush()
        return JSONObject().put("chapterSourceHash", hash).put("chapterPageCount", pages)
    }

    fun read(entity: String, provenance: JSONObject): JSONObject {
        if (!provenance.has("chapterPageCount")) return provenance
        val hash = provenance.getString("chapterSourceHash")
        val chapters = JSONArray()
        var page = 0
        db.controlQuery(
            """SELECT page, body FROM syncv2_book_provenance
               WHERE scope=? AND entity_key=? AND source_hash=? ORDER BY page""",
            arrayOf(scope, entity, hash)
        ).use { rows ->
            while (rows.moveToNext()) {
                if (rows.getInt(0) != page) SyncV2Codec.fail("closure_incomplete")
                val slice = open(purpose(entity, hash, page), rows.getBlob(1)).getJSONArray("chapters")
                for (i in 0 until slice.length()) chapters.put(slice.getJSONObject(i))
                page++
            }
        }
        if (page != provenance.getInt("chapterPageCount")) SyncV2Codec.fail("closure_incomplete")
        return JSONObject().put("chapters", chapters)
    }

    fun remove(entity: String) {
        db.execSQL("DELETE FROM syncv2_book_provenance WHERE scope=? AND entity_key=? AND retained=0", arrayOf(scope, entity))
    }

    private fun purpose(entity: String, hash: String, page: Int) = "book-provenance/$entity/$hash/$page"
}
