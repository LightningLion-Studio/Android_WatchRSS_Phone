package com.lightningstudio.watchrss.phone.connection.bluetooth.b2

import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/** These directories contain rebuildable intermediates only. Active workers in
 * the phone app's single process own their directories until their finally runs. */
internal object B2Scratch {
    const val FREEZE = ".freeze-"
    const val BOOK_IMPORT = ".b2-import-"
    private val active = mutableSetOf<File>()

    class Directory internal constructor(val file: File) : Closeable {
        override fun close() {
            synchronized(active) {
                if (active.remove(file)) remove(file)
            }
        }
    }

    fun open(parent: File, prefix: String): Directory = synchronized(active) {
        val path = Files.createTempDirectory(parent.canonicalFile.toPath(), prefix).toFile()
        active.add(path)
        Directory(path)
    }

    fun <T> measure(block: () -> T): T = synchronized(active, block)

    fun bytes(parent: File?, prefix: String): Long = directories(parent, prefix).sumOf(::treeBytes)

    fun collect(parent: File?, prefix: String) = synchronized(active) {
        for (directory in directories(parent, prefix)) {
            if (directory.canonicalFile !in active) remove(directory)
        }
    }

    private fun directories(parent: File?, prefix: String): List<File> =
        parent?.listFiles()?.filter { it.name.startsWith(prefix) && Files.isDirectory(it.toPath(), NOFOLLOW_LINKS) }.orEmpty()

    private fun treeBytes(directory: File): Long {
        var bytes = 0L
        Files.walk(directory.toPath()).use { paths ->
            paths.forEach { if (Files.isRegularFile(it, NOFOLLOW_LINKS)) bytes += it.toFile().length() }
        }
        return bytes
    }

    private fun remove(directory: File) {
        // A failed removal stays in the peak ledger and can be retried.
        try {
            Files.walk(directory.toPath()).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        } catch (_: java.io.IOException) {
            // The next collection will retry.
        }
    }
}
