package com.lightningstudio.watchrss.phone.data.note

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import java.util.UUID

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
    fun observeConflicts(): Flow<List<NoteConflictEntity>> = flow {
        dao.normalizeConflicts()
        emitAll(dao.observeUnresolvedConflicts())
    }
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
        val instant = nextTime(previous)
        // Preserve authoritative body bytes; retain this branch's monotonic clock and CAS.
        val canonical = markdown
        val hash = MarkdownNoteCodec.sha256(canonical)
        val saved = NoteEntity(
            noteId = previous?.noteId ?: noteId ?: UUID.randomUUID().toString(),
            folderId = folderId,
            title = title.trim().ifBlank { deriveNoteTitle(MarkdownNoteCodec.titlePlainText(canonical)) },
            markdown = canonical,
            plainText = MarkdownNoteCodec.toPlainText(canonical),
            contentHash = hash,
            baseContentHash = if (previous?.deleted == true) previous.contentHash else previous?.baseContentHash ?: hash,
            baseMarkdown = if (previous?.deleted == true) previous.markdown else previous?.baseMarkdown ?: canonical,
            pinned = pinned,
            createdAt = previous?.createdAt ?: instant,
            updatedAt = instant,
            modifiedBy = deviceId,
            deleted = false,
            deletedAt = 0L
        )
        val committed = if (editorSourceMode == null) dao.commitNote(saved.noteId, previous, saved)
        else dao.upsertEditorNote(previous, saved, editorPreferenceOwner, editorSourceMode)
        check(committed) { "notes_stale" }
        return saved
    }

    suspend fun move(noteId: String, folderId: String?): NoteEntity =
        updateMetadata(noteId) { it.copy(folderId = folderId) }

    suspend fun setPinned(noteId: String, pinned: Boolean): NoteEntity =
        updateMetadata(noteId) { it.copy(pinned = pinned) }

    suspend fun delete(noteId: String): NoteEntity {
        return updateMetadata(noteId) { it.copy(deleted = true) }
    }

    private suspend fun updateMetadata(
        noteId: String,
        instant: Long = now(),
        transform: (NoteEntity) -> NoteEntity
    ): NoteEntity {
        val local = dao.note(noteId) ?: error("笔记不存在")
        val version = nextTime(local, instant)
        val changed = transform(local)
        val updated = changed.copy(updatedAt = version, modifiedBy = deviceId,
            deletedAt = if (changed.deleted) version else changed.deletedAt)
        check(dao.commitNote(noteId, local, updated)) { "notes_stale" }
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

    private fun nextTime(local: NoteEntity?, clock: Long = now(), peer: NoteEntity? = null): Long {
        val previous = maxOf(local?.updatedAt ?: 0L, local?.deletedAt ?: 0L, peer?.updatedAt ?: 0L, peer?.deletedAt ?: 0L)
        check(previous < Long.MAX_VALUE) { "counter_exhausted" }
        return maxOf(clock, previous + 1)
    }

    suspend fun captureConflict(conflict: NoteConflictEntity): NoteConflictCapture? =
        dao.captureConflict(conflict.noteId, conflict.conflictId)

    suspend fun resolveConflict(capture: NoteConflictCapture, markdown: String): NoteEntity =
        resolveConflict(capture.conflict, markdown, capture.note, capture.openConflicts)

    // Older callers only captured one row. They may commit only while it is the entire open set.
    suspend fun resolveConflict(conflict: NoteConflictEntity, markdown: String, expected: NoteEntity? = null,
        expectedConflicts: List<NoteConflictEntity> = listOf(conflict)): NoteEntity {
        val local = dao.note(conflict.noteId) ?: error("冲突笔记不存在")
        val known = dao.conflictsFor(local.noteId).find { it.conflictId == conflict.conflictId }
        check(known == conflict && known.resolvedAt == 0L && !local.deleted &&
            (if (expected == null) local.markdown == conflict.localMarkdown else expected == local)) { "notes_stale" }
        require(local.title.isNotBlank() || MarkdownNoteCodec.toPlainText(markdown).isNotBlank()) { "笔记不能为空" }
        val body = markdown.replace("\r\n", "\n").replace('\r', '\n')
        val resolved = local.copy(markdown = body, plainText = MarkdownNoteCodec.toPlainText(body),
            contentHash = MarkdownNoteCodec.sha256(body), baseMarkdown = body, baseContentHash = MarkdownNoteCodec.sha256(body),
            updatedAt = nextTime(local), modifiedBy = deviceId, deleted = false, deletedAt = 0L)
        check(dao.commitNote(local.noteId, local, resolved, conflict = conflict, resolvedId = conflict.conflictId, resolvedAt = maxOf(1L, now()), expectedConflicts = expectedConflicts)) { "notes_stale" }
        return resolved
    }

    private fun metadataOrder(a: NoteEntity, b: NoteEntity): Int = compareValuesBy(a, b,
        { maxOf(it.updatedAt, it.deletedAt) }, { it.modifiedBy }, { it.deleted }, { it.title },
        { it.folderId != null }, { it.folderId.orEmpty() }, { it.pinned }, { it.createdAt }, { it.baseContentHash }, { it.baseMarkdown })

    suspend fun applyRemote(remote: NoteEntity, remoteDeviceId: String): MarkdownMergeResult {
        val receipt = NoteRemoteReceiptEntity(noteRemoteSnapshotHash(remote, remoteDeviceId), remote.noteId)
        repeat(8) {
            val local = dao.note(remote.noteId)
            if (local != null && dao.hasRemoteReceipt(receipt.snapshotHash)) return MarkdownMergeResult.Merged(local?.markdown ?: remote.markdown)
            if (local == remote) {
                if (dao.commitRemoteNote(remote.noteId, local, null, null, receipt)) return MarkdownMergeResult.Merged(remote.markdown)
                return@repeat
            }
            var value: NoteEntity? = remote
            var conflict: NoteConflictEntity? = null
            var result: MarkdownMergeResult = MarkdownMergeResult.Merged(remote.markdown)
            if (local != null) {
                val meta = if (metadataOrder(remote, local) > 0) remote else local
                if (local.markdown == remote.markdown && local.deleted == remote.deleted) {
                    value = meta
                    result = MarkdownMergeResult.Merged(local.markdown)
                } else {
                    val forward = remote.baseMarkdown == local.markdown
                    val backward = local.baseMarkdown == remote.markdown
                    val known = local.modifiedBy.isNotEmpty() && remote.modifiedBy.isNotEmpty()
                    result = when {
                        local.deleted != remote.deleted || local.deleted || !known -> MarkdownMergeResult.Conflict
                        forward && backward -> MarkdownMergeResult.Conflict
                        forward -> MarkdownMergeResult.Merged(remote.markdown)
                        backward -> MarkdownMergeResult.Merged(local.markdown)
                        local.baseMarkdown == remote.baseMarkdown -> MarkdownThreeWayMerge.merge(local.baseMarkdown, local.markdown, remote.markdown)
                        else -> MarkdownMergeResult.Conflict
                    }
                    if (result is MarkdownMergeResult.Merged) {
                        val body = result.markdown
                        value = when (body) {
                            remote.markdown -> remote.copy(title = meta.title, folderId = meta.folderId, pinned = meta.pinned)
                            local.markdown -> local.copy(title = meta.title, folderId = meta.folderId, pinned = meta.pinned)
                            else -> meta.copy(markdown = body, plainText = MarkdownNoteCodec.toPlainText(body),
                                contentHash = MarkdownNoteCodec.sha256(body), updatedAt = nextTime(local, peer = remote),
                                modifiedBy = deviceId, deleted = false, deletedAt = 0L)
                        }
                        if (body == remote.markdown && meta !== remote || body == local.markdown && meta !== local)
                            value = value.copy(updatedAt = nextTime(local, peer = remote), modifiedBy = deviceId)
                    } else {
                        // A fork never replaces the current saved version.
                        value = null
                        conflict = NoteConflictEntity(noteConflictId(local.noteId, local.baseMarkdown, local.markdown, remote.markdown, remoteDeviceId),
                            local.noteId, local.baseMarkdown, local.markdown, remote.markdown, remoteDeviceId, now())
                    }
                }
            }
            if (dao.commitRemoteNote(remote.noteId, local, value, conflict, receipt)) return result
        }
        error("notes_stale")
    }
}
