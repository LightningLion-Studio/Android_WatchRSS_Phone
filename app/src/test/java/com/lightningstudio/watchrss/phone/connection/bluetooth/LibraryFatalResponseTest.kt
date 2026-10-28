package com.lightningstudio.watchrss.phone.connection.bluetooth

import android.Manifest
import android.bluetooth.*
import androidx.room.Room
import com.lightningstudio.watchrss.phone.cloud.CloudSnapshotCodec
import com.lightningstudio.watchrss.phone.connection.ip.*
import com.lightningstudio.watchrss.phone.data.db.*
import com.lightningstudio.watchrss.phone.data.log.BluetoothDebugLog
import com.lightningstudio.watchrss.phone.data.note.*
import com.lightningstudio.watchrss.phone.data.reader.ReaderPresetRepository
import com.lightningstudio.watchrss.phone.data.repo.PhoneCompanionRepository
import kotlinx.coroutines.*
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.*
import java.io.*
import java.util.UUID

/** Production client/manager, length-prefixed frames and two real SQLite stores; no radios. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35],shadows=[LibraryFatalResponseTest.DeviceShadow::class,LibraryFatalResponseTest.SocketShadow::class])
class LibraryFatalResponseTest {
    private val context=RuntimeEnvironment.getApplication()
    private val db=Room.inMemoryDatabaseBuilder(context,PhoneCompanionDatabase::class.java).allowMainThreadQueries().build()
    private val watchDb=Room.inMemoryDatabaseBuilder(context,PhoneCompanionDatabase::class.java).allowMainThreadQueries().build()
    private fun repository(db:PhoneCompanionDatabase,id:String)=PhoneCompanionRepository(db.phoneSavedItemDao(),db.phoneArticleDao(),db.phoneRssSourceDao(),id,
        db.syncChangeLogDao(),db.syncPeerStateDao(),appMetaDao=db.appMetaDao())
    private val repo=repository(db,"phone")
    private val watch=repository(watchDb,"watch")
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    private val log=BluetoothDebugLog(context)
    private val client=PhoneBluetoothSyncClient(context,log)
    private val device=PhoneBluetoothWatchDevice("watch","11:22:33:44:55:66",1,"watch",supportsPersistentSession=true)
    private var cloudCalls=0
    private val manager=PhoneBluetoothSyncManager(context,repo,NoteRepository(db.noteDao(),"phone"),ReaderPresetRepository(context,db,db.readerPresetDao(),"phone",scope),
        PhoneLlmTokenUsageRepository(db.llmTokenUsageDao()),"phone",log,onLibrarySyncCompleted={cloudCalls++},currentPhoneUserId={"owner"})
    private fun ok(phase:String)=JSONObject().put("success",true).put("action","syncLibrary").put("phase",phase).put("version",14).put("deviceId","watch")
    private fun manifest()=LibrarySyncPayload.buildManifestResponse("watch",emptyList()).put("supportsReaderPresets",false)
        .put("supportsNoteManifestV2",false).put("supportsNoteAssetInventory",false)
    private fun binding()=ok("probe").put("account",JSONObject().put("bound",true).put("userIdHash",CloudSnapshotCodec.sha256("owner".toByteArray())))
    private fun fatal()=JSONObject("""{"success":false,"action":"syncLibrary","version":14,"message":"Library changed repeatedly while preparing sync","errorCode":"library_exchange_failed","fatalSession":true}""")
    private fun wire(responses:List<JSONObject>)=ByteArrayOutputStream().also{out->responses.forEach{BluetoothSyncProtocol.writeFrame(out,it)}}.toByteArray()
    private fun frames(out:ByteArrayOutputStream):List<JSONObject> = buildList {
        val input=ByteArrayInputStream(out.toByteArray());while(input.available()>0)add(BluetoothSyncProtocol.readFrame(input))
    }
    private fun session(responses:List<JSONObject>,out:ByteArrayOutputStream,closed:()->Unit={})=PhoneSyncSession(client,device,"phone",
        PhoneSyncSession.Transport(wire(responses).inputStream(),out,"test",false,closed),true,false,false)
    @Before fun noSilentRecovery(){manager.reconnectDelay={fail("explicit rejection must not retry")};manager.reconnectSession={_,_->error("explicit rejection must not reconnect")}}
    @After fun close(){PhoneIpSyncSessionRegistry.closeAll();scope.cancel();db.close();watchDb.close()}

    @Test fun fatal175ByteManifestCannotCompleteOrPublishProofAndNextUserRetryDelivers500Durably()=runBlocking<Unit> {
        assertEquals(175,BluetoothSyncProtocol.wireSize(fatal()))
        val local=(0 until 500).map{i->PhoneArticleEntity("bulk-$i","phone","https://fixture.invalid/$i","entry $i","test","",null,"body $i",null,
            CloudSnapshotCodec.sha256("body $i".toByteArray()),1,10,false,0,0,null,null,true,10,0,false,0,0,false,0)}
        repo.mergeArticlesFromSync(local)
        repo.markLibrarySyncSuccess("watch",0,0,14,true,false)
        val peerBefore=db.syncPeerStateDao().get("watch")
        val beforeMeta=db.appMetaDao().getAll().filter{it.key.startsWith("ble_echo_v2:")}
        val sent=ByteArrayOutputStream();var closed=false
        val failure=runCatching{manager.syncAll(device,reusableSession=session(listOf(binding(),ok("cursor"),fatal()),sent,{closed=true}))}.exceptionOrNull()
        assertTrue("fatal must reach manager caller: $failure",failure is IllegalArgumentException)
        assertTrue(closed);assertEquals(peerBefore,db.syncPeerStateDao().get("watch"));assertEquals(beforeMeta,db.appMetaDao().getAll().filter{it.key.startsWith("ble_echo_v2:")})
        assertEquals(0,cloudCalls);assertTrue(watchDb.phoneArticleDao().getAllForSync().isEmpty())
        val requests=frames(sent);val manifestIndex=requests.indexOfLast{it.optString("phase")=="manifest"}
        assertTrue(manifestIndex>=0)
        assertFalse(requests.drop(manifestIndex+1).any{it.optString("action")=="ack"&&it.optBoolean("success")})
        assertFalse(requests.any{it.optString("action")=="syncSession"&&it.optString("phase")=="complete"})
        assertFalse(log.snapshot().contains("sync.library.complete"))

        // A corrected peer applies the real article request frames before replying success.
        val pending=ArrayDeque<Byte>();val requestFrames=mutableListOf<JSONObject>();val articleFrames=mutableListOf<JSONObject>()
        val output=object:ByteArrayOutputStream(){override fun flush(){
            if(size()==0)return
            val q=BluetoothSyncProtocol.readFrame(toByteArray().inputStream());reset();requestFrames+=q
            val response=when(q.optString("action")) {
                "ack"->return
                "syncLibrary"->when(q.optString("phase")) {
                    "probe"->binding()
                    "cursor"->LibrarySyncPayload.buildCursorResponse("watch",LibrarySyncCursor(0,0,0)).put("supportsRssCanonicalIds",false)
                    "manifest"->manifest().put("bodyRequests",JSONArray().apply{
                        LibrarySyncPayload.buildBodyRequestsForRemoteArticles(emptyList(),LibrarySyncPayload.parseArticleManifest(q)).forEach{r->
                            put(JSONObject().put("articleId",r.articleId).put("chunkIndexes",JSONArray(r.chunkIndexes)).put("metadataOnly",r.metadataOnly))
                        }
                    })
                    "articles"->{
                        articleFrames+=q
                        if(q.optInt("batchIndex",0)+1<q.optInt("batchCount",1))return
                        runBlocking{watch.mergeChunkedArticlesFromSync(LibrarySyncPayload.parseChunkedArticles(LibrarySyncPayload.combineArticlePayloads(articleFrames)))}
                        ok("complete").put("articles",JSONArray())
                    }
                    else->error("unexpected library phase")
                }
                "syncNotes"->NoteSyncPayload.manifest("watch",emptyList()).put("success",true)
                "syncLlmTokenUsage"->JSONObject().put("success",true).put("action","syncLlmTokenUsage").put("records",JSONArray())
                "syncSession"->BluetoothSyncProtocol.buildSessionControlResponse(14,q.getString("phase"))
                else->error("unexpected ${q.optString("action")}")
            }
            wire(listOf(response)).forEach{pending.addLast(it)}
        }}
        val input=object:InputStream(){override fun read()=if(pending.isEmpty())-1 else pending.removeFirst().toInt() and 255}
        val retry=PhoneSyncSession(client,device,"phone",PhoneSyncSession.Transport(input,output,"test",false,{}),true,false,false)
        val result=manager.syncAll(device,reusableSession=retry)
        assertEquals(500,result.libraryStats!!.sent)
        val durable=watchDb.phoneArticleDao().getAllForSync()
        assertEquals(500,durable.size);assertEquals(local.associate{it.articleId to it.contentText},durable.associate{it.articleId to it.contentText})
        assertTrue(db.syncPeerStateDao().get("watch")!!.lastLocalSeqAckedByPeer>0)
        assertTrue(requestFrames.any{it.optString("action")=="syncSession"&&it.optString("phase")=="complete"})
    }

    @Test fun fatalCompleteIncludingLaterBatchCannotApplyArticlesOrAdvanceCursors()=runBlocking<Unit> {
        val original=PhoneArticleEntity("remote","watch","https://fixture.invalid/remote","incoming","test","",null,"real incoming body",null,
            CloudSnapshotCodec.sha256("real incoming body".toByteArray()),1,10,false,0,0,null,null,true,10,0,false,0,0,false,0)
        val metadata=ArticleSyncBody.metadataFor(original)
        val incoming=original.copy(syncBodyHash=metadata.bodyHash,syncBodyByteCount=metadata.bodyByteCount,syncChunkSize=metadata.chunkSize,
            syncChunkHashesJson=JSONArray(metadata.chunkHashes).toString(),syncMetadataHash=metadata.metadataHash)
        val incomingManifest=LibrarySyncPayload.buildManifestResponse("watch",listOf(incoming),changeSequence=LibraryChangeSequence(0,40,true))
        val body=LibrarySyncPayload.buildChunkedResponseFrames("watch",listOf(incoming),emptyList(),useBatches=false).single()
        repo.markLibrarySyncSuccess("watch",0,0,14,true,false)
        val peer=db.syncPeerStateDao().get("watch")
        for(tail in listOf(false,true))for(success in listOf(false,true)) {
            val error=JSONObject(body.toString()).put("success",success).put("fatalSession",true)
            val response=if(tail)listOf(JSONObject(body.toString()).put("batchIndex",0).put("batchCount",2),error.put("batchIndex",1).put("batchCount",2))else listOf(error)
            val out=ByteArrayOutputStream()
            val failure=runCatching{manager.syncAll(device,reusableSession=session(listOf(binding(),ok("cursor"),incomingManifest)+response,out))}.exceptionOrNull()
            assertTrue("$tail/$success: $failure",failure is IllegalArgumentException)
            assertTrue(failure.toString(),failure!!.message.orEmpty().contains("可直接点“同步”重试"))
            assertTrue(db.phoneArticleDao().getAllForSync().isEmpty());assertEquals(peer,db.syncPeerStateDao().get("watch"));assertEquals(0,cloudCalls)
            val sent=frames(out);val articles=sent.indexOfLast{it.optString("phase")=="articles"}
            assertTrue(articles>=0);assertFalse(sent.drop(articles+1).any{it.optString("action")=="ack"&&it.optBoolean("success")})
            assertFalse(sent.any{it.optString("action")=="syncSession"&&it.optString("phase")=="complete"})
        }
    }

    private suspend fun rejectAtEveryFrame(useIp:Boolean=false) {
        val failures=listOf(fatal(),fatal().apply{remove("fatalSession")},fatal().put("success",true))
        for(rejected in failures)for(stage in listOf("cursor","manifest","manifest-tail","complete","complete-tail")) {
            val prefix=when(stage){
                "cursor"->emptyList()
                "manifest"->listOf(ok("cursor"))
                "manifest-tail"->listOf(ok("cursor"),manifest().put("manifestBatchIndex",0).put("manifestBatchCount",2))
                "complete"->listOf(ok("cursor"),manifest())
                else->listOf(ok("cursor"),manifest(),ok("complete").put("articles",JSONArray()).put("batchIndex",0).put("batchCount",2))
            }
            val errorFrame=JSONObject(rejected.toString()).put("deviceId","watch").apply {
                put("phase",if(stage=="cursor")"cursor" else if(stage.startsWith("manifest"))"manifest" else "complete")
                if(stage=="manifest-tail"){put("manifestBatchIndex",1);put("manifestBatchCount",2)}
                if(stage=="complete-tail"){put("batchIndex",1);put("batchCount",2)}
                if(stage.startsWith("complete"))put("articles",JSONArray())
            }
            val out=ByteArrayOutputStream();var built=false;var applied=false
            val ip=if(useIp)PhoneIpSyncSession("watch","fatal",IpTransportKind.WIFI_LAN,"192.0.2.1",{out.write(it)},{}).also{
                PhoneIpSyncSessionRegistry.register(it);it.acceptBinary(wire(prefix+errorFrame))
            }else null
            val s=if(useIp)null else session(prefix+errorFrame,out)
            try {
                val failure=runCatching{
                    if(s!=null)s.exchangeLibrary(LibrarySyncPayload.buildCursorRequest("phone",LibrarySyncCursor(0,0,0)),{_,_->manifest()},{_,_->built=true;listOf(LibrarySyncPayload.buildEmptyArticlesProbeRequest("phone"))},"fatal",{}, {applied=true})
                    else client.exchangeLibrary(cursorRequest=LibrarySyncPayload.buildCursorRequest("phone",LibrarySyncCursor(0,0,0)),manifestRequest=manifest(),
                        buildArticleRequests={_,_->built=true;listOf(LibrarySyncPayload.buildEmptyArticlesProbeRequest("phone"))},deviceAddress="ip:watch",applyResponse={applied=true})
                }.exceptionOrNull()
                assertTrue("$useIp/$stage/$rejected: $failure",failure is IllegalArgumentException)
                assertFalse(applied);if(stage.startsWith("manifest")||stage=="cursor")assertFalse(built)
                if(s!=null)assertFalse(s.isPersistent)else assertTrue(ip!!.isClosed)
                assertFalse(frames(out).any{it.optString("action")=="ack"&&it.optBoolean("success")})
            }finally{s?.close();ip?.close()}
        }
    }
    @Test fun persistentTransportRejectsFalseOrFatalOnCursorAndEveryManifestOrBodyFrame()=runBlocking<Unit>{rejectAtEveryFrame()}
    @Test fun legacyIpTransportRejectsFalseOrFatalOnCursorAndEveryManifestOrBodyFrame()=runBlocking<Unit>{rejectAtEveryFrame(true)}

    @Test fun legacyRfcommManifestFailureCannotAckOrInvokeApplication()=runBlocking<Unit> {
        Shadows.shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_SCAN)
        val adapter=context.getSystemService(BluetoothManager::class.java).adapter
        Shadows.shadowOf(adapter).setEnabled(true)
        val bonded=adapter.getRemoteDevice(device.address)
        Shadows.shadowOf(bonded).setName("watch")
        Shadows.shadowOf(adapter).setBondedDevices(setOf(bonded))
        socketInput=wire(listOf(ok("cursor"),fatal())).inputStream();socketOutput=ByteArrayOutputStream();var applied=false
        val failure=runCatching{client.exchangeLibrary(cursorRequest=LibrarySyncPayload.buildCursorRequest("phone",LibrarySyncCursor(0,0,0)),manifestRequest=manifest(),
            buildArticleRequests={_,_->error("must not build after rejection")},deviceAddress=device.address,applyResponse={applied=true})}.exceptionOrNull()
        assertTrue("$failure",failure is IllegalArgumentException);assertFalse(applied)
        assertFalse(frames(socketOutput).any{it.optString("action")=="ack"&&it.optBoolean("success")})
    }
    @Implements(BluetoothDevice::class) class DeviceShadow:ShadowBluetoothDevice(){
        @Implementation override fun createRfcommSocketToServiceRecord(uuid:UUID):BluetoothSocket=Shadow.newInstanceOf(BluetoothSocket::class.java)
    }
    @Implements(BluetoothSocket::class) class SocketShadow:ShadowBluetoothSocket(){
        @Implementation override fun getInputStream():InputStream=socketInput
        @Implementation override fun getOutputStream():OutputStream=socketOutput
    }
    companion object {private var socketInput:InputStream=ByteArrayInputStream(byteArrayOf());private var socketOutput=ByteArrayOutputStream()}
}
