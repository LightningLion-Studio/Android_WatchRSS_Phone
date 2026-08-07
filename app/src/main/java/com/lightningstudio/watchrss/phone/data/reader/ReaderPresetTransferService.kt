package com.lightningstudio.watchrss.phone.data.reader

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PushbackInputStream
import java.util.UUID

enum class ReaderPresetSingleImportChoice {
    OVERWRITE,
    COPY
}

enum class ReaderPresetLibraryImportChoice {
    MERGE,
    REPLACE
}

data class ReaderPresetImportPreview(
    val scope: ReaderPresetPackageScope,
    val presetIds: List<String>,
    val presetNames: List<String>,
    val presetCount: Int,
    val fontCount: Int,
    val backgroundCount: Int,
    val packageBytes: Long?,
    val resourceBytes: Long,
    val hasSingleIdConflict: Boolean,
    val legacyJson: Boolean,
    val warnings: List<String>
)

class PreparedReaderPresetImport internal constructor(
    val preview: ReaderPresetImportPreview,
    internal val payload: ReaderPresetPackagePayload,
    internal val stagingDirectory: File
)

data class ReaderPresetTransferImportResult(
    val scope: ReaderPresetPackageScope,
    val importedPresetIds: List<String>,
    val importedPresetNames: List<String>,
    val warnings: List<String>
)

data class ReaderPresetUndoEntry(
    val id: String,
    val label: String,
    val createdAt: Long,
    val expiresAt: Long
)

data class ReaderPresetUndoResult(
    val restoredLabel: String? = null,
    val requiresConfirmation: Boolean = false,
    val remainingCount: Int = 0
)

