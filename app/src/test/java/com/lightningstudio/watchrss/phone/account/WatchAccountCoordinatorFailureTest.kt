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
        android.provider.Settings.Global.putInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, 100)
        java.security.Security.addProvider(object:java.security.Provider("acctgate-r2-keystore",1.0,"synthetic identity") {
            init { put("KeyStore.AndroidKeyStore",com.lightningstudio.watchrss.phone.cloud.CloudContentRebuildIntegrationTest.FixtureKeyStore::class.java.name) }
        })
        server.start()
        val environment=AccountEnvironment(backendBaseUrl=server.url("/").toString().trimEnd('/'),supabaseAnonKey="synthetic",appAccessPublicKey=publicPem)
        repo=PhoneAccountRepository(environment,sessionStore,PhoneInstallationIdentity(context),PhoneAccountClient(environment,identity),"phone")
        sessionStore.save(PhoneAccountSession("owner","phone","synthetic-account","",Long.MAX_VALUE))
        keys.saveAccountKey("owner",1,root,true);keys.syncV2Write("owner","secret/K_id","synthetic-K_id")
        coordinator=AppAccessCoordinator(context,repo,identity,scope,appStore,environmentOverride=environment)
        // An expired lease takes the real status reconciliation branch; failed status must retain its type.
        appStore.save(AppAuthorization("old",1,"expired-lease",1,"release",AppAccessSummary(deviceStatus="authorized")))
    }
    @After fun close(){scope.cancel();server.shutdown();java.security.Security.removeProvider("acctgate-r2-keystore")}
    private fun custodyUnchanged() {
        assertEquals("owner",repo.session.value?.userId);assertEquals("synthetic-account",repo.session.value?.accessToken)
        assertArrayEquals(root,keys.getAccountKey("owner",1));assertEquals("synthetic-K_id",keys.syncV2Read("owner","secret/K_id"))
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

    @Test fun integratedCloudAndWatchRefreshShareRateLimitBackoffWithoutLosingTypedDelay()=runBlocking<Unit>{
        val now=System.currentTimeMillis()
        appStore.save(AppAuthorization("old",now+300000,validLease(),now+3600000,"release",AppAccessSummary(deviceStatus="authorized"),now))
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After","120").setBody("{}"))
        assertTrue(coordinator.refreshWatchDeviceSession("old","owner") is WatchAccountRefreshResult.TemporaryFailure)
        val watch=coordinator.refreshWatchDeviceSession("old","owner") as WatchAccountRefreshResult.TemporaryFailure
        assertTrue(watch.retryAfterMillis!! in 119000L..120000L)
        val cloud=runCatching{coordinator.refreshRejectedDeviceSession("old","owner")}.exceptionOrNull() as PhoneAccountTemporarilyUnavailable
        assertTrue(cloud.retryAfterMillis!! in 119000L..120000L)
        assertEquals(1,server.requestCount);custodyUnchanged()
    }
    @Test fun integratedWatchRefreshWaitsForAnInFlightCloudSessionLease()=runBlocking<Unit>{
        val now=System.currentTimeMillis()
        appStore.save(AppAuthorization("old",now+300000,validLease(),now+3600000,"release",AppAccessSummary(deviceStatus="authorized"),now))
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val reader=scope.async{coordinator.withDeviceSession("owner"){token->assertEquals("old",token);entered.complete(Unit);release.await()}}
        entered.await();server.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
        val refresh=scope.async{coordinator.refreshWatchDeviceSession("old","owner")}
        delay(80);assertEquals(0,server.requestCount)
        release.complete(Unit);withTimeout(3000){reader.await();assertTrue(refresh.await() is WatchAccountRefreshResult.TemporaryFailure)}
        assertEquals(1,server.requestCount);custodyUnchanged()
    }

}
