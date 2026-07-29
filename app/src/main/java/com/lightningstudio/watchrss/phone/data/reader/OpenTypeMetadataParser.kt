package com.lightningstudio.watchrss.phone.data.reader

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset

internal object OpenTypeMetadataParser {
    fun parse(file: File): JSONObject = RandomAccessFile(file, "r").use { input ->
        val faceOffsets = if (input.readTag(0) == "ttcf") {
            val count = input.u32(8).coerceIn(1, 128)
            List(count) { input.u32(12L + it * 4L).toLong() }
        } else {
            listOf(0L)
        }
        JSONObject().apply {
            put("faceCount", faceOffsets.size)
            put("faces", JSONArray().apply {
                faceOffsets.forEachIndexed { index, offset ->
                    put(parseFace(input, index, offset))
                }
            })
        }
    }

    private fun parseFace(input: RandomAccessFile, index: Int, offset: Long): JSONObject {
        val tableCount = input.u16(offset + 4).coerceIn(0, 256)
        val tables = buildMap<String, Table> {
            repeat(tableCount) { tableIndex ->
                val record = offset + 12 + tableIndex * 16L
                val tag = input.readTag(record)
                val tableOffset = input.u32(record + 8).toLong()
                val length = input.u32(record + 12).toLong()
                if (tag.length == 4 && tableOffset >= 0 && length >= 0 &&
                    tableOffset + length <= input.length()
                ) {
                    put(tag, Table(tableOffset, length))
                }
            }
        }
        val names = tables["name"]?.let { readNames(input, it) }.orEmpty()
        val weight = tables["OS/2"]
            ?.takeIf { it.length >= 6 }
            ?.let { input.u16(it.offset + 4).coerceIn(1, 1000) }
            ?: 400
        val macStyle = tables["head"]
            ?.takeIf { it.length >= 46 }
            ?.let { input.u16(it.offset + 44) }
            ?: 0
        return JSONObject().apply {
            put("index", index)
            put("family", names[1].orEmpty())
            put("subfamily", names[2].orEmpty())
            put("fullName", names[4].orEmpty())
            put("postScriptName", names[6].orEmpty())
            put("weight", weight)
            put("bold", macStyle and 1 != 0 || weight >= 700)
            put("italic", macStyle and 2 != 0)
            put("axes", tables["fvar"]?.let { readAxes(input, it, names) } ?: JSONArray())
        }
    }

    private fun readNames(input: RandomAccessFile, table: Table): Map<Int, String> {
        if (table.length < 6) return emptyMap()
        val count = input.u16(table.offset + 2).coerceIn(0, 4096)
        val storage = table.offset + input.u16(table.offset + 4)
        val names = linkedMapOf<Int, String>()
        repeat(count) { index ->
            val record = table.offset + 6 + index * 12L
            if (record + 12 > table.offset + table.length) return@repeat
            val platform = input.u16(record)
            val language = input.u16(record + 4)
            val nameId = input.u16(record + 6)
            val length = input.u16(record + 8)
            val relativeOffset = input.u16(record + 10)
            val position = storage + relativeOffset
            if (position + length > table.offset + table.length) return@repeat
            val preferred = platform == 3 && (language == 0x0409 || language == 0x0804)
            if (nameId !in names || preferred) {
                val bytes = input.bytes(position, length)
                val text = runCatching {
                    if (platform == 0 || platform == 3) {
                        bytes.toString(Charset.forName("UTF-16BE"))
                    } else {
                        bytes.toString(Charsets.ISO_8859_1)
                    }
                }.getOrDefault("").trim('\u0000', ' ')
                if (text.isNotBlank()) names[nameId] = text
            }
        }
        return names
    }

    private fun readAxes(
        input: RandomAccessFile,
        table: Table,
        names: Map<Int, String>
    ): JSONArray {
        if (table.length < 16) return JSONArray()
        val axesOffset = input.u16(table.offset + 4)
        val axisCount = input.u16(table.offset + 8).coerceIn(0, 64)
        val axisSize = input.u16(table.offset + 10)
        if (axisSize < 20) return JSONArray()
        return JSONArray().apply {
            repeat(axisCount) { index ->
                val axis = table.offset + axesOffset + index * axisSize.toLong()
                if (axis + 20 > table.offset + table.length) return@repeat
                val nameId = input.u16(axis + 18)
                put(JSONObject().apply {
                    put("tag", input.readTag(axis))
                    put("minimum", input.fixed(axis + 4))
                    put("default", input.fixed(axis + 8))
                    put("maximum", input.fixed(axis + 12))
                    put("hidden", input.u16(axis + 16) and 1 != 0)
                    put("name", names[nameId].orEmpty())
                })
            }
        }
    }

    private fun RandomAccessFile.u16(offset: Long): Int {
        seek(offset)
        return readUnsignedShort()
    }

    private fun RandomAccessFile.u32(offset: Long): Int {
        seek(offset)
        return readInt()
    }

    private fun RandomAccessFile.fixed(offset: Long): Double =
        u32(offset).toDouble() / 65536.0

    private fun RandomAccessFile.readTag(offset: Long): String =
        bytes(offset, 4).toString(Charsets.US_ASCII)

    private fun RandomAccessFile.bytes(offset: Long, count: Int): ByteArray {
        require(offset >= 0 && count >= 0 && offset + count <= length()) { "字体表越界" }
        seek(offset)
        return ByteArray(count).also(::readFully)
    }

    private data class Table(val offset: Long, val length: Long)
}
