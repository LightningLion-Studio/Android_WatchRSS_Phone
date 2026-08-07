package com.lightningstudio.watchrss.phone.data.reader

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

const val READER_PRESET_PACKAGE_EXTENSION = ".wrsspreset"
const val READER_PRESET_PACKAGE_MIME_TYPE = "application/vnd.watchrss.reader-preset+zip"

enum class ReaderPresetPackageScope {
    SINGLE,
    LIBRARY
}

data class ReaderPresetPackageResource(
    val kind: String,
    val fileName: String,
    val sha256: String,
    val byteCount: Long,
    val sourceFile: File
) {
    val entryName: String
        get() {
            val directory = when (kind) {
                KIND_FONT -> "fonts"
                KIND_BACKGROUND -> "backgrounds"
                KIND_VARIANT -> "variants"
                else -> error("未知阅读器资源类型：$kind")
            }
            return "resources/$directory/$sha256.bin"
        }

    companion object {
        const val KIND_FONT = "font"
        const val KIND_BACKGROUND = "background"
        const val KIND_VARIANT = "variant"
    }
}

data class ReaderPresetPackagePayload(
    val scope: ReaderPresetPackageScope,
    val snapshot: ReaderPresetSnapshot,
    val resources: List<ReaderPresetPackageResource>,
    val exportedAt: Long,
    val appVersion: String,
    val legacyJson: Boolean = false,
    val warnings: List<String> = emptyList()
)

object ReaderPresetSnapshotCodec {
    fun encode(snapshot: ReaderPresetSnapshot): JSONObject = JSONObject().apply {
        put("presets", JSONArray().also { array ->
            snapshot.presets.forEach { array.put(it.toJson()) }
        })
        put("fonts", JSONArray().also { array ->
            snapshot.fonts.forEach { array.put(it.toJson()) }
        })
        put("backgrounds", JSONArray().also { array ->
            snapshot.backgrounds.forEach { array.put(it.toJson()) }
        })
        put("deletions", JSONArray().also { array ->
            snapshot.deletions.forEach { array.put(it.toJson()) }
        })
    }

    fun decode(json: JSONObject): ReaderPresetSnapshot = ReaderPresetSnapshot(
        presets = json.optJSONArray("presets").objects { item ->
            ReaderPresetEntity(
                id = item.getString("id"),
                name = item.getString("name"),
                payloadJson = item.getString("payloadJson"),
                updatedAt = item.getLong("updatedAt"),
                modifiedBy = item.getString("modifiedBy"),
                deleted = item.getBoolean("deleted")
            )
        },
        fonts = json.optJSONArray("fonts").objects { item ->
            ReaderFontAssetEntity(
                id = item.getString("id"),
                sha256 = item.getString("sha256"),
                displayName = item.getString("displayName"),
                familyName = item.getString("familyName"),
                fileName = item.getString("fileName"),
                mimeType = item.getString("mimeType"),
                byteCount = item.getLong("byteCount"),
                faceCount = item.getInt("faceCount"),
                metadataJson = item.getString("metadataJson"),
                updatedAt = item.getLong("updatedAt"),
                modifiedBy = item.getString("modifiedBy"),
                deleted = item.getBoolean("deleted")
            )
        },
        backgrounds = json.optJSONArray("backgrounds").objects { item ->
            ReaderBackgroundAssetEntity(
                id = item.getString("id"),
                sha256 = item.getString("sha256"),
                displayName = item.getString("displayName"),
                kind = item.getString("kind"),
                mimeType = item.getString("mimeType"),
                masterFileName = item.getString("masterFileName"),
                byteCount = item.getLong("byteCount"),
                durationMs = item.getLong("durationMs"),
                width = item.getInt("width"),
                height = item.getInt("height"),
                posterAssetId = item.nullableString("posterAssetId"),
                variantsJson = item.getString("variantsJson"),
                updatedAt = item.getLong("updatedAt"),
                modifiedBy = item.getString("modifiedBy"),
                deleted = item.getBoolean("deleted")
            )
        },
        deletions = json.optJSONArray("deletions").objects { item ->
            ReaderDeletionEntity(
                kind = item.getString("kind"),
                entityId = item.getString("entityId"),
                deletedAt = item.getLong("deletedAt"),
                deletedBy = item.getString("deletedBy")
            )
        }
    )

