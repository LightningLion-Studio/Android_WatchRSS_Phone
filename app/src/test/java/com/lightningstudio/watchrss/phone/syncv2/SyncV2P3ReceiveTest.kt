package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncV2P3ReceiveTest {
    @get:Rule val temp=TemporaryFolder()
    private val identity=SyncV2Fixtures.identity();private val key=ByteArray(32){7}
    private fun error(code:String,fn:()->Unit) {try{fn();fail("accepted invalid input")}catch(e:IllegalStateException){assertEquals(code,e.message)}}
    private fun availability(f:SyncV2P3Fixture,entity:String,revision:String,oid:String,blobs:JSONArray,status:String="cloud-ready")=f.signer.signed("wrss.sync.v2/availability-sign",JSONObject()
        .put("version",2).put("scope",identity.scope).put("entityKey",entity).put("bodyRevision",revision).put("recipeOid",oid).put("status",status)
        .put("blobOidsHash",if(status=="cloud-ready")SyncV2Codec.hash(blobs)else JSONObject.NULL).put("storeId","99".repeat(16)).put("receiptRevision","9007199254740993").put("createdAtMillis",f.now).put("expiresAtMillis",f.now+1000))
    @Test fun signedAvailabilityBindsEveryReferenceScopeStatusAndTimeAndCannotBeAnObjectReceipt() {
        SyncV2P3Fixture().use { f -> val entity="11".repeat(32);val revision="22".repeat(16);val oid="recipe:sha256:"+"33".repeat(32);val blobs=JSONArray().put("blob:sha256:"+"44".repeat(32))
            val signed=availability(f,entity,revision,oid,blobs);assertEquals("cloud-ready",f.trust().availability(signed,identity.scope,entity,revision,oid,blobs,f.now))
            error("hash_mismatch"){f.trust().availability(signed,identity.scope,entity,revision,oid,JSONArray(),f.now)}
            error("invalid_schema"){f.trust().availability(signed,identity.scope,entity,"55".repeat(16),oid,blobs,f.now)}
            error("replay_detected"){f.trust().availability(signed,identity.scope,entity,revision,oid,blobs,f.now+1000)}
            error("signature_invalid"){f.trust().availability(f.receipt(oid,1,1),identity.scope,entity,revision,oid,blobs,f.now)}
            assertEquals("source-pending",f.trust().availability(availability(f,entity,revision,oid,blobs,"source-pending"),identity.scope,entity,revision,oid,blobs,f.now))
        }
    }
    @Test fun decryptedRecipePublicEdgesAndFullBodyMustAllMatchBeforeBindingAndStaleTaskCannotBind() {
        SyncV2P3Fixture().use { f ->
            val dir=temp.newFolder();val store=SyncV2ContentStore(dir,identity.scopeJson,SyncV2Fixtures.localKey)
            val b=SyncV2ContentExport.Binding("11".repeat(32),"22".repeat(16),"auth")
            val source=object:SyncV2ContentExport.Source {override val size=524289L;override val version="v1";override fun read(offset:Long,maxBytes:Int)=ByteArray(maxBytes){((it+offset)%251).toByte()}}
            val export=SyncV2ContentExport(identity,key,1,store);while(!export.step(b,source)){}
            val descriptor=export.descriptor(b)!!;val recipe=descriptor.getJSONObject("recipe");val recipeOid=descriptor.getString("recipeOid")
            val row=JSONObject().put("entityKey",b.entity).put("bodyRevision",b.revision).put("recipeOid",recipeOid).put("opaqueContentId",recipe.get("opaqueContentId")).put("contentBytes",source.size).put("rowSequence","9007199254740993").put("deleted",false)
            val blocks=recipe.getJSONArray("blocks");val blobs=JSONArray();for(i in 0 until blocks.length())blobs.put(blocks.getJSONObject(i).getString("oid"))
            val signed=SyncV2ContentCodec.refs(identity,f.signer,recipeOid,blobs);val raw=SyncV2Codec.utf8(SyncV2Codec.canonical(signed));val refsOid=SyncV2Codec.oid("recipe-refs",signed)
            val mapping=f.dto().put("parentOid",recipeOid).put("refsOid",refsOid).put("authorDeviceId",f.signer.deviceId).put("keyId",f.signer.keyId).put("totalBytes",raw.size)
            f.objects[refsOid]=raw
            f.route={request->when {
                request.requestUrl!!.encodedPath.contains("recipe-refs/")->f.response(mapping)
                request.requestUrl!!.encodedPath.endsWith("availability/check")->f.response(f.dto().put("leaseId",JSONObject.NULL).put("expiresAtMillis",JSONObject.NULL).put("availability",JSONObject().put("scope",identity.scope).put("entityKey",b.entity).put("bodyRevision",b.revision).put("recipeOid",recipeOid).put("status","cloud-ready").put("receipt",availability(f,b.entity,b.revision,recipeOid,blobs))))
                else->null
            }}
            val client=f.client();client.capabilities();val worker=SyncV2ContentWorker(client,identity,store,{SyncV2TransportSignals(wifi=true,internet=true,cellular=false)},enabled=true,now={f.now})
            val receiver=SyncV2ContentReceive(identity,f.authors(),store,worker,client,{key},now={f.now})
            val verifiedRecipe=receiver.recipe(row,"12".repeat(16))
            assertNull(receiver.step(row,verifiedRecipe));assertEquals(2,store.counts.authentications) // recipe plus one blob
            val reopened=SyncV2ContentStore(dir,identity.scopeJson,SyncV2Fixtures.localKey)
            val resumedWorker=SyncV2ContentWorker(client,identity,reopened,{SyncV2TransportSignals(wifi=true,internet=true,cellular=false)},enabled=true,now={f.now})
            val resumed=SyncV2ContentReceive(identity,f.authors(),reopened,resumedWorker,client,{key},now={f.now})
            assertNull(resumed.step(row,verifiedRecipe));assertNull(resumed.step(row,verifiedRecipe));val complete=resumed.step(row,verifiedRecipe)!!
            assertEquals(2,reopened.counts.authentications)
            var binds=0;complete.bind(row){binds++};assertEquals(1,binds)
            error("body_revision_changed"){complete.bind(JSONObject(row.toString()).put("rowSequence","9007199254740994")){binds++}}
            error("body_revision_changed"){complete.bind(JSONObject(row.toString()).put("deleted",true)){binds++}}
            assertEquals(1,binds)
            val before=reopened.counts.authentications;assertNotNull(resumed.step(row,verifiedRecipe));assertEquals(before,reopened.counts.authentications)
            val changed=JSONObject(recipe.toString()).put("plaintextSha256","ff".repeat(32))
            error("hash_mismatch"){SyncV2ContentCodec.verifyBody(identity,changed,{block->reopened.plain(block.getString("oid"))})}
            error("invalid_schema"){SyncV2ContentCodec.verifyRefs(identity,f.authors(),recipeOid,JSONArray(),JSONObject(mapping.toString()).apply{remove("version")},raw)}
            // A separate destination exercises the complete page entry point and exact receipt sizes.
            for(oid in (0 until blobs.length()).map{blobs.getString(it)}+recipeOid) {
                f.objects[oid]=store.objectBytes(oid);f.prefix[oid]=f.objects[oid]!!
            }
            val fresh=SyncV2ContentStore(temp.newFolder(),identity.scopeJson,SyncV2Fixtures.localKey)
            val freshClient=f.client();val freshWorker=SyncV2ContentWorker(freshClient,identity,fresh,{SyncV2TransportSignals(wifi=true,internet=true,cellular=false)},enabled=true,now={f.now})
            val freshReceiver=SyncV2ContentReceive(identity,f.authors(),fresh,freshWorker,freshClient,{key},now={f.now})
            val session=SyncV2ContentSession(identity,fresh,freshWorker,freshClient,f.signer,SyncV2ContentExport(identity,key,1,fresh))
            var cacheReady=0
            val outcome=session.downloadPage(listOf(row),freshReceiver,{f.now},{row},{cacheReady++})
            assertEquals("complete",outcome.status);assertEquals(1,outcome.completeBodies);assertEquals(1,cacheReady);assertTrue(outcome.transferredBytes>source.size)
            SyncV2Fixtures.evidence("p3-receive-authentication.json",JSONObject().put("blocks",blocks.length()).put("beforeRestartAuth",store.counts.authentications).put("afterRestartAuth",reopened.counts.authentications).put("bindings",binds))
        }
    }
}
