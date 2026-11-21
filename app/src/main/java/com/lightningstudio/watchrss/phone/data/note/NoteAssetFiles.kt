package com.lightningstudio.watchrss.phone.data.note

import androidx.sqlite.db.SupportSQLiteDatabase
import com.lightningstudio.watchrss.phone.data.book.BookPackageFiles
import com.lightningstudio.watchrss.phone.data.local.syncDirectory
import com.lightningstudio.watchrss.phone.syncv2.SyncV2BodyRepository
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** B2 durably stages originals in a private directory. Readers can find
 * them only through a committed reference. Existing local files use their old path. */
internal object NoteAssetFiles {
    private const val TABLE = "note_asset_file_references"
    private val _publication = MutableStateFlow(0L)
    val publication = _publication.asStateFlow()
    fun published() = _publication.update { it + 1 }

    private val safeKey = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,254}")
    fun isSafe(key: String): Boolean = safeKey.matches(key)

    fun legacy(files: File, key: String): File {
        require(isSafe(key))
        return File(files, "notes/assets/$key")
    }

    fun candidate(files: File, sha256: String): File {
        require(sha256.length == 64 && sha256.all { it in '0'..'9' || it in 'a'..'f' })
        return File(files, "notes/.b2-originals/$sha256")
    }

    fun ensureCandidateDirectory(files: File): File {
        val notes = File(files, "notes")
        if (notes.mkdir()) syncDirectory(files)
        val originals = File(notes, ".b2-originals")
        if (originals.mkdir()) syncDirectory(notes)
        if (!originals.isDirectory) throw java.io.IOException("attachment storage unavailable")
        return originals
    }

    fun initialize(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS $TABLE (storage_key TEXT PRIMARY KEY, sha256 TEXT NOT NULL, size INTEGER NOT NULL)")
    }

    fun reference(db: SupportSQLiteDatabase?, key: String): BookPackageFiles.Revision? {
        if (db == null || !db.query("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(TABLE)).use { it.moveToFirst() }) {
            return null
        }
        return db.query("SELECT sha256,size FROM $TABLE WHERE storage_key=?", arrayOf(key)).use {
            if (it.moveToFirst()) BookPackageFiles.Revision(it.getString(0), it.getLong(1)) else null
        }
    }

    fun referencesDigest(db: SupportSQLiteDatabase, sha256: String): Boolean =
        db.query("SELECT 1 FROM $TABLE WHERE sha256=? LIMIT 1", arrayOf(sha256)).use { it.moveToFirst() }

    fun current(db: SupportSQLiteDatabase?, files: File, key: String): File {
        val reference = reference(db, key) ?: return legacy(files, key)
        return candidate(files, reference.sha256)
    }

    fun verifiedOriginal(db: SupportSQLiteDatabase, files: File, key: String,
        expected: BookPackageFiles.Revision, onRead: (Int) -> Unit = {}): BookPackageFiles.Original? {
        val published = reference(db, key)
        if ((published != null && published != expected) || ownerKeys(db, key).isEmpty() ||
            !winnerAllows(db, key, expected)) return null
        val selected = current(db, files, key)
        val original = BookPackageFiles.verify(selected, expected, onRead) ?: return null
        return original.takeIf { reference(db, key) == published && current(db, files, key) == selected &&
            ownerKeys(db, key).isNotEmpty() && winnerAllows(db, key, expected) && it.isCurrent() }
    }

    /** The editor also displays unpublished images imported into a local draft. */
    fun readable(db: SupportSQLiteDatabase, files: File, key: String): File? {
        val published = reference(db, key)
        if (published != null) return verifiedOriginal(db, files, key, published)?.file
        return current(db, files, key).takeIf { reference(db, key) == null }
    }

    fun publish(db: SupportSQLiteDatabase, key: String, revision: BookPackageFiles.Revision) {
        check(db.inTransaction())
        db.execSQL("INSERT OR REPLACE INTO $TABLE VALUES(?,?,?)",
            arrayOf(key, revision.sha256, revision.bytes))
    }

    fun ownerKeys(db: SupportSQLiteDatabase, key: String): List<String> {
        val needle = "assets/$key"
        return buildList {
            db.query("""
                SELECT noteId,markdown,baseMarkdown FROM notes WHERE deleted=0
                    AND (instr(markdown,?)>0 OR instr(baseMarkdown,?)>0) ORDER BY noteId
            """, arrayOf(needle, needle)).use { row ->
                while (row.moveToNext()) if ((1..2).any { key in SyncV2BodyRepository.assetKeys(row.getString(it)) }) {
                    add("note:" + row.getString(0))
                }
            }
            db.query("""
                SELECT c.conflictId,c.baseMarkdown,c.localMarkdown,c.remoteMarkdown
                    FROM note_conflicts c JOIN notes n ON n.noteId=c.noteId WHERE n.deleted=0
                    AND (instr(c.baseMarkdown,?)>0 OR instr(c.localMarkdown,?)>0 OR instr(c.remoteMarkdown,?)>0)
                    ORDER BY c.conflictId
            """, arrayOf(needle, needle, needle)).use { row ->
                while (row.moveToNext()) if ((1..3).any { key in SyncV2BodyRepository.assetKeys(row.getString(it)) }) {
                    add("conflict:" + row.getString(0))
                }
            }
        }
    }

    fun winnerAllows(db: SupportSQLiteDatabase, key: String, winner: BookPackageFiles.Revision): Boolean =
        db.query("SELECT sha256,byteCount,deleted FROM note_assets WHERE storageKey=?", arrayOf(key)).use {
            var seen = false
            while (it.moveToNext()) {
                seen = true
                if (it.getInt(2) == 0 && it.getString(0) == winner.sha256 && it.getLong(1) == winner.bytes) return@use true
            }
            !seen
        }

}
