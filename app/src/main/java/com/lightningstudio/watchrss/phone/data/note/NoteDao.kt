package com.lightningstudio.watchrss.phone.data.note

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.SkipQueryVerification
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    // This local table is installed by NoteEditorPreferenceMigration after Room opens.
    @SkipQueryVerification
    @Query("SELECT sourceMode FROM note_editor_preferences WHERE noteId = :noteId AND installationId = :installationId")
    suspend fun editorSourceMode(noteId: String, installationId: String): Boolean?

    @SkipQueryVerification
    @Query("INSERT OR REPLACE INTO note_editor_preferences (noteId, installationId, sourceMode) VALUES (:noteId, :installationId, :sourceMode)")
    suspend fun writeEditorSourceMode(noteId: String, installationId: String, sourceMode: Boolean)

    @Transaction
    suspend fun upsertEditorNote(expected: NoteEntity?, note: NoteEntity, installationId: String, sourceMode: Boolean): Boolean {
        if (!commitNote(note.noteId, expected, note)) return false
        writeEditorSourceMode(note.noteId, installationId, sourceMode)
        return true
    }

    @SkipQueryVerification
    @Query("DELETE FROM note_editor_preferences")
    suspend fun clearEditorPreferences()

    @Query("SELECT EXISTS(SELECT 1 FROM notes) OR EXISTS(SELECT 1 FROM note_folders)") suspend fun hasAny(): Boolean
    @Query("SELECT * FROM note_folders WHERE deleted = 0 ORDER BY sortOrder, name COLLATE NOCASE")
    fun observeFolders(): Flow<List<NoteFolderEntity>>

    @Query("SELECT * FROM notes WHERE deleted = 0 ORDER BY pinned DESC, updatedAt DESC")
    fun observeNotes(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE noteId = :noteId LIMIT 1")
    suspend fun note(noteId: String): NoteEntity?

    @Query("SELECT * FROM notes WHERE deleted = 0 AND (title LIKE '%' || :query || '%' OR plainText LIKE '%' || :query || '%') ORDER BY pinned DESC, updatedAt DESC")
    suspend fun search(query: String): List<NoteEntity>

    @Query("SELECT * FROM notes")
    suspend fun allNotes(): List<NoteEntity>

    @Query("SELECT * FROM note_assets WHERE noteId = :noteId AND deleted = 0")
    suspend fun assets(noteId: String): List<NoteAssetEntity>

    @Query("SELECT * FROM note_assets WHERE sha256 = :sha256 AND deleted = 0 LIMIT 1")
    suspend fun assetByHash(sha256: String): NoteAssetEntity?

    @Query("SELECT * FROM note_conflicts WHERE resolvedAt = 0 ORDER BY createdAt DESC")
    fun observeUnresolvedConflicts(): Flow<List<NoteConflictEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFolders(items: List<NoteFolderEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNotes(items: List<NoteEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAssets(items: List<NoteAssetEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun upsertConflicts(items: List<NoteConflictEntity>)

    @Query("SELECT * FROM note_conflicts WHERE noteId = :noteId")
    suspend fun conflictsFor(noteId: String): List<NoteConflictEntity>

    @Query("SELECT * FROM note_conflicts")
    suspend fun allConflicts(): List<NoteConflictEntity>

    @androidx.room.Transaction
    suspend fun normalizeConflicts() {
        val rows = allConflicts()
        val normalized = normalizeNoteConflicts(rows)
        if (normalized.size != rows.size) {
            clearConflicts()
            upsertConflicts(normalized)
        }
    }

    @Query("SELECT EXISTS(SELECT 1 FROM note_remote_receipts WHERE snapshotHash=:hash)")
    suspend fun hasRemoteReceipt(hash: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRemoteReceipt(receipt: NoteRemoteReceiptEntity)

    @Query("DELETE FROM note_remote_receipts")
    suspend fun clearRemoteReceipts()

    @androidx.room.Transaction
    suspend fun commitRemoteNote(noteId: String, expected: NoteEntity?, replacement: NoteEntity?,
        conflict: NoteConflictEntity?, receipt: NoteRemoteReceiptEntity): NoteRemoteWrites? {
        if (expected != null && hasRemoteReceipt(receipt.snapshotHash)) return NoteRemoteWrites()
        val newConflict = conflict != null && conflictsFor(noteId).none { sameConflictContent(it, conflict) }
        if (!commitNote(noteId, expected, replacement, conflict)) return null
        insertRemoteReceipt(receipt)
        return NoteRemoteWrites(
            changedNotes = if (replacement != null && replacement != expected) 1 else 0,
            addedConflicts = if (newConflict) 1 else 0
        )
    }

    @androidx.room.Transaction
    suspend fun captureConflict(noteId: String, conflictId: String): NoteConflictCapture? {
        normalizeConflicts()
        val current = note(noteId)?.takeUnless { it.deleted } ?: return null
        val open = conflictsFor(noteId).filter { it.resolvedAt == 0L }.sortedBy { it.conflictId }
        val selected = open.find { it.conflictId == conflictId } ?: return null
        return NoteConflictCapture(selected, current, java.util.Collections.unmodifiableList(open.toList()))
    }

    /** Room serializes the read-basis check and every business write together. */
    @androidx.room.Transaction
    suspend fun commitNote(
        noteId: String, expected: NoteEntity?, replacement: NoteEntity?,
        conflict: NoteConflictEntity? = null, resolvedId: String? = null, resolvedAt: Long = 0L,
        expectedConflicts: List<NoteConflictEntity>? = null
    ): Boolean {
        if (note(noteId) != expected) return false
        if (resolvedId != null) {
            normalizeConflicts()
            // The captured conflict and note are checked inside this final transaction.
            if (expected == null || expected.deleted || conflict == null || conflict.conflictId != resolvedId ||
                conflict.resolvedAt != 0L) return false
            val open = conflictsFor(noteId).filter { it.resolvedAt == 0L }.sortedBy { it.conflictId }
            if (open != (expectedConflicts ?: listOf(conflict)).sortedBy { it.conflictId } ||
                open.find { it.conflictId == resolvedId } != conflict) return false
        } else if (conflict != null) {
            val known = conflictsFor(noteId)
            val sameId = known.find { it.conflictId == conflict.conflictId }
            check(sameId == null || sameConflictContent(sameId, conflict)) { "conflict_identity_mismatch" }
            if (known.none { sameConflictContent(it, conflict) }) upsertConflicts(listOf(conflict))
            else System.err.println("notes_conflict_tuple_seen")
        }
        if (replacement != null) upsertNotes(listOf(replacement))
        if (resolvedId != null) resolveConflict(resolvedId, resolvedAt)
        return true
    }

    @Query("UPDATE note_conflicts SET resolvedAt = :resolvedAt WHERE conflictId = :conflictId")
    suspend fun resolveConflict(conflictId: String, resolvedAt: Long)
    @Query("DELETE FROM note_conflicts")
    suspend fun clearConflicts()
    @Query("DELETE FROM note_assets")
    suspend fun clearAssets()
    @Query("DELETE FROM notes")
    suspend fun clearNotes()
    @Query("DELETE FROM note_folders")
    suspend fun clearFolders()
    @androidx.room.Transaction
    suspend fun clearForAccountSwitch() {
        clearRemoteReceipts()
        clearConflicts()
        clearAssets()
        clearNotes()
        clearFolders()
        clearEditorPreferences()
    }
}
