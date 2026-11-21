package com.lightningstudio.watchrss.phone.connection.bluetooth.b2

import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream

internal interface B2TransferObserver {
    fun bytes(count: Long)
    fun binaryRequest(request: JSONObject, incoming: Boolean)
    fun binaryDescriptor(id: String, kind: String, incoming: Boolean)
    fun chunk(objectId: String, index: Int, incoming: Boolean, length: Int, accepted: Boolean = false)
    fun binaryApplied(request: JSONObject, incoming: Boolean)

    fun chunk(request: JSONObject, incoming: Boolean, length: Int, accepted: Boolean = false) {
        val body = request.optJSONObject("payload") ?: return
        chunk(body.optString("objectId"), body.optInt("chunkIndex", -1), incoming, length, accepted)
    }
}

internal fun B2TransferObserver.counting(input: InputStream): InputStream = object : InputStream() {
    override fun read(): Int = input.read().also { if (it >= 0) bytes(1) }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        input.read(buffer, offset, length).also { if (it > 0) bytes(it.toLong()) }
}

internal fun B2TransferObserver.counting(output: OutputStream): OutputStream = object : OutputStream() {
    override fun write(byte: Int) {
        output.write(byte)
        bytes(1)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        output.write(buffer, offset, length)
        bytes(length.toLong())
    }

    override fun flush() = output.flush()
}
