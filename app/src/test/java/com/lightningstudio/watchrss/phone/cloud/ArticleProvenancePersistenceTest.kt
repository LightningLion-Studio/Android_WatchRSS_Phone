package com.lightningstudio.watchrss.phone.cloud

import android.content.Context
import androidx.room.Room
import com.lightningstudio.watchrss.phone.data.db.*
import com.lightningstudio.watchrss.phone.data.repo.*
import com.lightningstudio.watchrss.phone.data.model.PhoneSavedItemType
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class ArticleProvenancePersistenceTest {
    private val context:Context=RuntimeEnvironment.getApplication()
    private val databases=mutableListOf<PhoneCompanionDatabase>()
    private val names=mutableListOf<String>()
    private fun db(name:String?=null):PhoneCompanionDatabase = (if(name==null)Room.inMemoryDatabaseBuilder(context,PhoneCompanionDatabase::class.java)
        else Room.databaseBuilder(context,PhoneCompanionDatabase::class.java,name))
        .addMigrations(PhoneCompanionDatabase.MIGRATION_18_19).addCallback(PhoneCompanionDatabase.ARTICLE_PROVENANCE_CALLBACK)
        .allowMainThreadQueries().build().also{databases+=it}
    private fun fixture()=JSONObject(ArticleProvenanceFixtures.resource("vectors.json").toString(Charsets.UTF_8))
    private fun record(v:JSONObject)=ArticleRegisterProvenance.decode(v.toString().toByteArray())
    private fun repo(db:PhoneCompanionDatabase,store:ArticleProvenanceStore)=PhoneCompanionRepository(db.phoneSavedItemDao(),db.phoneArticleDao(),db.phoneRssSourceDao(),"phone",
        appMetaDao=db.appMetaDao(),articleProvenance=store)
    private fun legacy(text:String="legacy original",clock:Long=100)=PhoneArticleEntity("synthetic-article","legacy-a",
        "https://watchrss.local/import-content/txt/synthetic","old","site","",null,text,null,"body-v1",1,clock,
        false,0,0,null,null,false,0,0,false,0,0,false,0)
    @After fun close(){databases.forEach{if(it.isOpen)it.close()};names.forEach(context::deleteDatabase)}

    @Test fun all159CasesSurviveNativeSqlProjectionAndStoreRecreationInAllSixOrders()=runBlocking {
        val db=db();val cases=fixture().getJSONArray("mergeCases")
        for(i in 0 until cases.length()) {
            val c=cases.getJSONObject(i);val inputs=c.getJSONArray("inputs");val orders=c.getJSONArray("orders")
            for(j in 0 until orders.length()) {
                db.clearAllTables();var store=ArticleProvenanceStore(db)
                val order=orders.getJSONObject(j).getJSONArray("order")
                for(n in 0..2) { store.accept(record(inputs.getJSONObject(order.getInt(n))));store=ArticleProvenanceStore(db) }
                val actual=store.record("synthetic-article")!!
                assertEquals(c.getString("id"),c.getJSONObject("expected").getString("canonicalUtf8"),actual.canonicalUtf8())
                val row=db.phoneArticleDao().getById("synthetic-article")!!
                assertEquals(actual.value.getJSONObject("registerSources").getString("metadata"),row.sourceDeviceId)
                assertEquals(actual.value.getLong("deletedAt"),row.deletedAt)
                assertEquals(actual.value.getBoolean("favoriteSaved"),row.favoriteSaved)
                assertEquals(actual.value.getLong("readingPositionChangedAt"),row.readingPositionChangedAt)
            }
        }
    }
    @Test fun actual18To19MigrationFreezesLegacyOnceBeforeSubsequentSharedSourceWrites()=runBlocking {
        val name="article-provenance-${System.nanoTime()}";names+=name
        var db=db(name);db.phoneArticleDao().upsert(legacy())
        val sql=db.openHelper.writableDatabase
        for(t in listOf("article_provenance_freeze_insert","article_provenance_freeze_update"))sql.execSQL("DROP TRIGGER IF EXISTS $t")
        for(t in listOf("article_register_provenance","article_register_originals","article_register_versions"))sql.execSQL("DROP TABLE $t")
        sql.version=18;db.close();db=db(name)
        val seed=db.articleProvenanceDao().get("synthetic-article")!!
        assertEquals("legacy-a",seed.legacySource);assertEquals(100L,seed.legacyReadingClock)
        db.phoneArticleDao().upsert(legacy(clock=999).copy(sourceDeviceId="metadata-z"))
        val input=fixture().getJSONArray("mergeCases").getJSONObject(0).getJSONArray("inputs").getJSONObject(0)
        val v=JSONObject(input.toString());v.getJSONObject("value").put("updatedAt",2000).put("readingPositionChangedAt",150).put("isRead",true)
        val store=ArticleProvenanceStore(db);store.accept(record(v))
        val accepted=store.record("synthetic-article")!!
        assertEquals(150L,accepted.value.getLong("readingPositionChangedAt"))
        assertTrue(accepted.value.getBoolean("isRead"))
        assertEquals("legacy-a",db.articleProvenanceDao().get("synthetic-article")!!.legacySource)
        db.close();db=db(name)
        assertEquals(accepted.canonicalUtf8(),ArticleProvenanceStore(db).record("synthetic-article")!!.canonicalUtf8())
        assertEquals(19,db.openHelper.readableDatabase.version)
    }
    @Test fun knownEmptyBodyProjectionCannotAcquireMetadataClockAsANewReadingOperation()=runBlocking {
        val db=db();db.phoneArticleDao().upsert(legacy(text="",clock=900).copy(sourceDeviceId="z"))
        assertEquals(0L,db.articleProvenanceDao().get("synthetic-article")!!.legacyReadingClock)
        val input=fixture().getJSONArray("mergeCases").getJSONObject(0).getJSONArray("inputs").getJSONObject(0)
        val v=JSONObject(input.toString());v.getJSONObject("value").put("updatedAt",901).put("isRead",true).put("readingPositionChangedAt",5)
        val store=ArticleProvenanceStore(db);store.accept(record(v))
        assertTrue(store.record("synthetic-article")!!.value.getBoolean("isRead"));assertEquals(5L,store.record("synthetic-article")!!.value.getLong("readingPositionChangedAt"))
    }
    @Test fun legacyExplicitReadingWithoutAvailableBodyStillFreezesItsOriginalR12ClockOnce()=runBlocking {
        val db=db();db.phoneArticleDao().upsert(legacy(text="",clock=100).copy(isRead=true))
        assertEquals(100L,db.articleProvenanceDao().get("synthetic-article")!!.legacyReadingClock)
        db.phoneArticleDao().upsert(legacy(text="",clock=999).copy(isRead=true,sourceDeviceId="metadata-z"))
        val input=fixture().getJSONArray("mergeCases").getJSONObject(0).getJSONArray("inputs").getJSONObject(0)
        val incoming=CloudRawDocument.parse(input.toString()).patch(listOf("value","readingPositionChangedAt"),"5")
        val store=ArticleProvenanceStore(db);store.accept(ArticleRegisterProvenance.decode(incoming))
        assertTrue(store.record("synthetic-article")!!.value.getBoolean("isRead"));assertEquals(100L,store.record("synthetic-article")!!.value.getLong("readingPositionChangedAt"))
    }
    @Test fun allThreeBodyBindingVectorsPreserveVersionsWithoutRelabellingOrResettingReading()=runBlocking {
        val cases=JSONObject(ArticleProvenanceFixtures.resource("body-bindings.json").toString(Charsets.UTF_8)).getJSONArray("cases")
        for(i in 0 until cases.length()) {
            val db=db();val store=ArticleProvenanceStore(db);val c=cases.getJSONObject(i);val inputs=c.getJSONArray("inputs")
            for(j in 0 until inputs.length())store.accept(record(inputs.getJSONObject(j)))
            val inventory=c.getJSONArray("inventory")
            for(j in 0 until inventory.length()) {
                val body=inventory.getJSONObject(j);val row=db.phoneArticleDao().getById("synthetic-article")!!
                store.materializeOriginal(row.copy(contentHash=body.getString("contentHash"),contentText=body.getString("bodyUtf8"),contentHtml=null))
                assertTrue(db.articleProvenanceDao().hasOriginal(row.articleId,body.getString("contentHash")))
            }
            val selected=c.getJSONObject("expected").opt("selectedBodyHex")
            val row=db.phoneArticleDao().getById("synthetic-article")!!
            assertEquals(if(selected==JSONObject.NULL)"" else selected,row.contentText.toByteArray().joinToString(""){"%02x".format(it)})
            assertEquals(c.getJSONObject("expected").getJSONObject("merged").getString("canonicalUtf8"),store.record(row.articleId)!!.canonicalUtf8())
            assertFalse(store.materializeOriginal(row.copy(contentText="",contentHtml=null)))
        }
    }
    @Test fun protectedNativeEditsRemainPendingAcrossReopenAndNeverRewriteTheSixRegisters()=runBlocking {
        val name="article-draft-${System.nanoTime()}";names+=name;var db=db(name);var store=ArticleProvenanceStore(db)
        val input=fixture().getJSONArray("mergeCases").getJSONObject(0).getJSONArray("inputs").getJSONObject(0)
        store.accept(record(input));val before=store.record("synthetic-article")!!.canonicalUtf8();val repository=repo(db,store)
        for(action in listOf<suspend()->Unit>({repository.markArticleRead("synthetic-article",false)},
            {repository.toggleSaved(db.phoneArticleDao().getById("synthetic-article")!!,PhoneSavedItemType.FAVORITE);Unit},
            {repository.deleteArticle("synthetic-article")})) {
            try{action();fail("must remain read only")}catch(e:CloudFormatUpgradeRequired){assertEquals("请升级到最新版本以继续同步",e.message)}
        }
        assertEquals(before,store.record("synthetic-article")!!.canonicalUtf8());assertEquals(1,store.pendingCount())
        assertThrows(CloudFormatUpgradeRequired::class.java){runBlocking{repository.getArticlesForSync()}}
        assertThrows(CloudFormatUpgradeRequired::class.java){runBlocking{repository.prepareLibrarySyncWindow("old-peer",0)}}
        assertFalse(repository.libraryFastPathAllowed("old-peer"))
        db.close();db=db(name);store=ArticleProvenanceStore(db)
        assertEquals(before,store.record("synthetic-article")!!.canonicalUtf8());assertTrue(store.state("synthetic-article")!!.editPending)
        assertEquals(3,JSONObject(db.articleProvenanceDao().get("synthetic-article")!!.pending!!).getJSONArray("intents").length())
    }
    @Test fun conflictingUnknownTokensKeepBothOriginalsAndSuspendOnlyThisArticle()=runBlocking {
        val db=db();val store=ArticleProvenanceStore(db)
        val input=fixture().getJSONArray("mergeCases").getJSONObject(0).getJSONArray("inputs").getJSONObject(0)
        val base=CloudRawDocument.parse(input.toString()).patch(listOf("value","future"),"{\"x\":1e+03}")
        store.accept(ArticleRegisterProvenance.decode(base));store.accept(ArticleRegisterProvenance.decode(base.patch(listOf("value","future"),"{\"x\":1001}")))
        assertEquals(2,db.articleProvenanceDao().versions("synthetic-article").size)
        assertTrue(store.state("synthetic-article")!!.editPending)
        val other=CloudRawDocument.parse(input.toString()).patch(listOf("value","articleId"),"\"unrelated\"")
        store.accept(ArticleRegisterProvenance.decode(other));assertNotNull(store.record("unrelated"))
    }
    @Test fun ownerChangeRollsBackVersionsProjectionAndBodyRetentionAtomically()=runBlocking {
        val db=db();val store=ArticleProvenanceStore(db)
        val input=fixture().getJSONArray("mergeCases").getJSONObject(0).getJSONArray("inputs").getJSONObject(0)
        store.accept(record(input));val before=store.record("synthetic-article")!!.canonicalUtf8()
        val changed=CloudRawDocument.parse(input.toString()).patch(listOf("value","contentHash"),"\"another-body\"").patch(listOf("value","updatedAt"),"9999")
        var calls=0
        assertThrows(CloudTrustException::class.java){runBlocking{store.accept(ArticleRegisterProvenance.decode(changed)){if(++calls==2)throw CloudTrustException()}}}
        assertEquals(before,ArticleProvenanceStore(db).record("synthetic-article")!!.canonicalUtf8())
        assertEquals(1,db.articleProvenanceDao().versions("synthetic-article").size)
    }
    @Test fun retainedLargeUnicodeOriginalRestoresByCodePointsAndExplicitLibraryClearRemovesWitnesses()=runBlocking {
        val db=db();val store=ArticleProvenanceStore(db)
        val input=fixture().getJSONArray("mergeCases").getJSONObject(0).getJSONArray("inputs").getJSONObject(0)
        store.accept(record(input));val current=db.phoneArticleDao().getById("synthetic-article")!!
        val original="🙂中".repeat(70000)
        store.materializeOriginal(current.copy(contentHash="future-body",contentText=original))
        val changed=CloudRawDocument.parse(input.toString()).patch(listOf("value","contentHash"),"\"future-body\"").patch(listOf("value","updatedAt"),"9999")
        store.accept(ArticleRegisterProvenance.decode(changed))
        assertEquals(original,db.phoneArticleDao().getById("synthetic-article")!!.contentText)
        val repository=repo(db,store)
        assertThrows(CloudFormatUpgradeRequired::class.java){runBlocking{repository.finalizeAuthoritativeLibrarySnapshot(emptyList(),emptyList())}}
        repository.clearLibrary()
        assertNull(db.articleProvenanceDao().get("synthetic-article"));assertTrue(db.articleProvenanceDao().versions("synthetic-article").isEmpty())
        assertFalse(store.anyProtected());assertEquals(0,store.pendingCount())
    }
    @Test fun repeatedReaderAutosavesAreDurablePendingWithoutThrowingOrTouchingAcceptedRegisters()=runBlocking {
        val db=db();val store=ArticleProvenanceStore(db);val repository=repo(db,store)
        val input=fixture().getJSONArray("mergeCases").getJSONObject(0).getJSONArray("inputs").getJSONObject(0)
        store.accept(record(input));val before=store.record("synthetic-article")!!.canonicalUtf8()
        repeat(300){repository.updateArticleReadingProgress("synthetic-article",it/300f)}
        assertEquals(before,store.record("synthetic-article")!!.canonicalUtf8())
        assertEquals(1,ArticleProvenanceStore(db).pendingCount())
        val draft=JSONObject(db.articleProvenanceDao().get("synthetic-article")!!.pending!!)
        assertEquals((299/300f).toDouble(),draft.getJSONObject("latestReadingProgress").getDouble("readingProgress"),0.0)
        assertEquals(input.getJSONObject("value").getString("contentHash"),draft.getJSONObject("latestReadingProgress").getString("readingPositionContentHash"))
        assertTrue(draft.toString().length<1024)
    }
    @Test fun independentlySelectedLargeValidGroupsRemainReadableAfterReopenWithoutRelaxingTheWireLimit()=runBlocking {
        val name="article-large-projection-${System.nanoTime()}";names+=name;var db=db(name)
        val input=fixture().getJSONArray("mergeCases").getJSONObject(0).getJSONArray("inputs").getJSONObject(0)
        val metadata=CloudRawDocument.parse(input.toString()).patch(listOf("value","title"),JSONObject.quote("m".repeat(40000))).patch(listOf("value","updatedAt"),"500")
        val reading=CloudRawDocument.parse(input.toString()).patch(listOf("value","readingPositionContentHash"),JSONObject.quote("r".repeat(40000))).patch(listOf("value","readingPositionChangedAt"),"500")
        val a=ArticleRegisterProvenance.decode(metadata);val b=ArticleRegisterProvenance.decode(reading)
        val store=ArticleProvenanceStore(db);store.accept(a);store.accept(b)
        val expected=ArticleRegisterProvenance.merge(a,b).canonicalUtf8()
        assertTrue(expected.toByteArray().size>65520)
        assertThrows(IllegalArgumentException::class.java){ArticleRegisterProvenance.decode(expected.toByteArray())}
        assertEquals(expected,store.record("synthetic-article")!!.canonicalUtf8())
        db.close();db=db(name)
        assertEquals(expected,ArticleProvenanceStore(db).record("synthetic-article")!!.canonicalUtf8())
    }
}
