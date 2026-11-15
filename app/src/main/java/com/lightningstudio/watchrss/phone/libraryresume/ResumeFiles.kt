package com.lightningstudio.watchrss.phone.libraryresume

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** Private cache only. The engine's writer lane covers validation, publish and receipt.
 * Never release business-referenced files here: adapters publish their own immutable files. */
class ResumeFiles(private val root: File) {
    init { root.mkdirs(); if (!root.isDirectory) fail("LOCAL_IO") }
    private fun local(vararg parts: String) = File(root, ResumeDigests.d("wrss.library-resume.v1/local-file", parts.toList()))
    internal fun chunk(scope: String, job: String, oid: String, index: Int) = local("chunk", scope, job, oid, index.toString())
    internal fun composed(scope: String, job: String, oid: String) = local("object", scope, job, oid)
    internal fun frozen(scope: String, snapshot: String, oid: String) = local("source", scope, snapshot, oid)
    fun usedBytes() = root.listFiles()?.sumOf { it.length() } ?: 0L
    internal fun durable(file: File, bytes: ByteArray, budget: Long) {
        if (usedBytes() + bytes.size > budget) fail("STORAGE_PRESSURE")
        val temp = File.createTempFile("publish-", ".tmp", root)
        try {
            FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
            java.nio.file.Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            FileChannel.open(root.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (e: java.io.IOException) { throw ResumeException("LOCAL_IO") }
        finally { temp.delete() }
    }
    internal fun verified(file: File, size: Long, hash: String): Boolean = file.isFile && file.length() == size && ResumeDigests.hash(file.readBytes()) == hash
    internal fun missing(scope: String, job: String, d: JSONObject): List<Int> = d.a("chunkHashes").strings().indices.filter { index ->
        !verified(chunk(scope, job, d.s("objectId"), index), minOf(ResumeWire.CHUNK.toLong(), d.n("size") - index * ResumeWire.CHUNK.toLong()), d.a("chunkHashes").getString(index))
    }
    internal fun stage(scope: String, job: String, d: JSONObject, index: Int, bytes: ByteArray, budget: Long) {
        val target = chunk(scope, job, d.s("objectId"), index)
        if (!verified(target, bytes.size.toLong(), ResumeDigests.hash(bytes))) durable(target, bytes, budget)
    }
    internal fun finalize(scope: String, job: String, d: JSONObject, budget: Long): File {
        val target = composed(scope, job, d.s("objectId"))
        if (verified(target, d.n("size"), d.s("sha256"))) return target
        if (missing(scope, job, d).isNotEmpty()) fail("CHUNKS_MISSING")
        val bytes = ByteArray(d.n("size").toInt()); var offset = 0
        d.a("chunkHashes").strings().indices.forEach { val b = chunk(scope, job, d.s("objectId"), it).readBytes(); b.copyInto(bytes, offset); offset += b.size }
        if (ResumeDigests.hash(bytes) != d.s("sha256")) fail("HASH_MISMATCH")
        durable(target, bytes, budget); return target
    }
    internal fun release(scope: String, job: String, d: JSONObject) {
        d.a("chunkHashes").strings().indices.forEach { chunk(scope, job, d.s("objectId"), it).delete() }
        composed(scope, job, d.s("objectId")).delete()
    }
}

/** Validates transport/business bindings before an adapter sees an object.
 * Domain adapters remain responsible for legacy schema, merge clocks and P6 inbox gates. */
object ResumeMetadata {
    fun validate(d: JSONObject): JSONObject {
        val m = ResumeJson.parse(ResumeJson.b64(d.get("metadataBase64"), 16384), false)
        when (d.s("kind")) {
            "rssSource" -> {
                ResumeJson.exact(m, "url", "sourceDeviceId", "title", "description", "siteUrl", "imageUrl", "createdAt", "updatedAt", "sortOrder", "isPinned", "deleted", "deletedAt")
                if (m.s("url") != d.s("businessId")) fail("OBJECT_CONFLICT")
                listOf("url", "sourceDeviceId", "title", "description").forEach { if (m.get(it) !is String) fail("BUSINESS_REJECTED") }
                listOf("siteUrl", "imageUrl").forEach { if (!m.nil(it) && m.get(it) !is String) fail("BUSINESS_REJECTED") }
                listOf("createdAt", "updatedAt", "deletedAt").forEach { m.n(it) }; if (m.get("sortOrder") !is Number) fail("BUSINESS_REJECTED")
                ResumeJson.bool(m.get("isPinned")); ResumeJson.bool(m.get("deleted"))
            }
            "noteAsset" -> {
                ResumeJson.exact(m, "storageKey", "sha256", "size")
                if (!m.s("storageKey").matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,159}")) || m.n("size") > 5 * 1024 * 1024) fail("BUSINESS_REJECTED")
                if (m.s("storageKey") != d.s("businessId") || m.s("sha256") != d.s("sha256") || m.n("size") != d.n("size")) fail("OBJECT_CONFLICT")
            }
            "readerResource" -> {
                ResumeJson.exact(m, "kind", "fileName", "sha256", "byteCount")
                if (m.s("kind") !in listOf("font", "background", "variant") || !m.s("fileName").matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,254}")) || ".." in m.s("fileName")) fail("BUSINESS_REJECTED")
                if (m.s("sha256") != d.s("sha256") || m.n("byteCount") != d.n("size")) fail("OBJECT_CONFLICT")
            }
            "readerMetadata" -> {
                ResumeJson.exact(m, "presets", "fonts", "backgrounds", "deletions")
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
                val b = ResumeJson.exact(m.get("body"), "bodyHash", "bodyByteCount", "chunkSize", "chunkHashes", "metadataOnly")
                if (ResumeJson.bool(b.get("metadataOnly")) != (d.s("kind") == "articleMetadata")) fail("OBJECT_CONFLICT")
                val n = b.n("bodyByteCount"); if (n > 16777216 || b.n("chunkSize") != 131072L && !(d.s("kind") == "articleMetadata" && n == 0L && b.n("chunkSize") == 0L && b.s("bodyHash") == "")) fail("OBJECT_CONFLICT")
                if (b.a("chunkHashes").length().toLong() != (n + 131071) / 131072) fail("OBJECT_CONFLICT")
                b.a("chunkHashes").strings().forEach { ResumeJson.hex(it) }
                if (d.s("kind") == "articleBody" && (b.s("bodyHash") != d.s("sha256") || n != d.n("size"))) fail("OBJECT_CONFLICT")
            }
        }
        return m
    }
}
