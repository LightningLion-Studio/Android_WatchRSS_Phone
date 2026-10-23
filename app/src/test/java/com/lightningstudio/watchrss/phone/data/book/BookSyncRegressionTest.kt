package com.lightningstudio.watchrss.phone.data.book

import android.content.Context
import androidx.room.Room
import com.lightningstudio.watchrss.phone.cloud.*
import com.lightningstudio.watchrss.phone.connection.bluetooth.*
import com.lightningstudio.watchrss.phone.data.backup.*
import com.lightningstudio.watchrss.phone.data.db.*
import com.lightningstudio.watchrss.phone.data.model.ImportedContentIds
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
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class BookSyncRegressionTest {
    private val context:Context=RuntimeEnvironment.getApplication()
    private val fixtures=mutableListOf<Fixture>()
    private inner class Fixture {
        val dir=File.createTempFile("book-test-","",context.cacheDir).apply{delete();mkdirs()}
        val db=Room.inMemoryDatabaseBuilder(context,PhoneCompanionDatabase::class.java).allowMainThreadQueries().build()
        val store=ImportedBookStore(db,dir,null,"phone")
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val repo=PhoneCompanionRepository(db.phoneSavedItemDao(),db.phoneArticleDao(),db.phoneRssSourceDao(),"phone",syncChangeLogDao=db.syncChangeLogDao(),appMetaDao=db.appMetaDao(),books=store)
        val reader=ReaderPresetRepository(context,db,db.readerPresetDao(),"phone",scope)
        val backup=WatchRssBackupService(context,db,repo,reader,"phone")
        suspend fun seed(count:Int=3,key:String="sample"):JSONObject {
            val source=ImportedContentIds.txtNovelSourceUrl(key)
            db.phoneRssSourceDao().upsert(PhoneRssSourceEntity(source,"phone","测试书","",null,null,1,10,1,false,false,0))
            db.phoneArticleDao().upsertAll((0 until count).map{i-> row("$key-$i",ImportedContentIds.txtNovelChapterUrl(key,i,"hash"),source,"第${i+1}章","正文$i")})
            store.ensureRegistered();return store.states().single()
        }
    }
    private fun fixture()=Fixture().also{fixtures+=it}
    @After fun close(){fixtures.forEach{it.scope.cancel();it.db.close();it.dir.deleteRecursively()}}
    private fun row(id:String,url:String,source:String,title:String,text:String)=PhoneArticleEntity(id,"phone",url,title,"测试书","",null,text,null,
        CloudSnapshotCodec.sha256(text.toByteArray()),1,10,false,0,0,source,"测试书",false,0,0,false,0,0,false,0)
    private fun copy(v:JSONObject)=JSONObject(v.toString())
    private fun reading(id:String,index:Int=1,clock:Long=100)=JSONObject().put("bookId",id).put("chapterIndex",index).put("sourceDeviceId","watch")
        .put("isRead",true).put("readingProgress",0.5).put("readingPositionBytes",3).put("readingPositionContentHash","hash").put("readingPositionChangedAt",clock)
    private fun reply(phase:String)=JSONObject().put("action","syncBooks").put("version",1).put("success",true).put("phase",phase)
    private fun readingReply()=reply("reading").put("states",JSONArray()).put("hasMore",false).put("nextAfter",0)
    private fun entries(bytes:ByteArray):Map<String,ByteArray> = buildMap {ZipInputStream(ByteArrayInputStream(bytes)).use{z->while(true){val e=z.nextEntry?:break;put(e.name,z.readBytes())}}}

    @Test fun sharedFingerprintVectorsMatchEveryChapterBookAndArticleId() {
        val cases=JSONObject(requireNotNull(javaClass.getResource("/book/book-fingerprint.json")).readText()).getJSONArray("cases")
        for(i in 0 until cases.length()) {
            val c=cases.getJSONObject(i);val chapters=c.getJSONArray("chapters")
            val hashes=(0 until chapters.length()).map{j->chapters.getJSONObject(j).let{BookSync.chapterHash(j+1,it.getString("title"),it.getString("contentText"))}}
            assertEquals(c.getJSONArray("chapterHashes").toString(),JSONArray(hashes).toString())
            assertEquals(c.getString("fingerprint"),BookSync.fingerprint(hashes));assertEquals(c.getString("bookId"),BookSync.id(BookSync.fingerprint(hashes)))
            for(j in hashes.indices)assertEquals(c.getJSONArray("articleIds").getString(j),BookSync.articleId(c.getString("bookId"),j+1))
        }
    }
    @Test fun deletionUnionAndWholeTombstoneAreCommutativeAssociativeIdempotent()=runBlocking<Unit> {
        val state=fixture().seed()
        val a=copy(state).put("deletedChapters",JSONArray(listOf(1))).put("deletionClock",20)
        val b=copy(state).put("deletedChapters",JSONArray(listOf(3))).put("deletionClock",30).put("title","new").put("updatedAt",40).put("sourceDeviceId","watch")
        val c=copy(state).put("deleted",true).put("deletedAt",50)
        fun canonical(v:JSONObject)=BookSync.canonical(v)
        assertEquals(canonical(BookSync.merge(a,b)),canonical(BookSync.merge(b,a)))
        assertEquals(canonical(BookSync.merge(BookSync.merge(a,b),c)),canonical(BookSync.merge(a,BookSync.merge(b,c))))
        assertEquals(canonical(a),canonical(BookSync.merge(a,a)))
        val merged=BookSync.merge(BookSync.merge(a,b),c)
        assertEquals(listOf(1,3),BookSync.deletions(merged).toList());assertEquals(30,merged.getInt("deletionClock"));assertTrue(BookSync.merge(merged,state).getBoolean("deleted"))
        assertTrue(runCatching{BookSync.merge(state,copy(state).put("chapterCount",4))}.isFailure)
        for(bad in listOf(JSONArray(listOf(2,1)),JSONArray(listOf(1,1)),JSONArray(listOf(0)),JSONArray(listOf(4))))assertTrue(runCatching{BookSync.validate(copy(state).put("deletedChapters",bad))}.isFailure)
    }
    @Test fun chapterDeleteAndReadingKeepOriginalPackageFingerprintAndCloudBytes()=runBlocking<Unit> {
        val f=fixture();val state=f.seed();val id=state.getString("bookId");val original=f.store.packageFile(id)!!.readBytes()
        val fingerprint=f.backup.privateCloudFingerprint();val cloud=f.backup.createCloudPrivateArchive()
        f.repo.deleteArticle("sample-1");f.store.mergeReading(reading(id))
        val next=f.store.states().single()
        assertEquals(state.getString("fingerprint"),next.getString("fingerprint"));assertEquals(setOf(2),BookSync.deletions(next));assertFalse(next.getBoolean("deleted"))
        assertArrayEquals(original,f.store.packageFile(id)!!.readBytes());assertEquals(fingerprint,f.backup.privateCloudFingerprint())
        assertArrayEquals(cloud,f.backup.createCloudPrivateArchive())
        val files=entries(cloud);assertEquals(1,files.keys.count{it.startsWith("books/")});assertTrue(files.containsKey("books.json"));assertTrue(files.keys.none{it.startsWith("articles/")})
        val records=mutableListOf<Pair<String,JSONObject>>();f.backup.cloudRecordPages{kind,value->records+=kind to value}
        assertEquals(1,records.count{it.first=="book"});assertEquals(1,records.count{it.first=="book-reading"});assertEquals(0,records.count{it.first=="article"})
        assertEquals(0,f.store.merge(next));assertEquals(0,f.store.mergeReading(reading(id)))
    }
    @Test fun recordBeforeBodyOverlaysDeletedAndReadStateAndReplayWritesNothing()=runBlocking<Unit> {
        val source=fixture();val state=source.seed();val id=state.getString("bookId");val target=fixture()
        target.store.merge(copy(state).put("deletedChapters",JSONArray(listOf(2))).put("deletionClock",100))
        target.store.mergeReading(reading(id,3))
        target.store.receivePackage(id,source.store.packageFile(id)!!)
        val mappings=target.db.bookDao().chapters(id)
        assertTrue(target.db.phoneArticleDao().getById(mappings[1].articleId)!!.deleted)
        assertTrue(target.db.phoneArticleDao().getById(mappings[2].articleId)!!.isRead)
        val before=target.db.syncChangeLogDao().maxSeq()
        assertEquals(0,target.store.receivePackage(id,source.store.packageFile(id)!!));assertEquals(before,target.db.syncChangeLogDao().maxSeq())
        val whole=copy(state).put("deleted",true).put("deletedAt",200)
        target.store.merge(whole);assertTrue(target.db.phoneArticleDao().getAllForSync().all{it.deleted})
        assertTrue(target.db.phoneRssSourceDao().getByUrl(state.getString("sourceUrl"))!!.deleted)
        target.store.receivePackage(id,source.store.packageFile(id)!!);assertTrue(target.db.phoneArticleDao().getAllForSync().all{it.deleted})
    }
    @Test fun incompleteOrCorruptPackagePublishesNoBookOrChapters()=runBlocking<Unit> {
        val source=fixture();val state=source.seed();val id=state.getString("bookId");val target=fixture()
        val bytes=source.store.packageFile(id)!!.readBytes()
        for(bad in listOf(bytes.copyOf(bytes.size/2),bytes.toString(Charsets.UTF_8).replace("正文0","改写正文").toByteArray())) {
            val file=File.createTempFile("bad-book",".jsonl",context.cacheDir).apply{writeBytes(bad)}
            try{assertTrue(runCatching{target.store.receivePackage(id,file)}.isFailure)}finally{file.delete()}
            assertTrue(target.db.bookDao().all().isEmpty());assertTrue(target.db.phoneArticleDao().getAllForSync().isEmpty())
        }
    }
    @Test fun missingOriginalBodyKeepsLegacyRowsAndWarnsInsteadOfFingerprintingSubset()=runBlocking<Unit> {
        val f=fixture();val source=ImportedContentIds.txtNovelSourceUrl("partial")
        f.db.phoneArticleDao().upsertAll(listOf(row("one",ImportedContentIds.txtNovelChapterUrl("partial",0,"a"),source,"a","text"),row("two",ImportedContentIds.txtNovelChapterUrl("partial",1,"b"),source,"b","")))
        f.store.ensureRegistered();f.store.ensureRegistered()
        assertTrue(f.store.states().isEmpty());assertEquals(1,f.store.migrationWarnings.size);assertEquals(2,f.db.phoneArticleDao().getAllForSync().size)
    }
    @Test fun sameBookBleManifestDoesNotSendOrPullAnyContent()=runBlocking<Unit> {
        val f=fixture();val state=f.seed();val phases=mutableListOf<String>();var sentBooks=0
        val result=BookBluetoothSync(f.store,"phone",context.cacheDir){request->
            phases+=request.getString("phase")
            when(request.getString("phase")) {
                "manifest"->{sentBooks=request.getJSONArray("books").length();reply("manifest").put("books",JSONArray().put(copy(state).put("contentAvailable",true))).put("missingBookIds",JSONArray())}
                "reading"->readingReply()
                else->error("unexpected body transfer")
            }
        }.sync()
        assertEquals(0,result);assertEquals(1,sentBooks);assertEquals(listOf("manifest","reading"),phases)
    }
    @Test fun missingBookIsTransferredOnceAndReadingPagedSeparately()=runBlocking<Unit> {
        val f=fixture();val state=f.seed();val id=state.getString("bookId");val target=fixture();val received=ByteArrayOutputStream();var bodies=0
        BookBluetoothSync(f.store,"phone",context.cacheDir){request->when(request.getString("phase")) {
            "manifest"->reply("manifest").put("books",JSONArray()).put("missingBookIds",JSONArray().put(id))
            "body"->{bodies++;received.write(Base64.getDecoder().decode(request.getString("dataBase64")));reply("body").put("received",true).put("complete",true)}
            "reading"->readingReply();else->error("unexpected")
        }}.sync()
        assertEquals(1,bodies);assertArrayEquals(f.store.packageFile(id)!!.readBytes(),received.toByteArray())
        BookBluetoothSync(target.store,"new-phone",context.cacheDir){request->when(request.getString("phase")) {
            "manifest"->reply("manifest").put("books",JSONArray().put(copy(state).put("contentAvailable",true))).put("missingBookIds",JSONArray())
            "pull"->reply("body").put("bookId",id).put("fingerprint",state.getString("fingerprint")).put("packageSha256",f.store.packageHash(id))
                .put("byteCount",received.size()).put("chunkIndex",0).put("chunkCount",1).put("dataBase64",Base64.getEncoder().encodeToString(received.toByteArray()))
            "reading"->readingReply().put("states",JSONArray().put(reading(id)))
            else->error("unexpected")
        }}.sync()
        assertEquals(3,target.db.phoneArticleDao().getAllForSync().size);assertEquals(1,target.store.readingStates().size)
    }
    @Test fun cloudBookArchiveAndMiniDirectObjectRestoreWithoutArticleRecords()=runBlocking<Unit> {
        val f=fixture();val state=f.seed();val id=state.getString("bookId");val cloud=f.backup.createCloudPrivateArchive()
        val target=fixture();target.backup.applyCloudRecord("book",copy(state).put("deletedChapters",JSONArray(listOf(1))).put("deletionClock",99))
        target.backup.restorePrivateLibrary(cloud)
        assertEquals(1,target.store.states().size);assertEquals(3,target.db.phoneArticleDao().getAllForSync().size)
        assertTrue(target.db.phoneArticleDao().getById(BookSync.articleId(id,1))!!.deleted)
        val direct=fixture();val metadata=JSONObject().put("bookId",id).put("fingerprint",state.getString("fingerprint")).put("chapterCount",3).put("packagePath","books/$id.jsonl")
        val descriptor=CloudObjectDescriptor("books/$id.jsonl","raw",1,1,emptyList(),privateBook=metadata)
        assertEquals(metadata.toString(),CloudObjectDescriptor.fromJson(descriptor.toJson()).privateBook!!.toString())
        direct.backup.restorePrivateBook(metadata,f.store.packageFile(id)!!.readBytes())
        assertEquals(3,direct.db.phoneArticleDao().getAllForSync().size)
        assertTrue(runCatching{direct.backup.restorePrivateBook(copy(metadata).put("packagePath","../secret"),byteArrayOf())}.isFailure)
    }
    @Test fun wholeBookDeletionRemovesPrivateBodyButKeepsOneBookTombstone()=runBlocking<Unit> {
        val f=fixture();val state=f.seed();f.repo.deleteRssSource(state.getString("sourceUrl"))
        val tombstone=f.store.states().single();assertTrue(tombstone.getBoolean("deleted"));assertTrue(f.store.availableBooks().isEmpty())
        val records=mutableListOf<Pair<String,JSONObject>>();f.backup.cloudRecordPages{kind,value->records+=kind to value}
        assertEquals(1,records.size);assertEquals("book",records.single().first)
        assertTrue(entries(f.backup.createCloudPrivateArchive()).keys.none{it.startsWith("books/")})
    }
    @Test fun readingAndSavedClocksAreIndependentAndMissingListsDoNotClear()=runBlocking<Unit> {
        val f=fixture();val state=f.seed();val id=state.getString("bookId")
        val saved=reading(id,clock=0).put("isRead",false).put("readingProgress",0).put("readingPositionBytes",0)
            .put("favoriteSaved",true).put("favoriteChangedAt",200).put("favoriteSortOrder",4)
        f.store.mergeReading(saved);f.store.mergeReading(reading(id,clock=100))
        val row=f.db.phoneArticleDao().getById("sample-0")!!;assertTrue(row.favoriteSaved);assertTrue(row.isRead);assertEquals(200,row.favoriteChangedAt)
        val unsaved=copy(saved).put("favoriteSaved",false).put("favoriteChangedAt",300)
        f.store.mergeReading(unsaved);val merged=f.store.readingStates().single();assertFalse(merged.getBoolean("favoriteSaved"));assertEquals(100,merged.getInt("readingPositionChangedAt"))
    }
    @Test fun fullLibraryOf5614ChaptersShrinksToOneBookManifestItem()=runBlocking<Unit> {
        val f=fixture();val state=f.seed(5614);val old=f.repo.getArticleManifestsForSync()
        val before=LibrarySyncPayload.buildManifestRequestFromEntries("phone",old,emptyList()).toString().toByteArray().size
        val after=JSONObject().put("action","syncBooks").put("version",1).put("deviceId","phone").put("phase","manifest").put("books",JSONArray(f.store.manifests())).toString().toByteArray().size
        assertEquals(5614,old.size);assertEquals(1,f.store.manifests().size);assertTrue(after<before/100)
        assertTrue(f.store.readingStates().isEmpty())
        val oldFrames=LibrarySyncPayload.buildManifestFrames(LibrarySyncPayload.buildManifestRequestFromEntries("phone",old,emptyList()))
        val oldWire=oldFrames.sumOf(BluetoothSyncProtocol::wireSize)
        fun bookFrame(books:List<JSONObject>)=JSONObject().put("action","syncBooks").put("version",1).put("deviceId","phone").put("phase","manifest").put("books",JSONArray(books))
        val oneWire=BluetoothSyncProtocol.wireSize(bookFrame(f.store.manifests()))
        println("R44_BOOK_ESTIMATE chapters=5614 bookCount=1 oldManifestBytes=$before bookManifestBytes=$after oldFrames=${oldFrames.size} oldWireBytes=$oldWire bookWireBytes=$oneWire")
        for(count in listOf(9,10)) {
            val books=(1..count).map{i->val fp=CloudSnapshotCodec.sha256("estimate-$i".toByteArray());copy(state).put("fingerprint",fp).put("bookId",BookSync.id(fp))
                .put("chapterCount",5614/count+if(i<=5614%count)1 else 0).put("title","测试书$i").put("sourceUrl",ImportedContentIds.txtNovelSourceUrl("sample-$i")).put("contentAvailable",true)}
            println("R44_BOOK_ESTIMATE chapters=5614 bookCount=$count bookWireBytes=${BluetoothSyncProtocol.wireSize(bookFrame(books))}")
        }
    }
    @Test fun sharedMergeVectorsAndReadingRegistersConverge()=runBlocking<Unit> {
        val vectors=JSONObject(requireNotNull(javaClass.getResource("/book/book-fingerprint.json")).readText()).getJSONArray("mergeCases")
        for(i in 0 until vectors.length()) {
            val row=vectors.getJSONObject(i)
            assertEquals(BookSync.canonical(row.getJSONObject("expected")),BookSync.canonical(BookSync.merge(row.getJSONObject("left"),row.getJSONObject("right"))))
        }
        val id=fixture().seed().getString("bookId")
        val a=reading(id,clock=100).put("sourceDeviceId","a").put("favoriteSaved",true).put("favoriteChangedAt",200).put("favoriteSortOrder",1)
        val b=reading(id,clock=300).put("sourceDeviceId","b")
        val c=reading(id,clock=0).put("sourceDeviceId","c").put("favoriteSaved",false).put("favoriteChangedAt",400).put("favoriteSortOrder",0)
        fun merge(x:JSONObject,y:JSONObject)=BookSync.mergeReading(x,y)
        fun json(x:JSONObject)=BookSync.canonical(x)
        assertEquals(json(merge(a,b)),json(merge(b,a)))
        assertEquals(json(merge(merge(a,b),c)),json(merge(a,merge(b,c))))
        val normalized=BookSync.mergeReading(null,a);assertEquals(json(normalized),json(merge(normalized,normalized)))
    }
    @Test fun lateLegacyChapterCannotReviveWholeBookOrPartialDeletion()=runBlocking<Unit> {
        val f=fixture();val state=f.seed();val original=f.db.phoneArticleDao().getById("sample-0")!!
        f.store.merge(copy(state).put("deletedChapters",JSONArray(listOf(1))).put("deletionClock",100))
        f.repo.mergeArticlesFromSync(listOf(original.copy(updatedAt=999,deleted=false,deletedAt=0)))
        assertTrue(f.db.phoneArticleDao().getById(original.articleId)!!.deleted)
        val cold=fixture();cold.store.merge(copy(state).put("deleted",true).put("deletedAt",200))
        val value=JSONObject(CloudRssStateCodec.encode(listOf(original.copy(updatedAt=999)),0).toString(Charsets.UTF_8)).getJSONArray("articles").getJSONObject(0)
        cold.backup.applyCloudRecord("article",value)
        assertTrue(cold.db.phoneArticleDao().getById(original.articleId)!!.deleted)
    }
    @Test fun replacingLocalRowsCanRematerializeAnAlreadyCachedBookPackage()=runBlocking<Unit> {
        val f=fixture();val state=f.seed();val archive=f.backup.createCloudPrivateArchive()
        f.backup.restorePrivateLibrary(archive,BackupImportMode.REPLACE)
        assertEquals(3,f.db.phoneArticleDao().getAllForSync().size);assertEquals(state.getString("fingerprint"),f.store.states().single().getString("fingerprint"))
    }
    @Test fun actualSqlite16To17MigrationPreservesArticlesAndValidatesRoomSchema()=runBlocking<Unit> {
        val name="book-migration-${System.nanoTime()}"
        val article=row("existing",ImportedContentIds.txtArticleUrl("one"),ImportedContentIds.ROOT_SOURCE_URL,"title","original")
        val first=Room.databaseBuilder(context,PhoneCompanionDatabase::class.java,name).allowMainThreadQueries().build()
        first.phoneArticleDao().upsert(article)
        first.openHelper.writableDatabase.apply {
            execSQL("DROP TABLE imported_books");execSQL("DROP TABLE imported_book_chapters");execSQL("DROP TABLE imported_book_reading");version=16
        }
        first.close()
        val migrated=Room.databaseBuilder(context,PhoneCompanionDatabase::class.java,name).addMigrations(PhoneCompanionDatabase.MIGRATION_16_17,PhoneCompanionDatabase.MIGRATION_17_18).allowMainThreadQueries().build()
        try {
            assertEquals(article,migrated.phoneArticleDao().getById("existing"));assertTrue(migrated.bookDao().all().isEmpty());assertEquals(18,migrated.openHelper.readableDatabase.version)
        } finally {migrated.close();context.deleteDatabase(name)}
    }

    @Test fun sameContentImportedUnderAnotherSourceDeduplicatesWithoutCreatingUnreadRecords()=runBlocking<Unit> {
        val f=fixture();val first=f.seed(key="first");val id=first.getString("bookId")
        val secondSource=ImportedContentIds.txtNovelSourceUrl("second")
        f.db.phoneRssSourceDao().upsert(PhoneRssSourceEntity(secondSource,"phone","测试书","",null,null,1,10,1,false,false,0))
        f.db.phoneArticleDao().upsertAll((0 until 3).map{i->row("second-$i",ImportedContentIds.txtNovelChapterUrl("second",i,"hash"),secondSource,"第${i+1}章","正文$i")})
        f.store.ensureRegistered()
        assertEquals(1,f.store.states().size);assertEquals(3,f.db.phoneArticleDao().getAllForSync().size)
        assertTrue(f.store.readingStates().isEmpty());assertEquals(id,f.store.bookForArticle("second-1")!!.bookId)
        assertNull(f.db.phoneRssSourceDao().getByUrl(secondSource))
    }
    @Test fun readingBatchesStayBoundedAndNonAdvancingRemotePageFails()=runBlocking<Unit> {
        val f=fixture();val id=f.seed(131).getString("bookId")
        for(i in 1..131)f.store.mergeReading(reading(id,i))
        val batches=mutableListOf<Int>()
        suspend fun respond(request:JSONObject)=when(request.getString("phase")) {
            "manifest"->reply("manifest").put("books",JSONArray(f.store.manifests())).put("missingBookIds",JSONArray())
            "reading"->{batches+=request.getJSONArray("states").length();readingReply()}
            else->error("unchanged book must not transfer body")
        }
        BookBluetoothSync(f.store,"phone",context.cacheDir,::respond).sync()
        assertEquals(listOf(100,31),batches)
        val failure=runCatching{BookBluetoothSync(f.store,"phone",context.cacheDir){request->
            if(request.getString("phase")=="reading")readingReply().put("hasMore",true)else respond(request)
        }.sync()}.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }
    @Test fun managerUsesBookStageAndExcludesChaptersOnlyWhenPeerDeclaredCapability()=runBlocking<Unit> {
        for(modern in listOf(true,false)) {
            val f=fixture();val state=f.seed()
            fun ok(action:String,phase:String?=null)=JSONObject().put("success",true).put("action",action).put("version",14).put("deviceId","watch").apply{phase?.let{put("phase",it)}}
            val manifest=LibrarySyncPayload.buildManifestRequestFromEntries("watch",emptyList(),emptyList()).put("success",true)
                .put("supportsReaderPresets",false).put("supportsNoteManifestV2",false).put("supportsNoteAssetInventory",false).put("supportsBookSyncV1",modern)
            val frames=buildList {
                add(ok("syncLibrary").put("supportsBookSyncV1",modern))
                if(modern){add(reply("manifest").put("books",JSONArray().put(copy(state).put("contentAvailable",true))).put("missingBookIds",JSONArray()));add(readingReply())}
                add(ok("syncLibrary","cursor"));add(manifest);add(ok("syncLibrary","complete").put("articles",JSONArray()))
                add(ok("syncNotes").put("notes",JSONArray()));add(ok("syncLlmTokenUsage").put("records",JSONArray()));add(BluetoothSyncProtocol.buildSessionControlResponse(14,"complete"))
            }
            val input=ByteArrayOutputStream().also{out->frames.forEach{BluetoothSyncProtocol.writeFrame(out,it)}}
            val output=ByteArrayOutputStream();val log=com.lightningstudio.watchrss.phone.data.log.BluetoothDebugLog(context)
            val device=PhoneBluetoothWatchDevice("watch","11:22:33:44:55:66",1,"watch",supportsPersistentSession=true)
            val session=PhoneSyncSession(PhoneBluetoothSyncClient(context,log),device,"phone",PhoneSyncSession.Transport(ByteArrayInputStream(input.toByteArray()),output,"test",false,{}),true,false,false)
            val manager=PhoneBluetoothSyncManager(context,f.repo,com.lightningstudio.watchrss.phone.data.note.NoteRepository(f.db.noteDao(),"phone"),f.reader,
                PhoneLlmTokenUsageRepository(f.db.llmTokenUsageDao()),"phone",log)
            manager.syncAll(device,reusableSession=session)
            val wire=ByteArrayInputStream(output.toByteArray());val sent=buildList{while(wire.available()>0)add(BluetoothSyncProtocol.readFrame(wire))}
            val library=sent.single{it.optString("action")=="syncLibrary"&&it.optString("phase")=="manifest"}
            assertEquals(if(modern)0 else 3,library.getJSONArray("articleManifest").length())
            assertEquals(if(modern)1 else 0,sent.count{it.optString("action")=="syncBooks"&&it.optString("phase")=="manifest"})
            if(modern)assertEquals(0,library.getJSONArray("rssSources").length())
        }
    }

    @Test fun singleTxtAndEpubRestoreKeepOriginalChannelLayoutAndHtml()=runBlocking<Unit> {
        val original=fixture()
        val txt=row("txt",ImportedContentIds.txtArticleUrl("text"),ImportedContentIds.ROOT_SOURCE_URL,"TXT标题","TXT正文")
        val epub=row("epub",ImportedContentIds.epubChapterUrl("epub",0,"first"),ImportedContentIds.epubSourceUrl("epub"),"EPUB章节","EPUB正文").copy(contentHtml="<p>EPUB正文</p>")
        original.db.phoneArticleDao().upsertAll(listOf(txt,epub));original.store.ensureRegistered()
        val target=fixture()
        for(state in original.store.states())target.store.receivePackage(state.getString("bookId"),original.store.packageFile(state.getString("bookId"))!!)
        val restored=target.db.phoneArticleDao().getAllForSync()
        assertTrue(restored.all{!it.independentSaved});assertEquals(ImportedContentIds.ROOT_SOURCE_URL,restored.single{it.url==txt.url}.rssSourceUrl)
        assertEquals(epub.contentHtml,restored.single{it.url==epub.url}.contentHtml)
        assertEquals(2,target.db.phoneRssSourceDao().getAllForSync().size)
    }

}
