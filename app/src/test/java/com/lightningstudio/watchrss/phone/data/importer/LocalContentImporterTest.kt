package com.lightningstudio.watchrss.phone.data.importer

import com.lightningstudio.watchrss.phone.data.model.ImportedContentIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LocalContentImporterTest {
    private val importer = LocalContentImporter()

    @Test
    fun importTxt_buildsArticleUnderImportContentChannel() {
        val result = importer.importFile(
            fileName = "长篇小说.txt",
            mimeType = "text/plain",
            bytes = "第一章\n\n这是正文。".toByteArray(Charsets.UTF_8)
        )

        assertEquals(LocalContentImportKind.TXT, result.kind)
        assertEquals(ImportedContentIds.ROOT_SOURCE_URL, result.source.url)
        assertEquals(ImportedContentIds.ROOT_SOURCE_TITLE, result.source.title)
        val article = result.source.items.single()
        assertEquals("长篇小说", article.title)
        assertTrue(article.url.startsWith("${ImportedContentIds.ROOT_SOURCE_URL}/txt/"))
        assertTrue(article.contentText.contains("这是正文"))
        assertNull(article.contentHtml)
    }

    @Test
    fun importTxt_keepsLargeTextAsSingleArticle() {
        val longText = buildString {
            repeat(130_000) { append('字') }
        }

        val result = importer.importFile(
            fileName = "长篇小说.txt",
            mimeType = "text/plain",
            bytes = longText.toByteArray(Charsets.UTF_8)
        )

        assertEquals(LocalContentImportKind.TXT, result.kind)
        val article = result.source.items.single()
        assertEquals("长篇小说", article.title)
        assertEquals(longText.length, article.contentText.length)
        assertNull(article.contentHtml)
    }

    @Test
    fun importEpub_buildsBookChannelAndChapterArticlesInSpineOrder() {
        val result = importer.importFile(
            fileName = "book.epub",
            mimeType = "application/epub+zip",
            bytes = sampleEpub()
        )

        assertEquals(LocalContentImportKind.EPUB, result.kind)
        assertEquals("示例书名", result.source.title)
        assertTrue(result.source.url.startsWith("${ImportedContentIds.ROOT_SOURCE_URL}/epub/"))
        assertEquals(listOf("第一章", "第二章"), result.source.items.map { it.title })
        assertTrue(result.source.items[0].contentText.contains("第一章正文"))
        assertTrue(result.source.items[1].contentText.contains("第二章正文"))
        assertTrue(result.source.items.all { it.url.startsWith("${result.source.url}/chapter/") })
    }

    @Test
    fun importEpub_usesEpub3NavTitlesAndRewritesTocLinks() {
        val result = importer.importFile(
            fileName = "three-body.epub",
            mimeType = "application/epub+zip",
            bytes = sampleEpubWithNav()
        )

        val items = result.source.items
        assertEquals(listOf("目录", "科学边界", "三体、周文王、长夜"), items.map { it.title })
        assertTrue(items[0].contentHtml.orEmpty().contains(items[1].url))
        assertTrue(items[0].contentHtml.orEmpty().contains(items[2].url))
        assertTrue(items[0].contentHtml.orEmpty().contains("#part-2"))
        assertTrue(items.none { it.title == "未知" })
    }

    @Test
    fun importEpub_usesEpub2NcxTitlesWhenChapterTitleIsUnknown() {
        val result = importer.importFile(
            fileName = "three-body.epub",
            mimeType = "application/epub+zip",
            bytes = sampleEpubWithNcx()
        )

        assertEquals(listOf("科学边界", "台球"), result.source.items.map { it.title })
        assertTrue(result.source.items.none { it.title == "未知" })
    }

    @Test
    fun importEpub_usesSpineHtmlTocWhenFormalTocIsMissing() {
        val result = importer.importFile(
            fileName = "three-body.epub",
            mimeType = "application/epub+zip",
            bytes = sampleEpubWithHtmlTocOnly()
        )

        val items = result.source.items
        assertEquals(
            listOf("目录", "第一章 科学边界", "第二章 台 球", "第三章 射手和农场主"),
            items.map { it.title }
        )
        assertTrue(items[0].contentHtml.orEmpty().contains(items[1].url))
        assertTrue(items.none { it.title == "未知" })
    }

    private fun sampleEpub(): ByteArray {
        return zipOf(
            "META-INF/container.xml" to """
                <?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
            """.trimIndent(),
            "OPS/content.opf" to """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns:dc="http://purl.org/dc/elements/1.1/">
                  <metadata><dc:title>示例书名</dc:title></metadata>
                  <manifest>
                    <item id="c1" href="chapter1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c2" href="chapter2.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine>
                    <itemref idref="c1"/>
                    <itemref idref="c2"/>
                  </spine>
                </package>
            """.trimIndent(),
            "OPS/chapter1.xhtml" to """
                <html xmlns="http://www.w3.org/1999/xhtml"><head><title>第一章</title></head>
                <body><h1>第一章</h1><p>第一章正文</p></body></html>
            """.trimIndent(),
            "OPS/chapter2.xhtml" to """
                <html xmlns="http://www.w3.org/1999/xhtml"><head><title>第二章</title></head>
                <body><h1>第二章</h1><p>第二章正文</p></body></html>
            """.trimIndent()
        )
    }

    private fun sampleEpubWithHtmlTocOnly(): ByteArray {
        return zipOf(
            "META-INF/container.xml" to """
                <?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
            """.trimIndent(),
            "OPS/content.opf" to """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns:dc="http://purl.org/dc/elements/1.1/">
                  <metadata><dc:title>三体全集</dc:title></metadata>
                  <manifest>
                    <item id="toc" href="contents.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c1" href="part0005_split_001.html" media-type="application/xhtml+xml"/>
                    <item id="c2" href="part0005_split_002.html" media-type="application/xhtml+xml"/>
                    <item id="c3" href="part0005_split_003.html" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine>
                    <itemref idref="toc"/>
                    <itemref idref="c1"/>
                    <itemref idref="c2"/>
                    <itemref idref="c3"/>
                  </spine>
                </package>
            """.trimIndent(),
            "OPS/contents.xhtml" to """
                <html xmlns="http://www.w3.org/1999/xhtml"><head><title>Contents</title></head>
                <body>
                  <div class="sgc-toc-title">目录</div>
                  <a href="part0005_split_001.html#sigil_toc_id_1">§§第一章 科学边界</a>
                  <a href="part0005_split_002.html#sigil_toc_id_2">§§第二章 台 球</a>
                  <a href="part0005_split_003.html#sigil_toc_id_3">§§第三章 射手和农场主</a>
                </body></html>
            """.trimIndent(),
            "OPS/part0005_split_001.html" to """
                <html xmlns="http://www.w3.org/1999/xhtml"><head><title>未知</title></head>
                <body><p id="sigil_toc_id_1">第一章正文</p></body></html>
            """.trimIndent(),
            "OPS/part0005_split_002.html" to """
                <html xmlns="http://www.w3.org/1999/xhtml"><head><title>未知</title></head>
                <body><p id="sigil_toc_id_2">第二章正文</p></body></html>
            """.trimIndent(),
            "OPS/part0005_split_003.html" to """
                <html xmlns="http://www.w3.org/1999/xhtml"><head><title>未知</title></head>
                <body><p id="sigil_toc_id_3">第三章正文</p></body></html>
            """.trimIndent()
        )
    }

    private fun sampleEpubWithNav(): ByteArray {
        return zipOf(
            "META-INF/container.xml" to """
                <?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
            """.trimIndent(),
            "OPS/content.opf" to """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns:dc="http://purl.org/dc/elements/1.1/">
                  <metadata><dc:title>三体</dc:title></metadata>
                  <manifest>
                    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                    <item id="c1" href="chapter1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c2" href="chapter2.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine>
                    <itemref idref="nav"/>
                    <itemref idref="c1"/>
                    <itemref idref="c2"/>
                  </spine>
                </package>
            """.trimIndent(),
            "OPS/nav.xhtml" to """
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                  <head><title>目录</title></head>
                  <body>
                    <nav epub:type="toc">
                      <ol>
                        <li><a href="chapter1.xhtml">科学边界</a></li>
                        <li><a href="chapter2.xhtml#part-2">三体、周文王、长夜</a></li>
                      </ol>
                    </nav>
                  </body>
                </html>
            """.trimIndent(),
            "OPS/chapter1.xhtml" to """
                <html xmlns="http://www.w3.org/1999/xhtml"><head><title>未知</title></head>
                <body><p>第一章正文</p></body></html>
            """.trimIndent(),
            "OPS/chapter2.xhtml" to """
                <html xmlns="http://www.w3.org/1999/xhtml"><head><title>未知</title></head>
                <body><p id="part-2">第二章正文</p></body></html>
            """.trimIndent()
        )
    }

    private fun sampleEpubWithNcx(): ByteArray {
        return zipOf(
            "META-INF/container.xml" to """
                <?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
            """.trimIndent(),
            "OPS/content.opf" to """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns:dc="http://purl.org/dc/elements/1.1/">
                  <metadata><dc:title>三体</dc:title></metadata>
                  <manifest>
                    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                    <item id="c1" href="chapter1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c2" href="chapter2.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine toc="ncx">
                    <itemref idref="c1"/>
                    <itemref idref="c2"/>
                  </spine>
                </package>
            """.trimIndent(),
            "OPS/toc.ncx" to """
                <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/">
                  <navMap>
                    <navPoint id="navPoint-1"><navLabel><text>科学边界</text></navLabel><content src="chapter1.xhtml"/></navPoint>
                    <navPoint id="navPoint-2"><navLabel><text>台球</text></navLabel><content src="chapter2.xhtml"/></navPoint>
                  </navMap>
                </ncx>
            """.trimIndent(),
            "OPS/chapter1.xhtml" to """
                <html xmlns="http://www.w3.org/1999/xhtml"><head><title>未知</title></head>
                <body><p>第一章正文</p></body></html>
            """.trimIndent(),
            "OPS/chapter2.xhtml" to """
                <html xmlns="http://www.w3.org/1999/xhtml"><head><title>未知</title></head>
                <body><p>第二章正文</p></body></html>
            """.trimIndent()
        )
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
