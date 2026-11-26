package com.lightningstudio.watchrss.phone.connection.bluetooth

import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PhoneArticleReceiveCheckpointTest {
    internal fun article(): PhoneArticleEntity {
        val random = java.util.Random(81)
        val text = CharArray(600_000) { (33 + random.nextInt(80)).toChar() }.concatToString()
        return PhoneArticleEntity("article", "watch", "https://example.invalid/a", "title", "site", "excerpt", null,
            text, null, "content", 1, 2, false, 0, 0, null, null, true, 2, 2, false, 0, 0, false, 0)
    }
    private fun entry(a: PhoneArticleEntity, m: ArticleBodyMetadata) = ArticleSyncManifestEntry(a.articleId, a.sourceDeviceId,
        a.contentHash, a.updatedAt, 0, a.favoriteChangedAt, 0, 0, bodyHash=m.bodyHash,bodyByteCount=m.bodyByteCount,
        chunkSize=m.chunkSize,chunkHashes=m.chunkHashes,metadataHash=m.metadataHash)

    // RVHF1313-01: keep the independent review's valid sparse-response counterexample.
    @Test fun validLocalReusableChunksMustNotBecomeAnIncompleteCheckpoint()=runBlocking {
        val old=article();val updated=old.copy(contentText=old.contentText.dropLast(100)+"changed suffix".repeat(10),contentHash="new",updatedAt=3)
        val local=entry(old,ArticleSyncBody.metadataFor(old));val remote=entry(updated,ArticleSyncBody.metadataFor(updated))
        val request=LibrarySyncPayload.buildBodyRequestsForRemoteArticles(listOf(local),listOf(remote)).single()
        assertTrue(request.chunkIndexes.size in 1 until remote.chunkHashes.size)
        val response=ArticleSyncBody.payloadForRequest(updated,request)
        assertEquals(updated.contentText,ArticleSyncBody.rebuildBody(old,response).second)
        val root=Files.createTempDirectory("phone-local-reuse").toFile()
        try {
            var applications=0
            val cp=PhoneArticleReceiveCheckpoint(root,"owner","peer",localArticle={old});cp.bind(listOf(remote))
            assertTrue(cp.apply(response){applications++;assertEquals(updated.contentText,ArticleSyncBody.rebuildBody(old,it).second);1})
            assertEquals(1,applications)
            // A body with different bytes cannot satisfy omitted chunks merely by its cached hashes.
            val invalid=PhoneArticleReceiveCheckpoint(File(root,"invalid"),"owner","peer",localArticle={old.copy(contentText="wrong")})
            invalid.bind(listOf(remote));assertFalse(invalid.apply(response){fail("unverified local bytes applied");0})
        }finally{root.deleteRecursively()}
    }

    // RVHF1313-02: reading state changes independently of body and metadataHash.
    @Test fun aNewReadingPositionAfterReconnectMustNotReuseTheOldAppliedReceipt()=runBlocking {
        val first=article();val updated=first.copy(readingProgress=.9f,readingPositionBytes=5000,
            readingPositionContentHash=first.contentHash,readingPositionChangedAt=400,isRead=true)
        val before=entry(first,ArticleSyncBody.metadataFor(first))
        val after=before.copy(readingProgress=.9f,readingPositionBytes=5000,readingPositionContentHash=first.contentHash,
            readingPositionChangedAt=400,isRead=true)
        assertEquals(before.metadataHash,after.metadataHash);assertEquals(before.updatedAt,after.updatedAt)
        val request=LibrarySyncPayload.buildBodyRequestsForRemoteArticles(listOf(before),listOf(after)).single()
        val root=Files.createTempDirectory("phone-reading-receipt").toFile()
        try {
            var latest=first;var applications=0
            val cp=PhoneArticleReceiveCheckpoint(root,"owner","peer",localArticle={latest})
            cp.bind(listOf(before));assertTrue(cp.apply(ArticleSyncBody.payloadForRequest(first,
                ArticleBodyRequest(first.articleId,before.bodyHash,before.chunkHashes.indices.toList()))){latest=it.article;applications++;1})
            cp.bind(listOf(after));assertFalse(cp.isApplied(first.articleId))
            val response=ArticleSyncBody.payloadForRequest(updated,request)
            assertTrue(cp.apply(response){latest=it.article;applications++;1})
            assertEquals(400L,latest.readingPositionChangedAt);assertTrue(latest.isRead);assertEquals(5000L,latest.readingPositionBytes)
            assertEquals(2,applications)
            assertTrue(cp.apply(response){fail("same full state applied twice");0})
        }finally{root.deleteRecursively()}
    }

    @Test fun recreatedStoreRequestsOnlyMissingChunksAndAppliesExactlyOnce()=runBlocking {
        val root=Files.createTempDirectory("phone-receive").toFile()
        try {
            val a=article();val m=ArticleSyncBody.metadataFor(a);val manifest=entry(a,m)
            val request=ArticleBodyRequest(a.articleId,m.bodyHash,m.chunkHashes.indices.toList())
            val full=ArticleSyncBody.payloadForRequest(a,request,m)
            assertTrue(full.chunks.size>1)
            var cp=PhoneArticleReceiveCheckpoint(root,"owner","watch");cp.bind(listOf(manifest))
            assertFalse(cp.apply(full.copy(chunks=full.chunks.take(1))){fail("partial body applied");0})
            cp=PhoneArticleReceiveCheckpoint(root,"owner","watch");cp.bind(listOf(manifest))
            assertEquals(request.chunkIndexes.drop(1),cp.remaining(request).chunkIndexes)
            var applies=0
            val suffix=full.copy(chunks=full.chunks.drop(1))
            assertTrue(cp.apply(suffix){verified->applies++;assertEquals(a.contentText,ArticleSyncBody.rebuildBody(null,verified).second);1})
            assertTrue(cp.apply(full){fail("same article applied twice");0})
            assertEquals(1,applies);assertEquals(1,cp.merged)
        } finally {root.deleteRecursively()}
    }
    @Test fun corruptChunkOtherOwnerAndChangedMetadataDoNotReuseCheckpoint()=runBlocking {
        val root=Files.createTempDirectory("phone-receive").toFile()
        try {
            val a=article();val m=ArticleSyncBody.metadataFor(a);val e=entry(a,m)
            val request=ArticleBodyRequest(a.articleId,m.bodyHash,m.chunkHashes.indices.toList())
            val full=ArticleSyncBody.payloadForRequest(a,request,m)
            val cp=PhoneArticleReceiveCheckpoint(root,"owner","watch");cp.bind(listOf(e));cp.stage(full.copy(chunks=full.chunks.take(1)))
            for(other in listOf(PhoneArticleReceiveCheckpoint(root,"other","watch"),PhoneArticleReceiveCheckpoint(root,"owner","other"))) {
                other.bind(listOf(e));assertEquals(request.chunkIndexes,other.remaining(request).chunkIndexes)
            }
            val bin=root.walkTopDown().first{it.extension=="bin"};bin.writeBytes(ByteArray(bin.length().toInt()))
            assertEquals(request.chunkIndexes,cp.remaining(request).chunkIndexes)
            assertThrows(IllegalArgumentException::class.java){cp.stage(full.copy(article=a.copy(title="tampered")))}
            assertThrows(IllegalArgumentException::class.java){cp.stage(full.copy(chunks=listOf(full.chunks.first().copy(bytes=byteArrayOf(1)))))}
            cp.bind(listOf(e.copy(updatedAt=3)));assertEquals(request.chunkIndexes,cp.remaining(request).chunkIndexes)
        } finally {root.deleteRecursively()}
    }
    @Test fun checkpointsAreOnlyAtArticleBoundaryAndAcknowledgementsAreStrict() {
        fun frame(id:String)=JSONObject().put("articles",org.json.JSONArray().put(JSONObject().put("articleId",id)))
        val frames=listOf(frame("a"),frame("a"),frame("b"))
        assertFalse(LibrarySyncPayload.wantsArticleCheckpoint(frames,0))
        assertTrue(LibrarySyncPayload.wantsArticleCheckpoint(frames,1))
        val ack=LibrarySyncPayload.articleBatchAck("phoneToWatch",1,1)
        LibrarySyncPayload.requireArticleBatchAck(ack,"phoneToWatch",1,1)
        for(bad in listOf(JSONObject(ack.toString()).put("direction","watchToPhone"),JSONObject(ack.toString()).put("batchIndex",2),JSONObject(ack.toString()).put("confirmedArticles",2)))
            assertThrows(IllegalArgumentException::class.java){LibrarySyncPayload.requireArticleBatchAck(bad,"phoneToWatch",1,1)}
    }
    @Test fun postUploadMergedRevisionUsesFreshChunksAndResumesAgainstItsNewManifest()=runBlocking {
        val root=Files.createTempDirectory("phone-merged-revision").toFile()
        try {
            val old=article();val before=ArticleSyncBody.metadataFor(old)
            val updated=old.copy(title="merged title",updatedAt=3,contentHash="updated",contentText=old.contentText!!.reversed())
            val after=ArticleSyncBody.metadataFor(updated)
            val oldRequest=ArticleBodyRequest(old.articleId,before.bodyHash,before.chunkHashes.indices.toList())
            val request=ArticleBodyRequest(updated.articleId,after.bodyHash,after.chunkHashes.indices.toList())
            val full=ArticleSyncBody.payloadForRequest(updated,request,after)
            val cp=PhoneArticleReceiveCheckpoint(root,"owner","watch");cp.bind(listOf(entry(old,before)))
            cp.stage(ArticleSyncBody.payloadForRequest(old,oldRequest,before).copy(chunks=ArticleSyncBody.payloadForRequest(old,oldRequest,before).chunks.take(1)))
            assertThrows(IllegalArgumentException::class.java){cp.stage(full.copy(chunks=full.chunks.take(1)))}
            cp.allowResponseChanges(setOf(old.articleId))
            assertFalse(cp.apply(full.copy(chunks=full.chunks.take(1))){fail("old revision chunks reused");0})
            cp.bind(listOf(entry(updated,after)))
            assertEquals(request.chunkIndexes.drop(1),cp.remaining(request).chunkIndexes)
            assertTrue(cp.apply(full.copy(chunks=full.chunks.drop(1))){
                assertEquals(updated.contentText,ArticleSyncBody.rebuildBody(null,it).second);1
            })
            assertEquals(1,cp.merged)
        } finally {root.deleteRecursively()}
    }
    @Test fun bodyRepeatMetricsSeparateDirectionsVersionsAndPartialFrames() {
        fun frame(hash:String)=JSONObject().put("articles",org.json.JSONArray().put(JSONObject().put("articleId","a")
            .put("body",JSONObject().put("bodyHash",hash).put("chunks",org.json.JSONArray().put(JSONObject()
                .put("index",0).put("hash","chunk").put("data","AQIDBA=="))))))
        val s=LibraryBodyTransferStats();s.frame("send",frame("body"));s.frame("send",frame("body"));s.frame("receive",frame("body"));s.frame("send",frame("changed"));s.partialWire(27)
        assertEquals(16L,s.snapshot()["bodyBytes"]);assertEquals(4L,s.snapshot()["repeatedBodyBytes"])
        assertEquals(27L,s.snapshot()["partialFrameWireBytes"])
    }
}