class ReaderPresetTransferService(
    context: Context,
    private val repository: ReaderPresetRepository,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val undoRetentionMillis: Long = DEFAULT_UNDO_RETENTION_MS
) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val importsRoot = File(appContext.cacheDir, IMPORT_STAGING_DIRECTORY)
    private val undoFile = File(appContext.filesDir, UNDO_JOURNAL_FILE)
    private var undoRecords = loadUndoRecords()
    private val undoEntriesState = MutableStateFlow(visibleUndoEntries(undoRecords))
    val undoEntries: StateFlow<List<ReaderPresetUndoEntry>> = undoEntriesState

    init {
        require(undoRetentionMillis > 0L) { "撤销保留时间必须大于零" }
    }

    suspend fun exportSingle(presetId: String, uri: Uri) = withContext(Dispatchers.IO) {
        val payload = buildExportPayload(ReaderPresetPackageScope.SINGLE, presetId)
        val output = resolver.openOutputStream(uri, "w") ?: error("无法创建预设包")
        output.use { ReaderPresetPackageArchive.write(payload, it) }
    }

    suspend fun exportLibrary(uri: Uri) = withContext(Dispatchers.IO) {
        val payload = buildExportPayload(ReaderPresetPackageScope.LIBRARY, null)
        val output = resolver.openOutputStream(uri, "w") ?: error("无法创建预设包")
        output.use { ReaderPresetPackageArchive.write(payload, it) }
    }

    suspend fun inspect(uri: Uri): PreparedReaderPresetImport = withContext(Dispatchers.IO) {
        val staging = File(importsRoot, UUID.randomUUID().toString())
        try {
            val input = resolver.openInputStream(uri) ?: error("无法读取所选文件")
            val rawPayload = input.use { source ->
                PushbackInputStream(source.buffered(), ZIP_SIGNATURE.size).use { buffered ->
                    val signature = ByteArray(ZIP_SIGNATURE.size)
                    val count = buffered.read(signature)
                    if (count > 0) buffered.unread(signature, 0, count)
                    if (count == ZIP_SIGNATURE.size && signature.contentEquals(ZIP_SIGNATURE)) {
                        ReaderPresetPackageArchive.read(buffered, staging)
                    } else {
                        val raw = buffered.readUtf8Bounded(MAX_LEGACY_JSON_BYTES)
                        ReaderPresetPackageArchive.readLegacyJson(raw)
                    }
                }
            }
            val payload = if (rawPayload.legacyJson) sanitizeLegacyPayload(rawPayload) else rawPayload
            val presetIds = payload.snapshot.presets.map(ReaderPresetEntity::id)
            val presetNames = payload.snapshot.presets.map(ReaderPresetEntity::name)
            val hasConflict = payload.scope == ReaderPresetPackageScope.SINGLE &&
                repository.preset(presetIds.single()) != null
            PreparedReaderPresetImport(
                preview = ReaderPresetImportPreview(
                    scope = payload.scope,
                    presetIds = presetIds,
                    presetNames = presetNames,
                    presetCount = presetIds.size,
                    fontCount = payload.snapshot.fonts.size,
                    backgroundCount = payload.snapshot.backgrounds.size,
                    packageBytes = contentLength(uri),
                    resourceBytes = payload.resources
                        .distinctBy(ReaderPresetPackageResource::entryName)
                        .sumOf(ReaderPresetPackageResource::byteCount),
                    hasSingleIdConflict = hasConflict,
                    legacyJson = payload.legacyJson,
                    warnings = payload.warnings
                ),
                payload = payload,
                stagingDirectory = staging
            )
        } catch (throwable: Throwable) {
            staging.deleteRecursively()
            throw throwable
        }
    }

    fun discard(prepared: PreparedReaderPresetImport) {
        prepared.stagingDirectory.deleteRecursively()
    }

    suspend fun importSingle(
        prepared: PreparedReaderPresetImport,
        choice: ReaderPresetSingleImportChoice
    ): ReaderPresetTransferImportResult = importPrepared(
        prepared = prepared,
        repositoryMode = when (choice) {
            ReaderPresetSingleImportChoice.OVERWRITE ->
                ReaderPresetRepositoryImportMode.SINGLE_OVERWRITE
            ReaderPresetSingleImportChoice.COPY ->
                ReaderPresetRepositoryImportMode.SINGLE_COPY
        },
        label = "导入“${prepared.preview.presetNames.single()}”"
    )

    suspend fun importLibrary(
        prepared: PreparedReaderPresetImport,
        choice: ReaderPresetLibraryImportChoice
    ): ReaderPresetTransferImportResult = importPrepared(
        prepared = prepared,
        repositoryMode = when (choice) {
            ReaderPresetLibraryImportChoice.MERGE ->
                ReaderPresetRepositoryImportMode.LIBRARY_MERGE
            ReaderPresetLibraryImportChoice.REPLACE ->
                ReaderPresetRepositoryImportMode.LIBRARY_REPLACE
        },
        label = when (choice) {
            ReaderPresetLibraryImportChoice.MERGE -> "合并全部预设"
            ReaderPresetLibraryImportChoice.REPLACE -> "替换全部预设"
        }
    )

    suspend fun refreshUndoHistory() = withContext(Dispatchers.IO) {
        pruneExpiredUndoRecords()
    }

    suspend fun undoLatest(force: Boolean = false): ReaderPresetUndoResult =
        withContext(Dispatchers.IO) {
            pruneExpiredUndoRecords()
            val latest = undoRecords.lastOrNull()
                ?: return@withContext ReaderPresetUndoResult(remainingCount = 0)
            val currentSnapshot = repository.exportSnapshot()
            val currentSelection = repository.currentSelection()
            val currentFingerprint = ReaderPresetSnapshotCodec.fingerprint(
                currentSnapshot,
                currentSelection
            )
            if (!force && currentFingerprint != latest.afterFingerprint) {
                return@withContext ReaderPresetUndoResult(
                    requiresConfirmation = true,
                    remainingCount = undoRecords.size
                )
            }
            repository.restoreImportSnapshot(latest.beforeSnapshot, latest.beforeSelection)
            undoRecords = undoRecords.dropLast(1)
            if (undoRecords.isNotEmpty()) {
                val restoredSnapshot = repository.exportSnapshot()
                val restoredSelection = repository.currentSelection()
                val restoredFingerprint = ReaderPresetSnapshotCodec.fingerprint(
                    restoredSnapshot,
                    restoredSelection
                )
                undoRecords = undoRecords.dropLast(1) +
                    undoRecords.last().copy(afterFingerprint = restoredFingerprint)
            }
            saveUndoRecords()
            publishUndoEntries()
            pruneUnreferencedResources()
            ReaderPresetUndoResult(
                restoredLabel = latest.label,
                remainingCount = undoRecords.size
            )
        }

    private suspend fun importPrepared(
        prepared: PreparedReaderPresetImport,
        repositoryMode: ReaderPresetRepositoryImportMode,
        label: String
    ): ReaderPresetTransferImportResult = withContext(Dispatchers.IO) {
        require(
            (prepared.preview.scope == ReaderPresetPackageScope.SINGLE &&
                repositoryMode in setOf(
                    ReaderPresetRepositoryImportMode.SINGLE_COPY,
                    ReaderPresetRepositoryImportMode.SINGLE_OVERWRITE
                )) ||
                (prepared.preview.scope == ReaderPresetPackageScope.LIBRARY &&
                    repositoryMode in setOf(
                        ReaderPresetRepositoryImportMode.LIBRARY_MERGE,
                        ReaderPresetRepositoryImportMode.LIBRARY_REPLACE
                    ))
        ) { "导入模式与预设包范围不匹配" }
        val beforeSnapshot = repository.exportSnapshot()
        val beforeSelection = repository.currentSelection()
        val beforeFingerprint = ReaderPresetSnapshotCodec.fingerprint(
            beforeSnapshot,
            beforeSelection
        )
        try {
            installResources(prepared.payload.resources)
            val result = repository.applyImportedSnapshot(prepared.payload.snapshot, repositoryMode)
            val afterSnapshot = repository.exportSnapshot()
            val afterSelection = repository.currentSelection()
            val afterFingerprint = ReaderPresetSnapshotCodec.fingerprint(
                afterSnapshot,
                afterSelection
            )
            runCatching {
                recordUndo(
                    label = label,
                    beforeSnapshot = beforeSnapshot,
                    beforeSelection = beforeSelection,
                    beforeFingerprint = beforeFingerprint,
                    afterFingerprint = afterFingerprint
                )
            }.getOrElse { journalError ->
                repository.restoreImportSnapshot(beforeSnapshot, beforeSelection)
                throw IllegalStateException("无法保存撤销记录，导入已回滚", journalError)
            }
            ReaderPresetTransferImportResult(
                scope = prepared.preview.scope,
                importedPresetIds = result.importedPresetIds,
                importedPresetNames = result.importedPresetNames,
                warnings = prepared.preview.warnings
            )
        } catch (throwable: Throwable) {
            runCatching { pruneUnreferencedResources() }
            throw throwable
        } finally {
            prepared.stagingDirectory.deleteRecursively()
        }
    }

    private suspend fun buildExportPayload(
        scope: ReaderPresetPackageScope,
        presetId: String?
    ): ReaderPresetPackagePayload {
        val full = repository.exportSnapshot()
        val livePresets = full.presets.filterNot(ReaderPresetEntity::deleted)
        val selectedPresets = if (scope == ReaderPresetPackageScope.SINGLE) {
            listOf(
                requireNotNull(livePresets.firstOrNull { it.id == presetId }) { "预设不存在" }
            )
        } else {
            livePresets
        }
        require(selectedPresets.isNotEmpty()) { "没有可导出的预设" }

        val selectedModels = selectedPresets.map { ReaderPresetCodec.decode(it.payloadJson) }
        val fontIds = if (scope == ReaderPresetPackageScope.LIBRARY) {
            full.fonts.filterNot(ReaderFontAssetEntity::deleted)
                .mapTo(hashSetOf(), ReaderFontAssetEntity::id)
        } else {
            selectedModels.flatMapTo(hashSetOf(), ReaderPreset::referencedFontIds)
        }
        val backgroundIds = if (scope == ReaderPresetPackageScope.LIBRARY) {
            full.backgrounds.filterNot(ReaderBackgroundAssetEntity::deleted)
                .mapTo(hashSetOf(), ReaderBackgroundAssetEntity::id)
        } else {
            selectedModels.flatMapTo(hashSetOf(), ReaderPreset::referencedBackgroundIds).also { ids ->
                var changed: Boolean
                do {
                    changed = false
                    full.backgrounds.filterNot(ReaderBackgroundAssetEntity::deleted)
                        .filter { it.id in ids }
                        .mapNotNull(ReaderBackgroundAssetEntity::posterAssetId)
                        .forEach { if (ids.add(it)) changed = true }
                } while (changed)
            }
        }
        val selectedFonts = full.fonts.filter {
            !it.deleted && it.id in fontIds
        }
        val selectedBackgrounds = full.backgrounds.filter {
            !it.deleted && it.id in backgroundIds
        }

        val resources = mutableListOf<ReaderPresetPackageResource>()
        selectedFonts.forEach { font ->
            val file = repository.resourceStore.fontFile(font.fileName)
                ?: error("字体资源不存在：${font.displayName}")
            resources += ReaderPresetPackageResource(
                kind = ReaderPresetPackageResource.KIND_FONT,
                fileName = font.fileName,
                sha256 = font.sha256,
                byteCount = font.byteCount,
                sourceFile = file
            )
        }
        val normalizedBackgrounds = selectedBackgrounds.map { background ->
            val master = repository.resourceStore.backgroundFile(background.masterFileName)
                ?: error("背景资源不存在：${background.displayName}")
            resources += ReaderPresetPackageResource(
                kind = ReaderPresetPackageResource.KIND_BACKGROUND,
                fileName = background.masterFileName,
                sha256 = background.sha256,
                byteCount = background.byteCount,
                sourceFile = master
            )
            val sourceVariants = runCatching { JSONObject(background.variantsJson) }
                .getOrElse { JSONObject() }
            val exportedVariants = JSONObject()
            val keys = sourceVariants.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val variant = sourceVariants.optJSONObject(key) ?: continue
                val fileName = variant.optString("fileName")
                val hash = variant.optString("sha256")
                val byteCount = variant.optLong("byteCount", -1L)
                val file = repository.resourceStore.variantFile(fileName)
                if (file != null && hash.matches(Regex("[0-9a-f]{64}")) &&
                    byteCount >= 0L && file.length() == byteCount &&
                    repository.resourceStore.fileSha256(file) == hash
                ) {
                    exportedVariants.put(key, variant)
                    resources += ReaderPresetPackageResource(
                        kind = ReaderPresetPackageResource.KIND_VARIANT,
                        fileName = fileName,
                        sha256 = hash,
                        byteCount = byteCount,
                        sourceFile = file
                    )
                }
            }
            background.copy(variantsJson = exportedVariants.toString())
        }
        return ReaderPresetPackagePayload(
            scope = scope,
            snapshot = ReaderPresetSnapshot(
                presets = selectedPresets,
                fonts = selectedFonts,
                backgrounds = normalizedBackgrounds,
                deletions = emptyList()
            ),
            resources = resources,
            exportedAt = System.currentTimeMillis(),
            appVersion = currentAppVersion()
        )
    }

    private suspend fun sanitizeLegacyPayload(
        payload: ReaderPresetPackagePayload
    ): ReaderPresetPackagePayload {
        val local = repository.exportSnapshot()
        val localFontIds = local.fonts.filterNot(ReaderFontAssetEntity::deleted)
            .mapTo(hashSetOf(), ReaderFontAssetEntity::id)
        val localBackgroundIds = local.backgrounds.filterNot(ReaderBackgroundAssetEntity::deleted)
            .mapTo(hashSetOf(), ReaderBackgroundAssetEntity::id)
        val warnings = payload.warnings.toMutableList()
        val records = payload.snapshot.presets.map { record ->
            var preset = ReaderPresetCodec.decode(record.payloadJson)
            val missingFonts = preset.referencedFontIds() - localFontIds
            missingFonts.forEach { id -> preset = preset.withoutFont(id) }
            if (missingFonts.isNotEmpty()) warnings += "缺失字体已改用系统字体"
            val missingBackgrounds = preset.referencedBackgroundIds() - localBackgroundIds
            if (missingBackgrounds.isNotEmpty()) {
                val background = preset.background
                preset = preset.copy(
                    background = if (background.assetId in missingBackgrounds) {
                        background.copy(
                            type = ReaderBackgroundType.SOLID,
                            assetId = null,
                            posterAssetId = null
                        )
                    } else {
                        background.copy(
                            posterAssetId = background.posterAssetId
                                .takeUnless { it in missingBackgrounds }
                        )
                    }
                )
                warnings += "缺失背景已回退为可用样式"
            }
            preset.toEntity()
        }
        return payload.copy(
            snapshot = payload.snapshot.copy(presets = records),
            warnings = warnings.distinct()
        )
    }

    private suspend fun installResources(resources: List<ReaderPresetPackageResource>) {
        resources.distinctBy { Triple(it.kind, it.fileName, it.sha256) }.forEach { resource ->
            val target = when (resource.kind) {
                ReaderPresetPackageResource.KIND_FONT ->
                    repository.resourceStore.targetFontFile(resource.fileName)
                ReaderPresetPackageResource.KIND_BACKGROUND ->
                    repository.resourceStore.targetBackgroundFile(resource.fileName)
                ReaderPresetPackageResource.KIND_VARIANT ->
                    repository.resourceStore.targetVariantFile(resource.fileName)
                else -> error("未知阅读器资源类型")
            }
            if (target.exists()) {
                require(
                    target.length() == resource.byteCount &&
                        repository.resourceStore.fileSha256(target) == resource.sha256
                ) { "本机同名资源内容冲突：${resource.fileName}" }
                return@forEach
            }
            val parent = requireNotNull(target.parentFile)
            require(parent.isDirectory || parent.mkdirs()) { "无法创建阅读器资源目录" }
            val partial = File(parent, ".${target.name}.${UUID.randomUUID()}.import")
            try {
                resource.sourceFile.inputStream().buffered().use { input ->
                    partial.outputStream().buffered().use { output -> input.copyTo(output) }
                }
                require(
                    partial.length() == resource.byteCount &&
                        repository.resourceStore.fileSha256(partial) == resource.sha256
                ) { "导入资源校验失败：${resource.fileName}" }
                require(partial.renameTo(target)) { "阅读器资源保存失败：${resource.fileName}" }
            } finally {
                partial.delete()
            }
        }
    }

    private fun recordUndo(
        label: String,
        beforeSnapshot: ReaderPresetSnapshot,
        beforeSelection: ReaderPresetSelection,
        beforeFingerprint: String,
        afterFingerprint: String
    ) {
        val now = nowMillis()
        undoRecords = undoRecords.filter { it.expiresAt > now }
        if (undoRecords.isNotEmpty()) {
            undoRecords = undoRecords.dropLast(1) +
                undoRecords.last().copy(afterFingerprint = beforeFingerprint)
        }
        undoRecords = undoRecords + ReaderPresetUndoRecord(
            id = UUID.randomUUID().toString(),
            label = label,
            createdAt = now,
            expiresAt = now + undoRetentionMillis,
            beforeSnapshot = beforeSnapshot,
            beforeSelection = beforeSelection,
            afterFingerprint = afterFingerprint
        )
        saveUndoRecords()
        publishUndoEntries()
    }

    private suspend fun pruneExpiredUndoRecords() {
        val now = nowMillis()
        val retained = undoRecords.filter { it.expiresAt > now }
        if (retained.size != undoRecords.size) {
            undoRecords = retained
            saveUndoRecords()
            publishUndoEntries()
            pruneUnreferencedResources()
        } else {
            publishUndoEntries()
        }
    }

    private suspend fun pruneUnreferencedResources() {
        val snapshots = buildList {
            add(repository.exportSnapshot())
            undoRecords.forEach { add(it.beforeSnapshot) }
        }
        val keepFonts = hashSetOf<String>()
        val keepBackgrounds = hashSetOf<String>()
        val keepVariants = hashSetOf<String>()
        snapshots.forEach { snapshot ->
            snapshot.fonts.filterNot(ReaderFontAssetEntity::deleted)
                .mapTo(keepFonts, ReaderFontAssetEntity::fileName)
            snapshot.backgrounds.filterNot(ReaderBackgroundAssetEntity::deleted).forEach {
                keepBackgrounds += it.masterFileName
                it.variantDescriptors().mapTo(keepVariants, ReaderBackgroundVariantDescriptor::fileName)
            }
        }
        repository.resourceStore.pruneUnreferencedFiles(
            keepFonts = keepFonts,
            keepBackgrounds = keepBackgrounds,
            keepVariants = keepVariants
        )
    }

    private fun loadUndoRecords(): List<ReaderPresetUndoRecord> {
        val atomicFile = AtomicFile(undoFile)
        if (!undoFile.isFile && !File("${undoFile.path}.bak").isFile) return emptyList()
        return runCatching {
            val array = atomicFile.openRead().bufferedReader(Charsets.UTF_8).use { reader ->
                JSONArray(reader.readText())
            }
            buildList {
                for (index in 0 until array.length()) {
                    val json = array.getJSONObject(index)
                    add(
                        ReaderPresetUndoRecord(
                            id = json.getString("id"),
                            label = json.getString("label"),
                            createdAt = json.getLong("createdAt"),
                            expiresAt = json.getLong("expiresAt"),
                            beforeSnapshot = ReaderPresetSnapshotCodec.decode(
                                json.getJSONObject("beforeSnapshot")
                            ),
                            beforeSelection = ReaderPresetSnapshotCodec.decodeSelection(
                                json.getJSONObject("beforeSelection")
                            ),
                            afterFingerprint = json.getString("afterFingerprint")
                        )
                    )
                }
            }
        }.getOrElse {
            atomicFile.delete()
            emptyList()
        }
    }

    private fun saveUndoRecords() {
        val json = JSONArray().also { array ->
            undoRecords.forEach { record ->
                array.put(JSONObject().apply {
                    put("id", record.id)
                    put("label", record.label)
                    put("createdAt", record.createdAt)
                    put("expiresAt", record.expiresAt)
                    put("beforeSnapshot", ReaderPresetSnapshotCodec.encode(record.beforeSnapshot))
                    put(
                        "beforeSelection",
                        ReaderPresetSnapshotCodec.encodeSelection(record.beforeSelection)
                    )
                    put("afterFingerprint", record.afterFingerprint)
                })
            }
        }
        val atomicFile = AtomicFile(undoFile)
        val output = atomicFile.startWrite()
        try {
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(output)
        } catch (throwable: Throwable) {
            atomicFile.failWrite(output)
            throw throwable
        }
    }

    private fun publishUndoEntries() {
        undoEntriesState.value = visibleUndoEntries(undoRecords)
    }

    private fun visibleUndoEntries(records: List<ReaderPresetUndoRecord>): List<ReaderPresetUndoEntry> =
        records.filter { it.expiresAt > nowMillis() }.map {
            ReaderPresetUndoEntry(it.id, it.label, it.createdAt, it.expiresAt)
        }

    private fun currentAppVersion(): String = runCatching {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    private fun contentLength(uri: Uri): Long? {
        if (uri.scheme == "file") {
            return uri.path?.let(::File)?.length()?.takeIf { it >= 0L }
        }
        return runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index < 0 || cursor.isNull(index)) null else cursor.getLong(index)
            }
        }.getOrNull()?.takeIf { it >= 0L }
    }

    private fun PushbackInputStream.readUtf8Bounded(limit: Long): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var count = 0L
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            count += read
            require(count <= limit) { "旧版预设 JSON 文件过大" }
            output.write(buffer, 0, read)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private data class ReaderPresetUndoRecord(
        val id: String,
        val label: String,
        val createdAt: Long,
        val expiresAt: Long,
        val beforeSnapshot: ReaderPresetSnapshot,
        val beforeSelection: ReaderPresetSelection,
        val afterFingerprint: String
    )

    private companion object {
        private const val IMPORT_STAGING_DIRECTORY = "reader-preset-imports"
        private const val UNDO_JOURNAL_FILE = "reader-preset-import-undo.json"
        private const val DEFAULT_UNDO_RETENTION_MS = 5L * 60L * 1000L
        private const val MAX_LEGACY_JSON_BYTES = 4L * 1024L * 1024L
        private val ZIP_SIGNATURE = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    }
}
