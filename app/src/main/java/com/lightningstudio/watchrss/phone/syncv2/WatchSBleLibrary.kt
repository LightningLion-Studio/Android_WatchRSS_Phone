package com.lightningstudio.watchrss.phone.syncv2

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONObject

internal interface WatchSBleLibrary {
    data class Row(val cursor: String, val row: JSONObject)

    fun watchSBleHead(): String
    fun watchSBleNext(head: String, after: String): Row?
    fun watchSBlePut(head: String, row: JSONObject, authorize: () -> Unit): Boolean
}

/** The signed-out view reads Room directly and commits through its SQLite writer.
 * Sidecars retain states with no Room column or no matching public article yet.
 */
internal class WatchSBleLocalLibrary(private val db: SupportSQLiteDatabase) : WatchSBleLibrary {
    override fun watchSBleHead() = SyncV2Codec.canonical(null)

    override fun watchSBleNext(head: String, after: String): WatchSBleLibrary.Row? {
        check(head == watchSBleHead())
        SyncV2Codec.text(after, 8202)
        val kind = after.substringBefore(':')
        val id = after.substringAfter(':', "")
        if (after.isNotEmpty() && (kind !in WatchSBleRows.kinds || ':' !in after)) {
            SyncV2Codec.fail("invalid_schema")
        }
        return transaction {
            for (domain in orderedKinds.filter { it >= kind }) {
                next(domain, if (domain == kind) id else null)?.let { return@transaction it }
            }
            null
        }
    }

