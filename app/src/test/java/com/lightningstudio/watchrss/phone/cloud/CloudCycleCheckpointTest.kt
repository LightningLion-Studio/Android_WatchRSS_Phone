package com.lightningstudio.watchrss.phone.cloud

import com.lightningstudio.watchrss.phone.testing.MemoryPreferences
import org.junit.Assert.*
import org.junit.Test

class CloudCycleCheckpointTest {
    private val receipt=CloudCycleCheckpoint("schema/owner/epoch/generation",mapOf("head" to "sha"),23,"40",100)
    private fun head(id:String="head",sha:String="sha")=CloudSnapshotHead(id,"watch",7,1,sha,10,emptyMap(),emptyMap())

    @Test fun persistedCommitIncludesDigestAndCursorAndCounterWithoutLibraryHashing() {
        assertEquals(receipt,CloudCycleCheckpoint.decode(receipt.encode()))
        assertEquals(CloudCyclePlan.IDLE,receipt.plan(listOf(head()),23,true))
        assertEquals(CloudCyclePlan.PUBLISH,receipt.plan(listOf(head()),24,true))
        assertEquals(CloudCyclePlan.APPLY,receipt.plan(listOf(head(sha="new")),23,true))
        assertEquals(CloudCyclePlan.APPLY,receipt.plan(listOf(head("new")),23,true))
        assertEquals(CloudCyclePlan.APPLY,receipt.plan(emptyList(),23,true))
        assertEquals(CloudCyclePlan.APPLY,receipt.plan(listOf(head()),23,false))
        assertEquals(CloudCyclePlan.FULL,receipt.plan(listOf(head("new")),24,true))
    }
    @Test fun schemaCursorAndDailyBoundaryRequireFullRevalidation() {
        assertTrue(receipt.compatible(receipt.context,"40",101))
        assertFalse(receipt.compatible("next-schema","40",101))
        assertFalse(receipt.compatible(receipt.context,"0",101))
        assertFalse(receipt.compatible(receipt.context,"40",99))
        assertFalse(receipt.compatible(receipt.context,"40",86_400_100))
        assertNull(CloudCycleCheckpoint.decode("{}"))
        assertNull(CloudCycleCheckpoint.decode(receipt.encode().replace("\"version\":2","\"version\":3")))
    }
    @Test fun failureToPersistNeverLeavesAnEligibleReceiptEvenInTheSameProcess() {
        val prefs=MemoryPreferences();val state=PhoneCloudStateStore(prefs)
        state.bindScope("backend","owner");state.completeCycle(receipt,state.admitCycle())
        state.invalidateCycleCheckpoint()
        assertNull(PhoneCloudStateStore(prefs).also{it.bindScope("backend","owner")}.cycleCheckpoint())
        val admission=state.admitCycle()
        prefs.failCommit=true
        assertTrue(runCatching{state.completeCycle(receipt.copy(localMaxSeq=24),admission)}.isFailure)
        assertNull(state.cycleCheckpoint())
        prefs.failCommit=false;state.completeCycle(receipt,state.admitCycle())
        prefs.failCommit=true
        assertTrue(runCatching{state.invalidateCycleCheckpoint()}.isFailure)
        assertNull(state.cycleCheckpoint())
        prefs.failCommit=false;state.invalidateCycleCheckpoint()
        assertNull(state.cycleCheckpoint())
    }
    @Test fun receiptsCannotCrossAccountsBackendsEpochResetOrLibraryClear() {
        val state=PhoneCloudStateStore(MemoryPreferences());state.bindScope("a","owner");state.completeCycle(receipt,state.admitCycle())
        state.bindScope("b","owner");assertNull(state.cycleCheckpoint())
        state.bindScope("a","other");assertNull(state.cycleCheckpoint())
        state.bindScope("a","owner");assertEquals(receipt,state.cycleCheckpoint())
        state.clearLibraryState();assertNull(state.cycleCheckpoint())
    }
    @Test fun anyInvalidationAfterAdmissionRejectsCompletionAndSurvivesReopenAndClear() {
        val prefs=MemoryPreferences();val state=PhoneCloudStateStore(prefs)
        state.bindScope("backend","owner")
        val admission=state.admitCycle()
        state.invalidateCycleCheckpoint() // arbitrary future source, no inventory preference involved
        assertFalse(state.completeCycle(receipt,admission));assertNull(state.cycleCheckpoint())
        val reopened=PhoneCloudStateStore(prefs).also{it.bindScope("backend","owner")}
        assertFalse(reopened.completeCycle(receipt,admission))
        val beforeClear=state.admitCycle();state.clearScope()
        assertFalse(state.completeCycle(receipt,beforeClear))
        assertTrue(state.completeCycle(receipt,state.admitCycle()))
    }

}
