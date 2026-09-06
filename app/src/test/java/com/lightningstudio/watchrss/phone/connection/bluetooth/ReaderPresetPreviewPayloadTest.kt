package com.lightningstudio.watchrss.phone.connection.bluetooth

import com.lightningstudio.watchrss.phone.data.reader.ReaderPreset
import com.lightningstudio.watchrss.phone.data.reader.ReaderPresetCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPresetPreviewPayloadTest {
    @Test
    fun positionAndZoomUseDeltasWithoutChangingResourceIdentity() {
        val before = ReaderPreset.lightDefault(id = "draft", name = "background").let {
            it.copy(background = it.background.copy(
                type = com.lightningstudio.watchrss.phone.data.reader.ReaderBackgroundType.VIDEO,
                assetId = "full-frame-video"))
        }
        val after = before.copy(background = before.background.copy(
            zoom = 3f, focusX = 0.1f, focusY = 0.9f, rotationDegrees = 25f,
            brightness = 1.2f, saturation = 0.8f))
        assertEquals(before.previewResourceSignature(), after.previewResourceSignature())
        val frame = ReaderPresetPreviewPayload.delta("preview", 2, before, after)
        assertEquals(ReaderPresetPreviewPayload.PHASE_UPDATE, frame.getString("phase"))
        assertTrue(frame.getJSONObject("changes").has("background"))
        assertFalse(frame.optBoolean("resourceTransfer"))
        assertFalse(before.previewResourceSignature() == after.copy(
            background = after.background.copy(assetId = "different-video")).previewResourceSignature())
    }

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
            ReaderPresetCodec.decode(request.getJSONObject("preset").toString()).name
        )
        assertFalse(request.has("presetJson"))
        assertFalse(request.toString().contains("activePresetId"))
    }

    @Test
    fun stopOnlyIdentifiesPreviewSession() {
        val request = ReaderPresetPreviewPayload.stop("preview-session")

        assertEquals(ReaderPresetPreviewPayload.PHASE_STOP, request.getString("phase"))
        assertEquals("preview-session", request.getString("sessionId"))
        assertFalse(request.has("preset"))
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
            ReaderPresetCodec.decode(request.getJSONObject("preset").toString()).name
        )
        assertFalse(request.toString().contains("activePresetId"))
    }

    @Test
    fun deltaSendsOnlyChangedPresetSections() {
        val before = ReaderPreset.lightDefault(id = "draft", name = "实时草稿")
        val after = before.copy(body = before.body.copy(fontSizeSp = 26f))

        val request = ReaderPresetPreviewPayload.delta(
            sessionId = "preview-stream",
            sequence = 8L,
            previous = before,
            current = after
        )

        val changes = request.getJSONObject("changes")
        assertEquals(1, changes.length())
        assertEquals(26.0, changes.getJSONObject("body").getDouble("fontSizeSp"), 0.0)
        assertFalse(request.has("preset"))
        assertTrue(
            BluetoothSyncProtocol.encodedSize(request) <
                BluetoothSyncProtocol.encodedSize(
                    ReaderPresetPreviewPayload.update("preview-stream", 8L, after)
                )
        )
    }

    @Test
    fun heartbeatDoesNotRepeatPresetPayload() {
        val request = ReaderPresetPreviewPayload.heartbeat("preview-stream", 9L)

        assertEquals(ReaderPresetPreviewPayload.PHASE_HEARTBEAT, request.getString("phase"))
        assertEquals(9L, request.getLong("sequence"))
        assertFalse(request.has("preset"))
        assertFalse(request.has("changes"))
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

    @Test
    fun resourceHandoffKeepsPresetInMemoryWhileEndingOnlyTheStream() {
        val request = ReaderPresetPreviewPayload.resourceHandoff(
            sessionId = "preview-resource",
            sequence = 11L,
            preset = ReaderPreset.lightDefault(id = "draft", name = "新字体")
        )

        assertEquals(
            ReaderPresetPreviewPayload.PHASE_RESOURCE_HANDOFF,
            request.getString("phase")
        )
        assertEquals(11L, request.getLong("sequence"))
        assertTrue(request.getBoolean("resourceTransfer"))
        assertEquals(
            "新字体",
            ReaderPresetCodec.decode(request.getJSONObject("preset").toString()).name
        )
        assertFalse(request.toString().contains("activePresetId"))
    }
}
