package com.lightningstudio.watchrss.phone.cloud

import com.lightningstudio.watchrss.phone.account.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class CloudV2ClientAndStorageTest {
    private val session=PhoneAccountSession("owner","***","token","",Long.MAX_VALUE)
    @Test fun mutationEpochHeaderIsScopedAndMismatchClearsItBeforeAnyRetry()=runBlocking {
        var mismatch=0;var calls=0
        val c=PhoneCloudClient(AccountEnvironment(backendBaseUrl="https://test.invalid"),http=OkHttpClient.Builder().addInterceptor { chain ->
            calls++;assertEquals("epoch-a",chain.request().header("x-watchrss-library-epoch"))
            assertEquals("phone",chain.request().header("x-watchrss-cloud-device-id"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(409).message("test")
                .body("{\"error\":\"library_epoch_mismatch\"}".toResponseBody("application/json".toMediaType())).build()
        }.build())
        c.useEpoch(session,"epoch-a");c.currentDeviceId="phone";c.onEpochMismatch={mismatch++}
        try{c.revokeDevice(session,"other");fail("mismatch swallowed")}catch(e:CloudHttpException){assertEquals(409,e.status)}
        assertEquals(1,mismatch);assertEquals(1,calls)
        try{c.epoch(session);fail("stale epoch retained")}catch(_:IllegalStateException){}
        assertEquals(c.scopeId(session),c.scopeId(session.copy(accessToken="refreshed")))
        assertNotEquals(c.scopeId(session),c.scopeId(session.copy(userId="different")))
    }
    @Test fun commitmentsUseAtomicBatchAnd426RequiresUpgrade()=runBlocking {
        var inspected=false
        val c=PhoneCloudClient(AccountEnvironment(backendBaseUrl="https://test.invalid"),http=OkHttpClient.Builder().addInterceptor { chain ->
            val buffer=okio.Buffer();chain.request().body!!.writeTo(buffer);val body=JSONObject(buffer.readUtf8())
            assertEquals("device",body.getString("deviceId"));assertEquals(2,body.getJSONArray("commitments").length());inspected=true
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(426).message("upgrade")
                .body("{\"error\":\"client_upgrade_required\"}".toResponseBody("application/json".toMediaType())).build()
        }.build())
        c.useEpoch(session,"epoch")
        try{c.storeCommitments(session,"device",listOf(CloudKeyCommitment(1,"a"),CloudKeyCommitment(2,"b","proof")));fail("426 swallowed")}
        catch(e:CloudHttpException){assertTrue(e.message!!.contains("升级"))}
        assertTrue(inspected)
    }
    @Test fun cloudSecretsAndCursorDatabaseAreExcludedFromBackupAndTransfer() {
        val paths=setOf("watchrss_cloud_keys.xml","watchrss_cloud_state.xml","cloud-records-v1.db","cloud-records-v1.db-wal","cloud-records-v1.db-shm")
        fun assertExcludes(file:File,section:String) {
            val doc=DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val root=doc.getElementsByTagName(section).item(0) as org.w3c.dom.Element
            val exclusions=root.getElementsByTagName("exclude")
            val seen=(0 until exclusions.length).map{(exclusions.item(it) as org.w3c.dom.Element).getAttribute("path")}.toSet()
            assertTrue("$section missing exclusions",seen.containsAll(paths))
        }
        assertExcludes(File("src/main/res/xml/backup_rules.xml"),"full-backup-content")
        assertExcludes(File("src/main/res/xml/data_extraction_rules.xml"),"cloud-backup")
        assertExcludes(File("src/main/res/xml/data_extraction_rules.xml"),"device-transfer")
    }
    @Test fun manifestMustMatchEveryHeadIdentityFieldAndReaderProjectionCannotApplyLibraryObjects() {
        val manifest=CloudSnapshotCodec().create(ByteArray(32),1,"phone",3,emptyList()).manifest
        val head=CloudSnapshotHead(manifest.snapshotId,"phone",3,1,"hash",0,emptyMap(),emptyMap())
        validateCloudManifestHead(manifest,head)
        listOf(head.copy(id="wrong"),head.copy(sourceDeviceId="other"),head.copy(deviceSequence=4),head.copy(keyVersion=2)).forEach {
            try{validateCloudManifestHead(manifest,it);fail("mismatched manifest")}catch(_:IllegalArgumentException){}
        }
        val projection=manifest.copy(sourceDeviceId="reader-migration-job",objects=listOf(CloudObjectDescriptor("rss-state.json","identity",0,0,emptyList())))
        try{validateCloudManifestHead(projection,head.copy(sourceDeviceId=projection.sourceDeviceId));fail("projection injection")}
        catch(_:IllegalArgumentException){}
    }
    @Test fun recoveryInputOptsOutOfImeLearningWithoutDroppingExistingOptions() {
        val options = 0x00000006
        val protected = recoveryImeOptions(options)
        assertEquals(options, protected and options)
        assertTrue(protected and 0x01000000 != 0)
    }
    @Test fun recoveryWordErrorsNeverIncludeUserInput() {
        val secret="private-entered-word"
        try{RecoveryWords.decode(List(24){secret});fail("invalid words")}
        catch(e:IllegalStateException){assertFalse(e.message!!.contains(secret))}
    }
}
