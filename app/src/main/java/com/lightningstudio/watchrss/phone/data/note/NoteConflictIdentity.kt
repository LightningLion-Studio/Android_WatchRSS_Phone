package com.lightningstudio.watchrss.phone.data.note

/** UTF-16 lengths agree with JavaScript String.length. Existing IDs stay opaque. */
fun noteConflictId(noteId: String, base: String, local: String, remote: String, device: String): String =
    MarkdownNoteCodec.sha256("notes-conflict-v2:" + listOf(noteId, base, local, remote, device).joinToString("") { "${it.length}:$it" })

fun sameConflictContent(a: NoteConflictEntity, b: NoteConflictEntity): Boolean =
    a.noteId == b.noteId && a.baseMarkdown == b.baseMarkdown && a.localMarkdown == b.localMarkdown &&
        a.remoteMarkdown == b.remoteMarkdown && a.remoteDeviceId == b.remoteDeviceId

/** Keep legacy IDs readable; union resolution markers before exposing any alias. */
fun normalizeNoteConflicts(rows: List<NoteConflictEntity>): List<NoteConflictEntity> =
    rows.groupBy { listOf(it.noteId, it.baseMarkdown, it.localMarkdown, it.remoteMarkdown, it.remoteDeviceId) }
        .map { (tuple, copies) ->
            val legacy = MarkdownNoteCodec.sha256(tuple.joinToString("\u0000"))
            val kept = copies.find { it.conflictId == legacy } ?: copies.minBy { it.conflictId }
            kept.copy(createdAt = copies.minOf { it.createdAt }, resolvedAt = copies.maxOf { it.resolvedAt })
        }

fun noteRemoteSnapshotHash(n: NoteEntity, source: String): String = MarkdownNoteCodec.sha256(
    "notes-remote-snapshot-v1:" + listOf(n.noteId, n.folderId, n.title, n.markdown, n.plainText,
        n.contentHash, n.baseContentHash, n.baseMarkdown, n.pinned, n.createdAt, n.updatedAt,
        n.modifiedBy, n.deleted, n.deletedAt, source).joinToString("") { value ->
        if (value == null) "-1:" else value.toString().let { "${it.length}:$it" }
    })
