package com.lightningstudio.watchrss.phone.connection.bluetooth

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

object BluetoothSyncProtocol {
    const val SERVICE_NAME = "WatchRSS Bluetooth Sync"
    val SERVICE_UUID: UUID = UUID.fromString("bb2f0b7d-8f2f-4a96-95b2-d2c91d58a861")

    const val ACTION_PING = "ping"
    const val ACTION_REMOTE_INPUT = "remoteInput"
    const val ACTION_PULL_SAVED_ITEMS = "pullSavedItems"
    const val ACTION_SYNC_LIBRARY = "syncLibrary"
    const val ACTION_ACK = "ack"

    private const val MAX_FRAME_BYTES = 2 * 1024 * 1024

    fun readFrame(input: InputStream): JSONObject {
        val dataInput = DataInputStream(BufferedInputStream(input))
        val length = dataInput.readInt()
        require(length in 1..MAX_FRAME_BYTES) { "蓝牙消息长度异常：$length" }
        val bytes = ByteArray(length)
        dataInput.readFully(bytes)
        return JSONObject(bytes.toString(Charsets.UTF_8))
    }

    fun writeFrame(output: OutputStream, payload: JSONObject) {
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_FRAME_BYTES) { "蓝牙消息过大：${bytes.size}" }
        val dataOutput = DataOutputStream(BufferedOutputStream(output))
        dataOutput.writeInt(bytes.size)
        dataOutput.write(bytes)
        dataOutput.flush()
    }
}
