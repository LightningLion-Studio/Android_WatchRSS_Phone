package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Network parser: reject duplicate keys before org.json can discard them. */
internal object SyncV2Wire {
    inline fun <T> schema(block:()->T):T = try {block()} catch(_:org.json.JSONException){SyncV2Codec.fail("invalid_schema")}

    fun string(o: JSONObject, k: String): String = o.get(k) as? String ?: SyncV2Codec.fail("invalid_schema")
    fun uint(o: JSONObject, k: String, max: Long = 9007199254740991L): Long {
        val n = o.get(k)
        if (n !is Int && n !is Long) SyncV2Codec.fail("invalid_schema")
        return (n as Number).toLong().also { if (it !in 0..max) SyncV2Codec.fail("invalid_schema") }
    }
    fun oid(s: String, type: String? = null): String = s.also {
        if (!Regex("${type ?: "(?:meta|state|blob|recipe|tree|commit|state-refs|recipe-refs|commit-header|repo-descriptor|id-key-recovery)"}:sha256:[0-9a-f]{64}").matches(it)) SyncV2Codec.fail("invalid_oid")
    }
    fun strings(a: JSONArray, limit: Int, sorted: Boolean = true): List<String> {
        if (a.length() > limit) SyncV2Codec.fail("invalid_schema")
        val list = (0 until a.length()).map { a.get(it) as? String ?: SyncV2Codec.fail("invalid_schema") }
        if (sorted && list != list.distinct().sorted()) SyncV2Codec.fail("invalid_schema")
        return list
    }
    fun features(a: JSONArray): List<String> = strings(a, 32).also { values ->
        if (values.any { !Regex("[a-z0-9][a-z0-9.-]{0,63}").matches(it) }) SyncV2Codec.fail("invalid_schema")
    }
    fun parse(bytes: ByteArray, limit: Int = 1048576, canonical: Boolean = false): JSONObject = SyncV2LocalProfile.time("json.parse",bytes.size.toLong()) {
        if (bytes.size > limit) SyncV2Codec.fail("object_too_large")
        val text = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
        catch (_: Exception) { SyncV2Codec.fail("invalid_encoding") }
        val parser = Parser(text)
        val value = parser.value(0) as? JSONObject ?: SyncV2Codec.fail("invalid_encoding")
        parser.space()
        if (parser.at != text.length || (canonical && SyncV2Codec.canonical(value) != text)) SyncV2Codec.fail("invalid_encoding")
        value
    }
    private class Parser(val text: String) {
        var at = 0
        fun space() { while (at < text.length && text[at] in " \r\n\t") at++ }
        fun take(c: Char): Boolean { space(); return if (at < text.length && text[at] == c) { at++; true } else false }
        fun need(c: Char) { if (!take(c)) SyncV2Codec.fail("invalid_encoding") }
        fun value(depth: Int): Any {
            if (depth > 64) SyncV2Codec.fail("invalid_encoding")
            space(); if (at == text.length) SyncV2Codec.fail("invalid_encoding")
            return when (text[at]) {
                '{' -> { at++; val o = JSONObject(); if (!take('}')) { do {
                    space(); val key = quoted(); if (o.has(key)) SyncV2Codec.fail("invalid_encoding")
                    need(':'); o.put(key, value(depth + 1))
                } while (take(',')); need('}') }; o }
                '[' -> { at++; val a = JSONArray(); if (!take(']')) { do { a.put(value(depth + 1)) } while (take(',')); need(']') }; a }
                '"' -> quoted()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", JSONObject.NULL)
                else -> {
                    val start = at
                    if (text[at] == '-') at++
                    while (at < text.length && text[at] in '0'..'9') at++
                    val raw = text.substring(start, at)
                    if (!Regex("0|[1-9][0-9]*|-[1-9][0-9]*").matches(raw)) SyncV2Codec.fail("invalid_encoding")
                    val n = raw.toLongOrNull() ?: SyncV2Codec.fail("invalid_encoding")
                    if (n !in -9007199254740991L..9007199254740991L) SyncV2Codec.fail("invalid_encoding")
                    if (n in Int.MIN_VALUE..Int.MAX_VALUE) n.toInt() else n
                }
            }
        }
        private fun literal(word: String, v: Any): Any {
            if (!text.startsWith(word, at)) SyncV2Codec.fail("invalid_encoding")
            at += word.length; return v
        }
        private fun quoted(): String {
            if (at >= text.length || text[at++] != '"') SyncV2Codec.fail("invalid_encoding")
            val result = StringBuilder()
            while (at < text.length) {
                val c = text[at++]
                if (c == '"') return result.toString().also { SyncV2Codec.utf8(it) }
                if (c < ' ') SyncV2Codec.fail("invalid_encoding")
                if (c != '\\') { result.append(c); continue }
                if (at == text.length) SyncV2Codec.fail("invalid_encoding")
                when (val escape = text[at++]) {
                    '"', '\\', '/' -> result.append(escape)
                    'b' -> result.append('\b'); 'f' -> result.append('\u000c'); 'n' -> result.append('\n')
                    'r' -> result.append('\r'); 't' -> result.append('\t')
                    'u' -> {
                        if (at + 4 > text.length) SyncV2Codec.fail("invalid_encoding")
                        val h = text.substring(at, at + 4)
                        if (!Regex("[0-9a-fA-F]{4}").matches(h)) SyncV2Codec.fail("invalid_encoding")
                        result.append(h.toInt(16).toChar()); at += 4
                    }
                    else -> SyncV2Codec.fail("invalid_encoding")
                }
            }
            SyncV2Codec.fail("invalid_encoding")
        }
    }
}
