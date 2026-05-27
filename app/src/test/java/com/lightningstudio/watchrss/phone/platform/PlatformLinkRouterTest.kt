package com.lightningstudio.watchrss.phone.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlatformLinkRouterTest {
    @Test
    fun detectsBilibiliLinks() {
        assertEquals(
            PlatformLinkKind.BILI,
            PlatformLinkRouter.detect("https://www.bilibili.com/video/BV1bDDEBZEp8?cid=37347986442")
        )
        assertEquals(PlatformLinkKind.BILI, PlatformLinkRouter.detect("https://m.bilibili.com/video/BV123"))
        assertEquals(PlatformLinkKind.BILI, PlatformLinkRouter.detect("https://b23.tv/abc123"))
        assertEquals(PlatformLinkKind.BILI, PlatformLinkRouter.detect("https://bili2233.cn/abc123"))
    }

    @Test
    fun detectsDouyinLinks() {
        assertEquals(PlatformLinkKind.DOUYIN, PlatformLinkRouter.detect("https://www.douyin.com/video/7641924749823659279"))
        assertEquals(PlatformLinkKind.DOUYIN, PlatformLinkRouter.detect("https://v.douyin.com/example/"))
        assertEquals(PlatformLinkKind.DOUYIN, PlatformLinkRouter.detect("https://www.iesdouyin.com/share/video/123"))
    }

    @Test
    fun ignoresNonPlatformAndLookalikeLinks() {
        assertNull(PlatformLinkRouter.detect("https://example.com/article"))
        assertNull(PlatformLinkRouter.detect("https://notbilibili.com/video/BV123"))
        assertNull(PlatformLinkRouter.detect("https://evil-douyin.com/video/123"))
        assertNull(PlatformLinkRouter.detect("bilibili://video/BV123"))
        assertNull(PlatformLinkRouter.detect(""))
    }
}
