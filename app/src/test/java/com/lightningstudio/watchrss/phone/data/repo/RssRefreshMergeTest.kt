package com.lightningstudio.watchrss.phone.data.repo

import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
import org.junit.Assert.*
import org.junit.Test

class RssRefreshMergeTest {
    private fun row()=PhoneArticleEntity(articleId="rss",sourceDeviceId="phone",url="https://example.test/article",
        title="Title",siteName="Feed",excerpt="Preview",contentHtml=null,contentText="",imageUrl=null,contentHash="v1",
        importedAt=10,updatedAt=10,independentSaved=false,independentChangedAt=0,independentSortOrder=0,
        rssSourceUrl="https://example.test/feed",rssSourceTitle="Feed",favoriteSaved=false,favoriteChangedAt=0,favoriteSortOrder=0,
        watchLaterSaved=false,watchLaterChangedAt=0,watchLaterSortOrder=0,deleted=false,deletedAt=0)

    @Test fun unchangedFeedDoesNotResetReadStateSavedFlagsOrCachedBodyAndInventChanges() {
        val existing=row().copy(contentText="complete cached body",contentHtml="<p>complete cached body</p>",
            isRead=true,readingProgress=.5f,readingPositionBytes=30,readingPositionContentHash="v1",readingPositionChangedAt=15,
            independentSaved=true,independentChangedAt=12,independentSortOrder=4,
            favoriteSaved=true,favoriteChangedAt=13,watchLaterSaved=true,watchLaterChangedAt=14)
        val incoming=row().copy(contentText="feed summary",updatedAt=100,importedAt=100)
        val merged=incoming.withRssLocalStateFrom(existing)
        // The repository's semantic comparison can now preserve the original clocks.
        assertEquals(existing,merged.copy(updatedAt=existing.updatedAt,importedAt=existing.importedAt))
    }
    @Test fun newContentVersionUsesNewBodyButStillPreservesUserState() {
        val existing=row().copy(contentText="old body",isRead=true,deleted=true,deletedAt=15)
        val incoming=row().copy(contentHash="v2",contentText="new body")
        val merged=incoming.withRssLocalStateFrom(existing)
        assertEquals("new body",merged.contentText);assertEquals("v2",merged.contentHash)
        assertTrue(merged.isRead);assertTrue(merged.deleted);assertEquals(15L,merged.deletedAt)
    }
    @Test fun newlyAvailableFeedBodyCanFillAnEmptyCacheAndNewRowsStayUnchanged() {
        val incoming=row().copy(contentText="available body")
        assertEquals("available body",incoming.withRssLocalStateFrom(row()).contentText)
        assertSame(incoming,incoming.withRssLocalStateFrom(null))
    }
    @Test fun sharedArticleDiscoveryKeepsTheActiveChannelAndItsReadSavedState() {
        val owner="https://example.test/feed"
        val other="https://example.test/alternate"
        val existing=row().copy(isRead=true,favoriteSaved=true,readingProgress=.75f)
        val active=setOf(owner,other)
        val refreshed=if(existing.keepsOtherActiveRssOwner(other,active)) existing
            else row().copy(importedAt=100,updatedAt=100).withRssLocalStateFrom(existing)
        assertSame(existing,refreshed)
        assertFalse(existing.keepsOtherActiveRssOwner(owner,active))
        // Removing the old channel permits reassociation without losing the original import clock.
        assertFalse(existing.keepsOtherActiveRssOwner(other,setOf(other)))
        val reassociated=row().copy(rssSourceUrl=other,contentHash="v2",importedAt=100,updatedAt=100)
            .withRssLocalStateFrom(existing)
        assertEquals(10L,reassociated.importedAt)
        assertEquals(100L,reassociated.updatedAt)
        assertTrue(reassociated.isRead);assertTrue(reassociated.favoriteSaved)
        assertEquals(.75f,reassociated.readingProgress)
    }

}
