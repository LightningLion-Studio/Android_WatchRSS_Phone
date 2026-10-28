package com.lightningstudio.watchrss.phone.connection.bluetooth

import android.bluetooth.*
import androidx.room.Room
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
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import java.io.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35],shadows=[DirectProbeCancellationTest.SocketShadow::class])
class WatchLibraryClearOperationTest {
    private val context=RuntimeEnvironment.getApplication()
    private val log=BluetoothDebugLog(context)
    private val db=Room.inMemoryDatabaseBuilder(context,PhoneCompanionDatabase::class.java).allowMainThreadQueries().build()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    private val repo=PhoneCompanionRepository(db.phoneSavedItemDao(),db.phoneArticleDao(),db.phoneRssSourceDao(),"phone")
    private val client=PhoneBluetoothSyncClient(context,log)
    private val active=mutableListOf<Boolean>()
    private val manager=PhoneBluetoothSyncManager(context,repo,NoteRepository(db.noteDao(),"phone"),
        ReaderPresetRepository(context,db,db.readerPresetDao(),"phone",scope),PhoneLlmTokenUsageRepository(db.llmTokenUsageDao()),"phone",log,onSyncActiveChanged={active+=it})
    private val device=BluetoothAdapter.getDefaultAdapter().getRemoteDevice("11:22:33:44:55:66")
    private val states=mutableListOf<WatchLibraryClearState>()
    private val io=DirectProbeCancellationTest.BlockingIo(false)
    private var sockets=0
    private fun response(action:String)=JSONObject().put("success",true).put("action",action).put("version",14)
    private fun probeResponse()=response("syncLibrary").put("phase","probe").put("deviceId","watch")
        .put("supportsPersistentSession",true).put("persistentSessionAccepted",true).put("account",JSONObject().put("bound",false))
    private fun encode(vararg frames:JSONObject)=ByteArrayOutputStream().apply{frames.forEach{BluetoothSyncProtocol.writeFrame(this,it)}}.toByteArray()
    private fun configure(vararg frames:JSONObject) {
        io.inputOverride=ByteArrayInputStream(encode(*frames))
        val socket=Shadow.newInstanceOf(BluetoothSocket::class.java)
        Shadow.extract<DirectProbeCancellationTest.SocketShadow>(socket).state=io
        client.probeSocketFactory={sockets++;socket}
    }
    private suspend fun targets():PhoneBluetoothProbeTargets {
        val identity=client.probeDirectPersistentOnce(device,"phone","clear-probe",false)!!
        return PhoneBluetoothProbeTargets(listOf(identity.session!!.device),identity.session)
    }
    private fun operation()=WatchLibraryClearOperation(
        probe={progress->progress(1,2);withContext(Dispatchers.IO){targets()}},
        clear={watch,lease->manager.clearWatchLibrary(watch.address,lease);Unit},
        onActive={if(it)manager.foregroundSyncStarted() else manager.foregroundSyncFinished()})
    private fun sent():List<JSONObject> {
        val input=io.output.toByteArray().inputStream()
        return buildList{while(input.available()>0)add(BluetoothSyncProtocol.readFrame(input))}
    }
    @After fun close(){io.close();scope.cancel();db.close()}
    @Test fun unboundProbeSessionCarriesClearAckAndCompleteWithoutReconnect()=runBlocking<Unit> {
        configure(probeResponse(),response("clearLibrary"),response("syncSession").put("phase","complete"))
        operation().run({error("single watch must not prompt")},{assertEquals(true,active.last());states+=it})
        assertEquals(listOf(true,false),active);assertEquals(1,sockets);assertEquals(1,io.closeCalls.get())
        assertEquals(listOf("syncLibrary","ack","clearLibrary","ack","syncSession","ack"),sent().map{it.getString("action")})
        assertEquals("complete",sent().first{it.optString("action")=="syncSession"}.getString("phase"))
        assertFalse(sent().first().has("phoneIpEndpoint"))
        val probing=states.first{it.probeCompleted==1};assertEquals(2,probing.probeTotal);assertNull(probing.clearPercent)
        assertTrue(states.filter{it.phase!=WatchLibraryClearPhase.COMPLETE}.all{it.clearPercent==null})
        assertEquals(100,states.last().clearPercent);assertEquals(WatchLibraryClearPhase.COMPLETE,states.last().phase)
    }
    @Test fun peerRefusalPreservesErrorCodeAndDoesNotAckAppliedOrShowComplete()=runBlocking<Unit> {
        configure(probeResponse(),response("clearLibrary").put("success",false).put("errorCode","clear_library_busy").put("retryable",true))
        try{operation().run({null},states::add);fail("peer refusal ignored")}catch(e:WatchLibraryClearException){assertEquals("clear_library_busy",e.errorCode)}
        assertEquals(listOf(true,false),active);assertEquals(WatchLibraryClearPhase.FAILED,states.last().phase)
        assertEquals("clear_library_busy",states.last().errorCode);assertFalse(states.last().remoteMayHaveChanged)
        assertEquals(listOf("syncLibrary","ack","clearLibrary"),sent().map{it.getString("action")});assertEquals(1,io.closeCalls.get())
    }
    @Test fun cancelledClearReadClosesSessionAndReleasesForegroundWithUnknownOutcome()=runBlocking<Unit> {
        configure(probeResponse());io.inputOverride=SequenceInputStream(io.inputOverride,io.input)
        val job=async(Dispatchers.Default){operation().run({null},states::add)}
        assertTrue(io.entered.await(2,TimeUnit.SECONDS));job.cancel()
        withTimeout(700){job.join()}
        assertEquals(listOf(true,false),active);assertEquals(WatchLibraryClearPhase.CANCELLED,states.last().phase)
        assertTrue(states.last().remoteMayHaveChanged);assertEquals(0L,io.closed.count)
        assertFalse(sent().any{it.optString("action")=="syncSession"})
    }
    @Test fun acceptedClearWithoutSessionCompleteResponseCannotPublishSuccess()=runBlocking<Unit> {
        configure(probeResponse(),response("clearLibrary"));io.inputOverride=SequenceInputStream(io.inputOverride,io.input)
        val job=async(Dispatchers.Default){operation().run({null},states::add)}
        assertTrue(io.entered.await(2,TimeUnit.SECONDS));job.cancel();withTimeout(700){job.join()}
        assertEquals(listOf(true,false),active);assertTrue(states.last().remoteMayHaveChanged)
        assertFalse(states.any{it.phase==WatchLibraryClearPhase.COMPLETE})
        assertEquals(listOf("syncLibrary","ack","clearLibrary","ack","syncSession"),sent().map{it.getString("action")})
        assertEquals(1,io.closeCalls.get())
    }
    @Test fun cancelledManagerIoHandoffClosesReturnedProbeLease()=runBlocking<Unit> {
        configure(probeResponse())
        val ready=CountDownLatch(1);val release=CountDownLatch(1)
        manager.probeDevices={_,ip,_->assertFalse(ip);val target=targets();ready.countDown();release.await()
            PhoneBluetoothWatchProbeBatch(listOf(PhoneBluetoothWatchProbeResult(target.devices.single(),true)),target.sessionLease)}
        val job=async(Dispatchers.Default){manager.probeLibrarySyncTargets(allowIpUpgrade=false)}
        assertTrue(ready.await(2,TimeUnit.SECONDS));job.cancel();release.countDown()
        withTimeout(700){job.join()};assertEquals(0L,io.closed.count);assertEquals(1,io.closeCalls.get())
    }
    @Test fun chooserDismissalNeverSendsDestructiveRequestAndReleasesOwner()=runBlocking<Unit> {
        val a=PhoneBluetoothWatchDevice("a","A",0);val b=PhoneBluetoothWatchDevice("b","B",0)
        var clears=0
        val op=WatchLibraryClearOperation({PhoneBluetoothProbeTargets(listOf(a,b))},{_,_->clears++},{active+=it})
        try{op.run({null},states::add);fail("dismiss ignored")}catch(_:CancellationException){}
        assertEquals(0,clears);assertEquals(listOf(true,false),active)
        assertEquals(WatchLibraryClearPhase.CANCELLED,states.last().phase);assertFalse(states.last().remoteMayHaveChanged)
    }
    @Test fun probeTimeoutAndNoDeviceHaveDistinctErrorsAndNeverClear()=runBlocking<Unit> {
        var clears=0
        for(code in listOf("clear_probe_timeout","clear_probe_not_found")) {
            val op=WatchLibraryClearOperation({PhoneBluetoothProbeTargets(emptyList(),failureCode=code)},{_,_->clears++},{})
            try{op.run({null},states::add);fail("no device ignored")}catch(e:WatchLibraryClearException){assertEquals(code,e.errorCode)}
            assertEquals(code,states.last().errorCode);assertFalse(states.last().remoteMayHaveChanged)
        }
        assertEquals(0,clears)
    }
    @Test fun cancelledSessionCannotBeRevivedByALateReplacement() {
        val watch=PhoneBluetoothWatchDevice("watch","A",0)
        fun session(onClose:()->Unit)=PhoneSyncSession(client,watch,"phone",PhoneSyncSession.Transport(ByteArrayInputStream(byteArrayOf()),ByteArrayOutputStream(),"test",false,onClose),true,false,false)
        val old=session({});old.close();var closed=0;val late=session{closed++}
        try{old.replaceWith(late);fail("cancelled session revived")}catch(_:CancellationException){}
        assertEquals(1,closed);assertNull(old.transport);assertNull(late.transport)
    }
    @Test fun cleanupFailureCannotPublishACompleteState()=runBlocking<Unit> {
        val watch=PhoneBluetoothWatchDevice("watch","A",0)
        val lease=PhoneSyncSession(client,watch,"phone",PhoneSyncSession.Transport(ByteArrayInputStream(byteArrayOf()),ByteArrayOutputStream(),"test",false,{throw IOException("close failed")}),true,false,false)
        val op=WatchLibraryClearOperation({PhoneBluetoothProbeTargets(listOf(watch),lease)},{_,_->},{active+=it})
        try{op.run({null},states::add);fail("cleanup ignored")}catch(_:WatchLibraryClearException){}
        assertEquals(WatchLibraryClearPhase.FAILED,states.last().phase);assertTrue(states.last().remoteMayHaveChanged)
        assertFalse(states.any{it.phase==WatchLibraryClearPhase.COMPLETE});assertEquals(listOf(true,false),active)
    }
    @Test fun probeFailureClassificationKeepsTimeoutPermissionAndIoStages()=runBlocking<Unit> {
        val connect=watchLibraryClearFailure(ProbeTransportException("connect",IOException()),WatchLibraryClearPhase.PROBING)
        assertEquals("clear_probe_connect",connect.errorCode)
        assertEquals("clear_probe_read",watchLibraryClearFailure(IOException(),WatchLibraryClearPhase.PROBING).errorCode)
        assertEquals("clear_probe_permission",watchLibraryClearFailure(SecurityException(),WatchLibraryClearPhase.PROBING).errorCode)
        assertEquals("clear_probe_bluetooth_off",watchLibraryClearFailure(IllegalArgumentException("蓝牙未开启"),WatchLibraryClearPhase.PROBING).errorCode)
        assertEquals("clear_probe_permission",watchLibraryClearFailure(IllegalArgumentException("缺少 BLUETOOTH_CONNECT 权限"),WatchLibraryClearPhase.PROBING).errorCode)
        assertEquals("clear_probe_bluetooth_unavailable",watchLibraryClearFailure(IllegalStateException("此设备没有蓝牙适配器"),WatchLibraryClearPhase.PROBING).errorCode)
        val timeout=try{withTimeout(1){delay(100)};error("timeout ignored")}catch(e:TimeoutCancellationException){e}
        assertEquals("clear_probe_timeout",watchLibraryClearFailure(timeout,WatchLibraryClearPhase.PROBING).errorCode)
        assertEquals("clear_library_transport_timeout",watchLibraryClearFailure(timeout,WatchLibraryClearPhase.CLEARING).errorCode)
    }
    @Test fun remoteErrorWhitelistAndProtocolValidationNeverPretendSuccess() {
        for(code in listOf("clear_library_cancelled","clear_library_timeout","clear_library_busy","clear_library_database","clear_library_files","clear_library_changed","clear_library_cleanup","unknown/private/path")) {
            try{requireWatchLibraryCleared(response("clearLibrary").put("success",false).put("errorCode",code));fail("false accepted")}
            catch(e:WatchLibraryClearException){assertEquals(if(code.startsWith("unknown"))"clear_library_rejected" else code,e.errorCode)}
        }
        try{requireWatchLibraryCleared(response("other"));fail("wrong action accepted")}catch(_:IllegalArgumentException){}
        assertFalse(isSessionOperationSafeForTransparentReplay(BluetoothSyncProtocol.ACTION_CLEAR_LIBRARY))
    }
}
