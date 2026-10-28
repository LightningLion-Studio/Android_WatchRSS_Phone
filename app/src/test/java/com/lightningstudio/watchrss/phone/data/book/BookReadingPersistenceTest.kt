package com.lightningstudio.watchrss.phone.data.book

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import com.lightningstudio.watchrss.phone.cloud.CloudSnapshotCodec
import com.lightningstudio.watchrss.phone.data.db.*
import com.lightningstudio.watchrss.phone.data.model.ImportedContentIds
import com.lightningstudio.watchrss.phone.data.local.FileArticleContentStore
import kotlinx.coroutines.runBlocking
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class BookReadingPersistenceTest {
    private val context:Context=RuntimeEnvironment.getApplication()
    private val queries=java.util.Collections.synchronizedList(mutableListOf<String>())
    private fun querySnapshot()=synchronized(queries){queries.toList()}
    private val dbs=mutableListOf<PhoneCompanionDatabase>()
    private fun db(name:String?=null):PhoneCompanionDatabase {
        val builder=if(name==null)Room.inMemoryDatabaseBuilder(context,PhoneCompanionDatabase::class.java)else Room.databaseBuilder(context,PhoneCompanionDatabase::class.java,name)
        return builder.allowMainThreadQueries().setQueryCallback({sql,_->queries+=sql},Executor{it.run()}).build().also{dbs+=it}
    }
    private fun store(db:PhoneCompanionDatabase)=ImportedBookStore(db,File(context.cacheDir,"reading-${System.nanoTime()}"),null,"phone")
    private val id=BookSync.id("a".repeat(64))
    private fun state(index:Int=1,clock:Long=100,source:String="watch")=JSONObject().put("bookId",id).put("chapterIndex",index)
        .put("isRead",true).put("readingProgress",0.123457).put("readingPositionBytes",42).put("readingPositionContentHash","hash")
        .put("readingPositionChangedAt",clock).put("sourceDeviceId",source)
    private fun article(index:Int=1)=PhoneArticleEntity("chapter-$index","phone",ImportedContentIds.txtNovelChapterUrl("test",index-1,"h"),"chapter $index","book","",null,"text",null,"hash",1,1,false,0,0,
        ImportedContentIds.txtNovelSourceUrl("test"),"book",false,0,0,false,0,0,false,0)
    private suspend fun mapped(db:PhoneCompanionDatabase,count:Int=1) {
        val book=JSONObject().put("bookId",id).put("fingerprint","a".repeat(64)).put("title","book").put("chapterCount",count).put("deletedChapters",JSONArray()).put("deletionClock",0).put("deleted",false).put("deletedAt",0).put("updatedAt",0).put("sourceDeviceId","phone")
        db.bookDao().put(BookEntity(id,BookSync.canonical(book)))
        db.phoneArticleDao().upsertAll((1..count).map(::article));db.bookDao().putChapters((1..count).map{BookChapterEntity(id,it,"chapter-$it")})
    }
    @After fun close(){dbs.forEach{if(it.isOpen)it.close()}}

    @Test fun everySharedLeafBookAndRootVectorMatches() {
        val vectors=JSONObject(javaClass.getResource("/book/book-reading-delta.json")!!.readText()).getJSONArray("cases")
        for(i in 0 until vectors.length()) {
            val v=vectors.getJSONObject(i);val states=v.getJSONArray("states")
            val leaves=(0 until states.length()).map{states.getJSONObject(it)}.associate{(it.getString("bookId") to it.getInt("chapterIndex")) to BookReadingHash.leaf(it)}
            val expected=v.getJSONArray("leaves")
            for(j in 0 until expected.length()){val e=expected.getJSONObject(j);assertEquals(v.getString("name"),e.getString("hash"),leaves[e.getString("bookId") to e.getInt("chapterIndex")])}
            val roots=leaves.entries.groupBy{it.key.first}.map{(book,rows)->BookReadingRootEntity(book,BookReadingHash.book(book,rows.map{it.key.second to it.value}),rows.size.toLong())}
            assertEquals(v.getString("name"),v.getString("root"),BookReadingHash.root(roots))
            val books=v.getJSONArray("books");for(j in 0 until books.length()){val b=books.getJSONObject(j);assertEquals(b.getString("root"),roots.single{it.bookId==b.getString("bookId")}.root)}
        }
    }
    @Test fun latestPerKeyFrozenJournalDoesNotBorrowPostWindowState()=runBlocking<Unit> {
        val db=db();val s=store(db)
        s.mergeReading(state());val frozen=s.readingSummary()
        s.mergeReading(state(clock=200).put("isRead",false))
        val old=s.readingEntries(0,frozen.seq).single();assertTrue(old.state.getBoolean("isRead"));assertEquals(100,old.state.getInt("readingPositionChangedAt"))
        val next=s.readingEntries(frozen.seq,s.readingSummary().seq).single();assertFalse(next.state.getBoolean("isRead"));assertTrue(next.seq>frozen.seq)
        assertEquals(1,s.readingEntries(0,next.seq).size)
    }
    @Test fun sqlReadUnreadSaveAndCancelAreDirtyAndIndependentSourcesSurviveProjection()=runBlocking<Unit> {
        val db=db();mapped(db);val s=store(db);s.readingSummary()
        s.mergeReading(state().put("favoriteSaved",true).put("favoriteChangedAt",100).put("favoriteSortOrder",4).put("favoriteSourceDeviceId","favorite-watch"))
        val first=s.readingSummary()
        db.openHelper.writableDatabase.execSQL("UPDATE phone_articles SET favoriteSaved=0,favoriteChangedAt=200,sourceDeviceId='phone' WHERE articleId='chapter-1'")
        val cancelled=s.readingSummary();assertTrue(cancelled.seq>first.seq)
        val v=s.readingEntries(first.seq,cancelled.seq).single().state
        assertFalse(v.getBoolean("favoriteSaved"));assertEquals("phone",v.getString("favoriteSourceDeviceId"));assertEquals("watch",v.getString("sourceDeviceId"))
        db.openHelper.writableDatabase.execSQL("UPDATE phone_articles SET isRead=0,readingProgress=0,readingPositionBytes=0,readingPositionChangedAt=300,sourceDeviceId='phone' WHERE articleId='chapter-1'")
        val unread=s.readingSummary();assertTrue(unread.seq>cancelled.seq)
        assertFalse(s.readingEntries(cancelled.seq,unread.seq).single().state.getBoolean("isRead"))
        val hashes=s.readingHashCounts
        assertEquals(unread,s.readingSummary());assertEquals(hashes,s.readingHashCounts)
        s.mergeReading(s.readingEntries(0,unread.seq).single().state);assertEquals(unread,s.readingSummary())
    }
    @Test fun pendingLocalUnreadIsCapturedBeforeRemoteOlderReadAndMissingListFieldsNeverDelete()=runBlocking<Unit> {
        val db=db();mapped(db);val s=store(db);s.readingSummary();s.mergeReading(state())
        db.openHelper.writableDatabase.execSQL("UPDATE phone_articles SET isRead=0,readingPositionChangedAt=300,favoriteSaved=0,favoriteChangedAt=400,sourceDeviceId='phone' WHERE articleId='chapter-1'")
        s.mergeReading(state(clock=200))
        val latest=s.readingEntries(0,s.readingSummary().seq).single().state
        assertFalse(latest.getBoolean("isRead"));assertFalse(latest.getBoolean("favoriteSaved"));assertEquals(400,latest.getInt("favoriteChangedAt"))
    }
    @Test fun deletionWinsAndRepeatProjectionDoesNotIncrementJournal()=runBlocking<Unit> {
        val db=db();mapped(db);val s=store(db);s.readingSummary()
        s.mergeReading(state().put("favoriteSaved",true).put("favoriteChangedAt",100).put("favoriteSortOrder",0))
        db.openHelper.writableDatabase.execSQL("UPDATE phone_articles SET deleted=1,deletedAt=500 WHERE articleId='chapter-1'")
        assertFalse(s.readingEntries(0,s.readingSummary().seq).single().state.getBoolean("favoriteSaved"))
        s.mergeReading(state().put("favoriteSaved",true).put("favoriteChangedAt",100).put("favoriteSortOrder",0))
        assertFalse(db.phoneArticleDao().getById("chapter-1")!!.favoriteSaved)
        assertFalse(s.readingEntries(0,s.readingSummary().seq).single().state.getBoolean("favoriteSaved"))
        assertTrue(BookSync.deletions(JSONObject(db.bookDao().get(id)!!.stateJson)).contains(1))
        val before=s.readingSummary();s.mergeReading(state().put("favoriteSaved",true).put("favoriteChangedAt",100).put("favoriteSortOrder",0));assertEquals(before,s.readingSummary())
    }
    @Test fun initializationPreserves5500StatesAndRestartReadsNoChapterStatesOrHashes()=runBlocking<Unit> {
        val name="r46-persist-${System.nanoTime()}";val db=db(name)
        db.withTransaction {for(i in 1..5500)db.bookDao().putReading(BookReadingEntity(id,i,BookSync.canonical(state(i))))}
        val s=store(db);val first=s.readingSummary();assertEquals(5500L,first.count)
        val leaf=s.readingHashCounts;assertEquals(5500L,leaf[0]);assertEquals(1L,leaf[1]);assertEquals(1L,leaf[2])
        db.close();val reopened=db(name);val cold=store(reopened);queries.clear()
        assertEquals(first,cold.readingSummary());assertEquals(listOf(0L,0L,0L),cold.readingHashCounts)
        assertTrue(querySnapshot().none{it.startsWith("SELECT") && (it.contains("FROM imported_book_reading")||it.contains("FROM phone_articles")||it.contains("FROM imported_book_chapters")||it.contains("FROM book_reading_versions"))})
        cold.mergeReading(state(5000,clock=200));val changed=cold.readingSummary();assertEquals(5500L,changed.count);assertEquals(first.seq+1,changed.seq)
        assertEquals(listOf(1L,1L,1L),cold.readingHashCounts)
        reopened.close();context.deleteDatabase(name)
    }
    @Test fun readEditDoesNotInventAbsentSavedFieldsOrOverwriteTheirIndependentOrigin()=runBlocking<Unit> {
        val db=db();mapped(db);val s=store(db);s.readingSummary();s.mergeReading(state())
        db.openHelper.writableDatabase.execSQL("UPDATE phone_articles SET readingPositionChangedAt=200,isRead=0,sourceDeviceId='phone' WHERE articleId='chapter-1'")
        val value=s.readingEntries(0,s.readingSummary().seq).single().state
        assertFalse(value.has("favoriteSaved"));assertFalse(value.has("watchLaterSaved"));assertEquals("phone",value.getString("sourceDeviceId"))
    }
    @Test fun cachedRegistrationAndSummaryNeverRescanUnchangedLibraryAcrossRestart()=runBlocking<Unit> {
        val name="r46-registration-${System.nanoTime()}";val db=db(name)
        val directory=File(context.cacheDir,"registered-${System.nanoTime()}")
        val s=ImportedBookStore(db,directory,null,"phone")
        db.phoneArticleDao().upsertAll((1..101).map(::article));s.ensureRegistered();val first=s.manifests()
        s.readingSummary();db.close()
        val coldDb=db(name);val cold=ImportedBookStore(coldDb,directory,null,"phone");queries.clear()
        assertEquals(first.map{it.toString()},cold.manifests().map{it.toString()});cold.readingSummary()
        assertEquals(listOf(0L,0L,0L),cold.readingHashCounts)
        assertTrue(querySnapshot().none{it.startsWith("SELECT") && (it.contains("FROM phone_articles")||it.contains("FROM imported_book_chapters")||it.contains("FROM imported_book_reading"))})
        coldDb.close();context.deleteDatabase(name);directory.deleteRecursively()
    }
    @Test fun structuralRegistrationFailureIsCachedUntilBusinessRevisionChanges()=runBlocking<Unit> {
        val db=db();val s=store(db)
        db.phoneArticleDao().upsertAll(listOf(article(1),article(3)))
        s.ensureRegistered();queries.clear();s.ensureRegistered()
        assertTrue(querySnapshot().none{it.contains("NOT EXISTS(SELECT 1 FROM imported_book_chapters")})
        assertEquals(1,s.migrationWarnings.size)
        db.phoneArticleDao().upsert(article(2));s.ensureRegistered();assertEquals(1,db.bookDao().all().size)
    }
    @Test fun dirtyChapterDeletionIsNotOverwrittenByConcurrentRemoteBookMetadata()=runBlocking<Unit> {
        val db=db();mapped(db);val s=store(db);s.readingSummary()
        db.openHelper.writableDatabase.execSQL("UPDATE phone_articles SET deleted=1,deletedAt=500 WHERE articleId='chapter-1'")
        val remote=JSONObject(db.bookDao().get(id)!!.stateJson).put("title","remote title").put("updatedAt",1000).put("sourceDeviceId","watch")
        s.merge(remote)
        val result=JSONObject(db.bookDao().get(id)!!.stateJson)
        assertEquals("remote title",result.getString("title"));assertTrue(BookSync.deletions(result).contains(1));assertEquals(500,result.getInt("deletionClock"))
    }
    @Test fun physicalSourceAliasEditsStillUpdateBookWithoutWarmChapterScans()=runBlocking<Unit> {
        val db=db();mapped(db);val s=store(db)
        val localSource=ImportedContentIds.txtNovelSourceUrl("test")
        db.phoneRssSourceDao().upsert(PhoneRssSourceEntity(localSource,"phone","initial","",null,null,1,1,1,false,false,0))
        s.captureLocalChanges()
        val remote=JSONObject(db.bookDao().get(id)!!.stateJson).put("sourceUrl","https://watchrss.local/import-txt-novel/remote").put("title","remote").put("updatedAt",100).put("sourceDeviceId","watch")
        s.merge(remote)
        db.phoneRssSourceDao().upsert(db.phoneRssSourceDao().getByUrl(localSource)!!.copy(title="local renamed",updatedAt=200))
        s.captureLocalChanges();assertEquals("local renamed",JSONObject(db.bookDao().get(id)!!.stateJson).getString("title"))
        queries.clear();s.captureLocalChanges();assertTrue(querySnapshot().none{it.startsWith("SELECT")&&it.contains("FROM imported_book_chapters")})
    }
    @Test fun temporaryMissingOriginalFileRetriesWithoutAnyDatabaseRevisionChange()=runBlocking<Unit> {
        val db=db();val originalDir=File(context.cacheDir,"original-${System.nanoTime()}")
        val files=FileArticleContentStore(originalDir);val marker=files.storeText("original","temporary original")
        val original=originalDir.walkTopDown().single{it.isFile};val bytes=original.readBytes();original.delete()
        db.phoneArticleDao().upsert(article().copy(contentText=marker))
        val directory=File(context.cacheDir,"retry-registration-${System.nanoTime()}")
        val s=ImportedBookStore(db,directory,files,"phone")
        s.ensureRegistered();assertTrue(db.bookDao().all().isEmpty());assertEquals(1,s.migrationWarnings.size)
        original.writeBytes(bytes);s.ensureRegistered();assertEquals(1,db.bookDao().all().size);assertTrue(s.migrationWarnings.isEmpty())
        originalDir.deleteRecursively();directory.deleteRecursively()
    }
    @Test fun failedPackageRepairDoesNotInvalidateFrozenBookDirectoryWitness()=runBlocking<Unit> {
        val db=db();val files=File(context.cacheDir,"stable-books-${System.nanoTime()}").apply{mkdirs()}
        val directory=File(files,"imported_books").apply{mkdirs()}
        mapped(db);db.phoneArticleDao().upsert(article().copy(contentText=""))
        val books=ImportedBookStore(db,directory,null,"phone")
        books.ensureRegistered()
        val domains=com.lightningstudio.watchrss.phone.data.ble.BleDomainStore(db,files)
        val before=domains.beginRootWindow("account")
        repeat(3){books.manifests()}
        assertEquals(before.initial["books"],before.current("account")["books"])
        assertEquals(emptyList<String>(),directory.list()!!.toList())
    }
    @Test fun wholeBookTombstoneNeverTriesToRebuildMissingImmutablePackage()=runBlocking<Unit> {
        val db=db();mapped(db);val book=db.bookDao().get(id)!!
        db.bookDao().put(book.copy(stateJson=JSONObject(book.stateJson).put("deleted",true).put("deletedAt",300).toString()))
        val directory=File(context.cacheDir,"deleted-book-${System.nanoTime()}")
        val books=ImportedBookStore(db,directory,null,"phone")
        repeat(3){books.manifests()}
        assertFalse(directory.exists());assertTrue(books.migrationWarnings.isEmpty())
    }
    @Test fun bookDeletionClampsExistingLogicalSavedStateAndCannotBeRevivedByReadingDelta()=runBlocking<Unit> {
        val db=db();mapped(db);val s=store(db);s.readingSummary()
        val saved=state().put("favoriteSaved",true).put("favoriteChangedAt",100).put("favoriteSortOrder",5)
        s.mergeReading(saved)
        val before=s.readingSummary()
        s.merge(JSONObject(db.bookDao().get(id)!!.stateJson).put("deletedChapters",JSONArray(listOf(1))).put("deletionClock",500))
        val after=s.readingSummary();assertTrue(after.seq>before.seq)
        assertFalse(s.readingEntries(0,after.seq).single().state.getBoolean("favoriteSaved"))
        s.mergeReading(saved.put("favoriteChangedAt",900))
        assertFalse(s.readingEntries(0,s.readingSummary().seq).single().state.getBoolean("favoriteSaved"))
    }
    @Test fun onlyRealClearRotatesEpochAndClearsPeers()=runBlocking<Unit> {
        val db=db();val s=store(db);s.mergeReading(state());val before=s.readingSummary()
        s.acknowledgeReading("watch",before,before,before.root);assertNotNull(s.readingPeer("watch"))
        assertEquals(before.epoch,store(db).readingSummary().epoch)
        s.clear();assertNotEquals(before.epoch,s.readingSummary().epoch);assertNull(s.readingPeer("watch"));assertEquals(0L,s.readingSummary().count)
    }
    @Test fun actual17To18MigrationPreservesLegacyUnreadAndCancellation()=runBlocking<Unit> {
        val name="r46-migrate-${System.nanoTime()}";val db=db(name)
        val value=state().put("isRead",false).put("favoriteSaved",false).put("favoriteChangedAt",200).put("favoriteSortOrder",0)
        db.bookDao().putReading(BookReadingEntity(id,1,BookSync.canonical(value)))
        val sql=db.openHelper.writableDatabase
        sql.query("SELECT name FROM sqlite_master WHERE type='trigger' AND name LIKE 'book_%'").use{c->val names=mutableListOf<String>();while(c.moveToNext())names+=c.getString(0);names.forEach{sql.execSQL("DROP TRIGGER $it")}}
        for(t in listOf("book_reading_meta","book_reading_versions","book_reading_journal","book_reading_roots","book_reading_hash_dirty","book_reading_dirty","book_reading_peers","book_capture_meta"))sql.execSQL("DROP TABLE $t")
        sql.version=17;db.close()
        val migrated=Room.databaseBuilder(context,PhoneCompanionDatabase::class.java,name).addMigrations(PhoneCompanionDatabase.MIGRATION_17_18,PhoneCompanionDatabase.MIGRATION_18_19).allowMainThreadQueries().build().also{dbs+=it}
        val s=store(migrated);val summary=s.readingSummary();assertEquals(1L,summary.count)
        val restored=s.readingEntries(0,summary.seq).single().state;assertFalse(restored.getBoolean("isRead"));assertFalse(restored.getBoolean("favoriteSaved"));assertEquals(200,restored.getInt("favoriteChangedAt"))
        assertEquals(19,migrated.openHelper.readableDatabase.version);migrated.close();context.deleteDatabase(name)
    }
}
