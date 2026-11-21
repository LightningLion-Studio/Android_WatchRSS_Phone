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
    private var rejectReader=false
    private var closeOnRejection=false
    private var failClear=false
    private var unbound=false
    private var failFirstCursor=false
    private var rejectCount=0
    private var cursorCount=0
    private var accountCount=0
    private var reconnects=0
    private var transport="rfcomm"
    private var rejectionCode:String?=null
    private var issueFailure:Throwable?=null
    private val manager=PhoneBluetoothSyncManager(context,repo,NoteRepository(db.noteDao(),"phone"),ReaderPresetRepository(context,db,db.readerPresetDao(),"phone",scope),PhoneLlmTokenUsageRepository(db.llmTokenUsageDao()),"phone",log,
        canSyncAccount={login},currentPhoneUserId={"owner"},buildAccountSyncRequest={_,_,_->
            issueFailure?.let { throw it }
            withWatchAccountDeviceRefresh({"old-device-token"},{refreshes++;if(deviceRefreshSucceeds) com.lightningstudio.watchrss.phone.account.WatchAccountRefreshResult.Ready else com.lightningstudio.watchrss.phone.account.WatchAccountRefreshResult.DeviceVerificationRequired()}) {
                if(requiresDeviceRefresh && refreshes==0)throw PhoneAccountHttpException(403,"""{"error":"device_session_expired"}""")
                JSONObject().put("action","syncAccount")
            }
        })
    @After fun close(){scope.cancel();db.close()}
    private fun answer(action:String,phase:String="")=JSONObject().put("success",true).put("action",action).put("phase",phase).put("version",14).put("deviceId","watch")
    private fun session():PhoneSyncSession {
        val incoming=ArrayDeque<Byte>()
        var ip:PhoneIpSyncSession?=null
        var rejectedConnection=false
        val output=object:ByteArrayOutputStream(){override fun flush(){
            if(size()==0)return
            if(rejectedConnection)throw EOFException("Compose closed rejected connection")
            val q=BluetoothSyncProtocol.readFrame(ByteArrayInputStream(toByteArray()));reset();frames+=q
            val action=q.optString("action");val phase=q.optString("phase")
            if(action=="ack")return
            val a=when(action){
                "syncAccount"->{accountCount++;answer(action).put("boundUserId","owner")}
                "syncLibrary"->when(phase){
                    "probe"->answer(action,phase).asLegacyPodJsProbe().put("supportsPersistentSession",true).put("persistentSessionAccepted",true).apply { if(unbound)put("account",JSONObject().put("bound",false)) }
                    "cursor"->{if(failFirstCursor){failFirstCursor=false;throw IOException("synthetic disconnect")};cursorCount++;if(cursorCount<=rejectCount)answer(action,phase).put("success",false)
                        .put("message","请重新连接并同步账号后再同步资料").apply {rejectionCode?.let {put("errorCode",it)}}
                        else LibrarySyncPayload.buildCursorResponse("watch",LibrarySyncCursor(0,0,0)).put("supportsAuthoritativeLibraryReplace",true)}
                    "manifest"->LibrarySyncPayload.buildManifestResponse("watch",emptyList()).put("supportsReaderPresets",rejectReader)
                        .put("supportsNoteManifestV2",false).put("supportsNoteAssetInventory",false)
                    "articles"->LibrarySyncPayload.buildChunkedResponseFrames("watch",emptyList(),emptyList(),useBatches=false).single()
                    else->error("unexpected library phase $phase")
                }
                "syncReader"->if(rejectReader)answer(action,phase).put("success",false).put("message","请重新连接并同步账号后再同步资料")
                    else JSONObject(q.toString()).put("success",true).put("deviceId","watch").put("missingResources",org.json.JSONArray())
                "remoteInput"->answer(action)
                "clearLibrary"->if(failClear)throw IOException("clear transport disconnected") else answer(action)
                "pullSavedItems"->answer(action).put("items",org.json.JSONArray())
                "previewReader"->JSONObject(q.toString()).put("success",true)
                "syncNotes"->NoteSyncPayload.manifest("watch",emptyList()).put("success",true)
                "syncLlmTokenUsage"->answer(action).put("records",org.json.JSONArray())
                "syncSession"->answer(action,phase)
                else->error("unexpected $action/$phase")
            }
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
            PhoneSyncSession.Transport(activeIp.inputStream,activeIp.outputStream,transport,false,activeIp::close)
        } else PhoneSyncSession.Transport(input,output,transport,false,{})
        return PhoneSyncSession(PhoneBluetoothSyncClient(context,log),device,"phone",streams,true,false,false)
    }
    private suspend fun runBoth(block:suspend ()->Unit) {
        for(route in listOf("rfcomm","ip:test")) {
            transport=route;frames.clear();accountCount=0;cursorCount=0;reconnects=0;refreshes=0
            manager.probeDevices={_,_,_->PhoneBluetoothWatchProbeBatch(listOf(PhoneBluetoothWatchProbeResult(device,true)),session())}
            manager.reconnectDelay={error("account rejection must use its separate bounded repair")}
            manager.reconnectSession={_,_->reconnects++;session()}
            block()
        }
    }
    @Test fun validLoginConfirmsAccountBeforeAnyDataOnBothRoutes()=runBlocking<Unit>{runBoth {
        manager.syncAll(device,reusableSession=session())
        val requests=frames.filter{it.optString("action")!="ack"}
        assertEquals(listOf("syncLibrary","syncAccount","syncLibrary"),requests.take(3).map{it.optString("action")})
        assertEquals(1,accountCount);assertEquals(1,cursorCount)
    }}
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
        assertTrue(runCatching{manager.syncAll(device,reusableSession=session())}.exceptionOrNull() is PhoneLoginRequired)
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
    @Test fun composeMessageRejectionReconnectsAndResyncsExactlyOnceOnBothRoutes()=runBlocking<Unit>{rejectCount=1;runBoth {
        manager.syncAll(device,reusableSession=session())
        assertEquals(2,accountCount);assertEquals(2,cursorCount);assertEquals(1,reconnects)
    }}
    @Test fun codedRejectionResyncsExactlyOnceOnBothRoutes()=runBlocking<Unit>{rejectCount=1;rejectionCode="account_sync_required";runBoth {
        manager.syncAll(device,reusableSession=session());assertEquals(2,accountCount);assertEquals(1,reconnects)
    }}
    @Test fun repeatedRejectionKeepsAccurateReasonAndStopsOnBothRoutes()=runBlocking<Unit>{rejectCount=3;runBoth {
        val failure=runCatching{manager.syncAll(device,reusableSession=session())}.exceptionOrNull()
        assertTrue(failure is WatchAccountSyncRequired);assertEquals("请重新连接并同步账号后再同步资料",failure!!.message)
        assertEquals(2,accountCount);assertEquals(2,cursorCount);assertEquals(1,reconnects)
        assertFalse(frames.any{it.optString("action")=="syncLibrary"&&it.optString("phase")=="manifest"})
    }}
    @Test fun replaceAlsoConfirmsAccountBeforeSendingDataOnBothRoutes()=runBlocking<Unit>{runBoth {
        manager.syncAll(device,mode=LibrarySyncMode.WATCH_TO_PHONE_REPLACE,reusableSession=session())
        assertEquals(1,accountCount);assertEquals(1,cursorCount)
    }}
    @Test fun unrelatedPeerFailureDoesNotTriggerAccountRepair() {
        assertTrue(runCatching{requireAccountAccepted(JSONObject().put("success",false).put("errorCode","device_owned_by_another_account"))}.isSuccess)
        assertTrue(runCatching{requireAccountAccepted(JSONObject().put("success",false).put("message","other"))}.isSuccess)
    }
    @Test fun independentReaderStopsBeforeDataWithoutLoginOnBothRoutes()=runBlocking<Unit>{login=false;runBoth {
        assertTrue(runCatching{manager.syncReaderPresets(device.address,syncSession=session())}.exceptionOrNull() is PhoneLoginRequired)
        assertFalse(frames.any{it.optString("action")=="syncReader"})
    }}
    @Test fun boundOrUnknownClearStopsBeforeDataWithoutLoginOnBothRoutes()=runBlocking<Unit>{login=false;runBoth {
        assertTrue(runCatching{manager.clearWatchLibrary(device.address,session())}.exceptionOrNull() is PhoneLoginRequired)
        assertFalse(frames.any{it.optString("action")=="clearLibrary"})
    }}
    @Test fun explicitlyUnboundClearKeepsExistingPolicyWithoutLoginOnBothRoutes()=runBlocking<Unit>{login=false;unbound=true;runBoth {
        manager.clearWatchLibrary(device.address,session())
        assertEquals(0,accountCount);assertEquals(1,frames.count{it.optString("action")=="clearLibrary"})
    }}
    @Test fun independentReaderConfirmsEveryNewConnectionOnBothRoutes()=runBlocking<Unit>{runBoth {
        repeat(2){manager.syncReaderPresets(device.address,syncSession=session())}
        assertEquals(2,accountCount);assertEquals(2,frames.count{it.optString("action")=="syncReader"})
        assertTrue(frames.indexOfFirst{it.optString("action")=="syncAccount"}<frames.indexOfFirst{it.optString("action")=="syncReader"})
    }}
    @Test fun readerRefusalInGlobalSyncHasExactlyOneRepairAndRetryOnBothRoutes()=runBlocking<Unit>{rejectReader=true;runBoth {
        val failure=runCatching{manager.syncAll(device,reusableSession=session())}.exceptionOrNull()
        assertTrue(failure.toString(),failure is WatchAccountSyncRequired)
        assertEquals(2,accountCount);assertEquals(1,reconnects)
        assertEquals(2,frames.count{it.optString("action")=="syncReader"})
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
    @Test fun forceFullAndPhoneReplaceRequireAccountOnBothRoutes()=runBlocking<Unit>{runBoth {
        for(mode in listOf(LibrarySyncMode.MERGE,LibrarySyncMode.PHONE_TO_WATCH_REPLACE)) {
            val before=accountCount
            manager.syncAll(device,forceFull=true,mode=mode,reusableSession=session())
            assertEquals(before+1,accountCount)
        }
    }}
    @Test fun readerTransportRecoveryReconfirmsAccountOnBothRoutes()=runBlocking<Unit>{runBoth {
        failFirstCursor=true;manager.reconnectDelay={}
        manager.syncAll(device,reusableSession=session())
        assertEquals(2,accountCount);assertEquals(1,reconnects)
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
        manager.syncLibrary(device.address,session(),syncAuxiliary=false,booksPrepared=true)
        assertEquals(1,accountCount);assertEquals(1,cursorCount)
    }}

    @Test fun clearTransportFailureKeepsManualRetryPolicyOnBothRoutes()=runBlocking<Unit>{
        failClear=true;unbound=true;login=false
        runBoth {
            val failure=runCatching{manager.clearWatchLibrary(device.address,session())}.exceptionOrNull()
            assertTrue(failure.toString(),failure is WatchLibraryClearException)
            assertEquals(0,reconnects);assertEquals(1,frames.count{it.optString("action")=="clearLibrary"})
        }
    }

    @Test fun debugNonLibraryDataActionsUseThePublicManagerGateOnBothRoutes()=runBlocking<Unit>{runBoth {
        for(action in listOf("remoteInput","pullSavedItems","clearLibrary")) {
            val before=accountCount
            com.lightningstudio.watchrss.phone.runDebugBluetoothSyncAction(manager,action,com.lightningstudio.watchrss.phone.data.model.PhoneSavedItemType.FAVORITE,
                "https://example.invalid/feed",device.address){error("data action bypassed the manager")}
            assertEquals(before+1,accountCount)
        }
    }}
    @Test fun debugDataActionsStopWithoutLoginAndUnknownRawActionsAreRejected()=runBlocking<Unit>{login=false;runBoth {
        for(action in listOf("remoteInput","pullSavedItems","clearLibrary","syncLibrary","previewReader","syncNotes")) {
            val failure=runCatching{com.lightningstudio.watchrss.phone.runDebugBluetoothSyncAction(manager,action,com.lightningstudio.watchrss.phone.data.model.PhoneSavedItemType.FAVORITE,
                "https://example.invalid/feed",device.address){error("raw data dispatch must be forbidden")}}.exceptionOrNull()
            assertNotNull(failure)
        }
        assertEquals(0,accountCount)
        assertFalse(frames.any{it.optString("action") in setOf("remoteInput","pullSavedItems","clearLibrary","syncNotes","previewReader")})
    }}

}
