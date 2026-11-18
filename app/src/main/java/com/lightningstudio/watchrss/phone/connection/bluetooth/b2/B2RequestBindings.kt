package com.lightningstudio.watchrss.phone.connection.bluetooth.b2

import java.io.DataInputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.zip.CRC32

/** Independent protocol journal. A fixed-width record stores the existing
 * scope/job/peer/request key and its full digest. The key prefix selects a shard.
 * Readers only inspect the complete immutable prefix. Writers serialize admission
 * and append across processes and sync before acknowledging a binding. */
internal class B2RequestBindings(private val directory: File) {
    private val writer = writers[(directory.canonicalPath.hashCode() and Int.MAX_VALUE) % writers.size]
    private var migrated = false

    fun check(scope: String, job: String, peer: String, id: String, digest: String) {
        val key = key(scope, job, peer, id)
        try {
            // Read legacy first. If migration unlinks it before this lookup,
            // the corresponding compact record is already durable.
            val legacy = readLegacy(File(directory, key))
            val packed = readPacked(shard(key), key)
            if (legacy != null && legacy != digest || packed != null && packed != digest) fail("REQUEST_CONFLICT")
        } catch (_: java.io.IOException) {
            fail("LOCAL_IO")
        }
    }

    fun bind(scope: String, job: String, peer: String, id: String, digest: String, limit: Long) = writeLocked {
        migrate()
        val key = key(scope, job, peer, id)
        val file = shard(key)
        val old = readPacked(file, key)
        if (old != null && old != digest) fail("REQUEST_CONFLICT")
        val current = charged()
        val length = file.length()
        val extra = if (old == null) charge(recordPrefix(length) + RECORD_BYTES) -
            (if (file.exists()) charge(length) else 0) else 0L
        val ceiling = minOf(MAX_PARTITION, limit / 2)
        if (current + extra > ceiling || extra > 0 && directory.usableSpace < extra + BLOCK) fail("STORAGE_PRESSURE")
        if (old == null) append(file, key, digest)
        else force(file) // The prior attempt may have failed after writing a complete record.
        syncDirectory()
    }

    fun chargedBytes(): Long = writeLocked {
        migrate()
        charged()
    }

    private fun key(scope: String, job: String, peer: String, id: String) =
        B2Digests.d("wrss.library-binary.v2/request-binding-file", listOf(scope, job, peer, id))

    private fun shard(key: String) = File(directory, "pack-v1-" + key.take(2))

    private fun readLegacy(file: File): String? {
        return try {
            if (Files.isSymbolicLink(file.toPath())) fail("JOURNAL_CORRUPT")
            if (!file.exists()) return null
            val bytes = ByteArray(64)
            DataInputStream(file.inputStream()).use { input ->
                input.readFully(bytes)
                if (input.read() != -1) fail("JOURNAL_CORRUPT")
            }
            try { B2Json.decode(bytes).also { B2Json.hex(it) } }
            catch (_: B2Exception) { fail("JOURNAL_CORRUPT") }
        } catch (e: java.io.FileNotFoundException) {
            if (file.exists()) throw e
            null
        } catch (_: java.io.EOFException) {
            fail("JOURNAL_CORRUPT")
        }
    }

    private fun readPacked(file: File, key: String): String? {
        if (!file.exists()) return null
        requireRegular(file)
        val length = file.length()
        if (length > MAX_PARTITION) fail("JOURNAL_CORRUPT")
        val count = length / RECORD_BYTES
        val expected = B2Frames.unhex(key, FIELD_BYTES)
        val record = ByteArray(2 * FIELD_BYTES)
        val crc = CRC32()
        DataInputStream(file.inputStream().buffered()).use { input ->
            repeat(count.toInt()) {
                input.readFully(record)
                crc.reset()
                crc.update(record)
                if (input.readInt() != crc.value.toInt()) fail("JOURNAL_CORRUPT")
                if (expected.indices.all { expected[it] == record[it] }) {
                    return B2Frames.hex(record.copyOfRange(FIELD_BYTES, record.size))
                }
            }
        }
        return null
    }

    private fun append(file: File, key: String, digest: String) {
        val record = B2Frames.unhex(key, FIELD_BYTES) + B2Frames.unhex(digest, FIELD_BYTES)
        val crc = CRC32().apply { update(record) }
        RandomAccessFile(file, "rw").use { out ->
            // A partial suffix has never been synced as a complete binding.
            val end = recordPrefix(out.length())
            if (end != out.length()) out.setLength(end)
            out.seek(end)
            out.write(record)
            out.writeInt(crc.value.toInt())
            out.fd.sync()
        }
    }

    private fun migrate() {
        if (migrated) return
        syncDirectory(directory.absoluteFile.parentFile)
        for (legacy in files()) {
            if (LEGACY.matches(legacy.name)) {
                val digest = readLegacy(legacy) ?: fail("JOURNAL_CORRUPT")
                val file = shard(legacy.name)
                val existing = readPacked(file, legacy.name)
                if (existing == null) append(file, legacy.name, digest)
                else {
                    if (existing != digest) fail("JOURNAL_CORRUPT")
                    force(file)
                }
                // Commit the new directory entry before removing the only old copy.
                syncDirectory()
                if (!legacy.delete()) fail("LOCAL_IO")
            } else if (legacy.name.startsWith("binding-") && legacy.name.endsWith(".tmp")) {
                if (!legacy.delete()) fail("LOCAL_IO")
            }
        }
        syncDirectory()
        migrated = true
    }

    private fun charged(): Long = files().sumOf { file ->
        when {
            SHARD.matches(file.name) -> {
                requireRegular(file)
                charge(file.length())
            }
            file.name == LOCK -> {
                requireRegular(file)
                BLOCK
            }
            else -> fail("JOURNAL_CORRUPT")
        }
    }

    private fun files() = directory.listFiles() ?: fail("LOCAL_IO")

    private fun requireRegular(file: File) {
        if (Files.isSymbolicLink(file.toPath()) || !file.isFile) fail("JOURNAL_CORRUPT")
    }

    private fun recordPrefix(length: Long) = length - length % RECORD_BYTES

    private fun charge(length: Long): Long =
        BLOCK + (length + BLOCK - 1) / BLOCK * BLOCK

    private fun force(file: File) {
        FileChannel.open(file.toPath(), StandardOpenOption.WRITE).use { it.force(true) }
    }

    private fun syncDirectory(target: File = directory) {
        FileChannel.open(target.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }

    private fun <T> writeLocked(work: () -> T): T = synchronized(writer) {
        try {
            if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) fail("LOCAL_IO")
            val path = File(directory, LOCK).toPath()
            FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                channel.lock().use { work() }
            }
        } catch (_: java.io.IOException) {
            fail("LOCAL_IO")
        }
    }

    private companion object {
        const val BLOCK = 4096L
        const val MAX_PARTITION = 33554432L
        const val FIELD_BYTES = 32
        const val RECORD_BYTES = 68L
        const val LOCK = "pack.lock"
        val LEGACY = Regex("[0-9a-f]{64}")
        val SHARD = Regex("pack-v1-[0-9a-f]{2}")
        val writers = Array(16) { Any() }
    }
}
