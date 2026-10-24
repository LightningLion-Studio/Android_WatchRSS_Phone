package com.lightningstudio.watchrss.phone.connection.bluetooth

import android.content.Context
import androidx.room.Room
import com.lightningstudio.watchrss.phone.data.ble.BleDomainStore
import com.lightningstudio.watchrss.phone.data.db.*
import com.lightningstudio.watchrss.phone.data.note.NoteRepository
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
class DomainFastPathSessionTest {
    private val context:Context=RuntimeEnvironment.getApplication()
    private val db=Room.inMemoryDatabaseBuilder(context,PhoneCompanionDatabase::class.java).allowMainThreadQueries().build()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    private val store=BleDomainStore(db,File(context.cacheDir,"fast-session-${System.nanoTime()}"))
    private val repo=PhoneCompanionRepository(db.phoneSavedItemDao(),db.phoneArticleDao(),db.phoneRssSourceDao(),"phone",db.syncChangeLogDao(),db.syncPeerStateDao(),appMetaDao=db.appMetaDao(),bleDomains=store)
    private val device=PhoneBluetoothWatchDevice("watch","11:22:33:44:55:66",1,"watch",supportsPersistentSession=true)
    private val log=BluetoothDebugLog(context)
    private var allowAccount=true;private var stopAtCursor=false;private var legacyOnly=false
    private var cloudCalls=0;private var failCompleteAck=false;private var editAfterAck=false;private var discardOldReceipts=false;private var changeOwner=false;private var owner="owner"
    private val frames=mutableListOf<JSONObject>()
    private var accountWitness="account";private var accountApplyFixture=false
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
                val action=request.getString("action");val phase=request.getString("phase")
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
                            } else {put("committedDomains",JSONArray(BleDomainStore.DOMAINS));put("retryDomains",JSONArray())}
                        }
                    }
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
    @Test fun unchangedSevenDomainsOnlyExchangeSummaryCommitAndStrictComplete()=runBlocking<Unit> {
        seed();val result=manager.syncAll(device,reusableSession=session())
        assertEquals(0,cloudCalls);assertEquals(0,result.libraryStats?.sent)
        assertEquals(listOf("syncLibrary:probe","syncDomains:summary","syncDomains:commit","syncSession:complete"),frames.filter{it.optString("action")!="ack"}.map{it.getString("action")+":"+it.getString("phase")})
        assertEquals(7,store.receipts("scope","watch").size)
        assertEquals(4,frames.count{it.optString("action")=="ack"})
    }
    @Test fun lostCompleteAppliedAckDoesNotPublishStagedReceipts()=runBlocking<Unit> {
        seed();discardOldReceipts=true;failCompleteAck=true;manager.reconnectDelay={};manager.reconnectSession={_,_->throw IllegalArgumentException("offline fixture")}
        assertTrue(runCatching{manager.syncAll(device,reusableSession=session())}.isFailure)
        assertTrue(store.receipts("scope","watch").isEmpty());assertEquals(0,cloudCalls)
    }
    @Test fun editAfterCommitBeforeCompleteAckOnlyInvalidatesChangedDomain()=runBlocking<Unit> {
        seed();discardOldReceipts=true;editAfterAck=true
        manager.syncAll(device,reusableSession=session())
        assertTrue(store.receipts("scope","watch").isEmpty()) // cleared skip receipts cannot be recreated or renewed
        assertTrue(log.snapshot().contains("domains=\"books,bookReading,reader,notes,account,usage\""))
    }    @Test fun accountChangeDuringWindowCannotPublishOrComplete()=runBlocking<Unit> {
        seed();discardOldReceipts=true;changeOwner=true
        assertTrue(runCatching{manager.syncAll(device,reusableSession=session())}.isFailure)
        assertTrue(store.receipts("scope","watch").isEmpty())
        assertFalse(frames.any{it.optString("action")=="syncSession"&&it.optString("phase")=="complete"})
    }

    @Test fun postApplyAccountWitnessIsPersistedOnlyAfterStrictCompleteAndIsNotDomainProof()=runBlocking<Unit> {
        seed();db.openHelper.writableDatabase.execSQL("DELETE FROM ble_fast_receipts WHERE domain='account'")
        accountApplyFixture=true;manager.syncAll(device,reusableSession=session())
        val p=store.projectionVersions().getValue("account")
        assertEquals(listOf(BleDomainStore.hash("watchrss.ble.account-grant.v2\n${p.text("account")}\n${"c".repeat(64)}")),grantMarkers)
        assertFalse(store.receipts("scope","watch").containsKey("account"))
    }
    @Test fun lostStrictCompleteNeverBindsPostApplyAccountWitness()=runBlocking<Unit> {
        seed();db.openHelper.writableDatabase.execSQL("DELETE FROM ble_fast_receipts WHERE domain='account'")
        accountApplyFixture=true;failCompleteAck=true;manager.reconnectDelay={};manager.reconnectSession={_,_->throw IllegalArgumentException("offline")}
        assertTrue(runCatching{manager.syncAll(device,reusableSession=session())}.isFailure)
        assertTrue(grantMarkers.isEmpty())
    }
    @Test fun accountPostApplyRootIsOptionalAndStrictlyValidated() {
        val response=JSONObject().put("success",true)
        assertNull(AccountSyncPayload.parseResponse(response).accountStateRoot)
        assertEquals("c".repeat(64),AccountSyncPayload.parseResponse(response.put("accountStateRoot","c".repeat(64))).accountStateRoot)
        assertTrue(runCatching{AccountSyncPayload.parseResponse(response.put("accountStateRoot","bad"))}.isFailure)
    }

    @Test fun periodicFullAndPeerProtocolCannotBeSkippedByFreshSevenDomainReceipts()=runBlocking<Unit> {
        for(state in listOf(SyncPeerStateEntity("watch",lastProtocolVersion=12,lastFullSyncAt=System.currentTimeMillis()),SyncPeerStateEntity("watch",lastProtocolVersion=14,lastFullSyncAt=System.currentTimeMillis()-BleDomainStore.RECEIPT_MAX_AGE_MS))) {
            frames.clear();db.syncPeerStateDao().upsert(state);seed();stopAtCursor=true;allowAccount=false
            assertTrue(runCatching{manager.syncAll(device,reusableSession=session())}.isFailure)
            val summary=frames.single {it.optString("action")=="syncDomains"&&it.optString("phase")=="summary"}
            assertFalse(summary.getJSONObject("roots").has("library"));assertFalse(summary.getJSONObject("known").has("library"))
            assertTrue(frames.any {it.optString("action")=="syncLibrary"&&it.optString("phase")=="cursor"})
        }
    }
    @Test fun expiredReceiptsAndV1OnlyPeersReachOriginalCursorRatherThanSkip()=runBlocking<Unit> {
        seed();db.openHelper.writableDatabase.execSQL("UPDATE ble_fast_receipts SET verifiedAt=?",arrayOf(System.currentTimeMillis()-BleDomainStore.RECEIPT_MAX_AGE_MS))
        stopAtCursor=true;allowAccount=false
        assertTrue(runCatching{manager.syncAll(device,reusableSession=session())}.isFailure)
        assertEquals(0,frames.single{it.optString("action")=="syncDomains"&&it.optString("phase")=="summary"}.getJSONObject("known").length())
        assertTrue(frames.any{it.optString("action")=="syncLibrary"&&it.optString("phase")=="cursor"})
        frames.clear();legacyOnly=true
        assertTrue(runCatching{manager.syncAll(device,reusableSession=session())}.isFailure)
        assertFalse(frames.any{it.optString("action")=="syncDomains"})
        assertTrue(frames.any{it.optString("action")=="syncLibrary"&&it.optString("phase")=="cursor"})
    }

}
