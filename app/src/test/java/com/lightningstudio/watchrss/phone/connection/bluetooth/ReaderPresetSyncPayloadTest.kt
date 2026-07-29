package com.lightningstudio.watchrss.phone.connection.bluetooth

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files

class ReaderPresetSyncPayloadTest {
    private val directory = Files.createTempDirectory("reader-sync-phone").toFile()
    private val partial = File(directory, "resource.part")
    private val metadata = File(directory, "resource.part.meta")

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun firstChunkReplacesLegacyPartialWithoutMetadata() {
        RandomAccessFile(partial, "rw").use {
            it.setLength(4L * ReaderPresetSyncPayload.CHUNK_BYTES)
        }
        val firstChunk = ByteArray(ReaderPresetSyncPayload.CHUNK_BYTES) { 0x41 }

        ReaderPresetSyncPayload.applyIncomingChunk(
            partial = partial,
            metadata = metadata,
            index = 0,
            chunkCount = 5,
            data = firstChunk,
            totalBytes = 5L * ReaderPresetSyncPayload.CHUNK_BYTES,
            expectedHash = "legacy-retry"
        )

        assertEquals(firstChunk.size.toLong(), partial.length())
        assertArrayEquals(firstChunk, partial.readBytes())
    }

    @Test
    fun repeatedChunksAreIdempotentAndResumeAtNextMissingChunk() {
        val chunks = List(3) { index ->
            ByteArray(ReaderPresetSyncPayload.CHUNK_BYTES) { (index + 1).toByte() }
        }
        val totalBytes = 3L * ReaderPresetSyncPayload.CHUNK_BYTES

        fun apply(index: Int) {
            ReaderPresetSyncPayload.applyIncomingChunk(
                partial = partial,
                metadata = metadata,
                index = index,
                chunkCount = chunks.size,
                data = chunks[index],
                totalBytes = totalBytes,
                expectedHash = "same-transfer"
            )
        }

        apply(0)
        apply(1)
        apply(0)
        apply(1)
        apply(2)

        assertEquals(totalBytes, partial.length())
        RandomAccessFile(partial, "r").use {
            chunks.forEachIndexed { index, expected ->
                val actual = ByteArray(expected.size)
                it.seek(index.toLong() * ReaderPresetSyncPayload.CHUNK_BYTES)
                it.readFully(actual)
                assertArrayEquals(expected, actual)
            }
        }
    }
}
