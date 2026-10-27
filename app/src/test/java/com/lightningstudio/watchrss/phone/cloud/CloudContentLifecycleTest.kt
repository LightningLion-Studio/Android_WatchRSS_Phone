package com.lightningstudio.watchrss.phone.cloud

import com.lightningstudio.watchrss.phone.account.*
import com.lightningstudio.watchrss.phone.testing.MemoryPreferences
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.*
import org.junit.Assert.*
import org.junit.Test

class CloudContentLifecycleTest {
    private val session = PhoneAccountSession("owner", "***", "session", "", Long.MAX_VALUE)
    private val vector = JSONObject(requireNotNull(javaClass.getResource("/cloud/content-lifecycle-r45.json")).readText())
    private val before = vector.getString("generationBefore")
    private val after = vector.getString("generationAfter")
    private fun client(withGeneration:Boolean=true, handler: (Request) -> Pair<Int, JSONObject>) = PhoneCloudClient(AccountEnvironment(backendBaseUrl="https://test.invalid"), http=OkHttpClient.Builder().addInterceptor {
        val (status, body)=handler(it.request())
        Response.Builder().request(it.request()).code(status).message("fixture").protocol(Protocol.HTTP_1_1).body(body.toString().toResponseBody("application/json".toMediaType())).build()
    }.build()).also { it.useEpoch(session, vector.getString("epoch"));if(withGeneration)it.useContentGeneration(session,before) }
    private fun bootstrap(generation: String=after, empty: Boolean=true) = JSONObject().put("member",JSONObject().put("usedBytes",17).put("reservedBytes",23).put("quotaBytes",100))
        .put("devices",JSONArray()).put("keyEnvelopes",JSONArray()).put("libraryEpoch",vector.getString("epoch"))
        .put("contentGeneration",generation).put("contentClearedAt","2026-10-23T00:00:00Z").put("contentEmpty",empty)

