package com.lightningstudio.watchrss.phone.connection.bluetooth

import android.content.Context
import androidx.room.Room
import com.lightningstudio.watchrss.phone.data.ble.BleDomainStore
import com.lightningstudio.watchrss.phone.data.db.*
import com.lightningstudio.watchrss.phone.data.note.*
import com.lightningstudio.watchrss.phone.data.reader.ReaderPresetRepository
import com.lightningstudio.watchrss.phone.data.repo.PhoneCompanionRepository
import com.lightningstudio.watchrss.phone.data.log.BluetoothDebugLog
import kotlinx.coroutines.*
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.*

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class R2ScopeRegressionTest {
    private val context:Context=RuntimeEnvironment.getApplication()
    private val db=Room.inMemoryDatabaseBuilder(context,PhoneCompanionDatabase::class.java).allowMainThreadQueries().build()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    private val store=BleDomainStore(db,context.filesDir)
    private val repo=PhoneCompanionRepository(db.phoneSavedItemDao(),db.phoneArticleDao(),db.phoneRssSourceDao(),"phone",db.syncChangeLogDao(),db.syncPeerStateDao(),appMetaDao=db.appMetaDao(),bleDomains=store)
    private val device=PhoneBluetoothWatchDevice("watch","11:22:33:44:55:66",1,"watch",supportsPersistentSession=true)
    private val log=BluetoothDebugLog(context)
    private var allowAccount=true;private var stopAtCursor=false;private var legacyOnly=false
    private var cloudCalls=0;private var failCompleteAck=false;private var editAfterAck=false;private var discardOldReceipts=false;private var changeOwner=false;private var owner="owner"
    private val frames=mutableListOf<JSONObject>()
    private var accountWitness="account";private var accountApplyFixture=false
    private var reviewNote:NoteEntity?=null
    private val grantMarkers=mutableListOf<String>()
    private val remote=BleDomainStore.DOMAINS.associateWith{"b".repeat(64)}
    private val local by lazy {store.roots("account")}
    private val manager=PhoneBluetoothSyncManager(context,repo,NoteRepository(db.noteDao(),"phone"),ReaderPresetRepository(context,db,db.readerPresetDao(),"phone",scope),PhoneLlmTokenUsageRepository(db.llmTokenUsageDao()),"phone",log,
        buildAccountSyncRequest={_,_,_->JSONObject().put("action","syncAccount").put("phase","apply")},
        canSyncAccount={allowAccount},currentPhoneUserId={owner},onLibrarySyncCompleted={cloudCalls++},domainScope={"scope"},accountDomainWitness={accountWitness},acknowledgeAccountDomain={_,root->grantMarkers+=root})
    @Before fun registeredEmpty()=runBlocking<Unit>{
        db.openHelper.writableDatabase.execSQL("INSERT OR REPLACE INTO book_capture_meta VALUES(1,0,0,'[]')")
        repo.markLibrarySyncSuccess("watch",0,0,14,true,true,store.libraryDefinition())
    }
    @After fun close(){scope.cancel();db.close()}
    private fun response(action:String,phase:String)=JSONObject().put("success",true).put("version",if(action=="syncDomains")2 else 14).put("action",action).put("phase",phase).put("deviceId","watch")
    private fun session():PhoneSyncSession {
        val incoming=ArrayDeque<Byte>();var awaitingCompleteAck=false
        val output=object:ByteArrayOutputStream() {
            override fun flush() {
                if(size()==0)return
                val request=BluetoothSyncProtocol.readFrame(ByteArrayInputStream(toByteArray()));reset();frames+=request
                if(request.optString("action")=="ack") {
                    if(awaitingCompleteAck) {
                        if(failCompleteAck)throw IOException("bt socket closed")
                        if(editAfterAck)db.openHelper.writableDatabase.execSQL("UPDATE ble_fast_versions SET revision=revision+1 WHERE domain='library'")
                    }
                    return
                }
                val action=request.getString("action");val phase=request.optString("phase","chunk")
                if(stopAtCursor && action=="syncLibrary" && phase=="cursor")throw IllegalArgumentException("original cursor reached")
                val answer=when(action) {
                    "syncLibrary"->response(action,phase).put("supportsPersistentSession",true).put("persistentSessionAccepted",true).put("account",JSONObject().put("bound",true).put("userIdHash",BleDomainStore.hash("owner"))).advertiseBleSyncV2().apply {if(legacyOnly){remove("supportsDomainFastPathV2");put("supportsDomainFastPathV1",true)}}
                    "syncAccount"->{check(accountApplyFixture);accountWitness="installed-grant";response(action,phase).put("accountStateRoot","c".repeat(64))}
                    "syncDomains"->response(action,phase).put("windowId","window").put("roots",JSONObject(remote)).put("projectionVersions",JSONObject().apply { store.projectionVersions().forEach{(d,p)->put(d,p.json())} }).apply {
                        if(phase=="summary")put("skipDomains",JSONArray(request.getJSONObject("known").keys().asSequence().toList()))
                        else {
                            if(changeOwner)owner="other"
                            if(discardOldReceipts)db.openHelper.writableDatabase.execSQL("DELETE FROM ble_fast_receipts")
                            if(accountApplyFixture) {
                                getJSONObject("roots").remove("account")
                                put("committedDomains",JSONArray(BleDomainStore.DOMAINS.filter{it!="account"}));put("retryDomains",JSONArray(listOf("account")))
                            } else {put("committedDomains",request.getJSONArray("completedDomains"));put("retryDomains",JSONArray())}
                        }
                    }
                    "syncNotes"->if(phase=="manifest") NoteSyncPayload.inventory("watch",listOfNotNull(reviewNote),emptyList()).put("success",true)
                        else NoteSyncPayload.delta("watch",listOfNotNull(reviewNote),emptyList()).put("success",true).put("applied",0)
                    "syncNoteAsset"->response(action,phase).put("alreadyPresent",false)
                    "syncSession"->response(action,phase).also{awaitingCompleteAck=phase=="complete"}
                    else->error("whole-domain skip unexpectedly exported $action")
                }
                val encoded=ByteArrayOutputStream();BluetoothSyncProtocol.writeFrame(encoded,answer);encoded.toByteArray().forEach{incoming.addLast(it)}
            }
        }
        val input=object:InputStream(){override fun read():Int=if(incoming.isEmpty())-1 else incoming.removeFirst().toInt() and 255}
        return PhoneSyncSession(PhoneBluetoothSyncClient(context,log),device,"phone",PhoneSyncSession.Transport(input,output,"test",false,{}),true,false,false)
    }
    private fun seed(){store.publish("scope","watch",local.mapValues{it.value to remote.getValue(it.key)})}
    @Test fun excludedAssetsMustNotPublishAFullNotesProof()=runBlocking<Unit> {
        val noteRepo=NoteRepository(db.noteDao(),"phone",now={100})
        val asset=File(context.filesDir,"notes/assets/r2-excluded.png").apply{parentFile!!.mkdirs();writeBytes(byteArrayOf(97))}
        try {
            reviewNote=noteRepo.save(noteId="r2-excluded",title="scope",markdown="![asset](assets/r2-excluded.png)")
            seed();db.openHelper.writableDatabase.execSQL("DELETE FROM ble_fast_receipts WHERE domain='notes'")
            manager.syncAll(device,includeNoteAssets=false,reusableSession=session())
            val firstNotesProof=store.receipts("scope","watch").containsKey("notes")
            frames.clear()
            manager.syncAll(device,reusableSession=session())
            val assetFrames=frames.count{it.optString("action")=="syncNoteAsset"}
            val secondNotesFrames=frames.count{it.optString("action")=="syncNotes"}
            println("R2_SCOPE_PROBE firstFullNotesProof=$firstNotesProof secondNotesFrames=$secondNotesFrames secondAssetFrames=$assetFrames")
            assertFalse("Excluding assets must not create a full notes-domain proof",firstNotesProof)
            assertTrue("Next full sync must send the still-missing asset",assetFrames>0)
        } finally {asset.delete()}
    }

    @Test fun defaultScopeStillTransfersMissingAsset()=runBlocking<Unit> {
        val noteRepo=NoteRepository(db.noteDao(),"phone",now={100})
        val asset=File(context.filesDir,"notes/assets/r2-control.png").apply{parentFile!!.mkdirs();writeBytes(byteArrayOf(97))}
        try {
            reviewNote=noteRepo.save(noteId="r2-control",title="scope",markdown="![asset](assets/r2-control.png)")
            seed();db.openHelper.writableDatabase.execSQL("DELETE FROM ble_fast_receipts WHERE domain='notes'")
            manager.syncAll(device,reusableSession=session())
            val assetFrames=frames.count{it.optString("action")=="syncNoteAsset"}
            println("R2_SCOPE_CONTROL defaultAssetFrames=$assetFrames")
            assertTrue("The unchanged default path must send the missing asset",assetFrames>0)
        } finally {asset.delete()}
    }
    @Test fun defaultFullScopeMatchesExplicitFullScopeWire()=runBlocking<Unit> {
        val noteRepo=NoteRepository(db.noteDao(),"phone",now={100})
        val asset=File(context.filesDir,"notes/assets/r2-wire.png").apply{parentFile!!.mkdirs();writeBytes(byteArrayOf(97))}
        fun captured():ByteArray = ByteArrayOutputStream().also { out -> frames.forEach { original ->
            // Match the input wall clock, preserving all other fields and framing.
            val frame=JSONObject(original.toString()).apply{if(has("sentAt"))put("sentAt",100)}
            BluetoothSyncProtocol.writeFrame(out,frame)
        } }.toByteArray()
        try {
            reviewNote=noteRepo.save(noteId="r2-wire",title="scope",markdown="![asset](assets/r2-wire.png)")
            seed();db.openHelper.writableDatabase.execSQL("DELETE FROM ble_fast_receipts WHERE domain='notes'")
            val implicit=manager.syncAll(device,reusableSession=session())
            assertTrue(frames.any{it.optString("action")=="syncNoteAsset"})
            val expected=captured()
            frames.clear();seed();db.openHelper.writableDatabase.execSQL("DELETE FROM ble_fast_receipts WHERE domain='notes'")
            val explicit=manager.syncAll(device,includeNoteAssets=true,reusableSession=session())
            assertEquals(implicit,explicit)
            assertArrayEquals(expected,captured())
        } finally {asset.delete()}
    }

}
