package com.lightningstudio.watchrss.phone.connection.bluetooth

import com.lightningstudio.watchrss.phone.data.reader.ReaderBackgroundAssetEntity
import com.lightningstudio.watchrss.phone.data.reader.ReaderDeletionEntity
import com.lightningstudio.watchrss.phone.data.reader.ReaderFontAssetEntity
import com.lightningstudio.watchrss.phone.data.reader.ReaderPresetEntity
import com.lightningstudio.watchrss.phone.data.reader.ReaderPresetRepository
import com.lightningstudio.watchrss.phone.data.reader.ReaderPresetSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Base64

object ReaderPresetSyncPayload {
    const val PHASE_MANIFEST = "manifest"
    const val PHASE_PUSH_RESOURCE = "pushResource"
    const val PHASE_PULL_RESOURCE = "pullResource"
    const val CHUNK_BYTES = 1024 * 1024

    fun buildManifest(snapshot: ReaderPresetSnapshot, deviceId: String): JSONObject =
        snapshotJson(snapshot).apply {
            put("version", 11)
            put("action", BluetoothSyncProtocol.ACTION_SYNC_READER)
            put("phase", PHASE_MANIFEST)
            put("deviceId", deviceId)
        }

    suspend fun mergeManifest(response: JSONObject, repository: ReaderPresetRepository) {
        repository.mergeRemote(
            parsePresets(response.optJSONArray("presets")),
            parseFonts(response.optJSONArray("fonts")),
            parseBackgrounds(response.optJSONArray("backgrounds")),
            parseDeletions(response.optJSONArray("deletions"))
        )
    }

    fun missingResources(response: JSONObject): List<ResourceDescriptor> =
        response.optJSONArray("missingResources").objects().map {
            ResourceDescriptor(
                kind = it.getString("kind"),
                fileName = it.getString("fileName"),
                sha256 = it.getString("sha256"),
                byteCount = it.getLong("byteCount")
            )
        }

    suspend fun locallyMissing(repository: ReaderPresetRepository): List<ResourceDescriptor> {
        val snapshot = repository.exportSnapshot()
        return buildList {
            snapshot.fonts.filterNot { it.deleted }.forEach {
                val file = repository.resourceStore.fontFile(it.fileName)
                if (file == null || file.length() != it.byteCount ||
                    repository.resourceStore.fileSha256(file) != it.sha256
                ) add(ResourceDescriptor("font", it.fileName, it.sha256, it.byteCount))
            }
            snapshot.backgrounds.filterNot { it.deleted }.forEach {
                val file = repository.resourceStore.backgroundFile(it.masterFileName)
                if (file == null || file.length() != it.byteCount ||
                    repository.resourceStore.fileSha256(file) != it.sha256
                ) add(ResourceDescriptor("background", it.masterFileName, it.sha256, it.byteCount))
            }
        }
    }

    fun pushFrames(repository: ReaderPresetRepository, resource: ResourceDescriptor): Sequence<JSONObject> {
        val file = existingFile(repository, resource.kind, resource.fileName)
        require(file.length() == resource.byteCount) { "资源大小已变化" }
        val count = ((file.length() + CHUNK_BYTES - 1) / CHUNK_BYTES).toInt().coerceAtLeast(1)
        return sequence {
            RandomAccessFile(file, "r").use { input ->
                for (index in 0 until count) {
                    val size = minOf(CHUNK_BYTES.toLong(), file.length() - input.filePointer).toInt()
                    val data = ByteArray(size)
                    input.readFully(data)
                    yield(JSONObject().apply {
                        put("version", 11)
                        put("action", BluetoothSyncProtocol.ACTION_SYNC_READER)
                        put("phase", PHASE_PUSH_RESOURCE)
                        put("kind", resource.kind)
                        put("fileName", resource.fileName)
                        put("sha256", resource.sha256)
                        put("totalBytes", resource.byteCount)
                        put("chunkIndex", index)
                        put("chunkCount", count)
                        put("chunkSha256", sha256(data))
                        put("data", Base64.getEncoder().encodeToString(data))
                    })
                }
            }
        }
    }

