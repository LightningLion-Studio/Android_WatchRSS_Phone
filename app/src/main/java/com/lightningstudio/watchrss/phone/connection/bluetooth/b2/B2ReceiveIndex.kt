package com.lightningstudio.watchrss.phone.connection.bluetooth.b2

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** Immutable, disposable view of the committed manifest. Header validation reads
 * one bounded record without acquiring the Room writer or retaining all hashes. */
internal class B2ReceiveIndex private constructor(
    private val file: File,
    private val objectIds: List<String>,
    private val offsets: LongArray
) : AutoCloseable {
    data class Chunk(val objectId: String, val hash: String, val dependencies: List<String>)

    fun chunk(slot: Int, index: Int, length: Int): Chunk {
        val oid = objectIds.getOrNull(slot) ?: fail()
        try {
            return RandomAccessFile(file, "r").use { input ->
                input.seek(offsets[slot])
                val size = input.readLong()
                val chunks = input.readUnsignedShort()
                val dependencies = input.readUnsignedByte()
                if (index >= chunks || length.toLong() != minOf(B2Wire.CHUNK.toLong(), size - index * B2Wire.CHUNK.toLong())) fail()
                val scratch = ByteArray(32)
                input.seek(input.filePointer + index * 32)
                input.readFully(scratch)
                val hash = B2Frames.hex(scratch)
                input.seek(offsets[slot] + 11 + chunks * 32)
                val deps = List(dependencies) {
                    input.readFully(scratch)
                    B2Frames.hex(scratch)
                }
                Chunk(oid, hash, deps)
            }
        } catch (_: IOException) { fail("LOCAL_IO") }
    }

    override fun close() { file.delete() }

    companion object {
        fun cost(d: JSONObject): Long = 11L + 32L * (d.a("chunkHashes").length() + d.a("dependsOn").length())

        fun create(file: File, descriptors: List<JSONObject>, work: () -> Unit): B2ReceiveIndex {
            val offsets = LongArray(descriptors.size)
            try {
                RandomAccessFile(file, "rw").use { out ->
                    descriptors.forEachIndexed { slot, d ->
                        offsets[slot] = out.filePointer
                        val hashes = d.a("chunkHashes")
                        val deps = d.a("dependsOn")
                        out.writeLong(d.n("size"))
                        out.writeShort(hashes.length())
                        out.writeByte(deps.length())
                        for (i in 0 until hashes.length()) out.write(B2Frames.unhex(hashes.getString(i), 32))
                        for (i in 0 until deps.length()) out.write(B2Frames.unhex(deps.getString(i), 32))
                        work()
                    }
                }
                return B2ReceiveIndex(file, descriptors.map { it.s("objectId") }, offsets)
            } catch (e: Throwable) {
                file.delete()
                if (e is IOException) fail("LOCAL_IO")
                throw e
            }
        }
    }
}
