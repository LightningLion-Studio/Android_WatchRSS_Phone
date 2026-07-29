package com.lightningstudio.watchrss.phone.connection.bluetooth

import com.lightningstudio.watchrss.phone.data.reader.ReaderPreset
import com.lightningstudio.watchrss.phone.data.reader.ReaderPresetCodec
import org.json.JSONObject

object ReaderPresetPreviewPayload {
    const val VERSION = 1
    const val PHASE_UPDATE = "update"
    const val PHASE_STOP = "stop"

    fun update(sessionId: String, sequence: Long, preset: ReaderPreset): JSONObject =
        JSONObject().apply {
            put("version", VERSION)
            put("action", BluetoothSyncProtocol.ACTION_PREVIEW_READER)
            put("phase", PHASE_UPDATE)
            put("sessionId", sessionId)
            put("sequence", sequence)
            put("presetJson", ReaderPresetCodec.encode(preset.normalized()))
        }

    fun streamStart(sessionId: String, sequence: Long, preset: ReaderPreset): JSONObject =
        update(sessionId, sequence, preset).apply {
            put("stream", true)
        }

    fun stop(sessionId: String): JSONObject = JSONObject().apply {
        put("version", VERSION)
        put("action", BluetoothSyncProtocol.ACTION_PREVIEW_READER)
        put("phase", PHASE_STOP)
        put("sessionId", sessionId)
    }
}
