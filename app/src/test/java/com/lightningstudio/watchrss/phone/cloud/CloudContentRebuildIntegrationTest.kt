package com.lightningstudio.watchrss.phone.cloud

import android.app.Application
import android.content.Context
import android.net.*
import androidx.room.Room
import com.lightningstudio.watchrss.phone.account.*
import com.lightningstudio.watchrss.phone.data.backup.*
import com.lightningstudio.watchrss.phone.data.db.*
import com.lightningstudio.watchrss.phone.data.note.NoteRepository
import com.lightningstudio.watchrss.phone.data.reader.ReaderPresetRepository
import com.lightningstudio.watchrss.phone.data.repo.PhoneCompanionRepository
import com.lightningstudio.watchrss.phone.testing.MemoryPreferences
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkInfo
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE, sdk=[35], application=Application::class, shadows=[CloudContentRebuildIntegrationTest.FixtureResources::class])
class CloudContentRebuildIntegrationTest {
    @org.robolectric.annotation.Implements(android.content.res.Resources::class)
    class FixtureResources : org.robolectric.shadows.ShadowResources() {
        @org.robolectric.annotation.RealObject lateinit var resources:android.content.res.Resources
        @org.robolectric.annotation.Implementation
        protected fun getBoolean(id:Int):Boolean = if(id ushr 24 == 0x7f) {
            id == androidx.work.R.bool.workmanager_test_configuration
        } else org.robolectric.shadow.api.Shadow.directlyOn(resources,android.content.res.Resources::class.java,"getBoolean",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType,id))
    }
    class FixtureKeyStore : java.security.KeyStoreSpi() {
        override fun engineGetKey(alias:String?,password:CharArray?)=pair.private
        override fun engineGetCertificate(alias:String?)=object:java.security.cert.Certificate("fixture") {
            override fun getPublicKey()=pair.public
            override fun getEncoded()=pair.public.encoded
            override fun verify(key:java.security.PublicKey?)=Unit
            override fun verify(key:java.security.PublicKey?,provider:String?)=Unit
            override fun toString()="fixture-certificate"
        }
        override fun engineGetCertificateChain(alias:String?)=arrayOf(engineGetCertificate(alias))
        override fun engineGetCreationDate(alias:String?)=java.util.Date(0)
        override fun engineSetKeyEntry(alias:String?,key:java.security.Key?,password:CharArray?,chain:Array<out java.security.cert.Certificate>?)=Unit
        override fun engineSetKeyEntry(alias:String?,key:ByteArray?,chain:Array<out java.security.cert.Certificate>?)=Unit
        override fun engineSetCertificateEntry(alias:String?,cert:java.security.cert.Certificate?)=Unit
        override fun engineDeleteEntry(alias:String?){deleted++}
        override fun engineAliases():java.util.Enumeration<String> = java.util.Collections.emptyEnumeration()
        override fun engineContainsAlias(alias:String?)=true
        override fun engineSize()=1
        override fun engineIsKeyEntry(alias:String?)=true
        override fun engineIsCertificateEntry(alias:String?)=false
        override fun engineGetCertificateAlias(cert:java.security.cert.Certificate?)="fixture"
        override fun engineStore(stream:java.io.OutputStream?,password:CharArray?)=Unit
        override fun engineLoad(stream:java.io.InputStream?,password:CharArray?)=Unit
        companion object {
            val pair=java.security.KeyPairGenerator.getInstance("EC").apply{initialize(java.security.spec.ECGenParameterSpec("secp256r1"))}.generateKeyPair()
            var deleted=0
        }
    }
    @Before fun prepareKeyStore() {
        val app:Context=RuntimeEnvironment.getApplication()
        try { androidx.work.WorkManager.getInstance(app).cancelAllWork().result.get(5,java.util.concurrent.TimeUnit.SECONDS) }
        catch (_:IllegalStateException) { androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().setMinimumLoggingLevel(android.util.Log.ERROR).build()) }
        FixtureKeyStore.deleted=0
        java.security.Security.addProvider(object:java.security.Provider("cloud-r45-fixture",1.0,"in-memory device identity") {
            init {put("KeyStore.AndroidKeyStore",FixtureKeyStore::class.java.name)}
        })
    }
    private val before="22222222-2222-4222-8222-222222222222"
    private val after="33333333-3333-4333-8333-333333333333"
    private val epoch="11111111-1111-4111-8111-111111111111"
    private val session=PhoneAccountSession("rebuild-owner","***","session","refresh",Long.MAX_VALUE)
    private val key=ByteArray(32) {it.toByte()}
    private val fixtures=mutableListOf<Fixture>()
    private inner class Fixture(val initialEmpty:Boolean=true, val failStage:String?=null, val alwaysFail:Boolean=false) {
        val context:Context=RuntimeEnvironment.getApplication()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val db=Room.inMemoryDatabaseBuilder(context,PhoneCompanionDatabase::class.java).allowMainThreadQueries().build()
        val bookDir=java.io.File.createTempFile("cloud-r45-local-books-","",context.cacheDir).apply{delete();mkdirs()}
        val books=com.lightningstudio.watchrss.phone.data.book.ImportedBookStore(db,bookDir,null,"phone")
        val repository=PhoneCompanionRepository(db.phoneSavedItemDao(),db.phoneArticleDao(),db.phoneRssSourceDao(),"phone",syncChangeLogDao=db.syncChangeLogDao(),syncPeerStateDao=db.syncPeerStateDao(),appMetaDao=db.appMetaDao(),cloudBackendBaseUrl="https://test.invalid",books=books)
        val reader=ReaderPresetRepository(context,db,db.readerPresetDao(),"phone",scope)
        val backup=WatchRssBackupService(context,db,repository,reader,"phone")
        val state=PhoneCloudStateStore(MemoryPreferences())
        val keys=CloudKeyManager(null,preferencesOverride=MemoryPreferences()).also {it.bindBackend("https://test.invalid");it.saveVerifiedKeys(session.userId,mapOf(1 to key))}
        val publicKey=keys.devicePublicKeySpki(session.userId,"phone")
        val cacheDir=java.io.File.createTempFile("cloud-r45-","",context.cacheDir).apply{delete();mkdirs()}
        val cache=CloudLocalCache(cacheDir)
        val sessionStore=EncryptedAccountSessionStore(context).also { store ->
            EncryptedAccountSessionStore::class.java.getDeclaredField("prefsRef").apply{isAccessible=true}.set(store,MemoryPreferences())
        }
        val env=AccountEnvironment(backendBaseUrl="https://test.invalid")
        val account=PhoneAccountRepository(env,sessionStore,PhoneInstallationIdentity(context),PhoneAccountClient(env,LicenseDeviceIdentity(context)),"phone")
        var generation=after
        var sequence=0L
        var revision=0L
        var completed=0
        var recordsPosted=0
        var r48WriteEnabled=false
        var formatFloor=false
        var deduplicateStoredChunks=false
        var failed=false
        var putCount=0
        var httpRequests=0
        var requestBodyBytes=0L
        val plans=mutableListOf<JSONObject>()
        val objects=linkedMapOf<String,JSONObject>()
        val uploaded=mutableSetOf<String>()
        val r48Blobs=linkedMapOf<String,ByteArray>()
        val r48BlobReads=mutableListOf<String>()
        val latestObjectIds=mutableSetOf<String>()
        val ready=mutableListOf<JSONObject>()
        val recordRows=linkedMapOf<String,JSONObject>()
        val afterQueries=mutableListOf<String>()
        var mutateDuringPull=false
        var mutatedDuringPull=false
        var mutateDuringUpload=false
        var mutatedDuringUpload=false
        val client=PhoneCloudClient(env,http=OkHttpClient.Builder().addInterceptor { chain ->
            synchronized(this) {
                val request=chain.request();val path=request.url.encodedPath;httpRequests++;requestBodyBytes+=request.body?.contentLength()?.coerceAtLeast(0) ?: 0
                if(path.startsWith("/r48-fixture-blob/")) {
                    val sha=path.substringAfterLast('/');r48BlobReads+=sha
                    return@addInterceptor Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture").body(r48Blobs.getValue(sha).toResponseBody()).build()
                }
                val buffer=okio.Buffer();request.body?.writeTo(buffer)
                val body=if(request.method=="POST")JSONObject(buffer.readUtf8())else JSONObject()
                var code=200
                fun shouldFail(stage:String)=failStage==stage && (!failed || alwaysFail)
                val result=if(formatFloor && request.method!="GET" && (path.endsWith("/records") || path.endsWith("/reserve") || path.endsWith("/complete") || path.contains("/uploads/")) && request.header("x-watchrss-r48-write-v1")!="1"){code=426;JSONObject().put("error","cloud_format_upgrade_required")}else if(isCloudContentWrite(request.method,path)&&request.header("x-watchrss-content-generation")!=generation){code=409;JSONObject().put("error","cloud_content_rebuild_required")}else when {
                    path.endsWith("/bootstrap") -> bootstrap()
                    path.endsWith("/ack") -> JSONObject()
                    path.endsWith("/format-capabilities") -> JSONObject().put("protocol","watchrss-content-evolution").put("version",1)
                        .put("phaseA",JSONObject().put("cloudFormatR48ReadV1",true).put("cloudFormatR48WriteV1",r48WriteEnabled)).put("phaseB",JSONObject().put("cloudFormatMigrationR48V1",false))
                    path.endsWith("/devices/register") -> JSONObject()
                    path.endsWith("/records") && request.method=="GET" -> {
                        val cursor=request.url.queryParameter("after")!!;afterQueries+=cursor
                        if(mutateDuringPull&&!mutatedDuringPull){mutatedDuringPull=true;runBlocking{repository.setRssSourcePinned("http://127.0.0.1/rss",true)}}
                        val remaining=recordRows.values.filter{it.getString("revision").toLong()>cursor.toLong()}.sortedBy{it.getString("revision").toLong()}
                        val page=remaining.take(50)
                        JSONObject().put("formatVersion",1).put("items",JSONArray(page)).put("cursor",page.lastOrNull()?.getString("revision") ?: revision.toString()).put("headRevision",revision.toString()).put("hasMore",remaining.size>page.size)
                            .put("contentGeneration",generation).put("contentEmpty",ready.isEmpty()&&recordRows.isEmpty())
                    }
                    path.endsWith("/records") -> {
                        assertEquals(generation,request.header("x-watchrss-content-generation"))
                        val items=body.getJSONArray("items");val result=JSONArray()
                        for(i in 0 until items.length()) {val item=items.getJSONObject(i);val id=item.getString("recordId");assertEquals(recordRows[id]?.getString("revision")?:"0",item.getString("expectedRevision"));recordRows[id]=JSONObject(item.toString()).put("revision",(++revision).toString());result.put(JSONObject().put("recordId",id).put("revision",revision.toString()));recordsPosted++}
                        JSONObject().put("items",result)
                    }
                    path.endsWith("/heads") -> JSONObject().put("heads",JSONArray(ready)).put("nextOffset",JSONObject.NULL).put("contentGeneration",generation).put("contentEmpty",ready.isEmpty()&&recordRows.isEmpty())
                    request.method=="GET" && path.contains("/cloud/snapshots/") -> {
                        val plan=plans.single{it.getString("snapshotId")==path.substringAfterLast('/')}
                        val head=JSONObject(ready.single{it.getString("id")==plan.getString("snapshotId")}.toString())
                        head.put("manifestSignedUrl","https://test.invalid/r48-fixture-blob/"+head.getString("manifestSha256"))
                        val chunks=JSONArray()
                        if(request.url.queryParameter("manifestOnly")!="true")for(i in 0 until plan.getJSONArray("chunks").length()) {
                            val c=plan.getJSONArray("chunks").getJSONObject(i)
                            chunks.put(JSONObject(c.toString()).put("signedUrl","https://test.invalid/r48-fixture-blob/"+c.getString("sha256")))
                        }
                        JSONObject().put("snapshot",head).put("chunks",chunks)
                    }
                    path.endsWith("/reserve") -> {
                        assertEquals(3,body.getInt("contentLayout"));assertEquals(generation,request.header("x-watchrss-content-generation"));assertTrue(body.getLong("deviceSequence")>sequence)
                        sequence=body.getLong("deviceSequence");plans+=body
                        val missing=JSONArray();latestObjectIds.clear()
                        fun add(kind:String,sha:String,size:Long) {
                            if(deduplicateStoredChunks && sha in r48Blobs)return
                            val id=UUID.randomUUID().toString();val target=JSONObject().put("kind",kind).put("sha256",sha).put("sizeBytes",size).put("objectPath","owner/$id").put("reservationId",id).put("uploadUrl","https://test.invalid/functions/v1/cloud/uploads/$id").put("transport","epoch-proxy").put("uploadMethod","PUT")
                            objects[id]=target;latestObjectIds+=id;missing.put(target)
                        }
                        add("manifest",body.getJSONObject("manifest").getString("sha256"),body.getJSONObject("manifest").getLong("sizeBytes"))
                        val chunks=body.getJSONArray("chunks");for(i in 0 until chunks.length()){val c=chunks.getJSONObject(i);add("chunk",c.getString("sha256"),c.getLong("sizeBytes"))}
                        JSONObject().put("snapshotId",body.getString("snapshotId")).put("reservedBytes",100).put("missingObjects",missing)
                    }
                    path.endsWith("/refresh") -> {
                        assertEquals(generation,request.header("x-watchrss-content-generation"))
                        val id=path.split('/').dropLast(1).last()
                        if(shouldFail("expired")){failed=true;code=400;JSONObject().put("error","reservation_expired")}
                        else JSONObject(objects.getValue(id).toString()).put("alreadyUploaded",id in uploaded)
                    }
                    request.method=="PUT" -> {
                        putCount++
                        if(shouldFail("put")) {failed=true;generation=before;recordRows.clear();revision=0;objects.clear();uploaded.clear();code=409;JSONObject().put("error","cloud_content_rebuild_required")}
                        else if(request.header("x-watchrss-content-generation")!=generation){code=409;JSONObject().put("error","cloud_content_rebuild_required")}
                        else {
                            val id=path.substringAfterLast('/');val bytes=buffer.readByteArray();val target=objects.getValue(id)
                            assertEquals(target.getLong("sizeBytes"),bytes.size.toLong());assertEquals(target.getString("sha256"),CloudSnapshotCodec.sha256(bytes));uploaded+=id;r48Blobs[target.getString("sha256")]=bytes
                            if(mutateDuringUpload&&!mutatedDuringUpload){mutatedDuringUpload=true;runBlocking{repository.setRssSourcePinned("http://127.0.0.1/rss",true)}}
                            JSONObject()
                        }
                    }
                    path.endsWith("/complete") -> {
                        assertEquals(generation,request.header("x-watchrss-content-generation"))
                        if(shouldFail("complete")){failed=true;code=404;JSONObject().put("error","snapshot_not_found")}
                        else {
                            val plan=plans.last();assertEquals(plan.getString("snapshotId"),body.getString("snapshotId"));assertTrue(latestObjectIds.all{it in uploaded})
                            completed++;ready.clear();ready+=JSONObject().put("id",plan.getString("snapshotId")).put("sourceDeviceId","phone").put("deviceSequence",sequence).put("keyVersion",1)
                                .put("manifestSha256",plan.getJSONObject("manifest").getString("sha256")).put("manifestSizeBytes",plan.getJSONObject("manifest").getLong("sizeBytes")).put("parentHeads",JSONObject()).put("observedHeads",JSONObject()).put("contentLayout",3)
                            JSONObject()
                        }
                    }
                    else -> error("unexpected route $path")
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture").body(result.toString().toResponseBody("application/json".toMediaType())).build()
            }
        }.build())
        lateinit var service:PhoneCloudSyncService
        val recordStore get()=(PhoneCloudSyncService::class.java.getDeclaredField("recordStore").apply{isAccessible=true}.get(service) as PhoneCloudRecordStore)
        fun bootstrap()=JSONObject().put("libraryEpoch",epoch).put("contentGeneration",generation).put("contentEmpty",initialEmpty&&ready.isEmpty()&&recordRows.isEmpty())
            .put("member",JSONObject().put("active",true).put("writable",true).put("readable",true).put("quotaBytes",1000000).put("usedBytes",if(completed>0)777 else 0).put("reservedBytes",if(completed>0)0 else 100))
            .put("keyVersionFloor",0).put("keyCommitments",JSONArray().put(JSONObject().put("keyVersion",1).put("commitment",CloudProtocolV2.commitment(key,session.userId,epoch,1))))
            .put("keyEnvelopes",JSONArray()).put("devices",JSONArray().put(JSONObject().put("deviceId","phone").put("platform","phone").put("publicKeySpki",publicKey).put("keyVersion",1).put("lastSequence",sequence)))
        suspend fun initialize() {
            sessionStore.save(session)
            state.bindScope(env.backendBaseUrl,session.userId);state.ownerUserId=session.userId;state.libraryEpoch=epoch
            state.observeContentGeneration(before,false){};state.markApplied("old-phone",900,true);state.markUploaded("stale",mapOf("old-phone" to "gone"),true);state.lastSnapshotPublishedAt=System.currentTimeMillis()
            val oldId="11111111-1111-4111-8111-111111111119";cache.storePendingManifest(client.scopeId(session),oldId,byteArrayOf(1),emptyList())
            val manager=context.getSystemService(ConnectivityManager::class.java)
            val shadow=Shadows.shadowOf(manager)
            shadow.setActiveNetworkInfo(ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,ConnectivityManager.TYPE_WIFI,0,true,true))
            val caps=NetworkCapabilities();Shadows.shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);Shadows.shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            shadow.setNetworkCapabilities(manager.activeNetwork,caps)
            service=PhoneCloudSyncService(context,account,backup,repository,NoteRepository(db.noteDao(),"phone"),"phone",client,keyManager=keys,settings=state,cache=cache)
            val owner=client.scopeId(session)
            recordStore.advance(owner,"900");recordStore.save(owner,"stale-cas","900","stale-hash")
            recordStore.saveRawEnvelope(owner,"stale-cas","{}")
            recordStore.quarantine(owner,QuarantinedCloudRecord("old-quarantine","900",9,"missing_key","{}"))
            repository.mergeRssSourcesFromSync(listOf(PhoneRssSourceEntity("http://127.0.0.1/rss","phone","Local source","",null,null,10,10,0,false,false,0)))
        }
        fun close(){scope.cancel();if(::service.isInitialized) (PhoneCloudSyncService::class.java.getDeclaredField("recordStore").apply{isAccessible=true}.get(service) as PhoneCloudRecordStore).close();db.close();cacheDir.deleteRecursively();bookDir.deleteRecursively()}
    }
    private suspend fun fixture(stage:String?=null,always:Boolean=false)=Fixture(failStage=stage,alwaysFail=always).also{fixtures+=it;it.initialize()}
    @After fun close(){fixtures.forEach{it.close()};java.security.Security.removeProvider("cloud-r45-fixture")}
    @Test fun clearedServerRebuildsRealRecordsAndEncryptedSnapshotWithoutLosingIdentityOrLocalLibrary()=runBlocking {
        val f=fixture();val result=f.service.syncNow(manual=true);assertTrue(result.snapshotUploaded);assertEquals(1,f.completed);assertTrue(f.recordsPosted>0)
        assertEquals(CloudSyncPhase.COMPLETE,f.service.state.value.phase);assertEquals(777,f.service.state.value.member!!.usedBytes);assertEquals(0,f.service.state.value.member!!.reservedBytes)
        assertEquals(after,f.state.contentGeneration);assertEquals(epoch,f.state.libraryEpoch);assertEquals(0,f.state.appliedSequence("old-phone",true));assertEquals(listOf("0"),f.afterQueries.distinct())
        assertEquals(session,f.account.session.value);assertArrayEquals(key,f.keys.getAccountKey(session.userId,1));assertEquals(f.publicKey,f.keys.devicePublicKeySpki(session.userId,"phone"));assertEquals(1,f.db.phoneRssSourceDao().getAllForSync().size)
        assertNull(f.cache.loadPendingManifest(f.client.scopeId(session)));assertEquals(0,FixtureKeyStore.deleted)
        assertNull(f.recordStore.record(f.client.scopeId(session),"stale-cas"));assertNull(f.recordStore.rawEnvelope(f.client.scopeId(session),"stale-cas"));assertTrue(f.recordStore.quarantined(f.client.scopeId(session)).isEmpty())
    }
    @Test fun clearedCloudRepushesAnR44BookPackageWithoutChangingLocalFingerprintOrChapterBytes()=runBlocking {
        val f=fixture();val ids=com.lightningstudio.watchrss.phone.data.model.ImportedContentIds
        val source=ids.txtNovelSourceUrl("r45-book")
        f.db.phoneRssSourceDao().upsert(PhoneRssSourceEntity(source,"phone","Full book","",null,null,1,10,1,false,false,0))
        f.db.phoneArticleDao().upsertAll((0..1).map {i-> val text="Chapter body $i";PhoneArticleEntity("chapter-$i","phone",ids.txtNovelChapterUrl("r45-book",i,"hash"),"Chapter $i","Book","",null,text,null,CloudSnapshotCodec.sha256(text.toByteArray()),1,10,false,0,0,source,"Book",false,0,0,false,0,0,false,0)})
        val original=f.books.availableBooks().single();val bytes=original.second.readBytes();val fingerprint=original.first.getString("fingerprint")
        f.service.syncNow()
        val current=f.books.availableBooks().single();assertEquals(fingerprint,current.first.getString("fingerprint"));assertArrayEquals(bytes,current.second.readBytes());assertEquals(2,f.db.phoneArticleDao().getAllForSync().size)
        val records=f.recordRows.values.map { PhoneCloudRecordCodec.decrypt(key,session.userId,it.getString("recordId"),it.getJSONObject("envelope")) }
        assertEquals(1,records.count{it.first=="book"});assertEquals(0,records.count{it.first=="article"})
        val snapshotId=f.plans.single().getString("snapshotId");val codec=CloudSnapshotCodec()
        val manifest=codec.decryptManifest(key,snapshotId,f.cache.loadManifest(f.client.scopeId(session),snapshotId)!!)
        val archive=codec.restoreObjects(key,manifest){f.cache.loadChunk(f.client.scopeId(session),it)!!}.getValue("private-library.wrss")
        val backup=WatchRssBackupArchive.read(java.io.ByteArrayInputStream(archive));assertEquals(1,backup.bookPackages.size);assertArrayEquals(bytes,backup.bookPackages.single().file.readBytes());backup.bookPackages.forEach{it.file.delete()}
        assertEquals(CloudSyncPhase.COMPLETE,f.service.state.value.phase);assertEquals(1,f.completed);assertEquals(0,FixtureKeyStore.deleted)
    }
    @Test fun changesAlreadyIncludedInPublicationDoNotLeaveTheCompletedPassBusy()=runBlocking {
        val f=fixture();f.mutateDuringPull=true
        val start=f.repository.getLibrarySyncCursor(null).localMaxSeq
        f.service.syncNow()
        assertTrue(f.repository.getLibrarySyncCursor(null).localMaxSeq>start)
        assertEquals(CloudSyncPhase.COMPLETE,f.service.state.value.phase);assertEquals(1,f.completed)
        assertTrue(androidx.work.WorkManager.getInstance(f.context).getWorkInfosForUniqueWork("watchrss-cloud-follow-up").get(5,java.util.concurrent.TimeUnit.SECONDS).none{!it.state.isFinished})
    }
    @Test fun userChangeDuringUploadQueuesFollowUpAndTheStableNextPassFinishesWithoutAnotherSnapshot()=runBlocking {
        val f=fixture();f.mutateDuringUpload=true
        f.service.syncNow();assertEquals(CloudSyncPhase.IDLE,f.service.state.value.phase)
        assertTrue(androidx.work.WorkManager.getInstance(f.context).getWorkInfosForUniqueWork("watchrss-cloud-follow-up").get(5,java.util.concurrent.TimeUnit.SECONDS).any{!it.state.isFinished})
        val second=f.service.syncNow(manual=true);assertFalse(second.snapshotUploaded);assertEquals(1,f.plans.size);assertEquals(CloudSyncPhase.COMPLETE,f.service.state.value.phase)
    }
    @Test fun formatFloorHaltsAutomaticRetriesAndPreservesPendingRecordsKeysAndArchive()=runBlocking {
        val f=fixture();f.formatFloor=true
        val pending=f.cache.loadPendingManifest(f.client.scopeId(session))!!
        val result=runCatching{f.service.syncNow(manual=true)}
        assertTrue(result.exceptionOrNull() is CloudHttpException)
        assertEquals("cloud_format_upgrade_required",f.service.state.value.errorCode)
        assertEquals("请升级到最新版本以继续同步",f.service.state.value.message)
        assertTrue(f.service.formatStatus.value.upgradeRequired)
        assertTrue(f.db.phoneRssSourceDao().getAllForSync().isNotEmpty())
        assertTrue(f.keys.hasAccountKey(session.userId));assertEquals(session.userId,f.account.session.value!!.userId)
        // The fixture includes an obsolete-generation cache, legitimately cleared by R45. Create a
        // current-generation pending archive and verify 426's halt never clears it or sends IO.
        f.cache.storePendingManifest(f.client.scopeId(session),pending.first,pending.second,emptyList())
        val requests=f.httpRequests
        f.service.syncNow();assertEquals(requests,f.httpRequests)
        assertArrayEquals(pending.second,f.cache.loadPendingManifest(f.client.scopeId(session))!!.second)
    }
    @Test fun bleSavedRssOriginalRepairPublishesInsteadOfCarryingEmptyArchiveAndThenNoops()=runBlocking {
        for(writeOverlay in listOf(false,true)) {
            val f=fixture();f.deduplicateStoredChunks=true
            val empty=PhoneArticleEntity("empty-rss","phone","https://synthetic.invalid/entry","RSS title","Site","",null,"",null,
                "placeholder-version",1,10,false,0,0,"https://synthetic.invalid/rss","RSS",true,10,10,false,0,0,false,0,
                readingProgress=.6f,readingPositionBytes=60,readingPositionChangedAt=9,isRead=true)
            f.db.phoneArticleDao().upsert(empty)
            f.service.syncNow(manual=true)
            val initial=f.plans.last().getString("snapshotId")
            val old=CloudSnapshotCodec().decryptManifest(key,initial,f.cache.loadManifest(f.client.scopeId(session),initial)!!)
            val before=f.backup.privateCloudFingerprint()
            if(writeOverlay) {
                f.r48WriteEnabled=true;f.recordStore.close()
                f.service=PhoneCloudSyncService(f.context,f.account,f.backup,f.repository,NoteRepository(f.db.noteDao(),"phone"),"phone",f.client,
                    keyManager=f.keys,settings=f.state,cache=f.cache,formatWritePolicy=CloudFormatWritePolicy(true,false))
            }
            val full=empty.copy(contentText="Full watch original\n".repeat(1000),contentHtml="<p>Full original HTML</p>",contentHash="real-original",sourceDeviceId="watch")
            val m=com.lightningstudio.watchrss.phone.connection.bluetooth.ArticleSyncBody.metadataFor(full)
            val payload=com.lightningstudio.watchrss.phone.connection.bluetooth.ArticleSyncBody.payloadForRequest(full,
                com.lightningstudio.watchrss.phone.connection.bluetooth.ArticleBodyRequest(full.articleId,m.bodyHash,m.chunkHashes.indices.toList()),m)
            assertEquals(1,f.repository.mergeChunkedArticlesFromSync(listOf(payload)))
            assertNotEquals(before,f.backup.privateCloudFingerprint())
            assertTrue(f.service.syncNow(manual=true).snapshotUploaded)
            val id=f.plans.last().getString("snapshotId")
            val current=CloudSnapshotCodec().decryptManifest(key,id,f.cache.loadManifest(f.client.scopeId(session),id)!!)
            assertNotEquals(old.settings.getString("privateBodyFingerprintV2"),current.settings.getString("privateBodyFingerprintV2"))
            val objects=CloudSnapshotCodec().restoreObjects(key,current){f.cache.loadChunk(f.client.scopeId(session),it)!!}
            val name=if(writeOverlay)CloudPrivateOverlay.articleName(full.articleId) else "private-library.wrss"
            val restored=WatchRssBackupArchive.read(java.io.ByteArrayInputStream(objects.getValue(name))).articles.single()
            assertEquals(full.contentText,restored.contentText);assertEquals(full.contentHtml,restored.contentHtml)
            val completed=f.completed;val puts=f.putCount
            assertFalse(f.service.syncNow(manual=true).snapshotUploaded)
            assertEquals(completed,f.completed);assertEquals(puts,f.putCount)
            assertEquals(CloudSyncPhase.COMPLETE,f.service.state.value.phase)
        }
    }

    @Test fun r48OverlayArticleEditAndDeleteDoNotUploadTheLegacyLibraryAndNoopDoesNotRepublish()=runBlocking {
        val f=fixture();f.deduplicateStoredChunks=true
        fun article(i:Int):PhoneArticleEntity {val text="Private original $i 中文 HTML";return PhoneArticleEntity("overlay-$i","phone","https://synthetic.invalid/$i","Title $i","Site","",null,text,"<p>$text</p>",CloudSnapshotCodec.sha256(text.toByteArray()),1,10,true,10,i.toLong(),null,null,false,0,0,false,0,0,false,0)}
        f.db.phoneArticleDao().upsertAll((0 until 24).map(::article))
        f.service.syncNow();val first=f.plans.last();val originalId=first.getString("snapshotId")
        val old=CloudSnapshotCodec().decryptManifest(key,originalId,f.cache.loadManifest(f.client.scopeId(session),originalId)!!)
        val backing=old.objects.single{it.name=="private-library.wrss"}.toRawString()
        f.r48WriteEnabled=true
        f.recordStore.close()
        f.service=PhoneCloudSyncService(f.context,f.account,f.backup,f.repository,NoteRepository(f.db.noteDao(),"phone"),"phone",f.client,keyManager=f.keys,settings=f.state,cache=f.cache,formatWritePolicy=CloudFormatWritePolicy(true,false))
        val changed=article(1).copy(contentText="Edited body",contentHtml="<p>Edited &amp; original</p>",contentHash=CloudSnapshotCodec.sha256("Edited body".toByteArray()),updatedAt=20)
        f.db.phoneArticleDao().upsert(changed)
        f.db.phoneArticleDao().upsert(article(2).copy(deleted=true,deletedAt=30,updatedAt=30))
        val result=f.service.syncNow(manual=true);assertTrue(result.snapshotUploaded)
        val id=f.plans.last().getString("snapshotId");val manifest=CloudSnapshotCodec().decryptManifest(key,id,f.cache.loadManifest(f.client.scopeId(session),id)!!)
        assertEquals(backing,manifest.objects.single{it.name=="private-library.wrss"}.toRawString())
        assertEquals(1,manifest.objects.count{it.name.startsWith("private/articles/")})
        val restored=CloudSnapshotCodec().restoreObjects(key,manifest){f.cache.loadChunk(f.client.scopeId(session),it)!!}
        val index=CloudPrivateOverlay.decode(restored.getValue(CloudPrivateOverlay.NAME))
        assertNull(index.single{it.id=="overlay-2"}.objectName)
        val restoredEdit=CloudPrivateOverlayArchive.validate(restored.getValue(CloudPrivateOverlay.articleName("overlay-1")),"overlay-1").articles.single()
        assertEquals(changed.contentHtml,restoredEdit.contentHtml)
        assertTrue(result.uploadedBytes<old.objects.single{it.name=="private-library.wrss"}.originalBytes)
        val complete=f.completed;val puts=f.putCount
        assertFalse(f.service.syncNow().snapshotUploaded);assertEquals(complete,f.completed);assertEquals(puts,f.putCount)
        assertEquals(CloudSyncPhase.COMPLETE,f.service.state.value.phase)
    }

    @Test fun r48ColdManualRestoreConsumesOverlayWithWriteGateOffAndDoesNotResurrectBackingDeletion()=runBlocking {
        val f=fixture();val vector=JSONObject(requireNotNull(javaClass.getResource("/r48-write-v1/mixed-manifest.json")).readText())
        val overlay=CloudPrivateOverlay.decode(vector.getString("overlayUtf8").toByteArray())
        val edited=java.util.Base64.getDecoder().decode(vector.getString("editedArchiveBase64"))
        val source=CloudSnapshotCodec().create(key,1,"phone",1,listOf(CloudLogicalObject("private-library.wrss",java.util.Base64.getDecoder().decode(vector.getString("legacyArchiveBase64")),false),CloudLogicalObject(CloudPrivateOverlay.NAME,CloudPrivateOverlay.encode(overlay),false),CloudLogicalObject(CloudPrivateOverlay.articleName("article-edit"),edited,false)),privateOverlay=true)
        source.newCiphertextChunks.forEach{(hash,bytes)->f.r48Blobs[hash]=bytes}
        f.r48Blobs[CloudSnapshotCodec.sha256(source.encryptedManifest)]=source.encryptedManifest
        f.plans+=JSONObject().put("snapshotId",source.manifest.snapshotId).put("chunks",JSONArray(source.manifest.allChunks.map{JSONObject().put("sha256",it.ciphertextSha256).put("sizeBytes",it.ciphertextBytes)}))
        f.sequence=1;f.ready+=JSONObject().put("id",source.manifest.snapshotId).put("sourceDeviceId","phone").put("deviceSequence",1).put("keyVersion",1).put("manifestSha256",CloudSnapshotCodec.sha256(source.encryptedManifest)).put("manifestSizeBytes",source.encryptedManifest.size).put("parentHeads",JSONObject()).put("observedHeads",JSONObject()).put("contentLayout",3)
        val preview=f.service.inspectSnapshot(source.manifest.snapshotId);assertEquals(2,preview.privateArticleCount)
        f.service.restoreSnapshot(source.manifest.snapshotId)
        val rows=f.db.phoneArticleDao().getAllForSync()
        assertEquals(2,rows.size);assertTrue(rows.none{it.articleId=="article-delete"})
        assertEquals(vector.getJSONObject("expected").getString("editedHtmlUtf8"),rows.single{it.articleId=="article-edit"}.contentHtml)
        assertEquals(CloudSyncPhase.COMPLETE,f.service.state.value.phase);assertFalse(f.r48WriteEnabled)
    }

    @Test fun r48MissingLocalOriginalCannotBecomeAnOverlayTombstone()=runBlocking {
        val f=fixture();val text="Original body"
        val row=PhoneArticleEntity("missing-original","phone","https://synthetic.invalid/missing","Original","Site","",null,text,"<p>Original</p>",CloudSnapshotCodec.sha256(text.toByteArray()),1,10,true,10,0,null,null,false,0,0,false,0,0,false,0)
        f.db.phoneArticleDao().upsert(row)
        val backing=f.backup.createCloudPrivateArchive()
        f.db.phoneArticleDao().upsert(row.copy(contentText="",contentHtml=null))
        val error=runCatching{f.backup.createPrivateOverlayExport(backing,emptyList(),emptyMap())}.exceptionOrNull()
        assertTrue(error is CloudFormatUpgradeRequired)
        assertEquals("overlay_original_missing",(error as CloudFormatUpgradeRequired).reason)
        assertFalse(f.db.phoneArticleDao().getById(row.articleId)!!.deleted)
    }

    private suspend fun runR48Budget(write:Boolean) {
        val f=fixture();f.deduplicateStoredChunks=true
        val rng=java.util.Random(4801)
        fun article(i:Int):PhoneArticleEntity {
            val text=java.util.Base64.getEncoder().encodeToString(ByteArray(2048).also(rng::nextBytes))
            return PhoneArticleEntity("budget-$i","phone","https://synthetic.invalid/budget/$i","Budget $i","Site","",null,text,"<p>$text</p>",CloudSnapshotCodec.sha256(text.toByteArray()),1,10,true,10,i.toLong(),null,null,false,0,0,false,0,0,false,0)
        }
        f.db.phoneArticleDao().upsertAll((0 until 2000).map(::article))
        val source=com.lightningstudio.watchrss.phone.data.model.ImportedContentIds.txtNovelSourceUrl("r48-budget")
        f.db.phoneRssSourceDao().upsert(PhoneRssSourceEntity(source,"phone","Budget book","",null,null,1,10,1,false,false,0))
        f.db.phoneArticleDao().upsertAll((0..1).map{i->val text="Book chapter $i";PhoneArticleEntity("budget-chapter-$i","phone",com.lightningstudio.watchrss.phone.data.model.ImportedContentIds.txtNovelChapterUrl("r48-budget",i,"hash"),"Chapter $i","Book","",null,text,null,CloudSnapshotCodec.sha256(text.toByteArray()),1,10,false,0,0,source,"Book",false,0,0,false,0,0,false,0)})
        f.service.syncNow(manual=true)
        fun latest():CloudSnapshotManifest {val id=f.plans.last().getString("snapshotId");return CloudSnapshotCodec().decryptManifest(key,id,f.cache.loadManifest(f.client.scopeId(session),id)!!)}
        val base=latest();val backing=base.objects.single{it.name=="private-library.wrss"};assertEquals(1,f.books.availableBooks().size)
        val bookBytes=f.books.availableBooks().single().second.readBytes()
        f.r48WriteEnabled=write;f.recordStore.close()
        f.service=PhoneCloudSyncService(f.context,f.account,f.backup,f.repository,NoteRepository(f.db.noteDao(),"phone"),"phone",f.client,keyManager=f.keys,settings=f.state,cache=f.cache,formatWritePolicy=CloudFormatWritePolicy(write,false))
        val measurements=JSONArray()
        suspend fun measure(name:String,edit:suspend ()->Unit) {
            val old=latest();val requests=f.httpRequests;val bytes=f.requestBodyBytes;val start=System.nanoTime()
            edit();val result=f.service.syncNow(manual=true);val current=latest()
            if(write)assertEquals(backing.toRawString(),current.objects.single{it.name=="private-library.wrss"}.toRawString())
            assertArrayEquals(bookBytes,f.books.availableBooks().single().second.readBytes())
            val oldHashes=old.allChunks.map{it.ciphertextSha256}.toSet()
            val bodyBytes=current.objects.filter{it.name.startsWith("private/articles/")}.flatMap{it.chunks}.filter{it.ciphertextSha256 !in oldHashes}.sumOf{it.ciphertextBytes.toLong()}
            if(write && name in setOf("delete","favorite"))assertEquals(0,bodyBytes)
            if(write)assertTrue(result.uploadedBytes<backing.originalBytes/20)
            measurements.put(JSONObject().put("case",name).put("uploadedCiphertextBytes",result.uploadedBytes).put("newBodyCiphertextBytes",bodyBytes).put("requests",f.httpRequests-requests).put("requestBodyBytes",f.requestBodyBytes-bytes).put("objects",current.objects.size).put("elapsedMillis",(System.nanoTime()-start)/1_000_000))
        }
        measure("add"){f.db.phoneArticleDao().upsert(article(2000))}
        measure("edit"){val row=f.db.phoneArticleDao().getById("budget-1")!!;f.db.phoneArticleDao().upsert(row.copy(title="Edited original",contentText=row.contentText+"edited",contentHtml=row.contentHtml+"<!--edited-->",contentHash=CloudSnapshotCodec.sha256((row.contentText+"edited").toByteArray()),updatedAt=20))}
        measure("delete"){val row=f.db.phoneArticleDao().getById("budget-2")!!;f.db.phoneArticleDao().upsert(row.copy(deleted=true,deletedAt=30,updatedAt=30))}
        measure("favorite"){val row=f.db.phoneArticleDao().getById("budget-3")!!;f.db.phoneArticleDao().upsert(row.copy(favoriteSaved=true,favoriteChangedAt=40,updatedAt=40))}
        println("R48_PRODUCTION_BUDGET "+JSONObject().put("writer",if(write)"overlay"else"legacy").put("baselineArchiveBytes",backing.originalBytes).put("baselineCiphertextBytes",backing.chunks.sumOf{it.ciphertextBytes.toLong()}).put("articles",2000).put("books",1).put("measurements",measurements))
        val count=f.completed;assertFalse(f.service.syncNow(manual=true).snapshotUploaded);assertEquals(count,f.completed)
    }

    @Test fun r48TwoThousandArticlesAndBookBudgetUsesTheRealProducerForAddEditDeleteAndFavorite()=runBlocking {runR48Budget(true)}
    @Test fun r48LegacyTwoThousandArticlesControlMeasuresSameOperationsAndRequestCounts()=runBlocking {runR48Budget(false)}

    @Test fun r48CacheLossAndAddColumnDoNotReencryptOrDownloadUnchangedPrivateBody()=runBlocking {
        val f=fixture();assertTrue(f.service.syncNow().snapshotUploaded)
        val originalId=f.plans.single().getString("snapshotId");val original=f.cache.loadManifest(f.client.scopeId(session),originalId)!!
        val puts=f.putCount;val keyBefore=f.keys.getAccountKey(session.userId,1)
        f.cache.clear(f.client.scopeId(session))
        f.db.openHelper.writableDatabase.execSQL("ALTER TABLE phone_articles ADD COLUMN r48_test_optional TEXT")
        f.recordStore.close()
        f.service=PhoneCloudSyncService(f.context,f.account,f.backup,f.repository,NoteRepository(f.db.noteDao(),"phone"),"phone",f.client,keyManager=f.keys,settings=f.state,cache=f.cache)
        val next=f.service.syncNow(manual=true)
        assertFalse(next.snapshotUploaded);assertEquals(0L,next.uploadedBytes);assertEquals(puts,f.putCount);assertEquals(1,f.completed)
        assertEquals(listOf(CloudSnapshotCodec.sha256(original)),f.r48BlobReads)
        assertEquals(CloudSyncPhase.COMPLETE,f.service.state.value.phase);assertEquals(epoch,f.state.libraryEpoch);assertEquals(after,f.state.contentGeneration)
        assertArrayEquals(keyBefore,f.keys.getAccountKey(session.userId,1));assertEquals(0,FixtureKeyStore.deleted)
    }
    @Test fun r48ReadonlyManifestDoesNotInventAPendingEditUntilTheUserActuallyChangesContent()=runBlocking {
        val f=fixture();f.service.syncNow()
        val head=f.ready.single();val id=head.getString("id");val codec=CloudSnapshotCodec()
        val old=codec.decryptManifest(key,id,f.cache.loadManifest(f.client.scopeId(session),id)!!)
        val doc=CloudRawDocument.parse(old.toRawString()).patch(listOf("features"),"{\"roCompat\":[\"future.manifest.v1\"]}")
        val bytes=codec.encryptManifestBytes(key,id,doc.source.toByteArray(),ByteArray(16),ByteArray(12));val sha=CloudSnapshotCodec.sha256(bytes)
        head.put("manifestSha256",sha).put("manifestSizeBytes",bytes.size);f.r48Blobs[sha]=bytes
        f.cache.clear(f.client.scopeId(session))
        val unchanged=f.service.syncNow(manual=true)
        assertFalse(unchanged.snapshotUploaded);assertEquals(0,f.service.formatStatus.value.editPending);assertEquals(1,f.service.formatStatus.value.readOnly)
        NoteRepository(f.db.noteDao(),"phone").save(title="local draft",markdown="preserve local edit")
        try{f.service.syncNow(manual=true);fail("readonly manifest written")}catch(_:CloudFormatUpgradeRequired){}
        assertEquals(1,f.completed);assertEquals(1,f.service.formatStatus.value.editPending)
        assertEquals("preserve local edit",f.db.noteDao().allNotes().single().markdown);assertEquals(sha,head.getString("manifestSha256"))
    }
    @Test fun repeatedPlanExpiryStopsAfterOneRebuildAndSchedulesRetryWithoutTouchingLocalData()=runBlocking {
        val f=fixture("expired",true)
        try{f.service.syncNow();fail("unbounded rebuild")}catch(e:CloudHttpException){assertEquals("reservation_expired",cloudErrorCode(e))}
        assertEquals(2,f.plans.size);assertEquals(0,f.completed);assertEquals(CloudSyncPhase.IDLE,f.service.state.value.phase)
        assertEquals(1,f.db.phoneRssSourceDao().getAllForSync().size);assertEquals(session,f.account.session.value);assertArrayEquals(key,f.keys.getAccountKey(session.userId,1));assertEquals(0,FixtureKeyStore.deleted)
        assertTrue(androidx.work.WorkManager.getInstance(f.context).getWorkInfosForUniqueWork("watchrss-cloud-follow-up").get(5,java.util.concurrent.TimeUnit.SECONDS).any{!it.state.isFinished})
    }
    @Test fun resetDuringPutRebuildsAtMostOnceAndReuploadsNewMissingPlan()=runBlocking {
        val f=fixture("put");assertTrue(f.service.syncNow().snapshotUploaded);assertEquals(1,f.completed);assertEquals(2,f.plans.size)
        assertNotEquals(f.plans[0].getString("snapshotId"),f.plans[1].getString("snapshotId"));assertTrue(f.plans[1].getLong("deviceSequence")>f.plans[0].getLong("deviceSequence"));assertTrue(f.recordsPosted>=2);assertEquals(before,f.state.contentGeneration);assertTrue(f.putCount>f.uploaded.size)
        assertEquals(CloudSyncPhase.COMPLETE,f.service.state.value.phase);assertArrayEquals(key,f.keys.getAccountKey(session.userId,1))
    }
    @Test fun expiredReservationAndLostCompleteReserveFreshPlanWithoutTrustingPriorPutMarks()=runBlocking {
        for(stage in listOf("expired","complete")){
            val f=fixture(stage);assertTrue(f.service.syncNow().snapshotUploaded);assertEquals(2,f.plans.size);assertEquals(1,f.completed)
            assertNotEquals(f.plans[0].getString("snapshotId"),f.plans[1].getString("snapshotId"));assertEquals(CloudSyncPhase.COMPLETE,f.service.state.value.phase)
            fun hashes(plan:JSONObject):Set<String> { val chunks=plan.getJSONArray("chunks");return (0 until chunks.length()).map{chunks.getJSONObject(it).getString("sha256")}.toSet() }
            assertEquals(hashes(f.plans[0]),hashes(f.plans[1]))
            assertNotEquals(f.plans[0].getJSONObject("manifest").getString("sha256"),f.plans[1].getJSONObject("manifest").getString("sha256"))
        }
    }
}
