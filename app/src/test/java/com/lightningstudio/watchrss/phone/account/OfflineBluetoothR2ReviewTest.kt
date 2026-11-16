package com.lightningstudio.watchrss.phone.account

import android.content.Context
import androidx.room.Room
import com.lightningstudio.watchrss.phone.connection.bluetooth.*
import com.lightningstudio.watchrss.phone.data.ble.BleDomainStore
import com.lightningstudio.watchrss.phone.data.db.*
import com.lightningstudio.watchrss.phone.data.log.BluetoothDebugLog
import com.lightningstudio.watchrss.phone.data.note.NoteRepository
import com.lightningstudio.watchrss.phone.data.reader.ReaderPresetRepository
import com.lightningstudio.watchrss.phone.data.repo.PhoneCompanionRepository
import org.json.JSONObject
import org.json.JSONArray
import java.io.*
import okio.buffer
import com.lightningstudio.watchrss.phone.cloud.CloudKeyManager
import kotlinx.coroutines.*
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Independent review: real manager/account/Room/framing, in-process HTTP replies only.
 * No socket, DNS query, server, or device is used. New independent r2 probes.
 * Assertions in OfflineBluetoothReviewTest are left untouched.
 * Transport fixture adapted from OfflineBluetoothAccountTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application=android.app.Application::class,manifest=Config.NONE,sdk=[35])
