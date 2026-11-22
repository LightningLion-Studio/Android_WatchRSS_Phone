package com.lightningstudio.watchrss.phone.data.note

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lightningstudio.watchrss.phone.PhoneCompanionApplication
import com.lightningstudio.watchrss.phone.connection.bluetooth.NoteAssetSyncPayload
import com.lightningstudio.watchrss.phone.data.book.BookPackageFiles
import com.lightningstudio.watchrss.phone.data.importer.LocalFileImportTarget
import com.lightningstudio.watchrss.phone.data.importer.classifyLocalFileImport
import com.lightningstudio.watchrss.phone.data.importer.isMarkdownFileName
import com.lightningstudio.watchrss.phone.data.importer.markdownTitleFromFileName
import com.lightningstudio.watchrss.phone.data.local.CloudExportFileWitness
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/** Bridges the portable Markdown/ZIP format to app-private note asset storage. */
class NoteImportExportService internal constructor(
    private val context: Context,
    private val repository: NoteRepository,
    private val assetDatabase: () -> SupportSQLiteDatabase? = { null },
    private val assetFile: (String) -> File?
) {
    constructor(context: Context, repository: NoteRepository) : this(
        context,
        repository,
        assetDatabase = { (context.applicationContext as? PhoneCompanionApplication)?.container?.noteAssetDatabase() },
        assetFile = { key ->
            val app = context.applicationContext as? PhoneCompanionApplication
            if (app != null) app.container.noteAssetFile(key) else NoteAssetFiles.legacy(context.filesDir, key)
        }
    )

    suspend fun exportZip(): ByteArray {
        val entries = mutableListOf<MarkdownArchiveEntry>()
        repository.allNotes().filterNot { it.deleted }.forEach { note ->
            entries += MarkdownArchiveEntry("notes/${note.noteId}.md", MarkdownNoteCodec.export(note).toByteArray())
            // Cloud hydration can restore referenced image files before local asset rows exist.
            val keys=repository.assets(note.noteId).map{it.storageKey}.toSet()+NoteAssetSyncPayload.referencedStorageKeys(note.markdown)
            keys.filter(NoteAssetFiles::isSafe).forEach asset@ { key ->
                val source = assetFile(key) ?: return@asset
                val raw = try {
                    val identity = BookPackageFiles.fileIdentity(source) ?: return@asset
                    source.readBytes().takeIf { BookPackageFiles.fileIdentity(source) == identity }
                } catch (_: IOException) {
                    null
                } ?: return@asset
                if (assetFile(key) == source) entries += MarkdownArchiveEntry("notes/assets/$key", raw)
            }
        }
        return MarkdownNoteArchive.write(entries.distinctBy{it.path})
    }

    suspend fun importZip(bytes: ByteArray): Int = importEntries(MarkdownNoteArchive.read(bytes))

    suspend fun importMarkdown(fileName: String, mimeType: String?, bytes: ByteArray): NoteEntity {
        require(classifyLocalFileImport(fileName, mimeType) == LocalFileImportTarget.MARKDOWN_NOTE) {
            "只支持导入 Markdown（.md）文件"
        }
        require(bytes.isNotEmpty()) { "Markdown 文件内容为空" }
        return repository.importMarkdown(
            markdownFile = bytes.toString(Charsets.UTF_8),
            fallbackTitle = markdownTitleFromFileName(fileName)
        )
    }

    /** Cloud already merges note text through diff3; this only hydrates its image files. */
    fun restoreAssetsZip(bytes: ByteArray) {
        MarkdownNoteArchive.read(bytes)
            .filter { it.path.startsWith("notes/assets/") }
            .forEach { entry ->
                val key = MarkdownNoteArchive.safePath(entry.path.removePrefix("notes/assets/")).substringAfterLast('/')
                restoreArchiveAsset(key, entry.bytes)
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
        val entriesByPath = entries.associateBy { MarkdownNoteArchive.safePath(it.path) }
        var count = 0
        markdownNoteEntries(entries).forEach { entry ->
            val notePath = MarkdownNoteArchive.safePath(entry.path)
            val note = repository.importMarkdown(
                entry.bytes.toString(Charsets.UTF_8),
                markdownTitleFromFileName(notePath)
            )
            // Markdown points at assets/<key>; only restore names that the note actually references.
            Regex("!\\[[^]]*]\\(assets/([^)]*)\\)").findAll(note.markdown).forEach asset@ { image ->
                val relativeAssetPath = MarkdownNoteArchive.safePath("assets/${image.groupValues[1]}")
                val noteDirectory = notePath.substringBeforeLast('/', missingDelimiterValue = "")
                val archiveAssetPath = if (noteDirectory.isEmpty()) {
                    relativeAssetPath
                } else {
                    "$noteDirectory/$relativeAssetPath"
                }
                val source = entriesByPath[archiveAssetPath] ?: return@asset
                val safeKey = relativeAssetPath.substringAfterLast('/')
                val target = restoreArchiveAsset(safeKey, source.bytes) ?: return@asset
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

    private fun restoreArchiveAsset(key: String, bytes: ByteArray): File? {
        val files = context.filesDir
        val db = assetDatabase()
        if (db != null && NoteAssetFiles.isSafe(key) && NoteAssetFiles.restoreReferenced(db, files, key, bytes)) {
            return NoteAssetFiles.readable(db, files, key)
        }
        val target = File(files, "notes/assets/$key").also { it.parentFile?.mkdirs() }
        if (!target.exists()) CloudExportFileWitness.writeBytes(target, bytes, expectedSha = null)
        return target
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}

internal fun markdownNoteEntries(entries: List<MarkdownArchiveEntry>): List<MarkdownArchiveEntry> =
    entries.filter { isMarkdownFileName(it.path) }
