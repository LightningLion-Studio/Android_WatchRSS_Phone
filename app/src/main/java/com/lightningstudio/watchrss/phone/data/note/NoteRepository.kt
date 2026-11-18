package com.lightningstudio.watchrss.phone.data.note

import kotlinx.coroutines.flow.Flow
import java.util.UUID

data class NoteRemoteApplyResult(
    val mergeResult: MarkdownMergeResult,
    val changedNotes: Int = 0,
    val addedConflicts: Int = 0
)

class NoteRepository(
    private val dao: NoteDao,
    private val deviceId: String,
    private val editorPreferenceOwner: String = deviceId,
    private val now: () -> Long = System::currentTimeMillis
) {
    suspend fun clearForAccountSwitch() = dao.clearForAccountSwitch()
    suspend fun hasAny(): Boolean = dao.hasAny()
    internal suspend fun editorSourceMode(noteId: String): Boolean? = dao.editorSourceMode(noteId, editorPreferenceOwner)
    fun observeNotes(): Flow<List<NoteEntity>> = dao.observeNotes()
    fun observeFolders(): Flow<List<NoteFolderEntity>> = dao.observeFolders()
    fun observeConflicts(): Flow<List<NoteConflictEntity>> = dao.observeUnresolvedConflicts()
    suspend fun allNotes(): List<NoteEntity> = dao.allNotes()
    suspend fun note(noteId:String):NoteEntity? = dao.note(noteId)
    suspend fun assets(noteId: String): List<NoteAssetEntity> = dao.assets(noteId)
    suspend fun registerAsset(asset: NoteAssetEntity): NoteAssetEntity {
        dao.assetByHash(asset.sha256)?.let { return it }
        dao.upsertAssets(listOf(asset))
        return asset
    }

    suspend fun save(
        noteId: String? = null,
        title: String,
        markdown: String,
        folderId: String? = null,
        pinned: Boolean = false,
        editorSourceMode: Boolean? = null
    ): NoteEntity {
        require(title.trim().isNotBlank() || MarkdownNoteCodec.toPlainText(markdown).isNotBlank()) {
            "笔记不能为空"
        }
        val previous = if (noteId == null) null else dao.note(noteId)
        val instant = now()
        // Markdown is authoritative, including existing CRLF/CR/LF and trailing whitespace.
        val canonical = markdown
        val hash = MarkdownNoteCodec.sha256(canonical)
        val saved = NoteEntity(
            noteId = previous?.noteId ?: noteId ?: UUID.randomUUID().toString(),
            folderId = folderId,
            title = title.trim().ifBlank { deriveNoteTitle(MarkdownNoteCodec.titlePlainText(canonical)) },
            markdown = canonical,
            plainText = MarkdownNoteCodec.toPlainText(canonical),
            contentHash = hash,
            baseContentHash = previous?.baseContentHash ?: hash,
            baseMarkdown = previous?.baseMarkdown ?: canonical,
            pinned = pinned,
            createdAt = previous?.createdAt ?: instant,
            updatedAt = instant,
            modifiedBy = deviceId,
            deleted = false,
            deletedAt = 0L
        )
        if (editorSourceMode == null) dao.upsertNotes(listOf(saved))
        else dao.upsertEditorNote(saved, editorPreferenceOwner, editorSourceMode)
        return saved
    }

    suspend fun move(noteId: String, folderId: String?): NoteEntity =
        updateMetadata(noteId) { it.copy(folderId = folderId) }

    suspend fun setPinned(noteId: String, pinned: Boolean): NoteEntity =
        updateMetadata(noteId) { it.copy(pinned = pinned) }

    suspend fun delete(noteId: String): NoteEntity {
        val instant = now()
        return updateMetadata(noteId, instant) {
            it.copy(deleted = true, deletedAt = instant)
        }
    }

    private suspend fun updateMetadata(
        noteId: String,
        instant: Long = now(),
        transform: (NoteEntity) -> NoteEntity
    ): NoteEntity {
        val local = dao.note(noteId) ?: error("笔记不存在")
        val updated = transform(local).copy(
            updatedAt = instant,
            modifiedBy = deviceId
        )
        dao.upsertNotes(listOf(updated))
        return updated
    }

    suspend fun importMarkdown(markdownFile: String, fallbackTitle: String? = null): NoteEntity {
        val imported = MarkdownNoteCodec.parse(markdownFile)
        return save(
            noteId = imported.noteId,
            title = imported.title
                ?: fallbackTitle.takeIf { imported.noteId == null }.orEmpty(),
            markdown = imported.markdown,
            folderId = imported.folderId
        )
    }

    suspend fun resolveConflict(conflict: NoteConflictEntity, markdown: String): NoteEntity {
        val local = dao.note(conflict.noteId) ?: error("冲突笔记不存在")
        val resolved = save(local.noteId, local.title, markdown, local.folderId, local.pinned)
        dao.resolveConflict(conflict.conflictId, now())
        return resolved
    }

    suspend fun applyRemote(remote: NoteEntity, remoteDeviceId: String): MarkdownMergeResult =
        applyRemoteWithChanges(remote, remoteDeviceId).mergeResult

    /** Counts only successful business writes; ignored conflict inserts report zero. */
    suspend fun applyRemoteWithChanges(remote: NoteEntity, remoteDeviceId: String): NoteRemoteApplyResult {
        val local = dao.note(remote.noteId)
        if(local==remote)return NoteRemoteApplyResult(MarkdownMergeResult.Merged(local.markdown))
        if(local!=null && local.markdown==remote.markdown && local.contentHash==remote.contentHash) {
            val order=compareValuesBy(remote,local,{maxOf(it.updatedAt,it.deletedAt)},{it.modifiedBy},{it.deleted},{it.title},{it.folderId!=null},{it.folderId.orEmpty()},{it.pinned},{it.createdAt},{it.baseContentHash},{it.baseMarkdown})
            if(order>0)dao.upsertNotes(listOf(remote))
            return NoteRemoteApplyResult(MarkdownMergeResult.Merged(local.markdown), changedNotes = if(order>0) 1 else 0)
        }
        if(local!=null && maxOf(remote.updatedAt,remote.deletedAt)<maxOf(local.updatedAt,local.deletedAt) && (local.deleted || remote.deleted))
            return NoteRemoteApplyResult(MarkdownMergeResult.Merged(local.markdown))
        if (local == null || local.deleted || remote.updatedAt > local.updatedAt && local.contentHash == local.baseContentHash) {
            dao.upsertNotes(listOf(remote))
            return NoteRemoteApplyResult(MarkdownMergeResult.Merged(remote.markdown), changedNotes = 1)
        }
        val result = MarkdownThreeWayMerge.merge(local.baseMarkdown, local.markdown, remote.markdown)
        if (result is MarkdownMergeResult.Merged) {
            if (result.markdown != local.markdown) {
                save(local.noteId, local.title, result.markdown, local.folderId, local.pinned)
                return NoteRemoteApplyResult(result, changedNotes = 1)
            }
        } else {
            val inserted = dao.upsertConflicts(listOf(NoteConflictEntity(
                conflictId = MarkdownNoteCodec.sha256(listOf(local.noteId,local.baseMarkdown,local.markdown,remote.markdown,remoteDeviceId).joinToString("\u0000")), noteId = local.noteId,
                baseMarkdown = local.baseMarkdown, localMarkdown = local.markdown,
                remoteMarkdown = remote.markdown, remoteDeviceId = remoteDeviceId, createdAt = now()
            )))
            return NoteRemoteApplyResult(result, addedConflicts = inserted.count { it != -1L })
        }
        return NoteRemoteApplyResult(result)
    }
}
