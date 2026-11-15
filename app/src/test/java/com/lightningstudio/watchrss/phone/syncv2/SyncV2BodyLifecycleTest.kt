package com.lightningstudio.watchrss.phone.syncv2
import android.content.Context
import com.lightningstudio.watchrss.phone.cloud.CloudKeyManager
import okhttp3.mockwebserver.MockResponse
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SyncV2BodyLifecycleTest {
 private fun keys(f:SyncV2P5Fixture):SyncV2ContentKeys {
  val c=RuntimeEnvironment.getApplication() as Context
  val m=CloudKeyManager(null,preferencesOverride=c.getSharedPreferences("review-body-${System.nanoTime()}",Context.MODE_PRIVATE));m.bindBackend("http://synthetic");m.saveAccountKey("synthetic",1,f.key,true)
  return SyncV2ContentKeys.authenticated(m,"synthetic"){}
 }
 @Test fun accountReleaseAfterNetworkReadCannotInstallOrAcknowledge() {
  SyncV2P5Fixture().use{f->f.start();val client=f.f.client();val id="release.bin";val raw=ByteArray(180000){(it%251).toByte()};val entity=f.identity.entity("asset",id)
   SyncV2PhoneApply(f.sql).upsert("note_assets",listOf("assetId"),JSONObject().put("assetId","local-private").put("storageKey",id).put("mimeType","application/octet-stream").put("displayName",id).put("createdAt",1).put("deleted",false).put("deletedAt",0))
   f.drain();f.projection.prepareWireIndex(f.f.signer,f.key,1).close();val revision=SyncV2BodyCodec.revision("asset",entity,SyncV2BodyCodec.hash(raw));f.projection.bindArticleRevision(f.projection.wireContentRow(entity)!!,revision,true)
   SyncV2CloudSync(client,f.identity,f.projection,{true},{f.key},{f.f.now}).publishIndex(f.f.signer,f.key,1);f.actions().refresh()
   val entry=f.projection.cloudRows(setOf(entity)).single();val a=f.projection.state().body.getJSONObject("account");val anchor=JSONObject().put("commitOid",a.get("oid")).put("revision",a.get("revision")).put("rollbackGeneration",a.get("generation"))
   val prepared=SyncV2BodyCodec.prepare(f.identity.scope,anchor,SyncV2BodyCodec.Binding("asset",id,entity,revision),f.key,1,f.f.signer,raw)
   var current=true;var bodyReads=0;val previous=f.f.route
   f.f.route={r->if(r.requestUrl!!.encodedPath.startsWith("/functions/v1/cloud/bodies/v1/")){bodyReads++;current=false;val h=r.requestUrl!!.pathSegments.last();val bytes=prepared.objects.getValue(h);val at=r.requestUrl!!.queryParameter("offset")!!.toInt();MockResponse().setBody(Buffer().write(bytes.copyOfRange(at,minOf(at+65536,bytes.size)))).setHeader("x-watchrss-body-total-bytes",bytes.size).setHeader("x-watchrss-body-offset",at)}else previous!!.invoke(r)}
   val repo=SyncV2BodyRepository(f.sql,File(f.root,"receiving"));val ack=f.ackCalls;val before=f.projection.state().revision
   keys(f).use{ring->val channel=SyncV2BodyChannel(f.projection,f.identity,ring,f.f.signer,client,repo,File(f.root,"cache"),SyncV2Lifetime{current},{true},{SyncV2TransportSignals(wifi=true,internet=true)})
    val err=runCatching{channel.receive(entry,anchor,prepared.descriptor,File(f.root,"cache"),true)}.exceptionOrNull();assertEquals("authentication_required",err?.message);assertEquals(1,bodyReads);assertFalse(repo.file("asset",id).exists());assertEquals(ack,f.ackCalls);assertEquals(before,f.projection.state().revision)
   }
  }
 }
 @Test fun completedDownloadRemovesDuplicatePartAndCleansAnOldCheckpoint() {
  SyncV2P5Fixture().use{f->val client=f.f.client();client.capabilities();val raw=ByteArray(180000){(it%251).toByte()};val hash=SyncV2BodyCodec.hash(raw);val previous=f.f.route
   f.f.route={r->if(r.requestUrl!!.encodedPath.startsWith("/functions/v1/cloud/bodies/v1/")){val at=r.requestUrl!!.queryParameter("offset")!!.toInt();MockResponse().setBody(Buffer().write(raw.copyOfRange(at,minOf(at+65536,raw.size)))).setHeader("x-watchrss-body-total-bytes",raw.size).setHeader("x-watchrss-body-offset",at)}else previous!!.invoke(r)}
   val dir=File(f.root,"cache");assertArrayEquals(raw,SyncV2BodyHttp(client){}.download("aa".repeat(16),hash,raw.size,dir))
   assertEquals(raw.size.toLong(),File(dir,hash).length());assertFalse(File(dir,"$hash.part").exists())
   assertEquals(raw.size.toLong(),dir.listFiles()!!.sumOf{it.length()})
   // Upgrade cleanup: a previously completed target plus stale part needs no network.
   File(dir,"$hash.part").writeBytes(raw);var checks=0
   assertArrayEquals(raw,SyncV2BodyHttp(client){checks++}.download("aa".repeat(16),hash,raw.size,dir))
   assertEquals(0,checks);assertFalse(File(dir,"$hash.part").exists())
  }
 }
 @Test fun missingCapabilitiesDoesNotDeclareUpgradeButExplicitServerRejectionDoes() {
  for((status,code)in listOf(404 to "object_missing",426 to "upgrade_required"))SyncV2P5Fixture().use{f->
   val previous=f.f.route
   f.f.route={r->if(r.requestUrl!!.encodedPath.endsWith("/capabilities"))f.f.response(JSONObject().put("error",JSONObject().put("code",code).put("retryable",false).put("retryAfterMillis",JSONObject.NULL).put("details",JSONObject()))).setResponseCode(status)else previous!!.invoke(r)}
   val error=runCatching{f.f.client().capabilities()}.exceptionOrNull()!!
   assertEquals(code,error.message)
   assertEquals(status==426,com.lightningstudio.watchrss.phone.cloud.serverRequiresCloudClientUpgrade(error))
  }
 }

}
