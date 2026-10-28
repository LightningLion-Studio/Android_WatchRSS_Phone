package com.lightningstudio.watchrss.phone.connection.bluetooth

import com.lightningstudio.watchrss.phone.cloud.CloudSnapshotCodec
import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.Random
import java.util.zip.GZIPInputStream

class ArticleBodyGzipOracleTest {
    private fun decode(value:String)=Base64.getDecoder().decode(value)
    private fun row(html:String?,text:String)=PhoneArticleEntity("oracle","watch","https://fixture.invalid/oracle","title","test","",html,text,null,
        CloudSnapshotCodec.sha256(text.toByteArray()),1,1,false,0,0,null,null,true,1,0,false,0,0,false,0)
    @Test fun eightPodJsJavaOracleMembersMatchExactProductionGzipBytesInBothStreamingModes() {
        val vector=JSONObject(requireNotNull(javaClass.getResource("/ble/article-body-gzip-oracle.json")).readText()).getJSONArray("cases")
        assertEquals(8,vector.length())
        repeat(vector.length()) {i->
            val c=vector.getJSONObject(i);val plain=decode(c.getString("utf8Base64"));val expected=decode(c.getString("gzipBase64"))
            for(fragmented in listOf(false,true)) {
                val out=ByteArrayOutputStream()
                ArticleBodyGzipOutputStream(out).use {gzip->
                    if(fragmented)for(at in plain.indices step 7){gzip.write(plain,at,minOf(7,plain.size-at));gzip.flush()}
                    else gzip.write(plain)
                    gzip.finish();gzip.finish() // finish/close must not emit a second trailer.
                }
                val actual=out.toByteArray()
                assertArrayEquals(c.getString("name"),expected,actual)
                assertEquals("1f8b08000000000000ff",actual.take(10).joinToString(""){"%02x".format(it)})
                assertEquals(c.getString("sha256"),CloudSnapshotCodec.sha256(actual));assertEquals(c.getLong("size"),actual.size.toLong())
                assertArrayEquals(plain,GZIPInputStream(actual.inputStream()).readBytes())
            }
            if(c.getBoolean("article")) {
                val input=c.getJSONObject("input");val article=row(if(input.isNull("a"))null else input.getString("a"),input.getString("b"))
                val body=ArticleSyncBody.payloadForRequest(article,ArticleBodyRequest(article.articleId,"",emptyList()))
                assertArrayEquals(expected,body.chunks.flatMap{it.bytes.asIterable()}.toByteArray())
                assertEquals(c.getString("sha256"),body.bodyHash)
            }
        }
    }
    @Test fun largeMultiChunkLegacyWitnessKeepsExactBytesAndValidatesEveryChunk() {
        val random=ByteArray(ArticleSyncBody.CHUNK_SIZE_BYTES*3).also{Random(72).nextBytes(it)}
        val original=row("<p>large /🙂</p>",Base64.getEncoder().encodeToString(random))
        val canonical=ArticleSyncBody.payloadForRequest(original,ArticleBodyRequest(original.articleId,"",emptyList()))
        val canonicalBytes=canonical.chunks.flatMap{it.bytes.asIterable()}.toByteArray()
        assertTrue(canonicalBytes.size>ArticleSyncBody.CHUNK_SIZE_BYTES*2)
        for(os in listOf(0,3,255)) {
            val bytes=canonicalBytes.copyOf().apply{this[9]=os.toByte()}
            val chunks=bytes.asList().chunked(ArticleSyncBody.CHUNK_SIZE_BYTES).map{it.toByteArray()}
            val old=original.copy(syncBodyHash=CloudSnapshotCodec.sha256(bytes),syncBodyByteCount=bytes.size.toLong(),syncChunkSize=ArticleSyncBody.CHUNK_SIZE_BYTES,
                syncChunkHashesJson=org.json.JSONArray(chunks.map(CloudSnapshotCodec::sha256)).toString(),syncMetadataHash=ArticleSyncBody.metadataHashFor(original))
            val metadata=requireNotNull(ArticleSyncBody.cachedMetadataFor(old))
            assertEquals(old.syncBodyHash,ArticleSyncBody.metadataFor(old).bodyHash)
            val payload=ArticleSyncBody.payloadForRequest(old,ArticleBodyRequest(old.articleId,old.syncBodyHash,metadata.chunkHashes.indices.toList()),metadata)
            assertArrayEquals(bytes,payload.chunks.flatMap{it.bytes.asIterable()}.toByteArray())
            val partial=payload.copy(chunks=listOf(payload.chunks.last()))
            assertEquals(old.contentHtml to old.contentText,ArticleSyncBody.rebuildBody(old,partial))
        }
    }
    @Test fun legacyMetadataOnlyRequiresActualCurrentPlaintextNotJustPersistedHash() {
        val original=row("<p>body</p>","body")
        val wire=ArticleSyncBody.payloadForRequest(original,ArticleBodyRequest(original.articleId,"",emptyList())).chunks.single().bytes.copyOf().apply{this[9]=0}
        val hash=CloudSnapshotCodec.sha256(wire)
        val old=original.copy(syncBodyHash=hash,syncBodyByteCount=wire.size.toLong(),syncChunkSize=ArticleSyncBody.CHUNK_SIZE_BYTES,
            syncChunkHashesJson=org.json.JSONArray().put(hash).toString(),syncMetadataHash=ArticleSyncBody.metadataHashFor(original))
        val request=ArticleBodyRequest(old.articleId,hash,emptyList(),metadataOnly=true)
        val unchanged=ArticleSyncBody.payloadForRequest(old,request)
        assertTrue(unchanged.metadataOnly);assertEquals(hash,unchanged.bodyHash);assertTrue(unchanged.chunks.isEmpty())
        val changed=ArticleSyncBody.payloadForRequest(old.copy(contentText="changed"),request)
        assertFalse(changed.metadataOnly);assertTrue(changed.chunks.isNotEmpty());assertNotEquals(hash,changed.bodyHash)
    }
}
