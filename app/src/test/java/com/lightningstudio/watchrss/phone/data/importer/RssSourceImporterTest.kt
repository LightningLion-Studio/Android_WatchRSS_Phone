package com.lightningstudio.watchrss.phone.data.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RssSourceImporterTest {
    @Test
    fun parseVideoEnclosureKeepsVideoAndUsesPodcastArtworkAsImage() {
        val source = RssSourceImporter().parse(
            "https://www.jpl.nasa.gov/feeds/podcasts/",
            """
                <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
                  <channel>
                    <title>NASA JPL</title>
                    <item>
                      <title>Methane Hot Spots</title>
                      <link>https://www.jpl.nasa.gov/videos/methane-hot-spots</link>
                      <description>Video description</description>
                      <enclosure
                        type="video/mp4"
                        url="https://cdn.example.com/methane.m4v"
                        length="22285997" />
                      <itunes:image href="https://cdn.example.com/methane.jpg" />
                    </item>
                  </channel>
                </rss>
            """.trimIndent()
        )

        val item = source.items.single()
        assertEquals("https://cdn.example.com/methane.jpg", item.imageUrl)
        assertTrue(item.contentHtml.orEmpty().contains("<video"))
        assertTrue(item.contentHtml.orEmpty().contains("https://cdn.example.com/methane.m4v"))
        assertFalse(item.imageUrl.orEmpty().endsWith(".m4v"))
    }

    @Test
    fun parseAudioEnclosureKeepsAudioInArticleContent() {
        val source = RssSourceImporter().parse(
            "https://example.com/feed.xml",
            """
                <rss version="2.0">
                  <channel>
                    <title>Podcast</title>
                    <item>
                      <title>Episode</title>
                      <enclosure type="audio/mpeg" url="https://cdn.example.com/episode.mp3" />
                    </item>
                  </channel>
                </rss>
            """.trimIndent()
        )

        val contentHtml = source.items.single().contentHtml.orEmpty()
        assertTrue(contentHtml.contains("<audio"))
        assertTrue(contentHtml.contains("https://cdn.example.com/episode.mp3"))
    }
}
