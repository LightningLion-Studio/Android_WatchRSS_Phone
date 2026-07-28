package com.lightningstudio.watchrss.phone.cloud

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.AEADBadTagException

class CloudSnapshotCodecTest {
    private val key = ByteArray(32) { index -> (index + 1).toByte() }
    private val codec = CloudSnapshotCodec(SecureRandom())

    @Test
    fun encryptsAndRestoresMultipleLogicalObjects() {
        val source = "hello watchrss".repeat(10_000).toByteArray()
        val privateLibrary = ByteArray(5 * 1024 * 1024) { index -> (index % 251).toByte() }
        val encrypted = codec.create(
            accountKey = key,
            keyVersion = 1,
            sourceDeviceId = "phone-1",
            deviceSequence = 1,
            logicalObjects = listOf(
                CloudLogicalObject("rss-state.json", source),
                CloudLogicalObject("private-library.wrss", privateLibrary, compress = false)
            ),
            snapshotId = "11111111-1111-4111-8111-111111111111"
        )

        val manifest = codec.decryptManifest(
            accountKey = key,
            snapshotId = encrypted.manifest.snapshotId,
            encryptedManifest = encrypted.encryptedManifest
        )
        val restored = codec.restoreObjects(key, manifest) { hash ->
            encrypted.newCiphertextChunks.getValue(hash)
        }

        assertArrayEquals(source, restored.getValue("rss-state.json"))
        assertArrayEquals(privateLibrary, restored.getValue("private-library.wrss"))
        assertTrue(manifest.allChunks.size >= 2)
    }

    @Test
    fun reusesUnchangedCiphertextChunksAcrossSnapshots() {
        val content = ByteArray(1024 * 1024) { 7 }
        val first = codec.create(
            accountKey = key,
            keyVersion = 1,
            sourceDeviceId = "phone-1",
            deviceSequence = 1,
            logicalObjects = listOf(CloudLogicalObject("private-library.wrss", content, false))
        )
        val second = codec.create(
            accountKey = key,
            keyVersion = 1,
            sourceDeviceId = "phone-1",
            deviceSequence = 2,
            logicalObjects = listOf(CloudLogicalObject("private-library.wrss", content, false)),
            previousManifest = first.manifest
        )

        assertTrue(second.newCiphertextChunks.isEmpty())
        assertEquals(
            first.manifest.allChunks.single().ciphertextSha256,
            second.manifest.allChunks.single().ciphertextSha256
        )
        assertNotEquals(
            CloudSnapshotCodec.sha256(first.encryptedManifest),
            CloudSnapshotCodec.sha256(second.encryptedManifest)
        )
    }

    @Test
    fun compressedObjectsReuseUnchangedFixedSizeChunks() {
        val content = ByteArray(9 * 1024 * 1024) { index -> (index * 31).toByte() }
        val first = codec.create(
            accountKey = key,
            keyVersion = 1,
            sourceDeviceId = "phone-1",
            deviceSequence = 1,
            logicalObjects = listOf(CloudLogicalObject("novel.epub", content))
        )
        val changed = content.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val second = codec.create(
            accountKey = key,
            keyVersion = 1,
            sourceDeviceId = "phone-1",
            deviceSequence = 2,
            logicalObjects = listOf(CloudLogicalObject("novel.epub", changed)),
            previousManifest = first.manifest
        )

        assertEquals("gzip-chunks-v1", second.manifest.objects.single().encoding)
        assertEquals(3, first.manifest.allChunks.size)
        assertEquals(1, second.newCiphertextChunks.size)
        val restored = codec.restoreObjects(key, second.manifest) { hash ->
            second.newCiphertextChunks[hash] ?: first.newCiphertextChunks.getValue(hash)
        }
        assertArrayEquals(changed, restored.getValue("novel.epub"))
    }

    @Test(expected = AEADBadTagException::class)
    fun rejectsTamperedManifest() {
        val encrypted = codec.create(
            accountKey = key,
            keyVersion = 1,
            sourceDeviceId = "phone-1",
            deviceSequence = 1,
            logicalObjects = listOf(CloudLogicalObject("rss-state.json", "{}".toByteArray()))
        )
        encrypted.encryptedManifest[encrypted.encryptedManifest.lastIndex] =
            (encrypted.encryptedManifest.last().toInt() xor 1).toByte()
        codec.decryptManifest(key, encrypted.manifest.snapshotId, encrypted.encryptedManifest)
    }

    @Test
    fun rotatedSnapshotCarriesKeyVersionAndRejectsOldKey() {
        val rotatedKey = ByteArray(32) { index -> (255 - index).toByte() }
        val encrypted = codec.create(
            accountKey = rotatedKey,
            keyVersion = 2,
            sourceDeviceId = "phone-1",
            deviceSequence = 2,
            logicalObjects = listOf(CloudLogicalObject("rss-state.json", "{}".toByteArray()))
        )
        val manifest = codec.decryptManifest(
            rotatedKey,
            encrypted.manifest.snapshotId,
            encrypted.encryptedManifest
        )
        assertEquals(2, manifest.keyVersion)
        runCatching {
            codec.decryptManifest(key, encrypted.manifest.snapshotId, encrypted.encryptedManifest)
        }.onSuccess {
            throw AssertionError("old account key decrypted rotated snapshot")
        }
    }
}
