package com.lightningstudio.watchrss.phone.connection.bluetooth

import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
import org.junit.Assert.*
import org.junit.Test

/** Frozen Compose v1.3.3-5 LibrarySyncPayload.parseArticles trims these fields before
 * ArticleReceiveCheckpoint.stage hashes them. PodJS v2.0.1-1 uses the same v14 rule.
 * The chapter excerpt with a trailing space reproduces F-EMU-PHONE-01. */
class LegacyWatchMetadataCompatibilityTest {
    @Test fun chapterManifestHashMatchesFrozenReceiverWithoutChangingStoredBody() {
        val stored = chapter().copy(excerpt = "CHAPTER II Anna Pavlovna's drawing room ")
        for (canonicalIds in listOf(false, true)) {
            val projection = BluetoothArticleIdentityProjection(listOf(stored), emptyMap(), canonicalIds)
            val wire = projection.outgoing(stored)
            val manifest = LibrarySyncPayload.parseArticleManifest(
                LibrarySyncPayload.buildManifestRequest("phone", listOf(stored))
            ).single().let(projection::manifest)
            // Both frozen receivers trim excerpt before hashing, even if the sender omits a body.
            assertEquals(ArticleSyncBody.metadataHashFor(wire.copy(excerpt = wire.excerpt.trim())), manifest.metadataHash)
            assertEquals(stored.contentText, wire.contentText)
            assertEquals(stored.contentHash, wire.contentHash)
            assertEquals("CHAPTER II Anna Pavlovna's drawing room ", stored.excerpt)
        }
    }

    @Test fun fullAndMetadataOnlyFramesUseTheSameFrozenMetadataAsManifest() {
        val stored = chapter().copy(title = "  Chapter title\n", siteName = " site ",
            excerpt = "\u00a0excerpt\u3000", imageUrl = "  ", rssSourceTitle = "\tbook\n")
        val projection = BluetoothArticleIdentityProjection(listOf(stored), emptyMap(), false)
        val wire = projection.outgoing(stored)
        val manifest = LibrarySyncPayload.parseArticleManifest(
            LibrarySyncPayload.buildManifestRequest("phone", listOf(stored))
        ).single().let(projection::manifest)
        val receiver = wire.copy(title = wire.title.trim().ifBlank { wire.url.trim() },
            siteName = wire.siteName.trim(), excerpt = wire.excerpt.trim(),
            imageUrl = wire.imageUrl?.trim()?.ifBlank { null },
            rssSourceUrl = wire.rssSourceUrl?.trim()?.ifBlank { null },
            rssSourceTitle = wire.rssSourceTitle?.trim()?.ifBlank { null })
        assertEquals(ArticleSyncBody.metadataHashFor(receiver), manifest.metadataHash)
        assertEquals(manifest.metadataHash, ArticleSyncBody.metadataHashFor(wire))
        for (metadataOnly in listOf(false, true)) {
            val metadata = ArticleSyncBody.metadataFor(wire)
            val payload = ArticleSyncBody.payloadForRequest(wire, ArticleBodyRequest(wire.articleId,
                metadata.bodyHash, if(metadataOnly) emptyList() else metadata.chunkHashes.indices.toList(), metadataOnly))
            assertEquals(manifest.metadataHash, ArticleSyncBody.metadataHashFor(payload.article))
            assertEquals(metadataOnly, payload.metadataOnly)
        }
    }

    private fun chapter() = PhoneArticleEntity(
        articleId = "chapter", sourceDeviceId = "phone", url = "watchrss-import://book/chapter",
        title = "Chapter", siteName = "Book", excerpt = "excerpt", contentHtml = "<p>body </p>",
        contentText = "body with trailing space \n", imageUrl = null, contentHash = "body-identity",
        importedAt = 10, updatedAt = 20, independentSaved = false, independentChangedAt = 0,
        independentSortOrder = 0, rssSourceUrl = "watchrss-import://book", rssSourceTitle = "Book",
        favoriteSaved = false, favoriteChangedAt = 0, favoriteSortOrder = 0,
        watchLaterSaved = false, watchLaterChangedAt = 0, watchLaterSortOrder = 0,
        deleted = false, deletedAt = 0)
}
