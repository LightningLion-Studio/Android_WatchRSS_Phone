package com.lightningstudio.watchrss.phone.connection.bluetooth

import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.*
import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.B2Wire.BOOK_COMMIT
import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.B2Wire.BOOK_READING
import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.B2Wire.BOOK_SEGMENT
import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.B2Wire.BOOK_SEGMENT_BYTES
import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.B2Wire.BOOK_STATE
import com.lightningstudio.watchrss.phone.data.book.BookSync
import com.lightningstudio.watchrss.phone.data.book.ImportedBookStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

internal class B2BookBusiness(
    private val persistence: B2Persistence,
    private val scope: String,
    private val books: ImportedBookStore,
    private val authorize: () -> Unit
) {
    suspend fun freeze(directory: File, work: () -> Unit, add: (JSONObject, File) -> Unit) {
        val empty = File(directory, "empty").apply { createNewFile() }
        val emptyHashes = binaryFileHashes(empty)
        var reserved = 0L
        suspend fun reserve(bytes: Long) {
            val active = persistence.exclusive {
                persistence.list(scope, "job").sumOf { (_, row) ->
                    if (row.s("state") in setOf("COMPLETE", "CANCELLED")) 0 else row.optLong("stagingReservation")
                }
            }
            val existing = directory.parentFile?.listFiles()?.sumOf { if (it.isFile) it.length() else 0 } ?: 0L
            val limits = B2Wire.s4Limits()
            reserved += bytes
            if (existing + reserved + active + limits.n("maxJournalBytes") > limits.n("maxStagingBytes") ||
                bytes + active + limits.n("maxJournalBytes") + 1048576 > directory.usableSpace) fail("STORAGE_PRESSURE")
        }
        books.freezeBinaryBooks { row, packageFile ->
            authorize()
            val state = decodeState(row.stateJson)
            val id = row.bookId
            reserve(B2Json.utf8(row.stateJson).size.toLong())
            val stateFile = writeObject(directory, row.stateJson)
            val stateObject = binaryDescriptor(BOOK_STATE, id, obj("bookId" to id), binaryFileHashes(stateFile, work))
            add(stateObject, stateFile)
            val stateId = stateObject.s("objectId")
            if (packageFile != null && !state.getBoolean("deleted")) {
                val size = row.packageBytes
                if (size !in 1..B2Wire.BOOK_MAX_BYTES) fail("LIMIT_EXCEEDED")
                reserve(size)
                val hash = row.packageSha256
                val fingerprint = state.s("fingerprint")
                val count = (size + BOOK_SEGMENT_BYTES - 1) / BOOK_SEGMENT_BYTES
                val whole = MessageDigest.getInstance("SHA-256")
                val segments = mutableListOf<String>()
                val buffer = ByteArray(B2Wire.CHUNK)
                java.io.DataInputStream(packageFile.inputStream()).use { input ->
                    for (index in 0 until count) {
                        val offset = index * BOOK_SEGMENT_BYTES
                        val length = minOf(BOOK_SEGMENT_BYTES, size - offset)
                        val part = File.createTempFile("segment-", ".part", directory)
                        val digest = MessageDigest.getInstance("SHA-256")
                        val chunks = mutableListOf<String>()
                        FileOutputStream(part).use { output ->
                            var remaining = length
                            while (remaining > 0L) {
                                val n = minOf(remaining, buffer.size.toLong()).toInt()
                                try { input.readFully(buffer, 0, n) } catch (_: java.io.EOFException) { fail("SOURCE_CHANGED") }
                                output.write(buffer, 0, n)
                                whole.update(buffer, 0, n)
                                digest.update(buffer, 0, n)
                                chunks += B2Frames.hex(MessageDigest.getInstance("SHA-256").apply { update(buffer, 0, n) }.digest())
                                remaining -= n
                                authorize()
                                work()
                            }
                        }
                        val meta = obj("bookId" to id, "fingerprint" to fingerprint, "packageSha256" to hash,
                            "packageBytes" to size, "segmentIndex" to index, "segmentCount" to count, "segmentOffset" to offset)
                        val d = binaryDescriptor(BOOK_SEGMENT, "$id:$hash:$index", meta,
                            B2FileHashes(length, B2Frames.hex(digest.digest()), chunks, emptyList()), listOf(stateId))
                        segments += d.s("objectId")
                        add(d, part)
                    }
                    if (input.read() != -1 || B2Frames.hex(whole.digest()) != hash) fail("SOURCE_CHANGED")
                }
                val meta = obj("bookId" to id, "fingerprint" to fingerprint, "packageSha256" to hash,
                    "packageBytes" to size, "segmentObjectIds" to JSONArray(segments))
                add(binaryDescriptor(BOOK_COMMIT, id, meta, emptyHashes, (segments + stateId).sorted()), empty)
            }
            var after = 0
            var page = 0
            while (true) {
                val rows = books.binaryReadingPage(id, after)
                if (rows.isEmpty()) break
                val json = rows.joinToString(",", "[", "]") { it.stateJson }
                reserve(B2Json.utf8(json).size.toLong())
                val file = writeObject(directory, json)
                val meta = obj("bookId" to id, "pageIndex" to page)
                add(binaryDescriptor(BOOK_READING, "$id:$page", meta, binaryFileHashes(file, work), listOf(stateId)), file)
                after = rows.last().chapterIndex
                page++
                work()
            }
        }
    }

    private fun writeObject(directory: File, json: String): File =
        File.createTempFile("book-", ".json", directory).apply { writeText(json) }

    private fun stateKey(job: String, book: String) = "$job:$book"

    fun scratchBytes(objects: List<JSONObject>): Long = objects.filter { it.s("kind") == BOOK_COMMIT }.sumOf {
        val bytes = B2Metadata.validate(it).n("packageBytes")
        // Retained segments are exchanged for a verified assembly before body
        // extraction. Reserve the overlapping raw/decoded copy and Room shadow/WAL space.
        val rows = minOf(100_000L, (bytes + 63) / 64)
        bytes + rows * 4096 + 65536
    }

    suspend fun apply(context: B2ApplyContext, descriptor: JSONObject, metadata: JSONObject,
        staged: B2FileRef, work: () -> Unit): String = try {
        val check = { context.check(); authorize(); work() }
        val id = metadata.s("bookId")
        val key = stateKey(context.jobId, id)
        check()
        when (descriptor.s("kind")) {
            BOOK_STATE -> {
                val state = decodeState(readJson(staged))
                if (state.s("bookId") != id) fail("OBJECT_CONFLICT")
                val witness = obj("fingerprint" to state.s("fingerprint"), "chapterCount" to state.n("chapterCount"))
                state.optJSONObject("lifecycle")?.let { witness.put("lifecycle", it) }
                persistence.put(scope, "bookStateWitness", key, witness)
                val changed = books.merge(state, check, binary = true)
                if (changed > 0) "merged"
                else if (BookSync.canonical(state) == books.binaryState(id)?.let(BookSync::canonical)) "alreadyPresent"
                else "retainedLocal"
            }
            BOOK_SEGMENT -> {
                val state = persistence.get(scope, "bookStateWitness", key) ?: fail("DEPENDENCIES_PENDING")
                if (state.s("fingerprint") != metadata.s("fingerprint")) fail("OBJECT_CONFLICT")
                // Receiver keeps this immutable FileRef after writing the same-DB APPLIED receipt.
                if (!staged.file.isFile || staged.file.length() != staged.size) fail("LOCAL_IO")
                "merged"
            }
            BOOK_COMMIT -> {
                val state = persistence.get(scope, "bookStateWitness", key) ?: fail("DEPENDENCIES_PENDING")
                val fingerprint = metadata.s("fingerprint")
                if (state.s("fingerprint") != fingerprint) fail("OBJECT_CONFLICT")
                val hash = metadata.s("packageSha256")
                val bytes = metadata.n("packageBytes")
                val assembly = localAssembly(context, metadata)
                val scratch = java.nio.file.Files.createTempDirectory(books.binaryImportDirectory().toPath(), ".b2-import-").toFile()
                try {
                    books.installBinaryPackage(id, fingerprint, hash, bytes, state, assembly, scratch, check)
                } finally {
                    scratch.deleteRecursively()
                }
            }
            BOOK_READING -> {
                val state = persistence.get(scope, "bookStateWitness", key) ?: fail("DEPENDENCIES_PENDING")
                val rows = B2Json.parse("{\"rows\":" + readJson(staged) + "}", false).a("rows")
                if (rows.length() !in 1..100) fail("BUSINESS_REJECTED")
                val accepted = mutableListOf<JSONObject>()
                var previous = 0L
                val count = state.n("chapterCount")
                val bitmap = persistence.get(scope, "bookReadingIndices", key)?.s("bits")?.let { B2Frames.unhex(it, 12500) }
                    ?: ByteArray(12500)
                for (i in 0 until rows.length()) {
                    val row = rows.getJSONObject(i)
                    BookSync.validateReading(row)
                    val index = row.n("chapterIndex")
                    if (row.s("bookId") != id || index <= previous || index > count) fail("BUSINESS_REJECTED")
                    val bit = index.toInt() - 1
                    val mask = 1 shl (bit % 8)
                    if ((bitmap[bit / 8].toInt() and mask) != 0) fail("OBJECT_CONFLICT")
                    bitmap[bit / 8] = (bitmap[bit / 8].toInt() or mask).toByte()
                    previous = index
                    accepted += row
                }
                persistence.put(scope, "bookReadingIndices", key, obj("bits" to B2Frames.hex(bitmap)))
                if (books.mergeReadingBatch(accepted, check, binary = true) > 0) "merged" else "retainedLocal"
            }
            else -> fail("UNSUPPORTED_KIND")
        }
    } catch (_: java.nio.charset.CharacterCodingException) {
        fail("BUSINESS_REJECTED")
    } catch (_: IOException) {
        fail("LOCAL_IO")
    } catch (_: IllegalArgumentException) {
        fail("BUSINESS_REJECTED")
    } catch (_: org.json.JSONException) {
        fail("BUSINESS_REJECTED")
    }

    private fun readJson(staged: B2FileRef): String {
        // Whitespace counts towards the wire object limit. It does not consume
        // the local SQLite row budget. Preserve strings and token boundaries.
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        val json = StringBuilder()
        var quoted = false
        var escaped = false
        var gap = false
        java.io.InputStreamReader(staged.file.inputStream(), decoder).buffered().use { input ->
            val buffer = CharArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                for (i in 0 until count) {
                    val c = buffer[i]
                    if (!quoted && c in " \t\r\n") {
                        gap = true
                        continue
                    }
                    if (gap && json.isNotEmpty() && json.last() !in "{}[],:" && c !in "{}[],:") {
                        fail("BUSINESS_REJECTED")
                    }
                    gap = false
                    if (quoted) {
                        if (escaped) escaped = false
                        else if (c == '\\') escaped = true
                        else if (c == '"') quoted = false
                    } else if (c == '"') quoted = true
                    if (json.length >= 2 * 1024 * 1024) fail("LIMIT_EXCEEDED")
                    json.append(c)
                }
            }
        }
        return json.toString()
    }

    private fun decodeState(json: String): JSONObject = B2Json.parse(json, maxNodes = 100032).also(BookSync::validateBinary)

    private fun localAssembly(context: B2ApplyContext, metadata: JSONObject): File =
        context.assembly?.takeIf { it.isFile } ?: books.binaryPackage(metadata.s("bookId"), metadata.s("packageSha256"))
            .takeIf { it.isFile } ?: fail("JOURNAL_CORRUPT")

    suspend fun prepareCommit(context: B2ApplyContext, metadata: JSONObject, work: () -> Unit) {
        val check = { context.check(); authorize(); work() }
        val original = requireNotNull(context.assembly)
        val segments = metadata.a("segmentObjectIds").strings()
        try {
            if (context.assemblyReady()) {
                val path = localAssembly(context, metadata)
                if (path.length() != metadata.n("packageBytes") || hashFile(path, check) != metadata.s("packageSha256")) fail("HASH_MISMATCH")
                if (path == original) context.publishAssembly(original, segments)
                return
            }
            if (original.isFile) {
                if (original.length() == metadata.n("packageBytes") && hashFile(original, check) == metadata.s("packageSha256")) {
                    context.publishAssembly(original, segments)
                    return
                }
                if (!original.delete()) fail("LOCAL_IO")
            }
            val temp = File.createTempFile("assemble-", ".part", original.parentFile)
            try {
                combine(context, metadata, temp, check)
                context.publishAssembly(temp, segments)
            } finally {
                temp.delete()
            }
        } catch (_: IOException) {
            fail("LOCAL_IO")
        }
    }

    private fun hashFile(file: File, check: () -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(B2Wire.CHUNK)
        file.inputStream().use { input ->
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
                check()
            }
        }
        return B2Frames.hex(digest.digest())
    }

    private suspend fun combine(context: B2ApplyContext, metadata: JSONObject, target: File, check: () -> Unit) {
        val buffer = ByteArray(B2Wire.CHUNK)
        val digest = MessageDigest.getInstance("SHA-256")
        val size = metadata.n("packageBytes")
        var total = 0L
        FileOutputStream(target).use { out ->
            for (id in metadata.a("segmentObjectIds").strings()) {
                val file = context.segment(id)
                if (file.file.length() != file.size) fail("LOCAL_IO")
                file.file.inputStream().use { input ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > size) fail("HASH_MISMATCH")
                        out.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        check()
                    }
                }
            }
            if (total != size || B2Frames.hex(digest.digest()) != metadata.s("packageSha256")) fail("HASH_MISMATCH")
            out.fd.sync()
        }
    }
}
