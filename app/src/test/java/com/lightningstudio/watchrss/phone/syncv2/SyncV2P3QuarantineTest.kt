package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SyncV2P3QuarantineTest {
    @get:Rule val temp=TemporaryFolder()
    private val identity=SyncV2Fixtures.identity();private val key=ByteArray(32){7}
    private fun store(dir:File,fault:(String)->Unit={})=SyncV2ContentStore(dir,identity.scopeJson,SyncV2Fixtures.localKey,fault=fault)
    private fun worker(f:SyncV2P3Fixture,s:SyncV2ContentStore)=SyncV2ContentWorker(f.client(),identity,s,
        {SyncV2TransportSignals(wifi=true,internet=true,cellular=false)},enabled=true,now={f.now})
    private fun references(s:SyncV2ContentStore,oid:String) {
        s.save("recipe/entity/revision",JSONObject().put("recipeOid",oid).put("ready",true))
        s.save("receive/$oid",JSONObject().put("index",1).put("complete",true))
        s.save("receipt/$oid",JSONObject().put("oid",oid).put("durableOffset",1))
    }
    private fun snapshot(dir:File)=dir.listFiles()!!.associate{it.name to it.readBytes()}
    private fun unchanged(dir:File,before:Map<String,ByteArray>) {
        for((name,bytes)in before){val f=File(dir,name);assertTrue("retained path $name",f.isFile);assertArrayEquals("retained bytes $name",bytes,f.readBytes())}
    }
    @Test fun n01SameOidCiphertextPlaintextAndReferencesSurviveBadPartAndInterruptedQuarantine() {
        for(crash in listOf(false,true))SyncV2P3Fixture().use { f ->
            val dir=temp.newFolder();var armed=crash
            val s=store(dir){if(it=="quarantine" && armed){armed=false;throw java.io.IOException("quarantine crash")}}
            val plain=ByteArray(1000){1};val obj=SyncV2Envelope.seal(identity,key,1,"blob",plain)
            s.putObject(obj.oid,obj.bytes);assertArrayEquals(plain,worker(f,s).authenticate(obj.oid){key});references(s,obj.oid)
            val before=snapshot(dir);val wrong=obj.bytes.copyOf();wrong[wrong.lastIndex]=(wrong.last().toInt() xor 1).toByte()
            s.append(obj.oid,wrong.size,0,wrong)
            try{s.finish(obj.oid,wrong.size);fail("accepted bad part")}
            catch(e:IllegalStateException){assertFalse(crash);assertEquals("hash_mismatch",e.message)}
            catch(_:java.io.IOException){assertTrue(crash);assertTrue(s.load("quarantine/${obj.oid}")!!.getBoolean("pending"))}
            val reopened=store(dir);reopened.recoverDownload(obj.oid);reopened.recoverDownload(obj.oid)
            assertEquals(0,reopened.prefix(obj.oid,obj.bytes.size));assertNull(reopened.load("download/${obj.oid}"))
            assertTrue(reopened.checked(obj.oid));assertArrayEquals(obj.bytes,reopened.objectBytes(obj.oid));assertArrayEquals(plain,worker(f,reopened).authenticate(obj.oid){key})
            assertEquals(0L,reopened.counts.authentications);assertEquals(0,f.requests);unchanged(dir,before)
            assertFalse(dir.listFiles()!!.any{it.name.endsWith(".object.bad") || it.name.endsWith(".checked.bad")})
        }
    }
    @Test fun n01LegacyUnscopedPendingIntentDefaultsToPartOnlyAndKeepsAuthenticatedReferences() {
        SyncV2P3Fixture().use { f ->
            val dir=temp.newFolder();val s=store(dir);val plain=byteArrayOf(2);val obj=SyncV2Envelope.seal(identity,key,1,"blob",plain)
            s.putObject(obj.oid,obj.bytes);worker(f,s).authenticate(obj.oid){key};references(s,obj.oid);val before=snapshot(dir)
            val wrong=obj.bytes.copyOf();wrong[wrong.lastIndex]=(wrong.last().toInt() xor 1).toByte();s.append(obj.oid,wrong.size,0,wrong)
            s.save("quarantine/${obj.oid}",JSONObject().put("pending",true).put("reason","hash_mismatch"))
            val reopened=store(dir);assertEquals(0,reopened.prefix(obj.oid,obj.bytes.size))
            assertTrue(reopened.checked(obj.oid));assertArrayEquals(plain,reopened.plain(obj.oid));unchanged(dir,before)
        }
    }
    @Test fun n01KnownBadCheckedFileDoesNotQuarantineTheImmutableObjectOrItsDownloadReference() {
        SyncV2P3Fixture().use { f ->
            val dir=temp.newFolder();val s=store(dir);val plain=byteArrayOf(3);val obj=SyncV2Envelope.seal(identity,key,1,"blob",plain)
            s.putObject(obj.oid,obj.bytes);worker(f,s).authenticate(obj.oid){key};references(s,obj.oid)
            s.save("download/${obj.oid}",JSONObject().put("total",obj.bytes.size))
            val ciphertext=s.objectBytes(obj.oid);val reference=s.load("recipe/entity/revision")!!
            val bad=dir.listFiles()!!.single{it.name.endsWith(".checked")};val bytes=bad.readBytes();bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte();bad.writeBytes(bytes)
            try{worker(f,s).authenticate(obj.oid){key};fail("accepted bad checked file")}
            catch(e:IllegalStateException){assertEquals("aead_invalid",e.message)}
            val reopened=store(dir);assertArrayEquals(ciphertext,reopened.objectBytes(obj.oid));assertNotNull(reopened.load("download/${obj.oid}"))
            assertEquals(SyncV2Codec.canonical(reference),SyncV2Codec.canonical(reopened.load("recipe/entity/revision")))
            assertArrayEquals(plain,worker(f,reopened).authenticate(obj.oid){key});assertEquals(0,f.requests)
        }
    }
}
