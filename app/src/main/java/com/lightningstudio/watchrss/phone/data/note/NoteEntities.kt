package com.lightningstudio.watchrss.phone.data.note

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Markdown is authoritative; plainText is a searchable/watch-editable projection. */
@Entity(tableName = "note_folders", indices = [Index(value = ["deleted", "sortOrder"])])
data class NoteFolderEntity(
    @PrimaryKey val folderId: String,
    val name: String,
    val sortOrder: Long,
    val updatedAt: Long,
    val modifiedBy: String,
    val deleted: Boolean = false,
    val deletedAt: Long = 0L
)

@Entity(
    tableName = "notes",
    indices = [
        Index(value = ["folderId", "deleted", "pinned", "updatedAt"]),
        Index(value = ["deleted", "updatedAt"]),
        Index(value = ["contentHash"])
    ]
)
data class NoteEntity(
    @PrimaryKey val noteId: String,
    val folderId: String?,
    val title: String,
    val markdown: String,
    val plainText: String,
    val contentHash: String,
    /** Common ancestor used by the diff3 resolver. */
    val baseContentHash: String,
    val baseMarkdown: String,
    val pinned: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val modifiedBy: String,
    val deleted: Boolean = false,
    val deletedAt: Long = 0L
)

@Entity(tableName = "note_assets", indices = [Index(value = ["noteId"]), Index(value = ["sha256"], unique = true)])
data class NoteAssetEntity(
    @PrimaryKey val assetId: String,
    val noteId: String,
    val sha256: String,
    val displayName: String,
    val mimeType: String,
    val byteCount: Long,
    /** app-private relative path; never trust an imported path directly. */
    val storageKey: String,
    val isOriginal: Boolean,
    val createdAt: Long,
    val deleted: Boolean = false,
    val deletedAt: Long = 0L
)

@Entity(tableName = "note_conflicts", indices = [Index(value = ["noteId", "resolvedAt"])])
data class NoteConflictEntity(
    @PrimaryKey val conflictId: String,
    val noteId: String,
    val baseMarkdown: String,
    val localMarkdown: String,
    val remoteMarkdown: String,
    val remoteDeviceId: String,
    val createdAt: Long,
    val resolvedAt: Long = 0L
)
