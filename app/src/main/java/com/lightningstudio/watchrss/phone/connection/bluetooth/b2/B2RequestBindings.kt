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
        val bindingLocks = mutableMapOf<String, BindingLock>()
        val unsynced = mutableSetOf<String>()
    }
    private class BindingLock {
        var users = 0
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
    private fun readBinding(key: String): String? {
        val target = File(directory, key)
        if (!target.exists()) return null
        if (Files.isSymbolicLink(target.toPath()) || !target.isFile || target.length() != 64L) fail("JOURNAL_CORRUPT")
        val bytes = ByteArray(64)
        target.inputStream().use { input -> java.io.DataInputStream(input).readFully(bytes); if (input.read() != -1) fail("JOURNAL_CORRUPT") }
        val value = B2Json.decode(bytes); B2Json.hex(value)
        return value
    }
    fun check(scope: String, job: String, peer: String, id: String, digest: String) {
        try {
            val old = readBinding(key(scope, job, peer, id))
            if (old != null && old != digest) fail("REQUEST_CONFLICT")
        } catch (_: java.io.IOException) { fail("LOCAL_IO") }
    }
    fun bind(scope: String, job: String, peer: String, id: String, digest: String, limit: Long) {
        val key = key(scope, job, peer, id)
        val ceiling = minOf(33554432L, limit / 2)
        val lock = synchronized(state) {
            initialize()
            state.bindingLocks.getOrPut(key, ::BindingLock).also { it.users++ }
        }
        try {
            synchronized(lock) {
                val old = readBinding(key)
                if (old != null) {
                    if (old != digest) fail("REQUEST_CONFLICT")
                    if (synchronized(state) { state.charged } > ceiling) fail("STORAGE_PRESSURE")
                    if (synchronized(state) { key in state.unsynced }) syncDirectory(key)
                    return
                }
                if (directory.usableSpace < 16384) fail("STORAGE_PRESSURE")
                synchronized(state) {
                    if (state.charged + CHARGE > ceiling) fail("STORAGE_PRESSURE")
                    state.charged += CHARGE
                }
                var published = false
                var temp: File? = null
                try {
                    temp = File.createTempFile("binding-", ".tmp", directory)
                    FileOutputStream(temp).use { it.write(B2Json.utf8(digest)); it.fd.sync() }
                    try {
                        Files.createLink(File(directory, key).toPath(), temp.toPath())
                        published = true
                        synchronized(state) { state.unsynced.add(key) }
                    } catch (_: java.nio.file.FileAlreadyExistsException) {
                        if (readBinding(key) != digest) fail("REQUEST_CONFLICT")
                    }
                    syncDirectory(key)
                } finally {
                    if (!published) synchronized(state) { state.charged -= CHARGE }
                    temp?.delete()
                }
            }
        } catch (_: java.io.IOException) { fail("LOCAL_IO") }
        finally {
            synchronized(state) { if (--lock.users == 0) state.bindingLocks.remove(key) }
        }
    }
    private fun syncDirectory(key: String) {
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        synchronized(state) { state.unsynced.remove(key) }
    }

    fun chargedBytes(): Long = synchronized(state) { initialize(); state.charged }
}
