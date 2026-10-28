package com.lightningstudio.watchrss.phone.connection.bluetooth

import androidx.room.Room
import com.lightningstudio.watchrss.phone.cloud.*
import com.lightningstudio.watchrss.phone.data.backup.*
import com.lightningstudio.watchrss.phone.data.db.*
import com.lightningstudio.watchrss.phone.data.ble.BleDomainStore
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

/** Real BLE body/frame codecs, private archive, authenticated CloudSnapshot and file-backed Room. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class PhoneBodyHashCloudRoundTripTest {
    private val context=RuntimeEnvironment.getApplication()
    private val root=File(context.cacheDir,"body-hash-${System.nanoTime()}").apply{mkdirs()}
    private var db=openDatabase()
    private var scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    private var repo=repository()
    private fun openDatabase()=Room.databaseBuilder(context,PhoneCompanionDatabase::class.java,File(root,"phone.db").absolutePath).allowMainThreadQueries().build()
    private fun repository()=PhoneCompanionRepository(db.phoneSavedItemDao(),db.phoneArticleDao(),db.phoneRssSourceDao(),"phone",db.syncChangeLogDao(),db.syncPeerStateDao(),appMetaDao=db.appMetaDao())
    private fun backup()=WatchRssBackupService(context,db,repo,ReaderPresetRepository(context,db,db.readerPresetDao(),"phone",scope),"phone")
    private fun row(i:Int)=PhoneArticleEntity("body-$i","watch","https://fixture.invalid/body/$i","entry$i","test","","<p>正文$i /🙂</p>","正文$i /🙂",null,
        CloudSnapshotCodec.sha256("正文$i /🙂".toByteArray()),1,100,false,0,0,null,null,true,100,0,false,0,0,false,0,
        readingProgress=.7925f,isRead=true,readingPositionBytes=132161,readingPositionContentHash="position",readingPositionChangedAt=900)
    private fun legacy(row:PhoneArticleEntity,os:Int):Pair<PhoneArticleEntity,ByteArray> {
        val bytes=ArticleSyncBody.payloadForRequest(row,ArticleBodyRequest(row.articleId,"",emptyList())).chunks.flatMap{it.bytes.asIterable()}.toByteArray().apply{this[9]=os.toByte()}
        val hash=CloudSnapshotCodec.sha256(bytes)
        return row.copy(syncBodyHash=hash,syncBodyByteCount=bytes.size.toLong(),syncChunkSize=ArticleSyncBody.CHUNK_SIZE_BYTES,
            syncChunkHashesJson=JSONArray().put(hash).toString(),syncMetadataHash=ArticleSyncBody.metadataHashFor(row)) to bytes
    }
    private fun wireRoundTrip(frame:JSONObject):JSONObject {
        val sink=ByteArrayOutputStream();BluetoothSyncProtocol.writeFrame(sink,frame)
        return BluetoothSyncProtocol.readFrame(sink.toByteArray().inputStream())
    }
    private fun receiveFrames(values:List<Pair<PhoneArticleEntity,ByteArray>>):List<ChunkedArticlePayload> {
        val frames=LibrarySyncPayload.buildChunkedResponseFrames("watch",values.map{it.first},emptyList(),useBatches=true)
        val byId=values.associateBy{it.first.articleId}
        frames.forEach { frame->frame.optJSONArray("articles")?.let { articles->
            for(i in 0 until articles.length()) {
                val item=articles.getJSONObject(i);val(row,bytes)=byId.getValue(item.getString("articleId"));val hash=row.syncBodyHash
                item.put("body",JSONObject().put("bodyHash",hash).put("bodyByteCount",bytes.size).put("chunkSize",ArticleSyncBody.CHUNK_SIZE_BYTES)
                    .put("chunkHashes",JSONArray().put(hash)).put("chunks",JSONArray().put(JSONObject().put("index",0).put("hash",hash).put("data",ArticleSyncBody.encodeChunkData(bytes)))))
            }
        }}
        return LibrarySyncPayload.parseChunkedArticles(LibrarySyncPayload.combineArticlePayloads(frames.map(::wireRoundTrip)))
    }
    private fun manifest(rows:List<PhoneArticleEntity>)=LibrarySyncPayload.parseArticleManifest(LibrarySyncPayload.buildManifestRequest("watch",rows))
    private fun transferredBytes(rows:List<PhoneArticleEntity>,requests:List<ArticleBodyRequest>):Long {
        val wanted=requests.map{it.articleId}.toSet()
        val frames=LibrarySyncPayload.buildChunkedResponseFrames("sender",rows.filter{it.articleId in wanted},requests,useBatches=true)
        val payload=LibrarySyncPayload.parseChunkedArticles(LibrarySyncPayload.combineArticlePayloads(frames.map(::wireRoundTrip)))
        return payload.sumOf{it.chunks.sumOf{c->c.bytes.size.toLong()}}
    }
    @After fun close(){scope.cancel();db.close();root.deleteRecursively()}

    private suspend fun roundTrip(count:Int) {
        val originals=(0 until count).map{legacy(row(it),listOf(0,3,255)[it%3])}
        assertEquals(count,repo.mergeChunkedArticlesFromSync(receiveFrames(originals)))
        val before=db.phoneArticleDao().getAllForSync().associateBy{it.articleId}
        originals.forEach{(r,_)->assertEquals(r.syncBodyHash,before.getValue(r.articleId).syncBodyHash)}
        val service=backup();val archive=service.createCloudPrivateArchive()
        val codec=CloudSnapshotCodec();val key=ByteArray(32){it.toByte()}
        val snapshot=codec.create(key,1,"phone",1,listOf(CloudLogicalObject("private-library.wrss",archive)))
        val cloudManifest=codec.decryptManifest(key,snapshot.manifest.snapshotId,snapshot.encryptedManifest)
        val restored=codec.restoreObjects(key,cloudManifest){snapshot.newCiphertextChunks.getValue(it)}.getValue("private-library.wrss")
        service.restorePrivateLibrary(restored)
        scope.cancel();db.close();db=openDatabase();scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);repo=repository()
        val after=db.phoneArticleDao().getAllForSync().associateBy{it.articleId}
        originals.forEach{(r,_)->
            val actual=after.getValue(r.articleId);assertEquals(r.syncBodyHash,actual.syncBodyHash);assertEquals(r.syncChunkHashesJson,actual.syncChunkHashesJson)
            assertEquals(r.contentHtml,actual.contentHtml);assertEquals(r.contentText,actual.contentText)
            assertEquals(r.readingProgress,actual.readingProgress);assertEquals(r.readingPositionChangedAt,actual.readingPositionChangedAt)
            assertEquals(r.readingPositionBytes,actual.readingPositionBytes);assertEquals(r.favoriteChangedAt,actual.favoriteChangedAt)
        }
        repeat(2) {
            val local=repo.getArticleManifestsForSync();val peer=manifest(originals.map{it.first})
            val pull=LibrarySyncPayload.buildBodyRequestsForRemoteArticles(local,peer,supportsMetadataOnlyArticles=true,supportsFullMetadataOnlyArticles=true)
            val push=LibrarySyncPayload.buildBodyRequestsForRemoteArticles(peer,local,supportsMetadataOnlyArticles=true,supportsFullMetadataOnlyArticles=true)
            assertEquals(0L,transferredBytes(originals.map{it.first},pull));assertEquals(0L,transferredBytes(repo.getArticlesForSync(),push))
        }
        val current=repo.getArticle("body-0")!!
        val changed=current.copy(contentHtml="<p>真正修改正文</p>",contentText="真正修改正文",contentHash="new-content",updatedAt=1000)
        repo.mergeArticlesFromSync(listOf(changed))
        val peer=manifest(originals.map{it.first});val changedManifest=repo.getArticleManifestsForSync()
        val requests=LibrarySyncPayload.buildBodyRequestsForRemoteArticles(peer,changedManifest,supportsMetadataOnlyArticles=true,supportsFullMetadataOnlyArticles=true)
        assertTrue(transferredBytes(repo.getArticlesForSync(),requests)>0)
        assertNotEquals(current.syncBodyHash,repo.getArticle("body-0")!!.syncBodyHash)
        println("PHONE_BODY_HASH count=$count cloudRoundTrip=authenticated coldReopen=true secondAndThirdBodyBytes=0 changedBodyTransferred=true")
    }
    @Test fun oneArticleBleCloudBleKeepsLegacyOsAndTwoSubsequentExchangesSendNoBodies()=runBlocking<Unit>{roundTrip(1)}
    @Test fun fiveHundredArticlesBleCloudBleKeepWireHashesReadingAndSavedClocks()=runBlocking<Unit>{roundTrip(500)}
    @Test fun cloudRestorePreservesExistingLegacyWireWitnessEvenWhenMetadataClockChanges()=runBlocking<Unit> {
        val (old,_)=legacy(row(0),0);db.phoneArticleDao().upsert(old)
        repo.mergeArticlesFromBackup(listOf(old.copy(title="new title",updatedAt=200,syncBodyHash="",syncBodyByteCount=0,syncChunkSize=0,syncChunkHashesJson="",syncMetadataHash="")))
        val actual=repo.getArticle(old.articleId)!!
        assertEquals("new title",actual.title);assertEquals(old.syncBodyHash,actual.syncBodyHash)
        assertNotNull(ArticleSyncBody.cachedMetadataFor(actual))
    }
    @Test fun relayAndPrivateReaderCloudReceivePreserveSamePlaintextWireWitness()=runBlocking<Unit> {
        val (old,_)=legacy(row(0).copy(contentHtml=null),3);db.phoneArticleDao().upsert(old)
        val service=backup()
        val relay=LibrarySyncPayload.buildResponse("watch",listOf(old.copy(updatedAt=200))).toString().toByteArray()
        service.restoreCloudRelayLibrary(relay)
        assertEquals(old.syncBodyHash,repo.getArticle(old.articleId)!!.syncBodyHash)
        val metadata=JSONObject().put("articleId",old.articleId).put("url",old.url).put("title",old.title).put("contentHash",old.contentHash)
        assertNull(service.restorePrivateReaderArticle(metadata,old.contentText){})
        val actual=repo.getArticle(old.articleId)!!
        assertEquals(old.syncBodyHash,actual.syncBodyHash);assertEquals(old.readingPositionChangedAt,actual.readingPositionChangedAt)
        assertNotNull(ArticleSyncBody.cachedMetadataFor(actual))
    }
    @Test fun projectionUpgradeKeepsOnlyUntrustedBodyWitnessAndRevalidatesItBeforeExport()=runBlocking<Unit> {
        val oldStore=BleDomainStore(db,root,BleDomainStore.DOMAINS.associateWith{if(it=="library")4 else 1})
        oldStore.initialize()
        val (accepted,_)=legacy(row(0),0);db.phoneArticleDao().upsert(accepted)
        val before=oldStore.roots("account")
        oldStore.publish("scope","watch",mapOf("library" to(before.getValue("library") to "b".repeat(64))))
        val upgraded=BleDomainStore(db,root);val after=upgraded.roots("account")
        assertNotEquals(before.getValue("library"),after.getValue("library"))
        assertFalse(upgraded.receipts("scope","watch").containsKey("library"))
        val unverified=db.phoneArticleDao().getById(accepted.articleId)!!
        assertEquals(accepted.syncBodyHash,unverified.syncBodyHash);assertEquals("",unverified.syncMetadataHash)
        assertNull(ArticleSyncBody.cachedMetadataFor(unverified))
        assertEquals(accepted.syncBodyHash,repo.getArticleManifestsForSync().single().bodyHash)
        val verified=db.phoneArticleDao().getById(accepted.articleId)!!
        assertNotNull(ArticleSyncBody.cachedMetadataFor(verified))
        db.phoneArticleDao().upsert(verified.copy(contentText="changed despite old hash",syncMetadataHash=""))
        assertNotEquals(accepted.syncBodyHash,repo.getArticleManifestsForSync().single().bodyHash)
    }
    @Test fun changedPlaintextCannotReuseOldDigestAndCorruptChunkDescriptionCannotValidate() {
        val (old,_)=legacy(row(0),3)
        val changed=old.copy(contentText="different",contentHtml=null)
        assertNotEquals(old.syncBodyHash,ArticleSyncBody.metadataFor(changed).bodyHash)
        assertNull(ArticleSyncBody.cachedMetadataFor(old.copy(syncChunkHashesJson=JSONArray().put("0".repeat(64)).toString())))
        val fake=old.copy(syncBodyHash="0".repeat(64),syncMetadataHash="")
        assertNotEquals(fake.syncBodyHash,ArticleSyncBody.metadataFor(fake).bodyHash)
    }
}
