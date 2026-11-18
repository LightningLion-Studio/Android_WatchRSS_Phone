package com.lightningstudio.watchrss.phone.connection.bluetooth.b2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class B2ScratchTest {
    @get:Rule val folders = TemporaryFolder()

    @Test fun coldScratchIsChargedAndCollectedWhileActiveWorkersKeepTheirFiles() {
        val staging = folders.newFolder("staging")
        val imports = folders.newFolder("imports")
        val ordinary = File(staging, "frozen").apply { writeBytes(ByteArray(41)) }
        val published = File(imports, "b2-published.jsonl").apply { writeBytes(ByteArray(51)) }
        fun orphan(root: File, path: String, bytes: Int) = File(root, path).apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(bytes))
        }
        val freezeOrphan = orphan(staging, ".freeze-crash/nested/data", 61)
        val importOrphan = orphan(imports, ".b2-import-crash/nested/body", 71)
        val before = B2Files(staging, imports)
        assertEquals(173L, before.dataBytes())

        B2Scratch.open(staging, B2Scratch.FREEZE).use { source ->
            B2Scratch.open(imports, B2Scratch.BOOK_IMPORT).use { decoder ->
                val activeSource = orphan(source.file, "segment", 81)
                val activeDecoder = orphan(decoder.file, "text", 91)
                val restarted = B2Files(staging, imports)
                assertEquals(345L, restarted.dataBytes())
                restarted.collectScratch()
                assertEquals(213L, restarted.dataBytes())
                assertFalse(freezeOrphan.exists())
                assertFalse(importOrphan.exists())
                assertTrue(activeSource.isFile)
                assertTrue(activeDecoder.isFile)
                val hash = activeSource.inputStream().use(restarted::hashStream)
                restarted.adoptSource(activeSource, File(staging, "adopted"), 81, hash, 213)
                assertEquals(213L, restarted.dataBytes())
            }
        }

        assertEquals(122L, B2Files(staging, imports).dataBytes())
        assertTrue(ordinary.isFile)
        assertTrue(published.isFile)
    }
}
