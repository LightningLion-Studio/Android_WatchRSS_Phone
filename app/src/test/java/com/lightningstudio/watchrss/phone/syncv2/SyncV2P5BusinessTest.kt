package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SyncV2P5BusinessTest {
    @Test fun sevenRoomDomainsAndPublicStateKeepFrozenWireFieldsAcrossApplyRebuildAndNewLocalEdits():Unit=SyncV2P5Fixture().use{w->
        val source=javaClass.classLoader!!.getResourceAsStream("syncv2-business-schema.json")!!.bufferedReader().use{JSONObject(it.readText())}.getJSONArray("records")
        val records=(0 until source.length()).map{source.getJSONObject(it)}.map{r->
            val kind=r.getString("kind");val v=r.getJSONObject("value");val id=SyncV2Business.logicalId(kind,v);val entity=w.identity.entity(kind,id);val p=SyncV2Business.split(kind,id,entity,v,null)
            JSONObject().put("meta",p.meta).put("state",p.state).put("value",v)
        }
        val dated=records.single{it.getJSONObject("meta").getString("kind")=="article"};dated.getJSONObject("meta").put("addedAtSource","original");dated.put("row",JSONObject().put("addedAtMillis",123).put("rowSequence","9"))
        w.projection.observeVerified(SyncV2Fixtures.window(),SyncV2Fixtures.observationHeaders())
        val st=w.projection.state();w.projection.accountWriter(st.version){current->SyncV2PhoneApply(w.sql).apply(emptyList(),records);w.projection.accountApplied(current,current.body.getJSONObject("account"),records,"synthetic-apply")}
        w.drain()
        for(r in records){val m=r.getJSONObject("meta");val pair=w.projection.candidate(m.getString("kind"),m.getString("logicalId"))!!
            assertEquals(m.getString("kind"),SyncV2LocalJson.canonical(r.getJSONObject("value")),SyncV2LocalJson.canonical(SyncV2Business.join(m.getString("kind"),pair.first,pair.second)))}
        assertEquals(0,w.projection.accountChangedIds().size)
        val first=w.projection.prepareWireIndex(w.f.signer,w.key,1)
        assertEquals(123,SyncV2Index.read(w.identity.scope,first.tree.root,first.objects::getValue).single{it.getString("entityKey")==dated.getJSONObject("meta").getString("entityKey")}.getLong("addedAtMillis"))
        val article=records.single{it.getJSONObject("meta").getString("kind")=="article"}.getJSONObject("value")
        w.sql.execSQL("UPDATE phone_articles SET title='local title' WHERE articleId=?",arrayOf(article.getString("articleId")));w.drain()
        val changed=w.projection.candidate("article",article.getString("articleId"))!!;val value=SyncV2Business.join("article",changed.first,changed.second)
        assertEquals("original",changed.first.getString("addedAtSource"));val next=w.projection.prepareWireIndex(w.f.signer,w.key,1)
        val nextRow=SyncV2Index.read(w.identity.scope,next.tree.root,next.objects::getValue).single{it.getString("entityKey")==dated.getJSONObject("meta").getString("entityKey")};assertEquals(123,nextRow.getLong("addedAtMillis"));assertEquals("11",nextRow.getString("rowSequence"))
        assertEquals("local title",value.getString("title"));assertEquals(article.getString("guid"),value.getString("guid"));assertEquals(SyncV2Codec.canonical(article.getJSONObject("registerSources")),SyncV2Codec.canonical(value.getJSONObject("registerSources")))
    }
    @Test fun publicInteractionUsesRealArticleIdentityAndLocalEditsRetainAuthenticatedBase():Unit=SyncV2P5Fixture().use{w->
        val data=javaClass.classLoader!!.getResourceAsStream("syncv2-business-schema.json")!!.bufferedReader().use{JSONObject(it.readText())}.getJSONArray("records")
        val article=(0 until data.length()).map{data.getJSONObject(it)}.single{it.getString("kind")=="article"}.getJSONObject("value")
        val state=(0 until data.length()).map{data.getJSONObject(it)}.single{it.getString("kind")=="rss-state"}.getJSONObject("value")
        val url="https://example.invalid/p5-feed";article.put("rssSourceUrl",url).put("isRead",true);state.put("sourceId",com.lightningstudio.watchrss.phone.cloud.CloudRssSourceIdentity.sourceKey(url)).put("articleIdHash",SyncV2Codec.hex(SyncV2Codec.sha(SyncV2Codec.utf8(article.getString("articleId"))))).put("isRead",true)
        val rows=listOf("article" to article,"rss-state" to state).map{(kind,v)->val id=SyncV2Business.logicalId(kind,v);val p=SyncV2Business.split(kind,id,w.identity.entity(kind,id),v,null);JSONObject().put("meta",p.meta).put("state",p.state).put("value",v)}
        w.projection.observeVerified(SyncV2Fixtures.window(),SyncV2Fixtures.observationHeaders());val st=w.projection.state()
        w.projection.accountWriter(st.version){current->SyncV2PhoneApply(w.sql).apply(emptyList(),rows);w.projection.accountApplied(current,current.body.getJSONObject("account"),rows,"public-state")};w.drain()
        assertEquals(1,w.sql.query("SELECT isRead FROM phone_articles").use{it.moveToFirst();it.getInt(0)})
        w.sql.execSQL("UPDATE phone_articles SET isRead=0");w.drain()
        val public=w.projection.accountLocalRows().single{it.getJSONObject("meta").getString("kind")=="rss-state"}
        assertFalse(public.getJSONObject("value").getBoolean("isRead"));assertTrue(public.getJSONObject("p5Base").getBoolean("isRead"))
    }
    @Test fun malformedFinalWireRollsBackEveryBusinessDomain():Unit=SyncV2P5Fixture().use{w->
        w.start();val st=w.projection.state();val before=w.text();val local=w.projection.accountLocalRows();val malformed=JSONObject(local.single().toString())
        malformed.getJSONObject("value").put("markdown","x".repeat(262144))
        assertNotNull(runCatching{w.projection.accountWriter(st.version){SyncV2PhoneApply(w.sql).apply(local,listOf(malformed))}}.exceptionOrNull())
        assertEquals(before,w.text());assertEquals(st.version,w.projection.state().version)
    }
}
