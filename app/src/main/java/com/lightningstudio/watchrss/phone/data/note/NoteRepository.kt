package com.lightningstudio.watchrss.phone.data.note

import kotlinx.coroutines.flow.Flow
import java.util.UUID

class NoteRepository(
    private val dao: NoteDao,
    private val deviceId: String,
    private val now: () -> Long = System::currentTimeMillis
) {
    fun observeNotes(): Flow<List<NoteEntity>> = dao.observeNotes()
    fun observeFolders(): Flow<List<NoteFolderEntity>> = dao.observeFolders()
    fun observeConflicts(): Flow<List<NoteConflictEntity>> = dao.observeUnresolvedConflicts()
    suspend fun allNotes(): List<NoteEntity> = dao.allNotes()
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
        pinned: Boolean = false
    ): NoteEntity {
        require(title.trim().isNotBlank() || MarkdownNoteCodec.toPlainText(markdown).isNotBlank()) {
            "笔记不能为空"
        }
        val previous = if (noteId == null) null else dao.note(noteId)
        val instant = now()
        val canonical = markdown.replace("\r\n", "\n").replace('\r', '\n')
        val hash = MarkdownNoteCodec.sha256(canonical)
        val saved = NoteEntity(
            noteId = previous?.noteId ?: noteId ?: UUID.randomUUID().toString(),
            folderId = folderId,
            title = title.trim().ifBlank { MarkdownNoteCodec.toPlainText(canonical).lineSequence().firstOrNull().orEmpty().take(80) },
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
        dao.upsertNotes(listOf(saved))
        return saved
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

    suspend fun applyRemote(remote: NoteEntity, remoteDeviceId: String): MarkdownMergeResult {
        val local = dao.note(remote.noteId)
        if (local == null || local.deleted || remote.updatedAt > local.updatedAt && local.contentHash == local.baseContentHash) {
            dao.upsertNotes(listOf(remote))
            return MarkdownMergeResult.Merged(remote.markdown)
        }
        val result = MarkdownThreeWayMerge.merge(local.baseMarkdown, local.markdown, remote.markdown)
        if (result is MarkdownMergeResult.Merged) {
            if (result.markdown != local.markdown) {
                save(local.noteId, local.title, result.markdown, local.folderId, local.pinned)
            }
        } else {
            dao.upsertConflicts(listOf(NoteConflictEntity(
                conflictId = UUID.randomUUID().toString(), noteId = local.noteId,
                baseMarkdown = local.baseMarkdown, localMarkdown = local.markdown,
                remoteMarkdown = remote.markdown, remoteDeviceId = remoteDeviceId, createdAt = now()
            )))
        }
        return result
    }
}