    fun encodeSelection(selection: ReaderPresetSelection): JSONObject = JSONObject().apply {
        put("mode", selection.mode.name)
        putNullable("lightPresetId", selection.lightPresetId)
        putNullable("darkPresetId", selection.darkPresetId)
        put("darkFollowsLight", selection.darkFollowsLight)
    }

    fun decodeSelection(json: JSONObject): ReaderPresetSelection = ReaderPresetSelection(
        mode = runCatching { ReaderThemeMode.valueOf(json.getString("mode")) }
            .getOrDefault(ReaderThemeMode.SYSTEM),
        lightPresetId = json.nullableString("lightPresetId"),
        darkPresetId = json.nullableString("darkPresetId"),
        darkFollowsLight = json.optBoolean("darkFollowsLight", true)
    )

    fun fingerprint(snapshot: ReaderPresetSnapshot, selection: ReaderPresetSelection): String {
        val normalized = ReaderPresetSnapshot(
            presets = snapshot.presets.sortedBy(ReaderPresetEntity::id),
            fonts = snapshot.fonts.sortedBy(ReaderFontAssetEntity::id),
            backgrounds = snapshot.backgrounds.sortedBy(ReaderBackgroundAssetEntity::id),
            deletions = snapshot.deletions.sortedWith(
                compareBy(ReaderDeletionEntity::kind, ReaderDeletionEntity::entityId)
            )
        )
        val raw = JSONObject().apply {
            put("snapshot", encode(normalized))
            put("selection", encodeSelection(selection))
        }.toString().toByteArray(Charsets.UTF_8)
        return sha256(raw)
    }

    private fun ReaderPresetEntity.toJson() = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("payloadJson", payloadJson)
        put("updatedAt", updatedAt)
        put("modifiedBy", modifiedBy)
        put("deleted", deleted)
    }

    private fun ReaderFontAssetEntity.toJson() = JSONObject().apply {
        put("id", id)
        put("sha256", sha256)
        put("displayName", displayName)
        put("familyName", familyName)
        put("fileName", fileName)
        put("mimeType", mimeType)
        put("byteCount", byteCount)
        put("faceCount", faceCount)
        put("metadataJson", metadataJson)
        put("updatedAt", updatedAt)
        put("modifiedBy", modifiedBy)
        put("deleted", deleted)
    }

    private fun ReaderBackgroundAssetEntity.toJson() = JSONObject().apply {
        put("id", id)
        put("sha256", sha256)
        put("displayName", displayName)
        put("kind", kind)
        put("mimeType", mimeType)
        put("masterFileName", masterFileName)
        put("byteCount", byteCount)
        put("durationMs", durationMs)
        put("width", width)
        put("height", height)
        putNullable("posterAssetId", posterAssetId)
        put("variantsJson", variantsJson)
        put("updatedAt", updatedAt)
        put("modifiedBy", modifiedBy)
        put("deleted", deleted)
    }

    private fun ReaderDeletionEntity.toJson() = JSONObject().apply {
        put("kind", kind)
        put("entityId", entityId)
        put("deletedAt", deletedAt)
        put("deletedBy", deletedBy)
    }
}

object ReaderPresetPackageArchive {
    private const val FORMAT = "watchrss-reader-preset"
    const val CURRENT_VERSION = 1
    private const val MANIFEST_ENTRY = "manifest.json"
    private const val READER_ENTRY = "reader.json"
    private const val MAX_ENTRY_COUNT = 4_096
    private const val MAX_MANIFEST_BYTES = 1024L * 1024L
    private const val MAX_READER_BYTES = 4L * 1024L * 1024L
    private const val MAX_RESOURCE_BYTES = 512L * 1024L * 1024L
    private const val MAX_EXPANDED_BYTES = 4L * 1024L * 1024L * 1024L
    private val RESOURCE_ENTRY =
        Regex("""resources/(fonts|backgrounds|variants)/[0-9a-f]{64}\.bin""")

