package com.lightningstudio.watchrss.phone.cloud

import com.lightningstudio.watchrss.phone.data.importer.WebArticleImporter
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(manifest=org.robolectric.annotation.Config.NONE,sdk=[35])
class CloudRssSourceIdentityTest {
    private val vector=JSONObject(requireNotNull(javaClass.getResource("/cloud/rss-source-key.json")).readText())
    @Test fun sourceNormalizationAndKeysMatchExactBackendRustFunctions() {
        val rows=vector.getJSONArray("sourceCases")
        for (i in 0 until rows.length()) {
            val row=rows.getJSONObject(i);val input=row.getString("input")
            assertEquals(input,row.getString("canonicalUrl"),CloudRssSourceIdentity.canonicalUrl(input))
            assertEquals(input,row.getString("sourceKey"),CloudRssSourceIdentity.sourceKey(input))
        }
    }
    @Test fun backendRejectedUrlsAreRejectedWithoutResolvingDnsOrFetchingFeeds() {
        val rows=vector.getJSONArray("rejectedSources")
        for (i in 0 until rows.length()) assertThrows(rows.getJSONObject(i).getString("input"), IllegalArgumentException::class.java) {
            CloudRssSourceIdentity.sourceKey(rows.getJSONObject(i).getString("input"))
        }
    }
    private fun JSONObject.nullable(name: String): String? = if (isNull(name) || !has(name)) null else getString(name)

    @Test fun articleIdentityAndDoubleHashMatchBackendDedupRuleIncludingNonHttpLinks() {
        val rows=vector.getJSONArray("articleCases")
        for (i in 0 until rows.length()) {
            val row=rows.getJSONObject(i);val input=row.getString("input")
            assertEquals(input,row.nullable("canonicalEntryLink"),CloudRssSourceIdentity.canonicalEntryLink(input))
            val id=CloudRssEntryIdentity.id(row.nullable("guid"),input,row.nullable("contentHash"),row.optString("title",""))
            assertEquals(row.optString("name")+input,row.getString("articleId"),id)
            assertEquals(id,row.getString("dedupKey"))
            assertEquals(row.getString("articleIdHash"),CloudSnapshotCodec.sha256(id.toByteArray()))
        }
    }

    @Test fun privateWebIdentityRemainsStableAndDoesNotReplaceRssGuidIdentity() {
        val rows=vector.getJSONArray("legacyWebArticleCases")
        for (i in 0 until rows.length()) {
            val row=rows.getJSONObject(i)
            assertEquals(row.getString("articleId"),WebArticleImporter.stableArticleId(row.getString("input")))
        }
        val guidId=CloudRssEntryIdentity.id("tag:example.com,2026:post-1","https://example.com/changed",null,"Changed title")
        assertEquals(guidId,CloudRssEntryIdentity.id("tag:example.com,2026:post-1",null,"changed-hash","Other title"))
    }

    @Test fun endToEndVectorConnectsBackendFeedAndEntryIdentityToActualEncryptedRecord() {
        val v=JSONObject(requireNotNull(javaClass.getResource("/cloud/rss-state-e2e-v1.json")).readText())
        val feed=v.getJSONObject("feed"); val entry=v.getJSONObject("entry")
        val sourceKey=CloudRssSourceIdentity.sourceKey(feed.getString("input"))
        val articleId=CloudRssEntryIdentity.id(entry.nullable("guid"),entry.getString("input"),entry.nullable("contentHash"),entry.optString("title",""))
        assertEquals(feed.getString("sourceKey"),sourceKey)
        assertEquals(entry.getString("articleId"),articleId)
        val value=JSONObject(v.getJSONObject("value").toString()).put("sourceId",sourceKey)
            .put("articleIdHash",CloudSnapshotCodec.sha256(articleId.toByteArray()))
        fun bytes(field: String)=v.getString(field).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val anchor=bytes("anchorKeyHex"); val content=bytes("contentKeyHex"); val owner=v.getString("userId")
        val id=PhoneCloudRecordCodec.id(anchor,owner,"rss-state",value)
        assertEquals(v.getString("recordId"),id)
        val actual=PhoneCloudRecordCodec.encrypt(content,owner,id,v.getInt("keyVersion"),"rss-state",value,nonce=bytes("nonceHex"),idKey=anchor)
        assertEquals(PhoneCloudRecordCodec.canonical(v.getJSONObject("envelope")),PhoneCloudRecordCodec.canonical(actual))
        val decoded=PhoneCloudRecordCodec.decrypt(content,owner,id,actual,listOf(anchor,content))
        assertEquals(PhoneCloudRecordCodec.canonical(value),PhoneCloudRecordCodec.canonical(decoded.second))
    }

    @Test fun rawDelimitersAndAlreadyEscapedDelimitersNeverAlias() {
        for (delimiter in listOf("|" to "%7C", "^" to "%5E")) {
            assertNotEquals(CloudRssSourceIdentity.sourceKey("https://example.com/a${delimiter.first}b"),
                CloudRssSourceIdentity.sourceKey("https://example.com/a${delimiter.second}b"))
            assertNotEquals(WebArticleImporter.stableArticleId("https://example.com/a${delimiter.first}b"),
                WebArticleImporter.stableArticleId("https://example.com/a${delimiter.second}b"))
        }
        assertEquals("http://[::ffff:808:808]/f",CloudRssSourceIdentity.canonicalUrl("http://[::ffff:8.8.8.8]/f"))
        assertNotEquals(CloudRssSourceIdentity.sourceKey("http://[::ffff:8.8.8.8]/f"),CloudRssSourceIdentity.sourceKey("http://8.8.8.8/f"))
    }

    @Test fun localOnlyClassificationIsOfflineAndDoesNotMisclassifyPublicOrMalformedSources() {
        for (url in listOf("http://192.168.1.1/rss", "http://10.0.0.1/rss", "http://127.0.0.1/rss", "http://[::ffff:192.168.1.1]/rss", "https://user:pass@example.com/rss", "https://user:@example.com/rss")) {
            assertTrue(url,CloudRssSourceIdentity.isLocalOnly(url))
        }
        for (url in listOf("https://example.com/rss", "http://8.8.8.8/rss", "http://[::ffff:8.8.8.8]/rss", "not a URL")) {
            assertFalse(url,CloudRssSourceIdentity.isLocalOnly(url))
        }
    }
}
