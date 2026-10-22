package com.lightningstudio.watchrss.phone.cloud

import com.lightningstudio.watchrss.phone.account.AccountEnvironment
import com.lightningstudio.watchrss.phone.account.PhoneAccountSession
import com.lightningstudio.watchrss.phone.data.backup.CloudRestoreWarning
import com.lightningstudio.watchrss.phone.testing.MemoryPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.nio.file.Files

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CloudDownloadRecoveryTest {
    private val key=ByteArray(32) { it.toByte() }
    private val codec=CloudSnapshotCodec()
    private val url="https://objects.invalid/signed-private-url?token=synthetic-secret"
    private fun state()=PhoneCloudStateStore(MemoryPreferences()).also { it.bindScope("https://test.invalid","owner");it.libraryEpoch="epoch" }
    private fun client(pauses: MutableList<Long>, reply: (Request) -> Pair<Int,ByteArray>) = PhoneCloudClient(
        AccountEnvironment(backendBaseUrl="https://test.invalid"),
        http=OkHttpClient.Builder().addInterceptor { chain ->
            val (status,bytes)=reply(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("test").body(bytes.toResponseBody()).build()
        }.build(),downloadBackoff={ pauses += it })

    @Test fun truncatedOrSameLengthWrongHashDownloadsRetryThenRecover()=runBlocking<Unit> {
        val expected="authenticated ciphertext".toByteArray()
        for (bad in listOf("short".toByteArray(),ByteArray(expected.size) { '!'.code.toByte() })) {
            val pauses=mutableListOf<Long>();var calls=0
            val c=client(pauses) { 200 to if (++calls==1) bad else expected }
            assertArrayEquals(expected,c.download(url,expected.size.toLong(),CloudSnapshotCodec.sha256(expected)))
            assertEquals(2,calls);assertEquals(listOf(500L),pauses)
        }
    }

    @Test fun exhaustedIntegrityRetriesDoNotCacheAbandonOrAckAndNextCycleRestoresSameHead()=runBlocking<Unit> {
        val head="same-head";val objectName="private-library.wrss";val plain="Private original".toByteArray()
        val encrypted=codec.create(key,1,"source",1,listOf(CloudLogicalObject(objectName,plain,false)),snapshotId=head)
        val chunk=encrypted.manifest.allChunks.single();val good=encrypted.newCiphertextChunks.getValue(chunk.ciphertextSha256)
        val pauses=mutableListOf<Long>();var gets=0;var acks=0
        val c=client(pauses) { request ->
            if (request.method=="POST") { acks++;200 to "{}".toByteArray() }
            else 200 to if (++gets<=3) ByteArray(good.size) else good
        }
        val session=PhoneAccountSession("owner","***","session","",Long.MAX_VALUE);c.useEpoch(session,"epoch")
        val root=Files.createTempDirectory("cloud-download-retry-test").toFile()
        try {
            val cache=CloudLocalCache(root);val settings=state()
            val error=try {
                c.download(url,chunk.ciphertextBytes.toLong(),chunk.ciphertextSha256).also { cache.storeChunk("owner",chunk.ciphertextSha256,it) }
                throw AssertionError("must reject corrupt response")
            } catch (error: CloudDownloadIntegrityException) { error }
            assertEquals(3,gets);assertEquals(listOf(500L,1000L),pauses)
            val warning=cloudPrivateObjectWarning(head,objectName,error)
            val settled=settleCloudRestoreWarnings(settings,head,listOf(warning),{})
            if (settled.pending.isEmpty()) { settings.markApplied("source",1,true);c.acknowledge(session,head,"phone") }
            assertFalse(warning.permanent);assertEquals(1,settled.pending.size)
            assertTrue(settings.abandonedRestoreIds(head).isEmpty());assertEquals(0L,settings.appliedSequence("source",true));assertEquals(0,acks)
            assertNull(cache.loadChunk("owner",chunk.ciphertextSha256))
            val retried=c.download(url,chunk.ciphertextBytes.toLong(),chunk.ciphertextSha256)
            cache.storeChunk("owner",chunk.ciphertextSha256,retried)
            val restored=decodeCloudObject(head,objectName) { codec.restoreObjects(key,encrypted.manifest) { cache.loadChunk("owner",it)!! } }
            assertArrayEquals(plain,restored.getValue(objectName));assertEquals(4,gets)
            settings.markApplied("source",1,true);c.acknowledge(session,head,"phone")
            assertEquals(1,acks);assertEquals(1L,settings.appliedSequence("source",true))
        } finally { root.deleteRecursively() }
    }

    @Test fun transportBackoffIsBoundedAndDoesNotRetryTerminalHttpOrCancellation()=runTest {
        val attempts=mutableListOf<Long>()
        val value=retryCloudObjectDownload({ delay(it) }) {
            attempts += testScheduler.currentTime
            if (attempts.size<3) throw CloudDownloadIntegrityException()
            "ok"
        }
        assertEquals("ok",value);assertEquals(listOf(0L,500L,1500L),attempts)
        for (status in listOf(401,403,404,409,410,426)) {
            var calls=0;val pauses=mutableListOf<Long>()
            val c=client(pauses) { calls++;status to "{}".toByteArray() }
            try { c.download(url,1,"0".repeat(64));fail() } catch (e:CloudHttpException) { assertEquals(status,e.status) }
            assertEquals(1,calls);assertTrue(pauses.isEmpty())
        }
        var calls=0
        try {
            retryCloudObjectDownload({throw CancellationException("stop")}) { calls++;throw CloudDownloadIntegrityException() };fail()
        } catch (_:CancellationException) { }
        assertEquals(1,calls)
    }

    @Test fun persistentServerFailureRemainsRetryableAfterThreeAttempts()=runBlocking<Unit> {
        val pauses=mutableListOf<Long>();var calls=0
        val c=client(pauses) { calls++;503 to "{}".toByteArray() }
        val error=try { c.download(url,1,"0".repeat(64));throw AssertionError() } catch (e:CloudHttpException) { e }
        assertEquals(3,calls);assertEquals(listOf(500L,1000L),pauses)
        assertFalse(permanentCloudRestoreFailure(error))
        for (status in listOf(404,410)) assertTrue(permanentCloudRestoreFailure(CloudHttpException(status,"missing")))
    }

    @Test fun onlyDecodeParseAndNotFoundFailuresArePermanentAndDecryptDiagnosticIsRedacted() {
        val snapshot="private-snapshot-id";val name="reader/private-person-id/version.txt"
        val encrypted=codec.create(key,1,"source",1,listOf(CloudLogicalObject(name,"sensitive original".toByteArray())),snapshotId=snapshot)
        val error=try {
            decodeCloudObject(snapshot,name) { codec.restoreObjects(ByteArray(32) { 7 },encrypted.manifest) { encrypted.newCiphertextChunks.getValue(it) } }
            throw AssertionError("wrong key accepted")
        } catch (e:CloudObjectDecryptionException) { e }
        val id=cloudObjectDiagnosticId(snapshot,name)
        assertTrue(id.matches(Regex("[0-9a-f]{16}")));assertTrue(error.message!!.contains(id))
        for (secret in listOf(snapshot,name,"private-person-id","sensitive original","synthetic-secret")) assertFalse(error.message!!.contains(secret))
        val warning=cloudPrivateObjectWarning(snapshot,name,error)
        assertEquals("decryption_failed:$id",warning.reason);assertTrue(warning.permanent);assertEquals(id,warning.articleId)
        val settings=state()
        assertTrue(settleCloudRestoreWarnings(settings,snapshot,listOf(warning),{}).pending.isEmpty())
        assertTrue(settleCloudRestoreWarnings(settings,snapshot,listOf(warning),{}).visible.isEmpty())
        val parse=try { decodeCloudObject(snapshot,name) { JSONObject("not JSON") };throw AssertionError() } catch (e:CloudObjectParseException) { e }
        assertTrue(permanentCloudRestoreFailure(parse));assertTrue(permanentCloudRestoreFailure(javax.crypto.AEADBadTagException()))
        assertFalse(permanentCloudRestoreFailure(IllegalArgumentException("unclassified validation")))
        assertFalse(permanentCloudRestoreFailure(CloudDownloadIntegrityException()));assertFalse(permanentCloudRestoreFailure(IOException("offline")))
    }

    @Test fun corruptCachedCiphertextIsRetryableAndUnauthenticatedManifestFailsClosedWithRedactedId()=runBlocking<Unit> {
        val snapshot="manifest-snapshot";val name="private-library.wrss"
        val image=codec.create(key,1,"source",1,listOf(CloudLogicalObject(name,"body".toByteArray())),snapshotId=snapshot)
        val chunk=image.manifest.allChunks.single()
        val bytes=image.newCiphertextChunks.getValue(chunk.ciphertextSha256).copyOf().also { it[it.lastIndex]=(it.last().toInt() xor 1).toByte() }
        val integrity=try { decodeCloudObject(snapshot,name) { codec.restoreObjects(key,image.manifest) { bytes } };throw AssertionError() }
            catch (e:CloudDownloadIntegrityException) { e }
        assertFalse(permanentCloudRestoreFailure(integrity))
        val head=CloudSnapshotHead(snapshot,"source",1,1,CloudSnapshotCodec.sha256(image.encryptedManifest),image.encryptedManifest.size.toLong(),emptyMap(),emptyMap())
        val manifestIntegrity=try {
            decodeCloudObject(snapshot,"manifest") { CloudSnapshotReuse.authenticate(codec,key,head,image.encryptedManifest.copyOf(image.encryptedManifest.size-1)) };throw AssertionError()
        } catch (e:CloudDownloadIntegrityException) { e }
        assertFalse(permanentCloudRestoreFailure(manifestIntegrity))
        val settings=state()
        val error=try { decodeCloudObject(snapshot,"manifest") { codec.decryptManifest(ByteArray(32) { 7 },snapshot,image.encryptedManifest) };throw AssertionError() }
            catch (e:CloudObjectDecryptionException) { e }
        assertEquals(cloudObjectDiagnosticId(snapshot,"manifest"),error.objectId)
        val bodyWarning=restoreCloudBodyOrWarn("record-id",{}) { throw error }!!
        assertEquals("decryption_failed:${error.objectId}",bodyWarning.reason);assertFalse(bodyWarning.permanent)
        assertTrue(settings.abandonedRestoreIds(snapshot).isEmpty());assertEquals(0L,settings.appliedSequence("source",true))
    }

    @Test fun oldAmbiguousAbandonmentIsRevisitedOnceWithoutClearingValidRowDecisionsOrOtherScope() {
        val prefs=MemoryPreferences();val settings=PhoneCloudStateStore(prefs)
        settings.bindScope("https://test.invalid","owner");settings.libraryEpoch="epoch"
        settings.abandonRestore("head",CloudRestoreWarning("private-library.wrss","private_object_unavailable",true))
        settings.abandonRestore("head",CloudRestoreWarning("bad-row","missing_metadata",true))
        settings.markApplied("source",4,true);settings.markApplied("source",4,false)
        settings.ensureRestoreFailureClassificationV2()
        assertEquals(setOf("bad-row"),settings.abandonedRestoreIds("head"));assertEquals(0L,settings.appliedSequence("source",true));assertEquals(0L,settings.appliedSequence("source",false))
        settings.markApplied("source",5,true);settings.ensureRestoreFailureClassificationV2();assertEquals(5L,settings.appliedSequence("source",true))
        settings.bindScope("https://other.invalid","owner");settings.libraryEpoch="epoch"
        settings.markApplied("other",8,true);settings.ensureRestoreFailureClassificationV2();assertEquals(8L,settings.appliedSequence("other",true))
    }
}
