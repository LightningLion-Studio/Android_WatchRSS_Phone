package com.lightningstudio.watchrss.phone.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineNovelLinkDetectorTest {
    @Test
    fun detectsProvidedQimaoAndFanqieShareSamples() {
        val samples = listOf(
            "《贫道看事，只杀不渡！》https://app-share.wtzw.com/app-h5/freebook/article-detail/9758196",
            "推荐一部好书《游戏入侵：开局金刚明王功》https://changdunovel.com/t/NRL-N8bjtO4/",
            "https://changdunovel.com/ug/pages/book-share?share_type=11&aid=1967&book_id=7590221243043826712&encrypt_did=MDIEDBoiMh6qIFtPw9MPVwQQ1LWEdji%2FrxdeWo%2FQKnypDwQQdutxh%2FRkHUsdILUA4AnCFg%3D%3D&ver=&share_genre=read&user_id=&did=166ab580686c01e3a41cebdd15424026&entrance=book_unread_share_button&zlink=https%3A%2F%2Fzlink.fqnovel.com%2FdhVGe&gd_label=click_schema_lhft_share_novelapp_ios&ver=v2&share_channel=copy_link&report_params=%7B%22content_id_key%22%3A%22book_id%22%2C%22share_timestamp%22%3A1783931177%2C%22entrance%22%3A%22book_unread_share_button%22%2C%22content_id%22%3A%227590221243043826712%22%2C%22if_full_screen%22%3A0%2C%22type%22%3A%22book_unread%22%2C%22content_type%22%3A%22novel%22%7D&ui_exp_group=3",
            "https://fanqienovel.com/page/7080092010052324352",
            "https://fanqienovel.com/reader/7119057346516484648?enter_from=page"
        )

        samples.forEach { sample ->
            assertTrue("Expected online novel link: $sample", OnlineNovelLinkDetector.isOnlineNovelLink(sample))
        }
    }

    @Test
    fun extractsUrlFromSharedText() {
        assertEquals(
            "https://app-share.wtzw.com/app-h5/freebook/article-detail/9758196",
            OnlineNovelLinkDetector.findOnlineNovelUrl(
                "《贫道看事，只杀不渡！》https://app-share.wtzw.com/app-h5/freebook/article-detail/9758196"
            )
        )
        assertEquals(
            "https://changdunovel.com/t/NRL-N8bjtO4/",
            OnlineNovelLinkDetector.findOnlineNovelUrl(
                "推荐一部好书《游戏入侵：开局金刚明王功》https://changdunovel.com/t/NRL-N8bjtO4/"
            )
        )
    }

    @Test
    fun detectsNamedOnlineNovelPlatformsAndShareDomains() {
        assertTrue(
            OnlineNovelLinkDetector.isOnlineNovelLink(
                "https://fanqienovel.com/page/7143038691944959011"
            )
        )
        assertTrue(
            OnlineNovelLinkDetector.isOnlineNovelLink(
                "https://changdunovel.com/wap/share-v2.html?book_id=123"
            )
        )
        assertTrue(OnlineNovelLinkDetector.isOnlineNovelLink("https://www.qimao.com/shuku/1841192/"))
        assertTrue(OnlineNovelLinkDetector.isOnlineNovelLink("https://app-share.wtzw.com/book/123"))
        assertTrue(OnlineNovelLinkDetector.isOnlineNovelLink("https://m.jjwxc.net/book2/123"))
        assertTrue(OnlineNovelLinkDetector.isOnlineNovelLink("http://www.jjwxc.com/onebook.php?novelid=123"))
    }

    @Test
    fun detectsOtherCommonOnlineNovelLibraries() {
        assertTrue(OnlineNovelLinkDetector.isOnlineNovelLink("https://www.qidian.com/book/1031940621/"))
        assertTrue(OnlineNovelLinkDetector.isOnlineNovelLink("https://book.qq.com/book-detail/123"))
        assertTrue(OnlineNovelLinkDetector.isOnlineNovelLink("https://weread.qq.com/web/bookDetail/123"))
    }

    @Test
    fun ignoresOrdinaryPagesMalformedUrlsAndLookalikeHosts() {
        assertFalse(OnlineNovelLinkDetector.isOnlineNovelLink("https://example.com/article"))
        assertFalse(OnlineNovelLinkDetector.isOnlineNovelLink("https://fanqienovel.com.evil.example/book/123"))
        assertFalse(OnlineNovelLinkDetector.isOnlineNovelLink("https://notqimao.com/shuku/123"))
        assertFalse(OnlineNovelLinkDetector.isOnlineNovelLink("fanqienovel://book/123"))
        assertFalse(OnlineNovelLinkDetector.isOnlineNovelLink("fanqienovel.com/page/123"))
        assertFalse(OnlineNovelLinkDetector.isOnlineNovelLink(""))
        assertFalse(OnlineNovelLinkDetector.isOnlineNovelLink(null))
    }
}
