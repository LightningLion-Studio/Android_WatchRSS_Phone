package com.lightningstudio.watchrss.phone.connection.bluetooth.b2

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** A locally constructed immutable range. Neither path nor offset comes from the wire. */
class B2FileRef internal constructor(internal val file: File, private val offset: Long, val size: Long) {
    internal fun readBlock(buffer: ByteArray, expectedHash: String, work: () -> Unit): ByteArray {
        require(size in 1..buffer.size.toLong())
        try {
            RandomAccessFile(file, "r").use {
                it.seek(offset)
                it.readFully(buffer, 0, size.toInt())
            }
        } catch (_: java.io.EOFException) {
            fail("SOURCE_CHANGED")
        } catch (e: IOException) {
            if (!file.isFile) fail("SOURCE_CHANGED")
            fail("LOCAL_IO")
        }
        if (B2Frames.hex(java.security.MessageDigest.getInstance("SHA-256").apply { update(buffer, 0, size.toInt()) }.digest()) != expectedHash) fail("SOURCE_CHANGED")
        work()
        return buffer
    }
}
