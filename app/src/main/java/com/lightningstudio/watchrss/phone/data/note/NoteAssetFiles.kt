package com.lightningstudio.watchrss.phone.data.note

import androidx.sqlite.db.SupportSQLiteDatabase
import com.lightningstudio.watchrss.phone.data.book.BookPackageFiles
import com.lightningstudio.watchrss.phone.data.local.ConditionalBodyFile
import com.lightningstudio.watchrss.phone.data.local.syncDirectory
import com.lightningstudio.watchrss.phone.syncv2.SyncV2BodyRepository
import com.lightningstudio.watchrss.phone.syncv2.SyncV2NoteAssetNames
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** B2 durably stages originals in a private directory. Readers can find
 * them only through a committed reference. Existing local files use their old path. */
internal object NoteAssetFiles {
    private const val TABLE = "note_asset_file_references"
    // A null slot preserves the digest-only path used by existing references.
    data class Reference(val revision: BookPackageFiles.Revision, val slot: String? = null)
    private val _publication = MutableStateFlow(0L)
    val publication = _publication.asStateFlow()
    fun published() = _publication.update { it + 1 }

    private val safeKey = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,254}")
    private val slotId = Regex("[0-9a-f]{32}")
    fun isSafe(key: String): Boolean = safeKey.matches(key)

    fun legacy(files: File, key: String): File {
        require(isSafe(key))
        return File(files, "notes/assets/$key")
    }

    fun candidate(files: File, sha256: String): File = candidate(files, sha256, null)

    fun candidate(files: File, reference: Reference): File =
        candidate(files, reference.revision.sha256, reference.slot)

    private fun candidate(files: File, sha256: String, slot: String?): File {
        require(sha256.length == 64 && sha256.all { it in '0'..'9' || it in 'a'..'f' })
        require(slot == null || slotId.matches(slot))
        val name = if (slot == null) sha256 else "$sha256-$slot"
        return File(files, "notes/.b2-originals/$name")
    }

    fun replacement(revision: BookPackageFiles.Revision): Reference =
        Reference(revision, UUID.randomUUID().toString().replace("-", ""))

    fun ensureCandidateDirectory(files: File): File {
        val notes = File(files, "notes")
        if (notes.mkdir()) syncDirectory(files)
        val originals = File(notes, ".b2-originals")
        if (originals.mkdir()) syncDirectory(notes)
        if (!originals.isDirectory) throw java.io.IOException("attachment storage unavailable")
        return originals
    }

    fun initialize(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS $TABLE (storage_key TEXT PRIMARY KEY, sha256 TEXT NOT NULL, size INTEGER NOT NULL, file_slot TEXT)")
        val hasSlot = db.query("PRAGMA table_info($TABLE)").use { rows ->
            val name = rows.getColumnIndexOrThrow("name")
            var found = false
            while (rows.moveToNext() && !found) found = rows.getString(name) == "file_slot"
            found
        }
        if (!hasSlot) db.execSQL("ALTER TABLE $TABLE ADD COLUMN file_slot TEXT")
    }

    private fun exists(db: SupportSQLiteDatabase): Boolean =
        db.query("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(TABLE)).use { it.moveToFirst() }

    fun reference(db: SupportSQLiteDatabase?, key: String): BookPackageFiles.Revision? = selection(db, key)?.revision

    fun selection(db: SupportSQLiteDatabase?, key: String): Reference? {
        if (db == null || !exists(db)) return null
        return db.query("SELECT * FROM $TABLE WHERE storage_key=?", arrayOf(key)).use { row ->
            if (!row.moveToFirst()) return@use null
            val slot = row.getColumnIndex("file_slot").takeIf { it >= 0 }?.let { row.getString(it) }
            Reference(BookPackageFiles.Revision(row.getString(row.getColumnIndexOrThrow("sha256")),
                row.getLong(row.getColumnIndexOrThrow("size"))), slot)
        }
    }

    private fun referenced(db: SupportSQLiteDatabase, sha256: String, slot: String?): Boolean {
        if (!exists(db)) return false
        return db.query("SELECT * FROM $TABLE WHERE sha256=?", arrayOf(sha256)).use { rows ->
            val index = rows.getColumnIndex("file_slot")
            while (rows.moveToNext()) {
                if ((if (index < 0) null else rows.getString(index)) == slot) return@use true
            }
            false
        }
    }

    fun referencesDigest(db: SupportSQLiteDatabase, sha256: String): Boolean =
        referenced(db, sha256, null)

    fun current(db: SupportSQLiteDatabase?, files: File, key: String): File {
        val reference = selection(db, key) ?: return legacy(files, key)
        return candidate(files, reference)
    }

    fun verifiedOriginal(db: SupportSQLiteDatabase, files: File, key: String,
        expected: BookPackageFiles.Revision, onRead: (Int) -> Unit = {}): BookPackageFiles.Original? {
        val published = selection(db, key)
        if ((published != null && published.revision != expected) || ownerKeys(db, key).isEmpty() ||
            !winnerAllows(db, key, expected)) return null
        val selected = current(db, files, key)
        val original = BookPackageFiles.verify(selected, expected, onRead) ?: return null
        return original.takeIf { selection(db, key) == published && current(db, files, key) == selected &&
            ownerKeys(db, key).isNotEmpty() && winnerAllows(db, key, expected) && it.isCurrent() }
    }

    /** The editor also displays unpublished images imported into a local draft. */
    fun readable(db: SupportSQLiteDatabase, files: File, key: String): File? {
        val published = reference(db, key)
        if (published != null) return verifiedOriginal(db, files, key, published)?.file
        return current(db, files, key).takeIf { reference(db, key) == null }
    }

    fun publish(db: SupportSQLiteDatabase, key: String, revision: BookPackageFiles.Revision) =
        publish(db, key, Reference(revision))

    fun publish(db: SupportSQLiteDatabase, key: String, reference: Reference) {
        check(db.inTransaction())
        initialize(db)
        db.execSQL("INSERT OR REPLACE INTO $TABLE (storage_key,sha256,size,file_slot) VALUES(?,?,?,?)",
            arrayOf(key, reference.revision.sha256, reference.revision.bytes, reference.slot))
    }

    fun discardUnreferenced(db: SupportSQLiteDatabase, files: File, reference: Reference, staged: File) {
        db.beginTransactionNonExclusive()
        try {
            if (!referenced(db, reference.revision.sha256, reference.slot)) {
                ConditionalBodyFile.unlinkPublication(candidate(files, reference), staged)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** A selected reference owns archive hydration even when the bytes cannot repair it. */
    fun restoreReferenced(db: SupportSQLiteDatabase, files: File, key: String, raw: ByteArray): Boolean {
        val previous = selection(db, key) ?: return false
        val revision = previous.revision
        val before = candidate(files, previous)
        if (before.exists() || raw.size.toLong() != revision.bytes) return true
        val owners = ownerKeys(db, key)
        if (owners.isEmpty() || !winnerAllows(db, key, revision)) return true
        val parent = ensureCandidateDirectory(files)
        val staged = File.createTempFile("zip-", ".part", parent)
        val next = replacement(revision)
        var linked = false
        var committed = false
        try {
            FileOutputStream(staged).use { output ->
                output.write(raw)
                output.fd.sync()
            }
            val original = BookPackageFiles.verify(staged, revision) {} ?: return true
            db.beginTransactionNonExclusive()
            try {
                if (selection(db, key) != previous || before.exists() || !original.isCurrent() ||
                    ownerKeys(db, key) != owners || !winnerAllows(db, key, revision)) return true
                linked = ConditionalBodyFile.linkIfAbsent(candidate(files, next), staged)
                if (!linked) return true
                syncDirectory(parent)
                publish(db, key, next)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            committed = true
            published()
            return true
        } finally {
            try {
                if (linked && !committed) discardUnreferenced(db, files, next, staged)
            } finally {
                staged.delete()
            }
        }
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

    fun winnerAllows(db: SupportSQLiteDatabase, key: String, winner: BookPackageFiles.Revision): Boolean {
        val source = SyncV2NoteAssetNames.source
        val hasAliases = db.query("SELECT 1 FROM sqlite_master WHERE type='view' AND name=?", arrayOf(source))
            .use { it.moveToFirst() }
        val metadata = if (hasAliases) source else "note_assets"
        return db.query("SELECT sha256,byteCount,deleted FROM $metadata WHERE storageKey=?", arrayOf(key)).use {
            var seen = false
            while (it.moveToNext()) {
                seen = true
                if (it.getInt(2) == 0 && it.getString(0) == winner.sha256 && it.getLong(1) == winner.bytes) return@use true
            }
            !seen
        }
    }
}
