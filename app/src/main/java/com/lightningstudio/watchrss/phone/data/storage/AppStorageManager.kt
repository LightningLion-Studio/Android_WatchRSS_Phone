package com.lightningstudio.watchrss.phone.data.storage

import android.content.Context
import android.os.SystemClock
import com.lightningstudio.watchrss.phone.data.reader.ReaderResourceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class AppStorageStats(
    val totalBytes: Long,
    val originalBackgroundBytes: Long,
    val watchBackgroundBytes: Long,
    val fontBytes: Long,
    val cacheBytes: Long,
    val databaseBytes: Long,
    val otherBytes: Long
)

class AppStorageManager(
    context: Context,
    private val elapsedMillis: () -> Long = SystemClock::elapsedRealtime
) {
    private val appContext = context.applicationContext
    @Volatile private var recent: Pair<Long, AppStorageStats>? = null

    /** The total for a summary view. A recent result is used again; the directory tree is not read. */
    suspend fun totalBytes(minIntervalMillis: Long = TOTAL_REFRESH_INTERVAL_MILLIS): Long {
        recent?.let { (at, stats) -> if (elapsedMillis() - at in 0 until minIntervalMillis) return stats.totalBytes }
        return calculate().totalBytes
    }

    suspend fun calculate(): AppStorageStats = withContext(Dispatchers.IO) {
        val readerRoot = File(appContext.filesDir, ReaderResourceStore.ROOT_DIRECTORY)
        val originalBackgroundBytes = File(
            readerRoot,
            ReaderResourceStore.BACKGROUND_DIRECTORY
        ).sizeRecursively()
        val watchBackgroundBytes = File(
            readerRoot,
            ReaderResourceStore.VARIANT_DIRECTORY
        ).sizeRecursively()
        val fontBytes = File(readerRoot, ReaderResourceStore.FONT_DIRECTORY).sizeRecursively()
        val cacheBytes = appContext.cacheDir.sizeRecursively() +
            appContext.externalCacheDirs.filterNotNull().sumOf { it.sizeRecursively() }
        val databaseBytes = File(appContext.applicationInfo.dataDir, "databases").sizeRecursively()
        val filesBytes = appContext.filesDir.sizeRecursively()
        val sharedPreferencesBytes = File(
            appContext.applicationInfo.dataDir,
            "shared_prefs"
        ).sizeRecursively()
        val knownFiles = originalBackgroundBytes + watchBackgroundBytes + fontBytes
        val otherBytes = (filesBytes - knownFiles).coerceAtLeast(0L) + sharedPreferencesBytes
        AppStorageStats(
            totalBytes = filesBytes + cacheBytes + databaseBytes + sharedPreferencesBytes,
            originalBackgroundBytes = originalBackgroundBytes,
            watchBackgroundBytes = watchBackgroundBytes,
            fontBytes = fontBytes,
            cacheBytes = cacheBytes,
            databaseBytes = databaseBytes,
            otherBytes = otherBytes
        ).also { recent = elapsedMillis() to it }
    }

    // Cache eviction, not a reader-content deletion: retain presets, originals and cloud copies.
    // Deliberately do not advance the publication witness just to remove regenerable local variants.
    // The Settings action promises that these copies will be generated again when required.
    suspend fun clearWatchBackgroundCopies(): Long = withContext(Dispatchers.IO) {
        val directory = File(
            File(appContext.filesDir, ReaderResourceStore.ROOT_DIRECTORY),
            ReaderResourceStore.VARIANT_DIRECTORY
        )
        val before = directory.sizeRecursively()
        directory.deleteChildren()
        recent = null
        (before - directory.sizeRecursively()).coerceAtLeast(0L)
    }

    suspend fun clearCache(): Long = withContext(Dispatchers.IO) {
        val before = appContext.cacheDir.sizeRecursively() +
            appContext.externalCacheDirs.filterNotNull().sumOf { it.sizeRecursively() }
        appContext.cacheDir.deleteChildren()
        appContext.externalCacheDirs.filterNotNull().forEach { it.deleteChildren() }
        recent = null
        val after = appContext.cacheDir.sizeRecursively() +
            appContext.externalCacheDirs.filterNotNull().sumOf { it.sizeRecursively() }
        (before - after).coerceAtLeast(0L)
    }

    private fun File.sizeRecursively(): Long = when {
        !exists() -> 0L
        isFile -> length()
        else -> listFiles().orEmpty().sumOf { it.sizeRecursively() }
    }

    private fun File.deleteChildren() {
        listFiles().orEmpty().forEach { child ->
            if (child.isDirectory) child.deleteRecursively() else child.delete()
        }
    }

    companion object {
        const val CLEANUP_REMINDER_BYTES = 900L * 1024L * 1024L
        const val TOTAL_REFRESH_INTERVAL_MILLIS = 5L * 60L * 1000L
    }
}
