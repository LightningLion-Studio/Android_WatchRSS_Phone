package com.lightningstudio.watchrss.phone.connection.bluetooth

import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
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
    const val ACTION_SYNC_READER = "syncReader"
    const val ACTION_PREVIEW_READER = "previewReader"
    const val ACTION_SYNC_ACCOUNT = "syncAccount"
    const val ACTION_SYNC_LLM_TOKEN_USAGE = "syncLlmTokenUsage"
    const val ACTION_SYNC_NOTE_ASSET = "syncNoteAsset"
    const val ACTION_SYNC_SESSION = "syncSession"
    const val ACTION_ACK = "ack"

    const val SESSION_PHASE_COMPLETE = "complete"
    const val SESSION_PHASE_ABORT = "abort"
    const val FIELD_REQUEST_PERSISTENT_SESSION = "requestPersistentSession"
    const val FIELD_SUPPORTS_PERSISTENT_SESSION = "supportsPersistentSession"
    const val FIELD_PERSISTENT_SESSION_ACCEPTED = "persistentSessionAccepted"

    const val ACK_PHASE_RECEIVED = "received"
    const val ACK_PHASE_APPLIED = "applied"

    const val MAX_FRAME_BYTES = 2 * 1024 * 1024
    const val LENGTH_PREFIX_BYTES = 4
    const val PERSISTENT_SESSION_IDLE_TIMEOUT_MS = 15 * 60 * 1_000L

    fun readFrame(
        input: InputStream,
        onBytesTransferred: ((Long) -> Unit)? = null
    ): JSONObject {
        val dataInput = DataInputStream(input)
        val length = dataInput.readInt()
        onBytesTransferred?.invoke(LENGTH_PREFIX_BYTES.toLong())
        require(length in 1..MAX_FRAME_BYTES) { "同步消息长度异常：$length" }
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = dataInput.read(bytes, offset, minOf(TRANSFER_CHUNK_BYTES, length - offset))
            if (read < 0) throw EOFException("同步消息读取中断：$offset/$length")
            offset += read
            onBytesTransferred?.invoke(read.toLong())
        }
        return JSONObject(bytes.toString(Charsets.UTF_8))
    }

    fun writeFrame(
        output: OutputStream,
        payload: JSONObject,
        onBytesTransferred: ((Long) -> Unit)? = null
    ) {
        val bytes = encodeFrame(payload)
        require(bytes.size <= MAX_FRAME_BYTES) { "同步消息过大：${bytes.size}" }
        val dataOutput = DataOutputStream(output)
        dataOutput.writeInt(bytes.size)
        onBytesTransferred?.invoke(LENGTH_PREFIX_BYTES.toLong())
        var offset = 0
        while (offset < bytes.size) {
            val count = minOf(TRANSFER_CHUNK_BYTES, bytes.size - offset)
            dataOutput.write(bytes, offset, count)
            offset += count
            onBytesTransferred?.invoke(count.toLong())
        }
        dataOutput.flush()
    }

    fun encodedSize(payload: JSONObject): Int = encodeFrame(payload).size

    fun wireSize(payload: JSONObject): Long =
        encodedSize(payload).toLong() + LENGTH_PREFIX_BYTES.toLong()

    fun withPersistentSessionRequest(payload: JSONObject): JSONObject =
        JSONObject(payload.toString()).apply {
            put(FIELD_REQUEST_PERSISTENT_SESSION, true)
            put(FIELD_SUPPORTS_PERSISTENT_SESSION, true)
        }

    fun requestsPersistentSession(payload: JSONObject): Boolean =
        payload.optBoolean(FIELD_REQUEST_PERSISTENT_SESSION, false) &&
            payload.optBoolean(FIELD_SUPPORTS_PERSISTENT_SESSION, false)

    fun acceptsPersistentSession(payload: JSONObject): Boolean =
        payload.optBoolean(FIELD_SUPPORTS_PERSISTENT_SESSION, false) &&
            payload.optBoolean(FIELD_PERSISTENT_SESSION_ACCEPTED, false)

    fun buildSessionControlRequest(version: Int, phase: String): JSONObject =
        JSONObject().apply {
            put("version", version)
            put("action", ACTION_SYNC_SESSION)
            put("phase", requireValidSessionPhase(phase))
        }

    fun buildSessionControlResponse(version: Int, phase: String): JSONObject =
        buildSessionControlRequest(version, phase).apply {
            put("success", true)
            put(FIELD_SUPPORTS_PERSISTENT_SESSION, true)
            put(FIELD_PERSISTENT_SESSION_ACCEPTED, true)
        }

    fun sessionControlPhase(payload: JSONObject): String? {
        if (payload.optString("action") != ACTION_SYNC_SESSION) return null
        return requireValidSessionPhase(payload.optString("phase"))
    }

    fun requireValidSessionPhase(phase: String): String {
        require(phase == SESSION_PHASE_COMPLETE || phase == SESSION_PHASE_ABORT) {
            "未知同步会话结束阶段：$phase"
        }
        return phase
    }

    private fun encodeFrame(payload: JSONObject): ByteArray =
        payload.toString().toByteArray(Charsets.UTF_8)

    private const val TRANSFER_CHUNK_BYTES = 16 * 1024
}
