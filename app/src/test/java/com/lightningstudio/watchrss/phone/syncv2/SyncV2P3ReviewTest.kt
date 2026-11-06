package com.lightningstudio.watchrss.phone.syncv2
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Test
import org.junit.Assert.*
import java.nio.file.Files
class SyncV2P3ReviewTest {
 private val identity=SyncV2Fixtures.identity()
 private val contentKey=SyncV2Codec.random(32)
 private fun store(dir:java.io.File)=SyncV2ContentStore(dir,identity.scopeJson,SyncV2Fixtures.localKey)
 @Test fun badPrefixMustBeDiscardedBeforeRetry() {
  val dir=Files.createTempDirectory("p3ar-phone-prefix-").toFile()
  try{val s=store(dir);val o=SyncV2Envelope.seal(identity,contentKey,1,"blob",ByteArray(1000){1});val wrong=o.bytes.copyOf();wrong[wrong.lastIndex]=(wrong.last().toInt() xor 1).toByte();s.append(o.oid,wrong.size,0,wrong)
   try{s.finish(o.oid,wrong.size);fail("accepted bad OID")}catch(e:IllegalStateException){assertEquals("hash_mismatch",e.message)}
   println("REVIEW_PREFIX offset="+s.prefix(o.oid,wrong.size)+" total="+wrong.size)
   assertEquals("discard bad prefix",0,s.prefix(o.oid,wrong.size))
  }finally{dir.deleteRecursively()}
 }
 @Test fun expiredFullPrefixMustRenewReservation() {
  val dir=Files.createTempDirectory("p3ar-phone-expiry-").toFile()
  try{SyncV2P3Fixture().use { f->val s=store(dir);val o=SyncV2Envelope.seal(identity,contentKey,1,"blob",ByteArray(1000){1});s.putObject(o.oid,o.bytes);f.objects[o.oid]=o.bytes;f.prefix[o.oid]=o.bytes
   f.route={r->if(r.requestUrl!!.encodedPath.endsWith("/objects/have-want")&&f.reserveIds.isEmpty())f.response(JSONObject().put("error",JSONObject().put("code","reservation_expired").put("retryable",true).put("retryAfterMillis",JSONObject.NULL).put("details",JSONObject()))).setResponseCode(410)else null}
   val w=SyncV2ContentWorker(f.client(),identity,s,{SyncV2TransportSignals(wifi=true,internet=true,cellular=false)},enabled=true,now={f.now})
   val first=w.upload(listOf(o.oid));val second=w.upload(listOf(o.oid));println("REVIEW_EXPIRED first="+first.status+" second="+second.status+" reserves="+f.reserveIds.size)
   assertEquals("complete",second.status)
  }}finally{dir.deleteRecursively()}
 }
 @Test fun sessionMustKeepAddedAtWhenCreatingUploadTasks() {
  val dir=Files.createTempDirectory("p3ar-phone-rank-").toFile()
  try{SyncV2P3Fixture().use{f->val s=store(dir);val client=f.client();val w=SyncV2ContentWorker(client,identity,s,{SyncV2TransportSignals(wifi=true,internet=true,cellular=false)},enabled=true,now={f.now});val ex=SyncV2ContentExport(identity,contentKey,1,s);val queue=SyncV2ContentQueue(s);val byOid=mutableMapOf<String,Int>()
   for(i in 1..2){val entity=i.toString(16).padStart(64,'0');val rev="22".repeat(16);val row=JSONObject().put("entityKey",entity).put("bodyRevision",rev).put("rowSequence","1").put("deleted",false);queue.enqueue(SyncV2ContentQueue.Job(row,"note",false,i.toLong()))
    val src=object:SyncV2ContentExport.Source{override val size=1L;override val version="v$i";override fun read(offset:Long,maxBytes:Int)=byteArrayOf(i.toByte())};val bind=SyncV2ContentExport.Binding(entity,rev,ex.authenticationBinding);while(!ex.step(bind,src)){};val d=ex.descriptor(bind)!!;val oid=d.getJSONObject("recipe").getJSONArray("blocks").getJSONObject(0).getString("oid");for(id in listOf(oid,d.getString("recipeOid"))){f.objects[id]=s.objectBytes(id);byOid[id]=i}
   }
   val session=SyncV2ContentSession(identity,s,w,client,f.signer,ex);f.route={r->if(r.requestUrl!!.encodedPath.endsWith("/recipe-refs"))f.response(f.dto().put("accepted",true))else null};session.uploadPage(queue,{null},{_,_,_->},emptyList())
   val actual=f.putOids.filter{it.startsWith("blob:")}.map{byOid[it]};println("REVIEW_RANK="+actual);assertEquals(listOf(2,1),actual)
  }}finally{dir.deleteRecursively()}
 }
 @Test fun fiveRecipeDifferentials() {
  fun base()=JSONObject().put("version",2).put("entityKey","aa".repeat(32)).put("bodyRevision","bb".repeat(16)).put("opaqueContentId","cc".repeat(32)).put("plaintextSha256","dd".repeat(32)).put("totalPlainBytes",0).put("blocks",JSONArray()).put("ranges",JSONArray())
  val cases=listOf(base(),base().put("title","x"),base().put("totalPlainBytes",1),base().put("ranges",JSONArray().put(JSONObject().put("logicalPart","x".repeat(1025)).put("offset",0).put("length",0))),base().put("ranges",JSONArray().put(JSONObject().put("logicalPart","z").put("offset",0).put("length",0)).put(JSONObject().put("logicalPart","a").put("offset",0).put("length",0))))
  val out=cases.map{try{SyncV2ContentCodec.recipe(it);"accept"}catch(e:Exception){e.message}}
  println("REVIEW_DIFF="+out);assertEquals(listOf("accept","invalid_schema","invalid_schema","invalid_schema","invalid_schema"),out)
 }
 @Test fun downloadPageMustHonorApprovedDemandBeforeAnotherBody() {
  val dir=Files.createTempDirectory("p3ar-phone-demand-").toFile()
  try{SyncV2P3Fixture().use { f->val origin=store(java.io.File(dir,"origin"));val rows=mutableListOf<JSONObject>();val mappings=mutableMapOf<String,JSONObject>();val available=mutableMapOf<String,JSONObject>();val blobOwner=mutableMapOf<String,Int>();val seen=mutableListOf<Int>()
   for(i in 1..2){val ex=SyncV2ContentExport(identity,contentKey,1,origin);val b=SyncV2ContentExport.Binding(i.toString(16).padStart(64,'0'),"22".repeat(16),ex.authenticationBinding);val source=object:SyncV2ContentExport.Source{override val size=100000L;override val version="v$i";override fun read(offset:Long,maxBytes:Int)=ByteArray(maxBytes){i.toByte()}};while(!ex.step(b,source)){};val d=ex.descriptor(b)!!;val recipe=d.getJSONObject("recipe");val oid=d.getString("recipeOid");val blob=recipe.getJSONArray("blocks").getJSONObject(0).getString("oid");blobOwner[blob]=i
    for(id in listOf(blob,oid)){f.objects[id]=origin.objectBytes(id);f.prefix[id]=f.objects[id]!!}
    val blobs=JSONArray().put(blob);val refs=SyncV2ContentCodec.refs(identity,f.signer,oid,blobs);val raw=SyncV2Codec.utf8(SyncV2Codec.canonical(refs));val roid=SyncV2Codec.oid("recipe-refs",refs);f.objects[roid]=raw;mappings[oid]=f.dto().put("parentOid",oid).put("refsOid",roid).put("authorDeviceId",f.signer.deviceId).put("keyId",f.signer.keyId).put("totalBytes",raw.size)
    val signed=f.signer.signed("wrss.sync.v2/availability-sign",JSONObject().put("version",2).put("scope",identity.scope).put("entityKey",b.entity).put("bodyRevision",b.revision).put("recipeOid",oid).put("status","cloud-ready").put("blobOidsHash",SyncV2Codec.hash(blobs)).put("storeId","99".repeat(16)).put("receiptRevision","1").put("createdAtMillis",f.now).put("expiresAtMillis",f.now+600000))
    available[oid]=f.dto().put("leaseId",JSONObject.NULL).put("expiresAtMillis",JSONObject.NULL).put("availability",JSONObject().put("scope",identity.scope).put("entityKey",b.entity).put("bodyRevision",b.revision).put("recipeOid",oid).put("status","cloud-ready").put("receipt",signed))
    rows.add(JSONObject().put("entityKey",b.entity).put("bodyRevision",b.revision).put("recipeOid",oid).put("opaqueContentId",recipe.get("opaqueContentId")).put("contentBytes",source.size).put("rowSequence","1").put("deleted",false))
   }
   f.route={r->val path=r.requestUrl!!.encodedPath;when{
    path.contains("/recipe-refs/")->f.response(mappings[path.substringAfterLast('/')]!!)
    path.endsWith("/availability/check")->f.response(available[JSONObject(r.body.readUtf8()).getString("recipeOid")]!!)
    path.endsWith("/demand")->f.response(f.dto().put("serverNowMillis",f.now).put("demands",JSONArray().put(JSONObject().put("demandId","11".repeat(16)).put("entityKey",rows[1].get("entityKey")).put("bodyRevision",rows[1].get("bodyRevision")).put("requesterDeviceId",f.signer.deviceId).put("demandAtMillis",f.now).put("expiresAtMillis",f.now+600000))))
    else->{if(r.method=="GET"&&r.getHeader("Range")!=null)blobOwner[path.substringAfterLast('/')]?.let{if(seen.lastOrNull()!=it)seen.add(it)};null}
   }}
   val local=store(java.io.File(dir,"recv"));val client=f.client();val worker=SyncV2ContentWorker(client,identity,local,{SyncV2TransportSignals(wifi=true,internet=true,cellular=false)},enabled=true,now={f.now},approvedDevice={true});val receiver=SyncV2ContentReceive(identity,f.authors(),local,worker,client,{contentKey},now={f.now});val session=SyncV2ContentSession(identity,local,worker,client,f.signer,SyncV2ContentExport(identity,contentKey,1,local))
   val outcome=session.downloadPage(rows,receiver,{f.now},{e->rows.first{it.getString("entityKey")==e}},{})
   println("REVIEW_DOWNLOAD_ORDER="+seen+" status="+outcome.status);assertEquals(listOf(2,1),seen)
  }}finally{dir.deleteRecursively()}
 }

}
