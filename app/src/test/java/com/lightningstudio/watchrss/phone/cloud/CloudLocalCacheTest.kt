package com.lightningstudio.watchrss.phone.cloud

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudLocalCacheTest {
    @Test
    fun `prune keeps local head and removes unreferenced snapshots and chunks`() = withCache { root, cache ->
        val userId = "user-1"
        val retainedChunk = "retained".toByteArray()
        val removedChunk = "removed".toByteArray()
        val retainedSha = CloudSnapshotCodec.sha256(retainedChunk)
        val removedSha = CloudSnapshotCodec.sha256(removedChunk)
        cache.storeChunk(userId, retainedSha, retainedChunk)
        cache.storeChunk(userId, removedSha, removedChunk)
        cache.storeManifest(userId, "snapshot-1", "one".toByteArray(), markAsLocalHead = true)
        cache.recordManifestReferences(userId, "snapshot-1", listOf(retainedSha))
        cache.storeManifest(userId, "snapshot-2", "two".toByteArray(), markAsLocalHead = false)
        cache.recordManifestReferences(userId, "snapshot-2", listOf(removedSha))
        val manifestDirectory = requireNotNull(
            root.walkTopDown().first { it.name == "snapshot-1.bin" }.parentFile
        )
        val abandonedTemporary = manifestDirectory.resolve("abandoned.bin.tmp")
            .apply { writeText("partial") }

        val result = cache.prune(userId, keepManifestCount = 1)

        assertArrayEquals("one".toByteArray(), cache.loadLatestManifest(userId)?.second)
        assertArrayEquals(retainedChunk, cache.loadChunk(userId, retainedSha))
        assertNull(cache.loadManifest(userId, "snapshot-2"))
        assertNull(cache.loadChunk(userId, removedSha))
        assertEquals(1, result.retainedManifestCount)
        assertTrue(root.walkTopDown().none { it.name == "snapshot-2.refs" })
        assertTrue(!abandonedTemporary.exists())
    }

    @Test
    fun `quota evicts cached chunks even when retained manifest references them`() = withCache { root, cache ->
        val userId = "user-2"
        val first = "first-chunk".toByteArray()
        val second = "second-chunk".toByteArray()
        val firstSha = CloudSnapshotCodec.sha256(first)
        val secondSha = CloudSnapshotCodec.sha256(second)
        cache.storeChunk(userId, firstSha, first)
        cache.storeChunk(userId, secondSha, second)
        cache.storeManifest(userId, "snapshot-1", "manifest".toByteArray(), markAsLocalHead = true)
        cache.recordManifestReferences(userId, "snapshot-1", listOf(firstSha, secondSha))
        root.walkTopDown().first { it.name == "$firstSha.bin" }.setLastModified(1L)
        root.walkTopDown().first { it.name == "$secondSha.bin" }.setLastModified(2L)
        val currentBytes = root.walkTopDown().filter(File::isFile).sumOf(File::length)

        cache.prune(userId, maxBytes = currentBytes - first.size)

        assertNull(cache.loadChunk(userId, firstSha))
        assertArrayEquals(second, cache.loadChunk(userId, secondSha))
    }

    @Test
    fun `deleting local head removes its cache and latest pointer`() = withCache { _, cache ->
        val userId = "user-3"
        val chunk = "body".toByteArray()
        val sha = CloudSnapshotCodec.sha256(chunk)
        cache.storeChunk(userId, sha, chunk)
        cache.storeManifest(userId, "snapshot-1", "manifest".toByteArray(), markAsLocalHead = true)
        cache.recordManifestReferences(userId, "snapshot-1", listOf(sha))

        cache.deleteSnapshot(userId, "snapshot-1")

        assertNull(cache.loadLatestManifest(userId))
        assertNull(cache.loadManifest(userId, "snapshot-1"))
        assertNull(cache.loadChunk(userId, sha))
    }

    private fun withCache(block: (File, CloudLocalCache) -> Unit) {
        val root = Files.createTempDirectory("watchrss-cloud-cache-test").toFile()
        try {
            block(root, CloudLocalCache(root))
        } finally {
            root.deleteRecursively()
        }
    }
}
