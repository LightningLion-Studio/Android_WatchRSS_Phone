package com.lightningstudio.watchrss.phone.cloud

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Content-defined boundaries survive insertions. The wire format still concatenates chunks. */
internal class CloudContentChunks(private val input: InputStream) {
    private val buffer = ByteArray(32 * 1024)
    private var position = 0
    private var available = 0

    fun next(): ByteArray? {
        if (available < 0) return null
        val output = ByteArrayOutputStream(AVERAGE_BYTES)
        var hash = 0L
        while (true) {
            if (position == available) {
                available = input.read(buffer)
                position = 0
                if (available < 0) return if (output.size() == 0) null else output.toByteArray()
                if (available == 0) continue
            }
            val start = position
            while (position < available) {
                hash = (hash shl 1) + GEAR[buffer[position].toInt() and 255]
                position++
                val size = output.size() + position - start
                if (size >= MIN_BYTES && ((hash and (AVERAGE_BYTES - 1).toLong()) == 0L || size >= MAX_BYTES)) {
                    output.write(buffer, start, position - start)
                    return output.toByteArray()
                }
            }
            output.write(buffer, start, position - start)
        }
    }

    companion object {
        const val MIN_BYTES = 64 * 1024
        const val AVERAGE_BYTES = 256 * 1024
        const val MAX_BYTES = 1024 * 1024
        // Fixed SplitMix64 table; boundaries are independent of process, account and platform.
        private val GEAR = LongArray(256) { index ->
            var value = index.toLong() - 7046029254386353131L
            value = (value xor (value ushr 30)) * -4658895280553007687L
            value = (value xor (value ushr 27)) * -7723592293110705685L
            value xor (value ushr 31)
        }
    }
}
