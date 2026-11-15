package com.lightningstudio.watchrss.phone.account

import android.content.Context
import com.lightningstudio.watchrss.phone.cloud.CloudKeyManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(application=android.app.Application::class,manifest=Config.NONE,sdk=[35])
class SameAccountReauthenticationTest {
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
        syncState.edit().putString("epoch","epoch-A").putLong("pending",17).commit()
        repo.onClearCloudState={session->clears++;keys.clearAccountKeys(session.userId);syncState.edit().clear().commit()}
    }
    @Test fun sameAccountReauthenticationPreservesRealKeyStoreAndSyncStateAcrossReopen()=runBlocking<Unit>{
        seed();val before=syncState.all.toMap()
        repo.beginSameAccountReauthentication()
        assertEquals("owner",repo.session.value?.userId);assertFalse(repo.hasUsableSession)
        assertEquals(Long.MAX_VALUE,repo.session.value!!.expiresAtMillis)
        assertArrayEquals(root,keys.getAccountKey("owner",1))
        val reopened=EncryptedAccountSessionStore(context).also { ReflectionHelpers.setField(it,"prefsRef",prefs) }
        reopened.load();assertEquals("owner",reopened.reauthenticationOwner.value);assertEquals(Long.MAX_VALUE,reopened.session.value!!.expiresAtMillis)
        repo.saveCompletedLogin(LoginProgress("login",1,listOf("sms"),true,old.copy(accessToken="synthetic-new")))
        assertTrue(repo.hasUsableSession);assertNull(repo.reauthenticationOwner.value)
        assertArrayEquals(root,keys.getAccountKey("owner",1))
        assertEquals(before,syncState.all);assertEquals(0,releases);assertEquals(0,clears)
    }
    @Test fun differentAccountReauthenticationUsesExistingCustodyCleanup()=runBlocking<Unit>{
        seed();repo.beginSameAccountReauthentication()
        repo.saveCompletedLogin(LoginProgress("login",1,listOf("sms"),true,old.copy(userId="other",accessToken="synthetic-new")))
        assertEquals("other",repo.session.value?.userId);assertTrue(repo.hasUsableSession)
        assertFalse(keys.hasAccountKey("owner"))
        assertTrue(syncState.all.isEmpty());assertEquals(1,clears);assertEquals(0,releases)
    }
    @Test fun pausedServerValidSessionStillRevokesOnCompleteLogout()=runBlocking<Unit>{
        seed();repo.beginSameAccountReauthentication()
        val server=okhttp3.mockwebserver.MockWebServer();server.start()
        try {
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("{}"))
            val target=AccountEnvironment(backendBaseUrl=server.url("/").toString().trimEnd('/'),supabaseAnonKey="synthetic")
            val logoutRepo=PhoneAccountRepository(target,store,PhoneInstallationIdentity(context),PhoneAccountClient(target,LicenseDeviceIdentity(context)),"phone")
            logoutRepo.onClearCloudState=repo.onClearCloudState
            logoutRepo.logout()
            assertEquals(1,server.requestCount);assertNull(logoutRepo.session.value)
            assertNull(logoutRepo.reauthenticationOwner.value);assertFalse(keys.hasAccountKey("owner"))
            assertTrue(syncState.all.isEmpty());assertEquals(1,clears)
        } finally {server.shutdown()}
    }

    @Test fun pausedColdSessionPreservesExpiryAndBlocksAllAuthenticationRequests()=runBlocking<Unit>{
        seed();repo.beginSameAccountReauthentication()
        val reopened=EncryptedAccountSessionStore(context).also { ReflectionHelpers.setField(it,"prefsRef",prefs) }
        val paused=reopened.load()!!
        assertEquals(Long.MAX_VALUE,paused.expiresAtMillis);assertFalse(paused.isExpired)
        assertTrue(paused.authenticationPaused);assertFalse(paused.isUsableForAuthentication)
        val server=okhttp3.mockwebserver.MockWebServer();server.start()
        try {
            val env=environment.copy(backendBaseUrl=server.url("/").toString().trimEnd('/'))
            assertTrue(runCatching{repo.appAccessStatus()}.isFailure)
            assertTrue(runCatching{com.lightningstudio.watchrss.phone.cloud.PhoneCloudClient(env).bootstrap(paused)}.isFailure)
            val client=PhoneAccountClient(env,LicenseDeviceIdentity(context))
            assertEquals("登录已过期，请重新登录",runCatching{client.issueWatchDeviceToken(paused,"phone","watch")}.exceptionOrNull()?.message)
            assertEquals(0,server.requestCount);assertArrayEquals(root,keys.getAccountKey("owner",1))
        } finally {server.shutdown()}
    }
    @Test fun pausedLogoutQueuesOriginalServerValidTokenWhenServerUnavailable()=runBlocking<Unit>{
        seed();repo.beginSameAccountReauthentication()
        val server=okhttp3.mockwebserver.MockWebServer();server.start()
        try {
            try { androidx.work.WorkManager.getInstance(context) } catch (_:IllegalStateException) {
                val held=java.util.concurrent.Executor { }
                androidx.work.WorkManager.initialize(context,androidx.work.Configuration.Builder().setExecutor(held).setTaskExecutor(held).build())
            }
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(503).setBody("{}"))
            val env=environment.copy(backendBaseUrl=server.url("/").toString().trimEnd('/'))
            val offline=PhoneAccountRepository(env,store,PhoneInstallationIdentity(context),PhoneAccountClient(env,LicenseDeviceIdentity(context)),"phone")
            offline.onClearCloudState=repo.onClearCloudState;offline.logout()
            val pending=store.pendingRevocation()!!
            assertEquals(Long.MAX_VALUE,pending.expiresAtMillis);assertFalse(pending.isExpired)
            assertEquals("synthetic-old",pending.accessToken);assertNull(offline.session.value)
            assertFalse(keys.hasAccountKey("owner"));assertEquals(1,server.requestCount)
        } finally {server.shutdown()}
    }

}
