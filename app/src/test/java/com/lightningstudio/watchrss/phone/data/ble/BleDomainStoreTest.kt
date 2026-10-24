package com.lightningstudio.watchrss.phone.data.ble

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import com.lightningstudio.watchrss.phone.data.db.*
import com.lightningstudio.watchrss.phone.data.book.*
import com.lightningstudio.watchrss.phone.data.repo.PhoneCompanionRepository
import com.lightningstudio.watchrss.phone.connection.bluetooth.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.json.JSONObject
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class BleDomainStoreTest {
    private val context:Context=RuntimeEnvironment.getApplication()
    private val directory=File(context.cacheDir,"ble-fast-${System.nanoTime()}").also{it.mkdirs()}
    private val db=Room.inMemoryDatabaseBuilder(context,PhoneCompanionDatabase::class.java).allowMainThreadQueries().build()
    private val store=BleDomainStore(db,directory)
    private fun roots()=store.roots("account")
    private fun article(id:String="a",saved:Boolean=true)=PhoneArticleEntity(id,"phone","https://example.org/$id","title","site","",null,"body",null,"hash",1,100,false,0,0,"https://example.org/rss","rss",saved,if(saved)100 else 0,1,false,0,0,false,0)
    private fun repo()=PhoneCompanionRepository(db.phoneSavedItemDao(),db.phoneArticleDao(),db.phoneRssSourceDao(),"phone",db.syncChangeLogDao(),db.syncPeerStateDao(),appMetaDao=db.appMetaDao(),bleDomains=store)
    @Before fun registeredEmpty()=runBlocking<Unit>{db.bookReadingDao().putRegistration(BookCaptureMetaEntity(checkedRevision=0))}
    @After fun close(){db.close();directory.deleteRecursively()}
    @Test fun trueBusinessWritesEvenEqualClockRevertsChangeRootButTechnicalWritesDoNot()=runBlocking<Unit> {
        roots();val row=article();db.phoneArticleDao().upsert(row);val first=roots()
        db.phoneArticleDao().upsert(row);assertEquals(first,roots())
        db.phoneArticleDao().upsert(row.copy(syncMetadataHash="technical"));assertEquals(first,roots())
        db.phoneArticleDao().upsert(row.copy(favoriteSaved=false));val second=roots();assertNotEquals(first["library"],second["library"])
        db.phoneArticleDao().upsert(row);assertNotEquals(first["library"],roots()["library"])
        val beforeTechnical=roots();db.appMetaDao().set(AppMetaEntity("cloud_generation","2"));assertEquals(beforeTechnical,roots())
    }
    @Test fun ordinaryUnsavedRssRefreshDoesNotDirtyLibrary()=runBlocking<Unit> {
        val before=roots();db.phoneArticleDao().upsert(article(saved=false));assertEquals(before,roots())
        db.phoneArticleDao().upsert(article(saved=false).copy(contentText="refreshed",contentHash="new",updatedAt=500));assertEquals(before,roots())
    }
    @Test fun chapterReadingOnlyDirtiesReadingDomain()=runBlocking<Unit> {
        roots();db.phoneArticleDao().upsert(article());db.bookDao().putChapters(listOf(BookChapterEntity("book",1,"a")))
        val before=roots();db.phoneArticleDao().upsert(article().copy(isRead=true,readingPositionChangedAt=101));val after=roots()
        assertEquals(before["library"],after["library"]);assertEquals(before["books"],after["books"]);assertNotEquals(before["bookReading"],after["bookReading"])
    }
    @Test fun rollbackDoesNotAdvanceAnyDomain()=runBlocking<Unit> {
        val before=roots();runCatching{db.withTransaction{db.phoneArticleDao().upsert(article());error("rollback")}};assertEquals(before,roots())
    }
    @Test fun receiptScopePeerAndEpochResetAreIsolated() {
        val r=roots();val pair=r.getValue("library") to "b".repeat(64);store.publish("s","watch",mapOf("library" to pair))
        assertEquals(pair,BleDomainStore(db,directory).receipts("s","watch")["library"])
        assertTrue(store.receipts("other-account","watch").isEmpty());assertTrue(store.receipts("s","other-watch").isEmpty())
        db.openHelper.writableDatabase.execSQL("UPDATE ble_fast_versions SET epoch='ffffffffffffffffffffffffffffffff' WHERE domain='library'")
        assertNotEquals(r["library"],roots()["library"])
    }
    @Test fun resourceDeletionAndSameSizeModificationInvalidateDomainAcrossRestart() {
        val folder=File(directory,"reader_assets/fonts").also{it.mkdirs()};val f=File(folder,"font.ttf").also{it.writeText("one")}
        val before=roots();f.writeText("two");f.setLastModified(f.lastModified()+3000);val second=BleDomainStore(db,directory).roots("account")
        assertNotEquals(before["reader"],second["reader"]);f.delete();assertNotEquals(second["reader"],roots()["reader"])
        assertEquals(before["notes"],second["notes"])
    }
    @Test fun manifestCachePublishesOnlyFrozenDirtyRevision()=runBlocking<Unit> {
        roots();db.phoneArticleDao().upsert(article());val pending=store.pending().single()
        db.phoneArticleDao().upsert(article().copy(isRead=true))
        assertFalse(store.publishManifest(pending.first,pending.second,null));assertEquals(1,store.pending().size)
    }
    @Test fun deltaZeroDoesNotHydrateStableCandidatesAndOneDirtyRowSurvivesRestart()=runBlocking<Unit> {
        roots();db.phoneArticleDao().upsertAll((1..20).map{article("a$it")})
        val repo=repo();val first=repo.prepareLibrarySyncWindow("watch",0,true);assertEquals(20,first.articleManifest.size);assertTrue(store.pending().isEmpty())
        repo.markLibrarySyncSuccess("watch",first.toSeqInclusive,0,14,true,true,first.projectionDefinition)
        val second=repo.prepareLibrarySyncWindow("watch",first.toSeqInclusive,true)
        assertFalse(second.fullSnapshot);assertTrue(second.articleManifest.isEmpty());assertTrue(store.pending().isEmpty());assertEquals(20,second.fullArticleManifest.size)
        db.phoneArticleDao().upsert(article("a1").copy(favoriteSaved=false))
        val third=repo.prepareLibrarySyncWindow("watch",first.toSeqInclusive,true)
        assertEquals(listOf("a1"),third.articleManifest.map{it.articleId});assertEquals(20,third.fullArticleManifest.size)
        assertEquals(20,BleDomainStore(db,directory).manifests().size)
    }
    @Test fun versionCounterOverflowRotatesEpoch()=runBlocking<Unit> {
        roots();db.openHelper.writableDatabase.execSQL("UPDATE ble_fast_versions SET revision=9007199254740991 WHERE domain='library'")
        val before=roots();db.phoneArticleDao().upsert(article());assertNotEquals(before["library"],roots()["library"])
    }    @Test fun identityHashesPersistAndOnlyChangedUrlsAreRebuilt()=runBlocking<Unit> {
        roots();db.phoneArticleDao().upsertAll(listOf(article("a"),article("b")))
        val first=store.identities();assertEquals(2,first.legacy.size)
        assertEquals(first.legacy,BleDomainStore(db,directory).identities().legacy)
        db.phoneArticleDao().upsert(article("a").copy(url="https://example.org/changed"))
        val second=store.identities();assertNotEquals(first.legacy["a"],second.legacy["a"]);assertEquals(first.legacy["b"],second.legacy["b"])
    }
    @Test fun seedingExistingCacheDoesNotInventBusinessChangesOrAdvanceCursor()=runBlocking<Unit> {
        val row=article();db.phoneArticleDao().upsert(row)
        db.syncChangeLogDao().insert(SyncChangeLogEntity(kind="article",entityId=row.articleId,changedAt=100,originDeviceId="phone",reason="existing",createdAt=100))
        db.appMetaDao().set(AppMetaEntity("ble_repair_watermark_v2","100"))
        val r=repo();val seq=db.syncChangeLogDao().maxSeq();r.markLibrarySyncSuccess("watch",seq,0,14,true,true)
        val result=r.prepareLibrarySyncWindow("watch",seq,true)
        assertEquals(seq,result.toSeqInclusive);assertTrue(result.fullSnapshot);assertEquals("projectionChanged",result.fallbackReason);assertEquals(1,result.fullArticleManifest.size)
        r.markLibrarySyncSuccess("watch",seq,0,14,true,true,result.projectionDefinition)
        val delta=r.prepareLibrarySyncWindow("watch",seq,true)
        assertTrue(delta.articleManifest.isEmpty());assertEquals(seq,delta.toSeqInclusive)
    }
    @Test fun bookRootIgnoresRssCacheAndTracksMappedSourceAndPackageLoss()=runBlocking<Unit> {
        val initial=roots();db.phoneArticleDao().upsert(article(saved=false));assertEquals(initial["books"],roots()["books"])
        val source="https://watchrss.local/import-epub/test"
        db.bookDao().put(BookEntity("book",JSONObject().put("sourceUrl",source).toString()))
        val before=roots()
        db.phoneRssSourceDao().upsert(PhoneRssSourceEntity(source,"phone","title","",null,null,1,1,1,false,false,0))
        val after=roots();assertNotEquals(before["books"],after["books"])
        val file=File(directory,"imported_books/book.jsonl");file.parentFile!!.mkdirs();file.writeText("original")
        val available=roots();assertNotEquals(after["books"],available["books"]);file.delete();assertNotEquals(available["books"],roots()["books"])
    }
    @Test fun registrationRevisionInvalidatesBooksButTechnicalCheckedFlagDoesNot()=runBlocking<Unit> {
        val before=roots()
        db.bookReadingDao().putRegistration(BookCaptureMetaEntity(registrationRevision=2,checkedRevision=1))
        val pending=roots();assertNotEquals(before["books"],pending["books"]);assertNotEquals(before["bookReading"],pending["bookReading"])
        db.bookReadingDao().putRegistration(BookCaptureMetaEntity(registrationRevision=2,checkedRevision=2));assertEquals(pending,roots())
        db.openHelper.writableDatabase.execSQL("DELETE FROM book_capture_meta")
        assertFalse(roots().containsKey("books"));assertFalse(roots().containsKey("bookReading"))
    }
    @Test fun mappedPhysicalDeletionInvalidatesBooksBeforeProjectionIsCaptured()=runBlocking<Unit> {
        roots();db.phoneArticleDao().upsert(article());db.bookDao().putChapters(listOf(BookChapterEntity("book",1,"a")))
        val before=roots();db.phoneArticleDao().upsert(article().copy(deleted=true,deletedAt=200));assertNotEquals(before["books"],roots()["books"])
    }

    @Test fun concurrentWriteCannotBeAcknowledgedBeyondFrozenManifest()=runBlocking<Unit> {
        roots()
        val row=article().copy(contentText="marker")
        db.phoneArticleDao().upsert(row)
        val writerScope=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()+kotlinx.coroutines.Dispatchers.Default)
        val done=java.util.concurrent.CountDownLatch(1)
        var writer:kotlinx.coroutines.Job?=null
        var completedDuringExport=false
        val once=java.util.concurrent.atomic.AtomicBoolean()
        val body=object:com.lightningstudio.watchrss.phone.data.local.ArticleContentStore {
            override fun markerFor(articleId:String)="marker"
            override fun isMarker(value:String)=value=="marker"
            override fun storeText(articleId:String,text:String)=text
            override fun loadText(marker:String):String {
                if(once.compareAndSet(false,true)) {
                    writer=writerScope.launch {
                        db.phoneArticleDao().upsert(row.copy(title="late",updatedAt=200))
                        db.syncChangeLogDao().insert(SyncChangeLogEntity(kind="article",entityId=row.articleId,changedAt=200,originDeviceId="phone",reason="late",createdAt=200))
                        done.countDown()
                    }
                    completedDuringExport=done.await(200,java.util.concurrent.TimeUnit.MILLISECONDS)
                }
                return "body"
            }
            override fun textChunkHandle(marker:String,chunkBytes:Int)=com.lightningstudio.watchrss.phone.data.local.StoredTextChunkHandle(marker,4,chunkBytes,1)
            override fun loadTextChunk(marker:String,chunkIndex:Int,chunkBytes:Int)="body"
        }
        try {
            val r=PhoneCompanionRepository(db.phoneSavedItemDao(),db.phoneArticleDao(),db.phoneRssSourceDao(),"phone",db.syncChangeLogDao(),db.syncPeerStateDao(),articleContentStore=body,appMetaDao=db.appMetaDao(),bleDomains=store)
            val frozen=r.prepareLibrarySyncWindow("watch",0,true)
            requireNotNull(writer).join()
            assertFalse(completedDuringExport)
            assertEquals(100L,frozen.articleManifest.single().updatedAt)
            assertTrue(db.syncChangeLogDao().maxSeq()>frozen.toSeqInclusive)
            assertEquals(200L,r.prepareLibrarySyncWindow("watch",0,true).articleManifest.single().updatedAt)
        } finally {writerScope.cancel()}
    }
    @Test fun metadataOnlyChangeReusesBodyHashWithoutReadingExternalBody()=runBlocking<Unit> {
        roots();var reads=0
        val body=object:com.lightningstudio.watchrss.phone.data.local.ArticleContentStore {
            override fun markerFor(articleId:String)="marker"
            override fun isMarker(value:String)=value=="marker"
            override fun storeText(articleId:String,text:String)=text
            override fun loadText(marker:String):String {reads++;return "body"}
            override fun textChunkHandle(marker:String,chunkBytes:Int)=com.lightningstudio.watchrss.phone.data.local.StoredTextChunkHandle(marker,4,chunkBytes,1)
            override fun loadTextChunk(marker:String,chunkIndex:Int,chunkBytes:Int)="body"
        }
        val r=PhoneCompanionRepository(db.phoneSavedItemDao(),db.phoneArticleDao(),db.phoneRssSourceDao(),"phone",db.syncChangeLogDao(),db.syncPeerStateDao(),articleContentStore=body,appMetaDao=db.appMetaDao(),bleDomains=store)
        val row=article().copy(contentText="marker");db.phoneArticleDao().upsert(row)
        val first=r.prepareLibrarySyncWindow("watch",0,true).articleManifest.single();val before=reads
        db.phoneArticleDao().upsert(row.copy(favoriteSaved=false))
        assertFalse(store.pending().single().third)
        val next=r.prepareLibrarySyncWindow("watch",0,true).articleManifest.single()
        assertEquals(before,reads);assertEquals(next,r.bluetoothManifestsForRequests(listOf(row.articleId)).single());assertEquals(before,reads);assertEquals(first.bodyHash,next.bodyHash);assertNotEquals(first.metadataHash,next.metadataHash)
        val changed=row.copy(contentText="changed body",contentHash="new hash")
        db.phoneArticleDao().upsert(changed);db.phoneArticleDao().upsert(changed.copy(title="metadata after body"))
        assertTrue(store.pending().single().third)
        assertNotEquals(first.bodyHash,r.prepareLibrarySyncWindow("watch",0,true).articleManifest.single().bodyHash)
    }
    @Test fun unversionedTriggerReinstallationInvalidatesReceiptsAndMigratesDirtyShape()=runBlocking<Unit> {
        val before=roots();store.publish("scope","watch",mapOf("library" to(before.getValue("library") to "b".repeat(64))))
        val raw=db.openHelper.writableDatabase
        raw.execSQL("DELETE FROM ble_fast_schema")
        raw.execSQL("DROP TABLE ble_fast_dirty")
        raw.execSQL("CREATE TABLE ble_fast_dirty(articleId TEXT PRIMARY KEY NOT NULL,seq INTEGER NOT NULL)")
        val upgraded=BleDomainStore(db,directory);assertNotEquals(before,upgraded.roots("account"));assertTrue(upgraded.receipts("scope","watch").isEmpty())
        db.phoneArticleDao().upsert(article());assertTrue(upgraded.pending().single().third)
    }
    @Test fun missingReferencedReaderFileIsUnknownInsteadOfEligibleForSkip()=runBlocking<Unit> {
        roots();val sha=BleDomainStore.hash("abc")
        db.readerPresetDao().upsertFont(com.lightningstudio.watchrss.phone.data.reader.ReaderFontAssetEntity(sha,sha,"font","font","$sha.ttf","font/ttf",3,1,"{}",1,"phone",false))
        assertFalse(roots().containsKey("reader"))
        val file=File(directory,"reader_assets/fonts/$sha.ttf");file.parentFile!!.mkdirs();file.writeText("abc")
        assertTrue(roots().containsKey("reader"));file.delete();assertFalse(roots().containsKey("reader"))
    }
    @Test fun bodyRemovedDuringRemoteRoundTripCannotBeReusedFromManifestCache()=runBlocking<Unit> {
        val files=com.lightningstudio.watchrss.phone.data.local.FileArticleContentStore(File(directory,"imported_text"))
        val marker=files.storeText("a","original body")
        roots();db.phoneArticleDao().upsert(article().copy(contentText=marker))
        val r=PhoneCompanionRepository(db.phoneSavedItemDao(),db.phoneArticleDao(),db.phoneRssSourceDao(),"phone",db.syncChangeLogDao(),db.syncPeerStateDao(),articleContentStore=files,appMetaDao=db.appMetaDao(),bleDomains=store)
        assertTrue(r.prepareLibrarySyncWindow("watch",0,true).articleManifest.single().bodyAvailable)
        File(directory,"imported_text").listFiles()!!.forEach{it.delete()}
        assertFalse(r.bluetoothManifestsForRequests(listOf("a")).single().bodyAvailable)
    }
    @Test fun corruptedTechnicalVersionAndReceiptOnlyRemoveAffectedProof() {
        val before=roots();store.publish("scope","watch",mapOf("library" to(before.getValue("library") to "b".repeat(64))))
        val raw=db.openHelper.writableDatabase
        raw.execSQL("UPDATE ble_fast_versions SET revision=-1 WHERE domain='books'")
        raw.execSQL("INSERT OR REPLACE INTO ble_fast_receipts VALUES('scope','watch','reader','invalid','also-invalid',1)")
        val after=roots();assertFalse(after.containsKey("books"));assertEquals(before["library"],after["library"])
        assertEquals(setOf("library"),store.receipts("scope","watch").keys)
        assertNotEquals(before["books"],roots()["books"]) // repaired epoch cannot certify an old number
    }

}