    fun write(payload: ReaderPresetPackagePayload, output: OutputStream) {
        validatePayload(payload)
        val descriptors = payload.resources.distinctBy {
            Triple(it.kind, it.fileName, it.sha256)
        }
        val uniqueResources = descriptors.distinctBy(ReaderPresetPackageResource::entryName)
        val readerData = ReaderPresetSnapshotCodec.encode(payload.snapshot)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val resourceBytes = uniqueResources.sumOf(ReaderPresetPackageResource::byteCount)
        val manifest = JSONObject().apply {
            put("format", FORMAT)
            put("version", CURRENT_VERSION)
            put("scope", payload.scope.name)
            put("exportedAt", payload.exportedAt)
            put("appVersion", payload.appVersion)
            put("presetCount", payload.snapshot.presets.size)
            put("fontCount", payload.snapshot.fonts.size)
            put("backgroundCount", payload.snapshot.backgrounds.size)
            put("readerBytes", readerData.size)
            put("readerSha256", sha256(readerData))
            put("resourceBytes", resourceBytes)
            put("contentBytes", readerData.size.toLong() + resourceBytes)
            put("resources", JSONArray().also { array ->
                descriptors.forEach { resource -> array.put(resource.toJson()) }
            })
        }
        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            zip.writeUtf8(MANIFEST_ENTRY, manifest.toString())
            zip.writeBytes(READER_ENTRY, readerData)
            uniqueResources.forEach { resource ->
                require(resource.sourceFile.isFile) { "阅读器资源不存在：${resource.fileName}" }
                require(resource.sourceFile.length() == resource.byteCount) {
                    "阅读器资源大小已变化：${resource.fileName}"
                }
                require(fileSha256(resource.sourceFile) == resource.sha256) {
                    "阅读器资源内容已变化：${resource.fileName}"
                }
                zip.putNextEntry(ZipEntry(resource.entryName))
                resource.sourceFile.inputStream().buffered().use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    fun read(input: InputStream, stagingDirectory: File): ReaderPresetPackagePayload {
        require(stagingDirectory.mkdirs() || stagingDirectory.isDirectory) { "无法创建导入暂存目录" }
        var manifestBytes: ByteArray? = null
        var readerBytes: ByteArray? = null
        val stagedResources = linkedMapOf<String, File>()
        val seen = hashSetOf<String>()
        var expandedBytes = 0L
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory) { "预设包中不允许目录条目" }
                val name = entry.name
                require(name == MANIFEST_ENTRY || name == READER_ENTRY || RESOURCE_ENTRY.matches(name)) {
                    "预设包包含未知或不安全条目：$name"
                }
                require(seen.add(name)) { "预设包包含重复条目：$name" }
                require(seen.size <= MAX_ENTRY_COUNT) { "预设包条目数量过多" }
                when (name) {
                    MANIFEST_ENTRY -> {
                        val result = zip.readBounded(MAX_MANIFEST_BYTES, name)
                        expandedBytes += result.size
                        manifestBytes = result
                    }
                    READER_ENTRY -> {
                        val result = zip.readBounded(MAX_READER_BYTES, name)
                        expandedBytes += result.size
                        readerBytes = result
                    }
                    else -> {
                        val target = File(stagingDirectory, name)
                        val parent = requireNotNull(target.parentFile)
                        require(parent.isDirectory || parent.mkdirs()) { "无法创建资源暂存目录" }
                        val digest = MessageDigest.getInstance("SHA-256")
                        var count = 0L
                        target.outputStream().buffered().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val read = zip.read(buffer)
                                if (read < 0) break
                                count += read
                                expandedBytes += read
                                require(count <= MAX_RESOURCE_BYTES) { "预设包资源过大：$name" }
                                require(expandedBytes <= MAX_EXPANDED_BYTES) { "预设包解压后内容过大" }
                                output.write(buffer, 0, read)
                                digest.update(buffer, 0, read)
                            }
                        }
                        val nameHash = name.substringAfterLast('/').substringBefore('.')
                        val actualHash = digest.digest().toHex()
                        require(actualHash == nameHash) { "预设包资源哈希不匹配：$name" }
                        stagedResources[name] = target
                    }
                }
                require(expandedBytes <= MAX_EXPANDED_BYTES) { "预设包解压后内容过大" }
                zip.closeEntry()
            }
        }

        val manifest = JSONObject(
            requireNotNull(manifestBytes) { "预设包缺少 manifest.json" }.toString(Charsets.UTF_8)
        )
        require(manifest.getString("format") == FORMAT) { "不是腕上RSS阅读器预设包" }
        val version = manifest.getInt("version")
        require(version > 0) { "预设包版本无效" }
        require(version <= CURRENT_VERSION) { "预设包版本过高，请升级应用后重试" }
        val scope = runCatching {
            ReaderPresetPackageScope.valueOf(manifest.getString("scope"))
        }.getOrElse { throw IllegalArgumentException("预设包范围无效") }
        val readerData = requireNotNull(readerBytes) { "预设包缺少 reader.json" }
        require(manifest.getLong("readerBytes") == readerData.size.toLong()) {
            "reader.json 大小不匹配"
        }
        val expectedReaderHash = manifest.getString("readerSha256")
        require(expectedReaderHash.matches(Regex("[0-9a-f]{64}"))) {
            "reader.json 哈希格式无效"
        }
        require(sha256(readerData) == expectedReaderHash) { "reader.json 哈希不匹配" }
        val snapshot = ReaderPresetSnapshotCodec.decode(
            JSONObject(readerData.toString(Charsets.UTF_8))
        )
        val resources = manifest.getJSONArray("resources").objects { json ->
            val kind = json.getString("kind")
            val fileName = json.getString("fileName")
            val hash = json.getString("sha256")
            val byteCount = json.getLong("byteCount")
            requireSafeFileName(fileName)
            require(hash.matches(Regex("[0-9a-f]{64}"))) { "资源哈希格式无效" }
            require(byteCount in 0..MAX_RESOURCE_BYTES) { "资源大小无效：$fileName" }
            val directory = when (kind) {
                ReaderPresetPackageResource.KIND_FONT -> "fonts"
                ReaderPresetPackageResource.KIND_BACKGROUND -> "backgrounds"
                ReaderPresetPackageResource.KIND_VARIANT -> "variants"
                else -> throw IllegalArgumentException("未知阅读器资源类型：$kind")
            }
            val entryName = "resources/$directory/$hash.bin"
            val file = stagedResources[entryName]
                ?: throw IllegalArgumentException("预设包缺少资源：$fileName")
            require(file.length() == byteCount) { "预设包资源大小不匹配：$fileName" }
            ReaderPresetPackageResource(kind, fileName, hash, byteCount, file)
        }
        require(resources.map { Triple(it.kind, it.fileName, it.sha256) }.distinct().size == resources.size) {
            "预设包清单包含重复资源"
        }
        require(stagedResources.keys == resources.mapTo(linkedSetOf(), ReaderPresetPackageResource::entryName)) {
            "预设包包含未引用或缺失的资源"
        }
        require(manifest.getInt("presetCount") == snapshot.presets.size) { "预设数量不匹配" }
        require(manifest.getInt("fontCount") == snapshot.fonts.size) { "字体数量不匹配" }
        require(manifest.getInt("backgroundCount") == snapshot.backgrounds.size) { "背景数量不匹配" }
        require(
            manifest.getLong("resourceBytes") == resources
                .distinctBy(ReaderPresetPackageResource::entryName)
                .sumOf { it.byteCount }
        ) {
            "资源总大小不匹配"
        }
        require(
            manifest.getLong("contentBytes") == readerData.size.toLong() + resources
                .distinctBy(ReaderPresetPackageResource::entryName)
                .sumOf { it.byteCount }
        ) {
            "预设包内容总大小不匹配"
        }
        return ReaderPresetPackagePayload(
            scope = scope,
            snapshot = snapshot,
            resources = resources,
            exportedAt = manifest.getLong("exportedAt"),
            appVersion = manifest.optString("appVersion"),
            legacyJson = false
        ).also(::validatePayload)
    }

    fun readLegacyJson(raw: String): ReaderPresetPackagePayload {
        val preset = ReaderPresetCodec.decode(raw)
        require(!preset.deleted) { "不能导入已删除的预设" }
        val entity = preset.copy(deleted = false).toEntity()
        return ReaderPresetPackagePayload(
            scope = ReaderPresetPackageScope.SINGLE,
            snapshot = ReaderPresetSnapshot(
                presets = listOf(entity),
                fonts = emptyList(),
                backgrounds = emptyList(),
                deletions = emptyList()
            ),
            resources = emptyList(),
            exportedAt = System.currentTimeMillis(),
            appVersion = "legacy-json",
            legacyJson = true,
            warnings = listOf("旧版 JSON 不包含字体或背景资源")
        )
    }

    private fun validatePayload(payload: ReaderPresetPackagePayload) {
        require(payload.exportedAt > 0L) { "预设包导出时间无效" }
        require(payload.snapshot.deletions.isEmpty()) { "预设分享包不能包含删除记录" }
        require(payload.snapshot.presets.isNotEmpty()) { "预设包中没有预设" }
        require(payload.snapshot.presets.none(ReaderPresetEntity::deleted)) { "预设包包含已删除预设" }
        require(payload.snapshot.fonts.none(ReaderFontAssetEntity::deleted)) { "预设包包含已删除字体" }
        require(payload.snapshot.backgrounds.none(ReaderBackgroundAssetEntity::deleted)) {
            "预设包包含已删除背景"
        }
        if (payload.scope == ReaderPresetPackageScope.SINGLE) {
            require(payload.snapshot.presets.size == 1) { "单项预设包只能包含一个预设" }
        }
        require(payload.snapshot.presets.map(ReaderPresetEntity::id).distinct().size ==
            payload.snapshot.presets.size
        ) { "预设包包含重复预设 ID" }
        require(payload.snapshot.fonts.map(ReaderFontAssetEntity::id).distinct().size ==
            payload.snapshot.fonts.size
        ) { "预设包包含重复字体 ID" }
        require(payload.snapshot.backgrounds.map(ReaderBackgroundAssetEntity::id).distinct().size ==
            payload.snapshot.backgrounds.size
        ) { "预设包包含重复背景 ID" }

        val fontIds = payload.snapshot.fonts.mapTo(hashSetOf(), ReaderFontAssetEntity::id)
        val backgroundIds = payload.snapshot.backgrounds
            .mapTo(hashSetOf(), ReaderBackgroundAssetEntity::id)
        payload.snapshot.presets.forEach { record ->
            val preset = ReaderPresetCodec.decode(record.payloadJson)
            require(preset.id == record.id && preset.name == record.name) { "预设元数据不匹配" }
            preset.referencedFontIds().forEach { id ->
                require(id in fontIds || payload.legacyJson) { "预设缺少字体资源：$id" }
            }
            preset.referencedBackgroundIds().forEach { id ->
                require(id in backgroundIds || payload.legacyJson) { "预设缺少背景资源：$id" }
            }
        }

        if (payload.legacyJson) return
        val descriptorKeys = payload.resources.mapTo(hashSetOf()) {
            Triple(it.kind, it.fileName, it.sha256)
        }
        payload.snapshot.fonts.forEach { font ->
            require(font.id == font.sha256) { "字体资源 ID 与哈希不匹配" }
            require(
                Triple(ReaderPresetPackageResource.KIND_FONT, font.fileName, font.sha256) in
                    descriptorKeys
            ) { "预设包缺少字体文件：${font.displayName}" }
        }
        payload.snapshot.backgrounds.forEach { background ->
            require(background.id == background.sha256) { "背景资源 ID 与哈希不匹配" }
            require(
                Triple(
                    ReaderPresetPackageResource.KIND_BACKGROUND,
                    background.masterFileName,
                    background.sha256
                ) in descriptorKeys
            ) { "预设包缺少背景文件：${background.displayName}" }
            background.variantDescriptors().forEach { variant ->
                require(
                    Triple(
                        ReaderPresetPackageResource.KIND_VARIANT,
                        variant.fileName,
                        variant.sha256
                    ) in descriptorKeys
                ) { "预设包缺少背景派生文件：${variant.fileName}" }
            }
        }
    }

    private fun ReaderPresetPackageResource.toJson() = JSONObject().apply {
        put("kind", kind)
        put("fileName", fileName)
        put("sha256", sha256)
        put("byteCount", byteCount)
    }

    private fun ZipOutputStream.writeUtf8(name: String, value: String) {
        writeBytes(name, value.toByteArray(Charsets.UTF_8))
    }

    private fun ZipOutputStream.writeBytes(name: String, value: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(value)
        closeEntry()
    }

    private fun InputStream.readBounded(limit: Long, name: String): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var count = 0L
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            count += read
            require(count <= limit) { "预设包条目过大：$name" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
}

