package com.lightningstudio.watchrss.phone.connection.bluetooth.b2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

class B2RequestBindingsTest {
    @get:Rule val root = TemporaryFolder()

    private val scope = "a".repeat(64)
    private val job = "b".repeat(32)
    private val peer = "watch"
    private val digest = "c".repeat(64)
    private val changed = "d".repeat(64)
    private val journalLimit = B2Wire.hardLimits.getValue("maxJournalBytes")

    private fun id(index: Int) = index.toString(16).padStart(32, '0')
    private fun key(id: String) =
        B2Digests.d("wrss.library-binary.v2/request-binding-file", listOf(scope, job, peer, id))

    @Test fun fullSizeBookCanRetainEveryBindingAndStillRejectReuse() {
        val directory = root.newFolder()
        val writer = B2RequestBindings(directory)
        val packageChunks = (B2Wire.BOOK_MAX_BYTES / B2Wire.CHUNK).toInt()
        val count = packageChunks + 108 // State and room for manifest, apply, and query controls.
        repeat(count) { writer.bind(scope, job, peer, id(it), digest, journalLimit) }

        val reader = B2RequestBindings(directory)
        assertTrue(reader.chargedBytes() < 3 * 1024 * 1024)
        assertTrue(directory.listFiles()!!.size <= 257)
        for (index in listOf(0, 4096, packageChunks - 1, count - 1)) {
            reader.check(scope, job, peer, id(index), digest)
            val conflict = assertThrows(B2Exception::class.java) {
                reader.check(scope, job, peer, id(index), changed)
            }
            assertEquals("REQUEST_CONFLICT", conflict.code)
        }
        assertEquals("REQUEST_CONFLICT", assertThrows(B2Exception::class.java) {
            reader.bind(scope, job, peer, id(0), changed, journalLimit)
        }.code)
    }

    @Test fun migratesExistingBindingAndChargesWithinNegotiatedPartition() {
        val directory = root.newFolder()
        val request = id(0)
        val legacy = File(directory, key(request)).apply { writeText(digest) }
        val journal = B2RequestBindings(directory)
        assertEquals("REQUEST_CONFLICT", assertThrows(B2Exception::class.java) {
            journal.check(scope, job, peer, request, changed)
        }.code)
        val migratedCharge = journal.chargedBytes()
        assertFalse(legacy.exists())

        val reopened = B2RequestBindings(directory)
        reopened.bind(scope, job, peer, request, digest, migratedCharge * 2)
        assertEquals(migratedCharge, reopened.chargedBytes())
        assertEquals("REQUEST_CONFLICT", assertThrows(B2Exception::class.java) {
            reopened.bind(scope, job, peer, request, changed, journalLimit)
        }.code)
        val other = (1..1024).map(::id).first { key(it).take(2) != key(request).take(2) }
        assertEquals("STORAGE_PRESSURE", assertThrows(B2Exception::class.java) {
            reopened.bind(scope, job, peer, other, digest, migratedCharge * 2)
        }.code)
        assertEquals(migratedCharge, reopened.chargedBytes())
    }

    @Test fun interruptedAppendLeavesCommittedPrefixAndChecksumProtectsPublishedRecords() {
        val directory = root.newFolder()
        val journal = B2RequestBindings(directory)
        val first = id(0)
        val prefix = key(first).take(2)
        val next = (1..8192).map(::id).first { key(it).startsWith(prefix) }
        journal.bind(scope, job, peer, first, digest, journalLimit)
        val shard = File(directory, "pack-v1-" + prefix)
        val recordBytes = shard.length()
        shard.appendBytes(byteArrayOf(1, 2, 3))

        val reopened = B2RequestBindings(directory)
        reopened.check(scope, job, peer, first, digest)
        reopened.bind(scope, job, peer, next, changed, journalLimit)
        assertEquals(2 * recordBytes, shard.length())
        assertEquals("REQUEST_CONFLICT", assertThrows(B2Exception::class.java) {
            reopened.check(scope, job, peer, next, digest)
        }.code)
        RandomAccessFile(shard, "rw").use { file ->
            val previous = file.read()
            file.seek(0)
            file.write(previous xor 1)
        }
        assertEquals("JOURNAL_CORRUPT", assertThrows(B2Exception::class.java) {
            reopened.check(scope, job, peer, first, digest)
        }.code)
    }
}
