package com.lightningstudio.watchrss.phone.account

import android.content.Context
import com.lightningstudio.watchrss.phone.cloud.CloudKeyManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(application=android.app.Application::class,manifest=Config.NONE,sdk=[35])
class SameAccountReauthenticationTest {
    private var previousManagers=emptyMap<java.lang.reflect.Field,Any?>()
    private var isolatedManager:androidx.work.impl.WorkManagerImpl?=null
    @Before fun isolateRevocationWork() {
        val fields=listOf("sDefaultInstance","sDelegatedInstance").map{name->androidx.work.impl.WorkManagerImpl::class.java.getDeclaredField(name).apply{isAccessible=true}}
        previousManagers=fields.associateWith{it.get(null)}
        fields.forEach{it.set(null,null)}
        androidx.work.WorkManager.initialize(RuntimeEnvironment.getApplication(), androidx.work.Configuration.Builder()
            .setExecutor { /* Background execution is outside this persistence test. */ }
            .setTaskExecutor { /* Keep the queued token under this test's custody. */ }.build())
        isolatedManager=androidx.work.impl.WorkManagerImpl.getInstance(RuntimeEnvironment.getApplication())
    }
    @After fun restoreRevocationWork() {
        isolatedManager?.let{manager->
            val background=ReflectionHelpers.getField<kotlinx.coroutines.CoroutineScope>(manager,"mWorkManagerScope")
            background.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            manager.workDatabase.close()
        }
        previousManagers.forEach{(field,value)->field.set(null,value)}
    }

    private val context=RuntimeEnvironment.getApplication()
    private val prefs=context.getSharedPreferences("reauth-account",Context.MODE_PRIVATE)
    private val store=EncryptedAccountSessionStore(context).also { ReflectionHelpers.setField(it,"prefsRef",prefs) }
    private val keys=CloudKeyManager(context,"",context.getSharedPreferences("reauth-keys",Context.MODE_PRIVATE))
    private val syncState=context.getSharedPreferences("reauth-sync",Context.MODE_PRIVATE)
    private val environment=AccountEnvironment(backendBaseUrl="http://127.0.0.1:1",supabaseAnonKey="synthetic")
    private val repo=PhoneAccountRepository(environment,store,PhoneInstallationIdentity(context),PhoneAccountClient(environment,LicenseDeviceIdentity(context)),"phone")
    private var releases=0;private var clears=0
    private val old=PhoneAccountSession("owner","phone","synthetic-old","",Long.MAX_VALUE)
    private val root=ByteArray(32){it.toByte()}
    private suspend fun seed() {
        store.save(old);keys.saveAccountKey("owner",1,root,true)
        keys.syncV2Write("owner","secret/K_id","synthetic-secret")
        syncState.edit().putString("epoch","epoch-A").putLong("pending",17).commit()
        repo.onReleaseCloudAuthorization={session->assertFalse(session.authenticationPaused);releases++}
        repo.onClearCloudState={session->clears++;keys.clearAccountKeys(session.userId);syncState.edit().clear().commit()}
    }
    @Test fun sameAccountReauthenticationPreservesRealKeyStoreAndSyncStateAcrossReopen()=runBlocking<Unit>{
        seed();val before=syncState.all.toMap()
        repo.beginSameAccountReauthentication()
        assertEquals("owner",repo.session.value?.userId);assertFalse(repo.hasUsableSession)
        assertFalse(repo.session.value!!.isExpired);assertTrue(repo.session.value!!.authenticationPaused);assertEquals(Long.MAX_VALUE,repo.session.value!!.expiresAtMillis)
        assertArrayEquals(root,keys.getAccountKey("owner",1));assertEquals("synthetic-secret",keys.syncV2Read("owner","secret/K_id"))
        val reopened=EncryptedAccountSessionStore(context).also { ReflectionHelpers.setField(it,"prefsRef",prefs) }
        reopened.load();assertEquals("owner",reopened.reauthenticationOwner.value);assertFalse(reopened.session.value!!.isExpired);assertTrue(reopened.session.value!!.authenticationPaused);assertEquals(Long.MAX_VALUE,reopened.session.value!!.expiresAtMillis)
        repo.saveCompletedLogin(LoginProgress("login",1,listOf("sms"),true,old.copy(accessToken="synthetic-new")))
        assertTrue(repo.hasUsableSession);assertNull(repo.reauthenticationOwner.value)
        assertArrayEquals(root,keys.getAccountKey("owner",1));assertEquals("synthetic-secret",keys.syncV2Read("owner","secret/K_id"))
        assertEquals(before,syncState.all);assertEquals(0,releases);assertEquals(0,clears)
    }
    @Test fun differentAccountReauthenticationUsesExistingCustodyCleanup()=runBlocking<Unit>{
        seed();repo.beginSameAccountReauthentication()
        repo.saveCompletedLogin(LoginProgress("login",1,listOf("sms"),true,old.copy(userId="other",accessToken="synthetic-new")))
        assertEquals("other",repo.session.value?.userId);assertTrue(repo.hasUsableSession)
        assertFalse(keys.hasAccountKey("owner"));assertNull(keys.syncV2Read("owner","secret/K_id"))
        assertTrue(syncState.all.isEmpty());assertEquals(1,clears);assertEquals(0,releases)
    }
    @Test fun coldRepositoryPreservesExpiryAndCompletesSameOwnerLogin()=runBlocking<Unit>{
        seed();repo.beginSameAccountReauthentication()
        val reopened=EncryptedAccountSessionStore(context).also{ReflectionHelpers.setField(it,"prefsRef",prefs)};reopened.load()
        val next=PhoneAccountRepository(environment,reopened,PhoneInstallationIdentity(context),PhoneAccountClient(environment,LicenseDeviceIdentity(context)),"phone")
        next.onClearCloudState={error("same owner must retain keys")}
        assertFalse(next.hasUsableSession);assertEquals(Long.MAX_VALUE,next.session.value!!.expiresAtMillis)
        assertTrue(runCatching{next.appAccessStatus()}.isFailure)
        next.saveCompletedLogin(LoginProgress("login",1,listOf("sms"),true,old.copy(accessToken="synthetic-new")))
        val cold=EncryptedAccountSessionStore(context).also{ReflectionHelpers.setField(it,"prefsRef",prefs)};cold.load()
        assertNull(cold.reauthenticationOwner.value);assertTrue(cold.session.value!!.isUsableForAuthentication)
        assertArrayEquals(root,keys.getAccountKey("owner",1));assertEquals(17L,syncState.getLong("pending",0))
    }
    @Test fun explicitLogoutDuringPauseQueuesServerValidToken()=runBlocking<Unit>{
        seed();repo.beginSameAccountReauthentication();repo.logout()
        assertNull(repo.session.value);assertEquals(1,clears);assertEquals(1,releases)
        val queued=store.pendingRevocation();assertNotNull(queued)
        assertEquals("synthetic-old",queued!!.accessToken);assertEquals(Long.MAX_VALUE,queued.expiresAtMillis)
        assertFalse(queued.isExpired)
    }

