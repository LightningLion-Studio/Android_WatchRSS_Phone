package com.lightningstudio.watchrss.phone.connection.bluetooth

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
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.*

/** Real manager + session framing + SQLite; the peer transport controls ACK loss/refusal. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class ConflictDecisionSessionTest {
    private val context=RuntimeEnvironment.getApplication()
    private val db=Room.inMemoryDatabaseBuilder(context,PhoneCompanionDatabase::class.java).allowMainThreadQueries().build()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    private val repo=PhoneCompanionRepository(db.phoneSavedItemDao(),db.phoneArticleDao(),db.phoneRssSourceDao(),"phone",db.syncChangeLogDao(),db.syncPeerStateDao(),appMetaDao=db.appMetaDao())
    private val log=BluetoothDebugLog(context)
    private val manager=PhoneBluetoothSyncManager(context,repo,NoteRepository(db.noteDao(),"phone"),ReaderPresetRepository(context,db,db.readerPresetDao(),"phone",scope),PhoneLlmTokenUsageRepository(db.llmTokenUsageDao()),"phone",log)
    private val frames=mutableListOf<JSONObject>()
    private var watch=PhoneArticleEntity("a","watch","https://example.org/a","title","site","",null,"watch-body",null,"hash",1,9_000_000_000_000L,false,0,0,null,null,true,9_000_000_000_000L,1,false,0,0,false,0)
    private var round=0;private var refuseReturn=false;private var loseReturnAck=false
    private var prompts=0
    @After fun close(){scope.cancel();db.close()}
    private fun session():PhoneSyncSession {
        val incoming=ArrayDeque<Byte>()
        fun enqueue(value:JSONObject){val bytes=ByteArrayOutputStream();BluetoothSyncProtocol.writeFrame(bytes,value);bytes.toByteArray().forEach{incoming.addLast(it)}}
        val output=object:ByteArrayOutputStream(){override fun flush(){
            if(size()==0)return
            val q=BluetoothSyncProtocol.readFrame(ByteArrayInputStream(toByteArray()));reset();frames+=q
            val phase=q.optString("phase")
            if(q.optString("action")=="ack") {if(loseReturnAck&&round==2)throw IOException("bt socket closed after decision applied");return}
            when(phase){
                "cursor"->{round++;enqueue(LibrarySyncPayload.buildCursorResponse("watch",LibrarySyncCursor(0,0,0)).put("supportsRssCanonicalIds",false))}
                "manifest"->{val m=ArticleSyncBody.metadataFor(watch);watch=watch.copy(syncBodyHash=m.bodyHash,syncBodyByteCount=m.bodyByteCount,syncChunkSize=m.chunkSize,syncChunkHashesJson=org.json.JSONArray(m.chunkHashes).toString())
                    enqueue(LibrarySyncPayload.buildManifestResponse("watch",listOf(watch)).put("supportsRssCanonicalIds",false).apply{if(refuseReturn&&round==2)put("success",false).put("message","peer refused decision")})}
                "articles"->{
                    val items=LibrarySyncPayload.parseChunkedArticles(q)
                    if(items.isNotEmpty()) {
                        val p=items.single();val body=ArticleSyncBody.rebuildBody(null,p)
                        watch=p.article.copy(contentHtml=body.first,contentText=body.second)
                    }
                    val responses=LibrarySyncPayload.buildChunkedResponseFrames("watch",if(round==1)listOf(watch) else emptyList(),emptyList(),useBatches=false)
                    responses.forEach(::enqueue)
                }
                else->error("unexpected phase $phase")
            }
        }}
        val input=object:InputStream(){override fun read()=if(incoming.isEmpty())-1 else incoming.removeFirst().toInt() and 255}
        val device=PhoneBluetoothWatchDevice("watch","11:22:33:44:55:66",1,"watch",supportsPersistentSession=true)
        return PhoneSyncSession(PhoneBluetoothSyncClient(context,log),device,"phone",PhoneSyncSession.Transport(input,output,"test",false,{}),true,false,false)
    }
    private suspend fun runSync()=session().use { s->manager.syncLibrary(syncSession=s,syncAuxiliary=false,booksPrepared=true,
        binding=BleSyncBindingSnapshot(s.device.address,null,s,JSONObject().put("supportsRssCanonicalIds",false)),
        resolveDeleteConflicts={ conflicts->prompts++;conflicts.associate{it.articleId to PhoneSyncConflictResolution.KEEP_WATCH}}) }
    private suspend fun seed(){db.phoneArticleDao().upsert(watch.copy(sourceDeviceId="phone",updatedAt=100,favoriteSaved=false,favoriteChangedAt=100,deleted=true,deletedAt=100))}
    @Test fun selectingWatchBodyPersistsThenReturnsNewDecisionAndWaitsForAppliedAck()=runBlocking<Unit>{
        seed();runSync()
        assertEquals(1,prompts);assertEquals(2,round)
        assertFalse(watch.deleted);assertTrue(watch.updatedAt>9_000_000_000_000L)
        assertEquals("watch-body",db.phoneArticleDao().getById("a")!!.contentText)
        assertEquals(db.phoneArticleDao().getById("a")!!.updatedAt,watch.updatedAt)
        assertEquals(2,frames.count{it.optString("action")=="ack"})
        assertTrue(repo.findDeleteConflicts(LibrarySyncPayload.parseArticleManifest(LibrarySyncPayload.buildManifestResponse("watch",listOf(watch)))).isEmpty())
    }
    @Test fun refusedDecisionReturnCannotReportSyncSuccess()=runBlocking<Unit>{
        seed();refuseReturn=true
        val error=runCatching{runSync()}.exceptionOrNull()
        assertNotNull(error);assertTrue(error!!.message.orEmpty().contains("peer refused"))
        assertFalse(db.phoneArticleDao().getById("a")!!.deleted)
        assertNotNull(db.appMetaDao().getString("article_conflict_decision_v1:a"))
    }
    @Test fun lostReturnAckKeepsDurableDecisionAndRetryDoesNotAskAgain()=runBlocking<Unit>{
        seed();loseReturnAck=true;assertTrue(runCatching{runSync()}.isFailure)
        val clock=watch.updatedAt
        loseReturnAck=false;runSync()
        assertEquals(1,prompts);assertEquals(clock,db.phoneArticleDao().getById("a")!!.updatedAt)
    }
}
