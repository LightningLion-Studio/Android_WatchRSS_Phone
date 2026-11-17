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
    suspend fun upsertEditorNote(note: NoteEntity, installationId: String, sourceMode: Boolean) {
        upsertNotes(listOf(note))
        writeEditorSourceMode(note.noteId, installationId, sourceMode)
    }

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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConflicts(items: List<NoteConflictEntity>)

    @Query("UPDATE note_conflicts SET resolvedAt = :resolvedAt WHERE conflictId = :conflictId")
    suspend fun resolveConflict(conflictId: String, resolvedAt: Long)
}
