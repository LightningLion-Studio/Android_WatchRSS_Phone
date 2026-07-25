package com.lightningstudio.watchrss.phone.test

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.lightningstudio.watchrss.phone.PhoneCompanionApplication
import com.lightningstudio.watchrss.phone.data.db.PhoneArticleDao
import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
import com.lightningstudio.watchrss.phone.data.db.PhoneRssSourceDao
import com.lightningstudio.watchrss.phone.data.db.PhoneRssSourceEntity
import com.lightningstudio.watchrss.phone.data.db.PhoneSavedItemDao
import com.lightningstudio.watchrss.phone.data.db.PhoneSavedItemEntity
import com.lightningstudio.watchrss.phone.data.model.ImportedContentIds
import com.lightningstudio.watchrss.phone.data.model.PhoneSavedItemType
import kotlinx.coroutines.runBlocking

/**
 * 在真实 App 数据库中插入/清理测试数据。
 *
 * 注意：这里不使用 mock Repository 或 Container，而是直接操作
 * [PhoneCompanionApplication] 的真实 Room DAO，确保截图测试看到的数据
 * 与真实运行环境一致。
 */
object RealDataTestHelper {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val application: PhoneCompanionApplication
        get() = context.applicationContext as PhoneCompanionApplication

    private val articleDao: PhoneArticleDao
        get() = application.container.repository.testArticleDao

    private val rssSourceDao: PhoneRssSourceDao
        get() = application.container.repository.testRssSourceDao

    private val savedItemDao: PhoneSavedItemDao
        get() = application.container.repository.testSavedItemDao

    /**
     * 清空与截图测试相关的数据表。
     */
    fun clearTestData() = runBlocking {
        articleDao.deleteAll()
        rssSourceDao.deleteAll()
        savedItemDao.deleteAll()
    }

    /**
     * 构造一个稳定的 RSS 源，字段使用固定时间戳，避免基线漂移。
     */
    fun sampleRssSource(
        url: String = "https://example.com/feed.xml",
        title: String = "示例 RSS 源",
        description: String = "用于截图测试的示例源",
        isPinned: Boolean = false,
        sortOrder: Long = FIXED_TIMESTAMP
    ): PhoneRssSourceEntity = PhoneRssSourceEntity(
        url = url,
        sourceDeviceId = "screenshot-test-device",
        title = title,
        description = description,
        siteUrl = "https://example.com",
        imageUrl = null,
        createdAt = FIXED_TIMESTAMP,
        updatedAt = FIXED_TIMESTAMP,
        sortOrder = sortOrder,
        isPinned = isPinned,
        deleted = false,
        deletedAt = 0L
    )

    /**
     * 构造一篇稳定的 RSS 文章。
     */
    fun sampleRssArticle(
        articleId: String = "rss-article-1",
        sourceUrl: String = "https://example.com/feed.xml",
        sourceTitle: String = "示例 RSS 源",
        title: String = "示例文章标题",
        url: String = "https://example.com/article-1",
        excerpt: String = "这是用于截图测试的文章摘要。",
        favoriteSaved: Boolean = false,
        watchLaterSaved: Boolean = false,
        sortOrder: Long = FIXED_TIMESTAMP
    ): PhoneArticleEntity = PhoneArticleEntity(
        articleId = articleId,
        sourceDeviceId = "screenshot-test-device",
        url = url,
        title = title,
        siteName = sourceTitle,
        excerpt = excerpt,
        contentHtml = "<p>这是用于截图测试的文章正文。</p>",
        contentText = "这是用于截图测试的文章正文。",
        imageUrl = null,
        contentHash = "screenshot-hash-$articleId",
        importedAt = FIXED_TIMESTAMP,
        updatedAt = FIXED_TIMESTAMP,
        independentSaved = false,
        independentChangedAt = 0L,
        independentSortOrder = 0L,
        rssSourceUrl = sourceUrl,
        rssSourceTitle = sourceTitle,
        favoriteSaved = favoriteSaved,
        favoriteChangedAt = if (favoriteSaved) FIXED_TIMESTAMP else 0L,
        favoriteSortOrder = if (favoriteSaved) sortOrder else 0L,
        watchLaterSaved = watchLaterSaved,
        watchLaterChangedAt = if (watchLaterSaved) FIXED_TIMESTAMP else 0L,
        watchLaterSortOrder = if (watchLaterSaved) sortOrder else 0L,
        deleted = false,
        deletedAt = 0L,
        readingProgress = 0f
    )

