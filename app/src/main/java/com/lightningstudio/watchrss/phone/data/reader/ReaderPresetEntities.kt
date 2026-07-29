package com.lightningstudio.watchrss.phone.data.reader

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "reader_presets",
    indices = [
        Index(value = ["deleted", "name"]),
        Index(value = ["updatedAt"])
    ]
)
data class ReaderPresetEntity(
    @androidx.room.PrimaryKey val id: String,
    val name: String,
    val payloadJson: String,
    val updatedAt: Long,
    val modifiedBy: String,
    val deleted: Boolean
)

@Entity(
    tableName = "reader_font_assets",
    indices = [
        Index(value = ["deleted", "displayName"]),
        Index(value = ["sha256"], unique = true)
    ]
)
data class ReaderFontAssetEntity(
    @androidx.room.PrimaryKey val id: String,
    val sha256: String,
    val displayName: String,
    val familyName: String,
    val fileName: String,
    val mimeType: String,
    val byteCount: Long,
    val faceCount: Int,
    val metadataJson: String,
    val updatedAt: Long,
    val modifiedBy: String,
    val deleted: Boolean
)

@Entity(
    tableName = "reader_background_assets",
    indices = [
        Index(value = ["deleted", "displayName"]),
        Index(value = ["sha256"], unique = true)
    ]
)
data class ReaderBackgroundAssetEntity(
    @androidx.room.PrimaryKey val id: String,
    val sha256: String,
    val displayName: String,
    val kind: String,
    val mimeType: String,
    val masterFileName: String,
    val byteCount: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val posterAssetId: String?,
    val variantsJson: String,
    val updatedAt: Long,
    val modifiedBy: String,
    val deleted: Boolean
)

@Entity(
    tableName = "reader_deletions",
    primaryKeys = ["kind", "entityId"],
    indices = [Index(value = ["deletedAt"])]
)
data class ReaderDeletionEntity(
    val kind: String,
    val entityId: String,
    val deletedAt: Long,
    val deletedBy: String
)

@Dao
interface ReaderPresetDao {
    @Query("SELECT * FROM reader_presets ORDER BY deleted ASC, name COLLATE NOCASE ASC")
    fun observeAllPresets(): Flow<List<ReaderPresetEntity>>

    @Query("SELECT * FROM reader_font_assets ORDER BY deleted ASC, displayName COLLATE NOCASE ASC")
    fun observeAllFonts(): Flow<List<ReaderFontAssetEntity>>

    @Query("SELECT * FROM reader_background_assets ORDER BY deleted ASC, displayName COLLATE NOCASE ASC")
    fun observeAllBackgrounds(): Flow<List<ReaderBackgroundAssetEntity>>

    @Query("SELECT * FROM reader_presets")
    suspend fun allPresetRecords(): List<ReaderPresetEntity>

    @Query("SELECT * FROM reader_font_assets")
    suspend fun allFontRecords(): List<ReaderFontAssetEntity>

    @Query("SELECT * FROM reader_background_assets")
    suspend fun allBackgroundRecords(): List<ReaderBackgroundAssetEntity>

    @Query("SELECT * FROM reader_deletions")
    suspend fun allDeletions(): List<ReaderDeletionEntity>

    @Query("SELECT * FROM reader_presets WHERE id = :id LIMIT 1")
    suspend fun presetById(id: String): ReaderPresetEntity?

    @Query("SELECT * FROM reader_font_assets WHERE id = :id LIMIT 1")
    suspend fun fontById(id: String): ReaderFontAssetEntity?

    @Query("SELECT * FROM reader_font_assets WHERE sha256 = :sha256 LIMIT 1")
    suspend fun fontByHash(sha256: String): ReaderFontAssetEntity?

    @Query("SELECT * FROM reader_background_assets WHERE id = :id LIMIT 1")
    suspend fun backgroundById(id: String): ReaderBackgroundAssetEntity?

    @Query("SELECT * FROM reader_background_assets WHERE sha256 = :sha256 LIMIT 1")
    suspend fun backgroundByHash(sha256: String): ReaderBackgroundAssetEntity?

    @Query(
        "SELECT COUNT(*) FROM reader_presets " +
            "WHERE deleted = 0 AND lower(trim(name)) = lower(trim(:name)) AND id != :excludingId"
    )
    suspend fun countNameConflicts(name: String, excludingId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPreset(entity: ReaderPresetEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPresets(entities: List<ReaderPresetEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFont(entity: ReaderFontAssetEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFonts(entities: List<ReaderFontAssetEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBackground(entity: ReaderBackgroundAssetEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBackgrounds(entities: List<ReaderBackgroundAssetEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDeletion(entity: ReaderDeletionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDeletions(entities: List<ReaderDeletionEntity>)

    @Query("DELETE FROM reader_deletions WHERE kind = :kind AND entityId = :entityId")
    suspend fun deleteDeletion(kind: String, entityId: String)
}
