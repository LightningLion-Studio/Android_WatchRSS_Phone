package com.lightningstudio.watchrss.phone.connection.bluetooth.b2

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** Private cache only. The engine's writer lane covers validation, publish and receipt.
 * Never release business-referenced files here: adapters publish their own immutable files. */
class B2Files internal constructor(private val root: File, private val deleteCache: (File) -> Boolean) {
    constructor(root: File) : this(root, { it.delete() })
    var onProgress: (String) -> Unit = {}
    private val activeJob = ThreadLocal<String>()
    internal fun <T> working(job: String, action: () -> T): T { activeJob.set(job); try { return action() } finally { activeJob.remove() } }
    private fun progress() { activeJob.get()?.let(onProgress) }
    // The internal delete port lets host tests retain a retryable cache deletion failure.
    init { root.mkdirs(); if (!root.isDirectory) fail("LOCAL_IO") }
    private fun local(vararg parts: String) = File(root, B2Digests.d("wrss.library-binary.v2/local-file", parts.toList()))
    internal fun chunk(scope: String, job: String, oid: String, index: Int) = local("chunk", scope, job, oid, index.toString())
    internal fun composed(scope: String, job: String, oid: String) = local("object", scope, job, oid)
    internal fun frozen(scope: String, snapshot: String, oid: String) = local("source", scope, snapshot, oid)
    private val requests by lazy { try { B2RequestBindings(File(root, "request-bindings")) } catch (_: java.io.IOException) { fail("LOCAL_IO") } }
    fun dataBytes() = root.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
    fun usedBytes() = dataBytes() + requests.chargedBytes()
    internal fun checkRequest(scope: String, job: String, peer: String, id: String, digest: String) = requests.check(scope, job, peer, id, digest)
    internal fun bindRequest(scope: String, job: String, peer: String, id: String, digest: String, limit: Long) = requests.bind(scope, job, peer, id, digest, limit)
    internal fun durable(file: File, bytes: ByteArray, budget: Long) {
        if (usedBytes() + bytes.size > budget) fail("STORAGE_PRESSURE")
        val temp = File.createTempFile("publish-", ".tmp", root)
        try {
            FileOutputStream(temp).use { it.write(bytes); progress(); it.fd.sync(); progress() }
            java.nio.file.Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            FileChannel.open(root.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (e: java.io.IOException) { throw B2Exception("LOCAL_IO") }
        finally { temp.delete() }
    }
    internal fun receiveIndex(job: String, descriptors: List<JSONObject>): B2ReceiveIndex {
        val file = try { File.createTempFile("receive-index-", ".tmp", root) }
        catch (_: java.io.IOException) { fail("LOCAL_IO") }
        return working(job) { B2ReceiveIndex.create(file, descriptors, ::progress) }
    }

    internal fun writeChunk(bytes: ByteArray, length: Int, check: () -> Unit): File {
        check()
        val temp = try { File.createTempFile("publish-", ".tmp", root) } catch (_: java.io.IOException) { fail("LOCAL_IO") }
        try {
            FileOutputStream(temp).use { it.write(bytes, 0, length) }
            return temp
        } catch (e: Throwable) {
            temp.delete()
            if (e is java.io.IOException) fail("LOCAL_IO")
            throw e
        }
    }

    internal fun publishChunks(chunks: List<Pair<File, File>>, check: () -> Unit) {
        if (chunks.isEmpty()) return
        try {
            for ((temp, _) in chunks) {
                check()
                FileChannel.open(temp.toPath(), StandardOpenOption.WRITE).use { it.force(true) }
            }
            for ((temp, target) in chunks) {
                check()
                java.nio.file.Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }
            FileChannel.open(root.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (_: java.io.IOException) { fail("LOCAL_IO") }
    }

    internal fun verified(file: File, size: Long, hash: String): Boolean = file.isFile && file.length() == size && file.inputStream().use { hashStream(it) } == hash
    internal fun hashStream(input: java.io.InputStream): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
        while (true) { val n = input.read(buffer); if (n < 0) break; if (n > 0) { digest.update(buffer, 0, n); progress() } }
        return B2Frames.hex(digest.digest())
    }
    internal fun publish(source: File, target: File, size: Long, hash: String, budget: Long) {
        if (usedBytes() + size > budget || root.usableSpace < size + 1048576) fail("STORAGE_PRESSURE")
        val temp = File.createTempFile("publish-", ".tmp", root)
        try {
            val digest = java.security.MessageDigest.getInstance("SHA-256"); var total = 0L
            java.io.FileOutputStream(temp).use { out -> source.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) { val n = input.read(buffer); if (n < 0) break; if (n > 0) { total += n; if (total > size) fail("SOURCE_CHANGED"); out.write(buffer, 0, n); digest.update(buffer, 0, n); progress() } }
            }; out.fd.sync() }
            if (total != size || B2Frames.hex(digest.digest()) != hash) fail("SOURCE_CHANGED")
            java.nio.file.Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            FileChannel.open(root.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (_: java.io.IOException) { fail("LOCAL_IO") } finally { temp.delete() }
    }
    internal fun availableBytes() = root.usableSpace
    internal fun missing(scope: String, job: String, d: JSONObject): List<Int> = d.a("chunkHashes").strings().indices.filter { index ->
        !verified(chunk(scope, job, d.s("objectId"), index), minOf(B2Wire.CHUNK.toLong(), d.n("size") - index * B2Wire.CHUNK.toLong()), d.a("chunkHashes").getString(index))
    }
    internal fun stage(scope: String, job: String, d: JSONObject, index: Int, bytes: ByteArray, budget: Long) {
        val target = chunk(scope, job, d.s("objectId"), index)
        if (!verified(target, bytes.size.toLong(), d.a("chunkHashes").getString(index))) durable(target, bytes, budget)
    }
    internal fun finalize(scope: String, job: String, d: JSONObject, budget: Long): File {
        val target = composed(scope, job, d.s("objectId"))
        if (verified(target, d.n("size"), d.s("sha256"))) return target
        if (missing(scope, job, d).isNotEmpty()) fail("CHUNKS_MISSING")
        if (usedBytes() + d.n("size") > budget) fail("STORAGE_PRESSURE")
        val temp = File.createTempFile("compose-", ".tmp", root)
        try {
            val digest = java.security.MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
            FileOutputStream(temp).use { out ->
                d.a("chunkHashes").strings().indices.forEach { index ->
                    chunk(scope, job, d.s("objectId"), index).inputStream().use { input ->
                        while (true) { val n = input.read(buffer); if (n < 0) break; if (n > 0) { out.write(buffer, 0, n); digest.update(buffer, 0, n); progress() } }
                    }
                }; out.fd.sync()
            }
            if (temp.length() != d.n("size") || B2Frames.hex(digest.digest()) != d.s("sha256")) fail("HASH_MISMATCH")
            java.nio.file.Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            FileChannel.open(root.toPath(), StandardOpenOption.READ).use { it.force(true) }
            return target
        } catch (_: java.io.IOException) { fail("LOCAL_IO") } finally { temp.delete() }
    }

    internal fun release(scope: String, job: String, d: JSONObject): Boolean {
        val paths = d.a("chunkHashes").strings().indices.map { chunk(scope, job, d.s("objectId"), it) } + composed(scope, job, d.s("objectId"))
        var released = true
        for (path in paths) {
            try { if (path.exists() && !deleteCache(path)) released = false }
            catch (_: java.io.IOException) { released = false }
            catch (_: SecurityException) { released = false }
        }
        return released
    }
}

/** Validates transport/business bindings before an adapter sees an object.
 * Domain adapters remain responsible for legacy schema, merge clocks and P6 inbox gates. */
object B2Metadata {
    fun validate(d: JSONObject): JSONObject {
        val m = B2Json.parse(B2Json.utf8(d.s("metadataUtf8")), false)
        when (d.s("kind")) {
            "rssSource" -> {
                B2Json.exact(m, "url", "sourceDeviceId", "title", "description", "siteUrl", "imageUrl", "createdAt", "updatedAt", "sortOrder", "isPinned", "deleted", "deletedAt")
                if (m.s("url") != d.s("businessId")) fail("OBJECT_CONFLICT")
                listOf("url", "sourceDeviceId", "title", "description").forEach { if (m.get(it) !is String) fail("BUSINESS_REJECTED") }
                listOf("siteUrl", "imageUrl").forEach { if (!m.nil(it) && m.get(it) !is String) fail("BUSINESS_REJECTED") }
                listOf("createdAt", "updatedAt", "deletedAt").forEach { m.n(it) }; if (m.get("sortOrder") !is Number) fail("BUSINESS_REJECTED")
                B2Json.bool(m.get("isPinned")); B2Json.bool(m.get("deleted"))
            }
            "noteAsset" -> {
                B2Json.exact(m, "storageKey", "sha256", "size")
                if (!m.s("storageKey").matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,159}")) || m.n("size") > 5 * 1024 * 1024) fail("BUSINESS_REJECTED")
                if (m.s("storageKey") != d.s("businessId") || m.s("sha256") != d.s("sha256") || m.n("size") != d.n("size")) fail("OBJECT_CONFLICT")
            }
            "readerResource" -> {
                B2Json.exact(m, "kind", "fileName", "sha256", "byteCount")
                if (m.s("kind") !in listOf("font", "background", "variant") || !m.s("fileName").matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,254}")) || ".." in m.s("fileName")) fail("BUSINESS_REJECTED")
                if (m.s("sha256") != d.s("sha256") || m.n("byteCount") != d.n("size")) fail("OBJECT_CONFLICT")
            }
            "readerMetadata" -> {
                B2Json.exact(m, "presets", "fonts", "backgrounds", "deletions")
                val entities = listOf("presets" to "preset", "fonts" to "font", "backgrounds" to "background").flatMap { (key, kind) -> m.a(key).values().map { kind to (it as? JSONObject ?: fail("BUSINESS_REJECTED")) } }
                val ds = m.a("deletions").values().map { it as? JSONObject ?: fail("BUSINESS_REJECTED") }
                if (entities.size > 1 || ds.size > 1 || entities.size + ds.size == 0) fail("BUSINESS_REJECTED")
                val target = entities.firstOrNull()?.let { it.first + ":" + it.second.s("id") } ?: (ds[0].s("kind") + ":" + ds[0].s("entityId"))
                if (target != d.s("businessId") || ds.any { it.s("kind") + ":" + it.s("entityId") != target }) fail("BUSINESS_REJECTED")
            }
            "articleBody", "articleMetadata" -> {
                val fields = setOf("articleId", "legacyArticleIds", "sourceDeviceId", "url", "title", "siteName", "excerpt", "imageUrl", "contentHash", "importedAt", "updatedAt", "independentSaved", "independentChangedAt", "independentSortOrder", "rssSourceUrl", "rssSourceTitle", "favoriteSaved", "favoriteChangedAt", "favoriteSortOrder", "watchLaterSaved", "watchLaterChangedAt", "watchLaterSortOrder", "deleted", "deletedAt", "readingProgress", "readingPositionBytes", "readingPositionContentHash", "readingPositionChangedAt", "isRead", "body")
                if (m.keys().asSequence().any { it !in fields }) fail("BUSINESS_REJECTED")
                if (m.s("articleId") != d.s("businessId")) fail("OBJECT_CONFLICT")
                val b = B2Json.exact(m.get("body"), "bodyHash", "bodyByteCount", "chunkSize", "chunkHashes", "metadataOnly")
                if (B2Json.bool(b.get("metadataOnly")) != (d.s("kind") == "articleMetadata")) fail("OBJECT_CONFLICT")
                val n = b.n("bodyByteCount"); if (n > 16777216 || b.n("chunkSize") != 131072L && !(d.s("kind") == "articleMetadata" && n == 0L && b.n("chunkSize") == 0L && b.s("bodyHash") == "")) fail("OBJECT_CONFLICT")
                if (b.a("chunkHashes").length().toLong() != (n + 131071) / 131072) fail("OBJECT_CONFLICT")
                b.a("chunkHashes").strings().forEach { B2Json.hex(it) }
                if (d.s("kind") == "articleBody" && (b.s("bodyHash") != d.s("sha256") || n != d.n("size"))) fail("OBJECT_CONFLICT")
            }
        }
        return m
    }
}
