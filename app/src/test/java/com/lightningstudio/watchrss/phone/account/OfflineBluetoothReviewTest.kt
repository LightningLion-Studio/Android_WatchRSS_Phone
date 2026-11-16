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
 * No socket, DNS query, server, or device is used. The original four review
 * counterexamples retain their assertions; r2 adds refusal/boot/race coverage.
 * Transport fixture adapted from OfflineBluetoothAccountTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application=android.app.Application::class,manifest=Config.NONE,sdk=[35])
class OfflineBluetoothReviewTest {
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

    @Test fun cachedAccountAndSummaryContainNoPreGateSecrets() = runBlocking<Unit> {
        seedCache(); sync()
        assertEquals(1, networkCalls)
        val preGate = frames.takeWhile { it.optString("action") != "syncAccount" }
        val summary = preGate.single { it.optString("action") == "syncDomains" }
        assertEquals("summary", summary.getString("phase"))
        assertEquals(2, summary.getInt("version"))
        assertEquals(0, summary.getJSONObject("roots").length())
        assertEquals(0, summary.getJSONObject("known").length())
        assertEquals(setOf("action", "version", "phase", "deviceId", "projectionVersions", "profile", "roots", "known"), summary.keys().asSequence().toSet())
        assertFalse(preGate.any { it.has("account") || it.has("entitlement") })
    }

    @Test fun otherOwnerAndUnboundWatchStopBeforeSummary() = runBlocking<Unit> {
        seedCache(); peerOwner = "another-owner"
        assertTrue(runCatching { sync() }.exceptionOrNull() is WatchSyncBlocked)
        assertFalse(frames.any { it.optString("action") == "syncDomains" }); noDataOrReconnect()
        frames.clear(); peerOwner = "owner"; peerBound = false
        assertTrue(runCatching { sync() }.exceptionOrNull() is WatchSyncBlocked)
        assertFalse(frames.any { it.optString("action") == "syncDomains" }); noDataOrReconnect()
    }

    @Test fun changedCredentialRootAndLegacyWatchRequireNetwork() = runBlocking<Unit> {
        seedCache(); peerRoot = "e".repeat(64)
        assertEquals("需要联网完成账号验证", runCatching { sync() }.exceptionOrNull()?.message)
        noDataOrReconnect(); frames.clear(); seedCache(); peerFastPath = false
        assertEquals("需要联网完成账号验证", runCatching { sync() }.exceptionOrNull()?.message)
        noDataOrReconnect()
    }

    @Test fun switchingPhoneOwnerCannotReplayPreviousOwnersGrant() = runBlocking<Unit> {
        seedCache(); sessionStore.save(repo.session.value!!.copy(userId = "another-owner")); peerOwner = "another-owner"
        assertEquals("需要联网完成账号验证", runCatching { sync() }.exceptionOrNull()?.message)
        noDataOrReconnect()
    }

    @Test fun invalidSignatureAndExactLeaseExpiryStopBeforeAccountFrame() = runBlocking<Unit> {
        seedCache(); appStore.save(appStore.load()!!.copy(lease = "invalid-signature"))
        assertEquals("需要联网完成账号验证", runCatching { sync() }.exceptionOrNull()?.message)
        noDataOrReconnect(); frames.clear(); seedCache()
        appStore.save(appStore.load()!!.copy(lease = validLease(System.currentTimeMillis() / 1000)))
        assertEquals("需要联网完成账号验证", runCatching { sync() }.exceptionOrNull()?.message)
        noDataOrReconnect()
    }

    @Test fun exactGrantMarginAndTrustedExpiryAreRejected() = runBlocking<Unit> {
        val now = System.currentTimeMillis(); seedCache(now + 300000)
        assertNull(repo.cachedBluetoothAccount("watch", now))
        seedCache(now + 3600000)
        assertNull(repo.cachedBluetoothAccount("watch", now + 3300000))
    }

