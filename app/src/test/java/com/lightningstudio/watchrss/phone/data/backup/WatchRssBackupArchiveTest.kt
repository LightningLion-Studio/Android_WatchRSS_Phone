package com.lightningstudio.watchrss.phone.data.backup

import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
import com.lightningstudio.watchrss.phone.data.db.PhoneRssSourceEntity
import com.lightningstudio.watchrss.phone.data.db.PhoneSavedItemEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class WatchRssBackupArchiveTest {
    @Test
    fun roundTrip_preservesUserLibraryAndDropsDeviceSyncMetadata() {
        val snapshot = sampleSnapshot()
        val output = ByteArrayOutputStream()

        WatchRssBackupArchive.write(snapshot, output)
        val archiveBytes = output.toByteArray()
        val restored = WatchRssBackupArchive.read(ByteArrayInputStream(archiveBytes))

        assertEquals(snapshot.exportedAt, restored.exportedAt)
        assertEquals(snapshot.sources.single().copy(sourceDeviceId = ""), restored.sources.single())
        assertEquals(
            snapshot.articles.single().copy(
                sourceDeviceId = "",
                syncBodyHash = "",
                syncBodyByteCount = 0L,
                syncChunkSize = 0,
                syncChunkHashesJson = "",
                syncMetadataHash = ""
            ),
            restored.articles.single()
        )
        assertEquals(snapshot.savedItems, restored.savedItems)

        val expandedText = readZipEntries(archiveBytes).values
            .joinToString("\n") { it.toString(Charsets.UTF_8) }
        assertFalse(expandedText.contains("device-secret"))
        assertFalse(expandedText.contains("sync-secret"))
        assertTrue(expandedText.contains("中文正文"))
    }

    @Test
    fun roundTrip_preservesNullHtmlAndDeletionRecord() {
        val base = sampleSnapshot()
        val deleted = base.articles.single().copy(
            articleId = "deleted-article",
            contentHtml = null,
            contentText = "",
            independentSaved = false,
            favoriteSaved = false,
            watchLaterSaved = false,
            deleted = true,
            deletedAt = 999L
        )
        val output = ByteArrayOutputStream()

        WatchRssBackupArchive.write(base.copy(articles = base.articles + deleted), output)
        val restored = WatchRssBackupArchive.read(ByteArrayInputStream(output.toByteArray()))
            .articles.single { it.articleId == deleted.articleId }

        assertNull(restored.contentHtml)
        assertEquals("", restored.contentText)
        assertTrue(restored.deleted)
        assertEquals(999L, restored.deletedAt)
    }

    @Test
    fun read_rejectsMissingArticleBody() {
        val output = ByteArrayOutputStream()
        WatchRssBackupArchive.write(sampleSnapshot(), output)
        val entries = readZipEntries(output.toByteArray()).toMutableMap()
        val textEntry = entries.keys.single { it.endsWith(".txt") && it.startsWith("bodies/") }
        entries.remove(textEntry)

        val error = assertThrows(IllegalArgumentException::class.java) {
            WatchRssBackupArchive.read(ByteArrayInputStream(writeZipEntries(entries)))
        }

        assertTrue(error.message.orEmpty().contains("缺少文章正文"))
    }

    @Test
    fun read_rejectsPathTraversal() {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("../manifest.json"))
            zip.write("{}".toByteArray())
            zip.closeEntry()
        }

        val error = assertThrows(IllegalArgumentException::class.java) {
            WatchRssBackupArchive.read(ByteArrayInputStream(output.toByteArray()))
        }

        assertTrue(error.message.orEmpty().contains("不安全路径"))
    }

    @Test
    fun read_rejectsUnsupportedFutureVersion() {
        val output = ByteArrayOutputStream()
        WatchRssBackupArchive.write(sampleSnapshot(), output)
        val entries = readZipEntries(output.toByteArray()).toMutableMap()
        val manifest = entries.getValue("manifest.json").toString(Charsets.UTF_8)
            .replace("\"version\":1", "\"version\":99")
        entries["manifest.json"] = manifest.toByteArray()

        val error = assertThrows(IllegalArgumentException::class.java) {
            WatchRssBackupArchive.read(ByteArrayInputStream(writeZipEntries(entries)))
        }

        assertTrue(error.message.orEmpty().contains("不支持"))
    }

    private fun sampleSnapshot(): WatchRssBackupSnapshot = WatchRssBackupSnapshot(
        exportedAt = 1_725_000_000_000L,
        appVersion = "1.0-test",
        sources = listOf(
            PhoneRssSourceEntity(
                url = "https://example.com/feed.xml",
                sourceDeviceId = "device-secret",
                title = "示例源",
                description = "描述",
                siteUrl = "https://example.com",
                imageUrl = null,
                createdAt = 100L,
                updatedAt = 200L,
                sortOrder = 300L,
                isPinned = true,
                deleted = false,
                deletedAt = 0L
            )
        ),
        articles = listOf(
            PhoneArticleEntity(
                articleId = "article-1",
                sourceDeviceId = "device-secret",
                url = "https://example.com/article",
                title = "中文标题",
                siteName = "示例源",
                excerpt = "摘要",
                contentHtml = "<p>中文正文</p>",
                contentText = "中文正文",
                imageUrl = null,
                contentHash = "content-hash",
                importedAt = 100L,
                updatedAt = 200L,
                independentSaved = true,
                independentChangedAt = 210L,
                independentSortOrder = 220L,
                rssSourceUrl = "https://example.com/feed.xml",
                rssSourceTitle = "示例源",
                favoriteSaved = true,
                favoriteChangedAt = 230L,
                favoriteSortOrder = 240L,
                watchLaterSaved = false,
                watchLaterChangedAt = 250L,
                watchLaterSortOrder = 0L,
                deleted = false,
                deletedAt = 0L,
                syncBodyHash = "sync-secret",
                syncBodyByteCount = 99L,
                syncChunkSize = 16,
                syncChunkHashesJson = "[\"sync-secret\"]",
                syncMetadataHash = "sync-secret",
                readingProgress = 0.75f
            )
        ),
        savedItems = listOf(
            PhoneSavedItemEntity(
                type = "FAVORITE",
                stableKey = "saved-1",
                remoteId = 1L,
                title = "保存项",
                link = "https://example.com/article",
                summary = "摘要",
                channelTitle = "示例源",
                pubDate = "2026-07-13",
                syncedAt = 300L
            )
        )
    )

    private fun readZipEntries(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        return entries
    }

    private fun writeZipEntries(entries: Map<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
