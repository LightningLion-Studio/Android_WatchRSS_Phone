package com.lightningstudio.watchrss.phone.data.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OpmlImporterTest {
    @Test fun `parses nested subscriptions and removes duplicate feed urls`() {
        val subscriptions = OpmlImporter().parse(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <opml version="2.0">
              <head><title>Subscriptions</title></head>
              <body>
                <outline text="Tech">
                  <outline text="Example" xmlUrl="https://example.com/feed.xml" htmlUrl="https://example.com" />
                  <outline title="Second" xmlUrl="http://example.org/rss" />
                </outline>
                <outline text="Duplicate" xmlUrl="https://example.com/feed.xml" />
                <outline text="Unsafe" xmlUrl="file:///etc/passwd" />
              </body>
            </opml>
            """.trimIndent().toByteArray()
        )

        assertEquals(2, subscriptions.size)
        assertEquals("Example", subscriptions[0].title)
        assertEquals("https://example.com/feed.xml", subscriptions[0].feedUrl)
        assertEquals("https://example.com", subscriptions[0].siteUrl)
        assertEquals("Second", subscriptions[1].title)
    }

    @Test fun `rejects documents without subscriptions`() {
        assertThrows(IllegalArgumentException::class.java) {
            OpmlImporter().parse("<opml><body><outline text=\"Folder\" /></body></opml>".toByteArray())
        }
    }

    @Test fun `rejects doctype declarations`() {
        assertThrows(IllegalArgumentException::class.java) {
            OpmlImporter().parse(
                "<!DOCTYPE opml [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]><opml><body /></opml>"
                    .toByteArray()
            )
        }
    }
}
