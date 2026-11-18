package com.lightningstudio.watchrss.phone.connection.bluetooth

import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Watch apply limits for outgoing metadata. Incoming B2 objects keep the negotiated limit. */
internal object B2BookMetadataLimits {
    const val BODY_BYTES = 1024 * 1024
    private const val STRING_BYTES = 256 * 1024
    const val VERSION = 1

    fun bodyBytes(raw: String): Int = if (raw.length > BODY_BYTES) BODY_BYTES + 1 else B2Json.utf8(raw).size

    fun stringsFit(value: Any?): Boolean = when (value) {
        is String -> value.length <= STRING_BYTES && B2Json.utf8(value).size <= STRING_BYTES
        is JSONObject -> value.keys().asSequence().all { stringsFit(it) && stringsFit(value.get(it)) }
        is JSONArray -> (0 until value.length()).all { stringsFit(value.get(it)) }
        else -> true
    }

    fun acceptsFrozen(d: JSONObject, file: File, work: () -> Unit): Boolean {
        val kind = d.s("kind")
        if (kind != B2Wire.BOOK_STATE && kind != B2Wire.BOOK_READING) return true
        if (d.n("size") > BODY_BYTES) return false
        if (!file.isFile || file.length() != d.n("size")) fail("SOURCE_CHANGED")
        val decoder = Charsets.UTF_8.newDecoder()
        return java.io.InputStreamReader(file.inputStream(), decoder).use { input ->
            val parser = B2BookJson(input, kind == B2Wire.BOOK_STATE, 100032, work)
            if (kind == B2Wire.BOOK_STATE) stringsFit(parser.state()) else parser.readings().all(::stringsFit)
        }
    }

    fun issue(bookId: String) = BookSyncIssue(bookId.take(8), "book_metadata_limit")
}
