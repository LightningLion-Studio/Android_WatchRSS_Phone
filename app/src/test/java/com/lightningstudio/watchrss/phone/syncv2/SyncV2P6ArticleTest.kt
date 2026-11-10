package com.lightningstudio.watchrss.phone.syncv2

import android.content.Context
import com.lightningstudio.watchrss.phone.cloud.CloudKeyManager
import com.lightningstudio.watchrss.phone.data.local.FileArticleContentStore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SyncV2P6ArticleTest {
    private fun article():JSONObject {
        val rows=javaClass.classLoader!!.getResourceAsStream("syncv2-business-schema.json")!!.bufferedReader().use{JSONObject(it.readText())}.getJSONArray("records")
        return (0 until rows.length()).map{rows.getJSONObject(it)}.single{it.getString("kind")=="article"}.getJSONObject("value")
    }
    @Test fun fixedMini27a71acUtf8BytesAndRevisionMatchWithoutNormalization() {
        val entity="11".repeat(32)
        val pairs=listOf("正文\r\nAé😀" to "a345e5197be8f7deea762517ca9300b3","<p>HTML é</p>" to "9d3ef4584a21d563cea18c95907adc11")
        for((text,expected)in pairs)assertEquals(expected,SyncV2ArticleBody.revision(entity,SyncV2Codec.hex(SyncV2Codec.sha(text.toByteArray()))))
        assertTrue(SyncV2ArticleBody.placeholder("book")!!.getBoolean("keepLocalBody"))
        assertTrue(SyncV2ArticleBody.placeholder("asset")!!.getBoolean("placeholder"));assertNull(SyncV2ArticleBody.placeholder("note"))
    }
    @Test fun actualV1MarkerPublishesRealBlobsRecipeRefsAndDeterministicRowBinding() {
        SyncV2P5Fixture().use {f->
            val original="正文\r\nAé😀".repeat(21000)
            val content=FileArticleContentStore(File(f.root,"imported_text"));val value=article()
            val marker=content.storeText(value.getString("articleId"),original)
            SyncV2PhoneApply(f.sql).upsert("phone_articles",listOf("articleId"),JSONObject(value.toString()).put("contentText",marker).put("contentHtml","ignored"))
            f.drain();f.projection.prepareWireIndex(f.f.signer,f.key,1).close()
            val context=RuntimeEnvironment.getApplication() as Context
            val manager=CloudKeyManager(null,preferencesOverride=context.getSharedPreferences("article-${System.nanoTime()}",Context.MODE_PRIVATE)).also{it.bindBackend("http://synthetic");it.saveAccountKey("u",1,f.key,true)}
            SyncV2ContentKeys.authenticated(manager,"u"){}.use {keys->
                SyncV2ContentStore(File(f.root,"content"),f.identity.scopeJson,ByteArray(32){9}).use {store->
                    var refs=0;val prior=f.f.route
                    f.f.route={r->if(r.requestUrl!!.encodedPath.endsWith("recipe-refs")){refs++;f.f.response(f.f.dto().put("accepted",true))}else prior?.invoke(r)}
                    val client=f.f.client();val worker=SyncV2ContentWorker(client,f.identity,store,{SyncV2TransportSignals(wifi=true,internet=true)},enabled=true,now={f.f.now},approvedDevice={true})
                    val publisher=SyncV2ArticlePublisher(f.projection,f.identity,keys,f.f.signer,client,store,worker,f.root,{})
                    assertTrue(publisher.runPage())
                    val entity=f.identity.entity("article",value.getString("articleId"));val row=f.projection.wireContentRow(entity)!!
                    assertEquals(SyncV2ArticleBody.revision(entity,SyncV2Codec.hex(SyncV2Codec.sha(original.toByteArray()))),row.getString("bodyRevision"))
                    assertFalse(row.isNull("recipeOid"));assertTrue(row.isNull("promiseId"));assertEquals(1,refs)
                    val recipe=SyncV2Wire.parse(SyncV2ContentCodec.openStored(f.identity,store,row.getString("recipeOid"),keys::read),262144,true)
                    SyncV2ContentCodec.matchRow(recipe,row)
                    val decoded=java.io.ByteArrayOutputStream();val blocks=recipe.getJSONArray("blocks")
                    SyncV2ContentCodec.verifyBody(f.identity,recipe,{block->SyncV2ContentCodec.openStored(f.identity,store,block.getString("oid"),keys::read).also{decoded.write(it)}})
                    assertArrayEquals(original.toByteArray(),decoded.toByteArray());assertTrue(blocks.length()>1)
                    assertEquals(marker,f.sql.query("SELECT contentText FROM phone_articles").use{it.moveToFirst();it.getString(0)})
                    assertEquals(original,content.loadText(marker))
                    assertTrue(publisher.runPage());assertEquals(1,refs)
                }
            }
        }
    }
    @Test fun realDatabaseTextWinsAndChangedRevisionStopsSourceRead() {
        SyncV2P5Fixture().use {f->
            val value=article();SyncV2PhoneApply(f.sql).upsert("phone_articles",listOf("articleId"),value.put("contentText","é😀").put("contentHtml","ignored"))
            var current=true
            val source=SyncV2ArticleBody.source(f.sql,f.root,value.getString("articleId")){if(!current)error("body_revision_changed")}!!
            assertArrayEquals("é😀".toByteArray(),source.read(0,source.size.toInt()))
            current=false
            try{source.read(0,1);fail("changed source read")}catch(e:IllegalStateException){assertEquals("body_revision_changed",e.message)}
        }
    }
    @Test fun authenticatedOriginalAddedAtSourceDoesNotBlockLaterLocalArticleEdits() {
        SyncV2P5Fixture().use {f->
            f.start()
            val records=javaClass.classLoader!!.getResourceAsStream("syncv2-business-schema.json")!!.bufferedReader().use{JSONObject(it.readText())}.getJSONArray("records")
            val value=(0 until records.length()).map{records.getJSONObject(it)}.single{it.getString("kind")=="article"}.getJSONObject("value")
            val old=f.commit(listOf(f.note(),value),f.current.getString("commitOid"),"0","normal",null)
            val originalHeader=f.headers.getValue(old)
            val rows=SyncV2Index.read(f.identity.scope,originalHeader.getJSONObject("body").getString("treeRoot"),f.f.objects::getValue)
            val entity=f.identity.entity("article",value.getString("articleId"))
            val article=rows.single{it.getString("entityKey")==entity}
            val meta=SyncV2Wire.parse(SyncV2Envelope.open(f.identity,f.key,article.getString("metaOid"),f.f.objects.getValue(article.getString("metaOid"))),262144,true).put("addedAtSource","original")
            val sealed=SyncV2Envelope.seal(f.identity,f.key,1,"meta",SyncV2Codec.utf8(SyncV2Codec.canonical(meta)));f.f.objects[sealed.oid]=sealed.bytes
            article.put("metaOid",sealed.oid).put("addedAtMillis",123)
            val tree=SyncV2Index.build(f.identity.scope,rows);f.f.objects.putAll(tree.objects)
            val body=JSONObject(originalHeader.getJSONObject("body").toString()).put("treeRoot",tree.root)
            val signed=f.f.signer.commit(body);val oid=SyncV2Codec.oid("commit",body);f.headers[oid]=signed;f.f.objects[oid]=SyncV2Codec.utf8(SyncV2Codec.canonical(signed))
            f.deviceOid=oid;f.deviceRevision="3";f.setCurrent(oid,"3");f.actions().refresh();f.drain()
            assertEquals("original",f.projection.candidate("article",value.getString("articleId"))!!.first.getString("addedAtSource"))
            f.sql.execSQL("UPDATE phone_articles SET title='local later title' WHERE articleId=?",arrayOf(value.getString("articleId")));f.drain()
            val cloud=SyncV2CloudSync(f.f.client(),f.identity,f.projection,{true},{f.key},now={f.f.now})
            cloud.publishIndex(f.f.signer,f.key,1)
            f.actions().refresh()
            assertEquals("local later title",f.sql.query("SELECT title FROM phone_articles WHERE articleId=?",arrayOf(value.getString("articleId"))).use{assertTrue(it.moveToFirst());it.getString(0)})
        }
    }

}