    /**
     * 构造一篇稳定的独立文章（网页导入）。
     */
    fun sampleIndependentArticle(
        articleId: String = "independent-article-1",
        title: String = "独立文章示例",
        url: String = "https://example.com/independent-1",
        excerpt: String = "这是通过网页导入的独立文章摘要。"
    ): PhoneArticleEntity = PhoneArticleEntity(
        articleId = articleId,
        sourceDeviceId = "screenshot-test-device",
        url = url,
        title = title,
        siteName = "",
        excerpt = excerpt,
        contentHtml = "<p>这是独立文章正文。</p>",
        contentText = "这是独立文章正文。",
        imageUrl = null,
        contentHash = "screenshot-hash-$articleId",
        importedAt = FIXED_TIMESTAMP,
        updatedAt = FIXED_TIMESTAMP,
        independentSaved = true,
        independentChangedAt = FIXED_TIMESTAMP,
        independentSortOrder = FIXED_TIMESTAMP,
        rssSourceUrl = null,
        rssSourceTitle = null,
        favoriteSaved = false,
        favoriteChangedAt = 0L,
        favoriteSortOrder = 0L,
        watchLaterSaved = false,
        watchLaterChangedAt = 0L,
        watchLaterSortOrder = 0L,
        deleted = false,
        deletedAt = 0L,
        readingProgress = 0f
    )

    /**
     * 构造一个稳定的保存项（收藏/稍后读列表中显示）。
     */
    fun sampleSavedItem(
        type: PhoneSavedItemType,
        stableKey: String = "saved-1",
        title: String = "保存项示例",
        link: String = "https://example.com/saved-1"
    ): PhoneSavedItemEntity = PhoneSavedItemEntity(
        type = type.name,
        stableKey = stableKey,
        remoteId = 1L,
        title = title,
        link = link,
        summary = "保存项摘要",
        channelTitle = "示例源",
        pubDate = "2026-07-13",
        syncedAt = FIXED_TIMESTAMP
    )

    /**
     * 插入一组完整的截图测试数据：
     * - 1 个 RSS 源
     * - 2 篇 RSS 文章（其中一篇收藏、一篇稍后读）
     * - 1 篇独立文章
     */
    fun seedPopulatedLibrary() = runBlocking {
        clearTestData()

        val source = sampleRssSource()
        rssSourceDao.upsert(source)

        val articles = listOf(
            sampleRssArticle(
                articleId = "rss-article-1",
                title = "示例文章一",
                excerpt = "这是第一篇示例文章的摘要内容。"
            ),
            sampleRssArticle(
                articleId = "rss-article-2",
                title = "示例文章二",
                excerpt = "这是第二篇示例文章的摘要内容。",
                favoriteSaved = true,
                sortOrder = FIXED_TIMESTAMP + 1
            ),
            sampleRssArticle(
                articleId = "rss-article-3",
                title = "示例文章三",
                excerpt = "这是第三篇示例文章的摘要内容。",
                watchLaterSaved = true,
                sortOrder = FIXED_TIMESTAMP + 2
            )
        )
        articleDao.upsertAll(articles)

        val independent = sampleIndependentArticle()
        articleDao.upsert(independent)
    }

    /**
     * 插入一个空的资料库（仅清空，用于空状态截图）。
     */
    fun seedEmptyLibrary() = runBlocking {
        clearTestData()
    }

    /**
     * 插入导入内容数据（模拟 txt 导入后的状态）。
     */
    fun seedImportedTextContent() = runBlocking {
        clearTestData()

        val importedArticle = sampleRssArticle(
            articleId = "imported-text-1",
            sourceUrl = ImportedContentIds.ROOT_SOURCE_URL,
            sourceTitle = "导入内容",
            title = "导入的 TXT 文档",
            url = ImportedContentIds.txtArticleUrl("imported-text-1"),
            excerpt = "这是导入的本地 TXT 文档摘要。"
        )
        articleDao.upsert(importedArticle)
    }

    private const val FIXED_TIMESTAMP = 1_725_000_000_000L
}
