package com.lightningstudio.watchrss.phone.syncv2

import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.B2CommitWindow
import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.B2Exception
import com.lightningstudio.watchrss.phone.data.local.ConditionalBodyFile
import com.lightningstudio.watchrss.phone.data.local.syncDirectory
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.RandomAccessFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** Received bytes remain private and encrypted even after the B2 journal expires. */
internal class SyncV2BluetoothInbox(private val projection: SyncV2Projection, files: File) {
    private val directory = File(files, "syncv2-guarded-inbox")
    private val quota = 1024L * 1024 * 1024

    fun prepare(input: B2CommitWindow.InboxInput, check: () -> Unit): B2CommitWindow.PendingInbox? {
        if (!projection.bluetoothNeedsInbox(input.descriptor)) return null
        check()
        val objectId = input.descriptor.getString("objectId")
        val size = input.descriptor.getLong("size")
        val sha = input.descriptor.getString("sha256")
        val peer = "b2:${input.direction}:${input.peer}"
        val cursor = "${input.job}:$objectId"
        val id = SyncV2Codec.hash(org.json.JSONArray().put(projection.scope).put(peer).put(cursor).put(input.plan))
        val link = JSONObject().put("planDigest", input.plan).put("jobId", input.job)
            .put("direction", input.direction).put("descriptor", input.descriptor).put("archiveId", id)
        return archive(peer, 14, cursor, id, link, size, sha, input.file::inputStream, check)
    }

    fun prepareLegacy(input: B2CommitWindow.LegacyInput, check: () -> Unit): B2CommitWindow.PendingInbox? {
        if (!projection.bluetoothNeedsInbox()) return null
        check()
        val bytes = SyncV2Codec.utf8(input.payload.toString())
        val sha = SyncV2Codec.hex(SyncV2Codec.sha(bytes))
        val cursor = "${input.action}:$sha"
        val id = SyncV2Codec.hash(org.json.JSONArray().put(projection.scope).put(input.peer).put(input.version).put(cursor))
        val link = JSONObject().put("direction", "watchToPhone").put("archiveId", id)
            .put("descriptor", JSONObject().put("kind", input.action).put("sha256", sha).put("size", bytes.size))
        return archive(input.peer, input.version, cursor, id, link, bytes.size.toLong(), sha, bytes::inputStream, check)
    }

    private fun archive(peer: String, version: Int, cursor: String, id: String, link: JSONObject,
        size: Long, sha: String, open: () -> InputStream, check: () -> Unit): B2CommitWindow.PendingInbox {
        var retained = false
        val commit: () -> Unit = { retained = projection.recordGuardedBluetooth(peer, version, cursor, link) }
        if (size == 0L) return B2CommitWindow.PendingInbox(commit) {}
        if (directory.mkdirs()) syncDirectory(requireNotNull(directory.parentFile))
        val target = File(directory, "$id.body")
        if (target.isFile) {
            val digest = MessageDigest.getInstance("SHA-256")
            var offset = 0L
            while (offset < size) {
                check()
                val raw = read(id, size, offset, minOf(65536L, size - offset).toInt())
                digest.update(raw)
                offset += raw.size
            }
            if (SyncV2Codec.hex(digest.digest()) != sha) throw B2Exception("HASH_MISMATCH")
            return B2CommitWindow.PendingInbox(commit) {}
        }
        val needed = size + ((size + 65535) / 65536) * 32 + 4096
        val allocated = directory.listFiles()?.sumOf { it.length() } ?: throw B2Exception("LOCAL_IO")
        if (needed > quota - allocated || needed > directory.usableSpace) throw B2Exception("STORAGE_PRESSURE")
        val temp = File.createTempFile("incoming-", ".part", directory)
        val key = projection.recoveryKey()
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileOutputStream(temp).use { file ->
                val encoded = DataOutputStream(file.buffered())
                val buffer = ByteArray(65536)
                DataInputStream(open().buffered()).use { source ->
                    var offset = 0L
                    while (offset < size) {
                        check()
                        val count = minOf(buffer.size.toLong(), size - offset).toInt()
                        try {
                            source.readFully(buffer, 0, count)
                        } catch (_: EOFException) {
                            throw B2Exception("HASH_MISMATCH")
                        }
                        digest.update(buffer, 0, count)
                        val nonce = SyncV2Codec.random(12)
                        val aad = associatedData(id, offset)
                        val encrypted = SyncV2Codec.encrypt(key, nonce, aad, buffer.copyOf(count))
                        encoded.write(nonce)
                        encoded.write(encrypted)
                        offset += count
                    }
                    if (source.read() != -1 || SyncV2Codec.hex(digest.digest()) != sha) throw B2Exception("HASH_MISMATCH")
                }
                encoded.flush()
                file.fd.sync()
            }
            check()
            if (ConditionalBodyFile.linkIfAbsent(target, temp)) syncDirectory(directory)
            // A concurrent identical operation may have committed this path.
            // Only its durable discard marker authorizes deletion.
            return B2CommitWindow.PendingInbox(commit) { committed -> if (committed && !retained) target.delete() }
        } catch (_: IOException) {
            throw B2Exception("LOCAL_IO")
        } finally {
            key.fill(0)
            temp.delete()
        }
    }

    fun collectResolved() {
        if (directory.list()?.none { it.endsWith(".body") } != false) return
        var changed = false
        for (id in projection.resolvedGuardedArchives()) {
            if (File(directory, "$id.body").delete()) changed = true
        }
        if (changed) syncDirectory(directory)
    }

    private fun associatedData(id: String, offset: Long) =
        SyncV2Codec.utf8("wrss.sync.v2/local/legacy-body/${projection.scope}/$id/$offset")

    fun read(id: String, size: Long, offset: Long, count: Int): ByteArray {
        SyncV2Codec.unhex(id, 32)
        if (offset < 0 || offset > size - count || count !in 1..262144) SyncV2Codec.fail("closure_incomplete")
        val result = ByteArray(count)
        val key = projection.recoveryKey()
        try {
            RandomAccessFile(File(directory, "$id.body"), "r").use { file ->
                val blocks = (size + 65535) / 65536
                if (file.length() != size + 28 * blocks) SyncV2Codec.fail("closure_incomplete")
                var written = 0
                while (written < count) {
                    val position = offset + written
                    val index = position / 65536
                    val base = index * 65536
                    val within = (position - base).toInt()
                    val bytes = minOf(65536L, size - base).toInt()
                    file.seek(index * (65536 + 28L))
                    val nonce = ByteArray(12).also(file::readFully)
                    val cipher = ByteArray(bytes + 16).also(file::readFully)
                    val raw = SyncV2Codec.decrypt(key, nonce, associatedData(id, base), cipher)
                    val copied = minOf(raw.size - within, count - written)
                    raw.copyInto(result, written, within, within + copied)
                    written += copied
                }
            }
            return result
        } catch (_: IOException) {
            SyncV2Codec.fail("closure_incomplete")
        } finally {
            key.fill(0)
        }
    }
}
