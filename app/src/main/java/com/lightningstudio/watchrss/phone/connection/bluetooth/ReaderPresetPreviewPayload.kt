package com.lightningstudio.watchrss.phone.connection.bluetooth

import com.lightningstudio.watchrss.phone.data.reader.ReaderPreset
import com.lightningstudio.watchrss.phone.data.reader.ReaderPresetCodec
import com.lightningstudio.watchrss.phone.data.reader.ReaderTypographyRole
import org.json.JSONObject

object ReaderPresetPreviewPayload {
    const val VERSION = 2
    const val PHASE_UPDATE = "update"
    const val PHASE_RESOURCE_HANDOFF = "resourceHandoff"
    const val PHASE_HEARTBEAT = "heartbeat"
    const val PHASE_STOP = "stop"

    fun update(sessionId: String, sequence: Long, preset: ReaderPreset): JSONObject =
        baseFrame(sessionId, PHASE_UPDATE).apply {
            put("sequence", sequence)
            put("preset", preset.toPreviewJson())
        }

    fun streamStart(sessionId: String, sequence: Long, preset: ReaderPreset): JSONObject =
        update(sessionId, sequence, preset).apply {
            put("stream", true)
        }

    fun delta(
        sessionId: String,
        sequence: Long,
        previous: ReaderPreset,
        current: ReaderPreset
    ): JSONObject {
        val before = previous.toPreviewJson()
        val after = current.toPreviewJson()
        val changes = JSONObject()
        after.keys().forEach { key ->
            val oldValue = before.opt(key)
            val newValue = after.opt(key)
            if (!jsonValuesEqual(oldValue, newValue)) {
                changes.put(key, newValue)
            }
        }
        return baseFrame(sessionId, PHASE_UPDATE).apply {
            put("sequence", sequence)
            put("changes", changes)
        }
    }

    fun heartbeat(sessionId: String, sequence: Long): JSONObject =
        baseFrame(sessionId, PHASE_HEARTBEAT).apply {
            put("sequence", sequence)
        }

    fun resourceTransfer(
        sessionId: String,
        sequence: Long,
        preset: ReaderPreset
    ): JSONObject = update(sessionId, sequence, preset).apply {
        put("resourceTransfer", true)
    }

    fun resourceHandoff(
        sessionId: String,
        sequence: Long,
        preset: ReaderPreset
    ): JSONObject = baseFrame(sessionId, PHASE_RESOURCE_HANDOFF).apply {
        put("sequence", sequence)
        put("preset", preset.toPreviewJson())
        put("resourceTransfer", true)
    }

    fun stop(sessionId: String): JSONObject = baseFrame(sessionId, PHASE_STOP)

    private fun baseFrame(sessionId: String, phase: String): JSONObject = JSONObject().apply {
        put("version", VERSION)
        put("action", BluetoothSyncProtocol.ACTION_PREVIEW_READER)
        put("phase", phase)
        put("sessionId", sessionId)
    }

    private fun ReaderPreset.toPreviewJson(): JSONObject =
        JSONObject(ReaderPresetCodec.encode(normalized()))

    private fun jsonValuesEqual(left: Any?, right: Any?): Boolean = when {
        left === right -> true
        left == null || right == null -> false
        left == JSONObject.NULL || right == JSONObject.NULL -> left == right
        left is JSONObject && right is JSONObject -> left.toString() == right.toString()
        else -> left == right
    }
}

internal fun ReaderPreset.previewResourceSignature(): String {
    val fontIds = buildSet {
        body.fontAssetId?.let(::add)
        ReaderTypographyRole.entries.forEach { role ->
            resolvedStyle(role).fontAssetId?.let(::add)
        }
    }.sorted().joinToString("|")
    return listOf(
        fontIds,
        background.type.name,
        background.assetId.orEmpty(),
        background.posterAssetId.orEmpty()
    ).joinToString(":")
}
