package com.lightningstudio.watchrss.phone.data.backup

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal object SafetySnapshotRetention {
    const val MAX_SNAPSHOT_COUNT = 3
    const val MAX_TOTAL_BYTES = 512L * 1024L * 1024L

    fun commit(temporary: File, destination: File) {
        destination.parentFile?.mkdirs()
        try {
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    fun prune(
        directory: File,
        maxSnapshotCount: Int = MAX_SNAPSHOT_COUNT,
        maxTotalBytes: Long = MAX_TOTAL_BYTES
    ): SafetySnapshotPruneResult {
        require(maxSnapshotCount >= 1) { "至少保留一份安全快照" }
        require(maxTotalBytes >= 0L) { "安全快照容量上限不能为负数" }
        if (!directory.isDirectory) return SafetySnapshotPruneResult(0L, 0, 0)

        directory.listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith(SNAPSHOT_PREFIX) && it.name.endsWith(PART_SUFFIX) }
            .forEach(File::delete)

        val snapshots = directory.listFiles().orEmpty()
            .filter { it.isFile && SNAPSHOT_PATTERN.matches(it.name) }
            .sortedWith(
                compareByDescending<File> { snapshotTimestamp(it.name) }
                    .thenByDescending(File::lastModified)
            )
        var retainedBytes = 0L
        var retainedCount = 0
        var deletedBytes = 0L
        var deletedCount = 0
        snapshots.forEachIndexed { index, snapshot ->
            val mustKeepNewest = index == 0
            val fitsCount = retainedCount < maxSnapshotCount
            val fitsBytes = retainedBytes + snapshot.length() <= maxTotalBytes
            if (mustKeepNewest || (fitsCount && fitsBytes)) {
                retainedBytes += snapshot.length()
                retainedCount += 1
            } else {
                val bytes = snapshot.length()
                if (snapshot.delete()) {
                    deletedBytes += bytes
                    deletedCount += 1
                }
            }
        }
        return SafetySnapshotPruneResult(deletedBytes, deletedCount, retainedCount)
    }

    private fun snapshotTimestamp(name: String): Long =
        SNAPSHOT_PATTERN.matchEntire(name)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

    private const val SNAPSHOT_PREFIX = "before-restore-"
    private const val PART_SUFFIX = ".part"
    private val SNAPSHOT_PATTERN = Regex("""before-restore-(\d+)\.wrss""")
}

internal data class SafetySnapshotPruneResult(
    val deletedBytes: Long,
    val deletedCount: Int,
    val retainedCount: Int
)
