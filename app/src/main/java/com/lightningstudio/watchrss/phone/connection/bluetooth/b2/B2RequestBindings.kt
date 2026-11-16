package com.lightningstudio.watchrss.phone.connection.bluetooth.b2

import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/** Independent, bounded protocol journal. Heartbeats never join the business DB lane.
 * Atomic no-replace links make requestId binding immutable across processes/crashes.
 * The native journal and business journal each reserve half maxJournalBytes. */
internal class B2RequestBindings(private val directory: File) {
    private val state = synchronized(states) { states.getOrPut(directory.canonicalPath) { State() } }
    private class State {
        var initialized = false
        var charged = 0L
        val cache = object : LinkedHashMap<String, String>(512, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 512
        }
    }
    companion object {
        private const val CHARGE = 8192L // one inode/block, directory/index and atomic-publish scratch
        private val states = mutableMapOf<String, State>()
    }
    private fun initialize() {
        if (state.initialized) return
        directory.mkdirs()
        if (!directory.isDirectory) fail("LOCAL_IO")
        state.charged = (directory.listFiles() ?: fail("LOCAL_IO")).sumOf { file ->
            if (file.name.matches(Regex("[0-9a-f]{64}")) && file.isFile && !Files.isSymbolicLink(file.toPath())) CHARGE
            else if (file.name.startsWith("binding-") && file.name.endsWith(".tmp")) {
                // Unpublished scratch has never acknowledged a request binding.
                if (!file.delete()) fail("LOCAL_IO"); 0L
            } else fail("JOURNAL_CORRUPT")
        }
        state.initialized = true
    }
    private fun key(scope: String, job: String, peer: String, id: String) =
        B2Digests.d("wrss.library-binary.v2/request-binding-file", listOf(scope, job, peer, id))
    private fun existing(key: String): String? {
        state.cache[key]?.let { return it }
        val target = File(directory, key)
        if (!target.exists()) return null
        if (Files.isSymbolicLink(target.toPath()) || !target.isFile || target.length() != 64L) fail("JOURNAL_CORRUPT")
        val bytes = ByteArray(64)
        target.inputStream().use { input -> java.io.DataInputStream(input).readFully(bytes); if (input.read() != -1) fail("JOURNAL_CORRUPT") }
        val value = B2Json.decode(bytes); B2Json.hex(value)
        state.cache[key] = value
        return value
    }
    fun check(scope: String, job: String, peer: String, id: String, digest: String) = synchronized(state) {
        try {
            initialize()
            val old = existing(key(scope, job, peer, id))
            if (old != null && old != digest) fail("REQUEST_CONFLICT")
        } catch (_: java.io.IOException) { fail("LOCAL_IO") }
    }
    fun bind(scope: String, job: String, peer: String, id: String, digest: String, limit: Long) = synchronized(state) {
        try {
            initialize()
            val key = key(scope, job, peer, id)
            val old = existing(key)
            if (old != null) {
                if (old != digest) fail("REQUEST_CONFLICT")
                if (state.charged > minOf(33554432L, limit / 2)) fail("STORAGE_PRESSURE")
                return@synchronized
            }
            if (state.charged + CHARGE > minOf(33554432L, limit / 2) || directory.usableSpace < 16384) fail("STORAGE_PRESSURE")
            val temp = File.createTempFile("binding-", ".tmp", directory)
            try {
                FileOutputStream(temp).use { it.write(B2Json.utf8(digest)); it.fd.sync() }
                try { Files.createLink(File(directory, key).toPath(), temp.toPath()); state.charged += CHARGE }
                catch (_: java.nio.file.FileAlreadyExistsException) { if (existing(key) != digest) fail("REQUEST_CONFLICT") }
                FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
                state.cache[key] = digest
            } finally { temp.delete() }
        } catch (_: java.io.IOException) { fail("LOCAL_IO") }
    }
    fun chargedBytes(): Long = synchronized(state) { initialize(); state.charged }
}
