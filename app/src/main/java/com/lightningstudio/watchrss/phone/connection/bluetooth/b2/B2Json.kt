package com.lightningstudio.watchrss.phone.connection.bluetooth.b2

import org.json.JSONObject
import org.json.JSONArray
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

class B2Exception(val code: String, val retryAfterMillis: Long = 0) : Exception(code)
internal fun fail(code: String = "INVALID_FRAME"): Nothing = throw B2Exception(code)
internal fun obj(vararg pairs: Pair<String, Any?>) = JSONObject().also { o -> pairs.forEach { o.put(it.first, it.second ?: JSONObject.NULL) } }
internal fun JSONObject.s(k: String) = opt(k) as? String ?: fail()
internal fun JSONObject.n(k: String) = B2Json.integer(opt(k) ?: fail())
internal fun JSONObject.o(k: String) = opt(k) as? JSONObject ?: fail()
internal fun JSONObject.a(k: String) = opt(k) as? JSONArray ?: fail()
internal fun JSONObject.nil(k: String) = has(k) && isNull(k)
internal fun JSONArray.values(): List<Any> = (0 until length()).map { get(it) }
internal fun JSONArray.strings() = values().map { it as? String ?: fail() }
internal fun copy(o: JSONObject) = B2Json.parse(o.toString(), false)

/** C-only parser. Never reuse the permissive v14 JSONObject constructor on network bytes. */
object B2Json {
    const val MAX_SAFE = 9007199254740991L
    fun utf8(s: String): ByteArray = try {
        val b = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(s))
        ByteArray(b.remaining()).also { b.get(it) }
    } catch (_: java.nio.charset.CharacterCodingException) { fail() }
    fun decode(b: ByteArray): String = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString()
    } catch (_: java.nio.charset.CharacterCodingException) { fail() }
    fun exact(v: Any, vararg keys: String): JSONObject {
        val o = v as? JSONObject ?: fail()
        if (o.keys().asSequence().toSet() != keys.toSet()) fail()
        return o
    }
    fun integer(v: Any, max: Long = MAX_SAFE): Long {
        if (v !is Int && v !is Long) fail()
        return (v as Number).toLong().also { if (it < 0 || it > max) fail() }
    }
    fun text(v: Any, max: Int, min: Int = 1): String {
        val s = v as? String ?: fail()
        if (utf8(s).size !in min..max) fail()
        return s
    }
    fun hex(v: Any, n: Int = 64) = (v as? String)?.takeIf { it.length == n && it.all { char -> char in "0123456789abcdef" } } ?: fail()
    fun bool(v: Any) = v as? Boolean ?: fail()
    fun parse(bytes: ByteArray, integersOnly: Boolean = true) = parse(decode(bytes), integersOnly)
    fun parse(source: String, integersOnly: Boolean = true): JSONObject {
        var i = 0
        fun ws() { while (i < source.length && source[i] in " \t\r\n") i++ }
        fun string(): String {
            ws(); if (i >= source.length || source[i++] != '"') fail()
            val start = i - 1
            var escaped = false
            while (i < source.length) {
                val c = source[i++]
                if (escaped) { if (c !in "\"\\/bfnrtu") fail(); if (c == 'u') {
                    if (i + 4 > source.length || !source.substring(i, i + 4).matches(Regex("[0-9a-fA-F]{4}"))) fail(); i += 4
                }; escaped = false; continue }
                if (c < ' ') fail()
                if (c == '\\') { escaped = true; continue }
                if (c == '"') {
                    val value = try { JSONArray("[" + source.substring(start, i) + "]").getString(0) } catch (_: Exception) { fail() }
                    utf8(value); return value
                }
            }; fail()
        }
        var nodes = 0
        fun value(depth: Int) { // Local recursive function retains the lexical numeric spelling.
            if (depth > 16 || ++nodes > 32768) fail()
            ws(); if (i >= source.length) fail()
            when (source[i]) {
                '"' -> { string(); return }
                '{' -> {
                    i++; ws(); if (i < source.length && source[i] == '}') { i++; return }
                    val keys = HashSet<String>()
                    while (true) {
                        if (!keys.add(string())) fail(); ws()
                        if (i >= source.length || source[i++] != ':') fail()
                        value(depth + 1); ws(); if (i >= source.length) fail()
                        when (source[i++]) { '}' -> return; ',' -> {}; else -> fail() }
                    }
                }
                '[' -> {
                    i++; ws(); if (i < source.length && source[i] == ']') { i++; return }
                    while (true) { value(depth + 1); ws(); if (i >= source.length) fail(); when (source[i++]) { ']' -> return; ',' -> {}; else -> fail() } }
                }
            }
            for (literal in listOf("true", "false", "null")) if (source.startsWith(literal, i)) { i += literal.length; return }
            val token = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?").find(source, i)?.takeIf { it.range.first == i }?.value ?: fail()
            i += token.length
            if (!token.toDouble().isFinite() || token.toDouble() == 0.0 && token.startsWith("-") || kotlin.math.abs(token.toDouble()) > MAX_SAFE) fail()
            if (integersOnly && (!token.matches(Regex("0|[1-9][0-9]*")) || token.toLongOrNull()?.let { it <= MAX_SAFE } != true)) fail()
        }
        value(0); ws(); if (i != source.length) fail()
        return try { JSONObject(source) } catch (_: Exception) { fail() }
    }
}
