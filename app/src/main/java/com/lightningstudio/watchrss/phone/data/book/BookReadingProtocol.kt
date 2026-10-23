package com.lightningstudio.watchrss.phone.data.book

import com.lightningstudio.watchrss.phone.cloud.CloudProtocolV2
import com.lightningstudio.watchrss.phone.cloud.CloudSnapshotCodec
import org.json.JSONObject
import kotlin.math.floor

data class BookReadingSummary(val epoch: String, val seq: Long, val root: String, val count: Long) {
    init {
        require(epoch.matches(Regex("[0-9a-f]{32}")) && root.matches(Regex("[0-9a-f]{64}")))
        require(seq in 0..BookReadingHash.MAX && count in 0..BookReadingHash.MAX)
    }
    fun json() = JSONObject().put("epoch", epoch).put("seq", seq).put("root", root).put("count", count)
    companion object {
        fun read(v: JSONObject) = BookReadingSummary(v.getString("epoch"), BookSync.integer(v, "seq"), v.getString("root"), BookSync.integer(v, "count"))
    }
}

data class BookReadingEntry(val seq: Long, val state: JSONObject) {
    fun json() = JSONObject().put("seq", seq).put("state", state)
}

/** BLE §12. No JSON serialization, clock, physical row id or epoch enters these hashes. */
object BookReadingHash {
    const val MAX = 9_007_199_254_740_991L
    private fun hash(fields: List<String>) = CloudSnapshotCodec.sha256(CloudProtocolV2.lp(*fields.toTypedArray()))
    fun leaf(v: JSONObject): String {
        BookSync.validateReading(v)
        val fields = mutableListOf("watchrss/book/reading-state/v1", v.getString("bookId"), v.getInt("chapterIndex").toString(),
            if (v.getBoolean("isRead")) "1" else "0", floor(v.getDouble("readingProgress") * 1_000_000 + 0.5).toLong().toString(),
            v.getLong("readingPositionBytes").toString(), v.getString("readingPositionContentHash"),
            v.getLong("readingPositionChangedAt").toString(), v.getString("sourceDeviceId"))
        for (prefix in listOf("favorite", "watchLater")) {
            val present = v.has("${prefix}Saved")
            fields += listOf(prefix, if (present) "1" else "0")
            if (present) fields += listOf(if (v.getBoolean("${prefix}Saved")) "1" else "0", v.getLong("${prefix}ChangedAt").toString(),
                v.getLong("${prefix}SortOrder").toString(), v.optString("${prefix}SourceDeviceId", v.getString("sourceDeviceId")))
        }
        return hash(fields)
    }
    fun book(id: String, leaves: List<Pair<Int, String>>) = hash(buildList {
        add("watchrss/book/reading-book/v1"); add(id); add(leaves.size.toString())
        leaves.sortedBy { it.first }.forEach { (index, leaf) -> add(index.toString()); add(leaf) }
    })
    fun root(books: List<BookReadingRootEntity>) = hash(buildList {
        add("watchrss/book/reading-root/v1"); add(books.sumOf { it.count }.toString()); add(books.size.toString())
        books.sortedBy { it.bookId }.forEach { add(it.bookId); add(it.root); add(it.count.toString()) }
    })
}