    private fun next(kind: String, after: String?): WatchSBleLibrary.Row? {
        val table = domains[kind]
        val key = table?.key ?: "logical_id"
        val operator = if (after == null) ">=" else ">"
        val filter = if (table == null) "kind='rss-state' AND " else ""
        val tableName = table?.table ?: SIDECARS
        return db.controlQuery("SELECT `$key` FROM `$tableName` WHERE $filter`$key` $operator ? ORDER BY `$key`",
            arrayOf(after ?: "")).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val value = current(kind, id, stored(kind, id)) ?: continue
                if (kind == "article" && !WatchSBleRows.portableArticle(value) ||
                    kind == "source" && !value.has("sourceId")) continue
                return@use WatchSBleLibrary.Row("$kind:$id", WatchSBleRows.encode(kind, id, value))
            }
            null
        }
    }

    override fun watchSBlePut(head: String, row: JSONObject, authorize: () -> Unit): Boolean {
        check(head == watchSBleHead())
        val incoming = WatchSBleRows.decode(row)
        val meta = incoming.getJSONObject("meta")
        val kind = meta.getString("kind")
        val id = meta.getString("logicalId")
        return transaction {
            authorize()
            if (kind == "article" && db.controlQuery(
                    """SELECT articleId FROM article_register_provenance
                       WHERE articleId=? AND (raw!='' OR pending IS NOT NULL)
                       UNION ALL SELECT articleId FROM imported_book_chapters WHERE articleId=?""",
                    arrayOf(id, id)
                ).use { it.moveToFirst() }) SyncV2Codec.fail("unsupported_feature")
            val stored = stored(kind, id)
            val old = current(kind, id, stored)
            if (kind == "article" && old != null && !WatchSBleRows.portableArticle(old)) {
                SyncV2Codec.fail("unsupported_feature")
            }
            val merged = WatchSBleRows.merge(kind, old, incoming.getJSONObject("value"))
            val changed = SyncV2LocalJson.canonical(old) != SyncV2LocalJson.canonical(merged)
            val hasNewRssMatch = kind == "rss-state" && stored != null && stored.isNull("footprint") &&
                SyncV2RssApply(db, listOf(incoming)).footprint(merged) != null
            if (changed || hasNewRssMatch) {
                val before = if (hasNewRssMatch) null else old
                val applied = SyncV2PhoneApply(db).apply(
                    listOfNotNull(before?.let { record(kind, id, it) }), listOf(record(kind, id, merged))
                )
                if (kind == "rss-state" || kind == "article") {
                    val sidecar = JSONObject().put("value", merged).put("footprint", applied?.footprint(merged))
                    db.execSQL("INSERT OR REPLACE INTO $SIDECARS VALUES(?,?,?)",
                        arrayOf(kind, id, SyncV2LocalJson.canonical(sidecar)))
                }
            }
            authorize()
            changed || hasNewRssMatch
        }
    }

    private fun stored(kind: String, id: String): JSONObject? = db.controlQuery(
        "SELECT body FROM $SIDECARS WHERE kind=? AND logical_id=?", arrayOf(kind, id)
    ).use { if (it.moveToFirst()) JSONObject(it.getString(0)) else null }

    private fun current(kind: String, id: String, sidecar: JSONObject?): JSONObject? {
        val saved = sidecar?.getJSONObject("value")
        if (kind == "rss-state") {
            if (saved == null) return null
            val row = record(kind, id, saved)
            val live = SyncV2RssApply(db, listOf(row)).footprint(saved) ?: return saved
            val footprint = sidecar.optJSONObject("footprint")
            if (footprint == null) return WatchSBleRows.merge(kind, live.put("sourceId", saved.getString("sourceId"))
                .put("articleIdHash", saved.getString("articleIdHash")), saved)
            return JSONObject(saved.toString()).apply {
                for (field in live.keys()) if (SyncV2LocalJson.canonical(live.opt(field)) !=
                    SyncV2LocalJson.canonical(footprint.opt(field))) put(field, live.get(field))
            }
        }
        val domain = domains.getValue(kind)
        val columns = (SyncV2Business.allowed(kind) - sidecarFields).sorted().joinToString(",") { "`$it`" }
        val filter = if (kind == "article") " AND NOT EXISTS (SELECT 1 FROM imported_book_chapters b WHERE b.articleId=phone_articles.articleId)" else ""
        val physical = db.controlQuery("SELECT $columns FROM `${domain.table}` WHERE `${domain.key}`=?$filter",
            arrayOf(id)).use { cursor ->
            if (!cursor.moveToFirst()) return null
            JSONObject().apply {
                for (index in 0 until cursor.columnCount) {
                    val field = cursor.getColumnName(index)
                    put(field, when {
                        cursor.isNull(index) -> JSONObject.NULL
                        field in booleanFields -> cursor.getInt(index) != 0
                        cursor.getType(index) == Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                        cursor.getType(index) == Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                        else -> cursor.getString(index)
                    })
                }
            }
        }
        if (kind == "source") runCatching { WatchSBleRows.rssSourceKey(id) }.getOrNull()?.let {
            physical.put("sourceId", it)
        }
        return if (kind == "article" && saved != null) WatchSBleRows.carryArticleState(physical, saved) else physical
    }

    private fun record(kind: String, id: String, value: JSONObject) =
        WatchSBleRows.record(kind, id, WatchSBleRows.entityKey(kind, id), value)

    private fun <T> transaction(run: () -> T): T {
        db.beginTransaction()
        try {
            db.execSQL("""CREATE TABLE IF NOT EXISTS $SIDECARS (
                kind TEXT NOT NULL, logical_id TEXT NOT NULL, body TEXT NOT NULL, PRIMARY KEY(kind,logical_id))""")
            return run().also { db.setTransactionSuccessful() }
        } finally {
            db.endTransaction()
        }
    }

    private companion object {
        const val SIDECARS = "watchs_ble_local_state"
        val orderedKinds = WatchSBleRows.kinds.sorted()
        val domains = SyncV2Schema.domains.filter { it.kind == "article" || it.kind == "source" }.associateBy { it.kind }
        val sidecarFields = setOf("sourceId", "guid", "rssIndexRetired", "bookIndexRetired", "registerSources")
        val booleanFields = setOf("isRead", "favoriteSaved", "watchLaterSaved", "independentSaved", "deleted",
            "isPinned", "useOriginalContent", "continuePlaybackInBackground")
    }
}
