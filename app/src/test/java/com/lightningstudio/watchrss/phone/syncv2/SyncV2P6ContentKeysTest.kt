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
    @Test fun oldRecipeAndFinalBodyBindingRemainReadableWithNewerAuthenticatedKey() {
        SyncV2P3Fixture().use {f->
            val manager=manager();manager.saveAccountKey("u",1,ByteArray(32){7},true);manager.saveAccountKey("u",2,ByteArray(32){8},true)
            SyncV2ContentKeys.authenticated(manager,"u"){}.use {keys->
                val directory=java.io.File(RuntimeEnvironment.getApplication().cacheDir,"old-recipe-${System.nanoTime()}")
                try {
                    val store=SyncV2ContentStore(directory,f.identity.scopeJson,SyncV2Fixtures.localKey.copyOf())
                    val binding=SyncV2ContentExport.Binding("11".repeat(32),"22".repeat(16),"auth")
                    val exporter=SyncV2ContentExport(f.identity,keys.read(1),1,store)
                    val source=object:SyncV2ContentExport.Source {override val size=3L;override val version="one";override fun read(offset:Long,maxBytes:Int)="abc".toByteArray().copyOfRange(offset.toInt(),offset.toInt()+maxBytes)}
                    while(!exporter.step(binding,source)){}
                    val prepared=exporter.descriptor(binding)!!;val oid=prepared.getString("recipeOid");val recipe=prepared.getJSONObject("recipe")
                    val blobs=org.json.JSONArray().put(recipe.getJSONArray("blocks").getJSONObject(0).getString("oid"))
                    val signed=SyncV2ContentCodec.refs(f.identity,f.signer,oid,blobs);val raw=SyncV2Codec.utf8(SyncV2Codec.canonical(signed));val refsOid=SyncV2Codec.oid("recipe-refs",signed);f.objects[refsOid]=raw
                    val mapping=f.dto().put("parentOid",oid).put("refsOid",refsOid).put("authorDeviceId",f.signer.deviceId).put("keyId",f.signer.keyId).put("totalBytes",raw.size)
                    val proof=f.signer.signed("wrss.sync.v2/availability-sign",org.json.JSONObject().put("version",2).put("scope",f.identity.scope).put("entityKey",binding.entity).put("bodyRevision",binding.revision).put("recipeOid",oid).put("status","cloud-ready").put("blobOidsHash",SyncV2Codec.hash(blobs)).put("storeId","99".repeat(16)).put("receiptRevision","1").put("createdAtMillis",f.now).put("expiresAtMillis",f.now+1000))
                    f.route={r->when {
                        r.requestUrl!!.encodedPath.contains("recipe-refs/")->f.response(mapping)
                        r.requestUrl!!.encodedPath.endsWith("availability/check")->f.response(f.dto().put("availability",org.json.JSONObject().put("scope",f.identity.scope).put("entityKey",binding.entity).put("bodyRevision",binding.revision).put("recipeOid",oid).put("status","cloud-ready").put("receipt",proof)))
                        else->null
                    }}
                    val client=f.client();client.capabilities()
                    val worker=SyncV2ContentWorker(client,f.identity,store,{SyncV2TransportSignals(wifi=true,internet=true)},enabled=true,now={f.now})
                    val receiver=SyncV2ContentReceive(f.identity,f.authors(),store,worker,client,keys::read,now={f.now})
                    val row=org.json.JSONObject().put("entityKey",binding.entity).put("bodyRevision",binding.revision).put("recipeOid",oid).put("opaqueContentId",recipe.get("opaqueContentId")).put("contentBytes",3).put("rowSequence","1").put("deleted",false)
                    val verifiedRecipe=receiver.recipe(row,"12".repeat(16));assertNull(receiver.step(row,verifiedRecipe))
                    var applied=false;receiver.step(row,verifiedRecipe)!!.bind(row){applied=true}
                    assertTrue(applied);assertEquals(2L,keys.newestVersion)
                    store.close()
                } finally {directory.deleteRecursively()}
            }
        }
    }

}
