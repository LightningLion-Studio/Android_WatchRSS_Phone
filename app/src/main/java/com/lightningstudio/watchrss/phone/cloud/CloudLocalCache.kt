package com.lightningstudio.watchrss.phone.cloud

import android.content.Context
import java.io.File

class CloudLocalCache internal constructor(
    private val root: File
) {
    constructor(
        context: Context,
        directoryName: String = "cloud-cache"
    ) : this(File(context.applicationContext.filesDir, directoryName))

    fun storeManifest(
        userId: String,
        snapshotId: String,
        bytes: ByteArray,
        markAsLocalHead: Boolean
    ) {
        atomicWrite(manifestFile(userId, snapshotId), bytes)
        if (markAsLocalHead) {
            atomicWrite(latestFile(userId), snapshotId.toByteArray())
        }
    }

    fun recordManifestReferences(
        userId: String,
        snapshotId: String,
        chunkSha256: Collection<String>
    ) {
        val references = chunkSha256
            .onEach { require(it.matches(SHA256)) { "加密块标识无效" } }
            .distinct()
            .sorted()
            .joinToString(separator = "\n", postfix = if (chunkSha256.isEmpty()) "" else "\n")
            .toByteArray()
        atomicWrite(manifestReferenceFile(userId, snapshotId), references)
    }

    fun loadManifest(userId: String, snapshotId: String): ByteArray? =
        manifestFile(userId, snapshotId).takeIf(File::isFile)?.readBytes()

    fun loadLatestManifest(userId: String): Pair<String, ByteArray>? {
        val snapshotId = latestFile(userId).takeIf(File::isFile)
            ?.readText()
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: return null
        return loadManifest(userId, snapshotId)?.let { snapshotId to it }
    }

    fun storeChunk(userId: String, sha256: String, bytes: ByteArray) {
        require(CloudSnapshotCodec.sha256(bytes) == sha256) { "缓存加密块哈希不匹配" }
        atomicWrite(chunkFile(userId, sha256), bytes)
    }

    fun loadChunk(userId: String, sha256: String): ByteArray? =
        chunkFile(userId, sha256).takeIf(File::isFile)?.readBytes()?.takeIf {
            CloudSnapshotCodec.sha256(it) == sha256
        }

    fun clear(userId: String) {
        val directory = userDirectory(userId)
        check(!directory.exists() || directory.deleteRecursively()) { "本机云端缓存清理失败" }
    }

    fun deleteSnapshot(userId: String, snapshotId: String) {
        manifestFile(userId, snapshotId).delete()
        manifestReferenceFile(userId, snapshotId).delete()
        val latest = latestFile(userId)
        if (latest.takeIf(File::isFile)?.readText()?.trim() == snapshotId) {
            latest.delete()
        }
        prune(userId)
    }

    fun prune(
        userId: String,
        maxBytes: Long = DEFAULT_MAX_CACHE_BYTES,
        keepManifestCount: Int = DEFAULT_MANIFEST_RETENTION
    ): CloudCachePruneResult {
        require(maxBytes >= 0L) { "缓存容量上限不能为负数" }
        require(keepManifestCount >= 1) { "至少保留一份清单" }
        val directory = userDirectory(userId)
        if (!directory.isDirectory) return CloudCachePruneResult(0L, 0, 0)

        var deletedFiles = 0
        var deletedBytes = 0L
        directory.walkTopDown()
            .filter { it.isFile && it.name.endsWith(TEMPORARY_SUFFIX) }
            .toList()
            .forEach { temporary ->
                deletedBytes += deleteFile(temporary).also { if (it > 0L) deletedFiles += 1 }
            }

        val manifests = File(directory, "manifests").listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.endsWith(MANIFEST_SUFFIX) }
            .sortedByDescending(File::lastModified)
        val latestMarker = latestFile(userId)
        val latestSnapshotId = latestMarker
            .takeIf(File::isFile)
            ?.readText()
            ?.trim()
            ?.takeIf { it.matches(SAFE_ID) && manifestFile(userId, it).isFile }
        if (latestMarker.isFile && latestSnapshotId == null) {
            deletedBytes += deleteFile(latestMarker).also { if (it > 0L) deletedFiles += 1 }
        }
        val retainedIds = linkedSetOf<String>()
        latestSnapshotId?.let(retainedIds::add)
        manifests.asSequence()
            .map { it.name.removeSuffix(MANIFEST_SUFFIX) }
            .filter { it !in retainedIds }
            .take((keepManifestCount - retainedIds.size).coerceAtLeast(0))
            .forEach(retainedIds::add)

        manifests.forEach { manifest ->
            val snapshotId = manifest.name.removeSuffix(MANIFEST_SUFFIX)
            if (snapshotId !in retainedIds) {
                deletedBytes += deleteFile(manifest).also { if (it > 0L) deletedFiles += 1 }
                deletedBytes += deleteFile(manifestReferenceFile(userId, snapshotId)).also {
                    if (it > 0L) deletedFiles += 1
                }
            }
        }

        val referencedChunks = retainedIds.flatMapTo(linkedSetOf()) { snapshotId ->
            manifestReferenceFile(userId, snapshotId)
                .takeIf(File::isFile)
                ?.readLines()
                .orEmpty()
                .map(String::trim)
                .filter { it.matches(SHA256) }
        }
        val chunkFiles = File(directory, "chunks").walkTopDown()
            .filter { it.isFile && it.name.endsWith(CHUNK_SUFFIX) }
            .toList()
        chunkFiles.filter { it.name.removeSuffix(CHUNK_SUFFIX) !in referencedChunks }
            .forEach { chunk ->
                deletedBytes += deleteFile(chunk).also { if (it > 0L) deletedFiles += 1 }
            }

        var remainingBytes = directorySize(directory)
        if (remainingBytes > maxBytes) {
            chunkFiles.asSequence()
                .filter(File::isFile)
                .sortedBy(File::lastModified)
                .forEach { chunk ->
                    if (remainingBytes <= maxBytes) return@forEach
                    val bytes = deleteFile(chunk)
                    if (bytes > 0L) {
                        remainingBytes -= bytes
                        deletedBytes += bytes
                        deletedFiles += 1
                    }
                }
        }
        directory.walkBottomUp()
            .filter { it.isDirectory && it != directory }
            .forEach { it.delete() }
        return CloudCachePruneResult(
            deletedBytes = deletedBytes,
            deletedFiles = deletedFiles,
            retainedManifestCount = retainedIds.count { manifestFile(userId, it).isFile }
        )
    }

    private fun manifestFile(userId: String, snapshotId: String): File {
        require(snapshotId.matches(SAFE_ID)) { "快照标识无效" }
        return File(userDirectory(userId), "manifests/$snapshotId.bin")
    }

    private fun manifestReferenceFile(userId: String, snapshotId: String): File {
        require(snapshotId.matches(SAFE_ID)) { "快照标识无效" }
        return File(userDirectory(userId), "manifests/$snapshotId.refs")
    }

    private fun latestFile(userId: String): File = File(userDirectory(userId), "latest")

    private fun chunkFile(userId: String, sha256: String): File {
        require(sha256.matches(SHA256)) { "加密块标识无效" }
        return File(userDirectory(userId), "chunks/${sha256.take(2)}/$sha256.bin")
    }

    private fun userDirectory(userId: String): File =
        File(root, CloudSnapshotCodec.sha256(userId.toByteArray()).take(32))

    private fun atomicWrite(destination: File, bytes: ByteArray) {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        temporary.writeBytes(bytes)
        check(temporary.renameTo(destination) || run {
            destination.delete()
            temporary.renameTo(destination)
        }) { "无法保存云端本地缓存" }
    }

    companion object {
        const val DEFAULT_MAX_CACHE_BYTES = 512L * 1024L * 1024L
        const val DEFAULT_MANIFEST_RETENTION = 3
        private const val MANIFEST_SUFFIX = ".bin"
        private const val CHUNK_SUFFIX = ".bin"
        private const val TEMPORARY_SUFFIX = ".tmp"
        private val SAFE_ID = Regex("""[a-zA-Z0-9-]{1,128}""")
        private val SHA256 = Regex("""[0-9a-f]{64}""")

        private fun deleteFile(file: File): Long {
            if (!file.isFile) return 0L
            val bytes = file.length()
            return if (file.delete()) bytes else 0L
        }

        private fun directorySize(directory: File): Long =
            directory.walkTopDown().filter(File::isFile).sumOf(File::length)
    }
}

data class CloudCachePruneResult(
    val deletedBytes: Long,
    val deletedFiles: Int,
    val retainedManifestCount: Int
)
