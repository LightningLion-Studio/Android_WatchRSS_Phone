package com.lightningstudio.watchrss.phone.cloud

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.Random

class CloudIncrementalUploadTest {
    private val key = ByteArray(32) { (it + 1).toByte() }
    private val codec = CloudSnapshotCodec()
    private fun source() = ByteArray(8 * 1024 * 1024).also { Random(42).nextBytes(it) }
    private fun objectOf(bytes: ByteArray) = CloudLogicalObject("library-sync.json", bytes, incrementalChunks = true)

    @Test fun insertionDoesNotReuploadUnchangedRemainderAndOldDecoderRestoresAllBytes() {
        val original = source()
        val first = codec.create(key, 1, "phone", 1, listOf(objectOf(original)))
        val insertion = "新增的一段内容\n".toByteArray()
        val changed = original.copyOfRange(0, 700_000) + insertion + original.copyOfRange(700_000, original.size)
        val second = codec.create(key, 1, "phone", 2, listOf(objectOf(changed)), previousManifest = first.manifest)
        val transferred = second.newCiphertextChunks.values.sumOf { it.size.toLong() }
        println("incremental fixture: original=${original.size}, insertion=${insertion.size}, newCiphertextBytes=$transferred")
        assertTrue("insertion reuploaded $transferred bytes", transferred < 2 * 1024 * 1024)
        val oldHashes = first.manifest.allChunks.map { it.ciphertextSha256 }.toSet()
        val reused = second.manifest.allChunks.filter { it.ciphertextSha256 in oldHashes }.sumOf { it.plaintextBytes.toLong() }
        assertTrue("unchanged suffix was not reused", reused > 6 * 1024 * 1024)
        // No new encoding or decoder branch: existing concatenation semantics restore exactly.
        assertArrayEquals(changed, codec.restoreObjects(key, second.manifest) {
            second.newCiphertextChunks[it] ?: first.newCiphertextChunks.getValue(it)
        }.getValue("library-sync.json"))
    }

    @Test fun metadataOnlyEditReusesEveryBodyChunkAndUnchangedRetryUploadsNoBody() {
        val body = objectOf(source())
        val first = codec.create(key, 1, "phone", 1, listOf(body, CloudLogicalObject("rss-state.json", "unread".toByteArray())))
        val second = codec.create(key, 1, "phone", 2, listOf(body, CloudLogicalObject("rss-state.json", "read".toByteArray())), previousManifest = first.manifest)
        assertEquals(first.manifest.objects.first().chunks, second.manifest.objects.first().chunks)
        assertTrue(second.newCiphertextChunks.values.sumOf { it.size } < 1024)
        val retry = codec.create(key, 1, "phone", 3, listOf(body, CloudLogicalObject("rss-state.json", "read".toByteArray())), previousManifest = second.manifest)
        assertTrue(retry.newCiphertextChunks.isEmpty())
    }

    @Test fun mergerReusesOtherDevicesButNotDifferentKeysOrWrappedReaderRoots() {
        val objectA = objectOf("article A".toByteArray())
        val objectB = CloudLogicalObject("notes-state.json", "article B".toByteArray())
        val a = codec.create(key, 1, "a", 1, listOf(objectA))
        val b = codec.create(key, 1, "b", 1, listOf(objectB))
        val merged = codec.create(key, 1, "phone", 2, listOf(objectA, objectB), previousManifest = a.manifest, reusableManifests = listOf(b.manifest))
        assertTrue(merged.newCiphertextChunks.isEmpty())
        val rotated = codec.create(ByteArray(32) { 99 }, 2, "phone", 3, listOf(objectA), previousManifest = a.manifest)
        assertFalse(rotated.newCiphertextChunks.isEmpty())
        val wrapped = a.manifest.copy(objects = a.manifest.objects.map { it.copy(readerKeyEnvelope = JSONObject().put("version", 1)) })
        val root = codec.create(key, 1, "phone", 4, listOf(objectA), previousManifest = wrapped)
        assertFalse(root.newCiphertextChunks.isEmpty())
    }

    @Test fun evictedCiphertextCanBeRebuiltExactlyWithoutNewNonceOrReservation() {
        val body = objectOf(source())
        val first = codec.create(key, 1, "phone", 1, listOf(body))
        val rebuilt = mutableMapOf<String, ByteArray>()
        val retry = codec.create(key, 1, "phone", 2, listOf(body), previousManifest = first.manifest,
            onReusedChunk = { descriptor, materialize -> rebuilt[descriptor.ciphertextSha256] = materialize() })
        assertTrue(retry.newCiphertextChunks.isEmpty())
        first.newCiphertextChunks.forEach { (sha, bytes) -> assertArrayEquals(bytes, rebuilt[sha]) }
    }

    @Test fun splitterBoundsAndEmptyInput() {
        assertNull(CloudContentChunks(ByteArrayInputStream(byteArrayOf())).next())
        val data = source()
        val chunks = CloudContentChunks(ByteArrayInputStream(data))
        val output = java.io.ByteArrayOutputStream()
        while (true) {
            val next = chunks.next() ?: break
            assertTrue(next.size <= CloudContentChunks.MAX_BYTES)
            output.write(next)
        }
        assertArrayEquals(data, output.toByteArray())
    }
}
