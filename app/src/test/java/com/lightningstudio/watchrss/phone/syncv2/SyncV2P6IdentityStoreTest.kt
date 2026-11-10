package com.lightningstudio.watchrss.phone.syncv2

import android.content.Context
import com.lightningstudio.watchrss.phone.cloud.CloudKeyManager
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class SyncV2P6IdentityStoreTest {
    private val user="12121212-1212-1212-1212-121212121212"
    private fun manager()=CloudKeyManager(null,preferencesOverride=RuntimeEnvironment.getApplication().getSharedPreferences("id-${System.nanoTime()}",Context.MODE_PRIVATE)).also {
        it.bindBackend("https://synthetic.invalid");it.saveAccountKey(user,1,ByteArray(32){7},true);it.saveAccountKey(user,2,ByteArray(32){8},true)
    }
    @Test fun exactOldWrappingVersionSamePinReinstallAndFullPinConflict() {
        val manager=manager();val life=SyncV2Lifetime{true};val ring=life.own(SyncV2ContentKeys.authenticated(manager,user){})
        val store=SyncV2AccountStore(manager,user,life)
        val candidate=SyncV2RepoBootstrap.prepareStored("12".repeat(16),"old","epoch","gen",1,ring::read,{null}){}
        val pin=SyncV2Codec.oid("repo-descriptor",candidate.descriptor);val recovery=SyncV2Codec.oid("id-key-recovery",candidate.recoveryCapsule)
        val first=life.own(store.install(candidate.descriptor,candidate.recoveryCapsule,pin,recovery,ring))
        val again=life.own(store.install(candidate.descriptor,candidate.recoveryCapsule,pin,recovery,ring))
        assertEquals(first.entity("note","id"),again.entity("note","id"));assertEquals(2L,ring.newestVersion)
        val original=store.read("pin")!!
        for(field in listOf("descriptorOid","idKeyCommitment","wrappingKeyVersion","scope")) {
            val changed=JSONObject(original.toString());changed.put(field,when(field){"wrappingKeyVersion"->2;"scope"->JSONObject(first.scopeJson).put("libraryEpoch","2");else->"conflict"})
            store.write("pin",changed)
            try{store.install(candidate.descriptor,candidate.recoveryCapsule,pin,recovery,ring);fail("pin conflict accepted")}
            catch(e:IllegalStateException){assertEquals("identity_conflict",e.message)}
        }
        store.write("pin",original)
        val bad=JSONObject(candidate.recoveryCapsule.toString()).put("ciphertext","AAAA")
        try{store.install(candidate.descriptor,bad,pin,recovery,ring);fail("bad capsule accepted")}
        catch(e:IllegalStateException){assertEquals("hash_mismatch",e.message)}
        life.close()
    }
    @Test fun accountClearImmediatelyClosesInflightKeysAndRejectsLateInstall() {
        val manager=manager();val life=SyncV2Lifetime{true}
        life.own(manager.onAccountRelease(user){life.close()})
        val ring=life.own(SyncV2ContentKeys.authenticated(manager,user){})
        val buffer=ring.read(1);val store=SyncV2AccountStore(manager,user,life)
        store.pending(JSONObject().put("test",true))
        manager.clearAccountKeys(user)
        assertTrue(buffer.all{it==0.toByte()})
        assertNull(manager.syncV2Read(user,"secret/pending"))
        try{store.pending(JSONObject());fail("late install accepted")}catch(e:IllegalStateException){assertEquals("authentication_required",e.message)}
        assertFalse(manager.hasAccountKey(user))
    }
    @Test fun closedRoomProjectionAndIdentityRejectRetainedReferences() {
        SyncV2P5Fixture().use {f->
            val identity=f.identity;val projection=f.projection
            projection.close();identity.close()
            try{projection.state();fail("closed projection accepted")}catch(e:IllegalStateException){assertEquals("authentication_required",e.message)}
            try{identity.entity("note","id");fail("closed identity accepted")}catch(e:IllegalStateException){assertEquals("authentication_required",e.message)}
            f.sql.query("SELECT length(identity) FROM syncv2_state").use{assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0))}
        }
    }
}