    @Test fun explicitLogoutDuringPauseRevokesOriginalTokenWhenServerAvailable()=runBlocking<Unit>{
        val server=okhttp3.mockwebserver.MockWebServer();server.start()
        try {
            seed();repo.beginSameAccountReauthentication()
            val env=environment.copy(backendBaseUrl=server.url("/").toString().trimEnd('/'))
            val next=PhoneAccountRepository(env,store,PhoneInstallationIdentity(context),PhoneAccountClient(env,LicenseDeviceIdentity(context)),"phone")
            next.onClearCloudState=repo.onClearCloudState;next.onReleaseCloudAuthorization=repo.onReleaseCloudAuthorization
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("{}"))
            next.logout()
            val request=server.takeRequest(2,java.util.concurrent.TimeUnit.SECONDS)!!
            assertEquals("/functions/v1/account/logout",request.path)
            assertEquals("Bearer synthetic-old",request.getHeader("authorization"))
            assertNull(store.pendingRevocation());assertNull(next.session.value)
            assertEquals(1,releases);assertEquals(1,clears);assertFalse(keys.hasAccountKey("owner"))
        } finally {server.shutdown()}
    }

    @Test fun pausedAuthenticationBlocksRepositoryCloudAndSupportRequestsWithoutNetwork()=runBlocking<Unit>{
        val server=okhttp3.mockwebserver.MockWebServer();server.start()
        try {
            seed();repo.beginSameAccountReauthentication()
            val env=environment.copy(backendBaseUrl=server.url("/").toString().trimEnd('/'))
            val paused=repo.session.value!!
            assertFalse(paused.isExpired);assertFalse(paused.isUsableForAuthentication)
            val accountError=runCatching{repo.appAccessStatus()}.exceptionOrNull()
            assertEquals("请先完成账号重新认证",accountError!!.message)
            assertTrue(runCatching{com.lightningstudio.watchrss.phone.cloud.PhoneCloudClient(env).bootstrap(paused)}.isFailure)
            val support=com.lightningstudio.watchrss.phone.support.SupportClient(env,repo.session)
            assertNull(support.identity());assertTrue(runCatching{support.json("owner","/status")}.isFailure)
            assertEquals(0,server.requestCount);assertArrayEquals(root,keys.getAccountKey("owner",1))
        } finally {server.shutdown()}
    }

}
