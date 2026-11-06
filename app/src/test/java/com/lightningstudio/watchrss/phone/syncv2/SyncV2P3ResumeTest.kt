package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SyncV2P3ResumeTest {
    @get:Rule val temp=TemporaryFolder()
    private val identity=SyncV2Fixtures.identity()
    private fun store(dir:File)=SyncV2ContentStore(dir,identity.scopeJson,SyncV2Fixtures.localKey)
    private fun worker(f:SyncV2P3Fixture,s:SyncV2ContentStore)=SyncV2ContentWorker(f.client(),identity,s,
        {SyncV2TransportSignals(wifi=true,internet=true,cellular=false)},enabled=true,now={f.now},reserveRequestId={"55".repeat(16)})
    private fun seed(f:SyncV2P3Fixture,s:SyncV2ContentStore):SyncV2Envelope.Sealed =
        SyncV2Envelope.seal(identity,ByteArray(32){7},1,"blob",ByteArray(262144){(it*17).toByte()}).also{s.putObject(it.oid,it.bytes);f.objects[it.oid]=it.bytes}
    @Test fun c11SameReserveRequestReplaysZeroButReopenedWorkerKeepsConfirmedFineFrames() {
        SyncV2P3Fixture().use { f ->
            f.replayReserve=true;val dir=temp.newFolder();val obj=seed(f,store(dir))
            repeat(5){worker(f,store(dir)).upload(listOf(obj.oid),SyncV2ContentWorker.Budget(bytes=65536))}
            assertEquals(listOf(0,65536,131072,196608,262144),f.putOffsets)
            assertEquals(1,f.reserveIds.distinct().size);assertTrue(f.reserveOffsets.all{it==0})
            assertEquals(obj.bytes.size,store(dir).load("upload/${obj.oid}")!!.getInt("offset"))
        }
    }
    @Test fun c11ServerAdvanceDuringReserveUsesFreshSignedPrefixAndPendingFrameAnchor() {
        SyncV2P3Fixture().use { f ->
            f.replayReserve=true;val dir=temp.newFolder();val obj=seed(f,store(dir))
            worker(f,store(dir)).upload(listOf(obj.oid),SyncV2ContentWorker.Budget(bytes=65536))
            val s=store(dir);s.save("upload-start/${obj.oid}",JSONObject().put("offset",65536).put("bytes",65536)
                .put("partSha256",SyncV2Codec.hex(SyncV2Codec.sha(obj.bytes.copyOfRange(65536,131072)))))
            f.afterReserve={f.prefix[obj.oid]=obj.bytes.copyOf(131072);f.afterReserve=null}
            val r=worker(f,store(dir)).upload(listOf(obj.oid),SyncV2ContentWorker.Budget(bytes=65536))
            assertEquals("budget_paused",r.status);assertEquals(listOf(0,131072),f.putOffsets)
            assertEquals(listOf(0,0),f.reserveOffsets);assertEquals(196608,store(dir).load("upload/${obj.oid}")!!.getInt("offset"))
        }
    }
    @Test fun c11AdvancedServerPrefixCannotHideAnInvalidLocalPendingFrameHash() {
        SyncV2P3Fixture().use { f ->
            f.replayReserve=true;val dir=temp.newFolder();val obj=seed(f,store(dir))
            worker(f,store(dir)).upload(listOf(obj.oid),SyncV2ContentWorker.Budget(bytes=65536))
            store(dir).save("upload-start/${obj.oid}",JSONObject().put("offset",65536).put("bytes",65536).put("partSha256","00".repeat(32)))
            f.afterReserve={f.prefix[obj.oid]=obj.bytes.copyOf(131072);f.afterReserve=null}
            try{worker(f,store(dir)).upload(listOf(obj.oid));fail("accepted changed pending bytes")}
            catch(e:IllegalStateException){assertEquals("hash_mismatch",e.message)}
            assertEquals(listOf(0),f.putOffsets);assertEquals(65536,store(dir).load("upload/${obj.oid}")!!.getInt("offset"))
            assertArrayEquals(obj.bytes,store(dir).objectBytes(obj.oid))
        }
    }
    @Test fun c11OneFrameServerRollbackKeepsHighWaterAndCannotBuyRepeatedReplayBudgets() {
        SyncV2P3Fixture().use { f ->
            f.replayReserve=true;val dir=temp.newFolder();val obj=seed(f,store(dir))
            worker(f,store(dir)).upload(listOf(obj.oid),SyncV2ContentWorker.Budget(bytes=131072))
            f.prefix[obj.oid]=obj.bytes.copyOf(65536)
            val first=worker(f,store(dir)).upload(listOf(obj.oid),SyncV2ContentWorker.Budget(bytes=65536))
            assertEquals("budget_paused",first.status)
            val saved=store(dir).load("upload/${obj.oid}")!!
            assertEquals(131072,saved.getInt("offset"));assertEquals(65536,saved.getInt("replayedBytes"))
            val before=f.putOffsets.size;f.prefix[obj.oid]=obj.bytes.copyOf(65536)
            val second=worker(f,store(dir)).upload(listOf(obj.oid),SyncV2ContentWorker.Budget(bytes=65536))
            assertEquals("offset_conflict",second.status);assertEquals(before,f.putOffsets.size)
            assertEquals(131072,store(dir).load("upload/${obj.oid}")!!.getInt("offset"))
        }
    }
    @Test fun c11RollbackBeyondOneConfirmedFrameStopsWithoutOverwritingCheckpointOrBody() {
        SyncV2P3Fixture().use { f ->
            f.replayReserve=true;val dir=temp.newFolder();val obj=seed(f,store(dir))
            worker(f,store(dir)).upload(listOf(obj.oid),SyncV2ContentWorker.Budget(bytes=196608))
            val saved=store(dir).load("upload/${obj.oid}")!!;val before=f.putOffsets.size
            for(offset in listOf(65536,0)) {
                f.prefix[obj.oid]=obj.bytes.copyOf(offset)
                assertEquals("offset_conflict",worker(f,store(dir)).upload(listOf(obj.oid)).status)
                assertEquals(before,f.putOffsets.size)
                assertEquals(SyncV2Codec.canonical(saved),SyncV2Codec.canonical(store(dir).load("upload/${obj.oid}")))
            }
            assertArrayEquals(obj.bytes,store(dir).objectBytes(obj.oid))
        }
    }
    @Test fun c11HistoricalReserveMayClaimCompleteButOnlyCurrentHaveCanChooseTheStart() {
        SyncV2P3Fixture().use { f ->
            val dir=temp.newFolder();val obj=seed(f,store(dir));f.prefix[obj.oid]=ByteArray(0)
            f.route={r->if(r.requestUrl!!.encodedPath.endsWith("/objects/reserve"))f.response(f.dto().put("reservationId","34".repeat(16)).put("expiresAtMillis",f.now+600000).put("reservedBytes",0)
                .put("objects",JSONArray().put(JSONObject().put("oid",obj.oid).put("totalBytes",obj.bytes.size).put("durableOffset",obj.bytes.size))))else null}
            assertEquals("complete",worker(f,store(dir)).upload(listOf(obj.oid)).status)
            assertEquals(listOf(0,65536,131072,196608,262144),f.putOffsets)
        }
    }
    @Test fun c11PresentAfterReserveNeedsNeitherPartReplayNorRedundantComplete() {
        SyncV2P3Fixture().use { f ->
            f.replayReserve=true;val dir=temp.newFolder();val obj=seed(f,store(dir))
            worker(f,store(dir)).upload(listOf(obj.oid),SyncV2ContentWorker.Budget(bytes=65536))
            f.afterReserve={f.prefix[obj.oid]=obj.bytes;f.afterReserve=null}
            assertEquals("complete",worker(f,store(dir)).upload(listOf(obj.oid)).status)
            assertEquals(listOf(0),f.putOffsets);assertEquals(0,f.completed)
            assertEquals(obj.bytes.size,store(dir).load("upload/${obj.oid}")!!.getInt("offset"))
        }
    }
    @Test fun c11CurrentPrefixReceiptMustMatchTheExpectedScopeOidAndTotalBeforeAnyPut() {
        SyncV2P3Fixture().use { f ->
            val dir=temp.newFolder();val s=store(dir);val obj=seed(f,s)
            s.save("upload/${obj.oid}",JSONObject().put("offset",65536).put("total",obj.bytes.size))
            f.route={r->if(r.requestUrl!!.encodedPath.endsWith("/objects/have-want"))f.response(f.dto().put("windowId","12".repeat(16)).put("objects",JSONArray().put(JSONObject()
                .put("oid",obj.oid).put("status","partial").put("totalBytes",obj.bytes.size).put("durableOffset",131072)
                .put("receipt",f.receipt("blob:sha256:"+"ff".repeat(32),obj.bytes.size,131072)))))else null}
            try{worker(f,s).upload(listOf(obj.oid));fail("accepted unrelated prefix receipt")}
            catch(e:IllegalStateException){assertEquals("invalid_schema",e.message)}
            assertTrue(f.putOffsets.isEmpty());assertTrue(f.reserveIds.isEmpty());assertEquals(65536,s.load("upload/${obj.oid}")!!.getInt("offset"))
        }
    }

}
