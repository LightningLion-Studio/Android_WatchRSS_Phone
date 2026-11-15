package com.lightningstudio.watchrss.phone.connection.bluetooth

import androidx.room.Room
import com.lightningstudio.watchrss.phone.account.withWatchAccountDeviceRefresh
import com.lightningstudio.watchrss.phone.account.PhoneAccountHttpException
import com.lightningstudio.watchrss.phone.connection.ip.PhoneIpSyncSession
import com.lightningstudio.watchrss.phone.connection.ip.IpTransportKind
import com.lightningstudio.watchrss.phone.data.db.*
import com.lightningstudio.watchrss.phone.data.log.BluetoothDebugLog
import com.lightningstudio.watchrss.phone.data.note.NoteRepository
import com.lightningstudio.watchrss.phone.data.reader.ReaderPresetRepository
import com.lightningstudio.watchrss.phone.data.repo.PhoneCompanionRepository
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.*

/** Real manager/client/framing/Room. Only the transport and credential issuer are replaced. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class AccountSyncGateSessionTest {
    private val context=RuntimeEnvironment.getApplication()
    private val db=Room.inMemoryDatabaseBuilder(context,PhoneCompanionDatabase::class.java).allowMainThreadQueries().build()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    private val repo=PhoneCompanionRepository(db.phoneSavedItemDao(),db.phoneArticleDao(),db.phoneRssSourceDao(),"phone",db.syncChangeLogDao(),db.syncPeerStateDao(),appMetaDao=db.appMetaDao())
    private val log=BluetoothDebugLog(context)
    private val device=PhoneBluetoothWatchDevice("watch","11:22:33:44:55:66",1,"watch",supportsPersistentSession=true)
    private val frames=mutableListOf<JSONObject>()
    private var requiresDeviceRefresh=false
    private var deviceRefreshSucceeds=true
    private var refreshes=0
    private var login=true
    private var simulatedUserId:String?=null
    private var afterCursor:(()->Unit)?=null
    private var rejectReader=false
    private var closeOnRejection=false
    private var failClear=false
    private var unbound=false
    private var failFirstCursor=false
    private var dropCursorCount=0
    private var closeIpAtCursor=false
    private var protocolCursor=false
    private var cancelAtCursor:Job?=null
    private var dropCompleteCount=0
    private var enableReader=false
    private var dropReaderCount=0
    private var supportsCheckpoints=false
    private var badBatchAck=false
    private var loseAssetAck=false
    private var remoteAsset=ByteArrayOutputStream()
    private val notes=NoteRepository(db.noteDao(),"phone")
    private var rejectLibraryPhase:String?=null
    private var rejectCount=0
    private var cursorCount=0
    private var accountCount=0
    private var reconnects=0
    private var transport="rfcomm"
    private var requireBackgroundIo=false
    private var rejectionCode:String?=null
    private var issueFailure:Throwable?=null
    private val manager=PhoneBluetoothSyncManager(context,repo,notes,ReaderPresetRepository(context,db,db.readerPresetDao(),"phone",scope),PhoneLlmTokenUsageRepository(db.llmTokenUsageDao()),"phone",log,
        canSyncAccount={login},currentPhoneUserId={simulatedUserId},buildAccountSyncRequest={_,_,_->
            issueFailure?.let { throw it }
            withWatchAccountDeviceRefresh({"old-device-token"},{refreshes++;if(deviceRefreshSucceeds) com.lightningstudio.watchrss.phone.account.WatchAccountRefreshResult.Ready else com.lightningstudio.watchrss.phone.account.WatchAccountRefreshResult.DeviceVerificationRequired()}) {
                if(requiresDeviceRefresh && refreshes==0)throw PhoneAccountHttpException(403,"""{"error":"device_session_expired"}""")
                JSONObject().put("action","syncAccount")
            }
        })
    @After fun close()=runBlocking {scope.coroutineContext[Job]!!.cancelAndJoin();db.close()}
    private fun answer(action:String,phase:String="")=JSONObject().put("success",true).put("action",action).put("phase",phase).put("version",14).put("deviceId","watch")
    private fun session(pending:Boolean=false):PhoneSyncSession {
        val incoming=ArrayDeque<Byte>()
        var ip:PhoneIpSyncSession?=null
        var rejectedConnection=false
        val output=object:ByteArrayOutputStream(){override fun flush(){
            if(size()==0)return
            if(requireBackgroundIo)assertNotSame("IP negotiation, recovery and completion must leave the main thread",android.os.Looper.getMainLooper().thread,Thread.currentThread())
            if(rejectedConnection)throw EOFException("Compose closed rejected connection")
            val q=BluetoothSyncProtocol.readFrame(ByteArrayInputStream(toByteArray()));reset();frames+=q
            val action=q.optString("action");val phase=q.optString("phase")
            if(action=="ack")return
            val a=if(action=="syncLibrary" && phase==rejectLibraryPhase) answer(action,phase).put("success",false).put("message","请重新连接并同步账号后再同步资料") else when(action){
                "syncAccount"->{accountCount++;answer(action).put("boundUserId",simulatedUserId?:"owner")}
                "syncLibrary"->when(phase){
                    "probe"->answer(action,phase).put("supportsPersistentSession",true).put("persistentSessionAccepted",true).apply { if(unbound)put("account",JSONObject().put("bound",false)) }
                    "cursor"->{if(closeIpAtCursor){closeIpAtCursor=false;ip!!.close();throw IllegalStateException("IP 同步连接已关闭")};if(protocolCursor)throw java.net.ProtocolException("bad frame");cancelAtCursor?.let{it.cancel();throw IOException("cancelled socket")};if(dropCursorCount>0){dropCursorCount--;throw IOException("reset by peer")};if(failFirstCursor){failFirstCursor=false;throw IOException("synthetic disconnect")};cursorCount++;if(cursorCount<=rejectCount)answer(action,phase).put("success",false)
                        .put("message","请重新连接并同步账号后再同步资料").apply {rejectionCode?.let {put("errorCode",it)}}
                        else LibrarySyncPayload.buildCursorResponse("watch",LibrarySyncCursor(0,0,0)).put("supportsAuthoritativeLibraryReplace",true).apply{if(supportsCheckpoints && q.optBoolean(LibrarySyncPayload.ARTICLE_CHECKPOINT_FEATURE))put(LibrarySyncPayload.ARTICLE_CHECKPOINT_FEATURE,true)}}
                    "manifest"->LibrarySyncPayload.buildManifestResponse("watch",emptyList()).put("supportsReaderPresets",rejectReader||enableReader)
                        .put("supportsNoteManifestV2",false).put("supportsNoteAssetInventory",false)
                    "articles"->LibrarySyncPayload.buildChunkedResponseFrames("watch",emptyList(),emptyList(),useBatches=false).single()
                    else->error("unexpected library phase $phase")
                }
                "syncReader"->if(dropReaderCount>0){dropReaderCount--;throw IOException("reader socket closed")}else if(rejectReader)answer(action,phase).put("success",false).put("message","请重新连接并同步账号后再同步资料")
                    else JSONObject(q.toString()).put("success",true).put("deviceId","watch").put("missingResources",org.json.JSONArray())
                "clearLibrary"->if(failClear)throw IOException("clear transport disconnected") else answer(action)
                "remoteInput"->answer(action)
                "pullSavedItems"->answer(action).put("items",org.json.JSONArray())
                "previewReader"->JSONObject(q.toString()).put("success",true)
                "syncNotes"->NoteSyncPayload.manifest("watch",emptyList()).put("success",true)
                "syncLlmTokenUsage"->answer(action).put("records",org.json.JSONArray())
                "syncNoteAsset"->{val n=q.getInt("chunkIndex");if(n==0)remoteAsset.reset()else require(remoteAsset.size()==n*NoteAssetSyncPayload.CHUNK_BYTES);remoteAsset.write(java.util.Base64.getDecoder().decode(q.getString("data")));if(loseAssetAck && n==1){loseAssetAck=false;throw IOException("lost asset ACK")};answer(action).put("complete",n==q.getInt("chunkCount")-1)}
                "syncSession"->if(dropCompleteCount>0){dropCompleteCount--;throw IOException("lost complete ACK")}else answer(action,phase)
                else->error("unexpected $action/$phase")
            }
            if(action=="syncLibrary" && phase=="articles" && q.optBoolean("libraryCheckpoint")) {
                val receipt=LibrarySyncPayload.articleBatchAck(if(badBatchAck)"watchToPhone"else"phoneToWatch",q.optInt("batchIndex"),0)
                val bytes=ByteArrayOutputStream().also{BluetoothSyncProtocol.writeFrame(it,receipt)}.toByteArray()
                if(ip!=null)ip!!.acceptBinary(bytes)else bytes.forEach{incoming.addLast(it)}
                a.put("libraryCheckpoint",true)
            }
            if(action=="syncLibrary" && phase=="cursor")afterCursor?.invoke()
            val encoded=ByteArrayOutputStream().also{BluetoothSyncProtocol.writeFrame(it,a)}.toByteArray()
            val activeIp=ip
            if(activeIp!=null)activeIp.acceptBinary(encoded) else encoded.forEach{incoming.addLast(it)}
            if(closeOnRejection && !a.optBoolean("success",true))rejectedConnection=true
        }}
        val input=object:InputStream(){override fun read()=if(incoming.isEmpty())-1 else incoming.removeFirst().toInt() and 255}
        val streams=if(transport.startsWith("ip:")) {
            // Exercise the production IP pipe and byte-stream framing, with no network socket.
            val pending=ByteArrayOutputStream()
            val activeIp=PhoneIpSyncSession("watch","account-gate",IpTransportKind.WIFI_LAN,"192.0.2.1",{ bytes ->
                pending.write(bytes)
                while(pending.size()>=4) {
                    val wire=pending.toByteArray()
                    val length=DataInputStream(wire.inputStream()).readInt()
                    if(wire.size<4+length)break
                    pending.reset();pending.write(wire,4+length,wire.size-4-length)
                    output.write(wire,0,4+length);output.flush()
                }
            },{})
            ip=activeIp
            PhoneSyncSession.Transport(activeIp.inputStream,activeIp.outputStream,transport,false,
                remotelyClosed={activeIp.isClosed},closeTransport=activeIp::close)
        } else PhoneSyncSession.Transport(input,output,transport,false,closeTransport={})
        return PhoneSyncSession(PhoneBluetoothSyncClient(context,log),device,"phone",streams,!pending,pending,false)
    }
    private suspend fun runBoth(block:suspend ()->Unit) {
        for(route in listOf("rfcomm","ip:test")) {
            transport=route;frames.clear();accountCount=0;cursorCount=0;reconnects=0;refreshes=0
            manager.probeDevices={_,_->PhoneBluetoothWatchProbeBatch(listOf(PhoneBluetoothWatchProbeResult(device,true)),session())}
            manager.reconnectSession={_,_->reconnects++;session()}
            manager.recoveryProbeDevices={_,_->reconnects++;PhoneBluetoothWatchProbeBatch(listOf(PhoneBluetoothWatchProbeResult(device,true)),session())}
            block()
        }
    }
    @Test fun validLoginConfirmsAccountBeforeAnyDataOnBothRoutes()=runBlocking<Unit>{runBoth {
        manager.syncAll(device,reusableSession=session())
        val requests=frames.filter{it.optString("action")!="ack"}
        assertEquals(listOf("syncAccount","syncLibrary"),requests.take(2).map{it.optString("action")})
        assertEquals(1,accountCount);assertEquals(1,cursorCount)
    }}
    @Test fun promotedIpNegotiationRecoveryAndCompletionUseBackgroundIo()=runBlocking<Unit>{
        transport="ip:test";requireBackgroundIo=true;failFirstCursor=true
        manager.recoveryProbeDevices={_,_->
            assertNotSame(android.os.Looper.getMainLooper().thread,Thread.currentThread())
            reconnects++;PhoneBluetoothWatchProbeBatch(listOf(PhoneBluetoothWatchProbeResult(device,true)),session(pending=true))
        }
        manager.syncAll(device,reusableSession=session(pending=true))
        assertEquals(1,reconnects);assertEquals(2,accountCount)
        assertEquals(2,frames.count{it.optString("phase")=="probe"})
        assertEquals("syncSession",frames.last{it.optString("action")!="ack"}.optString("action"))
    }
    @Test fun actualClosedIpPipeReconfirmsAndRecoversInsteadOfReportingStateError()=runBlocking<Unit>{
        transport="ip:test";closeIpAtCursor=true
        manager.recoveryProbeDevices={_,_->reconnects++;PhoneBluetoothWatchProbeBatch(listOf(PhoneBluetoothWatchProbeResult(device,true)),session())}
        manager.syncAll(device,reusableSession=session())
        assertEquals(1,reconnects);assertEquals(2,accountCount);assertEquals(1,cursorCount)
    }
    @Test fun logoutDuringCursorStopsBeforeNextReadOrManifestWithoutSearch()=runBlocking<Unit>{runBoth {
        login=true;simulatedUserId="owner";afterCursor={login=false;simulatedUserId=null}
        try {
            assertTrue(runCatching{manager.syncAll(device,reusableSession=session())}.exceptionOrNull() is PhoneLoginRequired)
            assertEquals(1,accountCount);assertEquals(1,cursorCount);assertEquals(0,reconnects)
            assertFalse(frames.any{it.optString("phase")=="manifest"})
        } finally {login=true;simulatedUserId=null;afterCursor=null}
    }}
    @Test fun validAccountSwitchStopsIoWithoutInvalidatingNewLoginOrSearching()=runBlocking<Unit>{runBoth {
        login=true;simulatedUserId="first";afterCursor={simulatedUserId="second"}
        try {
            val failure=runCatching{manager.syncAll(device,reusableSession=session())}.exceptionOrNull()
            assertEquals("同步账号已变化，请重新开始同步",failure?.message)
            assertFalse(isTerminalAccountSyncFailure(requireNotNull(failure)))
            assertTrue(login);assertEquals("second",simulatedUserId);assertEquals(0,reconnects)
            assertFalse(frames.any{it.optString("phase")=="manifest"})
        } finally {simulatedUserId=null;afterCursor=null}
    }}
    @Test fun closedIpPipeStateBecomesIoOnlyForLibraryTask() {
        val ip=PhoneIpSyncSession("watch","closed",IpTransportKind.WIFI_LAN,"192.0.2.1",{}, {})
        val wire=PhoneSyncSession.Transport(ip.inputStream,ip.outputStream,"ip:test",false,
            remotelyClosed={ip.isClosed},closeTransport=ip::close)
        ip.close()
        assertThrows(IllegalStateException::class.java){wire.outputStream.write(byteArrayOf(1))}
        wire.recoveryEnabled=true
        val failure=assertThrows(LibraryTransportFailure::class.java){wire.outputStream.write(byteArrayOf(1))}
        assertEquals(wire.generation,failure.generation);assertTrue(wire.isClosed)
    }
    @Test fun cancellingLibraryIpOnMainClosesCapturedSocketOnBackgroundThread() {
        val closed=java.util.concurrent.CountDownLatch(1);var closingThread:Thread?=null
        val wire=PhoneSyncSession.Transport(ByteArrayInputStream(byteArrayOf()),ByteArrayOutputStream(),"ip:test",false,
            closeTransport={closingThread=Thread.currentThread();closed.countDown()})
        assertSame(android.os.Looper.getMainLooper().thread,Thread.currentThread())
        wire.recoveryEnabled=true;wire.close()
        assertTrue(wire.isClosed);assertTrue(closed.await(2,java.util.concurrent.TimeUnit.SECONDS))
        assertNotSame(android.os.Looper.getMainLooper().thread,closingThread)
    }
    @Test fun refreshableDeviceCredentialsRefreshBeforeAccountAndDataOnBothRoutes()=runBlocking<Unit>{requiresDeviceRefresh=true;runBoth {
        manager.syncAll(device,reusableSession=session())
        assertEquals(1,refreshes);assertEquals(1,accountCount);assertEquals(1,cursorCount)
    }}
    @Test fun failedDeviceRefreshRequiresDeviceVerificationWithoutSendingDataOnBothRoutes()=runBlocking<Unit>{requiresDeviceRefresh=true;deviceRefreshSucceeds=false;runBoth {
        val failure=runCatching{manager.syncAll(device,reusableSession=session())}.exceptionOrNull()
        assertTrue(failure is com.lightningstudio.watchrss.phone.account.PhoneDeviceVerificationRequired)
        assertEquals(1,refreshes);assertEquals(0,accountCount);assertEquals(0,cursorCount);assertEquals(0,reconnects)
    }}
    @Test fun expiredOrMissingLoginStopsBeforeAnyRequestOnBothRoutes()=runBlocking<Unit>{login=false;runBoth {
        val borrowed=session()
        assertTrue(runCatching{manager.syncAll(device,reusableSession=borrowed)}.exceptionOrNull() is PhoneLoginRequired)
        assertNull(borrowed.transport)
        assertTrue(frames.isEmpty())
        assertEquals(0,accountCount);assertEquals(0,cursorCount)
    }}
    @Test fun failedAccountDeliveryNeverSendsDataOnBothRoutes()=runBlocking<Unit>{issueFailure=PhoneLoginRequired();runBoth {
        assertTrue(runCatching{manager.syncAll(device,reusableSession=session())}.exceptionOrNull() is PhoneLoginRequired)
        assertEquals(0,cursorCount);assertEquals(0,accountCount)
    }}
    @Test fun accountIssuanceNetworkFailureDoesNotSendDataOrReconnectBluetooth()=runBlocking<Unit>{issueFailure=IOException("credential service unavailable");runBoth {
        val failure=runCatching{manager.syncAll(device,reusableSession=session())}.exceptionOrNull()
        assertTrue(failure.toString(),failure is com.lightningstudio.watchrss.phone.account.PhoneAccountTemporarilyUnavailable)
        assertEquals(0,cursorCount);assertEquals(0,accountCount);assertEquals(0,reconnects)
    }}
    @Test fun composeMessageRejectionStopsImmediatelyInLibraryTaskOnBothRoutes()=runBlocking<Unit>{rejectCount=1;runBoth {
        assertTrue(runCatching{manager.syncAll(device,reusableSession=session())}.exceptionOrNull() is WatchAccountSyncRequired)
        assertEquals(1,accountCount);assertEquals(1,cursorCount);assertEquals(0,reconnects)
    }}
    @Test fun codedRejectionStopsImmediatelyInLibraryTaskOnBothRoutes()=runBlocking<Unit>{rejectCount=1;rejectionCode="account_sync_required";runBoth {
        assertTrue(runCatching{manager.syncAll(device,reusableSession=session())}.exceptionOrNull() is WatchAccountSyncRequired)
        assertEquals(1,accountCount);assertEquals(0,reconnects)
    }}
    @Test fun repeatedRejectionKeepsAccurateReasonAndStopsOnBothRoutes()=runBlocking<Unit>{rejectCount=3;runBoth {
        val failure=runCatching{manager.syncAll(device,reusableSession=session())}.exceptionOrNull()
        assertTrue(failure is WatchAccountSyncRequired);assertEquals("请重新连接并同步账号后再同步资料",failure!!.message)
        assertEquals(1,accountCount);assertEquals(1,cursorCount);assertEquals(0,reconnects)
        assertFalse(frames.any{it.optString("action")=="syncLibrary"&&it.optString("phase")=="manifest"})
    }}

    @Test fun unrelatedPeerFailureDoesNotTriggerAccountRepair() {
        assertTrue(runCatching{requireAccountAccepted(JSONObject().put("success",false).put("errorCode","device_owned_by_another_account"))}.isSuccess)
        assertTrue(runCatching{requireAccountAccepted(JSONObject().put("success",false).put("message","other"))}.isSuccess)
    }
    @Test fun independentReaderStopsBeforeDataWithoutLoginOnBothRoutes()=runBlocking<Unit>{login=false;runBoth {
        assertTrue(runCatching{manager.syncReaderPresets(device.address,syncSession=session())}.exceptionOrNull() is PhoneLoginRequired)
        assertFalse(frames.any{it.optString("action")=="syncReader"})
    }}


    @Test fun independentReaderConfirmsEveryNewConnectionOnBothRoutes()=runBlocking<Unit>{runBoth {
        repeat(2){manager.syncReaderPresets(device.address,syncSession=session())}
        assertEquals(2,accountCount);assertEquals(2,frames.count{it.optString("action")=="syncReader"})
        assertTrue(frames.indexOfFirst{it.optString("action")=="syncAccount"}<frames.indexOfFirst{it.optString("action")=="syncReader"})
    }}
    @Test fun readerRefusalInGlobalSyncStopsImmediatelyOnBothRoutes()=runBlocking<Unit>{rejectReader=true;runBoth {
        val failure=runCatching{manager.syncAll(device,reusableSession=session())}.exceptionOrNull()
        assertTrue(failure.toString(),failure is WatchAccountSyncRequired)
        assertEquals(1,accountCount);assertEquals(0,reconnects)
        assertEquals(1,frames.count{it.optString("action")=="syncReader"})
        assertEquals("completed library stage must not replay",1,cursorCount)
    }}
    @Test fun independentReaderPreservesComposeRefusalAfterPeerClosesOnBothRoutes()=runBlocking<Unit>{rejectReader=true;closeOnRejection=true;runBoth {
        val failure=runCatching{manager.syncReaderPresets(device.address,syncSession=session())}.exceptionOrNull()
        assertTrue(failure.toString(),failure is WatchAccountSyncRequired)
        assertEquals(2,accountCount);assertEquals(1,reconnects);assertEquals(2,frames.count{it.optString("action")=="syncReader"})
    }}
    @Test fun savedItemsUsesSameAccountConnectionGateOnBothRoutes()=runBlocking<Unit>{runBoth {
        manager.syncSavedItems(com.lightningstudio.watchrss.phone.data.model.PhoneSavedItemType.FAVORITE)
        assertEquals(1,accountCount)
        assertTrue(frames.indexOfFirst{it.optString("action")=="syncAccount"}<frames.indexOfFirst{it.optString("action")=="pullSavedItems"})
    }}
    @Test fun previewUpdateRequiresAccountOnItsActualConnectionOnBothRoutes()=runBlocking<Unit>{runBoth {
        val preset=com.lightningstudio.watchrss.phone.data.reader.ReaderPreset.darkDefault()
        manager.updateReaderPresetPreview(device.address,"preview",1,preset)
        assertEquals(1,accountCount)
        assertTrue(frames.indexOfFirst{it.optString("action")=="syncAccount"}<frames.indexOfFirst{it.optString("action")=="previewReader"})
    }}


    @Test fun temporaryAccountFailureStopsBothRoutesWithoutLoginOrData()=runBlocking<Unit>{
        issueFailure=com.lightningstudio.watchrss.phone.account.PhoneAccountTemporarilyUnavailable(IOException("backoff"),2000)
        runBoth {
            val failure=runCatching{manager.syncAll(device,reusableSession=session())}.exceptionOrNull()
            assertTrue(failure.toString(),failure is com.lightningstudio.watchrss.phone.account.PhoneAccountTemporarilyUnavailable)
            assertFalse(failure is PhoneLoginRequired);assertEquals(0,accountCount);assertEquals(0,cursorCount);assertEquals(0,reconnects)
        }
    }
    @Test fun directLibraryEntryUsesTheCommonGateOnBothRoutes()=runBlocking<Unit>{runBoth {
        manager.syncLibrary(device.address,session())
        assertEquals(1,accountCount);assertEquals(1,cursorCount)
    }}


    @Test fun everyIndependentDataEntryStopsBeforeAnyFrameWithoutLogin()=runBlocking<Unit>{login=false;runBoth {
        val preset=com.lightningstudio.watchrss.phone.data.reader.ReaderPreset.darkDefault()
        val calls=listOf<suspend ()->Unit>(
            {manager.syncLibrary(device.address,session());Unit},
            {manager.syncReaderPresets(device.address,syncSession=session())},
            {manager.syncSavedItems(com.lightningstudio.watchrss.phone.data.model.PhoneSavedItemType.FAVORITE);Unit},
            {manager.syncSavedItems(com.lightningstudio.watchrss.phone.data.model.PhoneSavedItemType.WATCH_LATER);Unit},
            {manager.sendRemoteInput("https://example.invalid/feed");Unit},
            {manager.updateReaderPresetPreview(device.address,"preview",1,preset);Unit},
            {manager.stopReaderPresetPreview(device.address,"preview")},
            {manager.syncLlmTokenUsage(device,session());Unit}
        )
        calls.forEach { assertTrue(runCatching{it()}.exceptionOrNull() is PhoneLoginRequired) }
        assertTrue(frames.isEmpty())
    }}
    @Test fun unrelatedPodjsRejectionsNeverRepairAccountOnEitherRoute()=runBlocking<Unit>{runBoth {
        for(code in listOf("account_switch_rejected","library_exchange_failed","capability_not_supported")) {
            frames.clear();accountCount=0;cursorCount=0;reconnects=0
            rejectCount=1;rejectionCode=code
            val error=runCatching{manager.syncAll(device,reusableSession=session())}.exceptionOrNull()
            assertNotNull(error);assertFalse(error is WatchAccountSyncRequired)
            assertEquals(1,accountCount);assertEquals(0,reconnects)
        }
    }}
    @Test fun retiredCloud426StopsBeforeDataWithoutLoginPromptOnEitherRoute()=runBlocking<Unit>{
        issueFailure=PhoneAccountHttpException(426,"""{"error":"upgrade_required"}""")
        runBoth {
            val error=runCatching{manager.syncAll(device,reusableSession=session())}.exceptionOrNull()
            assertTrue(error is PhoneAccountSyncFailed);assertFalse(error is PhoneLoginRequired)
            assertEquals(0,cursorCount);assertEquals(0,reconnects)
        }
    }

    @Test fun freshTransportNegotiatesSessionBeforeAccountAndDataOnBothRoutes()=runBlocking<Unit>{runBoth {
        manager.syncAll(device,reusableSession=session(pending=true))
        val actions=frames.filter{it.optString("action")!="ack"}.take(3).map{it.optString("action")}
        assertEquals(listOf("syncLibrary","syncAccount","syncLibrary"),actions)
        assertEquals(1,accountCount);assertEquals(1,cursorCount)
    }}

    @Test fun debugNonLibraryActionsUseTheSameGateOnBothRoutes()=runBlocking<Unit>{runBoth {
        for(action in listOf("remoteInput","pullSavedItems")) {
            frames.clear();val before=accountCount
            com.lightningstudio.watchrss.phone.runDebugBluetoothSyncAction(manager,action,com.lightningstudio.watchrss.phone.data.model.PhoneSavedItemType.FAVORITE,
                "https://example.invalid/feed",device.address){error("data must not use raw diagnostic")}
            assertEquals(before+1,accountCount)
            assertTrue(frames.indexOfFirst{it.optString("action")=="syncAccount"}<frames.indexOfFirst{it.optString("action")==action})
        }
    }}
    @Test fun invalidLoginAndUnknownDebugActionsCannotReachRawTransport()=runBlocking<Unit>{login=false;runBoth {
        var rawCalls=0
        for(action in listOf("remoteInput","pullSavedItems","syncLibrary","syncNotes")) {
            assertNotNull(runCatching { com.lightningstudio.watchrss.phone.runDebugBluetoothSyncAction(manager,action,
                com.lightningstudio.watchrss.phone.data.model.PhoneSavedItemType.FAVORITE,"https://example.invalid/feed",device.address){rawCalls++;error("must not reach raw transport")} }.exceptionOrNull())
        }
        assertEquals(0,rawCalls)
        assertTrue(frames.isEmpty())
    }}


    @Test fun disconnectedLibrarySearchesAndReconfirmsAccountBeforeResuming()=runBlocking<Unit>{runBoth {
        failFirstCursor=true
        manager.syncAll(device,reusableSession=session())
        assertEquals(1,reconnects);assertEquals(2,accountCount)
        assertEquals(2,frames.count{it.optString("action")=="syncLibrary" && it.optString("phase")=="cursor"})
        val requests=frames.filter{it.optString("action")!="ack"}
        val secondAccount=requests.indexOfLast{it.optString("action")=="syncAccount"}
        assertEquals("cursor",requests[secondAccount+1].optString("phase"))
    }}
    @Test fun lostConnectionReportsOnlyAfterThreeFailedSearches()=runBlocking<Unit>{runBoth {
        failFirstCursor=true
        manager.recoveryProbeDevices={_,_->reconnects++;PhoneBluetoothWatchProbeBatch(emptyList())}
        assertTrue(runCatching{manager.syncAll(device,reusableSession=session())}.exceptionOrNull() is LibraryReconnectLimit)
        assertEquals(3,reconnects);assertEquals(1,accountCount)
    }}
    @Test fun socketSearchFailuresCountThreeRoundsOnBothRoutes()=runBlocking<Unit>{runBoth {
        failFirstCursor=true
        manager.recoveryProbeDevices={_,_->reconnects++;throw IOException("connect refused")}
        assertTrue(runCatching{manager.syncAll(device,reusableSession=session())}.exceptionOrNull() is LibraryReconnectLimit)
        assertEquals(3,reconnects);assertEquals(1,accountCount)
    }}
    @Test fun changedIdentityAtOriginalBluetoothAddressStopsImmediately()=runBlocking<Unit>{runBoth {
        failFirstCursor=true
        manager.recoveryProbeDevices={_,_->reconnects++;PhoneBluetoothWatchProbeBatch(
            listOf(PhoneBluetoothWatchProbeResult(device.copy(remoteDeviceId="different-watch"),true)),session())}
        val failure=runCatching{manager.syncAll(device,reusableSession=session())}.exceptionOrNull()
        assertEquals("重新连接的手表身份不一致",failure?.message)
        assertEquals(1,reconnects);assertEquals(1,accountCount)
    }}
    @Test fun manifestAndArticleRefusalsStopBeforeAppliedAckOnBothRoutes()=runBlocking<Unit>{
        for(phase in listOf("manifest","articles")) {rejectLibraryPhase=phase;runBoth {
            val failure=runCatching{manager.syncAll(device,reusableSession=session())}.exceptionOrNull()
            assertTrue(failure.toString(),failure is WatchAccountSyncRequired)
            assertEquals(1,accountCount);assertEquals(0,reconnects)
            // The single account confirmation receives an ACK; explicit data refusal does not.
            assertEquals(1,frames.count{it.optString("action")=="ack" && it.optBoolean("success") && it.optBoolean("applied")})
            assertEquals(1,frames.count{it.optString("action")=="syncLibrary" && it.optString("phase")==phase})
            if(phase=="manifest") assertFalse(frames.any{it.optString("action")=="syncLibrary" && it.optString("phase")=="articles"})
        }}
    }

    @Test fun threeLiveDisconnectsInTenSecondsStopWithoutThirdReconnect()=runBlocking<Unit>{runBoth {
        dropCursorCount=3
        assertTrue(runCatching{manager.syncAll(device,reusableSession=session())}.exceptionOrNull() is LibraryDisconnectLimit)
        assertEquals(2,reconnects);assertEquals(3,accountCount)
    }}
    @Test fun protocolErrorsNeverReconnect()=runBlocking<Unit>{protocolCursor=true;runBoth {
        assertTrue(runCatching{manager.syncAll(device,reusableSession=session())}.exceptionOrNull() is java.net.ProtocolException)
        assertEquals(0,reconnects)
    }}
    @Test fun cancellingLiveIoStopsWithoutSearch()=runBlocking<Unit>{runBoth {
        val job=launch(start=CoroutineStart.LAZY){manager.syncAll(device,reusableSession=session())}
        cancelAtCursor=job;job.start();job.join();cancelAtCursor=null
        assertTrue(job.isCancelled);assertEquals(0,reconnects)
    }}
    @Test fun lostFinalSessionAckDoesNotReplayCompletedLibrary()=runBlocking<Unit>{runBoth {
        dropCompleteCount=1
        manager.syncAll(device,reusableSession=session())
        assertEquals(1,cursorCount);assertEquals(2,accountCount);assertEquals(1,reconnects)
        assertEquals(2,frames.count{it.optString("action")=="syncSession"})
    }}
    @Test fun readerNetworkRetryResumesReaderWithoutReplayingLibrary()=runBlocking<Unit>{enableReader=true;runBoth {
        dropReaderCount=1
        manager.syncAll(device,reusableSession=session())
        assertEquals(1,cursorCount);assertEquals(2,accountCount);assertEquals(1,reconnects)
        assertEquals(2,frames.count{it.optString("action")=="syncReader"})
    }}
    @Test fun lostImageAckRestartsOnlyCurrentImageAndDoesNotReapplyNotes()=runBlocking<Unit>{runBoth {
        val file=File(context.filesDir,"notes/assets/probe.png");file.parentFile!!.mkdirs()
        val bytes=ByteArray(NoteAssetSyncPayload.CHUNK_BYTES+7){(it%251).toByte()};file.writeBytes(bytes)
        notes.save(title="asset",markdown="![probe](assets/probe.png)")
        remoteAsset=ByteArrayOutputStream();loseAssetAck=true
        manager.syncAll(device,reusableSession=session())
        assertArrayEquals(bytes,remoteAsset.toByteArray())
        assertEquals(1,cursorCount);assertEquals(1,frames.count{it.optString("action")=="syncNotes"})
        assertEquals(listOf(0,1,0,1),frames.filter{it.optString("action")=="syncNoteAsset"}.map{it.optInt("chunkIndex")})
        file.delete()
    }}
    @Test fun onlyExistingPodjsCapabilityEnablesBatchReceiptsInMerge()=runBlocking<Unit>{supportsCheckpoints=true;runBoth {
        manager.syncAll(device,reusableSession=session())
        assertTrue(frames.any{it.optString("action")=="ack"&&it.optString("phase")=="libraryBatch"})
        assertTrue(frames.first{it.optString("phase")=="cursor"}.optBoolean(LibrarySyncPayload.ARTICLE_CHECKPOINT_FEATURE))
        frames.clear()
        manager.syncAll(device,reusableSession=session(),mode=LibrarySyncMode.PHONE_TO_WATCH_REPLACE)
        assertFalse(frames.any{it.optString("phase")=="libraryBatch"})
        assertFalse(frames.first{it.optString("phase")=="cursor"}.has(LibrarySyncPayload.ARTICLE_CHECKPOINT_FEATURE))
    }}
    @Test fun malformedBatchReceiptIsProtocolFailureWithoutSearch()=runBlocking<Unit>{supportsCheckpoints=true;badBatchAck=true;runBoth {
        assertTrue(runCatching{manager.syncAll(device,reusableSession=session())}.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0,reconnects)
    }}
    @Test fun initialSearchAlsoHasThreeFailureLimit()=runBlocking<Unit>{
        var searches=0
        manager.probeDevices={_,_->searches++;PhoneBluetoothWatchProbeBatch(emptyList())}
        assertTrue(runCatching{manager.probeLibrarySyncTargets(recoveryPolicy=LibraryTransferRecovery{0})}.exceptionOrNull() is LibraryReconnectLimit)
        assertEquals(3,searches)
    }

    // RVHOTFIX131-01: retain the two independent ReviewHotfix131ExtraDataProbeTest counterexamples.
    private suspend fun clearThroughProductionViewModel(expected:com.lightningstudio.watchrss.phone.PhoneSyncAccountPrompt) {
        val env=com.lightningstudio.watchrss.phone.account.AccountEnvironment(backendBaseUrl="http://127.0.0.1:1",supabaseAnonKey="synthetic")
        val account=com.lightningstudio.watchrss.phone.account.PhoneAccountRepository(env,
            com.lightningstudio.watchrss.phone.account.EncryptedAccountSessionStore(context),
            com.lightningstudio.watchrss.phone.account.PhoneInstallationIdentity(context),
            com.lightningstudio.watchrss.phone.account.PhoneAccountClient(env,com.lightningstudio.watchrss.phone.account.LicenseDeviceIdentity(context)),"phone")
        val telemetry=com.lightningstudio.watchrss.phone.data.telemetry.PhoneUsageTelemetry(context,env,
            com.lightningstudio.watchrss.phone.account.PhoneInstallationIdentity(context),"phone",account,scope)
        val reader=ReaderPresetRepository(context,db,db.readerPresetDao(),"phone",scope)
        val vm=com.lightningstudio.watchrss.phone.viewmodel.MainViewModel(repo,manager,PhoneLlmTokenUsageRepository(db.llmTokenUsageDao()),telemetry,
            com.lightningstudio.watchrss.phone.data.backup.WatchRssBackupService(context,db,repo,reader,"phone"),
            com.lightningstudio.watchrss.phone.tips.TipManager(emptyList(),com.lightningstudio.watchrss.phone.tips.TipStateStore(context),com.lightningstudio.watchrss.phone.tips.TipEventStore(context)))
        val lifecycle=androidx.lifecycle.ViewModelStore().apply{put("review",vm)}
        val state=org.robolectric.util.ReflectionHelpers.getField<kotlinx.coroutines.flow.MutableStateFlow<com.lightningstudio.watchrss.phone.viewmodel.MainUiState>>(vm,"sessionState")
        try {
            vm.clearWatchLibraryByBluetooth()
            val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
            do {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                if(System.nanoTime()>deadline)error("clear ViewModel state timeout")
                Thread.sleep(5)
            }while(state.value.isBusy || (state.value.message==null && state.value.accountPrompt==null))
            assertFalse(frames.any{it.optString("action")=="clearLibrary"})
            println("route=$transport busy="+state.value.isBusy+" prompt="+state.value.accountPrompt+" status="+state.value.syncStatusMessage+" error="+state.value.syncStatusError)
            assertEquals("clear-library account failure must expose the production reauthentication prompt",expected,state.value.accountPrompt)
            assertNull("failed clear must not retain an in-progress status",state.value.syncStatusMessage)
            assertNull("failed clear must not retain an in-progress message",state.value.message)
        }finally{lifecycle.clear()}
    }
    @Test fun reviewClearDeviceVerificationUsesTheProductionAccountPrompt()=runBlocking<Unit>{
        issueFailure=com.lightningstudio.watchrss.phone.account.PhoneDeviceVerificationRequired()
        runBoth{clearThroughProductionViewModel(com.lightningstudio.watchrss.phone.PhoneSyncAccountPrompt.DEVICE)}
    }
    @Test fun reviewClearMissingLoginUsesTheProductionLoginPrompt()=runBlocking<Unit>{
        login=false
        runBoth{clearThroughProductionViewModel(com.lightningstudio.watchrss.phone.PhoneSyncAccountPrompt.LOGIN)}
    }

}
