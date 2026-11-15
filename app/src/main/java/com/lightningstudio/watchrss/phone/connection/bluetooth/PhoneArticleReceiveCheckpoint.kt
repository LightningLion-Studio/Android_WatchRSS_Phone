package com.lightningstudio.watchrss.phone.connection.bluetooth

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** A cache of verified body chunks, never an assertion that the library cursor was applied. */
internal class PhoneArticleReceiveCheckpoint(
    private val root: File, private val owner: String, private val peer: String
) {
    private var entries = emptyMap<String, ArticleSyncManifestEntry>()
    private var responseMayChange = emptySet<String>()
    private val applied = mutableSetOf<String>()
    val receivedIds = mutableSetOf<String>()
    var merged = 0; private set

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun key(e: ArticleSyncManifestEntry) = hash(listOf(owner, peer, e.articleId, e.bodyHash, e.bodyByteCount,
        e.chunkSize, e.chunkHashes.joinToString(","), e.metadataHash, e.updatedAt).joinToString("|").toByteArray())
    private fun chunk(e: ArticleSyncManifestEntry, index: Int) = File(File(root, key(e)), "$index.bin")
    fun bind(manifest: List<ArticleSyncManifestEntry>) {
        require(manifest.map { it.articleId }.distinct().size == manifest.size) { "同步清单包含重复文章" }
        entries = manifest.associateBy { it.articleId }
        responseMayChange = emptySet()
    }
    fun allowResponseChanges(sentArticleIds: Set<String>) { responseMayChange = sentArticleIds }
    private fun verified(e: ArticleSyncManifestEntry, index: Int): ByteArray? {
        require(index in e.chunkHashes.indices) { "同步正文分块越界" }
        val file = chunk(e, index)
        val size = minOf(e.chunkSize.toLong(), e.bodyByteCount - index.toLong() * e.chunkSize)
        if (size !in 0..ArticleSyncBody.CHUNK_SIZE_BYTES.toLong() || !file.isFile || file.length() != size) return null
        return file.readBytes().takeIf { hash(it) == e.chunkHashes[index] }
    }
    fun remaining(request: ArticleBodyRequest): ArticleBodyRequest {
        val e = entries[request.articleId] ?: error("同步正文不在清单中")
        require(e.bodyHash == request.bodyHash) { "同步正文版本已变化" }
        return if (request.metadataOnly) request else request.copy(chunkIndexes = request.chunkIndexes.filter { verified(e, it) == null })
    }
    fun stage(payload: ChunkedArticlePayload): ChunkedArticlePayload {
        val advertised = entries[payload.article.articleId] ?: error("同步正文不在清单中")
        // Frozen peers export requested articles after merging this phone's uploads. Their
        // response can therefore contain the merged revision rather than the old manifest.
        // Bind a new cache key; never combine its chunks with the advertised older revision.
        val e = if (payload.article.articleId in responseMayChange) advertised.copy(
            contentHash = payload.article.contentHash, updatedAt = payload.article.updatedAt,
            deleted = payload.article.deleted, deletedAt = payload.article.deletedAt,
            metadataHash = ArticleSyncBody.metadataHashFor(payload.article),
            bodyHash = payload.bodyHash, bodyByteCount = payload.bodyByteCount,
            chunkSize = payload.chunkSize, chunkHashes = payload.chunkHashes
        ).also { entries = entries + (it.articleId to it) } else advertised
        require(e.contentHash == payload.article.contentHash && e.updatedAt == payload.article.updatedAt &&
            e.deleted == payload.article.deleted && e.deletedAt == payload.article.deletedAt &&
            (e.metadataHash.isBlank() || e.metadataHash == ArticleSyncBody.metadataHashFor(payload.article))) { "同步清单元数据不一致" }
        if (payload.metadataOnly) return payload
        require(e.bodyHash == payload.bodyHash && e.bodyByteCount == payload.bodyByteCount &&
            e.chunkSize == payload.chunkSize && e.chunkHashes == payload.chunkHashes) { "同步清单正文不一致" }
        require(payload.chunks.map { it.index }.distinct().size == payload.chunks.size) { "同步正文包含重复分块" }
        if (e.bodyByteCount == 0L && e.chunkHashes.isEmpty()) return payload
        require(e.chunkSize in 1..ArticleSyncBody.CHUNK_SIZE_BYTES && e.bodyByteCount in 0..Int.MAX_VALUE.toLong() &&
            e.chunkHashes.size.toLong() == (e.bodyByteCount + e.chunkSize - 1) / e.chunkSize) { "同步正文分块结构无效" }
        for (incoming in payload.chunks) {
            require(incoming.index in e.chunkHashes.indices && incoming.hash == e.chunkHashes[incoming.index] &&
                incoming.bytes.size.toLong() == minOf(e.chunkSize.toLong(), e.bodyByteCount - incoming.index.toLong() * e.chunkSize) &&
                hash(incoming.bytes) == incoming.hash) { "同步正文分块校验失败" }
            val target = chunk(e, incoming.index)
            check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) { "同步检查点目录不可写" }
            val temporary = File(target.parentFile, target.name + ".tmp")
            FileOutputStream(temporary).use { it.write(incoming.bytes); it.fd.sync() }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
        return payload.copy(chunks = e.chunkHashes.indices.mapNotNull { n -> verified(e, n)?.let { ArticleBodyChunk(n, e.chunkHashes[n], it) } })
    }
    suspend fun apply(payload: ChunkedArticlePayload, merge: suspend (ChunkedArticlePayload) -> Int): Boolean {
        val staged = stage(payload)
        val e = entries.getValue(payload.article.articleId)
        if (key(e) in applied) { File(root, key(e)).deleteRecursively(); return true }
        if (!staged.metadataOnly && staged.chunks.size != staged.chunkHashes.size) return false
        // The repository rebuild validates the complete encoded body and its hash before commit.
        merged += merge(staged)
        applied += key(e)
        receivedIds += e.articleId
        File(root, key(e)).deleteRecursively()
        return true
    }
    fun isApplied(id: String): Boolean = entries[id]?.let { key(it) in applied } == true
    fun close() { root.deleteRecursively() }
}
