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
        var failed=false
        var putCount=0
        val plans=mutableListOf<JSONObject>()
        val objects=linkedMapOf<String,JSONObject>()
        val uploaded=mutableSetOf<String>()
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
                val request=chain.request();val path=request.url.encodedPath
                val buffer=okio.Buffer();request.body?.writeTo(buffer)
                val body=if(request.method=="POST")JSONObject(buffer.readUtf8())else JSONObject()
                var code=200
                fun shouldFail(stage:String)=failStage==stage && (!failed || alwaysFail)
                val result=if(isCloudContentWrite(request.method,path)&&request.header("x-watchrss-content-generation")!=generation){code=409;JSONObject().put("error","cloud_content_rebuild_required")}else when {
                    path.endsWith("/bootstrap") -> bootstrap()
                    path.endsWith("/devices/register") -> JSONObject()
                    path.endsWith("/records") && request.method=="GET" -> {
                        val cursor=request.url.queryParameter("after")!!;afterQueries+=cursor
                        if(mutateDuringPull&&!mutatedDuringPull){mutatedDuringPull=true;runBlocking{repository.setRssSourcePinned("http://127.0.0.1/rss",true)}}
                        JSONObject().put("formatVersion",1).put("items",JSONArray(recordRows.values.filter{it.getString("revision").toLong()>cursor.toLong()})).put("cursor",revision.toString()).put("headRevision",revision.toString()).put("hasMore",false)
                            .put("contentGeneration",generation).put("contentEmpty",ready.isEmpty()&&recordRows.isEmpty())
                    }
                    path.endsWith("/records") -> {
                        assertEquals(generation,request.header("x-watchrss-content-generation"))
                        val items=body.getJSONArray("items");val result=JSONArray()
                        for(i in 0 until items.length()) {val item=items.getJSONObject(i);val id=item.getString("recordId");assertEquals(recordRows[id]?.getString("revision")?:"0",item.getString("expectedRevision"));recordRows[id]=JSONObject(item.toString()).put("revision",(++revision).toString());result.put(JSONObject().put("recordId",id).put("revision",revision.toString()));recordsPosted++}
                        JSONObject().put("items",result)
                    }
                    path.endsWith("/heads") -> JSONObject().put("heads",JSONArray(ready)).put("nextOffset",JSONObject.NULL).put("contentGeneration",generation).put("contentEmpty",ready.isEmpty()&&recordRows.isEmpty())
                    path.endsWith("/reserve") -> {
                        assertEquals(3,body.getInt("contentLayout"));assertEquals(generation,request.header("x-watchrss-content-generation"));assertTrue(body.getLong("deviceSequence")>sequence)
                        sequence=body.getLong("deviceSequence");plans+=body
                        val missing=JSONArray();latestObjectIds.clear()
                        fun add(kind:String,sha:String,size:Long) {
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
                            assertEquals(target.getLong("sizeBytes"),bytes.size.toLong());assertEquals(target.getString("sha256"),CloudSnapshotCodec.sha256(bytes));uploaded+=id
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
        val f=fixture();val result=f.service.syncNow();assertTrue(result.snapshotUploaded);assertEquals(1,f.completed);assertTrue(f.recordsPosted>0)
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
