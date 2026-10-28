package com.lightningstudio.watchrss.phone.data.repo

import com.lightningstudio.watchrss.phone.connection.bluetooth.ArticleBodyRequest
import com.lightningstudio.watchrss.phone.connection.bluetooth.ArticleBodyChunk
import com.lightningstudio.watchrss.phone.connection.bluetooth.ArticleSyncBody
import com.lightningstudio.watchrss.phone.connection.bluetooth.ArticleSyncManifestEntry
import com.lightningstudio.watchrss.phone.connection.bluetooth.ChunkedArticlePayload
import com.lightningstudio.watchrss.phone.connection.bluetooth.PhoneSyncConflictResolution
import com.lightningstudio.watchrss.phone.data.db.AppMetaDao
import com.lightningstudio.watchrss.phone.data.db.AppMetaEntity
import com.lightningstudio.watchrss.phone.data.db.PhoneArticleDao
import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
import com.lightningstudio.watchrss.phone.data.db.PhoneRssSourceDao
import com.lightningstudio.watchrss.phone.data.db.PhoneRssSourceEntity
import com.lightningstudio.watchrss.phone.data.db.PhoneSavedItemDao
import com.lightningstudio.watchrss.phone.data.db.PhoneSavedItemEntity
import com.lightningstudio.watchrss.phone.data.db.SyncChangeLogDao
import com.lightningstudio.watchrss.phone.data.db.SyncChangeLogEntity
import com.lightningstudio.watchrss.phone.data.db.SyncChangeLogEntityState
import com.lightningstudio.watchrss.phone.data.db.SyncPeerStateDao
import com.lightningstudio.watchrss.phone.data.db.SyncPeerStateEntity
import com.lightningstudio.watchrss.phone.data.importer.ImportedLocalContent
import com.lightningstudio.watchrss.phone.data.importer.ImportedRssItem
import com.lightningstudio.watchrss.phone.data.importer.ImportedRssSource
import com.lightningstudio.watchrss.phone.data.importer.ImportedWebArticle
import com.lightningstudio.watchrss.phone.data.importer.LocalContentImportKind
import com.lightningstudio.watchrss.phone.data.importer.WebArticleImporter
import com.lightningstudio.watchrss.phone.data.local.ArticleContentStore
import com.lightningstudio.watchrss.phone.data.local.StoredTextChunkHandle
import com.lightningstudio.watchrss.phone.data.model.ImportedContentIds
import com.lightningstudio.watchrss.phone.data.model.PhoneSavedItemType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import com.lightningstudio.watchrss.phone.connection.bluetooth.LibrarySyncPayload
import com.lightningstudio.watchrss.phone.cloud.PhoneCloudRecordCodec
import com.lightningstudio.watchrss.phone.data.backup.toMetadataArticle
import com.lightningstudio.watchrss.phone.data.backup.mergeCloudState
import com.lightningstudio.watchrss.phone.data.backup.CloudRssStateCodec
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class PhoneCompanionRepositoryTest {
    @Test fun equalClockSavedRssOriginalRepairsDifferentHashPlaceholderAcrossMergePaths() = runBlocking {
        for ((case,clock) in listOf(0 to 1780624780267L,1 to 1786512641785L))
        for (route in listOf("inline", "chunked", "backup")) {
            val empty = article("rss-repair-$case-$route", contentText="", rssSourceUrl="https://feed.test/rss",
                favoriteSaved=true, favoriteChangedAt=clock, updatedAt=clock).copy(contentHash="empty-placeholder",
                readingProgress=.6f, readingPositionBytes=42, readingPositionChangedAt=clock-10, isRead=true)
            val full = empty.copy(contentHash="watch-original", contentText="手表完整原文\n".repeat(600),
                contentHtml="<article>保留 HTML 原文</article>", sourceDeviceId="watch",
                readingProgress=0f, readingPositionBytes=0, readingPositionChangedAt=0, isRead=false)
            val dao=FakePhoneArticleDao().apply { items=listOf(empty) }
            val repo=PhoneCompanionRepository(FakePhoneSavedItemDao(),dao,FakePhoneRssSourceDao(),"phone")
            val before=repo.privateCloudVersions()
            suspend fun merge(row:PhoneArticleEntity):Int = when(route) {
                "backup" -> repo.mergeArticlesFromBackup(listOf(row))
                "inline" -> repo.mergeArticlesFromSync(listOf(row))
                else -> {
                    val m=ArticleSyncBody.metadataFor(row)
                    repo.mergeChunkedArticlesFromSync(listOf(ArticleSyncBody.payloadForRequest(row,
                        ArticleBodyRequest(row.articleId,m.bodyHash,m.chunkHashes.indices.toList()),m)))
                }
            }
            assertEquals(route,1,merge(full))
            val actual=dao.items.single()
            assertEquals(route,full.contentText,actual.contentText);assertEquals(full.contentHtml,actual.contentHtml)
            assertEquals(full.contentHash,actual.contentHash);assertTrue(actual.favoriteSaved)
            assertEquals(clock,actual.updatedAt);assertEquals(clock,actual.favoriteChangedAt)
            assertEquals(empty.readingPositionBytes,actual.readingPositionBytes);assertEquals(clock-10,actual.readingPositionChangedAt)
            assertEquals(empty.readingProgress,actual.readingProgress);assertTrue(actual.isRead)
            assertNotEquals(before,repo.privateCloudVersions())
            repeat(2) { merge(empty);merge(full) }
            assertEquals(full.contentText,dao.items.single().contentText)
            assertEquals(full.contentHash,dao.items.single().contentHash)
            assertTrue(dao.items.single().isRead);assertEquals(clock-10,dao.items.single().readingPositionChangedAt)
            val exported=repo.getArticlesForSync().single()
            assertEquals(full.contentText,exported.contentText)
            assertEquals(ArticleSyncBody.metadataFor(full).bodyHash,exported.syncBodyHash)
        }
    }

    @Test fun equalClockRssRepairDoesNotCrossIdentityPrivateVersionOrDeletion() = runBlocking {
        val empty=article("guard",contentText="",rssSourceUrl="https://feed.test/rss",favoriteSaved=true,
            favoriteChangedAt=100,updatedAt=100)
        for(local in listOf(empty.copy(updatedAt=101),empty.copy(deleted=true,deletedAt=102),
            empty.copy(rssSourceUrl=null),empty.copy(url="https://entry.test/different"),
            empty.copy(rssSourceUrl="https://different.test/rss"))) {
            val dao=FakePhoneArticleDao().apply { items=listOf(local) }
            val repo=PhoneCompanionRepository(FakePhoneSavedItemDao(),dao,FakePhoneRssSourceDao(),"phone")
            repo.mergeArticlesFromSync(listOf(empty.copy(contentHash="different",contentText="older or different original")))
            assertEquals("",dao.items.single().contentText)
            if(local.deleted)assertTrue(dao.items.single().deleted)
        }
    }

    @Test fun savedRssEmptyPlaceholderIsNotAnAvailableBodyOrExportableOriginal() = runBlocking {
        val row=article("placeholder",contentText="",rssSourceUrl="https://feed.test/rss",favoriteSaved=true)
        val dao=FakePhoneArticleDao().apply { items=listOf(row) }
        val repo=PhoneCompanionRepository(FakePhoneSavedItemDao(),dao,FakePhoneRssSourceDao(),"phone")
        assertFalse(repo.getArticleManifestsForSync().single().bodyAvailable)
        assertTrue(repo.getArticlesForSync().isEmpty())
        assertTrue(repo.getArticlesForSync(listOf(row.articleId)).isEmpty())
    }

    @Test fun authoritativeSyncStillCannotReplaceSavedRssOriginalWithEqualClockEmptyPlaceholder()=runBlocking {
        for(chunked in listOf(false,true)) {
            val full=article("authoritative",contentText="verified original",rssSourceUrl="https://feed.test/rss",
                favoriteSaved=true,favoriteChangedAt=100,updatedAt=100)
            val empty=full.copy(contentText="",contentHash="placeholder")
            val dao=FakePhoneArticleDao().apply { items=listOf(full) }
            val repo=PhoneCompanionRepository(FakePhoneSavedItemDao(),dao,FakePhoneRssSourceDao(),"phone")
            if(chunked) {
                val m=ArticleSyncBody.metadataFor(empty)
                val payload=ArticleSyncBody.payloadForRequest(empty,ArticleBodyRequest(empty.articleId,m.bodyHash,m.chunkHashes.indices.toList()),m)
                repo.mergeChunkedArticlesFromSync(listOf(payload),authoritative=true)
            } else repo.mergeArticlesFromSync(listOf(empty),authoritative=true)
            assertEquals(full.contentText,dao.items.single().contentText)
            assertEquals(full.contentHash,dao.items.single().contentHash)
        }
    }

    @Test
    fun liveLegacyCancellationWithResidualDeleteClockDoesNotAdvertiseArticleDeletion() = runBlocking {
        val dao = FakePhoneArticleDao().apply { items = listOf(article("cancelled", favoriteChangedAt = 200, deleted = false, deletedAt = 100)) }
        val repository = PhoneCompanionRepository(FakePhoneSavedItemDao(), dao, FakePhoneRssSourceDao(), "test-phone")
        repeat(3) {
            val manifest = repository.getArticleManifestsForSync().single()
            assertFalse(manifest.deleted)
            assertEquals(200L, manifest.favoriteChangedAt)
            assertTrue(repository.findDeleteConflicts(listOf(manifest)).isEmpty())
        }
    }

    @Test fun explicitMarkUnreadAdvancesReadingClockAndSurvivesOlderReadState() = runBlocking {
        val dao = FakePhoneArticleDao().apply { items = listOf(article("read").copy(isRead = true, readingPositionChangedAt = 10)) }
        val repository = PhoneCompanionRepository(savedItemDao = FakePhoneSavedItemDao(), articleDao = dao, rssSourceDao = FakePhoneRssSourceDao(), deviceId = "phone")
        repository.markArticleRead("read", false)
        val unread = dao.items.single()
        assertFalse(unread.isRead); assertTrue(unread.readingPositionChangedAt > 10)
        val incoming = CloudRssStateCodec.decode(CloudRssStateCodec.encode(listOf(unread.copy(isRead = true, readingPositionChangedAt = 9)), 0)).single()
        assertFalse(unread.mergeCloudState(incoming).isRead)
    }

    @Test
    fun oversizedExcerptIsMovedToBodyWithoutLosingTextOrLocalState() = runBlocking {
        val original = "段落😀\n".repeat(20000)
        val row = article("legacy", contentText="", favoriteSaved=true).copy(excerpt=original)
        val dao = FakePhoneArticleDao().apply { items=listOf(row) }
        val repository = PhoneCompanionRepository(savedItemDao=FakePhoneSavedItemDao(),articleDao=dao,rssSourceDao=FakePhoneRssSourceDao(),deviceId="phone")
        assertEquals(1, repository.compactCloudExcerpts {})
        val actual=dao.items.single()
        assertEquals(280, actual.excerpt.codePointCount(0,actual.excerpt.length))
        assertEquals(original,actual.contentText)
        assertTrue(actual.favoriteSaved)
        assertEquals(row.contentHash,actual.contentHash)
        val value = JSONObject(CloudRssStateCodec.encode(listOf(actual),0).toString(Charsets.UTF_8)).getJSONArray("articles").getJSONObject(0)
        assertFalse(value.has("contentText"))
        val key = ByteArray(32) { 1 }
        val id = PhoneCloudRecordCodec.id(key,"user","article",value)
        PhoneCloudRecordCodec.encrypt(key,"user",id,1,"article",value)
        assertEquals(0,repository.compactCloudExcerpts {})
        dao.items=listOf(row.copy(contentText="existing full body"))
        repository.compactCloudExcerpts {}
        assertEquals("existing full body",dao.items.single().contentText)
    }

    @Test
    fun missingRowsMaterializeWithoutChangingRemoteClocksOrTombstones() {
        val remote = article("index-only", contentText = "must not enter metadata", favoriteSaved = true,
            favoriteChangedAt = 7, deleted = true, deletedAt = 9, updatedAt = 11)
        val state = CloudRssStateCodec.decode(CloudRssStateCodec.encode(listOf(remote), 100)).single()
        val restored = state.toMetadataArticle()
        assertEquals(remote.articleId, restored.articleId)
        assertEquals(remote.contentHash, restored.contentHash)
        assertEquals(11L, restored.updatedAt)
        assertEquals(9L, restored.deletedAt)
        assertEquals(7L, restored.favoriteChangedAt)
        assertTrue(restored.deleted)
        assertTrue(restored.favoriteSaved)
        assertEquals("", restored.contentText)
        assertEquals(null, restored.contentHtml)
    }

    @Test
    fun newerIndexCannotRelabelOldBodyAsANewVersion() = runBlocking {
        val old = article("book", contentText = "old", updatedAt = 10)
        val dao = FakePhoneArticleDao().apply { items = listOf(old) }
        val repository = PhoneCompanionRepository(savedItemDao = FakePhoneSavedItemDao(), articleDao = dao,
            rssSourceDao = FakePhoneRssSourceDao(), deviceId = "phone")
        repository.mergeArticlesFromSync(listOf(old.copy(contentHash = "new-version", updatedAt = 20, contentText = "")))
        assertEquals("new-version", dao.items.single().contentHash)
        assertEquals("", dao.items.single().contentText)
    }

    @Test
    fun matchingVersionRecoversHtmlFromBackupAlongsideAPlainProjection() = runBlocking {
        val flat = article("book", contentText = "first second", updatedAt = 20)
        val dao = FakePhoneArticleDao().apply { items = listOf(flat) }
        val repository = PhoneCompanionRepository(savedItemDao = FakePhoneSavedItemDao(), articleDao = dao,
            rssSourceDao = FakePhoneRssSourceDao(), deviceId = "phone")
        repository.mergeArticlesFromBackup(listOf(flat.copy(contentHtml = "<p>first</p><p>second</p>", updatedAt = 10)))
        assertEquals("<p>first</p><p>second</p>", dao.items.single().contentHtml)
        assertEquals(20L, dao.items.single().updatedAt)
    }

    @Test
    fun fullSyncAndBackupFillMatchingVersionBehindNewerMetadata() = runBlocking {
        val missing = article("cloud-book", contentText = "", updatedAt = 20, favoriteSaved = true, favoriteChangedAt = 20)
        val body = missing.copy(contentText = "first\nsecond", updatedAt = 10, favoriteSaved = false, favoriteChangedAt = 5)
        for (backup in listOf(false, true)) {
            val dao = FakePhoneArticleDao().apply { items = listOf(missing) }
            val repository = PhoneCompanionRepository(savedItemDao = FakePhoneSavedItemDao(), articleDao = dao,
                rssSourceDao = FakePhoneRssSourceDao(), deviceId = "phone")
            if (backup) repository.mergeArticlesFromBackup(listOf(body)) else repository.mergeArticlesFromSync(listOf(body))
            assertEquals("first\nsecond", dao.items.single().contentText)
            assertEquals(20L, dao.items.single().updatedAt)
            assertTrue(dao.items.single().favoriteSaved)
            dao.items = listOf(missing.copy(contentHash = "edited"))
            if (backup) repository.mergeArticlesFromBackup(listOf(body)) else repository.mergeArticlesFromSync(listOf(body))
            assertEquals("", dao.items.single().contentText)
        }
    }

    @Test
    fun foregroundBodyFillPreservesStateAndRejectsDeletedOrChangedVersions() = runBlocking {
        val row = article("book", url = ImportedContentIds.epubChapterUrl("b", 1, "c"), contentText = "", favoriteSaved = true)
        val dao = FakePhoneArticleDao().apply { items = listOf(row) }
        val repository = PhoneCompanionRepository(savedItemDao = FakePhoneSavedItemDao(), articleDao = dao,
            rssSourceDao = FakePhoneRssSourceDao(), deviceId = "phone")
        assertEquals(listOf("book"), repository.missingCloudBodyIds())
        repository.fillMissingCloudBody(row, "first\nsecond") {}
        assertEquals("first\nsecond", dao.items.single().contentText)
        assertTrue(dao.items.single().favoriteSaved)
        assertTrue(repository.missingCloudBodyIds().isEmpty())
        dao.items = listOf(row.copy(contentHash = "new-version"))
        repository.fillMissingCloudBody(row, "stale") {}
        assertEquals("", dao.items.single().contentText)
        dao.items = listOf(row.copy(deleted = true))
        repository.fillMissingCloudBody(row, "stale") {}
        assertEquals("", dao.items.single().contentText)
        dao.items = listOf(row)
        var rejected = false
        try { repository.fillMissingCloudBody(row, "wrong-owner") { error("owner changed") } }
        catch (_: IllegalStateException) { rejected = true }
        assertTrue(rejected)
        assertEquals("", dao.items.single().contentText)
    }

    @Test
    fun newerCloudMetadataInvalidatesOldBodyButNotUnsentLocalState() {
        val local = article("book", contentText = "old body", updatedAt = 10, favoriteSaved = true, favoriteChangedAt = 50)
        val remote = local.copy(title = "renamed", contentHash = "new-hash", updatedAt = 20,
            sourceDeviceId = "other", contentText = "", favoriteSaved = false, favoriteChangedAt = 40)
        val state = CloudRssStateCodec.decode(CloudRssStateCodec.encode(listOf(remote), 100)).single()
        val merged = local.mergeCloudState(state)
        assertEquals("renamed", merged.title)
        assertEquals("new-hash", merged.contentHash)
        assertEquals("", merged.contentText)
        assertTrue(merged.favoriteSaved)
        assertEquals(local.contentText, local.mergeCloudState(state.copy(updatedAt = 5)).contentText)
    }

    @Test
    fun prepareLibrarySyncWindow_usesReportedPeerCursorAndFallsBackWhenPeerWentBackwards() = runBlocking {
        val changeLogDao = FakeSyncChangeLogDao(maxSeq = 100L)
        val peerStateDao = FakeSyncPeerStateDao(
            SyncPeerStateEntity(
                peerDeviceId = "watch-device",
                lastLocalSeqAckedByPeer = 80L,
                lastRemoteSeqApplied = 40L,
                lastFullSyncAt = System.currentTimeMillis(),
                lastProtocolVersion = 13,
                updatedAt = System.currentTimeMillis()
            )
        )
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = FakePhoneArticleDao(),
            rssSourceDao = FakePhoneRssSourceDao(),
            deviceId = "phone-device",
            syncChangeLogDao = changeLogDao,
            syncPeerStateDao = peerStateDao
        )

        val cursor = repository.getLibrarySyncCursor("watch-device")
        val forwardWindow = repository.prepareLibrarySyncWindow(
            peerDeviceId = "watch-device",
            peerAppliedLocalSeq = 90L
        )
        val resetWindow = repository.prepareLibrarySyncWindow(
            peerDeviceId = "watch-device",
            peerAppliedLocalSeq = 0L
        )

        assertEquals(PhoneLibrarySyncCursorSnapshot(100L, 40L, 80L), cursor)
        assertEquals(false, forwardWindow.fullSnapshot)
        assertEquals(90L, forwardWindow.fromSeqExclusive)
        assertEquals(90L, forwardWindow.peerAckedSeq)
        assertEquals(true, resetWindow.fullSnapshot)
        assertEquals("peerCursorBehind", resetWindow.fallbackReason)
        assertEquals(0L, resetWindow.fromSeqExclusive)
    }

    @Test
    fun replaceSavedItems_usesPayloadContentWithoutFetchingMetadata() = runBlocking {
        val dao = FakePhoneSavedItemDao()
        val articleDao = FakePhoneArticleDao()
        val repository = PhoneCompanionRepository(
            savedItemDao = dao,
            articleDao = articleDao,
            rssSourceDao = FakePhoneRssSourceDao(),
            deviceId = "test-phone"
        )

        val count = repository.replaceSavedItems(
            PhoneSavedItemType.FAVORITE,
            JSONArray(
                """
                [
                  {
                    "id": 7,
                    "title": "示例标题",
                    "link": "https://example.com/post",
                    "summary": "示例摘要",
                    "channelTitle": "示例频道",
                    "pubDate": "2026-03-27"
                  }
                ]
                """.trimIndent()
            )
        )

        assertEquals(1, count)
        assertEquals(1, dao.items.size)
        val savedItem = dao.items.single()
        assertEquals("示例标题", savedItem.title)
        assertEquals("示例摘要", savedItem.summary)
        assertEquals("示例频道", savedItem.channelTitle)
        assertEquals("2026-03-27", savedItem.pubDate)
        assertEquals(1, articleDao.items.size)
        assertEquals(true, articleDao.items.single().favoriteSaved)
    }

    @Test
    fun replaceSavedItems_fallsBackToLinkAndHostForLinksOnlyPayload() = runBlocking {
        val dao = FakePhoneSavedItemDao()
        val articleDao = FakePhoneArticleDao()
        val repository = PhoneCompanionRepository(
            savedItemDao = dao,
            articleDao = articleDao,
            rssSourceDao = FakePhoneRssSourceDao(),
            deviceId = "test-phone"
        )

        val count = repository.replaceSavedItems(
            PhoneSavedItemType.WATCH_LATER,
            JSONArray(
                """
                [
                  {
                    "link": "https://www.example.com/path/to/article"
                  }
                ]
                """.trimIndent()
            )
        )

        assertEquals(1, count)
        val savedItem = dao.items.single()
        assertEquals("https://www.example.com/path/to/article", savedItem.title)
        assertEquals("", savedItem.summary)
        assertEquals("example.com", savedItem.channelTitle)
        assertTrue(savedItem.syncedAt > 0L)
    }

    @Test
    fun importWebArticle_savesAsIndependentArticle() = runBlocking {
        val articleDao = FakePhoneArticleDao()
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = articleDao,
            rssSourceDao = FakePhoneRssSourceDao(),
            deviceId = "test-phone",
            webArticleImporter = {
                ImportedWebArticle(
                    articleId = "article-1",
                    url = "https://example.com/post",
                    title = "独立标题",
                    siteName = "example.com",
                    excerpt = "摘要",
                    contentHtml = null,
                    contentText = "正文",
                    imageUrl = null,
                    contentHash = "hash"
                )
            }
        )

        val article = repository.importWebArticle("https://example.com/post")

        assertEquals("独立标题", article.title)
        assertTrue(article.independentSaved)
        assertEquals(false, article.favoriteSaved)
        assertEquals(false, article.watchLaterSaved)
    }

    @Test
    fun addRssSource_savesSourceAndChannelArticles() = runBlocking {
        val sourceDao = FakePhoneRssSourceDao()
        val articleDao = FakePhoneArticleDao()
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = articleDao,
            rssSourceDao = sourceDao,
            deviceId = "test-phone",
            rssSourceImporter = {
                ImportedRssSource(
                    url = "https://example.com/feed.xml",
                    title = "示例源",
                    description = "源描述",
                    siteUrl = "https://example.com",
                    imageUrl = null,
                    items = listOf(
                        ImportedRssItem(
                            url = "https://example.com/a",
                            title = "频道文章",
                            excerpt = "摘要",
                            contentHtml = null,
                            contentText = "正文",
                            imageUrl = null,
                            guid = "a"
                        )
                    )
                )
            }
        )

        val result = repository.addRssSource("https://example.com/feed.xml")

        assertEquals(1, result.articleCount)
        assertEquals("示例源", sourceDao.sources.single().title)
        val article = articleDao.items.single()
        assertEquals("https://example.com/feed.xml", article.rssSourceUrl)
        assertEquals("示例源", article.rssSourceTitle)
        assertEquals(false, article.independentSaved)
    }

    @Test
    fun refreshRssSource_reimportsFeedAndPreservesSavedState() = runBlocking {
        val sourceDao = FakePhoneRssSourceDao()
        val articleDao = FakePhoneArticleDao()
        var importCount = 0
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = articleDao,
            rssSourceDao = sourceDao,
            deviceId = "test-phone",
            rssSourceImporter = { input ->
                importCount += 1
                ImportedRssSource(
                    url = input,
                    title = if (importCount == 1) "示例源" else "示例源更新",
                    description = "源描述",
                    siteUrl = "https://example.com",
                    imageUrl = null,
                    items = buildList {
                        add(
                            ImportedRssItem(
                                url = "https://example.com/a",
                                title = if (importCount == 1) "频道文章" else "频道文章更新",
                                excerpt = "摘要",
                                contentHtml = null,
                                contentText = "正文",
                                imageUrl = null,
                                guid = "a"
                            )
                        )
                        if (importCount > 1) {
                            add(
                                ImportedRssItem(
                                    url = "https://example.com/b",
                                    title = "新增文章",
                                    excerpt = "摘要",
                                    contentHtml = null,
                                    contentText = "正文",
                                    imageUrl = null,
                                    guid = "b"
                                )
                            )
                        }
                    }
                )
            }
        )
        repository.addRssSource("https://example.com/feed.xml")
        val existingArticle = articleDao.items.single()
        articleDao.upsert(
            existingArticle.copy(
                favoriteSaved = true,
                favoriteChangedAt = 123L,
                favoriteSortOrder = 123L
            )
        )

        val result = repository.refreshRssSource("https://example.com/feed.xml")

        assertEquals(2, result.articleCount)
        assertEquals("示例源", sourceDao.sources.single().title) // S8: subscription title is user-owned.
        val refreshedArticle = articleDao.items.first { it.url == "https://example.com/a" }
        assertEquals("频道文章", refreshedArticle.title) // S8: retain the saved original.
        assertTrue(refreshedArticle.favoriteSaved)
        assertEquals(123L, refreshedArticle.favoriteChangedAt)
        assertTrue(articleDao.items.any { it.url == "https://example.com/b" })
    }

    @Test
    fun toggleSaved_preservesRssArticleUpdatedAt() = runBlocking {
        val articleDao = FakePhoneArticleDao()
        val article = article(
            id = "rss-article",
            rssSourceUrl = "https://example.com/feed.xml"
        ).copy(updatedAt = 42L)
        articleDao.items = listOf(article)
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = articleDao,
            rssSourceDao = FakePhoneRssSourceDao(),
            deviceId = "test-phone"
        )

        val updated = repository.toggleSaved(article, PhoneSavedItemType.FAVORITE)

        assertTrue(updated.favoriteSaved)
        assertTrue(updated.favoriteChangedAt > 0L)
        assertEquals(42L, updated.updatedAt)
        assertEquals(42L, articleDao.items.single().updatedAt)
    }

    @Test
    fun refreshRssSource_rejectsImportedContentChannels() = runBlocking {
        val sourceUrl = ImportedContentIds.epubSourceUrl("book")
        val sourceDao = FakePhoneRssSourceDao().apply {
            sources = listOf(source(sourceUrl, title = "本地书籍"))
        }
        var importCount = 0
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = FakePhoneArticleDao(),
            rssSourceDao = sourceDao,
            deviceId = "test-phone",
            rssSourceImporter = {
                importCount += 1
                ImportedRssSource(
                    url = it,
                    title = "不应抓取",
                    description = "",
                    siteUrl = null,
                    imageUrl = null,
                    items = emptyList()
                )
            }
        )

        val result = runCatching { repository.refreshRssSource(sourceUrl) }

        assertTrue(result.isFailure)
        assertEquals("本地导入频道无需从 RSS 源刷新", result.exceptionOrNull()?.message)
        assertEquals(0, importCount)
    }

    @Test
    fun importLocalContent_savesImportedChannelArticle() = runBlocking {
        val sourceDao = FakePhoneRssSourceDao()
        val articleDao = FakePhoneArticleDao()
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = articleDao,
            rssSourceDao = sourceDao,
            deviceId = "test-phone",
            localContentImporter = { _, _, _ ->
                ImportedLocalContent(
                    kind = LocalContentImportKind.TXT,
                    source = ImportedRssSource(
                        url = ImportedContentIds.ROOT_SOURCE_URL,
                        title = ImportedContentIds.ROOT_SOURCE_TITLE,
                        description = "导入",
                        siteUrl = null,
                        imageUrl = null,
                        items = listOf(
                            ImportedRssItem(
                                url = ImportedContentIds.txtArticleUrl("txt-1"),
                                title = "本地小说",
                                excerpt = "摘要",
                                contentHtml = "<article><p>正文</p></article>",
                                contentText = "正文",
                                imageUrl = null,
                                guid = "txt-1"
                            )
                        )
                    )
                )
            }
        )

        val result = repository.importLocalContent("novel.txt", "text/plain", byteArrayOf(1))

        assertEquals(LocalContentImportKind.TXT, result.kind)
        assertEquals(ImportedContentIds.ROOT_SOURCE_TITLE, sourceDao.sources.single().title)
        val article = articleDao.items.single()
        assertEquals(ImportedContentIds.ROOT_SOURCE_URL, article.rssSourceUrl)
        assertEquals(false, article.independentSaved)
        assertEquals(false, article.favoriteSaved)
        assertEquals(false, article.watchLaterSaved)
    }

    @Test
    fun inspectAndConfirmTxtUpdate_matchesVersionedNameAndKeepsArticleState() = runBlocking {
        val sourceDao = FakePhoneRssSourceDao()
        val articleDao = FakePhoneArticleDao()
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = articleDao,
            rssSourceDao = sourceDao,
            deviceId = "test-phone",
            localContentImporter = ::txtImportForTest
        )
        val oldText = "第一章 开始\n\n第二章 继续\n\n第三章 转折\n\n第四章 暂停"
        repository.importLocalContent(
            "星河传说 1-60章 完整版.txt",
            "text/plain",
            oldText.toByteArray()
        )
        val old = articleDao.items.single()
        val oldPosition = oldText.substringBefore("第四章").toByteArray().size.toLong()
        articleDao.items = listOf(
            old.copy(
                favoriteSaved = true,
                watchLaterSaved = true,
                readingProgress = oldPosition.toFloat() / oldText.toByteArray().size,
                readingPositionBytes = oldPosition,
                readingPositionContentHash = old.contentHash,
                readingPositionChangedAt = 1234L
            )
        )
        val newText = "$oldText\n\n第五章 新篇\n\n第六章 未完待续"

        val inspection = repository.inspectLocalContentImport(
            "星河传说 更新至80章 2026-07-30.txt",
            "text/plain",
            newText.toByteArray()
        )
        val candidate = inspection.candidates.single()

        assertEquals(TxtUpdateRelation.APPEND_ONLY, candidate.relation)
        assertEquals(old.articleId, candidate.articleId)
        assertEquals(oldPosition, candidate.inheritedPositionBytes)
        assertEquals(
            oldPosition.toFloat() / newText.toByteArray().size,
            candidate.inheritedProgress,
            0.0001f
        )

        repository.confirmLocalContentImport(inspection, candidate.articleId)
        val updated = articleDao.items.single()
        assertEquals(old.articleId, updated.articleId)
        assertEquals("星河传说 更新至80章 2026-07-30", updated.title)
        assertEquals(true, updated.favoriteSaved)
        assertEquals(true, updated.watchLaterSaved)
        assertEquals(old.importedAt, updated.importedAt)
        assertEquals(oldPosition, updated.readingPositionBytes)
    }

    @Test
    fun appendFrom60To80Sections_mapsOldHalfToThirtySevenPointFivePercent() = runBlocking {
        val articleDao = FakePhoneArticleDao()
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = articleDao,
            rssSourceDao = FakePhoneRssSourceDao(),
            deviceId = "test-phone",
            localContentImporter = ::txtImportForTest
        )
        val oldText = "a\n".repeat(60)
        val newText = oldText + "b\n".repeat(20)
        repository.importLocalContent("连载故事 1-60章.txt", "text/plain", oldText.toByteArray())
        val old = articleDao.items.single()
        articleDao.items = listOf(
            old.copy(
                readingProgress = 0.5f,
                readingPositionBytes = oldText.toByteArray().size / 2L,
                readingPositionContentHash = old.contentHash,
                readingPositionChangedAt = 10L
            )
        )

        val candidate = repository.inspectLocalContentImport(
            "连载故事 更新至80章.txt",
            "text/plain",
            newText.toByteArray()
        ).candidates.single()

        assertEquals(TxtUpdateRelation.APPEND_ONLY, candidate.relation)
        assertEquals(0.375f, candidate.inheritedProgress, 0.0001f)
    }

    @Test
    fun inspectTxtUpdate_distinguishesIdenticalOlderAndMiddleRevision() = runBlocking {
        val articleDao = FakePhoneArticleDao()
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = articleDao,
            rssSourceDao = FakePhoneRssSourceDao(),
            deviceId = "test-phone",
            localContentImporter = ::txtImportForTest
        )
        val oldText = "第一章 原文\n\n第二章 原文\n\n第三章 原文\n\n第四章 原文"
        repository.importLocalContent("长篇故事 1-60章.txt", "text/plain", oldText.toByteArray())

        val identical = repository.inspectLocalContentImport(
            "长篇故事 完整版.txt",
            "text/plain",
            oldText.toByteArray()
        )
        val older = repository.inspectLocalContentImport(
            "长篇故事 1-40章.txt",
            "text/plain",
            oldText.substringBefore("\n\n第四章").toByteArray()
        )
        val revision = repository.inspectLocalContentImport(
            "长篇故事 修订版.txt",
            "text/plain",
            oldText.replace("第二章 原文", "第二章 已修订").toByteArray()
        )

        assertEquals(TxtUpdateRelation.IDENTICAL, identical.candidates.single().relation)
        assertEquals(TxtUpdateRelation.OLDER_VERSION, older.candidates.single().relation)
        assertEquals(TxtUpdateRelation.POSSIBLE_REVISION, revision.candidates.single().relation)
        assertEquals(true, revision.candidates.single().approximateProgress)
    }

    @Test
    fun importLocalContent_externalizesLargeTxtWithoutSplitting() = runBlocking {
        val sourceDao = FakePhoneRssSourceDao()
        val articleDao = FakePhoneArticleDao()
        val contentStore = FakeArticleContentStore()
        val longText = buildString {
            repeat(130_000) { append('字') }
        }
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = articleDao,
            rssSourceDao = sourceDao,
            deviceId = "test-phone",
            localContentImporter = { _, _, _ ->
                ImportedLocalContent(
                    kind = LocalContentImportKind.TXT,
                    source = ImportedRssSource(
                        url = ImportedContentIds.ROOT_SOURCE_URL,
                        title = ImportedContentIds.ROOT_SOURCE_TITLE,
                        description = "导入",
                        siteUrl = null,
                        imageUrl = null,
                        items = listOf(
                            ImportedRssItem(
                                url = ImportedContentIds.txtArticleUrl("txt-large"),
                                title = "长篇小说",
                                excerpt = "摘要",
                                contentHtml = null,
                                contentText = longText,
                                imageUrl = null,
                                guid = "txt-large"
                            )
                        )
                    )
                )
            },
            articleContentStore = contentStore
        )

        val result = repository.importLocalContent("novel.txt", "text/plain", byteArrayOf(1))

        assertEquals(1, result.articleCount)
        val storedArticle = articleDao.items.single()
        assertTrue(contentStore.isMarker(storedArticle.contentText))
        assertEquals(longText, contentStore.loadText(storedArticle.contentText))
        assertEquals(longText, repository.getArticlesForSync().single().contentText)

        val reader = repository.getImportedTextReader(storedArticle.articleId)
        assertEquals(storedArticle.contentText, reader?.marker)
        assertTrue((reader?.chunkCount ?: 0) > 1)
        val firstChunk = repository.loadImportedTextChunk(storedArticle.contentText, 0).orEmpty()
        assertTrue(firstChunk.isNotBlank())
        assertTrue(firstChunk.length < longText.length)
    }

    @Test
    fun importLocalContent_externalizesLargeEpubChapterWithoutSplitting() = runBlocking {
        val sourceDao = FakePhoneRssSourceDao()
        val articleDao = FakePhoneArticleDao()
        val contentStore = FakeArticleContentStore()
        val longHtml = "<article><p>${"字".repeat(130_000)}</p></article>"
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = articleDao,
            rssSourceDao = sourceDao,
            deviceId = "test-phone",
            localContentImporter = { _, _, _ ->
                ImportedLocalContent(
                    kind = LocalContentImportKind.EPUB,
                    source = ImportedRssSource(
                        url = ImportedContentIds.epubSourceUrl("book-large"),
                        title = "长篇 EPUB",
                        description = "导入",
                        siteUrl = null,
                        imageUrl = null,
                        items = listOf(
                            ImportedRssItem(
                                url = ImportedContentIds.epubChapterUrl("book-large", 1, "chapter-large"),
                                title = "第一章",
                                excerpt = "摘要",
                                contentHtml = longHtml,
                                contentText = "正文",
                                imageUrl = null,
                                guid = "chapter-large"
                            )
                        )
                    )
                )
            },
            articleContentStore = contentStore
        )

        val result = repository.importLocalContent("book.epub", "application/epub+zip", byteArrayOf(1))

        assertEquals(1, result.articleCount)
        assertTrue(result.source.url.startsWith(ImportedContentIds.EPUB_SOURCE_ROOT_URL))
        val storedArticle = articleDao.items.single()
        assertTrue(contentStore.isMarker(storedArticle.contentHtml.orEmpty()))
        assertEquals("正文", storedArticle.contentText)
        assertEquals(longHtml, contentStore.loadText(storedArticle.contentHtml.orEmpty()))
        assertEquals(longHtml, repository.getArticlesForSync().single().contentHtml)
    }

    @Test
    fun repairImportedContentTitles_usesExistingHtmlTocAndTextFallback() = runBlocking {
        val sourceUrl = ImportedContentIds.epubSourceUrl("three-body")
        val sourceDao = FakePhoneRssSourceDao().apply {
            sources = listOf(
                PhoneRssSourceEntity(
                    url = sourceUrl,
                    sourceDeviceId = "test-phone",
                    title = "三体全集",
                    description = "导入",
                    siteUrl = null,
                    imageUrl = null,
                    createdAt = 1L,
                    updatedAt = 1L,
                    sortOrder = 1L,
                    isPinned = false,
                    deleted = false,
                    deletedAt = 0L
                )
            )
        }
        val articleDao = FakePhoneArticleDao().apply {
            items = listOf(
                article(
                    id = "toc",
                    url = "$sourceUrl/chapter/0001",
                    rssSourceUrl = sourceUrl,
                    title = "Contents",
                    contentHtml = """
                        <article>
                          <a href="part0001.html">§§第一章 科学边界</a>
                          <a href="part0002.html">§§第二章 台 球</a>
                          <a href="part0003.html">§§第三章 射手和农场主</a>
                        </article>
                    """.trimIndent(),
                    contentText = "目录",
                    importedAt = 4L
                ),
                article(id = "c1", url = "$sourceUrl/chapter/0002", rssSourceUrl = sourceUrl, title = "未知", importedAt = 3L),
                article(id = "c2", url = "$sourceUrl/chapter/0003", rssSourceUrl = sourceUrl, title = "未知", importedAt = 2L),
                article(id = "c3", url = "$sourceUrl/chapter/0004", rssSourceUrl = sourceUrl, title = "未知", contentText = "第三章 射手和农场主\n正文", importedAt = 1L)
            )
        }
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = articleDao,
            rssSourceDao = sourceDao,
            deviceId = "test-phone"
        )

        val repaired = repository.repairImportedContentTitles()

        assertEquals(4, repaired)
        assertEquals(listOf("目录", "第一章 科学边界", "第二章 台 球", "第三章 射手和农场主"), articleDao.items.sortedByDescending { it.importedAt }.map { it.title })
    }

    @Test
    fun getArticlesForSync_excludesPlainRssSourceArticles() = runBlocking {
        val articleDao = FakePhoneArticleDao()
        articleDao.items = listOf(
            article(
                id = "plain-rss",
                rssSourceUrl = "https://example.com/feed.xml"
            ),
            article(
                id = "saved-rss",
                rssSourceUrl = "https://example.com/feed.xml",
                favoriteSaved = true,
                favoriteChangedAt = 10L
            ),
            article(
                id = "removed-save-state",
                rssSourceUrl = "https://example.com/feed.xml",
                favoriteChangedAt = 20L
            ),
            article(
                id = "independent",
                independentSaved = true,
                independentChangedAt = 30L
            ),
            article(
                id = "imported-content",
                url = ImportedContentIds.txtArticleUrl("txt-1"),
                rssSourceUrl = ImportedContentIds.ROOT_SOURCE_URL
            )
        )
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = articleDao,
            rssSourceDao = FakePhoneRssSourceDao(),
            deviceId = "test-phone"
        )

        val ids = repository.getArticlesForSync().map { it.articleId }.toSet()

        assertEquals(setOf("saved-rss", "removed-save-state", "independent", "imported-content"), ids)
    }

    @Test
    fun deleteArticle_marksImportedContentAsDeletedTombstone() = runBlocking {
        val articleDao = FakePhoneArticleDao()
        val imported = article(
            id = "imported-content",
            url = ImportedContentIds.txtArticleUrl("txt-1"),
            rssSourceUrl = ImportedContentIds.ROOT_SOURCE_URL,
            favoriteSaved = true,
            favoriteChangedAt = 10L
        )
        articleDao.items = listOf(imported)
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = articleDao,
            rssSourceDao = FakePhoneRssSourceDao(),
            deviceId = "test-phone"
        )

        repository.deleteArticle(imported.articleId)

        val deleted = articleDao.items.single()
        assertTrue(deleted.deleted)
        assertEquals(false, deleted.favoriteSaved)
        assertEquals(ImportedContentIds.ROOT_SOURCE_URL, deleted.rssSourceUrl)
        assertEquals(setOf(imported.articleId), repository.getArticlesForSync().map { it.articleId }.toSet())
    }

    @Test
    fun deleteArticle_recomputesTombstoneMetadataHash() = runBlocking {
        val articleDao = FakePhoneArticleDao()
        val current = article(
            id = "article-with-metadata",
            contentText = "完整正文",
            favoriteSaved = true,
            favoriteChangedAt = 10L
        )
        val currentMetadata = ArticleSyncBody.metadataFor(current)
        articleDao.items = listOf(
            current.copy(
                syncBodyHash = currentMetadata.bodyHash,
                syncBodyByteCount = currentMetadata.bodyByteCount,
                syncChunkSize = currentMetadata.chunkSize,
                syncChunkHashesJson = JSONArray(currentMetadata.chunkHashes).toString(),
                syncMetadataHash = currentMetadata.metadataHash
            )
        )
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = articleDao,
            rssSourceDao = FakePhoneRssSourceDao(),
            deviceId = "test-phone"
        )

        repository.deleteArticle(current.articleId)

        val tombstone = articleDao.items.single()
        assertTrue(tombstone.deleted)
        assertEquals(ArticleSyncBody.metadataHashFor(tombstone), tombstone.syncMetadataHash)
        assertTrue(tombstone.syncMetadataHash != currentMetadata.metadataHash)
    }

    @Test
    fun deletedManifest_marksMissingExternalBodyUnavailable() = runBlocking {
        val contentStore = FakeArticleContentStore()
        val articleDao = FakePhoneArticleDao().apply {
            items = listOf(
                article(
                    id = "deleted-missing-body",
                    contentText = contentStore.markerFor("missing"),
                    deleted = true,
                    deletedAt = 20L,
                    updatedAt = 20L
                ).copy(syncMetadataHash = "pre-delete-metadata")
            )
        }
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = articleDao,
            rssSourceDao = FakePhoneRssSourceDao(),
            deviceId = "test-phone",
            articleContentStore = contentStore
        )

        val manifest = repository.getArticleManifestsForSync().single()

        assertFalse(manifest.bodyAvailable)
        assertEquals(ArticleSyncBody.metadataHashFor(articleDao.items.single()), manifest.metadataHash)
        assertEquals(emptyList<PhoneArticleEntity>(), repository.getArticlesForSync(listOf(manifest.articleId)))
    }

    @Test fun conflictDecisionDominatesFuturePeerClockAndIsDurableAcrossFullReplayAndUpgrade() = runBlocking {
        for (resolution in PhoneSyncConflictResolution.entries) for (phoneDeleted in listOf(false, true)) {
            val future = 9_000_000_000_000L
            val local = article("conflict", favoriteSaved = !phoneDeleted, favoriteChangedAt = future - 100,
                deleted = phoneDeleted, deletedAt = if (phoneDeleted) future - 50 else 0)
            val remote = local.copy(sourceDeviceId = "watch", updatedAt = future, favoriteSaved = phoneDeleted,
                favoriteChangedAt = future, deleted = !phoneDeleted, deletedAt = if (!phoneDeleted) future else 0)
            val dao = FakePhoneArticleDao().apply { items = listOf(local) }
            val meta = ConflictMetaDao()
            fun repo() = PhoneCompanionRepository(FakePhoneSavedItemDao(), dao, FakePhoneRssSourceDao(), "test-phone", appMetaDao = meta)
            val repository = repo()
            assertEquals(1, repository.findDeleteConflicts(listOf(remote.toManifestEntry())).size)
            val plan = repository.prepareDeleteConflictResolutions(listOf(remote.toManifestEntry()), mapOf("conflict" to resolution))
            if (plan.forcedRemoteRequests.isNotEmpty()) {
                // The pending user choice also survives process loss before the body response.
                assertTrue(repo().findDeleteConflicts(listOf(remote.toManifestEntry())).isEmpty())
                val retry = repo().prepareDeleteConflictResolutions(listOf(remote.toManifestEntry()), emptyMap())
                assertEquals(plan.mergeResolutions, retry.mergeResolutions)
                repository.mergeArticlesFromSync(listOf(remote), plan.mergeResolutions)
            } else assertEquals(setOf("conflict"), plan.outgoingArticleIds)
            val decided = dao.items.single()
            assertTrue("$resolution/$phoneDeleted", decided.updatedAt > future)
            assertEquals(decided.updatedAt, decided.favoriteChangedAt)
            assertEquals(decided.updatedAt, decided.watchLaterChangedAt)
            assertEquals(decided.updatedAt, decided.independentChangedAt)
            val expectedDeleted = when(resolution) {
                PhoneSyncConflictResolution.DELETE_CONTENT -> true
                PhoneSyncConflictResolution.MERGE_CONTENT -> false
                PhoneSyncConflictResolution.KEEP_PHONE -> phoneDeleted
                else -> !phoneDeleted
            }
            assertEquals(expectedDeleted, decided.deleted)
            // Real native encoder/decoder, not an in-memory direct assignment to the peer.
            val frame = LibrarySyncPayload.buildChunkedArticleRequestFrames("test-phone", listOf(decided), emptyList(), emptyList(), true)
            val payload = LibrarySyncPayload.parseChunkedArticles(frame.single()).single()
            assertEquals(decided.updatedAt, payload.article.updatedAt)
            assertEquals(decided.deleted, payload.article.deleted)
            repeat(3) { // reconnect, forced full snapshot, projection-cache rebuild / new repository instance
                val next = repo()
                assertTrue(next.findDeleteConflicts(listOf(remote.toManifestEntry())).isEmpty())
                val replay = next.prepareDeleteConflictResolutions(listOf(remote.toManifestEntry()), emptyMap())
                if (decided.deleted != remote.deleted) assertEquals(setOf("conflict"), replay.outgoingArticleIds)
                assertEquals(decided, dao.items.single())
                assertTrue(next.findDeleteConflicts(listOf(payload.article.toManifestEntry())).isEmpty())
            }
            // A genuinely new remote user decision is never hidden by the old receipt.
            val newer = remote.copy(updatedAt = decided.updatedAt + 10, favoriteChangedAt = decided.updatedAt + 10,
                deleted = !decided.deleted, deletedAt = if (!decided.deleted) decided.updatedAt + 10 else 0)
            assertEquals(1, repo().findDeleteConflicts(listOf(newer.toManifestEntry())).size)
        }
    }

    @Test fun sharedConflictVectorsProducePersistedAndEncodedDecisions() = runBlocking {
        val fixture = JSONObject(requireNotNull(javaClass.getResourceAsStream("/ble/conflict-resolution-v1.json")).bufferedReader().readText())
        val cases = fixture.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            fun parse(key: String): PhoneArticleEntity {
                val value = JSONObject(c.getJSONObject(key).toString())
                value.keys().asSequence().toList().filter { value.isNull(it) }.forEach(value::remove)
                return LibrarySyncPayload.parseArticles(JSONObject().put("articles", JSONArray().put(value))).single()
                    .copy(contentText = value.getString("contentText"))
            }
            val phone = parse("phone"); val watch = parse("watch"); val expected = parse("expected")
            val dao = FakePhoneArticleDao().apply { items = listOf(phone) }
            val meta = ConflictMetaDao()
            val repository = PhoneCompanionRepository(FakePhoneSavedItemDao(), dao, FakePhoneRssSourceDao(), "test-phone", appMetaDao = meta)
            val plan = repository.prepareDeleteConflictResolutions(listOf(watch.toManifestEntry()),
                mapOf(phone.articleId to PhoneSyncConflictResolution.valueOf(c.getString("resolution"))))
            assertEquals(c.getBoolean("expectBodyFetch"), plan.forcedRemoteRequests.isNotEmpty())
            if (plan.forcedRemoteRequests.isNotEmpty()) repository.mergeArticlesFromSync(listOf(watch), plan.mergeResolutions)
            val decided = dao.items.single()
            assertEquals(expected.deleted, decided.deleted)
            assertEquals(expected.deletedAt, decided.deletedAt)
            assertEquals(expected.updatedAt, decided.updatedAt)
            assertEquals(expected.favoriteSaved, decided.favoriteSaved)
            assertEquals(expected.favoriteChangedAt, decided.favoriteChangedAt)
            assertEquals(expected.watchLaterChangedAt, decided.watchLaterChangedAt)
            assertEquals(expected.independentChangedAt, decided.independentChangedAt)
            val frames = LibrarySyncPayload.buildChunkedArticleRequestFrames("test-phone", listOf(decided), emptyList(), emptyList(), true)
            val received = LibrarySyncPayload.parseChunkedArticles(frames.single()).single()
            assertEquals("fixture body", ArticleSyncBody.rebuildBody(null, received).second)
            assertEquals(decided.updatedAt, received.article.updatedAt)
            val dir = java.io.File("build/conflict-vectors").apply { mkdirs() }
            java.io.File(dir, c.getString("name")+".json").writeText(frames.single().toString(2))
        }
    }

    @Test fun conflictReceiptMigratesWithLegacyAliasAndSurvivesEncoderMetadataChange() = runBlocking {
        val url = "https://example.com/alias-conflict"
        val legacy = WebArticleImporter.stableArticleId(url)
        val canonical = WebArticleImporter.sha256("canonical-guid")
        val local = article(legacy, url=url, rssSourceUrl="https://example.com/feed", favoriteSaved=true)
        val remote = local.copy(sourceDeviceId="watch", deleted=true, deletedAt=100)
        val dao=FakePhoneArticleDao().apply { items=listOf(local) }; val meta=ConflictMetaDao()
        fun repo()=PhoneCompanionRepository(FakePhoneSavedItemDao(),dao,FakePhoneRssSourceDao(),"test-phone",appMetaDao=meta)
        repo().prepareDeleteConflictResolutions(listOf(remote.toManifestEntry()),mapOf(legacy to PhoneSyncConflictResolution.KEEP_PHONE))
        val remoteCanonical=remote.toManifestEntry().copy(articleId=canonical,legacyArticleIds=listOf(legacy),metadataHash="new-encoder")
        repo().acceptBluetoothAliases(listOf(remoteCanonical))
        assertEquals(canonical,dao.items.single().articleId)
        assertTrue(repo().findDeleteConflicts(listOf(remoteCanonical)).isEmpty())
        val before=dao.items.single()
        assertEquals(setOf(canonical),repo().prepareDeleteConflictResolutions(listOf(remoteCanonical),emptyMap()).outgoingArticleIds)
        assertEquals(before,dao.items.single())
    }

    @Test fun consumedAliasDecisionsSurviveMetadataRefreshWithoutHidingNewUserOperations() = runBlocking {
        val future=9_000_000_000_000L
        val local=article("multi-alias",favoriteSaved=true,favoriteChangedAt=future)
        val dao=FakePhoneArticleDao().apply{items=listOf(local)};val meta=ConflictMetaDao()
        fun repo()=PhoneCompanionRepository(FakePhoneSavedItemDao(),dao,FakePhoneRssSourceDao(),"test-phone",appMetaDao=meta)
        val first=local.copy(sourceDeviceId="watch",favoriteSaved=false,favoriteChangedAt=100,deleted=true,deletedAt=100,updatedAt=100).toManifestEntry()
        val second=first.copy(deletedAt=200,favoriteChangedAt=200,updatedAt=200)
        for(remote in listOf(first,second))repo().prepareDeleteConflictResolutions(listOf(remote),mapOf(local.articleId to PhoneSyncConflictResolution.KEEP_PHONE))
        val decision=dao.items.single()
        // Body/RSS refresh provenance is not a new save/delete operation.
        dao.items=listOf(decision.copy(sourceDeviceId="watch",updatedAt=future+100,contentHash="refreshed-body"))
        assertTrue(repo().findDeleteConflicts(listOf(first,second)).isEmpty())
        val before=dao.items.single()
        assertEquals(setOf(local.articleId),repo().prepareDeleteConflictResolutions(listOf(first,second),emptyMap()).outgoingArticleIds)
        assertEquals(before,dao.items.single())
        assertEquals(1,repo().findDeleteConflicts(listOf(first.copy(favoriteChangedAt=future+200,deletedAt=future+200))).size)
    }

    @Test fun aliasMigrationNeverReplacesNewerCanonicalDecisionWithOlderLegacyReceipt() = runBlocking {
        val url="https://example.com/receipt-order";val legacy=WebArticleImporter.stableArticleId(url);val canonical=WebArticleImporter.sha256("real-guid")
        val future=9_000_000_000_000L
        val old=article(legacy,url=url,rssSourceUrl="https://example.com/feed",favoriteSaved=true,favoriteChangedAt=future)
        val dao=FakePhoneArticleDao().apply{items=listOf(old)};val meta=ConflictMetaDao()
        fun repo()=PhoneCompanionRepository(FakePhoneSavedItemDao(),dao,FakePhoneRssSourceDao(),"test-phone",appMetaDao=meta)
        val deletion=old.copy(sourceDeviceId="watch",deleted=true,deletedAt=100)
        repo().prepareDeleteConflictResolutions(listOf(deletion.toManifestEntry()),mapOf(legacy to PhoneSyncConflictResolution.KEEP_PHONE))
        val next=old.copy(articleId=canonical,updatedAt=future+100,favoriteChangedAt=future+100)
        dao.items=dao.items+next
        val canonicalDelete=next.copy(sourceDeviceId="watch",deleted=true,deletedAt=future+200)
        repo().prepareDeleteConflictResolutions(listOf(canonicalDelete.toManifestEntry()),mapOf(canonical to PhoneSyncConflictResolution.KEEP_PHONE))
        val receipt=meta.getString("article_conflict_decision_v1:$canonical")
        repo().acceptBluetoothAliases(listOf(canonicalDelete.toManifestEntry().copy(legacyArticleIds=listOf(legacy))))
        assertEquals(receipt,meta.getString("article_conflict_decision_v1:$canonical"))
        assertTrue(repo().findDeleteConflicts(listOf(canonicalDelete.toManifestEntry())).isEmpty())
    }

    @Test fun missingConflictAnswerDoesNotSilentlyChooseLatest() = runBlocking {
        val local = article("unanswered", favoriteSaved = true)
        val dao = FakePhoneArticleDao().apply { items = listOf(local) }
        val remote = local.copy(deleted = true, deletedAt = 200).toManifestEntry()
        val repository = PhoneCompanionRepository(FakePhoneSavedItemDao(), dao, FakePhoneRssSourceDao(), "phone", appMetaDao = ConflictMetaDao())
        assertTrue(runCatching { repository.prepareDeleteConflictResolutions(listOf(remote), emptyMap()) }.isFailure)
        assertEquals(local, dao.items.single())
        assertEquals(1, repository.findDeleteConflicts(listOf(remote)).size)
    }

    private class ConflictMetaDao : AppMetaDao {
        private val values = linkedMapOf<String, String>()
        override suspend fun getString(key: String) = values[key]
        override fun observeString(key: String): Flow<String?> = flowOf(values[key])
        override suspend fun set(entity: AppMetaEntity) { values[entity.key] = entity.value }
        override suspend fun setIfAbsent(entity: AppMetaEntity) { values.putIfAbsent(entity.key, entity.value) }
        override suspend fun getAll() = values.map { AppMetaEntity(it.key, it.value) }
        override suspend fun deleteAll() { values.clear() }
    }

    @Test
    fun prepareDeleteConflictResolutions_keepPhoneSendsFreshLocalVersion() = runBlocking {
        val articleDao = FakePhoneArticleDao()
        val local = article(
            id = "article-1",
            favoriteSaved = true,
            favoriteChangedAt = 10L
        )
        val remoteDeleted = local.copy(
            sourceDeviceId = "watch",
            favoriteSaved = false,
            favoriteChangedAt = 100L,
            deleted = true,
            deletedAt = 100L
        ).toManifestEntry()
        articleDao.items = listOf(local)
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = articleDao,
            rssSourceDao = FakePhoneRssSourceDao(),
            deviceId = "test-phone"
        )

        val plan = repository.prepareDeleteConflictResolutions(
            remoteManifest = listOf(remoteDeleted),
            resolutions = mapOf(local.articleId to PhoneSyncConflictResolution.KEEP_PHONE)
        )

        val updated = articleDao.items.single()
        assertEquals(setOf(local.articleId), plan.outgoingArticleIds)
        assertEquals(false, updated.deleted)
        assertTrue(updated.updatedAt > remoteDeleted.deletedAt)
        assertEquals("test-phone", updated.sourceDeviceId)
    }

    @Test
    fun prepareDeleteConflictResolutions_deleteContentMarksLocalTombstoneForSync() = runBlocking {
        val articleDao = FakePhoneArticleDao()
        val local = article(id = "article-1", favoriteSaved = true, favoriteChangedAt = 10L)
        val remoteDeleted = local.copy(
            sourceDeviceId = "watch",
            favoriteSaved = false,
            favoriteChangedAt = 100L,
            deleted = true,
            deletedAt = 100L
        ).toManifestEntry()
        articleDao.items = listOf(local)
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = articleDao,
            rssSourceDao = FakePhoneRssSourceDao(),
            deviceId = "test-phone"
        )

        val plan = repository.prepareDeleteConflictResolutions(
            remoteManifest = listOf(remoteDeleted),
            resolutions = mapOf(local.articleId to PhoneSyncConflictResolution.DELETE_CONTENT)
        )

        val updated = articleDao.items.single()
        assertEquals(setOf(local.articleId), plan.outgoingArticleIds)
        assertTrue(updated.deleted)
        assertEquals(false, updated.favoriteSaved)
        assertTrue(updated.deletedAt > remoteDeleted.deletedAt)
    }

    @Test
    fun prepareDeleteConflictResolutions_mergeContentRequestsWatchWhenPhoneDeleted() = runBlocking {
        val articleDao = FakePhoneArticleDao()
        val localDeleted = article(
            id = "article-1",
            deleted = true,
            deletedAt = 100L
        )
        val remoteKept = localDeleted.copy(
            sourceDeviceId = "watch",
            favoriteSaved = true,
            favoriteChangedAt = 50L,
            deleted = false,
            deletedAt = 0L
        ).toManifestEntry()
        articleDao.items = listOf(localDeleted)
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = articleDao,
            rssSourceDao = FakePhoneRssSourceDao(),
            deviceId = "test-phone"
        )

        val plan = repository.prepareDeleteConflictResolutions(
            remoteManifest = listOf(remoteKept),
            resolutions = mapOf(localDeleted.articleId to PhoneSyncConflictResolution.MERGE_CONTENT)
        )

        assertEquals(emptySet<String>(), plan.outgoingArticleIds)
        assertEquals(listOf(localDeleted.articleId), plan.forcedRemoteRequests.map { it.articleId })
        assertEquals(
            PhoneSyncConflictResolution.MERGE_CONTENT,
            plan.mergeResolutions[localDeleted.articleId]
        )
    }

    @Test
    fun mergeArticlesFromSync_keepWatchRestoresRemoteAfterLocalDelete() = runBlocking {
        val articleDao = FakePhoneArticleDao()
        val localDeleted = article(
            id = "article-1",
            title = "本地已删",
            deleted = true,
            deletedAt = 100L
        )
        val remoteKept = article(
            id = "article-1",
            title = "手表保留",
            favoriteSaved = true,
            favoriteChangedAt = 50L
        ).copy(sourceDeviceId = "watch")
        articleDao.items = listOf(localDeleted)
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = articleDao,
            rssSourceDao = FakePhoneRssSourceDao(),
            deviceId = "test-phone"
        )

        repository.mergeArticlesFromSync(
            incoming = listOf(remoteKept),
            conflictResolutions = mapOf(localDeleted.articleId to PhoneSyncConflictResolution.KEEP_WATCH)
        )

        val updated = articleDao.items.single()
        assertEquals(false, updated.deleted)
        assertEquals("手表保留", updated.title)
        assertTrue(updated.favoriteSaved)
        assertEquals(0L, updated.deletedAt)
    }

    @Test
    fun mergeArticlesFromSync_stateOnlyPayloadPreservesLocalBody() = runBlocking {
        val articleDao = FakePhoneArticleDao()
        val local = article(
            id = "article-1",
            contentText = "本地完整正文",
            favoriteSaved = false,
            favoriteChangedAt = 10L
        ).copy(
            syncBodyHash = "local-body",
            syncBodyByteCount = 18L,
            syncChunkSize = 4096,
            syncChunkHashesJson = """["local-chunk"]""",
            syncMetadataHash = "local-metadata"
        )
        val remoteState = local.copy(
            sourceDeviceId = "watch",
            contentHtml = null,
            contentText = "",
            favoriteSaved = true,
            favoriteChangedAt = 20L,
            syncBodyHash = "",
            syncBodyByteCount = 0L,
            syncChunkSize = 0,
            syncChunkHashesJson = "",
            syncMetadataHash = ""
        )
        articleDao.items = listOf(local)
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = articleDao,
            rssSourceDao = FakePhoneRssSourceDao(),
            deviceId = "test-phone"
        )

        repository.mergeArticlesFromSync(listOf(remoteState))

        val updated = articleDao.items.single()
        val actualMetadata = ArticleSyncBody.metadataFor(updated)
        assertEquals("本地完整正文", updated.contentText)
        assertEquals(actualMetadata.bodyHash, updated.syncBodyHash)
        assertEquals(actualMetadata.metadataHash, updated.syncMetadataHash)
        assertTrue(updated.favoriteSaved)
    }

    @Test
    fun authoritativeSnapshot_remoteWinsAndTargetOnlyArticleBecomesTombstone() = runBlocking {
        val articleDao = FakePhoneArticleDao()
        val local = article(id = "keep", title = "手机较新标题").copy(updatedAt = 200L)
        val targetOnly = article(id = "remove", title = "仅手机存在")
        articleDao.items = listOf(local, targetOnly)
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = articleDao,
            rssSourceDao = FakePhoneRssSourceDao(),
            deviceId = "test-phone"
        )

        repository.mergeArticlesFromSync(
            incoming = listOf(local.copy(title = "手表权威标题", updatedAt = 100L)),
            authoritative = true
        )
        repository.finalizeAuthoritativeLibrarySnapshot(
            retainedArticleIds = listOf("keep"),
            retainedSourceUrls = emptyList()
        )

        assertEquals(listOf("keep"), articleDao.items.filterNot { it.deleted }.map { it.articleId })
        assertTrue(articleDao.items.single { it.articleId == "remove" }.deleted)
        assertTrue(articleDao.items.single { it.articleId == "remove" }.deletedAt > 0)
        assertEquals("手表权威标题", articleDao.items.single { it.articleId == "keep" }.title)
    }

    @Test
    fun mergeChunkedArticlesFromSync_keepPhoneRecomputesMetadataForPreservedLocalBody() = runBlocking {
        val articleDao = FakePhoneArticleDao()
        val local = article(
            id = "article-1",
            title = "手机正文",
            contentText = "手机保留正文",
            favoriteSaved = true,
            favoriteChangedAt = 10L
        ).copy(
            syncBodyHash = "local-body",
            syncBodyByteCount = 123L,
            syncChunkSize = 456,
            syncChunkHashesJson = """["local-chunk"]""",
            syncMetadataHash = "local-metadata"
        )
        val remote = article(
            id = "article-1",
            title = "手表正文",
            contentText = "手表远端正文",
            favoriteSaved = true,
            favoriteChangedAt = 50L
        ).copy(sourceDeviceId = "watch")
        val remoteMetadata = ArticleSyncBody.metadataFor(remote)
        val canonicalPayload = ArticleSyncBody.payloadForRequest(
            article = remote,
            request = ArticleBodyRequest(
                articleId = remote.articleId,
                bodyHash = remoteMetadata.bodyHash,
                chunkIndexes = remoteMetadata.chunkHashes.indices.toList()
            ),
            cachedMetadata = remoteMetadata
        )
        val wireBytes = canonicalPayload.chunks.single().bytes.copyOf().also { bytes ->
            bytes[4] = 1 // Valid alternate GZIP header; decoded article semantics are unchanged.
        }
        val wireHash = sha256(wireBytes)
        val remotePayload = canonicalPayload.copy(
            bodyHash = wireHash,
            bodyByteCount = wireBytes.size.toLong(),
            chunkHashes = listOf(wireHash),
            chunks = listOf(ArticleBodyChunk(index = 0, hash = wireHash, bytes = wireBytes))
        )
        articleDao.items = listOf(local)
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = articleDao,
            rssSourceDao = FakePhoneRssSourceDao(),
            deviceId = "test-phone"
        )

        repository.mergeChunkedArticlesFromSync(
            incoming = listOf(remotePayload),
            conflictResolutions = mapOf(local.articleId to PhoneSyncConflictResolution.KEEP_PHONE)
        )

        val updated = articleDao.items.single()
        val expectedMetadata = ArticleSyncBody.metadataFor(updated)
        assertEquals("手机保留正文", updated.contentText)
        assertEquals(expectedMetadata.bodyHash, updated.syncBodyHash)
        assertEquals(expectedMetadata.bodyByteCount, updated.syncBodyByteCount)
        assertEquals(expectedMetadata.chunkSize, updated.syncChunkSize)
        assertEquals(JSONArray(expectedMetadata.chunkHashes).toString(), updated.syncChunkHashesJson)
        assertEquals(expectedMetadata.metadataHash, updated.syncMetadataHash)
    }

    @Test
    fun mergeChunkedArticlesFromSync_missingExternalBodyUsesCompleteRemoteBody() = runBlocking {
        val articleDao = FakePhoneArticleDao()
        val contentStore = FakeArticleContentStore()
        val local = article(
            id = "article-1",
            url = ImportedContentIds.txtArticleUrl("article-1"),
            contentText = contentStore.markerFor("missing-text"),
            updatedAt = 10L
        ).copy(excerpt = "不能冒充正文的摘要")
        val remote = article(
            id = "article-1",
            url = local.url,
            contentHtml = "<article>手表完整正文</article>",
            contentText = "手表完整正文",
            updatedAt = 20L
        ).copy(sourceDeviceId = "watch")
        val remoteMetadata = ArticleSyncBody.metadataFor(remote)
        val canonicalPayload = ArticleSyncBody.payloadForRequest(
            article = remote,
            request = ArticleBodyRequest(
                articleId = remote.articleId,
                bodyHash = "",
                chunkIndexes = remoteMetadata.chunkHashes.indices.toList()
            ),
            cachedMetadata = remoteMetadata
        )
        val wireBytes = canonicalPayload.chunks.single().bytes.copyOf().also { bytes ->
            bytes[4] = 1 // Valid alternate GZIP header; decoded article semantics are unchanged.
        }
        val wireHash = sha256(wireBytes)
        val remotePayload = canonicalPayload.copy(
            bodyHash = wireHash,
            bodyByteCount = wireBytes.size.toLong(),
            chunkHashes = listOf(wireHash),
            chunks = listOf(ArticleBodyChunk(index = 0, hash = wireHash, bytes = wireBytes))
        )
        articleDao.items = listOf(local)
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = articleDao,
            rssSourceDao = FakePhoneRssSourceDao(),
            deviceId = "test-phone",
            articleContentStore = contentStore
        )

        repository.mergeChunkedArticlesFromSync(listOf(remotePayload))

        val updated = repository.getArticlesForSync().single()
        assertEquals("<article>手表完整正文</article>", updated.contentHtml)
        assertEquals("手表完整正文", updated.contentText)
        assertNotEquals(remotePayload.bodyHash, updated.syncBodyHash)
        assertEquals(remoteMetadata.bodyHash, updated.syncBodyHash)
        assertEquals(remoteMetadata.bodyByteCount, updated.syncBodyByteCount)
        assertEquals(remoteMetadata.chunkSize, updated.syncChunkSize)
        assertEquals(JSONArray(remoteMetadata.chunkHashes).toString(), updated.syncChunkHashesJson)
    }

    @Test
    fun mergeChunkedArticlesFromSync_missingExternalBodyRejectsMetadataOnlyWithoutChangingDatabase() = runBlocking {
        val articleDao = FakePhoneArticleDao()
        val contentStore = FakeArticleContentStore()
        val local = article(
            id = "article-1",
            url = ImportedContentIds.txtArticleUrl("article-1"),
            contentText = contentStore.markerFor("missing-text"),
            updatedAt = 10L
        ).copy(
            excerpt = "不能冒充正文的摘要",
            syncBodyHash = "cached-body",
            syncBodyByteCount = 321L,
            syncChunkSize = 123,
            syncChunkHashesJson = """["cached-chunk"]""",
            syncMetadataHash = "cached-metadata"
        )
        val remote = local.copy(
            sourceDeviceId = "watch",
            contentText = local.excerpt,
            updatedAt = 20L
        )
        val remoteMetadata = ArticleSyncBody.metadataFor(remote)
        val remotePayload = ArticleSyncBody.payloadForRequest(
            article = remote,
            request = ArticleBodyRequest(
                articleId = remote.articleId,
                bodyHash = remoteMetadata.bodyHash,
                chunkIndexes = emptyList(),
                metadataOnly = true
            ),
            cachedMetadata = remoteMetadata
        )
        articleDao.items = listOf(local)
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = articleDao,
            rssSourceDao = FakePhoneRssSourceDao(),
            deviceId = "test-phone",
            articleContentStore = contentStore
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                repository.mergeChunkedArticlesFromSync(listOf(remotePayload))
            }
        }

        assertEquals(listOf(local), articleDao.items)
    }

    @Test
    fun mergeChunkedArticlesFromSync_metadataOnlyPreservesBodyAndRecomputesCompleteMetadata() = runBlocking {
        val articleDao = FakePhoneArticleDao()
        val local = article(
            id = "article-1",
            title = "手机旧标题",
            contentHtml = "<article>手机完整正文</article>",
            contentText = "手机完整正文",
            updatedAt = 10L
        ).copy(
            syncBodyHash = "stale-body",
            syncBodyByteCount = 0L,
            syncChunkSize = 0,
            syncChunkHashesJson = "",
            syncMetadataHash = "stale-metadata"
        )
        val remote = local.copy(
            sourceDeviceId = "watch",
            title = "手表新标题",
            updatedAt = 20L,
            contentHtml = null,
            contentText = ""
        )
        val localBodyMetadata = ArticleSyncBody.metadataFor(local)
        val remotePayload = ChunkedArticlePayload(
            article = remote,
            bodyHash = localBodyMetadata.bodyHash,
            bodyByteCount = localBodyMetadata.bodyByteCount,
            chunkSize = localBodyMetadata.chunkSize,
            chunkHashes = localBodyMetadata.chunkHashes,
            chunks = emptyList(),
            metadataOnly = true
        )
        articleDao.items = listOf(local)
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = articleDao,
            rssSourceDao = FakePhoneRssSourceDao(),
            deviceId = "test-phone"
        )

        repository.mergeChunkedArticlesFromSync(listOf(remotePayload))

        val updated = articleDao.items.single()
        val expectedMetadata = ArticleSyncBody.metadataFor(updated.copy(
            syncBodyHash = "",
            syncBodyByteCount = 0L,
            syncChunkSize = 0,
            syncChunkHashesJson = "",
            syncMetadataHash = ""
        ))
        assertEquals("手表新标题", updated.title)
        assertEquals("<article>手机完整正文</article>", updated.contentHtml)
        assertEquals("手机完整正文", updated.contentText)
        assertEquals(expectedMetadata.bodyHash, updated.syncBodyHash)
        assertEquals(expectedMetadata.bodyByteCount, updated.syncBodyByteCount)
        assertEquals(expectedMetadata.chunkSize, updated.syncChunkSize)
        assertEquals(JSONArray(expectedMetadata.chunkHashes).toString(), updated.syncChunkHashesJson)
        assertEquals(expectedMetadata.metadataHash, updated.syncMetadataHash)
    }

    @Test
    fun mergeChunkedDeletedMetadataOnly_preservesVerifiedLocalBodyMetadata() = runBlocking {
        val articleDao = FakePhoneArticleDao()
        val local = article(
            id = "deleted-remote",
            contentText = "手机保留正文",
            favoriteSaved = true,
            favoriteChangedAt = 10L,
            updatedAt = 10L
        )
        val remoteTombstone = local.copy(
            sourceDeviceId = "watch",
            contentText = "",
            favoriteSaved = false,
            favoriteChangedAt = 30L,
            deleted = true,
            deletedAt = 30L,
            updatedAt = 30L
        )
        val localMetadata = ArticleSyncBody.metadataFor(local)
        val payload = ChunkedArticlePayload(
            article = remoteTombstone,
            bodyHash = localMetadata.bodyHash,
            bodyByteCount = localMetadata.bodyByteCount,
            chunkSize = localMetadata.chunkSize,
            chunkHashes = localMetadata.chunkHashes,
            chunks = emptyList(),
            metadataOnly = true
        )
        articleDao.items = listOf(local)
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = articleDao,
            rssSourceDao = FakePhoneRssSourceDao(),
            deviceId = "test-phone"
        )

        repository.mergeChunkedArticlesFromSync(listOf(payload))

        val stored = articleDao.items.single()
        val actual = ArticleSyncBody.metadataFor(stored)
        assertTrue(stored.deleted)
        assertEquals("手机保留正文", stored.contentText)
        assertEquals(actual.bodyHash, stored.syncBodyHash)
        assertEquals(actual.bodyByteCount, stored.syncBodyByteCount)
        assertEquals(actual.chunkSize, stored.syncChunkSize)
        assertEquals(JSONArray(actual.chunkHashes).toString(), stored.syncChunkHashesJson)
        assertEquals(ArticleSyncBody.metadataHashFor(stored), stored.syncMetadataHash)
    }

    @Test
    fun mergeChunkedDeletedMetadataOnly_rejectsMismatchedShapeBeforeWrite() = runBlocking {
        val articleDao = FakePhoneArticleDao()
        val local = article(id = "deleted-invalid", contentText = "手机正文")
        val metadata = ArticleSyncBody.metadataFor(local)
        val remoteTombstone = local.copy(
            sourceDeviceId = "watch",
            contentText = "",
            deleted = true,
            deletedAt = 30L,
            updatedAt = 30L
        )
        val payload = ChunkedArticlePayload(
            article = remoteTombstone,
            bodyHash = metadata.bodyHash,
            bodyByteCount = metadata.bodyByteCount + 1L,
            chunkSize = metadata.chunkSize,
            chunkHashes = metadata.chunkHashes,
            chunks = emptyList(),
            metadataOnly = true
        )
        articleDao.items = listOf(local)
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = articleDao,
            rssSourceDao = FakePhoneRssSourceDao(),
            deviceId = "test-phone"
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.mergeChunkedArticlesFromSync(listOf(payload)) }
        }
        assertEquals(listOf(local), articleDao.items)
    }

    @Test
    fun mergeChunkedDeletedExplicitUnavailable_doesNotManufactureEmptyBodyMetadata() = runBlocking {
        val articleDao = FakePhoneArticleDao()
        val tombstone = article(
            id = "deleted-without-body",
            contentText = "",
            deleted = true,
            deletedAt = 30L,
            updatedAt = 30L
        ).copy(sourceDeviceId = "watch")
        val payload = ChunkedArticlePayload(
            article = tombstone,
            bodyHash = "",
            bodyByteCount = 0L,
            chunkSize = 0,
            chunkHashes = emptyList(),
            chunks = emptyList(),
            metadataOnly = false
        )
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = articleDao,
            rssSourceDao = FakePhoneRssSourceDao(),
            deviceId = "test-phone"
        )

        repository.mergeChunkedArticlesFromSync(listOf(payload))

        val stored = articleDao.items.single()
        assertTrue(stored.deleted)
        assertEquals("", stored.syncBodyHash)
        assertEquals(0L, stored.syncBodyByteCount)
        assertEquals(0, stored.syncChunkSize)
        assertEquals("", stored.syncChunkHashesJson)
        assertEquals(ArticleSyncBody.metadataHashFor(stored), stored.syncMetadataHash)
    }

    @Test
    fun replaceSavedItems_preservesExistingCompleteExternalBodyAndSyncMetadata() = runBlocking {
        val link = "https://example.com/post"
        val articleId = WebArticleImporter.stableArticleId(link)
        val contentStore = FakeArticleContentStore()
        val full = article(
            id = articleId,
            url = link,
            title = "已有标题",
            contentHtml = "<article>已有完整正文</article>",
            contentText = "已有完整正文",
            updatedAt = 10L
        ).copy(contentHash = "complete-content-hash")
        val metadata = ArticleSyncBody.metadataFor(full)
        val htmlMarker = contentStore.storeText("$articleId-html", full.contentHtml.orEmpty())
        val textMarker = contentStore.storeText("$articleId-text", full.contentText)
        val stored = full.copy(
            contentHtml = htmlMarker,
            contentText = textMarker,
            syncBodyHash = metadata.bodyHash,
            syncBodyByteCount = metadata.bodyByteCount,
            syncChunkSize = metadata.chunkSize,
            syncChunkHashesJson = JSONArray(metadata.chunkHashes).toString(),
            syncMetadataHash = metadata.metadataHash
        )
        val articleDao = FakePhoneArticleDao().apply { items = listOf(stored) }
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = articleDao,
            rssSourceDao = FakePhoneRssSourceDao(),
            deviceId = "test-phone",
            articleContentStore = contentStore
        )

        repository.replaceSavedItems(
            PhoneSavedItemType.FAVORITE,
            JSONArray(
                """
                [{"id":7,"title":"收藏标题","link":"$link","summary":"只有摘要","channelTitle":"示例频道"}]
                """.trimIndent()
            )
        )

        val updated = articleDao.items.single()
        assertEquals(htmlMarker, updated.contentHtml)
        assertEquals(textMarker, updated.contentText)
        assertEquals("complete-content-hash", updated.contentHash)
        assertEquals(metadata.bodyHash, updated.syncBodyHash)
        assertEquals(metadata.bodyByteCount, updated.syncBodyByteCount)
        assertEquals(metadata.chunkSize, updated.syncChunkSize)
        assertEquals(JSONArray(metadata.chunkHashes).toString(), updated.syncChunkHashesJson)
        assertTrue(updated.favoriteSaved)
    }

    @Test
    fun mergeArticlesFromSync_clearsDeletedAtWhenLatestStateRestoresArticle() = runBlocking {
        val articleDao = FakePhoneArticleDao()
        val localDeleted = article(
            id = "article-1",
            title = "本地已删",
            deleted = true,
            deletedAt = 100L
        )
        val remoteRestored = article(
            id = "article-1",
            title = "手机恢复",
            independentSaved = true,
            independentChangedAt = 120L,
            deleted = false,
            deletedAt = 100L
        ).copy(sourceDeviceId = "watch")
        articleDao.items = listOf(localDeleted)
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = articleDao,
            rssSourceDao = FakePhoneRssSourceDao(),
            deviceId = "test-phone"
        )

        repository.mergeArticlesFromSync(incoming = listOf(remoteRestored))

        val updated = articleDao.items.single()
        assertEquals(false, updated.deleted)
        assertTrue(updated.independentSaved)
        assertEquals(0L, updated.deletedAt)
    }

    @Test
    fun clearImportedContent_marksOnlyTxtRootArticlesAsDeletedTombstones() = runBlocking {
        val articleDao = FakePhoneArticleDao()
        val txt = article(
            id = "txt",
            url = ImportedContentIds.txtArticleUrl("txt-1"),
            rssSourceUrl = ImportedContentIds.ROOT_SOURCE_URL
        )
        val epub = article(
            id = "epub",
            url = ImportedContentIds.epubChapterUrl("book", 1, "chapter-1"),
            rssSourceUrl = ImportedContentIds.epubSourceUrl("book")
        )
        articleDao.items = listOf(txt, epub)
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = articleDao,
            rssSourceDao = FakePhoneRssSourceDao(),
            deviceId = "test-phone"
        )

        val cleared = repository.clearImportedContent()

        assertEquals(1, cleared)
        val deletedTxt = articleDao.items.first { it.articleId == txt.articleId }
        val keptEpub = articleDao.items.first { it.articleId == epub.articleId }
        assertTrue(deletedTxt.deleted)
        assertEquals(false, keptEpub.deleted)
        assertEquals(
            setOf(txt.articleId, epub.articleId),
            repository.getArticlesForSync().map { it.articleId }.toSet()
        )
    }

    @Test
    fun importedTextRootSource_isSyncedAsRssSourceForChannelOrdering() = runBlocking {
        val importedText = source(
            url = ImportedContentIds.ROOT_SOURCE_URL,
            title = ImportedContentIds.ROOT_SOURCE_TITLE,
            deleted = true,
            updatedAt = 100L
        )
        val regular = source(url = "https://example.com/feed.xml")
        val sourceDao = FakePhoneRssSourceDao().apply {
            sources = listOf(importedText, regular)
        }
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = FakePhoneArticleDao(),
            rssSourceDao = sourceDao,
            deviceId = "test-phone"
        )

        val exported = repository.getRssSourcesForSync()
        val merged = repository.mergeRssSourcesFromSync(
            listOf(importedText.copy(sourceDeviceId = "watch", deleted = false, updatedAt = 200L))
        )

        assertEquals(listOf(importedText.url, regular.url), exported.map { it.url })
        assertEquals(1, merged)
        assertEquals(false, sourceDao.sources.first { it.url == importedText.url }.deleted)
    }

    @Test
    fun importedEpubSources_areSyncedAsRssSources() = runBlocking {
        val importedEpub = source(
            url = ImportedContentIds.epubSourceUrl("book"),
            title = "三体全集",
            deleted = true,
            updatedAt = 100L
        )
        val legacyImportedEpub = source(
            url = "${ImportedContentIds.ROOT_SOURCE_URL}/epub/legacy-book",
            title = "旧版导入书籍",
            deleted = true,
            updatedAt = 100L
        )
        val regular = source(url = "https://example.com/feed.xml")
        val sourceDao = FakePhoneRssSourceDao().apply {
            sources = listOf(importedEpub, legacyImportedEpub, regular)
        }
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = FakePhoneArticleDao(),
            rssSourceDao = sourceDao,
            deviceId = "test-phone"
        )

        val exported = repository.getRssSourcesForSync()
        val merged = repository.mergeRssSourcesFromSync(
            listOf(importedEpub.copy(sourceDeviceId = "watch", deleted = false, updatedAt = 200L))
        )

        assertEquals(
            listOf(importedEpub.url, legacyImportedEpub.url, regular.url),
            exported.map { it.url }
        )
        assertEquals(1, merged)
        assertEquals(false, sourceDao.sources.first { it.url == importedEpub.url }.deleted)
    }

    @Test
    fun repairImportedContentSourceStates_restoresDeletedSourceWithLiveArticles() = runBlocking {
        val sourceUrl = ImportedContentIds.epubSourceUrl("three-body")
        val sourceDao = FakePhoneRssSourceDao().apply {
            sources = listOf(
                source(
                    url = sourceUrl,
                    title = "三体全集",
                    deleted = true,
                    deletedAt = 50L,
                    updatedAt = 50L
                )
            )
        }
        val articleDao = FakePhoneArticleDao().apply {
            items = listOf(
                article(
                    id = "chapter",
                    url = ImportedContentIds.epubChapterUrl("three-body", 1, "chapter"),
                    rssSourceUrl = sourceUrl,
                    importedAt = 120L
                )
            )
        }
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = articleDao,
            rssSourceDao = sourceDao,
            deviceId = "test-phone"
        )

        val repaired = repository.repairImportedContentSourceStates()

        val repairedSource = sourceDao.sources.single()
        assertEquals(1, repaired)
        assertEquals(false, repairedSource.deleted)
        assertEquals(0L, repairedSource.deletedAt)
        assertEquals("test-phone", repairedSource.sourceDeviceId)
        assertEquals(120L, repairedSource.updatedAt)
    }

    @Test
    fun deleteRssSource_marksImportedContentArticlesDeleted() = runBlocking {
        val sourceUrl = ImportedContentIds.epubSourceUrl("three-body")
        val sourceDao = FakePhoneRssSourceDao().apply {
            sources = listOf(source(url = sourceUrl, title = "三体全集"))
        }
        val articleDao = FakePhoneArticleDao().apply {
            items = listOf(
                article(
                    id = "chapter",
                    url = ImportedContentIds.epubChapterUrl("three-body", 1, "chapter"),
                    rssSourceUrl = sourceUrl
                )
            )
        }
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = articleDao,
            rssSourceDao = sourceDao,
            deviceId = "test-phone"
        )

        repository.deleteRssSource(sourceUrl)

        assertEquals(true, articleDao.items.single().deleted)
        assertEquals(true, sourceDao.sources.single().deleted)
    }

    @Test
    fun getArticleManifestsForSync_recomputesStaleBodyMetadata() = runBlocking {
        val sourceUrl = ImportedContentIds.ROOT_SOURCE_URL
        val articleDao = FakePhoneArticleDao().apply {
            items = listOf(
                article(
                    id = "stale",
                    url = ImportedContentIds.txtArticleUrl("stale"),
                    rssSourceUrl = sourceUrl,
                    contentText = "新的正文"
                ).copy(
                    syncBodyHash = "stale-hash",
                    syncBodyByteCount = 18L,
                    syncChunkSize = 131_072,
                    syncChunkHashesJson = """["stale-hash"]""",
                    syncMetadataHash = "stale-metadata"
                )
            )
        }
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = articleDao,
            rssSourceDao = FakePhoneRssSourceDao(),
            deviceId = "test-phone"
        )

        val manifest = repository.getArticleManifestsForSync().single()

        assertEquals(false, manifest.chunkHashes.contains("stale-hash"))
        assertEquals(false, articleDao.items.single().syncChunkHashesJson.contains("stale-hash"))
    }

    @Test
    fun syncExport_reassemblesLargeInlineBodyFromDatabaseChunks() = runBlocking {
        val largeText = buildString {
            repeat(220_000) { index -> append(('a'.code + index % 23).toChar()) }
        }
        val articleDao = FakePhoneArticleDao().apply {
            items = listOf(
                article(
                    id = "large-inline",
                    contentText = largeText,
                    independentSaved = true,
                    independentChangedAt = 10L
                )
            )
        }
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = articleDao,
            rssSourceDao = FakePhoneRssSourceDao(),
            deviceId = "test-phone"
        )

        val manifest = repository.getArticleManifestsForSync().single()
        val outgoing = repository.getArticlesForSync(listOf("large-inline")).single()

        assertEquals(largeText, outgoing.contentText)
        assertTrue(manifest.bodyByteCount > 0L)
        assertEquals(manifest.bodyHash, articleDao.items.single().syncBodyHash)
    }

    @Test
    fun getArticleManifestsForSync_marksMissingExternalBodyUnavailable() = runBlocking {
        val sourceUrl = ImportedContentIds.ROOT_SOURCE_URL
        val articleDao = FakePhoneArticleDao().apply {
            items = listOf(
                article(
                    id = "missing-body",
                    url = ImportedContentIds.txtArticleUrl("missing-body"),
                    rssSourceUrl = sourceUrl,
                    contentText = "fake-local-text:missing-body-text"
                ).copy(
                    syncBodyHash = "cached-body",
                    syncBodyByteCount = 1024L,
                    syncChunkSize = 131_072,
                    syncChunkHashesJson = """["cached-chunk"]""",
                    syncMetadataHash = "cached-metadata"
                )
            )
        }
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = articleDao,
            rssSourceDao = FakePhoneRssSourceDao(),
            deviceId = "test-phone",
            articleContentStore = FakeArticleContentStore()
        )

        val manifest = repository.getArticleManifestsForSync().single()
        val outgoing = repository.getArticlesForSync(listOf("missing-body"))

        assertEquals(false, manifest.bodyAvailable)
        assertEquals("cached-body", manifest.bodyHash)
        assertEquals(emptyList<PhoneArticleEntity>(), outgoing)
        assertEquals("cached-body", articleDao.items.single().syncBodyHash)
    }

    @Test
    fun reorderContentChannels_updatesSourceAndIndependentSortStateForSync() = runBlocking {
        val rss = source(url = "https://example.com/feed.xml", updatedAt = 10L)
        val importedText = source(
            url = ImportedContentIds.ROOT_SOURCE_URL,
            title = ImportedContentIds.ROOT_SOURCE_TITLE,
            updatedAt = 20L
        )
        val sourceDao = FakePhoneRssSourceDao().apply {
            sources = listOf(rss, importedText)
        }
        val articleDao = FakePhoneArticleDao().apply {
            items = listOf(
                article(
                    id = "independent-1",
                    independentSaved = true,
                    independentChangedAt = 5L
                ),
                article(
                    id = "independent-2",
                    independentSaved = true,
                    independentChangedAt = 4L
                )
            )
        }
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = articleDao,
            rssSourceDao = sourceDao,
            deviceId = "test-phone"
        )

        repository.reorderContentChannels(
            sourceUrlsInDisplayOrder = listOf(importedText.url, rss.url),
            independentIndex = 1
        )

        val importedTextUpdated = sourceDao.sources.first { it.url == importedText.url }
        val rssUpdated = sourceDao.sources.first { it.url == rss.url }
        val independentUpdated = articleDao.items
            .filter { it.independentSaved }
            .sortedByDescending { it.independentSortOrder }

        assertTrue(importedTextUpdated.sortOrder > independentUpdated.first().independentSortOrder)
        assertTrue(independentUpdated.first().independentSortOrder > rssUpdated.sortOrder)
        assertTrue(independentUpdated[0].independentSortOrder > independentUpdated[1].independentSortOrder)
        assertEquals(
            setOf(importedText.url, rss.url),
            repository.getRssSourcesForSync().map { it.url }.toSet()
        )
        assertEquals(
            setOf("independent-1", "independent-2"),
            repository.getArticleManifestsForSync().map { it.articleId }.toSet()
        )
    }

    @Test
    fun reorderContentChannels_reordersPinnedSourcesWithinPinnedGroup() = runBlocking {
        val pinnedFirst = source(
            url = "https://example.com/first.xml",
            updatedAt = 100L,
            isPinned = true
        )
        val pinnedSecond = source(
            url = "https://example.com/second.xml",
            updatedAt = 90L,
            isPinned = true
        )
        val sourceDao = FakePhoneRssSourceDao().apply {
            sources = listOf(pinnedFirst, pinnedSecond)
        }
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = FakePhoneArticleDao(),
            rssSourceDao = sourceDao,
            deviceId = "test-phone"
        )

        repository.reorderContentChannels(
            sourceUrlsInDisplayOrder = listOf(pinnedSecond.url, pinnedFirst.url),
            independentIndex = null
        )

        val updatedFirst = sourceDao.sources.first { it.url == pinnedFirst.url }
        val updatedSecond = sourceDao.sources.first { it.url == pinnedSecond.url }
        assertTrue(updatedSecond.sortOrder > updatedFirst.sortOrder)
        assertEquals(true, updatedFirst.isPinned)
        assertEquals(true, updatedSecond.isPinned)
    }

    @Test
    fun reorderContentChannels_ignoresMixedPinnedAndNormalSources() = runBlocking {
        val pinned = source(
            url = "https://example.com/pinned.xml",
            updatedAt = 100L,
            isPinned = true
        )
        val normal = source(
            url = "https://example.com/normal.xml",
            updatedAt = 10L
        )
        val sourceDao = FakePhoneRssSourceDao().apply {
            sources = listOf(pinned, normal)
        }
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = FakePhoneArticleDao(),
            rssSourceDao = sourceDao,
            deviceId = "test-phone"
        )

        repository.reorderContentChannels(
            sourceUrlsInDisplayOrder = listOf(normal.url, pinned.url),
            independentIndex = null
        )

        assertEquals(pinned.sortOrder, sourceDao.sources.first { it.url == pinned.url }.sortOrder)
        assertEquals(normal.sortOrder, sourceDao.sources.first { it.url == normal.url }.sortOrder)
    }

    @Test
    fun reorderContentChannels_ignoresIndependentArticleInPinnedGroup() = runBlocking {
        val pinnedFirst = source(
            url = "https://example.com/first.xml",
            updatedAt = 100L,
            isPinned = true
        )
        val pinnedSecond = source(
            url = "https://example.com/second.xml",
            updatedAt = 90L,
            isPinned = true
        )
        val sourceDao = FakePhoneRssSourceDao().apply {
            sources = listOf(pinnedFirst, pinnedSecond)
        }
        val articleDao = FakePhoneArticleDao().apply {
            items = listOf(
                article(
                    id = "independent",
                    independentSaved = true,
                    independentChangedAt = 50L
                )
            )
        }
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = articleDao,
            rssSourceDao = sourceDao,
            deviceId = "test-phone"
        )

        repository.reorderContentChannels(
            sourceUrlsInDisplayOrder = listOf(pinnedFirst.url, pinnedSecond.url),
            independentIndex = 1
        )

        assertEquals(pinnedFirst.sortOrder, sourceDao.sources.first { it.url == pinnedFirst.url }.sortOrder)
        assertEquals(pinnedSecond.sortOrder, sourceDao.sources.first { it.url == pinnedSecond.url }.sortOrder)
        assertEquals(50L, articleDao.items.single().independentSortOrder)
    }

    @Test
    fun repairImportedContentSourceStates_createsMissingImportedTextSource() = runBlocking {
        val sourceDao = FakePhoneRssSourceDao()
        val articleDao = FakePhoneArticleDao().apply {
            items = listOf(
                article(
                    id = "txt",
                    url = ImportedContentIds.txtArticleUrl("txt"),
                    importedAt = 42L
                )
            )
        }
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = articleDao,
            rssSourceDao = sourceDao,
            deviceId = "test-phone"
        )

        val repaired = repository.repairImportedContentSourceStates()

        val source = sourceDao.sources.single()
        assertEquals(1, repaired)
        assertEquals(ImportedContentIds.ROOT_SOURCE_URL, source.url)
        assertEquals(ImportedContentIds.ROOT_SOURCE_TITLE, source.title)
        assertEquals(false, source.deleted)
        assertTrue(source.sortOrder >= 42L)
    }

    @Test
    fun mergeArticlesFromBackup_usesLatestPerFieldAndKeepsMaximumProgress() = runBlocking {
        val articleDao = FakePhoneArticleDao().apply {
            items = listOf(
                article(
                    id = "same",
                    title = "当前标题",
                    updatedAt = 100L,
                    favoriteSaved = false,
                    favoriteChangedAt = 300L,
                    readingProgress = 0.8f
                ),
                article(
                    id = "tie",
                    title = "当前同时间标题",
                    updatedAt = 500L
                )
            )
        }
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = articleDao,
            rssSourceDao = FakePhoneRssSourceDao(),
            deviceId = "current-device"
        )

        val changed = repository.mergeArticlesFromBackup(
            listOf(
                article(
                    id = "same",
                    title = "备份较新标题",
                    updatedAt = 200L,
                    favoriteSaved = true,
                    favoriteChangedAt = 250L,
                    watchLaterSaved = true,
                    watchLaterChangedAt = 400L,
                    readingProgress = 0.4f
                ),
                article(
                    id = "tie",
                    title = "备份同时间标题",
                    updatedAt = 500L
                ),
                article(id = "missing", title = "新增文章", updatedAt = 50L)
            )
        )

        assertEquals(2, changed)
        val same = articleDao.items.single { it.articleId == "same" }
        assertEquals("备份较新标题", same.title)
        assertEquals(false, same.favoriteSaved)
        assertEquals(true, same.watchLaterSaved)
        assertEquals(0.8f, same.readingProgress)
        assertEquals("current-device", same.sourceDeviceId)
        assertEquals("当前同时间标题", articleDao.items.single { it.articleId == "tie" }.title)
        assertEquals("新增文章", articleDao.items.single { it.articleId == "missing" }.title)
    }

    @Test
    fun getArticlesForBackup_hydratesExternalTextAndRejectsMissingBody() {
        val store = FakeArticleContentStore()
        val marker = store.storeText("external-text", "完整外置正文")
        val articleDao = FakePhoneArticleDao().apply {
            items = listOf(article(id = "external", contentText = marker))
        }
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = articleDao,
            rssSourceDao = FakePhoneRssSourceDao(),
            deviceId = "current-device",
            articleContentStore = store
        )

        val hydrated = runBlocking { repository.getArticlesForBackup().single() }
        assertEquals("完整外置正文", hydrated.contentText)

        articleDao.items = listOf(article(id = "missing", contentText = store.markerFor("missing")))
        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { repository.getArticlesForBackup() }
        }
        assertTrue(error.message.orEmpty().contains("正文文件缺失"))
    }

    @Test
    fun replaceArticlesFromBackup_removesCurrentOnlyRows() = runBlocking {
        val articleDao = FakePhoneArticleDao().apply {
            items = listOf(article(id = "old"))
        }
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = articleDao,
            rssSourceDao = FakePhoneRssSourceDao(),
            deviceId = "current-device"
        )

        val replaced = repository.replaceArticlesFromBackup(
            listOf(article(id = "restored", readingProgress = 0.6f))
        )

        assertEquals(1, replaced)
        assertEquals(listOf("restored"), articleDao.items.map { it.articleId })
        assertEquals("current-device", articleDao.items.single().sourceDeviceId)
        assertEquals(0.6f, articleDao.items.single().readingProgress)
    }

    @Test
    fun channelSettings_persistAndOriginalModeRefreshesReadableBody() = runBlocking {
        val sourceUrl = "https://example.com/feed.xml"
        val articleUrl = "https://example.com/post"
        val sourceDao = FakePhoneRssSourceDao().apply {
            sources = listOf(source(sourceUrl))
        }
        val articleDao = FakePhoneArticleDao()
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = articleDao,
            rssSourceDao = sourceDao,
            deviceId = "current-device",
            webArticleImporter = {
                ImportedWebArticle(
                    articleId = "post",
                    url = articleUrl,
                    title = "原文标题",
                    siteName = "example.com",
                    excerpt = "原文摘要",
                    contentHtml = "<article>完整原文</article>",
                    contentText = "完整原文",
                    imageUrl = "https://example.com/cover.jpg",
                    contentHash = "original-hash"
                )
            },
            rssSourceImporter = {
                ImportedRssSource(
                    url = sourceUrl,
                    title = "示例源",
                    description = "",
                    siteUrl = "https://example.com",
                    imageUrl = null,
                    items = listOf(
                        ImportedRssItem(
                            url = articleUrl,
                            title = "Feed标题",
                            excerpt = "Feed摘要",
                            contentHtml = "<p>Feed正文</p>",
                            contentText = "Feed正文",
                            imageUrl = null,
                            guid = "post"
                        )
                    )
                )
            }
        )

        repository.setRssSourceOriginalContentEnabled(sourceUrl, true)
        repository.setRssSourceContinuePlaybackInBackground(sourceUrl, true)
        repository.refreshRssSource(sourceUrl)

        val updatedSource = sourceDao.sources.single()
        assertEquals(true, updatedSource.useOriginalContent)
        assertEquals(true, updatedSource.continuePlaybackInBackground)
        val updatedArticle = articleDao.items.single()
        assertEquals("Feed标题", updatedArticle.title)
        assertEquals("完整原文", updatedArticle.contentText)
        assertEquals("<article>完整原文</article>", updatedArticle.contentHtml)
        assertEquals("https://example.com/cover.jpg", updatedArticle.imageUrl)
    }

    @Test
    fun mergeRssSourcesFromLegacyPeer_preservesLocalChannelSettings() = runBlocking {
        val sourceUrl = "https://example.com/feed.xml"
        val sourceDao = FakePhoneRssSourceDao().apply {
            sources = listOf(
                source(
                    url = sourceUrl,
                    updatedAt = 10L,
                    useOriginalContent = true,
                    continuePlaybackInBackground = true
                )
            )
        }
        val repository = PhoneCompanionRepository(
            savedItemDao = FakePhoneSavedItemDao(),
            articleDao = FakePhoneArticleDao(),
            rssSourceDao = sourceDao,
            deviceId = "current-device"
        )
        val legacyRemote = source(
            url = sourceUrl,
            title = "手表更新的标题",
            updatedAt = 20L
        ).copy(sourceDeviceId = "watch")

        repository.mergeRssSourcesFromSync(listOf(legacyRemote))

        val merged = sourceDao.sources.single()
        assertEquals("手表更新的标题", merged.title)
        assertEquals(true, merged.useOriginalContent)
        assertEquals(true, merged.continuePlaybackInBackground)
    }

    @Test
    fun encryptedUnknownTombstoneThenOlderLegacyLiveDoesNotResurrect() = runBlocking {
        val sourceUrl = "https://example.com/feed"
        val wireValue = JSONObject().put("articleId", "deleted-first").put("url", "https://example.com/a")
            .put("rssSourceUrl", sourceUrl).put("title", "deleted").put("deleted", true)
            .put("deletedAt", 100L).put("updatedAt", 100L).put("sourceDeviceId", "mini")
        val key = ByteArray(32) { it.toByte() }; val owner = "owner"
        val id = PhoneCloudRecordCodec.id(key, owner, "article", wireValue)
        val decrypted = PhoneCloudRecordCodec.decrypt(key, owner, id, PhoneCloudRecordCodec.encrypt(key, owner, id, 1, "article", wireValue)).second
        // Same no-body stub constructor used by WatchRssBackupService.applyCloudRecord.
        val unknownStub = LibrarySyncPayload.parseArticles(JSONObject().put("articles", JSONArray().put(decrypted))).single()
        val dao = FakePhoneArticleDao()
        val repository = PhoneCompanionRepository(savedItemDao=FakePhoneSavedItemDao(),articleDao=dao,rssSourceDao=FakePhoneRssSourceDao(),deviceId="phone")
        repository.mergeArticlesFromSync(listOf(unknownStub))
        assertTrue(dao.items.single().deleted)
        assertEquals("", dao.items.single().contentText)
        assertEquals(100L, dao.items.single().deletedAt)
        val olderLegacy = article(id="deleted-first", url="https://example.com/a", rssSourceUrl=sourceUrl,
            contentText="older body", favoriteSaved=true, favoriteChangedAt=50L, updatedAt=50L)
        repository.mergeArticlesFromSync(listOf(olderLegacy))
        assertTrue(dao.items.single().deleted)
        assertEquals(100L, dao.items.single().deletedAt)
        assertTrue(dao.items.filterNot { it.deleted }.isEmpty())
        val exported = JSONObject(CloudRssStateCodec.encode(dao.items, 0).toString(Charsets.UTF_8)).getJSONArray("articles").getJSONObject(0)
        assertTrue(exported.getBoolean("deleted"));assertEquals(100L,exported.getLong("deletedAt"))
    }

    @Test
    fun encryptedSourceSettingsApplyExportAndLegacyOmissionPreserveTrue() = runBlocking {
        val sourceUrl="https://example.com/flags"
        val wire=JSONObject().put("url",sourceUrl).put("title","settings").put("updatedAt",100L)
            .put("useOriginalContent",true).put("continuePlaybackInBackground",true)
        val key=ByteArray(32) { it.toByte() };val owner="owner"
        val id=PhoneCloudRecordCodec.id(key,owner,"source",wire)
        val decrypted=PhoneCloudRecordCodec.decrypt(key,owner,id,PhoneCloudRecordCodec.encrypt(key,owner,id,1,"source",wire)).second
        fun state(value:JSONObject)=JSONObject().put("format","watchrss-rss-state").put("version",1).put("articles",JSONArray()).put("sources",JSONArray().put(value)).toString().toByteArray()
        val incoming=CloudRssStateCodec.decodeSources(state(decrypted)).single()
        assertTrue(incoming.syncedSettingsIncluded)
        val sourceDao=FakePhoneRssSourceDao()
        val repository=PhoneCompanionRepository(savedItemDao=FakePhoneSavedItemDao(),articleDao=FakePhoneArticleDao(),rssSourceDao=sourceDao,deviceId="phone")
        repository.mergeRssSourcesFromSync(listOf(incoming))
        val olderClientWire=JSONObject().put("url",sourceUrl).put("title","legacy metadata").put("updatedAt",200L)
        val legacy=CloudRssStateCodec.decodeSources(state(olderClientWire)).single()
        assertFalse(legacy.syncedSettingsIncluded)
        repository.mergeRssSourcesFromSync(listOf(legacy))
        val exported=JSONObject(CloudRssStateCodec.encode(emptyList(),0,sourceDao.sources).toString(Charsets.UTF_8)).getJSONArray("sources").getJSONObject(0)
        assertTrue(exported.getBoolean("useOriginalContent"));assertTrue(exported.getBoolean("continuePlaybackInBackground"))
        assertEquals("legacy metadata",exported.getString("title"))
    }

    @Test
    fun cloudInventoryRefreshDoesNotResurrectPreviouslyDeletedRecord() = runBlocking {
        val sourceUrl="https://example.com/feed";val articleUrl="https://example.com/deleted"
        val id=WebArticleImporter.stableArticleId(articleUrl)
        val dao=FakePhoneArticleDao().apply { items=listOf(article(id=id,url=articleUrl,rssSourceUrl=sourceUrl,
            contentText="",deleted=true,deletedAt=100L,updatedAt=100L)) }
        val repository=PhoneCompanionRepository(savedItemDao=FakePhoneSavedItemDao(),articleDao=dao,rssSourceDao=FakePhoneRssSourceDao(),deviceId="phone")
        repository.mergeCloudRssInventory(ImportedRssSource(url=sourceUrl,title="Feed",description="",siteUrl=null,imageUrl=null,
            items=listOf(ImportedRssItem(url=articleUrl,title="still in feed",excerpt="",contentHtml=null,contentText="feed body",imageUrl=null,guid=null))))
        assertEquals(1,dao.items.size);assertTrue(dao.items.single().deleted);assertEquals(100L,dao.items.single().deletedAt)
    }

    private fun source(
        url: String,
        title: String = "示例源",
        deleted: Boolean = false,
        deletedAt: Long = 0L,
        updatedAt: Long = 1L,
        isPinned: Boolean = false,
        useOriginalContent: Boolean = false,
        continuePlaybackInBackground: Boolean = false
    ): PhoneRssSourceEntity {
        return PhoneRssSourceEntity(
            url = url,
            sourceDeviceId = "test-phone",
            title = title,
            description = "",
            siteUrl = null,
            imageUrl = null,
            createdAt = 1L,
            updatedAt = updatedAt,
            sortOrder = updatedAt,
            isPinned = isPinned,
            deleted = deleted,
            deletedAt = deletedAt,
            useOriginalContent = useOriginalContent,
            continuePlaybackInBackground = continuePlaybackInBackground
        )
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun article(
        id: String,
        url: String = "https://example.com/$id",
        title: String = id,
        contentHtml: String? = null,
        contentText: String = "正文",
        importedAt: Long = 1L,
        rssSourceUrl: String? = null,
        independentSaved: Boolean = false,
        independentChangedAt: Long = 0L,
        favoriteSaved: Boolean = false,
        favoriteChangedAt: Long = 0L,
        watchLaterSaved: Boolean = false,
        watchLaterChangedAt: Long = 0L,
        deleted: Boolean = false,
        deletedAt: Long = 0L,
        updatedAt: Long = maxOf(independentChangedAt, favoriteChangedAt, watchLaterChangedAt, 1L),
        readingProgress: Float = 0f
    ): PhoneArticleEntity {
        return PhoneArticleEntity(
            articleId = id,
            sourceDeviceId = "test-phone",
            url = url,
            title = title,
            siteName = "example.com",
            excerpt = "",
            contentHtml = contentHtml,
            contentText = contentText,
            imageUrl = null,
            contentHash = "hash-$id",
            importedAt = importedAt,
            updatedAt = updatedAt,
            independentSaved = independentSaved,
            independentChangedAt = independentChangedAt,
            independentSortOrder = independentChangedAt,
            rssSourceUrl = rssSourceUrl,
            rssSourceTitle = rssSourceUrl?.let { "示例源" },
            favoriteSaved = favoriteSaved,
            favoriteChangedAt = favoriteChangedAt,
            favoriteSortOrder = favoriteChangedAt,
            watchLaterSaved = watchLaterSaved,
            watchLaterChangedAt = watchLaterChangedAt,
            watchLaterSortOrder = watchLaterChangedAt,
            deleted = deleted,
            deletedAt = deletedAt,
            readingProgress = readingProgress
        )
    }

    private fun PhoneArticleEntity.toManifestEntry(): ArticleSyncManifestEntry {
        return ArticleSyncManifestEntry(
            articleId = articleId,
            sourceDeviceId = sourceDeviceId,
            contentHash = contentHash,
            updatedAt = updatedAt,
            independentChangedAt = independentChangedAt,
            favoriteChangedAt = favoriteChangedAt,
            watchLaterChangedAt = watchLaterChangedAt,
            deletedAt = deletedAt,
            deleted = deleted
        )
    }

    private class FakePhoneSavedItemDao : PhoneSavedItemDao {
        var items: List<PhoneSavedItemEntity> = emptyList()

        override fun observeByType(type: String): Flow<List<PhoneSavedItemEntity>> = emptyFlow()

        override suspend fun deleteByType(type: String) {
            items = items.filterNot { it.type == type }
        }

        override suspend fun upsertAll(items: List<PhoneSavedItemEntity>) {
            this.items = this.items + items
        }

        override suspend fun getAll(): List<PhoneSavedItemEntity> = items

        override suspend fun deleteAll() {
            items = emptyList()
        }
    }

    private class FakeSyncChangeLogDao(
        private var maxSeq: Long
    ) : SyncChangeLogDao {
        private val changes = mutableListOf<SyncChangeLogEntity>()
        override suspend fun lastConflictResolution(articleId: String) = changes.filter { it.entityId == articleId && it.reason == "conflictResolution" }.maxOfOrNull { it.changedAt }
        override suspend fun insert(change: SyncChangeLogEntity): Long {
            changes += change
            maxSeq += 1L
            return maxSeq
        }

        override suspend fun maxSeq(): Long = maxSeq

        override fun observeMaxSeq(): Flow<Long> = flowOf(maxSeq)

        override suspend fun entityIdsChangedAfter(kind: String, afterSeq: Long): List<String> = emptyList()

        override suspend fun maxChangedAtByEntityIds(
            kind: String,
            entityIds: List<String>
        ): List<SyncChangeLogEntityState> = emptyList()

        override suspend fun deleteAll() {
            changes.clear()
            maxSeq = 0L
        }
    }

    private class FakeSyncPeerStateDao(
        initial: SyncPeerStateEntity? = null
    ) : SyncPeerStateDao {
        private var state = initial

        override suspend fun get(peerDeviceId: String): SyncPeerStateEntity? {
            return state?.takeIf { it.peerDeviceId == peerDeviceId }
        }

        override suspend fun upsert(state: SyncPeerStateEntity) {
            this.state = state
        }

        override suspend fun deleteAll() {
            state = null
        }
    }

    private fun txtImportForTest(
        fileName: String,
        @Suppress("UNUSED_PARAMETER") mimeType: String?,
        bytes: ByteArray
    ): ImportedLocalContent {
        val title = fileName.substringBeforeLast('.', fileName)
        val text = bytes.toString(Charsets.UTF_8)
        return ImportedLocalContent(
            kind = LocalContentImportKind.TXT,
            source = ImportedRssSource(
                url = ImportedContentIds.ROOT_SOURCE_URL,
                title = ImportedContentIds.ROOT_SOURCE_TITLE,
                description = "导入",
                siteUrl = null,
                imageUrl = null,
                items = listOf(
                    ImportedRssItem(
                        url = ImportedContentIds.txtArticleUrl("test-${title.hashCode()}"),
                        title = title,
                        excerpt = text.take(32),
                        contentHtml = null,
                        contentText = text,
                        imageUrl = null,
                        guid = "test-${title.hashCode()}"
                    )
                )
            )
        )
    }

    private class FakePhoneArticleDao : PhoneArticleDao {
        override suspend fun getBodyMetadataById(articleId:String)=getById(articleId)?.copy(contentHtml=null,contentText="")
        override suspend fun getAllBodyMetadata()=items.map { it.copy(contentHtml=null,contentText="") }
        override suspend fun getBodyMetadataBySource(rssSourceUrl:String)=getByRssSourceUrl(rssSourceUrl).map { it.copy(contentHtml=null,contentText="") }
        override fun observeBodyMetadataById(articleId:String):Flow<PhoneArticleEntity?> = emptyFlow()
        override suspend fun privateCloudBodyMetadataPage(after:String,limit:Int)=privateCloudBodyPage(after,limit).map { it.copy(contentHtml=null,contentText="") }
        override suspend fun longCloudExcerptMetadata()=longCloudExcerpts().map { it.copy(contentHtml=null,contentText="") }
        override suspend fun bookMetadata(ids:List<String>)=items.filter{it.articleId in ids}.map{it.copy(contentText="",contentHtml=null)}
        override suspend fun unregisteredBookMetadata()=items.filter{ImportedContentIds.isImportedContentUrl(it.url)}
        override suspend fun projectBookReading(id:String,source:String,read:Boolean,progress:Float,bytes:Long,hash:String,clock:Long,
            favorite:Boolean,favoriteClock:Long,favoriteOrder:Long,later:Boolean,laterClock:Long,laterOrder:Long) {
            items=items.map{if(it.articleId!=id)it else it.copy(sourceDeviceId=source,isRead=read,readingProgress=progress,
                readingPositionBytes=bytes,readingPositionContentHash=hash,readingPositionChangedAt=clock,
                favoriteSaved=favorite,favoriteChangedAt=favoriteClock,favoriteSortOrder=favoriteOrder,
                watchLaterSaved=later,watchLaterChangedAt=laterClock,watchLaterSortOrder=laterOrder,syncMetadataHash="")}
        }
        var items: List<PhoneArticleEntity> = emptyList()
        override suspend fun privateCloudBodyPage(after: String, limit: Int) = items.filter { it.articleId>after && !it.deleted && (ImportedContentIds.isImportedContentUrl(it.url) || ImportedContentIds.isImportedContentUrl(it.rssSourceUrl) || it.rssSourceUrl.isNullOrBlank() || it.favoriteSaved || it.watchLaterSaved) }.sortedBy { it.articleId }.take(limit)
        override suspend fun longCloudExcerpts() = items.filter { it.excerpt.codePointCount(0,it.excerpt.length)>280 }
        override suspend fun compactCloudExcerpt(id: String, hash: String, oldExcerpt: String, oldText: String,
            oldHtml: String?, excerpt: String, text: String, html: String?, clock: Long, device: String): Int {
            val old = items.firstOrNull { it.articleId==id && it.contentHash==hash && it.excerpt==oldExcerpt && it.contentText==oldText && it.contentHtml==oldHtml } ?: return 0
            items=items.map { if(it.articleId==id) old.copy(excerpt=excerpt,contentText=text,contentHtml=html,updatedAt=maxOf(old.updatedAt,clock),sourceDeviceId=device,syncMetadataHash="") else it }
            return 1
        }
        override suspend fun missingPrivateBodyIds(): List<String> = items.filter {
            !it.deleted && it.contentText.isEmpty() && it.contentHtml.isNullOrEmpty() && it.url.startsWith("https://watchrss.local/")
        }.map { it.articleId }.sorted()
        override suspend fun fillMissingCloudBody(id: String, hash: String, text: String, html: String?, expectedText: String): Int {
            val current = items.firstOrNull { it.articleId == id && it.contentHash == hash && !it.deleted && it.contentText == expectedText && it.contentHtml.isNullOrEmpty() } ?: return 0
            items = items.map { if (it.articleId == id) current.copy(contentText = text, contentHtml = html) else it }
            return 1
        }
        override suspend fun cloudRecordPage(after: String, limit: Int): List<PhoneArticleEntity> =
            items.filter { it.articleId > after }.sortedBy { it.articleId }.take(limit).map { it.copy(contentHtml = null, contentText = "") }

        override fun observeRecent(limit: Int): Flow<List<PhoneArticleEntity>> = emptyFlow()

        override fun observeIndependent(): Flow<List<PhoneArticleEntity>> = emptyFlow()

        override fun observeRssArticles(
            importedTextSourceUrl: String,
            importedTextArticlePrefix: String
        ): Flow<List<PhoneArticleEntity>> = emptyFlow()

        override fun observeImportedContentArticles(
            importedTextSourceUrl: String,
            importedTextArticlePrefix: String
        ): Flow<List<PhoneArticleEntity>> = emptyFlow()

        override fun observeFavorites(): Flow<List<PhoneArticleEntity>> = emptyFlow()

        override fun observeWatchLater(): Flow<List<PhoneArticleEntity>> = emptyFlow()

        override suspend fun getById(articleId: String): PhoneArticleEntity? {
            return items.firstOrNull { it.articleId == articleId }
        }

        override suspend fun getByRssSourceUrl(rssSourceUrl: String): List<PhoneArticleEntity> {
            return items.filter { it.rssSourceUrl == rssSourceUrl }
        }

        override fun observeById(articleId: String): Flow<PhoneArticleEntity?> = emptyFlow()

        override suspend fun getAllForSync(): List<PhoneArticleEntity> = items

        override suspend fun getAllIds(): List<String> = items.map { it.articleId }

        override suspend fun getAllMetadataForSync(): List<PhoneArticleEntity> = items

        override suspend fun getMetadataForSync(
            articleIds: List<String>
        ): List<PhoneArticleEntity> = items.filter { it.articleId in articleIds }

        override suspend fun getContentHtmlChunk(articleId: String, startByte: Int, byteCount: Int): ByteArray? =
            items.firstOrNull { it.articleId == articleId }?.contentHtml?.syncChunk(startByte, byteCount)

        override suspend fun getContentTextChunk(articleId: String, startByte: Int, byteCount: Int): ByteArray? =
            items.firstOrNull { it.articleId == articleId }?.contentText?.syncChunk(startByte, byteCount)

        override suspend fun updateSyncMetadata(
            articleId: String,
            bodyHash: String,
            bodyByteCount: Long,
            chunkSize: Int,
            chunkHashesJson: String,
            metadataHash: String
        ) {
            items = items.map { article ->
                if (article.articleId == articleId) {
                    article.copy(
                        syncBodyHash = bodyHash,
                        syncBodyByteCount = bodyByteCount,
                        syncChunkSize = chunkSize,
                        syncChunkHashesJson = chunkHashesJson,
                        syncMetadataHash = metadataHash
                    )
                } else {
                    article
                }
            }
        }

        override suspend fun updateTitle(articleId: String, title: String, updatedAt: Long) {
            items = items.map { article ->
                if (article.articleId == articleId) {
                    article.copy(title = title, updatedAt = updatedAt)
                } else {
                    article
                }
            }
        }

        override suspend fun updateReadingProgress(
            articleId: String,
            progress: Float,
            positionBytes: Long,
            positionContentHash: String,
            positionChangedAt: Long
        ) {
            items = items.map { article ->
                if (article.articleId == articleId) {
                    article.copy(
                        readingProgress = progress,
                        readingPositionBytes = positionBytes,
                        readingPositionContentHash = positionContentHash,
                        readingPositionChangedAt = positionChangedAt
                    )
                } else {
                    article
                }
            }
        }

        override suspend fun upsert(article: PhoneArticleEntity) {
            items = items.filterNot { it.articleId == article.articleId } + article
        }

        override suspend fun upsertAll(articles: List<PhoneArticleEntity>) {
            articles.forEach { upsert(it) }
        }

        override suspend fun deleteByRssSourceUrl(rssSourceUrl: String) {
            items = items.filterNot { it.rssSourceUrl == rssSourceUrl }
        }

        override suspend fun deleteByIds(articleIds: List<String>) {
            items = items.filterNot { it.articleId in articleIds }
        }

        override suspend fun deleteAll() {
            items = emptyList()
        }

        private fun String.syncChunk(startByte: Int, byteCount: Int): ByteArray {
            val bytes = toByteArray(Charsets.UTF_8)
            val start = (startByte - 1).coerceIn(0, bytes.size)
            return bytes.copyOfRange(start, (start + byteCount).coerceAtMost(bytes.size))
        }

    }

    private class FakePhoneRssSourceDao : PhoneRssSourceDao {
        var sources: List<PhoneRssSourceEntity> = emptyList()
        override suspend fun cloudRecordPage(after: String, limit: Int): List<PhoneRssSourceEntity> =
            sources.filter { it.url > after }.sortedBy { it.url }.take(limit)

        override fun observeActive(): Flow<List<PhoneRssSourceEntity>> = emptyFlow()

        override suspend fun getByUrl(url: String): PhoneRssSourceEntity? {
            return sources.firstOrNull { it.url == url }
        }

        override suspend fun getAllForSync(): List<PhoneRssSourceEntity> = sources

        override suspend fun getAllUrls(): List<String> = sources.map { it.url }

        override suspend fun upsert(source: PhoneRssSourceEntity) {
            sources = sources.filterNot { it.url == source.url } + source
        }

        override suspend fun upsertAll(sources: List<PhoneRssSourceEntity>) {
            sources.forEach { upsert(it) }
        }

        override suspend fun deleteByUrls(urls: List<String>) {
            sources = sources.filterNot { it.url in urls }
        }

        override suspend fun deleteAll() {
            sources = emptyList()
        }
    }

    private class FakeArticleContentStore : ArticleContentStore {
        private val texts = mutableMapOf<String, String>()

        override fun markerFor(articleId: String): String = "fake-local-text:$articleId"

        override fun isMarker(value: String): Boolean = value.startsWith("fake-local-text:")

        override fun storeText(articleId: String, text: String): String {
            val marker = markerFor(articleId)
            texts[marker] = text
            return marker
        }

        override fun loadText(marker: String): String? = texts[marker]

        override fun textChunkHandle(marker: String, chunkBytes: Int): StoredTextChunkHandle? {
            val text = texts[marker] ?: return null
            val byteLength = text.toByteArray(Charsets.UTF_8).size.toLong()
            val chunkCount = ((byteLength + chunkBytes - 1L) / chunkBytes)
                .toInt()
                .coerceAtLeast(1)
            return StoredTextChunkHandle(
                marker = marker,
                byteLength = byteLength,
                chunkBytes = chunkBytes,
                chunkCount = chunkCount
            )
        }

        override fun loadTextChunk(marker: String, chunkIndex: Int, chunkBytes: Int): String? {
            if (chunkIndex < 0 || chunkBytes <= 0) return null
            val bytes = texts[marker]?.toByteArray(Charsets.UTF_8) ?: return null
            val nominalStart = chunkIndex * chunkBytes
            if (nominalStart >= bytes.size) return null
            val nominalEnd = (nominalStart + chunkBytes).coerceAtMost(bytes.size)
            val start = bytes.adjustUtf8Boundary(nominalStart)
            val end = bytes.adjustUtf8Boundary(nominalEnd)
            if (end <= start) return ""
            return String(bytes.copyOfRange(start, end), Charsets.UTF_8)
        }

        private fun ByteArray.adjustUtf8Boundary(requested: Int): Int {
            var position = requested.coerceIn(0, size)
            if (position <= 0 || position >= size) return position
            while (position > 0 && (this[position].toInt() and UTF8_CONTINUATION_MASK) == UTF8_CONTINUATION_PREFIX) {
                position -= 1
            }
            return position
        }

        companion object {
            private const val UTF8_CONTINUATION_MASK = 0xC0
            private const val UTF8_CONTINUATION_PREFIX = 0x80
        }
    }
}
