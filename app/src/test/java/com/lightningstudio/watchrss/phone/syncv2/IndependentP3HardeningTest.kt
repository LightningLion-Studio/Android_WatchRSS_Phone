package com.lightningstudio.watchrss.phone.syncv2
import org.junit.Test
import org.junit.Assert.*
import java.nio.file.Files
import okhttp3.mockwebserver.MockResponse
import okio.Buffer
class IndependentP3HardeningTest {
 @Test fun badRedownloadMustPreserveSameOidAlreadyAuthenticatedPlaintext() {
  val dir=Files.createTempDirectory("p3ar-r1-preserve-").toFile()
  try{SyncV2P3Fixture().use{f->val identity=SyncV2Fixtures.identity();val key=SyncV2Codec.random(32);val plain=SyncV2Codec.random(1000);val obj=SyncV2Envelope.seal(identity,key,1,"blob",plain);val store=SyncV2ContentStore(dir,identity.scopeJson,SyncV2Fixtures.localKey);store.putObject(obj.oid,obj.bytes)
   val worker=SyncV2ContentWorker(f.client(),identity,store,{SyncV2TransportSignals(wifi=true,internet=true,cellular=false)},enabled=true,now={f.now});assertArrayEquals(plain,worker.authenticate(obj.oid){key});assertTrue(store.checked(obj.oid));assertNull(store.load("download/${obj.oid}"));f.objects[obj.oid]=obj.bytes
   f.route={r->if(r.method=="GET"&&r.getHeader("Range")!=null){val wrong=obj.bytes.copyOf();wrong[wrong.lastIndex]=(wrong.last().toInt() xor 1).toByte();MockResponse().setResponseCode(206).setHeader("ETag",obj.oid).setHeader("Content-Range","bytes 0-${wrong.lastIndex}/${wrong.size}").setBody(Buffer().write(wrong))}else null}
   try{worker.download(listOf(obj.oid to obj.bytes.size));fail("accepted corrupt prefix")}catch(e:IllegalStateException){assertEquals("hash_mismatch",e.message)}
   val reopened=SyncV2ContentStore(dir,identity.scopeJson,SyncV2Fixtures.localKey);println("HARDEN_SAME_OID confirmedPlainRetained="+reopened.checked(obj.oid)+" newPrefix="+reopened.prefix(obj.oid,obj.bytes.size));assertTrue("previously authenticated plaintext must remain readable",reopened.checked(obj.oid));assertArrayEquals(plain,reopened.plain(obj.oid))
  }}finally{dir.deleteRecursively()}
 }
}
