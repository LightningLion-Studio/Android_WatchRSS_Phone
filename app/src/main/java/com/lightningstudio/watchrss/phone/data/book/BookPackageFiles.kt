package com.lightningstudio.watchrss.phone.data.book

import androidx.sqlite.db.SupportSQLiteDatabase
import com.lightningstudio.watchrss.phone.data.local.syncDirectory
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.MessageDigest

/** Resolve the original named by the current Room row, across both storage layouts. */
internal object BookPackageFiles {
    data class Revision(val sha256: String, val bytes: Long)
    data class Publication(val revision: Revision, val slot: Int)

    class Original internal constructor(val file: File, val revision: Revision, private val identity: FileIdentity) {
        fun isCurrent(): Boolean = try {
            fileIdentity(file) == identity
        } catch (_: IOException) {
            false
        }
    }

    internal data class FileIdentity(
        val bytes: Long,
        val modified: FileTime,
        val inode: Any?,
        val changed: FileTime?
    )

    private data class Verification(val revision: Revision, val identity: FileIdentity, val matches: Boolean)

    private const val CACHE_CAPACITY = 512
    private const val PUBLICATIONS = "b2_book_file_references"
    private val verified = LinkedHashMap<File, Verification>(CACHE_CAPACITY, 0.75f, true)
    private val hex = Regex("[0-9a-f]{64}")

    fun revision(db: SupportSQLiteDatabase, id: String): Revision? =
        db.query("SELECT packageSha256,packageBytes FROM imported_books WHERE bookId=?", arrayOf(id)).use { row ->
            if (row.moveToFirst()) Revision(row.getString(0), row.getLong(1)) else null
        }

    fun legacy(directory: File, id: String): File = File(directory, "$id.jsonl")

    fun binary(directory: File, id: String, hash: String): File = File(directory, "b2-$id-$hash.jsonl")

    private fun binary(directory: File, id: String, publication: Publication): File =
        File(directory, "b2-$id-${publication.revision.sha256}-${publication.slot}.jsonl")

    fun publication(db: SupportSQLiteDatabase, id: String): Publication? =
        publication(id) { sql, args -> db.query(sql, args) }

    private fun hasPublicationTable(query: (String, Array<String>) -> android.database.Cursor): Boolean =
        query("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(PUBLICATIONS)).use { it.moveToFirst() }

    fun publication(id: String, query: (String, Array<String>) -> android.database.Cursor): Publication? {
        if (!hasPublicationTable(query)) return null
        return query("SELECT sha256,size,slot FROM $PUBLICATIONS WHERE book_id=?", arrayOf(id)).use {
            if (it.moveToFirst()) Publication(Revision(it.getString(0), it.getLong(1)), it.getInt(2)) else null
        }
    }

    fun clearPublications(db: SupportSQLiteDatabase) {
        check(db.inTransaction())
        if (hasPublicationTable { sql, args -> db.query(sql, args) }) db.execSQL("DELETE FROM $PUBLICATIONS")
    }

    /** Keep the assembly available for retry and switch the selected file with the Room commit. */
    fun publish(db: SupportSQLiteDatabase, directory: File, id: String, original: Original) {
        check(db.inTransaction())
        require(hex.matches(id) && hex.matches(original.revision.sha256))
        val previous = publication(db, id)
        val selected = Publication(original.revision, if (previous?.slot == 0) 1 else 0)
        val target = binary(directory, id, selected)
        // A same-revision repair must never repopulate the path an independent reader can already select.
        Files.deleteIfExists(target.toPath())
        Files.createLink(target.toPath(), original.file.toPath())
        syncDirectory(directory)
        db.execSQL("CREATE TABLE IF NOT EXISTS $PUBLICATIONS (book_id TEXT PRIMARY KEY, sha256 TEXT NOT NULL, size INTEGER NOT NULL, slot INTEGER NOT NULL CHECK(slot IN (0,1)))")
        db.execSQL("INSERT OR REPLACE INTO $PUBLICATIONS VALUES(?,?,?,?)",
            arrayOf(id, selected.revision.sha256, selected.revision.bytes, selected.slot))
    }

    fun candidates(directory: File, id: String, revision: Revision, publication: Publication? = null): List<File> {
        if (!hex.matches(id)) return emptyList()
        val legacy = legacy(directory, id)
        if (!hex.matches(revision.sha256)) return listOf(legacy)
        val binary = if (publication?.revision == revision) binary(directory, id, publication)
            else binary(directory, id, revision.sha256)
        return listOf(legacy, binary)
    }

    fun resolve(directory: File, id: String, revision: Revision, publication: Publication? = null,
        onRead: (Int) -> Unit = {}): File? =
        resolveOriginal(directory, id, revision, publication, onRead)?.file

    fun resolveOriginal(directory: File, id: String, revision: Revision, publication: Publication? = null,
        onRead: (Int) -> Unit = {}): Original? =
        candidates(directory, id, revision, publication).firstNotNullOfOrNull { verify(it, revision, onRead) }

    fun verify(file: File, revision: Revision, onRead: (Int) -> Unit): Original? {
        if (!hex.matches(revision.sha256) || revision.bytes <= 0) return null
        return try {
            val identity = fileIdentity(file) ?: return null
            if (identity.bytes != revision.bytes) return null
            val key = file.absoluteFile
            if (identity.inode != null && identity.changed != null) {
                val cached = synchronized(verified) { verified[key] }
                if (cached?.revision == revision && cached.identity == identity) {
                    return if (cached.matches) Original(file, revision, identity) else null
                }
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var remaining = revision.bytes
            file.inputStream().use { input ->
                val buffer = ByteArray(65_536)
                while (remaining > 0) {
                    val n = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                    if (n <= 0) return null
                    onRead(n)
                    digest.update(buffer, 0, n)
                    remaining -= n
                }
                if (input.read() != -1) return null
            }
            if (fileIdentity(file) != identity) return null
            val matches = digest.digest().joinToString("") { "%02x".format(it) } == revision.sha256
            if (identity.inode != null && identity.changed != null) synchronized(verified) {
                verified[key] = Verification(revision, identity, matches)
                if (verified.size > CACHE_CAPACITY) verified.remove(verified.keys.first())
            }
            if (matches) Original(file, revision, identity) else null
        } catch (_: IOException) {
            null
        }
    }

    internal fun fileIdentity(file: File): FileIdentity? {
        val path = file.toPath()
        val unix = try {
            Files.readAttributes(path, "unix:isRegularFile,size,lastModifiedTime,fileKey,ctime")
        } catch (_: UnsupportedOperationException) {
            null
        }
        if (unix != null) {
            if (unix["isRegularFile"] != true) return null
            return FileIdentity(
                unix.getValue("size") as Long,
                unix.getValue("lastModifiedTime") as FileTime,
                unix["fileKey"],
                unix["ctime"] as FileTime?
            )
        }
        val basic = Files.readAttributes(path, BasicFileAttributes::class.java)
        return if (basic.isRegularFile) FileIdentity(basic.size(), basic.lastModifiedTime(), basic.fileKey(), null) else null
    }
}
