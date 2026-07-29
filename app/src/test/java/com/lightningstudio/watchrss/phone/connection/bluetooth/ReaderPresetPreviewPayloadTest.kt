package com.lightningstudio.watchrss.phone.connection.bluetooth

import com.lightningstudio.watchrss.phone.data.reader.ReaderPreset
import com.lightningstudio.watchrss.phone.data.reader.ReaderPresetCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ReaderPresetPreviewPayloadTest {
    @Test
    fun updateContainsTemporaryPresetWithoutActiveSelection() {
        val request = ReaderPresetPreviewPayload.update(
            sessionId = "preview-session",
            sequence = 7L,
            preset = ReaderPreset.lightDefault(id = "draft", name = "实时草稿")
        )

        assertEquals(BluetoothSyncProtocol.ACTION_PREVIEW_READER, request.getString("action"))
        assertEquals(ReaderPresetPreviewPayload.PHASE_UPDATE, request.getString("phase"))
        assertEquals(7L, request.getLong("sequence"))
        assertEquals(
            "实时草稿",
            ReaderPresetCodec.decode(request.getString("presetJson")).name
        )
        assertFalse(request.toString().contains("activePresetId"))
    }

    @Test
    fun stopOnlyIdentifiesPreviewSession() {
        val request = ReaderPresetPreviewPayload.stop("preview-session")

        assertEquals(ReaderPresetPreviewPayload.PHASE_STOP, request.getString("phase"))
        assertEquals("preview-session", request.getString("sessionId"))
        assertFalse(request.has("presetJson"))
    }

    @Test
    fun streamStartNegotiatesPersistentPreviewWithoutChangingPresetPayload() {
        val request = ReaderPresetPreviewPayload.streamStart(
            sessionId = "preview-stream",
            sequence = 0L,
            preset = ReaderPreset.darkDefault(id = "draft", name = "深色预览")
        )

        assertEquals(true, request.getBoolean("stream"))
        assertEquals(ReaderPresetPreviewPayload.PHASE_UPDATE, request.getString("phase"))
        assertEquals(
            "深色预览",
            ReaderPresetCodec.decode(request.getString("presetJson")).name
        )
        assertFalse(request.toString().contains("activePresetId"))
    }

    @Test
    fun resourceTransferMarksOnlyTheTemporaryPreviewFrame() {
        val request = ReaderPresetPreviewPayload.resourceTransfer(
            sessionId = "preview-resource",
            sequence = 0L,
            preset = ReaderPreset.darkDefault(id = "draft", name = "资源预览")
        )

        assertEquals(true, request.getBoolean("resourceTransfer"))
        assertEquals(ReaderPresetPreviewPayload.PHASE_UPDATE, request.getString("phase"))
        assertFalse(request.toString().contains("activePresetId"))
    }
}
