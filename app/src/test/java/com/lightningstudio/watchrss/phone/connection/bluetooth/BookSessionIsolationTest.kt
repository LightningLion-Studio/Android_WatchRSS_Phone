package com.lightningstudio.watchrss.phone.connection.bluetooth

import androidx.room.Room
import com.lightningstudio.watchrss.phone.cloud.CloudSnapshotCodec
import com.lightningstudio.watchrss.phone.data.book.ImportedBookStore
import com.lightningstudio.watchrss.phone.data.ble.BleDomainStore
import com.lightningstudio.watchrss.phone.data.db.*
import com.lightningstudio.watchrss.phone.data.log.BluetoothDebugLog
import com.lightningstudio.watchrss.phone.data.note.NoteRepository
import com.lightningstudio.watchrss.phone.data.reader.ReaderPresetRepository
import com.lightningstudio.watchrss.phone.data.repo.PhoneCompanionRepository
import kotlinx.coroutines.*
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.*
import java.util.Base64

/** The production manager, session framing, ACKs and SQLite; no radios or credentials. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class BookSessionIsolationTest {
    private val context=RuntimeEnvironment.getApplication()
    private val root=File(context.cacheDir,"book-session-${System.nanoTime()}").apply{mkdirs()}
    private val db=Room.inMemoryDatabaseBuilder(context,PhoneCompanionDatabase::class.java).allowMainThreadQueries().build()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    private val domains=BleDomainStore(db,root)
    private val books=ImportedBookStore(db,File(root,"books"),null,"phone")
    private val repo=PhoneCompanionRepository(db.phoneSavedItemDao(),db.phoneArticleDao(),db.phoneRssSourceDao(),"phone",db.syncChangeLogDao(),db.syncPeerStateDao(),appMetaDao=db.appMetaDao(),books=books,bleDomains=domains)
    private val log=BluetoothDebugLog(context)
    private val device=PhoneBluetoothWatchDevice("watch","11:22:33:44:55:66",1,"watch",supportsPersistentSession=true)
    private val frames=mutableListOf<JSONObject>()
    private val fixture=JSONObject(requireNotNull(javaClass.getResource("/book/cli-single-txt-book.json")).readText())
    private fun state()=JSONObject().put("bookId",fixture.getString("bookId")).put("fingerprint",fixture.getString("fingerprint")).put("title","CLI book").put("chapterCount",1)
        .put("sourceUrl",fixture.getString("sourceUrl")).put("kind","txt").put("deletedChapters",JSONArray()).put("deletionClock",0).put("deleted",false).put("deletedAt",0).put("updatedAt",1).put("sourceDeviceId","cli").put("contentAvailable",true)
    private fun reply(action:String,phase:String)=JSONObject().put("success",true).put("action",action).put("phase",phase).put("version",if(action=="syncDomains")2 else if(action=="syncBooks")1 else 14).put("deviceId","watch")
    @After fun close(){scope.cancel();db.close();root.deleteRecursively()}
    private suspend fun runSession(invalid:Boolean):PhoneBluetoothSyncResult {
        books.manifests()
        repo.markLibrarySyncSuccess("watch",0,0,14,true,true,domains.libraryDefinition())
        val initial=domains.roots("account");val remote=BleDomainStore.DOMAINS.associateWith{"b".repeat(64)}
        domains.publish("scope","watch",initial.mapValues{it.value to remote.getValue(it.key)})
        for(domain in listOf("books","library","notes"))db.openHelper.writableDatabase.execSQL("DELETE FROM ble_fast_receipts WHERE domain=?",arrayOf(domain))
        val bytes=fixture.getString("packageUtf8").let{if(invalid)it.replace(fixture.getString("sourceUrl"),"javascript:bad")else it}.toByteArray()
        val incoming=ArrayDeque<Byte>()
        val output=object:ByteArrayOutputStream() {
            override fun flush() {
                if(size()==0)return
                val q=BluetoothSyncProtocol.readFrame(ByteArrayInputStream(toByteArray()));reset();frames+=q
                val action=q.optString("action");val phase=q.optString("phase");if(action=="ack")return
                val a=when(action) {
                    "syncLibrary"->when(phase) {
                        "probe"->reply(action,phase).put("account",JSONObject().put("bound",true).put("userIdHash",CloudSnapshotCodec.sha256("owner".toByteArray())))
                            .put("supportsPersistentSession",true).put("persistentSessionAccepted",true).advertiseBleSyncV2()
                        "cursor"->LibrarySyncPayload.buildCursorResponse("watch",LibrarySyncCursor(0,0,0))
                        "manifest"->LibrarySyncPayload.buildManifestRequestFromEntries("watch",emptyList(),emptyList()).put("success",true)
                        "articles","complete"->reply(action,"complete").put("articles",JSONArray())
                        else->error("unexpected library phase $phase")
                    }.put("supportsReaderPresets",false).put("supportsNoteManifestV2",false).put("supportsNoteAssetInventory",false).put("supportsBookReadingDeltaV1",phase=="probe")
                    "syncDomains"->reply(action,phase).put("windowId","window").put("roots",JSONObject(remote)).put("projectionVersions",JSONObject().apply{domains.projectionVersions().forEach{(d,p)->put(d,p.json())}}).apply {
                        if(phase=="summary")put("skipDomains",JSONArray(q.getJSONObject("known").keys().asSequence().toList()))
                        else {
                            val done=q.getJSONArray("completedDomains").let{a->(0 until a.length()).map{a.getString(it)}}
                            val stable=done.filter{initial[it]==q.getJSONObject("roots").optString(it)}
                            put("committedDomains",JSONArray(stable));put("retryDomains",JSONArray(done-stable.toSet()))
                        }
                    }
                    "syncBooks"->when(phase) {
                        "manifest"->reply(action,phase).put("books",JSONArray().put(state())).put("missingBookIds",JSONArray())
                        "pull"->reply(action,"body").put("bookId",fixture.getString("bookId")).put("fingerprint",fixture.getString("fingerprint")).put("packageSha256",CloudSnapshotCodec.sha256(bytes))
                            .put("byteCount",bytes.size).put("chunkIndex",0).put("chunkCount",1).put("dataBase64",Base64.getEncoder().encodeToString(bytes))
                        "reading"->reply(action,phase).put("states",JSONArray()).put("hasMore",false).put("nextAfter",0)
                        else->error("unexpected book phase")
                    }
                    "syncNotes"->NoteSyncPayload.manifest("watch",emptyList()).put("success",true)
                    "syncSession"->reply(action,phase)
                    else->error("unexpected action $action/$phase")
                }
                ByteArrayOutputStream().also{BluetoothSyncProtocol.writeFrame(it,a)}.toByteArray().forEach{incoming.addLast(it)}
            }
        }
        val input=object:InputStream(){override fun read()=if(incoming.isEmpty())-1 else incoming.removeFirst().toInt() and 255}
        val session=PhoneSyncSession(PhoneBluetoothSyncClient(context,log),device,"phone",PhoneSyncSession.Transport(input,output,"test",false,{}),true,false,false)
        val manager=PhoneBluetoothSyncManager(context,repo,NoteRepository(db.noteDao(),"phone"),ReaderPresetRepository(context,db,db.readerPresetDao(),"phone",scope),PhoneLlmTokenUsageRepository(db.llmTokenUsageDao()),"phone",log,
            currentPhoneUserId={"owner"},domainScope={"scope"},accountDomainWitness={"account"})
        manager.reconnectDelay={};manager.reconnectSession={_,_->error("must not reconnect for invalid items")}
        return manager.syncAll(device,reusableSession=session)
    }
    @Test fun invalidBookContinuesLibraryNotesAndFinalAckWithoutABooksDomainReceipt()=runBlocking<Unit> {
        val result=runSession(true)
        assertEquals(1,result.bookSyncIssues.size);assertNotNull(result.bookSyncWarning)
        assertTrue(frames.any{it.optString("action")=="syncLibrary"&&it.optString("phase")=="cursor"})
        assertTrue(frames.any{it.optString("action")=="syncNotes"})
        assertTrue(frames.any{it.optString("action")=="syncSession"&&it.optString("phase")=="complete"})
        assertFalse(frames.any{it.optString("phase")=="abort"})
        val commit=frames.single{it.optString("action")=="syncDomains"&&it.optString("phase")=="commit"}
        assertFalse(commit.getJSONArray("completedDomains").toString().contains("\"books\""))
        assertFalse(domains.receipts("scope","watch").containsKey("books"));assertTrue(db.bookDao().chapters().isEmpty())
        assertTrue(log.snapshot().contains("sync.book.isolated"));assertFalse(log.snapshot().contains("javascript:"))
    }
    @Test fun realCliPackageContinuesAllPhasesAndHasNoBookWarning()=runBlocking<Unit> {
        val result=runSession(false)
        assertTrue(result.bookSyncIssues.isEmpty());assertNull(result.bookSyncWarning)
        assertEquals(fixture.getJSONObject("chapter").getString("contentText"),db.phoneArticleDao().getAllForSync().single().contentText)
        assertTrue(frames.any{it.optString("action")=="syncNotes"})
        assertTrue(frames.any{it.optString("action")=="syncSession"&&it.optString("phase")=="complete"})
    }
}
