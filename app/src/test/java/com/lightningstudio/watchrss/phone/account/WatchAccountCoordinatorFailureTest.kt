package com.lightningstudio.watchrss.phone.account

import android.content.Context
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

/** Real coordinator + repository + HTTP client. Secure prefs and the local HTTP server are boundaries. */
@RunWith(RobolectricTestRunner::class)
@Config(application=android.app.Application::class,manifest=Config.NONE,sdk=[35])
class WatchAccountCoordinatorFailureTest {
    private val context=RuntimeEnvironment.getApplication()
    private val server=MockWebServer()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    private val sessionStore=EncryptedAccountSessionStore(context).also { ReflectionHelpers.setField(it,"prefsRef",context.getSharedPreferences("coordinator-session",Context.MODE_PRIVATE)) }
    private val appStore=AppAccessStore(context,preferencesOverride=context.getSharedPreferences("coordinator-access",Context.MODE_PRIVATE))
    private val identity=LicenseDeviceIdentity(context)
    private val keys=CloudKeyManager(context,preferencesOverride=context.getSharedPreferences("coordinator-keys",Context.MODE_PRIVATE))
    private val root=ByteArray(32){it.toByte()}
    private val leaseKeys=java.security.KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    private val publicPem="-----BEGIN PUBLIC KEY-----"+java.util.Base64.getEncoder().encodeToString(leaseKeys.public.encoded)+"-----END PUBLIC KEY-----"
    private fun validLease():String {
        fun b64(bytes:ByteArray)=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val payload=org.json.JSONObject().put("licenseDeviceId",identity.deviceId).put("expiresAt",System.currentTimeMillis()/1000+3600)
        val signed=b64("{\"alg\":\"ES256\"}".toByteArray())+"."+b64(payload.toString().toByteArray())
        val der=java.security.Signature.getInstance("SHA256withECDSA").run{initSign(leaseKeys.private);update(signed.toByteArray());sign()}
        val rLength=der[3].toInt() and 255
        val r=der.copyOfRange(4,4+rLength).takeLast(32).toByteArray()
        val sLength=der[5+rLength].toInt() and 255
        val part=der.copyOfRange(6+rLength,6+rLength+sLength).takeLast(32).toByteArray()
        return signed+"."+b64(ByteArray(32-r.size)+r+ByteArray(32-part.size)+part)
    }
    private lateinit var repo:PhoneAccountRepository
    private lateinit var coordinator:AppAccessCoordinator
    @Before fun setup()=runBlocking<Unit>{
        java.security.Security.addProvider(object:java.security.Provider("hotfix133-keystore",1.0,"synthetic signing identity") {
            init { put("KeyStore.AndroidKeyStore",FixtureKeyStore::class.java.name) }
        })
        server.start()
        val environment=AccountEnvironment(backendBaseUrl=server.url("/").toString().trimEnd('/'),supabaseAnonKey="synthetic",appAccessPublicKey=publicPem)
        repo=PhoneAccountRepository(environment,sessionStore,PhoneInstallationIdentity(context),PhoneAccountClient(environment,identity),"phone")
        sessionStore.save(PhoneAccountSession("owner","phone","synthetic-account","",Long.MAX_VALUE))
        keys.saveAccountKey("owner",1,root,true)
        coordinator=AppAccessCoordinator(context,repo,identity,scope,appStore,environmentOverride=environment)
        // An expired lease takes the real status reconciliation branch; failed status must retain its type.
        appStore.save(AppAuthorization("old",1,"expired-lease",1,"release",AppAccessSummary(deviceStatus="authorized")))
    }
    @After fun close(){scope.cancel();server.shutdown();java.security.Security.removeProvider("hotfix133-keystore")}
    private fun custodyUnchanged() {
        assertEquals("owner",repo.session.value?.userId);assertEquals("synthetic-account",repo.session.value?.accessToken)
        assertArrayEquals(root,keys.getAccountKey("owner",1))
    }
    @Test fun realCoordinatorPreserves5xxInsteadOfReturningLoginOrBooleanFalse()=runBlocking<Unit>{
        server.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
        val result=coordinator.refreshWatchDeviceSession("old","owner")
        assertTrue(result.toString(),result is WatchAccountRefreshResult.TemporaryFailure);custodyUnchanged()
        assertEquals(1,server.requestCount)
    }
    @Test fun realCoordinatorPreservesRateLimitAndRetryDelay()=runBlocking<Unit>{
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"retryAfterMillis":2000}"""))
        val result=coordinator.refreshWatchDeviceSession("old","owner")
        assertTrue(result is WatchAccountRefreshResult.TemporaryFailure)
        assertEquals(2000L,(result as WatchAccountRefreshResult.TemporaryFailure).retryAfterMillis);custodyUnchanged()
    }
    @Test fun realCoordinatorUsesOnlyAccount401ForUnrecoverableAuthentication()=runBlocking<Unit>{
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        assertTrue(coordinator.refreshWatchDeviceSession("old","owner") is WatchAccountRefreshResult.AccountUnrecoverable)
        custodyUnchanged()
    }
    @Test fun realCoordinatorDistinguishesDeviceVerificationFromAccountFailure()=runBlocking<Unit>{
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"device_session_revoked"}"""))
        assertTrue(coordinator.refreshWatchDeviceSession("old","owner") is WatchAccountRefreshResult.DeviceVerificationRequired)
        custodyUnchanged()
    }
    @Test fun refresh403FollowedByStatus5xxRemainsTemporaryAndPreservesCustody()=runBlocking<Unit>{
        val now=System.currentTimeMillis()
        appStore.save(AppAuthorization("old",now+300000,validLease(),now+3600000,"release",AppAccessSummary(deviceStatus="authorized"),now))
        server.enqueue(MockResponse().setResponseCode(403).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
        val result=coordinator.refreshWatchDeviceSession("old","owner")
        assertTrue(result.toString(),result is WatchAccountRefreshResult.TemporaryFailure)
        assertEquals(2,server.requestCount);custodyUnchanged()
    }

    @Test fun claimBranchesPreserve401429And503InsteadOfDeviceVerification()=runBlocking<Unit>{
        sessionStore.save(sessionStore.session.value!!.copy(activationProof="synthetic-proof"))
        for(code in listOf(401,429,503)) {
            server.enqueue(MockResponse().setBody("""{"accessMode":"paid","deviceStatus":"unclaimed"}"""))
            server.enqueue(MockResponse().setResponseCode(code).setBody("{}"))
            server.enqueue(MockResponse().setBody("""{"accessMode":"paid","deviceStatus":"unclaimed"}"""))
            val error=runCatching{coordinator.buildWatchAccountSyncRequest("watch",null,null)}.exceptionOrNull()
            if(code==401)assertTrue(error.toString(),error is com.lightningstudio.watchrss.phone.connection.bluetooth.PhoneLoginRequired)
            else assertTrue(error.toString(),error is PhoneAccountTemporarilyUnavailable)
            custodyUnchanged()
        }
        assertEquals(9,server.requestCount)
    }
    @Test fun pendingPaymentFailureRetainsTemporaryClassification()=runBlocking<Unit>{
        appStore.savePendingOrder(AppPaymentOrder("order","merchant",600,"pending",null),"owner")
        server.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
        val error=runCatching{coordinator.buildWatchAccountSyncRequest("watch",null,null)}.exceptionOrNull()
        assertTrue(error.toString(),error is PhoneAccountTemporarilyUnavailable)
        assertEquals(1,server.requestCount);custodyUnchanged()
    }


    @Test fun postLoginDevice401RestoresPendingAuthenticationWithoutClearingKeys()=runBlocking<Unit>{
        repo.beginSameAccountReauthentication()
        repo.saveCompletedLogin(LoginProgress("login",1,listOf("sms"),true,repo.session.value!!.copy(accessToken="synthetic-account")))
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        assertTrue(coordinator.completeSameAccountLogin() is WatchAccountRefreshResult.AccountUnrecoverable)
        assertFalse(repo.hasUsableSession);assertEquals("owner",repo.reauthenticationOwner.value)
        assertEquals(Long.MAX_VALUE,repo.session.value!!.expiresAtMillis);custodyUnchanged()
    }
    @Test fun postLoginCompletionRequiresReadyDeviceAuthorization()=runBlocking<Unit>{
        repo.beginSameAccountReauthentication()
        repo.saveCompletedLogin(LoginProgress("login",1,listOf("sms"),true,repo.session.value!!))
        val now=System.currentTimeMillis()
        appStore.save(AppAuthorization("old",now+3600000,validLease(),now+3600000,"release",AppAccessSummary(deviceStatus="authorized"),now))
        server.enqueue(MockResponse().setBody(org.json.JSONObject().put("challengeId","synthetic").put("nonce","synthetic").put("expiresAt",now+60000).toString()))
        server.enqueue(MockResponse().setBody(org.json.JSONObject().put("deviceAccessToken","new").put("deviceAccessTokenExpiresAt",now+3600000)
            .put("lease",validLease()).put("leaseExpiresAt",now+3600000).put("serverTime",now).put("access",org.json.JSONObject().put("deviceStatus","authorized")).toString()))
        assertEquals(WatchAccountRefreshResult.Ready,coordinator.completeSameAccountLogin())
        assertTrue(repo.hasUsableSession);assertNull(repo.reauthenticationOwner.value);custodyUnchanged()
    }

    private suspend fun claimFailure(status:Int, fallbackStatus:Int=200):Throwable? {
        sessionStore.save(repo.session.value!!.copy(activationProof="synthetic-proof"))
        server.enqueue(MockResponse().setBody("""{"accessMode":"paid","deviceStatus":"authorized"}"""))
        server.enqueue(MockResponse().setResponseCode(status).setBody("""{"error":"synthetic","retryAfterMillis":2000}"""))
        server.enqueue(MockResponse().setResponseCode(fallbackStatus).setBody("""{"accessMode":"paid","deviceStatus":"authorized"}"""))
        val failure=runCatching{coordinator.buildWatchAccountSyncRequest("watch",null,"watch")}.exceptionOrNull()
        assertEquals(3,server.requestCount);custodyUnchanged();return failure
    }
    @Test fun claim401RetainsAccountFailure()=runBlocking<Unit>{assertTrue(claimFailure(401) is com.lightningstudio.watchrss.phone.connection.bluetooth.PhoneLoginRequired)}
    @Test fun claim403RetainsDeviceVerification()=runBlocking<Unit>{assertTrue(claimFailure(403) is PhoneDeviceVerificationRequired)}
    @Test fun claim429RetainsTemporaryFailureAndDelay()=runBlocking<Unit>{
        val failure=claimFailure(429);assertTrue(failure is PhoneAccountTemporarilyUnavailable)
        assertEquals(2000L,(failure as PhoneAccountTemporarilyUnavailable).retryAfterMillis)
    }
    @Test fun claim503RetainsTemporaryFailure()=runBlocking<Unit>{assertTrue(claimFailure(503) is PhoneAccountTemporarilyUnavailable)}
    @Test fun pendingOrderErrorsKeepTheirTypes()=runBlocking<Unit>{
        for(code in listOf(401,403,429,503)) {
            appStore.savePendingOrder(AppPaymentOrder(orderId="order",merchantOrderId="merchant",amountFen=600,status="pending",paymentUrl=null),"owner")
            server.enqueue(MockResponse().setResponseCode(code).setBody("""{"retryAfterMillis":1000}"""))
            val result=coordinator.reconcile()
            when(code){401->assertTrue(result is WatchAccountRefreshResult.AccountUnrecoverable)
                403->assertTrue(result is WatchAccountRefreshResult.DeviceVerificationRequired)
                else->assertTrue(result is WatchAccountRefreshResult.TemporaryFailure)}
            custodyUnchanged()
        }
        assertEquals(4,server.requestCount)
    }
    @Test fun paidOrderClaimFailureAlsoKeepsTemporaryType()=runBlocking<Unit>{
        sessionStore.save(repo.session.value!!.copy(activationProof="synthetic-proof"));appStore.clear()
        appStore.savePendingOrder(AppPaymentOrder(orderId="order",merchantOrderId="merchant",amountFen=600,status="pending",paymentUrl=null),"owner")
        server.enqueue(MockResponse().setBody("""{"orderId":"order","merchantOrderId":"merchant","amountFen":600,"status":"paid"}"""))
        server.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
        server.enqueue(MockResponse().setBody("""{"accessMode":"paid","deviceStatus":"authorized"}"""))
        assertTrue(coordinator.reconcile() is WatchAccountRefreshResult.TemporaryFailure);assertEquals(3,server.requestCount);custodyUnchanged()
    }
    @Test fun cancelledOrderStatusIsNotConvertedToDeviceFailure()=runBlocking<Unit>{
        appStore.savePendingOrder(AppPaymentOrder(orderId="order",merchantOrderId="merchant",amountFen=600,status="pending",paymentUrl=null),"owner")
        server.enqueue(MockResponse().setBody("""{"orderId":"order","merchantOrderId":"merchant","amountFen":600,"status":"cancelled"}"""))
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"retryAfterMillis":1000}"""))
        assertTrue(coordinator.reconcile() is WatchAccountRefreshResult.TemporaryFailure);assertEquals(2,server.requestCount);custodyUnchanged()
    }

    @Test fun pendingOrderCancellationEscapesWithoutFallbackRequests()=runBlocking<Unit>{
        appStore.savePendingOrder(AppPaymentOrder(orderId="order",merchantOrderId="merchant",amountFen=600,status="pending",paymentUrl=null),"owner")
        server.enqueue(MockResponse().setBody("""{"orderId":"order","merchantOrderId":"merchant","amountFen":600,"status":"pending"}""").setBodyDelay(150,java.util.concurrent.TimeUnit.MILLISECONDS))
        var returned=false
        val job=scope.async{coordinator.reconcile();returned=true}
        assertNotNull(server.takeRequest(2,java.util.concurrent.TimeUnit.SECONDS));job.cancel()
        withTimeout(3000){job.join()};assertFalse(returned);assertEquals(1,server.requestCount);custodyUnchanged()
    }
    @Test fun claimCancellationEscapesBeforeAnyStatusFallback()=runBlocking<Unit>{
        sessionStore.save(repo.session.value!!.copy(activationProof="synthetic-proof"))
        server.enqueue(MockResponse().setBody("""{"accessMode":"paid","deviceStatus":"authorized"}"""))
        server.enqueue(MockResponse().setResponseCode(503).setBody("{}").setBodyDelay(150,java.util.concurrent.TimeUnit.MILLISECONDS))
        var returned=false
        val job=scope.async{coordinator.reconcile();returned=true}
        assertNotNull(server.takeRequest(2,java.util.concurrent.TimeUnit.SECONDS));assertNotNull(server.takeRequest(2,java.util.concurrent.TimeUnit.SECONDS))
        job.cancel();withTimeout(3000){job.join()};assertFalse(returned);assertEquals(2,server.requestCount);custodyUnchanged()
    }

    @Test fun claimFallbackFailuresAreAlsoTyped()=runBlocking<Unit>{
        val failure=claimFailure(403,503)
        assertTrue(failure.toString(),failure is PhoneAccountTemporarilyUnavailable)
    }
    @Test fun claimFallback401IsDefinitiveAccountFailure()=runBlocking<Unit>{
        assertTrue(claimFailure(503,401) is com.lightningstudio.watchrss.phone.connection.bluetooth.PhoneLoginRequired)
    }
    @Test fun claim401IsNotOverwrittenByFallback503()=runBlocking<Unit>{
        assertTrue(claimFailure(401,503) is com.lightningstudio.watchrss.phone.connection.bluetooth.PhoneLoginRequired)
    }

    @Test fun legacyRefreshAdapterPreservesTemporaryErrorRatherThanFalse()=runBlocking<Unit>{
        server.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
        val error=runCatching{coordinator.refreshRejectedDeviceSession("old","owner")}.exceptionOrNull()
        assertTrue(error.toString(),error is PhoneAccountTemporarilyUnavailable);custodyUnchanged()
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

}
