package com.lightningstudio.watchrss.phone.cloud

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class CloudSyncRecoveryTest {
    private fun head(id: String, device: String, sequence: Long) = CloudSnapshotHead(id,device,sequence,1,"0".repeat(64),1,emptyMap(),emptyMap())

    @Test fun absentBodyBecomesRetryableWarningWithoutCatchingProgrammingOrOwnerErrors() = runBlocking<Unit> {
        val missing=restoreCloudBodyOrWarn("pending-import",{}) { throw MissingCloudBodyException() }
        assertEquals("pending-import",missing!!.articleId); assertEquals("missing_body",missing.reason)
        assertNull(restoreCloudBodyOrWarn("ready",{}) {})
        for (error in listOf(IllegalStateException("control flow"),CancellationException("cancelled"),CloudTrustException(),CloudHttpException(409,"library_epoch_mismatch"))) {
            try { restoreCloudBodyOrWarn("id",{}) { throw error }; fail("Swallowed ${error.javaClass}") }
            catch (actual: Exception) { assertSame(error,actual) }
        }
        val changed=IllegalStateException("account changed")
        try { restoreCloudBodyOrWarn("id",{throw changed}) { throw MissingCloudBodyException() }; fail() }
        catch (actual: Exception) { assertSame(changed,actual) }
    }

    @Test fun warnedHeadDoesNotGateLocalPublishingOrFalselyClaimRemoteHeadApplied() {
        val broken=head("remote-v2","remote",2); val healthy=head("healthy-v3","healthy",3)
        val progress=CloudSnapshotMergeProgress();progress.pendingWarnings(broken)
        val applied=mapOf("remote" to 1L,"healthy" to 3L)
        assertTrue(progress.readyToPublish(listOf(broken,healthy)) { applied[it] ?: 0 })
        assertEquals(listOf(healthy),progress.appliedHeads(listOf(broken,healthy)) { applied[it] ?: 0 })
        assertFalse(progress.readyToPublish(listOf(broken.copy(id="remote-v3",deviceSequence=3),healthy)) { applied[it] ?: 0 })
        // A new cycle must retry the old missing body, not persist the exemption as markApplied/ACK.
        assertFalse(CloudSnapshotMergeProgress().readyToPublish(listOf(broken,healthy)) { applied[it] ?: 0 })
        assertTrue(progress.readyToPublish(listOf(broken,healthy)) { 3 })
        assertEquals(listOf(broken,healthy),progress.appliedHeads(listOf(broken,healthy)) { 3 })
    }

    @Test fun recordsReadOutageIsRecoverableButCancellationAuthenticationAndEpochAreNot() = runBlocking<Unit> {
        for (error in listOf(IOException("offline"),CloudRecordsUnavailableException(),CloudHttpException(503,"unavailable"))) {
            var checks=0
            assertFalse(readCloudRecords({checks++}) { throw error });assertEquals(1,checks)
        }
        assertTrue(readCloudRecords({}) {})
        for (error in listOf(CancellationException("cancelled"),CloudTrustException(),CloudHttpException(401,"expired"),CloudHttpException(409,"library_epoch_mismatch"),CloudHttpException(426,"upgrade"),IllegalStateException("control"))) {
            try { readCloudRecords({}) { throw error };fail() }
            catch (actual: Exception) { assertSame(error,actual) }
        }
    }
    @Test fun permanentWarningsArePersistedOnceAndAllowAppliedWhileNewHeadsRetry() {
        val prefs=com.lightningstudio.watchrss.phone.testing.MemoryPreferences()
        fun store()=PhoneCloudStateStore(prefs).also { it.bindScope("https://backend.test","owner");it.libraryEpoch="epoch" }
        val first=store()
        val broken=com.lightningstudio.watchrss.phone.data.backup.CloudRestoreWarning("broken","missing_metadata",true)
        val settled=settleCloudRestoreWarnings(first,"head-1",listOf(broken),{})
        assertEquals(listOf(broken),settled.visible);assertTrue(settled.pending.isEmpty())
        if (settled.pending.isEmpty()) first.markApplied("remote",1,true)
        val restarted=store()
        assertEquals(1L,restarted.appliedSequence("remote",true))
        assertEquals(setOf("broken"),restarted.abandonedRestoreIds("head-1"))
        assertTrue(settleCloudRestoreWarnings(restarted,"head-1",listOf(broken),{}).visible.isEmpty())
        assertEquals(listOf(broken),settleCloudRestoreWarnings(restarted,"head-2",listOf(broken),{}).visible)
        assertTrue(restarted.abandonedRestoreIds("repaired-head-3").isEmpty())
    }

    @Test fun mixedTransientWarningsKeepRetryingButPermanentWarningsDoNotRepeat() {
        val state=PhoneCloudStateStore(com.lightningstudio.watchrss.phone.testing.MemoryPreferences())
        state.bindScope("https://backend.test","a");state.libraryEpoch="epoch"
        val broken=com.lightningstudio.watchrss.phone.data.backup.CloudRestoreWarning("broken","missing_body",true)
        val transient=com.lightningstudio.watchrss.phone.data.backup.CloudRestoreWarning("late-record-body","missing_body")
        val first=settleCloudRestoreWarnings(state,"head",listOf(broken,transient),{})
        assertEquals(listOf(transient),first.pending);assertEquals(2,first.visible.size)
        val next=settleCloudRestoreWarnings(state,"head",listOf(broken,transient),{})
        assertEquals(listOf(transient),next.pending);assertEquals(listOf(transient),next.visible)
        assertFalse(permanentCloudRestoreFailure(IOException("offline")))
        assertFalse(permanentCloudRestoreFailure(CloudHttpException(503,"retry")))
        assertTrue(permanentCloudRestoreFailure(CloudHttpException(404,"chunk missing")))
        assertFalse(permanentCloudRestoreFailure(IllegalArgumentException("missing_cloud_chunk")))
    }

    @Test fun abandonmentIsAccountBackendEpochScopedAndCannotCrossOwnerFence() {
        val state=PhoneCloudStateStore(com.lightningstudio.watchrss.phone.testing.MemoryPreferences())
        val bad=com.lightningstudio.watchrss.phone.data.backup.CloudRestoreWarning("bad","invalid_metadata",true)
        state.bindScope("a","owner");state.libraryEpoch="one"
        settleCloudRestoreWarnings(state,"head",listOf(bad),{})
        state.libraryEpoch="two";assertTrue(state.abandonedRestoreIds("head").isEmpty())
        state.libraryEpoch="one";assertEquals(setOf("bad"),state.abandonedRestoreIds("head"))
        state.bindScope("b","owner");state.libraryEpoch="one";assertTrue(state.abandonedRestoreIds("head").isEmpty())
        state.bindScope("a","other");state.libraryEpoch="one";assertTrue(state.abandonedRestoreIds("head").isEmpty())
        try { settleCloudRestoreWarnings(state,"head",listOf(bad),{throw CloudTrustException()});fail() } catch (_:CloudTrustException) { }
        assertTrue(state.abandonedRestoreIds("head").isEmpty())
        state.bindScope("a","owner");state.libraryEpoch="one";state.clearLibraryState()
        assertTrue(state.abandonedRestoreIds("head").isEmpty())
    }
}
