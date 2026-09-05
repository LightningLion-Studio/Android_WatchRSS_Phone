package com.lightningstudio.watchrss.phone.data.reader

import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import androidx.room.withTransaction
import com.lightningstudio.watchrss.phone.data.db.PhoneCompanionDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class ReaderThemeMode {
    LIGHT,
    DARK,
    SYSTEM
}

data class ReaderPresetSelection(
    val mode: ReaderThemeMode,
    val lightPresetId: String?,
    val darkPresetId: String?,
    val darkFollowsLight: Boolean
)

enum class ReaderPresetRepositoryImportMode {
    SINGLE_OVERWRITE,
    SINGLE_COPY,
    LIBRARY_MERGE,
    LIBRARY_REPLACE
}

data class ReaderPresetRepositoryImportResult(
    val importedPresetIds: List<String>,
    val importedPresetNames: List<String>,
    val warning: String? = null
)

class ReaderPresetRepository(
    context: Context,
    private val database: PhoneCompanionDatabase,
    private val dao: ReaderPresetDao,
    private val deviceId: String,
    scope: CoroutineScope,
    val resourceStore: ReaderResourceStore = ReaderResourceStore(context)
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        ACTIVE_PRESET_PREFERENCES,
        Context.MODE_PRIVATE
    )
    private val legacyActivePresetId = preferences.getString(ACTIVE_PRESET_KEY, null)
    private val selectionState = MutableStateFlow(
        ReaderPresetSelection(
            mode = preferences.getString(THEME_MODE_KEY, null)
                ?.let { runCatching { ReaderThemeMode.valueOf(it) }.getOrNull() }
                ?: ReaderThemeMode.SYSTEM,
            lightPresetId = preferences.getString(LIGHT_PRESET_KEY, legacyActivePresetId),
            darkPresetId = preferences.getString(DARK_PRESET_KEY, null),
            darkFollowsLight = preferences.getBoolean(
                DARK_FOLLOWS_LIGHT_KEY,
                !preferences.contains(DARK_FOLLOWS_LIGHT_KEY)
            )
        )
    )
    private val systemDark = MutableStateFlow(
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    )
    val selection: StateFlow<ReaderPresetSelection> = selectionState

    val presetRecords: StateFlow<List<ReaderPresetEntity>> = dao.observeAllPresets()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val presets: StateFlow<List<ReaderPreset>> = presetRecords
        .map { records ->
            records.asSequence()
                .filterNot(ReaderPresetEntity::deleted)
                .mapNotNull { runCatching { ReaderPresetCodec.decode(it.payloadJson) }.getOrNull() }
                .sortedBy { it.name.lowercase() }
                .toList()
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val fonts: StateFlow<List<ReaderFontAssetEntity>> = dao.observeAllFonts()
        .map { it.filterNot(ReaderFontAssetEntity::deleted) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val backgrounds: StateFlow<List<ReaderBackgroundAssetEntity>> = dao.observeAllBackgrounds()
        .map {
            it.filterNot(ReaderBackgroundAssetEntity::deleted)
                .sortedByDescending(ReaderBackgroundAssetEntity::updatedAt)
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val activePreset: StateFlow<ReaderPreset> = combine(
        presets,
        selectionState,
        systemDark
    ) { all, selection, isSystemDark ->
        val useDark = when (selection.mode) {
            ReaderThemeMode.LIGHT -> false
            ReaderThemeMode.DARK -> true
            ReaderThemeMode.SYSTEM -> isSystemDark
        }
        val id = if (useDark && !selection.darkFollowsLight) {
            selection.darkPresetId
        } else {
            selection.lightPresetId
        }
        if (id == ReaderPreset.FALLBACK_ID) ReaderPreset.fallback
        else all.firstOrNull { it.id == id } ?: all.firstOrNull() ?: ReaderPreset.fallback
    }.stateIn(scope, SharingStarted.Eagerly, ReaderPreset.fallback)

    suspend fun ensureSeeded() {
        if (dao.allPresetRecords().isNotEmpty()) return
        val now = System.currentTimeMillis()
        val dark = ReaderPreset.darkDefault().copy(updatedAt = now, modifiedBy = deviceId)
        val light = ReaderPreset.lightDefault().copy(updatedAt = now + 1, modifiedBy = deviceId)
        dao.upsertPresets(listOf(dark.toEntity(), light.toEntity()))
        updateSelection(
            ReaderPresetSelection(
                mode = ReaderThemeMode.SYSTEM,
                lightPresetId = light.id,
                darkPresetId = dark.id,
                darkFollowsLight = false
            )
        )
    }

    suspend fun savePreset(draft: ReaderPreset, applyAfterSave: Boolean = false): ReaderPreset {
        val normalized = draft.normalized()
        require(normalized.name.isNotBlank()) { "预设名称不能为空" }
        require(
            presets.value.none {
                it.id != normalized.id &&
                    canonicalPresetName(it.name) == canonicalPresetName(normalized.name)
            } &&
                dao.countNameConflicts(normalized.name, normalized.id) == 0
        ) {
            "预设名称已存在"
        }
        val saved = normalized.copy(
            updatedAt = nextTimestamp(normalized.updatedAt),
            modifiedBy = deviceId,
            deleted = false
        )
        dao.upsertPreset(saved.toEntity())
        if (applyAfterSave) {
            updateSelection(
                selectionState.value.copy(
                    lightPresetId = saved.id,
                    darkPresetId = null,
                    darkFollowsLight = true
                )
            )
        }
        return saved
    }

    suspend fun saveAsNew(
        draft: ReaderPreset,
        name: String,
        applyAfterSave: Boolean = false
    ): ReaderPreset =
        savePreset(
            draft.copy(
                id = UUID.randomUUID().toString(),
                name = uniqueName(name),
                updatedAt = 0L,
                modifiedBy = deviceId,
                deleted = false
            ),
            applyAfterSave = applyAfterSave
        )

    suspend fun duplicate(id: String): ReaderPreset {
        val original = requireNotNull(preset(id)) { "预设不存在" }
        return saveAsNew(original, uniqueName("${original.name} 副本"))
    }

    suspend fun rename(id: String, name: String): ReaderPreset {
        val original = requireNotNull(preset(id)) { "预设不存在" }
        return savePreset(original.copy(name = name))
    }

    suspend fun deletePreset(id: String) {
        val original = dao.presetById(id) ?: return
        val timestamp = nextTimestamp(original.updatedAt)
        database.withTransaction {
            val tombstonePreset = runCatching {
                ReaderPresetCodec.decode(original.payloadJson)
            }.getOrElse {
                ReaderPreset(id = id, name = original.name)
            }.copy(
                updatedAt = timestamp,
                modifiedBy = deviceId,
                deleted = true
            )
            dao.upsertPreset(tombstonePreset.toEntity())
            dao.upsertDeletion(
                ReaderDeletionEntity(
                    kind = DELETION_KIND_PRESET,
                    entityId = id,
                    deletedAt = timestamp,
                    deletedBy = deviceId
                )
            )
        }
        val current = selectionState.value
        updateSelection(
            current.copy(
                lightPresetId = current.lightPresetId
                    .takeUnless { it == id }
                    ?: ReaderPreset.FALLBACK_ID,
                darkPresetId = current.darkPresetId.takeUnless { it == id },
                darkFollowsLight = current.darkFollowsLight || current.darkPresetId == id
            )
        )
    }

    fun setActivePreset(id: String?) {
        val safeId = validPresetId(id)
        updateSelection(
            selectionState.value.copy(
                lightPresetId = safeId,
                darkPresetId = null,
                darkFollowsLight = true
            )
        )
    }

    fun setThemeMode(mode: ReaderThemeMode) {
        updateSelection(selectionState.value.copy(mode = mode))
    }

    fun setLightPreset(id: String?) {
        updateSelection(selectionState.value.copy(lightPresetId = validPresetId(id)))
    }

    fun setDarkPreset(id: String?) {
        updateSelection(
            selectionState.value.copy(
                darkPresetId = validPresetId(id),
                darkFollowsLight = id == null
            )
        )
    }

    fun setSystemDark(isDark: Boolean) {
        systemDark.value = isDark
    }

    private fun validPresetId(id: String?): String? =
        id?.takeIf { candidate ->
            candidate == ReaderPreset.FALLBACK_ID || presets.value.any { it.id == candidate }
        }

    private fun updateSelection(selection: ReaderPresetSelection) {
        preferences.edit()
            .putString(THEME_MODE_KEY, selection.mode.name)
            .putString(LIGHT_PRESET_KEY, selection.lightPresetId)
            .putString(DARK_PRESET_KEY, selection.darkPresetId)
            .putBoolean(DARK_FOLLOWS_LIGHT_KEY, selection.darkFollowsLight)
            .remove(ACTIVE_PRESET_KEY)
            .apply()
        selectionState.value = selection
    }

    suspend fun importFont(uri: Uri): ReaderFontAssetEntity {
        return saveImportedFont(resourceStore.importFont(uri))
    }

    suspend fun availableSystemFonts(): List<SystemReaderFont> =
        resourceStore.availableSystemFonts()

    suspend fun importSystemFont(font: SystemReaderFont): ReaderFontAssetEntity {
        return saveImportedFont(resourceStore.importSystemFont(font))
    }

    private suspend fun saveImportedFont(
        imported: ImportedReaderFont
    ): ReaderFontAssetEntity {
        dao.fontByHash(imported.sha256)?.takeIf { !it.deleted }?.let { return it }
        val now = System.currentTimeMillis()
        val entity = ReaderFontAssetEntity(
            id = imported.sha256,
            sha256 = imported.sha256,
            displayName = uniqueFontName(imported.displayName),
            familyName = imported.familyName,
            fileName = imported.fileName,
            mimeType = imported.mimeType,
            byteCount = imported.byteCount,
            faceCount = imported.faceCount,
            metadataJson = imported.metadataJson,
            updatedAt = now,
            modifiedBy = deviceId,
            deleted = false
        )
        database.withTransaction {
            dao.upsertFont(entity)
            dao.deleteDeletion(DELETION_KIND_FONT, entity.id)
        }
        return entity
    }

    suspend fun renameFont(id: String, name: String): ReaderFontAssetEntity {
        val current = requireNotNull(dao.fontById(id)?.takeUnless { it.deleted }) {
            "字体不存在"
        }
        val normalized = name.trim().take(MAX_FONT_NAME_LENGTH)
        require(normalized.isNotBlank()) { "字体名称不能为空" }
        require(
            fonts.value.none {
                it.id != id && it.displayName.trim().equals(normalized, ignoreCase = true)
            }
        ) { "字体名称已存在" }
        return current.copy(
            displayName = normalized,
            updatedAt = nextTimestamp(current.updatedAt),
            modifiedBy = deviceId
        ).also { dao.upsertFont(it) }
    }

    fun fontUsageNames(id: String): List<String> =
        presets.value.filter { it.referencesFont(id) }.map(ReaderPreset::name)

    suspend fun deleteFont(id: String) {
        val current = dao.fontById(id)?.takeUnless { it.deleted } ?: return
        val timestamp = nextTimestamp(current.updatedAt)
        database.withTransaction {
            val updatedPresets = dao.allPresetRecords()
                .asSequence()
                .filterNot(ReaderPresetEntity::deleted)
                .mapNotNull { record ->
                    runCatching { ReaderPresetCodec.decode(record.payloadJson) }.getOrNull()
                }
                .filter { it.referencesFont(id) }
                .mapIndexed { index, preset ->
                    preset.withoutFont(id).copy(
                        updatedAt = timestamp + index + 1L,
                        modifiedBy = deviceId
                    ).toEntity()
                }
                .toList()
            if (updatedPresets.isNotEmpty()) dao.upsertPresets(updatedPresets)
            dao.upsertFont(
                current.copy(
                    updatedAt = timestamp,
                    modifiedBy = deviceId,
                    deleted = true
                )
            )
            dao.upsertDeletion(
                ReaderDeletionEntity(
                    kind = DELETION_KIND_FONT,
                    entityId = id,
                    deletedAt = timestamp,
                    deletedBy = deviceId
                )
            )
        }
        resourceStore.deleteFontFile(current.fileName)
    }

    suspend fun inspectBackground(uri: Uri): ReaderBackgroundImportInspection =
        resourceStore.inspectBackground(uri)

    suspend fun importBackground(
        uri: Uri,
        mode: ReaderBackgroundImportMode = ReaderBackgroundImportMode.KEEP_ORIGINAL,
        inspection: ReaderBackgroundImportInspection? = null
    ): ReaderBackgroundAssetEntity {
        val imported = resourceStore.importBackground(uri, mode, inspection)
        val entity = ReaderBackgroundAssetEntity(
            id = imported.id,
            sha256 = imported.sha256,
            displayName = uniqueBackgroundName(imported.displayName),
            kind = imported.kind.name,
            mimeType = imported.mimeType,
            masterFileName = imported.fileName,
            byteCount = imported.byteCount,
            durationMs = imported.durationMs,
            width = imported.width,
            height = imported.height,
            posterAssetId = null,
            variantsJson = imported.variantsJson,
            updatedAt = System.currentTimeMillis(),
            modifiedBy = deviceId,
            deleted = false
        )
        dao.upsertBackground(entity)
        return entity
    }

    suspend fun extractVideoFrame(
        sourceAssetId: String,
        timeMs: Long,
        cropX: Float = 0f,
        cropY: Float = 0f
    ): ReaderBackgroundAssetEntity {
        val sourceAsset = requireNotNull(
            dao.backgroundById(sourceAssetId)?.takeUnless { it.deleted }
        ) { "背景原视频不存在" }
        require(sourceAsset.kind == ReaderBackgroundType.VIDEO.name) { "所选资源不是视频" }
        val sourceFile = requireNotNull(resourceStore.backgroundFile(sourceAsset.masterFileName)) {
            "背景原视频不存在"
        }
        val imported = resourceStore.extractVideoFrame(
            source = sourceFile,
            sourceAssetId = sourceAssetId,
            timeMs = timeMs.coerceIn(0L, sourceAsset.durationMs.coerceAtLeast(0L)),
            cropX = cropX,
            cropY = cropY
        )
        val entity = ReaderBackgroundAssetEntity(
            id = imported.id,
            sha256 = imported.sha256,
            displayName = uniqueBackgroundName(imported.displayName),
            kind = imported.kind.name,
            mimeType = imported.mimeType,
            masterFileName = imported.fileName,
            byteCount = imported.byteCount,
            durationMs = imported.durationMs,
            width = imported.width,
            height = imported.height,
            posterAssetId = null,
            variantsJson = imported.variantsJson,
            updatedAt = System.currentTimeMillis(),
            modifiedBy = deviceId,
            deleted = false
        )
        dao.upsertBackground(entity)
        return entity
    }

    suspend fun updateVideoEdit(
        assetId: String,
        cropX: Float,
        cropY: Float,
        frameTimeMs: Long
    ): ReaderBackgroundAssetEntity {
        val current = requireNotNull(dao.backgroundById(assetId)?.takeUnless { it.deleted }) {
            "背景原视频不存在"
        }
        require(current.kind == ReaderBackgroundType.VIDEO.name) { "所选资源不是视频" }
        val variants = runCatching { JSONObject(current.variantsJson) }.getOrElse { JSONObject() }
        variants.remove("watch")
        variants.remove("watchPoster")
        variants.put("edit", JSONObject().apply {
            put("cropX", cropX.coerceIn(-1f, 1f).toDouble())
            put("cropY", cropY.coerceIn(-1f, 1f).toDouble())
            put("frameTimeMs", frameTimeMs.coerceIn(0L, current.durationMs.coerceAtLeast(0L)))
        })
        return current.copy(variantsJson = variants.toString()).also { updateBackgroundAsset(it) }
    }

    suspend fun updateBackgroundAsset(entity: ReaderBackgroundAssetEntity) {
        dao.upsertBackground(
            entity.copy(
                updatedAt = nextTimestamp(entity.updatedAt),
                modifiedBy = deviceId
            )
        )
    }

    fun fontFile(assetId: String?): File? {
        val record = fonts.value.firstOrNull { it.id == assetId } ?: return null
        return resourceStore.fontFile(record.fileName)
    }

    fun backgroundFile(assetId: String?): File? {
        val record = backgrounds.value.firstOrNull { it.id == assetId } ?: return null
        return resourceStore.backgroundFile(record.masterFileName)
    }

    suspend fun preset(id: String): ReaderPreset? =
        dao.presetById(id)
            ?.takeUnless(ReaderPresetEntity::deleted)
            ?.let { runCatching { ReaderPresetCodec.decode(it.payloadJson) }.getOrNull() }

    suspend fun mergeRemote(
        presets: List<ReaderPresetEntity>,
        fonts: List<ReaderFontAssetEntity>,
        backgrounds: List<ReaderBackgroundAssetEntity>,
        deletions: List<ReaderDeletionEntity>
    ) {
        database.withTransaction {
            presets.forEach { incoming ->
                val local = dao.presetById(incoming.id)
                if (local == null || incoming.winsOver(local)) dao.upsertPreset(incoming)
            }
            fonts.forEach { incoming ->
                val local = dao.fontById(incoming.id)
                if (local == null || incoming.winsOver(local)) dao.upsertFont(incoming)
            }
            backgrounds.forEach { incoming ->
                val local = dao.backgroundById(incoming.id)
                if (local == null || incoming.winsOver(local)) dao.upsertBackground(incoming)
            }
            dao.upsertDeletions(deletions)
        }
    }

    suspend fun exportSnapshot(): ReaderPresetSnapshot = ReaderPresetSnapshot(
        presets = dao.allPresetRecords(),
        fonts = dao.allFontRecords(),
        backgrounds = dao.allBackgroundRecords(),
        deletions = dao.allDeletions()
    )

    fun currentSelection(): ReaderPresetSelection = selectionState.value

    suspend fun applyImportedSnapshot(
        incoming: ReaderPresetSnapshot,
        mode: ReaderPresetRepositoryImportMode
    ): ReaderPresetRepositoryImportResult {
        val incomingPresets = incoming.presets
            .filterNot(ReaderPresetEntity::deleted)
            .map { record ->
                val preset = ReaderPresetCodec.decode(record.payloadJson)
                require(preset.id == record.id) { "预设 ID 与内容不匹配" }
                require(preset.name.isNotBlank()) { "预设名称不能为空" }
                preset.copy(deleted = false)
            }
        require(incomingPresets.isNotEmpty()) { "预设包中没有可导入的预设" }
        if (mode == ReaderPresetRepositoryImportMode.SINGLE_COPY ||
            mode == ReaderPresetRepositoryImportMode.SINGLE_OVERWRITE
        ) {
            require(incomingPresets.size == 1) { "单项预设包只能包含一个预设" }
        }
        require(incoming.fonts.none(ReaderFontAssetEntity::deleted)) {
            "预设包不能包含已删除字体"
        }
        require(incoming.backgrounds.none(ReaderBackgroundAssetEntity::deleted)) {
            "预设包不能包含已删除背景"
        }

        val savedPresets = mutableListOf<ReaderPreset>()
        database.withTransaction {
            val localPresetRecords = dao.allPresetRecords()
            val localFontRecords = dao.allFontRecords()
            val localBackgroundRecords = dao.allBackgroundRecords()
            val localDeletions = dao.allDeletions()
            var timestamp = maxOf(
                System.currentTimeMillis(),
                localPresetRecords.maxOfOrNull(ReaderPresetEntity::updatedAt)?.plus(1L) ?: 0L,
                localFontRecords.maxOfOrNull(ReaderFontAssetEntity::updatedAt)?.plus(1L) ?: 0L,
                localBackgroundRecords.maxOfOrNull(ReaderBackgroundAssetEntity::updatedAt)
                    ?.plus(1L) ?: 0L,
                localDeletions.maxOfOrNull(ReaderDeletionEntity::deletedAt)?.plus(1L) ?: 0L
            )
            fun nextTimestamp(): Long = timestamp++

            val incomingFontIds = incoming.fonts.mapTo(hashSetOf(), ReaderFontAssetEntity::id)
            val incomingBackgroundIds = incoming.backgrounds
                .mapTo(hashSetOf(), ReaderBackgroundAssetEntity::id)

            incoming.fonts.forEach { font ->
                require(font.id == font.sha256) { "字体资源 ID 与哈希不匹配" }
                dao.upsertFont(
                    font.copy(
                        updatedAt = nextTimestamp(),
                        modifiedBy = deviceId,
                        deleted = false
                    )
                )
                dao.deleteDeletion(DELETION_KIND_FONT, font.id)
            }
            incoming.backgrounds.forEach { background ->
                dao.upsertBackground(
                    background.copy(
                        updatedAt = nextTimestamp(),
                        modifiedBy = deviceId,
                        deleted = false
                    )
                )
                dao.deleteDeletion(DELETION_KIND_BACKGROUND, background.id)
            }

            if (mode == ReaderPresetRepositoryImportMode.LIBRARY_REPLACE) {
                localPresetRecords.filterNot(ReaderPresetEntity::deleted)
                    .filter { local -> incomingPresets.none { it.id == local.id } }
                    .forEach { local ->
                        val deletedAt = nextTimestamp()
                        val tombstone = runCatching {
                            ReaderPresetCodec.decode(local.payloadJson)
                        }.getOrElse {
                            ReaderPreset(id = local.id, name = local.name)
                        }.copy(
                            updatedAt = deletedAt,
                            modifiedBy = deviceId,
                            deleted = true
                        )
                        dao.upsertPreset(tombstone.toEntity())
                        dao.upsertDeletion(
                            ReaderDeletionEntity(
                                kind = DELETION_KIND_PRESET,
                                entityId = local.id,
                                deletedAt = deletedAt,
                                deletedBy = deviceId
                            )
                        )
                    }
                localFontRecords.filterNot(ReaderFontAssetEntity::deleted)
                    .filter { it.id !in incomingFontIds }
                    .forEach { local ->
                        val deletedAt = nextTimestamp()
                        dao.upsertFont(
                            local.copy(
                                updatedAt = deletedAt,
                                modifiedBy = deviceId,
                                deleted = true
                            )
                        )
                        dao.upsertDeletion(
                            ReaderDeletionEntity(
                                kind = DELETION_KIND_FONT,
                                entityId = local.id,
                                deletedAt = deletedAt,
                                deletedBy = deviceId
                            )
                        )
                    }
                localBackgroundRecords.filterNot(ReaderBackgroundAssetEntity::deleted)
                    .filter { it.id !in incomingBackgroundIds }
                    .forEach { local ->
                        val deletedAt = nextTimestamp()
                        dao.upsertBackground(
                            local.copy(
                                updatedAt = deletedAt,
                                modifiedBy = deviceId,
                                deleted = true
                            )
                        )
                        dao.upsertDeletion(
                            ReaderDeletionEntity(
                                kind = DELETION_KIND_BACKGROUND,
                                entityId = local.id,
                                deletedAt = deletedAt,
                                deletedBy = deviceId
                            )
                        )
                    }
            }

            val namesInUse = localPresetRecords.asSequence()
                .filterNot(ReaderPresetEntity::deleted)
                .filter { local ->
                    when (mode) {
                        ReaderPresetRepositoryImportMode.LIBRARY_REPLACE -> false
                        ReaderPresetRepositoryImportMode.SINGLE_COPY -> true
                        else -> incomingPresets.none { it.id == local.id }
                    }
                }
                .map { canonicalPresetName(it.name) }
                .toMutableSet()

            incomingPresets.sortedBy { it.name.lowercase() }.forEach { imported ->
                val targetId = if (mode == ReaderPresetRepositoryImportMode.SINGLE_COPY) {
                    UUID.randomUUID().toString()
                } else {
                    imported.id
                }
                val uniqueName = uniqueNameFromSet(imported.name, namesInUse)
                namesInUse += canonicalPresetName(uniqueName)
                val saved = imported.copy(
                    id = targetId,
                    name = uniqueName,
                    updatedAt = nextTimestamp(),
                    modifiedBy = deviceId,
                    deleted = false
                ).normalized()
                dao.upsertPreset(saved.toEntity())
                dao.deleteDeletion(DELETION_KIND_PRESET, targetId)
                savedPresets += saved
            }
        }

        if (mode == ReaderPresetRepositoryImportMode.LIBRARY_REPLACE) {
            val importedIds = savedPresets.mapTo(hashSetOf(), ReaderPreset::id)
            val firstId = savedPresets.sortedBy { it.name.lowercase() }.first().id
            val current = selectionState.value
            val lightId = current.lightPresetId?.takeIf { it in importedIds } ?: firstId
            val keepIndependentDark = !current.darkFollowsLight && current.darkPresetId in importedIds
            updateSelection(
                current.copy(
                    lightPresetId = lightId,
                    darkPresetId = current.darkPresetId.takeIf { keepIndependentDark },
                    darkFollowsLight = !keepIndependentDark
                )
            )
        }

        return ReaderPresetRepositoryImportResult(
            importedPresetIds = savedPresets.map(ReaderPreset::id),
            importedPresetNames = savedPresets.map(ReaderPreset::name)
        )
    }

    suspend fun restoreImportSnapshot(
        desired: ReaderPresetSnapshot,
        desiredSelection: ReaderPresetSelection
    ) {
        database.withTransaction {
            val currentPresets = dao.allPresetRecords()
            val currentFonts = dao.allFontRecords()
            val currentBackgrounds = dao.allBackgroundRecords()
            val currentDeletions = dao.allDeletions()
            var timestamp = maxOf(
                System.currentTimeMillis(),
                currentPresets.maxOfOrNull(ReaderPresetEntity::updatedAt)?.plus(1L) ?: 0L,
                currentFonts.maxOfOrNull(ReaderFontAssetEntity::updatedAt)?.plus(1L) ?: 0L,
                currentBackgrounds.maxOfOrNull(ReaderBackgroundAssetEntity::updatedAt)
                    ?.plus(1L) ?: 0L,
                currentDeletions.maxOfOrNull(ReaderDeletionEntity::deletedAt)?.plus(1L) ?: 0L
            )
            fun nextTimestamp(): Long = timestamp++

            val desiredPresetMap = desired.presets.associateBy(ReaderPresetEntity::id)
            val desiredFontMap = desired.fonts.associateBy(ReaderFontAssetEntity::id)
            val desiredBackgroundMap = desired.backgrounds
                .associateBy(ReaderBackgroundAssetEntity::id)

            currentPresets.filterNot(ReaderPresetEntity::deleted)
                .filter { desiredPresetMap[it.id]?.deleted != false }
                .forEach { current ->
                    val deletedAt = nextTimestamp()
                    val tombstone = ReaderPresetCodec.decode(current.payloadJson).copy(
                        updatedAt = deletedAt,
                        modifiedBy = deviceId,
                        deleted = true
                    )
                    dao.upsertPreset(tombstone.toEntity())
                    dao.upsertDeletion(
                        ReaderDeletionEntity(
                            DELETION_KIND_PRESET,
                            current.id,
                            deletedAt,
                            deviceId
                        )
                    )
                }
            desiredPresetMap.values.forEach { record ->
                val changedAt = nextTimestamp()
                val restored = ReaderPresetCodec.decode(record.payloadJson).copy(
                    updatedAt = changedAt,
                    modifiedBy = deviceId,
                    deleted = record.deleted
                )
                dao.upsertPreset(restored.toEntity())
                if (record.deleted) {
                    dao.upsertDeletion(
                        ReaderDeletionEntity(
                            DELETION_KIND_PRESET,
                            record.id,
                            changedAt,
                            deviceId
                        )
                    )
                } else {
                    dao.deleteDeletion(DELETION_KIND_PRESET, record.id)
                }
            }

            restoreAssetRecords(
                current = currentFonts,
                desired = desiredFontMap,
                kind = DELETION_KIND_FONT,
                nextTimestamp = ::nextTimestamp,
                upsert = dao::upsertFont,
                tombstone = { entity, changedAt ->
                    entity.copy(
                        updatedAt = changedAt,
                        modifiedBy = deviceId,
                        deleted = true
                    )
                },
                restore = { entity, changedAt ->
                    entity.copy(updatedAt = changedAt, modifiedBy = deviceId)
                },
                id = ReaderFontAssetEntity::id,
                deleted = ReaderFontAssetEntity::deleted
            )
            restoreAssetRecords(
                current = currentBackgrounds,
                desired = desiredBackgroundMap,
                kind = DELETION_KIND_BACKGROUND,
                nextTimestamp = ::nextTimestamp,
                upsert = dao::upsertBackground,
                tombstone = { entity, changedAt ->
                    entity.copy(
                        updatedAt = changedAt,
                        modifiedBy = deviceId,
                        deleted = true
                    )
                },
                restore = { entity, changedAt ->
                    entity.copy(updatedAt = changedAt, modifiedBy = deviceId)
                },
                id = ReaderBackgroundAssetEntity::id,
                deleted = ReaderBackgroundAssetEntity::deleted
            )
        }

        val availableIds = desired.presets.asSequence()
            .filterNot(ReaderPresetEntity::deleted)
            .mapTo(hashSetOf(), ReaderPresetEntity::id)
        val fallbackId = desired.presets.asSequence()
            .filterNot(ReaderPresetEntity::deleted)
            .sortedBy { it.name.lowercase() }
            .firstOrNull()?.id ?: ReaderPreset.FALLBACK_ID
        val lightId = desiredSelection.lightPresetId
            ?.takeIf { it == ReaderPreset.FALLBACK_ID || it in availableIds }
            ?: fallbackId
        val keepIndependentDark = !desiredSelection.darkFollowsLight &&
            desiredSelection.darkPresetId in availableIds
        updateSelection(
            desiredSelection.copy(
                lightPresetId = lightId,
                darkPresetId = desiredSelection.darkPresetId.takeIf { keepIndependentDark },
                darkFollowsLight = !keepIndependentDark
            )
        )
    }

    private suspend fun <T> restoreAssetRecords(
        current: List<T>,
        desired: Map<String, T>,
        kind: String,
        nextTimestamp: () -> Long,
        upsert: suspend (T) -> Unit,
        tombstone: (T, Long) -> T,
        restore: (T, Long) -> T,
        id: (T) -> String,
        deleted: (T) -> Boolean
    ) {
        current.filterNot(deleted)
            .filter { desired[id(it)]?.let(deleted) != false }
            .forEach { record ->
                val changedAt = nextTimestamp()
                upsert(tombstone(record, changedAt))
                dao.upsertDeletion(
                    ReaderDeletionEntity(kind, id(record), changedAt, deviceId)
                )
            }
        desired.values.forEach { record ->
            val changedAt = nextTimestamp()
            upsert(restore(record, changedAt))
            if (deleted(record)) {
                dao.upsertDeletion(
                    ReaderDeletionEntity(kind, id(record), changedAt, deviceId)
                )
            } else {
                dao.deleteDeletion(kind, id(record))
            }
        }
    }

    private suspend fun uniqueName(candidate: String): String {
        val base = candidate.trim().take(ReaderPreset.MAX_PRESET_NAME_LENGTH).ifBlank { "未命名预设" }
        if (dao.countNameConflicts(base, "") == 0) return base
        var suffix = 2
        while (true) {
            val suffixText = " $suffix"
            val value = base.take(ReaderPreset.MAX_PRESET_NAME_LENGTH - suffixText.length) + suffixText
            if (dao.countNameConflicts(value, "") == 0) return value
            suffix += 1
        }
    }

    private fun uniqueNameFromSet(candidate: String, namesInUse: Set<String>): String {
        val base = candidate.trim().take(ReaderPreset.MAX_PRESET_NAME_LENGTH)
            .ifBlank { "未命名预设" }
        if (canonicalPresetName(base) !in namesInUse) return base
        var suffix = 2
        while (true) {
            val suffixText = " $suffix"
            val value = base.take(ReaderPreset.MAX_PRESET_NAME_LENGTH - suffixText.length) + suffixText
            if (canonicalPresetName(value) !in namesInUse) return value
            suffix += 1
        }
    }

    private fun uniqueFontName(candidate: String): String {
        val existing = fonts.value.map { it.displayName.lowercase() }.toSet()
        return uniqueDisplayName(candidate, existing, "自定义字体")
    }

    private fun uniqueBackgroundName(candidate: String): String {
        val existing = backgrounds.value.map { it.displayName.lowercase() }.toSet()
        return uniqueDisplayName(candidate, existing, "阅读背景")
    }

    private fun uniqueDisplayName(candidate: String, existing: Set<String>, fallback: String): String {
        val base = candidate.trim().ifBlank { fallback }
        if (base.lowercase() !in existing) return base
        var index = 2
        while ("$base $index".lowercase() in existing) index += 1
        return "$base $index"
    }

    private fun nextTimestamp(previous: Long): Long =
        maxOf(System.currentTimeMillis(), previous + 1L)

    private fun canonicalPresetName(value: String): String =
        value.filterNot(Char::isWhitespace).lowercase()

    companion object {
        private const val ACTIVE_PRESET_PREFERENCES = "reader_preset_state"
        private const val ACTIVE_PRESET_KEY = "active_preset_id"
        private const val THEME_MODE_KEY = "reader_theme_mode"
        private const val LIGHT_PRESET_KEY = "light_preset_id"
        private const val DARK_PRESET_KEY = "dark_preset_id"
        private const val DARK_FOLLOWS_LIGHT_KEY = "dark_follows_light"
        private const val MAX_FONT_NAME_LENGTH = 80
        const val DELETION_KIND_PRESET = "preset"
        const val DELETION_KIND_FONT = "font"
        const val DELETION_KIND_BACKGROUND = "background"
    }
}

internal fun ReaderPreset.referencesFont(id: String): Boolean =
    body.fontAssetId == id ||
        listOf(title, subtitle, quote, code, link).any {
            it.useOwnFont && it.fontAssetId == id
        }

internal fun ReaderPreset.withoutFont(id: String): ReaderPreset {
    fun ReaderTextStyleOverride.cleared(): ReaderTextStyleOverride =
        if (useOwnFont && fontAssetId == id) {
            copy(fontAssetId = null, useOwnFont = false, fontFaceIndex = null)
        } else {
            this
        }
    return copy(
        body = if (body.fontAssetId == id) {
            body.copy(fontAssetId = null, fontFaceIndex = 0, variationSettings = "")
        } else {
            body
        },
        title = title.cleared(),
        subtitle = subtitle.cleared(),
        quote = quote.cleared(),
        code = code.cleared(),
        link = link.cleared()
    )
}

data class ReaderPresetSnapshot(
    val presets: List<ReaderPresetEntity>,
    val fonts: List<ReaderFontAssetEntity>,
    val backgrounds: List<ReaderBackgroundAssetEntity>,
    val deletions: List<ReaderDeletionEntity>
)

fun ReaderPreset.toEntity(): ReaderPresetEntity {
    val safe = normalized()
    return ReaderPresetEntity(
        id = safe.id,
        name = safe.name,
        payloadJson = ReaderPresetCodec.encode(safe),
        updatedAt = safe.updatedAt,
        modifiedBy = safe.modifiedBy,
        deleted = safe.deleted
    )
}

private fun ReaderPresetEntity.winsOver(local: ReaderPresetEntity): Boolean =
    updatedAt > local.updatedAt ||
        (updatedAt == local.updatedAt && deleted && !local.deleted) ||
        (updatedAt == local.updatedAt && deleted == local.deleted && modifiedBy > local.modifiedBy)

private fun ReaderFontAssetEntity.winsOver(local: ReaderFontAssetEntity): Boolean =
    updatedAt > local.updatedAt ||
        (updatedAt == local.updatedAt && deleted && !local.deleted) ||
        (updatedAt == local.updatedAt && deleted == local.deleted && modifiedBy > local.modifiedBy)

private fun ReaderBackgroundAssetEntity.winsOver(local: ReaderBackgroundAssetEntity): Boolean =
    updatedAt > local.updatedAt ||
        (updatedAt == local.updatedAt && deleted && !local.deleted) ||
        (updatedAt == local.updatedAt && deleted == local.deleted && modifiedBy > local.modifiedBy)
