package com.lightningstudio.watchrss.phone.connection.bluetooth

import java.io.IOException
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/** Article wire member only. Keep general cloud/archive gzip behavior independent. */
internal class ArticleBodyGzipOutputStream(output: OutputStream, os: Int = 255) :
    DeflaterOutputStream(output, Deflater(6, true), 8192) {
    private val crc = CRC32()
    private var finished = false
    private var closed = false

    init {
        require(os in setOf(0, 3, 255))
        // RFC 1952: deflate, no optional fields, mtime=0, XFL=0, OS=unknown.
        // Legacy OS values are emitted only while verifying an existing complete digest.
        output.write(byteArrayOf(0x1f, 0x8b.toByte(), 8, 0, 0, 0, 0, 0, 0, os.toByte()))
    }

    override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        if (finished) throw IOException("article gzip already finished")
        super.write(bytes, offset, length)
        crc.update(bytes, offset, length)
    }

    override fun finish() {
        if (finished) return
        super.finish()
        val size = def.bytesRead
        out.write(ByteArray(8) { index ->
            val value = if (index < 4) crc.value else size
            (value ushr ((index % 4) * 8)).toByte()
        })
        finished = true
    }

    override fun close() {
        if (closed) return
        closed = true
        try { super.close() } finally { def.end() }
    }
}
