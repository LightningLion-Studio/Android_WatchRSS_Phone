package com.lightningstudio.watchrss.phone.connection.bluetooth.b2

import org.json.JSONObject
import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.B2Wire.BOOK_COMMIT
import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.B2Wire.BOOK_READING
import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.B2Wire.BOOK_SEGMENT
import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.B2Wire.BOOK_SEGMENT_BYTES
import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.B2Wire.BOOK_STATE

internal object B2BookManifest {
    private fun segmentCount(bytes: Long) = (bytes + BOOK_SEGMENT_BYTES - 1) / BOOK_SEGMENT_BYTES

    fun validateObject(d: JSONObject, m: JSONObject) {
        val kind = d.s("kind")
        when (kind) {
            BOOK_STATE -> B2Json.exact(m, "bookId")
            BOOK_READING -> B2Json.exact(m, "bookId", "pageIndex")
            BOOK_SEGMENT -> B2Json.exact(m, "bookId", "fingerprint", "packageSha256", "packageBytes", "segmentIndex", "segmentCount", "segmentOffset")
            BOOK_COMMIT -> B2Json.exact(m, "bookId", "fingerprint", "packageSha256", "packageBytes", "segmentObjectIds")
        }
        val book = B2Json.hex(m.get("bookId"))
        val deps = d.a("dependsOn").strings()
        val size = d.n("size")
        if (kind != BOOK_COMMIT && size == 0L) fail("OBJECT_CONFLICT")
        val businessId = when (kind) {
            BOOK_STATE -> {
                if (deps.isNotEmpty()) fail("PLAN_CONFLICT")
                book
            }
            BOOK_READING -> {
                if (deps.size != 1) fail("PLAN_CONFLICT")
                "$book:${m.n("pageIndex")}"
            }
            else -> {
                B2Json.hex(m.get("fingerprint"))
                val sha = B2Json.hex(m.get("packageSha256"))
                val bytes = B2Json.integer(m.get("packageBytes"), B2Wire.BOOK_MAX_BYTES)
                if (bytes == 0L) fail("LIMIT_EXCEEDED")
                val count = segmentCount(bytes)
                if (kind == BOOK_SEGMENT) {
                    val index = m.n("segmentIndex")
                    val offset = m.n("segmentOffset")
                    if (deps.size != 1 || index >= count || m.n("segmentCount") != count ||
                        offset != index * BOOK_SEGMENT_BYTES || size != minOf(BOOK_SEGMENT_BYTES, bytes - offset)) fail("PLAN_CONFLICT")
                    "$book:$sha:$index"
                } else {
                    val segments = m.a("segmentObjectIds").strings()
                    if (segments.size.toLong() != count || segments.toSet().size != segments.size ||
                        deps.size != segments.size + 1 || !deps.containsAll(segments)) fail("PLAN_CONFLICT")
                    segments.forEach { B2Json.hex(it) }
                    book
                }
            }
        }
        if (businessId != d.s("businessId")) fail("OBJECT_CONFLICT")
    }

    fun validatePlan(objects: List<JSONObject>, maxPackageBytes: Long) {
        val bookObjects = objects.filter { it.s("kind") in B2Wire.bookKinds }
        for ((_, group) in bookObjects.map { it to B2Metadata.validate(it) }.groupBy { it.second.s("bookId") }) {
            val state = group.singleOrNull { it.first.s("kind") == BOOK_STATE }?.first?.s("objectId")
                ?: fail("PLAN_CONFLICT")
            val segments = group.filter { it.first.s("kind") == BOOK_SEGMENT }.sortedBy { it.second.n("segmentIndex") }
            val commit = group.singleOrNull { it.first.s("kind") == BOOK_COMMIT }
            val reading = group.filter { it.first.s("kind") == BOOK_READING }.sortedBy { it.second.n("pageIndex") }
            for ((index, item) in reading.withIndex()) {
                if (item.second.n("pageIndex") != index.toLong() ||
                    item.first.a("dependsOn").strings() != listOf(state)) fail("PLAN_CONFLICT")
            }
            if (commit == null) {
                if (segments.isNotEmpty() || group.any { it.first.s("kind") == BOOK_COMMIT }) fail("PLAN_CONFLICT")
                continue
            }
            val (d, m) = commit
            val bytes = m.n("packageBytes")
            if (bytes > maxPackageBytes) fail("LIMIT_EXCEEDED")
            val segmentIds = segments.map { it.first.s("objectId") }
            if (m.a("segmentObjectIds").strings() != segmentIds ||
                d.a("dependsOn").strings() != (segmentIds + state).sorted()) fail("PLAN_CONFLICT")
            for ((index, item) in segments.withIndex()) {
                val (sd, sm) = item
                if (sd.a("dependsOn").strings() != listOf(state) || sm.n("segmentIndex") != index.toLong() ||
                    sm.n("packageBytes") != bytes || sm.s("packageSha256") != m.s("packageSha256") ||
                    sm.s("fingerprint") != m.s("fingerprint")) fail("PLAN_CONFLICT")
            }
        }
    }
}
