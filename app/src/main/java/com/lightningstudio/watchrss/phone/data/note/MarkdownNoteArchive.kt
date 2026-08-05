package com.lightningstudio.watchrss.phone.data.note

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class MarkdownArchiveEntry(val path: String, val bytes: ByteArray)

/** Shared format for SAF directories and ZIP exports. Paths are always relative and normalized. */
object MarkdownNoteArchive {
    private const val MAX_ENTRIES = 10_000
    private const val MAX_UNCOMPRESSED_BYTES = 128L * 1024 * 1024

    fun write(entries: List<MarkdownArchiveEntry>): ByteArray {
        require(entries.isNotEmpty()) { "没有可导出的笔记" }
        val seen = HashSet<String>()
        return ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                entries.sortedBy { it.path }.forEach { entry ->
                    val path = safePath(entry.path)
                    require(seen.add(path)) { "归档含重复路径：$path" }
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(entry.bytes)
                    zip.closeEntry()
                }
            }
            output.toByteArray()
        }
    }

    fun read(bytes: ByteArray): List<MarkdownArchiveEntry> {
        var total = 0L
        val seen = HashSet<String>()
        return ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            buildList {
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    require(size < MAX_ENTRIES) { "压缩包文件数量过多" }
                    val path = safePath(entry.name)
                    require(seen.add(path)) { "压缩包含重复路径：$path" }
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= MAX_UNCOMPRESSED_BYTES) { "压缩包解压后过大" }
                        output.write(buffer, 0, read)
                    }
                    add(MarkdownArchiveEntry(path, output.toByteArray()))
                    zip.closeEntry()
                }
            }
        }
    }

    fun safePath(raw: String): String {
        val normalized = raw.replace('\\', '/').trimStart('/')
        require(normalized.isNotBlank() && !normalized.contains('\u0000')) { "归档路径无效" }
        val parts = normalized.split('/').filter { it.isNotBlank() }
        require(parts.none { it == "." || it == ".." }) { "归档路径越界" }
        return parts.joinToString("/")
    }
}
