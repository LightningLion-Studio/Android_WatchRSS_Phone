package com.lightningstudio.watchrss.phone.data.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebArticleImporterTest {
    @Test
    fun parse_extractsReadableArticleMetadata() {
        val article = WebArticleImporter().parse(
            baseUrl = "https://example.com/posts/one",
            html = """
                <html>
                  <head>
                    <title>Fallback</title>
                    <meta property="og:title" content="示例文章">
                    <meta property="og:site_name" content="示例站点">
                    <meta name="description" content="页面摘要">
                  </head>
                  <body>
                    <nav>导航</nav>
                    <article>
                      <h1>正文标题</h1>
                      <p>第一段正文。</p>
                      <img data-src="/cover.jpg">
                    </article>
                  </body>
                </html>
            """.trimIndent()
        )

        assertEquals("示例文章", article.title)
        assertEquals("示例站点", article.siteName)
        assertEquals("页面摘要", article.excerpt)
        assertEquals("https://example.com/cover.jpg", article.imageUrl)
        assertTrue(article.contentText.contains("第一段正文"))
    }

    @Test
    fun parse_extractsTencentInitDataBeforeRemovingScripts() {
        val article = WebArticleImporter().parse(
            baseUrl = "https://view.inews.qq.com/k/20260522A06RSD00?scene=wap",
            html = """
                <html>
                  <head>
                    <title>加载页</title>
                    <meta name="description" content="页面摘要">
                  </head>
                  <body>
                    <div id="root"></div>
                    <script>
                      window.initData = {
                        "content": {
                          "title": "T1 Trump Phone正式发货 金色外观配后置三摄",
                          "source": "手机中国",
                          "abstract": "腾讯新闻摘要",
                          "content": {
                            "text": "<div class='rich_media_content'><p>腾讯正文第一段。</p><p><!--IMG_0--></p><p>腾讯正文第二段。</p></div>"
                          },
                          "attribute": {
                            "IMG_0": {
                              "url": "/om_bt/cover/641",
                              "desc": "配图"
                            }
                          }
                        }
                      };
                    </script>
                  </body>
                </html>
            """.trimIndent()
        )

        assertEquals("T1 Trump Phone正式发货 金色外观配后置三摄", article.title)
        assertEquals("手机中国", article.siteName)
        assertEquals("腾讯新闻摘要", article.excerpt)
        assertEquals("https://view.inews.qq.com/om_bt/cover/641", article.imageUrl)
        assertTrue(article.contentText.contains("腾讯正文第一段"))
        assertTrue(article.contentText.contains("腾讯正文第二段"))
        assertTrue(article.contentHtml?.contains("https://view.inews.qq.com/om_bt/cover/641") == true)
    }

    @Test
    fun parse_keepsMetadataOnlyPagesImportable() {
        val article = WebArticleImporter().parse(
            baseUrl = "https://example.com/js-only",
            html = """
                <html>
                  <head>
                    <title>空壳页面</title>
                    <meta name="description" content="只有前端渲染入口的页面摘要">
                  </head>
                  <body>
                    <div id="root"></div>
                    <script>window.app = {};</script>
                  </body>
                </html>
            """.trimIndent()
        )

        assertEquals("空壳页面", article.title)
        assertEquals("只有前端渲染入口的页面摘要", article.contentText)
        assertEquals("只有前端渲染入口的页面摘要", article.excerpt)
        assertNull(article.contentHtml)
    }
}
