package com.lightningstudio.watchrss.phone.data.note

import kotlinx.coroutines.flow.Flow
import java.util.UUID

class NoteRepository(
    private val dao: NoteDao,
    private val deviceId: String,
    private val now: () -> Long = System::currentTimeMillis
) {
    suspend fun clearForAccountSwitch() = dao.clearForAccountSwitch()
    suspend fun hasAny(): Boolean = dao.hasAny()
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
        pinned: Boolean = false
    ): NoteEntity {
        require(title.trim().isNotBlank() || MarkdownNoteCodec.toPlainText(markdown).isNotBlank()) {
            "笔记不能为空"
        }
        val previous = if (noteId == null) null else dao.note(noteId)
        val instant = nextTime(previous)
        val canonical = markdown.replace("\r\n", "\n").replace('\r', '\n')
        val hash = MarkdownNoteCodec.sha256(canonical)
        val saved = NoteEntity(
            noteId = previous?.noteId ?: noteId ?: UUID.randomUUID().toString(),
            folderId = folderId,
            title = title.trim().ifBlank { MarkdownNoteCodec.toPlainText(canonical).lineSequence().firstOrNull().orEmpty().take(80) },
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
        check(dao.commitNote(saved.noteId, previous, saved)) { "notes_stale" }
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

    suspend fun resolveConflict(conflict: NoteConflictEntity, markdown: String, expected: NoteEntity? = null): NoteEntity {
        val local = dao.note(conflict.noteId) ?: error("冲突笔记不存在")
        val known = dao.conflictsFor(local.noteId).find { it.conflictId == conflict.conflictId }
        check(known == conflict && known.resolvedAt == 0L && local.markdown == conflict.localMarkdown &&
            (expected == null || expected == local)) { "notes_stale" }
        require(local.title.isNotBlank() || MarkdownNoteCodec.toPlainText(markdown).isNotBlank()) { "笔记不能为空" }
        val body = markdown.replace("\r\n", "\n").replace('\r', '\n')
        val resolved = local.copy(markdown = body, plainText = MarkdownNoteCodec.toPlainText(body),
            contentHash = MarkdownNoteCodec.sha256(body), baseMarkdown = body, baseContentHash = MarkdownNoteCodec.sha256(body),
            updatedAt = nextTime(local), modifiedBy = deviceId, deleted = false, deletedAt = 0L)
        check(dao.commitNote(local.noteId, local, resolved, resolvedId = conflict.conflictId, resolvedAt = maxOf(1L, now()))) { "notes_stale" }
        return resolved
    }

    private fun metadataOrder(a: NoteEntity, b: NoteEntity): Int = compareValuesBy(a, b,
        { maxOf(it.updatedAt, it.deletedAt) }, { it.modifiedBy }, { it.deleted }, { it.title },
        { it.folderId != null }, { it.folderId.orEmpty() }, { it.pinned }, { it.createdAt }, { it.baseContentHash }, { it.baseMarkdown })

    suspend fun applyRemote(remote: NoteEntity, remoteDeviceId: String): MarkdownMergeResult {
        repeat(8) {
            val local = dao.note(remote.noteId)
            if (local == remote) return MarkdownMergeResult.Merged(remote.markdown)
            if (local != null && !remote.deleted && local.markdown != remote.markdown && dao.conflictsFor(local.noteId).any {
                it.resolvedAt > 0 && it.remoteMarkdown == remote.markdown &&
                    it.remoteDeviceId == remoteDeviceId
            }) return MarkdownMergeResult.Merged(local.markdown)
            var value: NoteEntity? = remote
            var conflict: NoteConflictEntity? = null
            var result: MarkdownMergeResult = MarkdownMergeResult.Merged(remote.markdown)
            if (local != null) {
                val meta = if (metadataOrder(remote, local) > 0) remote else local
                if (local.markdown == remote.markdown) {
                    value = if (local.deleted != remote.deleted) { if (local.deleted) local else remote } else meta
                    result = MarkdownMergeResult.Merged(local.markdown)
                } else {
                    // A delete and a divergent edit are a conflict, regardless of wall clock.
                    // An explicit restore based on the tombstone is a sequential edit.
                    val restore = local.deleted && !remote.deleted && remote.baseMarkdown == local.markdown && remote.updatedAt > local.updatedAt
                    val localRestore = remote.deleted && !local.deleted && local.baseMarkdown == remote.markdown && local.updatedAt > remote.updatedAt
                    val sequentialDelete = remote.deleted && !local.deleted && remote.baseMarkdown == local.markdown && local.markdown == local.baseMarkdown
                    val cycle = !local.deleted && !remote.deleted && remote.baseMarkdown == local.markdown && local.baseMarkdown == remote.markdown
                    val deletionConflict = local.deleted != remote.deleted && !restore && !localRestore && !sequentialDelete
                    result = when {
                        deletionConflict || cycle -> MarkdownMergeResult.Conflict
                        restore -> MarkdownMergeResult.Merged(remote.markdown)
                        localRestore -> MarkdownMergeResult.Merged(local.markdown)
                        remote.baseMarkdown == local.markdown -> MarkdownMergeResult.Merged(remote.markdown)
                        local.baseMarkdown == remote.markdown -> MarkdownMergeResult.Merged(local.markdown)
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
                        // Ambiguous a -> b -> a ancestry retains both saved bodies.
                        value = if (cycle) meta else null
                        conflict = NoteConflictEntity(noteConflictId(local.noteId, local.baseMarkdown, local.markdown, remote.markdown, remoteDeviceId),
                            local.noteId, local.baseMarkdown, local.markdown, remote.markdown, remoteDeviceId, now())
                    }
                }
            }
            if (dao.commitNote(remote.noteId, local, value, conflict)) return result
        }
        error("notes_stale")
    }
}
