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
import com.lightningstudio.watchrss.phone.cloud.CloudKeyManager
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Real manager, framing, Room, account coordinator and HTTP client; simulated peer/secure prefs only. */
@RunWith(RobolectricTestRunner::class)
@Config(application=android.app.Application::class,manifest=Config.NONE,sdk=[35])
class OfflineBluetoothAccountTest {
    private val context=RuntimeEnvironment.getApplication()
    private val server=MockWebServer()
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
    private lateinit var repo:PhoneAccountRepository
    private lateinit var coordinator:AppAccessCoordinator
    @Before fun setup()=runBlocking<Unit>{
        android.provider.Settings.Global.putInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, 100)
        java.security.Security.addProvider(object:java.security.Provider("offline-keystore",1.0,"synthetic identity") {
            init { put("KeyStore.AndroidKeyStore",com.lightningstudio.watchrss.phone.cloud.CloudContentRebuildIntegrationTest.FixtureKeyStore::class.java.name) }
        })
        server.start()
        val environment=AccountEnvironment(backendBaseUrl=server.url("/").toString().trimEnd('/'),supabaseAnonKey="synthetic",appAccessPublicKey=publicPem)
        repo=PhoneAccountRepository(environment,sessionStore,PhoneInstallationIdentity(context),PhoneAccountClient(environment,identity,{appStore.load()?.deviceAccessToken},okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            networkCalls++;beforeNetwork();networkFailure?.let { throw it };chain.proceed(chain.request())
        }.build()),"phone")
        sessionStore.save(PhoneAccountSession("owner","phone","synthetic-account","",Long.MAX_VALUE))
        keys.saveAccountKey("owner",1,root,true);keys.syncV2Write("owner","secret/K_id","synthetic-K_id")
        coordinator=AppAccessCoordinator(context,repo,identity,scope,appStore,environmentOverride=environment)
        // An expired lease takes the real status reconciliation branch; failed status must retain its type.
        appStore.save(AppAuthorization("old",1,"expired-lease",1,"release",AppAccessSummary(deviceStatus="authorized")))
    }
    @After fun close(){scope.cancel();db.close();server.shutdown();java.security.Security.removeProvider("offline-keystore")}
    private fun grantKey(watch:String="watch",owner:String="owner")=BleDomainStore.hash("${server.url("/").toString().trimEnd('/')}\n$owner\n$watch")
    private fun peerWitness()=domains.projectionVersions().getValue("account").let { p->
        BleDomainStore.hash("watchrss.ble.account-grant.v2\n${p.text("account")}\n$peerRoot")
    }
    private suspend fun seedCache(expiry:Long=System.currentTimeMillis()+3600000) {
        val now=System.currentTimeMillis()
        appStore.save(AppAuthorization("device",now+3600000,validLease(),now+3600000,"release",AppAccessSummary(deviceStatus="authorized"),now))
        val key=grantKey()
        val grant=org.json.JSONObject().put("action","syncAccount").put("account",org.json.JSONObject()
            .put("userId","owner").put("phoneMasked","phone").put("accessTokenExpiresAt",expiry)
            .put("backendBaseUrl",server.url("/").toString().trimEnd('/')).put("watchAccessToken","synthetic-watch"))
        sessionStore.saveBluetoothGrant(key,grant)
        sessionStore.saveBluetoothGrant("$key:peer",JSONObject().put("root",peerWitness()).put("grant",BleDomainStore.hash(grant.toString())))
    }
    @Test fun offlineWithVerifiedCacheFallsBackAfterNetworkFailure()=runBlocking<Unit> {
        seedCache()
        val request=coordinator.buildWatchAccountSyncRequest("watch",null,"watch")
        assertEquals("syncAccount",request.getString("action"));assertEquals(1,networkCalls)
    }
    @Test fun cachedBluetoothAccountWaitsForOnlineResultBeforeNetworkFallback()=runBlocking<Unit> {
        seedCache()
        val entered=CompletableDeferred<Unit>();val release=java.util.concurrent.CountDownLatch(1)
        beforeNetwork={entered.complete(Unit);check(release.await(5,java.util.concurrent.TimeUnit.SECONDS))}
        val background=scope.async{coordinator.reconcile()}
        try {
            withTimeout(2000){entered.await()}
            val request=scope.async{coordinator.buildWatchAccountSyncRequest("watch",null,"watch")}
            delay(50);assertFalse(request.isCompleted)
            release.countDown();withTimeout(5000){background.await()}
            assertEquals("syncAccount",withTimeout(2000){request.await()}.getString("action"))
            assertEquals(1,networkCalls)
        } finally {release.countDown();withTimeout(5000){background.await()}}
    }
    @Test fun offlineWithoutCacheNeedsNetworkVerificationAndNoBluetoothRetry()=runBlocking<Unit> {
        val failure=runCatching{coordinator.buildWatchAccountSyncRequest("watch",null,"watch")}.exceptionOrNull()
        assertEquals("需要联网完成账号验证",failure?.message)
        assertFalse(com.lightningstudio.watchrss.phone.connection.bluetooth.isRetryableBluetoothDisconnect(requireNotNull(failure)))
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
                        .put("account",JSONObject().put("bound",true).put("userIdHash",BleDomainStore.hash(peerOwner))).advertiseBleSyncV2()
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
    @Test fun offlineCacheCompletesFullManagerSessionAfterNetworkFailureWithZeroReconnects()=runBlocking<Unit> {
        seedCache();sync()
        assertEquals(1,networkCalls);assertEquals(0,reconnects)
        assertTrue(frames.any{it.optString("action")=="syncLibrary"&&it.optString("phase")=="cursor"})
        assertTrue(frames.any{it.optString("action")=="syncSession"&&it.optString("phase")=="complete"})
        assertFalse(progress.any{it.stage==PhoneBluetoothSyncStage.RECONNECTING})
    }
    @Test fun offlineNoGrantStopsFullManagerBeforeDataAndReconnect()=runBlocking<Unit> {
        val error=runCatching{sync()}.exceptionOrNull()
        assertEquals("需要联网完成账号验证",error?.message);noDataOrReconnect()
    }
    @Test fun changedPeerCredentialsCannotBeOverwrittenByCachedGrantOffline()=runBlocking<Unit> {
        seedCache();peerRoot="d".repeat(64)
        assertEquals("需要联网完成账号验证",runCatching{sync()}.exceptionOrNull()?.message)
        noDataOrReconnect()
    }
    @Test fun missingPeerReceiptCannotReuseCacheOffline()=runBlocking<Unit> {
        seedCache();sessionStore.removeBluetoothGrant("${grantKey()}:peer")
        assertEquals("需要联网完成账号验证",runCatching{sync()}.exceptionOrNull()?.message)
        noDataOrReconnect()
    }
    @Test fun differentWatchAccountStopsBeforeHttpAndData()=runBlocking<Unit> {
        seedCache();peerOwner="other"
        assertTrue(runCatching{sync()}.exceptionOrNull() is WatchSyncBlocked)
        assertEquals(0,networkCalls);noDataOrReconnect()
    }
    @Test fun expiredAndNearExpiryWatchGrantsNeedNetworkWithoutBluetoothRetries()=runBlocking<Unit> {
        for(expiry in listOf(System.currentTimeMillis()-1,System.currentTimeMillis()+60000)) {
            seedCache(expiry)
            assertEquals("需要联网完成账号验证",runCatching{sync()}.exceptionOrNull()?.message)
            noDataOrReconnect()
        }
    }
    @Test fun invalidLocalLeaseCannotUseGrantOffline()=runBlocking<Unit> {
        seedCache();appStore.save(appStore.load()!!.copy(lease="invalid-signature"))
        assertEquals("需要联网完成账号验证",runCatching{sync()}.exceptionOrNull()?.message)
        noDataOrReconnect()
    }
    @Test fun expiredOrPausedPhoneLoginCannotUseCachedGrant()=runBlocking<Unit> {
        seedCache();sessionStore.save(repo.session.value!!.copy(expiresAtMillis=1))
        assertTrue(runCatching{sync()}.exceptionOrNull() is PhoneLoginRequired)
        assertEquals(0,networkCalls);assertTrue(frames.isEmpty())
        sessionStore.save(repo.session.value!!.copy(expiresAtMillis=Long.MAX_VALUE));sessionStore.beginSameAccountReauthentication()
        assertTrue(runCatching{sync()}.exceptionOrNull() is PhoneLoginRequired)
        assertEquals(0,networkCalls);assertTrue(frames.isEmpty())
    }
    @Test fun timeoutAndNoRouteAtAccountBoundaryNeverConsumeBluetoothRetryBudget()=runBlocking<Unit> {
        for(failure in listOf(java.net.UnknownHostException("dns"),java.net.SocketTimeoutException("http timeout"),java.net.NoRouteToHostException("no network"),java.net.ConnectException("offline"),IOException("connection lost"))) {
            networkFailure=failure
            assertEquals("需要联网完成账号验证",runCatching{sync()}.exceptionOrNull()?.message)
            noDataOrReconnect()
        }
    }
    private suspend fun replaceGrant(change:(JSONObject)->Unit) {
        val key=grantKey();val grant=sessionStore.bluetoothGrant(key)!!;change(grant)
        sessionStore.saveBluetoothGrant(key,grant)
        sessionStore.saveBluetoothGrant("$key:peer",JSONObject().put("root",peerWitness()).put("grant",BleDomainStore.hash(grant.toString())))
    }
    @Test fun cacheOwnerAndBackendRemainMandatoryEvenWithMatchingPeerReceipt()=runBlocking<Unit> {
        for(change in listOf<(JSONObject)->Unit>(
            {it.getJSONObject("account").put("userId","other")},
            {it.getJSONObject("account").put("backendBaseUrl","https://other.invalid")},
            {it.getJSONObject("account").put("watchAccessToken","")}
        )) {
            seedCache();replaceGrant(change)
            assertEquals("需要联网完成账号验证",runCatching{sync()}.exceptionOrNull()?.message)
            noDataOrReconnect()
        }
    }
    @Test fun signedButExpiredLeaseNeedsNetworkVerification()=runBlocking<Unit> {
        seedCache();appStore.save(appStore.load()!!.copy(lease=validLease(System.currentTimeMillis()/1000-1)))
        assertEquals("需要联网完成账号验证",runCatching{sync()}.exceptionOrNull()?.message)
        noDataOrReconnect()
    }
    @Test fun trustedTimeCannotExtendTheAccountOrWatchTokenExpiry()=runBlocking<Unit> {
        seedCache()
        assertNull(repo.cachedBluetoothAccount("watch",System.currentTimeMillis()+7200000))
        sessionStore.save(repo.session.value!!.copy(expiresAtMillis=System.currentTimeMillis()+60000))
        assertNull(repo.cachedBluetoothAccount("watch",System.currentTimeMillis()+120000))
    }
    @Test fun clockRollbackRequiresOnlineVerification()=runBlocking<Unit> {
        seedCache()
        context.getSharedPreferences("offline-access",Context.MODE_PRIVATE).edit()
            .putLong("trusted_time",System.currentTimeMillis()+3600000)
            .putLong("trusted_elapsed",android.os.SystemClock.elapsedRealtime()).commit()
        assertEquals("需要联网完成账号验证",runCatching{sync()}.exceptionOrNull()?.message)
        noDataOrReconnect()
    }
    @Test fun knownAccountRejectionInvalidatesPreviouslyVerifiedBluetoothCache()=runBlocking<Unit> {
        seedCache();networkFailure=null
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        assertTrue(coordinator.reconcile() is WatchAccountRefreshResult.AccountUnrecoverable)
        assertNull(sessionStore.bluetoothGrant(grantKey()))
        networkFailure=java.net.UnknownHostException("offline")
        assertTrue(runCatching{sync()}.exceptionOrNull() is PhoneAccountTemporarilyUnavailable)
        noDataOrReconnect()
    }
    private fun serveOnlineIssuance() {
        networkFailure=null
        server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
            val now=System.currentTimeMillis()
            return when(request.path) {
                "/functions/v1/account/phone-authorizations/challenge"->MockResponse().setBody(JSONObject().put("challengeId","challenge").put("nonce","nonce").put("expiresAt",now+60000).toString())
                "/functions/v1/account/app-access/refresh"->MockResponse().setBody(JSONObject().put("deviceAccessToken","rotated").put("deviceAccessTokenExpiresAt",now+3600000)
                    .put("lease",validLease()).put("leaseExpiresAt",now+3600000).put("releaseGrant","release").put("serverTime",now).put("access",JSONObject().put("deviceStatus","authorized")).toString())
                "/functions/v1/issue-watch-device-token"->MockResponse().setBody(JSONObject().put("watchAccessToken","synthetic-watch").put("accessTokenExpiresAt",now+3600000)
                    .put("watchRefreshToken","synthetic-refresh").put("refreshTokenExpiresAt",now+7200000).toString())
                else->MockResponse().setResponseCode(500).setBody("{}")
            }
        }}
    }
    @Test fun onlineIssuesCredentialsThenOfflineReusesAcknowledgedCache()=runBlocking<Unit> {
        seedCache();sessionStore.removeBluetoothGrant(grantKey());serveOnlineIssuance()
        sync()
        assertEquals(3,networkCalls);assertEquals(0,reconnects)
        networkFailure=java.net.UnknownHostException("offline");frames.clear();sync()
        assertEquals(4,networkCalls);assertEquals(0,reconnects)
        assertTrue(frames.any{it.optString("action")=="syncSession"&&it.optString("phase")=="complete"})
    }
    @Test fun dnsFailureIsNotBluetoothDisconnect() {
        assertFalse(com.lightningstudio.watchrss.phone.connection.bluetooth.isRetryableBluetoothDisconnect(java.net.UnknownHostException("offline")))
    }
}
