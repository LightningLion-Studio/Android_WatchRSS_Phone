package com.lightningstudio.watchrss.phone.data.note

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Bridges the portable Markdown/ZIP format to app-private note asset storage. */
class NoteImportExportService(private val context: Context, private val repository: NoteRepository) {
    suspend fun exportZip(): ByteArray {
        val entries = mutableListOf<MarkdownArchiveEntry>()
        repository.allNotes().filterNot { it.deleted }.forEach { note ->
            entries += MarkdownArchiveEntry("notes/${note.noteId}.md", MarkdownNoteCodec.export(note).toByteArray())
            repository.assets(note.noteId).forEach { asset ->
                val source = File(context.filesDir, "notes/assets/${asset.storageKey}")
                if (source.isFile) entries += MarkdownArchiveEntry("notes/assets/${asset.storageKey}", source.readBytes())
            }
        }
        return MarkdownNoteArchive.write(entries)
    }

    suspend fun importZip(bytes: ByteArray): Int = importEntries(MarkdownNoteArchive.read(bytes))

    /** Cloud already merges note text through diff3; this only hydrates its image files. */
    fun restoreAssetsZip(bytes: ByteArray) {
        MarkdownNoteArchive.read(bytes)
            .filter { it.path.startsWith("notes/assets/") }
            .forEach { entry ->
                val key = MarkdownNoteArchive.safePath(entry.path.removePrefix("notes/assets/")).substringAfterLast('/')
                val target = File(context.filesDir, "notes/assets/$key").also { it.parentFile?.mkdirs() }
                if (!target.exists()) target.writeBytes(entry.bytes)
            }
    }

    suspend fun importDirectory(uri: Uri): Int {
        val root = requireNotNull(DocumentFile.fromTreeUri(context, uri)) { "无法打开导入目录" }
        val entries = mutableListOf<MarkdownArchiveEntry>()
        fun visit(directory: DocumentFile, prefix: String) {
            directory.listFiles().forEach { child ->
                val path = if (prefix.isEmpty()) child.name.orEmpty() else "$prefix/${child.name.orEmpty()}"
                if (child.isDirectory) visit(child, path)
                else if (child.isFile) {
                    val safe = MarkdownNoteArchive.safePath(path)
                    val bytes = context.contentResolver.openInputStream(child.uri)?.use { it.readBytes() }
                        ?: throw IllegalArgumentException("无法读取 $safe")
                    entries += MarkdownArchiveEntry(safe, bytes)
                }
            }
        }
        visit(root, "")
        return importEntries(entries)
    }

    suspend fun importEntries(entries: List<MarkdownArchiveEntry>): Int {
        val assets = entries.filter { it.path.startsWith("notes/assets/") }.associateBy { it.path.removePrefix("notes/assets/") }
        var count = 0
        entries.filter { it.path.startsWith("notes/") && it.path.endsWith(".md") }.forEach { entry ->
            val note = repository.importMarkdown(entry.bytes.toString(Charsets.UTF_8))
            // Markdown points at assets/<key>; only restore names that the note actually references.
            Regex("!\\[[^]]*]\\(assets/([^)]*)\\)").findAll(note.markdown).forEach { image ->
                val key = image.groupValues[1]
                val source = assets[key] ?: return@forEach
                val safeKey = MarkdownNoteArchive.safePath(key).substringAfterLast('/')
                val target = File(context.filesDir, "notes/assets/$safeKey").also { it.parentFile?.mkdirs() }
                if (!target.exists()) target.writeBytes(source.bytes)
                repository.registerAsset(NoteAssetEntity(
                    assetId = UUID.randomUUID().toString(), noteId = note.noteId,
                    sha256 = sha256(target.readBytes()),
                    displayName = safeKey, mimeType = "application/octet-stream", byteCount = target.length(),
                    storageKey = safeKey, isOriginal = safeKey.endsWith(".original"), createdAt = System.currentTimeMillis()
                ))
            }
            count++
        }
        return count
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
