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
        keys.syncV2Write("owner","secret/K_id","synthetic-secret")
        syncState.edit().putString("epoch","epoch-A").putLong("pending",17).commit()
        repo.onReleaseCloudAuthorization={releases++}
        repo.onClearCloudState={session->clears++;keys.clearAccountKeys(session.userId);syncState.edit().clear().commit()}
    }
    @Test fun sameAccountReauthenticationPreservesRealKeyStoreAndSyncStateAcrossReopen()=runBlocking<Unit>{
        seed();val before=syncState.all.toMap()
        repo.beginSameAccountReauthentication()
        assertEquals("owner",repo.session.value?.userId);assertFalse(repo.hasUsableSession)
        assertTrue(repo.session.value!!.isExpired)
        assertArrayEquals(root,keys.getAccountKey("owner",1));assertEquals("synthetic-secret",keys.syncV2Read("owner","secret/K_id"))
        val reopened=EncryptedAccountSessionStore(context).also { ReflectionHelpers.setField(it,"prefsRef",prefs) }
        reopened.load();assertEquals("owner",reopened.reauthenticationOwner.value);assertTrue(reopened.session.value!!.isExpired)
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
}
