package com.lightningstudio.watchrss.phone.data.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

class BookPackageFilesTest {
    @get:Rule val folder = TemporaryFolder()

    private val id = "a".repeat(64)
    private val hashOfAbc = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    private val revision = BookPackageFiles.Revision(hashOfAbc, 3)

    @Test fun unchangedOriginalAndUnchangedMismatchAreBothRemembered() {
        val directory = folder.root
        BookPackageFiles.legacy(directory, id).writeText("bad")
        val binary = BookPackageFiles.binary(directory, id, revision.sha256).apply { writeText("abc") }
        var reads = 0
        assertEquals(binary, BookPackageFiles.resolve(directory, id, revision) { reads += it })
        assertEquals(6, reads)
        repeat(3) {
            assertEquals(binary, BookPackageFiles.resolve(directory, id, revision) { reads += it })
        }
        assertEquals(6, reads)

        val differentRevision = revision.copy(sha256 = "0".repeat(64))
        assertNull(BookPackageFiles.resolve(directory, id, differentRevision) { reads += it })
        assertEquals(9, reads)
    }

    @Test fun replacementWithSameLengthAndMtimeIsVerifiedAgain() {
        val directory = folder.root
        val legacy = BookPackageFiles.legacy(directory, id).apply { writeText("abc") }
        assertEquals(legacy, BookPackageFiles.resolve(directory, id, revision))
        val timestamp = Files.getLastModifiedTime(legacy.toPath())
        val replacement = folder.newFile().apply { writeText("bad") }
        Files.setLastModifiedTime(replacement.toPath(), timestamp)
        Files.move(replacement.toPath(), legacy.toPath(), REPLACE_EXISTING)
        assertEquals(timestamp, Files.getLastModifiedTime(legacy.toPath()))

        var reads = 0
        assertNull(BookPackageFiles.resolve(directory, id, revision) { reads += it })
        assertTrue(reads > 0)
    }
}
