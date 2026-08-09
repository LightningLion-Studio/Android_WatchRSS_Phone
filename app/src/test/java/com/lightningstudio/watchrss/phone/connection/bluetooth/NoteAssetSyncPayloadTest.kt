package com.lightningstudio.watchrss.phone.connection.bluetooth

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class NoteAssetSyncPayloadTest {
    @Test
    fun referencedStorageKeysFollowRenderedMarkdownImages() {
        val markdown = """
            ![first](assets/current.jpg)
            ![remote](https://example.com/remote.jpg)
            ```markdown
            ![code](assets/not-rendered.jpg)
            ```
            ![unsafe](assets/../escape.jpg)
            ![duplicate](assets/current.jpg)
        """.trimIndent()

        assertEquals(
            linkedSetOf("current.jpg"),
            NoteAssetSyncPayload.referencedStorageKeys(markdown)
        )
    }

    @Test
    fun referencedStorageKeysFollowRenderedHtmlImages() {
        val html = """
            <p>Before</p>
            <img src="assets/legacy.png" alt="legacy">
            <img src="https://example.com/remote.jpg" alt="remote">
        """.trimIndent()

        assertEquals(
            linkedSetOf("legacy.png"),
            NoteAssetSyncPayload.referencedStorageKeys(html)
        )
    }

    @Test
    fun maximumChunkFitsRfcommFrameAndRoundTrips() {
        val bytes = ByteArray(NoteAssetSyncPayload.CHUNK_BYTES) { (it % 251).toByte() }

        val payload = NoteAssetSyncPayload.chunk(
            storageKey = "example.jpg",
            sha256 = "a".repeat(64),
            chunkIndex = 0,
            chunkCount = 1,
            bytes = bytes
        )

        assertTrue(BluetoothSyncProtocol.encodedSize(payload) < BluetoothSyncProtocol.MAX_FRAME_BYTES)
        assertArrayEquals(bytes, Base64.getDecoder().decode(payload.getString("data")))
    }
}
