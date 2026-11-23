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
    fun importRoot(): File = books.binaryImportDirectory()

    suspend fun committedPackages(): JSONArray = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val result = JSONArray()
        books.visitCommittedBinaryPackages(B2BookState.MAX_PROOFS, { file -> hashFile(file, authorize) }) { row ->
            authorize()
            result.put(B2BookState.proof(JSONObject(row.stateJson), row.packageSha256, row.packageBytes))
        }
        result
    }

    suspend fun freeze(directory: File, work: () -> Unit,
        allow: (JSONObject) -> Boolean = { true }, warn: (BookSyncIssue) -> Unit = {},
        omitPackage: (JSONObject) -> Boolean = { false },
        add: (JSONObject, File) -> Unit) {
        val staging = B2Files(requireNotNull(directory.absoluteFile.parentFile), importRoot())
        val empty = File(directory, "empty").apply { createNewFile() }
        val emptyHashes = binaryFileHashes(empty)
        suspend fun reserve(bytes: Long) {
            val active = persistence.exclusive {
                persistence.list(scope, "job").sumOf { (_, row) ->
                    if (row.s("state") in setOf("COMPLETE", "CANCELLED")) 0 else row.optLong("stagingReservation")
                }
            }
            val limits = B2Wire.s4Limits()
            if (staging.dataBytes() + bytes + active + limits.n("maxJournalBytes") > limits.n("maxStagingBytes") ||
                bytes + active + limits.n("maxJournalBytes") + 1048576 > directory.usableSpace) fail("STORAGE_PRESSURE")
        }
        books.freezeBinaryBooks { row, packageFile ->
            authorize()
            val id = row.bookId
            fun tooLarge() = warn(B2BookMetadataLimits.issue(id))
            val stateBytes = B2BookMetadataLimits.bodyBytes(row.stateJson)
            if (stateBytes > B2BookMetadataLimits.BODY_BYTES) {
                tooLarge()
                return@freezeBinaryBooks
            }
            val state = decodeState(row.stateJson)
            if (!B2BookMetadataLimits.stringsFit(state)) {
                tooLarge()
                return@freezeBinaryBooks
            }
            reserve(stateBytes.toLong())
            val stateFile = writeObject(directory, row.stateJson)
            val stateObject = binaryDescriptor(BOOK_STATE, id, obj("bookId" to id), binaryFileHashes(stateFile, work))
            if (!allow(stateObject)) {
                stateFile.delete()
                return@freezeBinaryBooks
            }
            add(stateObject, stateFile)
            val stateId = stateObject.s("objectId")
            if (packageFile != null && !state.getBoolean("deleted") &&
                !omitPackage(B2BookState.proof(state, row.packageSha256, row.packageBytes))) {
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
            val reading = mutableListOf<String>()
            var pageBytes = 2L
            suspend fun emitPage() {
                if (reading.isEmpty()) return
                reserve(pageBytes)
                val file = File.createTempFile("book-", ".json", directory)
                file.bufferedWriter().use { output ->
                    output.write("[")
                    reading.forEachIndexed { index, value ->
                        if (index != 0) output.write(",")
                        output.write(value)
                    }
                    output.write("]")
                }
                val meta = obj("bookId" to id, "pageIndex" to page)
                val descriptor = binaryDescriptor(BOOK_READING, "$id:$page", meta,
                    binaryFileHashes(file, work), listOf(stateId))
                if (allow(descriptor)) {
                    add(descriptor, file)
                    page++
                } else file.delete()
                reading.clear()
                pageBytes = 2
                work()
            }
            while (true) {
                val rows = books.binaryReadingPage(id, after)
                if (rows.isEmpty()) break
                for (row in rows) {
                    val raw = row.stateJson
                    val bytes = B2BookMetadataLimits.bodyBytes(raw)
                    if (bytes + 2 > B2BookMetadataLimits.BODY_BYTES ||
                        !B2BookMetadataLimits.stringsFit(B2Json.parse(raw, false))) {
                        tooLarge()
                        continue
                    }
                    val separator = if (reading.isEmpty()) 0 else 1
                    if (reading.size == 100 || pageBytes + separator + bytes > B2BookMetadataLimits.BODY_BYTES) emitPage()
                    if (reading.isNotEmpty()) pageBytes++
                    reading += raw
                    pageBytes += bytes
                }
                after = rows.last().chapterIndex
            }
            emitPage()
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
                val state = readJson(staged, true, 100032, check) { it.state() }.also(BookSync::validateBinary)
                if (state.s("bookId") != id) fail("OBJECT_CONFLICT")
                val witness = obj("jobId" to context.jobId, "fingerprint" to state.s("fingerprint"), "chapterCount" to state.n("chapterCount"))
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
                B2Scratch.open(importRoot(), B2Scratch.BOOK_IMPORT).use { scratch ->
                    books.installBinaryPackage(id, fingerprint, hash, bytes, state, assembly, scratch.file, check)
                }
            }
            BOOK_READING -> {
                val state = persistence.get(scope, "bookStateWitness", key) ?: fail("DEPENDENCIES_PENDING")
                val rows = readJson(staged, false, 32766, check) { it.readings() }
                var previous = 0L
                val count = state.n("chapterCount")
                val bitmap = persistence.get(scope, "bookReadingIndices", key)?.s("bits")?.let { B2Frames.unhex(it, 12500) }
                    ?: ByteArray(12500)
                for (row in rows) {
                    BookSync.validateReading(row)
                    val index = row.n("chapterIndex")
                    if (row.s("bookId") != id || index <= previous || index > count) fail("BUSINESS_REJECTED")
                    val bit = index.toInt() - 1
                    val mask = 1 shl (bit % 8)
                    if ((bitmap[bit / 8].toInt() and mask) != 0) fail("OBJECT_CONFLICT")
                    bitmap[bit / 8] = (bitmap[bit / 8].toInt() or mask).toByte()
                    previous = index
                }
                persistence.put(scope, "bookReadingIndices", key, obj("jobId" to context.jobId, "bits" to B2Frames.hex(bitmap)))
                if (books.mergeReadingBatch(rows, check, binary = true) > 0) "merged" else "retainedLocal"
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

    private fun <T> readJson(staged: B2FileRef, integersOnly: Boolean, maxNodes: Int, work: () -> Unit,
        read: (B2BookJson) -> T): T {
        if (staged.size > B2Wire.MAX_OBJECT_BYTES) fail("LIMIT_EXCEEDED")
        if (staged.file.length() != staged.size) fail("LOCAL_IO")
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        return java.io.InputStreamReader(staged.file.inputStream(), decoder).use {
            read(B2BookJson(it, integersOnly, maxNodes, work))
        }
    }

    private fun decodeState(json: String): JSONObject =
        B2BookJson(java.io.StringReader(json), true, 100032) {}.state().also(BookSync::validateBinary)

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