    fun pullRequest(resource: ResourceDescriptor, index: Int) = JSONObject().apply {
        put("version", 11)
        put("action", BluetoothSyncProtocol.ACTION_SYNC_READER)
        put("phase", PHASE_PULL_RESOURCE)
        put("kind", resource.kind)
        put("fileName", resource.fileName)
        put("chunkIndex", index)
    }

    fun applyPulledChunk(
        response: JSONObject,
        resource: ResourceDescriptor,
        repository: ReaderPresetRepository
    ): Boolean {
        val data = Base64.getDecoder().decode(response.getString("data"))
        require(data.size <= CHUNK_BYTES && sha256(data) == response.getString("chunkSha256")) {
            "下载资源分块校验失败"
        }
        val index = response.getInt("chunkIndex")
        val count = response.getInt("chunkCount")
        val target = targetFile(repository, resource.kind, resource.fileName)
        if (
            target.exists() &&
            target.length() == resource.byteCount &&
            runBlockingHash(target) == resource.sha256
        ) {
            return true
        }
        val partial = File(target.parentFile, "${target.name}.part")
        val metadata = File(target.parentFile, "${target.name}.part.meta")
        applyIncomingChunk(
            partial = partial,
            metadata = metadata,
            index = index,
            chunkCount = count,
            data = data,
            totalBytes = resource.byteCount,
            expectedHash = resource.sha256
        )
        if (index != count - 1) return false
        require(partial.length() == resource.byteCount) { "下载资源大小校验失败" }
        require(runBlockingHash(partial) == resource.sha256) { "下载资源完整校验失败" }
        if (target.exists()) target.delete()
        require(partial.renameTo(target)) { "下载资源落盘失败" }
        metadata.delete()
        return true
    }

    internal fun applyIncomingChunk(
        partial: File,
        metadata: File,
        index: Int,
        chunkCount: Int,
        data: ByteArray,
        totalBytes: Long,
        expectedHash: String
    ) {
        val signature = "$expectedHash:$totalBytes:$chunkCount"
        val savedSignature = runCatching { metadata.readText() }.getOrNull()
        if (savedSignature != signature) {
            require(index == 0) { "下载资源传输已变化，请从第一块重试" }
            if (partial.exists()) partial.delete()
            metadata.parentFile?.mkdirs()
            metadata.writeText(signature)
        }

        RandomAccessFile(partial, "rw").use { output ->
            val offset = index.toLong() * CHUNK_BYTES
            val end = offset + data.size
            require(offset <= totalBytes && end <= totalBytes) { "下载资源分块范围异常" }
            when {
                output.length() < offset -> error("下载资源分块顺序不连续")
                output.length() >= end -> {
                    val existing = ByteArray(data.size)
                    output.seek(offset)
                    output.readFully(existing)
                    if (!existing.contentEquals(data)) {
                        require(index == 0) { "下载资源分块内容冲突，请从第一块重试" }
                        output.setLength(0L)
                        output.seek(0L)
                        output.write(data)
                    }
                }
                else -> {
                    output.setLength(offset)
                    output.seek(offset)
                    output.write(data)
                }
            }
            output.fd.sync()
        }
    }

    fun snapshotJson(snapshot: ReaderPresetSnapshot): JSONObject = JSONObject().apply {
        put("presets", JSONArray().also { a -> snapshot.presets.forEach { a.put(it.toJson()) } })
        put("fonts", JSONArray().also { a -> snapshot.fonts.forEach { a.put(it.toJson()) } })
        put("backgrounds", JSONArray().also { a -> snapshot.backgrounds.forEach { a.put(it.toJson()) } })
        put("deletions", JSONArray().also { a -> snapshot.deletions.forEach { a.put(it.toJson()) } })
    }

