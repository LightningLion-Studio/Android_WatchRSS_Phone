package com.lightningstudio.watchrss.phone.connection.bluetooth

import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.B2Json
import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.fail
import org.json.JSONArray
import org.json.JSONObject
import java.io.Reader
import kotlin.math.abs

/** Parse from a file without retaining the raw object alongside the decoded values. */
internal class B2BookJson(
    private val input: Reader,
    private val integersOnly: Boolean,
    private val maxNodes: Int,
    private val work: () -> Unit
) {
    private val buffer = CharArray(8192)
    private var at = 0
    private var end = 0
    private var nodes = 0

    private fun peek(): Int {
        if (at == end) {
            end = input.read(buffer)
            at = 0
            if (end > 0) work()
        }
        return if (end < 0) -1 else buffer[at].code
    }

    private fun take(): Char {
        val char = peek()
        if (char < 0) fail("BUSINESS_REJECTED")
        at++
        return char.toChar()
    }

    private fun whitespace(): Int {
        while (peek() in WHITESPACE) at++
        return peek()
    }

    private fun expect(char: Char) {
        if (whitespace() != char.code) fail("BUSINESS_REJECTED")
        at++
    }

    fun state(): JSONObject = (value(0) as? JSONObject ?: fail("BUSINESS_REJECTED")).also { finish() }

    fun readings(): List<JSONObject> {
        expect('[')
        val rows = mutableListOf<JSONObject>()
        while (true) {
            if (rows.size == 100) fail("BUSINESS_REJECTED")
            rows += value(2) as? JSONObject ?: fail("BUSINESS_REJECTED")
            when (whitespace()) {
                ']'.code -> {
                    at++
                    finish()
                    return rows
                }
                ','.code -> at++
                else -> fail("BUSINESS_REJECTED")
            }
        }
    }

    private fun finish() {
        if (whitespace() != -1) fail("BUSINESS_REJECTED")
    }

    private fun value(depth: Int): Any {
        if (depth > 16 || ++nodes > maxNodes) fail("BUSINESS_REJECTED")
        return when (whitespace()) {
            '{'.code -> {
                at++
                val objectValue = JSONObject()
                if (whitespace() != '}'.code) {
                    while (true) {
                        val name = string()
                        if (objectValue.has(name)) fail("BUSINESS_REJECTED")
                        expect(':')
                        objectValue.put(name, value(depth + 1))
                        if (whitespace() != ','.code) break
                        at++
                    }
                }
                expect('}')
                objectValue
            }
            '['.code -> {
                at++
                val array = JSONArray()
                if (whitespace() != ']'.code) {
                    while (true) {
                        array.put(value(depth + 1))
                        if (whitespace() != ','.code) break
                        at++
                    }
                }
                expect(']')
                array
            }
            '"'.code -> string()
            't'.code -> literal("true", true)
            'f'.code -> literal("false", false)
            'n'.code -> literal("null", JSONObject.NULL)
            else -> number()
        }
    }

    private fun literal(spelling: String, value: Any): Any {
        for (char in spelling) if (take() != char) fail("BUSINESS_REJECTED")
        return value
    }

    private fun number(): Number {
        val token = buildString {
            while (peek() >= 0 && peek().toChar() in "-+0123456789.eE") append(take())
        }
        if (!NUMBER.matches(token)) fail("BUSINESS_REJECTED")
        val integer = token.toLongOrNull()
        val decimal = token.toDoubleOrNull() ?: fail("BUSINESS_REJECTED")
        if (!decimal.isFinite() || abs(decimal) > B2Json.MAX_SAFE || decimal == 0.0 && token.startsWith('-') ||
            integersOnly && (integer == null || integer < 0)) fail("BUSINESS_REJECTED")
        return if (integer == null) decimal else if (integer in Int.MIN_VALUE..Int.MAX_VALUE) integer.toInt() else integer
    }

    private fun string(): String {
        expect('"')
        return buildString {
            var pendingSurrogate = false
            while (true) {
                val raw = take()
                if (raw == '"') break
                if (raw < ' ') fail("BUSINESS_REJECTED")
                val char = if (raw == '\\') escaped() else raw
                if (pendingSurrogate != char.isLowSurrogate()) fail("BUSINESS_REJECTED")
                pendingSurrogate = char.isHighSurrogate()
                append(char)
            }
            if (pendingSurrogate) fail("BUSINESS_REJECTED")
        }
    }

    private fun escaped(): Char = when (val char = take()) {
        '"', '\\', '/' -> char
        'b' -> '\b'
        'f' -> '\u000c'
        'n' -> '\n'
        'r' -> '\r'
        't' -> '\t'
        'u' -> {
            var value = 0
            repeat(4) {
                val digit = take()
                if (digit !in '0'..'9' && digit !in 'a'..'f' && digit !in 'A'..'F') fail("BUSINESS_REJECTED")
                value = value * 16 + digit.digitToInt(16)
            }
            value.toChar()
        }
        else -> fail("BUSINESS_REJECTED")
    }

    private companion object {
        val WHITESPACE = setOf(' '.code, '\t'.code, '\r'.code, '\n'.code)
        val NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
    }
}