    @Test fun floorUpgradeHaltPersistsButDoesNotCrossOwnerEpochGenerationOrNewBuild() {
        val prefs=MemoryPreferences();val state=PhoneCloudStateStore(prefs)
        state.bindScope("https://test.invalid","owner");state.libraryEpoch=vector.getString("epoch")
        state.observeContentGeneration(before,false){}
        state.formatUpgradeRequiredVersion=94;assertEquals(94,state.formatUpgradeRequiredVersion)
        val restarted=PhoneCloudStateStore(prefs);restarted.bindScope("https://test.invalid","owner")
        assertEquals(94,restarted.formatUpgradeRequiredVersion)
        assertFalse(restarted.formatUpgradeRequiredVersion>=95)
        restarted.bindScope("https://test.invalid","another-owner");assertEquals(0,restarted.formatUpgradeRequiredVersion)
        restarted.bindScope("https://test.invalid","owner");restarted.observeContentGeneration(after,false){}
        assertEquals(0,restarted.formatUpgradeRequiredVersion)
        restarted.formatUpgradeRequiredVersion=94
        restarted.libraryEpoch="aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";assertEquals(0,restarted.formatUpgradeRequiredVersion)
    }
    @Test fun firstEmptyAndChangedGenerationResetExactlyOnceWithCrashSafePersistenceAndScopeIsolation() {
        val prefs=MemoryPreferences(); val state=PhoneCloudStateStore(prefs); state.bindScope("a/","owner")
        state.libraryEpoch=vector.getString("epoch");state.ownerUserId="owner";state.markApplied("watch",9,true)
        state.markUploaded("old",mapOf("watch" to "old-head"),true);state.lastSnapshotPublishedAt=99;state.nextSequence(40)
        state.quarantineKey(2,"key-warning")
        var clears=0
        fun reset() { clears++;state.clearLibraryState(preserveDeviceSequence=true) }
        assertTrue(state.observeContentGeneration(before,true,::reset)); assertEquals(0,state.appliedSequence("watch",true));assertNull(state.lastContentHash(true));assertNull(state.lastParentHeads());assertEquals(0,state.lastSnapshotPublishedAt)
        assertEquals(42,state.nextSequence(0));assertEquals("owner",state.ownerUserId);assertEquals(vector.getString("epoch"),state.libraryEpoch);assertEquals(setOf(2),state.quarantinedKeyVersions())
        assertFalse(state.observeContentGeneration(before,true,::reset));assertEquals(1,clears)
        try { state.observeContentGeneration(after,true) { error("cache cleanup interrupted") };fail() } catch (_:IllegalStateException) {}
        assertEquals(before,state.contentGeneration)
        assertTrue(state.observeContentGeneration(after,false,::reset));assertEquals(2,clears)
        val reopened=PhoneCloudStateStore(prefs);reopened.bindScope("a","owner");assertEquals(after,reopened.contentGeneration)
        reopened.bindScope("b","owner");assertNull(reopened.contentGeneration)
        reopened.bindScope("a","other");assertNull(reopened.contentGeneration)
    }
    @Test fun firstNonemptyGenerationDoesNotDiscardExistingProgress() {
        val state=PhoneCloudStateStore(MemoryPreferences());state.bindScope("a","owner");state.markApplied("watch",9,true)
        assertFalse(state.observeContentGeneration(after,false) { fail("nonempty adoption") });assertEquals(9,state.appliedSequence("watch",true))
    }
    @Test fun unknownGenerationRefusesContentWritesLocallyWhileIdentityRemainsUsable()=runBlocking {
        var calls=0
        val c=client(withGeneration=false){calls++;200 to JSONObject()}
        try{c.postRecords(session,JSONArray());fail("unfenced write")}catch(e:CloudHttpException){assertEquals(426,e.status)}
        assertEquals(0,calls)
        c.registerDevice(session,"phone","Phone","fixture-public",1)
        assertEquals(1,calls)
    }
    @Test fun bootstrapRecordsAndHeadsAllObserveContentMetadataAndQuotaIsNotHistorySum()=runBlocking {
        val observed=mutableListOf<String>()
        val c=client { request -> 200 to if(request.url.encodedPath.endsWith("bootstrap")) bootstrap() else
            JSONObject().put("contentGeneration",before).put("contentEmpty",false).put("heads",JSONArray()).put("items",JSONArray()) }
        c.onContentMetadata={_,metadata->observed+=metadata.generation}
        val value=c.bootstrap(session);assertEquals(after,value.contentGeneration);assertEquals("2026-10-23T00:00:00Z",value.contentClearedAt);assertTrue(value.contentEmpty)
        assertEquals(60,value.member.availableBytes);assertEquals(17,value.member.usedBytes);assertEquals(23,value.member.reservedBytes)
        c.recordPage(session,"0");c.snapshotHeads(session)
        assertEquals(listOf(after,before,before),observed)
    }
    @Test fun allContentWritesCarryGenerationButIdentityAndKeysDoNotRequireIt()=runBlocking {
        val paths=listOf("/cloud/records","/cloud/records/consent","/cloud/snapshots/reserve","/cloud/uploads/x/refresh","/cloud/snapshots/complete","/cloud/snapshots/id/delete","/cloud/snapshots/id/ack","/cloud/missing-chunks")
        val seen=mutableListOf<String>()
        val c=client { request ->
            seen+=request.url.encodedPath
            assertEquals(after,request.header("x-watchrss-content-generation"));200 to JSONObject()
        };c.useContentGeneration(session,after)
        paths.forEach { c.v2Post(session,"/functions/v1$it",JSONObject()) }
        assertEquals(paths.size,seen.size)
        assertFalse(isCloudContentWrite("POST","/functions/v1/cloud/keys/rotate"));assertFalse(isCloudContentWrite("POST","/functions/v1/cloud/devices/register"));assertFalse(isCloudContentWrite("POST","/functions/v1/cloud/approvals/v2"));assertFalse(isCloudContentWrite("GET","/functions/v1/cloud/snapshots/heads"))
    }
    @Test fun concurrentlyRefreshedBootstrapCannotRetagAnOldPassOrItsPut()=runBlocking {
        val seen=mutableListOf<String?>();val bytes="cipher".toByteArray();val id="11111111-1111-4111-8111-111111111111"
        val c=client { request ->seen+=request.header("x-watchrss-content-generation");200 to JSONObject() }
        c.useContentGeneration(session,before)
        c.inContentGeneration(session) {
            c.useContentGeneration(session,after)
            c.postRecords(session,JSONArray())
            c.uploadCiphertext(session,CloudUploadObject("chunk",CloudSnapshotCodec.sha256(bytes),bytes.size.toLong(),"p","https://test.invalid/functions/v1/cloud/uploads/$id",reservationId=id,transport="epoch-proxy"),bytes,vector.getString("epoch"))
        }
        assertEquals(listOf(before,before),seen)
        c.postRecords(session,JSONArray());assertEquals(after,seen.last())
    }
    @Test fun lateForegroundMetadataCannotRollBackNewGenerationOrClearItsCachesAgain()=runBlocking {
        lateinit var c:PhoneCloudClient
        var resets=0
        c=client { c.useContentGeneration(session,after);200 to bootstrap(before,false) }
        c.useContentGeneration(session,before);c.onContentMetadata={_,_->resets++}
        try{c.bootstrap(session);fail("stale metadata accepted")}catch(e:CloudHttpException){assertTrue(discardedCloudTransfer(e))}
        assertEquals(after,c.knownContentGeneration(session));assertEquals(0,resets)
    }
    @Test fun generationChangeOnHeadsStopsOldPassBeforeItCanApplyOrPublish()=runBlocking {
        var applied=false
        val c=client { 200 to JSONObject().put("contentGeneration",after).put("contentEmpty",true).put("heads",JSONArray()) };c.useContentGeneration(session,before)
        try { c.inContentGeneration(session) { c.snapshotHeads(session);applied=true };fail() } catch(e:CloudHttpException){assertTrue(discardedCloudTransfer(e))}
        assertFalse(applied);assertEquals(after,c.knownContentGeneration(session))
    }
    @Test fun rebuildAndRemovedPlansAreBoundedWhileUpgradeAuthEpochAndOrdinary404NeverReset()=runBlocking {
        for ((status,code) in listOf(409 to "cloud_content_rebuild_required",404 to "snapshot_not_found",404 to "upload_reservation_not_found",400 to "reservation_expired")) {
            var attempts=0;var resets=0
            val result=retryDiscardedCloudTransfer({resets++}) { if(++attempts==1)throw CloudHttpException(status,"{\"error\":\"$code\"}");"done" }
            assertEquals("done",result);assertEquals(2,attempts);assertEquals(1,resets)
            assertFalse(recoverableCloudRestoreFailure(CloudHttpException(status,"{\"error\":\"$code\"}")))
            attempts=0;resets=0
            try { retryDiscardedCloudTransfer({resets++}) {attempts++;throw CloudHttpException(status,"{\"error\":\"$code\"}") };fail() } catch (_:CloudHttpException){}
            assertEquals(2,attempts);assertEquals(1,resets)
        }
        for((status,code) in listOf(426 to "client_upgrade_required",426 to "snapshot_content_layout_unsupported",401 to "invalid_token",409 to "library_epoch_mismatch",404 to "object_not_found")) {
            var attempts=0
            try { retryDiscardedCloudTransfer({fail("identity invalidation")}) {attempts++;throw CloudHttpException(status,"{\"error\":\"$code\"}")};fail() }catch(_:CloudHttpException){}
            assertEquals(1,attempts)
        }
        try { retryDiscardedCloudTransfer({fail()}) {throw CancellationException("cancel")};fail() } catch(_:CancellationException){}
    }
    @Test fun sharedAdmissionVectorsAreHandledWithoutOldFormatOrSignedUrlFallback()=runBlocking {
        for(name in listOf("layoutCases","generationCases")) {
            val rows=vector.getJSONArray(name)
            for(i in 0 until rows.length()) {
                val row=rows.getJSONObject(i);if(row.getInt("status")==200)continue
                val c=client { row.getInt("status") to JSONObject().put("error",row.getString("error")) }
                var calls=0
                try { retryDiscardedCloudTransfer({}) {calls++;c.postRecords(session,JSONArray())};fail() }catch(e:CloudHttpException){assertEquals(row.getInt("status"),e.status)}
                assertEquals(if(row.getInt("status")==409)2 else 1,calls)
            }
        }
    }
    @Test fun returnedPassNeverLeavesDownloadingWhenNewChangesNeedARealFollowUp() {
        assertEquals(CloudSyncPhase.IDLE,cloudPassCompletion(true).first)
        assertEquals(CloudSyncPhase.COMPLETE,cloudPassCompletion(false).first)
        val secret="books/private-book-name.jsonl";assertFalse(safeCloudTrace("object-start:$secret").contains(secret));assertEquals("records-pull:start",safeCloudTrace("records-pull:start"))
    }
}