data class ReaderBackgroundVariantDescriptor(
    val fileName: String,
    val sha256: String,
    val byteCount: Long
)

fun ReaderBackgroundAssetEntity.variantDescriptors(): List<ReaderBackgroundVariantDescriptor> {
    val variants = runCatching { JSONObject(variantsJson) }.getOrNull() ?: return emptyList()
    return buildList {
        val keys = variants.keys()
        while (keys.hasNext()) {
            val value = variants.optJSONObject(keys.next()) ?: continue
            val fileName = value.optString("fileName")
            val hash = value.optString("sha256")
            val byteCount = value.optLong("byteCount", -1L)
            if (fileName.isBlank() && hash.isBlank() && byteCount < 0L) continue
            requireSafeFileName(fileName)
            require(hash.matches(Regex("[0-9a-f]{64}")) && byteCount >= 0L) {
                "背景派生资源元数据无效"
            }
            add(ReaderBackgroundVariantDescriptor(fileName, hash, byteCount))
        }
    }
}

fun ReaderPreset.referencedFontIds(): Set<String> = buildSet {
    body.fontAssetId?.let(::add)
    listOf(title, subtitle, quote, code, link).forEach { style ->
        if (style.useOwnFont) style.fontAssetId?.let(::add)
    }
}

fun ReaderPreset.referencedBackgroundIds(): Set<String> = buildSet {
    background.assetId?.let(::add)
    background.posterAssetId?.let(::add)
}

private fun requireSafeFileName(fileName: String) {
    require(
        fileName.isNotBlank() &&
            fileName.length <= 180 &&
            File(fileName).name == fileName &&
            '/' !in fileName &&
            '\\' !in fileName
    ) { "阅读器资源文件名无效" }
}

private fun <T> JSONArray?.objects(transform: (JSONObject) -> T): List<T> = buildList {
    if (this@objects != null) {
        for (index in 0 until length()) add(transform(getJSONObject(index)))
    }
}

private fun JSONObject.putNullable(name: String, value: String?) {
    put(name, value ?: JSONObject.NULL)
}

private fun JSONObject.nullableString(name: String): String? =
    if (!has(name) || isNull(name)) null else getString(name)

private fun fileSha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().toHex()
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

private fun ByteArray.toHex(): String =
    joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
