package com.lightningstudio.watchrss.phone.syncv2
import android.content.Context
import com.lightningstudio.watchrss.phone.cloud.CloudKeyManager
import org.json.JSONObject
import org.json.JSONArray
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
class SyncV2BodyTombstoneTest {
 @Test fun tombstonesPreserveAuthenticatedDescriptorsOrPublishNullWithoutOriginals() {
  for(kind in listOf("asset","book"))for(description in listOf("missing","null","promise","recipe"))for(choice in listOf(false,true))for(present in listOf(false,true))SyncV2P5Fixture().use{f->f.start()
   val fingerprint=com.lightningstudio.watchrss.phone.data.book.BookSync.fingerprint(listOf(com.lightningstudio.watchrss.phone.data.book.BookSync.chapterHash(1,"Ch","Body")))
   val id=if(kind=="asset")"deleted.png"else com.lightningstudio.watchrss.phone.data.book.BookSync.id(fingerprint)
   val value=JSONObject().put("bookId",id).put("fingerprint",fingerprint).put("chapterCount",1).put("title","Deleted").put("kind","txt").put("sourceUrl","https://watchrss.local/import-txt-novel/review").put("deletedChapters",JSONArray()).put("deletionClock",0).put("deleted",false).put("deletedAt",0).put("updatedAt",1).put("sourceDeviceId","synthetic-phone")
   val apply=SyncV2PhoneApply(f.sql)
   fun write(deleted:Boolean){if(kind=="asset")apply.upsert("note_assets",listOf("assetId"),JSONObject().put("assetId","private-deleted").put("storageKey",id).put("mimeType","image/png").put("displayName",id).put("createdAt",if(deleted)2 else 1).put("deleted",deleted).put("deletedAt",if(deleted)2 else 0))
    else apply.upsert("imported_books",listOf("bookId"),JSONObject().put("bookId",id).put("stateJson",JSONObject(value.toString()).put("deleted",deleted).put("deletedAt",if(deleted)2 else 0).put("updatedAt",if(deleted)2 else 1).toString()))
   }
   val client=f.f.client();val cloud=SyncV2CloudSync(client,f.identity,f.projection,{true},{f.key},{f.f.now});val entity=f.identity.entity(kind,id)
   if(description!="missing") {
    write(false);f.drain();cloud.publishIndex(f.f.signer,f.key,1);f.actions().refresh()
    if(description!="promise") {
     // Authenticate a new account tree with unchanged business state. The body descriptor
     // can change independently, and its bytes can still be unavailable on this phone.
     val old=f.current.getString("commitOid");val header=f.headers.getValue(old)
     val rows=SyncV2Index.read(f.identity.scope,header.getJSONObject("body").getString("treeRoot"),f.f.objects::getValue)
     val row=rows.single{it.getString("entityKey")==entity}
     for(field in SyncV2BodyIdentity.fields)row.put(field,JSONObject.NULL)
     if(description=="recipe")row.put("bodyRevision","17".repeat(16)).put("recipeOid","recipe:sha256:"+"28".repeat(32)).put("opaqueContentId","39".repeat(32)).put("contentBytes",42)
     row.put("rowSequence",SyncV2Codec.next(row.getString("rowSequence")))
     val tree=SyncV2Index.build(f.identity.scope,rows);f.f.objects.putAll(tree.objects)
     val body=JSONObject(header.getJSONObject("body").toString()).put("treeRoot",tree.root).put("previousOwnCommit",old).put("accountParent",old)
      .put("deviceSequence",SyncV2Codec.next(header.getJSONObject("body").getString("deviceSequence")))
     val oid=SyncV2Codec.oid("commit",body);val signed=f.f.signer.commit(body);f.headers[oid]=signed;f.f.objects[oid]=SyncV2Codec.utf8(SyncV2Codec.canonical(signed))
     f.deviceOid=oid;f.deviceRevision=SyncV2Codec.next(f.deviceRevision);f.setCurrent(oid,SyncV2Codec.next(f.current.getString("revision")));f.actions().refresh()
    }
   }
   val expected=if(description=="missing")null else JSONObject(f.projection.cloudRows(setOf(entity)).single().getJSONObject("row").toString())
   write(true);f.drain()
   val repo=SyncV2BodyRepository(f.sql,f.root);val original="retained local original".toByteArray()
   if(present){repo.file(kind,id).parentFile!!.mkdirs();repo.file(kind,id).writeBytes(original)}else assertNull(repo.read(kind,id))
   val c=RuntimeEnvironment.getApplication() as Context;val manager=CloudKeyManager(null,preferencesOverride=c.getSharedPreferences("tombstone-${System.nanoTime()}",0));manager.bindBackend("http://synthetic");manager.saveAccountKey("synthetic",1,f.key,true)
   var code="PASS"
   SyncV2ContentKeys.authenticated(manager,"synthetic"){}.use{ring->val channel=SyncV2BodyChannel(f.projection,f.identity,ring,f.f.signer,client,repo,File(f.root,"cache"),SyncV2Lifetime{true},{true},{SyncV2TransportSignals(wifi=true,internet=true)})
    if(choice){f.projection.requestConcurrentChoice();val a=f.actions();code=runCatching{a.choose(a.preview(),"local")}.exceptionOrNull()?.message?:"PASS"}
    else code=runCatching{f.projection.prepareWireIndex(f.f.signer,f.key,1).close();channel.bindPrepared();cloud.publishIndex(f.f.signer,f.key,1)}.exceptionOrNull()?.message?:"PASS"
   }
   assertEquals("$kind/$description/$choice/$present","PASS",code)
   f.actions().refresh();val row=f.projection.cloudRows(setOf(entity)).single()
   assertTrue(row.getJSONObject("row").getBoolean("deleted"))
   for(field in SyncV2BodyIdentity.fields)assertEquals("$kind/$description/$choice/$present/$field",expected?.get(field)?:JSONObject.NULL,row.getJSONObject("row").get(field))
   assertTrue(SyncV2Business.join(kind,row.getJSONObject("meta"),row.getJSONObject("state")).getBoolean("deleted"))
   if(present)assertArrayEquals(original,repo.file(kind,id).readBytes())else assertNull(repo.read(kind,id))
  }
 }
}
