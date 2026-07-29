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
        .map { it.filterNot(ReaderBackgroundAssetEntity::deleted) }
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
        if (applyAfterSave) setActivePreset(saved.id)
        return saved
    }

    suspend fun saveAsNew(draft: ReaderPreset, name: String): ReaderPreset =
        savePreset(
            draft.copy(
                id = UUID.randomUUID().toString(),
                name = uniqueName(name),
                updatedAt = 0L,
                modifiedBy = deviceId,
                deleted = false
            )
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

    suspend fun importBackground(uri: Uri): ReaderBackgroundAssetEntity {
        val imported = resourceStore.importBackground(uri)
        dao.backgroundByHash(imported.sha256)?.takeIf { !it.deleted }?.let { return it }
        val entity = ReaderBackgroundAssetEntity(
            id = imported.sha256,
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
            variantsJson = "{}",
            updatedAt = System.currentTimeMillis(),
            modifiedBy = deviceId,
            deleted = false
        )
        dao.upsertBackground(entity)
        return entity
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
