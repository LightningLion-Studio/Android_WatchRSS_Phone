package com.lightningstudio.watchrss.phone.account

import android.content.Context
import com.lightningstudio.watchrss.phone.cloud.CloudKeyManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry

@RunWith(AndroidJUnit4::class)
class Hotfix133CustodyDeviceTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs=context.getSharedPreferences("reauth-account",Context.MODE_PRIVATE)
    private val store=EncryptedAccountSessionStore(context, prefsName="hotfix133-custody")
    private val keys=CloudKeyManager(context,"_hotfix133")
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
        val reopened=EncryptedAccountSessionStore(context, prefsName="hotfix133-custody")
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
}
