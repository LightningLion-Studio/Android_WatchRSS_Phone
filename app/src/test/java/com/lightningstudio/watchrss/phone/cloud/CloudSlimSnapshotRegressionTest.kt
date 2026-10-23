package com.lightningstudio.watchrss.phone.cloud

import android.content.Context
import androidx.room.Room
import com.lightningstudio.watchrss.phone.account.*
import com.lightningstudio.watchrss.phone.data.backup.*
import com.lightningstudio.watchrss.phone.data.db.*
import com.lightningstudio.watchrss.phone.data.reader.ReaderPresetRepository
import com.lightningstudio.watchrss.phone.data.repo.PhoneCompanionRepository
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.Random

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE, sdk=[35])
class CloudSlimSnapshotRegressionTest {
    private val key = ByteArray(32) { it.toByte() }
    private val session = PhoneAccountSession("slim-owner", "***", "session", "", Long.MAX_VALUE)
    private val codec = CloudSnapshotCodec()
    private val fixtures = mutableListOf<Fixture>()
    private class State : CloudRecordState {
        var cursor = "0"
        val rows = linkedMapOf<String,Pair<String,String>>()
        val raw = linkedMapOf<String,String>()
        val states = linkedMapOf<String,JSONObject>()
        override fun cursor(owner: String) = cursor
        override fun record(owner: String,id: String) = rows[id]
        override fun save(owner: String,id: String,revision: String,hash: String) { rows[id] = revision to hash }
        override fun advance(owner: String,cursor: String) { this.cursor = cursor }
        override fun clear(owner: String) { cursor="0";rows.clear();raw.clear();states.clear() }
        override fun rawEnvelope(owner: String,id: String) = raw[id]
        override fun saveRawEnvelope(owner: String,id: String,envelope: String) { raw[id]=envelope }
        override fun rawPage(owner: String,after: String,limit: Int) = raw.entries.filter { it.key>after }.sortedBy { it.key }.take(limit).map { it.key to it.value }
        override fun saveRssState(owner: String,value: JSONObject) { val id=value.getString("sourceId")+value.getString("articleIdHash"); states[id]=CloudRssInteraction.merge(states[id],value) }
        override fun rssStates(owner: String) = states.values.toList()
    }
    private inner class Server {
        var revision=0
        var writes=0
        var failWrites=false
        var failReads=false
        var recordsUnavailable=false
        val batchSizes=mutableListOf<Int>()
        val records=linkedMapOf<String,JSONObject>()
        val events=mutableListOf<Pair<String,JSONObject>>()
        val client=PhoneCloudClient(AccountEnvironment(backendBaseUrl="https://test.invalid"),http=OkHttpClient.Builder().addInterceptor { chain ->
            val req=chain.request()
            val response: JSONObject
            var status=200
            if(req.method=="GET") {
                if (failReads) throw java.io.IOException("records offline")
                if (recordsUnavailable) return@addInterceptor Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(404).message("test").body("{}".toResponseBody("application/json".toMediaType())).build()
                val after=req.url.queryParameter("after")!!.toLong()
                val items=records.values.filter { it.getString("revision").toLong()>after }.sortedBy { it.getString("revision").toLong() }.take(50)
                val cursor=items.lastOrNull()?.getString("revision") ?: revision.toString()
                response=JSONObject().put("formatVersion",1).put("items",JSONArray(items)).put("cursor",cursor).put("headRevision",revision.toString()).put("hasMore",cursor.toLong()<revision)
            } else {
                if(failWrites) throw java.io.IOException("offline")
                val buffer=okio.Buffer();req.body!!.writeTo(buffer)
                val items=JSONObject(buffer.readUtf8()).getJSONArray("items")
                batchSizes += items.length()
                val result=JSONArray()
                for(i in 0 until items.length()) {
                    val item=items.getJSONObject(i); val id=item.getString("recordId")
                    assertEquals(records[id]?.getString("revision") ?: "0",item.getString("expectedRevision"))
                    val row=JSONObject(item.toString()).put("revision",(++revision).toString())
                    records[id]=row; result.put(JSONObject().put("recordId",id).put("revision",revision.toString()));writes++
                    val decoded=PhoneCloudRecordCodec.decrypt(key,session.userId,id,item.getJSONObject("envelope"))
                    events+=decoded
                }
                response=JSONObject().put("items",result)
            }
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(status).message("test").body(response.toString().toResponseBody("application/json".toMediaType())).build()
        }.build()).also { it.useEpoch(session,"epoch");it.useContentGeneration(session,"22222222-2222-4222-8222-222222222222") }
    }
    private inner class Fixture(val server: Server, val backend: String="https://test.invalid", bookMode:Boolean=false) {
        val context: Context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context,PhoneCompanionDatabase::class.java).allowMainThreadQueries().build()
        val scope = CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val bookDirectory=java.io.File.createTempFile("cloud-book-test-","",context.cacheDir).apply{delete();mkdirs()}
        val books=if(bookMode)com.lightningstudio.watchrss.phone.data.book.ImportedBookStore(db,bookDirectory,null,"phone")else null
        val repository=PhoneCompanionRepository(db.phoneSavedItemDao(),db.phoneArticleDao(),db.phoneRssSourceDao(),"phone",appMetaDao=db.appMetaDao(),cloudBackendBaseUrl=backend,books=books)
        val reader=ReaderPresetRepository(context,db,db.readerPresetDao(),"phone",scope)
        val backup=WatchRssBackupService(context,db,repository,reader,"phone")
        val state=State()
        val sync=PhoneCloudRecordSync(server.client,state,backup::cloudRecordPages,{ _,kind,value -> if(kind != "rss-state") backup.applyCloudRecord(kind,value) },{1},{_,_->key.copyOf()},{},keepArticle=backup::keepCloudArticle,canonicalArticleId=backup::canonicalCloudArticleId,afterPull={ backup.applyRssInteractions(state.rssStates("")) },bookForArticle=backup::bookRecordForLegacyArticle)
        suspend fun records() = sync.sync(session,true,"epoch",requireAvailable=true)
        suspend fun subscription() {
            repository.rememberPublicRssSource("https://feed.test/rss","public-source")
            db.phoneRssSourceDao().upsert(PhoneRssSourceEntity("https://feed.test/rss","phone","My channel","public description",null,null,1,1,1,false,false,0))
        }
        suspend fun image(previous: EncryptedCloudSnapshot?=null): EncryptedCloudSnapshot? {
            val bytes=backup.createCloudPrivateArchive()
            val fingerprint=backup.privateCloudFingerprint()
            val objects=listOf(CloudLogicalObject("private-library.wrss",bytes,false,incrementalChunks=true))
            val hash=CloudSnapshotContent.hash(objects,emptyList(),1,fingerprint)
            if(!CloudContentPolicy.shouldPublish(previous?.manifest?.settings?.optString("snapshotContentHashV3")!=hash,true,0,1)) return null
            return codec.create(key,1,"phone",(previous?.manifest?.deviceSequence?:0)+1,objects,previousManifest=previous?.manifest,
                snapshotSettings=JSONObject().put("snapshotContentLayout",3).put("ownerUserId",session.userId).put("libraryEpoch","epoch")
                    .put("privateBodyFingerprintV2",fingerprint).put("snapshotContentHashV3",hash))
        }
    }
    private fun fixture(server: Server=Server(),bookMode:Boolean=false) = Fixture(server,bookMode=bookMode).also { fixtures+=it }
    @After fun close() { fixtures.forEach { it.scope.cancel();it.db.close();it.bookDirectory.deleteRecursively() } }
    private fun article(id:String="rss-1",body:String="RSS original",private:Boolean=false)=PhoneArticleEntity(id,"phone",
        if(private) "https://watchrss.local/import-txt/$id" else "https://entry.test/$id", "Title $id","Feed","summary",null,body,null,
        CloudSnapshotCodec.sha256(body.toByteArray()),10,10,private,if(private)10 else 0,0,if(private)null else "https://feed.test/rss",null,
        false,0,0,false,0,0,false,0)

    @Test fun backendDedupCasesUseOneLocalIdAndRecordIdentityIncludingGuidOnlyAndMalformedPeerRow()=runBlocking<Unit> {
        val vectors=JSONObject(requireNotNull(javaClass.getResource("/cloud/rss-source-key.json")).readText()).getJSONArray("articleCases")
        fun JSONObject.nullable(name: String): String?=if (!has(name) || isNull(name)) null else getString(name)
        val rows=(0 until vectors.length()).map(vectors::getJSONObject)
        val items=rows.mapIndexed { index,row -> com.lightningstudio.watchrss.phone.data.importer.ImportedRssItem(
            url=row.getString("input").ifBlank { "https://feed.test/rss?watchrss_entry=pool-uuid-$index" },
            title=row.optString("title",""),excerpt="",contentHtml=null,contentText="",imageUrl=null,
            guid=row.nullable("guid"),contentHash=row.nullable("contentHash"),dedupKey=row.getString("dedupKey"),identityLink=row.getString("input")) }
        val bad=com.lightningstudio.watchrss.phone.data.importer.ImportedRssItem("tag:broken","bad","",null,"",null,null,dedupKey="invalid")
        val inventory=com.lightningstudio.watchrss.phone.data.importer.ImportedRssSource("https://feed.test/rss","Feed","",null,null,items+bad,"pool")
        val f=fixture();val imported=f.repository.mergeCloudRssInventory(inventory)
        assertEquals(1,imported.skippedArticleCount)
        val ids=rows.map { it.getString("articleId") }.toSet()
        assertEquals(ids,f.db.phoneArticleDao().getAllForSync().map { it.articleId }.toSet())
        for (id in ids) f.db.phoneArticleDao().upsert(f.repository.getArticle(id)!!.copy(isRead=true,readingPositionChangedAt=100))
        f.records()
        val states=f.server.events.filter { it.first=="rss-state" }.map { it.second }
        assertEquals(ids.size,states.size)
        assertEquals(ids.map { CloudSnapshotCodec.sha256(it.toByteArray()) }.toSet(),states.map { it.getString("articleIdHash") }.toSet())
        assertNotNull(f.image())
        // Canonical Chinese path returns from another device; the same ID receives the state.
        val fresh=fixture(f.server);fresh.sync.sync(session,false,"epoch",requireAvailable=true)
        fresh.repository.mergeCloudRssInventory(inventory.copy(items=items.map { item ->
            item.copy(url=CloudRssSourceIdentity.canonicalEntryLink(item.identityLink) ?: item.url)
        }))
        fresh.backup.applyRssInteractions(fresh.state.rssStates(""))
        assertEquals(ids,fresh.db.phoneArticleDao().getAllForSync().map { it.articleId }.toSet())
        assertTrue(fresh.db.phoneArticleDao().getAllForSync().all { it.isRead })
    }

    @Test fun poolUuidNeverBecomesGuidOrArticleIdAndDedupKeyIsNotDoubleHashed()=runBlocking<Unit> {
        val expected=CloudRssEntryIdentity.id("tag:feed.test,2026:guid",null,null,"")
        val publicClient=PhoneCloudClient(AccountEnvironment(backendBaseUrl="https://test.invalid"),http=OkHttpClient.Builder().addInterceptor { chain ->
            val req=chain.request()
            val json=if (req.method=="POST") JSONObject().put("source",JSONObject().put("id","pool"))
                else JSONObject().put("source",JSONObject().put("title","Feed")).put("entries",JSONArray()
                    .put(JSONObject().put("id","uuid-guid").put("link",JSONObject.NULL).put("guid","tag:feed.test,2026:guid").put("dedupKey",expected).put("title","Guid only"))
                    .put(JSONObject().put("id","uuid-empty").put("link",JSONObject.NULL).put("guid",JSONObject.NULL).put("title","")))
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("test").body(json.toString().toResponseBody("application/json".toMediaType())).build()
        }.build())
        val imported=publicClient.rssInventory(session,"https://feed.test/rss",CloudRssInventoryMode.ALL)
        assertNull(imported.items[1].guid);assertNull(imported.items[1].identityLink)
        val f=fixture();f.repository.mergeCloudRssInventory(imported)
        assertNotNull(f.repository.getArticle(expected))
        assertNotNull(f.repository.getArticle(CloudSnapshotCodec.sha256(byteArrayOf())))
        assertEquals(2,f.db.phoneArticleDao().getAllForSync().size)
    }

    @Test fun rssIdentityRekeyPreservesSavedOriginalAndReadStateWithoutDuplicateChineseEntry()=runBlocking<Unit> {
        val f=fixture();val raw="https://www.例子.测试:8443/文章/../新闻?q=中文#片段"
        val legacy=com.lightningstudio.watchrss.phone.data.importer.WebArticleImporter.stableArticleId(raw)
        val id=CloudRssEntryIdentity.id(null,raw,null,"")
        assertNotEquals(legacy,id)
        val old=article(legacy,"Saved Chinese original").copy(url=raw,favoriteSaved=true,favoriteChangedAt=100,isRead=true,readingPositionChangedAt=200)
        f.db.phoneArticleDao().upsert(old)
        val item=com.lightningstudio.watchrss.phone.data.importer.ImportedRssItem(raw,"Fresh pool title","",null,"Pool changed body",null,null)
        val feed=com.lightningstudio.watchrss.phone.data.importer.ImportedRssSource("https://feed.test/rss","Feed","",null,null,listOf(item),"pool")
        f.repository.mergeCloudRssInventory(feed)
        assertNull(f.repository.getArticle(legacy));assertEquals("Saved Chinese original",f.repository.getArticle(id)!!.contentText)
        assertTrue(f.repository.getArticle(id)!!.isRead);assertTrue(f.repository.getArticle(id)!!.favoriteSaved)
        f.repository.mergeCloudRssInventory(feed.copy(items=listOf(item.copy(url=CloudRssSourceIdentity.canonicalEntryLink(raw)!!))))
        assertEquals(1,f.db.phoneArticleDao().getAllForSync().size)
        f.records();assertEquals(CloudSnapshotCodec.sha256(id.toByteArray()),f.server.events.single { it.first=="rss-state" }.second.getString("articleIdHash"))
    }

    @Test fun rekeyedLegacyIndexStillWaitsForCompletedCanonicalBodyAndAcceptsLateOldIdReadingState()=runBlocking<Unit> {
        val f=fixture();f.subscription();val url="https://entry.test/legacy"
        val legacy=com.lightningstudio.watchrss.phone.data.importer.WebArticleImporter.stableArticleId(url)
        val canonical=CloudRssEntryIdentity.id("tag:entry.test,2026:legacy",url,null,"")
        val row=article(legacy,"Legacy saved original").copy(url=url,favoriteSaved=true,favoriteChangedAt=100)
        f.db.phoneArticleDao().upsert(row)
        val value=JSONObject(CloudRssStateCodec.encode(listOf(row),0).toString(Charsets.UTF_8)).getJSONArray("articles").getJSONObject(0)
        val recordId=PhoneCloudRecordCodec.id(key,session.userId,"article",value)
        f.server.records[recordId]=JSONObject().put("recordId",recordId).put("revision","1").put("envelope",PhoneCloudRecordCodec.encrypt(key,session.userId,recordId,1,"article",value));f.server.revision=1
        f.sync.sync(session,false,"epoch",requireAvailable=true)
        val item=com.lightningstudio.watchrss.phone.data.importer.ImportedRssItem(url,"Pool title","",null,"Pool summary",null,"tag:entry.test,2026:legacy")
        f.repository.mergeCloudRssInventory(com.lightningstudio.watchrss.phone.data.importer.ImportedRssSource("https://feed.test/rss","Feed","",null,null,listOf(item),"pool"))
        assertNull(f.repository.getArticle(legacy));assertEquals(canonical,f.repository.canonicalRssArticleId(legacy))
        f.records();assertFalse(f.server.records.getValue(recordId).optBoolean("tombstone"))
        val late=CloudContentPolicy.rssState(value.put("isRead",true).put("readingPositionChangedAt",500),CloudRssSourceIdentity.sourceKey("https://feed.test/rss"))
        f.backup.applyRssInteraction(late)
        assertTrue(f.repository.getArticle(canonical)!!.isRead)
        assertEquals("Legacy saved original",f.repository.getArticle(canonical)!!.contentText)
        val image=f.image()!!
        val archive=WatchRssBackupArchive.read(ByteArrayInputStream(codec.restoreObjects(key,image.manifest){image.newCiphertextChunks.getValue(it)}.getValue("private-library.wrss")))
        assertEquals(canonical,archive.articles.single().articleId)
        assertTrue(archive.appMeta.none { it.key.startsWith("rss_identity_alias:") })
        f.sync.retireSavedRssIndexes(session,"epoch",setOf(canonical))
        assertTrue(f.server.records.getValue(recordId).getBoolean("tombstone"))
        val otherBackend=PhoneCompanionRepository(f.db.phoneSavedItemDao(),f.db.phoneArticleDao(),f.db.phoneRssSourceDao(),"phone",appMetaDao=f.db.appMetaDao(),cloudBackendBaseUrl="https://other.invalid")
        assertEquals(legacy,otherBackend.canonicalRssArticleId(legacy))
    }

    @Test fun invalidMetadataWithoutIdentityDoesNotDiscardHealthyArchiveRowsOrRetryForever()=runBlocking<Unit> {
        val f=fixture();val good=article("healthy-orphan","Healthy original",true);val bad=article("lost-identity","Unknown identity original",true)
        val bytes=rewriteArchive(cloudArchive(listOf(good,bad))) { name,body ->
            if (name!="articles.json") body else JSONArray(body.toString(Charsets.UTF_8)).also { rows ->
                for (i in 0 until rows.length()) if (rows.getJSONObject(i).getString("articleId")==bad.articleId) rows.put(i,JSONObject.NULL)
            }.toString().toByteArray()
        }
        val restored=f.backup.restorePrivateLibrary(bytes)
        assertEquals(good.contentText,f.repository.getArticle(good.articleId)!!.contentText)
        assertNull(f.repository.getArticle(bad.articleId));assertTrue(restored.warnings.isNotEmpty())
        assertTrue(restored.warnings.all { it.permanent })
        val state=PhoneCloudStateStore(com.lightningstudio.watchrss.phone.testing.MemoryPreferences());state.bindScope("a","b");state.libraryEpoch="one"
        assertTrue(settleCloudRestoreWarnings(state,"head",restored.warnings,{}).pending.isEmpty())
        assertTrue(settleCloudRestoreWarnings(state,"head",restored.warnings,{}).visible.isEmpty())
        val localBytes=rewriteArchive(bytes) { name,body -> if (name=="manifest.json") JSONObject(body.toString(Charsets.UTF_8)).put("cloudContentOnly",false).toString().toByteArray() else body }
        assertThrows(Exception::class.java) { WatchRssBackupArchive.read(ByteArrayInputStream(localBytes)) }
    }

    @Test fun structuralArchiveDamageIsAbandonedOnceWhileHealthyRowsAndFutureRepairedHeadRestore()=runBlocking<Unit> {
        val f=fixture();val state=PhoneCloudStateStore(com.lightningstudio.watchrss.phone.testing.MemoryPreferences())
        state.bindScope("https://test.invalid",session.userId);state.libraryEpoch="epoch"
        val good=article("good-structural","Good original",true)
        val missing=article("missing-structural","",true)
        val malformed=article("bad-structural","Body",true).copy(title="")
        val result=f.backup.restorePrivateLibrary(cloudArchive(listOf(good,missing,malformed)))
        assertEquals(2,result.warnings.size);assertTrue(result.warnings.all { it.permanent })
        val settled=settleCloudRestoreWarnings(state,"immutable-head-1",result.warnings,{})
        assertEquals(2,settled.visible.size);assertTrue(settled.pending.isEmpty())
        if (settled.pending.isEmpty()) state.markApplied("remote",1,true)
        assertEquals(1L,state.appliedSequence("remote",true));assertEquals(good.contentText,f.repository.getArticle(good.articleId)!!.contentText)
        assertTrue(settleCloudRestoreWarnings(state,"immutable-head-1",result.warnings,{}).visible.isEmpty())
        // A later immutable snapshot is not covered by old abandonment; its corrected original is restored.
        assertTrue(state.abandonedRestoreIds("repaired-head-2").isEmpty())
        val fixed=f.backup.restorePrivateLibrary(cloudArchive(listOf(missing.copy(contentText="Repaired original"),malformed.copy(title="Repaired title"))))
        assertTrue(fixed.warnings.isEmpty());assertEquals("Repaired original",f.repository.getArticle(missing.articleId)!!.contentText)
    }

    @Test fun localOnlySubscriptionsSyncOriginalUrlsWithoutPoolCallsWarningsOrInteractionStates()=runBlocking<Unit> {
        val f=fixture()
        val urls=listOf("http://192.168.1.1/rss", "http://[::ffff:10.0.0.1]/rss", "https://reader:secret@feed.test/rss")
        urls.forEachIndexed { i,url ->
            f.db.phoneRssSourceDao().upsert(PhoneRssSourceEntity(url,"phone","Local $i","",null,null,1,1,1,false,false,0))
            f.db.phoneArticleDao().upsert(article("local-$i").copy(rssSourceUrl=url,isRead=true,readingPositionChangedAt=100))
            f.repository.rememberPublicRssSource(url,"old-pool-id-$i")
            val legacy=CloudContentPolicy.rssState(JSONObject().put("articleId","local-$i").put("isRead",false).put("readingPositionChangedAt",1000),"old-pool-id-$i")
            f.backup.applyRssInteraction(legacy)
            val oldIndex=JSONObject(CloudRssStateCodec.encode(listOf(f.repository.getArticle("local-$i")!!.copy(isRead=false,readingPositionChangedAt=2000)),0).toString(Charsets.UTF_8)).getJSONArray("articles").getJSONObject(0)
            f.backup.applyCloudRecord("article",oldIndex)
            assertTrue(f.repository.getArticle("local-$i")!!.isRead)
        }
        f.records()
        assertEquals(urls.toSet(),f.server.events.filter { it.first=="source" }.map { it.second.getString("url") }.toSet())
        assertTrue(f.server.events.none { it.first=="rss-state" || it.first=="article" })
        assertTrue(f.server.events.all { !it.second.has("sourceId") })
        assertTrue(f.backup.unresolvedRssStateUrls.isEmpty())
        var requests=0
        val failed=refreshCloudRssChannels(f.repository.getCloudEligibleRssSources()) { requests++;error("must not resolve") }
        assertEquals(0,requests);assertTrue(failed.isEmpty())
    }

    @Test fun localOnlyAddAndExplicitRefreshNeverAttemptPublicPoolEvenWithoutLogin()=runBlocking<Unit> {
        val localCalls=mutableListOf<String>();val publicCalls=mutableListOf<String>()
        val importer=com.lightningstudio.watchrss.phone.data.importer.RssChannelImporter(
            publicImport={ url -> publicCalls+=url;throw IllegalStateException("needs login") },
            localImport={ url -> localCalls+=url;com.lightningstudio.watchrss.phone.data.importer.ImportedRssSource("http://redirect.local/rss","Local","",null,null,emptyList(),"stale-pool") })
        for (url in listOf("http://192.168.1.1/rss", "https://user:pass@feed.test/rss")) {
            val result=importer.importUrl(url)
            assertEquals(url,result.url);assertNull(result.sourceId)
        }
        assertEquals(2,localCalls.size);assertTrue(publicCalls.isEmpty())
        try { importer.importUrl("https://public.test/rss");fail() } catch (_:IllegalStateException) { }
        assertEquals(listOf("https://public.test/rss"),publicCalls);assertEquals(2,localCalls.size)
        val f=fixture();f.db.phoneRssSourceDao().upsert(PhoneRssSourceEntity("http://10.0.0.1/rss","phone","Local","",null,null,1,1,1,false,false,0))
        assertTrue(f.backup.ensureCloudRssSourceIds { error("must not resolve local source") }.isEmpty())
    }

    @Test fun saveSortingAndClocksDoNotPublishButContentAndMembershipChangesDo()=runBlocking<Unit> {
        val f=fixture();val saved=article("saved").copy(favoriteSaved=true,favoriteChangedAt=20,favoriteSortOrder=1)
        val imported=article("imported",private=true)
        f.db.phoneArticleDao().upsertAll(listOf(saved,imported))
        val first=f.image()!!;val fingerprint=f.backup.privateCloudFingerprint()
        f.db.phoneArticleDao().upsert(saved.copy(favoriteChangedAt=5000,favoriteSortOrder=99,watchLaterChangedAt=6000,watchLaterSortOrder=100))
        f.db.phoneArticleDao().upsert(imported.copy(independentChangedAt=7000,independentSortOrder=88))
        assertEquals(fingerprint,f.backup.privateCloudFingerprint());assertNull(f.image(first))
        val edited=f.repository.getArticle("saved")!!.copy(title="Edited private title")
        f.db.phoneArticleDao().upsert(edited)
        val next=f.image(first)!!
        val archive=codec.restoreObjects(key,next.manifest){(first.newCiphertextChunks+next.newCiphertextChunks).getValue(it)}.getValue("private-library.wrss")
        val exported=WatchRssBackupArchive.read(ByteArrayInputStream(archive)).articles.single { it.articleId=="saved" }
        assertEquals(5000L,exported.favoriteChangedAt);assertEquals(99L,exported.favoriteSortOrder)
        f.db.phoneArticleDao().upsert(edited.copy(watchLaterSaved=true))
        assertNotNull(f.image(next))
    }

    @Test fun recordsOfflineStillRestoresSnapshotAndNeverFallsBackToPublishingIt()=runBlocking<Unit> {
        val old=fixture();old.db.phoneArticleDao().upsert(article("private","Recover despite records outage",true))
        val image=old.image()!!
        for (unavailable in listOf(false,true)) {
            val fresh=fixture();fresh.server.failReads=!unavailable;fresh.server.recordsUnavailable=unavailable
            assertFalse(readCloudRecords({}) { fresh.sync.sync(session,false,"epoch",requireAvailable=true) })
            val archive=codec.restoreObjects(key,image.manifest){image.newCiphertextChunks.getValue(it)}.getValue("private-library.wrss")
            assertTrue(fresh.backup.restorePrivateLibrary(archive).warnings.isEmpty())
            assertEquals("Recover despite records outage",fresh.repository.getArticle("private")!!.contentText)
            var published=false
            try { fresh.records();published=fresh.image()!=null;fail("write outage must fail closed") } catch (_:java.io.IOException) { }
            assertFalse(published);assertEquals(0,fresh.server.writes)
        }
    }

    @Test fun brokenPeerRowsAndAbsentRecordBodyLeaveRetryHeadWhileOwnPrivateContentPublishes()=runBlocking<Unit> {
        val peer=codec.create(key,1,"remote",1,listOf(CloudLogicalObject("private-library.wrss",
            cloudArchive(listOf(article("broken","",true),article("good","Remote original",true))),false)))
        val f=fixture()
        val bytes=codec.restoreObjects(key,peer.manifest){peer.newCiphertextChunks.getValue(it)}.getValue("private-library.wrss")
        val result=f.backup.restorePrivateLibrary(bytes)
        assertTrue(result.warnings.any { it.articleId=="broken" });assertNotNull(f.repository.getArticle("good"))
        val missing=restoreCloudBodyOrWarn("metadata-before-body",{}) { throw MissingCloudBodyException() }
        assertNotNull(missing)
        f.db.phoneArticleDao().upsert(article("local-new","New private body",true));f.records()
        val head=CloudSnapshotHead(peer.manifest.snapshotId,"remote",1,1,CloudSnapshotCodec.sha256(peer.encryptedManifest),peer.encryptedManifest.size.toLong(),emptyMap(),emptyMap())
        val progress=CloudSnapshotMergeProgress();progress.pendingWarnings(head)
        assertTrue(progress.readyToPublish(listOf(head)){0});assertTrue(progress.appliedHeads(listOf(head)){0}.isEmpty())
        val own=f.image()!!
        val ownBytes=codec.restoreObjects(key,own.manifest){own.newCiphertextChunks.getValue(it)}.getValue("private-library.wrss")
        assertTrue(WatchRssBackupArchive.read(ByteArrayInputStream(ownBytes)).articles.any { it.articleId=="local-new" })
        assertTrue(f.server.events.any { it.first=="article" && it.second.getString("articleId")=="local-new" })
        assertFalse(CloudSnapshotMergeProgress().readyToPublish(listOf(head)){0})
    }

    @Test fun s7_1_oneReadToggleWritesExactlyOneInteractionAndNoSnapshot()=runBlocking<Unit> {
        val f=fixture();f.subscription();val row=article();f.db.phoneArticleDao().upsert(row)
        f.records();val before=f.server.writes;val image=f.image()!!
        f.db.phoneArticleDao().upsert(row.copy(isRead=true,readingPositionChangedAt=100))
        f.records()
        assertEquals(1,f.server.writes-before);assertEquals("rss-state",f.server.events.last().first)
        assertFalse(f.server.events.last().second.has("title"));assertNull(f.image(image))
    }
    @Test fun s7_2_hundredsOfAutomaticPublicEntriesWriteNoRecordsOrSnapshotChunks()=runBlocking<Unit> {
        val f=fixture();f.subscription();f.records();val image=f.image()!!;val writes=f.server.writes
        f.db.phoneArticleDao().upsertAll((1..500).map { article("entry-$it", "Public preview $it") })
        f.records();assertEquals(writes,f.server.writes);assertNull(f.image(image))
        assertTrue(image.manifest.objects.none { it.name in setOf("rss-state.json","library-sync.json") })
    }
    @Test fun s7_3_onePrivateImportAddsOnlyBodySizedCiphertext()=runBlocking<Unit> {
        val f=fixture();val random=Random(17)
        fun text(n:Int)=Base64.getEncoder().encodeToString(ByteArray(n).also(random::nextBytes))
        f.db.phoneArticleDao().upsertAll((1..24).map { article("private-$it",text(256*1024),true) })
        val first=f.image()!!;val body=text(512*1024)
        f.db.phoneArticleDao().upsert(article("new-private",body,true))
        val second=f.image(first)!!;val added=second.newCiphertextChunks.values.sumOf { it.size.toLong() }
        println("S7 private import: body=${body.length}, newCiphertext=$added, baseline=${first.manifest.objects.single().originalBytes}")
        assertTrue("Unchanged corpus reuploaded: $added",added<body.length*4L)
        val all=first.newCiphertextChunks+second.newCiphertextChunks
        val restored=codec.restoreObjects(key,second.manifest){all.getValue(it)}.getValue("private-library.wrss")
        assertEquals(body,WatchRssBackupArchive.read(ByteArrayInputStream(restored)).articles.single { it.articleId=="new-private" }.contentText)
    }
    @Test fun s7_4_missingManifestCacheAuthenticatesRemoteHeadAndCarriesWithoutAnyBodyBytes()=runBlocking<Unit> {
        val f=fixture();f.db.phoneArticleDao().upsert(article("private", "large private".repeat(20000),true));val first=f.image()!!
        val head=CloudSnapshotHead(first.manifest.snapshotId,"phone",1,1,CloudSnapshotCodec.sha256(first.encryptedManifest),first.encryptedManifest.size.toLong(),emptyMap(),emptyMap())
        // Only the current encrypted manifest is fetched; local manifest/chunk caches are absent.
        val manifest=CloudSnapshotReuse.authenticate(codec,key,head,first.encryptedManifest)
        assertTrue(CloudSnapshotReuse.canCarry(manifest,session.userId,"epoch",1,f.backup.privateCloudFingerprint()))
        val carried=codec.create(key,1,"phone",2,emptyList(),carryForwardObjects=manifest.objects)
        assertTrue(carried.newCiphertextChunks.isEmpty());assertEquals(first.manifest.objects,carried.manifest.objects)
        assertFalse(CloudSnapshotReuse.canCarry(manifest,"another","epoch",1,f.backup.privateCloudFingerprint()))
        assertFalse(CloudSnapshotReuse.canCarry(manifest,session.userId,"new-epoch",1,f.backup.privateCloudFingerprint()))
        assertFalse(CloudSnapshotReuse.canCarry(manifest,session.userId,"epoch",2,f.backup.privateCloudFingerprint()))
        assertThrows(IllegalArgumentException::class.java) { CloudSnapshotReuse.authenticate(codec,key,head.copy(sourceDeviceId="other"),first.encryptedManifest) }
    }
    @Test fun s7_5_emptyDeviceRestoresRecordsLatestArchiveAndDeferredPublicInteractions()=runBlocking<Unit> {
        val old=fixture();old.subscription()
        val private=article("book","Private full text",true).copy(isRead=true,readingPositionChangedAt=50)
        val saved=article("saved","Saved original forever").copy(favoriteSaved=true,favoriteChangedAt=40,isRead=true,readingPositionChangedAt=60)
        old.db.phoneArticleDao().upsertAll(listOf(private,saved,article("public-read").copy(isRead=true,readingPositionChangedAt=70)))
        old.records();val image=old.image()!!
        val fresh=fixture(old.server);fresh.sync.sync(session,false,"epoch",requireAvailable=true)
        assertNull(fresh.db.phoneArticleDao().getById("public-read"))
        val archive=codec.restoreObjects(key,image.manifest){image.newCiphertextChunks.getValue(it)}.getValue("private-library.wrss")
        fresh.backup.restorePrivateLibrary(archive)
        fresh.state.rssStates("").forEach { fresh.backup.applyRssInteraction(it) }
        assertEquals(private.contentText,fresh.repository.getArticle("book")!!.contentText)
        assertTrue(fresh.repository.getArticle("book")!!.isRead)
        assertEquals(saved.contentText,fresh.repository.getArticle("saved")!!.contentText)
        assertTrue(fresh.repository.getArticle("saved")!!.favoriteSaved)
        fresh.db.phoneArticleDao().upsert(article("public-read"))
        fresh.state.rssStates("").forEach { fresh.backup.applyRssInteraction(it) }
        assertTrue(fresh.repository.getArticle("public-read")!!.isRead)
        assertEquals("My channel",fresh.db.phoneRssSourceDao().getByUrl("https://feed.test/rss")!!.title)
    }
    @Test fun publicMetadataApplicationVersionSourcesAndReadClocksDoNotChangePrivateZip()=runBlocking<Unit> {
        val f=fixture();f.subscription();val private=article("book","immutable",true);f.db.phoneArticleDao().upsert(private)
        val first=f.backup.createCloudPrivateArchive()
        f.db.phoneArticleDao().upsert(private.copy(updatedAt=999,isRead=true,readingPositionChangedAt=888))
        f.db.phoneArticleDao().upsertAll((1..300).map { article("public-$it").copy(updatedAt=9999) })
        f.db.appMetaDao().set(AppMetaEntity("first_use_at","999"))
        assertArrayEquals(first,f.backup.createCloudPrivateArchive())
        val snapshot=WatchRssBackupArchive.read(ByteArrayInputStream(first));assertTrue(snapshot.cloudContentOnly);assertEquals("",snapshot.appVersion);assertTrue(snapshot.sources.isEmpty());assertEquals(10,snapshot.exportedAt)
    }
    @Test fun savedPublicBodyIsPrivateAndDisappearsAfterBothSaveFlagsAreCleared()=runBlocking<Unit> {
        val f=fixture();val row=article().copy(favoriteSaved=true,favoriteChangedAt=10);f.db.phoneArticleDao().upsert(row)
        assertEquals(1,WatchRssBackupArchive.read(ByteArrayInputStream(f.backup.createCloudPrivateArchive())).articles.size)
        f.db.phoneArticleDao().upsert(row.copy(favoriteSaved=false,favoriteChangedAt=20))
        assertTrue(WatchRssBackupArchive.read(ByteArrayInputStream(f.backup.createCloudPrivateArchive())).articles.isEmpty())
    }
    @Test fun automaticTenMinuteGateNeverSuppressesRecordsAndManualStillRequiresChange() {
        assertFalse(CloudContentPolicy.shouldPublish(true,false,1000,600999))
        assertTrue(CloudContentPolicy.shouldPublish(true,false,1000,601000))
        assertTrue(CloudContentPolicy.shouldPublish(true,true,1000,1001))
        assertFalse(CloudContentPolicy.shouldPublish(false,true,1000,999999))
    }
    @Test fun oldPublicIndexGetsEntityTombstoneAfterReplacementStateAndNeverDeletesLocalCache()=runBlocking<Unit> {
        val server=Server();val row=article().copy(isRead=true,readingPositionChangedAt=100)
        val value=JSONObject(CloudRssStateCodec.encode(listOf(row),0).toString(Charsets.UTF_8)).getJSONArray("articles").getJSONObject(0)
        val id=PhoneCloudRecordCodec.id(key,session.userId,"article",value)
        server.records[id]=JSONObject().put("recordId",id).put("revision","1").put("tombstone",false).put("envelope",PhoneCloudRecordCodec.encrypt(key,session.userId,id,1,"article",value));server.revision=1
        val f=fixture(server);f.subscription();f.records()
        val replacement=server.events.indexOfFirst { it.first=="rss-state" }
        val deletion=server.events.indexOfFirst { it.second.optBoolean("rssIndexRetired") }
        assertTrue(replacement>=0 && deletion>replacement)
        val decoded=PhoneCloudRecordCodec.decrypt(key,session.userId,id,server.records.getValue(id).getJSONObject("envelope"))
        assertTrue(decoded.second.getBoolean("deleted"));assertTrue(server.records.getValue(id).getBoolean("tombstone"))
        f.records();assertFalse(f.repository.getArticle(row.articleId)!!.deleted)
    }
    @Test fun recordWriteFailureRetainsLegacyIndexAndPendingInteraction()=runBlocking<Unit> {
        val f=fixture();f.subscription();f.db.phoneArticleDao().upsert(article().copy(isRead=true,readingPositionChangedAt=123));f.server.failWrites=true
        try { f.records();fail("write failure hidden") } catch(_:java.io.IOException) {}
        assertTrue(f.repository.getArticle("rss-1")!!.isRead);assertTrue(f.server.records.isEmpty())
        f.server.failWrites=false;f.records();assertTrue(f.server.events.any { it.first=="rss-state" })
    }
    @Test fun pendingRssStatesPersistAcrossRestartAndRemainAccountScoped() {
        val context: Context = RuntimeEnvironment.getApplication()
        val row=CloudContentPolicy.rssState(JSONObject().put("articleId","public").put("isRead",true).put("readingPositionChangedAt",100),"source")
        PhoneCloudRecordStore(context).use { store ->
            store.clear("scope-a");store.clear("scope-b");store.saveRssState("scope-a",row)
        }
        PhoneCloudRecordStore(context).use { store ->
            assertTrue(store.rssStates("scope-b").isEmpty())
            assertTrue(store.rssStates("scope-a").single().getBoolean("isRead"))
            store.saveRssState("scope-a",JSONObject(row.toString()).put("isRead",false).put("readingPositionChangedAt",200))
            assertFalse(store.rssStates("scope-a").single().getBoolean("isRead"))
            store.clear("scope-a");assertTrue(store.rssStates("scope-a").isEmpty())
        }
    }
    @Test fun publicFetchResolvesBeforeEntriesAndKeepsSourceIdForSubscription()=runBlocking<Unit> {
        val paths=mutableListOf<String>()
        val client=PhoneCloudClient(AccountEnvironment(backendBaseUrl="https://test.invalid"),http=OkHttpClient.Builder().addInterceptor { chain ->
            val req=chain.request();paths+=req.url.encodedPath
            val json=if(req.method=="POST") {
                assertEquals("/functions/v1/rss/sources/resolve",req.url.encodedPath)
                JSONObject().put("source",JSONObject().put("id","resolved-source"))
            } else JSONObject().put("source",JSONObject().put("title","Public title"))
                .put("entries",JSONArray().put(JSONObject().put("id","entry-id").put("link","https://entry.test/1").put("contentText","original")))
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("test").body(json.toString().toResponseBody("application/json".toMediaType())).build()
        }.build())
        val imported=client.rssInventory(session,"https://feed.test/rss",CloudRssInventoryMode.ALL)
        assertEquals(listOf("/functions/v1/rss/sources/resolve","/functions/v1/rss/sources/resolved-source/entries"),paths)
        assertEquals("resolved-source",imported.sourceId)
        val f=fixture();f.repository.mergeCloudRssInventory(imported)
        assertEquals("resolved-source",f.repository.publicRssSourceId(imported.url))
        f.records();assertTrue(f.server.events.all { it.first=="source" })
    }
    @Test fun publicRefreshDoesNotChangeSavedOriginalOrUploadAnotherIndexWithSameBody()=runBlocking<Unit> {
        val f=fixture();f.subscription()
        val original=article("saved", "Saved original").copy(favoriteSaved=true,favoriteChangedAt=20)
        f.db.phoneArticleDao().upsert(original)
        val first=f.image()!!
        val feed=com.lightningstudio.watchrss.phone.data.importer.ImportedRssSource("https://feed.test/rss","Changed public title","new description",null,null,
            listOf(com.lightningstudio.watchrss.phone.data.importer.ImportedRssItem(original.url,"New title","",null,"Changed public original",null,"saved"),
                com.lightningstudio.watchrss.phone.data.importer.ImportedRssItem("https://entry.test/new","New entry","",null,"Changed public original",null,"new")),"public-source")
        f.repository.mergeCloudRssInventory(feed)
        assertEquals("Saved original",f.repository.getArticle("saved")!!.contentText)
        assertNull(f.image(first))
        f.records()
        assertEquals(0,f.server.events.count { it.first=="article" })
        assertEquals("My channel",f.db.phoneRssSourceDao().getByUrl(feed.url)!!.title)
    }
    @Test fun firstS8MigrationResetsLegacyCursorExactlyOnceWithinEachAccountScope() {
        val settings=PhoneCloudStateStore(com.lightningstudio.watchrss.phone.testing.MemoryPreferences())
        var resets=0
        settings.bindScope("https://backend.test","a");settings.ensureRssChannelOnlyVersion { resets++ };settings.ensureRssChannelOnlyVersion { resets++ }
        assertEquals(1,resets)
        settings.bindScope("https://backend.test","b");settings.ensureRssChannelOnlyVersion { resets++ };assertEquals(2,resets)
    }

    @Test fun savedLegacyIndexIsRetiredOnlyAfterPrivateSnapshotSucceeds()=runBlocking<Unit> {
        val f=fixture();f.subscription();val row=article().copy(favoriteSaved=true,favoriteChangedAt=30)
        f.db.phoneArticleDao().upsert(row)
        val value=JSONObject(CloudRssStateCodec.encode(listOf(row),0).toString(Charsets.UTF_8)).getJSONArray("articles").getJSONObject(0)
        val id=PhoneCloudRecordCodec.id(key,session.userId,"article",value)
        f.server.records[id]=JSONObject().put("recordId",id).put("revision","1").put("envelope",PhoneCloudRecordCodec.encrypt(key,session.userId,id,1,"article",value));f.server.revision=1
        f.records();assertFalse(f.server.records.getValue(id).optBoolean("tombstone"))
        assertEquals(0,f.server.events.count { it.first=="article" })
        val image=f.image()!!
        assertEquals(1,WatchRssBackupArchive.read(ByteArrayInputStream(codec.restoreObjects(key,image.manifest){image.newCiphertextChunks.getValue(it)}.getValue("private-library.wrss"))).articles.size)
        f.sync.retireSavedRssIndexes(session,"epoch",setOf(row.articleId))
        assertTrue(f.server.records.getValue(id).getBoolean("tombstone"))
        assertTrue(f.repository.getArticle(row.articleId)!!.favoriteSaved)
    }
    @Test fun importedPrivateChannelRestoresWithoutResolvingIntoPublicPool()=runBlocking<Unit> {
        val f=fixture();val url=com.lightningstudio.watchrss.phone.data.model.ImportedContentIds.ROOT_SOURCE_URL
        f.db.phoneRssSourceDao().upsert(PhoneRssSourceEntity(url,"phone","My imported book","private",null,null,1,1,1,false,false,0))
        f.db.phoneArticleDao().upsert(article("book","book body",true).copy(rssSourceUrl=url))
        var resolves=0;f.backup.ensureCloudRssSourceIds { resolves++;"wrong" };assertEquals(0,resolves)
        f.records();val image=f.image()!!
        val fresh=fixture(f.server);fresh.sync.sync(session,false,"epoch",requireAvailable=true)
        fresh.backup.restorePrivateLibrary(codec.restoreObjects(key,image.manifest){image.newCiphertextChunks.getValue(it)}.getValue("private-library.wrss"))
        assertEquals("My imported book",fresh.db.phoneRssSourceDao().getByUrl(url)!!.title)
        assertEquals("book body",fresh.repository.getArticle("book")!!.contentText)
    }

    @Test fun savedRssFromOtherClientReaderDescriptorRestoresWithoutArticleIndexRecord()=runBlocking<Unit> {
        val old=fixture();old.subscription();val row=article("saved-reader","Original from mini").copy(watchLaterSaved=true,watchLaterChangedAt=40)
        old.db.phoneArticleDao().upsert(row);old.records();assertTrue(old.server.events.none { it.first=="article" })
        val metadata=JSONObject().put("articleId",row.articleId).put("url",row.url).put("title",row.title).put("rssSourceUrl",row.rssSourceUrl)
            .put("contentHash",row.contentHash).put("importedAt",row.importedAt).put("updatedAt",0)
        val name="reader/"+CloudSnapshotCodec.sha256(row.articleId.toByteArray()).take(32)+"/"+CloudSnapshotCodec.sha256(row.contentHash.toByteArray()).take(32)+".txt"
        val encrypted=codec.create(key,1,"mini",1,listOf(CloudLogicalObject(name,row.contentText.toByteArray())))
        val descriptor=CloudObjectDescriptor.fromJson(encrypted.manifest.objects.single().copy(privateArticle=metadata).toJson())
        validatePrivateReaderArticle(descriptor)
        val fresh=fixture(old.server);fresh.sync.sync(session,false,"epoch",requireAvailable=true)
        fresh.backup.restorePrivateReaderArticle(requireNotNull(descriptor.privateArticle),row.contentText) {}
        fresh.backup.applyRssInteractions(fresh.state.rssStates(""))
        assertEquals(row.contentText,fresh.repository.getArticle(row.articleId)!!.contentText)
        assertTrue(fresh.repository.getArticle(row.articleId)!!.watchLaterSaved)
        assertThrows(IllegalArgumentException::class.java) { validatePrivateReaderArticle(descriptor.copy(name="reader/wrong/identity.txt")) }
    }
    @Test fun interactedEntryFromRemovedChannelNeverResolvesAndUsesOfflineSourceKey()=runBlocking<Unit> {
        val f=fixture();f.db.phoneArticleDao().upsert(article().copy(isRead=true,readingPositionChangedAt=10))
        val urls=mutableListOf<String>()
        f.backup.ensureCloudRssSourceIds { urls+=it;"resolved-removed-channel" }
        f.backup.ensureCloudRssSourceIds { error("already resolved") }
        assertTrue(urls.isEmpty())
        f.records();assertEquals(CloudRssSourceIdentity.sourceKey("https://feed.test/rss"),f.server.events.single().second.getString("sourceId"))
    }

    private fun cloudArchive(rows: List<PhoneArticleEntity>): ByteArray = java.io.ByteArrayOutputStream().also {
        WatchRssBackupArchive.write(WatchRssBackupSnapshot(1,"",3,emptyList(),rows,emptyList(),cloudContentOnly=true),it)
    }.toByteArray()
    private fun rewriteArchive(bytes: ByteArray, change: (String,ByteArray)->ByteArray?): ByteArray {
        val out=java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { dest -> java.util.zip.ZipInputStream(ByteArrayInputStream(bytes)).use { input ->
            while(true) { val entry=input.nextEntry ?: break;val body=change(entry.name,input.readBytes()) ?: continue
                dest.putNextEntry(java.util.zip.ZipEntry(entry.name));dest.write(body);dest.closeEntry() }
        } }
        return out.toByteArray()
    }
    @Test fun oneMissingOrInvalidPrivateEntryDoesNotBlockOthersAndCanRetry()=runBlocking<Unit> {
        val f=fixture();val good=article("good","good original",true);val missing=article("missing","",true);val bad=article("bad","body",true).copy(url="")
        val result=f.backup.restorePrivateLibrary(cloudArchive(listOf(good,missing,bad)))
        assertEquals("good original",f.repository.getArticle("good")!!.contentText)
        assertEquals(setOf("missing","bad"),result.warnings.map { it.articleId }.toSet())
        assertNull(f.repository.getArticle("missing"));assertNull(f.repository.getArticle("bad"))
        val retried=f.backup.restorePrivateLibrary(cloudArchive(listOf(missing.copy(contentText="recovered"),bad.copy(url="https://watchrss.local/import-txt/bad"))))
        assertTrue(retried.warnings.isEmpty());assertEquals("recovered",f.repository.getArticle("missing")!!.contentText)
    }
    @Test fun missingZipBodyOrMetadataIsQuarantinedPerEntry()=runBlocking<Unit> {
        val f=fixture();val good=article("good","good body",true);val missing=article("missing","missing body",true);val bad=article("bad","bad body",true)
        val path="bodies/"+CloudSnapshotCodec.sha256(missing.articleId.toByteArray())+".txt"
        val archive=rewriteArchive(cloudArchive(listOf(good,missing,bad))) { name,bytes ->
            when(name) {
                path -> null
                "articles.json" -> JSONArray(bytes.toString(Charsets.UTF_8)).also { a ->
                    for(i in 0 until a.length()) if(a.getJSONObject(i).getString("articleId")=="bad") a.getJSONObject(i).remove("title")
                }.toString().toByteArray()
                else -> bytes
            }
        }
        val result=f.backup.restorePrivateLibrary(archive)
        assertEquals("good body",f.repository.getArticle("good")!!.contentText)
        assertEquals(setOf("missing","bad"),result.warnings.map { it.articleId }.toSet())
    }
    @Test fun importedReaderIdentityCreatesBaselineWithoutArticleRecordAndSkipsBrokenPeerEntry()=runBlocking<Unit> {
        val f=fixture();val row=article("imported-reader","valid original",true)
        val metadata=JSONObject().put("articleId",row.articleId).put("url",row.url).put("title",row.title).put("contentHash",row.contentHash)
        assertNull(f.backup.restorePrivateReaderArticle(metadata,row.contentText) {})
        assertEquals(row.contentText,f.repository.getArticle(row.articleId)!!.contentText)
        assertNotNull(f.backup.restorePrivateReaderArticle(JSONObject().put("articleId","bad"),"body") {})
    }
    @Test fun emptyPeerArchiveAndReaderNeverEraseLocalOriginalIncludingReplace()=runBlocking<Unit> {
        val f=fixture();val local=article("saved","precious original").copy(favoriteSaved=true,favoriteChangedAt=44,contentHtml="<p>precious original</p>")
        f.db.phoneArticleDao().upsert(local)
        for(mode in listOf(BackupImportMode.MERGE,BackupImportMode.REPLACE)) {
            val result=f.backup.restorePrivateLibrary(cloudArchive(listOf(local.copy(contentText="",contentHtml=null))),mode)
            assertEquals("missing_body",result.warnings.single().reason)
            assertEquals(local.contentText,f.repository.getArticle(local.articleId)!!.contentText)
            assertEquals(local.contentHtml,f.repository.getArticle(local.articleId)!!.contentHtml)
        }
        val metadata=JSONObject().put("articleId",local.articleId).put("url",local.url).put("title",local.title).put("rssSourceUrl",local.rssSourceUrl).put("contentHash",local.contentHash)
        assertEquals("missing_body",f.backup.restorePrivateReaderArticle(metadata,"") {}!!.reason)
        assertEquals(local.contentText,f.repository.getArticle(local.articleId)!!.contentText)
    }
    @Test fun deletedPrivateRowsAreNotArchivedAndSavedBaselineContainsSaveClocks()=runBlocking<Unit> {
        val f=fixture();val saved=article("saved","original").copy(favoriteSaved=true,favoriteChangedAt=30,favoriteSortOrder=31,watchLaterSaved=true,watchLaterChangedAt=40,watchLaterSortOrder=41)
        f.db.phoneArticleDao().upsertAll(listOf(saved,article("deleted","old",true).copy(deleted=true,deletedAt=100)))
        val snapshot=WatchRssBackupArchive.read(ByteArrayInputStream(f.backup.createCloudPrivateArchive()))
        val row=snapshot.articles.single();assertEquals(saved.articleId,row.articleId);assertTrue(row.favoriteSaved);assertTrue(row.watchLaterSaved)
        assertEquals(30,row.favoriteChangedAt);assertEquals(40,row.watchLaterChangedAt)
        assertFalse(f.backup.privateCloudBodyIds().contains("deleted"))
        val fresh=fixture();fresh.backup.restorePrivateLibrary(f.backup.createCloudPrivateArchive())
        assertTrue(fresh.repository.getArticle("saved")!!.favoriteSaved)
    }
    @Test fun resolveFailuresDoNotGateSubscriptionInteractionOrPrivateRecordsAndNeverResolveUnsubscribed()=runBlocking<Unit> {
        val f=fixture();f.subscription()
        f.db.phoneRssSourceDao().upsert(PhoneRssSourceEntity("https://bad-feed.test/rss","phone","bad","",null,null,1,1,1,false,false,0))
        f.db.phoneRssSourceDao().upsert(PhoneRssSourceEntity("https://removed.test/rss","phone","removed","",null,null,1,1,1,false,true,9))
        f.db.phoneArticleDao().upsertAll(listOf(article().copy(rssSourceUrl="https://bad-feed.test/rss",isRead=true,readingPositionChangedAt=10),article("book","private",true)))
        val attempted=mutableListOf<String>();val failed=f.backup.ensureCloudRssSourceIds { attempted+=it;throw java.io.IOException("offline") }
        assertEquals(listOf("https://bad-feed.test/rss"),attempted);assertEquals(attempted,failed)
        f.records();assertTrue(f.server.events.any { it.first=="article" });assertTrue(f.server.events.any { it.first=="source" && it.second.getString("url")=="https://bad-feed.test/rss" })
        val state=f.server.events.single { it.first=="rss-state" }.second
        assertEquals(CloudRssSourceIdentity.sourceKey("https://bad-feed.test/rss"),state.getString("sourceId"))
        assertNotNull(f.image())
    }
    @Test fun publicPoolUuidMappingsAreBackendScopedAndIgnoreUnscopedLegacyValues()=runBlocking<Unit> {
        val f=fixture();val url="https://feed.test/rss"
        f.db.appMetaDao().set(AppMetaEntity("public_rss_source:"+url,"legacy-unsafe"))
        val a=PhoneCompanionRepository(f.db.phoneSavedItemDao(),f.db.phoneArticleDao(),f.db.phoneRssSourceDao(),"phone",appMetaDao=f.db.appMetaDao(),cloudBackendBaseUrl="https://a.test/")
        val b=PhoneCompanionRepository(f.db.phoneSavedItemDao(),f.db.phoneArticleDao(),f.db.phoneRssSourceDao(),"phone",appMetaDao=f.db.appMetaDao(),cloudBackendBaseUrl="https://b.test")
        assertNull(a.publicRssSourceId(url));assertNull(b.publicRssSourceId(url))
        a.rememberPublicRssSource(url,"a-uuid");b.rememberPublicRssSource(url,"b-uuid")
        assertEquals("a-uuid",a.publicRssSourceId(url));assertEquals("b-uuid",b.publicRssSourceId(url))
    }
    @Test fun retirementBatchesAndDeletionClockDominateAllKnownClocks()=runBlocking<Unit> {
        val f=fixture();f.subscription()
        for(i in 1..105) {
            val row=article("old-$i").copy(isRead=true,readingPositionChangedAt=System.currentTimeMillis()+10000+i)
            val value=JSONObject(CloudRssStateCodec.encode(listOf(row),0).toString(Charsets.UTF_8)).getJSONArray("articles").getJSONObject(0)
            val id=PhoneCloudRecordCodec.id(key,session.userId,"article",value)
            f.server.records[id]=JSONObject().put("recordId",id).put("revision",(++f.server.revision).toString()).put("envelope",PhoneCloudRecordCodec.encrypt(key,session.userId,id,1,"article",value))
        }
        f.records()
        val tombstones=f.server.events.filter { it.second.optBoolean("rssIndexRetired") }
        assertEquals(105,tombstones.size);assertTrue(f.server.batchSizes.all { it<=50 })
        assertTrue("expected batch retirement",f.server.batchSizes.count { it==50 }>=4)
        tombstones.forEach { assertTrue(it.second.getLong("deletedAt")>it.second.getLong("readingPositionChangedAt")) }
    }
    @Test fun pendingManifestOrCompletedHeadWithoutOriginalCannotRetireSavedIndex()=runBlocking<Unit> {
        val f=fixture();f.subscription();val row=article().copy(favoriteSaved=true,favoriteChangedAt=30);f.db.phoneArticleDao().upsert(row)
        val value=JSONObject(CloudRssStateCodec.encode(listOf(row),0).toString(Charsets.UTF_8)).getJSONArray("articles").getJSONObject(0)
        val id=PhoneCloudRecordCodec.id(key,session.userId,"article",value)
        f.server.records[id]=JSONObject().put("recordId",id).put("revision","1").put("envelope",PhoneCloudRecordCodec.encrypt(key,session.userId,id,1,"article",value));f.server.revision=1
        f.records();val pending=f.image()!!;pending.manifest.settings.put("privateBodyArticleIds",JSONArray().put(row.articleId))
        val fp=f.backup.privateCloudFingerprint()
        assertTrue(CloudSnapshotReuse.canCarry(pending.manifest,session.userId,"epoch",1,fp))
        val confirmed=CloudSnapshotReuse.completedArticleIds(emptyList(),session.userId,"epoch",1,fp)
        f.sync.retireSavedRssIndexes(session,"epoch",confirmed);assertFalse(f.server.records.getValue(id).optBoolean("tombstone"))
        val noBody=pending.manifest.copy(settings=JSONObject(pending.manifest.settings.toString()).put("privateBodyArticleIds",JSONArray()))
        assertTrue(CloudSnapshotReuse.completedArticleIds(listOf(noBody),session.userId,"epoch",1,fp).isEmpty())
        f.sync.retireSavedRssIndexes(session,"epoch",CloudSnapshotReuse.completedArticleIds(listOf(pending.manifest),session.userId,"epoch",1,fp))
        assertTrue(f.server.records.getValue(id).getBoolean("tombstone"))
    }

    @Test fun exportedOriginalIdsReferToTheStagedArchiveEvenIfDatabaseChanges()=runBlocking<Unit> {
        val f=fixture();val first=article("saved-first","first original").copy(favoriteSaved=true,favoriteChangedAt=1)
        val empty=article("saved-empty","").copy(favoriteSaved=true,favoriteChangedAt=2)
        f.db.phoneArticleDao().upsertAll(listOf(first,empty));val export=f.backup.createCloudPrivateArchiveExport()
        try {
            f.db.phoneArticleDao().upsert(empty.copy(contentText="now downloaded"))
            val later=f.backup.createCloudPrivateArchiveExport()
            try {
                assertNotEquals(export.file.absolutePath,later.file.absolutePath)
                val ids=WatchRssBackupArchive.read(ByteArrayInputStream(export.file.readBytes())).articles.map { it.articleId }.toSet()
                assertEquals(setOf("saved-first"),ids);assertEquals(ids,export.articleIds)
                assertEquals(setOf("saved-first","saved-empty"),later.articleIds)
            } finally { later.file.delete() }
        } finally { export.file.delete() }
    }
    @Test fun actualResolve429OnlySkipsItsChannelAndCloudWritesContinue()=runBlocking<Unit> {
        val f=fixture();f.subscription()
        f.db.phoneRssSourceDao().upsert(PhoneRssSourceEntity("https://bad-feed.test/rss","phone","bad","",null,null,1,1,1,false,false,0))
        f.db.phoneArticleDao().upsertAll(listOf(article("book","private",true),article().copy(rssSourceUrl="https://bad-feed.test/rss",isRead=true,readingPositionChangedAt=10)))
        var fetchedGood=false
        val publicClient=PhoneCloudClient(AccountEnvironment(backendBaseUrl="https://test.invalid"),http=OkHttpClient.Builder().addInterceptor { chain ->
            val req=chain.request();var status=200
            val json=if(req.method=="POST") {
                val b=okio.Buffer();req.body!!.writeTo(b)
                if(JSONObject(b.readUtf8()).getString("url").contains("bad-feed")) { status=429;JSONObject().put("error","rss_busy") }
                else JSONObject().put("source",JSONObject().put("id","good-pool-id"))
            } else { fetchedGood=true;JSONObject().put("source",JSONObject().put("title","public")).put("entries",JSONArray()) }
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(status).message("test").body(json.toString().toResponseBody("application/json".toMediaType())).build()
        }.build())
        val failed=refreshCloudRssChannels(f.repository.getCloudEligibleRssSources()) { source ->
            f.repository.mergeCloudRssInventory(publicClient.rssInventory(session,source.url,CloudRssInventoryMode.ALL))
        }
        assertEquals(listOf("https://bad-feed.test/rss"),failed);assertTrue(fetchedGood)
        f.records();assertTrue(f.server.events.any { it.first=="article" });assertTrue(f.server.events.any { it.first=="rss-state" });assertNotNull(f.image())
    }
    @Test fun channelIsolationNeverSwallowsCancellationOrAccountFence()=runBlocking<Unit> {
        val f=fixture();f.subscription();val sources=f.repository.getCloudEligibleRssSources()
        try { refreshCloudRssChannels(sources) { throw CancellationException("stop") };fail("cancel swallowed") } catch(_:CancellationException) {}
        try { refreshCloudRssChannels(sources) { throw CloudTrustException() };fail("fence swallowed") } catch(_:CloudTrustException) {}
    }

    @Test fun lateLegacyUuidStateMergesByClockAndRewritesTheOfflineAnchor()=runBlocking<Unit> {
        val f=fixture();f.subscription();val row=article();f.db.phoneArticleDao().upsert(row)
        val metadata=JSONObject(CloudRssStateCodec.encode(listOf(row),0).toString(Charsets.UTF_8)).getJSONArray("articles").getJSONObject(0)
        val modern=CloudContentPolicy.rssState(metadata,CloudRssSourceIdentity.sourceKey("https://feed.test/rss")).put("readingPositionChangedAt",10)
        val legacy=JSONObject(modern.toString()).put("sourceId","public-source").put("readingPositionChangedAt",20).put("isRead",true)
        f.state.saveRssState("",modern);f.state.saveRssState("",legacy)
        f.backup.applyRssInteractions(f.state.rssStates(""));assertTrue(f.repository.getArticle(row.articleId)!!.isRead)
        f.records();val written=f.server.events.single { it.first=="rss-state" }.second
        assertEquals(20,written.getLong("readingPositionChangedAt"));assertTrue(written.getBoolean("isRead"))
        assertEquals(CloudRssSourceIdentity.sourceKey("https://feed.test/rss"),written.getString("sourceId"))
    }
    @Test fun nullableReaderSourceRemainsPrivateAndCanBeBackedUpAgain()=runBlocking<Unit> {
        val f=fixture();val body="independent original";val metadata=JSONObject().put("articleId","independent-reader")
            .put("url","https://independent.test/page").put("title","private page").put("rssSourceUrl",JSONObject.NULL)
            .put("contentHash",CloudSnapshotCodec.sha256(body.toByteArray()))
        assertNull(f.backup.restorePrivateReaderArticle(metadata,body) {})
        val row=f.repository.getArticle("independent-reader")!!;assertNull(row.rssSourceUrl);assertTrue(row.independentSaved)
        assertTrue(WatchRssBackupArchive.read(ByteArrayInputStream(f.backup.createCloudPrivateArchive())).articles.any { it.articleId==row.articleId })
    }

    @Test fun legacyCloudRestoreIsPerEntryWhileLocalBackupValidationStaysStrict()=runBlocking<Unit> {
        val f=fixture();val first=article("first","first body",true);val bad=article("bad","missing body",true)
        val output=java.io.ByteArrayOutputStream()
        WatchRssBackupArchive.write(WatchRssBackupSnapshot(1,"old app",3,emptyList(),listOf(first,bad),emptyList()),output)
        val path="bodies/"+CloudSnapshotCodec.sha256(bad.articleId.toByteArray())+".txt"
        val bytes=rewriteArchive(output.toByteArray()) { name,body -> if(name==path)null else body }
        assertThrows(IllegalArgumentException::class.java) { WatchRssBackupArchive.read(ByteArrayInputStream(bytes)) }
        assertEquals(1,f.backup.inspectCloudPrivateLibrary(bytes).articleCount)
        val restored=f.backup.restorePrivateLibrary(bytes)
        assertEquals("first body",f.repository.getArticle(first.articleId)!!.contentText)
        assertEquals("bad",restored.warnings.single().articleId)
    }

    @Test fun legacyBookChaptersRetireOnlyAfterBookReadingAndServerCompletedBookPackage()=runBlocking<Unit> {
        val f=fixture(bookMode=true)
        val source=com.lightningstudio.watchrss.phone.data.model.ImportedContentIds.txtNovelSourceUrl("migration")
        f.db.phoneRssSourceDao().upsert(PhoneRssSourceEntity(source,"phone","book","",null,null,1,10,0,false,false,0))
        val row=article("legacy-chapter","original",true).copy(url=com.lightningstudio.watchrss.phone.data.model.ImportedContentIds.txtNovelChapterUrl("migration",0,"a"),rssSourceUrl=source,favoriteSaved=true,favoriteChangedAt=20)
        f.db.phoneArticleDao().upsert(row)
        val value=JSONObject(CloudRssStateCodec.encode(listOf(row),0).toString(Charsets.UTF_8)).getJSONArray("articles").getJSONObject(0)
        val legacy=PhoneCloudRecordCodec.id(key,session.userId,"article",value)
        f.server.records[legacy]=JSONObject().put("recordId",legacy).put("revision","1").put("envelope",PhoneCloudRecordCodec.encrypt(key,session.userId,legacy,1,"article",value));f.server.revision=1
        f.records()
        assertFalse(f.server.records.getValue(legacy).optBoolean("tombstone"))
        assertEquals(1,f.server.events.count{it.first=="book"});assertEquals(1,f.server.events.count{it.first=="book-reading"})
        val exported=f.backup.createCloudPrivateArchiveExport()
        assertTrue(exported.articleIds.isEmpty());assertEquals(1,exported.bookIds.size)
        val image=try{codec.create(key,1,"phone",1,listOf(CloudLogicalObject("private-library.wrss",exported.file.readBytes())),snapshotSettings=JSONObject()
            .put("snapshotContentLayout",3).put("ownerUserId",session.userId).put("libraryEpoch","epoch").put("privateBodyFingerprintV2",f.backup.privateCloudFingerprint())
            .put("privateBodyBookIds",JSONArray(exported.bookIds.toList())))}finally{exported.file.delete()}
        val fp=f.backup.privateCloudFingerprint()
        f.sync.retireSavedRssIndexes(session,"epoch",emptySet(),CloudSnapshotReuse.completedBookIds(emptyList(),session.userId,"epoch",1,fp))
        assertFalse(f.server.records.getValue(legacy).optBoolean("tombstone"))
        val confirmed=CloudSnapshotReuse.completedBookIds(listOf(image.manifest),session.userId,"epoch",1,fp)
        f.sync.retireSavedRssIndexes(session,"epoch",emptySet(),confirmed)
        assertTrue(f.server.records.getValue(legacy).getBoolean("tombstone"))
        val decoded=PhoneCloudRecordCodec.decrypt(key,session.userId,legacy,f.server.records.getValue(legacy).getJSONObject("envelope")).second
        assertTrue(decoded.getBoolean("bookIndexRetired"));assertTrue(decoded.getLong("deletedAt")>20)
        f.records();assertFalse(f.repository.getArticle(row.articleId)!!.deleted);assertTrue(f.repository.getArticle(row.articleId)!!.favoriteSaved)
        val writes=f.server.writes;f.records();assertEquals(writes,f.server.writes)
    }
    @Test fun committedWholeBookTombstoneRetiresLegacyChapterWithoutUploadingDeletedBody()=runBlocking<Unit> {
        val f=fixture(bookMode=true);val source=com.lightningstudio.watchrss.phone.data.model.ImportedContentIds.txtNovelSourceUrl("deleted")
        f.db.phoneRssSourceDao().upsert(PhoneRssSourceEntity(source,"phone","book","",null,null,1,10,0,false,false,0))
        val row=article("delete-legacy","body",true).copy(url=com.lightningstudio.watchrss.phone.data.model.ImportedContentIds.txtNovelChapterUrl("deleted",0,"a"),rssSourceUrl=source)
        f.db.phoneArticleDao().upsert(row);f.books!!.ensureRegistered();f.repository.deleteRssSource(source)
        val value=JSONObject(CloudRssStateCodec.encode(listOf(row),0).toString(Charsets.UTF_8)).getJSONArray("articles").getJSONObject(0)
        val legacy=PhoneCloudRecordCodec.id(key,session.userId,"article",value)
        f.server.records[legacy]=JSONObject().put("recordId",legacy).put("revision","1").put("envelope",PhoneCloudRecordCodec.encrypt(key,session.userId,legacy,1,"article",value));f.server.revision=1
        f.records();assertTrue(f.server.records.getValue(legacy).getBoolean("tombstone"));assertTrue(f.books.availableBooks().isEmpty())
        assertTrue(f.repository.getArticle(row.articleId)!!.deleted)
    }

}
