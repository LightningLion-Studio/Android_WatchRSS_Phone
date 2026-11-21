package com.lightningstudio.watchrss.phone.data.book

import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.MessageDigest

/** Resolve the original named by the current Room row, across both storage layouts. */
internal object BookPackageFiles {
    data class Revision(val sha256: String, val bytes: Long)

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
    private val verified = LinkedHashMap<File, Verification>(CACHE_CAPACITY, 0.75f, true)
    private val hex = Regex("[0-9a-f]{64}")

    fun revision(db: SupportSQLiteDatabase, id: String): Revision? =
        db.query("SELECT packageSha256,packageBytes FROM imported_books WHERE bookId=?", arrayOf(id)).use { row ->
            if (row.moveToFirst()) Revision(row.getString(0), row.getLong(1)) else null
        }

    fun legacy(directory: File, id: String): File = File(directory, "$id.jsonl")

    fun binary(directory: File, id: String, hash: String): File = File(directory, "b2-$id-$hash.jsonl")

    fun candidates(directory: File, id: String, revision: Revision): List<File> {
        if (!hex.matches(id)) return emptyList()
        val legacy = legacy(directory, id)
        return if (hex.matches(revision.sha256)) listOf(legacy, binary(directory, id, revision.sha256)) else listOf(legacy)
    }

    fun resolve(directory: File, id: String, revision: Revision, onRead: (Int) -> Unit = {}): File? =
        resolveOriginal(directory, id, revision, onRead)?.file

    fun resolveOriginal(directory: File, id: String, revision: Revision, onRead: (Int) -> Unit = {}): Original? =
        candidates(directory, id, revision).firstNotNullOfOrNull { verify(it, revision, onRead) }

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
