package com.lightningstudio.watchrss.phone.data.note

/** UTF-16 lengths agree with JavaScript String.length. Existing IDs stay opaque. */
fun noteConflictId(noteId: String, base: String, local: String, remote: String, device: String): String =
    MarkdownNoteCodec.sha256("notes-conflict-v2:" + listOf(noteId, base, local, remote, device).joinToString("") { "${it.length}:$it" })

fun sameConflictContent(a: NoteConflictEntity, b: NoteConflictEntity): Boolean =
    a.noteId == b.noteId && a.baseMarkdown == b.baseMarkdown && a.localMarkdown == b.localMarkdown &&
        a.remoteMarkdown == b.remoteMarkdown && a.remoteDeviceId == b.remoteDeviceId
