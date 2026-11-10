package com.lightningstudio.watchrss.phone.syncv2

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class SyncV2P6BootstrapTest {
    @Test fun persistentCandidateReplaysCompleteDescriptorAndCapsuleAcrossReopen() {
        val prefs=RuntimeEnvironment.getApplication().getSharedPreferences("pending-${System.nanoTime()}",Context.MODE_PRIVATE)
        val account="12".repeat(16);val wrap=ByteArray(32){7};var saves=0
        fun prepare(version:Long=1)=SyncV2RepoBootstrap.prepareStored(account,"old","epoch","gen",version,{wrap},
            {prefs.getString("pending",null)?.let(::JSONObject)}) {saves++;assertTrue(prefs.edit().putString("pending",SyncV2Codec.canonical(it)).commit())}
        val first=prepare();val second=prepare(2)
        assertEquals(2,saves)
        assertEquals(SyncV2Codec.canonical(first.descriptor),SyncV2Codec.canonical(second.descriptor))
        assertEquals(SyncV2Codec.canonical(first.recoveryCapsule),SyncV2Codec.canonical(second.recoveryCapsule))
        assertEquals(1L,second.descriptor.getJSONObject("body").getLong("wrappingKeyVersion"))
        val pin=SyncV2Codec.oid("repo-descriptor",first.descriptor)
        val identity=SyncV2Identity.recover(first.recoveryCapsule,first.descriptor,wrap,account,first.scope,pin)
        assertEquals(identity.entity("note","one"),SyncV2Identity.recover(second.recoveryCapsule,second.descriptor,wrap,account,second.scope,pin).entity("note","one"))
        val device=SyncV2Signing.fromPrivate(ByteArray(31)+byteArrayOf(1),"aa".repeat(16),"bb".repeat(16))
        assertNotEquals(device.publicHex,first.descriptor.getJSONObject("body").getString("creatorPublicKey"))
        assertNotEquals(device.deviceId,first.descriptor.getJSONObject("body").getString("creatorDeviceId"))
        try{SyncV2RepoBootstrap.prepareStored(account,"old","other epoch","gen",1,{wrap},{JSONObject(prefs.getString("pending",null)!!)}){};fail("wrong fence accepted")}
        catch(e:IllegalStateException){assertEquals("scope_mismatch",e.message)}
    }
    @Test fun failedFullPersistenceNeverReturnsSendableCandidate() {
        var pending:JSONObject?=null;var saves=0
        try{SyncV2RepoBootstrap.prepareStored("12".repeat(16),"old","epoch","gen",1,{ByteArray(32){7}},{pending}) {
            saves++;if(saves==2)error("storage_pressure");pending=JSONObject(it.toString())
        };fail("failed persistence accepted")}catch(e:IllegalStateException){assertEquals("storage_pressure",e.message)}
        assertNotNull(pending);assertFalse(pending!!.has("descriptor"))
    }
}
