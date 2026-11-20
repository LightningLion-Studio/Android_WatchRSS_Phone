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

    private data class FileIdentity(
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

    fun resolve(directory: File, id: String, revision: Revision, onRead: (Int) -> Unit = {}): File? {
        if (!hex.matches(revision.sha256) || revision.bytes <= 0) return null
        return candidates(directory, id, revision).firstOrNull { matches(it, revision, onRead) }
    }

    private fun matches(file: File, revision: Revision, onRead: (Int) -> Unit): Boolean {
        return try {
            val identity = identity(file) ?: return false
            if (identity.bytes != revision.bytes) return false
            val key = file.absoluteFile
            if (identity.inode != null) {
                val cached = synchronized(verified) { verified[key] }
                if (cached?.revision == revision && cached.identity == identity) return cached.matches
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var remaining = revision.bytes
            file.inputStream().use { input ->
                val buffer = ByteArray(65_536)
                while (remaining > 0) {
                    val n = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                    if (n <= 0) return false
                    onRead(n)
                    digest.update(buffer, 0, n)
                    remaining -= n
                }
                if (input.read() != -1) return false
            }
            if (identity(file) != identity) return false
            val matches = digest.digest().joinToString("") { "%02x".format(it) } == revision.sha256
            if (identity.inode != null) synchronized(verified) {
                verified[key] = Verification(revision, identity, matches)
                if (verified.size > CACHE_CAPACITY) verified.remove(verified.keys.first())
            }
            matches
        } catch (_: IOException) {
            false
        }
    }

    private fun identity(file: File): FileIdentity? {
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
