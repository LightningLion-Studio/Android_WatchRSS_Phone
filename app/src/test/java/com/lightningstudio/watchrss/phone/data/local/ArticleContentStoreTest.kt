package com.lightningstudio.watchrss.phone.data.local

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArticleContentStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun longKeysWithSharedPrefixUseDifferentFiles() {
        val directory = temporaryFolder.newFolder("content")
        val store = FileArticleContentStore(directory)
        val sharedPrefix = "https://example.com/" + "same/".repeat(30)

        val first = store.storeText("${sharedPrefix}first", "same body")
        val second = store.storeText("${sharedPrefix}second", "same body")

        assertNotEquals(first, second)
        assertEquals("same body", store.loadText(first))
        assertEquals("same body", store.loadText(second))
        assertEquals(2, directory.listFiles().orEmpty().size)
    }

    @Test
    fun differentContentForSameKeyDoesNotOverwriteOldMarker() {
        val directory = temporaryFolder.newFolder("content")
        val store = FileArticleContentStore(directory)

        val first = store.storeText("article", "first body")
        val second = store.storeText("article", "second body")

        assertNotEquals(first, second)
        assertEquals("first body", store.loadText(first))
        assertEquals("second body", store.loadText(second))
        assertEquals(2, directory.listFiles().orEmpty().size)
    }

    @Test
    fun contentAndOriginalKeysRemainDistinctAfterLongPrefix() {
        val directory = temporaryFolder.newFolder("content")
        val store = FileArticleContentStore(directory)
        val key = "article-" + "x".repeat(160)

        val content = store.storeText("$key-content", "body")
        val original = store.storeText("$key-original", "body")

        assertNotEquals(content, original)
        assertEquals("body", store.loadText(content))
        assertEquals("body", store.loadText(original))
    }

    @Test
    fun legalLegacyMarkerRemainsReadable() {
        val directory = temporaryFolder.newFolder("content")
        val store = FileArticleContentStore(directory)
        val marker = store.markerFor("legacy/article")
        File(directory, marker.removePrefix(ARTICLE_CONTENT_MARKER_PREFIX)).writeText("legacy body")

        assertTrue(store.isMarker(marker))
        assertEquals("legacy body", store.loadText(marker))
    }

    @Test
    fun markerPrefixInLiteralBodyIsNotAStoreMarker() {
        val store = FileArticleContentStore(temporaryFolder.newFolder("content"))
        val literalBody = "${ARTICLE_CONTENT_MARKER_PREFIX}this is ordinary article text"

        assertFalse(store.isMarker(literalBody))
        assertNull(store.loadText(literalBody))
    }

    @Test
    fun traversalAndSeparatorMarkersCannotEscapeDirectory() {
        val directory = temporaryFolder.newFolder("content")
        val outside = File(temporaryFolder.root, "outside.txt").apply { writeText("outside") }
        val store = FileArticleContentStore(directory)
        val invalidMarkers = listOf(
            "${ARTICLE_CONTENT_MARKER_PREFIX}../outside.txt",
            "${ARTICLE_CONTENT_MARKER_PREFIX}../../outside.txt",
            "${ARTICLE_CONTENT_MARKER_PREFIX}/outside.txt",
            "${ARTICLE_CONTENT_MARKER_PREFIX}folder/outside.txt",
            "${ARTICLE_CONTENT_MARKER_PREFIX}folder\\outside.txt",
            "${ARTICLE_CONTENT_MARKER_PREFIX}.txt"
        )

        invalidMarkers.forEach { marker ->
            assertFalse(marker, store.isMarker(marker))
            assertNull(marker, store.loadText(marker))
            assertNull(marker, store.textChunkHandle(marker))
        }
        assertEquals("outside", outside.readText())
    }

    @Test
    fun sameKeyAndContentReuseStableMarkerAndFile() {
        val directory = temporaryFolder.newFolder("content")
        val store = FileArticleContentStore(directory)

        val first = store.storeText("article", "stable body")
        val second = store.storeText("article", "stable body")

        assertEquals(first, second)
        assertEquals("stable body", store.loadText(second))
        assertEquals(1, directory.listFiles().orEmpty().size)
    }

    @Test
    fun pruneRetainsReferencedLegacyAndV2FilesOnly() {
        val directory = temporaryFolder.newFolder("content")
        val store = FileArticleContentStore(directory)
        val v2Marker = store.storeText("article", "v2 body")
        val legacyMarker = store.markerFor("legacy")
        val legacyFile = File(directory, legacyMarker.removePrefix(ARTICLE_CONTENT_MARKER_PREFIX))
            .apply { writeText("legacy body") }
        val unreferencedMarker = store.markerFor("unreferenced")
        val unreferencedFile = File(directory, unreferencedMarker.removePrefix(ARTICLE_CONTENT_MARKER_PREFIX))
            .apply { writeText("old body") }
        val unrelatedFile = File(directory, "not-managed.bin").apply { writeText("leave me") }

        store.prune(setOf(v2Marker, legacyMarker))

        assertEquals("v2 body", store.loadText(v2Marker))
        assertTrue(legacyFile.isFile)
        assertFalse(unreferencedFile.exists())
        assertTrue(unrelatedFile.isFile)
    }
}
