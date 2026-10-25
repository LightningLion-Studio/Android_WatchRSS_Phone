package com.lightningstudio.watchrss.phone.connection.bluetooth

import android.content.Context
import androidx.room.Room
import com.lightningstudio.watchrss.phone.cloud.CloudContentPolicy
import com.lightningstudio.watchrss.phone.cloud.CloudSnapshotCodec
import com.lightningstudio.watchrss.phone.data.db.*
import com.lightningstudio.watchrss.phone.data.importer.WebArticleImporter
import com.lightningstudio.watchrss.phone.data.log.BluetoothDebugLog
import com.lightningstudio.watchrss.phone.data.note.*
import com.lightningstudio.watchrss.phone.data.reader.ReaderPresetRepository
import com.lightningstudio.watchrss.phone.data.repo.PhoneCompanionRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.*

/** Uses the production RFCOMM frame codec/client and Room, without radios or cloud credentials. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class BleSyncV2RegressionTest {
    private val context:Context=RuntimeEnvironment.getApplication()
    private val db=Room.inMemoryDatabaseBuilder(context,PhoneCompanionDatabase::class.java).allowMainThreadQueries().build()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    private val notes=NoteRepository(db.noteDao(),"phone",now={100})
    private val repo=PhoneCompanionRepository(db.phoneSavedItemDao(),db.phoneArticleDao(),db.phoneRssSourceDao(),"phone",
        syncChangeLogDao=db.syncChangeLogDao(),syncPeerStateDao=db.syncPeerStateDao(),appMetaDao=db.appMetaDao())
    private val tokens=PhoneLlmTokenUsageRepository(db.llmTokenUsageDao())
    private val reader=ReaderPresetRepository(context,db,db.readerPresetDao(),"phone",scope)
    private val log=BluetoothDebugLog(context)
    private val device=PhoneBluetoothWatchDevice("watch","11:22:33:44:55:66",1,"watch",supportsPersistentSession=true)
    private var cloudCalls=0
    private var transportClosed=false
    private var throwCloud=false
    private val manager=PhoneBluetoothSyncManager(context,repo,notes,reader,tokens,"phone",log,
        buildAccountSyncRequest={_,_,_->JSONObject().put("action","syncAccount")},canSyncAccount={true},
        onLibrarySyncCompleted={assertTrue(transportClosed);cloudCalls++;if(throwCloud)error("cloud offline")},currentPhoneUserId={"owner"})
    private val outputs=mutableListOf<ByteArrayOutputStream>()
    private val responseWireBytes=mutableListOf<Long>()
    private fun ok(action:String,phase:String?=null)=JSONObject().put("success",true).put("action",action).put("version",14).put("deviceId","watch").apply { phase?.let { put("phase",it) } }
    private fun noteResponse(phase:String?,values:List<NoteEntity> = emptyList(),applied:Int=0)=NoteSyncPayload.manifest("watch",values).put("success",true).put("applied",applied).apply {phase?.let{put("phase",it)}}
    private fun session(vararg responses:JSONObject):PhoneSyncSession {
        transportClosed=false
        val input=ByteArrayOutputStream().also { out-> responses.forEach { BluetoothSyncProtocol.writeFrame(out,it) } }
        responseWireBytes+=input.size().toLong()
        val output=ByteArrayOutputStream().also {outputs+=it}
        return PhoneSyncSession(PhoneBluetoothSyncClient(context,log),device,"phone",
            PhoneSyncSession.Transport(ByteArrayInputStream(input.toByteArray()),output,"test",false,{transportClosed=true}),true,false,false)
    }
    private fun frames(index:Int=outputs.lastIndex):List<JSONObject> {
        val input=ByteArrayInputStream(outputs[index].toByteArray());return buildList {while(input.available()>0)add(BluetoothSyncProtocol.readFrame(input))}
    }
    private fun payloads(index:Int=outputs.lastIndex)=frames(index).filter {it.optString("action")!="ack"}
    private fun row(id:String="canonical",body:String="正文")=PhoneArticleEntity(id,"watch","https://entry.test/item","title","site","summary",null,body,null,
        CloudSnapshotCodec.sha256(body.toByteArray()),1,10,false,0,0,"https://feed.test/rss","feed",true,10,0,false,0,0,false,0)
    private fun usage(epoch:String,next:Long,ids:List<Long> = emptyList(),more:Boolean=false,reset:Boolean=false)=ok("syncLlmTokenUsage")
        .put("usageEpoch",epoch).put("nextAfterId",next).put("hasMore",more).put("reset",reset).put("count",ids.size).put("records",JSONArray().apply {
            ids.forEach {put(JSONObject().put("id",it).put("createdAt",it).put("model","test").put("totalTokens",3))}
        })
    @After fun close(){scope.cancel();db.close();File(context.filesDir,"notes/assets/pic.png").delete()}

    @Test fun identicalNotesAndAssetsSendNoBodiesOrChunksAndFallbackKeepsFullEnvelope()=runBlocking<Unit> {
        val note=notes.save(noteId="n",title="笔记",markdown="![图](assets/pic.png)\n"+"same content\n".repeat(10000))
        val file=File(context.filesDir,"notes/assets/pic.png").apply {requireNotNull(parentFile).mkdirs();writeBytes(ByteArray(128*1024){it.toByte()})}
        notes.registerAsset(NoteAssetEntity("a","n",CloudSnapshotCodec.sha256(file.readBytes()),"pic","image/png",file.length(),"pic.png",false,1))
        val asset=NoteAssetHashCache().inventory(file,"pic.png")!!
        val inventory=ok("syncNotes","manifest").put("noteManifest",NoteSyncPayload.summaries(listOf(note))).put("assetManifest",JSONArray().put(asset.json()))
        val optimizedSession=session(inventory,noteResponse("delta"))
        val optimized=manager.syncNotes(device.address,"test",optimizedSession,BleSyncCapabilities(noteManifest=true,noteAssets=true))
        assertEquals(0,optimized.sent);assertEquals(0,optimized.appliedOnPhone)
        val optimizedPayloads=payloads();assertEquals(listOf("manifest","delta"),optimizedPayloads.map{it.getString("phase")})
        assertFalse(optimizedPayloads[0].has("notes"));assertEquals(0,optimizedPayloads[1].getJSONArray("notes").length())
        optimizedSession.close()
        val legacySession=session(noteResponse(null,listOf(note)),ok("syncNoteAsset").put("alreadyPresent",true))
        manager.syncNotes(device.address,"legacy",legacySession,BleSyncCapabilities())
        val old=payloads();assertFalse(old[0].has("phase"));assertEquals(1,old[0].getJSONArray("notes").length())
        assertEquals(1,old.count{it.optString("action")=="syncNoteAsset"})
        val before=old.sumOf(BluetoothSyncProtocol::wireSize);val after=optimizedPayloads.sumOf(BluetoothSyncProtocol::wireSize)
        assertTrue("$before -> $after",after<before/20)
        println("BLE_V2_UNCHANGED_NOTES phoneWireBytes=$before->$after noteBodies=1->0 assetChunks=1->0")
        legacySession.close()
    }
    @Test fun changedAssetHashOrSizeIsTransferredAndMissingAssetInventoryDoesNotSkip()=runBlocking<Unit> {
        val n=notes.save(noteId="n",title="note",markdown="![图](assets/pic.png)")
        File(context.filesDir,"notes/assets/pic.png").apply{requireNotNull(parentFile).mkdirs();writeBytes(ByteArray(12){1})}
        for(remote in listOf(JSONArray(),JSONArray().put(NoteAssetInventoryEntry("pic.png","0".repeat(64),12).json()))) {
            val s=session(ok("syncNotes","manifest").put("noteManifest",NoteSyncPayload.summaries(listOf(n))).put("assetManifest",remote),noteResponse("delta"),ok("syncNoteAsset"))
            manager.syncNotes(device.address,"asset",s,BleSyncCapabilities(noteManifest=true,noteAssets=true))
            assertEquals(1,payloads().count{it.getString("action")=="syncNoteAsset"});s.close()
        }
    }
    @Test fun changedNoteMetadataAndTombstonesAreIncludedEvenWithSameContentHash()=runBlocking<Unit> {
        val n=notes.save(noteId="n",title="original",markdown="same")
        assertTrue(NoteSyncPayload.differingNotes(listOf(n),NoteSyncPayload.summaries(listOf(n))).isEmpty())
        for(changed in listOf(n.copy(title="renamed"),n.copy(folderId="folder"),n.copy(pinned=true),n.copy(deleted=true,deletedAt=101)))
            assertEquals(listOf(changed),NoteSyncPayload.differingNotes(listOf(changed),NoteSyncPayload.summaries(listOf(n))))
        notes.applyRemote(n.copy(folderId=""),"phone");assertEquals("",notes.note("n")!!.folderId)
        notes.applyRemote(n,"phone");assertEquals("",notes.note("n")!!.folderId)
        val s=session(ok("syncNotes","manifest").put("noteManifest",NoteSyncPayload.summaries(listOf(n))),noteResponse("delta",listOf(n.copy(title="new",updatedAt=200,modifiedBy="watch"))))
        assertEquals(1,manager.syncNotes(device.address,"note",s,BleSyncCapabilities(noteManifest=true)).appliedOnPhone)
        assertEquals("new",notes.note("n")!!.title);s.close()
    }
    @Test fun malformedOrDuplicateNoteManifestFailsInsteadOfClaimingPeerHasBody()=runBlocking<Unit> {
        val n=notes.save(noteId="n",title="n",markdown="text")
        val bad=NoteSyncPayload.summaries(listOf(n)).apply{put(getJSONObject(0))}
        val s=session(ok("syncNotes","manifest").put("noteManifest",bad))
        assertTrue(runCatching{manager.syncNotes(device.address,"bad",s,BleSyncCapabilities(noteManifest=true))}.isFailure)
        assertEquals(1,payloads().size);s.close()
    }
    @Test fun hashingIsCachedAcrossInstancesAndInvalidatesOnMtimeSizeOrPathChange() {
        var reads=0;val prefs=context.getSharedPreferences("hash-test",0).also{it.edit().clear().commit()}
        val dir=File(context.cacheDir,"hash-test").apply{mkdirs()};val f=File(dir,"one").apply{writeText("one")}
        fun cache()=NoteAssetHashCache(prefs){reads++;CloudSnapshotCodec.sha256(it.readBytes())}
        val c=cache();val first=c.inventory(f,"one.png");assertEquals(first,c.inventory(f,"one.png"));assertEquals(first,cache().inventory(f,"one.png"));assertEquals(1,reads)
        f.writeText("longer");c.inventory(f,"one.png");assertEquals(2,reads)
        assertTrue(f.setLastModified(f.lastModified()+2000));c.inventory(f,"one.png");assertEquals(3,reads)
        val renamed=File(dir,"two");assertTrue(f.renameTo(renamed));c.inventory(renamed,"one.png");assertEquals(4,reads)
        assertNull(c.inventory(f,"one.png"));dir.deleteRecursively()
    }
    @Test fun inventoryAndCapabilitiesRejectCoercedAndUnsafeValues() {
        assertEquals(BleSyncCapabilities(),BleSyncCapabilities.read(JSONObject()))
        assertFalse(BleSyncCapabilities.read(JSONObject().put("supportsNoteManifestV2","true")).noteManifest)
        assertTrue(BleSyncCapabilities(noteManifest=true).withDeclarations(JSONObject()).noteManifest)
        assertFalse(BleSyncCapabilities(noteManifest=true).withDeclarations(JSONObject().put("supportsNoteManifestV2",false)).noteManifest)
        for(value in listOf(-1L,1.5,"12"))assertTrue(NoteAssetInventoryEntry.read(JSONArray().put(JSONObject().put("storageKey","pic.png").put("sha256","a".repeat(64)).put("size",value))).isEmpty())
        assertTrue(NoteAssetInventoryEntry.read(JSONArray().put(NoteAssetInventoryEntry("../x","a".repeat(64),1).json())).isEmpty())
    }
    @Test fun tokenPagingReplaysEmptyPollAndEpochResetAreIdempotentAndPeerScoped()=runBlocking<Unit> {
        assertEquals(2,tokens.applyIncremental("one",usage("a",2,listOf(1,2))))
        assertEquals(0,tokens.applyIncremental("one",usage("a",2,listOf(1,2))))
        assertEquals(0,tokens.applyIncremental("one",usage("a",2)))
        assertEquals(1,tokens.applyIncremental("two",usage("a",1,listOf(1))))
        assertTrue(runCatching{tokens.applyIncremental("one",usage("b",1,listOf(1)))}.isFailure)
        assertEquals(1,tokens.applyIncremental("one",usage("b",1,listOf(1),reset=true)))
        assertEquals(4,tokens.observeRecent().first().size)
        assertTrue(runCatching{tokens.applyIncremental("one",usage("b",2,listOf(2,2)))}.isFailure)
        assertEquals(1L,tokens.cursor("one")!!.afterId)
        tokens.clearAll();assertNull(tokens.cursor("one"));assertTrue(tokens.observeRecent().first().isEmpty())
    }
    @Test fun tokenManagerUsesCursorPagesAndLegacyFallbackDoesNotSendCursor()=runBlocking<Unit> {
        val binding=ok("syncLibrary").put("supportsTokenUsageCursor",true)
        val s=session(usage("epoch",1,listOf(1),more=true),usage("epoch",2,listOf(2)))
        manager.syncLlmTokenUsage(device,s,BleSyncBindingSnapshot(device.address,"owner",s,binding))
        assertEquals(listOf(0L,1L),payloads().map{it.getLong("afterId")});assertFalse(payloads()[0].has("usageEpoch"));assertEquals("epoch",payloads()[1].getString("usageEpoch"));s.close()
        val old=session(ok("syncLlmTokenUsage").put("records",JSONArray().put(JSONObject().put("totalTokens",9))))
        manager.syncLlmTokenUsage(device,old,BleSyncBindingSnapshot(device.address,"owner",old,ok("syncLibrary")))
        assertFalse(payloads().single().has("afterId"));assertEquals(3,tokens.observeRecent().first().size);old.close()
    }
    @Test fun canonicalAndLegacyArticleIdsRoundTripWithoutChangingBodyOrDuplicatingTrueGuids() {
        val canonical=row("a".repeat(64));val legacy=WebArticleImporter.stableArticleId(canonical.url)
        val old=BluetoothArticleIdentityProjection(listOf(canonical),mapOf(legacy to canonical.articleId),false)
        assertEquals(legacy,old.outgoing(canonical).articleId);assertEquals(canonical.articleId,old.incoming(old.outgoing(canonical)).articleId)
        assertEquals(canonical.contentText,old.outgoing(canonical).contentText)
        val modern=BluetoothArticleIdentityProjection(listOf(canonical),mapOf(legacy to canonical.articleId),true)
        val entry=ArticleSyncBody.metadataFor(canonical).let{ ArticleSyncManifestEntry(canonical.articleId,"watch",canonical.contentHash,10,0,10,0,0,bodyHash=it.bodyHash,bodyByteCount=it.bodyByteCount,chunkSize=it.chunkSize,chunkHashes=it.chunkHashes,metadataHash=it.metadataHash) }
        assertEquals(listOf(legacy),modern.manifest(entry).legacyArticleIds)
        val second=canonical.copy(articleId="b".repeat(64))
        for(mode in listOf(true,false)) {
            val projection=BluetoothArticleIdentityProjection(listOf(canonical,second),emptyMap(),mode)
            assertNotEquals(projection.wire(canonical.articleId),projection.wire(second.articleId))
            assertTrue(projection.manifest(entry).legacyArticleIds.isEmpty())
        }
    }
    @Test fun duplicateLegacyFavoriteMergesNewerSavedReadingAndTombstoneStateOnlyOnce()=runBlocking<Unit> {
        val canonical=row("a".repeat(64));val legacy=canonical.copy(articleId=WebArticleImporter.stableArticleId(canonical.url),updatedAt=20,favoriteSaved=false,favoriteChangedAt=20,watchLaterSaved=true,watchLaterChangedAt=20,readingProgress=.5f,readingPositionChangedAt=20)
        db.phoneArticleDao().upsertAll(listOf(canonical,legacy))
        assertEquals(1,repo.repairBluetoothRssDuplicates());assertEquals(0,repo.repairBluetoothRssDuplicates())
        val saved=db.phoneArticleDao().getById(canonical.articleId)!!;assertFalse(saved.favoriteSaved);assertTrue(saved.watchLaterSaved);assertEquals(.5f,saved.readingProgress)
        assertNull(db.phoneArticleDao().getById(legacy.articleId));assertEquals(canonical.articleId,repo.bluetoothIdentityProjection(false).local(legacy.articleId))
    }
    @Test fun identicalArticleAndSourceReceiptsDoNotWriteOrCreateEchoAndLocalEditEscapesSuppression()=runBlocking<Unit> {
        val remote=row();assertEquals(1,repo.mergeArticlesFromSync(listOf(remote)))
        val before=db.syncChangeLogDao().maxSeq();assertEquals(0,repo.mergeArticlesFromSync(listOf(remote)));assertEquals(before,db.syncChangeLogDao().maxSeq())
        val full=repo.prepareLibrarySyncWindow("watch");repo.rememberBluetoothReceipts("watch",full.fullArticleManifest)
        repo.markLibrarySyncSuccess("watch",0,0,14,true)
        assertTrue(repo.prepareLibrarySyncWindow("watch",0).articleManifest.isEmpty())
        assertEquals(1,repo.mergeArticlesFromSync(listOf(remote.copy(title="edited",updatedAt=30,sourceDeviceId="phone"))))
        assertEquals(1,repo.prepareLibrarySyncWindow("watch",0).articleManifest.size)
    }
    @Test fun fullMetadataOnlyRequiresNewCapabilityAndVerifiedAvailableLocalBody()=runBlocking<Unit> {
        repo.mergeArticlesFromSync(listOf(row().copy(rssSourceUrl=null,independentSaved=true,independentChangedAt=10)))
        val local=repo.getArticleManifestsForSync().single().copy(bodySyncMode=ARTICLE_BODY_SYNC_MODE_FULL)
        val remote=local.copy(metadataHash="different",updatedAt=20)
        fun request(modern:Boolean,l:ArticleSyncManifestEntry=local)=LibrarySyncPayload.buildBodyRequestsForRemoteArticles(listOf(l),listOf(remote),supportsMetadataOnlyArticles=true,supportsFullMetadataOnlyArticles=modern).single()
        assertTrue(request(true).metadataOnly);assertTrue(request(true).chunkIndexes.isEmpty());assertFalse(request(false).metadataOnly)
        assertFalse(request(true,local.copy(bodyAvailable=false)).metadataOnly)
        assertFalse(request(true,local.copy(bodyHash="x",chunkHashes=listOf("x"))).metadataOnly)
    }
    @Test fun localBleBookkeepingIsExcludedFromCloudPrivateSettings() {
        for(key in listOf("ble_identity_mode:peer","ble_echo_v2:peer:article","ble_repair_watermark_v2","ble_rss_identity_dedup_v2"))assertFalse(CloudContentPolicy.privateAppMeta(key))
        assertTrue(CloudContentPolicy.privateAppMeta("reader.setting"))
    }
    @Test fun noteStateHashesMatchSharedUtf8LengthPrefixVectors() {
        val vectors=JSONObject(requireNotNull(javaClass.getResource("/ble/ble-note-state-v2.json")).readText()).getJSONArray("cases")
        for(i in 0 until vectors.length()) {
            val vector=vectors.getJSONObject(i)
            val n=NoteSyncPayload.decodeNotes(JSONObject().put("action","syncNotes").put("notes",JSONArray().put(vector.getJSONObject("note")))).single()
            assertEquals(vector.getString("name"),vector.getString("stateHash"),NoteSyncPayload.stateHash(n))
        }
    }
    @Test fun noChangeNotesConflictsSourcesAndLegacyUsageDoNotWriteBusinessRows()=runBlocking<Unit> {
        db.openHelper.writableDatabase.execSQL("CREATE TABLE write_counter(n INTEGER NOT NULL)")
        db.openHelper.writableDatabase.execSQL("INSERT INTO write_counter VALUES(0)")
        for(table in listOf("notes","note_conflicts","phone_rss_sources","llm_token_usage"))for(op in listOf("INSERT","UPDATE","DELETE"))
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER count_${table}_$op AFTER $op ON $table BEGIN UPDATE write_counter SET n=n+1; END")
        fun writes()=db.openHelper.readableDatabase.query("SELECT n FROM write_counter").use{it.moveToFirst();it.getInt(0)}
        val n=notes.save(noteId="n",title="n",markdown="base")
        val src=PhoneRssSourceEntity("https://feed.test/rss","watch","feed","",null,null,1,1,1,false,false,0)
        assertEquals(1,repo.mergeRssSourcesFromSync(listOf(src)))
        tokens.replaceRecords(listOf(JSONObject().put("totalTokens",1)))
        val count=writes();notes.applyRemote(n,"watch");assertEquals(0,repo.mergeRssSourcesFromSync(listOf(src)));tokens.replaceRecords(listOf(JSONObject().put("totalTokens",1)))
        assertEquals(count,writes())
        db.noteDao().upsertNotes(listOf(n.copy(markdown="local",contentHash=MarkdownNoteCodec.sha256("local"),updatedAt=200)))
        val remote=n.copy(markdown="remote",contentHash=MarkdownNoteCodec.sha256("remote"),updatedAt=201)
        assertTrue(notes.applyRemote(remote,"watch") is MarkdownMergeResult.Conflict)
        val afterConflict=writes();notes.applyRemote(remote,"watch");assertEquals(afterConflict,writes())
    }
    @Test fun unchangedExternalizedBodyAndChunkedReceiptsAvoidFileWritesAndRetainValidatedEncoding()=runBlocking<Unit> {
        val store=com.lightningstudio.watchrss.phone.data.local.FileArticleContentStore(context)
        var fileWrites=0
        val counting=object:com.lightningstudio.watchrss.phone.data.local.ArticleContentStore by store {
            override fun storeText(articleId:String,text:String):String {fileWrites++;return store.storeText(articleId,text)}
        }
        val repository=PhoneCompanionRepository(db.phoneSavedItemDao(),db.phoneArticleDao(),db.phoneRssSourceDao(),"phone",articleContentStore=counting,
            syncChangeLogDao=db.syncChangeLogDao(),syncPeerStateDao=db.syncPeerStateDao(),appMetaDao=db.appMetaDao())
        val article=row(body="original paragraph\n".repeat(40000)).copy(url="https://watchrss.local/import-content/txt/chapter",rssSourceUrl="https://watchrss.local/import-content",independentSaved=true,independentChangedAt=10)
        repository.mergeArticlesFromSync(listOf(article));assertTrue(fileWrites>0)
        val firstWrites=fileWrites;val firstLog=db.syncChangeLogDao().maxSeq()
        assertEquals(0,repository.mergeArticlesFromSync(listOf(article)));assertEquals(firstWrites,fileWrites);assertEquals(firstLog,db.syncChangeLogDao().maxSeq())
        val wire=LibrarySyncPayload.buildChunkedResponseFrames("watch",listOf(article),emptyList(),useBatches=false).single()
        val incoming=LibrarySyncPayload.parseChunkedArticles(wire)
        assertEquals(0,repository.mergeChunkedArticlesFromSync(incoming));assertEquals(firstWrites,fileWrites)
        val changed=article.copy(title="new title",updatedAt=20)
        assertEquals(1,repository.mergeArticlesFromSync(listOf(changed)));assertEquals(firstWrites,fileWrites)
        val descriptor=repository.getArticleManifestsForSync().single();assertEquals(ArticleSyncBody.metadataFor(article).bodyHash,descriptor.bodyHash)
    }
    @Test fun incrementalRepairOnlyQueriesRowsBeyondSuccessfulScanWatermarkAndFullStillRepairsAll()=runBlocking<Unit> {
        val scanned=mutableListOf<List<String>>()
        val changes=object:SyncChangeLogDao by db.syncChangeLogDao() {
            override suspend fun maxChangedAtByEntityIds(kind:String,entityIds:List<String>):List<SyncChangeLogEntityState> {
                scanned+=entityIds;return db.syncChangeLogDao().maxChangedAtByEntityIds(kind,entityIds)
            }
        }
        val repository=PhoneCompanionRepository(db.phoneSavedItemDao(),db.phoneArticleDao(),db.phoneRssSourceDao(),"phone",syncChangeLogDao=changes,syncPeerStateDao=db.syncPeerStateDao(),appMetaDao=db.appMetaDao())
        db.phoneArticleDao().upsertAll(listOf(row("a"),row("b").copy(updatedAt=20)))
        val first=repository.prepareLibrarySyncWindow("watch");assertEquals(setOf("a","b"),scanned.single().toSet())
        repository.markLibrarySyncSuccess("watch",first.toSeqInclusive,0,14,true)
        scanned.clear();repository.prepareLibrarySyncWindow("watch",first.toSeqInclusive);assertTrue(scanned.isEmpty())
        db.phoneArticleDao().upsert(row("b").copy(updatedAt=30))
        repository.prepareLibrarySyncWindow("watch",first.toSeqInclusive);assertEquals(listOf(listOf("b")),scanned)
        scanned.clear();repository.prepareLibrarySyncWindow("watch",0);assertEquals(setOf("a","b"),scanned.single().toSet())
    }
    @Test fun oldAliasInRemoteManifestRekeysBeforeMergeAndRejectsAmbiguousClaims()=runBlocking<Unit> {
        val canonical="c".repeat(64);val old=WebArticleImporter.stableArticleId(row().url)
        db.phoneArticleDao().upsert(row(old))
        val entry=ArticleSyncManifestEntry(canonical,"watch","",10,0,10,0,0,legacyArticleIds=listOf(old))
        repo.acceptBluetoothAliases(listOf(entry));assertNull(db.phoneArticleDao().getById(old));assertNotNull(db.phoneArticleDao().getById(canonical))
        repo.acceptBluetoothAliases(listOf(entry));assertEquals(1,db.phoneArticleDao().getAllMetadataForSync().size)
        assertTrue(runCatching{repo.acceptBluetoothAliases(listOf(entry,entry.copy(articleId="d".repeat(64))))}.isFailure)
        val projection=repo.bluetoothIdentityProjection(true);val articles=repo.getArticlesForSync()
        val wire=LibrarySyncPayload.buildChunkedArticleRequestFrames("phone",articles,emptyList(),emptyList(),true,projection.outgoingAliases(articles))
        assertEquals(old,wire.first().getJSONArray("articles").getJSONObject(0).getJSONArray("legacyArticleIds").getString(0))
    }
    @Test fun fullMetadataOnlyReducesActualArticleWireBytesAndStillAppliesMetadata()=runBlocking<Unit> {
        val article=row(body=(1..12000).joinToString("\n"){"第${it}段内容 ${it*17}"}).copy(rssSourceUrl=null,independentSaved=true,independentChangedAt=10)
        repo.mergeArticlesFromSync(listOf(article))
        val local=repo.getArticleManifestsForSync().single();val remote=local.copy(metadataHash="different",updatedAt=20,bodySyncMode=ARTICLE_BODY_SYNC_MODE_FULL)
        fun request(modern:Boolean)=LibrarySyncPayload.buildBodyRequestsForRemoteArticles(listOf(local),listOf(remote),supportsMetadataOnlyArticles=true,supportsFullMetadataOnlyArticles=modern)
        val changed=article.copy(title="edited title",updatedAt=20)
        val old=LibrarySyncPayload.buildChunkedResponseFrames("watch",listOf(changed),request(false),useBatches=true)
        val modern=LibrarySyncPayload.buildChunkedResponseFrames("watch",listOf(changed),request(true),useBatches=true)
        val before=old.sumOf(BluetoothSyncProtocol::wireSize);val after=modern.sumOf(BluetoothSyncProtocol::wireSize)
        assertTrue("$before -> $after",after<before/10)
        val incoming=LibrarySyncPayload.parseChunkedArticles(modern.single());assertTrue(incoming.single().metadataOnly)
        assertEquals(1,repo.mergeChunkedArticlesFromSync(incoming));assertEquals(article.contentText,db.phoneArticleDao().getById(article.articleId)!!.contentText)
        println("BLE_V2_FULL_REUSE articleWireBytes=$before->$after bodyChunks=${local.chunkHashes.size}->0")
    }

    @Test fun usageSchemaMigrationRetainsExistingHistoryAndAddsCursorAndUniqueRemoteKey()=runBlocking<Unit> {
        val sql=db.openHelper.writableDatabase
        val table=sql.query("SELECT sql FROM sqlite_master WHERE type='table' AND name='llm_token_usage'").use {it.moveToFirst();it.getString(0)}
        sql.execSQL("DROP TABLE llm_token_usage_sync_state");sql.execSQL("DROP TABLE llm_token_usage")
        val legacy=table.replace("`remoteKey` TEXT, ","")
        assertFalse(legacy.contains("remoteKey"));sql.execSQL(legacy)
        sql.execSQL("INSERT INTO llm_token_usage(id,provider,model,requestId,createdAt,totalTokens) VALUES(7,'p','m','r',123,42)")
        PhoneCompanionDatabase.MIGRATION_15_16.migrate(sql)
        val row=tokens.observeRecent().first().single();assertEquals(7L,row.id);assertEquals(42,row.totalTokens);assertNull(row.remoteKey)
        assertEquals(1,tokens.applyIncremental("peer",usage("epoch",1,listOf(1))))
        assertEquals(0,tokens.applyIncremental("peer",usage("epoch",1,listOf(1))))
        assertEquals(2,tokens.observeRecent().first().size)
    }

    @Test fun cursorUpgradeAdoptsIdenticalLegacyProviderRequestWithoutDoubleCountingHistory()=runBlocking<Unit> {
        val event=JSONObject().put("createdAt",1).put("model","test").put("requestId","provider-request").put("totalTokens",3)
        tokens.replaceRecords(listOf(event));assertEquals(1,tokens.observeRecent().first().size)
        val page=usage("epoch",1,listOf(1)).put("records",JSONArray().put(JSONObject(event.toString()).put("id",1)))
        assertEquals(0,tokens.applyIncremental("peer",page));assertEquals(1,tokens.observeRecent().first().size)
        assertNotNull(tokens.observeRecent().first().single().remoteKey)
        assertEquals(1,tokens.applyIncremental("other-peer",page));assertEquals(2,tokens.observeRecent().first().size)
    }

    @Test fun canonicalAndLegacyWireIdentityMatchSharedBackendDerivedVectors() {
        val fixture=JSONObject(requireNotNull(javaClass.getResource("/ble/ble-rss-identity-v2.json")).readText())
        val cases=fixture.getJSONArray("cases")
        for(i in 0 until cases.length()) {
            val c=cases.getJSONObject(i);val article=row(c.getString("canonicalArticleId")).copy(url=c.getString("url"))
            val canonical=BluetoothArticleIdentityProjection(listOf(article),emptyMap(),true)
            val old=BluetoothArticleIdentityProjection(listOf(article),emptyMap(),false)
            assertEquals(c.getString("legacyArticleId"),old.wire(article.articleId))
            assertEquals(article.articleId,canonical.wire(article.articleId))
            val expected=c.getJSONArray("legacyArticleIds");val actual=canonical.outgoingAliases(listOf(article))[article.articleId].orEmpty()
            assertEquals((0 until expected.length()).map(expected::getString),actual)
        }
        val ambiguous=fixture.getJSONArray("ambiguousCases").getJSONObject(0)
        val ids=ambiguous.getJSONArray("canonicalArticleIds");val rows=(0 until ids.length()).map { row(ids.getString(it)).copy(url=ambiguous.getString("url")) }
        val old=BluetoothArticleIdentityProjection(rows,emptyMap(),false)
        assertEquals(rows.map{it.articleId},rows.map{old.wire(it.articleId)})
    }

    @Test fun invalidTokenPagesAndUnrelatedLegacyReplyCannotAdvanceOrDeleteHistory()=runBlocking<Unit> {
        tokens.replaceRecords(listOf(JSONObject().put("requestId","history").put("totalTokens",7)))
        for(page in listOf(usage("epoch",1,listOf(1)).put("count",0),usage("epoch",1,listOf(1)).put("nextAfterId",1.5),usage("epoch",1,listOf(1)).put("hasMore","false")))
            assertTrue(runCatching{tokens.applyIncremental("peer",page)}.isFailure)
        assertNull(tokens.cursor("peer"))
        val s=session(ok("otherAction").put("records",JSONArray()))
        assertTrue(runCatching { manager.syncLlmTokenUsage(device,s,BleSyncBindingSnapshot(device.address,"owner",s,ok("syncLibrary"))) }.isFailure)
        assertEquals(7,tokens.observeRecent().first().single().totalTokens);s.close()
    }

    @Test fun bindingSnapshotCannotBeReusedForAnotherPhysicalSessionOrOwner()=runBlocking<Unit> {
        val original=session();val another=session()
        for(snapshot in listOf(BleSyncBindingSnapshot(device.address,"different-owner",another,ok("syncLibrary")),BleSyncBindingSnapshot(device.address,"owner",original,ok("syncLibrary")))) {
            assertTrue(runCatching {manager.syncLlmTokenUsage(device,another,snapshot)}.isFailure)
            assertTrue(payloads().isEmpty())
        }
        original.close();another.close()
    }

    @Test fun repeatedSyncAllWithIdenticalNonEmptyLibraryMeasuresBothDirectionsAndNoCloudTrigger()=runBlocking<Unit> {
        repo.mergeArticlesFromSync(listOf(row("canonical-1"),row("canonical-2").copy(url="https://entry.test/second")))
        val note=notes.save(noteId="n",title="same",markdown="same line\n".repeat(12000))
        val events=JSONArray().apply {for(id in 1..200)put(JSONObject().put("id",id).put("provider","p").put("model","m").put("requestId","r-$id").put("createdAt",id).put("totalTokens",3))}
        suspend fun round(modern:Boolean):List<JSONObject> {
            val manifest=LibrarySyncPayload.buildManifestRequestFromEntries("watch",repo.getArticleManifestsForSync(),emptyList()).put("success",true)
                .put("supportsNoteManifestV2",modern).put("supportsNoteAssetInventory",false).put("supportsTokenUsageCursor",modern).put("supportsReaderPresets",false).put("supportsRssCanonicalIds",false)
            return buildList {
                add(ok("syncLibrary").put("supportsTokenUsageCursor",modern));add(ok("syncAccount"));add(ok("syncLibrary","cursor").put("supportsTokenUsageCursor",modern));add(manifest);add(ok("syncLibrary","complete").put("articles",JSONArray()))
                if(modern)add(ok("syncNotes","manifest").put("noteManifest",NoteSyncPayload.summaries(listOf(note))))
                add(noteResponse(if(modern)"delta" else null,if(modern)emptyList() else listOf(note)))
                add(if(modern)usage("epoch",200) else ok("syncLlmTokenUsage").put("records",events))
                add(BluetoothSyncProtocol.buildSessionControlResponse(14,"complete"))
            }
        }
        manager.syncAll(device,reusableSession=session(*round(false).toTypedArray()))
        val oldIndex=outputs.lastIndex;val before=outputs[oldIndex].size()+responseWireBytes[oldIndex]
        val imported=usage("epoch",200).put("records",events).put("count",200)
        tokens.applyIncremental("owner:watch",imported)
        manager.syncAll(device,reusableSession=session(*round(true).toTypedArray()))
        val newIndex=outputs.lastIndex;val after=outputs[newIndex].size()+responseWireBytes[newIndex]
        assertEquals(0,cloudCalls);assertTrue("$before -> $after",after<before/15)
        assertEquals(1,payloads(oldIndex).filter {it.optString("action")=="syncNotes"}.sumOf{it.optJSONArray("notes")?.length()?:0})
        assertEquals(0,payloads(newIndex).filter {it.optString("action")=="syncNotes"}.sumOf{it.optJSONArray("notes")?.length()?:0})
        assertEquals(200,tokens.observeRecent(1000).first().size)
        println("BLE_V2_IDENTICAL_SYNC_ALL totalWireBytes=$before->$after noteBodies=2->0 tokenRows=200->0 articleBodyRows=0->0 bindingProbes=1->1")
    }

    @Test fun failedLibraryResponseAckDoesNotCommitPeerCursorOrEchoSuppressionProof()=runBlocking<Unit> {
        val original=row("received-before-ack")
        val metadata=ArticleSyncBody.metadataFor(original)
        val remote=original.copy(syncBodyHash=metadata.bodyHash,syncBodyByteCount=metadata.bodyByteCount,syncChunkSize=metadata.chunkSize,
            syncChunkHashesJson=JSONArray(metadata.chunkHashes).toString(),syncMetadataHash=metadata.metadataHash)
        val manifest=LibrarySyncPayload.buildManifestRequest("watch",listOf(remote),emptyList(),LibraryChangeSequence(0,10,true)).put("success",true)
        val response=LibrarySyncPayload.buildChunkedResponseFrames("watch",listOf(remote),emptyList(),useBatches=false).single()
        val input=ByteArrayOutputStream().also {out->listOf(ok("syncLibrary","cursor"),manifest,response).forEach{BluetoothSyncProtocol.writeFrame(out,it)}}
        val output=object:ByteArrayOutputStream() {
            override fun flush() {
                val input=ByteArrayInputStream(toByteArray());val frames=mutableListOf<JSONObject>()
                while(input.available()>0)frames+=BluetoothSyncProtocol.readFrame(input)
                if(frames.lastOrNull()?.optString("action")=="ack" && frames.last().optBoolean("applied"))throw IOException("lost library ACK")
            }
        }
        val s=PhoneSyncSession(PhoneBluetoothSyncClient(context,log),device,"phone",PhoneSyncSession.Transport(ByteArrayInputStream(input.toByteArray()),output,"test",false,{}),true,false,false)
        val failure=runCatching {manager.syncLibrary(device.address,s,binding=BleSyncBindingSnapshot(device.address,"owner",s,ok("syncLibrary")))}.exceptionOrNull()
        assertNotNull(failure);assertTrue(failure.toString(),failure!!.message.orEmpty().contains("lost library ACK"))
        assertNull(db.syncPeerStateDao().get("watch"))
        assertTrue(db.appMetaDao().getAll().none {it.key.startsWith("ble_echo_v2:") || it.key.startsWith("ble_identity_mode:")})
        assertNotNull(db.phoneArticleDao().getById(remote.articleId));s.close()
    }

    @Test fun authoritativePruningCountsActualDeletionsAndUnchangedSourcesDoNotWrite()=runBlocking<Unit> {
        repo.mergeArticlesFromSync(listOf(row()))
        val source=PhoneRssSourceEntity("https://feed.test/rss","phone","feed","",null,null,1,1,1,false,false,0)
        db.phoneRssSourceDao().upsert(source);assertEquals(0,repo.mergeAuthoritativeRssSources(listOf(source)))
        repeat(2) {round->
            val manifest=LibrarySyncPayload.buildManifestRequestFromEntries("watch",emptyList(),emptyList()).put("success",true)
            val s=session(ok("syncLibrary"),ok("syncLibrary","cursor").put("supportsAuthoritativeLibraryReplace",true),manifest,
                ok("syncLibrary","complete").put("articles",JSONArray()),BluetoothSyncProtocol.buildSessionControlResponse(14,"complete"))
            val result=manager.syncAll(device,mode=LibrarySyncMode.WATCH_TO_PHONE_REPLACE,reusableSession=s)
            assertEquals(if(round==0)1 else 0,result.libraryStats!!.merged)
            assertEquals(if(round==0)1 else 0,result.libraryStats.sourcesMerged)
            assertEquals(1,cloudCalls)
        }
        assertTrue(db.phoneArticleDao().getById("canonical")!!.deleted);assertTrue(db.phoneRssSourceDao().getByUrl(source.url)!!.deleted)
    }

    private suspend fun syncAllResponses(note:NoteEntity?=null):PhoneSyncSession {
        val binding=ok("syncLibrary").put("account",JSONObject().put("bound",true).put("userIdHash",CloudSnapshotCodec.sha256("owner".toByteArray())))
        val cursor=ok("syncLibrary","cursor")
        val manifest=LibrarySyncPayload.buildManifestRequestFromEntries("watch",emptyList(),emptyList()).put("success",true).put("supportsReaderPresets",true).put("supportsNoteManifestV2",false).put("supportsNoteAssetInventory",false)
        val articleResponse=ok("syncLibrary","complete").put("articles",JSONArray())
        return session(binding,ok("syncAccount"),cursor,manifest,articleResponse,ok("syncReader","manifest"),
            noteResponse(null,listOfNotNull(note)),ok("syncLlmTokenUsage").put("records",JSONArray()),BluetoothSyncProtocol.buildSessionControlResponse(14,"complete"))
    }
    @Test fun syncAllSharesOneBindingProbeAcrossFourStagesAndNoChangeDoesNotTriggerCloud()=runBlocking<Unit> {
        val s=syncAllResponses();val result=manager.syncAll(device,reusableSession=s)
        assertEquals(0,cloudCalls);assertFalse(result.hasActualLibraryChanges());assertTrue(transportClosed)
        assertEquals(1,payloads().count{it.optString("action")=="syncLibrary" && it.optString("phase")=="probe"})
        for(action in listOf("syncAccount","syncReader","syncLlmTokenUsage"))assertEquals(1,payloads().count{it.optString("action")==action})
        assertEquals("complete",payloads().last().getString("phase"))
    }
    @Test fun actualNoteChangeSchedulesCloudAfterCompleteAndCloudFailureDoesNotFailBluetooth()=runBlocking<Unit> {
        val n=NoteEntity("incoming",null,"title","text","text",MarkdownNoteCodec.sha256("text"),MarkdownNoteCodec.sha256("text"),"text",false,1,2,"watch")
        throwCloud=true
        val result=manager.syncAll(device,reusableSession=syncAllResponses(n))
        assertEquals(1,cloudCalls);assertEquals(1,result.noteStats!!.appliedOnPhone);assertTrue(transportClosed)
        assertEquals("complete",payloads().last().getString("phase"))
    }
    @Test fun forcedFullReceivingOneUnchangedArticleAndSixteenSourcesDoesNotEnqueueCloud()=runBlocking<Unit> {
        repo.mergeArticlesFromSync(listOf(row()))
        val remote=repo.getArticlesForSync().single()
        val sources=(0 until 16).map {PhoneRssSourceEntity("https://feed.test/$it","watch","feed$it","",null,null,1,1,1,false,false,0)}
        sources.forEach{db.phoneRssSourceDao().upsert(it)}
        repeat(2) {
            val manifest=LibrarySyncPayload.buildManifestRequest("watch",listOf(remote),sources)
                .put("success",true).put("supportsReaderPresets",false).put("supportsNoteManifestV2",false).put("supportsNoteAssetInventory",false)
            val body=LibrarySyncPayload.buildChunkedResponseFrames("watch",listOf(remote),emptyList(),useBatches=false).single()
            val s=session(bindingResponse(),ok("syncAccount"),ok("syncLibrary","cursor"),manifest,body,
                noteResponse(null),ok("syncLlmTokenUsage").put("records",JSONArray()),finishResponse())
            val result=manager.syncAll(device,forceFull=true,reusableSession=s)
            assertEquals(1,result.libraryStats!!.received);assertEquals(16,result.libraryStats.sourcesReceived)
            assertEquals(0,result.libraryStats.merged);assertEquals(0,result.libraryStats.sourcesMerged)
            assertEquals(0,cloudCalls)
        }
    }
    @Test fun sourceRefreshMetadataWinsOnceAndStaleWatchReplayDoesNotChangeItAgain()=runBlocking<Unit> {
        val clock=System.currentTimeMillis()+60_000
        val old=PhoneRssSourceEntity("https://feed.test/rss","watch","feed","old description",null,null,1,clock,-100,false,false,0)
        db.phoneRssSourceDao().upsert(old)
        repo.markLibrarySyncSuccess("watch",0,0,14,true,canonicalIds=false)
        val fresh=com.lightningstudio.watchrss.phone.data.importer.ImportedRssSource(old.url,old.title,"public metadata",null,null,emptyList())
        val updated=repo.mergeCloudRssInventory(fresh).source
        assertTrue("changed metadata must advance the LWW clock",updated.updatedAt>clock)
        val seq=db.syncChangeLogDao().maxSeq();assertTrue(seq>0)
        val delta=repo.prepareLibrarySyncWindow("watch",0)
        assertFalse(delta.fullSnapshot);assertEquals(listOf(updated),delta.rssSources)
        repeat(3) {
            assertEquals(0,repo.mergeRssSourcesFromSync(listOf(old)))
            assertEquals(updated,repo.mergeCloudRssInventory(fresh).source)
            assertEquals(seq,db.syncChangeLogDao().maxSeq())
        }
    }
    @Test fun unchangedPublicSourceRefreshPreservesSignedOrderAndProvenance()=runBlocking<Unit> {
        val source=PhoneRssSourceEntity("https://feed.test/rss","watch","feed","description",null,null,1,100,-100,false,false,0)
        db.phoneRssSourceDao().upsert(source)
        val fresh=com.lightningstudio.watchrss.phone.data.importer.ImportedRssSource(source.url,source.title,source.description,null,null,emptyList())
        assertEquals(source,repo.mergeCloudRssInventory(fresh).source)
    }
    @Test fun delayedPublicInventoryCannotRestoreADeletedSubscriptionWithANewerClock()=runBlocking<Unit> {
        val source=PhoneRssSourceEntity("https://feed.test/rss","watch","feed","description",null,null,1,100,-100,false,true,System.currentTimeMillis()+60_000)
        db.phoneRssSourceDao().upsert(source)
        val fresh=com.lightningstudio.watchrss.phone.data.importer.ImportedRssSource(source.url,source.title,"new metadata",null,null,emptyList())
        assertEquals(source,repo.mergeCloudRssInventory(fresh).source)
        assertEquals(source,db.phoneRssSourceDao().getByUrl(source.url));assertEquals(0L,db.syncChangeLogDao().maxSeq())
    }
    @Test fun realWatchNullSourceUrlsAreNotTheLiteralStringNull() {
        val source=JSONObject().put("url","https://feed.test/rss").put("title","feed")
            .put("siteUrl",JSONObject.NULL).put("imageUrl",JSONObject.NULL)
        val parsed=LibrarySyncPayload.parseRssSources(JSONArray().put(source)).single()
        assertNull(parsed.siteUrl);assertNull(parsed.imageUrl)
    }
    private fun bindingResponse() = ok("syncLibrary").put("account", JSONObject().put("bound", true)
        .put("userIdHash", CloudSnapshotCodec.sha256("owner".toByteArray())))
    private fun emptyManifest() = LibrarySyncPayload.buildManifestRequestFromEntries("watch", emptyList(), emptyList())
        .put("success", true).put("supportsReaderPresets", false).put("supportsNoteManifestV2", false).put("supportsNoteAssetInventory", false)
    private fun emptyComplete() = ok("syncLibrary", "complete").put("articles", JSONArray())
    private fun finishResponse() = BluetoothSyncProtocol.buildSessionControlResponse(14, "complete")

    @Test fun disconnectMidBodyReconnectsWithFreshCursorAndKeepsCompletedAccountStage() = runBlocking<Unit> {
        repo.markLibrarySyncSuccess("watch", 0, 0, 14, true, canonicalIds = false)
        repo.mergeArticlesFromSync(listOf(row("local")))
        val seq = db.syncChangeLogDao().maxSeq()
        val first = session(bindingResponse(), ok("syncAccount"), ok("syncLibrary", "cursor"), emptyManifest())
        val waits = mutableListOf<Long>(); val attempts = mutableListOf<Int>(); var opens = 0
        manager.reconnectDelay = { waits += it }
        manager.reconnectSession = { _, _ ->
            opens++
            session(bindingResponse(), LibrarySyncPayload.buildCursorResponse("watch", LibrarySyncCursor(0, seq, 0)).put("supportsRssCanonicalIds", false),
                LibrarySyncPayload.buildManifestRequest("watch", repo.getArticlesForSync(), emptyList()).put("success", true).put("supportsReaderPresets", false).put("supportsNoteManifestV2", false).put("supportsNoteAssetInventory", false),
                emptyComplete(), noteResponse(null), ok("syncLlmTokenUsage").put("records", JSONArray()), finishResponse())
        }
        manager.syncAll(device, reusableSession = first, onProgress = { if (it.reconnectAttempt > 0) attempts += it.reconnectAttempt })
        assertEquals(listOf(2000L), waits); assertEquals(listOf(1), attempts); assertEquals(1, opens)
        assertEquals(1, payloads(0).count { it.optString("action") == "syncAccount" })
        assertEquals(0, payloads(1).count { it.optString("action") == "syncAccount" })
        val manifest = payloads(1).single { it.optString("phase") == "manifest" && it.optString("action") == "syncLibrary" }
        assertEquals(0, manifest.getJSONArray("articleManifest").length())
        assertEquals(seq, db.syncPeerStateDao().get("watch")!!.lastLocalSeqAckedByPeer)
        assertEquals(seq, db.syncChangeLogDao().maxSeq())
    }

    @Test fun stalledIpReadClosesThePipeAndResumesTheSameOperationOverBluetooth()=runBlocking<Unit> {
        val sent=ByteArrayOutputStream()
        val ip=com.lightningstudio.watchrss.phone.connection.ip.PhoneIpSyncSession(
            "watch","stalled",com.lightningstudio.watchrss.phone.connection.ip.IpTransportKind.WIFI_LAN,"192.0.2.1",
            {sent.write(it)},{transportClosed=true},readIdleTimeoutMs=100)
        val wire=ByteArrayOutputStream().also {out->listOf(bindingResponse(),ok("syncAccount")).forEach{BluetoothSyncProtocol.writeFrame(out,it)}}
        ip.acceptBinary(wire.toByteArray()) // cursor never arrives; there is no manual Wi-Fi toggle.
        val first=PhoneSyncSession(PhoneBluetoothSyncClient(context,log),device,"phone",
            PhoneSyncSession.Transport(ip.inputStream,ip.outputStream,"ip:wifiLan",false,ip::close),true,false,false)
        val waits=mutableListOf<Long>();var opens=0
        manager.reconnectDelay={waits+=it}
        manager.reconnectSession={_,_->
            assertTrue(ip.isClosed);opens++
            session(bindingResponse(),ok("syncLibrary","cursor"),emptyManifest(),emptyComplete(),
                noteResponse(null),ok("syncLlmTokenUsage").put("records",JSONArray()),finishResponse())
        }
        try {
            manager.syncAll(device,reusableSession=first)
            assertEquals(1,opens);assertEquals(listOf(2000L),waits);assertEquals(0,cloudCalls)
        }finally{first.close();ip.close()}
    }

    @Test fun lostAppliedAckReplaysIdempotentlyWithoutAnotherDatabaseWrite() = runBlocking<Unit> {
        val original = row("ack-replay")
        val metadata = ArticleSyncBody.metadataFor(original)
        val remote = original.copy(syncBodyHash = metadata.bodyHash, syncBodyByteCount = metadata.bodyByteCount,
            syncChunkSize = metadata.chunkSize, syncChunkHashesJson = JSONArray(metadata.chunkHashes).toString(), syncMetadataHash = metadata.metadataHash)
        val manifest = LibrarySyncPayload.buildManifestRequest("watch", listOf(remote), emptyList(), LibraryChangeSequence(0, 10, true))
            .put("success", true).put("supportsReaderPresets", false).put("supportsNoteManifestV2", false).put("supportsNoteAssetInventory", false)
        val complete = LibrarySyncPayload.buildChunkedResponseFrames("watch", listOf(remote), emptyList(), useBatches = false).single()
        val wire = ByteArrayOutputStream().also { out -> listOf(bindingResponse(), ok("syncAccount"), ok("syncLibrary", "cursor"), manifest, complete).forEach { BluetoothSyncProtocol.writeFrame(out, it) } }
        var acks = 0
        val output = object : ByteArrayOutputStream() {
            override fun flush() {
                val input = ByteArrayInputStream(toByteArray()); var last: JSONObject? = null
                while (input.available() > 0) last = BluetoothSyncProtocol.readFrame(input)
                if (last?.optString("action") == "ack" && ++acks == 3) throw IOException("bt socket closed, read return: -1")
            }
        }
        val first = PhoneSyncSession(PhoneBluetoothSyncClient(context, log), device, "phone",
            PhoneSyncSession.Transport(ByteArrayInputStream(wire.toByteArray()), output, "test", false, { transportClosed = true }), true, false, false)
        var afterFirstApply = -1L
        manager.reconnectDelay = {}
        manager.reconnectSession = { _, _ ->
            afterFirstApply = db.syncChangeLogDao().maxSeq()
            assertNotNull(db.phoneArticleDao().getById(remote.articleId)); assertNull(db.syncPeerStateDao().get("watch"))
            session(bindingResponse(), ok("syncLibrary", "cursor"), manifest, complete, noteResponse(null),
                ok("syncLlmTokenUsage").put("records", JSONArray()), finishResponse())
        }
        val result = manager.syncAll(device, reusableSession = first)
        assertTrue(afterFirstApply > 0); assertEquals(afterFirstApply, db.syncChangeLogDao().maxSeq())
        assertEquals(0, result.libraryStats!!.merged); assertEquals(1, cloudCalls)
        assertEquals(10, db.syncPeerStateDao().get("watch")!!.lastRemoteSeqApplied)
    }

    @Test fun failureAfterCommittedLibraryResumesNotesWithoutRepeatingLibrary() = runBlocking<Unit> {
        val first = session(bindingResponse(), ok("syncAccount"), ok("syncLibrary", "cursor"), emptyManifest(), emptyComplete())
        manager.reconnectDelay = {}
        manager.reconnectSession = { _, _ -> session(bindingResponse(), noteResponse(null), ok("syncLlmTokenUsage").put("records", JSONArray()), finishResponse()) }
        manager.syncAll(device, reusableSession = first)
        assertEquals(listOf("probe"), payloads().filter { it.optString("action") == "syncLibrary" }.map { it.getString("phase") })
        assertEquals(0, cloudCalls)
    }

    @Test fun exhaustedReconnectsUseOneBudgetAndOfferManualSyncRetry() = runBlocking<Unit> {
        val waits = mutableListOf<Long>(); val attempts = mutableListOf<Int>(); var opens = 0
        manager.reconnectDelay = { waits += it }
        manager.reconnectSession = { _, _ -> opens++; throw IOException("bt socket closed, read return: -1") }
        val first = session(bindingResponse(), ok("syncAccount"), ok("syncLibrary", "cursor"), emptyManifest())
        val failure = runCatching { manager.syncAll(device, reusableSession = first, onProgress = { if (it.reconnectAttempt > 0) attempts += it.reconnectAttempt }) }.exceptionOrNull()
        assertEquals(listOf(2000L, 5000L, 10000L), waits); assertEquals(listOf(1, 2, 3), attempts); assertEquals(3, opens)
        assertTrue(failure!!.message!!.contains("可直接点“同步”重试")); assertEquals(0, cloudCalls); assertTrue(transportClosed)
    }

    @Test fun explicitPeerRejectionNeverReconnectsEvenWhenMessageMentionsSocketClosed() = runBlocking<Unit> {
        var opens = 0
        manager.reconnectDelay = { fail("must not back off") }
        manager.reconnectSession = { _, _ -> opens++; error("must not reconnect") }
        val rejected = ok("syncLibrary", "cursor").put("success", false).put("message", "socket closed: peer rejected this operation")
        val first = session(bindingResponse(), ok("syncAccount"), rejected)
        val failure = runCatching { manager.syncAll(device, reusableSession = first) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException); assertTrue(failure!!.message!!.contains("peer rejected")); assertEquals(0, opens)
    }

    @Test fun operationTimeoutRetriesButUserCancellationDoesNot() = runBlocking<Unit> {
        val waits = mutableListOf<Long>(); var reconnects = 0; var calls = 0
        val recovery = BluetoothSyncRecovery({ waits += it }, { reconnects++ }, { _, _, _ -> })
        assertEquals("resumed", recovery.run { if (calls++ == 0) withTimeout(1) { delay(100) }; "resumed" })
        assertEquals(1, reconnects); assertEquals(listOf(2000L), waits)
        val failure = runCatching { recovery.run { throw CancellationException("user cancelled") } }.exceptionOrNull()
        assertTrue(failure is CancellationException); assertEquals(1, reconnects)
        assertTrue(isRetryableBluetoothDisconnect(java.net.SocketTimeoutException("read timeout")))
        assertTrue(isRetryableBluetoothDisconnect(EOFException("read -1")))
        assertFalse(isRetryableBluetoothDisconnect(IllegalArgumentException("read -1")))
    }

    @Test fun finishTimeoutClosesTheBlockedTransportInsteadOfHanging()=runBlocking<Unit> {
        val closed=java.util.concurrent.CountDownLatch(1)
        val blocked=object:InputStream() {override fun read():Int {closed.await();throw IOException("socket closed")}}
        val s=PhoneSyncSession(PhoneBluetoothSyncClient(context,log),device,"phone",
            PhoneSyncSession.Transport(blocked,ByteArrayOutputStream(),"test",false,{closed.countDown()}),true,false,false)
        val failure=runCatching{withTimeout(50){s.complete("finish-timeout")}}.exceptionOrNull()
        assertTrue(failure.toString(),isRetryableBluetoothDisconnect(requireNotNull(failure)));assertEquals(0,closed.count)
    }
    @Test fun reconnectDropsOldPhysicalSessionCapabilitiesAndUsesNewProbe()=runBlocking<Unit> {
        val first=session(bindingResponse(),ok("syncAccount"),ok("syncLibrary","cursor"),emptyManifest(),emptyComplete())
        manager.reconnectDelay={}
        manager.reconnectSession={_,_->session(bindingResponse().put("supportsNoteManifestV2",true),
            ok("syncNotes","manifest").put("noteManifest",JSONArray()),noteResponse("delta"),ok("syncLlmTokenUsage").put("records",JSONArray()),finishResponse())}
        manager.syncAll(device,reusableSession=first)
        assertEquals(listOf("manifest","delta"),payloads().filter{it.optString("action")=="syncNotes"}.map{it.getString("phase")})
    }
    @Test fun readerPullsRecoverableOriginalBeforePushingOtherResources()=runBlocking<Unit> {
        val a="peer font".toByteArray();val b="phone font".toByteArray()
        fun font(bytes:ByteArray):com.lightningstudio.watchrss.phone.data.reader.ReaderFontAssetEntity {
            val sha=CloudSnapshotCodec.sha256(bytes)
            return com.lightningstudio.watchrss.phone.data.reader.ReaderFontAssetEntity(sha,sha,"font","font","$sha.ttf","font/ttf",bytes.size.toLong(),1,"{}",1,"phone",false)
        }
        val fa=font(a);val fb=font(b);db.readerPresetDao().upsertFonts(listOf(fa,fb));reader.resourceStore.targetFontFile(fb.fileName).writeBytes(b)

        val snapshot=reader.exportSnapshot()
        fun reply(missing:Boolean)=ReaderPresetSyncPayload.buildManifest(snapshot,"watch").put("success",true).put("missingResources",JSONArray().apply{if(missing)put(JSONObject().put("kind","font").put("fileName",fb.fileName).put("sha256",fb.sha256).put("byteCount",fb.byteCount))})
        val pull=ok("syncReader","pullResource").put("kind","font").put("fileName",fa.fileName).put("totalBytes",a.size).put("chunkIndex",0).put("chunkCount",1).put("chunkSha256",fa.sha256).put("data",java.util.Base64.getEncoder().encodeToString(a))
        val push=ok("syncReader","pushResource").put("received",true).put("chunkSha256",fb.sha256).put("applied",true)
        val s=session(reply(true),pull,push,reply(false));var applied=0
        assertTrue(manager.syncReaderPresets(device.address,"reader-recovery",s,BleSyncBindingSnapshot(device.address,"owner",s,ok("syncLibrary").advertiseBleSyncV2()),onLocalApplied={applied+=it}))
        assertEquals(listOf("manifest","pullResource","pushResource","manifest"),payloads().map{it.getString("phase")});assertArrayEquals(a,reader.resourceStore.fontFile(fa.fileName)!!.readBytes());assertEquals(1,applied);s.close()
    }
    @Test fun missingOriginalOnBothPeersIsPreservedWithActionableWarning()=runBlocking<Unit> {
        val sha="a".repeat(64)
        val font=com.lightningstudio.watchrss.phone.data.reader.ReaderFontAssetEntity(sha,sha,"missing","font","$sha.ttf","font/ttf",4,1,"{}",1,"phone",false)
        db.readerPresetDao().upsertFont(font)
        val reply=ReaderPresetSyncPayload.buildManifest(reader.exportSnapshot(),"watch").put("success",true).put("missingResources",JSONArray().put(JSONObject().put("kind","font").put("fileName",font.fileName).put("sha256",sha).put("byteCount",4)))
        val s=session(reply)
        val failure=runCatching{manager.syncReaderPresets(device.address,"reader-missing",s,BleSyncBindingSnapshot(device.address,"owner",s,ok("syncLibrary")))}.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("两端均缺少字体"));assertTrue(failure?.message.orEmpty().contains("aaaaaaaa"));assertTrue(failure?.message.orEmpty().contains("重新导入"));assertEquals(1,payloads().size);assertFalse(db.readerPresetDao().fontById(sha)!!.deleted);s.close()
    }

}
