package com.lightningstudio.watchrss.phone.syncv2

import android.content.Context
import com.lightningstudio.watchrss.phone.cloud.CloudKeyManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class SyncV2P6ContentKeysTest {
    private fun manager():CloudKeyManager {
        val prefs=RuntimeEnvironment.getApplication().getSharedPreferences("p6-keys-${System.nanoTime()}",Context.MODE_PRIVATE)
        return CloudKeyManager(null,preferencesOverride=prefs).also{it.bindBackend("https://synthetic.invalid")}
    }
    @Test fun v1VersionsSelectObjectVersionAndNewestWriteVersion() {
        val manager=manager();manager.saveAccountKey("u",1,ByteArray(32){1},true);manager.saveAccountKey("u",2,ByteArray(32){2},false)
        var verified=false
        val keys=SyncV2ContentKeys.authenticated(manager,"u"){assertEquals(setOf(1,2),it.keys);verified=true}
        assertTrue(verified);assertEquals(2L,keys.newestVersion)
        val identity=SyncV2Fixtures.identity()
        val old=SyncV2Envelope.seal(identity,keys.read(1),1,"blob","old body".toByteArray())
        assertEquals("old body",SyncV2Envelope.open(identity,keys.read(old.header.getLong("keyVersion")),old.oid,old.bytes).toString(Charsets.UTF_8))
        try{keys.read(3);fail("missing version accepted")}catch(e:IllegalStateException){assertEquals("key_unavailable",e.message)}
        val retained=keys.read(1);keys.close();assertTrue(retained.all{it==0.toByte()})
        try{keys.read(1);fail("closed keys accepted")}catch(e:IllegalStateException){assertEquals("authentication_required",e.message)}
        assertArrayEquals(ByteArray(32){1},manager.getAccountKey("u",1))
    }
    @Test fun unauthenticatedKeysCannotEscape() {
        val manager=manager();manager.saveAccountKey("u",1,ByteArray(32){1},true)
        var retained:ByteArray?=null
        try{SyncV2ContentKeys.authenticated(manager,"u"){retained=it.getValue(1);error("authentication_required")};fail("accepted unverified keys")}
        catch(e:IllegalStateException){assertEquals("authentication_required",e.message)}
        assertTrue(retained!!.all{it==0.toByte()})
        try{SyncV2ContentKeys.authenticated(manager,"other"){};fail("missing root accepted")}
        catch(e:IllegalStateException){assertEquals("key_unavailable",e.message)}
    }
}
