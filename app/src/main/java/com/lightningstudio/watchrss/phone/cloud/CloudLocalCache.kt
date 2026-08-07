package com.lightningstudio.watchrss.phone.cloud

import android.content.Context
import java.io.File

class CloudLocalCache(
    context: Context,
    directoryName: String = "cloud-cache"
) {
    private val root = File(context.applicationContext.filesDir, directoryName)

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

    private fun manifestFile(userId: String, snapshotId: String): File {
        require(snapshotId.matches(SAFE_ID)) { "快照标识无效" }
        return File(userDirectory(userId), "manifests/$snapshotId.bin")
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

    private companion object {
        private val SAFE_ID = Regex("""[a-zA-Z0-9-]{1,128}""")
        private val SHA256 = Regex("""[0-9a-f]{64}""")
    }
}