    @Test fun sameBootRollbackDoesNotExtendLease() {
        assertNull(trustedTimeDecision(1_000_000, 100_000, 1_000_000, 500_001))
        assertEquals(1_100_000L, trustedTimeDecision(1_000_000, 100_000, 1_000_000, 200_000))
    }

    @Test fun rebootWithRolledBackClockMustNotAuthorizeOfflineSync() = runBlocking<Unit> {
        seedCache()
        // Persisted elapsed time belongs to an earlier boot. Wall time has been set back
        // to the receipt time after an arbitrarily long shutdown; its true age is unknown.
        context.getSharedPreferences("offline-access", Context.MODE_PRIVATE).edit()
            .putLong("trusted_time", System.currentTimeMillis())
            .putLong("trusted_elapsed", android.os.SystemClock.elapsedRealtime() + 3600000).commit()
        val failure = runCatching { sync() }.exceptionOrNull()
        assertEquals("A reboot invalidates the monotonic anchor; require online verification", "需要联网完成账号验证", failure?.message)
        noDataOrReconnect()
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

    @Test fun issuance401MustInvalidatePreviouslyAcknowledgedGrant() = runBlocking<Unit> {
        assertIssuanceRejectionInvalidatesOtherWatch(401, "unauthorized")
    }

    @Test fun issuance403MustInvalidatePreviouslyAcknowledgedGrant() = runBlocking<Unit> {
        assertIssuanceRejectionInvalidatesOtherWatch(403, "device_session_invalid")
    }

    @Test fun refresh401And403DoInvalidatePreviouslyAcknowledgedGrant() = runBlocking<Unit> {
        for (status in listOf(401, 403)) {
            seedCache(); networkFailure = null
            httpReply = { status to "{}" }
            coordinator = AppAccessCoordinator(context, repo, identity, scope, appStore,
                environmentOverride = AccountEnvironment(backendBaseUrl = origin, supabaseAnonKey = "synthetic", appAccessPublicKey = publicPem))
            coordinator.reconcile()
            assertNull("refresh rejection $status", sessionStore.bluetoothGrant(grantKey()))
            networkFailure = java.net.UnknownHostException("offline after refusal")
            assertNotNull(runCatching { sync() }.exceptionOrNull()); noDataOrReconnect()
        }
    }

    @Test fun onlineWithCacheStillPerformsBaselineAccountVerification() = runBlocking<Unit> {
        seedCache(); onlineReplies(); sync()
        assertTrue("Online account gate previously reconciled with the server before issuing a grant", networkCalls > 0)
    }

    @Test fun onlineWithoutCacheStillRefreshesAndIssuesCredentials() = runBlocking<Unit> {
        seedCache(); sessionStore.removeBluetoothGrant(grantKey()); onlineReplies(); sync()
        assertEquals(3, networkCalls)
        assertTrue(frames.any { it.optString("action") == "syncAccount" })
        assertEquals(0, reconnects)
    }

    @Test fun tlsProxyDnsTimeoutFailuresKeepPromptAndZeroReconnects() = runBlocking<Unit> {
        for (cause in listOf(java.net.UnknownHostException("DNS"), java.net.SocketTimeoutException("HTTP read"),
            java.net.ConnectException("proxy connect"), java.net.NoRouteToHostException("route"),
            javax.net.ssl.SSLHandshakeException("certificate"), javax.net.ssl.SSLPeerUnverifiedException("hostname"),
            java.net.ProtocolException("proxy CONNECT 407"), IOException("proxy EOF"))) {
            networkFailure = cause
            assertEquals(cause.javaClass.simpleName, "需要联网完成账号验证", runCatching { sync() }.exceptionOrNull()?.message)
            noDataOrReconnect()
        }
    }

    @Test fun allHttpRejectionsRemainDistinctFromNetworkFailure() = runBlocking<Unit> {
        for (status in listOf(400, 401, 402, 403, 404, 407, 408, 409, 410, 422, 429, 500, 502, 503, 504)) {
            networkFailure = null; httpReply = { status to "{}" }
            val failure = runCatching { sync() }.exceptionOrNull()
            assertNotNull("HTTP $status", failure)
            assertFalse("HTTP $status", failure is PhoneAccountNetworkRequired)
            noDataOrReconnect()
        }
    }
    @Test fun changedBootWithGreaterUptimeMustRequireOnlineValidation()=runBlocking<Unit> {
        seedCache()
        android.os.SystemClock.sleep(100)
        context.getSharedPreferences("offline-access",Context.MODE_PRIVATE).edit().putLong("trusted_elapsed",0).commit()
        android.provider.Settings.Global.putInt(context.contentResolver,android.provider.Settings.Global.BOOT_COUNT,101)
        assertTrue(android.os.SystemClock.elapsedRealtime()>0)
        assertEquals("需要联网完成账号验证",runCatching{sync()}.exceptionOrNull()?.message)
        noDataOrReconnect()
    }
    @Test fun nextOnlineValidationReanchorsTheNewBoot()=runBlocking<Unit> {
        seedCache();android.provider.Settings.Global.putInt(context.contentResolver,android.provider.Settings.Global.BOOT_COUNT,101)
        onlineReplies();sync();assertEquals(3,networkCalls)
        assertEquals("101",context.getSharedPreferences("offline-access",Context.MODE_PRIVATE).getString("trusted_boot",null))
        networkFailure=java.net.UnknownHostException("offline after validation");frames.clear();sync()
        assertEquals(4,networkCalls);assertEquals(0,reconnects)
        assertTrue(frames.any{it.optString("action")=="syncAccount"})
    }
    @Test fun networkFailuresFallbackToCacheButHttpResponsesNeverDo()=runBlocking<Unit> {
        for(cause in listOf(java.net.UnknownHostException(),java.net.ConnectException(),java.net.SocketTimeoutException(),
            javax.net.ssl.SSLHandshakeException("TLS"),javax.net.ssl.SSLPeerUnverifiedException("TLS"),java.net.ProtocolException("proxy CONNECT"))) {
            seedCache();networkFailure=cause;frames.clear()
            // A new coordinator removes only the in-memory retry deadline, not persistent security state.
            coordinator=AppAccessCoordinator(context,repo,identity,scope,appStore,environmentOverride=AccountEnvironment(backendBaseUrl=origin,supabaseAnonKey="synthetic",appAccessPublicKey=publicPem))
            sync();assertEquals(0,reconnects);assertTrue(frames.any{it.optString("action")=="syncAccount"})
        }
        seedCache();onlineReplies(503,"unavailable");frames.clear()
        coordinator=AppAccessCoordinator(context,repo,identity,scope,appStore,environmentOverride=AccountEnvironment(backendBaseUrl=origin,supabaseAnonKey="synthetic",appAccessPublicKey=publicPem))
        assertNotNull(runCatching{sync()}.exceptionOrNull());noDataOrReconnect()
    }
    @Test fun baselineHttpCountAndAfterReconcileAreRetainedWithCache()=runBlocking<Unit> {
        seedCache();onlineReplies();var notified=0
        coordinator=AppAccessCoordinator(context,repo,identity,scope,appStore,environmentOverride=AccountEnvironment(backendBaseUrl=origin,supabaseAnonKey="synthetic",appAccessPublicKey=publicPem),onAuthorized={notified++})
        sync();assertEquals(3,networkCalls);assertEquals(1,notified)
        frames.clear();sync();assertEquals(4,networkCalls);assertEquals(2,notified)
    }
    @Test fun directAccountAndSharedDeviceRequestsPersistRejectionForAllWatches()=runBlocking<Unit> {
        for(status in listOf(401,403)) {
            seedCache();sessionStore.saveBluetoothGrant(grantKey("other-watch"),sessionStore.bluetoothGrant(grantKey())!!)
            networkFailure=null;httpReply={status to "{}"}
            assertTrue(isExplicitAccountRejection(runCatching{repo.securityStatus()}.exceptionOrNull()!!))
            assertNull(sessionStore.bluetoothGrant(grantKey()));assertNull(sessionStore.bluetoothGrant(grantKey("other-watch")))
            val restored=EncryptedAccountSessionStore(context).also{ReflectionHelpers.setField(it,"prefsRef",context.getSharedPreferences("offline-session",Context.MODE_PRIVATE))}
            restored.load();assertTrue(restored.offlineAuthorizationBlocked(BleDomainStore.hash("$origin\nowner")))
            // Even a retained/repopulated grant is not sufficient to undo a durable refusal.
            seedCache();assertNull(repo.cachedBluetoothAccount("watch",System.currentTimeMillis()))
            val reply=Response.Builder().request(okhttp3.Request.Builder().url(origin).build()).protocol(Protocol.HTTP_1_1)
                .code(status).message("refused").body("{}".toResponseBody()).build()
            coordinator.withDeviceSession("owner"){reply}.use{assertEquals(status,it.code)}
            assertNull(sessionStore.bluetoothGrant(grantKey()))
        }
    }
    @Test fun successfulOnlineValidationClearsTheRefusalButDoesNotRestoreOldGrants()=runBlocking<Unit> {
        seedCache();onlineReplies(401,"unauthorized")
        assertNotNull(runCatching{coordinator.buildWatchAccountSyncRequest("second-watch",null,null)}.exceptionOrNull())
        assertTrue(repo.offlineAuthorizationBlocked("owner"));assertNull(sessionStore.bluetoothGrant(grantKey()))
        onlineReplies();val replies=httpReply
        httpReply={path->val(status,body)=replies(path);status to if(path.endsWith("app-access/refresh"))JSONObject(body).put("deviceAccessToken","recovery-token").toString() else body}
        sync();assertFalse(repo.offlineAuthorizationBlocked("owner"))
        assertNull(sessionStore.bluetoothGrant(grantKey("second-watch")))
        networkFailure=java.net.UnknownHostException("offline");frames.clear();sync()
        assertTrue(frames.any{it.optString("action")=="syncAccount"});assertEquals(0,reconnects)
    }
    @Test fun issuanceRetryRejectionAlsoBlocksOfflineCache()=runBlocking<Unit> {
        for(status in listOf(401,403)) {
            seedCache();onlineReplies();val replies=httpReply;var issued=0;var refreshed=0
            coordinator=AppAccessCoordinator(context,repo,identity,scope,appStore,environmentOverride=AccountEnvironment(backendBaseUrl=origin,supabaseAnonKey="synthetic",appAccessPublicKey=publicPem))
            httpReply={path->when {
                path.endsWith("issue-watch-device-token")->if(++issued==1)403 to "{\"error\":\"device_session_expired\"}" else status to "{\"error\":\"device_session_invalid\"}"
                path.endsWith("app-access/refresh")->replies(path).let{(n,b)->n to JSONObject(b).put("deviceAccessToken","rotation-${++refreshed}").toString()}
                else->replies(path)
            }}
            assertNotNull(runCatching{coordinator.buildWatchAccountSyncRequest("second-watch",null,null)}.exceptionOrNull())
            assertEquals(2,issued);assertTrue(repo.offlineAuthorizationBlocked("owner"));assertNull(sessionStore.bluetoothGrant(grantKey()))
            networkFailure=java.net.UnknownHostException("offline");frames.clear()
            assertNotNull(runCatching{sync()}.exceptionOrNull());noDataOrReconnect()
        }
    }
    @Test fun lateRefreshResponseCannotUndoANewerExplicitRefusal()=runBlocking<Unit> {
        seedCache();onlineReplies();val replies=httpReply
        httpReply={path->replies(path).also{if(path.endsWith("app-access/refresh"))repo.recordAuthenticationResponse("owner",401,"{}")}}
        assertNotNull(runCatching{sync()}.exceptionOrNull())
        assertTrue(repo.offlineAuthorizationBlocked("owner"));noDataOrReconnect()
    }

    @Test fun explicitRefusalHeadersStillBlockCacheWhenReadingTheBodyTimesOut()=runBlocking<Unit> {
        for(status in listOf(401,403)) {
            seedCache();networkFailure=null;httpReply={status to "{}"}
            coordinator=AppAccessCoordinator(context,repo,identity,scope,appStore,environmentOverride=AccountEnvironment(backendBaseUrl=origin,supabaseAnonKey="synthetic",appAccessPublicKey=publicPem))
            replyBody={_,_->object:okhttp3.ResponseBody(){
                override fun contentType()="application/json".toMediaType()
                override fun contentLength()=10L
                override fun source()=object:okio.Source {
                        override fun read(sink:okio.Buffer,byteCount:Long):Long=throw java.net.SocketTimeoutException("refusal body stalled")
                        override fun timeout()=okio.Timeout.NONE
                        override fun close()=Unit
                    }.buffer()
            }}
            val failure=runCatching{coordinator.buildWatchAccountSyncRequest("second-watch",null,null)}.exceptionOrNull()
            assertTrue(failure is PhoneLoginRequired || failure is PhoneDeviceVerificationRequired)
            assertTrue(repo.offlineAuthorizationBlocked("owner"));assertNull(sessionStore.bluetoothGrant(grantKey()))
            noDataOrReconnect()
        }
    }
    @Test fun lateIssuanceSuccessCannotRecreateAGrantAfterAnotherRequestRefuses()=runBlocking<Unit> {
        seedCache();onlineReplies();val replies=httpReply
        httpReply={path->replies(path).also{if(path.endsWith("issue-watch-device-token"))repo.recordAuthenticationResponse("owner",403,"{}")}}
        assertTrue(runCatching{sync()}.exceptionOrNull() is PhoneDeviceVerificationRequired)
        assertTrue(repo.offlineAuthorizationBlocked("owner"));assertNull(sessionStore.bluetoothGrant(grantKey()))
        noDataOrReconnect()
    }
    @Test fun refusalIsBoundToTheOwnerWhoSentTheRequest()=runBlocking<Unit> {
        seedCache();networkFailure=null;httpReply={401 to "{}"}
        val entered=CompletableDeferred<Unit>();val release=java.util.concurrent.CountDownLatch(1)
        beforeNetwork={entered.complete(Unit);check(release.await(5,java.util.concurrent.TimeUnit.SECONDS))}
        val call=scope.async{runCatching{repo.securityStatus()}}
        try {
            withTimeout(2000){entered.await()}
            sessionStore.save(repo.session.value!!.copy(userId="other-owner"))
            val other=JSONObject(sessionStore.bluetoothGrant(grantKey())!!.toString()).apply{getJSONObject("account").put("userId","other-owner")}
            sessionStore.saveBluetoothGrant(grantKey(owner="other-owner"),other)
            release.countDown();assertNotNull(withTimeout(5000){call.await()}.exceptionOrNull())
            assertTrue(repo.offlineAuthorizationBlocked("owner"));assertFalse(repo.offlineAuthorizationBlocked("other-owner"))
            assertNull(sessionStore.bluetoothGrant(grantKey()));assertNotNull(sessionStore.bluetoothGrant(grantKey(owner="other-owner")))
        } finally {release.countDown()}
    }
    @Test fun pushRegistrationRefusalUsesTheSamePersistentGate()=runBlocking<Unit> {
        seedCache()
        val http=okhttp3.OkHttpClient.Builder().addInterceptor {chain->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(403).message("refused").body("{}".toResponseBody()).build()
        }.build()
        val uploader=com.lightningstudio.watchrss.phone.push.PhonePushRegistrationUploader(
            AccountEnvironment(backendBaseUrl=origin,supabaseAnonKey="synthetic"),repo,{"device"},"install","phone",http)
        assertFalse(uploader.upload("synthetic-registration"))
        assertTrue(repo.offlineAuthorizationBlocked("owner"));assertNull(sessionStore.bluetoothGrant(grantKey()))
        noDataOrReconnect()
    }

}
