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
}