    fun parsePresets(a: JSONArray?) = a.objects().map { j ->
        ReaderPresetEntity(j.getString("id"), j.getString("name"), j.getString("payloadJson"), j.getLong("updatedAt"), j.getString("modifiedBy"), j.getBoolean("deleted"))
    }
    fun parseFonts(a: JSONArray?) = a.objects().map { j ->
        ReaderFontAssetEntity(j.getString("id"), j.getString("sha256"), j.getString("displayName"), j.getString("familyName"), j.getString("fileName"), j.getString("mimeType"), j.getLong("byteCount"), j.getInt("faceCount"), j.getString("metadataJson"), j.getLong("updatedAt"), j.getString("modifiedBy"), j.getBoolean("deleted"))
    }
    fun parseBackgrounds(a: JSONArray?) = a.objects().map { j ->
        ReaderBackgroundAssetEntity(j.getString("id"), j.getString("sha256"), j.getString("displayName"), j.getString("kind"), j.getString("mimeType"), j.getString("masterFileName"), j.getLong("byteCount"), j.getLong("durationMs"), j.getInt("width"), j.getInt("height"), j.optString("posterAssetId").takeIf(String::isNotBlank), j.getString("variantsJson"), j.getLong("updatedAt"), j.getString("modifiedBy"), j.getBoolean("deleted"))
    }
    fun parseDeletions(a: JSONArray?) = a.objects().map { j ->
        ReaderDeletionEntity(j.getString("kind"), j.getString("entityId"), j.getLong("deletedAt"), j.getString("deletedBy"))
    }

    private fun existingFile(repository: ReaderPresetRepository, kind: String, fileName: String) =
        when (kind) {
            "font" -> repository.resourceStore.fontFile(fileName)
            "background" -> repository.resourceStore.backgroundFile(fileName)
            "variant" -> repository.resourceStore.variantFile(fileName)
            else -> null
        } ?: error("本机资源不存在")

    private fun targetFile(repository: ReaderPresetRepository, kind: String, fileName: String) =
        when (kind) {
            "font" -> repository.resourceStore.targetFontFile(fileName)
            "background" -> repository.resourceStore.targetBackgroundFile(fileName)
            "variant" -> repository.resourceStore.targetVariantFile(fileName)
            else -> error("未知资源类型")
        }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun runBlockingHash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun ReaderPresetEntity.toJson() = JSONObject().apply {
        put("id", id); put("name", name); put("payloadJson", payloadJson); put("updatedAt", updatedAt); put("modifiedBy", modifiedBy); put("deleted", deleted)
    }
    private fun ReaderFontAssetEntity.toJson() = JSONObject().apply {
        put("id", id); put("sha256", sha256); put("displayName", displayName); put("familyName", familyName); put("fileName", fileName); put("mimeType", mimeType); put("byteCount", byteCount); put("faceCount", faceCount); put("metadataJson", metadataJson); put("updatedAt", updatedAt); put("modifiedBy", modifiedBy); put("deleted", deleted)
    }
    private fun ReaderBackgroundAssetEntity.toJson() = JSONObject().apply {
        put("id", id); put("sha256", sha256); put("displayName", displayName); put("kind", kind); put("mimeType", mimeType); put("masterFileName", masterFileName); put("byteCount", byteCount); put("durationMs", durationMs); put("width", width); put("height", height); put("posterAssetId", posterAssetId ?: ""); put("variantsJson", variantsJson); put("updatedAt", updatedAt); put("modifiedBy", modifiedBy); put("deleted", deleted)
    }
    private fun ReaderDeletionEntity.toJson() = JSONObject().apply {
        put("kind", kind); put("entityId", entityId); put("deletedAt", deletedAt); put("deletedBy", deletedBy)
    }
    private fun JSONArray?.objects(): List<JSONObject> = buildList {
        if (this@objects != null) for (i in 0 until length()) optJSONObject(i)?.let(::add)
    }
}

data class ResourceDescriptor(
    val kind: String,
    val fileName: String,
    val sha256: String,
    val byteCount: Long
)
