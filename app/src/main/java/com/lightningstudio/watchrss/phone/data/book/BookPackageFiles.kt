package com.lightningstudio.watchrss.phone.data.book

import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Resolve the original named by the current Room row, across both storage layouts. */
internal object BookPackageFiles {
    data class Revision(val sha256: String, val bytes: Long)

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
        if (!file.isFile || file.length() != revision.bytes) return false
        val modified = file.lastModified()
        return try {
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
            file.length() == revision.bytes && file.lastModified() == modified &&
                digest.digest().joinToString("") { "%02x".format(it) } == revision.sha256
        } catch (_: IOException) {
            false
        }
    }
}
