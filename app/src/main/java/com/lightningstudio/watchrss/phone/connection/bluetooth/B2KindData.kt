package com.lightningstudio.watchrss.phone.connection.bluetooth

import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.B2Digests
import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.B2Exception
import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.B2Frames
import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.B2Json
import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.B2Metadata
import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.B2Wire
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

internal const val B2_ARTICLE_BODY = "articleBody"
internal const val B2_ARTICLE_METADATA = "articleMetadata"
internal const val B2_RSS_SOURCE = "rssSource"
internal const val B2_NOTE_ASSET = "noteAsset"

internal data class B2FileHashes(
    val size: Long,
    val sha256: String,
    val chunks: List<String>,
    val legacyChunks: List<String>
)

internal fun binaryFileHashes(file: File, work: () -> Unit = {}): B2FileHashes {
    val whole = MessageDigest.getInstance("SHA-256")
    val chunk = MessageDigest.getInstance("SHA-256")
    val legacy = MessageDigest.getInstance("SHA-256")
    val chunks = mutableListOf<String>()
    val legacyChunks = mutableListOf<String>()
    var size = 0L
    val buffer = ByteArray(65536)
    file.inputStream().use { input ->
        while (true) {
            var count = 0
            while (count < buffer.size) {
                val read = input.read(buffer, count, buffer.size - count)
                if (read < 0) break
                count += read
            }
            if (count == 0) break
            size += count
            if (size > 16 * 1024 * 1024) throw B2Exception("LIMIT_EXCEEDED")
            whole.update(buffer, 0, count)
            chunk.update(buffer, 0, count)
            legacy.update(buffer, 0, count)
            chunks += B2Frames.hex(chunk.digest())
            if (chunks.size % 2 == 0 || count < buffer.size) legacyChunks += B2Frames.hex(legacy.digest())
            work()
        }
    }
    if (chunks.size % 2 == 1 && size % buffer.size == 0L) legacyChunks += B2Frames.hex(legacy.digest())
    return B2FileHashes(size, B2Frames.hex(whole.digest()), chunks, legacyChunks)
}

internal fun binaryDescriptor(kind: String, id: String, metadata: JSONObject, hashes: B2FileHashes,
    dependsOn: List<String> = emptyList()): JSONObject {
    val raw = metadata.toString()
    val metadataHash = B2Digests.hash(B2Json.utf8(raw))
    val encoding = when (kind) {
        B2_ARTICLE_BODY -> "article-v14"
        B2_NOTE_ASSET -> "raw"
        else -> "none"
    }
    val contentVersion = when (kind) {
        B2_NOTE_ASSET -> hashes.sha256
        B2_ARTICLE_BODY -> "${hashes.sha256}:$metadataHash"
        else -> metadataHash
    }
    val descriptor = JSONObject().put("kind", kind).put("businessId", id).put("schemaVersion", 1)
        .put("contentVersion", contentVersion)
        .put("encoding", encoding).put("size", hashes.size).put("chunkBytes", 65536)
        .put("sha256", hashes.sha256).put("chunkHashes", JSONArray(hashes.chunks))
        .put("metadataUtf8", raw).put("metadataSha256", metadataHash).put("dependsOn", JSONArray(dependsOn))
    descriptor.put("objectId", B2Digests.objectId(descriptor))
    B2Wire.descriptor(descriptor)
    val parsed = B2Metadata.validate(descriptor)
    when (kind) {
        B2_ARTICLE_BODY, B2_ARTICLE_METADATA -> validateBinaryArticle(descriptor, parsed)
        B2_RSS_SOURCE -> validateBinarySource(descriptor, parsed)
    }
    return descriptor
}

