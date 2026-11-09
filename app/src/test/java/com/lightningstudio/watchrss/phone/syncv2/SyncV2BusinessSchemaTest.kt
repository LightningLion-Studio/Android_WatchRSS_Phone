package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Complete synthetic records checked against the frozen section 14 source snapshots. */
class SyncV2BusinessSchemaTest {
    private fun records(): List<JSONObject> {
        val root=JSONObject(requireNotNull(javaClass.classLoader!!.getResourceAsStream("syncv2-business-schema.json"))
            .bufferedReader().use{it.readText()})
        return root.getJSONArray("records").let{rows->(0 until rows.length()).map(rows::getJSONObject)}
    }
    private fun error(code:String,action:()->Unit) {
        try {action();fail("accepted $code")}catch(e:IllegalStateException){assertEquals(code,e.message)}
    }
    @Test fun everyFrozenKindPreservesAllFieldsAcrossMetaStateRoundTrip() {
        // Independent section 14 partition; do not derive expected keys from the product table.
        val metaFields="articleId url title siteName excerpt imageUrl rssSourceUrl rssSourceTitle contentHash importedAt guid sourceUrl sourceId description siteUrl createdAt fingerprint chapterCount kind instanceId bookId articleIdHash chapterIndex noteId folderId name assetId mime originalFilename".split(' ').toSet()
        val samples=records()
        assertEquals(setOf("article","source","book","book-reading","rss-state","note","folder","asset"),samples.map{it.getString("kind")}.toSet())
        for(sample in samples) {
            val kind=sample.getString("kind");val value=sample.getJSONObject("value")
            val original=value.toString();val keys=value.keys().asSequence().toSet()
            assertEquals("$kind frozen schema",keys,SyncV2Business.allowed(kind))
            val id=SyncV2Business.logicalId(kind,value)
            val pair=SyncV2Business.split(kind,id,"ab".repeat(32),value,null)
            // C7 W2/F6 (spec 25.2): the two note content hashes belong to the state so the segmented
            // parent metadata keeps them; every other kind keeps the frozen P2 partition.
            val noteHashes=setOf("contentHash","baseContentHash")
            val metaExpect=if(kind=="note")keys.intersect(metaFields)-noteHashes else keys.intersect(metaFields)
            assertEquals("$kind meta",metaExpect,pair.meta.getJSONObject("payload").keys().asSequence().toSet())
            val stateKeys=(if(kind=="note")keys-metaFields+noteHashes.intersect(keys) else (keys-metaFields)).toMutableSet()
            if(stateKeys.remove("readingProgress"))stateKeys.add("readingProgressMicros")
            assertEquals("$kind state",stateKeys,pair.state.getJSONObject("payload").keys().asSequence().toSet())
            // Business readingProgress is fractional; only wire Micros may enter JCS.
            val expected=JSONObject(original);val restored=SyncV2Business.join(kind,pair.meta,pair.state)
            if(expected.has("readingProgress")) {
                assertEquals("$kind progress",expected.getDouble("readingProgress"),restored.getDouble("readingProgress"),0.0)
                expected.remove("readingProgress");restored.remove("readingProgress")
            }
            assertEquals("$kind record",SyncV2Codec.canonical(expected),SyncV2Codec.canonical(restored))
            assertEquals("$kind input unchanged",original,value.toString())
            error("unsupported_feature"){SyncV2Business.split(kind,id,"ab".repeat(32),JSONObject(original).put("futureField",true),null)}
        }
    }
    @Test fun optionalGuidAndRssTombstoneRemainAbsentAndCannotBorrowBookFields() {
        for((kind,value)in listOf(
            "article" to JSONObject().put("articleId","a").put("url","https://synthetic.invalid/a"),
            "rss-state" to JSONObject().put("sourceId","s").put("articleIdHash","ab".repeat(32)))) {
            val pair=SyncV2Business.split(kind,SyncV2Business.logicalId(kind,value),"ab".repeat(32),value,null)
            val restored=SyncV2Business.join(kind,pair.meta,pair.state)
            assertEquals(SyncV2Codec.canonical(value),SyncV2Codec.canonical(restored))
            for(field in listOf("guid","deleted","deletedAt"))assertFalse(restored.has(field))
        }
        val rss=records().single{it.getString("kind")=="rss-state"}.getJSONObject("value")
        for(field in listOf("favoriteSourceDeviceId","watchLaterSourceDeviceId","bookId","chapterIndex")) {
            error("unsupported_feature"){SyncV2Business.validate("rss-state",JSONObject(rss.toString()).put(field,"legacy-book"))}
        }
        // Those two provenance fields remain valid and preserved for book-reading.
        SyncV2Business.validate("book-reading",records().single{it.getString("kind")=="book-reading"}.getJSONObject("value"))
    }
    @Test fun guidAndRssDeletionKeepTheirFrozenWireTypesAndBounds() {
        val article=JSONObject().put("articleId","a").put("url","https://synthetic.invalid/a")
        val guid="g".repeat(8192)
        val pair=SyncV2Business.split("article","a","ab".repeat(32),JSONObject(article.toString()).put("guid",guid),null)
        assertEquals(guid,pair.meta.getJSONObject("payload").getString("guid"))
        assertFalse(pair.state.getJSONObject("payload").has("guid"))
        for(value in listOf<Any>(1,JSONObject.NULL))error("invalid_schema") {
            SyncV2Business.validate("article",JSONObject(article.toString()).put("guid",value))
        }
        error("invalid_schema"){SyncV2Business.validate("article",JSONObject(article.toString()).put("guid",guid+"g"))}
        val rss=JSONObject().put("sourceId","s").put("articleIdHash","ab".repeat(32)).put("deleted",true).put("deletedAt",9_007_199_254_740_991L)
        val tombstone=SyncV2Business.split("rss-state","s:"+"ab".repeat(32),"ab".repeat(32),rss,null)
        assertTrue(tombstone.state.getJSONObject("payload").getBoolean("deleted"))
        assertEquals(9_007_199_254_740_991L,tombstone.state.getJSONObject("payload").getLong("deletedAt"))
        error("invalid_schema"){SyncV2Business.validate("rss-state",JSONObject(rss.toString()).put("deleted",1))}
        for(value in listOf<Any>("1",-1,9_007_199_254_740_992L))error("invalid_schema") {
            SyncV2Business.validate("rss-state",JSONObject(rss.toString()).put("deletedAt",value))
        }
    }
}
