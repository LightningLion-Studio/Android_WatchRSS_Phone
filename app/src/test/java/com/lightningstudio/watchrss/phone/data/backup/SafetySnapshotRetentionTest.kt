package com.lightningstudio.watchrss.phone.data.backup

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetySnapshotRetentionTest {
    @Test
    fun `prune enforces count and total bytes while retaining newest snapshot`() {
        val directory = Files.createTempDirectory("watchrss-safety-test").toFile()
        try {
            (1L..5L).forEach { timestamp ->
                directory.resolve("before-restore-$timestamp.wrss").writeBytes(ByteArray(10))
            }
            val stalePart = directory.resolve("before-restore-6.wrss.part").apply {
                writeBytes(ByteArray(10))
            }
            val unrelated = directory.resolve("manual-export.wrss").apply {
                writeBytes(ByteArray(10))
            }

            val result = SafetySnapshotRetention.prune(
                directory,
                maxSnapshotCount = 3,
                maxTotalBytes = 25
            )

            assertEquals(2, result.retainedCount)
            assertEquals(3, result.deletedCount)
            assertTrue(directory.resolve("before-restore-5.wrss").isFile)
            assertTrue(directory.resolve("before-restore-4.wrss").isFile)
            assertFalse(directory.resolve("before-restore-3.wrss").exists())
            assertFalse(stalePart.exists())
            assertTrue(unrelated.isFile)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `prune retains newest snapshot when it alone exceeds quota`() {
        val directory = Files.createTempDirectory("watchrss-safety-test").toFile()
        try {
            directory.resolve("before-restore-1.wrss").writeBytes(ByteArray(20))
            directory.resolve("before-restore-2.wrss").writeBytes(ByteArray(20))

            val result = SafetySnapshotRetention.prune(directory, maxTotalBytes = 1)

            assertEquals(1, result.retainedCount)
            assertTrue(directory.resolve("before-restore-2.wrss").isFile)
            assertFalse(directory.resolve("before-restore-1.wrss").exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `commit replaces temporary file atomically`() {
        val directory = Files.createTempDirectory("watchrss-safety-test").toFile()
        try {
            val temporary = directory.resolve("snapshot.part").apply { writeText("complete") }
            val destination = directory.resolve("before-restore-1.wrss")

            SafetySnapshotRetention.commit(temporary, destination)

            assertFalse(temporary.exists())
            assertEquals("complete", destination.readText())
        } finally {
            directory.deleteRecursively()
        }
    }
}