internal fun validateBinaryArticle(descriptor: JSONObject, metadata: JSONObject) {
    fun reject(): Nothing = throw B2Exception("BUSINESS_REJECTED")
    for (key in listOf("articleId", "sourceDeviceId", "url", "title", "siteName", "excerpt", "contentHash", "readingPositionContentHash")) {
        if (metadata.opt(key) !is String) reject()
    }
    for (key in listOf("imageUrl", "rssSourceUrl", "rssSourceTitle")) {
        if (!metadata.isNull(key) && metadata.opt(key) !is String) reject()
    }
    for (key in listOf("independentSaved", "favoriteSaved", "watchLaterSaved", "deleted", "isRead")) {
        if (metadata.opt(key) !is Boolean) reject()
    }
    for (key in listOf("importedAt", "updatedAt", "independentChangedAt", "independentSortOrder",
        "favoriteChangedAt", "favoriteSortOrder", "watchLaterChangedAt", "watchLaterSortOrder",
        "deletedAt", "readingPositionBytes", "readingPositionChangedAt")) {
        if (!isSafeLegacyInteger(metadata.opt(key))) reject()
    }
    val progress = (metadata.opt("readingProgress") as? Number)?.toDouble() ?: reject()
    if (progress !in 0.0..1.0 || metadata.getString("url").isBlank()) reject()
    if (metadata.has("legacyArticleIds")) {
        val aliases = metadata.optJSONArray("legacyArticleIds") ?: reject()
        val seen = mutableSetOf<String>()
        for (index in 0 until aliases.length()) {
            val id = aliases.opt(index) as? String ?: reject()
            if (!id.matches(Regex("[0-9a-f]{64}")) || !seen.add(id)) reject()
        }
        if (!descriptor.getString("businessId").matches(Regex("[0-9a-f]{64}"))) reject()
    }
    val body = metadata.getJSONObject("body")
    val hash = body.getString("bodyHash")
    val size = body.getLong("bodyByteCount")
    val metadataOnly = body.getBoolean("metadataOnly")
    if ((!metadataOnly && size == 0L) || (size == 0L && hash.isNotEmpty()) ||
        (size > 0L && !hash.matches(Regex("[0-9a-f]{64}")))) reject()
    if (descriptor.getJSONArray("dependsOn").length() > 1) throw B2Exception("OBJECT_CONFLICT")
}

internal fun validateBinarySource(descriptor: JSONObject, metadata: JSONObject) {
    if (!isSafeLegacyInteger(metadata.opt("sortOrder")) || descriptor.getJSONArray("dependsOn").length() != 0) {
        throw B2Exception("BUSINESS_REJECTED")
    }
}

private fun isSafeLegacyInteger(value: Any?): Boolean {
    val number = (value as? Number)?.toDouble() ?: return false
    return number in -9007199254740991.0..9007199254740991.0 && number == number.toLong().toDouble()
}

/** The source-store URL rules used by the watch. This key is for matching plan
 * dependencies only. It does not change the frozen metadata or the stored URL. */
internal fun binaryRssDependencyKey(value: String): String {
    val trimmed = value.trim()
    if (trimmed.isEmpty() || "://" in trimmed || trimmed.startsWith('/') || trimmed.any(Char::isWhitespace)) {
        return trimmed
    }
    val authority = trimmed.takeWhile { it != '/' && it != '?' && it != '#' }
    val host = authority.substringBefore(':')
    if (host.equals("localhost", ignoreCase = true) ||
        '.' in host && !host.startsWith('.') && !host.endsWith('.') &&
        host.all { it.isLetterOrDigit() || it == '.' || it == '-' }) {
        return "http://$trimmed"
    }
    return trimmed
}

internal class B2RssDependencies {
    private val sources = mutableMapOf<String, String>()

    fun addSource(descriptor: JSONObject) {
        val key = binaryRssDependencyKey(descriptor.getString("businessId"))
        val id = descriptor.getString("objectId")
        val previous = sources.put(key, id)
        if (previous != null && previous != id) throw B2Exception("PLAN_CONFLICT")
    }

    fun forArticle(metadata: JSONObject): List<String> {
        val url = metadata.opt("rssSourceUrl")
        if (url == null || url == JSONObject.NULL) return emptyList()
        if (url !is String) throw B2Exception("BUSINESS_REJECTED")
        return listOfNotNull(sources[binaryRssDependencyKey(url)])
    }
}

internal fun validateBinaryRssDependencies(objects: List<JSONObject>, work: () -> Unit = {}) {
    val dependencies = B2RssDependencies()
    for (descriptor in objects) {
        if (descriptor.getString("kind") == B2_RSS_SOURCE) {
            if (descriptor.getJSONArray("dependsOn").length() != 0) throw B2Exception("PLAN_CONFLICT")
            val metadata = B2Metadata.validate(descriptor)
            if (metadata.opt("url") != descriptor.getString("businessId")) throw B2Exception("OBJECT_CONFLICT")
            validateBinarySource(descriptor, metadata)
            dependencies.addSource(descriptor)
        }
        work()
    }
    for (descriptor in objects) {
        val kind = descriptor.getString("kind")
        if (kind != B2_ARTICLE_BODY && kind != B2_ARTICLE_METADATA) continue
        val expected = dependencies.forArticle(B2Metadata.validate(descriptor))
        val declared = descriptor.getJSONArray("dependsOn")
        if (declared.length() != expected.size || expected.indices.any { declared.getString(it) != expected[it] }) {
            throw B2Exception("PLAN_CONFLICT")
        }
        work()
    }
}
