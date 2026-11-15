package com.lightningstudio.watchrss.phone.connection.bluetooth

import android.Manifest
import android.bluetooth.*
import androidx.room.Room
import com.lightningstudio.watchrss.phone.data.db.*
import com.lightningstudio.watchrss.phone.data.log.BluetoothDebugLog
import com.lightningstudio.watchrss.phone.data.note.NoteRepository
import com.lightningstudio.watchrss.phone.data.reader.*
import com.lightningstudio.watchrss.phone.data.repo.PhoneCompanionRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.*
import java.io.*
import java.util.UUID

/** The production streaming client opens each shadow socket and authenticates that same socket. */
@RunWith(RobolectricTestRunner::class)
@Config(application=android.app.Application::class,manifest=Config.NONE,sdk=[35],shadows=[PreviewAccountConnectionTest.DeviceShadow::class,PreviewAccountConnectionTest.SocketShadow::class])
class PreviewAccountConnectionTest {
    private val context=RuntimeEnvironment.getApplication()
    private val db=Room.inMemoryDatabaseBuilder(context,PhoneCompanionDatabase::class.java).allowMainThreadQueries().build()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    private val repo=PhoneCompanionRepository(db.phoneSavedItemDao(),db.phoneArticleDao(),db.phoneRssSourceDao(),"phone",db.syncChangeLogDao(),db.syncPeerStateDao(),appMetaDao=db.appMetaDao())
    private val reader=ReaderPresetRepository(context,db,db.readerPresetDao(),"phone",scope)
    private var login=true
    private val manager=PhoneBluetoothSyncManager(context,repo,NoteRepository(db.noteDao(),"phone"),reader,PhoneLlmTokenUsageRepository(db.llmTokenUsageDao()),"phone",BluetoothDebugLog(context),
        canSyncAccount={login},currentPhoneUserId={"owner"},buildAccountSyncRequest={_,_,_->JSONObject().put("action","syncAccount")})
    @Before fun setup() {
        peers.clear();refuse=0
        Shadows.shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_SCAN)
        val adapter=context.getSystemService(BluetoothManager::class.java).adapter
        Shadows.shadowOf(adapter).setEnabled(true)
        val watch=adapter.getRemoteDevice("11:22:33:44:55:66")
        Shadows.shadowOf(watch).setName("watch");Shadows.shadowOf(adapter).setBondedDevices(setOf(watch))
    }
    @After fun close(){peers.forEach{it.input.close();it.pipe.close()};scope.cancel();db.close()}
    private suspend fun preview() {
        val updates=Channel<ReaderPreset>(Channel.CONFLATED);updates.close()
        manager.streamReaderPresetPreview("11:22:33:44:55:66","preview",ReaderPreset.darkDefault(),updates,{}, {})
    }
    @Test fun everyFreshPreviewSocketConfirmsAccountBeforeAnyPreviewFrame()=runBlocking<Unit>{
        repeat(2){withTimeout(10_000){preview()}}
        assertEquals(2,peers.size)
        peers.forEach { peer ->assertEquals(listOf("syncLibrary","syncAccount","previewReader","previewReader"),peer.frames.filter{it.optString("action")!="ack"}.map{it.optString("action")})}
    }
    @Test fun refusalReopensAndConfirmsExactlyOnceBeforeRetry()=runBlocking<Unit>{
        refuse=1;withTimeout(10_000){preview()}
        assertEquals(2,peers.size);assertEquals(2,peers.sumOf{p->p.frames.count{it.optString("action")=="syncAccount"}})
        assertEquals(2,peers.sumOf{p->p.frames.count{it.optString("action")=="previewReader"&&it.optString("phase")=="update"}})
    }
    @Test fun secondRefusalStopsRatherThanLoopingOrLosingOriginalReason()=runBlocking<Unit>{
        refuse=2;val error=runCatching{withTimeout(10_000){preview()}}.exceptionOrNull()
        assertTrue(error.toString(),error is WatchAccountSyncRequired);assertEquals(2,peers.size)
        assertEquals(2,peers.sumOf{p->p.frames.count{it.optString("action")=="previewReader"}})
    }
    @Test fun invalidAccountNeverOpensPreviewTransport()=runBlocking<Unit>{
        login=false;assertTrue(runCatching{preview()}.exceptionOrNull() is PhoneLoginRequired);assertTrue(peers.isEmpty())
    }
    class Peer {
        val frames=mutableListOf<JSONObject>();val input=PipedInputStream(1024*1024);val pipe=PipedOutputStream(input)
        val output=object:ByteArrayOutputStream(){override fun flush(){
            if(size()==0)return
            val q=BluetoothSyncProtocol.readFrame(toByteArray().inputStream());reset();frames+=q
            if(q.optString("action")=="ack")return
            val reply=JSONObject(q.toString()).put("success",true).put("deviceId","watch")
            when(q.optString("action")) {
                "syncLibrary"->reply.put("supportsPersistentSession",true).put("persistentSessionAccepted",true)
                "syncAccount"->reply.put("boundUserId","owner")
                "previewReader"->if(refuse>0){refuse--;reply.put("success",false).put("message","请重新连接并同步账号后再同步资料")}
                else->error("unexpected action")
            }
            BluetoothSyncProtocol.writeFrame(pipe,reply);pipe.flush()
        }}
    }
    @Implements(BluetoothDevice::class) class DeviceShadow:ShadowBluetoothDevice(){
        @Implementation override fun createRfcommSocketToServiceRecord(uuid:UUID):BluetoothSocket {
            val socket=Shadow.newInstanceOf(BluetoothSocket::class.java)
            Shadow.extract<SocketShadow>(socket).peer=Peer().also{peers+=it};return socket
        }
    }
    @Implements(BluetoothSocket::class) class SocketShadow:ShadowBluetoothSocket(){
        lateinit var peer:Peer
        @Implementation override fun getInputStream():InputStream=peer.input
        @Implementation override fun getOutputStream():OutputStream=peer.output
        @Implementation override fun close(){peer.input.close();peer.pipe.close()}
    }
    companion object {private val peers=mutableListOf<Peer>();private var refuse=0}
}