class OfflineBluetoothR2ReviewTest {
    private val context=RuntimeEnvironment.getApplication()
    private val origin="https://review.invalid"
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    private val sessionStore=EncryptedAccountSessionStore(context).also { ReflectionHelpers.setField(it,"prefsRef",context.getSharedPreferences("offline-session",Context.MODE_PRIVATE)) }
    private val appStore=AppAccessStore(context,preferencesOverride=context.getSharedPreferences("offline-access",Context.MODE_PRIVATE))
    private val identity=LicenseDeviceIdentity(context)
    private val keys=CloudKeyManager(context,preferencesOverride=context.getSharedPreferences("offline-keys",Context.MODE_PRIVATE))
    private val db=Room.inMemoryDatabaseBuilder(context,PhoneCompanionDatabase::class.java).allowMainThreadQueries().build()
    private val domains=BleDomainStore(db,File(context.cacheDir,"offline-${System.nanoTime()}"))
    private val library=PhoneCompanionRepository(db.phoneSavedItemDao(),db.phoneArticleDao(),db.phoneRssSourceDao(),"phone",db.syncChangeLogDao(),db.syncPeerStateDao(),appMetaDao=db.appMetaDao(),bleDomains=domains)
    private val log=BluetoothDebugLog(context)
    private val device=PhoneBluetoothWatchDevice("watch","11:22:33:44:55:66",1,"watch",supportsPersistentSession=true)
    private val frames=mutableListOf<JSONObject>()
    private var peerRoot="c".repeat(64)
    private var peerOwner="owner"
    private var peerBound=true
    private var peerFastPath=true
    private var reconnects=0
    private val progress=mutableListOf<PhoneBluetoothSyncProgress>()
    private val root=ByteArray(32){it.toByte()}
    private val leaseKeys=java.security.KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    private val publicPem="-----BEGIN PUBLIC KEY-----"+java.util.Base64.getEncoder().encodeToString(leaseKeys.public.encoded)+"-----END PUBLIC KEY-----"
    private fun validLease(expiresAtSeconds:Long=System.currentTimeMillis()/1000+3600):String {
        fun b64(bytes:ByteArray)=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val payload=org.json.JSONObject().put("licenseDeviceId",identity.deviceId).put("expiresAt",expiresAtSeconds)
        val signed=b64("{\"alg\":\"ES256\"}".toByteArray())+"."+b64(payload.toString().toByteArray())
        val der=java.security.Signature.getInstance("SHA256withECDSA").run{initSign(leaseKeys.private);update(signed.toByteArray());sign()}
        val rLength=der[3].toInt() and 255
        val r=der.copyOfRange(4,4+rLength).takeLast(32).toByteArray()
        val sLength=der[5+rLength].toInt() and 255
        val part=der.copyOfRange(6+rLength,6+rLength+sLength).takeLast(32).toByteArray()
        return signed+"."+b64(ByteArray(32-r.size)+r+ByteArray(32-part.size)+part)
    }
    private var networkCalls=0
    private var beforeNetwork:()->Unit={}
    private var networkFailure:java.io.IOException?=java.net.UnknownHostException("offline fixture")
    private var replyBody:(Int,String)->okhttp3.ResponseBody={_,body->body.toResponseBody("application/json".toMediaType())}
    private var httpReply: (String) -> Pair<Int,String> = { error("Unexpected HTTP path: $it") }
    private lateinit var repo:PhoneAccountRepository
    private lateinit var coordinator:AppAccessCoordinator
    @Before fun setup()=runBlocking<Unit>{
        android.provider.Settings.Global.putInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, 100)
        java.security.Security.addProvider(object:java.security.Provider("offline-keystore",1.0,"synthetic identity") {
            init { put("KeyStore.AndroidKeyStore",com.lightningstudio.watchrss.phone.cloud.CloudContentRebuildIntegrationTest.FixtureKeyStore::class.java.name) }
        })
        val environment=AccountEnvironment(backendBaseUrl=origin,supabaseAnonKey="synthetic",appAccessPublicKey=publicPem)
        repo=PhoneAccountRepository(environment,sessionStore,PhoneInstallationIdentity(context),PhoneAccountClient(environment,identity,{appStore.load()?.deviceAccessToken},okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            networkCalls++;beforeNetwork();networkFailure?.let { throw it }
            val (status, body) = httpReply(chain.request().url.encodedPath)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
                .body(replyBody(status,body)).build()
        }.build()),"phone")
        sessionStore.save(PhoneAccountSession("owner","phone","synthetic-account","",Long.MAX_VALUE))
        keys.saveAccountKey("owner",1,root,true);keys.syncV2Write("owner","secret/K_id","synthetic-K_id")
        coordinator=AppAccessCoordinator(context,repo,identity,scope,appStore,environmentOverride=environment)
        // An expired lease takes the real status reconciliation branch; failed status must retain its type.
        appStore.save(AppAuthorization("old",1,"expired-lease",1,"release",AppAccessSummary(deviceStatus="authorized")))
    }
    @After fun close(){scope.cancel();db.close();java.security.Security.removeProvider("offline-keystore")}
    private fun grantKey(watch:String="watch",owner:String="owner")=BleDomainStore.hash("${origin}\n$owner\n$watch")
    private fun peerWitness()=domains.projectionVersions().getValue("account").let { p->
        BleDomainStore.hash("watchrss.ble.account-grant.v2\n${p.text("account")}\n$peerRoot")
    }
    private suspend fun seedCache(expiry:Long=System.currentTimeMillis()+3600000) {
        val now=System.currentTimeMillis()
        appStore.save(AppAuthorization("device",now+3600000,validLease(),now+3600000,"release",AppAccessSummary(deviceStatus="authorized"),now))
        val key=grantKey()
        val grant=org.json.JSONObject().put("action","syncAccount").put("account",org.json.JSONObject()
            .put("userId","owner").put("phoneMasked","phone").put("accessTokenExpiresAt",expiry)
            .put("backendBaseUrl",origin).put("watchAccessToken","synthetic-watch"))
        sessionStore.saveBluetoothGrant(key,grant)
        sessionStore.saveBluetoothGrant("$key:peer",JSONObject().put("root",peerWitness()).put("grant",BleDomainStore.hash(grant.toString())))
    }

    private fun manager()=PhoneBluetoothSyncManager(context,library,NoteRepository(db.noteDao(),"phone"),ReaderPresetRepository(context,db,db.readerPresetDao(),"phone",scope),PhoneLlmTokenUsageRepository(db.llmTokenUsageDao()),"phone",log,
        canSyncAccount={repo.hasUsableSession},currentPhoneUserId={repo.session.value?.userId},
        buildAccountSyncRequest={watch,install,name->coordinator.buildWatchAccountSyncRequest(watch,install,name)},
        domainScope={"fixture"},accountDomainWitness={repo.bluetoothAccountWitness(it)},
        prepareAccountDomain={watch,state->repo.prepareBluetoothAccount(watch,state)},
        acknowledgeAccountDomain={watch,state->repo.acknowledgeBluetoothAccount(watch,state)}).also { manager->
        manager.reconnectDelay={};manager.reconnectSession={_,_->reconnects++;session()}
    }
    private fun response(action:String,phase:String="")=JSONObject().put("success",true).put("action",action).put("phase",phase).put("version",if(action=="syncDomains")2 else 14).put("deviceId","watch")
    private fun session():PhoneSyncSession {
        val incoming=ArrayDeque<Byte>()
        val output=object:ByteArrayOutputStream(){override fun flush(){
            if(size()==0)return
            val q=BluetoothSyncProtocol.readFrame(ByteArrayInputStream(toByteArray()));reset();frames+=q
            val action=q.optString("action");val phase=q.optString("phase")
            if(action=="ack")return
            val answer=when(action) {
                "syncLibrary"->when(phase) {
                    "probe"->response(action,phase).put("supportsPersistentSession",true).put("persistentSessionAccepted",true)
                        .put("account",JSONObject().put("bound",peerBound).put("userIdHash",BleDomainStore.hash(peerOwner))).advertiseBleSyncV2().put("supportsDomainFastPathV2",peerFastPath)
                    "cursor"->LibrarySyncPayload.buildCursorResponse("watch",LibrarySyncCursor(0,0,0))
                    "manifest"->LibrarySyncPayload.buildManifestResponse("watch",emptyList()).put("supportsReaderPresets",false).put("supportsNoteManifestV2",false).put("supportsNoteAssetInventory",false)
                    "articles"->LibrarySyncPayload.buildChunkedResponseFrames("watch",emptyList(),emptyList(),useBatches=false).single()
                    else->error("unexpected $action/$phase")
                }
                "syncDomains"->response(action,phase).put("windowId","fixture-window")
                    .put("roots",JSONObject(mapOf("account" to peerRoot))).put("projectionVersions",JSONObject().apply {domains.projectionVersions().forEach { (d,p)->put(d,p.json()) }})
                    .apply {if(phase=="summary")put("skipDomains",JSONArray()) else {put("committedDomains",JSONArray());put("retryDomains",q.getJSONArray("completedDomains"))}}
                "syncAccount"->response(action).put("boundUserId","owner").put("accountStateRoot",peerRoot)
                "syncNotes"->NoteSyncPayload.manifest("watch",emptyList()).put("success",true)
                "syncLlmTokenUsage"->response(action).put("records",JSONArray())
                "syncSession"->response(action,phase)
                else->error("unexpected $action/$phase")
            }
            val wire=ByteArrayOutputStream().also {BluetoothSyncProtocol.writeFrame(it,answer)}.toByteArray()
            wire.forEach{incoming.addLast(it)}
        }}
        val input=object:InputStream(){override fun read()=if(incoming.isEmpty())-1 else incoming.removeFirst().toInt() and 255}
        return PhoneSyncSession(PhoneBluetoothSyncClient(context,log),device,"phone",PhoneSyncSession.Transport(input,output,"rfcomm",false,{}),true,false,false)
    }
    private suspend fun sync()=manager().syncAll(device,reusableSession=session(),onProgress={progress+=it})
    private fun noDataOrReconnect() {
        assertEquals(0,reconnects);assertFalse(progress.any{it.stage==PhoneBluetoothSyncStage.RECONNECTING})
        assertFalse(log.snapshot().contains("event=sync.reconnect"))
        assertFalse(frames.any {it.optString("action")=="syncAccount" || it.optString("action")=="syncLibrary"&&it.optString("phase")!="probe"})
        assertArrayEquals(root,keys.getAccountKey("owner",1))
        assertEquals("synthetic-K_id",keys.syncV2Read("owner","secret/K_id"))
    }

    private suspend fun replaceGrant(change:(JSONObject)->Unit) {
        val key=grantKey();val grant=sessionStore.bluetoothGrant(key)!!;change(grant)
        sessionStore.saveBluetoothGrant(key,grant)
        sessionStore.saveBluetoothGrant("$key:peer",JSONObject().put("root",peerWitness()).put("grant",BleDomainStore.hash(grant.toString())))
    }

    private fun onlineReplies(issueStatus: Int = 200, issueError: String = "") {
        networkFailure = null
        httpReply = { path ->
            val now = System.currentTimeMillis()
            when (path) {
                "/functions/v1/account/phone-authorizations/challenge" -> 200 to JSONObject()
                    .put("challengeId", "review-challenge").put("nonce", "review-nonce").put("expiresAt", now + 60000).toString()
                "/functions/v1/account/app-access/refresh" -> 200 to JSONObject()
                    .put("deviceAccessToken", "review-rotated").put("deviceAccessTokenExpiresAt", now + 3600000)
                    .put("lease", validLease()).put("leaseExpiresAt", now + 3600000).put("releaseGrant", "review-release")
                    .put("serverTime", now).put("access", JSONObject().put("deviceStatus", "authorized")).toString()
                "/functions/v1/issue-watch-device-token" -> issueStatus to if (issueStatus != 200)
                    JSONObject().put("error", issueError).toString() else JSONObject()
                    .put("watchAccessToken", "review-watch").put("accessTokenExpiresAt", now + 3600000)
                    .put("watchRefreshToken", "review-refresh").put("refreshTokenExpiresAt", now + 7200000).toString()
                else -> error("Unexpected HTTP path: $path")
            }
        }
    }

    private suspend fun assertIssuanceRejectionInvalidatesOtherWatch(status: Int, error: String) {
        seedCache(); onlineReplies(status, error)
        val rejected = runCatching { coordinator.buildWatchAccountSyncRequest("second-watch", null, "second-watch") }.exceptionOrNull()
        assertTrue("fixture must reach the explicit rejection", rejected is PhoneLoginRequired || rejected is PhoneDeviceVerificationRequired)
        assertEquals(3, networkCalls)
        networkFailure = java.net.UnknownHostException("offline after known rejection")
        val failure = runCatching { sync() }.exceptionOrNull()
        assertTrue("Known HTTP $status/$error must block old cached account delivery; actual=${failure?.javaClass?.simpleName}, accountFrames=${frames.count { it.optString("action") == "syncAccount" }}",
            failure is PhoneLoginRequired || failure is PhoneDeviceVerificationRequired || failure is PhoneAccountNetworkRequired)
        noDataOrReconnect()
        assertNull(sessionStore.bluetoothGrant(grantKey()))
    }

    private fun newCoordinator() = AppAccessCoordinator(context, repo, identity, scope, appStore,
        environmentOverride = AccountEnvironment(backendBaseUrl=origin, supabaseAnonKey="synthetic", appAccessPublicKey=publicPem))

    private fun brokenBody() = object: okhttp3.ResponseBody() {
        override fun contentType() = "application/json".toMediaType()
        override fun contentLength() = 100L
        override fun source() = object: okio.Source {
            override fun read(sink:okio.Buffer, byteCount:Long):Long = throw java.net.SocketTimeoutException("HTTP error body stalled")
            override fun timeout() = okio.Timeout.NONE
            override fun close() = Unit
        }.buffer()
    }

    private suspend fun assertBrokenHttpErrorCannotFallback(status: Int) {
        seedCache(); networkFailure=null; httpReply={status to "{}"}; replyBody={_,_->brokenBody()}
        val failure=runCatching { sync() }.exceptionOrNull()
        assertNotNull("Known HTTP $status must remain a service failure when its body times out; accountFrames=${frames.count { it.optString("action")=="syncAccount" }}", failure)
        assertFalse(failure is PhoneAccountNetworkRequired)
        noDataOrReconnect()
    }
    @Test fun http503BodyTimeoutCannotBeForgedIntoOfflineFallback()=runBlocking<Unit> {
        assertBrokenHttpErrorCannotFallback(503)
    }
    @Test fun http429BodyTimeoutCannotBeForgedIntoOfflineFallback()=runBlocking<Unit> {
        assertBrokenHttpErrorCannotFallback(429)
    }
    @Test fun http409BodyTimeoutCannotBeForgedIntoOfflineFallback()=runBlocking<Unit> {
        assertBrokenHttpErrorCannotFallback(409)
    }
    @Test fun http403BodyTimeoutStillPersistsRefusal()=runBlocking<Unit> {
        assertBrokenHttpErrorCannotFallback(403)
        assertTrue(repo.offlineAuthorizationBlocked("owner"))
    }

    @Test fun ioWrapperCannotHideHttpRefusal()=runBlocking<Unit> {
        seedCache(); networkFailure=IOException("transport wrapper",PhoneAccountHttpException(403,"{}"))
        assertNotNull(runCatching { sync() }.exceptionOrNull())
        assertTrue(repo.offlineAuthorizationBlocked("owner")); noDataOrReconnect()
    }
    @Test fun networkBackoffDoesNotOverrideLaterPersistentRefusal()=runBlocking<Unit> {
        seedCache(); sync(); frames.clear()
        val before=networkCalls
        repo.recordAuthenticationResponse("owner",403,"{}")
        assertNotNull(runCatching { sync() }.exceptionOrNull())
        assertEquals(before,networkCalls); noDataOrReconnect()
    }

    @Test fun queuedCachedReadCannotSurviveRefusalAndOnlineRecovery()=runBlocking<Unit> {
        seedCache()
        val tasks=java.util.concurrent.LinkedBlockingQueue<Runnable>()
        val dispatcher=object:CoroutineDispatcher() {
            override fun dispatch(context:kotlin.coroutines.CoroutineContext,block:Runnable) { tasks.put(block) }
        }
        // Suspend at the real withContext(IO) return. The grant has been read, but
        // validBluetoothGrant has not executed its final owner/refusal predicates.
        val read=scope.async(dispatcher,start=CoroutineStart.UNDISPATCHED) {
            repo.cachedBluetoothAccount("watch",System.currentTimeMillis())
        }
        val resume=withContext(Dispatchers.IO) { tasks.poll(5,java.util.concurrent.TimeUnit.SECONDS) }
        assertNotNull("cached read must suspend at the IO return",resume)
        try {
            repo.recordAuthenticationResponse("owner",401,"{}")
            assertNull(sessionStore.bluetoothGrant(grantKey()))
            assertTrue(repo.offlineAuthorizationBlocked("owner"))
            onlineReplies()
            assertEquals(WatchAccountRefreshResult.Ready,coordinator.reconcile())
            assertFalse(repo.offlineAuthorizationBlocked("owner"))
            assertNull("refresh must not restore the invalidated watch grant",sessionStore.bluetoothGrant(grantKey()))
            resume!!.run()
            val stale=withTimeout(2000) { read.await() }
            assertNull("A read from the old refusal generation must not return the deleted watch grant after recovery",stale?.optString("action"))
        } finally {
            read.cancel(); while(true) { val next=tasks.poll()?:break; next.run() }
        }
    }

    @Test fun refusedGenerationCannotSaveAfterSuccessfulOnlineRecovery()=runBlocking<Unit> {
        seedCache()
        val old=sessionStore.bluetoothGrant(grantKey())!!
        val version=repo.offlineAuthorizationVersion("owner")
        repo.recordAuthenticationResponse("owner",401,"{}")
        onlineReplies(); assertEquals(WatchAccountRefreshResult.Ready,coordinator.reconcile())
        val failure=runCatching { sessionStore.saveAuthorizedBluetoothGrant(grantKey(),BleDomainStore.hash("$origin\nowner"),version,old) }.exceptionOrNull()
        assertTrue(failure is PhoneDeviceVerificationRequired)
        assertNull(sessionStore.bluetoothGrant(grantKey()))
    }

    @Test fun refusalAfterFallbackReadBeforeTimeCommitMustStopDelivery()=runBlocking<Unit> {
        seedCache()
        val prefs=context.getSharedPreferences("offline-access",Context.MODE_PRIVATE)
        var arm=false; var observed=false
        val wrapped=object:android.content.SharedPreferences by prefs {
            override fun edit():android.content.SharedPreferences.Editor {
                val editor=prefs.edit()
                return object:android.content.SharedPreferences.Editor by editor {
                    override fun putLong(key:String?,value:Long):android.content.SharedPreferences.Editor {
                        editor.putLong(key,value); return this
                    }
                    override fun commit():Boolean {
                        if(arm && !observed) {
                            observed=true
                            // Another authenticated request completes while the fallback persists time.
                            repo.recordAuthenticationResponse("owner",403,"{}")
                        }
                        return editor.commit()
                    }
                }
            }
        }
        ReflectionHelpers.setField(appStore,"prefs",wrapped)
        beforeNetwork={arm=true}
        val failure=runCatching { sync() }.exceptionOrNull()
        assertTrue(observed); assertTrue(repo.offlineAuthorizationBlocked("owner"))
        assertNull(sessionStore.bluetoothGrant(grantKey()))
        assertNotNull("A refusal persisted before the account builder returns must stop credential delivery",failure)
        noDataOrReconnect()
    }

    @Test fun missingMalformedAndNegativeBootCountsDenyFallback()=runBlocking<Unit> {
        for(value in listOf(null,"not-an-int","-1",Int.MIN_VALUE.toString())) {
            android.provider.Settings.Global.putInt(context.contentResolver,android.provider.Settings.Global.BOOT_COUNT,100)
            seedCache(); frames.clear(); coordinator=newCoordinator()
            android.provider.Settings.Global.putString(context.contentResolver,android.provider.Settings.Global.BOOT_COUNT,value)
            assertNull(appStore.trustedNowMillis())
            assertEquals("需要联网完成账号验证",runCatching { sync() }.exceptionOrNull()?.message)
            noDataOrReconnect()
        }
    }
    @Test fun bootCounterWrapToZeroCannotReuseMaxValueAnchor()=runBlocking<Unit> {
        android.provider.Settings.Global.putInt(context.contentResolver,android.provider.Settings.Global.BOOT_COUNT,Int.MAX_VALUE)
        seedCache()
        android.provider.Settings.Global.putInt(context.contentResolver,android.provider.Settings.Global.BOOT_COUNT,0)
        assertNull(appStore.trustedNowMillis())
        assertEquals("需要联网完成账号验证",runCatching { sync() }.exceptionOrNull()?.message)
        noDataOrReconnect()
    }
    @Test fun onlineSuccessWithoutReadableBootNeverEnablesOfflineFallback()=runBlocking<Unit> {
        seedCache(); android.provider.Settings.Global.putInt(context.contentResolver,android.provider.Settings.Global.BOOT_COUNT,-1)
        onlineReplies(); sync(); assertEquals(3,networkCalls)
        assertNull(appStore.trustedNowMillis())
        networkFailure=java.net.UnknownHostException("offline"); frames.clear()
        assertEquals("需要联网完成账号验证",runCatching { sync() }.exceptionOrNull()?.message)
        noDataOrReconnect()
    }
    @Test fun onlineSuccessReplacesGrantAndPeerReceiptBeforeOfflineReuse()=runBlocking<Unit> {
        seedCache(); val previous=BleDomainStore.hash(sessionStore.bluetoothGrant(grantKey())!!.toString())
        onlineReplies();sync()
        val refreshed=sessionStore.bluetoothGrant(grantKey())!!
        val updated=BleDomainStore.hash(refreshed.toString())
        assertNotEquals(previous,updated)
        assertEquals(updated,sessionStore.bluetoothGrant("${grantKey()}:peer")!!.getString("grant"))
        networkFailure=java.net.UnknownHostException("offline"); frames.clear(); sync()
        assertEquals(updated,BleDomainStore.hash(frames.single { it.optString("action")=="syncAccount" }.toString()))
        assertEquals(0,reconnects)
    }

    @Test fun onlinePriorityWaitsForTransportFailureAndThenFallsBack()=runBlocking<Unit> {
        seedCache()
        val entered=CompletableDeferred<Unit>(); val release=java.util.concurrent.CountDownLatch(1)
        beforeNetwork={entered.complete(Unit);check(release.await(5,java.util.concurrent.TimeUnit.SECONDS))}
        val start=System.nanoTime()
        val call=scope.async { coordinator.buildWatchAccountSyncRequest("watch",null,null) }
        try {
            withTimeout(2000) { entered.await() }
            delay(250)
            assertFalse("Do not authorize before the online attempt has an outcome",call.isCompleted)
            release.countDown()
            assertEquals("syncAccount",withTimeout(2000) { call.await() }.getString("action"))
            val millis=(System.nanoTime()-start)/1_000_000
            assertTrue(millis>=250)
            println("R2 latency fixture waited ${millis}ms for the injected HTTP failure; no real network")
        } finally { release.countDown();call.cancel() }
    }
    @Test fun productionHttpTimeoutConfigurationHasNoWholeCallDeadline() {
        val client=PhoneAccountClient(AccountEnvironment(backendBaseUrl=origin,supabaseAnonKey="synthetic"),identity)
        val http=ReflectionHelpers.getField<okhttp3.OkHttpClient>(client,"httpClient")
        assertEquals(15000,http.connectTimeoutMillis)
        assertEquals(20000,http.readTimeoutMillis)
        assertEquals(20000,http.writeTimeoutMillis)
        assertEquals(0,http.callTimeoutMillis)
    }

    @Test fun fullManagerCannotDeliverQueuedOldGrantAfterRefusalAndRecovery()=runBlocking<Unit> {
        seedCache()
        val tasks=java.util.concurrent.LinkedBlockingQueue<Runnable>()
        val pauseNext=java.util.concurrent.atomic.AtomicBoolean(false)
        val captured=java.util.concurrent.atomic.AtomicBoolean(false)
        val networkAttempted=java.util.concurrent.atomic.AtomicBoolean(false)
        val prefs=context.getSharedPreferences("offline-session",Context.MODE_PRIVATE)
        val watchedKey="ble_grant:${grantKey()}"
        ReflectionHelpers.setField(sessionStore,"prefsRef",object:android.content.SharedPreferences by prefs {
            override fun getString(key:String?,defValue:String?):String? {
                val result=prefs.getString(key,defValue)
                if(key==watchedKey && result!=null && networkAttempted.get() && captured.compareAndSet(false,true)) {
                    pauseNext.set(true)
                }
                return result
            }
        })
        val dispatcher=object:CoroutineDispatcher() {
            override fun dispatch(context:kotlin.coroutines.CoroutineContext,block:Runnable) {
                if(pauseNext.compareAndSet(true,false)) tasks.put(block)
                else Dispatchers.Default.dispatch(context,block)
            }
        }
        beforeNetwork={networkAttempted.set(true)}
        val call=scope.async(dispatcher) { runCatching { sync() } }
        val resume=withContext(Dispatchers.IO) { tasks.poll(5,java.util.concurrent.TimeUnit.SECONDS) }
        assertNotNull("Pause the manager at the cached grant IO return",resume)
        try {
            assertFalse(frames.any { it.optString("action")=="syncAccount" })
            repo.recordAuthenticationResponse("owner",401,"{}")
            assertTrue(repo.offlineAuthorizationBlocked("owner"))
            assertNull(sessionStore.bluetoothGrant(grantKey()))
            // Model the real recovery after the shared one-second network backoff.
            android.os.SystemClock.sleep(1100)
            onlineReplies();assertEquals(WatchAccountRefreshResult.Ready,coordinator.reconcile())
            assertFalse(repo.offlineAuthorizationBlocked("owner"))
            assertNull(sessionStore.bluetoothGrant(grantKey()))
            resume!!.run()
            val result=withTimeout(3000) { call.await() }
            assertTrue("Deleted pre-refusal grant must not reach the peer after recovery; accountFrames=${frames.count { it.optString("action")=="syncAccount" }}",result.isFailure)
            noDataOrReconnect()
        } finally {
            call.cancel();while(true) { val next=tasks.poll()?:break;next.run() }
        }
    }
}
