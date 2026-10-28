package com.lightningstudio.watchrss.phone.data.book

import android.content.Context
import androidx.room.Room
import com.lightningstudio.watchrss.phone.cloud.CloudSnapshotCodec
import com.lightningstudio.watchrss.phone.connection.bluetooth.*
import com.lightningstudio.watchrss.phone.data.db.*
import com.lightningstudio.watchrss.phone.data.local.FileArticleContentStore
import com.lightningstudio.watchrss.phone.data.model.ImportedContentIds
import com.lightningstudio.watchrss.phone.data.repo.PhoneCompanionRepository
import kotlinx.coroutines.runBlocking
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class CliSingleTextBookTest {
    private val context:Context=RuntimeEnvironment.getApplication()
    private val root=File(context.cacheDir,"cli-book-${System.nanoTime()}").apply{mkdirs()}
    private val db=Room.inMemoryDatabaseBuilder(context,PhoneCompanionDatabase::class.java).allowMainThreadQueries().build()
    private val content=FileArticleContentStore(File(root,"text"))
    private var store=ImportedBookStore(db,File(root,"books"),content,"phone")
    private fun repo()=PhoneCompanionRepository(db.phoneSavedItemDao(),db.phoneArticleDao(),db.phoneRssSourceDao(),"phone",articleContentStore=content,books=store)
    private fun fixture()=JSONObject(requireNotNull(javaClass.getResource("/book/cli-single-txt-book.json")).readText())
    private fun original(f:JSONObject=fixture())=File(root,"incoming.jsonl").apply{writeText(f.getString("packageUtf8"))}
    private fun state(f:JSONObject)=JSONObject().put("bookId",f.getString("bookId")).put("fingerprint",f.getString("fingerprint"))
        .put("title","CLI synthetic single TXT").put("chapterCount",1).put("sourceUrl",f.getString("sourceUrl")).put("kind","txt")
        .put("deletedChapters",JSONArray()).put("deletionClock",0).put("deleted",false).put("deletedAt",0).put("updatedAt",10).put("sourceDeviceId","cli")
    private fun reply(phase:String)=JSONObject().put("success",true).put("action","syncBooks").put("version",1).put("phase",phase)
    private fun readingReply()=reply("reading").put("states",JSONArray()).put("hasMore",false).put("nextAfter",0)
    @After fun close(){db.close();root.deleteRecursively()}

    @Test fun strictCliSingleTxtIsClassifiedAsLocalTextNotAnArbitraryScheme() {
        val url=fixture().getString("sourceUrl")
        assertTrue(ImportedContentIds.isImportedContentUrl(url));assertTrue(ImportedContentIds.isImportedTextArticleUrl(url))
        assertTrue(ImportedContentIds.isImportedContentUrl(url.uppercase()))
        assertFalse(ImportedContentIds.isImportedEpubSourceUrl(url));assertFalse(ImportedContentIds.isImportedTextSourceUrl(url))
        for(bad in listOf("javascript:alert(1)",url.replace("watchrss:","watchrſſ:"),"file:///secret","content://import/online-v1/"+"a".repeat(64),url+"/chapter/1",url+"?x=1",url+"#x",url.dropLast(1),url+"0",url.replace("online-v1","online-v2"),url.replace("import/","import.evil/"),url.replace("aaaa","zzzz"))) {
            assertFalse(bad,ImportedContentIds.isImportedContentUrl(bad));assertFalse(bad,ImportedContentIds.isImportedTextArticleUrl(bad))
        }
    }
    @Test fun actualCliProducerPackagePublishesExactlyOneChapterAndReplayDoesNotWriteAgain()=runBlocking<Unit> {
        val f=fixture();val id=f.getString("bookId");val file=original()
        assertEquals(f.getString("fingerprint"),BookSync.fingerprint(listOf(BookSync.chapterHash(1,f.getJSONObject("chapter").getString("title"),f.getJSONObject("chapter").getString("contentText")))))
        assertTrue(store.receivePackage(id,file)>0)
        val chapter=db.bookDao().chapters(id).single();val row=db.phoneArticleDao().getById(chapter.articleId)!!
        assertEquals(BookSync.articleId(id,1),row.articleId);assertEquals(f.getString("sourceUrl"),row.url)
        assertEquals(ImportedContentIds.ROOT_SOURCE_URL,row.rssSourceUrl);assertEquals(f.getJSONObject("chapter").getString("contentText"),repo().getArticle(row.articleId)!!.contentText)
        val seq=db.syncChangeLogDao().maxSeq();val hash=store.packageHash(id)
        store=ImportedBookStore(db,File(root,"books"),content,"phone")
        assertEquals(0,store.receivePackage(id,file));assertEquals(seq,db.syncChangeLogDao().maxSeq());assertEquals(hash,store.packageHash(id))
        assertEquals(19,db.openHelper.readableDatabase.version);assertEquals(1,db.bookDao().all().size)
    }
    @Test fun largeCliTxtIsReceivedInChunksAndReadsBackThroughTheActualChunkedReader()=runBlocking<Unit> {
        val f=fixture();val text="合成🙂原文\n".repeat(25000);val c=f.getJSONObject("chapter").put("contentText",text)
        val fingerprint=BookSync.fingerprint(listOf(BookSync.chapterHash(1,c.getString("title"),text)));f.put("fingerprint",fingerprint).put("bookId",BookSync.id(fingerprint))
        val header=JSONObject(f.getString("packageUtf8").lineSequence().first());f.put("packageUtf8",BookSync.canonical(header)+"\n"+BookSync.canonical(JSONObject(c.toString()).put("chapterIndex",1))+"\n")
        val bytes=f.getString("packageUtf8").toByteArray();var pulls=0;val phases=mutableListOf<String>()
        BookBluetoothSync(store,"phone",File(root,"staging")){q->val phase=q.getString("phase");phases+=phase
            when(phase){
                "manifest"->reply("manifest").put("books",JSONArray().put(state(f).put("contentAvailable",true))).put("missingBookIds",JSONArray())
                "pull"->{pulls++;val index=q.getInt("chunkIndex");reply("body").put("bookId",f.getString("bookId")).put("fingerprint",fingerprint).put("packageSha256",CloudSnapshotCodec.sha256(bytes)).put("byteCount",bytes.size)
                    .put("chunkIndex",index).put("chunkCount",(bytes.size+BookBluetoothSync.CHUNK-1)/BookBluetoothSync.CHUNK).put("dataBase64",Base64.getEncoder().encodeToString(bytes.copyOfRange(index*BookBluetoothSync.CHUNK,minOf(bytes.size,(index+1)*BookBluetoothSync.CHUNK))))}
                "reading"->readingReply();else->error(phase)
            }
        }.sync()
        assertTrue(pulls>1);assertEquals("reading",phases.last())
        val id=BookSync.articleId(f.getString("bookId"),1);val reader=requireNotNull(repo().getImportedTextReader(id))
        assertTrue(reader.chunkCount>1);val restored=buildString{for(i in 0 until reader.chunkCount)append(repo().loadImportedTextChunk(reader.marker,i)!!)}
        assertEquals(text,restored);assertEquals(text.toByteArray().size.toLong(),reader.byteLength)
        val seq=db.syncChangeLogDao().maxSeq();assertEquals(0,store.receivePackage(f.getString("bookId"),original(f)));assertEquals(seq,db.syncChangeLogDao().maxSeq())
    }
    @Test fun invalidPackageIsIsolatedAndDoesNotPreventTheReadingPhase()=runBlocking<Unit> {
        val f=fixture();val bytes=f.getString("packageUtf8").replace(f.getString("sourceUrl"),"javascript:bad").toByteArray()
        var reading=false;val diagnostics=mutableListOf<String>()
        val sync=BookBluetoothSync(store,"phone",File(root,"staging")){q->when(q.getString("phase")) {
            "manifest"->reply("manifest").put("books",JSONArray().put(state(f).put("contentAvailable",true))).put("missingBookIds",JSONArray())
            "pull"->reply("body").put("bookId",f.getString("bookId")).put("fingerprint",f.getString("fingerprint")).put("packageSha256",CloudSnapshotCodec.sha256(bytes)).put("byteCount",bytes.size).put("chunkIndex",0).put("chunkCount",1).put("dataBase64",Base64.getEncoder().encodeToString(bytes))
            "reading"->{reading=true;readingReply()};else->error("unexpected")
        }}
        sync.trace={diagnostics+=it};sync.sync()
        assertTrue(reading);assertTrue(diagnostics.any{it.contains("book_package_invalid")})
        assertTrue(db.bookDao().chapters().isEmpty());assertTrue(db.phoneArticleDao().getAllForSync().isEmpty());assertNull(store.packageFile(f.getString("bookId")))
    }
    @Test fun malformedHeaderIsRejectedEvenOnReplayBeforeAnyPublication()=runBlocking<Unit> {
        val f=fixture();val file=original();val id=f.getString("bookId");store.receivePackage(id,file)
        val before=store.packageHash(id);val seq=db.syncChangeLogDao().maxSeq()
        val lines=f.getString("packageUtf8").lineSequence().toList()
        val header=JSONObject(lines[0]).put("sourceUrl","watchrss://import/online-v1/invalid")
        file.writeText(header.toString()+"\n"+lines[1]+"\n")
        assertThrows(InvalidBookPackageException::class.java){runBlocking{store.receivePackage(id,file)}}
        assertEquals(before,store.packageHash(id));assertEquals(seq,db.syncChangeLogDao().maxSeq())
    }
    @Test fun invalidBookDoesNotDiscardAValidNeighbourAndOnlyFailedBookIsPulledOnReplay()=runBlocking<Unit> {
        val bad=fixture();val good=fixture();val c=good.getJSONObject("chapter").put("contentText","Another immutable original.")
        val goodUrl="watchrss://import/online-v1/"+"b".repeat(64);good.put("sourceUrl",goodUrl);c.put("url",goodUrl)
        val fingerprint=BookSync.fingerprint(listOf(BookSync.chapterHash(1,c.getString("title"),c.getString("contentText"))))
        good.put("fingerprint",fingerprint).put("bookId",BookSync.id(fingerprint))
        val header=JSONObject(good.getString("packageUtf8").lineSequence().first()).put("sourceUrl",goodUrl)
        good.put("packageUtf8",header.toString()+"\n"+JSONObject(c.toString()).put("chapterIndex",1).toString()+"\n")
        val packages=mapOf(bad.getString("bookId") to bad.getString("packageUtf8").replace(bad.getString("sourceUrl"),"javascript:bad").toByteArray(),
            good.getString("bookId") to good.getString("packageUtf8").toByteArray())
        val pulled=mutableListOf<String>();var readings=0
        fun sync()=BookBluetoothSync(store,"phone",File(root,"staging")){q->when(q.getString("phase")) {
            "manifest"->reply("manifest").put("books",JSONArray(listOf(state(bad).put("contentAvailable",true),state(good).put("contentAvailable",true)))).put("missingBookIds",JSONArray())
            "pull"->{val id=q.getString("bookId");pulled+=id;val bytes=packages.getValue(id);reply("body").put("bookId",id).put("fingerprint",if(id==bad.getString("bookId"))bad.getString("fingerprint")else fingerprint)
                .put("packageSha256",CloudSnapshotCodec.sha256(bytes)).put("byteCount",bytes.size).put("chunkIndex",0).put("chunkCount",1).put("dataBase64",Base64.getEncoder().encodeToString(bytes))}
            "reading"->{readings++;readingReply()};else->error("unexpected")
        }}
        val first=sync();first.sync();assertFalse(first.booksComplete);assertEquals(1,first.issues.size)
        assertEquals("book_package_invalid",first.issues.single().code);assertEquals(bad.getString("bookId").take(8),first.issues.single().bookIdPrefix)
        assertEquals(1,db.bookDao().chapters().size);assertNotNull(store.packageFile(good.getString("bookId")))
        pulled.clear();val seq=db.syncChangeLogDao().maxSeq();sync().sync()
        assertEquals(listOf(bad.getString("bookId")),pulled);assertEquals(seq,db.syncChangeLogDao().maxSeq());assertEquals(2,readings)
    }
    @Test fun transportFailureAndExplicitPeerRefusalAreNotMisclassifiedAsInvalidItems()=runBlocking<Unit> {
        for(reject in listOf(false,true)) {
            val f=fixture()
            val sync=BookBluetoothSync(store,"phone",File(root,"staging")){q->when(q.getString("phase")) {
                "manifest"->reply("manifest").put("books",JSONArray().put(state(f).put("contentAvailable",true))).put("missingBookIds",JSONArray())
                "pull"->if(reject)reply("body").put("success",false) else throw java.io.IOException("bt socket closed, read return: -1")
                else->error("must stop before reading")
            }}
            val failure=runCatching{sync.sync()}.exceptionOrNull()
            if(reject)assertTrue(failure is IllegalArgumentException)else assertTrue(failure is java.io.IOException)
            assertTrue(sync.issues.isEmpty())
        }
    }
    @Test fun emptyLegacyProjectionIsFilledWithoutChangingItsReadingOrSavedClocks()=runBlocking<Unit> {
        val f=fixture();val legacy=PhoneArticleEntity("legacy-cli","cli",f.getString("sourceUrl"),"Legacy title","","",null,"",null,"placeholder",1,10,
            false,0,0,ImportedContentIds.ROOT_SOURCE_URL,ImportedContentIds.ROOT_SOURCE_TITLE,true,100,100,false,0,0,false,0,
            isRead=true,readingProgress=.7f,readingPositionChangedAt=99,readingPositionContentHash="old-reading-version")
        db.phoneArticleDao().upsert(legacy);store.receivePackage(f.getString("bookId"),original())
        val received=db.phoneArticleDao().getById("legacy-cli")!!
        assertEquals(f.getJSONObject("chapter").getString("contentText"),received.contentText)
        assertTrue(received.isRead);assertTrue(received.favoriteSaved);assertEquals(99L,received.readingPositionChangedAt);assertEquals(100L,received.favoriteChangedAt)
        assertEquals("old-reading-version",received.readingPositionContentHash)
    }

}
