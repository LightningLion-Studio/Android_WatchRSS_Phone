package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SyncV2ReusedArticleRowTest {
    @Test fun authenticatedArticleReusedDuringAnIndependentBodyPublicationRemainsReadableByArticleWorker() {
        SyncV2P5Fixture().use { fixture ->
            val records = javaClass.getResourceAsStream("/syncv2-business-schema.json")!!
                .bufferedReader().use { JSONObject(it.readText()).getJSONArray("records") }
            val article = (0 until records.length()).asSequence().map(records::getJSONObject)
                .first { it.getString("kind") == "article" }.getJSONObject("value")
            val entity = fixture.identity.entity("article", article.getString("articleId"))
            val oid = fixture.commit(listOf(fixture.note(), article), fixture.initial, "0", "normal", null)
            fixture.setCurrent(oid, "3")
            fixture.start()

            val authenticated = fixture.projection.cloudRows().single { it.getJSONObject("row").getString("entityKey") == entity }
                .getJSONObject("row")
            assertNull(fixture.projection.wireContentRow(entity))
            fixture.projection.prepareWireIndex(fixture.f.signer, fixture.key, 1).use { prepared ->
                val reusable = fixture.projection.wireContentRow(entity)
                assertNotNull("The article publisher needs the prepared authenticated wire row", reusable)
                assertEquals(SyncV2Codec.canonical(authenticated), SyncV2Codec.canonical(reusable))
                assertFalse(prepared.refs.any { it.getJSONObject("body").getString("stateOid") == authenticated.getString("stateOid") })
            }
        }
    }

}
