package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

class SyncV2P4PerfSafetyTest {
    private fun frame(f:SyncV2P4Fixture,s:SyncV2LocalSession,oid:String,raw:ByteArray,offset:Int):SyncV2Wrp2.Frame {
        val out=ByteArrayOutputStream();SyncV2Wrp2.write(out,s,oid,raw.size,offset,"44".repeat(16),raw.copyOfRange(offset,minOf(raw.size,offset+65536)))
        return SyncV2Wrp2.read(ByteArrayInputStream(out.toByteArray()),s)
    }
    private fun record(f:SyncV2P4Fixture,name:String,oid:String,extension:String)=File(File(f.root,name),SyncV2Codec.hex(SyncV2Codec.sha(SyncV2Codec.utf8("${f.identity.scopeJson}/$oid")))+extension)
    @Test fun v1DurableRecordsResumeWithV2RecordsWithoutReencryptingObjects() {SyncV2P4Fixture().use{f->
        val (s,_)=f.pair();val store=f.store("v1");val (oid,raw)=f.blob();val part=raw.copyOf(65536)
        val receipt=JSONObject().put("scope",f.identity.scope).put("sessionId",f.sid).put("holderDeviceId",f.a).put("peerDeviceId",f.b)
            .put("oid",oid).put("totalBytes",raw.size).put("durableOffset",part.size)
        val header=SyncV2LocalProtocol.bytes(JSONObject().put("total",raw.size).put("offset",0).put("hash",SyncV2Codec.hex(SyncV2Codec.sha(part))).put("receipt",receipt))
        val original=ByteBuffer.allocate(4).putInt(header.size).array()+header+part;val nonce=SyncV2Codec.random(12)
        record(f,"v1",oid,".local-0").writeBytes(nonce+SyncV2Codec.encrypt(SyncV2Fixtures.localKey,nonce,SyncV2Codec.utf8("${f.identity.scopeJson}/local-part/$oid/0"),original))
        assertEquals(65536,store.prefix(oid,raw.size));val port=SyncV2LocalObjects(s,store)
        for(offset in 65536 until raw.size step 65536)port.putPart(frame(f,s,oid,raw,offset))
        assertArrayEquals(raw,f.store("v1").objectBytes(oid));assertTrue(record(f,"v1",oid,".local-0").exists());assertTrue(record(f,"v1",oid,".local2-65536").exists())
        assertEquals(0L,store.counts.seals)
    }}
    @Test fun payloadCorruptionStillFailsFinalHashDespiteCachedDurableOffset() {SyncV2P4Fixture().use{f->
        val (s,_)=f.pair();val store=f.store("payload-corrupt");val port=SyncV2LocalObjects(s,store);val (oid,raw)=f.blob()
        port.putPart(frame(f,s,oid,raw,0));assertEquals(65536,store.prefix(oid,raw.size))
        val file=record(f,"payload-corrupt",oid,".local2-0");val changed=file.readBytes();changed[changed.lastIndex]=(changed.last().toInt() xor 1).toByte();file.writeBytes(changed)
        p4Error("hash_mismatch"){for(offset in 65536 until raw.size step 65536)port.putPart(frame(f,s,oid,raw,offset))}
        p4Error("object_missing"){store.objectSize(oid)};assertEquals(0,store.prefix(oid,raw.size));assertNull(store.localTotal(oid))
    }}
    @Test fun encryptedMetadataCorruptionIsRejectedOnReopen() {SyncV2P4Fixture().use{f->
        val (s,_)=f.pair();val store=f.store("metadata-corrupt");val port=SyncV2LocalObjects(s,store);val (oid,raw)=f.blob()
        port.putPart(frame(f,s,oid,raw,0))
        val file=record(f,"metadata-corrupt",oid,".local2-0");val changed=file.readBytes();changed[20]=(changed[20].toInt() xor 1).toByte();file.writeBytes(changed)
        p4Error("aead_invalid"){f.store("metadata-corrupt").prefix(oid,raw.size)}
    }}
    @Test fun failedGapCannotCreateAZeroLengthCachedReservation() {SyncV2P4Fixture().use{f->
        val (s,_)=f.pair();val store=f.store("gap");val port=SyncV2LocalObjects(s,store);val (oid,raw)=f.blob()
        p4Error("offset_conflict"){port.putPart(frame(f,s,oid,raw,65536))}
        assertNull(store.localTotal(oid));assertEquals(0,store.prefix(oid,raw.size))
        val window=port.openWindow().getString("windowId")
        val row=port.haveWant(JSONObject().put("scope",f.identity.scope).put("windowId",window).put("oids",org.json.JSONArray(listOf(oid)))).getJSONArray("objects").getJSONObject(0)
        assertEquals("missing",row.getString("status"))
    }}
    @Test fun hexLookupPreservesAllByteValuesAndLeadingZeros() {
        val raw=ByteArray(256){it.toByte()};val expected=(0..255).joinToString(""){"%02x".format(it)}
        assertEquals(expected,SyncV2Codec.hex(raw));assertArrayEquals(raw,SyncV2Codec.unhex(expected,256));assertEquals("",SyncV2Codec.hex(byteArrayOf()))
    }
}
